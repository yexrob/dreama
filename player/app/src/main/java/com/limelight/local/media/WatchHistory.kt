package com.limelight.local.media

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.limelight.LimeLog
import com.limelight.local.plugin.InstalledPluginEntity
import com.limelight.local.plugin.PluginInstanceDao
import com.limelight.local.plugin.PluginInstanceEntity
import com.limelight.local.plugin.PluginPackageDao
import com.limelight.local.plugin.PluginRepoDao
import com.limelight.local.plugin.PluginRepoEntity
import com.limelight.local.plugin.WALL_DATABASE_MIGRATION_1_2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Where each item was left, keyed by the source and the source's own item id,
 * so "continue watching" can mix every source and outlives any one of them.
 * The title is kept only so a row can still be drawn while its source is away.
 */
@Entity(tableName = "watch_progress", primaryKeys = ["sourceId", "itemId"])
data class WatchProgress(
    val sourceId: String,
    val itemId: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val parentId: String? = null,
) {
    /** Far enough in to be worth resuming, and not so far that it is done. */
    val resumable: Boolean
        get() = positionMs >= MIN_RESUME_MS && durationMs > 0 &&
            durationMs - positionMs >= maxOf(MIN_LEFT_MS, durationMs / 20)

    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)

    companion object {
        const val MIN_RESUME_MS = 30_000L
        const val MIN_LEFT_MS = 60_000L
    }
}

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<WatchProgress>>

    @Query("SELECT * FROM watch_progress WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun get(sourceId: String, itemId: String): WatchProgress?

    @Query("SELECT * FROM watch_progress WHERE sourceId = :sourceId AND itemId = :itemId")
    fun observe(sourceId: String, itemId: String): Flow<WatchProgress?>

    @Upsert
    suspend fun upsert(progress: WatchProgress)

    @Query("DELETE FROM watch_progress WHERE sourceId = :sourceId AND itemId = :itemId")
    suspend fun delete(sourceId: String, itemId: String)
}

@Database(
    entities = [
        WatchProgress::class,
        InstalledPluginEntity::class,
        PluginInstanceEntity::class,
        PluginRepoEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class WallDatabase : RoomDatabase() {
    abstract fun progress(): WatchProgressDao
    abstract fun pluginPackages(): PluginPackageDao
    abstract fun pluginInstances(): PluginInstanceDao
    abstract fun pluginRepos(): PluginRepoDao

    companion object {
        val MIGRATION_1_2 = WALL_DATABASE_MIGRATION_1_2

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `watch_progress` ADD COLUMN `parentId` TEXT")
            }
        }

        @Volatile
        private var instance: WallDatabase? = null

        fun get(context: Context): WallDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WallDatabase::class.java, "wall.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}

/**
 * The player's way in: LocalPlayerXR is Java and calls this from the main
 * thread as it pauses or leaves, so the write is pushed off it here.
 */
object WatchHistory {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    @JvmOverloads
    fun save(
        context: Context,
        sourceId: String,
        itemId: String,
        title: String?,
        positionMs: Long,
        durationMs: Long,
        parentId: String? = null,
    ) {
        if (durationMs <= 0) {
            return
        }
        val app = context.applicationContext
        val cleanParentId = parentId?.trim()?.takeIf { it.isNotEmpty() }
        io.launch {
            try {
                WallDatabase.get(app).progress().upsert(
                    WatchProgress(
                        sourceId = sourceId,
                        itemId = itemId,
                        title = title ?: itemId,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        updatedAt = System.currentTimeMillis(),
                        parentId = cleanParentId,
                    ),
                )
            } catch (e: Exception) {
                LimeLog.warning("depth3d: saving progress for $sourceId/$itemId failed: $e")
            }
        }
    }
}
