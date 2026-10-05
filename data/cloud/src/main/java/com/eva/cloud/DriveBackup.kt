package com.eva.cloud

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.work.*
import androidx.room.withTransaction
import com.eva.database.*
import com.eva.database.entity.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveBackup @Inject constructor(@ApplicationContext private val context: Context, private val db: RecorderDataBase, private val exporter: LectureExporter) {
    // ponytail: serialize Drive mutations; use per-session locks if backup throughput warrants it.
    private val remoteLock = Mutex()
    private val settings = AccountSettings(context)
    private val dao get() = db.cloudDao()
    suspend fun ensureRoot(token: String): String = withContext(Dispatchers.IO) {
        val id = DriveClient(token, context.getSharedPreferences("drive_ids_${settings.accountId}", Context.MODE_PRIVATE)).ensure("課程錄音", null, mapOf("ownerApp" to "PhotoKeyframeRecorder", "schema" to "lecture-root-v1"), true)
        settings.prefs.edit().putString("root_${settings.accountId}", id).apply()
        id
    }
    suspend fun reconcile() {
        if (!settings.configured) return
        for (session in db.sessionDao().completedList()) {
            if (session.accountId != null && session.accountId != settings.accountId) continue
            dao.enqueue(CloudBackupEntity(session.sessionId, requireNotNull(settings.accountId), requireNotNull(settings.email)))
            val row = dao.backup(session.sessionId) ?: continue
            if (row.accountId == settings.accountId && row.state in setOf("QUEUED", "UPLOADING", "PARTIAL", "FAILED_RETRYABLE")) enqueue(session.sessionId)
        }
    }
    suspend fun onAuthorizationGranted() {
        for (row in dao.all().filter { it.accountId == settings.accountId && it.state in setOf("AUTH_REQUIRED", "DELETE_AUTH_REQUIRED") }) retry(row.sessionId)
        reconcile()
        checkOnLaunch()
    }
    fun checkOnLaunch() {
        if (!settings.configured) return
        WorkManager.getInstance(context).enqueueUniqueWork("drive-backup-check", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DriveCheckWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    suspend fun checkCompleted() = withContext(Dispatchers.IO) {
        if (!settings.configured) return@withContext
        val accountId = requireNotNull(settings.accountId)
        if (dao.all().none { it.accountId == accountId && it.state == "COMPLETE" }) return@withContext
        val token = GoogleAccount(context).token(requireNotNull(settings.email))
        try { checkCompleted(DriveClient(token), accountId) }
        catch (e: DriveHttpError) {
            if (e.code == 401) runCatching { GoogleAccount(context).clearToken(token) }
            throw e
        }
    }
    internal suspend fun checkCompleted(client: DriveClient, accountId: String,
        queueRepair: (String) -> Unit = { enqueue(it, ExistingWorkPolicy.APPEND_OR_REPLACE) }) = remoteLock.withLock {
        for (row in dao.all().filter { it.accountId == accountId && it.state == "COMPLETE" }) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            // Check metadata only. A network/authentication failure must never imply deletion.
            fun exists(id: String?): Boolean {
                if (id == null) return false
                return try { !client.metadata(id).optBoolean("trashed") }
                catch (e: DriveHttpError) { if (e.code == 404) false else throw e }
            }
            val files = dao.files(row.sessionId)
            val intact = exists(row.folderId) && files.isNotEmpty() && files.all { file ->
                val id = file.driveFileId
                if (id == null) false else try {
                    val remote = client.metadata(id)
                    !remote.optBoolean("trashed") && remote.optLong("size", -1) == file.size &&
                        remote.optString("sha256Checksum") == file.contentHash
                } catch (e: DriveHttpError) { if (e.code == 404) false else throw e }
            }
            if (intact) continue
            val queued = db.withTransaction {
                val current = dao.backup(row.sessionId)
                val session = db.sessionDao().session(row.sessionId)
                // Respect a deletion requested while the remote check was running.
                if (current?.state != "COMPLETE" || current.accountId != accountId || session?.status != "COMPLETE") false
                else {
                    dao.put(current.copy(state = "QUEUED", errorCode = "雲端備份已遺失，正在重新備份"))
                    true
                }
            }
            if (queued) queueRepair(row.sessionId)
        }
    }
    fun enqueue(id: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        WorkManager.getInstance(context).enqueueUniqueWork("drive-upload-$id", policy,
            OneTimeWorkRequestBuilder<DriveUploadWorker>().setInputData(workDataOf("session_id" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    suspend fun retry(id: String) {
        val row = dao.backup(id) ?: return
        check(row.accountId == settings.accountId) { "請登入這堂課綁定的原 Google 帳號" }
        if (row.state.startsWith("DELETE_")) { enqueueDeletion(row.copy(state = "DELETE_QUEUED", errorCode = null)); return }
        dao.put(row.copy(state = "QUEUED", errorCode = null)); enqueue(id)
    }
    private fun open(path: String) = if (path.startsWith("content:")) requireNotNull(context.contentResolver.openInputStream(Uri.parse(path))) else File(path).inputStream()
    private fun length(path: String): Long = if (path.startsWith("content:")) context.contentResolver.openFileDescriptor(Uri.parse(path), "r")!!.use { it.statSize } else File(path).length()
    suspend fun upload(id: String): Unit = withContext(Dispatchers.IO) { remoteLock.withLock {
        var row = dao.backup(id) ?: return@withContext
        if (row.state == "DELETED" || row.state.startsWith("DELETE_")) return@withContext
        check(settings.agreementAccepted && row.accountId == settings.accountId) { "請登入這堂課綁定的原 Google 帳號" }
        val session = db.sessionDao().session(id) ?: error("本地錄音已刪除")
        check(session.status == "COMPLETE")
        if (db.sessionDao().items(id).any { it.state == "WRITING" }) throw java.io.IOException("照片仍在保存，稍後重試")
        val token = GoogleAccount(context).token(row.email)
        val client = DriveClient(token, context.getSharedPreferences("drive_ids_${row.accountId}", Context.MODE_PRIVATE))
        try {
        row = row.copy(state = "UPLOADING", lastAttemptAt = System.currentTimeMillis(), errorCode = null); dao.put(row)
        val root = ensureRoot(token)
        val courseKey = sha256 { session.courseName.byteInputStream() }
        val course = client.ensure(session.courseName, root, mapOf("ownerApp" to "PhotoKeyframeRecorder", "rootId" to root, "courseKey" to courseKey), true)
        settings.prefs.edit().putString("course_${row.accountId}_$courseKey", course).apply()
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(session.startedAt))
        val folder = client.ensure(date, course, mapOf("ownerApp" to "PhotoKeyframeRecorder", "sessionId" to id, "schema" to "lecture-session-v1"), true)
        row = row.copy(folderId = folder); dao.put(row)
        val contents = exporter.contents(session.recordingId, id, session.fileName ?: date)
        val dir = File(context.filesDir, "cloud-metadata/$id").apply { mkdirs() }
        fun text(name: String, value: String): Pair<String, String> {
            val file = File(dir, name)
            if (!file.exists() || file.readText() != value) { val tmp = File(dir, "$name.tmp"); tmp.writeText(value); check(tmp.renameTo(file)) }
            return name to file.absolutePath
        }
        val audio = session.exportUri ?: session.audioPath ?: error("找不到音訊檔案")
        val audioName = "recording.${contents.audioName.substringAfterLast('.')}"
        contents.manifest.put("audioPath", audioName)
        val sources = listOf(text("timeline.json", contents.timeline.toString(2))) +
            contents.photos.map { it.first to it.second.absolutePath } + listOf(audioName to audio, text("lecture.md", contents.markdown))
        val files = sources.map { (relative, path) ->
            val old = dao.files(id).firstOrNull { it.relativePath == relative }
            val size = runCatching { length(path) }.getOrDefault(0)
            if (size <= 0) {
                dao.putFile(CloudFileEntity(id, relative, path, old?.contentHash ?: "", 0, old?.mimeType ?: "application/octet-stream", state = "MISSING_LOCAL"))
                error("本地檔案遺失或空白：$relative")
            }
            val hash = sha256 { open(path) }
            if (old?.contentHash == hash) old else CloudFileEntity(id, relative, path, hash, size,
                when { relative.endsWith(".json") -> "application/json"; relative.endsWith(".md") -> "text/markdown"; relative.endsWith(".jpg") -> "image/jpeg"; else -> session.mimeType }, driveFileId = old?.driveFileId).also { dao.putFile(it) }
        }
        val manifest = contents.manifest.put("backupState", "UPLOADING").put("accountId", row.accountId)
            .put("files", JSONArray(files.map { JSONObject().put("relativePath", it.relativePath).put("sha256", it.contentHash).put("size", it.size) }))
        // Keep this timestamp stable across retries; unchanged payloads retain their upload session.
        manifest.put("exportedAt", session.startedAt)
        suspend fun uploadManifest() {
            val (relative, path) = text("manifest.json", manifest.toString(2))
            val old = dao.files(id).firstOrNull { it.relativePath == relative }
            val hash = sha256 { open(path) }
            val file = if (old?.contentHash == hash) old else CloudFileEntity(id, relative, path, hash, length(path), "application/json", driveFileId = old?.driveFileId)
            uploadFile(client, folder, file)
        }
        uploadManifest()
        for (file in files) {
            val parent = if (file.relativePath.startsWith("keyframes/")) client.ensure("keyframes", folder,
                mapOf("ownerApp" to "PhotoKeyframeRecorder", "sessionId" to id, "schema" to "keyframes-v1"), true)
            else folder
            uploadFile(client, parent, file)
        }
        manifest.put("backupState", "COMPLETE"); uploadManifest()
        dao.put(row.copy(state = "COMPLETE", lastSuccessAt = System.currentTimeMillis()))
        } catch (e: DriveHttpError) {
            if (e.code == 401) runCatching { GoogleAccount(context).clearToken(token) }
            throw e
        }
    } }
    suspend fun queueCloudDeletion(recordingIds: List<Long>) {
        val rows = recordingIds.flatMap { db.sessionDao().forRecording(it) }.mapNotNull { dao.backup(it.sessionId) }
        check(rows.all { it.accountId == settings.accountId }) { "請登入這堂課綁定的原 Google 帳號" }
        for (row in rows) {
            enqueueDeletion(row.copy(state = "DELETE_QUEUED"))
        }
    }
    private suspend fun enqueueDeletion(row: CloudBackupEntity) {
        dao.put(row)
        WorkManager.getInstance(context).enqueueUniqueWork("drive-upload-${row.sessionId}", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DriveDeleteWorker>().setInputData(workDataOf("session_id" to row.sessionId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    suspend fun deleteCloud(id: String): Unit = withContext(Dispatchers.IO) { remoteLock.withLock {
        val row = dao.backup(id) ?: return@withContext
        check(row.accountId == settings.accountId) { "請登入這堂課綁定的原 Google 帳號" }
        val client = DriveClient(GoogleAccount(context).token(row.email))
        val folder = row.folderId ?: client.find(mapOf("ownerApp" to "PhotoKeyframeRecorder", "sessionId" to id, "schema" to "lecture-session-v1"), true)
        if (folder != null) try { client.delete(folder) } catch (e: DriveHttpError) { if (e.code != 404) throw e }
        db.withTransaction {
            dao.removeFiles(id)
            dao.put(row.copy(state = "DELETED", folderId = null, errorCode = null))
        }
    } }
    internal suspend fun uploadFile(client: DriveClient, parent: String, original: CloudFileEntity) {
        var row = original
        val properties = mapOf("ownerApp" to "PhotoKeyframeRecorder", "sessionId" to row.sessionId, "relativePath" to row.relativePath)
        var fileId = row.driveFileId
        if (fileId != null) try { if (client.metadata(fileId).optBoolean("trashed")) fileId = null } catch (e: DriveHttpError) { if (e.code == 404) fileId = null else throw e }
        if (fileId == null) fileId = client.ensure(row.relativePath.substringAfterLast('/'), parent, properties, false)
        row = if (fileId == row.driveFileId) row else row.copy(resumableUri = null, uploadedBytes = 0)
        row = row.copy(driveFileId = fileId); dao.putFile(row)
        fun verified(): Boolean { val remote = client.metadata(requireNotNull(fileId)); return !remote.optBoolean("trashed") && remote.optLong("size", -1) == row.size && remote.optString("sha256Checksum") == row.contentHash }
        if (verified()) { dao.putFile(row.copy(state = "COMPLETE", uploadedBytes = row.size, resumableUri = null)); return }
        row = row.copy(state = "UPLOADING"); dao.putFile(row)
        var url = row.resumableUri
        var offset = 0L
        if (url != null) {
            try {
                val status = client.call(url, "PUT", ByteArray(0), mapOf("Content-Range" to "bytes */${row.size}"))
                if (status.code in 200..299 && verified()) { dao.putFile(row.copy(state = "COMPLETE", uploadedBytes = row.size, resumableUri = null)); return }
                check(status.code == 308); offset = uploadedOffset(status.range, row.size)
            } catch (e: DriveHttpError) { if (e.code in setOf(404, 410)) url = null else throw e }
        }
        if (url == null) { url = client.initiate(fileId, row.size, row.mimeType); row = row.copy(resumableUri = url, uploadedBytes = 0); dao.putFile(row) }
        val fd = if (row.localPath.startsWith("content:")) requireNotNull(context.contentResolver.openFileDescriptor(Uri.parse(row.localPath), "r")) else ParcelFileDescriptor.open(File(row.localPath), ParcelFileDescriptor.MODE_READ_ONLY)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            input.channel.position(offset)
            val buffer = ByteArray(1024 * 1024) // 256 KiB multiple; no whole-audio buffering.
            while (offset < row.size) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val needed = minOf(buffer.size.toLong(), row.size - offset).toInt()
                var read = 0
                while (read < needed) { val n = input.read(buffer, read, needed - read); check(n > 0) { "本地音訊讀取中斷" }; read += n }
                val response = client.call(requireNotNull(url), "PUT", buffer.copyOf(needed), mapOf("Content-Type" to row.mimeType, "Content-Range" to "bytes $offset-${offset + needed - 1}/${row.size}"))
                val next = if (response.code == 308) uploadedOffset(response.range, row.size) else if (response.code in 200..299) row.size else error("上傳回應無效")
                check(next > offset && next <= offset + needed)
                offset = next; input.channel.position(offset)
                row = row.copy(uploadedBytes = offset); dao.putFile(row)
            }
        }
        check(verified()) { "雲端檔案 SHA-256 驗證失敗" }
        dao.putFile(row.copy(state = "COMPLETE", resumableUri = null))
    }
}
