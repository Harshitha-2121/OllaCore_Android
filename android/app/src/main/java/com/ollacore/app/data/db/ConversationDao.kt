package com.ollacore.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY pinned DESC, lastEventSeq DESC, updatedAt DESC")
    fun observeInbox(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE archived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE roomId = :roomId")
    suspend fun get(roomId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE name LIKE '%' || :q || '%' OR peerPhone LIKE '%' || :q || '%' ORDER BY pinned DESC, lastEventSeq DESC LIMIT 50")
    fun search(q: String): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = :count, lastEventSeq = :lastSeq, lastMessagePreview = :preview, lastMessageAt = :at, updatedAt = :now WHERE roomId = :roomId")
    suspend fun updateLastMessage(roomId: String, preview: String?, at: String?, lastSeq: Int, count: Int, now: Long)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE roomId = :roomId")
    suspend fun clearUnread(roomId: String)

    @Query("UPDATE conversations SET muted = :muted WHERE roomId = :roomId")
    suspend fun setMuted(roomId: String, muted: Boolean)

    @Query("UPDATE conversations SET archived = :archived WHERE roomId = :roomId")
    suspend fun setArchived(roomId: String, archived: Boolean)

    @Query("UPDATE conversations SET pinned = :pinned WHERE roomId = :roomId")
    suspend fun setPinned(roomId: String, pinned: Boolean)

    @Query("DELETE FROM conversations WHERE roomId = :roomId")
    suspend fun delete(roomId: String)
}
