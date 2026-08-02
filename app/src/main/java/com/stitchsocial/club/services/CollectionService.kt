/*
 * CollectionService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services — Collection CRUD + watch progress
 * Mirror of Swift CollectionService
 * Dependencies: Firebase Firestore, VideoCollection, CollectionProgress, CoreVideoMetadata
 *
 * CACHING:
 *   - Discovery collections: in-memory Map with 5-min TTL. Tab switches = 0 reads.
 *   - User collections: in-memory Map keyed by userID. Invalidated on publish/archive/delete.
 *   - Watch progress: in-memory Map keyed by progressID. Invalidated on every save.
 *   - Segments: in-memory Map keyed by collectionID. TTL 10 min (segments rarely change).
 *
 * BATCHING:
 *   - getCollectionSegments: single Firestore query ordered by segmentNumber.
 *   - No per-segment reads — all fields present on the video doc.
 *
 * FIRESTORE PATHS:
 *   videoCollections/{collectionID}
 *   collectionProgress/{userID}_{collectionID}
 *   videos/{segmentID}  (collectionID field + segmentNumber field)
 *   videoCollections/{collectionID}/segments/{segmentID}  ← ImportSegmentsView writes here
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.util.Date

import com.stitchsocial.club.foundation.CollectionContentType
import com.stitchsocial.club.foundation.CollectionProgress
import com.stitchsocial.club.foundation.CollectionStatus
import com.stitchsocial.club.foundation.CollectionVisibility
import com.stitchsocial.club.foundation.ContentType
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.Temperature
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.BuildConfig

class CollectionService {

    private val db = FirebaseFirestore.getInstance("stitchfin")

    // ─────────────────────────────────────────────
    // MARK: - In-Memory Cache
    // ─────────────────────────────────────────────

    private data class CacheEntry<T>(val data: T, val fetchedAt: Long) {
        fun isValid(ttlMs: Long) = System.currentTimeMillis() - fetchedAt < ttlMs
    }

    private val DISCOVERY_TTL = 5 * 60 * 1_000L      // 5 min
    private val SEGMENT_TTL   = 10 * 60 * 1_000L     // 10 min

    private var discoveryCache: CacheEntry<List<VideoCollection>>? = null
    private val userCollectionCache = mutableMapOf<String, CacheEntry<List<VideoCollection>>>()
    private val segmentCache = mutableMapOf<String, CacheEntry<List<CoreVideoMetadata>>>()
    private val progressCache = mutableMapOf<String, CollectionProgress>()

    fun invalidateUserCache(userID: String) { userCollectionCache.remove(userID) }
    fun invalidateDiscoveryCache() { discoveryCache = null }

    // ─────────────────────────────────────────────
    // MARK: - Discovery Collections
    // ─────────────────────────────────────────────

    /**
     * Public published collections for discovery lane.
     * COST: 1 read per 5 min — TTL cached.
     */
    suspend fun getDiscoveryCollections(limit: Int = 30): List<VideoCollection> =
        publishedCollections().take(limit)

    /**
     * Every published, public collection — the set every discovery lane slices.
     *
     * The CACHE HOLDS THE FULL SET, deliberately. Caching a limited list means
     * whoever calls first decides what everyone else can see: ask for 20, and a
     * later request for 30 silently gets 20 back, because the cache is returned
     * without re-applying the limit. Taking the limit at the call site instead
     * makes that impossible.
     *
     * Single whereEqualTo only — visibility is filtered client-side so no
     * composite index is required.
     */
    private suspend fun publishedCollections(): List<VideoCollection> {
        discoveryCache?.let { if (it.isValid(DISCOVERY_TTL)) return it.data }

        val snap = db.collection("videoCollections")
            .whereEqualTo("status", CollectionStatus.PUBLISHED.rawValue)
            .get().await()

        val collections = snap.documents
            .mapNotNull { decodeCollection(it.data ?: return@mapNotNull null, it.id) }
            .filter { it.visibility == CollectionVisibility.PUBLIC }
            .sortedByDescending { it.publishedAt }

        discoveryCache = CacheEntry(collections, System.currentTimeMillis())
        if (BuildConfig.DEBUG) { println("📚 COLLECTION SERVICE: Loaded ${collections.size} published collections") }
        return collections
    }

    /**
     * Podcasts only — for podcast discovery lane.
     * Not separately cached; subset of discovery results.
     */
    suspend fun getPodcastCollections(limit: Int = 20): List<VideoCollection> =
        collectionsOfType(CollectionContentType.PODCAST, limit)

    /**
     * Films only — for film discovery lane.
     */
    suspend fun getFilmCollections(limit: Int = 20): List<VideoCollection> =
        collectionsOfType(CollectionContentType.FILM, limit)

    /**
     * One lane of the discovery set, filtered by type.
     *
     * Both lanes used to run their own query ending in
     * `.orderBy("publishedAt", DESCENDING)`, which hid collections two ways:
     *
     * 1. Firestore EXCLUDES documents missing the orderBy field entirely. A
     *    collection published without publishedAt wasn't ranked low, it was
     *    unreachable — which reads to a creator as "the app lost my show".
     * 2. Three equality filters plus an orderBy on a fourth field needs a
     *    COMPOSITE INDEX that doesn't exist for videoCollections. A missing
     *    index makes the query THROW, which reads as an empty lane.
     *
     * Reusing the discovery set also means these lanes hit its cache instead of
     * issuing two more reads per open. Same fix already applied to
     * getDiscoveryCollections and the per-creator query.
     */
    private suspend fun collectionsOfType(
        type: CollectionContentType,
        limit: Int
    ): List<VideoCollection> =
        publishedCollections()
            .filter { it.contentType == type }
            .take(limit)

    // ─────────────────────────────────────────────
    // MARK: - User Collections
    // ─────────────────────────────────────────────

    /**
     * All collections owned by a creator (all statuses).
     * COST: 1 read per session per userID — cache invalidated on write.
     */
    suspend fun getUserCollections(userID: String): List<VideoCollection> {
        userCollectionCache[userID]?.let { if (it.isValid(DISCOVERY_TTL)) return it.data }

        // Single whereEqualTo — no composite index needed, sort client-side
        val snap = db.collection("videoCollections")
            .whereEqualTo("creatorID", userID)
            .get().await()

        val collections = snap.documents
            .mapNotNull { decodeCollection(it.data ?: return@mapNotNull null, it.id) }
            .sortedByDescending { it.createdAt }

        userCollectionCache[userID] = CacheEntry(collections, System.currentTimeMillis())
        if (BuildConfig.DEBUG) { println("📚 COLLECTION SERVICE: Loaded ${collections.size} collections for $userID") }
        return collections
    }

    /**
     * Single collection by ID.
     */
    suspend fun getCollection(collectionID: String): VideoCollection? {
        val doc = db.collection("videoCollections").document(collectionID).get().await()
        return doc.data?.let { decodeCollection(it, doc.id) }
    }

    // ─────────────────────────────────────────────
    // MARK: - Segments
    // ─────────────────────────────────────────────

    /**
     * All segment videos for a collection, sorted by segmentNumber.
     * COST: 1 query per collectionID, cached 10 min.
     * BATCHING: single query — no per-segment reads.
     */
    suspend fun getCollectionSegments(collectionID: String): List<CoreVideoMetadata> {
        segmentCache[collectionID]?.let { if (it.isValid(SEGMENT_TTL)) return it.data }

        // Primary: query by collectionID field (fast, indexed)
        val snap = db.collection("videos")
            .whereEqualTo("collectionID", collectionID)
            .orderBy("segmentNumber", Query.Direction.ASCENDING)
            .get().await()

        var segments = snap.documents.mapNotNull { doc ->
            val d = doc.data ?: return@mapNotNull null
            // Moderation gate: exclude pending/flagged/blocked segments.
            if (!com.stitchsocial.club.foundation.isVideoPubliclyVisible(d)) return@mapNotNull null
            decodeSegment(d, doc.id)
        }

        // Fallback 1: videoCollections/{id}/segments subcollection.
        // ImportSegmentsView writes segments HERE — primary write path for imported episodes.
        // Swift VideoService checks this before segmentIDs — Kotlin must match.
        if (segments.isEmpty()) {
            if (BuildConfig.DEBUG) { println("🎬 COLLECTION SERVICE: primary query 0 — trying subcollection path") }
            val subSnap = db.collection("videoCollections")
                .document(collectionID)
                .collection("segments")
                .get().await()
            segments = subSnap.documents.mapNotNull { doc ->
                decodeSegment(doc.data ?: return@mapNotNull null, doc.id)
            }.sortedBy { it.segmentNumber ?: 0 }
            if (segments.isNotEmpty())
                if (BuildConfig.DEBUG) { println("🎬 COLLECTION SERVICE: Found ${segments.size} segments in subcollection") }
        }

        // Fallback 2: fetch by segmentIDs array on the collection doc.
        if (segments.isEmpty()) {
            if (BuildConfig.DEBUG) { println("🎬 COLLECTION SERVICE: collectionID query returned 0 — falling back to segmentIDs fetch") }
            val collDoc = db.collection("videoCollections").document(collectionID).get().await()
            @Suppress("UNCHECKED_CAST")
            val segmentIDs = (collDoc.data?.get("segmentIDs") as? List<*>)
                ?.mapNotNull { it as? String } ?: emptyList()

            if (segmentIDs.isNotEmpty()) {
                // Firestore whereIn supports max 30 — chunk if needed
                val allDocs = segmentIDs.chunked(30).flatMap { chunk ->
                    db.collection("videos")
                        .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                        .get().await().documents
                }
                segments = allDocs.mapNotNull { doc ->
                    decodeSegment(doc.data ?: return@mapNotNull null, doc.id)
                }.sortedBy { it.segmentNumber ?: 0 }
                if (BuildConfig.DEBUG) { println("🎬 COLLECTION SERVICE: Fetched ${segments.size} segments via segmentIDs fallback") }
            }
        }

        segmentCache[collectionID] = CacheEntry(segments, System.currentTimeMillis())
        if (BuildConfig.DEBUG) { println("🎬 COLLECTION SERVICE: Loaded ${segments.size} segments for $collectionID") }
        return segments
    }

    // ─────────────────────────────────────────────
    // MARK: - Watch Progress
    // ─────────────────────────────────────────────

    /**
     * Fetch watch progress for a user + collection.
     * COST: 1 read — cached in-memory for session.
     */
    suspend fun getWatchProgress(userID: String, collectionID: String): CollectionProgress? {
        val key = CollectionProgress.progressID(userID, collectionID)
        progressCache[key]?.let { return it }

        val doc = db.collection("collectionProgress").document(key).get().await()
        if (!doc.exists()) return null

        val data = doc.data ?: return null
        val progress = decodeProgress(data, key) ?: return null
        progressCache[key] = progress
        return progress
    }

    /**
     * Save watch progress.
     * COST: 1 write. Invalidates in-memory cache.
     */
    suspend fun updateWatchProgress(progress: CollectionProgress) {
        val data = mapOf(
            "id"                   to progress.id,
            "userID"               to progress.userID,
            "collectionID"         to progress.collectionID,
            "currentSegmentID"     to progress.currentSegmentID,
            "currentSegmentIndex"  to progress.currentSegmentIndex,
            "currentTimestamp"     to progress.currentTimestamp,
            "completedSegmentIDs"  to progress.completedSegmentIDs,
            "segmentProgress"      to progress.segmentProgress,
            "percentComplete"      to progress.percentComplete,
            "totalWatchTime"       to progress.totalWatchTime,
            "startedAt"            to Timestamp(progress.startedAt),
            "lastWatchedAt"        to Timestamp(Date())
        )
        db.collection("collectionProgress").document(progress.id)
            .set(data, com.google.firebase.firestore.SetOptions.merge()).await()
        progressCache[progress.id] = progress
        if (BuildConfig.DEBUG) { println("📊 COLLECTION SERVICE: Progress saved for ${progress.collectionID}") }
    }

    // ─────────────────────────────────────────────
    // MARK: - Engagement
    // ─────────────────────────────────────────────

    /**
     * Atomic increment on a collection field.
     * Mirrors Swift VideoService.propagateToCollection.
     * No read needed — FieldValue.increment is server-side.
     */
    /** Soft-delete a collection — sets status to DELETED, clears from cache. */
    suspend fun deleteCollection(collectionID: String) {
        db.collection("videoCollections").document(collectionID)
            .update(mapOf(
                "status" to "deleted",
                "updatedAt" to com.google.firebase.Timestamp.now()
            )).await()
        invalidateDiscoveryCache()
        userCollectionCache.clear()
        if (BuildConfig.DEBUG) { println("🗑️ COLLECTION SERVICE: Deleted $collectionID") }
    }

    suspend fun incrementCollectionField(collectionID: String, field: String, delta: Long = 1) {
        try {
            db.collection("videoCollections").document(collectionID)
                .update(mapOf(field to FieldValue.increment(delta), "updatedAt" to Timestamp(Date())))
                .await()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("⚠️ COLLECTION SERVICE: Failed to increment $field on $collectionID: ${e.message}") }
        }
    }

    // ─────────────────────────────────────────────
    // MARK: - Decode Helpers
    // ─────────────────────────────────────────────

    private fun decodeCollection(data: Map<String, Any>, id: String): VideoCollection? {
        val creatorID = data["creatorID"] as? String ?: return null
        val title     = data["title"] as? String ?: return null

        @Suppress("UNCHECKED_CAST")
        val segmentIDs = (data["segmentIDs"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

        return VideoCollection(
            id              = id,
            title           = title,
            description     = data["description"] as? String ?: "",
            creatorID       = creatorID,
            creatorName     = data["creatorName"] as? String ?: "",
            coverImageURL   = data["coverImageURL"] as? String,
            segmentIDs      = segmentIDs,
            segmentCount    = (data["segmentCount"] as? Long)?.toInt() ?: segmentIDs.size,
            totalDuration   = (data["totalDuration"] as? Number)?.toDouble() ?: 0.0,
            freeSegmentCount = (data["freeSegmentCount"] as? Number)?.toInt() ?: 0,
            isFree          = data["isFree"] as? Boolean ?: false,
            status          = CollectionStatus.from(data["status"] as? String),
            visibility      = CollectionVisibility.from(data["visibility"] as? String),
            contentType     = CollectionContentType.from(data["contentType"] as? String),
            allowReplies    = data["allowReplies"] as? Boolean ?: true,
            allowStitchReplies = data["allowStitchReplies"] as? Boolean ?: true,
            showId          = data["showId"] as? String,
            seasonId        = data["seasonId"] as? String,
            episodeNumber   = (data["episodeNumber"] as? Long)?.toInt(),
            totalViews      = (data["totalViews"] as? Long)?.toInt() ?: 0,
            totalHypes      = (data["totalHypes"] as? Long)?.toInt() ?: 0,
            totalCools      = (data["totalCools"] as? Long)?.toInt() ?: 0,
            totalReplies    = (data["totalReplies"] as? Long)?.toInt() ?: 0,
            totalShares     = (data["totalShares"] as? Long)?.toInt() ?: 0,
            publishedAt     = (data["publishedAt"] as? Timestamp)?.toDate(),
            createdAt       = (data["createdAt"] as? Timestamp)?.toDate() ?: Date(),
            updatedAt       = (data["updatedAt"] as? Timestamp)?.toDate() ?: Date()
        )
    }

    private fun decodeSegment(data: Map<String, Any>, id: String): CoreVideoMetadata? {
        val videoURL = data["videoURL"] as? String ?: ""  // Don't bail — empty URL shows error state in player
        return CoreVideoMetadata(
            id                  = id,
            title               = data["title"] as? String ?: "Untitled",
            description         = data["description"] as? String ?: "",
            videoURL            = videoURL,
            thumbnailURL        = data["thumbnailURL"] as? String ?: "",
            creatorID           = data["creatorID"] as? String ?: "",
            creatorName         = data["creatorName"] as? String ?: "",
            createdAt           = (data["createdAt"] as? Timestamp)?.toDate() ?: Date(),
            threadID            = data["threadID"] as? String,
            replyToVideoID      = null,
            conversationDepth   = 0,
            viewCount           = (data["viewCount"] as? Long)?.toInt() ?: 0,
            hypeCount           = (data["hypeCount"] as? Long)?.toInt() ?: 0,
            coolCount           = (data["coolCount"] as? Long)?.toInt() ?: 0,
            replyCount          = (data["replyCount"] as? Long)?.toInt() ?: 0,
            shareCount          = (data["shareCount"] as? Long)?.toInt() ?: 0,
            lastEngagementAt    = (data["lastEngagementAt"] as? Timestamp)?.toDate(),
            duration            = (data["duration"] as? Number)?.toDouble() ?: 0.0,
            aspectRatio         = (data["aspectRatio"] as? Number)?.toDouble() ?: (9.0 / 16.0),
            fileSize            = (data["fileSize"] as? Number)?.toLong() ?: 0L,
            contentType         = ContentType.THREAD,
            temperature         = Temperature.WARM,
            qualityScore        = (data["qualityScore"] as? Long)?.toInt() ?: 50,
            engagementRatio     = (data["engagementRatio"] as? Number)?.toDouble() ?: 0.5,
            velocityScore       = 0.0,
            trendingScore       = 0.0,
            discoverabilityScore = (data["discoverabilityScore"] as? Number)?.toDouble() ?: 0.5,
            isPromoted          = data["isPromoted"] as? Boolean ?: false,
            isProcessing        = false,
            isDeleted           = false,
            collectionID        = data["collectionID"] as? String,
            segmentNumber       = (data["segmentNumber"] as? Long)?.toInt(),
            segmentTitle        = data["segmentTitle"] as? String,
            isCollectionSegment = data["isCollectionSegment"] as? Boolean ?: true
        )
    }

    private fun decodeProgress(data: Map<String, Any>, id: String): CollectionProgress? {
        val userID       = data["userID"] as? String ?: return null
        val collectionID = data["collectionID"] as? String ?: return null
        val currentSeg   = data["currentSegmentID"] as? String ?: return null

        @Suppress("UNCHECKED_CAST")
        val completed = (data["completedSegmentIDs"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val segProg = (data["segmentProgress"] as? Map<*, *>)
            ?.mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? Number)?.let { n -> key to n.toDouble() } } }
            ?.toMap() ?: emptyMap()

        return CollectionProgress(
            id                   = id,
            userID               = userID,
            collectionID         = collectionID,
            currentSegmentID     = currentSeg,
            currentSegmentIndex  = (data["currentSegmentIndex"] as? Long)?.toInt() ?: 0,
            currentTimestamp     = (data["currentTimestamp"] as? Number)?.toDouble() ?: 0.0,
            completedSegmentIDs  = completed,
            segmentProgress      = segProg,
            percentComplete      = (data["percentComplete"] as? Number)?.toDouble() ?: 0.0,
            totalWatchTime       = (data["totalWatchTime"] as? Number)?.toDouble() ?: 0.0,
            startedAt            = (data["startedAt"] as? Timestamp)?.toDate() ?: Date(),
            lastWatchedAt        = (data["lastWatchedAt"] as? Timestamp)?.toDate() ?: Date()
        )
    }
}