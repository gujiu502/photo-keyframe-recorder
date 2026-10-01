package com.eva.database.dao

import androidx.room.*
import com.eva.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueue(row: CloudBackupEntity)
    @Upsert suspend fun put(row: CloudBackupEntity)
    @Query("SELECT * FROM cloud_backups WHERE sessionId=:id") suspend fun backup(id: String): CloudBackupEntity?
    @Query("SELECT * FROM cloud_backups ORDER BY lastAttemptAt DESC") fun observe(): Flow<List<CloudBackupEntity>>
    @Query("SELECT * FROM cloud_backups") suspend fun all(): List<CloudBackupEntity>
    @Upsert suspend fun putFile(row: CloudFileEntity)
    @Query("SELECT * FROM cloud_files WHERE sessionId=:id") suspend fun files(id: String): List<CloudFileEntity>
    @Query("SELECT * FROM cloud_files WHERE sessionId=:id") fun observeFiles(id: String): Flow<List<CloudFileEntity>>
    @Query("DELETE FROM cloud_files WHERE sessionId=:id") suspend fun removeFiles(id: String)
    @Query("DELETE FROM cloud_backups WHERE sessionId=:id") suspend fun removeBackup(id: String)
}
