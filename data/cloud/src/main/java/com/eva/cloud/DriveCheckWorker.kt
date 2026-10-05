package com.eva.cloud

import android.content.Context
import androidx.room.withTransaction
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eva.database.AccountSettings
import com.eva.database.RecorderDataBase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

@HiltWorker
class DriveCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backup: DriveBackup,
    private val db: RecorderDataBase,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        backup.checkCompleted()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val authRequired = e is DriveAuthorizationRequired || e is DriveHttpError && e.state == "AUTH_REQUIRED" ||
            e is com.google.android.gms.common.api.ApiException && e.statusCode in setOf(4, 16, 12501)
        if (authRequired) {
            val accountId = AccountSettings(applicationContext).accountId
            db.withTransaction {
                for (row in db.cloudDao().all().filter { it.accountId == accountId && it.state == "COMPLETE" })
                    db.cloudDao().put(row.copy(state = "AUTH_REQUIRED", errorCode = "請重新授權 Google Drive，以檢查備份"))
            }
            Result.failure()
        } else if (e is DriveHttpError) {
            if (e.state == "FAILED_RETRYABLE") Result.retry() else Result.failure()
        } else if (e is IOException || e is com.google.android.gms.common.api.ApiException) Result.retry()
        else Result.failure()
    }
}
