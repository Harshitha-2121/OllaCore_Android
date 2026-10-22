package com.ollacore.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommunityDao {
    @Query("SELECT * FROM communities ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CommunityEntity>>

    @Query("SELECT * FROM communities WHERE ownerId = :ownerId ORDER BY updatedAt DESC")
    fun observeByOwner(ownerId: String): Flow<List<CommunityEntity>>

    @Query("SELECT * FROM communities WHERE communityId = :communityId")
    suspend fun get(communityId: String): CommunityEntity?

    @Query("SELECT * FROM communities WHERE communityId IN (:communityIds)")
    suspend fun getByIds(communityIds: List<String>): List<CommunityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(community: CommunityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(communities: List<CommunityEntity>)

    @Query("UPDATE communities SET memberCount = memberCount + 1, updatedAt = :now WHERE communityId = :communityId")
    suspend fun incrementMemberCount(communityId: String, now: String)

    @Query("UPDATE communities SET memberCount = memberCount - 1, updatedAt = :now WHERE communityId = :communityId")
    suspend fun decrementMemberCount(communityId: String, now: String)

    @Query("UPDATE communities SET groupIdsCsv = groupIdsCsv || :groupId || ',' WHERE communityId = :communityId AND groupIdsCsv NOT LIKE '%' || :groupId || '%'")
    suspend fun addGroup(communityId: String, groupId: String)

    @Query("UPDATE communities SET groupIdsCsv = REPLACE(groupIdsCsv, :groupId || ',', '') WHERE communityId = :communityId")
    suspend fun removeGroup(communityId: String, groupId: String)

    @Query("DELETE FROM communities WHERE communityId = :communityId")
    suspend fun delete(communityId: String)

    @Query("SELECT COUNT(*) FROM communities WHERE ownerId = :ownerId")
    suspend fun getOwnedCount(ownerId: String): Int
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE ownerId = :ownerId ORDER BY updatedAt DESC")
    fun observeByOwner(ownerId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE channelId = :channelId")
    suspend fun get(channelId: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE channelId IN (:channelIds)")
    suspend fun getByIds(channelIds: List<String>): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE isPublic = 1 ORDER BY updatedAt DESC LIMIT 50")
    fun observePublic(): Flow<List<ChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channel: ChannelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Query("UPDATE channels SET followerCount = followerCount + 1, updatedAt = :now WHERE channelId = :channelId")
    suspend fun incrementFollowerCount(channelId: String, now: String)

    @Query("UPDATE channels SET followerCount = followerCount - 1, updatedAt = :now WHERE channelId = :channelId")
    suspend fun decrementFollowerCount(channelId: String, now: String)

    @Query("DELETE FROM channels WHERE channelId = :channelId")
    suspend fun delete(channelId: String)

    @Query("SELECT COUNT(*) FROM channels WHERE ownerId = :ownerId")
    suspend fun getOwnedCount(ownerId: String): Int
}