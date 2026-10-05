package com.eva.recorderapp

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.eva.cloud.*
import com.eva.database.*
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.room.withTransaction

@Composable
fun AccountGate(activity: ComponentActivity, backup: DriveBackup, db: RecorderDataBase, onLocalData: (() -> Unit)? = null, onReady: () -> Unit) {
    val settings = remember { AccountSettings(activity) }
    val scope = rememberCoroutineScope()
    var accepted by remember { mutableStateOf(settings.agreementAccepted) }
    var checked by remember { mutableStateOf(false) }
    var identity by remember { mutableStateOf(settings.accountId?.let { it to requireNotNull(settings.email) }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun finishAuthorization(token: String?) {
        scope.launch {
            busy = true
            try {
                check(!token.isNullOrEmpty()) { "Google Drive 授權未完成" }
                backup.ensureRoot(token)
                settings.prefs.edit().putBoolean("drive_authorized", true).apply()
                backup.onAuthorizationGranted(); onReady()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Google Drive 連結失敗，請檢查網路與授權後重試" }
            finally { busy = false }
        }
    }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            val auth = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(result.data)
            check(auth.grantedScopes.contains(DRIVE_SCOPE)) { "尚未授權 Google Drive" }
            finishAuthorization(auth.accessToken)
        } catch (e: Exception) { error = "Google Drive 授權未完成，請重新授權"; busy = false }
    }
    suspend fun login(automatic: Boolean) {
        check(!db.sessionDao().recordingBusy()) { "錄音結束後才能切換 Google 帳號" }
        val selected = GoogleAccount(activity).signIn(activity, BuildConfig.GOOGLE_WEB_CLIENT_ID, automatic)
        db.withTransaction {
        check(!db.sessionDao().recordingBusy()) { "錄音結束後才能切換 Google 帳號" }
        check(db.cloudDao().all().none { it.accountId != selected.first && it.state !in setOf("COMPLETE", "DELETED") }) { "原帳號仍有待備份課程，請先完成或處理備份再切換帳號" }
        val changed = settings.accountId != selected.first
        settings.prefs.edit().putString("account_id", selected.first).putString("email", selected.second)
            .apply { if (changed) putBoolean("drive_authorized", false) }.commit()
        }
        identity = selected
    }
    LaunchedEffect(accepted) {
        if (accepted && identity == null && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
            busy = true
            try { login(true) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Button remains available. */ }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("照片關鍵幀錄音", style = MaterialTheme.typography.headlineMedium)
        if (!accepted) {
            Text("使用者協議與雲端備份／自動更新授權", style = MaterialTheme.typography.titleLarge)
            Text(AGREEMENT)
            Row { Checkbox(checked, { checked = it }, Modifier.semantics { contentDescription = "我已閱讀並同意使用者協議" }); Text("我已閱讀並同意使用者協議", Modifier.padding(top = 12.dp)) }
            Button(onClick = { settings.acceptAgreement(); accepted = true; enqueueUpdate(activity) }, enabled = checked) { Text("同意並繼續") }
        } else {
            Text("連結 Google 帳號與雲端備份", style = MaterialTheme.typography.titleLarge)
            Text("錄音、照片、時間軸與筆記會自動備份到：\n我的雲端硬碟 / 課程錄音\n\n首次設定完成後，沒有網路也可以錄音。")
            identity?.let { Text("Google 帳號：${it.second}") }
            if (identity != null) TextButton(enabled = !busy, onClick = { identity = null }) { Text("選擇其他 Google 帳號") }
            if (identity == null) OutlinedButton(enabled = !busy, onClick = {
                scope.launch { busy = true; error = null; try { login(false) } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) "開發者尚未配置 Google 登入" else "Google 登入未完成，請檢查網路與 Google 帳號；切換帳號前須先處理原帳號待備份課程" } finally { busy = false } }
            }) { Text("使用 Google 帳號登入") }
            else Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true; error = null
                    try {
                        val auth = Identity.getAuthorizationClient(activity).authorize(GoogleAccount(activity).request(identity!!.second)).await()
                        if (auth.hasResolution()) { authorization.launch(IntentSenderRequest.Builder(requireNotNull(auth.pendingIntent).intentSender).build()); busy = false }
                        else { check(auth.grantedScopes.contains(DRIVE_SCOPE)); finishAuthorization(auth.accessToken) }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { error = "Google Drive 授權未完成，請檢查網路後重試"; busy = false }
                }
            }) { Text("授權 Google Drive 並繼續") }
            if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) Text("開發者尚未配置 Google OAuth，此版本無法完成登入。現有本地資料仍保留。", color = MaterialTheme.colorScheme.error)
        }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        onLocalData?.let { TextButton(onClick = it) { Text("查看／導出既有本地資料") } }
        Text("協議版本 ${AccountSettings.AGREEMENT_VERSION}", style = MaterialTheme.typography.labelSmall)
    }
}

const val AGREEMENT = """使用本應用程式需要 Google 帳號，並授權應用程式存取它自行建立及管理的 Google Drive 檔案。我們只要求 drive.file 權限，不會讀取你的整個雲端硬碟。

應用程式會建立「課程錄音」資料夾，自動上傳錄音、照片關鍵幀、時間軸、筆記及課程中繼資料。資料直接傳到 Google Drive，不經開發者伺服器。備份及更新可能使用行動數據。

本地資料優先保存。網路中斷或 Drive 授權失效不會中止錄音；資料保留在裝置，待條件允許後重試。備份成功也不會自動刪除本地原始資料。你需要確保 Google Drive 有足夠空間。

你同意應用程式自動檢查、下載經驗證的更新，並在 Android 允許時自動發起安裝。錄音、暫停或保存期間會延後安裝。若系統要求確認，仍須由你在 Android 安裝介面完成。此協議不會繞過 Android 安全機制。

撤銷 Drive 權限、移除 Google 帳號或刪除雲端檔案可能中斷備份。點選「同意並繼續」表示你同意上述機制。"""
