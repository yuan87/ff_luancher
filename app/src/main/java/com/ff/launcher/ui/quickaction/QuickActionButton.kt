package com.ff.launcher.ui.quickaction

import com.ff.launcher.util.Logger
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ff.launcher.R
import com.ff.launcher.data.AppInfo
import com.ff.launcher.data.QuickActionConfig
import com.ff.launcher.data.QuickActionPreferencesRepository
import com.ff.launcher.data.QuickActionType
import com.ff.launcher.data.TriggerEdgeSide

private const val TAG = "FF/QuickActionButton"

/**
 * Ergonomic floating quick-action button for instant launch of user-customized app or tool.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickActionButton(
    config: QuickActionConfig,
    targetApp: AppInfo?,
    isFlashlightOn: Boolean = false,
    maxHeight: Dp,
    onExecute: () -> Unit,
    onCustomize: () -> Unit,
    onPreviewVerticalBias: (Float) -> Unit = {},
    onCommitVerticalBias: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!config.enabled) return

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val buttonSize = config.sizeDp.dp
    val buttonTop = (maxHeight * config.verticalBias - buttonSize / 2)
        .coerceIn(16.dp, (maxHeight - buttonSize - 16.dp).coerceAtLeast(16.dp))

    val isLeft = config.edgeSide == TriggerEdgeSide.LEFT
    val description = stringResource(R.string.quick_action_tap_hint)

    val scale by animateFloatAsState(
        targetValue = if (config.actionType == QuickActionType.FLASHLIGHT && isFlashlightOn) 1.08f else 1f,
        animationSpec = tween(150),
        label = "quickActionScale",
    )

    val currentAlpha = if (config.actionType == QuickActionType.FLASHLIGHT && isFlashlightOn) {
        1f
    } else {
        config.opacity
    }

    Box(
        modifier = modifier
            .offset(y = buttonTop)
            .padding(horizontal = 8.dp)
            .scale(scale)
            .graphicsLayer {
                alpha = currentAlpha
            }
            .semantics {
                this.contentDescription = description
                this.role = Role.Button
            },
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (config.actionType == QuickActionType.FLASHLIGHT && isFlashlightOn) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.70f)
            },
            border = BorderStroke(
                width = 1.dp,
                color = if (config.actionType == QuickActionType.FLASHLIGHT && isFlashlightOn) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                },
            ),
            shadowElevation = (2.dp * currentAlpha).coerceAtLeast(0.dp),
            modifier = Modifier
                .size(buttonSize)
                .clip(RoundedCornerShape(16.dp))
                .pointerInput(maxHeight) {
                    val maxHeightPx = maxHeight.toPx()
                    detectVerticalDragGestures(
                        onDragStart = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            Logger.d(TAG) { "Vertical drag started at bias=${config.verticalBias}" }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val deltaBias = dragAmount / maxHeightPx
                            val newBias = (config.verticalBias + deltaBias)
                                .coerceIn(QuickActionPreferencesRepository.VERTICAL_BIAS_RANGE)
                            onPreviewVerticalBias(newBias)
                        },
                        onDragEnd = {
                            Logger.i(TAG) { "Vertical drag committed at bias=${config.verticalBias}" }
                            onCommitVerticalBias(config.verticalBias)
                        },
                        onDragCancel = {
                            Logger.d(TAG) { "Vertical drag cancelled" }
                            onCommitVerticalBias(config.verticalBias)
                        },
                    )
                }
                .combinedClickable(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        Logger.i(TAG) { "Tapped quick action: ${config.actionType}" }
                        onExecute()
                    },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        Logger.i(TAG) { "Long clicked quick action: open customization" }
                        onCustomize()
                    },
                ),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(buttonSize),
            ) {
                when (config.actionType) {
                    QuickActionType.SECOND_SCREEN -> {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.quick_action_preset_second_screen),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    QuickActionType.FLASHLIGHT -> {
                        Icon(
                            imageVector = if (isFlashlightOn) QuickActionIcons.FlashlightOn else QuickActionIcons.FlashlightOff,
                            contentDescription = stringResource(R.string.quick_action_preset_flashlight),
                            tint = if (isFlashlightOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    QuickActionType.CAMERA -> {
                        Icon(
                            imageVector = QuickActionIcons.Camera,
                            contentDescription = stringResource(R.string.quick_action_preset_camera),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    QuickActionType.ALARM -> {
                        Icon(
                            imageVector = QuickActionIcons.Alarm,
                            contentDescription = stringResource(R.string.quick_action_preset_alarm),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    QuickActionType.ASSISTANT -> {
                        Icon(
                            imageVector = QuickActionIcons.Assistant,
                            contentDescription = stringResource(R.string.quick_action_preset_assistant),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    QuickActionType.APP -> {
                        val bitmap = targetApp?.iconBitmap
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = targetApp.label,
                                modifier = Modifier.size(28.dp),
                            )
                        } else {
                            Icon(
                                imageVector = QuickActionIcons.Rocket,
                                contentDescription = config.customLabel.ifEmpty { "App" },
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
