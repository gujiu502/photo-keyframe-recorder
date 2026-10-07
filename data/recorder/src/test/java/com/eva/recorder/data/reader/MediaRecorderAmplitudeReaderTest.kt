package com.eva.recorder.data.reader

import com.eva.recorder.domain.models.RecorderState
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MediaRecorderAmplitudeReaderTest {
    @Test fun longBackgroundGapCannotExpandWaveform() = runTest {
        var calls = 0
        var position = 0L
        val reader = MediaRecorderAmplitudeReader({ 32767 }, {
            position += if (++calls == 51) 7_500_000 else 100
            position
        })
        reader.readAmplitudeBuffered(RecorderState.RECORDING).take(150).collect { points ->
            assertTrue(points.size <= 100)
            assertTrue(points.all { it.rmsValue in 0f..1f })
            assertEquals(position, points.last().timeInMillis)
        }
        assertTrue(position > 7_200_000)
        reader.readAmplitudeBuffered(RecorderState.PAUSED).collect { assertTrue(it.isEmpty()) }
    }
}
