package com.example.pwalibrary.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A folder the user picked for one mini-app, held through SAF's persistable uri
 * permission so it survives reboots.
 *
 * Grants are per app, never shared: one app's directory handle must not become
 * reachable from another zip.
 */
@Entity(tableName = "folder_grants", indices = [Index(value = ["app_uuid"])])
data class FolderGrant(
    /** Opaque id handed to JavaScript; the tree uri itself never leaves native code. */
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "app_uuid")
    val appUuid: String,

    @ColumnInfo(name = "tree_uri")
    val treeUri: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "granted_at")
    val grantedAt: Long
)

@Dao
interface FolderGrantDao {

    @Query("SELECT * FROM folder_grants WHERE app_uuid = :appUuid ORDER BY granted_at DESC")
    suspend fun forApp(appUuid: String): List<FolderGrant>

    @Query("SELECT * FROM folder_grants WHERE app_uuid = :appUuid ORDER BY granted_at DESC")
    fun observeForApp(appUuid: String): Flow<List<FolderGrant>>

    @Query("SELECT * FROM folder_grants WHERE id = :id AND app_uuid = :appUuid LIMIT 1")
    suspend fun find(id: String, appUuid: String): FolderGrant?

    @Query("SELECT * FROM folder_grants WHERE app_uuid = :appUuid AND tree_uri = :treeUri LIMIT 1")
    suspend fun findByTree(appUuid: String, treeUri: String): FolderGrant?

    @Insert
    suspend fun insert(grant: FolderGrant)

    /** Re-picking an existing folder should promote it, not leave it stale. */
    @Query("UPDATE folder_grants SET granted_at = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM folder_grants WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM folder_grants WHERE app_uuid = :appUuid")
    suspend fun deleteForApp(appUuid: String)

    /** Two apps can be granted the same tree; the permission is shared. */
    @Query("SELECT COUNT(*) FROM folder_grants WHERE tree_uri = :treeUri")
    suspend fun countByTree(treeUri: String): Int
}
