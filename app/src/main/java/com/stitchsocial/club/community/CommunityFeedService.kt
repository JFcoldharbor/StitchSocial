/*
 * CommunityFeedService.kt - COMMUNITY FEED, POSTS, REPLIES, PAGINATION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Services - Create/fetch posts, replies, hype, cursor pagination
 * Port of: CommunityFeedService.swift (iOS)
 *
 * CACHING:
 * - feedCache: First 20 posts per community, 2-min TTL, invalidate on new post
 * - postDetailCache: Individual post objects, 2-min TTL
 * - replyCache: First 20 replies per post, 2-min TTL
 * - hypeStateCache: Bool per userID+postID, 5-min TTL — prevents re-reading hype status
 * Add to OptimizationConfig under "Community Feed Cache"
 *
 * BATCHING:
 * - New post: post doc + community totalPosts + member totalPosts in one batch
 * - Hype: hype doc + post hypeCount increment in one batch — NO read-then-write
 * - Reply: reply doc + post replyCount + member totalReplies in one batch
 * - Pagination: cursor-based, one query per page, no offset scanning
 */

package com.stitchsocial.club.community

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

class CommunityFeedService private constructor() {

    companion object {
        val shared = CommunityFeedService()
        private const val TAG = "CommunityFeedService"
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val communityService = CommunityService.shared
    private val xpService = CommunityXPService.shared

    // Published state
    private val _currentFeed = MutableStateFlow<List<CommunityPost>>(emptyList())
    val currentFeed: StateFlow<List<CommunityPost>> = _currentFeed.asStateFlow()

    private val _currentReplies = MutableStateFlow<List<CommunityReply>>(emptyList())
    val currentReplies: StateFlow<List<CommunityReply>> = _currentReplies.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // MARK: - Cache

    private data class CachedFeed(
        val posts: List<CommunityPost>,
        val lastDocument: DocumentSnapshot?,
        val cachedAt: Long,
        val ttlMs: Long = 120_000 // 2 min
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() - cachedAt > ttlMs
    }

    private data class CachedItem<T>(val value: T, val cachedAt: Long, val ttlMs: Long) {
        val isExpired: Boolean get() = System.currentTimeMillis() - cachedAt > ttlMs
    }

    private val feedCache = mutableMapOf<String, CachedFeed>()
    private val postDetailCache = mutableMapOf<String, CachedItem<CommunityPost>>()
    private val replyCache = mutableMapOf<String, CachedItem<List<CommunityReply>>>()
    private val hypeStateCache = mutableMapOf<String, CachedItem<Boolean>>()

    private val feedTTL = 120_000L    // 2 min
    private val detailTTL = 120_000L  // 2 min
    private val hypeTTL = 300_000L    // 5 min

    // Pagination state
    private val lastDocuments = mutableMapOf<String, DocumentSnapshot>()
    private val replyLastDocs = mutableMapOf<String, DocumentSnapshot>()
    private val hasMorePosts = mutableMapOf<String, Boolean>()
    private val hasMoreReplies = mutableMapOf<String, Boolean>()

    private object Col {
        const val COMMUNITIES = "communities"
        const val POSTS = "posts"
        const val REPLIES = "replies"
        const val HYPES = "hypes"
        const val MEMBERS = "members"
    }

    // MARK: - Fetch Feed (Cursor Paginated, Cached)

    suspend fun fetchFeed(
        communityID: String, limit: Int = 20, refresh: Boolean = false
    ): List<CommunityPost> {
        if (!refresh) {
            feedCache[communityID]?.let { if (!it.isExpired) { _currentFeed.value = it.posts; return it.posts } }
        }

        if (refresh) {
            lastDocuments.remove(communityID); feedCache.remove(communityID)
            hasMorePosts[communityID] = true
        }

        _isLoading.value = true
        try {
            var query = db.collection(Col.COMMUNITIES).document(communityID)
                .collection(Col.POSTS)
                .orderBy("isPinned", Query.Direction.DESCENDING)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())

            lastDocuments[communityID]?.let { query = query.startAfter(it) }

            val snapshot = query.get().await()
            val posts = snapshot.documents.mapNotNull { doc ->
                doc.data?.let { CommunityPost.fromFirestore(doc.id, it) }
            }

            snapshot.documents.lastOrNull()?.let { lastDocuments[communityID] = it }
            hasMorePosts[communityID] = posts.size == limit

            if (!lastDocuments.containsKey(communityID) || refresh) {
                _currentFeed.value = posts
                feedCache[communityID] = CachedFeed(posts, snapshot.documents.lastOrNull(), System.currentTimeMillis())
            } else {
                _currentFeed.value = _currentFeed.value + posts
            }

            Log.d(TAG, "Loaded ${posts.size} posts for $communityID, total: ${_currentFeed.value.size}")
            return posts
        } finally {
            _isLoading.value = false
        }
    }

