package com.eva.recorderapp

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.eva.cloud.DriveBackup
import com.eva.database.*
import kotlinx.coroutines.launch

@Composable
fun CloudStatus(activity: ComponentActivity, db: RecorderDataBase, backup: DriveBackup, onClose: () -> Unit) {
    val rows by db.cloudDao().observe().collectAsState(emptyList())
    val sessions by db.sessionDao().completed().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val settings = remember { AccountSettings(activity) }
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("雲端備份與更新", style = MaterialTheme.typography.headlineSmall)
            Text(settings.email ?: "尚未登入")
            Text("我的雲端硬碟 / 課程錄音\n備份完成後，本地原始資料仍會保留。")
            var beta by remember { mutableStateOf(settings.prefs.getBoolean("beta_channel", false)) }
            Row { Checkbox(beta, { beta = it; settings.prefs.edit().putBoolean("beta_channel", it).apply(); enqueueUpdate(activity, true) }, Modifier.semantics { contentDescription = "接收測試版更新" }); Text("接收測試版更新", Modifier.padding(top = 12.dp)) }
            var wifi by remember { mutableStateOf(settings.prefs.getBoolean("updates_wifi_only", false)) }
            Row { Checkbox(wifi, { wifi = it; settings.prefs.edit().putBoolean("updates_wifi_only", it).apply(); enqueueUpdate(activity, true) }, Modifier.semantics { contentDescription = "只在 Wi-Fi 自動下載更新" }); Text("只在 Wi-Fi 自動下載更新", Modifier.padding(top = 12.dp)) }
            DistributionUpdater(activity, db)
            TextButton(onClick = { activity.startActivity(Intent(activity, AccountActivity::class.java)) }) { Text("重新授權 Google Drive") }
            TextButton(onClick = onClose) { Text("返回") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(rows, key = { it.sessionId }) { row ->
            val files by db.cloudDao().observeFiles(row.sessionId).collectAsState(emptyList())
            val total = files.sumOf { it.size }; val done = files.sumOf { it.uploadedBytes }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(sessions.firstOrNull { it.sessionId == row.sessionId }?.fileName ?: row.sessionId, style = MaterialTheme.typography.titleMedium)
                Text(when (row.state) {
                    "COMPLETE" -> "☁ 已備份"
                    "UPLOADING", "PARTIAL" -> "↑ 上傳中 ${if (total > 0) done * 100 / total else 0}%"
                    "QUEUED", "FAILED_RETRYABLE" -> "⏳ 等待網路／稍後重試"
                    "AUTH_REQUIRED", "DELETE_AUTH_REQUIRED" -> "Google Drive 需要重新授權"
                    "DELETE_QUEUED" -> "等待刪除 Google Drive 備份"
                    "QUOTA_FULL" -> "Google Drive 空間不足，本地資料仍保留"
                    else -> "上傳失敗，本地資料仍保留"
                })
                if (row.state != "COMPLETE") TextButton(onClick = { scope.launch {
                    try { backup.retry(row.sessionId) } catch (e: Exception) { message = e.message }
                } }) { Text("重新上傳") }
            } }
        }
    }
}
