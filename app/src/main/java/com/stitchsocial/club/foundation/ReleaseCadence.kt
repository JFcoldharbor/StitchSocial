/*
 * ShowSchedule.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1/4: Foundation + Service — Release cadence model and slot computation
 * Mirrors ShowSchedule.swift exactly.
 *
 * CACHING: ScheduleService is pure logic — zero Firestore reads.
 *          All inputs come from already-loaded in-memory data.
 * BATCHING: applyReorderedSchedule in ShowService batches all date writes in one commit.
 */

package com.stitchsocial.club.foundation

import com.google.firebase.Timestamp
import com.stitchsocial.club.foundation.VideoCollection
import java.util.*

// ─────────────────────────────────────────────
// MARK: - Release Cadence
// ─────────────────────────────────────────────

enum class ReleaseCadence(val rawValue: String) {
    ONE_OFF("oneOff"),      // Single premiere — not repeating
    DAILY("daily"),
    WEEKLY("weekly"),
    BIWEEKLY("biweekly"),
    MONTHLY("monthly"),
    CUSTOM("custom");       // No auto-suggest — creator picks manually

    val displayName: String get() = when (this) {
        ONE_OFF  -> "One-Time"
        DAILY    -> "Daily"
        WEEKLY   -> "Weekly"
        BIWEEKLY -> "Bi-Weekly"
        MONTHLY  -> "Monthly"
        CUSTOM   -> "Custom"
    }

    val icon: String get() = when (this) {
        ONE_OFF  -> "star"
        DAILY    -> "wb_sunny"
        WEEKLY   -> "calendar_today"
        BIWEEKLY -> "event_repeat"
        MONTHLY  -> "calendar_month"
        CUSTOM   -> "tune"
    }

    /** Days between episodes. Null = no auto-spacing. */
    val intervalDays: Int? get() = when (this) {
        ONE_OFF  -> null
        DAILY    -> 1
        WEEKLY   -> 7
        BIWEEKLY -> 14
        MONTHLY  -> 30
        CUSTOM   -> null
    }

    val isRepeating: Boolean get() = intervalDays != null

    companion object {
        fun from(raw: String?): ReleaseCadence =
            values().firstOrNull { it.rawValue == raw } ?: CUSTOM
    }
}

// ─────────────────────────────────────────────
// MARK: - ShowScheduleConfig
// ─────────────────────────────────────────────

/**
 * Stored on the Show doc — defines the release template.
 * Uses primitive fields for Firestore compat (no nested objects).
 */
