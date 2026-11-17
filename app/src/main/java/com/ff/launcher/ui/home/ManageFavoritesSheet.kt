package com.ff.launcher.ui.home

import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.ff.launcher.R
import com.ff.launcher.data.AppInfo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class AppSectionIndex(val label: String, val rowIndex: Int)

/**
 * Modern Google Material 3 Pinned Apps & Home Screen Manager.
 *
 * Design:
 * 1. Maintains the wallpaper background with a floating semi-transparent scrim.
 * 2. Each app is displayed in a semi-transparent box with floating glass effect.
 * 3. Next to every app in the drawer list, a Checkbox shows whether it is pinned to home.
 * 4. Maintains the right-edge vertical alphabet scrolling bar (WaveScroller) for instant A-Z jumping.
 * 5. Pinned sequence reordering supports BOTH smooth drag-and-drop AND up/down arrow buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFavoritesSheet(
    targetApp: AppInfo?,
    favoriteApps: List<AppInfo>,
    allApps: List<AppInfo>,
    onToggleFavorite: (AppInfo) -> Unit,
    onMoveFavorite: (componentKey: String, delta: Int) -> Unit,
    onReorderFavorites: (List<String>) -> Unit = {},
    onHideApp: (AppInfo) -> Unit,
    onOpenAppInfo: (AppInfo) -> Unit,
    onUninstallApp: (AppInfo) -> Unit,
    isAppMuted: (String) -> Boolean = { false },
    onToggleMuteApp: (String) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    // Tab 0 = All Apps (Checkbox to pin); Tab 1 = Pinned Sequence (Drag & Drop + Arrows)
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var showTargetCard by remember { mutableStateOf(targetApp != null) }

    val favoriteKeys = remember(favoriteApps) { favoriteApps.map { it.componentKey }.toSet() }

    val filteredApps = remember(allApps, searchQuery) {
        if (searchQuery.isBlank()) {
            allApps.sortedBy { it.label.lowercase() }
        } else {
            val q = searchQuery.trim().lowercase()
            allApps.filter { it.label.lowercase().contains(q) }
                .sortedBy { it.label.lowercase() }
        }
    }

    // Compute sections for the alphabet wave scroller
    val sections = remember(filteredApps) {
        val map = linkedMapOf<String, Int>()
        filteredApps.forEachIndexed { idx, app ->
            val firstChar = app.label.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            val section = if (firstChar in "A".."Z") firstChar else "#"
            if (!map.containsKey(section)) {
                map[section] = idx
            }
        }
        map.map { (label, idx) -> AppSectionIndex(label, idx) }
    }
    val sectionLabels = remember(sections) { sections.map { it.label } }

    val allAppsListState = rememberLazyListState()

    // Local state for smooth drag and drop reordering
    var localFavorites by remember(favoriteApps) { mutableStateOf(favoriteApps) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }

    val insets = WindowInsets.systemBars.asPaddingValues()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.50f)), // Translucent wallpaper scrim
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = insets.calculateTopPadding() + 8.dp),
        ) {
            // Top Bar: Back/Close, Title, Done Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.manage_favorites_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${favoriteApps.size} pinned to home",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }

                FilledTonalButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.arrange_favorites_done),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Navigation Segmented Buttons: All Apps vs Pinned Sequence
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.tab_all_apps, allApps.size))
                }
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.tab_pinned_apps, favoriteApps.size))
                }
            }

            Spacer(Modifier.height(10.dp))

            // Optional Long-Pressed Target App Quick Card
            if (showTargetCard && targetApp != null) {
                val isTargetFav = targetApp.componentKey in favoriteKeys
                val isMuted = isAppMuted(targetApp.packageName)
                TargetAppQuickCard(
                    app = targetApp,
                    isFavorite = isTargetFav,
                    isMuted = isMuted,
                    onToggleFavorite = { onToggleFavorite(targetApp) },
                    onToggleMute = { onToggleMuteApp(targetApp.packageName) },
                    onOpenAppInfo = {
                        onDismiss()
                        onOpenAppInfo(targetApp)
                    },
                    onHideApp = {
                        onDismiss()
                        onHideApp(targetApp)
                    },
                    onUninstallApp = {
                        onDismiss()
                        onUninstallApp(targetApp)
                    },
                    onDismissCard = { showTargetCard = false },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(6.dp))
            }

            // Tab Content
            if (selectedTab == 0) {
                // TAB 0: All Apps with Checkboxes + WaveScroller
                Column(modifier = Modifier.weight(1f)) {
                    // Search Filter
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .height(52.dp),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.manage_favorites_search_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(26.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.60f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.40f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        ),
                    )

                    Spacer(Modifier.height(6.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            state = allAppsListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 46.dp, // Clearance for WaveScroller
                                top = 6.dp,
                                bottom = insets.calculateBottomPadding() + 24.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                items = filteredApps,
                                key = { "app_${it.componentKey}" },
                            ) { app ->
                                val isPinned = app.componentKey in favoriteKeys
                                val favIndex = favoriteApps.indexOfFirst { it.componentKey == app.componentKey }

                                FloatingAppCheckboxRow(
                                    app = app,
                                    isPinned = isPinned,
                                    pinnedPosition = if (isPinned && favIndex >= 0) favIndex + 1 else null,
                                    onToggle = { onToggleFavorite(app) },
                                )
                            }
                        }

                        // Right-edge Alphabet Wave Scroller
                        if (sections.isNotEmpty() && searchQuery.isBlank()) {
                            WaveScroller(
                                sectionLabels = sectionLabels,
                                onSectionSelected = { sectionIndex ->
                                    sections.getOrNull(sectionIndex)?.let { section ->
                                        scope.launch { allAppsListState.scrollToItem(section.rowIndex) }
                                    }
                                },
                                onSettingsSelected = {},
                                onRevealChanged = {},
                                modifier = Modifier
                                    .align(AbsoluteAlignment.TopRight)
                                    .offset(y = this@BoxWithConstraints.maxHeight * 0.22f)
                                    .size(width = 34.dp, height = this@BoxWithConstraints.maxHeight * 0.60f),
                            )
                        }
                    }
                }
            } else {
                // TAB 1: Pinned Apps Sequence (Drag & Drop + Up/Down Arrows)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.drag_to_reorder_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )

                    if (localFavorites.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.60f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                modifier = Modifier.fillMaxWidth(0.9f),
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = stringResource(R.string.arrange_favorites_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    FilledTonalButton(
                                        onClick = { selectedTab = 0 },
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Text("Browse & Check Apps")
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(
                                top = 6.dp,
                                bottom = insets.calculateBottomPadding() + 24.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(
                                items = localFavorites,
                                key = { _, app -> "reorder_${app.componentKey}" },
                            ) { index, app ->
                                val isDragging = draggingIndex == index

                                FloatingDraggableSequenceRow(
                                    index = index,
                                    app = app,
                                    isDragging = isDragging,
                                    dragDeltaY = if (isDragging) dragDeltaY else 0f,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < localFavorites.size - 1,
                                    onMoveUp = {
                                        if (index > 0) {
                                            val list = localFavorites.toMutableList()
                                            val moved = list.removeAt(index)
                                            list.add(index - 1, moved)
                                            localFavorites = list
                                            onReorderFavorites(list.map { it.componentKey })
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    onMoveDown = {
                                        if (index < localFavorites.size - 1) {
                                            val list = localFavorites.toMutableList()
                                            val moved = list.removeAt(index)
                                            list.add(index + 1, moved)
                                            localFavorites = list
                                            onReorderFavorites(list.map { it.componentKey })
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    onRemove = { onToggleFavorite(app) },
                                    onDragStart = {
                                        draggingIndex = index
                                        dragDeltaY = 0f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { deltaY ->
                                        dragDeltaY += deltaY
                                        val currentIdx = draggingIndex ?: return@FloatingDraggableSequenceRow
                                        val itemHeightPx = with(density) { 68.dp.toPx() }
                                        val targetIdx = (currentIdx + (dragDeltaY / itemHeightPx).roundToInt())
                                            .coerceIn(0, localFavorites.size - 1)
                                        if (targetIdx != currentIdx) {
                                            val list = localFavorites.toMutableList()
                                            val moved = list.removeAt(currentIdx)
                                            list.add(targetIdx, moved)
                                            localFavorites = list
                                            draggingIndex = targetIdx
                                            dragDeltaY = 0f
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    onDragEnd = {
                                        onReorderFavorites(localFavorites.map { it.componentKey })
                                        draggingIndex = null
                                        dragDeltaY = 0f
                                    },
                                    onDragCancel = {
                                        draggingIndex = null
                                        dragDeltaY = 0f
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Semi-transparent floating box for an app row with a Checkbox.
 */
