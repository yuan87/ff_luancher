package com.ff.launcher.ui.home

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.ff.launcher.data.AppInfo
import com.ff.launcher.data.db.WidgetSlotEntity
import com.ff.launcher.data.glance.CalendarState
import com.ff.launcher.data.glance.HeaderConfig
import com.ff.launcher.data.glance.HeaderMode
import com.ff.launcher.data.glance.HeaderSize
import com.ff.launcher.data.glance.TimeData
import com.ff.launcher.data.glance.WeatherData
import com.ff.launcher.notification.BadgeKey
import com.ff.launcher.notification.NotificationCard
import com.ff.launcher.util.Logger
import com.ff.launcher.viewmodel.HomeListState
import com.ff.launcher.viewmodel.HomeRow
import com.ff.launcher.widget.LauncherAppWidgetHostView
import kotlinx.coroutines.launch

private const val TAG = "HomeScreen"

/**
 * Primary home screen, Niagara-style: collapsed state shows only pinned favorites;
 * touching the wave scroller reveals the full locale-collated app drawer list.
 * Remains open while browsing, and smoothly returns to favorites on Back or Home gesture.
 * Long-pressing a row opens a context menu (favorite toggle, App info, Uninstall).
 */
@Composable
fun HomeScreen(
    state: HomeListState,
    listState: LazyListState,
    homeResetTrigger: Long = 0L,
    homeWidgetSlot: WidgetSlotEntity? = null,
    timeData: TimeData = TimeData(),
    calendarState: CalendarState = CalendarState(),
    weatherData: WeatherData = WeatherData(),
    headerConfig: HeaderConfig = HeaderConfig(),
    onHeaderModeChange: (HeaderMode) -> Unit = {},
    onHeaderUnitChange: (Boolean) -> Unit = {},
    onHeaderSizeChange: (HeaderSize) -> Unit = {},
    onRefreshWeather: () -> Unit = {},
    onReplaceHomeWidget: () -> Unit = {},
    onRestoreDefaultClock: () -> Unit = {},
    obtainHostView: (Context, Int, Int) -> LauncherAppWidgetHostView? = { _, _, _ -> null },
    onAppClick: (AppInfo) -> Unit,
    onAppLongClick: (AppInfo) -> Unit,
    onOpenDeck: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    hasNotification: (AppInfo, Boolean) -> Boolean = { _, _ -> false },
    expandedBadgeKey: BadgeKey? = null,
    expandedCards: List<NotificationCard> = emptyList(),
    markAsReadEnabled: Boolean = true,
    onToggleAppNotifications: (AppInfo) -> Unit = {},
    onDismissNotificationCard: (NotificationCard) -> Unit = {},
    onClearAllNotificationCards: (AppInfo) -> Unit = {},
    onOpenNotificationCard: (NotificationCard) -> Unit = {},
    onCollapseNotifications: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var revealed by remember { mutableStateOf(false) }
    val favListState = rememberLazyListState()

    LaunchedEffect(homeResetTrigger) {
        if (homeResetTrigger > 0L) {
            Logger.d(TAG) { "homeResetTrigger fired ($homeResetTrigger) -> folding full list back to favorites" }
            onCollapseNotifications()
            revealed = false
            runCatching { listState.scrollToItem(0) }
            runCatching { favListState.scrollToItem(0) }
        }
    }

    val showFullList = revealed
    val activeListState = if (showFullList) listState else favListState

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val triggerDistancePx = with(density) { 64.dp.toPx() }
    var accumulatedPullUp by remember { mutableStateOf(0f) }

    // Consistent, deliberate overscroll-to-search:
    // Only activates when the bottom of the list is ALREADY visible and resting on screen,
    // and the user actively pulls up by a deliberate distance (64dp).
    // Never activates from momentum/fling.
    val nestedScrollConnection = remember(activeListState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && accumulatedPullUp > 0f) {
                    val consumed = available.y.coerceAtMost(accumulatedPullUp)
                    accumulatedPullUp -= consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) {
                    return Offset.Zero
                }

                val layoutInfo = activeListState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems == 0) return Offset.Zero

                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                val isBottomShown = lastVisibleItem != null &&
                    lastVisibleItem.index == totalItems - 1 &&
                    (lastVisibleItem.offset + lastVisibleItem.size) <= (layoutInfo.viewportEndOffset + 8)

                if (available.y < 0f && isBottomShown) {
                    accumulatedPullUp += -available.y
                    if (accumulatedPullUp >= triggerDistancePx) {
                        accumulatedPullUp = 0f
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenSearch()
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                accumulatedPullUp = 0f
                return Velocity.Zero
            }
        }
    }

    fun onScrollerReveal(active: Boolean) {
        if (active) {
            Logger.d(TAG) { "onScrollerReveal: active=true -> entering app drawer (revealed=true)" }
            revealed = true
        }
    }

    // System back gesture closes open notification panel or folds the revealed list back to favorites-only.
    BackHandler(enabled = revealed || expandedBadgeKey != null) {
        if (expandedBadgeKey != null) {
            onCollapseNotifications()
        } else {
            Logger.d(TAG) { "BackHandler: returning to favorites view" }
            revealed = false
        }
    }

    val insets = WindowInsets.systemBars.asPaddingValues()
    val listContentPadding = PaddingValues(
        start = 16.dp,
        end = 42.dp, // 34dp WaveScroller + 8dp breathing room = ~88% of screen width clickable
        top = insets.calculateTopPadding() + 12.dp,
        bottom = insets.calculateBottomPadding() + 24.dp,
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = activeListState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection),
            contentPadding = listContentPadding,
        ) {
            item(key = "home_header_area") {
                HomeHeaderArea(
                    homeWidgetSlot = homeWidgetSlot,
                    timeData = timeData,
                    calendarState = calendarState,
                    weatherData = weatherData,
                    headerConfig = headerConfig,
                    onHeaderModeChange = onHeaderModeChange,
                    onHeaderUnitChange = onHeaderUnitChange,
                    onHeaderSizeChange = onHeaderSizeChange,
                    onRefreshWeather = onRefreshWeather,
                    onReplaceHomeWidget = onReplaceHomeWidget,
                    onRestoreDefaultClock = onRestoreDefaultClock,
                    obtainHostView = obtainHostView,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            when {
                showFullList -> {
                    items(
                        items = state.rows,
                        key = ::rowKey,
                        contentType = { it::class },
                    ) { row ->
                        when (row) {
                            is HomeRow.Header -> SectionHeader(row.label)
                            is HomeRow.AppRow -> {
                                val app = row.app
                                val isAppExpanded = expandedBadgeKey?.packageName == app.packageName
                                AppListRow(
                                    row = row,
                                    isPinned = false,
                                    hasNotification = hasNotification(app, false),
                                    isExpanded = isAppExpanded,
                                    cards = if (isAppExpanded) expandedCards else emptyList(),
                                    markAsReadEnabled = markAsReadEnabled,
                                    onClick = onAppClick,
                                    onLongClick = onAppLongClick,
                                    onToggleNotifications = { onToggleAppNotifications(app) },
                                    onCardClick = onOpenNotificationCard,
                                    onDismissCard = onDismissNotificationCard,
                                    onClearAll = { onClearAllNotificationCards(app) },
                                )
                            }
                            HomeRow.Divider -> HomeDivider()
                        }
                    }
                    item(key = "search_pull_hint") {
                        SearchPullHint(onOpenSearch = onOpenSearch)
                    }
                }

                state.favoriteCount == 0 && state.favoriteKeys.isEmpty() && state.rows.isNotEmpty() -> {
                    item(key = "empty_guide") {
                        EmptyFavoritesGuide(
                            onBrowseApps = {
                                revealed = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                        )
                    }
                }

                else -> {
                    items(
                        items = state.rows
                            .takeWhile { it !is HomeRow.Header }
                            .filterIsInstance<HomeRow.AppRow>(),
                        key = { "fav_${it.app.componentKey}" },
                    ) { row ->
                        val app = row.app
                        val isAppExpanded = expandedBadgeKey?.packageName == app.packageName
                        AppListRow(
                            row = row,
                            isPinned = true,
                            hasNotification = hasNotification(app, true),
                            isExpanded = isAppExpanded,
                            cards = if (isAppExpanded) expandedCards else emptyList(),
                            markAsReadEnabled = markAsReadEnabled,
                            onClick = onAppClick,
                            onLongClick = onAppLongClick,
                            onToggleNotifications = { onToggleAppNotifications(app) },
                            onCardClick = onOpenNotificationCard,
                            onDismissCard = onDismissNotificationCard,
                            onClearAll = { onClearAllNotificationCards(app) },
                        )
                    }
                    item(key = "fav_search_pull_hint") {
                        SearchPullHint(onOpenSearch = onOpenSearch)
                    }
                }
            }
        }

        val sectionLabels = remember(state.sections) { state.sections.map { it.label } }
        WaveScroller(
            sectionLabels = sectionLabels,
            onSectionSelected = { sectionIndex ->
                state.sections.getOrNull(sectionIndex)?.let { section ->
                    scope.launch { listState.scrollToItem(section.rowIndex) }
                }
            },
            onSettingsSelected = onOpenSettings,
            onRevealChanged = ::onScrollerReveal,
            modifier = Modifier
                .align(AbsoluteAlignment.TopRight)
                .offset(y = maxHeight * 0.35f)
                .size(width = 34.dp, height = maxHeight * 0.50f),
        )
    }
}

private fun rowKey(row: HomeRow): String = when (row) {
    is HomeRow.Header -> "header_${row.label}"
    is HomeRow.AppRow ->
        if (row.isFavorite) "fav_${row.app.componentKey}" else "app_${row.app.componentKey}"
    HomeRow.Divider -> "divider"
}

@Composable
private fun HomeHeaderArea(
    homeWidgetSlot: WidgetSlotEntity?,
    timeData: TimeData,
    calendarState: CalendarState,
    weatherData: WeatherData,
    headerConfig: HeaderConfig,
    onHeaderModeChange: (HeaderMode) -> Unit,
    onHeaderUnitChange: (Boolean) -> Unit,
    onHeaderSizeChange: (HeaderSize) -> Unit = {},
    onRefreshWeather: () -> Unit,
    onReplaceHomeWidget: () -> Unit,
    onRestoreDefaultClock: () -> Unit,
    obtainHostView: (Context, Int, Int) -> LauncherAppWidgetHostView?,
    modifier: Modifier = Modifier,
) {
    val appWidgetId = homeWidgetSlot?.appWidgetId
    if (appWidgetId != null) {
        HomeCustomWidgetView(
            appWidgetId = appWidgetId,
            headerSize = headerConfig.size,
            onSizeChange = onHeaderSizeChange,
            obtainHostView = obtainHostView,
            onReplaceWidget = onReplaceHomeWidget,
            onRestoreDefaultClock = onRestoreDefaultClock,
            modifier = modifier,
        )
    } else {
        HomeSmartHeader(
            timeData = timeData,
            calendarState = calendarState,
            weatherData = weatherData,
            config = headerConfig,
            onModeChange = onHeaderModeChange,
            onUnitChange = onHeaderUnitChange,
            onSizeChange = onHeaderSizeChange,
            onRefreshWeather = onRefreshWeather,
            onReplaceWidget = onReplaceHomeWidget,
            modifier = modifier,
        )
    }
}
