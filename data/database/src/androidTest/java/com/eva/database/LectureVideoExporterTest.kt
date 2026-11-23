package com.eva.database

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class LectureVideoExporterTest {
    @Test
    fun exportsPlayableAudioAndTimedPhotosInOneFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = RecorderDataBase.createInMemoryDatabase(context)
        val store = SessionStore(context, db)
        val id = store.start(requireAccount = false)
        val audio = File.createTempFile("lecture-test-", ".wav", context.cacheDir)
        val output = File.createTempFile("lecture-test-", ".mp4", context.cacheDir)
        try {
            writeAudio(audio)
            for ((position, color) in listOf(1_000L to Color.RED, 2_800L to Color.BLUE)) {
                val item = store.beginPhoto(position)
                val image = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
                try {
                    image.eraseColor(color)
                    store.tempFile(item).outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                } finally {
                    image.recycle()
                }
                store.finishPhoto(item)
            }
            store.dao.complete(id, 42)
            val exporter = LectureVideoExporter(context, store)
            exporter.export(Uri.fromFile(output), Uri.fromFile(audio), 42, "測試")
            assertTracks(output)
            MediaMetadataRetriever().use { reader ->
                reader.setDataSource(output.absolutePath)
                val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Duration: $duration", duration in 4_800..5_250)
                val before = pixelAt(reader, 400_000L)
                val red = pixelAt(reader, 1_600_000L)
                val blue = pixelAt(reader, 3_600_000L)
                assertTrue("Cover before first photo", Color.red(before) < 90 && Color.blue(before) < 90)
                assertTrue("First photo at its timestamp", Color.red(red) > 180 && Color.blue(red) < 70)
                assertTrue("Second photo at its timestamp", Color.blue(blue) > 180 && Color.red(blue) < 70)
            }
            exporter.export(Uri.fromFile(output), Uri.fromFile(audio), 43, "純錄音")
            assertTracks(output)
        } finally {
            audio.delete()
            output.delete()
            store.deleteRecording(42)
            db.close()
        }
    }

    private fun assertTracks(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString("mime") }
            assertEquals(setOf("audio/mp4a-latm", "video/avc"), formats.toSet())
        } finally {
            extractor.release()
        }
    }

    private fun pixelAt(reader: MediaMetadataRetriever, timeUs: Long): Int {
        val bitmap = requireNotNull(reader.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST))
        return try {
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeAudio(file: File) {
        val sampleRate = 16_000
        val samples = sampleRate * 5
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort((3_000 * sin(it * 2 * Math.PI * 440 / sampleRate)).toInt().toShort()) }
        file.writeBytes(bytes.array())
    }
}
