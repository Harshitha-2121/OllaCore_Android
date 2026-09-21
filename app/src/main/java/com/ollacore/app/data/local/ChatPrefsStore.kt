package com.ollacore.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.prefsStore by preferencesDataStore(name = "ollacore_chat_prefs")

/**
 * CLIENT-ONLY per-chat preferences (no backend): mute flags now;
 * archive/pin/unread marks follow this same store later.
 */
class ChatPrefsStore(private val context: Context) {

    companion object {
        private val KEY_MUTED = stringPreferencesKey("muted_rooms_json")
        private val KEY_RECENTS = stringPreferencesKey("recent_searches_json")
        private val KEY_NOTIF_MESSAGES = stringPreferencesKey("notif_messages")
        private val KEY_NOTIF_CALLS = stringPreferencesKey("notif_calls")
        private val KEY_NOTIF_MENTIONS_ONLY = stringPreferencesKey("notif_mentions_only")
        private val KEY_NOTIF_SECURITY = stringPreferencesKey("notif_security")
        private val KEY_APP_LOCK = stringPreferencesKey("app_lock")
        private val json = Json { ignoreUnknownKeys = true }
        private val listSer = ListSerializer(String.serializer())
    }

    val mutedRooms: Flow<Set<String>> = context.prefsStore.data.map { prefs ->
        runCatching {
            prefs[KEY_MUTED]?.let { json.decodeFromString(listSer, it).toSet() } ?: emptySet()
        }.getOrElse { emptySet() }
    }

    suspend fun isMuted(roomId: String): Boolean {
        return mutedRooms.first().contains(roomId)
    }

    suspend fun setMuted(roomId: String, muted: Boolean) {
        context.prefsStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY_MUTED]?.let { json.decodeFromString(listSer, it).toSet() } ?: emptySet()
            }.getOrElse { emptySet() }.toMutableSet()
            if (muted) current.add(roomId) else current.remove(roomId)
            prefs[KEY_MUTED] = json.encodeToString(listSer, current.toList())
        }
    }

    // ── Recent searches (spec 22, CLIENT-ONLY) ──

    val recentSearches: Flow<List<String>> = context.prefsStore.data.map { prefs ->
        runCatching {
            prefs[KEY_RECENTS]?.let { json.decodeFromString(listSer, it) } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    suspend fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        context.prefsStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY_RECENTS]?.let { json.decodeFromString(listSer, it) } ?: emptyList()
            }.getOrElse { emptyList() }.toMutableList()
            current.remove(q)
            current.add(0, q)
            prefs[KEY_RECENTS] = json.encodeToString(listSer, current.take(8))
        }
    }

    suspend fun clearRecentSearches() {
        context.prefsStore.edit { prefs -> prefs.remove(KEY_RECENTS) }
    }

    // ── Notification categories (spec 28, CLIENT-ONLY, enforced in FCM service) ──

    private fun boolFlow(key: androidx.datastore.preferences.core.Preferences.Key<String>, default: Boolean): Flow<Boolean> =
        context.prefsStore.data.map { prefs ->
            prefs[key]?.toBooleanStrictOrNull() ?: default
        }

    val notifMessages: Flow<Boolean> = boolFlow(KEY_NOTIF_MESSAGES, true)
    val notifCalls: Flow<Boolean> = boolFlow(KEY_NOTIF_CALLS, true)
    val notifMentionsOnly: Flow<Boolean> = boolFlow(KEY_NOTIF_MENTIONS_ONLY, false)
    val notifSecurity: Flow<Boolean> = boolFlow(KEY_NOTIF_SECURITY, true)

    suspend fun setNotif(key: String, value: Boolean) {
        val prefKey = when (key) {
            "messages" -> KEY_NOTIF_MESSAGES
            "calls" -> KEY_NOTIF_CALLS
            "mentions_only" -> KEY_NOTIF_MENTIONS_ONLY
            "security" -> KEY_NOTIF_SECURITY
            else -> return
        }
        context.prefsStore.edit { it[prefKey] = value.toString() }
    }

    suspend fun getNotif(key: String, default: Boolean): Boolean {
        val prefKey = when (key) {
            "messages" -> KEY_NOTIF_MESSAGES
            "calls" -> KEY_NOTIF_CALLS
            "mentions_only" -> KEY_NOTIF_MENTIONS_ONLY
            "security" -> KEY_NOTIF_SECURITY
            else -> return default
        }
        return context.prefsStore.data.map { it[prefKey]?.toBooleanStrictOrNull() ?: default }.first()
    }

    // ── App lock (spec 25, CLIENT-ONLY device credential gate) ──

    val appLock: Flow<Boolean> = context.prefsStore.data.map { prefs ->
        prefs[KEY_APP_LOCK]?.toBooleanStrictOrNull() ?: false
    }

    suspend fun setAppLock(enabled: Boolean) {
        context.prefsStore.edit { it[KEY_APP_LOCK] = enabled.toString() }
    }
}
