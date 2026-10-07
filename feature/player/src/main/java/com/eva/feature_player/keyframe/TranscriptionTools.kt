package com.eva.feature_player.keyframe

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eva.transcription.TranscriptionService
import com.eva.transcription.TranscriptionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TranscriptionViewModel @Inject constructor(val store: TranscriptionStore) : ViewModel() {
    var text by mutableStateOf(""); private set
    suspend fun load(id: Long) { text = store.read(id) }
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
    var language by remember { mutableStateOf("zh") }
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    LaunchedEffect(id, status.doneMs, status.running) { vm.load(id) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.export(id, uri) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    fun start(action: String) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, TranscriptionService::class.java).setAction(action)
                .putExtra("recording_id", id).putExtra("audio_uri", audioUri).putExtra("language", language))
        } catch (e: Exception) { Toast.makeText(context, "無法開始識別：${e.localizedMessage ?: "請重試"}", Toast.LENGTH_LONG).show() }
    }
    TextButton(onClick = { expanded = true }) { Text("本地識別") }
    if (expanded) AlertDialog(
        onDismissRequest = { expanded = false; goHome() },
        title = { Text("Whisper 本地語音識別") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("離線識別，可處理這個錄音及以前的項目。識別可能有誤字，請核對原音訊。", style = MaterialTheme.typography.bodySmall)
            Row {
                FilterChip(selected = language == "zh", onClick = { language = "zh" }, label = { Text("中文") }, enabled = !status.running)
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = language == "auto", onClick = { language = "auto" }, label = { Text("自動語言") }, enabled = !status.running)
            }
            Row {
                if (!vm.store.hasModel()) {
                    TextButton(onClick = { confirmDownload = true }, enabled = !status.running) { Text("下載 Whisper 模型") }
                } else {
                    TextButton(onClick = { if (vm.text.isNotBlank()) confirmReplace = true else start(TranscriptionService.START) }, enabled = !status.running) { Text("開始識別") }
                }
                if (status.running) TextButton(onClick = {
                    context.startService(Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.STOP))
                }) { Text("停止識別") }
                if (vm.text.isNotBlank()) TextButton(onClick = { export.launch("${title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}-識別.txt") }) { Text("導出文字") }
            }
            if (status.message.isNotBlank() && (status.running || status.recordingId == null || status.recordingId == id)) {
                Text(if (status.running && status.recordingId != null && status.recordingId != id) "其他項目：${status.message}" else status.message, style = MaterialTheme.typography.bodySmall)
                if (status.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (vm.text.isNotBlank()) Text(vm.text.take(4000) + if (vm.text.length > 4000) "\n…可導出完整文字" else "", modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text("關閉") } },
    )
    if (confirmDownload) AlertDialog(onDismissRequest = { confirmDownload = false; goHome() }, title = { Text("下載本地 Whisper 模型？") },
        text = { Text("首次需下載約 74 MiB 的多語言 Tiny 模型。下載後可完全離線識別，錄音不會傳送到伺服器。") },
        confirmButton = { TextButton(onClick = { confirmDownload = false; start(TranscriptionService.DOWNLOAD) }) { Text("下載") } },
        dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("取消") } })
    if (confirmReplace) AlertDialog(onDismissRequest = { confirmReplace = false; goHome() }, title = { Text("重新識別這個錄音？") },
        text = { Text("新結果會取代之前的識別文字；原始錄音與照片不變。首次識別成功前會保留舊文字。") },
        confirmButton = { TextButton(onClick = { confirmReplace = false; start(TranscriptionService.START) }) { Text("開始識別") } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } })
}
