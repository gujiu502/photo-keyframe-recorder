package com.eva.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class DriveHttpError(val code: Int, val reason: String) : IOException("Drive $code: $reason") {
    val state get() = when {
        code == 401 || reason.contains("auth", true) || reason.contains("insufficientPermissions") -> "AUTH_REQUIRED"
        reason.contains("storageQuotaExceeded") -> "QUOTA_FULL"
        code == 429 || code >= 500 || reason.contains("rateLimit", true) -> "FAILED_RETRYABLE"
        else -> "PERMANENT_FAILURE"
    }
}
data class DriveResponse(val code: Int, val body: String, val location: String?, val range: String?)

class DriveClient(private val token: String, private val ids: android.content.SharedPreferences? = null) {
    fun call(url: String, method: String = "GET", bytes: ByteArray? = null, headers: Map<String, String> = emptyMap()): DriveResponse {
        val target = URL(url)
        require(target.protocol == "https" && target.host in setOf("www.googleapis.com", "upload.googleapis.com"))
        val c = target.openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000; c.readTimeout = 60_000
            c.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            c.setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (key, value) -> c.setRequestProperty(key, value) }
            if (bytes != null) {
                c.doOutput = true; c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) throw DriveHttpError(code, body.take(2000))
            return DriveResponse(code, body, c.getHeaderField("Location"), c.getHeaderField("Range"))
        } finally { c.disconnect() }
    }
    fun metadata(id: String) = JSONObject(call("$API/$id?fields=id,size,sha256Checksum,appProperties,trashed").body)
    fun find(properties: Map<String, String>, folder: Boolean = false): String? {
        fun quoted(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")
        val query = "trashed=false" + (if (folder) " and mimeType='application/vnd.google-apps.folder'" else "") +
            properties.entries.joinToString("") { " and appProperties has { key='${quoted(it.key)}' and value='${quoted(it.value)}' }" }
        val json = JSONObject(call("$API?q=${URLEncoder.encode(query, "UTF-8")}&fields=files(id)&pageSize=100&orderBy=createdTime").body)
        return json.getJSONArray("files").optJSONObject(0)?.getString("id")
    }
    fun ensure(name: String, parent: String?, properties: Map<String, String>, folder: Boolean): String {
        val key = "drive_id_" + sha256 { properties.toSortedMap().toString().byteInputStream() }
        var reserved = ids?.getString(key, null)
        if (reserved != null) try {
            val remote = metadata(reserved)
            if (!remote.optBoolean("trashed") && properties.all { remote.optJSONObject("appProperties")?.optString(it.key) == it.value }) {
                ids?.edit()?.putBoolean(key + "_created", true)?.commit()
                return reserved
            }
            reserved = null
        } catch (e: DriveHttpError) { if (e.code != 404) throw e else if (ids?.getBoolean(key + "_created", false) == true) reserved = null }
        find(properties, folder)?.let { ids?.edit()?.putString(key, it)?.putBoolean(key + "_created", true)?.commit(); return it }
        if (reserved == null) {
            reserved = JSONObject(call("$API/generateIds?count=1&space=drive&type=files").body).getJSONArray("ids").getString(0)
            check(ids?.edit()?.putString(key, reserved)?.putBoolean(key + "_created", false)?.commit() != false)
        }
        val json = JSONObject().put("name", name).put("appProperties", JSONObject(properties))
        json.put("id", reserved)
        if (folder) json.put("mimeType", "application/vnd.google-apps.folder")
        if (parent != null) json.put("parents", JSONArray(listOf(parent)))
        val id = try { JSONObject(call(API + "?fields=id", "POST", json.toString().toByteArray(), mapOf("Content-Type" to "application/json")).body).getString("id") }
            catch (e: DriveHttpError) { if (e.code == 409) { metadata(requireNotNull(reserved)); requireNotNull(reserved) } else throw e }
        ids?.edit()?.putBoolean(key + "_created", true)?.commit()
        return id
    }
    fun initiate(id: String, size: Long, mime: String): String = requireNotNull(call(
        "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=resumable&fields=id,size,sha256Checksum",
        "PATCH", "{}".toByteArray(), mapOf("Content-Type" to "application/json", "X-Upload-Content-Type" to mime,
            "X-Upload-Content-Length" to size.toString())).location)
    fun delete(id: String) { call("$API/$id", "DELETE") }
    companion object { const val API = "https://www.googleapis.com/drive/v3/files" }
}

fun sha256(open: () -> java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    open().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
fun uploadedOffset(range: String?, total: Long): Long {
    if (range == null) return 0
    require(range.matches(Regex("bytes=0-[0-9]+")))
    val next = range.substringAfter('-').toLong() + 1
    require(next in 0..total)
    return next
}
