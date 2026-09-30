package com.eva.feature_recorder.keyframe

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.database.SessionStore
import com.eva.database.LectureExporter
import com.eva.database.entity.TimelineItemEntity
import com.eva.recordings.domain.provider.RecorderFileProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject

@HiltViewModel
class KeyframeViewModel @Inject constructor(val sessions: SessionStore, private val audioFiles: RecorderFileProvider, private val exporter: LectureExporter) : ViewModel() {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val busy = MutableStateFlow(false)
    private val lock = Mutex()
    @OptIn(ExperimentalCoroutinesApi::class)
    val items = sessions.activeId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else sessions.dao.observeSession(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val unfinished = sessions.dao.unfinished().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init { viewModelScope.launch { runCatching { sessions.scanRecovery() }.onFailure { messages.emit("恢復檢查失敗，原始資料已保留") } } }

    fun capture(takePicture: (File, (Boolean) -> Unit) -> Unit, onDone: () -> Unit) {
        if (busy.value) return
        val position = sessions.position() // Read the engine's clock at shutter press, before any I/O.
        busy.value = true
        viewModelScope.launch {
            var item: TimelineItemEntity? = null
            try {
                item = sessions.beginPhoto(position)
                val saved = suspendCancellableCoroutine<Boolean> { continuation ->
                    takePicture(sessions.tempFile(item)) { ok -> if (continuation.isActive) continuation.resumeWith(Result.success(ok)) }
                }
                check(saved) { "拍照失敗，錄音繼續" }
                withContext(NonCancellable) { sessions.finishPhoto(item) }
                messages.emit("照片已保存 · ${com.eva.database.formatPosition(position)}")
                onDone()
            } catch (e: Exception) {
                withContext(NonCancellable) { item?.let { sessions.failPhoto(it) } }
                if (e is CancellationException) throw e
                messages.emit(e.message ?: "拍照失敗，錄音繼續")
            } finally { busy.value = false }
        }
    }
    fun recover(id: String) = viewModelScope.launch {
        lock.withLock {
            try {
                val session = sessions.dao.session(id) ?: return@withLock
                if (session.status == "COMPLETE") return@withLock
                check(id != sessions.activeId.value) { "這次錄音尚未停止" }
                val file = File(requireNotNull(session.audioPath) { "尚無音訊，可導出照片或捨棄這次錄音。" })
                withContext(Dispatchers.IO) {
                    MediaMetadataRetriever().use { reader ->
                        reader.setDataSource(file.absolutePath)
                        check((reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0) {
                            "中斷的音訊無法播放，請導出恢復包以保留原始檔案。"
                        }
                    }
                }
                sessions.dao.state(id, "FINALIZING", session.positionMs)
                audioFiles.transferFileDataToStorage(file, session.mimeType).getOrThrow()
                messages.emit("已恢復錄音與照片關鍵幀")
            } catch (e: Exception) { messages.emit(e.message ?: "恢復失敗，原始檔案已保留") }
        }
    }
    fun discard(id: String) = viewModelScope.launch {
        lock.withLock { runCatching { sessions.discard(id) }.onFailure { messages.emit(it.message ?: "清理失敗") } }
    }
    fun exportRecovery(id: String, uri: Uri) = viewModelScope.launch {
        runCatching { exporter.export(uri, null, null, id, "恢復的講義") }
            .onSuccess { messages.emit("已導出恢復包") }.onFailure { messages.emit(it.message ?: "導出失敗") }
    }
}
