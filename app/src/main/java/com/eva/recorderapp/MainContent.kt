package com.eva.recorderapp

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.eva.cloud.DriveBackup
import com.eva.database.*
import kotlinx.coroutines.delay

@Composable
fun MainContent(activity: ComponentActivity, sessions: SessionStore, backup: DriveBackup, db: RecorderDataBase, homeRequest: Int = 0, app: @Composable (Boolean) -> Unit) {
    val settings = remember { AccountSettings(activity) }
    var configured by remember { mutableStateOf(settings.configured) }
    var showCloud by remember { mutableStateOf(false) }
    var readOnly by remember { mutableStateOf(false) }
    var returnToHome by remember { mutableStateOf(false) }
    fun goHome() { showCloud = false; readOnly = !settings.configured; returnToHome = true }
    LaunchedEffect(homeRequest) { if (homeRequest > 0) goHome() }
    var blocked by remember { mutableStateOf(!settings.recordingAllowed) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        if (sessions.activeId.value == null) runCatching { sessions.scanRecovery() }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { configured = settings.configured; blocked = !settings.recordingAllowed; delay(1000) }
        }
    }
    if (!configured && !readOnly) AccountGate(activity, backup, db, onLocalData = { readOnly = true; returnToHome = false }) { configured = true }
    else if (showCloud) CloudStatus(activity, db, backup) { goHome() }
    else Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { app(readOnly && !returnToHome) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            if (blocked) Text("目前不能開始新錄音，仍可查看及導出本地資料。請完成系統更新或必要設定。", color = MaterialTheme.colorScheme.error)
            DistributionUpdater(activity, db)
            TextButton(onClick = { showCloud = true }) { Text("雲端備份與更新") }
            if (!configured) TextButton(onClick = { readOnly = false; returnToHome = false }) { Text("返回 Google 帳號設定") }
        }
    }
    BackHandler(enabled = showCloud || !configured && !readOnly) { goHome() }
}
