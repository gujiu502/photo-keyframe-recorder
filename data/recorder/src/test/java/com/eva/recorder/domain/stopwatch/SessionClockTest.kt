package com.eva.recorder.domain.stopwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionClockTest {
    @Test fun pausesLongSessionsAndRepeatedActions() {
        var now = 0L
        val clock = SessionClock { now }
        assertEquals(0, clock.positionMs())
        clock.start()
        now = 10000; assertEquals(10000, clock.positionMs())
        clock.pause(); clock.pause()
        now = 20000; assertEquals(10000, clock.positionMs())
        clock.resume(); clock.resume()
        now = 25000; assertEquals(15000, clock.positionMs())
        clock.pause(); now = 30000; clock.resume()
        now = 7_215_000; assertEquals(7_200_000, clock.positionMs())
        now += 86_400_000; assertEquals(93_600_000, clock.positionMs())
    }
}
