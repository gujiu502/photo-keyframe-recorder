package com.eva.transcription

/** Stateful linear resampling, including frame boundaries across decoder buffers. */
internal class Pcm16kResampler(private val sampleRate: Int, private val emit: (Float) -> Unit) {
    init { require(sampleRate in 8000..192000) }
    private var frame = 0L
    private var output = 0L
    private var previous = 0f
    fun push(value: Float) {
        val sample = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
        while (output * sampleRate <= frame * 16000) {
            val fraction = if (frame == 0L) 1f else (output * sampleRate / 16000.0 - (frame - 1)).toFloat()
            emit((previous + (sample - previous) * fraction).coerceIn(-1f, 1f))
            output++
        }
        previous = sample
        frame++
    }
}
