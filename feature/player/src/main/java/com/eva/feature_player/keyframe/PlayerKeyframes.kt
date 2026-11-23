package com.eva.feature_player.keyframe

import android.net.Uri
import com.eva.ui.navigation.LocalNavigateHome
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.eva.database.entity.TimelineItemEntity
import com.eva.database.formatPosition
import com.eva.database.lectureVideoFileName
import java.io.File

@Composable
internal fun PlayerKeyframes(id: Long, audioUri: String, title: String, currentPosition: () -> Long,
    onSeek: (Long) -> Unit, modifier: Modifier = Modifier, vm: KeyframePlayerViewModel = hiltViewModel(),
    playback: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val goHome = LocalNavigateHome.current
    val flow = remember(id) { vm.sessions.dao.observeRecording(id) }
    val items by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by remember { mutableStateOf<TimelineItemEntity?>(null) }
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) {
            vm.export(uri, Uri.parse(audioUri), id, title) { message ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    BoxWithConstraints(modifier) {
        val previewHeight = (maxHeight * .31f).coerceIn(86.dp, 178.dp)
        val preview = if (maxHeight >= 310.dp) {
            items.lastOrNull { it.type == "PHOTO" && it.positionMs <= currentPosition() }
                ?: items.firstOrNull { it.type == "PHOTO" }
        } else null
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            preview?.let { photo ->
                Surface(Modifier.fillMaxWidth().height(previewHeight), shape = MaterialTheme.shapes.medium,
                    color = com.eva.ui.theme.JournalConsole) {
                    AsyncImage(
                        model = photo.mediaPath?.let(::File),
                        contentDescription = "目前關鍵幀，點擊可放大",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clickable { selected = photo },
                    )
                }
            }
            playback()
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("關鍵幀 ${items.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { items.lastOrNull { it.positionMs < currentPosition() - 300 }?.let { onSeek(it.positionMs) } }, enabled = items.isNotEmpty()) { Text("上一幀") }
                TextButton(onClick = { items.firstOrNull { it.positionMs > currentPosition() + 300 }?.let { onSeek(it.positionMs) } }, enabled = items.isNotEmpty()) { Text("下一幀") }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    OutlinedCard(modifier = Modifier.width(104.dp), shape = MaterialTheme.shapes.small) {
                        val photo = item.mediaPath?.takeIf { item.type == "PHOTO" }
                        if (photo != null) {
                            AsyncImage(File(photo), "查看關鍵幀照片", contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(64.dp).clickable { selected = item })
                        } else {
                            Text("錄音書籤", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { onSeek(item.positionMs) }, modifier = Modifier.fillMaxWidth()) {
                            Text(formatPosition(item.positionMs).substringBefore('.'))
                        }
                        if (item.text.isNotEmpty()) Text(item.text, modifier = Modifier.padding(8.dp), maxLines = 2)
                    }
                }
            }
            }
            TranscriptionTools(id, audioUri, title)
            if (exporting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = vm::cancel, modifier = Modifier.align(Alignment.End)) { Text("取消匯出") }
            } else {
                Button(onClick = { export.launch(lectureVideoFileName(title)) },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("匯出 MP4") }
            }
        }
    }
    selected?.let { item ->
        var scale by remember(item.id) { mutableFloatStateOf(1f) }
        var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
        Dialog(onDismissRequest = { selected = null; goHome() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { selected = null }) { Text("關閉") }
                        TextButton(onClick = { onSeek(item.positionMs) }) { Text("播放 ${formatPosition(item.positionMs)}") }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(item.id) {
                        detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset = if (scale == 1f) Offset.Zero else offset + pan }
                    }) {
                        AsyncImage(File(requireNotNull(item.mediaPath)), "關鍵幀照片，可雙指縮放", modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                        })
                    }
                    Row {
                        TextButton(onClick = { items.takeWhile { it.id != item.id }.lastOrNull { it.type == "PHOTO" }?.let { selected = it; onSeek(it.positionMs) } }) { Text("上一幀") }
                        TextButton(onClick = { items.dropWhile { it.id != item.id }.drop(1).firstOrNull { it.type == "PHOTO" }?.let { selected = it; onSeek(it.positionMs) } }) { Text("下一幀") }
                    }
                }
            }
        }
    }
}
