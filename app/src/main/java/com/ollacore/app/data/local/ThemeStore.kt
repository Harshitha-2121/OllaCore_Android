package com.ollacore.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeStore by preferencesDataStore(name = "ollacore_theme")

/** Spec 31: user-selectable theme (CLIENT-ONLY). */
enum class ThemeMode { BLUE, GREEN, PURPLE, DARK, SYSTEM }

class ThemeStore(private val context: Context) {

    companion object {
        private val KEY_MODE = stringPreferencesKey("theme_mode")
    }

    val mode: Flow<ThemeMode> = context.themeStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[KEY_MODE] ?: ThemeMode.DARK.name) }
            .getOrElse { ThemeMode.DARK }
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeStore.edit { it[KEY_MODE] = mode.name }
    }
}
