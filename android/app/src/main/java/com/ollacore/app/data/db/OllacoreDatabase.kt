package com.ollacore.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQLite (via Room) source of truth for chat data.
 * DataStore keeps only: session, UI prefs, theme, chat prefs.
 *
 * SECURITY NOTE (DATA-01 residual risk): the message history stored here is
 * PLAINTEXT on disk. The project's minSdk (26) has no SQLCipher dependency and
 * switching to an encrypted DB would require a destructive data swap; rather
 * than risk orphaning existing user data, the DB stays in app-private storage
 * (`databases/`) and is excluded from every backup path via
 * `dataExtractionRules` + `allowBackup="false"` in the manifest. A
 * rooted-device/ADB attacker can still read it; a real fix is SQLCipher (or
 * file-level encryption) behind a deliberate migration in a future release.
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
    version = 4,
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

        /**
         * BUG-02 cleanup: collapse legacy duplicate rows that share a
         * client_message_id (optimistic shell + confirmed server copy, or a
         * replayed sync). Two safe statements, id-keyed only — distinct
         * messages that merely have identical text are never touched:
         *  1) drop optimistic shells (empty createdAt) when a confirmed row
         *     for the same clientMessageId exists;
         *  2) among remaining twins, keep the newest rowid.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM messages WHERE createdAt = '' AND clientMessageId IS NOT NULL " +
                        "AND clientMessageId IN (SELECT clientMessageId FROM messages WHERE createdAt != '' GROUP BY clientMessageId)"
                )
                db.execSQL(
                    "DELETE FROM messages WHERE clientMessageId IS NOT NULL AND rowid NOT IN " +
                        "(SELECT MAX(rowid) FROM messages WHERE clientMessageId IS NOT NULL GROUP BY clientMessageId)"
                )
            }
        }

        fun get(context: Context): OllacoreDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OllacoreDatabase::class.java,
                    "ollacore.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
