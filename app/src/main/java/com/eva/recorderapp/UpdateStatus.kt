package com.eva.recorderapp

import android.content.Context
import com.eva.database.AccountSettings

fun updateStatus(context: Context, state: String, message: String) {
    AccountSettings(context).prefs.edit().putString("update_state", state).putString("update_message", message).apply()
}
