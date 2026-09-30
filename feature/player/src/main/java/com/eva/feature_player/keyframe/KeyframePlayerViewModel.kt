package com.eva.feature_player.keyframe

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.database.LectureExporter
import com.eva.database.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class KeyframePlayerViewModel @Inject constructor(val sessions: SessionStore, private val exporter: LectureExporter) : ViewModel() {
    fun export(destination: Uri, audio: Uri, id: Long, title: String, result: (String) -> Unit) = viewModelScope.launch {
        runCatching { exporter.export(destination, audio, id, title = title) }
            .onSuccess { result("講義包已導出") }.onFailure { result(it.message ?: "導出失败") }
    }
}
