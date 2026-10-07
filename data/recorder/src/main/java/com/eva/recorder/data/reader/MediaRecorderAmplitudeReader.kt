package com.eva.recorder.data.reader

import com.eva.recorder.domain.models.RecordedPoint
import com.eva.recorder.domain.models.RecorderState
import com.eva.utils.RecorderConstants
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive

/** Read the recording encoder's peak instead of opening a second microphone. */
internal class MediaRecorderAmplitudeReader(
    private val amplitude: () -> Int,
    private val position: () -> Long,
) {
    fun readAmplitudeBuffered(state: RecorderState) = flow {
        val points = ArrayDeque<RecordedPoint>(RecorderConstants.RECORDER_AMPLITUDES_BUFFER_SIZE)
        if (state != RecorderState.RECORDING) { emit(emptyList()); return@flow }
        var peak = 100
        while (currentCoroutineContext().isActive) {
            val value = runCatching(amplitude).getOrDefault(0).coerceIn(0, 32767)
            peak = maxOf(peak, value)
            if (points.size == RecorderConstants.RECORDER_AMPLITUDES_BUFFER_SIZE) points.removeFirst()
            points.addLast(RecordedPoint(position(), value.toFloat()))
            emit(points.asSequence().smoothen(.3f).normalize(max = peak, min = 0).toList())
            delay(RecorderConstants.AMPS_READ_DELAY_RATE)
        }
    }.flowOn(Dispatchers.Default)
}
