package com.eva.transcription

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.SystemClock
import android.os.PowerManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in hardware diagnostic: no app recorder, waveform, photo or database code. */
class PlatformRecorderProbe {
    @Suppress("DEPRECATION")
    @Test fun microphoneDurationMatchesElapsedTime() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("platform_recorder_probe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, android.app.Activity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val output = File(context.getExternalFilesDir(null), "platform-recorder-probe.m4a")
        val recorder = MediaRecorder()
        val background = InstrumentationRegistry.getArguments().getString("background") == "true"
        val service = Intent(context, ProbeMicrophoneService::class.java)
        val wake = (context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "probe:microphone")
        try {
            if (background) { context.startForegroundService(service); wake.acquire(90000) }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setAudioChannels(1)
            recorder.setOutputFile(output.absolutePath)
            recorder.prepare()
            recorder.start()
            val started = SystemClock.elapsedRealtime()
            if (background) {
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            }
            Thread.sleep(60000)
            recorder.stop()
            val elapsed = SystemClock.elapsedRealtime() - started
            val metadata = MediaMetadataRetriever()
            val duration = try {
                metadata.setDataSource(output.absolutePath)
                requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            } finally { metadata.release() }
            Log.i("PlatformRecorderProbe", "background=$background elapsed=${elapsed}ms audio=${duration}ms path=${output.absolutePath}")
            assertTrue("Native microphone duration=${duration}ms vs elapsed=${elapsed}ms", duration in (elapsed * 98 / 100)..(elapsed * 102 / 100))
        } finally {
            recorder.release()
            if (background) {
                if (wake.isHeld) wake.release()
                context.stopService(service)
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}

class ProbeMicrophoneService : android.app.Service() {
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(android.app.NotificationManager::class.java)
        notifications.createNotificationChannel(android.app.NotificationChannel("probe", "錄音對照測試", android.app.NotificationManager.IMPORTANCE_LOW))
        startForeground(1, android.app.Notification.Builder(this, "probe").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("系統錄音對照測試").build())
    }
    override fun onBind(intent: Intent?): android.os.IBinder? = null
}
