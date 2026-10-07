package com.eva.transcription

import org.junit.Assert.*
import org.junit.Test

class Pcm16kResamplerTest {
    @Test fun ratesAndBufferBoundariesPreserveSampleCount() {
        for (rate in listOf(8000, 16000, 44100, 48000)) {
            val values = ArrayList<Float>()
            val resampler = Pcm16kResampler(rate) { values.add(it) }
            repeat(rate) { resampler.push(.25f) }
            assertTrue(values.size in 15999..16000)
            assertTrue(values.all { it == .25f })
            repeat(rate) { resampler.push(.25f) }
            assertTrue(values.size in 31999..32000)
            resampler.push(Float.NaN)
            resampler.push(Float.POSITIVE_INFINITY)
            assertTrue(values.all { it.isFinite() && it in -1f..1f })
        }
    }
}
