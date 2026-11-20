package com.ollacore.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE phone LIKE '%' || :q || '%' OR displayName LIKE '%' || :q || '%' OR normalizedPhone LIKE '%' || :q || '%' LIMIT 50")
    fun search(q: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE userId = :userId")
    suspend fun get(userId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ContactEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ContactEntity)

    @Query("DELETE FROM contacts WHERE userId = :userId")
    suspend fun delete(userId: String)

    @Query("DELETE FROM contacts WHERE localContactId = :localId")
    suspend fun deleteByLocalId(localId: String)

    @Query("SELECT * FROM contacts WHERE localContactId = :localId LIMIT 1")
    suspend fun getByLocalId(localId: String): ContactEntity?
}

@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE messageId = :messageId")
    suspend fun getByMessage(messageId: String): List<MediaEntity>

    @Query("SELECT * FROM media WHERE roomId = :roomId ORDER BY updatedAt DESC LIMIT 100")
    suspend fun listByRoom(roomId: String): List<MediaEntity>

    @Query("SELECT * FROM media WHERE attachmentId = :id")
    suspend fun get(id: String): MediaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MediaEntity)

    @Query("UPDATE media SET localPath = :path, downloadStatus = :status, updatedAt = :now WHERE attachmentId = :id")
    suspend fun updateLocal(id: String, path: String?, status: Int, now: Long)
}

@Dao
interface PendingDao {
    @Query("SELECT * FROM pending_messages WHERE roomId = :roomId ORDER BY createdAt ASC")
    fun observeByRoom(roomId: String): Flow<List<PendingMessageEntity>>

    @Query("SELECT * FROM pending_messages ORDER BY createdAt ASC")
    suspend fun all(): List<PendingMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PendingMessageEntity)

    @Query("DELETE FROM pending_messages WHERE clientMessageId = :id")
    suspend fun remove(id: String)

    @Query("UPDATE pending_messages SET attempts = attempts + 1, lastError = :err WHERE clientMessageId = :id")
    suspend fun markFailed(id: String, err: String?)
}
