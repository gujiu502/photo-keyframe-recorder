package com.eva.recorderapp

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.eva.database.AccountSettings
import com.eva.database.RecorderDataBase
import com.google.android.play.core.appupdate.*
import com.google.android.play.core.install.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import android.content.Context
import androidx.room.withTransaction

@Composable
fun DistributionUpdater(activity: ComponentActivity, db: RecorderDataBase) {
    val manager = remember { AppUpdateManagerFactory.create(activity) }
    var message by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        AccountSettings(activity).prefs.edit().putBoolean("install_committed", false).apply()
        message = "更新流程已返回，稍後再次檢查"
    }
    LaunchedEffect(Unit) {
        var requested = false
        if (!AccountSettings(activity).agreementAccepted) return@LaunchedEffect
        val prefs = AccountSettings(activity).prefs
        if (prefs.getInt("play_target_version", 0) <= BuildConfig.VERSION_CODE) prefs.edit().putBoolean("install_committed", false).apply()
        try {
            while (true) {
                val info = manager.appUpdateInfo.await()
                if (info.installStatus() in setOf(InstallStatus.FAILED, InstallStatus.CANCELED, InstallStatus.INSTALLED)) prefs.edit().putBoolean("install_committed", false).apply()
                if (!db.sessionDao().recordingBusy()) {
                    if (info.installStatus() == InstallStatus.DOWNLOADED) {
                        val locked = lockPlayUpdate(activity, db, info.availableVersionCode())
                        if (locked) { message = "新版已下載，正在安裝更新"; manager.completeUpdate().await() }
                    } else if (!requested && info.updateAvailability() in setOf(UpdateAvailability.UPDATE_AVAILABLE, UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)) {
                        val critical = info.updatePriority() >= 4
                        val kind = if (critical) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
                        AccountSettings(activity).prefs.edit().putBoolean("mandatory_update", critical).apply()
                        if (info.isUpdateTypeAllowed(kind) && (!critical || lockPlayUpdate(activity, db, info.availableVersionCode()))) {
                            manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(kind).build())
                            requested = true
                        }
                    }
                }
                delay(30_000)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { AccountSettings(activity).prefs.edit().putBoolean("install_committed", false).apply(); message = "Google Play 更新暫時無法使用，本地資料仍保留" }
    }
    if (message.isNotBlank()) Text(message)
}

suspend fun lockPlayUpdate(context: Context, db: RecorderDataBase, version: Int): Boolean = db.withTransaction {
    if (db.sessionDao().recordingBusy()) false
    else { check(AccountSettings(context).prefs.edit().putBoolean("install_committed", true).putInt("play_target_version", version).commit()); true }
}

suspend fun updateWhenIdle(context: Context, db: RecorderDataBase) { /* Play update completion belongs to the foreground flow. */ }
fun enqueueUpdate(context: Context, replace: Boolean = false) { /* Play checks are driven by its foreground lifecycle. */ }
