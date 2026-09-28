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
        private val KEY_MUTE_UNTIL = stringPreferencesKey("mute_until_json")
        private val KEY_RECENTS = stringPreferencesKey("recent_searches_json")
        private val KEY_NOTIF_MESSAGES = stringPreferencesKey("notif_messages")
        private val KEY_NOTIF_CALLS = stringPreferencesKey("notif_calls")
        private val KEY_NOTIF_MENTIONS_ONLY = stringPreferencesKey("notif_mentions_only")
        private val KEY_NOTIF_SECURITY = stringPreferencesKey("notif_security")
        private val KEY_APP_LOCK = stringPreferencesKey("app_lock")
        private val KEY_ALIASES = stringPreferencesKey("contact_aliases_json")
        private val KEY_STARRED = stringPreferencesKey("starred_json")
        private val KEY_FAVOURITES = stringPreferencesKey("favourites_json")
        private val KEY_LISTS = stringPreferencesKey("chat_lists_json")
        private val KEY_ARCHIVED = stringPreferencesKey("archived_json")
        private val KEY_PINNED = stringPreferencesKey("pinned_rooms_json")
        private val KEY_DISAPPEAR = stringPreferencesKey("disappearing_json")
        private val KEY_BLOCKED = stringPreferencesKey("blocked_users_json")
        private val KEY_REPORTS = stringPreferencesKey("reports_json")
        private val KEY_CLEARED = stringPreferencesKey("cleared_before_json")
        private val KEY_CUSTOM = stringPreferencesKey("custom_settings_json")
        private val json = Json { ignoreUnknownKeys = true }
        private val listSer = ListSerializer(String.serializer())
        private val mapStrSer = kotlinx.serialization.builtins.MapSerializer(
            String.serializer(), String.serializer()
        )
        private val mapSer = kotlinx.serialization.builtins.MapSerializer(
            String.serializer(), ListSerializer(String.serializer())
        )
        private val mapLongSer = kotlinx.serialization.builtins.MapSerializer(
            String.serializer(), Long.serializer()
        )
        /** Mute-until epoch-ms; Long.MAX_VALUE = Always. */
        const val MUTE_ALWAYS: Long = Long.MAX_VALUE
        const val MUTE_8H_MS: Long = 8L * 60 * 60 * 1000
        const val MUTE_WEEK_MS: Long = 7L * 24 * 60 * 60 * 1000
        // Disappearing-message TTL seconds (server TTL still required for enforcement).
        const val DISAPPEAR_OFF: Long = 0L
        const val DISAPPEAR_24H: Long = 24L * 60 * 60
        const val DISAPPEAR_7D: Long = 7L * 24 * 60 * 60
        const val DISAPPEAR_90D: Long = 90L * 24 * 60 * 60
    }

    val mutedRooms: Flow<Set<String>> = context.prefsStore.data.map { prefs ->
        runCatching {
            prefs[KEY_MUTED]?.let { json.decodeFromString(listSer, it).toSet() } ?: emptySet()
        }.getOrElse { emptySet() }
    }

    private suspend fun readMuteUntil(): MutableMap<String, Long> {
        return runCatching {
            context.prefsStore.data.map { prefs ->
                prefs[KEY_MUTE_UNTIL]?.let { json.decodeFromString(mapLongSer, it) } ?: emptyMap()
            }.first().toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    val muteUntilMap: Flow<Map<String, Long>> = context.prefsStore.data.map { prefs ->
        runCatching {
            prefs[KEY_MUTE_UNTIL]?.let { json.decodeFromString(mapLongSer, it) } ?: emptyMap()
        }.getOrElse { emptyMap() }
    }

    /** True while a mute window is active; expired windows auto-clear (durations enforced). */
    suspend fun isMuted(roomId: String): Boolean {
        val until = readMuteUntil()[roomId]
        if (until != null) {
            if (until == MUTE_ALWAYS || until > System.currentTimeMillis()) return true
            setMuteUntil(roomId, null) // expiry: drop the dead window
            return false
        }
        return mutedRooms.first().contains(roomId)
    }

    suspend fun getMuteUntil(roomId: String): Long? = readMuteUntil()[roomId]

    suspend fun setMuteUntil(roomId: String, untilMs: Long?) {
        val all = readMuteUntil()
        if (untilMs == null) all.remove(roomId) else all[roomId] = untilMs
        context.prefsStore.edit {
            it[KEY_MUTE_UNTIL] = json.encodeToString(mapLongSer, all)
        }
        // Legacy boolean flag mirrors "muted right now" for older readers.
        setMuted(roomId, untilMs != null)
    }

    suspend fun setMuted(roomId: String, muted: Boolean) {
        context.prefsStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY_MUTED]?.let { json.decodeFromString(listSer, it).toSet() } ?: emptySet()
            }.getOrElse { emptySet() }.toMutableSet()
            if (muted) current.add(roomId) else current.remove(roomId)
            prefs[KEY_MUTED] = json.encodeToString(listSer, current.toList())
        }
        if (!muted) {
            // Clear any mute window directly (must not call setMuteUntil: recursion).
            val all = readMuteUntil()
            if (all.remove(roomId) != null) {
                context.prefsStore.edit {
                    it[KEY_MUTE_UNTIL] = json.encodeToString(mapLongSer, all)
                }
            }
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

    // ── Contact display aliases, per room (CLIENT-ONLY rename) ──

    private suspend fun readAliases(): MutableMap<String, String> {
        return runCatching {
            context.prefsStore.data.map { prefs ->
                prefs[KEY_ALIASES]?.let {
                    json.decodeFromString<Map<String, String>>(it)
                } ?: emptyMap()
            }.first().toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    suspend fun getAlias(roomId: String): String? = readAliases()[roomId]

    suspend fun setAlias(roomId: String, name: String?) {
        val all = readAliases()
        if (name.isNullOrBlank()) all.remove(roomId) else all[roomId] = name.trim()
        context.prefsStore.edit {
            it[KEY_ALIASES] = json.encodeToString(mapStrSer, all)
        }
    }

    // ── Starred message ids, per room (CLIENT-ONLY; bodies stay server-side) ──

    private suspend fun readStarred(): MutableMap<String, MutableList<String>> {
        return runCatching {
            context.prefsStore.data.map { prefs ->
                prefs[KEY_STARRED]?.let { json.decodeFromString(mapSer, it) } ?: emptyMap()
            }.first().mapValues { it.value.toMutableList() }.toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    suspend fun getStarredIds(roomId: String): Set<String> {
        return readStarred()[roomId]?.toSet() ?: emptySet()
    }

    /** Toggles and returns the new starred state. */
    suspend fun toggleStar(roomId: String, messageId: String): Boolean {
        val all = readStarred()
        val list = all.getOrPut(roomId) { mutableListOf() }
        val nowStarred = if (list.contains(messageId)) {
            list.remove(messageId); false
        } else {
            list.add(messageId); true
        }
        context.prefsStore.edit {
            it[KEY_STARRED] = json.encodeToString(mapSer, all)
        }
        return nowStarred
    }

    // ── Favourite chats (CLIENT-ONLY; menu state persists) ──

    val favouriteRooms: Flow<Set<String>> = context.prefsStore.data.map { prefs ->
        runCatching {
            prefs[KEY_FAVOURITES]?.let { json.decodeFromString(listSer, it).toSet() } ?: emptySet()
        }.getOrElse { emptySet() }
    }

    /** Toggles and returns the new favourite state. */
    suspend fun toggleFavourite(roomId: String): Boolean {
        var nowFav = false
        context.prefsStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY_FAVOURITES]?.let { json.decodeFromString(listSer, it).toSet() } ?: emptySet()
            }.getOrElse { emptySet() }.toMutableSet()
            nowFav = if (current.contains(roomId)) {
                current.remove(roomId); false
            } else {
                current.add(roomId); true
            }
            prefs[KEY_FAVOURITES] = json.encodeToString(listSer, current.toList())
        }
        return nowFav
    }

    // ── Chat lists / labels (CLIENT-ONLY named sets of rooms) ──

    val chatLists: Flow<Map<String, List<String>>> = context.prefsStore.data.map { prefs ->
        runCatching {
            prefs[KEY_LISTS]?.let { json.decodeFromString(mapSer, it) } ?: emptyMap()
        }.getOrElse { emptyMap() }
    }

    private suspend fun readLists(): MutableMap<String, MutableList<String>> {
        return runCatching {
            context.prefsStore.data.map { prefs ->
                prefs[KEY_LISTS]?.let { json.decodeFromString(mapSer, it) } ?: emptyMap()
            }.first().mapValues { it.value.toMutableList() }.toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    suspend fun createChatList(name: String): Boolean {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return false
        val all = readLists()
        if (all.keys.any { it.equals(clean, ignoreCase = true) }) return false
        all[clean] = mutableListOf()
        context.prefsStore.edit { it[KEY_LISTS] = json.encodeToString(mapSer, all) }
        return true
    }

    suspend fun setRoomInList(listName: String, roomId: String, member: Boolean) {
        val all = readLists()
        val list = all[listName] ?: return
        if (member) { if (!list.contains(roomId)) list.add(roomId) }
        else list.remove(roomId)
        context.prefsStore.edit { it[KEY_LISTS] = json.encodeToString(mapSer, all) }
    }

    suspend fun listsForRoom(roomId: String): Set<String> {
        return readLists().filterValues { roomId in it }.keys
    }

    // ── Archived / closed chats (CLIENT-ONLY; home hides them, section below) ──

    val archivedRooms: Flow<Set<String>> = context.prefsStore.data.map { prefs ->
        runCatching {
            prefs[KEY_ARCHIVED]?.let { json.decodeFromString(listSer, it).toSet() } ?: emptySet()
        }.getOrElse { emptySet() }
    }

    suspend fun setArchived(roomId: String, archived: Boolean) {
        context.prefsStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY_ARCHIVED]?.let { json.decodeFromString(listSer, it).toSet() } ?: emptySet()
            }.getOrElse { emptySet() }.toMutableSet()
            if (archived) current.add(roomId) else current.remove(roomId)
            prefs[KEY_ARCHIVED] = json.encodeToString(listSer, current.toList())
        }
    }

    // ── Pinned chats (CLIENT-ONLY; pinned rooms float to the top of home) ──

    val pinnedRooms: Flow<Set<String>> = context.prefsStore.data.map { prefs ->
        runCatching {
            prefs[KEY_PINNED]?.let { json.decodeFromString(listSer, it).toSet() } ?: emptySet()
        }.getOrElse { emptySet() }
    }

    suspend fun setPinned(roomId: String, pinned: Boolean) {
        context.prefsStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY_PINNED]?.let { json.decodeFromString(listSer, it).toSet() } ?: emptySet()
            }.getOrElse { emptySet() }.toMutableSet()
            if (pinned) current.add(roomId) else current.remove(roomId)
            prefs[KEY_PINNED] = json.encodeToString(listSer, current.toList())
        }
    }

    // ── Disappearing-message TTL per room (CLIENT-ONLY selection; server TTL
    //   still required before messages actually auto-delete - see backend spec) ──

    val disappearingMap: Flow<Map<String, Long>> = context.prefsStore.data.map { prefs ->
        runCatching {
            prefs[KEY_DISAPPEAR]?.let { json.decodeFromString(mapLongSer, it) } ?: emptyMap()
        }.getOrElse { emptyMap() }
    }

    suspend fun getDisappearingTtl(roomId: String): Long {
        return runCatching {
            context.prefsStore.data.map { prefs ->
                prefs[KEY_DISAPPEAR]?.let { json.decodeFromString(mapLongSer, it) } ?: emptyMap()
            }.first()[roomId] ?: DISAPPEAR_OFF
        }.getOrElse { DISAPPEAR_OFF }
    }

    suspend fun setDisappearingTtl(roomId: String, ttlSec: Long) {
        val all = runCatching {
            context.prefsStore.data.map { prefs ->
                prefs[KEY_DISAPPEAR]?.let { json.decodeFromString(mapLongSer, it) } ?: emptyMap()
            }.first().toMutableMap()
        }.getOrElse { mutableMapOf() }
        if (ttlSec <= 0) all.remove(roomId) else all[roomId] = ttlSec
        context.prefsStore.edit { it[KEY_DISAPPEAR] = json.encodeToString(mapLongSer, all) }
    }

    // ── Blocked user ids (CLIENT-ONLY enforcement until the server block list
    //   lands: incoming messages from blocked senders are dropped locally) ──

    val blockedUsers: Flow<Set<String>> = context.prefsStore.data.map { prefs ->
        runCatching {
            prefs[KEY_BLOCKED]?.let { json.decodeFromString(listSer, it).toSet() } ?: emptySet()
        }.getOrElse { emptySet() }
    }

    suspend fun setUserBlocked(userId: String, blocked: Boolean) {
        if (userId.isBlank()) return
        context.prefsStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY_BLOCKED]?.let { json.decodeFromString(listSer, it).toSet() } ?: emptySet()
            }.getOrElse { emptySet() }.toMutableSet()
            if (blocked) current.add(userId) else current.remove(userId)
            prefs[KEY_BLOCKED] = json.encodeToString(listSer, current.toList())
        }
    }

    // ── Reports (CLIENT-ONLY queue until POST /v1/directory/blocks-style
    //   report endpoint lands; each entry kept for later upload) ──

    suspend fun submitReport(roomId: String, peerLabel: String, reason: String) {
        val entry = "$roomId|$peerLabel|$reason|${System.currentTimeMillis()}"
        context.prefsStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY_REPORTS]?.let { json.decodeFromString(listSer, it) } ?: emptyList()
            }.getOrElse { emptyList() }.toMutableList()
            current.add(0, entry)
            prefs[KEY_REPORTS] = json.encodeToString(listSer, current.take(50))
        }
    }

    // ── Cleared-chat watermark (CLIENT-ONLY Clear chat: messages at/under this
    //   eventSeq stay hidden locally; server history untouched) ──

    suspend fun getClearedBefore(roomId: String): Int? {
        return runCatching {
            context.prefsStore.data.map { prefs ->
                prefs[KEY_CLEARED]?.let { json.decodeFromString(mapLongSer, it) } ?: emptyMap()
            }.first()[roomId]?.toInt()
        }.getOrNull()
    }

    suspend fun setClearedBefore(roomId: String, eventSeq: Int?) {
        val all = runCatching {
            context.prefsStore.data.map { prefs ->
                prefs[KEY_CLEARED]?.let { json.decodeFromString(mapLongSer, it) } ?: emptyMap()
            }.first().toMutableMap()
        }.getOrElse { mutableMapOf() }
        if (eventSeq == null) all.remove(roomId) else all[roomId] = eventSeq.toLong()
        context.prefsStore.edit { it[KEY_CLEARED] = json.encodeToString(mapLongSer, all) }
    }

    // ── Generic app settings (Settings screen; CLIENT-ONLY unless a backend
    //   store lands - each row states its scope honestly) ──

    private suspend fun readCustom(): MutableMap<String, String> {
        return runCatching {
            context.prefsStore.data.map { prefs ->
                prefs[KEY_CUSTOM]?.let { json.decodeFromString(mapStrSer, it) } ?: emptyMap()
            }.first().toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    fun customFlow(key: String, default: String): Flow<String> =
        context.prefsStore.data.map { prefs ->
            runCatching {
                prefs[KEY_CUSTOM]?.let { json.decodeFromString(mapStrSer, it) } ?: emptyMap()
            }.getOrElse { emptyMap() }[key] ?: default
        }

    suspend fun getCustom(key: String, default: String): String =
        readCustom()[key] ?: default

    suspend fun setCustom(key: String, value: String) {
        val all = readCustom()
        all[key] = value
        context.prefsStore.edit { it[KEY_CUSTOM] = json.encodeToString(mapStrSer, all) }
    }

    fun customBoolFlow(key: String, default: Boolean): Flow<Boolean> =
        customFlow(key, if (default) "1" else "0").map { it == "1" }

    suspend fun getCustomBool(key: String, default: Boolean): Boolean =
        getCustom(key, if (default) "1" else "0") == "1"

    suspend fun setCustomBool(key: String, value: Boolean) = setCustom(key, if (value) "1" else "0")

    object SettingsKeys {
        const val ENTER_SEND = "enter_send"
        const val KEEP_ARCHIVED = "keep_archived"
        const val AUTO_DOWNLOAD = "auto_download"
        const val UPLOAD_QUALITY = "upload_quality" // high|balanced|saver
        const val TEXT_SCALE = "text_scale" // 0.85|1.0|1.15|1.3
        const val APP_LANG = "app_lang" // "" device | en|hi|es|fr|de|ar
        const val MEDIA_VISIBILITY = "media_visibility"
        const val DISAPPEAR_DEFAULT = "disappear_default" // seconds, 0=off
        const val PRIV_LASTSEEN = "privacy_lastseen"
        const val PRIV_PHOTO = "privacy_photo"
        const val PRIV_ABOUT = "privacy_about"
        const val PRIV_STATUS = "privacy_status"
        const val PRIV_RECEIPTS = "privacy_receipts"
        const val PRIV_GROUPS = "privacy_groups"
        const val PRIV_CALLS = "privacy_calls"
        const val NOTIF_TONE = "notif_tone" // "" system default | ringtone uri
        const val NOTIF_VIB = "notif_vib" // default|off|short|long
        const val NOTIF_POPUP = "notif_popup" // 0|1 heads-up
        const val LESS_DATA_CALLS = "less_data_calls"
    }
}
