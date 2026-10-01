package com.eva.recorderapp

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.room.withTransaction
import com.eva.database.AccountSettings
import com.eva.database.RecorderDataBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.json.JSONObject
import java.io.File

@Composable
fun DistributionUpdater(activity: ComponentActivity, db: RecorderDataBase) {
    val settings = remember { AccountSettings(activity) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        recoverInstaller(activity)
        enqueueUpdate(activity)
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) {
            message = settings.prefs.getString("update_message", "") ?: ""
            if (settings.prefs.getString("update_state", "") == "READY_TO_INSTALL" && activity.packageManager.canRequestPackageInstalls()) {
                try { requestInstall(activity, db) } catch (e: Exception) { updateStatus(activity, "FAILED", "更新安裝失敗：${e.message}") }
            }
            val confirmation = settings.prefs.getString("install_confirmation", null)
            if (confirmation != null && !db.sessionDao().recordingBusy()) {
                settings.prefs.edit().remove("install_confirmation").apply()
                runCatching { activity.startActivity(Intent.parseUri(confirmation, 0)) }.onFailure {
                    settings.prefs.edit().putBoolean("install_committed", false).apply()
                    updateStatus(activity, "FAILED", "無法開啟系統安裝確認，請重試")
                }
            }
            delay(2000)
        }
        }
    }
    if (message.isNotBlank()) Text(message)
    if (settings.prefs.getString("update_state", "") == "READY_TO_INSTALL" && !activity.packageManager.canRequestPackageInstalls()) {
        TextButton(onClick = { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }) { Text("在系統設定允許應用程式更新") }
    }
    if (settings.prefs.getString("update_state", "") == "FAILED") TextButton(onClick = { enqueueUpdate(activity) }) { Text("重試更新") }
}

suspend fun requestInstall(activity: Context, db: RecorderDataBase) = withContext(Dispatchers.IO) {
    val settings = AccountSettings(activity)
    if (db.sessionDao().recordingBusy() || settings.prefs.getBoolean("install_committed", false)) return@withContext
    val json = JSONObject(requireNotNull(settings.prefs.getString("update_manifest", null)))
    val apk = File(requireNotNull(settings.prefs.getString("update_apk", null)))
    require(apk.canonicalFile.parentFile == File(activity.filesDir, "updates").canonicalFile)
    verifyApk(activity, apk, json.getLong("versionCode"), json.getString("sha256"))
    val locked = db.withTransaction {
        if (db.sessionDao().recordingBusy() || settings.prefs.getBoolean("install_committed", false)) false
        else { check(settings.prefs.edit().putBoolean("install_committed", true).commit()); true }
    }
    if (!locked) return@withContext
    val installer = activity.packageManager.packageInstaller
    var sessionId: Int? = null
    try {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName); setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        sessionId = installer.createSession(params)
        settings.prefs.edit().putInt("installer_session", sessionId).commit()
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { output -> apk.inputStream().use { it.copyTo(output) }; session.fsync(output) }
            val result = PendingIntent.getBroadcast(activity, sessionId, Intent(activity, InstallResultReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            updateStatus(activity, "INSTALLING", "正在安裝更新")
            session.commit(result.intentSender)
        }
    } catch (e: Exception) {
        sessionId?.let { runCatching { installer.abandonSession(it) } }
        settings.prefs.edit().putBoolean("install_committed", false).apply(); throw e
    }
}

fun recoverInstaller(context: Context) {
    val settings = AccountSettings(context)
    if (!settings.prefs.getBoolean("install_committed", false)) return
    val installedVersion = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    val target = settings.prefs.getString("update_manifest", null)?.let { runCatching { JSONObject(it).getLong("versionCode") }.getOrNull() }
    val id = settings.prefs.getInt("installer_session", -1)
    val info = context.packageManager.packageInstaller.getSessionInfo(id)
    if (target != null && target <= installedVersion) {
        settings.prefs.edit().putBoolean("install_committed", false).putBoolean("mandatory_update", false).remove("install_confirmation").apply()
        updateStatus(context, "INSTALLED", "更新已完成")
    } else if (info == null || !info.isSealed) {
        if (info != null) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        settings.prefs.edit().putBoolean("install_committed", false).remove("install_confirmation").apply()
        updateStatus(context, "FAILED", "上次更新未完成，本地資料仍保留；可重試更新")
    }
}

suspend fun updateWhenIdle(context: Context, db: RecorderDataBase) {
    val settings = AccountSettings(context)
    if (settings.agreementAccepted && settings.prefs.getString("update_state", "") == "READY_TO_INSTALL" && context.packageManager.canRequestPackageInstalls())
        runCatching { requestInstall(context, db) }.onFailure { updateStatus(context, "FAILED", "更新安裝失敗：${it.message}") }
}
