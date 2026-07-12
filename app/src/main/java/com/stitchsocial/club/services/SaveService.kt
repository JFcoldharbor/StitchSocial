package com.stitchsocial.club.services

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.stitchsocial.club.foundation.CoreVideoMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * SaveService - Save-for-later bookmarks.
 *
 * Mirrors iOS StitchSocial/Services /SaveService.swift. Writes to
 * users/{uid}/savedVideos/{videoID} (owner-only per rules). Each doc carries a
 * denormalized thumbnail/title snapshot so the saved grid renders without a
 * video-doc read per row.
 *
 * Same pattern as BlockService: snapshot listener → in-memory ID set exposed
 * as a StateFlow, so any play surface can render saved-state synchronously.
 *
 * Database: stitchfin
 */
class SaveService private constructor() {

    companion object {
        const val TAG = "SaveService"
        const val PAGE_SIZE = 30
        val shared = SaveService()
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val auth = FirebaseAuth.getInstance()
    private var listener: ListenerRegistration? = null

    private val _savedVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val savedVideoIds: StateFlow<Set<String>> = _savedVideoIds.asStateFlow()

    // MARK: - Live saved list

    /** Start listening to the current user's saved list. Call once at sign-in. */
    fun startListening() {
        val userId = auth.currentUser?.uid ?: return
        listener?.remove()

        listener = db.collection("users").document(userId)
            .collection("savedVideos")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (com.stitchsocial.club.BuildConfig.DEBUG) {
                        Log.e(TAG, "Listener error: ${error.message}")
                    }
                    return@addSnapshotListener
                }
                val ids = snapshot?.documents?.mapNotNull { it.id }?.toSet() ?: emptySet()
                _savedVideoIds.value = ids
            }
    }

    fun stopListening() {
        listener?.remove()
        listener = null
        _savedVideoIds.value = emptySet()
    }

    // MARK: - Save / Unsave

    fun isSaved(videoId: String): Boolean = _savedVideoIds.value.contains(videoId)

    /** Toggle saved state. Returns the new state (true = now saved). */
    suspend fun toggleSave(video: CoreVideoMetadata): Result<Boolean> {
        return if (isSaved(video.id)) {
            unsave(video.id).map { false }
        } else {
            save(video).map { true }
        }
    }

    suspend fun save(video: CoreVideoMetadata): Result<Unit> {
        val userId = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Sign in to save videos."))

        // Optimistic: flip local state immediately; listener confirms.
        _savedVideoIds.value = _savedVideoIds.value + video.id

        return try {
            db.collection("users").document(userId)
                .collection("savedVideos").document(video.id)
                .set(mapOf(
                    "videoID" to video.id,
                    "savedAt" to Timestamp(Date()),
                    // Denormalized snapshot for the saved grid:
                    "thumbnailURL" to video.thumbnailURL,
                    "title" to video.title,
                    "creatorID" to video.creatorID,
                    "creatorName" to video.creatorName,
                    "duration" to video.duration
                ))
                .await()
            if (com.stitchsocial.club.BuildConfig.DEBUG) {
                Log.d(TAG, "🔖 SAVE: Saved ${video.id.take(8)}")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            _savedVideoIds.value = _savedVideoIds.value - video.id  // roll back
            Log.e(TAG, "❌ save failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun unsave(videoId: String): Result<Unit> {
        val userId = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Sign in to manage saved videos."))

        _savedVideoIds.value = _savedVideoIds.value - videoId

        return try {
            db.collection("users").document(userId)
                .collection("savedVideos").document(videoId)
                .delete()
                .await()
            if (com.stitchsocial.club.BuildConfig.DEBUG) {
                Log.d(TAG, "🔖 SAVE: Removed ${videoId.take(8)}")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            _savedVideoIds.value = _savedVideoIds.value + videoId  // roll back
            Log.e(TAG, "❌ unsave failed: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Saved grid feed

    data class SavedVideoRow(
        val id: String,            // videoID
        val savedAt: Date,
        val thumbnailURL: String,
        val title: String,
        val creatorID: String,
        val creatorName: String,
        val duration: Double
    )

    /** Page of saved rows, newest first. Pass the last row's savedAt to page. */
    suspend fun fetchSavedRows(after: Date? = null, limit: Long = PAGE_SIZE.toLong()): Result<List<SavedVideoRow>> {
        val userId = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Sign in to view saved videos."))

        return try {
            var query: Query = db.collection("users").document(userId)
                .collection("savedVideos")
                .orderBy("savedAt", Query.Direction.DESCENDING)
                .limit(limit)

            if (after != null) {
                query = query.startAfter(Timestamp(after))
            }

            val snapshot = query.get().await()
            val rows = snapshot.documents.map { doc ->
                SavedVideoRow(
                    id = doc.id,
                    savedAt = doc.getTimestamp("savedAt")?.toDate() ?: Date(),
                    thumbnailURL = doc.getString("thumbnailURL") ?: "",
                    title = doc.getString("title") ?: "",
                    creatorID = doc.getString("creatorID") ?: "",
                    creatorName = doc.getString("creatorName") ?: "",
                    duration = doc.getDouble("duration") ?: 0.0
                )
            }
            Result.success(rows)
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchSavedRows failed: ${e.message}")
            Result.failure(e)
        }
    }
}
