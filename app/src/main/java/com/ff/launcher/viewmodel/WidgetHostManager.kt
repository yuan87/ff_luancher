package com.ff.launcher.viewmodel

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import androidx.room.withTransaction
import com.ff.launcher.data.db.HOME_WIDGET_SLOT_ID
import com.ff.launcher.data.db.LauncherDatabase
import com.ff.launcher.data.db.SLOT_FRACTION_SMALL
import com.ff.launcher.data.db.WidgetSlotEntity
import com.ff.launcher.data.db.normalizeSlotFraction
import com.ff.launcher.util.Logger
import com.ff.launcher.widget.LauncherAppWidgetHost
import com.ff.launcher.widget.LauncherAppWidgetHostView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
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
 * built-in dispatcher.
 */
class WidgetHostManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val database: LauncherDatabase,
    private val savedStateHandle: SavedStateHandle,
    private val hostResumedProvider: () -> Boolean,
) {
    private val appWidgetHost = LauncherAppWidgetHost(application)
    private val appWidgetManager = AppWidgetManager.getInstance(application)

    /** Widget picker/configure intents for the Activity's registered launchers. */
    private val _widgetRequests = Channel<WidgetRequest>(Channel.BUFFERED)
    val widgetRequests: Flow<WidgetRequest> = _widgetRequests.receiveAsFlow()

    private var isListening = false
    private var refreshJob: Job? = null

    private var pendingPickSlotId: Int?
        get() = savedStateHandle[KEY_PENDING_PICK_SLOT]
        set(value) { savedStateHandle[KEY_PENDING_PICK_SLOT] = value }

    private var pendingPickWidgetId: Int?
        get() = savedStateHandle[KEY_PENDING_PICK_WIDGET]
        set(value) { savedStateHandle[KEY_PENDING_PICK_WIDGET] = value }

    private var pendingConfigSlotId: Int?
        get() = savedStateHandle[KEY_PENDING_CONFIG_SLOT]
        set(value) { savedStateHandle[KEY_PENDING_CONFIG_SLOT] = value }

    private var pendingConfigWidgetId: Int?
        get() = savedStateHandle[KEY_PENDING_CONFIG_WIDGET]
        set(value) { savedStateHandle[KEY_PENDING_CONFIG_WIDGET] = value }

    /**
     * Returns true if a widget pick or configure flow is in progress,
     * which means the host Activity's onStop should NOT close the secondary screen.
     */
    fun hasPendingOperation(): Boolean =
        pendingPickWidgetId != null || pendingConfigWidgetId != null

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

            val options = buildWidgetOptions(slotId)
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
                pendingPickSlotId = slotId
                pendingPickWidgetId = appWidgetId
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
    @MainThread
    fun onBindPermissionResult(resultCode: Int, data: Intent?) {
        val slotId = pendingPickSlotId ?: return
        val appWidgetId = pendingPickWidgetId ?: return
        pendingPickSlotId = null
        pendingPickWidgetId = null

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
            pendingConfigSlotId = slotId
            pendingConfigWidgetId = appWidgetId
            _widgetRequests.trySend(WidgetRequest.Configure(appWidgetId))
        } else {
            bindSlot(slotId, appWidgetId)
        }
    }

    /**
     * Launches the widget's configure page through [AppWidgetHost.startAppWidgetConfigureActivityForResult].
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
    @MainThread
    fun onWidgetConfigureResult(resultCode: Int, data: Intent?) {
        val slotId = pendingConfigSlotId ?: return
        val appWidgetId = pendingConfigWidgetId ?: return
        pendingConfigSlotId = null
        pendingConfigWidgetId = null

        val actualId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId) ?: appWidgetId
        Logger.d(TAG) { "onWidgetConfigureResult: resultCode=$resultCode, slotId=$slotId, appWidgetId=$actualId" }
        val info = appWidgetManager.getAppWidgetInfo(actualId)
        val isOptional = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info != null) {
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        } else false

        if (resultCode == Activity.RESULT_OK || isOptional) {
            bindSlot(slotId, actualId)
        } else {
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
                    (1..MAX_SECONDARY_SLOTS).forEach { id ->
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
     * Slots are written contiguously starting at slotId = 1, and trailing slots are cleared.
     */
    fun reorderSlots(reorderedSlots: List<WidgetSlotEntity>) {
        scope.launch {
            runCatching {
                database.withTransaction {
                    val dao = database.widgetSlotDao()
                    val active = reorderedSlots.filter { it.slotId != HOME_WIDGET_SLOT_ID && it.appWidgetId != null }
                    (1..MAX_SECONDARY_SLOTS).forEach { targetSlotId ->
                        val matching = active.getOrNull(targetSlotId - 1)
                        if (matching != null) {
                            dao.upsert(
                                WidgetSlotEntity(
                                    slotId = targetSlotId,
                                    appWidgetId = matching.appWidgetId,
                                    heightWeight = normalizeSlotFraction(matching.heightWeight),
                                )
                            )
                        } else {
                            dao.clearWidget(targetSlotId)
                        }
                    }
                }
            }.onFailure { Logger.w(TAG, "reorderSlots failed", it) }
            refreshListening()
        }
    }

    /**
     * Creates a fresh host view with the CALLING (activity) context.
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
                if (oldId != null && oldId != appWidgetId) {
                    releaseAppWidgetIdAsync(oldId)
                }
            }.onFailure {
                Logger.w(TAG, "bindSlot failed", it)
                releaseAppWidgetIdAsync(appWidgetId)
            }
        }
    }

    @MainThread
    fun ensureListening() {
        if (isListening || !hostResumedProvider()) return
        runCatching { appWidgetHost.startListening() }
            .onSuccess { isListening = true }
            .onFailure { Logger.w(TAG, "startListening failed", it) }
    }

    @MainThread
    fun stopListening() {
        refreshJob?.cancel()
        refreshJob = null
        if (!isListening) return
        isListening = false
        runCatching { appWidgetHost.stopListening() }
    }

    @MainThread
    fun refreshListening() {
        if (!hostResumedProvider()) return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val delaysMs = longArrayOf(0L, 350L, 800L)
            for (waitMs in delaysMs) {
                if (waitMs > 0L) delay(waitMs)
                if (!isActive || !hostResumedProvider()) break
                runCatching { appWidgetHost.startListening() }
                    .onSuccess { isListening = true }
            }
        }
    }

    private fun buildWidgetOptions(slotId: Int): Bundle {
        val dm = application.resources.displayMetrics
        val widthDp = (dm.widthPixels / dm.density).roundToInt()
        val fullHeightDp = (dm.heightPixels / dm.density).roundToInt()
        val targetHeightDp = if (slotId == HOME_WIDGET_SLOT_ID) 170 else (fullHeightDp * 0.25f).roundToInt()
        return Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, (widthDp - 36).coerceAtLeast(100))
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, (widthDp - 36).coerceAtLeast(100))
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, (targetHeightDp - 16).coerceAtLeast(60))
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, (targetHeightDp - 16).coerceAtLeast(60))
        }
    }

    fun releaseAppWidgetIdAsync(appWidgetId: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        scope.launch(Dispatchers.IO) {
            runCatching { appWidgetHost.deleteAppWidgetId(appWidgetId) }
                .onFailure { Logger.w(TAG, "deleteAppWidgetId failed for $appWidgetId", it) }
        }
    }

    fun cancelPendingOperations() {
        pendingPickWidgetId?.let(::releaseAppWidgetIdAsync)
        pendingPickSlotId = null
        pendingPickWidgetId = null
        pendingConfigWidgetId?.let(::releaseAppWidgetIdAsync)
        pendingConfigSlotId = null
        pendingConfigWidgetId = null
    }

    fun dispose() {
        stopListening()
    }

    private companion object {
        private const val TAG = "WidgetHostManager"
        private const val MAX_SECONDARY_SLOTS = 4
        private const val KEY_PENDING_PICK_SLOT = "pending_pick_slot_id"
        private const val KEY_PENDING_PICK_WIDGET = "pending_pick_widget_id"
        private const val KEY_PENDING_CONFIG_SLOT = "pending_config_slot_id"
        private const val KEY_PENDING_CONFIG_WIDGET = "pending_config_widget_id"
    }
}
