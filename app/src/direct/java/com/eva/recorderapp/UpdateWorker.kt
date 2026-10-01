package com.eva.recorderapp

import android.content.Context
import android.content.pm.PackageManager
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.eva.cloud.sha256
import com.eva.database.AccountSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

private const val REPO = "https://api.github.com/repos/gujiu502/photo-keyframe-recorder"
private const val ASSETS = "https://github.com/gujiu502/photo-keyframe-recorder/releases/download/"
private const val MAX_APK = 256L * 1024 * 1024

/** Only this repository can provide update metadata. Redirects never forward credentials. */
fun updateConnection(url: String): HttpURLConnection {
    var current = URL(url)
    repeat(6) {
        require(current.protocol == "https" && current.userInfo == null && current.port in setOf(-1, 443) &&
            current.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "更新來源不可信" }
        val c = (current.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false; connectTimeout = 30_000; readTimeout = 60_000
            setRequestProperty("User-Agent", "PhotoKeyframeRecorder/${BuildConfig.VERSION_NAME}")
        }
        if (c.responseCode in setOf(301, 302, 303, 307, 308)) {
            val location = c.getHeaderField("Location"); c.disconnect(); current = URL(current, requireNotNull(location))
        } else { if (c.responseCode !in 200..299) { val code = c.responseCode; c.disconnect(); throw IOException("更新伺服器回應 $code") }; return c }
    }
    error("更新重新導向過多")
}
fun readUpdateJson(url: String): String {
    val c = updateConnection(url)
    return try { c.inputStream.use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 512 * 1024); out.write(buffer, 0, n) }
        out.toString("UTF-8")
    } }
    finally { c.disconnect() }
}

@HiltWorker
class UpdateWorker @AssistedInject constructor(@Assisted context: Context, @Assisted params: WorkerParameters, private val db: com.eva.database.RecorderDataBase) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = AccountSettings(applicationContext)
        if (!settings.agreementAccepted) return@withContext Result.success()
        try {
            updateStatus(applicationContext, "CHECKING", "正在檢查更新")
            val releases = JSONArray(readUpdateJson("$REPO/releases?per_page=20"))
            val release = (0 until releases.length()).map { releases.getJSONObject(it) }.firstOrNull {
                !it.getBoolean("draft") && (settings.prefs.getBoolean("beta_channel", false) || !it.getBoolean("prerelease"))
            } ?: run { updateStatus(applicationContext, "IDLE", "目前沒有可用更新"); return@withContext Result.success() }
            val assets = release.getJSONArray("assets")
            val manifestUrl = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name") == "update.json" }?.getString("browser_download_url")
                ?: run { updateStatus(applicationContext, "IDLE", "目前沒有可用更新"); return@withContext Result.success() }
            require(manifestUrl.startsWith(ASSETS))
            val json = JSONObject(readUpdateJson(manifestUrl))
            require(json.getInt("schemaVersion") == 1)
            val version = json.getLong("versionCode")
            val minimum = json.getLong("minSupportedVersionCode")
            require(version > 0 && minimum in 1..version)
            if (version <= BuildConfig.VERSION_CODE) {
                settings.prefs.edit().putBoolean("mandatory_update", false).apply()
                updateStatus(applicationContext, "IDLE", "已是最新版本"); return@withContext Result.success()
            }
            val url = json.getString("apkUrl"); val hash = json.getString("sha256")
            require(url.startsWith(ASSETS) && url.endsWith(".apk") && hash.matches(Regex("[a-fA-F0-9]{64}")))
            settings.prefs.edit().putString("update_manifest", json.toString()).putBoolean("mandatory_update", BuildConfig.VERSION_CODE < minimum).apply()
            val dir = File(applicationContext.filesDir, "updates").apply { mkdirs() }
            val apk = File(dir, "update-$version.apk")
            if (!apk.exists() || sha256 { apk.inputStream() } != hash.lowercase()) {
                updateStatus(applicationContext, "DOWNLOADING", "正在下載更新 ${json.getString("versionName")}")
                val temp = File(dir, "update-$version.tmp")
                val c = updateConnection(url)
                try {
                    val size = c.contentLengthLong
                    require(size in 1..MAX_APK && dir.usableSpace > size * 2 + 50L * 1024 * 1024) { "儲存空間不足或安裝檔大小無效" }
                    c.inputStream.use { input -> temp.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024); var received = 0L
                        while (true) { coroutineContext.ensureActive(); val n = input.read(buffer); if (n < 0) break; received += n; check(received <= size); out.write(buffer, 0, n) }
                        check(received == size) { "更新下載未完成" }; out.fd.sync()
                    } }
                    updateStatus(applicationContext, "VERIFYING", "正在驗證更新")
                    require(sha256 { temp.inputStream() } == hash.lowercase()) { "更新 SHA-256 不符，已拒絕安裝" }
                    require(temp.renameTo(apk))
                } finally { c.disconnect() }
            }
            verifyApk(applicationContext, apk, version, hash)
            settings.prefs.edit().putString("update_apk", apk.absolutePath).apply()
            updateStatus(applicationContext, "READY_TO_INSTALL", "更新已準備好，錄音結束後安裝")
            updateWhenIdle(applicationContext, db)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            updateStatus(applicationContext, "FAILED", "更新失敗：${e.message}。本地資料仍保留")
            if (e is IOException) Result.retry() else Result.failure()
        }
    }
}

fun verifyApk(context: Context, apk: File, version: Long, hash: String) {
    require(sha256 { apk.inputStream() } == hash.lowercase()) { "更新 SHA-256 不符" }
    val pm = context.packageManager
    val archive = requireNotNull(pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)) { "更新 APK 無效" }
    val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    require(archive.packageName == context.packageName && archive.longVersionCode == version && version > installed.longVersionCode) { "更新套件或版本不符" }
    fun identities(info: android.content.pm.PackageInfo) = requireNotNull(info.signingInfo).apkContentsSigners.map { sig -> sha256 { sig.toByteArray().inputStream() } }.toSet()
    require(identities(archive).isNotEmpty() && identities(archive) == identities(installed)) { "更新簽名與目前版本不同，已拒絕安裝" }
}

fun enqueueUpdate(context: Context, replace: Boolean = false) {
    val settings = AccountSettings(context)
    if (!settings.agreementAccepted) return
    val constraints = Constraints.Builder().setRequiredNetworkType(if (settings.prefs.getBoolean("updates_wifi_only", false)) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true).build()
    val manager = WorkManager.getInstance(context)
    manager.enqueueUniqueWork("check-update-now", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(constraints).build())
    manager.enqueueUniquePeriodicWork("check-update-daily", ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build())
}
