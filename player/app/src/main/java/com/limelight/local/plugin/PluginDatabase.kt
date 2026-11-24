package com.limelight.local.plugin

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Installed plugin package metadata persisted in [com.limelight.local.media.WallDatabase].
 */
@Entity(tableName = "plugin_packages")
data class InstalledPluginEntity(
    @PrimaryKey
    val pluginId: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val manifestJson: String,
    val packagePath: String,
    val sourceUrl: String?,
    val repoUrl: String?,
    val sha256: String,
    val enabled: Boolean,
    val needsPermissionReconfirm: Boolean,
    val approvedPermissionsJson: String,
    val installedAt: Long,
    val updatedAt: Long,
)

/**
 * One configured instance of an installed plugin (e.g. "Living Room Jellyfin").
 * [encryptedConfig] holds AES-GCM ciphertext of the JSON configuration map so
 * passwords are never stored in plaintext inside SQLite.
 */
@Entity(
    tableName = "plugin_instances",
    foreignKeys = [
        ForeignKey(
            entity = InstalledPluginEntity::class,
            parentColumns = ["pluginId"],
            childColumns = ["pluginId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["pluginId"])],
)
data class PluginInstanceEntity(
    @PrimaryKey
    val instanceId: String,
    val pluginId: String,
    val displayName: String,
    val encryptedConfig: String,
    val enabled: Boolean,
    val authState: String,
    val authUserJson: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Subscribed third-party or official plugin repository index URL.
 */
@Entity(tableName = "plugin_repositories")
data class PluginRepoEntity(
    @PrimaryKey
    val url: String,
    val name: String,
    val lastSyncedAt: Long,
    val lastIndexJson: String?,
)

@Dao
interface PluginPackageDao {
    @Query("SELECT * FROM plugin_packages ORDER BY installedAt ASC")
    suspend fun listAll(): List<InstalledPluginEntity>

    @Query("SELECT * FROM plugin_packages ORDER BY installedAt ASC")
    fun observeAll(): Flow<List<InstalledPluginEntity>>

    @Query("SELECT * FROM plugin_packages WHERE pluginId = :pluginId")
    suspend fun get(pluginId: String): InstalledPluginEntity?

    @Upsert
    suspend fun upsert(entity: InstalledPluginEntity)

    @Query("UPDATE plugin_packages SET enabled = :enabled, updatedAt = :updatedAt WHERE pluginId = :pluginId")
    suspend fun setEnabled(pluginId: String, enabled: Boolean, updatedAt: Long)

    @Query(
        "UPDATE plugin_packages SET needsPermissionReconfirm = 0, " +
            "approvedPermissionsJson = :approvedPermissionsJson, updatedAt = :updatedAt " +
            "WHERE pluginId = :pluginId",
    )
    suspend fun confirmPermissions(pluginId: String, approvedPermissionsJson: String, updatedAt: Long)

    @Query("DELETE FROM plugin_packages WHERE pluginId = :pluginId")
    suspend fun delete(pluginId: String)
}

@Dao
interface PluginInstanceDao {
    @Query("SELECT * FROM plugin_instances ORDER BY createdAt ASC")
    suspend fun listAll(): List<PluginInstanceEntity>

    @Query("SELECT * FROM plugin_instances ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PluginInstanceEntity>>

    @Query("SELECT * FROM plugin_instances WHERE pluginId = :pluginId ORDER BY createdAt ASC")
    suspend fun listForPlugin(pluginId: String): List<PluginInstanceEntity>

    @Query("SELECT * FROM plugin_instances WHERE instanceId = :instanceId")
    suspend fun get(instanceId: String): PluginInstanceEntity?

    @Upsert
    suspend fun upsert(entity: PluginInstanceEntity)

    @Query(
        "UPDATE plugin_instances SET authState = :authState, authUserJson = :authUserJson, " +
            "updatedAt = :updatedAt WHERE instanceId = :instanceId",
    )
    suspend fun updateAuth(instanceId: String, authState: String, authUserJson: String?, updatedAt: Long)

    @Query("DELETE FROM plugin_instances WHERE instanceId = :instanceId")
    suspend fun delete(instanceId: String)
}

@Dao
interface PluginRepoDao {
    @Query("SELECT * FROM plugin_repositories ORDER BY name ASC")
    suspend fun listAll(): List<PluginRepoEntity>

    @Query("SELECT * FROM plugin_repositories WHERE url = :url")
    suspend fun get(url: String): PluginRepoEntity?

    @Upsert
    suspend fun upsert(entity: PluginRepoEntity)

    @Query("DELETE FROM plugin_repositories WHERE url = :url")
    suspend fun delete(url: String)
}

/**
 * WallDatabase migration from v1 (watch_progress only) to v2 (adds plugin
 * packages, instances, and repository tables).
 */
val WALL_DATABASE_MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `plugin_packages` (
                `pluginId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `version` TEXT NOT NULL,
                `author` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `manifestJson` TEXT NOT NULL,
                `packagePath` TEXT NOT NULL,
                `sourceUrl` TEXT,
                `repoUrl` TEXT,
                `sha256` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `needsPermissionReconfirm` INTEGER NOT NULL,
                `approvedPermissionsJson` TEXT NOT NULL,
                `installedAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`pluginId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `plugin_instances` (
                `instanceId` TEXT NOT NULL,
                `pluginId` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `encryptedConfig` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `authState` TEXT NOT NULL,
                `authUserJson` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`instanceId`),
                FOREIGN KEY(`pluginId`) REFERENCES `plugin_packages`(`pluginId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_plugin_instances_pluginId` ON `plugin_instances` (`pluginId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `plugin_repositories` (
                `url` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `lastSyncedAt` INTEGER NOT NULL,
                `lastIndexJson` TEXT,
                PRIMARY KEY(`url`)
            )
            """.trimIndent(),
        )
    }
}
