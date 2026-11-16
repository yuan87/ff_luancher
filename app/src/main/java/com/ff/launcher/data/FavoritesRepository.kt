package com.ff.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.UserManager
import android.provider.MediaStore
import com.ff.launcher.util.Logger
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.ff.launcher.data.db.FavoriteAppEntity
import com.ff.launcher.data.db.LauncherDatabase
import com.ff.launcher.data.db.moveFavorite
import com.ff.launcher.data.db.toggleFavorite
import com.ff.launcher.data.db.updateFavoriteOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Legacy Phase 1 favorites store. Kept as the source for the one-time migration into
 * Room AND as the persistence for the "default favorites seeded" flag.
 */
private val Context.legacyFavoritesDataStore by preferencesDataStore(name = "favorites_prefs")

/**
 * Room-backed favorites (Phase 2 / M1). Owns:
 * - the [favorites] stream consumed by the home screen,
 * - toggling via the composite component key (personal/work twins stay independent),
 * - pruning favorites of uninstalled packages (driven by AppRepository package events),
 * - the one-time lift of legacy DataStore favorites into the database.
 */
class FavoritesRepository(
    context: Context,
    private val database: LauncherDatabase,
) {
    private val appContext = context.applicationContext
    private val userManager = appContext.getSystemService(UserManager::class.java)
    private val legacyDataStore = appContext.legacyFavoritesDataStore
    private val legacyMigrationDone = AtomicBoolean(false)
    private val defaultSeedAttempted = AtomicBoolean(false)

    val favoriteKeysOrdered: Flow<List<String>> =
        database.favoriteAppDao().observeAll().map { rows -> rows.map { it.componentKey } }

    val favorites: Flow<Set<String>> =
        favoriteKeysOrdered.map { it.toSet() }

    suspend fun toggle(componentKey: String) {
        database.toggleFavorite(componentKey, System.currentTimeMillis())
    }

    suspend fun moveFavorite(componentKey: String, delta: Int) {
        database.moveFavorite(componentKey, delta)
    }

    suspend fun updateOrder(orderedKeys: List<String>) {
        database.updateFavoriteOrder(orderedKeys)
    }

    /** Drops every favorite belonging to [packageName] on [userSerial] (app uninstalled). */
    suspend fun removePackage(packageName: String, userSerial: Long) {
        database.favoriteAppDao().deleteForPackage(packageName, userSerial.toString())
    }

    /**
     * One-time lift of the Phase 1 DataStore favorites into Room. Early Phase 1 builds
     * stored BARE component keys (`ComponentName` without the `#userSerial` suffix), so
     * keys lacking the separator are normalized with the calling user's serial — a raw
     * copy would never match any AppInfo again. The legacy key is cleared afterwards so
     * the migration never re-runs meaningfully.
     */
    suspend fun migrateFromLegacyIfNeeded() {
        if (!legacyMigrationDone.compareAndSet(false, true)) return
        val legacy = runCatching {
            legacyDataStore.data.first()[KEY_FAVORITES] ?: emptySet()
        }.getOrDefault(emptySet())
        if (legacy.isEmpty()) return
        val defaultSerial =
            userManager?.getSerialNumberForUser(Process.myUserHandle()) ?: 0L
        runCatching {
            database.withTransaction {
                val dao = database.favoriteAppDao()
                val now = System.currentTimeMillis()
                legacy.forEachIndexed { index, rawKey ->
                    val normalizedKey = if (AppInfo.PROFILE_KEY_SEPARATOR in rawKey) {
                        rawKey
                    } else {
                        rawKey + AppInfo.PROFILE_KEY_SEPARATOR + defaultSerial
                    }
                    if (dao.get(normalizedKey) == null) {
                        dao.upsert(
                            FavoriteAppEntity(
                                componentKey = normalizedKey,
                                addedAt = now,
                                orderIndex = index,
                            ),
                        )
                    }
                }
            }
            legacyDataStore.edit { it.remove(KEY_FAVORITES) }
        }.onFailure { Logger.w(TAG, "Legacy favorites migration failed", it) }
    }

    /**
     * First-install out-of-the-box: when `installedApps` first loads non-empty, auto-detect
     * the device's dialer, browser, contacts, gallery, email and camera and pin up to 6 of
     * them so the home screen is immediately useful. Only fires if the favorites table is
     * empty AND the "seeded" DataStore flag is unset.
     */
    suspend fun seedDefaultFavoritesIfNeeded(installedApps: List<AppInfo>) {
        if (installedApps.isEmpty()) return
        if (!defaultSeedAttempted.compareAndSet(false, true)) return

        val seeded = runCatching {
            legacyDataStore.data.first()[KEY_DEFAULT_SEEDED] ?: false
        }.getOrDefault(false)
        if (seeded) return

        // User already has favorites (e.g. from migration) → mark seeded and skip.
        if (favorites.first().isNotEmpty()) {
            runCatching { legacyDataStore.edit { it[KEY_DEFAULT_SEEDED] = true } }
            return
        }

        val pm = appContext.packageManager
        val ownApps = installedApps.filter { it.userHandle == Process.myUserHandle() }
        val byPackage = ownApps.groupBy { it.packageName }

        fun resolvePkg(intent: Intent): String? = runCatching {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()

        // Probe the device's default handlers + known package names, in priority order.
        val candidatePackages = linkedSetOf<String>().apply {
            // 1. Phone / Dialer
            resolvePkg(Intent(Intent.ACTION_DIAL))?.let(::add)
            addAll(listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"))
            // 2. Browser (Chrome / system browser)
            add("com.android.chrome")
            resolvePkg(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))?.let(::add)
            // 3. Contacts
            resolvePkg(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS))?.let(::add)
            addAll(listOf("com.google.android.contacts", "com.samsung.android.app.contacts", "com.android.contacts"))
            // 4. Gallery (Google Photos / system gallery)
            add("com.google.android.apps.photos")
            resolvePkg(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY))?.let(::add)
            // 5. Email (Gmail / system email)
            add("com.google.android.gm")
            resolvePkg(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL))?.let(::add)
            // 6. Camera
            resolvePkg(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))?.let(::add)
        }

        // Match candidates against installed apps in priority order, take up to 6.
        val toSeed = candidatePackages
            .flatMap { pkg -> byPackage[pkg].orEmpty() }
            .distinctBy { it.componentKey }
            .take(6)

        if (toSeed.isEmpty()) {
            runCatching { legacyDataStore.edit { it[KEY_DEFAULT_SEEDED] = true } }
            return
        }

        runCatching {
            database.withTransaction {
                val dao = database.favoriteAppDao()
                val now = System.currentTimeMillis()
                toSeed.forEachIndexed { index, app ->
                    dao.upsert(
                        FavoriteAppEntity(
                            componentKey = app.componentKey,
                            addedAt = now + index,
                            orderIndex = index,
                        ),
                    )
                }
            }
            legacyDataStore.edit { it[KEY_DEFAULT_SEEDED] = true }
            Logger.i(TAG) { "Seeded ${toSeed.size} default favorites" }
        }.onFailure { Logger.w(TAG, "Default favorites seeding failed", it) }
    }

    companion object {
        private const val TAG = "FavoritesRepository"
        private val KEY_FAVORITES = stringSetPreferencesKey("favorite_component_keys")
        private val KEY_DEFAULT_SEEDED = booleanPreferencesKey("default_favorites_seeded")
    }
}
