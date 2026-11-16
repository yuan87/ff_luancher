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
 */
class WidgetHostViewCache(
    private val viewModel: LauncherViewModel,
    private val scope: CoroutineScope,
) {
    val cache = mutableMapOf<Int, LauncherAppWidgetHostView>()
    private var purgeJob: Job? = null

    fun obtain(context: Context, slotId: Int, appWidgetId: Int): LauncherAppWidgetHostView? {
        cache[slotId]?.let { cached ->
            if (cached.appWidgetId == appWidgetId) {
                (cached.parent as? ViewGroup)?.removeView(cached)
                return cached
            }
            (cached.parent as? ViewGroup)?.removeView(cached)
            cache.remove(slotId)
        }
        return viewModel.createHostView(context, appWidgetId)?.also {
            cache[slotId] = it
            viewModel.refreshWidgetListening()
        }
    }

    fun onSecondaryScreenChanged(isOpen: Boolean) {
        if (isOpen) {
            purgeJob?.cancel()
            purgeJob = null
        } else {
            cache.filterKeys { it != HOME_WIDGET_SLOT_ID }.values.forEach {
                it.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            purgeJob?.cancel()
            purgeJob = scope.launch {
                delay(WIDGET_CACHE_IDLE_MS)
                val secondarySlotIds = cache.keys.filter { it != HOME_WIDGET_SLOT_ID }
                secondarySlotIds.forEach { slotId ->
                    cache.remove(slotId)?.let { v ->
                        (v.parent as? ViewGroup)?.removeView(v)
                    }
                }
            }
        }
    }

    fun pruneRemovedSlots(slots: List<WidgetSlotEntity>) {
        val boundAppWidgetIds = slots
            .filter { it.appWidgetId != null }
            .associate { it.slotId to it.appWidgetId }
        cache.entries.removeAll { entry ->
            val stale = boundAppWidgetIds[entry.key] != entry.value.appWidgetId
            if (stale) {
                (entry.value.parent as? ViewGroup)?.removeView(entry.value)
                entry.value.setLayerType(View.LAYER_TYPE_NONE, null)
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
