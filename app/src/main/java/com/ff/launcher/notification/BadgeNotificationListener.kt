package com.ff.launcher.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ff.launcher.util.Logger
import com.ff.launcher.R
import java.lang.ref.WeakReference

/**
 * Android [NotificationListenerService] tracking active eligible notifications.
 * Never stores or logs message content or titles.
 */
class BadgeNotificationListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instanceRef?.get() == this) {
            instanceRef = null
        }
        NotificationBadgeStore.setConnected(false)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instanceRef = WeakReference(this)
        NotificationBadgeStore.setConnected(true)
        syncActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instanceRef?.get() == this) {
            instanceRef = null
        }
        NotificationBadgeStore.setConnected(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                requestRebind(ComponentName(this, BadgeNotificationListener::class.java))
            }.onFailure {
                Logger.w(TAG, "Failed to requestRebind for notification listener")
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        super.onNotificationPosted(sbn, rankingMap)
        syncActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        super.onNotificationRemoved(sbn, rankingMap)
        syncActiveNotifications()
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        super.onNotificationRankingUpdate(rankingMap)
        syncActiveNotifications()
    }

    fun syncActiveNotifications() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
        val includeSilent = NotificationBadgeStore.includeSilent

        val newMap = mutableMapOf<BadgeKey, MutableSet<String>>()

        for (sbn in active) {
            if (!isEligibleNotification(sbn, active, ranking, includeSilent)) continue

            val userSerial = userManager?.getSerialNumberForUser(sbn.user) ?: 0L
            val badgeKey = BadgeKey(sbn.packageName, userSerial)
            newMap.getOrPut(badgeKey) { mutableSetOf() }.add(sbn.key)
        }

        NotificationBadgeStore.updateKeys(newMap)
    }

    /**
     * Determines whether an individual [StatusBarNotification] is eligible to show an indicator
     * and appear in the inline panel.
     */
    private fun isEligibleNotification(
        sbn: StatusBarNotification,
        activeSbns: Array<StatusBarNotification>,
        rankingMap: RankingMap?,
        includeSilent: Boolean,
    ): Boolean {
        // 1. Ignore own notifications
        if (sbn.packageName == applicationContext.packageName) return false

        val notif = sbn.notification ?: return false

        // 2. Ongoing / FLAG_NO_CLEAR (music, navigation, downloads, foreground services)
        if (sbn.isOngoing || (notif.flags and Notification.FLAG_NO_CLEAR != 0)) return false

        // 3. Ignored categories
        when (notif.category) {
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS -> return false
        }

        // 4. Group summaries: if children exist in the same group, ignore summary
        val isSummary = (notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isSummary) {
            val groupKey = sbn.groupKey
            val hasChildren = activeSbns.any { other ->
                other != sbn &&
                    other.packageName == sbn.packageName &&
                    other.user == sbn.user &&
                    other.groupKey == groupKey &&
                    (other.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                    !other.isOngoing &&
                    (other.notification.flags and Notification.FLAG_NO_CLEAR) == 0
            }
            if (hasChildren) return false
        }

        // 5. Ranking badge capability & importance
        if (rankingMap != null) {
            val ranking = Ranking()
            if (rankingMap.getRanking(sbn.key, ranking)) {
                if (!ranking.canShowBadge()) return false
                if (!includeSilent && ranking.importance <= NotificationManager.IMPORTANCE_LOW) {
                    return false
                }
            }
        }

        return true
    }

    /**
     * Fetches transient [NotificationCard] items for the specified package and user serial.
     * Titles and message bodies are read on demand and never cached permanently or logged.
     */
    fun getCardsForApp(
        packageName: String,
        userSerial: Long,
        hideMessageText: Boolean,
    ): List<NotificationCard> {
        val active = runCatching { activeNotifications }.getOrNull() ?: return emptyList()
        val ranking = runCatching { currentRanking }.getOrNull()
        val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
        val includeSilent = NotificationBadgeStore.includeSilent

        val cards = mutableListOf<NotificationCard>()
        val genericNew = getString(R.string.notification_generic_new)
        val genericTitle = getString(R.string.notification_generic_title)
        val genericHidden = getString(R.string.notification_generic_hidden)
        val appName = runCatching {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()?.ifBlank { null } ?: genericTitle

        for (sbn in active) {
            if (sbn.packageName != packageName) continue
            val sbnSerial = userManager?.getSerialNumberForUser(sbn.user) ?: 0L
            if (sbnSerial != userSerial) continue

            if (!isEligibleNotification(sbn, active, ranking, includeSilent)) continue

            val notif = sbn.notification ?: continue
            val extras = notif.extras

            val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val rawText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
                ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
                ?: ""

            val isSecret = notif.visibility == Notification.VISIBILITY_SECRET
            val displayTitle: String
            val displayText: String

            if (hideMessageText) {
                displayTitle = appName
                displayText = genericNew
            } else if (isSecret) {
                displayTitle = rawTitle.ifBlank { genericTitle }
                displayText = genericHidden
            } else {
                displayTitle = rawTitle.ifBlank { genericTitle }
                displayText = rawText
            }

            val hasMarkRead = findMarkAsReadAction(notif) != null

            cards.add(
                NotificationCard(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    title = displayTitle,
                    text = displayText,
                    postTimeMillis = sbn.postTime,
                    isDismissible = !sbn.isOngoing && (notif.flags and Notification.FLAG_NO_CLEAR == 0),
                    hasMarkReadAction = hasMarkRead,
                    contentIntent = notif.contentIntent,
                )
            )
        }

        return cards.sortedByDescending { it.postTimeMillis }
    }

    /**
     * Dismisses the notification at the system level. If [markAsRead] is true and
     * the notification exposes a mark-as-read semantic action, fires that action before cancelling.
     */
    fun dismissNotification(key: String, markAsRead: Boolean) {
        if (markAsRead) {
            val active = runCatching { activeNotifications }.getOrNull()
            val sbn = active?.firstOrNull { it.key == key }
            if (sbn != null) {
                sbn.notification?.let { notif ->
                    findMarkAsReadAction(notif)?.let { action ->
                        runCatching {
                            action.actionIntent.send()
                        }.onFailure {
                            Logger.w(TAG, "Failed to invoke mark-as-read PendingIntent", it)
                        }
                    }
                }
            }
        }

        runCatching {
            cancelNotification(key)
        }.onFailure {
            Logger.w(TAG, "Failed to cancelNotification for key", it)
        }
    }

    /**
     * Dismisses all eligible notifications for an app, optionally marking as read.
     */
    fun clearAllForApp(packageName: String, userSerial: Long, markAsRead: Boolean) {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
        val includeSilent = NotificationBadgeStore.includeSilent
        val keysToCancel = mutableListOf<String>()

        for (sbn in active) {
            if (sbn.packageName != packageName) continue
            val sbnSerial = userManager?.getSerialNumberForUser(sbn.user) ?: 0L
            if (sbnSerial != userSerial) continue
            if (!isEligibleNotification(sbn, active, ranking, includeSilent)) continue

            if (markAsRead) {
                sbn.notification?.let { notif ->
                    findMarkAsReadAction(notif)?.let { action ->
                        runCatching {
                            action.actionIntent.send()
                        }.onFailure {
                            Logger.w(TAG, "Failed to invoke mark-as-read PendingIntent in clearAll", it)
                        }
                    }
                }
            }
            keysToCancel.add(sbn.key)
        }

        if (keysToCancel.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                runCatching { cancelNotifications(keysToCancel.toTypedArray()) }
            } else {
                keysToCancel.forEach { key -> runCatching { cancelNotification(key) } }
            }
        }
    }

    private fun findMarkAsReadAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            actions.firstOrNull {
                it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ &&
                    it.actionIntent != null
            }?.let { return it }
        }
        return actions.firstOrNull { action ->
            val title = action.title?.toString() ?: return@firstOrNull false
            action.actionIntent != null && MARK_READ_REGEX.containsMatchIn(title)
        }
    }

    companion object {
        private const val TAG = "BadgeListener"
        private val MARK_READ_REGEX = Regex(
            pattern = """\b(mark\s+as\s+read|mark\s+read|read)\b|已读|標示為已讀|標為已讀""",
            option = RegexOption.IGNORE_CASE,
        )
        private var instanceRef: WeakReference<BadgeNotificationListener>? = null

        fun getInstance(): BadgeNotificationListener? = instanceRef?.get()

        fun requestSync() {
            getInstance()?.syncActiveNotifications()
        }
    }
}
