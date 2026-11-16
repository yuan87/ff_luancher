package com.ff.launcher.data

import android.graphics.Bitmap
import android.os.UserHandle

/**
 * A single launchable app on the device.
 *
 * NOT a data class: the auto-generated [equals]/[hashCode] would compare [icon]
 * pixel-by-pixel (expensive, identity-based hashCode). Instead, equality and
 * hashing are keyed solely by [componentKey], which is the unique identity used
 * throughout favorites, hidden apps, badge notifications, and LazyColumn keys.
 *
 * @param componentKey Composite identity: "<ComponentName.flattenToString>#<user serial>".
 *   The serial (via [android.os.UserManager.getSerialNumberForUser]) is stable across
 *   reboots and makes the key unique when the same app is installed in both the personal
 *   and a work profile — LazyColumn keys and favorites persistence both depend on this.
 * @param icon Pre-decoded bitmap (size-capped, rasterized on [kotlinx.coroutines.Dispatchers.IO]),
 *   or null when the icon could not be loaded (a letter placeholder is rendered instead).
 * @param userHandle Owning user — work-profile apps are launched through
 *   [android.content.pm.LauncherApps.startMainActivity], never a bare [android.content.Intent].
 */
class AppInfo(
    val label: String,
    val packageName: String,
    val componentKey: String,
    val icon: Bitmap?,
    val userHandle: UserHandle,
    val firstInstallTime: Long = 0L,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is AppInfo && componentKey == other.componentKey)

    override fun hashCode(): Int = componentKey.hashCode()

    override fun toString(): String =
        "AppInfo(label=$label, componentKey=$componentKey)"

    /** Creates a copy with optionally replaced fields (replaces data class copy()). */
    fun copy(
        label: String = this.label,
        packageName: String = this.packageName,
        componentKey: String = this.componentKey,
        icon: Bitmap? = this.icon,
        userHandle: UserHandle = this.userHandle,
        firstInstallTime: Long = this.firstInstallTime,
    ): AppInfo = AppInfo(label, packageName, componentKey, icon, userHandle, firstInstallTime)

    companion object {
        const val PROFILE_KEY_SEPARATOR = "#"
    }
}
