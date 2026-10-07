package com.eva.transcription

internal object WhisperNative {
    init { System.loadLibrary("local-whisper") }
    external fun resetStop()
    external fun stop()
    external fun open(path: String): Long
    external fun close(handle: Long)
    external fun transcribe(handle: Long, samples: FloatArray, length: Int, language: String): Array<String>
}
