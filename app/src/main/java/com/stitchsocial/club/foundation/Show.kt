/*
 * Show.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation — Show data model
 * Mirrors ShowFormat.swift + ShowStatus.swift
 * Hierarchy: Show → Season → Episode (VideoCollection) → Segments (videos)
 *
 * CACHING: ShowService caches shows 30 min. Schedule primitives decoded
 *          into ShowScheduleConfig computed property — zero extra reads.
 */

package com.stitchsocial.club.foundation

import com.stitchsocial.club.foundation.CollectionContentType
import java.util.Date

// ─────────────────────────────────────────────
// MARK: - Enums
// ─────────────────────────────────────────────

enum class ShowFormat(val rawValue: String) {
    VERTICAL("vertical");
    companion object { fun from(raw: String?) = values().firstOrNull { it.rawValue == raw } ?: VERTICAL }
}

enum class ShowGenre(val rawValue: String) {
    PODCAST("podcast"), TUTORIAL("tutorial"), MOVIE("movie"),
    SHORT_FILM("shortFilm"), IRL("irl"), DOCUMENTARY("documentary"),
    INTERVIEW("interview"), SERIES("series"), MUSIC("music"),
    COMEDY("comedy"), OTHER("other");
    val displayName get() = when (this) {
        PODCAST -> "Podcast"; TUTORIAL -> "Tutorial"; MOVIE -> "Movie"
        SHORT_FILM -> "Short Film"; IRL -> "IRL"; DOCUMENTARY -> "Documentary"
        INTERVIEW -> "Interview"; SERIES -> "Series"; MUSIC -> "Music"
        COMEDY -> "Comedy"; OTHER -> "Other"
    }
    companion object { fun from(raw: String?) = values().firstOrNull { it.rawValue == raw } ?: OTHER }
}

enum class ShowStatus(val rawValue: String) {
    DRAFT("draft"), PUBLISHED("published"), PAUSED("paused"),
    COMPLETED("completed"), REMOVED("removed");
    val displayName get() = rawValue.replaceFirstChar { it.uppercase() }
    val isVisible get() = this == PUBLISHED || this == PAUSED || this == COMPLETED
    companion object { fun from(raw: String?) = values().firstOrNull { it.rawValue == raw } ?: DRAFT }
}

enum class ShowTag(val rawValue: String) {
    EXCLUSIVE("exclusive"), TRENDING("trending"), NEW("new"),
    PREMIERE("premiere"), BINGE("binge"), HOT("hot"), FEATURED("featured");
    val displayName get() = rawValue.uppercase()
    companion object { fun from(raw: String?) = values().firstOrNull { it.rawValue == raw } }
}

// ─────────────────────────────────────────────
// MARK: - Show Model
// ─────────────────────────────────────────────

/**
 * Top-level content container.
 * Firestore path: shows/{showId}
 *
 * Schedule stored as primitives (releaseCadence, releaseWeekday, releaseHour, releaseMinute)
 * for Firestore compat. Use scheduleConfig computed property for logic.
 */
data class Show(
    val id: String = java.util.UUID.randomUUID().toString(),
    var title: String = "",
    var description: String = "",
    val creatorID: String = "",
    var creatorName: String = "",

    var format: ShowFormat = ShowFormat.VERTICAL,
    var genre: ShowGenre = ShowGenre.OTHER,
    var contentType: CollectionContentType = CollectionContentType.SERIES,
    var tags: List<ShowTag> = emptyList(),

    var coverImageURL: String? = null,
    var thumbnailURL: String? = null,

    var status: ShowStatus = ShowStatus.DRAFT,
    var isFeatured: Boolean = false,
    var isFree: Boolean = false,

    var seasonCount: Int = 0,
    var totalEpisodes: Int = 0,
    var totalViews: Int = 0,
    var totalHypes: Int = 0,
    var totalCools: Int = 0,

    val createdAt: Date = Date(),
    var updatedAt: Date = Date(),

    // Schedule primitives — Codable-safe, no nested object in Firestore
    var releaseCadence: String? = null,
    var releaseWeekday: Int? = null,
    var releaseHour: Int? = null,
    var releaseMinute: Int? = null
) {
    /** Computed from primitives — zero Firestore reads. */
    val scheduleConfig: ShowScheduleConfig? get() {
        val cad = ReleaseCadence.from(releaseCadence) ?: return null
        if (releaseCadence == null) return null
        return ShowScheduleConfig(
            cadence = cad,
            releaseWeekday = releaseWeekday ?: 3,
            releaseHour    = releaseHour    ?: 20,
            releaseMinute  = releaseMinute  ?: 0
        )
    }

    companion object {
        fun newDraft(creatorID: String, creatorName: String) = Show(
            creatorID = creatorID,
            creatorName = creatorName
        )
    }
}

// ─────────────────────────────────────────────
// MARK: - Season
// ─────────────────────────────────────────────

enum class SeasonStatus(val rawValue: String) {
    DRAFT("draft"), PUBLISHED("published"), COMPLETED("completed"), UPCOMING("upcoming");
    val displayName get() = rawValue.replaceFirstChar { it.uppercase() }
    companion object { fun from(raw: String?) = values().firstOrNull { it.rawValue == raw } ?: DRAFT }
}

data class Season(
    val id: String = java.util.UUID.randomUUID().toString(),
    val showId: String = "",
    var number: Int = 1,
    var title: String = "",
    var description: String = "",
    var coverImageURL: String? = null,
    var status: SeasonStatus = SeasonStatus.DRAFT,
    var episodeCount: Int = 0,
    var totalViews: Int = 0,
    var totalHypes: Int = 0,
    var totalCools: Int = 0,
    val createdAt: Date = Date(),
    var updatedAt: Date = Date()
) {
    val displayTitle get() = title.ifBlank { "Season $number" }
}