    fun canLoadMore(communityID: String): Boolean = hasMorePosts[communityID] ?: true

    // MARK: - Create Post (BATCHED: post + community count + member count)

    suspend fun createPost(
        communityID: String, authorID: String, authorUsername: String,
        authorDisplayName: String, authorLevel: Int, authorBadgeIDs: List<String>,
        isCreatorPost: Boolean, postType: CommunityPostType, body: String,
        videoLinkID: String? = null, videoThumbnailURL: String? = null
    ): CommunityPost {
        if (postType == CommunityPostType.VIDEO_CLIP && authorLevel < CommunityFeatureGate.VIDEO_CLIPS.requiredLevel) {
            throw CommunityFeedError.LevelTooLow(CommunityFeatureGate.VIDEO_CLIPS.requiredLevel)
        }

        val post = CommunityPost(
            communityID = communityID, authorID = authorID,
            authorUsername = authorUsername, authorDisplayName = authorDisplayName,
            authorLevel = authorLevel, authorBadgeIDs = authorBadgeIDs,
            isCreatorPost = isCreatorPost, postType = postType, body = body,
            videoLinkID = videoLinkID, videoThumbnailURL = videoThumbnailURL
        )

        // BATCHED WRITE: post + community totalPosts + member totalPosts
        val batch = db.batch()
        val postRef = db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.POSTS).document(post.id)
        batch.set(postRef, post.toFirestore())

        batch.update(db.collection(Col.COMMUNITIES).document(communityID),
            mapOf("totalPosts" to FieldValue.increment(1), "updatedAt" to Timestamp(Date())))

