package com.eva.database

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

class LectureExporter @Inject constructor(@ApplicationContext private val context: Context, private val sessions: SessionStore) {
    suspend fun contents(recordingId: Long?, sessionId: String? = null, title: String = "講義"): LectureContents = withContext(Dispatchers.IO) {
        val session = sessionId?.let { sessions.dao.session(it) }
            ?: recordingId?.let { sessions.dao.forRecording(it).firstOrNull() }
        val items = session?.let { sessions.dao.items(it.sessionId) } ?: emptyList()
        val timeline = JSONArray()
        val markdown = StringBuilder("# ${title.replace('\n', ' ')}\n\n")
        val photoFiles = mutableListOf<Pair<String, File>>()
        for (item in items.filter { it.state == "READY" }) {
            val entry = JSONObject().put("id", item.id).put("type", item.type)
                .put("positionMs", item.positionMs).put("text", item.text).put("createdAt", item.createdAt)
            markdown.append("## ${formatPosition(item.positionMs)}\n\n")
            if (item.type == "PHOTO") {
                val file = File(requireNotNull(item.mediaPath))
                check(file.isFile) { "照片遺失，已停止導出" }
                val path = "keyframes/${item.id}.jpg"
                entry.put("mediaPath", path)
                photoFiles += path to file
                markdown.append("![$path]($path)\n\n")
            }
            markdown.append(item.text).append("\n\n")
            timeline.put(entry)
        }
        if (recordingId != null) for (bookmark in sessions.database.recordingBookMarkDao().getBookMarksFromRecordingId(recordingId)) {
            markdown.append("## ${formatPosition(bookmark.timeStamp.toMillisecondOfDay().toLong())}\n\n").append(bookmark.text).append("\n\n")
            timeline.put(JSONObject().put("id", "bookmark-${bookmark.bookMarkId}").put("type", "BOOKMARK")
                .put("positionMs", bookmark.timeStamp.toMillisecondOfDay().toLong()).put("text", bookmark.text))
        }
        val sorted = (0 until timeline.length()).map { timeline.getJSONObject(it) }.sortedBy { it.getLong("positionMs") }
        val extension = session?.audioPath?.substringAfterLast('.', "m4a") ?: "m4a"
        val audioName = "audio/lecture.$extension"
        val manifest = JSONObject().put("schemaVersion", 1).put("title", title)
            .put("sessionId", session?.sessionId ?: JSONObject.NULL).put("audioPath", audioName)
            .put("status", session?.status ?: "COMPLETE").put("exportedAt", System.currentTimeMillis())
        manifest.put("courseName", session?.courseName ?: "未分類").put("startedAt", session?.startedAt)
            .put("durationMs", session?.positionMs).put("mimeType", session?.mimeType)
        LectureContents(manifest, JSONObject().put("schemaVersion", 1).put("items", JSONArray(sorted)), markdown.toString(), photoFiles, audioName, session?.audioPath)
    }
    suspend fun export(destination: Uri, audio: Uri?, recordingId: Long?, sessionId: String? = null, title: String = "講義") = withContext(Dispatchers.IO) {
        val data = contents(recordingId, sessionId, title)
        val stream = requireNotNull(context.contentResolver.openOutputStream(destination, "wt"))
        ZipOutputStream(stream).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("manifest.json", data.manifest.toString(2).toByteArray())
            entry("timeline.json", data.timeline.toString(2).toByteArray())
            entry("lecture.md", data.markdown.toByteArray())
            zip.putNextEntry(ZipEntry(data.audioName))
            val audioStream = if (audio != null) context.contentResolver.openInputStream(audio)
                else data.audioPath?.let { File(it).takeIf(File::exists)?.inputStream() }
            requireNotNull(audioStream) { "找不到音訊檔案" }.use { it.copyTo(zip) }
            zip.closeEntry()
            for ((path, file) in data.photos) {
                zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}

data class LectureContents(val manifest: JSONObject, val timeline: JSONObject, val markdown: String,
    val photos: List<Pair<String, File>>, val audioName: String, val audioPath: String?)

fun formatPosition(ms: Long): String {
    val seconds = ms / 1000
    return "%02d:%02d:%02d.%03d".format(seconds / 3600, seconds / 60 % 60, seconds % 60, ms % 1000)
}
