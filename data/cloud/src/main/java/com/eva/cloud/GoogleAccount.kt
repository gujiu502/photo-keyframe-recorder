package com.eva.cloud

import android.accounts.Account
import android.content.Context
import android.content.MutableContextWrapper
import android.util.Base64
import androidx.credentials.*
import com.eva.database.AccountSettings
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.SecureRandom

const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
class DriveAuthorizationRequired : Exception("Google Drive 需要重新授權")

class GoogleAccount(private val context: Context) {
    private val settings = AccountSettings(context)
    suspend fun signIn(activity: Context, clientId: String, automatic: Boolean = false): Pair<String, String> {
        check(clientId.isNotBlank()) { "此版本尚未配置 Google 登入，請聯絡開發者" }
        val nonce = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val option = if (automatic) GetGoogleIdOption.Builder().setServerClientId(clientId)
            .setFilterByAuthorizedAccounts(true).setAutoSelectEnabled(true).setNonce(nonce).build()
        else GetSignInWithGoogleOption.Builder(clientId).setNonce(nonce).build()
        val credential = CredentialManager.create(context).getCredential(MutableContextWrapper(activity),
            GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
        check(credential is CustomCredential && credential.type in setOf(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL))
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        // Credential Manager is the trusted identity provider; this app has no backend.
        val claims = JSONObject(String(Base64.decode(google.idToken.split('.')[1], Base64.URL_SAFE)))
        check(claims.getString("aud") == clientId && claims.getString("nonce") == nonce &&
            claims.getString("iss") in setOf("accounts.google.com", "https://accounts.google.com") && claims.getLong("exp") * 1000 > System.currentTimeMillis()) { "Google 身分驗證失敗" }
        return claims.getString("sub") to google.id
    }
    fun request(email: String) = AuthorizationRequest.builder().setAccount(Account(email, "com.google"))
        .setRequestedScopes(listOf(Scope(DRIVE_SCOPE))).build()
    suspend fun token(email: String): String {
        val result = Identity.getAuthorizationClient(context).authorize(request(email)).await()
        if (result.hasResolution() || !result.grantedScopes.contains(DRIVE_SCOPE)) throw DriveAuthorizationRequired()
        return result.accessToken ?: throw DriveAuthorizationRequired()
    }
    suspend fun clearToken(token: String) {
        Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }
}