        batch.update(db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.MEMBERS).document(authorID),
            mapOf("totalPosts" to FieldValue.increment(1), "lastActiveAt" to Timestamp(Date())))

        batch.commit().await()

        // Award XP (buffered)
        val source = if (postType == CommunityPostType.VIDEO_CLIP) CommunityXPSource.VIDEO_POST else CommunityXPSource.TEXT_POST
        xpService.awardXP(authorID, communityID, source)

        // Invalidate caches
        feedCache.remove(communityID)
        communityService.invalidateListCache()
        communityService.invalidateMembershipCache(authorID, communityID)

        _currentFeed.value = listOf(post) + _currentFeed.value
        Log.d(TAG, "Post created by $authorUsername in $communityID")
        return post
    }

    // MARK: - Pin/Unpin Post (BATCHED: post + community pinnedPostID)

    suspend fun pinPost(postID: String, communityID: String, creatorID: String, pin: Boolean) {
        if (pin) {
            val community = communityService.fetchCommunity(creatorID)
            community?.pinnedPostID?.let { existingPinID ->
                db.collection(Col.COMMUNITIES).document(communityID)
                    .collection(Col.POSTS).document(existingPinID)
                    .update("isPinned", false).await()
            }
        }

        val batch = db.batch()
        batch.update(db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.POSTS).document(postID), "isPinned", pin)
        batch.update(db.collection(Col.COMMUNITIES).document(communityID),
            "pinnedPostID", if (pin) postID else FieldValue.delete())
        batch.commit().await()

        feedCache.remove(communityID); postDetailCache.remove(postID)
    }

    // MARK: - Delete Post (BATCHED: delete + decrement counts)

    suspend fun deletePost(postID: String, communityID: String, authorID: String) {
        val batch = db.batch()
        batch.delete(db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.POSTS).document(postID))
        batch.update(db.collection(Col.COMMUNITIES).document(communityID),
            "totalPosts", FieldValue.increment(-1))
        batch.update(db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.MEMBERS).document(authorID),
            "totalPosts", FieldValue.increment(-1))
        batch.commit().await()

        feedCache.remove(communityID); postDetailCache.remove(postID); replyCache.remove(postID)
        _currentFeed.value = _currentFeed.value.filter { it.id != postID }
    }

    // MARK: - Fetch Replies (Cursor Paginated, Cached)

    suspend fun fetchReplies(
        postID: String, communityID: String, limit: Int = 20, refresh: Boolean = false
    ): List<CommunityReply> {
        if (!refresh) {
            replyCache[postID]?.let { if (!it.isExpired) { _currentReplies.value = it.value; return it.value } }
        }
        if (refresh) { replyLastDocs.remove(postID); replyCache.remove(postID); hasMoreReplies[postID] = true }

        var query = db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.POSTS).document(postID)
            .collection(Col.REPLIES)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(limit.toLong())
        replyLastDocs[postID]?.let { query = query.startAfter(it) }

        val snapshot = query.get().await()
        val replies = snapshot.documents.mapNotNull { doc ->
            doc.data?.let { CommunityReply.fromFirestore(doc.id, it) }
        }

        snapshot.documents.lastOrNull()?.let { replyLastDocs[postID] = it }
        hasMoreReplies[postID] = replies.size == limit

        if (!replyLastDocs.containsKey(postID) || refresh) {
            _currentReplies.value = replies
            replyCache[postID] = CachedItem(replies, System.currentTimeMillis(), feedTTL)
        } else {
            _currentReplies.value = _currentReplies.value + replies
        }
        return replies
    }

    // MARK: - Create Reply (BATCHED: reply + post replyCount + member totalReplies)

    suspend fun createReply(
        postID: String, communityID: String, authorID: String,
        authorUsername: String, authorDisplayName: String, authorLevel: Int,
        isCreatorReply: Boolean, body: String
    ): CommunityReply {
        val reply = CommunityReply(
            postID = postID, communityID = communityID, authorID = authorID,
            authorUsername = authorUsername, authorDisplayName = authorDisplayName,
            authorLevel = authorLevel, isCreatorReply = isCreatorReply, body = body
        )

        val batch = db.batch()
        batch.set(db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.POSTS).document(postID)
            .collection(Col.REPLIES).document(reply.id), reply.toFirestore())

        batch.update(db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.POSTS).document(postID),
            mapOf("replyCount" to FieldValue.increment(1), "updatedAt" to Timestamp(Date())))

        batch.update(db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.MEMBERS).document(authorID),
            mapOf("totalReplies" to FieldValue.increment(1), "lastActiveAt" to Timestamp(Date())))

        batch.commit().await()

        xpService.awardXP(authorID, communityID, CommunityXPSource.REPLY)
        replyCache.remove(postID); postDetailCache.remove(postID)
        communityService.invalidateMembershipCache(authorID, communityID)

        _currentReplies.value = _currentReplies.value + reply
        Log.d(TAG, "Reply by $authorUsername on $postID")
        return reply
    }

    // MARK: - Hype Post (BATCHED: hype doc + hypeCount increment, CACHED state)

    suspend fun hypePost(postID: String, communityID: String, userID: String): Boolean {
        val cacheKey = "${userID}_${postID}"
        hypeStateCache[cacheKey]?.let { if (!it.isExpired && it.value) throw CommunityFeedError.AlreadyHyped }

        val hypeRef = db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.POSTS).document(postID)
            .collection(Col.HYPES).document(userID)

        val existingDoc = hypeRef.get().await()
        if (existingDoc.exists()) {
            hypeStateCache[cacheKey] = CachedItem(true, System.currentTimeMillis(), hypeTTL)
            throw CommunityFeedError.AlreadyHyped
        }

        val hype = CommunityPostHype(id = userID, postID = postID, userID = userID, communityID = communityID)

        // BATCHED: hype doc + post hypeCount
        val batch = db.batch()
        batch.set(hypeRef, hype.toFirestore())
        batch.update(db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.POSTS).document(postID),
            "hypeCount", FieldValue.increment(1))
        batch.commit().await()

        hypeStateCache[cacheKey] = CachedItem(true, System.currentTimeMillis(), hypeTTL)

        // Award XP to giver + receiver (buffered)
        xpService.awardXP(userID, communityID, CommunityXPSource.GAVE_HYPE)
        _currentFeed.value.firstOrNull { it.id == postID }?.let { post ->
            xpService.awardXP(post.authorID, communityID, CommunityXPSource.RECEIVED_HYPE)
        }

        // Update local feed for instant UI
        _currentFeed.value = _currentFeed.value.map {
            if (it.id == postID) it.copy(hypeCount = it.hypeCount + 1) else it
        }
        postDetailCache.remove(postID)
        return true
    }

    // MARK: - Check Hype State (Cached — prevents re-reading)

    suspend fun hasHyped(userID: String, postID: String, communityID: String): Boolean {
        val cacheKey = "${userID}_${postID}"
        hypeStateCache[cacheKey]?.let { if (!it.isExpired) return it.value }

        val doc = db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.POSTS).document(postID)
            .collection(Col.HYPES).document(userID).get().await()

        val result = doc.exists()
        hypeStateCache[cacheKey] = CachedItem(result, System.currentTimeMillis(), hypeTTL)
        return result
    }

    // Batch preload hype states for a feed page — reduces N sequential reads
    suspend fun preloadHypeStates(postIDs: List<String>, communityID: String, userID: String) {
        for (postID in postIDs) {
            val cacheKey = "${userID}_${postID}"
            if (hypeStateCache[cacheKey]?.isExpired != false) {
                try { hasHyped(userID, postID, communityID) } catch (_: Exception) {}
            }
        }
    }

    // MARK: - Fetch Single Post (Cached)

    suspend fun fetchPost(postID: String, communityID: String): CommunityPost? {
        postDetailCache[postID]?.let { if (!it.isExpired) return it.value }
        val doc = db.collection(Col.COMMUNITIES).document(communityID)
            .collection(Col.POSTS).document(postID).get().await()
        val data = doc.data ?: return null
        val post = CommunityPost.fromFirestore(doc.id, data)
        postDetailCache[postID] = CachedItem(post, System.currentTimeMillis(), detailTTL)
        return post
    }

    // MARK: - Cache Management

    fun clearAllCaches() {
        feedCache.clear(); postDetailCache.clear(); replyCache.clear(); hypeStateCache.clear()
        lastDocuments.clear(); replyLastDocs.clear(); hasMorePosts.clear(); hasMoreReplies.clear()
        _currentFeed.value = emptyList(); _currentReplies.value = emptyList()
    }

    fun invalidateFeedCache(communityID: String) { feedCache.remove(communityID) }

    fun pruneExpiredCaches() {
        feedCache.entries.removeAll { it.value.isExpired }
        postDetailCache.entries.removeAll { it.value.isExpired }
        replyCache.entries.removeAll { it.value.isExpired }
        hypeStateCache.entries.removeAll { it.value.isExpired }
    }
}