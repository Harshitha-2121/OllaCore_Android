package com.ollacore.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * SQLite (via Room) source of truth for chat data.
 * DataStore keeps only: session, UI prefs, theme, chat prefs.
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ContactEntity::class,
        MediaEntity::class,
        PendingMessageEntity::class,
        StatusEntity::class,
        CommunityEntity::class,
        ChannelEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class OllacoreDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun mediaDao(): MediaDao
    abstract fun pendingDao(): PendingDao
    abstract fun statusDao(): StatusDao
    abstract fun communityDao(): CommunityDao
    abstract fun channelDao(): ChannelDao

    companion object {
        @Volatile private var INSTANCE: OllacoreDatabase? = null

        fun get(context: Context): OllacoreDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OllacoreDatabase::class.java,
                    "ollacore.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
