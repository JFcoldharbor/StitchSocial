/*
 * CollectionProgress.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation — Watch progress tracking
 * Mirror of Swift CollectionProgress struct
 * Dependencies: Foundation only
 *
 * FIRESTORE PATH: collectionProgress/{userID}_{collectionID}
 * CACHING: CollectionService caches in-memory per session.
 *          Invalidated on every updateWatchProgress write.
 */

package com.stitchsocial.club.foundation

import java.util.Date

data class CollectionProgress(
    /** Composite key: {userID}_{collectionID} */
    val id: String,
    val userID: String,
    val collectionID: String,

    // Current position
    var currentSegmentID: String,
    var currentSegmentIndex: Int = 0,
    var currentTimestamp: Double = 0.0,         // seconds within segment

    // Completion tracking
    var completedSegmentIDs: List<String> = emptyList(),
    var segmentProgress: Map<String, Double> = emptyMap(), // segmentID → lastTimestamp
    var percentComplete: Double = 0.0,
    var totalWatchTime: Double = 0.0,           // seconds, cumulative

    // Timestamps
    val startedAt: Date = Date(),
    var lastWatchedAt: Date = Date()
) {

    // ── Computed ──

    val isInProgress: Boolean get() = percentComplete > 0.0 && percentComplete < 1.0
    val isCompleted: Boolean get() = percentComplete >= 1.0
    val isNotStarted: Boolean get() = percentComplete == 0.0 && totalWatchTime == 0.0
    val completedSegmentCount: Int get() = completedSegmentIDs.size

    val shouldShowResumePrompt: Boolean
        get() {
            val daysSince = ((Date().time - lastWatchedAt.time) / 86_400_000L).toInt()
            return isInProgress && daysSince < 30
        }

    val formattedCurrentTimestamp: String
        get() {
            val m = currentTimestamp.toInt() / 60
            val s = currentTimestamp.toInt() % 60
            return String.format("%d:%02d", m, s)
        }

    val resumePromptText: String
        get() = "Continue from $formattedCurrentTimestamp in Part ${currentSegmentIndex + 1}?"

    // ── Mutations (returns copy — data class stays immutable in-flow) ──

    fun withUpdatedPosition(
        segmentID: String,
        segmentIndex: Int,
        timestamp: Double
    ): CollectionProgress {
        val newProgress = segmentProgress.toMutableMap().also { it[segmentID] = timestamp }
        return copy(
            currentSegmentID = segmentID,
            currentSegmentIndex = segmentIndex,
            currentTimestamp = timestamp,
            segmentProgress = newProgress,
            lastWatchedAt = Date()
        )
    }

    fun withSegmentCompleted(segmentID: String): CollectionProgress {
        val updated = if (segmentID in completedSegmentIDs) completedSegmentIDs
                      else completedSegmentIDs + segmentID
        return copy(completedSegmentIDs = updated, lastWatchedAt = Date())
    }

    fun withWatchTimeAdded(seconds: Double): CollectionProgress =
        copy(totalWatchTime = totalWatchTime + seconds, lastWatchedAt = Date())

    fun withPercentUpdated(totalSegments: Int): CollectionProgress {
        val pct = if (totalSegments > 0)
            completedSegmentIDs.size.toDouble() / totalSegments else 0.0
        return copy(percentComplete = pct)
    }

    fun withAdvanceToNextSegment(nextID: String, nextIndex: Int): CollectionProgress {
        val completed = if (nextIndex > currentSegmentIndex)
            (completedSegmentIDs + currentSegmentID).distinct()
        else completedSegmentIDs
        return copy(
            completedSegmentIDs = completed,
            currentSegmentID = nextID,
            currentSegmentIndex = nextIndex,
            currentTimestamp = 0.0,
            lastWatchedAt = Date()
        )
    }

    fun progressFor(segmentID: String): Double = segmentProgress[segmentID] ?: 0.0
    fun isSegmentCompleted(segmentID: String): Boolean = segmentID in completedSegmentIDs

    // ── Factory ──

    companion object {
        fun start(userID: String, collectionID: String, firstSegmentID: String) =
            CollectionProgress(
                id = "${userID}_${collectionID}",
                userID = userID,
                collectionID = collectionID,
                currentSegmentID = firstSegmentID
            )

        fun progressID(userID: String, collectionID: String) = "${userID}_${collectionID}"
    }
}