package com.ff.launcher.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.ff.launcher.data.db.HOME_WIDGET_SLOT_ID
import com.ff.launcher.data.db.WidgetSlotEntity
import com.ff.launcher.viewmodel.LauncherViewModel
import com.ff.launcher.widget.LauncherAppWidgetHostView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** SPEC Module 4: idle widgets' cached views are dropped after 5 minutes closed. */
private const val WIDGET_CACHE_IDLE_MS = 5 * 60_000L

/**
 * Encapsulates the activity-scoped Compose AppWidgetHostView cache and idle purge lifecycle.
 *
 * View-cache lives in the composition (Activity context) and NOT in the ViewModel:
 * AppWidgetHostView/RemoteViews need an Activity context for PendingIntent launches (BAL),
 * themed inflation and collection widgets.
 *
 * Keyed by [appWidgetId] so slot reordering reuses live AppWidgetHostView instances
 * across positional slots without tearing down and re-inflating views.
 */
class WidgetHostViewCache(
    private val viewModel: LauncherViewModel,
    private val scope: CoroutineScope,
) {
    private val cacheByWidgetId = mutableMapOf<Int, LauncherAppWidgetHostView>()
    private var homeAppWidgetId: Int? = null
    private var purgeJob: Job? = null

    fun obtain(context: Context, slotId: Int, appWidgetId: Int): LauncherAppWidgetHostView? {
        if (slotId == HOME_WIDGET_SLOT_ID) {
            homeAppWidgetId = appWidgetId
        }
        cacheByWidgetId[appWidgetId]?.let { cached ->
            (cached.parent as? ViewGroup)?.removeView(cached)
            return cached
        }
        return viewModel.createHostView(context, appWidgetId)?.also {
            cacheByWidgetId[appWidgetId] = it
            viewModel.refreshWidgetListening()
        }
    }

    fun onSecondaryScreenChanged(isOpen: Boolean) {
        if (isOpen) {
            purgeJob?.cancel()
            purgeJob = null
        } else {
            val homeId = homeAppWidgetId
            cacheByWidgetId.filterKeys { it != homeId }.values.forEach {
                it.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            purgeJob?.cancel()
            purgeJob = scope.launch {
                delay(WIDGET_CACHE_IDLE_MS)
                val secondaryWidgetIds = cacheByWidgetId.keys.filter { it != homeAppWidgetId }
                secondaryWidgetIds.forEach { widgetId ->
                    cacheByWidgetId.remove(widgetId)?.let { v ->
                        (v.parent as? ViewGroup)?.removeView(v)
                    }
                }
            }
        }
    }

    fun pruneRemovedSlots(slots: List<WidgetSlotEntity>) {
        homeAppWidgetId = slots.firstOrNull { it.slotId == HOME_WIDGET_SLOT_ID }?.appWidgetId
        val activeWidgetIds = slots.mapNotNullTo(HashSet()) { it.appWidgetId }
        cacheByWidgetId.entries.removeAll { (widgetId, view) ->
            val stale = widgetId !in activeWidgetIds
            if (stale) {
                (view.parent as? ViewGroup)?.removeView(view)
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            stale
        }
    }
}

@Composable
fun rememberWidgetHostViewCache(
    viewModel: LauncherViewModel,
    isSecondScreenOpen: Boolean,
    widgetSlots: List<WidgetSlotEntity>,
): WidgetHostViewCache {
    val scope = rememberCoroutineScope()
    val cacheHolder = remember(viewModel) { WidgetHostViewCache(viewModel, scope) }

    LaunchedEffect(isSecondScreenOpen) {
        cacheHolder.onSecondaryScreenChanged(isSecondScreenOpen)
    }

    LaunchedEffect(widgetSlots) {
        cacheHolder.pruneRemovedSlots(widgetSlots)
    }

    return cacheHolder
}
