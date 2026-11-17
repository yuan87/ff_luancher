package com.ff.launcher.ui.secondary

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ff.launcher.R
import com.ff.launcher.data.db.SLOT_FRACTION_LARGE
import com.ff.launcher.data.db.SLOT_FRACTION_MEDIUM
import com.ff.launcher.data.db.SLOT_FRACTION_SMALL
import com.ff.launcher.data.db.WidgetSlotEntity
import com.ff.launcher.data.db.normalizeSlotFraction
import com.ff.launcher.widget.LauncherAppWidgetHostView
import kotlin.math.roundToInt

/**
 * Secondary Screen — Consistent Absolute Proportion Architecture:
 * - Small: 25% of content height (max 4 widgets fit: 4 * 25% = 100%)
 * - Medium: 27.5% of content height (max 3 widgets fit: 3 * 27.5% = 82.5%)
 * - Large: 30% of content height (max 3 widgets fit: 3 * 30% = 90%)
 *
 * Sits seamlessly on wallpaper in normal mode with a subtle '+' button at the bottom
 * aligned with the edge trigger pill. Long-pressing the screen or tapping the '+' button
 * enters Edit Mode where canvases become floating cards with drag/arrow reordering,
 * direct S/M/L size chips, and an adding section at the bottom.
 */
@Composable
fun SecondaryScreen(
    slots: List<WidgetSlotEntity>,
    maxHeight: androidx.compose.ui.unit.Dp,
    isTriggerOnRight: Boolean,
    onRequestWidget: (Int) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onOpenTuning: () -> Unit,
    onUpdateSlotWeight: (Int, Float) -> Unit,
    onReorderSlots: (List<WidgetSlotEntity>) -> Unit,
    obtainHostView: (Context, Int, Int) -> LauncherAppWidgetHostView?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnOpenTuning by rememberUpdatedState(onOpenTuning)
    val haptics = LocalHapticFeedback.current
    val insets = WindowInsets.systemBars.asPaddingValues()

    var isEditMode by remember { mutableStateOf(false) }
    BackHandler(enabled = isEditMode) { isEditMode = false }

    val activeSlots = remember(slots) { slots.filter { it.appWidgetId != null } }

    val totalAllocatedFraction = remember(activeSlots) {
        activeSlots.sumOf { normalizeSlotFraction(it.heightWeight).toDouble() }.toFloat()
    }
    val canAddMore = activeSlots.size < 4
    val nextAvailableSlotId = (1..4).firstOrNull { id -> activeSlots.none { it.slotId == id } } ?: 4

    val totalScreenHeight = maxHeight
    val contentHeight = (totalScreenHeight - insets.calculateTopPadding() - insets.calculateBottomPadding() - 56.dp)
        .coerceAtLeast(200.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent),
    ) {

        if (!isEditMode) {
            // ================= NORMAL VIEWING MODE =================
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { currentOnDismiss() },
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                isEditMode = true
                            },
                        )
                    }
                    .padding(
                        start = 18.dp,
                        end = 18.dp,
                        top = insets.calculateTopPadding() + 10.dp,
                        bottom = insets.calculateBottomPadding() + 10.dp,
                    ),
            ) {
                if (activeSlots.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.empty_second_screen_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        activeSlots.forEach { slot ->
                            val widgetKey = slot.appWidgetId ?: slot.slotId
                            key(widgetKey) {
                                val fraction = normalizeSlotFraction(slot.heightWeight)
                                val slotHeightDp = contentHeight * fraction

                                NormalWidgetHost(
                                    slot = slot,
                                    heightDp = slotHeightDp,
                                    obtainHostView = obtainHostView,
                                    onLongPress = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        isEditMode = true
                                    },
                                )
                            }
                        }
                    }
                }

                // Subtle '+' button at the bottom, on the exact same side as edge trigger pill
                SubtleBottomAddButton(
                    alignRight = isTriggerOnRight,
                    onClick = {
                        if (activeSlots.isEmpty()) {
                            onRequestWidget(1)
                        } else {
                            isEditMode = true
                        }
                    },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        isEditMode = true
                    },
                    modifier = Modifier.align(
                        if (isTriggerOnRight) Alignment.BottomEnd else Alignment.BottomStart,
                    ),
                )
            }
        } else {
            // ================= FLOATING CANVAS EDIT MODE =================
            EditModePanel(
                activeSlots = activeSlots,
                contentHeight = contentHeight,
                totalAllocatedFraction = totalAllocatedFraction,
                canAddMore = canAddMore,
                nextSlotId = nextAvailableSlotId,
                obtainHostView = obtainHostView,
                onUpdateSlotWeight = onUpdateSlotWeight,
                onRemoveWidget = onRemoveWidget,
                onReorderSlots = onReorderSlots,
                onRequestWidget = onRequestWidget,
                onOpenTuning = currentOnOpenTuning,
                onDone = { isEditMode = false },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = insets.calculateTopPadding() + 8.dp,
                        bottom = insets.calculateBottomPadding() + 8.dp,
                    ),
            )
        }
    }
}

