package com.ollacore.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StatusDao {
    // Observe all statuses ordered by creation time (newest first)
    @Query("SELECT * FROM statuses ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<StatusEntity>>

    // Observe statuses by author (for profile status view)
    @Query("SELECT * FROM statuses WHERE authorId = :authorId ORDER BY createdAt DESC")
    fun observeByAuthor(authorId: String): Flow<List<StatusEntity>>

    // Observe non-expired statuses for the current user's feed
    @Query("""
        SELECT * FROM statuses
        WHERE expiresAt > datetime('now')
        AND (
            privacy = 'contacts'
            OR (privacy = 'allow_list' AND authorId IN (SELECT userId FROM contacts))
            OR isOwn = 1
        )
        ORDER BY createdAt DESC
    """)
    fun observeFeed(): Flow<List<StatusEntity>>

    // Observe statuses that need cleanup (expired)
    @Query("SELECT * FROM statuses WHERE expiresAt <= datetime('now')")
    fun observeExpired(): Flow<List<StatusEntity>>

    // Get a specific status by ID
    @Query("SELECT * FROM statuses WHERE statusId = :statusId")
    suspend fun get(statusId: String): StatusEntity?

    // Insert or update a status
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(status: StatusEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(statuses: List<StatusEntity>)

    // Add a viewer to a status
    @Query("UPDATE statuses SET viewedUserIdsCsv = viewedUserIdsCsv || :viewerId || ',' WHERE statusId = :statusId AND viewedUserIdsCsv NOT LIKE '%' || :viewerId || '%'")
    suspend fun addViewer(statusId: String, viewerId: String)

    // Delete a status
    @Query("DELETE FROM statuses WHERE statusId = :statusId")
    suspend fun delete(statusId: String)

    // Delete all statuses by author
    @Query("DELETE FROM statuses WHERE authorId = :authorId")
    suspend fun deleteByAuthor(authorId: String)

    // Delete expired statuses
    @Query("DELETE FROM statuses WHERE expiresAt <= datetime('now')")
    suspend fun deleteExpired(): Int

    // Get status count for badge
    @Query("SELECT COUNT(*) FROM statuses WHERE expiresAt > datetime('now') AND isOwn = 0")
    suspend fun getUnviewedCount(): Int
}