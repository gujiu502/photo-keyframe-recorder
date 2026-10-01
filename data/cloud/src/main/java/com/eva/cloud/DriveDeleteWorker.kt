package com.eva.cloud

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eva.database.RecorderDataBase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class DriveDeleteWorker @AssistedInject constructor(@Assisted context: Context, @Assisted params: WorkerParameters,
    private val backup: DriveBackup, private val db: RecorderDataBase) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("session_id") ?: return Result.failure()
        return try { backup.deleteCloud(id); Result.success() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val auth = e is DriveAuthorizationRequired || (e is DriveHttpError && e.state == "AUTH_REQUIRED")
            db.cloudDao().backup(id)?.let { db.cloudDao().put(it.copy(state = if (auth) "DELETE_AUTH_REQUIRED" else "DELETE_QUEUED", errorCode = e.message?.take(500))) }
            if (auth) Result.failure() else Result.retry()
        }
    }
}
