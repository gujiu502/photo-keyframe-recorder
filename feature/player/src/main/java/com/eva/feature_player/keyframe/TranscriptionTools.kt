package com.eva.feature_player.keyframe

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eva.transcription.TranscriptionService
import com.eva.transcription.TranscriptionStore
import com.eva.transcription.NvidiaKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TranscriptionViewModel @Inject constructor(val store: TranscriptionStore, private val keys: NvidiaKeyStore) : ViewModel() {
    var text by mutableStateOf(""); private set
    var keyConfigured by mutableStateOf(keys.isConfigured()); private set
    var savingKey by mutableStateOf(false); private set
    suspend fun load(id: Long) { text = store.read(id) }
    fun updateKey(value: String?, done: (String?) -> Unit) = viewModelScope.launch {
        if (savingKey) return@launch
        savingKey = true
        try {
            if (value == null) keys.clear() else keys.save(value)
            keyConfigured = keys.isConfigured()
            done(null)
        } catch (_: IllegalArgumentException) {
            done("請輸入有效的 NVIDIA API 金鑰")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            done("無法更新金鑰，請重試")
        } finally {
            savingKey = false
        }
    }
    fun export(id: Long, uri: Uri, done: (String) -> Unit) = viewModelScope.launch {
        try { store.export(id, uri); done("已導出識別文字") }
        catch (e: Exception) { done(e.message ?: "導出失敗，已保存內容仍保留") }
    }
}

