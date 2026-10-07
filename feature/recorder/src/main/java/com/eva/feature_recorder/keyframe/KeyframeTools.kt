package com.eva.feature_recorder.keyframe

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.eva.database.formatPosition
import com.eva.feature_recorder.composable.SaveRecordingDialog
import com.eva.recorder.domain.models.RecorderState
import java.io.File

@Composable
internal fun KeyframeTools(state: RecorderState, timer: () -> String, onAction: (com.eva.recorder.domain.models.RecorderAction) -> Unit, vm: KeyframeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val photos by vm.items.collectAsStateWithLifecycle()
    val unfinished by vm.unfinished.collectAsStateWithLifecycle()
    val active by vm.sessions.activeId.collectAsStateWithLifecycle()
    val namingRequested by vm.sessions.namingRequested.collectAsStateWithLifecycle()
    LaunchedEffect(namingRequested, state) {
        if (namingRequested && state == RecorderState.RECORDING) onAction(com.eva.recorder.domain.models.RecorderAction.PauseRecorderAction)
    }
    SaveRecordingDialog(
        showDialog = namingRequested && state == RecorderState.PAUSED && active != null,
        onDismiss = { vm.sessions.namingRequested.value = false },
        onSave = { name, course -> onAction(com.eva.recorder.domain.models.RecorderAction.SaveRecorderAction(name, course)) },
    )
    val busy by vm.busy.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var discardId by remember { mutableStateOf<String?>(null) }
    var recoveryExportId by remember { mutableStateOf<String?>(null) }
    var selectedPhoto by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) recoveryExportId?.let { vm.exportRecovery(it, uri) }
    }
    LaunchedEffect(vm) { vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera = true else Toast.makeText(context, "相機未授權，錄音繼續", Toast.LENGTH_LONG).show()
    }
    if (showCamera && active != null) KeyframeCamera(timer, busy, vm, { showCamera = false })
    selectedPhoto?.let { path ->
        Dialog(onDismissRequest = { selectedPhoto = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface { Column {
                TextButton(onClick = { selectedPhoto = null }) { Text("關閉照片") }
                AsyncImage(File(path), "關鍵幀照片", modifier = Modifier.fillMaxWidth().heightIn(max = 650.dp))
            } }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (state in listOf(RecorderState.RECORDING, RecorderState.PAUSED)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                        showCamera = true else permission.launch(Manifest.permission.CAMERA)
                }, enabled = active != null && !busy) {
                    Icon(Icons.Default.CameraAlt, "拍照關鍵幀"); Spacer(Modifier.width(8.dp)); Text("拍照關鍵幀")
                }
                Spacer(Modifier.width(12.dp))
                Text("${photos.count { it.type == "PHOTO" }} 張照片", style = MaterialTheme.typography.labelLarge)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos.filter { it.type == "PHOTO" }, key = { it.id }) { item ->
                    Column(Modifier.clickable { selectedPhoto = item.mediaPath }) {
                        AsyncImage(File(requireNotNull(item.mediaPath)), "關鍵幀 ${formatPosition(item.positionMs)}", modifier = Modifier.size(72.dp))
                        Text(formatPosition(item.positionMs).substringBefore('.'), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        for (session in unfinished.filter { it.sessionId != active }) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("發現未完成的錄音", style = MaterialTheme.typography.titleSmall)
                    Text("原始音頻和照片已保留。中斷的音頻可能需要導出後修復。", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { vm.recover(session.sessionId) }) { Text("恢復") }
                        TextButton(onClick = { recoveryExportId = session.sessionId; export.launch("recovery-${session.sessionId}.zip") }) { Text("導出原始文件") }
                        TextButton(onClick = { discardId = session.sessionId }) { Text("丟棄") }
                    }
                }
            }
        }
    }
    discardId?.let { id -> AlertDialog(onDismissRequest = { discardId = null }, title = { Text("丟棄這次錄音？") },
        text = { Text("這會永久刪除本次錄音及照片。") },
        confirmButton = { TextButton(onClick = { vm.discard(id); discardId = null }) { Text("刪除") } },
        dismissButton = { TextButton(onClick = { discardId = null }) { Text("保留") } }) }
}

@Composable
private fun KeyframeCamera(timer: () -> String, busy: Boolean, vm: KeyframeViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(85)
        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build() }
    val preview = remember { Preview.Builder().setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()).build() }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val executor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val observer = Observer<PreviewView.StreamState> { ready = it == PreviewView.StreamState.STREAMING }
        previewView.previewStreamState.observe(owner, observer)
        future.addListener({
            if (!disposed) try {
                provider = future.get()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val camera = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                hasFlash = camera.cameraInfo.hasFlashUnit()
            } catch (e: Exception) { error = "相機無法啟動，錄音繼續。${e.localizedMessage ?: ""}" }
        }, executor)
        onDispose { disposed = true; previewView.previewStreamState.removeObserver(observer); provider?.unbind(preview, capture) }
    }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = dismiss, enabled = !busy) { Icon(Icons.Default.Close, "返回錄音") }
                    if (hasFlash) IconButton(onClick = { flash = !flash; capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }) {
                        Icon(Icons.Default.FlashOn, if (flash) "關閉閃光燈" else "开啟閃光燈")
                    }
                }
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().weight(1f))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                Text("錄音進行中 / 暫停時保留位置 · ${timer()}", modifier = Modifier.padding(16.dp))
                Button(onClick = {
                    capture.targetRotation = previewView.display?.rotation ?: 0
                    vm.capture(takePicture = { file, result ->
                        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) { result(true) }
                                override fun onError(exception: ImageCaptureException) { result(false) }
                            })
                    }, onDone = dismiss)
                }, enabled = ready && !busy, modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp).height(56.dp)) {
                    Text(if (busy) "正在保存…" else "拍照")
                }
            }
        }
    }
}
