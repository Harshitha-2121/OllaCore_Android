package com.ollacore.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Community entity (WhatsApp-style Communities).
 * A community is a collection of groups with admin announcements.
 */
@Entity(
    tableName = "communities",
    indices = [Index("ownerId"), Index("createdAt")]
)
data class CommunityEntity(
    @PrimaryKey val communityId: String,
    val name: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val ownerId: String,
    val createdAt: String = "",
    val updatedAt: String = "",
    val memberCount: Int = 0,
    val groupIdsCsv: String = ""
) {
    fun groupIds(): List<String> =
        groupIdsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Channel entity (WhatsApp-style Channels).
 * One-way broadcast with followers.
 */
@Entity(
    tableName = "channels",
    indices = [Index("ownerId"), Index("createdAt")]
)
data class ChannelEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val ownerId: String,
    val createdAt: String = "",
    val updatedAt: String = "",
    val followerCount: Int = 0,
    val isPublic: Boolean = true
)