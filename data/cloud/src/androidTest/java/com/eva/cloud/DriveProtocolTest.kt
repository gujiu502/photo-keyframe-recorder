package com.eva.cloud

import androidx.test.platform.app.InstrumentationRegistry
import com.eva.database.*
import com.eva.database.entity.CloudFileEntity
import com.eva.database.entity.CloudBackupEntity
import com.eva.database.entity.RecordingSessionEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*

/** Exercises real request/chunk/persistence code against an in-process Drive protocol double. */
class DriveProtocolTest {
    @Test fun lostCreateResponseInterruptedUploadResumeAndIntegrity() = runBlocking {
        val remote = FakeDrive()
        URL.setURLStreamHandlerFactory { protocol -> if (protocol == "https") object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = remote.connection(url)
        } else null }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("protocol-test-ids", 0).apply { edit().clear().commit() }
        val client = DriveClient("test-token", prefs)
        val properties = mapOf("ownerApp" to "PhotoKeyframeRecorder", "schema" to "lecture-root-v1")
        remote.failCreate = true
        assertTrue(runCatching { client.ensure("課程錄音", null, properties, true) }.isFailure)
        val root = client.ensure("課程錄音", null, properties, true)
        repeat(20) { assertEquals(root, client.ensure("課程錄音", null, properties, true)) }
        assertEquals(1, remote.files.size)

        val db = RecorderDataBase.createInMemoryDatabase(context)
        val source = File(context.cacheDir, "resumable-test.bin")
        try {
            source.writeBytes(ByteArray(2 * 1024 * 1024 + 123) { (it % 251).toByte() })
            val originalHash = sha256 { source.inputStream() }
            val row = CloudFileEntity("session", "audio/lecture.m4a", source.absolutePath, originalHash, source.length(), "audio/mp4")
            val backup = DriveBackup(context, db, LectureExporter(context, SessionStore(context, db)))
            remote.failSecondChunk = true
            assertTrue(runCatching { backup.uploadFile(client, root, row) }.isFailure)
            val checkpoint = db.cloudDao().files("session").single()
            assertEquals(1024L * 1024, checkpoint.uploadedBytes)
            assertNotNull(checkpoint.resumableUri)
            backup.uploadFile(DriveClient("test-token", prefs), root, checkpoint)
            val completed = db.cloudDao().files("session").single()
            assertEquals("COMPLETE", completed.state)
            assertEquals(source.length(), completed.uploadedBytes)
            assertEquals(2, remote.files.size) // one root + one audio, even after retry
            assertEquals(originalHash, sha256 { remote.files.getValue(completed.driveFileId!!).bytes.inputStream() })
            assertEquals(originalHash, sha256 { source.inputStream() })
            assertTrue(remote.probedResume)
            val chunks = remote.chunks
            backup.uploadFile(client, root, completed)
            assertEquals(chunks, remote.chunks) // verified complete data is never uploaded twice
            db.sessionDao().insertSession(RecordingSessionEntity("session", 1, status = "COMPLETE"))
            val completeBackup = CloudBackupEntity("session", "account", "test@example.invalid", state = "COMPLETE", folderId = root)
            db.cloudDao().put(completeBackup)
            val repairs = mutableListOf<String>()
            backup.checkCompleted(client, "account", repairs::add)
            assertTrue(repairs.isEmpty())
            remote.files.remove(completed.driveFileId)
            backup.checkCompleted(client, "account", repairs::add)
            assertEquals(listOf("session"), repairs)
            assertEquals("QUEUED", db.cloudDao().backup("session")!!.state)
            backup.checkCompleted(client, "account", repairs::add)
            assertEquals(1, repairs.size) // no duplicate repair on another launch
            // A removed file cannot reuse an upload URL bound to its old file ID.
            backup.uploadFile(client, root, completed.copy(resumableUri = "https://www.googleapis.com/resumable/stale"))
            val repaired = db.cloudDao().files("session").single()
            assertNotEquals(completed.driveFileId, repaired.driveFileId)
            assertEquals(originalHash, sha256 { remote.files.getValue(repaired.driveFileId!!).bytes.inputStream() })
            db.cloudDao().put(completeBackup)
            remote.files.getValue(root).metadata.put("trashed", true)
            repairs.clear()
            backup.checkCompleted(client, "account", repairs::add)
            assertEquals(listOf("session"), repairs)
            val replacementRoot = client.ensure("課程錄音", null, properties, true)
            assertNotEquals(root, replacementRoot)
            backup.uploadFile(client, replacementRoot, repaired)
            val restored = db.cloudDao().files("session").single()
            assertNotEquals(repaired.driveFileId, restored.driveFileId)
            assertEquals(originalHash, sha256 { remote.files.getValue(restored.driveFileId!!).bytes.inputStream() })
            val restoredBackup = completeBackup.copy(folderId = replacementRoot)
            db.cloudDao().put(restoredBackup)
            repairs.clear()
            backup.checkCompleted(client, "account", repairs::add)
            assertTrue(repairs.isEmpty())
            for (errorCode in listOf(401, 403, 500)) {
                remote.readError = errorCode
                assertTrue(runCatching { backup.checkCompleted(client, "account", repairs::add) }.isFailure)
                assertEquals("COMPLETE", db.cloudDao().backup("session")!!.state)
                assertTrue(repairs.isEmpty()) // failures are not proof that files were deleted
            }
            remote.readError = null
            remote.files.getValue(restored.driveFileId!!).metadata.put("trashed", true)
            remote.onMetadata = { db.cloudDao().put(restoredBackup.copy(state = "DELETE_QUEUED")) }
            backup.checkCompleted(client, "account", repairs::add)
            assertEquals("DELETE_QUEUED", db.cloudDao().backup("session")!!.state)
            assertTrue(repairs.isEmpty()) // preserve concurrent explicit deletion
            db.cloudDao().put(restoredBackup.copy(accountId = "another-account"))
            backup.checkCompleted(client, "account", repairs::add)
            assertTrue(repairs.isEmpty())
            val account = AccountSettings(context)
            account.prefs.edit().putInt("agreement_version", AccountSettings.AGREEMENT_VERSION)
                .putString("account_id", "account").putString("email", "test@example.invalid")
                .putBoolean("drive_authorized", true).commit()
            try {
                db.cloudDao().put(restoredBackup.copy(state = "DELETED"))
                backup.reconcile()
                backup.checkCompleted(client, "account", repairs::add)
                backup.upload("session") // even a previously queued upload respects the tombstone
                assertEquals("DELETED", db.cloudDao().backup("session")!!.state)
                assertTrue(repairs.isEmpty()) // a later startup must not resurrect explicit deletion
            } finally { account.prefs.edit().clear().commit() }
            assertEquals(originalHash, sha256 { source.inputStream() })
            remote.quotaFull = true
            val error = runCatching { client.ensure("new", null, mapOf("schema" to "quota-test"), true) }.exceptionOrNull()
            assertEquals("QUOTA_FULL", (error as DriveHttpError).state)
            assertTrue(source.exists())
        } finally { db.close(); source.delete(); prefs.edit().clear().commit() }
    }
}

