/*
 * ConversationLaneService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Conversation Lane Resolution
 * Dependencies: VideoServiceImpl
 *
 * Ports iOS ConversationLaneService.shared exactly:
 * - getLanes(forChildVideoID, childCreatorID) → list of ConversationLane
 * - loadLaneMessages(childVideo, participant1, participant2) → ordered messages
 * - canUserReply(to, userID) → (Boolean, String) lane-gated reply permission
 * - invalidateLane / clearCache for cache management
 *
 * CACHING: 5-min TTL LRU cache on lane lookups. One getTimestampedReplies call
 * per child video resolves ALL lanes (no per-participant queries). Cache key is
 * childVideoID. Invalidated when a new reply is created to that child.
 *
 * BATCHING: getLanes does a single Firestore query then groups locally by
 * creatorID to build lanes. No N+1 queries.
 *
 * ADD TO OptimizationConfig: laneCacheTTLMs = 300_000 (5 min)
 * ADD TO CachingService: laneCache bucket if you want centralized eviction.
 * For now this uses its own lightweight ConcurrentHashMap to avoid coupling.
 */

package com.stitchsocial.club.services

import com.stitchsocial.club.foundation.CoreVideoMetadata
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a private conversation lane between the child video creator
 * and one unique responder (depth-2 stepchild creator).
 */
data class ConversationLane(
    val childVideoID: String,
    val childCreatorID: String,
    val responderID: String,
    val firstReply: CoreVideoMetadata,
    val messageCount: Int
) {
    val participantIDs: Set<String> get() = setOf(childCreatorID, responderID)

    fun isParticipant(userID: String): Boolean = participantIDs.contains(userID)
}

/**
 * Singleton service that resolves conversation lanes for depth-1 child videos.
 *
 * A "lane" is a private back-and-forth between the child creator and one responder.
 * The ConversationNavigationBar shows one thumbnail per lane. Tapping a lane loads
 * only messages between those two participants.
 */
object ConversationLaneService {

    val shared: ConversationLaneService = this

    // --- Cache ---
    private data class CachedLanes(
        val lanes: List<ConversationLane>,
        val cachedAt: Long
    )

    private val laneCache = ConcurrentHashMap<String, CachedLanes>()
    private const val CACHE_TTL_MS = 300_000L // 5 minutes
    private const val MAX_CACHE_ENTRIES = 50
    private const val MAX_LANE_MESSAGES = 20 // cap per lane to bound memory

    // Lazy videoService reference — set once from app init or first caller
    private var _videoService: VideoServiceImpl? = null

    fun initialize(videoService: VideoServiceImpl) {
        _videoService = videoService
    }

    private val videoService: VideoServiceImpl
        get() = _videoService ?: throw IllegalStateException(
            "ConversationLaneService not initialized. Call initialize(videoService) first."
        )

    // ------------------------------------------------------------------
    // PUBLIC API
    // ------------------------------------------------------------------

