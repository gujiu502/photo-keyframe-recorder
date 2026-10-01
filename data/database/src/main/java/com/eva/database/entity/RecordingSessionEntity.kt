package com.eva.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recording_sessions")
data class RecordingSessionEntity(
    @PrimaryKey val sessionId: String,
    val startedAt: Long,
    val status: String = "ACTIVE",
    val audioPath: String? = null,
    val recordingId: Long? = null,
    val positionMs: Long = 0,
    val mimeType: String = "audio/mp4",
    val exportUri: String? = null,
    val fileName: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "'未分類'") val courseName: String = "未分類",
    val accountId: String? = null,
)
