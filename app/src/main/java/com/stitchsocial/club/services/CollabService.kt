package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.stitchsocial.club.BuildConfig
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * Who is on a post, and what being on it is worth — iOS parity
 * (Collab/CollabModels.swift, Collab/CollabService.swift).
 *
 * Every action here is a Cloud Function call and none of it is a Firestore
 * write, which is the design rather than an accident. Accepting an invite
 * grants credit on somebody else's post, a share of its clout and sometimes a
 * discovery boost, so a client that could write any of it could award all of
 * it to itself. firestore.rules refuses collaboratorIDs, participantIDs,
 * collaborators and collabBoost from every client.
 *
 * The shape of the deal:
 *   · a post goes live immediately, with or without an answer to the invite
 *   · nothing changes until the invitee accepts — not credit, not clout, not
 *     reach
 *   · a collaborator earns a third of what the post earns its owner, counted
 *     from acceptance forward, and nothing is taken from the owner
 *   · reaching UP earns the owner a discovery boost; reaching down earns
 *     nothing, because a bigger account's reach is already the boost
 */

/** Mirrors COLLAB in index.js, so the UI can say "4" without guessing. */
object CollabConfig {
    const val MAX_COLLABORATORS = 4
    const val INVITE_TTL_DAYS = 14
}

/**
 * One accepted collaborator, denormalised onto the video when they accepted.
 * The name is a snapshot so the credit line still reads correctly if they
 * later change their handle.
 */
data class Collaborator(
    val userID: String,
    val username: String = "",
    val displayName: String = "",
    val profileImageURL: String = "",
    val acceptedAt: Date? = null,
) {
    /** What the credit line shows, falling through the fields an older
     *  document might be missing rather than rendering nothing. */
    val handle: String
        get() = when {
            username.isNotEmpty() -> "@$username"
            displayName.isNotEmpty() -> displayName
            else -> "someone"
        }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun from(raw: Any?): Collaborator? {
            val map = raw as? Map<String, Any?> ?: return null
            val userID = map["userID"] as? String ?: return null
            if (userID.isEmpty()) return null
            return Collaborator(
                userID = userID,
                username = map["username"] as? String ?: "",
                displayName = map["displayName"] as? String ?: "",
                profileImageURL = map["profileImageURL"] as? String ?: "",
                acceptedAt = (map["acceptedAt"] as? Timestamp)?.toDate(),
            )
        }

        fun list(raw: Any?): List<Collaborator> =
            (raw as? List<*>)?.mapNotNull { from(it) } ?: emptyList()
    }
}

/**
 * The lift the OWNER earned by having somebody further up the tier ladder
 * accept. Read by discovery ranking; never written by a client.
 */
data class CollabBoost(
    val multiplier: Double,
    val expiresAt: Date?,
) {
    val isActive: Boolean
        get() = multiplier > 0 && (expiresAt?.after(Date()) == true)

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun from(raw: Any?): CollabBoost? {
            val map = raw as? Map<String, Any?> ?: return null
            val multiplier = (map["multiplier"] as? Number)?.toDouble() ?: return null
            return CollabBoost(multiplier, (map["expiresAt"] as? Timestamp)?.toDate())
        }
    }
}

enum class CollabInviteStatus { PENDING, ACCEPTED, DECLINED, EXPIRED;
    companion object {
        fun from(raw: String?) = when (raw) {
            "accepted" -> ACCEPTED
            "declined" -> DECLINED
            "expired" -> EXPIRED
            else -> PENDING
        }
    }
}

data class CollabInvite(
    val id: String,
    val videoID: String,
    val threadID: String,
    val ownerID: String,
    val inviteeID: String,
    val status: CollabInviteStatus,
    val message: String = "",
    val createdAt: Date? = null,
    val expiresAt: Date? = null,
) {
    /** Pending and not past its date. The server checks this too; this is so
     *  the UI does not offer a button that is certain to fail. */
    val isAnswerable: Boolean
        get() = status == CollabInviteStatus.PENDING &&
                (expiresAt == null || expiresAt.after(Date()))

    companion object {
        fun from(id: String, data: Map<String, Any?>): CollabInvite? {
            val videoID = data["videoID"] as? String ?: return null
            val ownerID = data["ownerID"] as? String ?: return null
            val inviteeID = data["inviteeID"] as? String ?: return null
            return CollabInvite(
                id = id,
                videoID = videoID,
                threadID = data["threadID"] as? String ?: videoID,
                ownerID = ownerID,
                inviteeID = inviteeID,
                status = CollabInviteStatus.from(data["status"] as? String),
                message = data["message"] as? String ?: "",
                createdAt = (data["createdAt"] as? Timestamp)?.toDate(),
                expiresAt = (data["expiresAt"] as? Timestamp)?.toDate(),
            )
        }
    }
}

