package com.ff.launcher.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ff.launcher.SettingsActivity
import com.ff.launcher.data.QuickActionType
import com.ff.launcher.data.TriggerEdgeSide
import com.ff.launcher.data.db.HOME_WIDGET_SLOT_ID
import com.ff.launcher.data.db.SLOT_FRACTION_SMALL
import com.ff.launcher.data.db.WidgetSlotEntity
import com.ff.launcher.ui.home.AppSearchSheet
import com.ff.launcher.ui.home.DefaultLauncherBanner
import com.ff.launcher.ui.home.HomeScreen
import com.ff.launcher.ui.home.ManageFavoritesSheet
import com.ff.launcher.ui.onboarding.WelcomeOnboardingScreen
import com.ff.launcher.ui.quickaction.QuickActionButton
import com.ff.launcher.ui.quickaction.QuickActionPickerSheet
import com.ff.launcher.ui.secondary.SecondaryScreen
import com.ff.launcher.ui.secondary.WidgetPickerSheet
import com.ff.launcher.ui.trigger.EdgeTrigger
import com.ff.launcher.viewmodel.LauncherViewModel

/** The wave scroller occupies 35%–85% of the screen height on the right edge. */
private const val SCROLLER_TOP_FRACTION = 0.35f
private const val SCROLLER_BOTTOM_FRACTION = 0.85f


/**
 * Root composition, layered bottom-to-top:
 * 1. Primary home screen (app list + A–Z wave scroller) over the wallpaper scrim.
 * 2. Secondary screen overlay, fading/sliding in from the trigger's edge.
 * 3. Edge trigger pill, always on top so it can open *and* close the overlay.
 *
 * All edge alignment uses [AbsoluteAlignment] (physical sides) so RTL locales can't flip
 * the trigger to the opposite edge of where its swipe direction and exclusion rect point.
 */
