package com.eva.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cloud_backups")
data class CloudBackupEntity(
    @PrimaryKey val sessionId: String,
    val accountId: String,
    val email: String,
    val state: String = "QUEUED",
    val folderId: String? = null,
    val lastAttemptAt: Long = 0,
    val lastSuccessAt: Long = 0,
    val errorCode: String? = null,
)

@Entity(tableName = "cloud_files", primaryKeys = ["sessionId", "relativePath"])
data class CloudFileEntity(
    val sessionId: String,
    val relativePath: String,
    val localPath: String,
    val contentHash: String,
    val size: Long,
    val mimeType: String,
    val driveFileId: String? = null,
    val resumableUri: String? = null,
    val uploadedBytes: Long = 0,
    val state: String = "PENDING",
)