/**
 * A refusal worth showing. The Cloud Functions already refuse for good
 * reasons — the post is full, they blocked you, an adult and a teen cannot
 * co-author — and those messages are written to be read by a person, so they
 * are passed through rather than replaced with "Something went wrong".
 */
class CollabException(val userMessage: String) : Exception(userMessage)

object CollabService {

    private val functions = FirebaseFunctions.getInstance("us-central1")
    private val db by lazy { FirebaseFirestore.getInstance("stitchfin") }
    private val auth = FirebaseAuth.getInstance()

    private fun asCollabError(e: Exception): CollabException {
        val message = (e as? FirebaseFunctionsException)?.message?.takeIf { it.isNotBlank() }
            ?: "Couldn't reach Stitch. Check your connection and try again."
        return CollabException(message)
    }

    // ---- Acting ----

    /** Ask somebody to be on a post you own. */
    suspend fun invite(videoID: String, inviteeID: String, message: String = ""): String {
        auth.currentUser ?: throw CollabException("Sign in to invite a collaborator.")
        try {
            val result = functions.getHttpsCallable("createCollabInvite").call(
                mapOf("videoID" to videoID, "inviteeID" to inviteeID, "message" to message)
            ).await()
            @Suppress("UNCHECKED_CAST")
            val data = result.data as? Map<String, Any?>
            return data?.get("inviteID") as? String ?: ""
        } catch (e: Exception) {
            throw asCollabError(e)
        }
    }

    /**
     * Accept or decline an invite addressed to you. Accepting is the only
     * moment anything changes — before it the post is already live and
     * earning, and none of it is yours.
     */
    suspend fun respond(inviteID: String, accept: Boolean) {
        auth.currentUser ?: throw CollabException("Sign in to respond.")
        try {
            functions.getHttpsCallable("respondToCollabInvite").call(
                mapOf("inviteID" to inviteID, "accept" to accept)
            ).await()
        } catch (e: Exception) {
            throw asCollabError(e)
        }
    }

    /** Take your name off a post, or — if you own it — somebody else's.
     *  Consent you cannot withdraw is not consent. */
    suspend fun leave(videoID: String, removeUserID: String? = null) {
        auth.currentUser ?: throw CollabException("Sign in to leave a collab.")
        val payload = mutableMapOf<String, Any>("videoID" to videoID)
        removeUserID?.let { payload["removeUserID"] = it }
        try {
            functions.getHttpsCallable("leaveCollab").call(payload).await()
        } catch (e: Exception) {
            throw asCollabError(e)
        }
    }

    // ---- Reading ----
    //
    // Straight to Firestore: the rule lets the two parties to an invite read
    // it and nobody else, so there is nothing a function would add.

    /** Invites the owner has sent on one post, so the sheet can count them
     *  against the cap the way the server does. */
    suspend fun pendingInvites(videoID: String): List<CollabInvite> = try {
        db.collection("collabInvites")
            .whereEqualTo("videoID", videoID)
            .whereEqualTo("status", "pending")
            .get().await()
            .documents.mapNotNull { doc ->
                doc.data?.let { CollabInvite.from(doc.id, it) }
            }
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) println("COLLAB: pending invites failed — ${e.message}")
        emptyList()
    }

    /** Invites waiting on the signed-in user. */
    suspend fun pendingInvitesForMe(): List<CollabInvite> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        return try {
            db.collection("collabInvites")
                .whereEqualTo("inviteeID", uid)
                .whereEqualTo("status", "pending")
                .get().await()
                .documents.mapNotNull { doc -> doc.data?.let { CollabInvite.from(doc.id, it) } }
                .filter { it.isAnswerable }
                .sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
