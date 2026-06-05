package com.techentrance.languageapp.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class SessionManager(context: Context) {

    private val prefs: SharedPreferences = buildPrefs(context)

    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_FILE = "langcall_secure_prefs"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_NAME = "name"
        private const val KEY_EMAIL = "email"
        private const val KEY_PHONE = "phone"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_SERVER_URL = "server_url"

        private fun buildPrefs(context: Context): SharedPreferences {
            return try {
                // security-crypto:1.0.0 stable API uses MasterKeys (not MasterKey.Builder)
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    PREFS_FILE,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Exception) {
                // Fall back to plain prefs if Keystore is unavailable (rare on modern devices)
                Log.e(TAG, "EncryptedSharedPreferences init failed, falling back: ${e.message}")
                context.getSharedPreferences(PREFS_FILE + "_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    var userId: Int?
        get() = prefs.getInt(KEY_USER_ID, -1).takeIf { it != -1 }
        set(v) = if (v == null) prefs.edit().remove(KEY_USER_ID).apply()
                 else prefs.edit().putInt(KEY_USER_ID, v).apply()

    var userName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(v) = prefs.edit().putString(KEY_NAME, v).apply()

    var userEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(v) = prefs.edit().putString(KEY_EMAIL, v).apply()

    var userPhone: String?
        get() = prefs.getString(KEY_PHONE, null)
        set(v) = prefs.edit().putString(KEY_PHONE, v).apply()

    var userLanguage: String?
        get() = prefs.getString(KEY_LANGUAGE, "hindi")
        set(v) = prefs.edit().putString(KEY_LANGUAGE, v).apply()

    /** Persisted server URL so the user doesn't have to re-enter it on every launch. */
    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(v) = prefs.edit().putString(KEY_SERVER_URL, v).apply()

    val isLoggedIn: Boolean get() = token != null
    val bearerToken: String get() = "Bearer ${token.orEmpty()}"

    fun save(response: TokenResponse) {
        token = response.access_token
        userId = response.user.id
        userName = response.user.name
        userEmail = response.user.email
        userPhone = response.user.phone
        userLanguage = response.user.preferred_language
    }

    fun clear() = prefs.edit().clear().apply()
}
