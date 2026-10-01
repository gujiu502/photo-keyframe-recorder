package com.eva.database

import android.content.Context

/** No tokens are stored here. Google Play services owns their lifecycle. */
class AccountSettings(context: Context) {
    val prefs = context.getSharedPreferences("account_backup", Context.MODE_PRIVATE)
    val accountId get() = prefs.getString("account_id", null)
    val email get() = prefs.getString("email", null)
    val agreementAccepted get() = prefs.getInt("agreement_version", 0) == AGREEMENT_VERSION
    val configured get() = agreementAccepted && accountId != null && prefs.getBoolean("drive_authorized", false)
    val recordingAllowed get() = configured && !prefs.getBoolean("mandatory_update", false) && !prefs.getBoolean("install_committed", false)
    fun acceptAgreement() { prefs.edit().putInt("agreement_version", AGREEMENT_VERSION).putLong("accepted_at", System.currentTimeMillis()).apply() }
    companion object { const val AGREEMENT_VERSION = 1 }
}
