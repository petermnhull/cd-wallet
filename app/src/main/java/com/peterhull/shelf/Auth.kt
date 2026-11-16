package com.peterhull.shelf

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** Authorization-code + PKCE login for the Web API (library reads). No client secret needed. */
class Auth(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    val isLoggedIn: Boolean get() = prefs.getString(KEY_REFRESH, null) != null

    fun startLogin(activity: Activity) {
        val verifier = randomString(64)
        val state = randomString(16)
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", Config.CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", Config.REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", Config.SCOPES)
            .build()
        CustomTabsIntent.Builder().build().launchUrl(activity, uri)
    }

    suspend fun handleRedirect(uri: Uri) {
        uri.getQueryParameter("error")?.let { throw IOException("Login failed: $it") }
        val code = uri.getQueryParameter("code") ?: throw IOException("Login failed: no code returned")
        if (uri.getQueryParameter("state") != prefs.getString(KEY_STATE, null)) {
            throw IOException("Login failed: state mismatch, try again")
        }
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: throw IOException("Login failed: try again")
        mutex.withLock {
            requestToken(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to Config.REDIRECT_URI,
                    "client_id" to Config.CLIENT_ID,
                    "code_verifier" to verifier,
                )
            )
        }
        prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
    }

    suspend fun accessToken(): String = mutex.withLock {
        val cached = prefs.getString(KEY_ACCESS, null)
        if (cached != null && System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES_AT, 0)) return cached
        val refresh = prefs.getString(KEY_REFRESH, null) ?: throw IOException("Not logged in")
        requestToken(
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to Config.CLIENT_ID,
            )
        )
        prefs.getString(KEY_ACCESS, null) ?: throw IOException("Not logged in")
    }

    fun logout() = prefs.edit().clear().apply()

    private suspend fun requestToken(form: Map<String, String>) {
        val res = Http.postForm("https://accounts.spotify.com/api/token", form)
        if (res.code != 200) {
            // A rejected refresh token means the user has to log in again.
            if (res.code == 400 && form["grant_type"] == "refresh_token") logout()
            throw IOException("Token request failed (${res.code}): ${res.body}")
        }
        val json = JSONObject(res.body)
        val editor = prefs.edit()
            .putString(KEY_ACCESS, json.getString("access_token"))
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + (json.getLong("expires_in") - 60) * 1000)
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { editor.putString(KEY_REFRESH, it) }
        editor.apply()
    }

    private companion object {
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_EXPIRES_AT = "expiresAt"
        const val KEY_VERIFIER = "verifier"
        const val KEY_STATE = "state"

        fun randomString(bytes: Int): String = base64Url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

        fun base64Url(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
