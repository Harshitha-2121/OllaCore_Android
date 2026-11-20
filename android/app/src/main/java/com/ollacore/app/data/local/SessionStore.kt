package com.ollacore.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.GeneralSecurityException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ollacore_session")

class SessionStore(private val context: Context) {

    companion object {
        private val KEY_SESSION_TOKEN = stringPreferencesKey("session_token")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_PHONE = stringPreferencesKey("phone")
        private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        private val KEY_ABOUT = stringPreferencesKey("about")
        private val KEY_AVATAR_URL = stringPreferencesKey("avatar_url")
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")

        private const val ENC_PREFS_NAME = "ollacore_session_secure"
        private const val E_KEY_SESSION_TOKEN = "session_token"
        private const val E_KEY_USER_ID = "user_id"
        private const val E_KEY_PHONE = "phone"
        private const val E_KEY_DISPLAY_NAME = "display_name"
        private const val E_KEY_ABOUT = "about"
        private const val E_KEY_AVATAR_URL = "avatar_url"
        private const val E_KEY_MIGRATED = "migrated_from_datastore"
    }

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null
    @Volatile
    private var migrationDone = false

    private val _tokenFlow = MutableStateFlow<String?>(null)
    private val _userIdFlow = MutableStateFlow<String?>(null)
    private val _phoneFlow = MutableStateFlow<String?>(null)
    private val _displayNameFlow = MutableStateFlow<String?>(null)
    private val _aboutFlow = MutableStateFlow<String?>(null)
    private val _avatarUrlFlow = MutableStateFlow<String?>(null)

    val sessionToken: Flow<String?> get() = _tokenFlow.asStateFlow()
    val userId: Flow<String?> get() = _userIdFlow.asStateFlow()
    val phone: Flow<String?> get() = _phoneFlow.asStateFlow()
    val displayName: Flow<String?> get() = _displayNameFlow.asStateFlow()
    val about: Flow<String?> get() = _aboutFlow.asStateFlow()
    val avatarUrl: Flow<String?> get() = _avatarUrlFlow.asStateFlow()
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[KEY_ONBOARDING_DONE] ?: false }

    init {
        // Paint whatever the encrypted store already holds synchronously so the
        // auth gate does not blink to the phone screen on cold start.
        runCatching { loadFromEncrypted() }
        // One-time AUTH-01 migration from the legacy plaintext DataStore. It is
        // suspend (DataStore), so it runs on an app-lifetime IO scope and then
        // re-reads the encrypted values.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { migrateIfNeeded() }
            runCatching { loadFromEncrypted() }
        }
    }

    private fun getEncryptedPrefs(): SharedPreferences? {
        if (encryptedPrefs != null) return encryptedPrefs
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                ENC_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            encryptedPrefs
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun migrateIfNeeded() {
        if (migrationDone) return
        val prefs = getEncryptedPrefs() ?: return
        if (prefs.getBoolean(E_KEY_MIGRATED, false)) {
            migrationDone = true
            return
        }
        try {
            val current = context.dataStore.data.first()
            val token = current[KEY_SESSION_TOKEN]
            val userId = current[KEY_USER_ID]
            val phone = current[KEY_PHONE]
            val displayName = current[KEY_DISPLAY_NAME]
            val about = current[KEY_ABOUT]
            val avatarUrl = current[KEY_AVATAR_URL]
            val editor = prefs.edit()
            token?.let { editor.putString(E_KEY_SESSION_TOKEN, it) }
            userId?.let { editor.putString(E_KEY_USER_ID, it) }
            phone?.let { editor.putString(E_KEY_PHONE, it) }
            displayName?.let { editor.putString(E_KEY_DISPLAY_NAME, it) }
            about?.let { editor.putString(E_KEY_ABOUT, it) }
            avatarUrl?.let { editor.putString(E_KEY_AVATAR_URL, it) }
            editor.putBoolean(E_KEY_MIGRATED, true)
            editor.apply()
            // Clear plaintext DataStore values after successful migration
            context.dataStore.edit { it.clear() }
            // Restore onboarding if needed
            val keepOnboarding = current[KEY_ONBOARDING_DONE] ?: false
            if (keepOnboarding) {
                context.dataStore.edit { it[KEY_ONBOARDING_DONE] = true }
            }
            migrationDone = true
        } catch (_: Exception) {
            migrationDone = true
        }
    }

    private fun loadFromEncrypted() {
        val prefs = getEncryptedPrefs() ?: return
        _tokenFlow.value = prefs.getString(E_KEY_SESSION_TOKEN, null)
        _userIdFlow.value = prefs.getString(E_KEY_USER_ID, null)
        _phoneFlow.value = prefs.getString(E_KEY_PHONE, null)
        _displayNameFlow.value = prefs.getString(E_KEY_DISPLAY_NAME, null)
        _aboutFlow.value = prefs.getString(E_KEY_ABOUT, null)
        _avatarUrlFlow.value = prefs.getString(E_KEY_AVATAR_URL, null)
    }

    suspend fun setOnboardingDone(done: Boolean = true) {
        context.dataStore.edit { it[KEY_ONBOARDING_DONE] = done }
    }

    suspend fun saveSession(token: String, userId: String, phone: String, displayName: String?) {
        val prefs = getEncryptedPrefs()
        prefs?.edit()?.apply {
            putString(E_KEY_SESSION_TOKEN, token)
            putString(E_KEY_USER_ID, userId)
            putString(E_KEY_PHONE, phone)
            if (displayName != null) putString(E_KEY_DISPLAY_NAME, displayName)
            apply()
        }
        _tokenFlow.value = token
        _userIdFlow.value = userId
        _phoneFlow.value = phone
        if (displayName != null) _displayNameFlow.value = displayName
    }

    suspend fun updateDisplayName(displayName: String) {
        getEncryptedPrefs()?.edit()?.putString(E_KEY_DISPLAY_NAME, displayName)?.apply()
        _displayNameFlow.value = displayName
    }

    suspend fun updateProfile(displayName: String?, about: String?, avatarUrl: String?) {
        val prefs = getEncryptedPrefs()
        val editor = prefs?.edit()
        if (displayName != null) {
            editor?.putString(E_KEY_DISPLAY_NAME, displayName)
            _displayNameFlow.value = displayName
        }
        if (about != null) {
            editor?.putString(E_KEY_ABOUT, about)
            _aboutFlow.value = about
        }
        if (avatarUrl != null) {
            editor?.putString(E_KEY_AVATAR_URL, avatarUrl)
            _avatarUrlFlow.value = avatarUrl
        }
        editor?.apply()
    }

    suspend fun clear() {
        // Logout clears the session but keeps onboarding (no re-onboarding after logout).
        val keepOnboarding = onboardingDone.first()
        getEncryptedPrefs()?.edit()?.clear()?.apply()
        _tokenFlow.value = null
        _userIdFlow.value = null
        _phoneFlow.value = null
        _displayNameFlow.value = null
        _aboutFlow.value = null
        _avatarUrlFlow.value = null
        context.dataStore.edit {
            it.clear()
            if (keepOnboarding) it[KEY_ONBOARDING_DONE] = true
        }
    }
}
