package com.eva.feature_player.keyframe

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.eva.database.entity.TimelineItemEntity
import com.eva.database.formatPosition
import java.io.File

@Composable
internal fun PlayerKeyframes(id: Long, audioUri: String, title: String, currentPosition: () -> Long,
    onSeek: (Long) -> Unit, modifier: Modifier = Modifier, vm: KeyframePlayerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val flow = remember(id) { vm.sessions.dao.observeRecording(id) }
    val items by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by remember { mutableStateOf<TimelineItemEntity?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            exporting = true
            vm.export(uri, Uri.parse(audioUri), id, title) { message ->
                exporting = false; Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("关键帧 ${items.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { items.lastOrNull { it.positionMs < currentPosition() - 300 }?.let { onSeek(it.positionMs) } }, enabled = items.isNotEmpty()) { Text("上一帧") }
            TextButton(onClick = { items.firstOrNull { it.positionMs > currentPosition() + 300 }?.let { onSeek(it.positionMs) } }, enabled = items.isNotEmpty()) { Text("下一帧") }
            TextButton(onClick = { export.launch("lecture-$id.zip") }, enabled = !exporting) { Text(if (exporting) "导出中" else "导出") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                Card(onClick = { onSeek(item.positionMs) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.type == "PHOTO") {
                            IconButton(onClick = { selected = item }, modifier = Modifier.size(80.dp)) {
                                AsyncImage(File(requireNotNull(item.mediaPath)), "查看关键帧照片", modifier = Modifier.fillMaxSize())
                            }
                        }
                        Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                            Text(formatPosition(item.positionMs), style = MaterialTheme.typography.titleMedium)
                            Text(if (item.type == "PHOTO") "点时间播放 · 点图片放大" else "录音书签", style = MaterialTheme.typography.bodySmall)
                            if (item.text.isNotEmpty()) Text(item.text)
                        }
                    }
                }
            }
        }
    }
    selected?.let { item ->
        var scale by remember(item.id) { mutableFloatStateOf(1f) }
        var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { selected = null }) { Text("关闭") }
                        TextButton(onClick = { onSeek(item.positionMs) }) { Text("播放 ${formatPosition(item.positionMs)}") }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(item.id) {
                        detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset = if (scale == 1f) Offset.Zero else offset + pan }
                    }) {
                        AsyncImage(File(requireNotNull(item.mediaPath)), "关键帧照片，可双指缩放", modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                        })
                    }
                    Row {
                        TextButton(onClick = { items.takeWhile { it.id != item.id }.lastOrNull { it.type == "PHOTO" }?.let { selected = it; onSeek(it.positionMs) } }) { Text("上一帧") }
                        TextButton(onClick = { items.dropWhile { it.id != item.id }.drop(1).firstOrNull { it.type == "PHOTO" }?.let { selected = it; onSeek(it.positionMs) } }) { Text("下一帧") }
                    }
                }
            }
        }
    }
}
