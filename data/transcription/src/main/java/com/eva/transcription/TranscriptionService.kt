package com.eva.transcription

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.ContentUris
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.eva.database.formatPosition
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject

@AndroidEntryPoint
class TranscriptionService : LifecycleService() {
    @Inject lateinit var store: TranscriptionStore
    @Inject lateinit var nvidiaKeys: NvidiaKeyStore
    private var job: Job? = null
    private var nativeLoaded = false
    private val wakeLock by lazy { (getSystemService(POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:transcription").apply { setReferenceCounted(false) } }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == STOP) { cancel(); return START_NOT_STICKY }
        if (job != null) return START_NOT_STICKY
        val id = intent?.getLongExtra("recording_id", -1) ?: -1
        val download = intent?.action == DOWNLOAD
        val cloud = intent?.action == START_CLOUD
        val uri = intent?.getStringExtra("audio_uri")?.let(Uri::parse)
        val language = intent?.getStringExtra("language") ?: "zh"
        val preparing = when {
            download -> "正在下載模型…"
            cloud -> "正在連接 NVIDIA 雲端…"
            else -> "正在準備本地識別…"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "語音識別", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIFICATION, notification(preparing),
            if (Build.VERSION.SDK_INT >= 35 && !download && !cloud) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        store.status.value = TranscriptionStatus(id.takeIf { it >= 0 }, true, preparing, cloud = cloud)
        job = lifecycleScope.launch {
            var reserved = false
            try {
                withContext(Dispatchers.IO) {
                    store.sessions.startTranscription()
                    reserved = true
                    wakeLock.acquire()
                    if (download) downloadModel() else {
                        require(id >= 0 && uri?.scheme == "content" && uri.authority == "media" &&
                            ContentUris.parseId(uri) == id) { "請從錄音項目選擇有效的音訊" }
                        if (cloud) recognizeCloud(id, uri) else {
                            require(language in setOf("zh", "auto"))
                            recognize(id, uri, language)
                        }
                    }
                }
                store.status.value = store.status.value.copy(running = false, partial = "", message = if (download) "模型已下載，可離線識別" else "識別完成，結果已保存在手機")
            } catch (e: CancellationException) {
                store.status.value = store.status.value.copy(running = false, partial = "", message = "已停止，已保存的識別內容仍保留")
            } catch (e: Exception) {
                store.status.value = store.status.value.copy(running = false, partial = "", message = e.message ?: "識別失敗，原始錄音已保留")
            } catch (e: OutOfMemoryError) {
                store.status.value = store.status.value.copy(running = false, partial = "", message = "手機記憶體不足，請關閉其他 App 後重試；原始錄音已保留")
            } finally {
                if (reserved) store.sessions.transcribing.value = false
                nativeLoaded = false
                if (wakeLock.isHeld) wakeLock.release()
                job = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun cancel() {
        if (job != null) store.status.value = store.status.value.copy(message = "正在停止…")
        if (nativeLoaded) WhisperNative.stop()
        job?.cancel()
        if (job == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { cancel(); if (wakeLock.isHeld) wakeLock.release(); super.onDestroy() }

    private suspend fun recognizeCloud(id: Long, uri: Uri) {
        val text = StringBuilder()
        NvidiaStreamingRecognizer(nvidiaKeys.read()).use { recognizer ->
            decodeAudio(this, uri) { samples, length, offset ->
                val startMs = offset / 16
                var segmentStart = startMs
                store.status.value = store.status.value.copy(message = "NVIDIA 雲端辨識中…")
                recognizer.recognize(samples, length) { words, finalized, processedMs ->
                    val end = (startMs + processedMs).coerceAtLeast(segmentStart)
                    if (words.isNotBlank()) {
                        if (finalized) {
                            text.append("[${formatPosition(segmentStart)} – ${formatPosition(end)}] $words\n")
                            store.save(id, text.toString())
                            segmentStart = end
                            val status = store.status.value
                            store.status.value = status.copy(doneMs = end, partial = "", revision = status.revision + 1)
                        } else {
                            store.status.value = store.status.value.copy(partial = words)
                        }
                    }
                }
                val done = (offset + length) / 16
                val message = "已識別至 ${formatPosition(done).substringBefore('.')}"
                store.status.value = store.status.value.copy(doneMs = done, partial = "", message = message)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
            }
        }
        if (text.isEmpty()) {
            check(store.read(id).isBlank()) { "沒有識別到新文字，已保留之前的結果" }
            store.save(id, "沒有識別到語音。\n")
        }
    }

    private suspend fun recognize(id: Long, uri: Uri, language: String) {
        check(store.hasModel()) { "請先下載 Whisper 模型（約 74 MiB）" }
        val memory = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        check(!memory.lowMemory && memory.availMem > 400L * 1024 * 1024) { "可用記憶體不足，請關閉其他 App 後重試" }
        nativeLoaded = true
        WhisperNative.resetStop()
        currentCoroutineContext().ensureActive()
        val handle = WhisperNative.open(store.model.absolutePath)
        check(handle != 0L) { "Whisper 模型無法載入，請重新下載" }
        val text = StringBuilder()
        var written = false
        try {
            // ponytail: independent 30s windows bound memory; add overlap if boundary accuracy needs it.
            decodeAudio(this, uri) { samples, length, offset ->
                currentCoroutineContext().ensureActive()
                val segments = WhisperNative.transcribe(handle, samples, length, language)
                currentCoroutineContext().ensureActive()
                for (segment in segments) {
                    val parts = segment.split('|', limit = 3)
                    check(parts.size == 3)
                    val start = offset * 1000 / 16000 + parts[0].toLong()
                    val end = offset * 1000 / 16000 + parts[1].toLong()
                    val words = parts[2].trim()
                    if (words.isNotEmpty()) text.append("[").append(formatPosition(start)).append(" – ").append(formatPosition(end)).append("] ").append(words).append('\n')
                }
                // Atomic checkpoints keep the old result until the first successful window.
                store.save(id, text.toString())
                written = true
                val done = (offset + length) * 1000 / 16000
                val message = "已識別至 ${formatPosition(done).substringBefore('.')}"
                store.status.value = TranscriptionStatus(id, true, message, done)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
            }
            if (written && text.isEmpty()) store.save(id, "沒有識別到語音。\n")
        } finally { WhisperNative.close(handle) }
    }

    private suspend fun downloadModel() {
        val target = store.model
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        check(filesDir.usableSpace > TranscriptionStore.MODEL_SIZE + 100L * 1024 * 1024) { "儲存空間不足，模型未下載" }
        val atomic = android.util.AtomicFile(target)
        val output = atomic.startWrite()
        var connection: HttpURLConnection? = null
        try {
            connection = URL(TranscriptionStore.MODEL_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            check(connection.responseCode == 200) { "模型下載失敗，請檢查網路後重試" }
            val hash = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            var percent = -1
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    check(bytes <= TranscriptionStore.MODEL_SIZE) { "模型大小不符" }
                    output.write(buffer, 0, count); hash.update(buffer, 0, count)
                    val progress = (bytes * 100 / TranscriptionStore.MODEL_SIZE).toInt()
                    if (progress != percent) {
                        percent = progress
                        store.status.value = TranscriptionStatus(running = true, message = "正在下載模型：$percent%")
                    }
                }
            }
            check(bytes == TranscriptionStore.MODEL_SIZE && hash.digest().joinToString("") { "%02x".format(it) } == TranscriptionStore.MODEL_SHA256) { "模型校驗失敗，請重新下載" }
            atomic.finishWrite(output)
        } catch (e: Throwable) { atomic.failWrite(output); throw e }
        finally { connection?.disconnect() }
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("語音識別").setContentText(message)
        .setOnlyAlertOnce(true).setOngoing(true)
        .addAction(0, "停止識別", PendingIntent.getService(this, 0, Intent(this, TranscriptionService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()
    companion object {
        const val START = "com.eva.transcription.START"
        const val START_CLOUD = "com.eva.transcription.START_CLOUD"
        const val DOWNLOAD = "com.eva.transcription.DOWNLOAD"
        const val STOP = "com.eva.transcription.STOP"
        private const val CHANNEL = "local-transcription"
        private const val NOTIFICATION = 9017
    }
}
