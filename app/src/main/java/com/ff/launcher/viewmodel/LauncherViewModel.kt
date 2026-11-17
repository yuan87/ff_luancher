package com.ff.launcher.viewmodel

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ff.launcher.LauncherApp
import com.ff.launcher.R
import com.ff.launcher.data.AppInfo
import com.ff.launcher.data.AppRepository
import com.ff.launcher.data.FavoritesRepository
import com.ff.launcher.data.HiddenAppUi
import com.ff.launcher.data.OnboardingRepository
import com.ff.launcher.data.QuickActionConfig
import com.ff.launcher.data.QuickActionPreferencesRepository
import com.ff.launcher.data.QuickActionType
import com.ff.launcher.data.TriggerConfig
import com.ff.launcher.data.TriggerEdgeSide
import com.ff.launcher.data.TriggerMode
import com.ff.launcher.data.TriggerPreferencesRepository
import com.ff.launcher.data.db.DEFAULT_WIDGET_SLOTS
import com.ff.launcher.data.db.HOME_WIDGET_SLOT_ID
import com.ff.launcher.data.db.HiddenAppEntity
import com.ff.launcher.data.db.LauncherDatabase
import com.ff.launcher.data.db.WidgetSlotEntity
import com.ff.launcher.data.db.normalizeSlotFraction
import com.ff.launcher.data.glance.CalendarProvider
import com.ff.launcher.data.glance.CalendarState
import com.ff.launcher.data.glance.HeaderConfig
import com.ff.launcher.data.glance.HeaderMode
import com.ff.launcher.data.glance.HeaderPreferencesRepository
import com.ff.launcher.data.glance.HeaderSize
import com.ff.launcher.data.glance.TimeData
import com.ff.launcher.data.glance.TimeProvider
import com.ff.launcher.data.glance.WeatherData
import com.ff.launcher.data.glance.WeatherRepository
import com.ff.launcher.data.notification.BadgePreferences
import com.ff.launcher.data.notification.BadgePreferencesRepository
import com.ff.launcher.data.notification.BadgeScope
import com.ff.launcher.notification.BadgeKey
import com.ff.launcher.notification.NotificationAccessHelper
import com.ff.launcher.notification.NotificationCard
import com.ff.launcher.util.Logger
import com.ff.launcher.widget.LauncherAppWidgetHostView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Widget binding requests routed to the Activity's registered launchers. */
sealed interface WidgetRequest {
    /**
     * `ACTION_APPWIDGET_BIND` — the system confirmation dialog shown when
     * [AppWidgetManager.bindAppWidgetIdIfAllowed] returns false.
     */
    data class BindPermission(val intent: Intent) : WidgetRequest

    /** [appWidgetId] is bound to the host; the configure page launches through it. */
    data class Configure(val appWidgetId: Int) : WidgetRequest
}

/**
 * Main coordinator ViewModel for the FF Launcher home screen.
 *
 * Decomposed into specialized domain managers:
 * - [widgetHostManager]: AppWidgetHost allocation, binding, configuration, and listening
 * - [triggerTuningManager]: Edge handle tuning, preview/commit/settle lifecycle
 * - [quickActionManager]: Floating quick action button & flashlight control
 * - [notificationPanelManager]: Notification badge filtering and inline expanded card panel
 * - [HomeStateBuilder]: Pure function building the locale-collated, Pinyin-rescued app list
 */
class LauncherViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val app = application as LauncherApp
    private val appRepository = app.appRepository
    private val database = app.database
    private val favoritesRepository = app.favoritesRepository
    private val triggerRepository = app.triggerRepository
    private val quickActionRepository = app.quickActionRepository
    private val onboardingRepository = app.onboardingRepository

    private val timeProvider = TimeProvider(application, viewModelScope)
    private val calendarProvider = CalendarProvider(application)
    private val weatherRepository = app.weatherRepository
    private val headerPreferencesRepository = app.headerRepository
    private val badgePreferencesRepository = app.badgePreferencesRepository

    private val userManager = application.getSystemService(Context.USER_SERVICE) as? UserManager
    private val appWidgetManager = AppWidgetManager.getInstance(application)

    private val _hostResumed = MutableStateFlow(false)

    // ----- Extracted Managers -----

    private val widgetHostManager = WidgetHostManager(
        application = application,
        scope = viewModelScope,
        database = database,
        savedStateHandle = savedStateHandle,
        hostResumedProvider = { _hostResumed.value },
    )

    private val triggerTuningManager = TriggerTuningManager(
        repository = triggerRepository,
        scope = viewModelScope,
    )

    /** One-off, fire-and-forget messages surfaced to the user as toasts. */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    private val quickActionManager = QuickActionManager(
        application = application,
        scope = viewModelScope,
        repository = quickActionRepository,
        appLauncher = ::launchApp,
        allInstalledApps = { allInstalledApps.value },
        userMessages = _userMessages,
    )

    private val notificationPanelManager = NotificationPanelManager(
        repository = badgePreferencesRepository,
        scope = viewModelScope,
    )

    // ----- Notification State & Preferences (delegated) -----

    val badgePreferences: StateFlow<BadgePreferences> = notificationPanelManager.badgePreferences
    val notificationKeys: StateFlow<Map<BadgeKey, Set<String>>> = notificationPanelManager.notificationKeys
    val expandedBadgeKey: StateFlow<BadgeKey?> = notificationPanelManager.expandedBadgeKey
    val expandedCards: StateFlow<List<NotificationCard>> = notificationPanelManager.expandedCards

    private val _hasNotificationAccess = MutableStateFlow(NotificationAccessHelper.hasAccess(application))
    val hasNotificationAccess: StateFlow<Boolean> = _hasNotificationAccess.asStateFlow()

    fun checkNotificationAccess() {
        _hasNotificationAccess.value = NotificationAccessHelper.hasAccess(getApplication())
    }

    fun appToBadgeKey(app: AppInfo): BadgeKey = app.badgeKey

    fun hasNotification(app: AppInfo, isPinned: Boolean): Boolean {
        return notificationPanelManager.hasNotification(
            key = appToBadgeKey(app),
            isPinned = isPinned,
            hasAccess = _hasNotificationAccess.value,
        )
    }

    fun toggleAppNotifications(app: AppInfo) =
        notificationPanelManager.toggleAppNotifications(appToBadgeKey(app))

    fun dismissNotificationCard(card: NotificationCard) =
        notificationPanelManager.dismissNotificationCard(card)

    fun clearAllNotificationCards(app: AppInfo) {
        val key = appToBadgeKey(app)
        notificationPanelManager.clearAllNotificationCards(app.packageName, key.userSerial)
    }

    fun openNotificationCard(card: NotificationCard) =
        notificationPanelManager.openNotificationCard(card)

    fun collapseNotifications() = notificationPanelManager.collapseNotifications()
    fun isAppMuted(packageName: String): Boolean = notificationPanelManager.isAppMuted(packageName)
    fun toggleMuteApp(packageName: String) = notificationPanelManager.toggleMuteApp(packageName)
    fun setBadgeEnabled(enabled: Boolean) = notificationPanelManager.setBadgeEnabled(enabled)
    fun setMarkAsReadOnDismiss(value: Boolean) = notificationPanelManager.setMarkAsReadOnDismiss(value)
    fun setHideMessageText(value: Boolean) = notificationPanelManager.setHideMessageText(value)
    fun setBadgeScope(scope: BadgeScope) = notificationPanelManager.setBadgeScope(scope)
    fun setIncludeSilent(value: Boolean) = notificationPanelManager.setIncludeSilent(value)
    fun resetBadgePreferences() = notificationPanelManager.resetBadgePreferences()

    // ----- Glance / Smart Header -----

    val timeState: StateFlow<TimeData> = timeProvider.timeState

    private val _calendarState = MutableStateFlow(CalendarState())
    val calendarState: StateFlow<CalendarState> = _calendarState.asStateFlow()

    val weatherState: StateFlow<WeatherData> = weatherRepository.cachedWeather
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeatherData())

    val headerConfig: StateFlow<HeaderConfig> = headerPreferencesRepository.headerConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeaderConfig())

    fun refreshCalendar() {
        viewModelScope.launch {
            _calendarState.value = calendarProvider.loadUpcomingEvents()
        }
    }

    fun hasLocationPermission(): Boolean = weatherRepository.hasLocationPermission()

    fun refreshWeather(force: Boolean = false) {
        viewModelScope.launch {
            weatherRepository.getOrFetchWeather(forceRefresh = force)
        }
    }

    fun setHeaderMode(mode: HeaderMode) {
        Logger.i(TAG) { "setHeaderMode: $mode" }
        viewModelScope.launch { headerPreferencesRepository.setHeaderMode(mode) }
    }

    fun setHeaderUseFahrenheit(useFahrenheit: Boolean) {
        Logger.i(TAG) { "setHeaderUseFahrenheit: $useFahrenheit" }
        viewModelScope.launch { headerPreferencesRepository.setUseFahrenheit(useFahrenheit) }
    }

    fun setHeaderSize(size: HeaderSize) {
        Logger.i(TAG) { "setHeaderSize: $size" }
        viewModelScope.launch { headerPreferencesRepository.setHeaderSize(size) }
    }

    // ----- Quick Action Button (delegated) -----

    val quickActionConfig: StateFlow<QuickActionConfig> = quickActionManager.quickActionConfig
    val quickActionDisplay: StateFlow<QuickActionConfig> = quickActionManager.quickActionDisplay
    val isFlashlightOn: StateFlow<Boolean> = quickActionManager.isFlashlightOn
    val isQuickActionPickerOpen: StateFlow<Boolean> = quickActionManager.isQuickActionPickerOpen

    fun openQuickActionPicker() = quickActionManager.openQuickActionPicker()
    fun closeQuickActionPicker() = quickActionManager.closeQuickActionPicker()
    fun setQuickActionEnabled(enabled: Boolean) = quickActionManager.setQuickActionEnabled(enabled)
    fun setQuickAction(type: QuickActionType, componentKey: String = "", label: String = "") =
        quickActionManager.setQuickAction(type, componentKey, label)
    fun setQuickActionEdgeSide(side: TriggerEdgeSide) = quickActionManager.setQuickActionEdgeSide(side)
    fun previewQuickActionVerticalBias(bias: Float) = quickActionManager.previewQuickActionVerticalBias(bias)
    fun previewQuickActionSizeDp(sizeDp: Int) = quickActionManager.previewQuickActionSizeDp(sizeDp)
    fun previewQuickActionOpacity(opacity: Float) = quickActionManager.previewQuickActionOpacity(opacity)
    fun setQuickActionVerticalBias(bias: Float) = quickActionManager.setQuickActionVerticalBias(bias)
    fun setQuickActionSizeDp(size: Int) = quickActionManager.setQuickActionSizeDp(size)
    fun setQuickActionOpacity(opacity: Float) = quickActionManager.setQuickActionOpacity(opacity)
    fun resetQuickActionToDefaults() = quickActionManager.resetQuickActionToDefaults()
    fun toggleFlashlight() = quickActionManager.toggleFlashlight()
    fun executeQuickAction() = quickActionManager.executeQuickAction(onOpenSecondScreen = { setSecondScreenOpen(true) })

    // ----- Trigger Tuning (delegated) -----

    val triggerConfig: StateFlow<TriggerConfig> = triggerTuningManager.triggerConfig
    val triggerDisplay: StateFlow<TriggerConfig> = triggerTuningManager.triggerDisplay

    fun previewTriggerVerticalBias(bias: Float) = triggerTuningManager.previewTriggerVerticalBias(bias)
    fun previewTriggerHeightDp(height: Int) = triggerTuningManager.previewTriggerHeightDp(height)
    fun previewTriggerWidthDp(width: Int) = triggerTuningManager.previewTriggerWidthDp(width)
    fun previewTriggerOpacity(opacity: Float) = triggerTuningManager.previewTriggerOpacity(opacity)
    fun setTriggerEdgeSide(side: TriggerEdgeSide) = triggerTuningManager.setTriggerEdgeSide(side)
    fun setTriggerVerticalBias(bias: Float) = triggerTuningManager.setTriggerVerticalBias(bias)
    fun setTriggerHeightDp(height: Int) = triggerTuningManager.setTriggerHeightDp(height)
    fun setTriggerWidthDp(width: Int) = triggerTuningManager.setTriggerWidthDp(width)
    fun setTriggerOpacity(opacity: Float) = triggerTuningManager.setTriggerOpacity(opacity)
    fun setTriggerMode(mode: TriggerMode) = triggerTuningManager.setTriggerMode(mode)

    fun setTriggerEnabled(enabled: Boolean) {
        Logger.i(TAG) { "setTriggerEnabled: enabled=$enabled" }
        if (!enabled && _isSecondScreenOpen.value) {
            setSecondScreenOpen(false)
        }
        triggerTuningManager.setTriggerEnabled(enabled)
    }

    fun setDeckWidgetCount(count: Int) {
        viewModelScope.launch {
            database.widgetSlotDao().ensureDefaults(DEFAULT_WIDGET_SLOTS)
            triggerTuningManager.setDeckWidgetCount(count)
        }
    }

    // ----- Screen & Sheet State -----

    private val _isSecondScreenOpen = MutableStateFlow(false)
    val isSecondScreenOpen: StateFlow<Boolean> = _isSecondScreenOpen.asStateFlow()

    private val _isTuningSheetOpen = MutableStateFlow(false)
    val isTuningSheetOpen: StateFlow<Boolean> = _isTuningSheetOpen.asStateFlow()

    private val _isArrangeFavoritesOpen = MutableStateFlow(false)
    val isArrangeFavoritesOpen: StateFlow<Boolean> = _isArrangeFavoritesOpen.asStateFlow()

    private val _selectedAppForManage = MutableStateFlow<AppInfo?>(null)
    val selectedAppForManage: StateFlow<AppInfo?> = _selectedAppForManage.asStateFlow()

    val allInstalledApps: StateFlow<List<AppInfo>> = appRepository.apps

    private val _pickingWidgetSlotId = MutableStateFlow<Int?>(null)
    val pickingWidgetSlotId: StateFlow<Int?> = _pickingWidgetSlotId.asStateFlow()

    private val _onboardingDoneOverride = MutableStateFlow<Boolean?>(null)
    val onboardingDone: StateFlow<Boolean?> = combine(
        onboardingRepository.isOnboardingDone,
        _onboardingDoneOverride,
    ) { persisted, override -> override ?: persisted }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _isDefaultLauncher = MutableStateFlow(false)
    val isDefaultLauncher: StateFlow<Boolean> = _isDefaultLauncher.asStateFlow()

    private val _isDeckActive: StateFlow<Boolean> =
        combine(_isSecondScreenOpen, _hostResumed) { open, resumed -> open && resumed }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val widgetRequests: Flow<WidgetRequest> = widgetHostManager.widgetRequests

    val recentApps: StateFlow<List<AppInfo>> = combine(
        database.recentAppDao().observeRecent(),
        appRepository.apps,
    ) { recents, apps ->
        val appMap = apps.associateBy { it.componentKey }
        recents.mapNotNull { appMap[it.componentKey] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val newlyInstalledApps: StateFlow<List<AppInfo>> = appRepository.apps.map { apps ->
        apps.filter { it.firstInstallTime > 0L }
            .sortedByDescending { it.firstInstallTime }
            .take(8)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isAppSearchOpen = MutableStateFlow(false)
    val isAppSearchOpen: StateFlow<Boolean> = _isAppSearchOpen.asStateFlow()

    private val _homeResetTrigger = MutableStateFlow(0L)
    val homeResetTrigger: StateFlow<Long> = _homeResetTrigger.asStateFlow()

    fun openAppSearch() { _isAppSearchOpen.value = true }
    fun closeAppSearch() { _isAppSearchOpen.value = false }

    val widgetSlots: StateFlow<List<WidgetSlotEntity>> =
        database.widgetSlotDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val homeWidgetSlot: StateFlow<WidgetSlotEntity?> = widgetSlots
        .map { slots -> slots.firstOrNull { it.slotId == HOME_WIDGET_SLOT_ID } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val hiddenKeysFlow: Flow<Set<String>> =
        database.hiddenAppDao().observeAll()
            .map { rows -> rows.mapTo(HashSet(rows.size)) { it.componentKey } }

    val hiddenApps: StateFlow<List<HiddenAppUi>> =
        combine(appRepository.apps, hiddenKeysFlow) { apps, hidden ->
            apps.filter { it.componentKey in hidden }
                .map { HiddenAppUi(it.componentKey, it.label) }
                .sortedBy { it.label.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteAppsList: StateFlow<List<AppInfo>> = combine(
        appRepository.apps,
        favoritesRepository.favoriteKeysOrdered,
        hiddenKeysFlow,
    ) { apps, orderedKeys, hiddenKeys ->
        val visibleByKey = apps.filter { it.componentKey !in hiddenKeys }.associateBy { it.componentKey }
        orderedKeys.mapNotNull { visibleByKey[it] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val homeState: StateFlow<HomeListState> =
        combine(
            appRepository.apps,
            favoritesRepository.favoriteKeysOrdered,
            hiddenKeysFlow,
            HomeStateBuilder::build,
        )
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, HomeListState())

    init {
        viewModelScope.launch {
            database.widgetSlotDao().ensureDefaults(DEFAULT_WIDGET_SLOTS)
            favoritesRepository.migrateFromLegacyIfNeeded()
        }
        viewModelScope.launch {
            val initialApps = appRepository.apps.first { it.isNotEmpty() }
            favoritesRepository.seedDefaultFavoritesIfNeeded(initialApps)
        }
        refreshDefaultLauncherState()
        refreshCalendar()
        refreshWeather()
        checkNotificationAccess()

        // Listen while the activity is resumed AND (secondary deck is open OR a home widget is bound).
        viewModelScope.launch {
            combine(_isDeckActive, _hostResumed, homeWidgetSlot) { isDeckActive, hostResumed, slot ->
                val hasHomeWidget = slot?.appWidgetId != null
                hostResumed && (isDeckActive || hasHomeWidget)
            }.collect { shouldListen ->
                Logger.d(TAG) { "widget listening state: shouldListen=$shouldListen" }
                if (shouldListen) {
                    widgetHostManager.ensureListening()
                } else {
                    widgetHostManager.stopListening()
                }
            }
        }

        // Prune favorites/hidden rows of uninstalled packages.
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.packageEvents.collect { event ->
                if (event is com.ff.launcher.data.PackageEvent.Removed && !event.replacing) {
                    favoritesRepository.removePackage(event.packageName, event.userSerial)
                    database.hiddenAppDao().deleteForPackage(event.packageName, event.userSerial.toString())
                    database.widgetSlotDao().getAll().forEach { slot ->
                        val id = slot.appWidgetId ?: return@forEach
                        if (appWidgetManager.getAppWidgetInfo(id) == null) {
                            database.widgetSlotDao().clearWidget(slot.slotId)
                            widgetHostManager.releaseAppWidgetIdAsync(id)
                        }
                    }
                }
            }
        }
    }

    fun setSecondScreenOpen(open: Boolean) {
        if (open) widgetHostManager.ensureListening()
        _isSecondScreenOpen.value = open
    }

    fun setTuningSheetOpen(open: Boolean) { _isTuningSheetOpen.value = open }
    fun setArrangeFavoritesOpen(open: Boolean) { _isArrangeFavoritesOpen.value = open }

    fun openManageFavorites(app: AppInfo? = null) {
        _selectedAppForManage.value = app
        _isArrangeFavoritesOpen.value = true
    }

    fun closeManageFavorites() {
        _selectedAppForManage.value = null
        _isArrangeFavoritesOpen.value = false
    }

    fun moveFavorite(componentKey: String, delta: Int) {
        Logger.i(TAG) { "moveFavorite: $componentKey, delta=$delta" }
        viewModelScope.launch { favoritesRepository.moveFavorite(componentKey, delta) }
    }

    fun updateFavoriteOrder(orderedKeys: List<String>) {
        Logger.i(TAG) { "updateFavoriteOrder: count=${orderedKeys.size}" }
        viewModelScope.launch { favoritesRepository.updateOrder(orderedKeys) }
    }

    fun setHostResumed(resumed: Boolean) {
        _hostResumed.value = resumed
    }

    fun refreshDefaultLauncherState() {
        viewModelScope.launch(Dispatchers.IO) {
            _isDefaultLauncher.value = onboardingRepository.isDefaultLauncher()
        }
    }

    fun completeOnboarding() {
        _onboardingDoneOverride.value = true
        viewModelScope.launch { onboardingRepository.setOnboardingDone() }
    }

    fun buildSetDefaultIntent(): Intent = onboardingRepository.createSetDefaultIntent()

    fun closeSecondary() {
        widgetHostManager.cancelPendingOperations()
        _pickingWidgetSlotId.value = null
        _isAppSearchOpen.value = false
        _isSecondScreenOpen.value = false
        _isTuningSheetOpen.value = false
        _selectedAppForManage.value = null
        _isArrangeFavoritesOpen.value = false
    }

    fun onHomeIntent() {
        closeSecondary()
        _homeResetTrigger.value = System.currentTimeMillis()
    }

    fun onHostStopped() {
        if (widgetHostManager.hasPendingOperation()) return
        closeSecondary()
    }

    fun launchApp(app: AppInfo) {
        Logger.i(TAG) { "launchApp: ${app.label} (${app.componentKey})" }
        viewModelScope.launch {
            runCatching {
                database.recentAppDao().recordLaunch(
                    com.ff.launcher.data.db.RecentAppEntity(
                        app.componentKey,
                        System.currentTimeMillis(),
                    ),
                )
            }
        }
        val component = parseComponent(app.componentKey) ?: return
        val context = getApplication<Application>()

        val launcherApps = context.getSystemService(LauncherApps::class.java)
        if (launcherApps != null) {
            val started = runCatching {
                launcherApps.startMainActivity(component, app.userHandle, null, null)
            }.onFailure { Logger.w(TAG, "startMainActivity failed for ${app.label}", it) }
                .isSuccess
            if (started) return
            if (app.userHandle != Process.myUserHandle()) {
                _userMessages.tryEmit(context.getString(R.string.error_work_profile))
                return
            }
        }

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }
            .onFailure {
                Logger.w(TAG, "startActivity failed for ${app.label}", it)
                _userMessages.tryEmit(context.getString(R.string.error_launch_failed))
            }
    }

    fun toggleFavorite(app: AppInfo) {
        Logger.i(TAG) { "toggleFavorite: ${app.label} (${app.componentKey})" }
        viewModelScope.launch { favoritesRepository.toggle(app.componentKey) }
    }

    fun hideApp(app: AppInfo) {
        viewModelScope.launch {
            database.hiddenAppDao().upsert(
                HiddenAppEntity(
                    componentKey = app.componentKey,
                    hiddenAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun unhideApp(componentKey: String) {
        viewModelScope.launch { database.hiddenAppDao().delete(componentKey) }
    }

    fun setSlotHeightWeight(slotId: Int, heightWeight: Float) {
        val safeFraction = normalizeSlotFraction(heightWeight)
        viewModelScope.launch {
            runCatching { database.widgetSlotDao().updateHeightWeight(slotId, safeFraction) }
                .onFailure { Logger.w(TAG, "updateHeightWeight failed", it) }
        }
    }

    fun openAppInfo(app: AppInfo) {
        val component = parseComponent(app.componentKey) ?: return
        val context = getApplication<Application>()

        val launcherApps = context.getSystemService(LauncherApps::class.java)
        if (launcherApps != null) {
            val opened = runCatching {
                launcherApps.startAppDetailsActivity(component, app.userHandle, null, null)
            }.onFailure { Logger.w(TAG, "startAppDetailsActivity failed for ${app.label}", it) }
                .isSuccess
            if (opened) return
            if (app.userHandle != Process.myUserHandle()) {
                _userMessages.tryEmit(context.getString(R.string.error_work_profile))
                return
            }
        }

        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${app.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                Logger.w(TAG, "openAppInfo failed for ${app.label}", it)
                _userMessages.tryEmit(context.getString(R.string.error_launch_failed))
            }
    }

    fun uninstallApp(app: AppInfo) {
        val context = getApplication<Application>()
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                Logger.w(TAG, "uninstall failed for ${app.label}", it)
                _userMessages.tryEmit(context.getString(R.string.error_launch_failed))
            }
    }

    // --- Widget slots & picker (delegated) ---

    fun requestWidgetForSlot(slotId: Int) {
        _pickingWidgetSlotId.value = slotId
    }

    fun dismissWidgetPicker() {
        _pickingWidgetSlotId.value = null
    }

    fun onWidgetProviderSelected(providerInfo: AppWidgetProviderInfo) {
        val slotId = _pickingWidgetSlotId.value ?: return
        _pickingWidgetSlotId.value = null
        widgetHostManager.onWidgetProviderSelected(providerInfo, slotId)
    }

    fun onBindPermissionResult(resultCode: Int, data: Intent?) =
        widgetHostManager.onBindPermissionResult(resultCode, data)

    fun startWidgetConfigure(activity: Activity, appWidgetId: Int, requestCode: Int) =
        widgetHostManager.startWidgetConfigure(activity, appWidgetId, requestCode)

    fun onWidgetConfigureResult(resultCode: Int, data: Intent?) =
        widgetHostManager.onWidgetConfigureResult(resultCode, data)

    fun removeWidgetFromSlot(slotId: Int) =
        widgetHostManager.removeWidgetFromSlot(slotId)

    fun reorderSlots(reorderedSlots: List<WidgetSlotEntity>) =
        widgetHostManager.reorderSlots(reorderedSlots)

    fun replaceHomeWidget() = requestWidgetForSlot(HOME_WIDGET_SLOT_ID)

    fun restoreDefaultClockWidget() = removeWidgetFromSlot(HOME_WIDGET_SLOT_ID)

    fun createHostView(activityContext: Context, appWidgetId: Int): LauncherAppWidgetHostView? =
        widgetHostManager.createHostView(activityContext, appWidgetId)

    fun refreshWidgetListening() = widgetHostManager.refreshListening()

    override fun onCleared() {
        timeProvider.dispose()
        quickActionManager.dispose()
        widgetHostManager.dispose()
        super.onCleared()
    }

    private fun parseComponent(componentKey: String): ComponentName? =
        ComponentName.unflattenFromString(
            componentKey.substringBeforeLast(AppInfo.PROFILE_KEY_SEPARATOR),
        )

    private companion object {
        private const val TAG = "LauncherViewModel"
    }
}
