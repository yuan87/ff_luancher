package com.ff.launcher.viewmodel

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.ff.launcher.util.Logger
import androidx.room.withTransaction
import com.ff.launcher.data.db.HOME_WIDGET_SLOT_ID
import com.ff.launcher.data.db.LauncherDatabase
import com.ff.launcher.data.db.SLOT_FRACTION_SMALL
import com.ff.launcher.data.db.WidgetSlotEntity
import com.ff.launcher.data.db.normalizeSlotFraction
import com.ff.launcher.widget.LauncherAppWidgetHost
import com.ff.launcher.widget.LauncherAppWidgetHostView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Encapsulates all AppWidget host operations: allocation, binding, configuration,
 * release, slot persistence, and listening lifecycle.
 *
 * Extracted from [LauncherViewModel] to satisfy single-responsibility and keep
 * the ViewModel under ~400 lines. The ViewModel delegates to this class and
 * exposes its public flows/methods unchanged.
 *
 * Thread-safety: all Binder calls ([allocateAppWidgetId], [deleteAppWidgetId],
 * [bindAppWidgetIdIfAllowed]) run on [Dispatchers.IO]. Database writes use Room's
 * built-in dispatcher. UI-thread state mutations use [MutableStateFlow].
 *
 * @param application Application context for AppWidgetManager and display metrics.
 * @param scope The [CoroutineScope] tied to the ViewModel's lifecycle.
 * @param database The Room database for widget slot persistence.
 * @param hostResumedProvider Lambda returning whether the host Activity is currently resumed.
 */
class WidgetHostManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val database: LauncherDatabase,
    private val hostResumedProvider: () -> Boolean,
) {
    private val appWidgetHost = LauncherAppWidgetHost(application)
    private val appWidgetManager = AppWidgetManager.getInstance(application)

    /**
     * Named state for an in-flight widget pick or configure operation.
     * Replaces the raw [Pair<Int, Int>] fields whose inconsistent tuple ordering
     * was a latent destructuring bug.
     */
    private data class PendingWidget(val slotId: Int, val appWidgetId: Int)

    private var pendingPick: PendingWidget? = null
    private var pendingConfigure: PendingWidget? = null

    /** Widget picker/configure intents for the Activity's registered launchers. */
    private val _widgetRequests = Channel<WidgetRequest>(Channel.BUFFERED)
    val widgetRequests: Flow<WidgetRequest> = _widgetRequests.receiveAsFlow()

    private var isListening = false

    /**
     * Returns true if a widget pick or configure flow is in progress,
     * which means the host Activity's onStop should NOT close the secondary screen.
     */
    fun hasPendingOperation(): Boolean = pendingPick != null || pendingConfigure != null

    /**
     * Called when the user selects a widget provider from the built-in picker.
     * Allocates a host id, attempts [AppWidgetManager.bindAppWidgetIdIfAllowed],
     * and routes to configure or bind-permission as needed.
     */
    fun onWidgetProviderSelected(providerInfo: AppWidgetProviderInfo, slotId: Int) {
        scope.launch {
            val appWidgetId = withContext(Dispatchers.IO) {
                runCatching { appWidgetHost.allocateAppWidgetId() }.getOrNull()
            }
            if (appWidgetId == null) {
                Logger.w(TAG, "Failed to allocate appWidgetId for slot $slotId")
                return@launch
            }

            val displayMetrics = application.resources.displayMetrics
            val widthDp = (displayMetrics.widthPixels / displayMetrics.density).roundToInt()
            val heightDp = ((displayMetrics.heightPixels / displayMetrics.density) / 3).roundToInt()
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp - 36)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp - 36)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp - 28)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp - 28)
            }
            val bound = withContext(Dispatchers.IO) {
                runCatching {
                    appWidgetManager.bindAppWidgetIdIfAllowed(
                        appWidgetId,
                        providerInfo.profile,
                        providerInfo.provider,
                        options,
                    )
                }.getOrDefault(false)
            }

            Logger.d(TAG) { "onWidgetProviderSelected: slotId=$slotId, appWidgetId=$appWidgetId, bound=$bound, provider=${providerInfo.provider}" }

            if (bound) {
                proceedAfterWidgetBound(slotId, appWidgetId)
            } else {
                pendingPick = PendingWidget(slotId = slotId, appWidgetId = appWidgetId)
                val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, providerInfo.provider)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, providerInfo.profile)
                }
                _widgetRequests.trySend(WidgetRequest.BindPermission(bindIntent))
            }
        }
    }

    /** Called when the system ACTION_APPWIDGET_BIND activity returns. */
    fun onBindPermissionResult(resultCode: Int, data: Intent?) {
        val pending = pendingPick ?: return
        val (slotId, appWidgetId) = pending
        pendingPick = null
        Logger.d(TAG) { "onBindPermissionResult: resultCode=$resultCode, slotId=$slotId, appWidgetId=$appWidgetId" }
        if (resultCode == Activity.RESULT_OK) {
            val actualId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId) ?: appWidgetId
            proceedAfterWidgetBound(slotId, actualId)
        } else {
            releaseAppWidgetIdAsync(appWidgetId)
        }
    }

    /** After a successful bind: launch the configure page if the widget has one. */
    private fun proceedAfterWidgetBound(slotId: Int, appWidgetId: Int) {
        val provider = appWidgetManager.getAppWidgetInfo(appWidgetId)
        Logger.d(TAG) { "proceedAfterWidgetBound: slotId=$slotId, appWidgetId=$appWidgetId, provider=$provider, configure=${provider?.configure}" }
        if (provider?.configure != null) {
            pendingConfigure = PendingWidget(slotId = slotId, appWidgetId = appWidgetId)
            _widgetRequests.trySend(WidgetRequest.Configure(appWidgetId))
        } else {
            bindSlot(slotId, appWidgetId)
        }
    }

    /**
     * Launches the widget's configure page through [AppWidgetHost.startAppWidgetConfigureActivityForResult] —
     * the system grants the temporary start permission for non-exported configure activities that a plain
     * Intent launch would crash on (SecurityException / ActivityNotFoundException, Android 11+).
     */
    fun startWidgetConfigure(activity: Activity, appWidgetId: Int, requestCode: Int) {
        val started = runCatching {
            appWidgetHost.startAppWidgetConfigureActivityForResult(
                activity,
                appWidgetId,
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_HISTORY,
                requestCode,
                null,
            )
        }.onFailure { Logger.w(TAG, "startWidgetConfigure failed for widget $appWidgetId", it) }
            .isSuccess
        if (!started) onWidgetConfigureResult(Activity.RESULT_CANCELED, null)
    }

    /** Called when the widget configure activity returns. */
    fun onWidgetConfigureResult(resultCode: Int, data: Intent?) {
        val pending = pendingConfigure
        pendingConfigure = null
        val (slotId, appWidgetId) = pending ?: return
        val actualId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId) ?: appWidgetId
        Logger.d(TAG) { "onWidgetConfigureResult: resultCode=$resultCode, slotId=$slotId, appWidgetId=$actualId" }
        val info = appWidgetManager.getAppWidgetInfo(actualId)
        val isOptional = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info != null) {
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        } else false

        if (resultCode == Activity.RESULT_OK || isOptional) {
            bindSlot(slotId, actualId)
        } else {
            // Configure abandoned — release the id or it leaks in AppWidgetService.
            releaseAppWidgetIdAsync(actualId)
        }
    }

    /** Removes a widget from [slotId], compacts remaining secondary slots, and releases the widget ID. */
    fun removeWidgetFromSlot(slotId: Int) {
        scope.launch {
            if (slotId == HOME_WIDGET_SLOT_ID) {
                val slot = runCatching { database.widgetSlotDao().getById(HOME_WIDGET_SLOT_ID) }.getOrNull()
                database.widgetSlotDao().clearWidget(HOME_WIDGET_SLOT_ID)
                slot?.appWidgetId?.let(::releaseAppWidgetIdAsync)
                refreshListening()
                return@launch
            }

            val allSlots = runCatching { database.widgetSlotDao().getAll() }.getOrDefault(emptyList())
            val slotToRemove = allSlots.firstOrNull { it.slotId == slotId }
            val widgetIdToRelease = slotToRemove?.appWidgetId

            val remainingActive = allSlots
                .filter { it.slotId != HOME_WIDGET_SLOT_ID && it.slotId != slotId && it.appWidgetId != null }

            runCatching {
                database.withTransaction {
                    val dao = database.widgetSlotDao()
                    (1..4).forEach { id ->
                        val matching = remainingActive.getOrNull(id - 1)
                        if (matching != null) {
                            dao.upsert(
                                WidgetSlotEntity(
                                    slotId = id,
                                    appWidgetId = matching.appWidgetId,
                                    heightWeight = normalizeSlotFraction(matching.heightWeight),
                                )
                            )
                        } else {
                            dao.clearWidget(id)
                        }
                    }
                }
            }.onFailure { Logger.w(TAG, "removeWidgetFromSlot failed to compact slots", it) }

            widgetIdToRelease?.let(::releaseAppWidgetIdAsync)
            refreshListening()
        }
    }

    /**
     * Persists a new sequence of active secondary screen slots (drag-and-drop reorder).
     * Slots are written contiguously starting at slotId = 1.
     */
    fun reorderSlots(reorderedSlots: List<WidgetSlotEntity>) {
        scope.launch {
            runCatching {
                database.withTransaction {
                    val dao = database.widgetSlotDao()
                    reorderedSlots.forEachIndexed { index, slot ->
                        val targetSlotId = index + 1
                        dao.upsert(
                            WidgetSlotEntity(
                                slotId = targetSlotId,
                                appWidgetId = slot.appWidgetId,
                                heightWeight = normalizeSlotFraction(slot.heightWeight),
                            )
                        )
                    }
                }
            }.onFailure { Logger.w(TAG, "reorderSlots failed", it) }
            refreshListening()
        }
    }

    /**
     * Creates a fresh host view with the CALLING (activity) context — never cache it
     * here: the ViewModel outlives Activities, and AppWidgetHostView/RemoteViews need
     * an Activity context for PendingIntent launches (BAL), themed inflation and
     * collection widgets. The activity-scoped cache lives in the UI layer instead.
     */
    fun createHostView(activityContext: Context, appWidgetId: Int): LauncherAppWidgetHostView? {
        val provider = appWidgetManager.getAppWidgetInfo(appWidgetId) ?: return null
        return runCatching {
            appWidgetHost.createView(activityContext, appWidgetId, provider)
                as LauncherAppWidgetHostView
        }.onFailure { Logger.w(TAG, "createView failed for widget $appWidgetId", it) }
            .getOrNull()
    }

    private fun bindSlot(slotId: Int, appWidgetId: Int) {
        scope.launch {
            val current = runCatching { database.widgetSlotDao().getById(slotId) }.getOrNull()
            val oldId = current?.appWidgetId
            val weight = current?.heightWeight?.let(::normalizeSlotFraction) ?: SLOT_FRACTION_SMALL
            runCatching {
                database.widgetSlotDao().upsert(WidgetSlotEntity(slotId, appWidgetId, weight))
            }.onSuccess {
                Logger.d(TAG) { "Successfully bound widget $appWidgetId to slot $slotId" }
                refreshListening()
            }.onFailure {
                Logger.w(TAG, "bindSlot failed", it)
                releaseAppWidgetIdAsync(appWidgetId)
            }
            // Replacing a widget leaks the OLD id into AppWidgetService unless deleted.
            if (oldId != null && oldId != appWidgetId) {
                releaseAppWidgetIdAsync(oldId)
            }
        }
    }

    /**
     * Ensures [AppWidgetHost.startListening] is called if the host is resumed.
     * Only a successful start sets the flag.
     */
    fun ensureListening() {
        if (isListening || !hostResumedProvider()) return
        runCatching { appWidgetHost.startListening() }
            .onSuccess { isListening = true }
            .onFailure { Logger.w(TAG, "startListening failed", it) }
    }

    /** Stops widget host listening. Called when the activity pauses or the deck closes. */
    fun stopListening() {
        if (!isListening) return
        isListening = false
        runCatching { appWidgetHost.stopListening() }
    }

    /**
     * Staggered startListening refresh: newly bound widgets or newly inflated host views
     * need AppWidgetService to push the latest RemoteViews. Running a staggered refresh
     * (immediate, 350ms, 800ms) guarantees the initial snapshot is received even if the
     * widget provider app took a brief moment to generate its views.
     */
    fun refreshListening() {
        if (!hostResumedProvider()) return
        scope.launch {
            runCatching { appWidgetHost.startListening() }
            delay(350)
            runCatching { appWidgetHost.startListening() }
            delay(800)
            runCatching { appWidgetHost.startListening() }
        }
    }

    /**
     * Releases a widget ID asynchronously on [Dispatchers.IO] to avoid jank
     * on the main thread (Binder call + disk write to appwidgets.xml).
     */
    fun releaseAppWidgetIdAsync(appWidgetId: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        scope.launch(Dispatchers.IO) {
            runCatching { appWidgetHost.deleteAppWidgetId(appWidgetId) }
                .onFailure { Logger.w(TAG, "deleteAppWidgetId failed for $appWidgetId", it) }
        }
    }

    /**
     * Releases all pending widget IDs when the secondary screen is force-closed.
     * Called from the ViewModel's [closeSecondary].
     */
    fun cancelPendingOperations() {
        pendingPick?.appWidgetId?.let(::releaseAppWidgetIdAsync)
        pendingPick = null
        pendingConfigure?.appWidgetId?.let(::releaseAppWidgetIdAsync)
        pendingConfigure = null
    }

    /** Cleans up the widget host on ViewModel clear. */
    fun dispose() {
        runCatching { appWidgetHost.stopListening() }
    }

    private companion object {
        private const val TAG = "WidgetHostManager"
    }
}