    /**
     * Get all conversation lanes for a depth-1 child video.
     * Each lane represents a unique responder who replied to this child.
     *
     * Cost: 1 Firestore read (getTimestampedReplies) on cache miss.
     * Cached for 5 minutes per childVideoID.
     */
    suspend fun getLanes(
        forChildVideoID: String,
        childCreatorID: String
    ): List<ConversationLane> {
        // Check cache
        val cached = laneCache[forChildVideoID]
        if (cached != null && (System.currentTimeMillis() - cached.cachedAt) < CACHE_TTL_MS) {
            return cached.lanes
        }

        // Single query: all stepchild replies to this child video
        val allReplies = videoService.getTimestampedReplies(forChildVideoID)

        // Group by unique responder (excluding child creator talking to themselves)
        val responderGroups = allReplies
            .filter { it.creatorID != childCreatorID || allReplies.any { r -> r.creatorID != childCreatorID } }
            .groupBy { reply ->
                // Lane key: the "other" person (not the child creator)
                if (reply.creatorID == childCreatorID) {
                    // This is the child creator responding — find who they're responding to
                    // by looking at who else is in the conversation
                    reply.replyToVideoID?.let { replyToID ->
                        allReplies.find { it.id == replyToID }?.creatorID
                    } ?: reply.creatorID
                } else {
                    reply.creatorID
                }
            }
            .filter { it.key != childCreatorID } // Remove self-lane

        val lanes = responderGroups.mapNotNull { (responderID, messages) ->
            val firstReply = messages
                .filter { it.creatorID == responderID }
                .minByOrNull { it.createdAt }
                ?: messages.minByOrNull { it.createdAt }
                ?: return@mapNotNull null

            ConversationLane(
                childVideoID = forChildVideoID,
                childCreatorID = childCreatorID,
                responderID = responderID,
                firstReply = firstReply,
                messageCount = messages.size
            )
        }.sortedByDescending { it.messageCount }

        // Cache result
        if (laneCache.size >= MAX_CACHE_ENTRIES) {
            evictOldest()
        }
        laneCache[forChildVideoID] = CachedLanes(lanes, System.currentTimeMillis())

        return lanes
    }

    /**
     * Load the full ordered message chain between two participants in a lane.
     * Returns messages sorted by createdAt (chronological).
     *
     * Cost: 1 Firestore read (getTimestampedReplies) — shares same query as getLanes
     * if called shortly after (Firestore client SDK caches).
     */
    suspend fun loadLaneMessages(
        childVideo: CoreVideoMetadata,
        participant1: String,
        participant2: String
    ): List<CoreVideoMetadata> {
        val allReplies = videoService.getTimestampedReplies(childVideo.id)

        val participants = setOf(participant1, participant2)

        return allReplies
            .filter { it.creatorID in participants }
            .sortedBy { it.createdAt }
            .take(MAX_LANE_MESSAGES)
    }

    /**
     * Check if a user can reply to a video based on lane rules.
     * - Depth 0: anyone can reply
     * - Depth 1+: only existing lane participants OR new lane if under 20-responder cap
     *
     * Returns (canReply, reason) matching iOS signature.
     */
    suspend fun canUserReply(
        video: CoreVideoMetadata,
        userID: String
    ): Pair<Boolean, String> {
        // Depth 0 threads — open to all
        if (video.conversationDepth == 0) {
            return Pair(true, "Thread-level reply allowed")
        }

        val childCreatorID = video.creatorID

        // Child creator can always reply in their own thread
        if (userID == childCreatorID) {
            return Pair(true, "Child creator can always reply")
        }

        // Check existing lanes
        val parentID = video.replyToVideoID ?: video.id
        val lanes = getLanes(
            forChildVideoID = if (video.conversationDepth == 1) video.id else parentID,
            childCreatorID = childCreatorID
        )

        // Already a participant in an existing lane
        if (lanes.any { it.isParticipant(userID) }) {
            return Pair(true, "Existing lane participant")
        }

        // New responder — check 20-lane cap
        if (lanes.size >= 20) {
            return Pair(false, "Maximum conversation partners reached (20)")
        }

        return Pair(true, "New lane allowed (${lanes.size}/20)")
    }

    // ------------------------------------------------------------------
    // CACHE MANAGEMENT
    // ------------------------------------------------------------------

    /** Invalidate cached lanes for a specific child video (call after new reply created) */
    fun invalidateLane(childVideoID: String) {
        laneCache.remove(childVideoID)
    }

    /** Clear entire lane cache (call on major state changes like logout) */
    fun clearCache() {
        laneCache.clear()
    }

    /** Evict oldest cache entry when at capacity */
    private fun evictOldest() {
        val oldest = laneCache.entries.minByOrNull { it.value.cachedAt }
        oldest?.let { laneCache.remove(it.key) }
    }

    /** Cleanup expired entries — call periodically or on memory pressure */
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        laneCache.entries.removeIf { now - it.value.cachedAt > CACHE_TTL_MS }
    }
}