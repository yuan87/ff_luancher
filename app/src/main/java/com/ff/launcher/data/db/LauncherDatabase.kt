package com.ff.launcher.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Favorites keyed by the composite identity from [com.ff.launcher.data.AppInfo]
 * (`ComponentName#userSerial`), so personal/work-profile twins stay independent.
 */
@Entity(tableName = "favorite_apps")
data class FavoriteAppEntity(
    @PrimaryKey val componentKey: String,
    val addedAt: Long,
    val orderIndex: Int = 0,
)

@Entity(tableName = "hidden_apps")
data class HiddenAppEntity(
    @PrimaryKey val componentKey: String,
    val hiddenAt: Long,
)

/** Fixed widget slots; [appWidgetId] == null means the slot is empty. */
@Entity(tableName = "widget_slots")
data class WidgetSlotEntity(
    @PrimaryKey val slotId: Int,
    val appWidgetId: Int?,
    val heightWeight: Float,
)

@Dao
interface FavoriteAppDao {
    @Query("SELECT * FROM favorite_apps ORDER BY orderIndex ASC, addedAt ASC")
    fun observeAll(): Flow<List<FavoriteAppEntity>>

    @Query("SELECT * FROM favorite_apps ORDER BY orderIndex ASC, addedAt ASC")
    suspend fun getAll(): List<FavoriteAppEntity>

    @Query("SELECT * FROM favorite_apps WHERE componentKey = :componentKey")
    suspend fun get(componentKey: String): FavoriteAppEntity?

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM favorite_apps")
    suspend fun getMaxOrderIndex(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteAppEntity)

    @Query("UPDATE favorite_apps SET orderIndex = :orderIndex WHERE componentKey = :componentKey")
    suspend fun updateOrderIndex(componentKey: String, orderIndex: Int)

    @Query("DELETE FROM favorite_apps WHERE componentKey = :componentKey")
    suspend fun delete(componentKey: String)

    /**
     * Uninstalled-app cleanup, scoped to ONE user: the composite key is
     * `packageName/ClassName#userSerial`. GLOB (not LIKE) so a `_` inside a package
     * name is a literal, not SQLite's single-char wildcard — otherwise uninstalling
     * `com.foo_bar.app` would also delete `com.fooxbar.app`'s rows.
     */
    @Query(
        "DELETE FROM favorite_apps WHERE componentKey GLOB :packageName || '/*#' || :userSerial",
    )
    suspend fun deleteForPackage(packageName: String, userSerial: String)
}

@Dao
interface HiddenAppDao {
    @Query("SELECT * FROM hidden_apps")
    fun observeAll(): Flow<List<HiddenAppEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HiddenAppEntity)

    @Query("DELETE FROM hidden_apps WHERE componentKey = :componentKey")
    suspend fun delete(componentKey: String)

    /** Same GLOB-based user-scoped cleanup rule as [FavoriteAppDao.deleteForPackage]. */
    @Query(
        "DELETE FROM hidden_apps WHERE componentKey GLOB :packageName || '/*#' || :userSerial",
    )
    suspend fun deleteForPackage(packageName: String, userSerial: String)
}

@Dao
interface WidgetSlotDao {
    @Query("SELECT * FROM widget_slots ORDER BY slotId ASC")
    fun observeAll(): Flow<List<WidgetSlotEntity>>

    @Query("SELECT * FROM widget_slots ORDER BY slotId ASC")
    suspend fun getAll(): List<WidgetSlotEntity>

    @Query("SELECT * FROM widget_slots WHERE slotId = :slotId")
    suspend fun getById(slotId: Int): WidgetSlotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(slot: WidgetSlotEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureDefaults(slots: List<WidgetSlotEntity>)

    @Query("UPDATE widget_slots SET appWidgetId = :appWidgetId WHERE slotId = :slotId")
    suspend fun bindWidget(slotId: Int, appWidgetId: Int)

    @Query("UPDATE widget_slots SET appWidgetId = NULL WHERE slotId = :slotId")
    suspend fun clearWidget(slotId: Int)

    @Query("UPDATE widget_slots SET heightWeight = :heightWeight WHERE slotId = :slotId")
    suspend fun updateHeightWeight(slotId: Int, heightWeight: Float)
}

@Entity(tableName = "recent_apps")
data class RecentAppEntity(
    @PrimaryKey val componentKey: String,
    val lastLaunchedAt: Long,
)

@Dao
interface RecentAppDao {
    @Query("SELECT * FROM recent_apps ORDER BY lastLaunchedAt DESC LIMIT 10")
    fun observeRecent(): Flow<List<RecentAppEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordLaunch(entity: RecentAppEntity)

