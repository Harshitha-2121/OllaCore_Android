package com.ollacore.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
    }

    val sessionToken: Flow<String?> = context.dataStore.data.map { it[KEY_SESSION_TOKEN] }
    val userId: Flow<String?> = context.dataStore.data.map { it[KEY_USER_ID] }
    val phone: Flow<String?> = context.dataStore.data.map { it[KEY_PHONE] }
    val displayName: Flow<String?> = context.dataStore.data.map { it[KEY_DISPLAY_NAME] }
    val about: Flow<String?> = context.dataStore.data.map { it[KEY_ABOUT] }
    val avatarUrl: Flow<String?> = context.dataStore.data.map { it[KEY_AVATAR_URL] }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[KEY_ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingDone(done: Boolean = true) {
        context.dataStore.edit { it[KEY_ONBOARDING_DONE] = done }
    }

    suspend fun saveSession(token: String, userId: String, phone: String, displayName: String?) {
        context.dataStore.edit {
            it[KEY_SESSION_TOKEN] = token
            it[KEY_USER_ID] = userId
            it[KEY_PHONE] = phone
            if (displayName != null) it[KEY_DISPLAY_NAME] = displayName
        }
    }

    suspend fun updateDisplayName(displayName: String) {
        context.dataStore.edit { it[KEY_DISPLAY_NAME] = displayName }
    }

    suspend fun updateProfile(displayName: String?, about: String?, avatarUrl: String?) {
        context.dataStore.edit {
            if (displayName != null) it[KEY_DISPLAY_NAME] = displayName
            if (about != null) it[KEY_ABOUT] = about
            if (avatarUrl != null) it[KEY_AVATAR_URL] = avatarUrl
        }
    }

    suspend fun clear() {
        // Logout clears the session but keeps onboarding (no re-onboarding after logout).
        val keepOnboarding = onboardingDone.first()
        context.dataStore.edit {
            it.clear()
            it[KEY_ONBOARDING_DONE] = keepOnboarding
        }
    }
}
