package com.eva.transcription

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class WhisperTest {
    @Test fun concurrentTranscriptAndDeletionSafety() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = com.eva.database.RecorderDataBase.createInMemoryDatabase(context)
        val sessions = com.eva.database.SessionStore(context, db)
        val store = TranscriptionStore(context, sessions)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "WhisperDeleteCheck-${java.util.UUID.randomUUID()}.wav")
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
        val id = android.content.ContentUris.parseId(uri)
        var audioExists = true
        try {
            store.save(id, "已保存識別文字")
            assertEquals("已保存識別文字", store.read(id))
            val body = "字".repeat(100000)
            store.save(id, "00$body")
            val exported = File(context.cacheDir, "concurrent-transcript-$id.txt")
            fun valid(text: String) = text.length == body.length + 2 && text.take(2).toIntOrNull() in 0..20 && text.drop(2) == body
            try {
                coroutineScope {
                    val writer = async(Dispatchers.IO) { repeat(20) { store.save(id, "${(it+1).toString().padStart(2, '0')}$body") } }
                    val readers = List(3) { async(Dispatchers.IO) { repeat(20) { assertTrue(valid(store.read(id))) } } }
                    val exporter = async(Dispatchers.IO) { repeat(20) {
                        store.export(id, android.net.Uri.fromFile(exported))
                        assertTrue(valid(exported.readText()))
                        val markdown = com.eva.database.LectureExporter(context, sessions).contents(id).markdown
                        assertTrue(valid(markdown.substringAfter("## Whisper 本地語音識別\n\n").trimEnd()))
                    } }
                    writer.await(); readers.forEach { it.await() }; exporter.await()
                }
                assertEquals("20$body", store.read(id))
            } finally { exported.delete() }
            assertEquals(1, context.contentResolver.delete(uri, null, null))
            audioExists = false
            sessions.deleteRecording(id)
            assertEquals("", store.read(id))
            assertTrue(runCatching { store.save(id, "不應重新出現") }.isFailure)
            assertEquals("", store.read(id))
        } finally {
            if (audioExists) context.contentResolver.delete(uri, null, null)
            sessions.deleteRecording(id)
            db.close()
        }
    }

    @Test fun genuineOfflineMultilingualInferenceAndAbort() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val model = File(context.getExternalFilesDir(null), "ggml-tiny.bin")
        assertTrue("Push the verified Tiny model before running this device test", model.isFile)
        assertEquals(TranscriptionStore.MODEL_SHA256, model.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            digest.digest().joinToString("") { "%02x".format(it) }
        })
        WhisperNative.resetStop()
        val handle = WhisperNative.open(model.absolutePath)
        assertNotEquals(0L, handle)
        try {
            for ((name, language) in listOf("jfk.wav" to "auto", "chinese.m4a" to "zh")) {
                val fixture = File(context.cacheDir, name)
                assets.open(name).use { source -> fixture.outputStream().use { source.copyTo(it) } }
                val segments = mutableListOf<String>()
                try {
                    decodeAudio(context, android.net.Uri.fromFile(fixture)) { samples, length, _ ->
                        segments.addAll(WhisperNative.transcribe(handle, samples, length, language))
                    }
                } finally { fixture.delete() }
                val text = segments.joinToString("\n")
                assertTrue("Whisper returned no text for $name", text.isNotBlank())
                if (language == "zh") assertTrue(text, text.contains("照片") && text.contains("本地"))
                else assertTrue(text, text.lowercase().contains("country"))
                assertTrue(segments.all { it.split('|', limit = 3)[0].toLong() >= 0 })
                WhisperNative.stop()
                assertTrue(WhisperNative.transcribe(handle, FloatArray(16000), 16000, language).isEmpty())
                WhisperNative.resetStop()
            }
            coroutineScope {
                val running = async(Dispatchers.Default) { WhisperNative.transcribe(handle, FloatArray(480000) { .02f }, 480000, "zh") }
                delay(100)
                WhisperNative.stop()
                assertTrue("Running inference did not stop", withTimeout(15000) { running.await() }.isEmpty())
            }
        } finally { WhisperNative.close(handle) }
    }
}