    @Query("DELETE FROM recent_apps WHERE componentKey = :componentKey")
    suspend fun delete(componentKey: String)
}

@Database(
    entities = [FavoriteAppEntity::class, HiddenAppEntity::class, WidgetSlotEntity::class, RecentAppEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun favoriteAppDao(): FavoriteAppDao
    abstract fun hiddenAppDao(): HiddenAppDao
    abstract fun widgetSlotDao(): WidgetSlotDao
    abstract fun recentAppDao(): RecentAppDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_apps ADD COLUMN orderIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS recent_apps (componentKey TEXT PRIMARY KEY NOT NULL, lastLaunchedAt INTEGER NOT NULL)")
            }
        }

        @Volatile
        private var instance: LauncherDatabase? = null

        fun get(context: Context): LauncherDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LauncherDatabase::class.java,
                    "launcher.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { instance = it }
            }
    }
}

/**
 * Consistent absolute height fractions of available screen height:
 * - Small: 25% (0.25f) — max 4 small widgets fit (4 * 25% = 100%)
 * - Medium: 27.5% (0.275f) — max 3 medium widgets fit (3 * 27.5% = 82.5%)
 * - Large: 30% (0.30f) — max 3 large widgets fit (3 * 30% = 90%)
 */
const val SLOT_FRACTION_SMALL = 0.25f
const val SLOT_FRACTION_MEDIUM = 0.275f
const val SLOT_FRACTION_LARGE = 0.30f

val SLOT_WEIGHT_SMALL = SLOT_FRACTION_SMALL
val SLOT_WEIGHT_MEDIUM = SLOT_FRACTION_MEDIUM
val SLOT_WEIGHT_LARGE = SLOT_FRACTION_LARGE

/**
 * Snaps an arbitrary [weight] value to the nearest canonical slot fraction:
 * [SLOT_FRACTION_SMALL] (25%), [SLOT_FRACTION_MEDIUM] (27.5%), or [SLOT_FRACTION_LARGE] (30%).
 *
 * Decision boundaries:
 * - weight ≤ 0.26  →  SMALL  (25%)
 * - 0.26 < weight < 0.29  →  MEDIUM (27.5%)
 * - weight ≥ 0.29  →  LARGE  (30%)
 *
 * Legacy values > 1.0 (from the Phase 1 integer-weight scheme) are mapped to SMALL.
 */
fun normalizeSlotFraction(weight: Float): Float = when {
    weight > 1f -> SLOT_FRACTION_SMALL
    weight >= 0.29f -> SLOT_FRACTION_LARGE
    weight > 0.26f -> SLOT_FRACTION_MEDIUM
    else -> SLOT_FRACTION_SMALL
}

/** Slot 0 is reserved for the primary Home Screen widget (or default smart clock when null). */
const val HOME_WIDGET_SLOT_ID = 0

val DEFAULT_WIDGET_SLOTS = listOf(
    WidgetSlotEntity(slotId = HOME_WIDGET_SLOT_ID, appWidgetId = null, heightWeight = 1f),
    WidgetSlotEntity(slotId = 1, appWidgetId = null, heightWeight = SLOT_FRACTION_SMALL),
    WidgetSlotEntity(slotId = 2, appWidgetId = null, heightWeight = SLOT_FRACTION_SMALL),
    WidgetSlotEntity(slotId = 3, appWidgetId = null, heightWeight = SLOT_FRACTION_SMALL),
    WidgetSlotEntity(slotId = 4, appWidgetId = null, heightWeight = SLOT_FRACTION_SMALL),
)

/** Transactional toggle helper shared by the repositories. */
suspend inline fun LauncherDatabase.toggleFavorite(componentKey: String, now: Long) {
    withTransaction {
        val dao = favoriteAppDao()
        if (dao.get(componentKey) == null) {
            val nextOrder = dao.getMaxOrderIndex() + 1
            dao.upsert(FavoriteAppEntity(componentKey = componentKey, addedAt = now, orderIndex = nextOrder))
        } else {
            dao.delete(componentKey)
        }
    }
}

/** Swaps or moves a favorite by [delta] (-1 = up, +1 = down) and re-indexes all rows. */
suspend fun LauncherDatabase.moveFavorite(componentKey: String, delta: Int) {
    withTransaction {
        val dao = favoriteAppDao()
        val all = dao.getAll().toMutableList()
        val currentIndex = all.indexOfFirst { it.componentKey == componentKey }
        if (currentIndex == -1) return@withTransaction
        val targetIndex = (currentIndex + delta).coerceIn(0, all.size - 1)
        if (currentIndex == targetIndex) return@withTransaction
        val item = all.removeAt(currentIndex)
        all.add(targetIndex, item)
        all.forEachIndexed { index, entity ->
            dao.updateOrderIndex(entity.componentKey, index)
        }
    }
}

/** Rewrites the entire sequence of favorites based on [orderedKeys]. */
suspend fun LauncherDatabase.updateFavoriteOrder(orderedKeys: List<String>) {
    withTransaction {
        val dao = favoriteAppDao()
        orderedKeys.forEachIndexed { index, key ->
            dao.updateOrderIndex(key, index)
        }
    }
}
