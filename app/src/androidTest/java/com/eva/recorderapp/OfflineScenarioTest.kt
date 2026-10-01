package com.eva.recorderapp

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.eva.database.AccountSettings
import org.junit.Assert.*
import org.junit.Test

/** A cached-account/offline scenario, NOT a real Google login or Drive verification. */
class OfflineScenarioTest {
    @Test fun configurePreviouslyAuthorizedOfflineAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AccountSettings(context)
        assertFalse("Test setup must not impersonate an existing real account", settings.accountId?.let { it != "offline-instrumentation" } ?: false)
        settings.acceptAgreement()
        settings.prefs.edit().putString("account_id", "offline-instrumentation").putString("email", "offline-test@example.invalid")
            .putBoolean("drive_authorized", true).putBoolean("install_committed", false).putBoolean("mandatory_update", false).commit()
        assertTrue(settings.configured)
        assertTrue(settings.recordingAllowed)
        settings.prefs.edit().putBoolean("mandatory_update", true).commit()
        assertFalse(settings.recordingAllowed)
        settings.prefs.edit().putBoolean("mandatory_update", false).putBoolean("install_committed", true).commit()
        assertFalse(settings.recordingAllowed)
        settings.prefs.edit().putBoolean("install_committed", false).commit()
    }
}
