package com.ff.launcher.viewmodel

import com.ff.launcher.util.Logger
import com.ff.launcher.data.AppInfo
import com.ff.launcher.data.notification.BadgePreferences
import com.ff.launcher.data.notification.BadgePreferencesRepository
import com.ff.launcher.data.notification.BadgeScope
import com.ff.launcher.notification.BadgeKey
import com.ff.launcher.notification.BadgeNotificationListener
import com.ff.launcher.notification.NotificationBadgeStore
import com.ff.launcher.notification.NotificationCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manages the expanded notification panel state and badge preferences.
 *
 * Extracted from [LauncherViewModel]. Owns:
 * - Which app's notifications are expanded ([expandedBadgeKey])
 * - The transient list of [NotificationCard]s shown in the panel
 * - Badge preference writes (enable/disable, scope, muting, mark-as-read)
 * - Auto-collapsing when a notification is dismissed externally
 *
 * Does NOT own [NotificationBadgeStore] or [BadgeNotificationListener] — those
 * are process-scoped singletons. This class only reads/controls transient panel state.
 */
class NotificationPanelManager(
    private val repository: BadgePreferencesRepository,
    private val scope: CoroutineScope,
) {
    val badgePreferences: StateFlow<BadgePreferences> = repository.preferences
        .stateIn(scope, SharingStarted.Eagerly, BadgePreferences())

    val notificationKeys: StateFlow<Map<BadgeKey, Set<String>>> = NotificationBadgeStore.badgeKeys

    private val _expandedBadgeKey = MutableStateFlow<BadgeKey?>(null)
    val expandedBadgeKey: StateFlow<BadgeKey?> = _expandedBadgeKey.asStateFlow()

    private val _expandedCards = MutableStateFlow<List<NotificationCard>>(emptyList())
    val expandedCards: StateFlow<List<NotificationCard>> = _expandedCards.asStateFlow()

    init {
        scope.launch {
            badgePreferences.collect { prefs ->
                NotificationBadgeStore.includeSilent = prefs.includeSilent
                BadgeNotificationListener.requestSync()
            }
        }
        startAutoCollapseWatcher()
    }

    private fun startAutoCollapseWatcher() {
        scope.launch {
            NotificationBadgeStore.badgeKeys.collect { keysMap ->
                val expanded = _expandedBadgeKey.value
                if (expanded != null) {
                    val remaining = keysMap[expanded]
                    if (remaining.isNullOrEmpty()) {
                        collapseNotifications()
                    } else {
                        val listener = BadgeNotificationListener.getInstance()
                        val hideText = badgePreferences.value.hideMessageText
                        val cards = listener?.getCardsForApp(expanded.packageName, expanded.userSerial, hideText) ?: emptyList()
                        if (cards.isEmpty()) {
                            collapseNotifications()
                        } else {
                            _expandedCards.value = cards
                        }
                    }
                }
            }
        }
    }

    fun hasNotification(key: BadgeKey, isPinned: Boolean, hasAccess: Boolean): Boolean {
        val prefs = badgePreferences.value
        if (!prefs.enabled) return false
        if (!hasAccess) return false
        if (prefs.mutedPackages.contains(key.packageName)) return false
        if (prefs.scope == BadgeScope.PINNED_ONLY && !isPinned) return false

        return NotificationBadgeStore.hasNotifications(key)
    }

    fun toggleAppNotifications(key: BadgeKey) {
        if (_expandedBadgeKey.value == key) {
            collapseNotifications()
        } else {
            val listener = BadgeNotificationListener.getInstance()
            val hideText = badgePreferences.value.hideMessageText
            val cards = listener?.getCardsForApp(key.packageName, key.userSerial, hideText) ?: emptyList()
            if (cards.isNotEmpty()) {
                _expandedBadgeKey.value = key
                _expandedCards.value = cards
            } else {
                collapseNotifications()
            }
        }
    }

    fun dismissNotificationCard(card: NotificationCard) {
        val listener = BadgeNotificationListener.getInstance()
        val markAsRead = badgePreferences.value.markAsReadOnDismiss
        listener?.dismissNotification(card.key, markAsRead)

        val updated = _expandedCards.value.filter { it.key != card.key }
        _expandedCards.value = updated
        if (updated.isEmpty()) {
            _expandedBadgeKey.value = null
        }
    }

    fun clearAllNotificationCards(packageName: String, userSerial: Long) {
        val listener = BadgeNotificationListener.getInstance()
        val markAsRead = badgePreferences.value.markAsReadOnDismiss
        listener?.clearAllForApp(packageName, userSerial, markAsRead)
        collapseNotifications()
    }

    fun openNotificationCard(card: NotificationCard) {
        runCatching {
            card.contentIntent?.send()
        }.onFailure {
            Logger.w(TAG, "Failed to send notification contentIntent")
        }
        collapseNotifications()
    }

    fun collapseNotifications() {
        _expandedBadgeKey.value = null
        _expandedCards.value = emptyList()
    }

    fun isAppMuted(packageName: String): Boolean {
        return badgePreferences.value.mutedPackages.contains(packageName)
    }

    fun toggleMuteApp(packageName: String) {
        scope.launch {
            repository.toggleMutePackage(packageName)
        }
    }

    fun setBadgeEnabled(enabled: Boolean) {
        scope.launch {
            repository.setEnabled(enabled)
        }
    }

    fun setMarkAsReadOnDismiss(value: Boolean) {
        scope.launch {
            repository.setMarkAsReadOnDismiss(value)
        }
    }

    fun setHideMessageText(value: Boolean) {
        scope.launch {
            repository.setHideMessageText(value)
        }
    }

    fun setBadgeScope(scope_: BadgeScope) {
        scope.launch {
            repository.setScope(scope_)
        }
    }

    fun setIncludeSilent(value: Boolean) {
        scope.launch {
            repository.setIncludeSilent(value)
        }
    }

    fun resetBadgePreferences() {
        scope.launch {
            repository.resetToDefaults()
        }
    }

    private companion object {
        private const val TAG = "NotificationPanelManager"
    }
}
