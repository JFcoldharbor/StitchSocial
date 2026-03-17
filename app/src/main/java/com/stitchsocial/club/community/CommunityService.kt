/*
 * CommunityService.kt - COMMUNITY CRUD, MEMBERSHIP, TIER GATING
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Services - Create/fetch communities, join/leave, membership, community list
 * Port of: CommunityService.swift (iOS)
 *
 * CACHING: communityCache (10-min), membershipCache (5-min), listCache (5-min), isMemberCache (5-min)
 * BATCHING: Join/leave use batched writes (member doc + memberCount increment in one op)
 * All caches clear on logout. Add to OptimizationConfig.
 */

package com.stitchsocial.club.community

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import com.stitchsocial.club.foundation.SpecialUsersConfig
import com.stitchsocial.club.foundation.UserTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

class CommunityService private constructor() {

    companion object {
        val shared = CommunityService()
        private const val TAG = "CommunityService"
        const val OFFICIAL_COMMUNITY_ID = "L9cfRdqpDMWA9tq12YBh3IkhnGh1"
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")

    /** Developer bypass — matches iOS SubscriptionService.shared.isDeveloper */
    val isDeveloper: Boolean
        get() {
            val email = FirebaseAuth.getInstance().currentUser?.email ?: return false
            val entry = SpecialUsersConfig.detectSpecialUser(email) ?: return false
            return entry.isFounder || entry.role == com.stitchsocial.club.foundation.SpecialUserRole.EMPLOYEE
        }

    // Published state
    private val _myCommunities = MutableStateFlow<List<CommunityListItem>>(emptyList())
    val myCommunities: StateFlow<List<CommunityListItem>> = _myCommunities.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // MARK: - Cache (reduces Firestore reads significantly)

    data class CachedItem<T>(val value: T, val cachedAt: Long, val ttlMs: Long) {
        val isExpired: Boolean get() = System.currentTimeMillis() - cachedAt > ttlMs
    }

    private val communityCache = mutableMapOf<String, CachedItem<Community>>()        // 10 min
    private val membershipCache = mutableMapOf<String, CachedItem<CommunityMembership>>()  // 5 min
    private var communityListCache: CachedItem<List<CommunityListItem>>? = null         // 5 min
    private val isMemberCache = mutableMapOf<String, CachedItem<Boolean>>()             // 5 min
    private var allCommunitiesCache: CachedItem<List<CommunityListItem>>? = null

    private val communityTTL = 600_000L   // 10 min
    private val membershipTTL = 300_000L  // 5 min
    private val listTTL = 300_000L        // 5 min

    private object Col {
        const val COMMUNITIES = "communities"
        const val MEMBERS = "members"
    }

    // MARK: - Auto-Join Official Community

    suspend fun autoJoinOfficialCommunity(userID: String, username: String, displayName: String) {
        val creatorID = OFFICIAL_COMMUNITY_ID
        val cacheKey = "${userID}_${creatorID}"

        isMemberCache[cacheKey]?.let { if (!it.isExpired && it.value) return }

        try {
            val memberRef = db.collection(Col.COMMUNITIES).document(creatorID)
                .collection(Col.MEMBERS).document(userID)
            val doc = memberRef.get().await()

            if (doc.exists()) {
                isMemberCache[cacheKey] = CachedItem(true, System.currentTimeMillis(), membershipTTL)
                return
            }

            val membership = CommunityMembership(
                id = userID, userID = userID, communityID = creatorID,
                username = username, displayName = displayName
            )
            val batch = db.batch()
            batch.set(memberRef, membership.toFirestore())
            batch.update(db.collection(Col.COMMUNITIES).document(creatorID),
                "memberCount", FieldValue.increment(1))
            batch.commit().await()

            isMemberCache[cacheKey] = CachedItem(true, System.currentTimeMillis(), membershipTTL)
            communityListCache = null
            Log.d(TAG, "Auto-joined $username to official community")
        } catch (e: Exception) {
            Log.w(TAG, "Auto-join failed: ${e.message}")
        }
    }

    // MARK: - Create Community (Influencer+ Only)

    suspend fun createCommunity(
        creatorID: String, creatorUsername: String, creatorDisplayName: String,
        creatorTier: UserTier, displayName: String? = null, description: String = ""
    ): Community {
        if (!Community.canCreateCommunity(creatorTier)) throw CommunityError.InsufficientTier

        val existingDoc = db.collection(Col.COMMUNITIES).document(creatorID).get().await()
        if (existingDoc.exists()) throw CommunityError.CommunityAlreadyExists

        val community = Community(
            id = creatorID, creatorID = creatorID,
            creatorUsername = creatorUsername, creatorDisplayName = creatorDisplayName,
            creatorTier = creatorTier.name.lowercase(),
            displayName = displayName ?: "${creatorDisplayName}'s Community",
            description = description
        )
        db.collection(Col.COMMUNITIES).document(creatorID).set(community.toFirestore()).await()
        communityCache[creatorID] = CachedItem(community, System.currentTimeMillis(), communityTTL)
        Log.d(TAG, "Created community for $creatorUsername")
        return community
    }

    // MARK: - Fetch Community (Cached)

    suspend fun fetchCommunity(creatorID: String): Community? {
        communityCache[creatorID]?.let { if (!it.isExpired) return it.value }

        val doc = db.collection(Col.COMMUNITIES).document(creatorID).get().await()
        val data = doc.data ?: return null
        val community = Community.fromFirestore(doc.id, data)
        communityCache[creatorID] = CachedItem(community, System.currentTimeMillis(), communityTTL)
        return community
    }

    // MARK: - Fetch Community Status

    suspend fun fetchCommunityStatus(creatorID: String): CommunityStatus {
        val community = fetchCommunity(creatorID) ?: return CommunityStatus.NotCreated
        return if (community.isActive) CommunityStatus.Active(community) else CommunityStatus.Inactive(community)
    }

    // MARK: - Update Community (Creator Only)

    suspend fun updateCommunity(
        creatorID: String, displayName: String? = null, description: String? = null,
        profileImageURL: String? = null, bannerImageURL: String? = null, pinnedPostID: String? = null
    ) {
        val updates = mutableMapOf<String, Any>("updatedAt" to Timestamp(Date()))
        displayName?.let { updates["displayName"] = it }
        description?.let { updates["description"] = it }
        profileImageURL?.let { updates["profileImageURL"] = it }
        bannerImageURL?.let { updates["bannerImageURL"] = it }
        pinnedPostID?.let { updates["pinnedPostID"] = it }

        db.collection(Col.COMMUNITIES).document(creatorID).update(updates).await()
        communityCache.remove(creatorID)
        Log.d(TAG, "Updated community $creatorID")
    }

    // MARK: - Join Community (BATCHED: member doc + count increment)

    suspend fun joinCommunity(
        userID: String, username: String, displayName: String, creatorID: String
    ): CommunityMembership {
        _isLoading.value = true
        try {
            val community = fetchCommunity(creatorID)
            if (community == null || !community.isActive) throw CommunityError.CommunityNotFound

            val memberRef = db.collection(Col.COMMUNITIES).document(creatorID)
                .collection(Col.MEMBERS).document(userID)
            val existingDoc = memberRef.get().await()
            if (existingDoc.exists()) {
                val existing = CommunityMembership.fromFirestore(existingDoc.data ?: emptyMap())
                if (!existing.isBanned) throw CommunityError.AlreadyMember
            }

            // Developer bypass — skip subscription check (matches iOS)
            if (!isDeveloper) {
                // TODO: Verify active subscription via SubscriptionService.shared.checkSubscription
            }

            val membership = CommunityMembership(
                id = userID, userID = userID, communityID = creatorID,
                username = username, displayName = displayName
            )

            // BATCHED WRITE: membership + memberCount
            val batch = db.batch()
            batch.set(memberRef, membership.toFirestore())
            batch.update(db.collection(Col.COMMUNITIES).document(creatorID),
                "memberCount", FieldValue.increment(1))
            batch.commit().await()

            // Cache
            val cacheKey = "${userID}_${creatorID}"
            membershipCache[cacheKey] = CachedItem(membership, System.currentTimeMillis(), membershipTTL)
            isMemberCache[cacheKey] = CachedItem(true, System.currentTimeMillis(), membershipTTL)
            communityCache.remove(creatorID)
            communityListCache = null

            Log.d(TAG, "$username joined $creatorID")
            return membership
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Leave Community (BATCHED: delete member + decrement count)

    suspend fun leaveCommunity(userID: String, creatorID: String) {
        _isLoading.value = true
        try {
            val memberRef = db.collection(Col.COMMUNITIES).document(creatorID)
                .collection(Col.MEMBERS).document(userID)

            val batch = db.batch()
            batch.delete(memberRef)
            batch.update(db.collection(Col.COMMUNITIES).document(creatorID),
                "memberCount", FieldValue.increment(-1))
            batch.commit().await()

            val cacheKey = "${userID}_${creatorID}"
            membershipCache.remove(cacheKey)
            isMemberCache.remove(cacheKey)
            communityCache.remove(creatorID)
            communityListCache = null
            Log.d(TAG, "$userID left $creatorID")
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Check Membership (Cached — avoids read on every navigation)

    suspend fun isMember(userID: String, creatorID: String): Boolean {
        val cacheKey = "${userID}_${creatorID}"
        isMemberCache[cacheKey]?.let { if (!it.isExpired) return it.value }

        val doc = db.collection(Col.COMMUNITIES).document(creatorID)
            .collection(Col.MEMBERS).document(userID).get().await()
        val result = doc.exists()
        isMemberCache[cacheKey] = CachedItem(result, System.currentTimeMillis(), membershipTTL)
        return result
    }

    // MARK: - Fetch Membership (with XP/Level, cached)

    suspend fun fetchMembership(userID: String, creatorID: String): CommunityMembership? {
        val cacheKey = "${userID}_${creatorID}"
        membershipCache[cacheKey]?.let { if (!it.isExpired) return it.value }

        val doc = db.collection(Col.COMMUNITIES).document(creatorID)
            .collection(Col.MEMBERS).document(userID).get().await()
        val data = doc.data ?: return null
        var membership = CommunityMembership.fromFirestore(data)

        // Developer bypass — max level, all features unlocked (matches iOS)
        if (isDeveloper) {
            membership = membership.copy(level = 1000, localXP = 999999, isModerator = true)
        }

        membershipCache[cacheKey] = CachedItem(membership, System.currentTimeMillis(), membershipTTL)
        return membership
    }

    // MARK: - Fetch My Communities (Single query + batched membership reads)

    suspend fun fetchMyCommunities(userID: String): List<CommunityListItem> {
        communityListCache?.let { if (!it.isExpired) { _myCommunities.value = it.value; return it.value } }

        _isLoading.value = true
        try {
            // Get all communities where user is a member
            // Uses collectionGroup query on members where userID matches
            val memberSnaps = db.collectionGroup(Col.MEMBERS)
                .whereEqualTo("userID", userID)
                .get().await()

            val communityIDs = memberSnaps.documents.mapNotNull { it.getString("communityID") }.distinct()
            if (communityIDs.isEmpty()) {
                _myCommunities.value = emptyList()
                communityListCache = CachedItem(emptyList(), System.currentTimeMillis(), listTTL)
                return emptyList()
            }

            // Fetch communities in batches of 30 (Firestore whereIn limit)
            val listItems = mutableListOf<CommunityListItem>()
            for (batch in communityIDs.chunked(30)) {
                val snapshot = db.collection(Col.COMMUNITIES)
                    .whereIn(FieldPath.documentId(), batch).get().await()

                for (doc in snapshot.documents) {
                    val data = doc.data ?: continue
                    val community = Community.fromFirestore(doc.id, data)
                    val membership = fetchMembership(userID, community.id)

                    listItems.add(CommunityListItem(
                        id = community.id,
                        creatorUsername = community.creatorUsername,
                        creatorDisplayName = community.creatorDisplayName,
                        creatorTier = community.creatorTier,
                        profileImageURL = community.profileImageURL,
                        memberCount = community.memberCount,
                        userLevel = membership?.level ?: 1,
                        userXP = membership?.localXP ?: 0,
                        unreadCount = 0,
                        lastActivityPreview = "",
                        lastActivityAt = community.updatedAt,
                        isCreatorLive = false,
                        isVerified = false
                    ))
                }
            }

            listItems.sortWith(compareByDescending<CommunityListItem> { it.isCreatorLive }
                .thenByDescending { it.lastActivityAt })

            _myCommunities.value = listItems
            communityListCache = CachedItem(listItems, System.currentTimeMillis(), listTTL)
            Log.d(TAG, "Loaded ${listItems.size} communities for $userID")
            return listItems
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Fetch All Communities (Discovery)

    suspend fun fetchAllCommunities(): List<CommunityListItem> {
        allCommunitiesCache?.let { if (!it.isExpired) return it.value }

        val snapshot = db.collection(Col.COMMUNITIES)
            .whereEqualTo("isActive", true)
            .orderBy("memberCount", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(50).get().await()

        val items = snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val c = Community.fromFirestore(doc.id, data)
            CommunityListItem(
                id = c.id, creatorUsername = c.creatorUsername,
                creatorDisplayName = c.creatorDisplayName, creatorTier = c.creatorTier,
                profileImageURL = c.profileImageURL, memberCount = c.memberCount,
                userLevel = 0, userXP = 0, unreadCount = 0,
                lastActivityPreview = c.description, lastActivityAt = c.updatedAt,
                isCreatorLive = false, isVerified = false
            )
        }
        allCommunitiesCache = CachedItem(items, System.currentTimeMillis(), listTTL)
        return items
    }

    // MARK: - Fetch Members (Paginated)

    suspend fun fetchMembers(
        creatorID: String, limit: Int = 20, afterDocument: com.google.firebase.firestore.DocumentSnapshot? = null
    ): Pair<List<CommunityMembership>, com.google.firebase.firestore.DocumentSnapshot?> {
        var query = db.collection(Col.COMMUNITIES).document(creatorID)
            .collection(Col.MEMBERS).orderBy("level", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit.toLong())
        afterDocument?.let { query = query.startAfter(it) }

        val snapshot = query.get().await()
        val members = snapshot.documents.mapNotNull { doc ->
            doc.data?.let { CommunityMembership.fromFirestore(it) }
        }
        return Pair(members, snapshot.documents.lastOrNull())
    }

    // MARK: - Ban/Unban/Mod (Creator Only)

    suspend fun banMember(userID: String, creatorID: String) {
        db.collection(Col.COMMUNITIES).document(creatorID)
            .collection(Col.MEMBERS).document(userID).update("isBanned", true).await()
        val cacheKey = "${userID}_${creatorID}"
        membershipCache.remove(cacheKey)
        isMemberCache[cacheKey] = CachedItem(false, System.currentTimeMillis(), membershipTTL)
    }

    suspend fun unbanMember(userID: String, creatorID: String) {
        db.collection(Col.COMMUNITIES).document(creatorID)
            .collection(Col.MEMBERS).document(userID).update("isBanned", false).await()
        membershipCache.remove("${userID}_${creatorID}")
        isMemberCache.remove("${userID}_${creatorID}")
    }

    suspend fun setModerator(userID: String, creatorID: String, isMod: Boolean) {
        if (isMod) {
            val membership = fetchMembership(userID, creatorID)
            if (membership == null || !membership.canBeNominatedMod) throw CommunityError.LevelTooLow
        }
        db.collection(Col.COMMUNITIES).document(creatorID)
            .collection(Col.MEMBERS).document(userID).update("isModerator", isMod).await()
        membershipCache.remove("${userID}_${creatorID}")
    }

    // MARK: - Feature Gate Check

    suspend fun canAccess(feature: CommunityFeatureGate, userID: String, creatorID: String): Boolean {
        val membership = fetchMembership(userID, creatorID) ?: return false
        return membership.isUnlocked(feature)
    }

    // MARK: - Cache Management

    fun invalidateMembershipCache(userID: String, creatorID: String) {
        membershipCache.remove("${userID}_${creatorID}")
    }

    fun invalidateListCache() { communityListCache = null }

    fun clearAllCaches() {
        communityCache.clear(); membershipCache.clear()
        communityListCache = null; isMemberCache.clear()
        allCommunitiesCache = null
        _myCommunities.value = emptyList()
    }

    fun pruneExpiredCaches() {
        communityCache.entries.removeAll { it.value.isExpired }
        membershipCache.entries.removeAll { it.value.isExpired }
        isMemberCache.entries.removeAll { it.value.isExpired }
        if (communityListCache?.isExpired == true) communityListCache = null
        if (allCommunitiesCache?.isExpired == true) allCommunitiesCache = null
    }
}