private data class FakeFile(val metadata: JSONObject, var bytes: ByteArray = ByteArray(0))
private data class Reply(val code: Int, val body: String = "", val location: String? = null, val range: String? = null)
private class FakeDrive {
    val files = linkedMapOf<String, FakeFile>()
    private val uploads = mutableMapOf<String, String>()
    var failCreate = false
    var failSecondChunk = false
    var quotaFull = false
    var probedResume = false
    var chunks = 0
    var readError: Int? = null
    var onMetadata: (suspend () -> Unit)? = null
    private fun trashed(id: String): Boolean {
        val file = files[id] ?: return true
        if (file.metadata.optBoolean("trashed")) return true
        val parents = file.metadata.optJSONArray("parents") ?: return false
        return (0 until parents.length()).any { trashed(parents.getString(it)) }
    }
    private var counter = 0
    fun connection(url: URL) = object : HttpURLConnection(url) {
        private val output = ByteArrayOutputStream()
        private val reply by lazy { respond(url, requestMethod, getRequestProperty("X-HTTP-Method-Override"), getRequestProperty("Content-Range"), output.toByteArray()) }
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = output
        override fun getResponseCode() = reply.code
        override fun getInputStream(): InputStream = reply.body.byteInputStream()
        override fun getErrorStream(): InputStream = reply.body.byteInputStream()
        override fun getHeaderField(name: String): String? = when (name) { "Location" -> reply.location; "Range" -> reply.range; else -> null }
    }
    private fun respond(url: URL, method: String, override: String?, range: String?, bytes: ByteArray): Reply {
        if (quotaFull) return Reply(403, "storageQuotaExceeded")
        if (url.path.endsWith("generateIds")) return Reply(200, JSONObject().put("ids", JSONArray(listOf("file-${++counter}"))).toString())
        if (url.query?.startsWith("q=") == true) {
            val query = URLDecoder.decode(url.query.substringAfter("q=").substringBefore('&'), "UTF-8")
            val props = Regex("key='([^']+)' and value='([^']+)'").findAll(query).map { it.groupValues[1] to it.groupValues[2] }.toList()
            val matching = files.filter { (id, file) -> !trashed(id) && props.all { file.metadata.optJSONObject("appProperties")?.optString(it.first) == it.second } }
            return Reply(200, JSONObject().put("files", JSONArray(matching.keys.map { JSONObject().put("id", it) })).toString())
        }
        if (override == "PATCH" && url.path.contains("/upload/drive/")) {
            val id = url.path.substringAfterLast('/')
            files.getValue(id).bytes = ByteArray(0)
            val session = "https://www.googleapis.com/resumable/$id"
            uploads[session] = id
            return Reply(200, location = session)
        }
        if (method == "POST") {
            val json = JSONObject(bytes.toString(Charsets.UTF_8)); val id = json.getString("id")
            files[id] = FakeFile(json)
            if (failCreate) { failCreate = false; throw IOException("create response lost after server commit") }
            return Reply(200, JSONObject().put("id", id).toString())
        }
        if (method == "PUT") {
            val file = files.getValue(uploads.getValue(url.toString()))
            if (range!!.startsWith("bytes */")) { probedResume = true; return Reply(308, range = "bytes=0-${file.bytes.size - 1}") }
            val parts = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(range)!!.groupValues
            assertEquals(file.bytes.size.toLong(), parts[1].toLong())
            if (failSecondChunk && file.bytes.isNotEmpty()) { failSecondChunk = false; throw IOException("connection lost") }
            chunks++; file.bytes += bytes
            return if (file.bytes.size.toLong() == parts[3].toLong()) Reply(200, "{}") else Reply(308, range = "bytes=0-${file.bytes.size - 1}")
        }
        readError?.let { return Reply(it, "metadata failure") }
        onMetadata?.let { callback -> onMetadata = null; runBlocking { callback() } }
        val id = url.path.substringAfterLast('/')
        val file = files[id] ?: return Reply(404, "notFound")
        return Reply(200, JSONObject(file.metadata.toString()).put("id", id).put("size", file.bytes.size)
            .put("sha256Checksum", sha256 { file.bytes.inputStream() }).put("trashed", trashed(id)).toString())
    }
}
