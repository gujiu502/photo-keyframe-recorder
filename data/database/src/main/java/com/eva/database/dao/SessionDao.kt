package com.eva.database.dao

import androidx.room.*
import com.eva.database.entity.RecordingSessionEntity
import com.eva.database.entity.TimelineItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM timeline_items WHERE type='PHOTO' AND state IN ('WRITING','READY')") suspend fun photosToValidate(): List<TimelineItemEntity>
    @Insert suspend fun insertSession(session: RecordingSessionEntity)
    @Upsert suspend fun putItem(item: TimelineItemEntity)
    @Query("SELECT * FROM recording_sessions WHERE sessionId=:id") suspend fun session(id: String): RecordingSessionEntity?
    @Query("SELECT * FROM recording_sessions WHERE status != 'COMPLETE' ORDER BY startedAt")
    fun unfinished(): Flow<List<RecordingSessionEntity>>
    @Query("SELECT * FROM recording_sessions WHERE status != 'COMPLETE'")
    suspend fun unfinishedList(): List<RecordingSessionEntity>
    @Query("SELECT * FROM recording_sessions WHERE recordingId=:id") suspend fun forRecording(id: Long): List<RecordingSessionEntity>
    @Query("SELECT * FROM timeline_items WHERE sessionId=:id ORDER BY positionMs,createdAt,id") suspend fun items(id: String): List<TimelineItemEntity>
    @Query("SELECT * FROM timeline_items WHERE sessionId=:id AND state='READY' ORDER BY positionMs,createdAt,id")
    fun observeSession(id: String): Flow<List<TimelineItemEntity>>
    @Query("SELECT * FROM timeline_items WHERE recordingId=:id AND state='READY' ORDER BY positionMs,createdAt,id")
    fun observeRecording(id: Long): Flow<List<TimelineItemEntity>>
    @Query("SELECT * FROM timeline_items WHERE recordingId=:id AND state='READY' ORDER BY positionMs,createdAt,id")
    suspend fun recordingItems(id: Long): List<TimelineItemEntity>
    @Query("UPDATE recording_sessions SET status=:status,positionMs=:position WHERE sessionId=:id") suspend fun state(id: String, status: String, position: Long)
    @Query("UPDATE recording_sessions SET audioPath=:path,mimeType=:mime WHERE sessionId=:id") suspend fun audio(id: String, path: String, mime: String)
    @Query("UPDATE recording_sessions SET exportUri=:uri WHERE sessionId=:id") suspend fun exportUri(id: String, uri: String)
    @Query("UPDATE timeline_items SET state=:state WHERE id=:id") suspend fun itemState(id: String, state: String)
    @Query("UPDATE recording_sessions SET recordingId=:recordingId,status='COMPLETE' WHERE sessionId=:id") suspend fun completeSession(id: String, recordingId: Long)
    @Query("UPDATE timeline_items SET recordingId=:recordingId WHERE sessionId=:id") suspend fun attachItems(id: String, recordingId: Long)
    @Transaction suspend fun complete(id: String, recordingId: Long) { attachItems(id, recordingId); completeSession(id, recordingId) }
    @Query("DELETE FROM recording_sessions WHERE sessionId=:id") suspend fun deleteSession(id: String)
    @Query("SELECT * FROM timeline_items WHERE id=:id") suspend fun item(id: String): TimelineItemEntity?
	@Query("UPDATE recording_sessions SET recordingId=:newId WHERE recordingId=:oldId") suspend fun remapSessions(oldId: Long, newId: Long)
	@Query("UPDATE timeline_items SET recordingId=:newId WHERE recordingId=:oldId") suspend fun remapItems(oldId: Long, newId: Long)
}
