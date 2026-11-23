package com.eva.player_shared.util

import com.eva.utils.formatElapsed
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

class ElapsedTimeTest {
    @Test fun displayKeepsHoursBeyondOneDay() {
        assertEquals("26:00:03", formatElapsed(26.hours + 3.seconds))
        assertEquals("00:00:00", formatElapsed((-1).seconds))
    }
}
