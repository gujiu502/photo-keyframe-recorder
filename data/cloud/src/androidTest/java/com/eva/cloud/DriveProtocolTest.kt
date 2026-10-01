package com.eva.cloud

import androidx.test.platform.app.InstrumentationRegistry
import com.eva.database.*
import com.eva.database.entity.CloudFileEntity
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
            val matching = files.filter { (_, file) -> props.all { file.metadata.optJSONObject("appProperties")?.optString(it.first) == it.second } }
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
        val id = url.path.substringAfterLast('/')
        val file = files[id] ?: return Reply(404, "notFound")
        return Reply(200, JSONObject(file.metadata.toString()).put("id", id).put("size", file.bytes.size)
            .put("sha256Checksum", sha256 { file.bytes.inputStream() }).put("trashed", false).toString())
    }
}
