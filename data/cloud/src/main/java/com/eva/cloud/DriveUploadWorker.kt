package com.eva.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.eva.database.RecorderDataBase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

@HiltWorker
class DriveUploadWorker @AssistedInject constructor(@Assisted context: Context, @Assisted params: WorkerParameters,
    private val backup: DriveBackup, private val db: RecorderDataBase) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("session_id") ?: return Result.failure()
        return try {
            setForeground(getForegroundInfo())
            backup.upload(id); Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val state = when (e) { is DriveAuthorizationRequired -> "AUTH_REQUIRED"; is DriveHttpError -> e.state; is com.google.android.gms.common.api.ApiException -> if (e.statusCode in setOf(4, 16, 12501)) "AUTH_REQUIRED" else "FAILED_RETRYABLE"; is IOException -> "FAILED_RETRYABLE"; else -> "PERMANENT_FAILURE" }
            val row = db.cloudDao().backup(id)
            if (row != null && row.state != "DELETED" && !row.state.startsWith("DELETE_"))
                db.cloudDao().put(row.copy(state = state, errorCode = e.message?.take(500)))
            for (file in db.cloudDao().files(id).filter { it.state == "UPLOADING" }) db.cloudDao().putFile(file.copy(state = "FAILED_RETRYABLE"))
            if (state == "FAILED_RETRYABLE" || e.message?.contains("照片仍在保存") == true) Result.retry() else Result.failure()
        }
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("drive_backup", "雲端備份", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "drive_backup").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("正在備份課程錄音").setContentText("本地錄音與照片仍保留在裝置上").setOngoing(true).build()
        return ForegroundInfo(4000 + id.hashCode().and(0x7fff), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
