package com.example.pwalibrary.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    @Query("SELECT * FROM apps ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AppEntity>>

    @Query("SELECT * FROM apps WHERE uuid = :uuid LIMIT 1")
    suspend fun findByUuid(uuid: String): AppEntity?

    @Query("SELECT * FROM apps WHERE manifest_id = :manifestId LIMIT 1")
    suspend fun findByManifestId(manifestId: String): AppEntity?

    @Query("SELECT * FROM apps WHERE manifest_id = :manifestId AND name = :name LIMIT 1")
    suspend fun findByManifestIdAndName(manifestId: String, name: String): AppEntity?

    @Query("SELECT * FROM apps WHERE zip_sha256 = :hash LIMIT 1")
    suspend fun findByZipHash(hash: String): AppEntity?

    @Query("UPDATE apps SET needs_storage_reset = 0 WHERE uuid = :uuid")
    suspend fun clearStorageResetFlag(uuid: String)

    @Insert
    suspend fun insert(app: AppEntity): Long

    @Update
    suspend fun update(app: AppEntity)

    @Delete
    suspend fun delete(app: AppEntity)
}
