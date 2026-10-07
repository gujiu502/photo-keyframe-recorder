package com.eva.transcription

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.content.ContentUris
import android.provider.MediaStore
import androidx.room.withTransaction
import com.eva.database.SessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class TranscriptionStatus(val recordingId: Long? = null, val running: Boolean = false, val message: String = "", val doneMs: Long = 0)

@Singleton
class TranscriptionStore @Inject constructor(@ApplicationContext val context: Context, val sessions: SessionStore) {
    val status = MutableStateFlow(TranscriptionStatus())
    val model get() = File(context.filesDir, "whisper/ggml-tiny.bin")
    fun hasModel() = model.isFile && model.length() == MODEL_SIZE
    private suspend fun result(id: Long): File {
        require(id >= 0)
        val session = sessions.dao.forRecording(id).firstOrNull()
        return if (session != null) File(context.filesDir, "keyframes/${session.sessionId}/transcript.txt")
        else File(context.filesDir, "transcripts/$id.txt")
    }
    suspend fun read(id: Long): String = withContext(Dispatchers.IO) {
        val file = result(id)
        if (file.exists() || File(file.path + ".bak").exists()) AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } else ""
    }
    suspend fun export(id: Long, uri: Uri) = withContext(Dispatchers.IO) {
        check(result(id).isFile) { "尚無識別結果" }
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { out -> AtomicFile(result(id)).openRead().use { it.copyTo(out) } }
    }
    internal suspend fun save(id: Long, text: String) = sessions.database.withTransaction {
        val audio = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        check(context.contentResolver.query(audio, arrayOf("_id"), null, null, null)?.use { it.moveToFirst() } == true) {
            "錄音已刪除，已停止識別"
        }
        val file = result(id)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    companion object {
        const val MODEL_SIZE = 77691713L
        const val MODEL_SHA256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
        const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/80da2d8bfee42b0e836fc3a9890373e5defc00a6/ggml-tiny.bin"
    }
}
