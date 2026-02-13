/*
 * HashtagService.kt - Hashtag Discovery Service
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Port of iOS HashtagService
 * Loads trending hashtags, videos by hashtag, velocity tiers
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.UUID

// MARK: - Velocity Tier (matches iOS)

enum class VelocityTier(val emoji: String, val label: String) {
    VIRAL("🚀", "Viral"),
    RISING("📈", "Rising"),
    STEADY("📊", "Steady"),
    NEW("✨", "New");

    companion object {
        fun fromCount(count: Int, daysOld: Int = 7): VelocityTier {
            val velocity = if (daysOld > 0) count.toDouble() / daysOld else count.toDouble()
            return when {
                velocity >= 10.0 -> VIRAL
                velocity >= 5.0 -> RISING
                velocity >= 1.0 -> STEADY
                else -> NEW
            }
        }
    }
}

// MARK: - Trending Hashtag Model (matches iOS TrendingHashtag)

data class TrendingHashtag(
    val id: String = UUID.randomUUID().toString(),
    val tag: String,
    val videoCount: Int,
    val velocityTier: VelocityTier,
    val recentCount: Int = 0
) {
    val displayTag: String get() = "#$tag"
}

// MARK: - Hashtag Video Result

data class HashtagVideoResult(
    val hashtag: String,
    val videos: List<com.stitchsocial.club.foundation.CoreVideoMetadata>,
    val totalCount: Int
)

// MARK: - Hashtag Service

class HashtagService {

    private val db = FirebaseFirestore.getInstance("stitchfin")

    companion object {
        private const val VIDEOS_COLLECTION = "videos"
    }

    // Cached trending hashtags
    private var cachedTrending: List<TrendingHashtag> = emptyList()
    private var trendingCacheTime: Long = 0
    private val trendingCacheExpiration = 5 * 60 * 1000L // 5 minutes

    /**
     * Load trending hashtags from recent videos (last 7 days)
     * Aggregates hashtag frequency and assigns velocity tiers
     */
    suspend fun loadTrendingHashtags(limit: Int = 10): List<TrendingHashtag> {
        // Check cache
        val now = System.currentTimeMillis()
        if (cachedTrending.isNotEmpty() && (now - trendingCacheTime) < trendingCacheExpiration) {
            return cachedTrending
        }

        return try {
            println("📈 HASHTAG: Loading trending hashtags")

            val cutoffDate = Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000)
            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereGreaterThan("createdAt", Timestamp(cutoffDate))
                .whereEqualTo("conversationDepth", 0)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(500)
                .get()
                .await()

            val hashtagCounts = mutableMapOf<String, Int>()

            for (document in snapshot.documents) {
                @Suppress("UNCHECKED_CAST")
                val hashtags = document.get("hashtags") as? List<String> ?: continue
                hashtags.forEach { hashtag ->
                    val clean = hashtag.lowercase().trim()
                    if (clean.isNotEmpty()) {
                        hashtagCounts[clean] = hashtagCounts.getOrDefault(clean, 0) + 1
                    }
                }
            }

            val trending = hashtagCounts.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { (tag, count) ->
                    TrendingHashtag(
                        tag = tag,
                        videoCount = count,
                        velocityTier = VelocityTier.fromCount(count),
                        recentCount = count
                    )
                }

            cachedTrending = trending
            trendingCacheTime = now

            println("✅ HASHTAG: Found ${trending.size} trending hashtags")
            trending

        } catch (e: Exception) {
            println("❌ HASHTAG: Failed to load trending - ${e.message}")
            emptyList()
        }
    }

    /**
     * Get videos for a specific hashtag (parent threads only)
     */
    suspend fun getVideosForHashtag(
        hashtag: String,
        limit: Int = 40
    ): HashtagVideoResult {
        val cleanTag = hashtag.removePrefix("#").lowercase().trim()

        return try {
            println("🏷️ HASHTAG: Loading videos for #$cleanTag")

            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereArrayContains("hashtags", cleanTag)
                .whereEqualTo("conversationDepth", 0)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            val videos = processVideoDocuments(snapshot.documents)

            println("✅ HASHTAG: Found ${videos.size} videos for #$cleanTag")

            HashtagVideoResult(
                hashtag = cleanTag,
                videos = videos,
                totalCount = videos.size
            )

        } catch (e: Exception) {
            println("❌ HASHTAG: Primary query failed for #$cleanTag - ${e.message}")
            // Fallback: query without conversationDepth, filter client-side
            getVideosForHashtagFallback(cleanTag, limit)
        }
    }

    private suspend fun getVideosForHashtagFallback(
        hashtag: String,
        limit: Int
    ): HashtagVideoResult {
        return try {
            val snapshot = db.collection(VIDEOS_COLLECTION)
                .whereArrayContains("hashtags", hashtag)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit((limit * 2).toLong())
                .get()
                .await()

            val videos = processVideoDocuments(snapshot.documents)
                .filter { it.conversationDepth == 0 }
                .take(limit)

            println("✅ HASHTAG: Fallback found ${videos.size} videos for #$hashtag")

            HashtagVideoResult(
                hashtag = hashtag,
                videos = videos,
                totalCount = videos.size
            )

        } catch (e: Exception) {
            println("❌ HASHTAG: Fallback also failed for #$hashtag - ${e.message}")
            HashtagVideoResult(hashtag = hashtag, videos = emptyList(), totalCount = 0)
        }
    }

    /**
     * Process video documents (same as SearchService)
     */
    private fun processVideoDocuments(
        documents: List<com.google.firebase.firestore.DocumentSnapshot>
    ): List<com.stitchsocial.club.foundation.CoreVideoMetadata> {
        val videos = mutableListOf<com.stitchsocial.club.foundation.CoreVideoMetadata>()

        for (document in documents) {
            val data = document.data ?: continue

            try {
                val temperatureString = data["temperature"] as? String ?: "WARM"
                val temperature = try {
                    com.stitchsocial.club.foundation.Temperature.valueOf(temperatureString.uppercase())
                } catch (e: IllegalArgumentException) {
                    com.stitchsocial.club.foundation.Temperature.WARM
                }

                val contentTypeString = data["contentType"] as? String ?: "THREAD"
                val contentType = try {
                    com.stitchsocial.club.foundation.ContentType.valueOf(contentTypeString.uppercase())
                } catch (e: IllegalArgumentException) {
                    com.stitchsocial.club.foundation.ContentType.THREAD
                }

                @Suppress("UNCHECKED_CAST")
                val hashtagsRaw = data["hashtags"] as? List<String>
                val hashtags = hashtagsRaw?.map { it.lowercase() } ?: emptyList()

                val video = com.stitchsocial.club.foundation.CoreVideoMetadata(
                    id = document.id,
                    title = data["title"] as? String ?: "",
                    description = data["description"] as? String ?: "",
                    videoURL = data["videoURL"] as? String ?: "",
                    thumbnailURL = data["thumbnailURL"] as? String ?: "",
                    creatorID = data["creatorID"] as? String ?: "",
                    creatorName = data["creatorName"] as? String ?: "",
                    createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date(),
                    hashtags = hashtags,
                    threadID = data["threadID"] as? String,
                    replyToVideoID = data["replyToVideoID"] as? String,
                    conversationDepth = (data["conversationDepth"] as? Long)?.toInt() ?: 0,
                    viewCount = (data["viewCount"] as? Long)?.toInt() ?: 0,
                    hypeCount = (data["hypeCount"] as? Long)?.toInt() ?: 0,
                    coolCount = (data["coolCount"] as? Long)?.toInt() ?: 0,
                    replyCount = (data["replyCount"] as? Long)?.toInt() ?: 0,
                    shareCount = (data["shareCount"] as? Long)?.toInt() ?: 0,
                    lastEngagementAt = (data["lastEngagementAt"] as? Timestamp)?.toDate(),
                    duration = data["duration"] as? Double ?: 0.0,
                    aspectRatio = data["aspectRatio"] as? Double ?: (9.0 / 16.0),
                    fileSize = data["fileSize"] as? Long ?: 0L,
                    contentType = contentType,
                    temperature = temperature,
                    qualityScore = (data["qualityScore"] as? Long)?.toInt() ?: 50,
                    engagementRatio = data["engagementRatio"] as? Double ?: 0.5,
                    velocityScore = data["velocityScore"] as? Double ?: 0.0,
                    trendingScore = data["trendingScore"] as? Double ?: 0.0,
                    discoverabilityScore = data["discoverabilityScore"] as? Double ?: 0.5,
                    isPromoted = data["isPromoted"] as? Boolean ?: false,
                    isProcessing = data["isProcessing"] as? Boolean ?: false,
                    isDeleted = data["isDeleted"] as? Boolean ?: false
                )

                videos.add(video)
            } catch (e: Exception) {
                println("⚠️ HASHTAG: Failed to process document ${document.id}")
            }
        }

        return videos
    }
}