/*
 * CommunityHomeService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Services - reads for the community home page.
 *
 *  - community_home / community_setInterestCards callables (us-central1)
 *  - the viewer's membership, top members, recent posts
 *  - profile images from users/{uid}
 *
 * Every read is independent and fails on its own: a missing index, a denied
 * rule or a server that hasn't deployed community_home yet costs that one
 * section, never the page.
 */

package com.stitchsocial.club.community

import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.stitchsocial.club.BuildConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/** The parts of users/{uid} the community page shows. */
data class CommunityUserProfile(
    val userID: String,
    val username: String?,
    val displayName: String?,
    val profileImageURL: String?,
    val tier: String?,
)

class CommunityHomeService private constructor() {

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val functions by lazy { FirebaseFunctions.getInstance("us-central1") }

    // ── Callables ───────────────────────────────────────────────────────────

    /** null when the call fails — the page renders without those sections. */
    suspend fun fetchHome(communityID: String): CommunityHome? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val data = functions.getHttpsCallable("community_home")
            .call(mapOf("communityID" to communityID))
            .await()
            .data as? Map<String, Any?> ?: emptyMap()
        CommunityHome.from(data)
    }.onFailure {
        if (BuildConfig.DEBUG) println("🏠 COMMUNITY HOME: community_home failed — ${it.message}")
    }.getOrNull()

    /** Saves the owner's own switches; returns what the server stored. Throws on failure. */
    suspend fun setInterestCards(settings: InterestCardSettings): InterestCardSettings {
        @Suppress("UNCHECKED_CAST")
        val data = functions.getHttpsCallable("community_setInterestCards")
            .call(settings.toPayload())
            .await()
            .data as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val stored = data["interestCards"] as? Map<String, Any?>
        return if (stored != null) InterestCardSettings.from(stored) else settings
    }

    // ── Firestore ───────────────────────────────────────────────────────────

    suspend fun fetchMembership(communityID: String, userID: String): CommunityMembership? = runCatching {
        if (userID.isBlank()) return null
        val doc = db.collection("communities").document(communityID)
            .collection("members").document(userID).get().await()
        if (!doc.exists()) null
        else CommunityMembership.fromFirestore(doc.data.orEmpty(), docID = doc.id)
    }.onFailure {
        if (BuildConfig.DEBUG) println("🏠 COMMUNITY HOME: membership read failed — ${it.message}")
    }.getOrNull()

    /**
     * Highest level first, ties by XP. Ordered by `level` server side (a
     * single-field index, always present) and re-sorted here for the tie-break,
     * so no composite index is needed.
     */
    suspend fun fetchTopMembers(communityID: String, limit: Long = 50): List<CommunityMembership> = runCatching {
        db.collection("communities").document(communityID)
            .collection("members")
            .orderBy("level", Query.Direction.DESCENDING)
            .limit(limit)
            .get().await()
            .documents
            .map { CommunityMembership.fromFirestore(it.data.orEmpty(), docID = it.id) }
            .filter { !it.isBanned }
            .sortedWith(compareByDescending<CommunityMembership> { it.level }.thenByDescending { it.localXP })
    }.onFailure {
        if (BuildConfig.DEBUG) println("🏠 COMMUNITY HOME: top members read failed — ${it.message}")
    }.getOrDefault(emptyList())

    suspend fun fetchPosts(communityID: String, limit: Long = 20): List<CommunityPost> = runCatching {
        db.collection("communities").document(communityID)
            .collection("posts")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .get().await()
            .documents
            .mapNotNull { d -> runCatching { CommunityPost.fromFirestore(d.id, d.data.orEmpty()) }.getOrNull() }
    }.onFailure {
        if (BuildConfig.DEBUG) println("🏠 COMMUNITY HOME: posts read failed — ${it.message}")
    }.getOrDefault(emptyList())

    /** users/{uid} for each id, in chunks of 10 (the `in` limit). Missing users are skipped. */
    suspend fun fetchProfiles(userIDs: Collection<String>): Map<String, CommunityUserProfile> {
        val ids = userIDs.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return coroutineScope {
            ids.chunked(10).map { chunk ->
                async {
                    runCatching {
                        db.collection("users")
                            .whereIn(FieldPath.documentId(), chunk)
                            .get().await()
                            .documents
                            .map { d ->
                                CommunityUserProfile(
                                    userID = d.id,
                                    username = d.getString("username")?.takeIf { it.isNotBlank() },
                                    displayName = d.getString("displayName")?.takeIf { it.isNotBlank() },
                                    profileImageURL = d.getString("profileImageURL")?.takeIf { it.isNotBlank() },
                                    tier = d.getString("tier"),
                                )
                            }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().associateBy { it.userID }
        }
    }

    /** viewerCount on the live stream doc, or null. */
    suspend fun fetchViewerCount(communityID: String, streamID: String): Int? = runCatching {
        db.collection("communities").document(communityID)
            .collection("streams").document(streamID)
            .get().await()
            .getLong("viewerCount")?.toInt()
    }.getOrNull()

    companion object {
        val shared: CommunityHomeService by lazy { CommunityHomeService() }
    }
}