/**
 * Clean widget rendering in Normal Mode at its exact proportional height.
 */
@Composable
private fun NormalWidgetHost(
    slot: WidgetSlotEntity,
    heightDp: androidx.compose.ui.unit.Dp,
    obtainHostView: (Context, Int, Int) -> LauncherAppWidgetHostView?,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appWidgetId = slot.appWidgetId ?: return

    val providerAlive = remember(appWidgetId) {
        AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId) != null
    }
    if (!providerAlive) return

    val hostView = remember(appWidgetId) {
        obtainHostView(context, slot.slotId, appWidgetId)
    } ?: return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp),
    ) {
        AndroidView(
            factory = { _ ->
                (hostView.parent as? android.view.ViewGroup)?.removeView(hostView)
                hostView.onLongPress = onLongPress
                hostView.post {
                    hostView.updateAppWidgetSizeFromLayout()
                    hostView.requestLayout()
                    hostView.invalidate()
                }
                hostView
            },
            update = { view ->
                view.onLongPress = onLongPress
                view.post { view.updateAppWidgetSizeFromLayout() }
            },
            onRelease = { view -> view.onLongPress = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Edit Mode Panel: Elevated floating canvases, direct S/M/L size chips,
 * up/down reorder buttons, and the bottom add widget section.
 */
@Composable
private fun EditModePanel(
    activeSlots: List<WidgetSlotEntity>,
    contentHeight: androidx.compose.ui.unit.Dp,
    totalAllocatedFraction: Float,
    canAddMore: Boolean,
    nextSlotId: Int,
    obtainHostView: (Context, Int, Int) -> LauncherAppWidgetHostView?,
    onUpdateSlotWeight: (Int, Float) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onReorderSlots: (List<WidgetSlotEntity>) -> Unit,
    onRequestWidget: (Int) -> Unit,
    onOpenTuning: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // --- Top Bar ---
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f),
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.edit_widgets_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val usagePercent = (totalAllocatedFraction * 100).roundToInt()
                    Text(
                        text = stringResource(R.string.edit_widgets_screen_usage, usagePercent),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (totalAllocatedFraction >= 0.99f) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenTuning) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = onDone,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.edit_widgets_done), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- Floating Canvases ---
        activeSlots.forEachIndexed { index, slot ->
            val widgetKey = slot.appWidgetId ?: slot.slotId
            key(widgetKey) {
                val fraction = normalizeSlotFraction(slot.heightWeight)
                val slotHeightDp = contentHeight * fraction

                FloatingWidgetCard(
                    slot = slot,
                    index = index,
                    totalCount = activeSlots.size,
                    heightDp = slotHeightDp,
                    totalAllocatedFraction = totalAllocatedFraction,
                    obtainHostView = obtainHostView,
                    onUpdateSlotWeight = onUpdateSlotWeight,
                    onRemove = { onRemoveWidget(slot.slotId) },
                    onMoveUp = {
                        if (index > 0) {
                            val mutable = activeSlots.toMutableList()
                            val item = mutable.removeAt(index)
                            mutable.add(index - 1, item)
                            onReorderSlots(mutable)
                        }
                    },
                    onMoveDown = {
                        if (index < activeSlots.lastIndex) {
                            val mutable = activeSlots.toMutableList()
                            val item = mutable.removeAt(index)
                            mutable.add(index + 1, item)
                            onReorderSlots(mutable)
                        }
                    },
                )
            }
        }

        // --- Bottom Add Widget Section ---
        if (canAddMore) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
                border = BorderStroke(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                ),
                onClick = { onRequestWidget(nextSlotId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.edit_widgets_add_section),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.edit_widgets_capacity_full),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Individual Floating Canvas Card in Edit Mode:
 * Features top controls for S (25%), M (28%), L (30%), reorder arrows, and delete button.
 */
@Composable
private fun FloatingWidgetCard(
    slot: WidgetSlotEntity,
    index: Int,
    totalCount: Int,
    heightDp: androidx.compose.ui.unit.Dp,
    totalAllocatedFraction: Float,
    obtainHostView: (Context, Int, Int) -> LauncherAppWidgetHostView?,
    onUpdateSlotWeight: (Int, Float) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val context = LocalContext.current
    val appWidgetId = slot.appWidgetId ?: return
    val currentFraction = normalizeSlotFraction(slot.heightWeight)
    val fractionWithoutCurrent = totalAllocatedFraction - currentFraction

    val hostView = remember(appWidgetId) {
        obtainHostView(context, slot.slotId, appWidgetId)
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.70f),
        border = BorderStroke(
            width = 1.5.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
        ),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            // Card Top Controls: Size chips & Reorder/Remove actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Size options S / M / L
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SizeChip(
                        label = stringResource(R.string.slot_size_s),
                        selected = currentFraction == SLOT_FRACTION_SMALL,
                        onClick = { onUpdateSlotWeight(slot.slotId, SLOT_FRACTION_SMALL) },
                    )
                    SizeChip(
                        label = stringResource(R.string.slot_size_m),
                        selected = currentFraction == SLOT_FRACTION_MEDIUM,
                        onClick = { onUpdateSlotWeight(slot.slotId, SLOT_FRACTION_MEDIUM) },
                    )
                    SizeChip(
                        label = stringResource(R.string.slot_size_l),
                        selected = currentFraction == SLOT_FRACTION_LARGE,
                        onClick = { onUpdateSlotWeight(slot.slotId, SLOT_FRACTION_LARGE) },
                    )
                }

                // Reorder arrows & Remove
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = index > 0,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.a11y_move_widget_up),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = index < totalCount - 1,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.a11y_move_widget_down),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.slot_action_remove_widget),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Widget view inside floating canvas with touch absorption in edit mode
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                if (hostView != null) {
                    AndroidView(
                        factory = { _ ->
                            (hostView.parent as? android.view.ViewGroup)?.removeView(hostView)
                            hostView.post {
                                hostView.updateAppWidgetSizeFromLayout()
                                hostView.requestLayout()
                                hostView.invalidate()
                            }
                            hostView
                        },
                        update = { view ->
                            view.post { view.updateAppWidgetSizeFromLayout() }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // In edit mode, absorb touches over the widget preview so tapping/dragging
                // never accidentally triggers child widget PendingIntents (e.g. launching external apps)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { /* absorb touches in edit mode */ }
                        },
                )
            }
        }
    }
}

@Composable
private fun SizeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            },
        ),
        tonalElevation = if (selected) 2.dp else 0.dp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * Discreet, subtle '+' button pinned to the screen bottom, aligned to the same side
 * as the edge trigger pill (left or right).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubtleBottomAddButton(
    alignRight: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(bottom = 12.dp, start = if (alignRight) 0.dp else 12.dp, end = if (alignRight) 12.dp else 0.dp)
            .size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f),
            ),
            tonalElevation = 0.dp,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.slot_action_add_widget),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