@Composable
internal fun TranscriptionTools(id: Long, audioUri: String, title: String, vm: TranscriptionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val goHome = com.eva.ui.navigation.LocalNavigateHome.current
    val status by vm.store.status.collectAsStateWithLifecycle()
    var expanded by remember(id) { mutableStateOf(false) }
    var cloud by remember(id) { mutableStateOf(true) }
    var language by remember { mutableStateOf("zh") }
    var confirmDownload by remember { mutableStateOf(false) }
    var replaceAction by remember { mutableStateOf<String?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var startAfterKey by remember { mutableStateOf(false) }
    LaunchedEffect(id, status.doneMs, status.running, status.revision) { vm.load(id) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.export(id, uri) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    fun start(action: String) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, TranscriptionService::class.java).setAction(action)
                .putExtra("recording_id", id).putExtra("audio_uri", audioUri).putExtra("language", language))
        } catch (e: Exception) { Toast.makeText(context, "無法開始識別：${e.localizedMessage ?: "請重試"}", Toast.LENGTH_LONG).show() }
    }
    OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium, onClick = {
        if (status.running && status.recordingId == id) cloud = status.cloud
        expanded = true
    }) { Text("語音識別") }
    if (expanded) AlertDialog(
        onDismissRequest = { expanded = false; goHome() },
        title = { Text("錄音轉文字") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = cloud, onClick = { cloud = true }, label = { Text("NVIDIA 雲端") }, enabled = !status.running)
                FilterChip(selected = !cloud, onClick = { cloud = false }, label = { Text("離線 Whisper") }, enabled = !status.running)
            }
            if (cloud) {
                Text("開始雲端辨識會將此錄音傳送給 NVIDIA。辨識文字會逐段顯示並儲存在手機。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { startAfterKey = false; showKey = true }, enabled = !status.running) {
                    Text(if (vm.keyConfigured) "管理 NVIDIA 金鑰" else "設定 NVIDIA 金鑰")
                }
                Button(onClick = {
                    when {
                        !vm.keyConfigured -> { startAfterKey = true; showKey = true }
                        vm.text.isNotBlank() -> replaceAction = TranscriptionService.START_CLOUD
                        else -> start(TranscriptionService.START_CLOUD)
                    }
                }, enabled = !status.running) { Text("開始雲端辨識") }
            } else {
                Text("離線識別，可處理這個錄音及以前的項目。", style = MaterialTheme.typography.bodySmall)
                Row {
                    FilterChip(selected = language == "zh", onClick = { language = "zh" }, label = { Text("中文") }, enabled = !status.running)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = language == "auto", onClick = { language = "auto" }, label = { Text("自動語言") }, enabled = !status.running)
                }
                if (!vm.store.hasModel()) {
                    TextButton(onClick = { confirmDownload = true }, enabled = !status.running) { Text("下載 Whisper 模型") }
                } else {
                    Button(onClick = {
                        if (vm.text.isNotBlank()) replaceAction = TranscriptionService.START else start(TranscriptionService.START)
                    }, enabled = !status.running) { Text("開始識別") }
                }
            }
            Row {
                if (status.running) TextButton(onClick = {
                    context.startService(Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.STOP))
                }) { Text("停止識別") }
                if (vm.text.isNotBlank()) TextButton(onClick = { export.launch("${title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}-識別.txt") }) { Text("導出文字") }
            }
            if (status.message.isNotBlank() && (status.running || status.recordingId == null || status.recordingId == id)) {
                Text(if (status.running && status.recordingId != null && status.recordingId != id) "其他項目：${status.message}" else status.message, style = MaterialTheme.typography.bodySmall)
                if (status.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val live = status.running && status.recordingId == id && status.cloud
            val savedText = if (live && status.revision == 0L) "" else vm.text
            val visible = savedText.takeLast(6000) + if (live && status.partial.isNotBlank()) "\n" + status.partial else ""
            val scroll = rememberScrollState()
            LaunchedEffect(visible, scroll.maxValue) { if (live) scroll.scrollTo(scroll.maxValue) }
            if (visible.isNotBlank()) SelectionContainer {
                Text(visible, modifier = Modifier.heightIn(max = 200.dp).verticalScroll(scroll), style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text("關閉") } },
    )
    if (confirmDownload) AlertDialog(onDismissRequest = { confirmDownload = false; goHome() }, title = { Text("下載本地 Whisper 模型？") },
        text = { Text("首次需下載約 74 MiB 的多語言 Tiny 模型。下載後可完全離線識別，錄音不會傳送到伺服器。") },
        confirmButton = { TextButton(onClick = { confirmDownload = false; start(TranscriptionService.DOWNLOAD) }) { Text("下載") } },
        dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("取消") } })
    replaceAction?.let { action -> AlertDialog(onDismissRequest = { replaceAction = null; goHome() }, title = { Text("重新識別這個錄音？") },
        text = { Text("新結果會取代之前的識別文字；原始錄音與照片不變。首次識別成功前會保留舊文字。") },
        confirmButton = { TextButton(onClick = { replaceAction = null; start(action) }) { Text("開始識別") } },
        dismissButton = { TextButton(onClick = { replaceAction = null }) { Text("取消") } }) }
    if (showKey) {
        var key by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!vm.savingKey) { showKey = false; goHome() } },
            title = { Text("NVIDIA API 金鑰") },
            text = {
                Column {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it.take(256); error = null },
                        label = { Text("貼上 NVIDIA API 金鑰") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !vm.savingKey, isError = error != null,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (vm.keyConfigured) TextButton(onClick = {
                        vm.updateKey(null) { message -> if (message == null) showKey = false else error = message }
                    }, enabled = !vm.savingKey) { Text("移除本機金鑰") }
                }
            },
            confirmButton = { TextButton(onClick = {
                vm.updateKey(key) { message ->
                    if (message == null) {
                        key = ""
                        showKey = false
                        if (startAfterKey && !status.running) {
                            if (vm.text.isNotBlank()) replaceAction = TranscriptionService.START_CLOUD
                            else start(TranscriptionService.START_CLOUD)
                        }
                    } else error = message
                }
            }, enabled = key.isNotBlank() && !vm.savingKey) { Text(if (startAfterKey) "儲存並開始" else "加密儲存") } },
            dismissButton = { TextButton(onClick = { showKey = false }, enabled = !vm.savingKey) { Text("取消") } },
        )
    }
}
