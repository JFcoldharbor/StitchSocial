package com.stitchsocial.club.services

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * BlockService - User blocking.
 *
 * Mirrors iOS StitchSocial/Moderation/BlockService.swift. Writes two edges so
 * either side can filter without a join:
 *   - users/{blockerID}/blocked/{targetID}
 *   - users/{targetID}/blockedBy/{blockerID}
 *
 * The current user's `blocked` subcollection is listened to live; `blockedUserIds`
 * is exposed as a StateFlow so feed/list views can filter reactively.
 *
 * Required by App Store Guideline 1.2 / Play Store UGC policy — blocked
 * content must disappear from the feed instantly without app restart.
 *
 * Database: stitchfin
 */
class BlockService private constructor() {

    companion object {
        const val TAG = "BlockService"
        val shared = BlockService()
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val auth = FirebaseAuth.getInstance()
    private var listener: ListenerRegistration? = null

    private val _blockedUserIds = MutableStateFlow<Set<String>>(emptySet())
    val blockedUserIds: StateFlow<Set<String>> = _blockedUserIds.asStateFlow()

    // MARK: - Live block list

    /**
     * Start listening to the current user's blocked list. Call once after
     * sign-in. Updates [blockedUserIds] reactively so feeds can filter without
     * polling.
     */
    fun startListening() {
        val userId = auth.currentUser?.uid ?: return
        listener?.remove()

        listener = db.collection("users").document(userId)
            .collection("blocked")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (com.stitchsocial.club.BuildConfig.DEBUG) {
                        Log.e(TAG, "Listener error: ${error.message}")
                    }
                    return@addSnapshotListener
                }
                val ids = snapshot?.documents?.mapNotNull { it.id }?.toSet() ?: emptySet()
                _blockedUserIds.value = ids
            }
    }

    fun stopListening() {
        listener?.remove()
        listener = null
        _blockedUserIds.value = emptySet()
    }

    // MARK: - Block / Unblock

    suspend fun blockUser(targetUserId: String): Result<Unit> {
        val blockerId = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Sign in to block users."))
        if (blockerId == targetUserId) {
            return Result.failure(IllegalArgumentException("You can't block yourself."))
        }

        return try {
            val now = Timestamp(Date())
            val batch = db.batch()

            val blockedRef = db.collection("users").document(blockerId)
                .collection("blocked").document(targetUserId)
            batch.set(blockedRef, mapOf(
                "blockedUserID" to targetUserId,
                "createdAt" to now
            ))

            val blockedByRef = db.collection("users").document(targetUserId)
                .collection("blockedBy").document(blockerId)
            batch.set(blockedByRef, mapOf(
                "blockerID" to blockerId,
                "createdAt" to now
            ))

            batch.commit().await()
            if (com.stitchsocial.club.BuildConfig.DEBUG) {
                Log.d(TAG, "🚫 BLOCK: $blockerId → $targetUserId")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ blockUser failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun unblockUser(targetUserId: String): Result<Unit> {
        val blockerId = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Sign in to manage blocks."))

        return try {
            val batch = db.batch()
            batch.delete(
                db.collection("users").document(blockerId)
                    .collection("blocked").document(targetUserId)
            )
            batch.delete(
                db.collection("users").document(targetUserId)
                    .collection("blockedBy").document(blockerId)
            )
            batch.commit().await()
            if (com.stitchsocial.club.BuildConfig.DEBUG) {
                Log.d(TAG, "✅ UNBLOCK: $blockerId → $targetUserId")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ unblockUser failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun isBlocked(userId: String): Boolean = _blockedUserIds.value.contains(userId)
}
