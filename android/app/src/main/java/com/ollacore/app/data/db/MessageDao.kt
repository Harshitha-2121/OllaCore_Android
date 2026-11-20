package com.ollacore.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE roomId = :roomId ORDER BY eventSeq ASC, rowid ASC LIMIT :limit OFFSET :offset")
    suspend fun pageByRoom(roomId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE roomId = :roomId ORDER BY eventSeq ASC")
    fun observeByRoom(roomId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE roomId = :roomId AND bodyJson LIKE '%' || :q || '%' ORDER BY eventSeq DESC LIMIT 50")
    suspend fun searchByRoom(roomId: String, q: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE msgId = :msgId")
    suspend fun get(msgId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE clientMessageId = :clientId LIMIT 1")
    suspend fun getByClientId(clientId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MessageEntity)

    @Query("UPDATE messages SET statusRank = :rank WHERE msgId = :msgId AND statusRank < :rank")
    suspend fun upgradeStatus(msgId: String, rank: Int)

    @Query("UPDATE messages SET statusRank = :rank WHERE clientMessageId = :clientId AND statusRank < :rank")
    suspend fun upgradeStatusByClientId(clientId: String, rank: Int)

    @Query("DELETE FROM messages WHERE msgId = :msgId")
    suspend fun delete(msgId: String)

    @Query("DELETE FROM messages WHERE roomId = :roomId")
    suspend fun deleteByRoom(roomId: String)
}
