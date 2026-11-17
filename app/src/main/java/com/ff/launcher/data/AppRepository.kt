package com.ff.launcher.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.ff.launcher.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Emitted for the actions the rest of the app must react to synchronously. */
sealed interface PackageEvent {
    /**
     * An app was uninstalled from the user identified by [userSerial] (composite keys
     * end in `#userSerial`). [replacing] is true when the removal is part of an update —
     * don't prune then.
     */
    data class Removed(
        val packageName: String,
        val userSerial: Long,
        val replacing: Boolean,
    ) : PackageEvent
}

/**
 * Loads all launchable apps and keeps the in-memory list fresh.
 *
 * Primary source is [LauncherApps] (SPEC-mandated): it covers work/managed profiles with
 * properly badged icons, and its callback surfaces cross-profile install/uninstall events
 * that package broadcasts never deliver. A plain [PackageManager] query remains as a
 * fallback for the calling user. Package broadcasts (calling profile) are also observed
 * directly — they carry a `package:` data URI, so the filter declares that scheme. Every
 * refresh is debounced so bursts of system broadcasts collapse into a single scan, and a
 * display-density/UiMode change re-decodes the icons. Package removals are additionally
 * published on [packageEvents] so repositories can prune their per-package rows.
 */
class AppRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val userManager = appContext.getSystemService(UserManager::class.java)

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _packageEvents = MutableSharedFlow<PackageEvent>(extraBufferCapacity = 8)
    val packageEvents: SharedFlow<PackageEvent> = _packageEvents.asSharedFlow()

    /** Delayed removal verifications in flight, keyed by package+user (update-safe). */
    private val pendingRemovalVerifications = ConcurrentHashMap<Pair<String, UserHandle>, Job>()

    private var packageReceiver: BroadcastReceiver? = null
    private var configurationReceiver: BroadcastReceiver? = null
    private var launcherAppsCallback: LauncherApps.Callback? = null
    private var refreshJob: Job? = null

    init {
        registerPackageReceiver()
        registerConfigurationReceiver()
        registerLauncherAppsCallback()
        scheduleRefresh(0L)
    }

    fun refresh() = scheduleRefresh(0L)

    /** Debounced full reload: bursts of package events collapse into a single scan. */
    private fun scheduleRefresh(delayMs: Long = DEBOUNCE_MS) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(delayMs)
            _apps.value = loadApps()
        }
    }

    suspend fun loadApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val viaLauncherApps = loadAppsViaLauncherApps()
        // No sorting here: LauncherViewModel.buildHomeState() owns bucketing AND order
        // (pinyin-collated groups with componentKey tie-breaks), so any ordering done in
        // the repository would be overwritten on every pass — pure redundant work.
        val loaded = if (viaLauncherApps.isNotEmpty()) {
            viaLauncherApps
        } else {
            loadAppsViaPackageManager()
        }
        loaded
    }

    /**
     * Primary source. Per-profile [SecurityException]s (LauncherApps restricts other
     * profiles to the default HOME app) degrade to that profile simply being skipped,
     * and are logged instead of silently swallowed.
     */
    private fun loadAppsViaLauncherApps(): List<AppInfo> {
        val service = launcherApps ?: return emptyList()
        val density = appContext.resources.configuration.densityDpi
        val serials = service.profiles.associateWith { user ->
            userManager?.getSerialNumberForUser(user) ?: 0L
        }
        return service.profiles
            .flatMap { user ->
                runCatching { service.getActivityList(null, user) }
                    .onFailure { Logger.w(TAG, "getActivityList failed for $user", it) }
                    .getOrDefault(emptyList())
            }
            // Defensive: the launcher never lists itself (its activity has no LAUNCHER
            // category today, but this guards future manifest changes).
            .filter { it.componentName.packageName != appContext.packageName }
            .mapNotNull { info ->
                runCatching { info.toAppInfo(density, serials[info.user] ?: 0L) }
                    .onFailure { Logger.w(TAG, "Failed to load ${info.componentName}", it) }
                    .getOrNull()
            }
    }

    /** Fallback: plain [PackageManager] query for the calling user only. */
    private fun loadAppsViaPackageManager(): List<AppInfo> {
        val pm = appContext.packageManager
        val mySerial = userManager?.getSerialNumberForUser(Process.myUserHandle()) ?: 0L
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            // Defensive self-exclusion (see the LauncherApps path).
            .filter { it.activityInfo?.packageName != appContext.packageName }
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                runCatching {
                    val label =
                        resolveInfo.loadLabel(pm).toString().ifBlank { activityInfo.packageName }
                    val component = ComponentName(activityInfo.packageName, activityInfo.name)
                    val installTime = runCatching {
                        pm.getPackageInfo(activityInfo.packageName, 0).firstInstallTime
                    }.getOrDefault(0L)
                    AppInfo(
                        label = label,
                        packageName = activityInfo.packageName,
                        componentKey = component.flattenToString() +
                            AppInfo.PROFILE_KEY_SEPARATOR + mySerial,
                        icon = runCatching { resolveInfo.loadIcon(pm).toCappedBitmap() }
                            .getOrNull(),
                        userHandle = Process.myUserHandle(),
                        firstInstallTime = installTime,
                    )
                }.onFailure { Logger.w(TAG, "Failed to load $activityInfo", it) }
                    .getOrNull()
            }
    }

    private fun LauncherActivityInfo.toAppInfo(density: Int, userSerial: Long): AppInfo {
        val component = componentName
        val label = label?.toString()?.ifBlank { component.packageName } ?: component.packageName
        val installTime = runCatching {
            appContext.packageManager.getPackageInfo(component.packageName, 0).firstInstallTime
        }.getOrDefault(0L)
        return AppInfo(
            label = label,
            packageName = component.packageName,
            componentKey = component.flattenToString() + AppInfo.PROFILE_KEY_SEPARATOR + userSerial,
            icon = runCatching { getBadgedIcon(density).toCappedBitmap() }.getOrNull(),
            userHandle = user,
            firstInstallTime = installTime,
        )
    }

    /**
     * Never upscales, and caps the long edge at [ICON_MAX_PX]. Adaptive icons otherwise
     * decode at full drawable density (108dp = 324px at 3x), so a few hundred held
     * bitmaps can balloon past 100MB of permanently-resident RAM.
     */
    private fun Drawable.toCappedBitmap(): Bitmap {
        val iw = intrinsicWidth.takeIf { it > 0 } ?: ICON_MAX_PX
        val ih = intrinsicHeight.takeIf { it > 0 } ?: ICON_MAX_PX
        val scale = min(1f, ICON_MAX_PX.toFloat() / max(iw, ih))
        return toBitmap(
            (iw * scale).roundToInt().coerceAtLeast(1),
            (ih * scale).roundToInt().coerceAtLeast(1),
        )
    }

    private fun registerPackageReceiver() {
        // The broadcast is for this process's user; its serial is constant for the
        // receiver's lifetime, so resolve it once instead of per event.
        val ownSerial = userManager?.getSerialNumberForUser(Process.myUserHandle()) ?: 0L
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED) {
                    val packageName = intent.data?.schemeSpecificPart
                    if (packageName != null) {
                        _packageEvents.tryEmit(
                            PackageEvent.Removed(
                                packageName = packageName,
                                userSerial = ownSerial,
                                replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false),
                            ),
                        )
                    }
                }
                scheduleRefresh()
            }
        }
        // Package broadcasts carry a `package:` data URI — the scheme must be declared
        // or the filter never matches. Locale broadcasts carry no data, so they need
        // their own filter (an intent without data cannot match a scheme-constrained one).
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val localeFilter = IntentFilter(Intent.ACTION_LOCALE_CHANGED)
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            packageFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            localeFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        packageReceiver = receiver
    }

    /** Display-size/DPI and dark-mode (uiMode) changes re-resolve icon resources — re-decode. */
    private fun registerConfigurationReceiver() {
        var lastDensityDpi = appContext.resources.configuration.densityDpi
        var lastUiMode = appContext.resources.configuration.uiMode
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val config = appContext.resources.configuration
                if (config.densityDpi != lastDensityDpi || config.uiMode != lastUiMode) {
                    lastDensityDpi = config.densityDpi
                    lastUiMode = config.uiMode
                    scheduleRefresh()
                }
            }
        }
        configurationReceiver = receiver
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Work-profile installs/uninstalls only ever surface through LauncherApps callbacks. */
    private fun registerLauncherAppsCallback() {
        val service = launcherApps ?: return
        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) {
                // An update finishing re-installs the package — cancel a pending removal
                // verification so a slow (multi-second) APK install isn't mistaken for
                // an uninstall past the grace window.
                pendingRemovalVerifications.remove(packageName to user)?.cancel()
                scheduleRefresh()
            }
            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                scheduleRefresh()
                // Own-user removals are already handled by the BroadcastReceiver with
                // the precise EXTRA_REPLACING flag — emitting here too would double-fire
                // and, worse, this callback can't distinguish an update's removal.
                if (user == Process.myUserHandle()) return
                // LauncherApps.Callback carries no EXTRA_REPLACING: an app UPDATE also
                // passes through here, and pruning on it would wipe the user's rows on
                // every app update. Verify after a grace period, tracked per
                // package+user so onPackageAdded can cancel it when a big APK's
                // install outlasts the window.
                val serial = userManager?.getSerialNumberForUser(user) ?: 0L
                pendingRemovalVerifications.remove(packageName to user)?.cancel()
                pendingRemovalVerifications[packageName to user] = scope.launch {
                    delay(PACKAGE_REMOVAL_GRACE_MS)
                    pendingRemovalVerifications.remove(packageName to user)
                    // getActivityList is a synchronous Binder call — keep it off Main,
                    // consistent with every other AppWidget/LauncherApps call site.
                    val stillInstalled = withContext(Dispatchers.IO) {
                        runCatching {
                            launcherApps?.getActivityList(packageName, user)?.isNotEmpty() == true
                        }.getOrDefault(true) // unverifiable → keep rows; refresh reconciles
                    }
                    if (!stillInstalled) {
                        _packageEvents.tryEmit(
                            PackageEvent.Removed(
                                packageName = packageName,
                                userSerial = serial,
                                replacing = false,
                            ),
                        )
                    }
                }
            }
            override fun onPackageChanged(packageName: String, user: UserHandle) {
                // A changed package obviously exists — cancel a stale verification too.
                pendingRemovalVerifications.remove(packageName to user)?.cancel()
                scheduleRefresh()
            }
            override fun onPackagesAvailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean,
            ) = scheduleRefresh()

            override fun onPackagesUnavailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean,
            ) = scheduleRefresh()
        }
        runCatching { service.registerCallback(callback, Handler(Looper.getMainLooper())) }
            .onFailure { Logger.w(TAG, "LauncherApps.registerCallback failed", it) }
        launcherAppsCallback = callback
    }

    fun dispose() {
        refreshJob?.cancel()
        pendingRemovalVerifications.values.forEach { it.cancel() }
        pendingRemovalVerifications.clear()
        packageReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        packageReceiver = null
        configurationReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        configurationReceiver = null
        launcherAppsCallback?.let { callback ->
            launcherApps?.let { runCatching { it.unregisterCallback(callback) } }
        }
        launcherAppsCallback = null
    }

    companion object {
        private const val TAG = "AppRepository"
        private const val DEBOUNCE_MS = 400L

        /**
         * LauncherApps.Callback.onPackageRemoved fires during app UPDATES too (it has no
         * EXTRA_REPLACING), so removal events sourced from it are verified after this
         * grace period before pruning anything.
         */
        private const val PACKAGE_REMOVAL_GRACE_MS = 5_000L

        /** Icons render at ≤42dp; 128px covers every density with headroom. */
        private const val ICON_MAX_PX = 128
    }
}