@Composable
private fun FloatingAppCheckboxRow(
    app: AppInfo,
    isPinned: Boolean,
    pinnedPosition: Int?,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isPinned) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (isPinned) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)
            } else {
                Color.White.copy(alpha = 0.12f)
            },
        ),
        shadowElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconImage(app = app, size = 42)

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isPinned && pinnedPosition != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.manage_favorites_pinned_badge, pinnedPosition),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Checkbox(
                checked = isPinned,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
            )
        }
    }
}

/**
 * Semi-transparent floating box for reordering pinned apps with Drag & Drop and Up/Down arrows.
 */
@Composable
private fun FloatingDraggableSequenceRow(
    index: Int,
    app: AppInfo,
    isDragging: Boolean,
    dragDeltaY: Float,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isDragging) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.50f)
        },
        border = BorderStroke(
            width = if (isDragging) 2.dp else 1.dp,
            color = if (isDragging) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.White.copy(alpha = 0.15f)
            },
        ),
        shadowElevation = if (isDragging) 10.dp else 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 2f else 1f)
            .graphicsLayer {
                translationY = dragDeltaY
                scaleX = if (isDragging) 1.03f else 1f
                scaleY = if (isDragging) 1.03f else 1f
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag Handle: Touching & dragging this icon reorders the list
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragCancel() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Drag to reorder",
                    tint = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(6.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            ) {
                Text(
                    text = "#${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.width(10.dp))
            AppIconImage(app = app, size = 38)
            Spacer(Modifier.width(10.dp))

            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // Up Arrow
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Move up",
                    tint = if (canMoveUp) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    modifier = Modifier.rotate(180f),
                )
            }

            // Down Arrow
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Move down",
                    tint = if (canMoveDown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                )
            }

            // Remove/Unpin
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Unpin",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Floating quick action card when an app was long-pressed.
 */
@Composable
private fun TargetAppQuickCard(
    app: AppInfo,
    isFavorite: Boolean,
    isMuted: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onHideApp: () -> Unit,
    onUninstallApp: () -> Unit,
    onDismissCard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.70f),
        ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIconImage(app = app, size = 44)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.manage_favorites_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }

                IconButton(
                    onClick = onDismissCard,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.10f),
                thickness = 1.dp,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onOpenAppInfo,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.menu_app_info), fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onHideApp,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.menu_hide_app), fontSize = 12.sp)
                }

                if (app.packageName != "com.ff.launcher" && app.userHandle == Process.myUserHandle()) {
                    OutlinedButton(
                        onClick = onUninstallApp,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.menu_uninstall), fontSize = 12.sp)
                    }
                }
            }

            // Notification Mute / Unmute Button
            OutlinedButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(if (isMuted) R.string.menu_unmute_notifications else R.string.menu_mute_notifications),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isMuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppIconImage(app: AppInfo, size: Int) {
    val bitmap = app.iconBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
