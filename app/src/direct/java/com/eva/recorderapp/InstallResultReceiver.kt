package com.eva.recorderapp

import android.app.*
import android.content.*
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import com.eva.database.AccountSettings

class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val settings = AccountSettings(context)
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != settings.prefs.getInt("installer_session", -2)) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) { settings.prefs.edit().putBoolean("install_committed", false).apply(); updateStatus(context, "FAILED", "系統安裝確認遺失，請重試"); return }
                settings.prefs.edit().putString("install_confirmation", confirmation.toUri(0)).apply()
                updateStatus(context, "USER_ACTION_REQUIRED", "Android 需要你確認更新安裝")
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("app_update", "應用程式更新", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(context, 8100, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(8100, NotificationCompat.Builder(context, "app_update").setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Android 需要確認更新").setContentText("點選後開啟系統安裝介面").setContentIntent(open).setAutoCancel(true).build())
            }
            PackageInstaller.STATUS_SUCCESS -> {
                settings.prefs.edit().putBoolean("install_committed", false).putBoolean("mandatory_update", false).remove("install_confirmation").apply()
                updateStatus(context, "INSTALLED", "更新安裝完成")
            }
            else -> {
                settings.prefs.edit().putBoolean("install_committed", false).remove("install_confirmation").apply()
                updateStatus(context, "FAILED", "安裝未完成，本地資料仍保留；可稍後重試")
            }
        }
    }
}