data class ShowScheduleConfig(
    val cadence: ReleaseCadence,
    /** Calendar.DAY_OF_WEEK: 1=Sun, 2=Mon … 7=Sat */
    val releaseWeekday: Int = 3,   // Tuesday
    val releaseHour: Int = 20,     // 8pm
    val releaseMinute: Int = 0
) {
    val releaseTimeDisplay: String get() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, releaseHour)
        cal.set(Calendar.MINUTE, releaseMinute)
        val fmt = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
        return fmt.format(cal.time)
    }

    val weekdayName: String get() {
        val names = listOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat")
        val idx = (releaseWeekday - 1).coerceIn(0, 6)
        return names[idx]
    }

    fun toFirestore(): Map<String, Any> = mapOf(
        "releaseCadence" to cadence.rawValue,
        "releaseWeekday" to releaseWeekday,
        "releaseHour"    to releaseHour,
        "releaseMinute"  to releaseMinute
    )

    companion object {
        val default_ = ShowScheduleConfig(
            cadence = ReleaseCadence.WEEKLY,
            releaseWeekday = 3,
            releaseHour = 20,
            releaseMinute = 0
        )

        fun from(data: Map<String, Any>): ShowScheduleConfig? {
            val raw = data["releaseCadence"] as? String ?: return null
            return ShowScheduleConfig(
                cadence        = ReleaseCadence.from(raw),
                releaseWeekday = (data["releaseWeekday"] as? Long)?.toInt() ?: 3,
                releaseHour    = (data["releaseHour"]    as? Long)?.toInt() ?: 20,
                releaseMinute  = (data["releaseMinute"]  as? Long)?.toInt() ?: 0
            )
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - ScheduleService
// ─────────────────────────────────────────────

/**
 * Pure logic — no Firestore reads. Zero cost.
 * Mirrors iOS ScheduleService enum exactly.
 */
object ScheduleService {

    /**
     * Returns the next open premiere slot given cadence config + already-scheduled episodes.
     * CACHING: Zero reads — operates on caller's in-memory episode list.
     *
     * @param config          Show schedule config
     * @param scheduledEpisodes Episodes with future publishedAt
     * @param minDate         Earliest acceptable date (default: now + 30 min)
     * @return Suggested Date, or null if cadence is custom/oneOff
     */
    fun nextAvailableSlot(
        config: ShowScheduleConfig,
        scheduledEpisodes: List<VideoCollection>,
        minDate: Date = Date(System.currentTimeMillis() + 30 * 60 * 1000L)
    ): Date? {
        if (config.cadence == ReleaseCadence.CUSTOM || config.cadence == ReleaseCadence.ONE_OFF) return null
        val interval = config.cadence.intervalDays ?: return null

        val takenDates = scheduledEpisodes
            .mapNotNull { it.publishedAt }
            .filter { it.after(minDate) }

        val anchor = takenDates.maxOrNull() ?: minDate

        for (week in 1..52) {
            val candidate: Date = if (config.cadence == ReleaseCadence.WEEKLY ||
                                      config.cadence == ReleaseCadence.BIWEEKLY) {
                val base = addDays(anchor, interval * week)
                nextOccurrenceOfWeekday(
                    weekday = config.releaseWeekday,
                    hour = config.releaseHour,
                    minute = config.releaseMinute,
                    onOrAfter = base
                ) ?: base
            } else {
                val base = addDays(anchor, interval * week)
                setTime(base, config.releaseHour, config.releaseMinute)
            }

            if (!candidate.after(minDate)) continue

            val conflict = takenDates.any { Math.abs(it.time - candidate.time) < 43_200_000L }
            if (!conflict) return candidate
        }
        return null
    }

    /**
     * Recomputes publishedAt for all episodes after drag-reorder.
     * BATCHING: Returns pairs — caller does one batch write.
     *
     * @param episodes Episodes in new display order
     * @param config   Show cadence config
     * @return List of (episodeID, newDate) pairs
     */
    fun recomputeDates(
        episodes: List<VideoCollection>,
        config: ShowScheduleConfig
    ): List<Pair<String, Date>> {
        if (config.cadence == ReleaseCadence.CUSTOM) return emptyList()
        val interval = config.cadence.intervalDays ?: return emptyList()
        val anchor = episodes.firstOrNull()?.publishedAt
            ?: Date(System.currentTimeMillis() + 86_400_000L)

        return episodes.mapIndexed { idx, ep ->
            val base = addDays(anchor, interval * idx)
            val dated = setTime(base, config.releaseHour, config.releaseMinute)
            ep.id to dated
        }
    }

    // ── Private helpers ──

    private fun addDays(date: Date, days: Int): Date =
        Date(date.time + days * 86_400_000L)

    private fun setTime(date: Date, hour: Int, minute: Int): Date {
        val cal = Calendar.getInstance()
        cal.time = date
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    private fun nextOccurrenceOfWeekday(
        weekday: Int, hour: Int, minute: Int, onOrAfter: Date
    ): Date? {
        val cal = Calendar.getInstance()
        cal.time = onOrAfter
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // Advance to the target weekday
        val current = cal.get(Calendar.DAY_OF_WEEK)
        val diff = (weekday - current + 7) % 7
        cal.add(Calendar.DAY_OF_YEAR, if (diff == 0) 7 else diff)
        return cal.time
    }
}