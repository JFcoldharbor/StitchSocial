/*
 * VideoCollection.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation — Collection data model
 * Mirror of Swift VideoCollection struct + CollectionStatus/Visibility/ContentType enums
 * Dependencies: Foundation only (java.util.Date)
 *
 * CACHING: CollectionService caches discovery results in-memory (5-min TTL).
 *          User collections invalidated on write.
 * FIRESTORE PATH: videoCollections/{collectionID}
 * SEGMENT PATH:   videos/{segmentID}  (field: collectionID, segmentNumber)
 */

package com.stitchsocial.club.foundation

import java.util.Date

// ─────────────────────────────────────────────
// MARK: - Enums
// ─────────────────────────────────────────────

enum class CollectionStatus(val rawValue: String) {
    DRAFT("draft"),
    PROCESSING("processing"),
    PUBLISHED("published"),
    ARCHIVED("archived"),
    DELETED("deleted");

    val isPublic: Boolean get() = this == PUBLISHED

    companion object {
        fun from(raw: String?): CollectionStatus =
            values().firstOrNull { it.rawValue == raw } ?: DRAFT
    }
}

enum class CollectionVisibility(val rawValue: String) {
    PUBLIC("public"),
    FOLLOWERS_ONLY("followersOnly"),
    PRIVATE("private");

    companion object {
        fun from(raw: String?): CollectionVisibility =
            values().firstOrNull { it.rawValue == raw } ?: PUBLIC
    }
}

enum class CollectionContentType(val rawValue: String) {
    GENERAL("general"),
    SERIES("series"),
    PODCAST("podcast"),
    FILM("film"),
    COURSE("course"),
    EVENT("event");

    /** Title-case label for badges and the BY TYPE chips. */
    val displayName: String
        get() = rawValue.replaceFirstChar { it.uppercase() }

    companion object {
        fun from(raw: String?): CollectionContentType =
            values().firstOrNull { it.rawValue == raw } ?: GENERAL
    }
}

// ─────────────────────────────────────────────
// MARK: - VideoCollection
// ─────────────────────────────────────────────

data class VideoCollection(
    val id: String,
    val title: String,
    val description: String = "",
    val creatorID: String,
    val creatorName: String,
    val coverImageURL: String? = null,

    // Segments
    val segmentIDs: List<String> = emptyList(),
    val segmentCount: Int = 0,
    val totalDuration: Double = 0.0,           // seconds

    // Status & access
    val status: CollectionStatus = CollectionStatus.DRAFT,
    val visibility: CollectionVisibility = CollectionVisibility.PUBLIC,
    val contentType: CollectionContentType = CollectionContentType.GENERAL,
    val allowReplies: Boolean = true,
    val allowStitchReplies: Boolean = true,

    // Show / Season linkage
    val showId: String? = null,
    val seasonId: String? = null,
    val episodeNumber: Int? = null,

    // Engagement (server-side counters — no writes from here)
    val totalViews: Int = 0,
    val totalHypes: Int = 0,
    val totalCools: Int = 0,
    val totalReplies: Int = 0,
    val totalShares: Int = 0,

    /**
     * Segments playable without a subscription (iOS parity).
     *
     * Android decoded neither this nor [isFree], so a collection a creator
     * paywalled on iPhone played in FULL on Android — the free-preview setting
     * had no effect on half the audience.
     *
     * Client-side gating stops the UI, not a determined reader of the video doc.
     * Real enforcement needs signed playback URLs, which is a server job and is
     * open on both platforms.
     */
    val freeSegmentCount: Int = 0,
    /** Whole collection is free — overrides [freeSegmentCount]. */
    val isFree: Boolean = false,

    // Timestamps
    val publishedAt: Date? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
) {

    // ── Computed ──

    val isPublished: Boolean get() = status == CollectionStatus.PUBLISHED
    val isDraft: Boolean get() = status == CollectionStatus.DRAFT
    val isPublic: Boolean get() = visibility == CollectionVisibility.PUBLIC

    /** "Episode 3 · Title" or just "Title" — mirrors iOS episodeDisplayTitle */
    val episodeDisplayTitle: String
        get() {
            val num = episodeNumber
            return if (num != null) "Episode $num · $title".trimEnd { it == '·' || it == ' ' }
            else title.ifBlank { "Untitled" }
        }

    /** Short episode label e.g. "Ep 3" */
    val episodeShortTitle: String
        get() = episodeNumber?.let { "Ep $it" } ?: title.ifBlank { "Episode" }

    /** Formatted total duration — alias for formattedDuration */
    val formattedTotalDuration: String get() = formattedDuration

    /** Total seconds formatted as "H:MM:SS" or "M:SS" */
    val formattedDuration: String
        get() {
            val total = totalDuration.toInt()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%d:%02d", m, s)
        }

    /** e.g. "5 parts" */
    val segmentCountLabel: String
        get() = if (segmentCount == 1) "1 part" else "$segmentCount parts"

    /** Net positivity ratio */
    val engagementRatio: Double
        get() {
            val total = totalHypes + totalCools
            return if (total > 0) totalHypes.toDouble() / total else 0.5
        }

    // ── Factory ──

    companion object {
        /** Empty placeholder — use when collection ID is known but data not yet loaded */
        fun placeholder(id: String) = VideoCollection(
            id = id,
            title = "Loading…",
            creatorID = "",
            creatorName = ""
        )
    }
}