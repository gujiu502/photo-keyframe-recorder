package com.eva.transcription

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class NvidiaKeyStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "nvidia-asr.key"))

    fun isConfigured() = file.baseFile.isFile

    suspend fun save(value: String) = withContext(Dispatchers.IO) {
        val normalized = value.trim()
        require(KEY_FORMAT.matches(normalized)) { "請輸入有效的 NVIDIA API 金鑰" }
        val key = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        try {
            check(cipher.iv.size == IV_LENGTH)
            val ciphertext = cipher.doFinal(bytes)
            val stream = file.startWrite()
            try {
                stream.write(cipher.iv)
                stream.write(ciphertext)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        } finally {
            bytes.fill(0)
        }
    }

    internal fun read(): String {
        check(isConfigured()) { "請先設定 NVIDIA API 金鑰" }
        return try {
            val payload = file.readFully()
            check(payload.size in (IV_LENGTH + 16 + 1)..(IV_LENGTH + 16 + MAX_KEY_LENGTH))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, requireNotNull(existingKey()), GCMParameterSpec(128, payload, 0, IV_LENGTH))
            val plaintext = cipher.doFinal(payload, IV_LENGTH, payload.size - IV_LENGTH)
            try { plaintext.toString(Charsets.UTF_8).also { check(KEY_FORMAT.matches(it)) } }
            finally { plaintext.fill(0) }
        } catch (_: Exception) {
            error("無法讀取本機金鑰，請重新設定 NVIDIA API 金鑰")
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        check(!isConfigured()) { "無法移除 NVIDIA API 金鑰" }
    }

    private fun existingKey() =
        KeyStore.getInstance(STORE).apply { load(null) }.getKey(ALIAS, null) as? SecretKey

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "nvidia-asr-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val MAX_KEY_LENGTH = 256
        val KEY_FORMAT = Regex("nvapi-[A-Za-z0-9_-]{20,250}")
    }
}
