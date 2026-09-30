package com.eva.database

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import com.eva.database.entity.RecordingSessionEntity
import com.eva.database.entity.TimelineItemEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionStore @Inject constructor(@ApplicationContext private val context: Context, val database: RecorderDataBase) {
    val dao get() = database.sessionDao()
    val activeId = MutableStateFlow<String?>(null)
    @Volatile var position: () -> Long = { 0L }
    private val root get() = File(context.filesDir, "keyframes").apply { mkdirs() }

    suspend fun start(): String = database.withTransaction {
        check(activeId.value == null) { "A recording is already active" }
        val id = UUID.randomUUID().toString()
        dao.insertSession(RecordingSessionEntity(id, System.currentTimeMillis()))
        activeId.value = id
        id
    }
    suspend fun bookmark(positionMs: Long) {
        val id = activeId.value ?: return
        dao.putItem(TimelineItemEntity(UUID.randomUUID().toString(), id,
            positionMs = positionMs, type = "BOOKMARK", state = "READY", createdAt = System.currentTimeMillis()))
    }
    suspend fun beginPhoto(positionMs: Long): TimelineItemEntity = withContext(Dispatchers.IO) {
        val sessionId = activeId.value ?: error("Recording has ended")
        val session = dao.session(sessionId) ?: error("Session missing")
        check(session.status in listOf("ACTIVE", "PAUSED")) { "Recording is being saved" }
        check(context.filesDir.usableSpace > 100L * 1024 * 1024) { "Storage is low. Audio has priority; photo was not saved." }
        val id = UUID.randomUUID().toString()
        val dir = File(root, sessionId).apply { check(mkdirs() || isDirectory) }
        val item = TimelineItemEntity(id, sessionId, positionMs = positionMs,
            mediaPath = File(dir, "$id.jpg").absolutePath, createdAt = System.currentTimeMillis())
        dao.putItem(item)
        item
    }
    fun tempFile(item: TimelineItemEntity) = File(item.mediaPath + ".tmp")
    suspend fun finishPhoto(item: TimelineItemEntity): Unit = withContext(Dispatchers.IO) {
        val temp = tempFile(item)
        check(validImage(temp)) { "Camera produced an invalid image" }
        RandomAccessFile(temp, "rw").use { it.fd.sync() }
        check(temp.renameTo(File(requireNotNull(item.mediaPath)))) { "Cannot save photo" }
        database.withTransaction {
            val session = dao.session(item.sessionId) ?: error("Session cancelled")
            check(session.status != "CANCELLED")
            dao.putItem(item.copy(state = "READY", recordingId = session.recordingId))
        }
    }
    suspend fun failPhoto(item: TimelineItemEntity): Unit = withContext(Dispatchers.IO) {
        tempFile(item).delete()
		val session = dao.session(item.sessionId)
		if (session == null || session.status == "CANCELLED") {
			File(requireNotNull(item.mediaPath)).delete()
			return@withContext
		}
        // Preserve a renamed photo when its DB commit failed; startup can recover it.
        if (!validImage(File(requireNotNull(item.mediaPath)))) dao.itemState(item.id, "FAILED")
    }
    suspend fun scanRecovery(): Unit = withContext(Dispatchers.IO) {
        for (item in dao.photosToValidate()) {
            if (item.sessionId == activeId.value) continue
            val session = dao.session(item.sessionId) ?: continue
            val file = File(requireNotNull(item.mediaPath))
            val temp = tempFile(item)
            val ready = validImage(file) || (item.state == "WRITING" && validImage(temp) && temp.renameTo(file))
            dao.putItem(item.copy(state = if (ready) "READY" else "FAILED", recordingId = session.recordingId))
        }
        for (session in dao.unfinishedList()) {
            if (session.sessionId == activeId.value) continue
            dao.state(session.sessionId, "RECOVERY_REQUIRED", session.positionMs)
        }
        // Unreferenced files are kept for inspection, never silently discarded.
    }
    suspend fun discard(id: String): Unit = withContext(Dispatchers.IO) {
        val session = dao.session(id)
        dao.state(id, "CANCELLED", dao.session(id)?.positionMs ?: 0)
        if (session?.recordingId == null) session?.exportUri?.let {
            context.contentResolver.delete(android.net.Uri.parse(it), null, null)
        }
        val dir = File(root, id)
        check(dir.canonicalFile.parentFile == root.canonicalFile)
        if (dir.exists()) check(dir.deleteRecursively()) { "Could not remove photos; retry cleanup" }
        dao.session(id)?.audioPath?.let { path ->
            val file = File(path)
            check(file.canonicalFile.parentFile == File(context.filesDir, "temp_recordings").canonicalFile)
            if (file.exists()) check(file.delete()) { "Could not remove audio" }
        }
        dao.deleteSession(id)
        if (activeId.value == id) activeId.value = null
    }
    suspend fun deleteRecording(id: Long) { for (session in dao.forRecording(id)) discard(session.sessionId) }
	 suspend fun remapRecording(oldId: Long, newId: Long) = database.withTransaction {
		val metadata = database.recordingMetaData().getRecordingMetaDataFromId(oldId)
			?: com.eva.database.entity.RecordingsMetaDataEntity(oldId)
		database.recordingMetaData().updateOrInsertRecordingMetadata(metadata.copy(recordingId = newId))
		val bookmarks = database.recordingBookMarkDao().getBookMarksFromRecordingId(oldId)
		database.recordingBookMarkDao().insertOrUpdateBookmarks(bookmarks.map { it.copy(recordingId = newId) })
		dao.remapSessions(oldId, newId); dao.remapItems(oldId, newId)
		database.recordingMetaData().deleteRecordingMetaDataFromIds(listOf(oldId))
	}
    companion object {
        fun validImage(file: File): Boolean {
            if (!file.isFile || file.length() == 0L) return false
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            return options.outWidth > 0 && options.outHeight > 0
        }
    }
}
