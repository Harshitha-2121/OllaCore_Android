package com.ollacore.app.data.di

import android.content.Context
import com.ollacore.app.data.e2ee.E2eeManager
import com.ollacore.app.data.e2ee.ForwardSecrecyManager
import com.ollacore.app.data.local.CallLogStore
import com.ollacore.app.data.local.ChatPrefsStore
import com.ollacore.app.data.local.ChatThemeStore
import com.ollacore.app.data.local.GroupEventStore
import com.ollacore.app.data.local.LocalContactsStore
import com.ollacore.app.data.local.MessageStatusStore
import com.ollacore.app.data.local.SessionStore
import com.ollacore.app.data.push.PushConfigManager
import com.ollacore.app.data.remote.OllacoreApi
import com.ollacore.app.data.repository.ChatRepository
import com.ollacore.app.data.repository.DirectoryRepository

class AppContainer(private val context: Context) {
    val sessionStore = SessionStore(context)

    // CLIENT-ONLY stores (no backend): call history now; archive/mute/pin follow this pattern.
    val callLogStore = CallLogStore(context)
    val chatPrefsStore = ChatPrefsStore(context)
    val localContactsStore = LocalContactsStore(context)
    val groupEventStore = GroupEventStore(context)
    val messageStatusStore = MessageStatusStore(context)
    val chatThemeStore = ChatThemeStore(context)

    val api = OllacoreApi(
        apiBase = "https://api.ollacore.com/v1",
        appId = "da_3a0a2cfaabd34b7dbfb4ecd5033b0c5f"
    )

    val directoryRepository = DirectoryRepository(api)
    val chatRepository = ChatRepository(api)

    val e2eeManager = E2eeManager(api)
    val forwardSecrecyManager = ForwardSecrecyManager()
    val pushConfigManager = PushConfigManager()
}
