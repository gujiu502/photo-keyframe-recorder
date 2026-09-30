package com.eva.recorder.domain.models

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun isValidRecordingName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() && trimmed.length <= 50 && trimmed.any { it != '.' } &&
        trimmed.none { it.isISOControl() || it in "\\/:*?\"<>|" }
}

fun recordingFileStem(name: String, time: LocalDateTime = LocalDateTime.now()): String {
    require(isValidRecordingName(name)) { "檔名不可空白，最多 50 字，且不可包含特殊符號" }
    return "${name.trim()}_${time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}"
}
