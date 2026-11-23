package com.eva.feature_player.keyframe

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.database.LectureVideoExporter
import com.eva.database.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@HiltViewModel
internal class KeyframePlayerViewModel @Inject constructor(val sessions: SessionStore, private val exporter: LectureVideoExporter) : ViewModel() {
    val exporting = MutableStateFlow(false)
    private var job: Job? = null

    fun export(destination: Uri, audio: Uri, id: Long, title: String, result: (String) -> Unit) {
        if (exporting.value) return
        exporting.value = true
        job = viewModelScope.launch {
            try {
                exporter.export(destination, audio, id, title)
                result("MP4 已儲存")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("LectureVideo", "Export failed", e)
                result("MP4 匯出失敗，原始錄音已保留")
            } finally {
                exporting.value = false
            }
        }
    }

    fun cancel() { job?.cancel() }
}
