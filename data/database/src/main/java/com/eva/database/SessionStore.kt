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
    val namingRequested = MutableStateFlow(false)
    val transcribing = MutableStateFlow(false)
    @Volatile var position: () -> Long = { 0L }
    suspend fun startTranscription() = database.withTransaction {
        check(activeId.value == null) { "請先停止錄音，再使用本地識別" }
        check(!transcribing.value) { "已有本地識別正在進行" }
        transcribing.value = true
    }
    private val root get() = File(context.filesDir, "keyframes").apply { mkdirs() }

    suspend fun start(requireAccount: Boolean = true): String = database.withTransaction {
        check(!AccountSettings(context).prefs.getBoolean("install_committed", false)) { "正在安裝更新，請稍後開始錄音" }
        if (requireAccount) check(AccountSettings(context).recordingAllowed) { "請先完成 Google 帳號與 Drive 設定，或完成必要更新" }
        check(activeId.value == null) { "已有錄音正在進行" }
        check(!transcribing.value) { "請先停止本地語音識別，再開始錄音" }
        val id = UUID.randomUUID().toString()
        dao.insertSession(RecordingSessionEntity(id, System.currentTimeMillis(), accountId = AccountSettings(context).accountId))
        activeId.value = id
        id
    }
    suspend fun bookmark(positionMs: Long) {
        val id = activeId.value ?: return
        dao.putItem(TimelineItemEntity(UUID.randomUUID().toString(), id,
            positionMs = positionMs, type = "BOOKMARK", state = "READY", createdAt = System.currentTimeMillis()))
    }
    suspend fun beginPhoto(positionMs: Long): TimelineItemEntity = withContext(Dispatchers.IO) {
        val sessionId = activeId.value ?: error("錄音已結束")
        val session = dao.session(sessionId) ?: error("找不到錄音資料")
        check(session.status in listOf("ACTIVE", "PAUSED")) { "錄音正在保存" }
        check(context.filesDir.usableSpace > 100L * 1024 * 1024) { "儲存空間不足，優先保留錄音，照片未保存。" }
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
        check(validImage(temp)) { "相機傳回的照片無效" }
        RandomAccessFile(temp, "rw").use { it.fd.sync() }
        check(temp.renameTo(File(requireNotNull(item.mediaPath)))) { "無法保存照片" }
        database.withTransaction {
            val session = dao.session(item.sessionId) ?: error("錄音已取消")
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
        if (dir.exists()) check(dir.deleteRecursively()) { "無法刪除照片，請重試清理" }
        val cloudMetadata = File(context.filesDir, "cloud-metadata/$id")
        check(cloudMetadata.canonicalFile.parentFile == File(context.filesDir, "cloud-metadata").canonicalFile)
        if (cloudMetadata.exists()) check(cloudMetadata.deleteRecursively()) { "無法清理本地備份中繼資料，請重試" }
        dao.session(id)?.audioPath?.let { path ->
            val file = File(path)
            check(file.canonicalFile.parentFile == File(context.filesDir, "temp_recordings").canonicalFile)
            if (file.exists()) check(file.delete()) { "無法刪除音訊" }
        }
        dao.deleteSession(id)
        if (activeId.value == id) activeId.value = null
    }
    suspend fun deleteRecording(id: Long) = database.withTransaction {
        for (session in dao.forRecording(id)) discard(session.sessionId)
        withContext(Dispatchers.IO) { android.util.AtomicFile(File(context.filesDir, "transcripts/$id.txt")).delete() }
    }
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