@Composable
fun LauncherRoot(viewModel: LauncherViewModel) {
    val isSecondScreenOpen by viewModel.isSecondScreenOpen.collectAsStateWithLifecycle()
    val triggerConfig by viewModel.triggerDisplay.collectAsStateWithLifecycle()
    val isArrangeFavoritesOpen by viewModel.isArrangeFavoritesOpen.collectAsStateWithLifecycle()
    val selectedAppForManage by viewModel.selectedAppForManage.collectAsStateWithLifecycle()
    val favoriteAppsList by viewModel.favoriteAppsList.collectAsStateWithLifecycle()
    val allInstalledApps by viewModel.allInstalledApps.collectAsStateWithLifecycle()
    val recentApps by viewModel.recentApps.collectAsStateWithLifecycle()
    val newlyInstalledApps by viewModel.newlyInstalledApps.collectAsStateWithLifecycle()
    val isAppSearchOpen by viewModel.isAppSearchOpen.collectAsStateWithLifecycle()
    val pickingWidgetSlotId by viewModel.pickingWidgetSlotId.collectAsStateWithLifecycle()
    val homeState by viewModel.homeState.collectAsStateWithLifecycle()
    val widgetSlots by viewModel.widgetSlots.collectAsStateWithLifecycle()
    val homeWidgetSlot by viewModel.homeWidgetSlot.collectAsStateWithLifecycle()
    val hiddenApps by viewModel.hiddenApps.collectAsStateWithLifecycle()
    val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
    val isDefaultLauncher by viewModel.isDefaultLauncher.collectAsStateWithLifecycle()
    val homeResetTrigger by viewModel.homeResetTrigger.collectAsStateWithLifecycle()
    val timeState by viewModel.timeState.collectAsStateWithLifecycle()
    val calendarState by viewModel.calendarState.collectAsStateWithLifecycle()
    val weatherState by viewModel.weatherState.collectAsStateWithLifecycle()
    val headerConfig by viewModel.headerConfig.collectAsStateWithLifecycle()
    val quickActionConfig by viewModel.quickActionDisplay.collectAsStateWithLifecycle()
    val isFlashlightOn by viewModel.isFlashlightOn.collectAsStateWithLifecycle()
    val isQuickActionPickerOpen by viewModel.isQuickActionPickerOpen.collectAsStateWithLifecycle()
    val quickActionTargetApp = remember(quickActionConfig, allInstalledApps) {
        if (quickActionConfig.actionType == QuickActionType.APP) {
            allInstalledApps.firstOrNull { it.componentKey == quickActionConfig.componentKey }
        } else null
    }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val badgePreferences by viewModel.badgePreferences.collectAsStateWithLifecycle()
    val notificationKeys by viewModel.notificationKeys.collectAsStateWithLifecycle()
    val hasNotificationAccess by viewModel.hasNotificationAccess.collectAsStateWithLifecycle()
    val expandedBadgeKey by viewModel.expandedBadgeKey.collectAsStateWithLifecycle()
    val expandedCards by viewModel.expandedCards.collectAsStateWithLifecycle()

    val badgeState = remember(badgePreferences, notificationKeys, hasNotificationAccess) {
        com.ff.launcher.viewmodel.BadgeEvaluationState(
            prefs = badgePreferences,
            activeKeys = notificationKeys,
            hasAccess = hasNotificationAccess,
        )
    }

    // Default-launcher re-prompt: shows a top banner when the user returns after
    // switching to another launcher, session-dismissable but reset when the role toggles.
    val bannerDismissed = remember { mutableStateOf(false) }
    LaunchedEffect(isDefaultLauncher) {
        if (isDefaultLauncher) bannerDismissed.value = false
    }

    val hostViewCache = rememberWidgetHostViewCache(
        viewModel = viewModel,
        isSecondScreenOpen = isSecondScreenOpen,
        widgetSlots = widgetSlots,
    )

    // Return mechanic #1: system edge-back gesture closes the Secondary Screen.
    BackHandler(enabled = isSecondScreenOpen) {
        viewModel.setSecondScreenOpen(false)
    }

    LaunchedEffect(viewModel) {
        viewModel.userMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Role-manager dialog result: re-check default-home state once the system dialog returns.
    val homeRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshDefaultLauncherState() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.30f)), // readability scrim over wallpaper
    ) {
        val showDefaultBanner = onboardingDone == true &&
            !isDefaultLauncher &&
            !isSecondScreenOpen &&
            !bannerDismissed.value

        val bannerExtraTopPadding by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (showDefaultBanner) 136.dp else 0.dp,
            label = "bannerExtraTopPadding",
        )

        AnimatedVisibility(
            visible = !isSecondScreenOpen,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.fillMaxSize(),
        ) {
            HomeScreen(
                state = homeState,
                listState = listState,
                maxHeight = maxHeight,
                extraTopPadding = bannerExtraTopPadding,
                homeResetTrigger = homeResetTrigger,
                homeWidgetSlot = homeWidgetSlot,
                timeData = timeState,
                calendarState = calendarState,
                weatherData = weatherState,
                headerConfig = headerConfig,
                onHeaderModeChange = viewModel::setHeaderMode,
                onHeaderUnitChange = viewModel::setHeaderUseFahrenheit,
                onHeaderSizeChange = viewModel::setHeaderSize,
                onRefreshWeather = viewModel::refreshWeather,
                onReplaceHomeWidget = viewModel::replaceHomeWidget,
                onRestoreDefaultClock = viewModel::restoreDefaultClockWidget,
                obtainHostView = hostViewCache::obtain,
                onAppClick = viewModel::launchApp,
                onAppLongClick = viewModel::openManageFavorites,
                onOpenDeck = { viewModel.setSecondScreenOpen(true) },
                onOpenSettings = {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
                onOpenSearch = viewModel::openAppSearch,
                hasNotification = { app, isPinned -> badgeState.hasNotification(app.badgeKey, isPinned) },
                expandedBadgeKey = expandedBadgeKey,
                expandedCards = expandedCards,
                markAsReadEnabled = badgePreferences.markAsReadOnDismiss,
                onToggleAppNotifications = viewModel::toggleAppNotifications,
                onDismissNotificationCard = viewModel::dismissNotificationCard,
                onClearAllNotificationCards = viewModel::clearAllNotificationCards,
                onOpenNotificationCard = { card -> viewModel.openNotificationCard(card) },
                onCollapseNotifications = viewModel::collapseNotifications,
            )
        }

        // Re-engagement banner: prompts the user to restore FF Launcher as default
        // when they return after switching to another launcher (post-onboarding only).
        AnimatedVisibility(
            visible = onboardingDone == true &&
                !isDefaultLauncher &&
                !isSecondScreenOpen &&
                !bannerDismissed.value,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            DefaultLauncherBanner(
                onClick = {
                    runCatching { homeRoleLauncher.launch(viewModel.buildSetDefaultIntent()) }
                },
                onDismiss = { bannerDismissed.value = true },
            )
        }

        val slideFromEnd = triggerConfig.edgeSide == TriggerEdgeSide.RIGHT
        val secondarySlots = remember(widgetSlots) {
            val nonHomeSlots = widgetSlots.filter { it.slotId != HOME_WIDGET_SLOT_ID }
            val existingMap = nonHomeSlots.associateBy { it.slotId }
            (1..4).map { id ->
                existingMap[id] ?: WidgetSlotEntity(slotId = id, appWidgetId = null, heightWeight = SLOT_FRACTION_SMALL)
            }
        }
        AnimatedVisibility(
            visible = isSecondScreenOpen,
            enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) { full ->
                if (slideFromEnd) full / 4 else -full / 4
            },
            exit = fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { full ->
                if (slideFromEnd) full / 4 else -full / 4
            },
        ) {
            SecondaryScreen(
                slots = secondarySlots,
                maxHeight = maxHeight,
                isTriggerOnRight = slideFromEnd,
                onRequestWidget = viewModel::requestWidgetForSlot,
                onRemoveWidget = viewModel::removeWidgetFromSlot,
                onOpenTuning = {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
                onUpdateSlotWeight = viewModel::setSlotHeightWeight,
                onReorderSlots = viewModel::reorderSlots,
                obtainHostView = hostViewCache::obtain,
                onDismiss = { viewModel.setSecondScreenOpen(false) },
            )
        }

        // Pill center sits at verticalBias of the screen height, clamped on-screen.
        val triggerHeight = triggerConfig.heightDp.dp
        val triggerTop = (maxHeight * triggerConfig.verticalBias - triggerHeight / 2)
            .coerceIn(0.dp, (maxHeight - triggerHeight).coerceAtLeast(0.dp))

        // Hit-testing hand-over: with the trigger parked inside the wave scroller's band
        // on the same edge, the scroller must own the strip. Merely flagging the
        // trigger's detectors off is not enough — its top-layer 24dp target would still
        // swallow ACTION_DOWN (tap detectors consume the down), so the scroller could
        // never start and any "now scrolling" flag could never flip. Skipping the
        // detectors entirely keeps the hit path from consuming anything. The deck stays
        // reachable via the ⚙ node → tuning sheet → move the trigger.
        val scrollerTop = maxHeight * SCROLLER_TOP_FRACTION
        val scrollerBottom = maxHeight * SCROLLER_BOTTOM_FRACTION
        val overlapsScroller = slideFromEnd &&
            triggerTop < scrollerBottom &&
            triggerTop + triggerHeight > scrollerTop

        // First-run onboarding: hides the edge trigger and gates its gestures until the
        // user finishes or skips the 3-step tour (steps 1–2 must not be auto-skipped).
        val showOnboarding = onboardingDone == false

        if (triggerConfig.enabled) {
            EdgeTrigger(
                config = triggerConfig,
                isSecondScreenOpen = isSecondScreenOpen,
                // Tapping the trigger opens the deck; tapping it again closes it.
                onActivate = { viewModel.setSecondScreenOpen(!isSecondScreenOpen) },
                gesturesEnabled = !showOnboarding && !isArrangeFavoritesOpen && (isSecondScreenOpen || !overlapsScroller),
                modifier = Modifier
                    .align(if (slideFromEnd) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft)
                    .offset(y = triggerTop),
            )
        }

        val quickActionIsRight = quickActionConfig.edgeSide == TriggerEdgeSide.RIGHT
        AnimatedVisibility(
            visible = quickActionConfig.enabled && !showOnboarding && !isSecondScreenOpen && !isArrangeFavoritesOpen && !isAppSearchOpen,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(if (quickActionIsRight) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft),
        ) {
            QuickActionButton(
                config = quickActionConfig,
                targetApp = quickActionTargetApp,
                isFlashlightOn = isFlashlightOn,
                maxHeight = maxHeight,
                onExecute = viewModel::executeQuickAction,
                onCustomize = viewModel::openQuickActionPicker,
                onPreviewVerticalBias = viewModel::previewQuickActionVerticalBias,
                onCommitVerticalBias = viewModel::setQuickActionVerticalBias,
            )
        }

        AnimatedVisibility(
            visible = isArrangeFavoritesOpen,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { full -> full / 8 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { full -> full / 8 },
            modifier = Modifier.fillMaxSize(),
        ) {
            ManageFavoritesSheet(
                targetApp = selectedAppForManage,
                favoriteApps = favoriteAppsList,
                allApps = allInstalledApps,
                onToggleFavorite = viewModel::toggleFavorite,
                onMoveFavorite = viewModel::moveFavorite,
                onReorderFavorites = viewModel::updateFavoriteOrder,
                onHideApp = viewModel::hideApp,
                onOpenAppInfo = viewModel::openAppInfo,
                onUninstallApp = viewModel::uninstallApp,
                isAppMuted = viewModel::isAppMuted,
                onToggleMuteApp = viewModel::toggleMuteApp,
                onDismiss = viewModel::closeManageFavorites,
            )
        }

        if (pickingWidgetSlotId != null) {
            WidgetPickerSheet(
                onDismiss = viewModel::dismissWidgetPicker,
                onSelectProvider = viewModel::onWidgetProviderSelected,
            )
        }

        if (isAppSearchOpen) {
            AppSearchSheet(
                allApps = allInstalledApps,
                recentApps = recentApps,
                newlyInstalledApps = newlyInstalledApps,
                onAppClick = viewModel::launchApp,
                onAppLongClick = viewModel::openManageFavorites,
                onOpenSettings = {
                    viewModel.closeAppSearch()
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
                hasNotification = { app -> badgeState.hasNotification(app.badgeKey, isPinned = false) },
                expandedBadgeKey = expandedBadgeKey,
                expandedCards = expandedCards,
                markAsReadEnabled = badgePreferences.markAsReadOnDismiss,
                onToggleAppNotifications = viewModel::toggleAppNotifications,
                onDismissNotificationCard = viewModel::dismissNotificationCard,
                onClearAllNotificationCards = viewModel::clearAllNotificationCards,
                onOpenNotificationCard = { card -> viewModel.openNotificationCard(card) },
                onDismiss = {
                    viewModel.collapseNotifications()
                    viewModel.closeAppSearch()
                },
            )
        }

        if (isQuickActionPickerOpen) {
            QuickActionPickerSheet(
                config = quickActionConfig,
                allApps = allInstalledApps,
                onSelectAction = viewModel::setQuickAction,
                onDismiss = viewModel::closeQuickActionPicker,
            )
        }

        // First-run onboarding overlays everything (topmost) until default home is set
        // or skipped. onboardingDone == null (DataStore still loading) shows nothing.
        if (showOnboarding) {
            WelcomeOnboardingScreen(
                isDefaultLauncher = isDefaultLauncher,
                onSetDefaultClick = {
                    runCatching { homeRoleLauncher.launch(viewModel.buildSetDefaultIntent()) }
                },
                onCompleteOnboarding = { viewModel.completeOnboarding() },
            )
        }
    }
}
