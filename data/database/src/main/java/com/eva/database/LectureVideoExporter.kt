package com.eva.database

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class LectureVideoExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessions: SessionStore,
) {
    private val exportLock = Mutex()

    suspend fun export(destination: Uri, audio: Uri, recordingId: Long, title: String) {
        var saved = false
        val acquired = exportLock.tryLock()
        try {
            check(acquired) { "已有影片正在匯出，請稍後再試" }
            withContext(Dispatchers.IO) {
                val output = File.createTempFile("lecture-", ".mp4", context.cacheDir)
                try {
                    val cover = File.createTempFile("lecture-cover-", ".png", context.cacheDir)
                    try {
                        val composition = composition(audio, recordingId, title, cover)
                        encode(composition, output)
                        check(output.length() > 0) { "未能產生影片" }
                        val resolver = context.contentResolver
                        requireNotNull(resolver.openOutputStream(destination, "wt")) { "無法開啟儲存位置" }.use { stream ->
                            output.inputStream().buffered().use { input ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    stream.write(buffer, 0, read)
                                }
                            }
                        }
                        saved = true
                    } finally {
                        cover.delete()
                    }
                } finally {
                    output.delete()
                }
            }
        } finally {
            if (acquired) exportLock.unlock()
            // CreateDocument gives us a newly created document. Remove an empty or partial result.
            if (!saved && destination.scheme == "content") withContext(NonCancellable + Dispatchers.IO) {
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, destination) }
            }
        }
    }

    private suspend fun composition(audio: Uri, id: Long, title: String, cover: File): Composition {
        val audioDuration = MediaMetadataRetriever().use { reader ->
            reader.setDataSource(context, audio)
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
        check(audioDuration != null && audioDuration > 0) { "無法讀取錄音長度" }
        val photos = sessions.dao.recordingItems(id).filter { it.type == "PHOTO" }
        val hasCover = photos.isEmpty() || photos.first().positionMs >= FRAME_MS
        val duration = maxOf(audioDuration, (photos.size + if (hasCover) 1 else 0) * FRAME_MS)
        val scenes = mutableListOf<Pair<Long, File>>()
        if (hasCover) {
            drawCover(cover, title)
            scenes += 0L to cover
        }
        for ((index, item) in photos.withIndex()) {
            val file = File(requireNotNull(item.mediaPath))
            check(file.isFile) { "關鍵幀照片遺失，無法匯出影片" }
            // Several photos can share a paused timestamp. Reserve a frame for each one.
            val earliest = scenes.lastOrNull()?.first?.plus(FRAME_MS) ?: 0L
            val latest = duration - (photos.size - index) * FRAME_MS
            val position = if (scenes.isEmpty()) 0L else item.positionMs.coerceIn(earliest, latest)
            scenes += position to file
        }
        val images = scenes.mapIndexed { index, (position, file) ->
            val length = (scenes.getOrNull(index + 1)?.first ?: duration) - position
            val image = MediaItem.Builder().setUri(Uri.fromFile(file)).setImageDurationMs(length).build()
            EditedMediaItem.Builder(image).setFrameRate(FRAME_RATE)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(
                    WIDTH, HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT,
                )))).build()
        }
        val soundtrack = EditedMediaItem.Builder(MediaItem.fromUri(audio)).setRemoveVideo(true).build()
        return Composition.Builder(
            EditedMediaItemSequence.withVideoFrom(images),
            EditedMediaItemSequence.withAudioFrom(listOf(soundtrack)),
        ).build()
    }

    private fun drawCover(file: File, title: String) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xff211d24.toInt())
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0xffef9d78.toInt()
            canvas.drawRect(72f, 267f, 124f, 273f, paint)
            paint.textSize = 27f
            canvas.drawText("照片關鍵幀錄音", 72f, 324f, paint)
            paint.color = 0xfffaf7f0.toInt()
            paint.textSize = 48f
            val text = TextUtils.ellipsize(title, paint, WIDTH - 144f, TextUtils.TruncateAt.END)
            canvas.drawText(text.toString(), 72f, 396f, paint)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    // Transformer and its cancellation must run on its application looper.
    private suspend fun encode(composition: Composition, file: File) = withContext(Dispatchers.Main.immediate) {
        var completed = false
        val transformer = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .build()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        completed = true
                        continuation.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        continuation.resumeWithException(exportException)
                    }
                })
                transformer.start(composition, file.absolutePath)
            }
        } finally {
            if (!completed) transformer.cancel()
        }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FRAME_RATE = 5
        const val FRAME_MS = 1000L / FRAME_RATE
    }
}

fun lectureVideoFileName(title: String): String =
    title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "錄音" } + ".mp4"
