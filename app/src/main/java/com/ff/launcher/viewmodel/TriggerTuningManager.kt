package com.ff.launcher.viewmodel

import com.ff.launcher.util.Logger
import com.ff.launcher.data.TriggerConfig
import com.ff.launcher.data.TriggerEdgeSide
import com.ff.launcher.data.TriggerMode
import com.ff.launcher.data.TriggerPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages the edge trigger pill's preview/commit/settle pattern.
 *
 * Extracted from [LauncherViewModel] — owns the in-memory preview overlay,
 * the persisted [TriggerPreferencesRepository] writes, and the post-commit
 * settle logic that retires the preview only after the persisted flow catches up.
 *
 * The [triggerDisplay] flow is what the pill renders: live preview while the
 * tuning sheet is open, persisted value otherwise.
 */
class TriggerTuningManager(
    private val repository: TriggerPreferencesRepository,
    private val scope: CoroutineScope,
) {
    /** In-memory preview overriding the persisted config while the tuning sheet is open. */
    private val _triggerPreview = MutableStateFlow<TriggerConfig?>(null)

    /** Bounded to one live waiter; replaced (never queued) by every new commit. */
    private var settleJob: Job? = null

    /** The persisted trigger configuration (source of truth). */
    val triggerConfig: StateFlow<TriggerConfig> = repository.config
        .stateIn(scope, SharingStarted.Eagerly, TriggerConfig())

    /** What the pill renders: live preview while tuning, persisted value otherwise. */
    val triggerDisplay: StateFlow<TriggerConfig> =
        combine(triggerConfig, _triggerPreview) { persisted, preview -> preview ?: persisted }
            .stateIn(scope, SharingStarted.Eagerly, TriggerConfig())

    fun previewTriggerVerticalBias(bias: Float) =
        previewTrigger {
            it.copy(verticalBias = bias.coerceIn(TriggerPreferencesRepository.VERTICAL_BIAS_RANGE))
        }

    fun previewTriggerHeightDp(height: Int) =
        previewTrigger { it.copy(heightDp = height.coerceIn(TriggerPreferencesRepository.HEIGHT_RANGE)) }

    fun previewTriggerWidthDp(width: Int) =
        previewTrigger { it.copy(widthDp = width.coerceIn(TriggerPreferencesRepository.WIDTH_RANGE)) }

    fun previewTriggerOpacity(opacity: Float) =
        previewTrigger { it.copy(opacity = opacity.coerceIn(TriggerPreferencesRepository.OPACITY_RANGE)) }

    fun setTriggerEdgeSide(side: TriggerEdgeSide) {
        previewTrigger { it.copy(edgeSide = side) }
        commitAndSettle { repository.setEdgeSide(side) }
    }

    fun setTriggerVerticalBias(bias: Float) {
        previewTrigger { it.copy(verticalBias = bias.coerceIn(TriggerPreferencesRepository.VERTICAL_BIAS_RANGE)) }
        commitAndSettle { repository.setVerticalBias(bias) }
    }

    fun setTriggerHeightDp(height: Int) {
        previewTrigger { it.copy(heightDp = height.coerceIn(TriggerPreferencesRepository.HEIGHT_RANGE)) }
        commitAndSettle { repository.setHeightDp(height) }
    }

    fun setTriggerWidthDp(width: Int) {
        previewTrigger { it.copy(widthDp = width.coerceIn(TriggerPreferencesRepository.WIDTH_RANGE)) }
        commitAndSettle { repository.setWidthDp(width) }
    }

    fun setTriggerOpacity(opacity: Float) {
        previewTrigger { it.copy(opacity = opacity.coerceIn(TriggerPreferencesRepository.OPACITY_RANGE)) }
        commitAndSettle { repository.setOpacity(opacity) }
    }

    fun setTriggerMode(mode: TriggerMode) {
        previewTrigger { it.copy(mode = mode) }
        commitAndSettle { repository.setMode(mode) }
    }

    fun setTriggerEnabled(enabled: Boolean) {
        scope.launch { repository.setEnabled(enabled) }
    }

    fun setDeckWidgetCount(count: Int) {
        scope.launch { repository.setWidgetCount(count) }
    }

    private fun previewTrigger(transform: (TriggerConfig) -> TriggerConfig) {
        _triggerPreview.value = transform(_triggerPreview.value ?: triggerConfig.value)
    }

    /**
     * Persists the commit, then retires the preview only after the persisted flow has
     * caught up (bounded wait). Clearing the preview eagerly — e.g. when the sheet is
     * dismissed while the write is still in flight — would flash the pre-tuning value
     * back onto the pill for one frame before the new value lands.
     */
    private fun commitAndSettle(persist: suspend () -> Unit) {
        scope.launch {
            runCatching { persist() }
                .onFailure { Logger.w(TAG, "Trigger persist failed", it) }
        }
        settleJob?.cancel()
        settleJob = scope.launch {
            val preview = _triggerPreview.value ?: return@launch
            withTimeoutOrNull(1_000) { triggerConfig.first { it == preview } }
            if (_triggerPreview.value == preview) _triggerPreview.value = null
        }
    }

    private companion object {
        private const val TAG = "TriggerTuningManager"
    }
}
