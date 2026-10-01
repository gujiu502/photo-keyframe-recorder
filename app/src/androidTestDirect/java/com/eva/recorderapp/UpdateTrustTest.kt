package com.eva.recorderapp

import androidx.test.platform.app.InstrumentationRegistry
import com.eva.cloud.sha256
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import com.eva.database.AccountSettings
import com.eva.database.RecorderDataBase
import com.eva.database.entity.RecordingSessionEntity
import org.json.JSONObject

class UpdateTrustTest {
    @Test fun nativeInstallerWaitsForIdleAndRequestsAndroidConfirmation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Explicitly opt in on Android 11; newer systems may silently install the fixture.
        assumeTrue(android.os.Build.VERSION.SDK_INT == 30 && InstrumentationRegistry.getArguments().getString("verifyInstaller") == "true")
        val source = File(context.getExternalFilesDir(null), "same-key.apk")
        assertTrue(source.exists())
        val apk = File(context.filesDir, "updates/installer-check.apk").apply { parentFile!!.mkdirs(); source.copyTo(this, true) }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode + 1
        val settings = AccountSettings(context)
        settings.prefs.edit().putString("update_manifest", JSONObject().put("versionCode", version).put("sha256", sha256 { apk.inputStream() }).toString())
            .putString("update_apk", apk.absolutePath).putBoolean("install_committed", false).putString("update_state", "IDLE").commit()
        val busy = RecorderDataBase.createInMemoryDatabase(context)
        try {
            busy.sessionDao().insertSession(RecordingSessionEntity("busy-test", 1))
            for (state in listOf("ACTIVE", "PAUSED", "FINALIZING")) {
                busy.sessionDao().state("busy-test", state, 0)
                requestInstall(context, busy)
                assertFalse(settings.prefs.getBoolean("install_committed", false))
            }
        } finally { busy.close() }
        val db = RecorderDataBase.createDataBase(context)
        assertFalse(db.sessionDao().recordingBusy())
        requestInstall(context, db)
        repeat(100) { if (settings.prefs.getString("install_confirmation", null) == null) delay(100) }
        assertNotNull(settings.prefs.getString("install_confirmation", null))
        assertTrue(settings.prefs.getBoolean("install_committed", false))
        assertEquals("USER_ACTION_REQUIRED", settings.prefs.getString("update_state", null))
        // The host must open MainActivity and cancel the native confirmation, then verify unchanged version/data.
    }
    @Test fun rejectsBadHashAndSigningKeyBeforeSystemInstaller() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (InstrumentationRegistry.getArguments().getString("verifyInstallerCleanup") == "true") {
            assertFalse(AccountSettings(context).prefs.getBoolean("install_committed", false))
        }
        val dir = context.getExternalFilesDir(null)!!
        val same = File(dir, "same-key.apk")
        val wrong = File(dir, "wrong-key.apk")
        assumeTrue("Run tools/update_test_fixtures.py and push fixtures to this test app", same.exists() && wrong.exists())
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode + 1
        verifyApk(context, same, version, sha256 { same.inputStream() })
        assertTrue(runCatching { verifyApk(context, same, version, "0".repeat(64)) }.exceptionOrNull()?.message?.contains("SHA-256") == true)
        assertTrue(runCatching { verifyApk(context, wrong, version, sha256 { wrong.inputStream() }) }.exceptionOrNull()?.message?.contains("簽名") == true)
        assertTrue(runCatching { verifyApk(context, same, version + 1, sha256 { same.inputStream() }) }.isFailure)
    }
}
