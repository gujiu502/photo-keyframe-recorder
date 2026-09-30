package com.eva.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "timeline_items", foreignKeys = [ForeignKey(
    entity = RecordingSessionEntity::class, parentColumns = ["sessionId"],
    childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("sessionId"), Index("recordingId")])
data class TimelineItemEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val recordingId: Long? = null,
    val positionMs: Long,
    val type: String = "PHOTO",
    val mediaPath: String? = null,
    val text: String = "",
    val state: String = "WRITING",
    val createdAt: Long,
)
