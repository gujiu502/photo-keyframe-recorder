package com.eva.transcription

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NvidiaKeyStoreTest {
    @Test fun keyIsEncryptedAtRestAndCanBeRemoved() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = NvidiaKeyStore(context)
        check(!store.isConfigured())
        val key = "nvapi-test_fixture_not_a_real_credential"
        val file = File(context.noBackupFilesDir, "nvidia-asr.key")
        try {
            store.save(key)
            assertTrue(store.isConfigured())
            assertEquals(key, NvidiaKeyStore(context).read())
            val first = file.readBytes()
            assertFalse(first.toString(Charsets.UTF_8).contains(key))
            store.save(key)
            assertFalse(first.contentEquals(file.readBytes()))
        } finally {
            store.clear()
        }
        assertFalse(store.isConfigured())
    }
}
