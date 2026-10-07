package com.eva.database

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.eva.database.entity.TimelineItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class KeyframePersistenceTest {
    @Test fun cloudQueueIdempotencyAndInstallerLockPreserveLocalData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = RecorderDataBase.createInMemoryDatabase(context)
        val store = SessionStore(context, db)
        val prefs = AccountSettings(context).prefs
        try {
            val session = store.start(requireAccount = false)
            assertTrue(db.sessionDao().recordingBusy())
            db.sessionDao().state(session, "PAUSED", 100)
            assertTrue(db.sessionDao().recordingBusy())
            db.sessionDao().state(session, "FINALIZING", 100)
            assertTrue(db.sessionDao().recordingBusy())
            db.sessionDao().complete(session, 9)
            assertFalse(db.sessionDao().recordingBusy())
            repeat(20) { db.cloudDao().enqueue(com.eva.database.entity.CloudBackupEntity(session, "original-account", "original@example.invalid")) }
            assertEquals(1, db.cloudDao().all().size)
            val row = db.cloudDao().backup(session)!!
            for (state in listOf("AUTH_REQUIRED", "QUOTA_FULL", "COMPLETE")) {
                db.cloudDao().put(row.copy(state = state))
                assertNotNull(db.sessionDao().session(session))
            }
            store.activeId.value = null
            store.transcribing.value = true
            assertTrue(runCatching { store.start(requireAccount = false) }.isFailure)
            store.transcribing.value = false
            store.startTranscription()
            assertTrue(store.transcribing.value)
            store.transcribing.value = false
            prefs.edit().putBoolean("install_committed", true).commit()
            assertTrue(runCatching { store.start(requireAccount = false) }.isFailure)
        } finally { prefs.edit().remove("install_committed").commit(); db.close() }
    }
    @Test fun hundredPhotosRecoveryIdempotencyAndCleanup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = RecorderDataBase.createInMemoryDatabase(context)
        val store = SessionStore(context, db)
        try {
            val session = store.start(requireAccount = false)
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            repeat(100) { index ->
                val item = store.beginPhoto(index * 72_000L)
                store.tempFile(item).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                store.finishPhoto(item)
            }
            val interrupted = store.beginPhoto(7_200_000)
            store.tempFile(interrupted).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            val failed = store.beginPhoto(7_200_000)
            store.tempFile(failed).writeText("invalid jpeg")
            store.activeId.value = null
            store.scanRecovery()
            assertEquals("RECOVERY_REQUIRED", store.dao.session(session)!!.status)
            assertEquals("READY", store.dao.item(interrupted.id)!!.state)
            assertEquals("FAILED", store.dao.item(failed.id)!!.state)
            repeat(3) { store.dao.complete(session, 42) }
            val items = store.dao.recordingItems(42)
            assertEquals(101, items.size)
            assertEquals(101, items.map { it.id }.toSet().size)
            assertTrue(items.zipWithNext().all { (a,b) -> a.positionMs <= b.positionMs })
            assertTrue(items.all { File(it.mediaPath!!).isFile })
            val audio = File(context.filesDir, "temp_recordings/test-$session.m4a").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            store.dao.audio(session, audio.absolutePath, "audio/mp4")
            val transcript = File(context.filesDir, "keyframes/$session/transcript.txt").apply { writeText("Whisper 文字保留檢查") }
            store.remapRecording(42, 84)
            assertTrue(transcript.isFile)
            store.remapRecording(84, 42)
            val exported = File(context.cacheDir, "test-$session.zip")
            LectureExporter(context, store).export(android.net.Uri.fromFile(exported), android.net.Uri.fromFile(audio), 42)
            java.util.zip.ZipFile(exported).use { zip ->
                assertNotNull(zip.getEntry("manifest.json"))
                assertNotNull(zip.getEntry("lecture.md"))
                assertTrue(zip.getInputStream(zip.getEntry("lecture.md")).bufferedReader().readText().contains("Whisper 文字保留檢查"))
                val timeline = org.json.JSONObject(zip.getInputStream(zip.getEntry("timeline.json")).bufferedReader().readText())
                assertEquals(1, timeline.getInt("schemaVersion"))
                assertEquals(101, timeline.getJSONArray("items").length())
                assertEquals(7_200_000L, timeline.getJSONArray("items").getJSONObject(100).getLong("positionMs"))
                assertEquals(101, zip.entries().asSequence().count { it.name.startsWith("keyframes/") })
            }
            exported.delete()
            store.deleteRecording(42)
            assertNull(store.dao.session(session))
            assertFalse(File(context.filesDir, "keyframes/$session").exists())
            assertFalse(transcript.exists())
            bitmap.recycle()
        } finally { db.close() }
    }
}
