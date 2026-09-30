package com.eva.recorder.domain.models

import java.time.LocalDateTime
import org.junit.Test
import org.junit.Assert.*

class RecordingNameTest {
    @Test fun namesHaveSafeAutomaticTimestamp() {
        assertEquals("數學課_2026-09-30_17-30-00", recordingFileStem(" 數學課 ", LocalDateTime.of(2026, 9, 30, 17, 30)))
        listOf("", "  ", "..", "a/b", "a\\b", "a:b", "a\nb", "字".repeat(51)).forEach {
            assertFalse(it, isValidRecordingName(it))
        }
        assertTrue(isValidRecordingName("測試 ZIP"))
    }
}
