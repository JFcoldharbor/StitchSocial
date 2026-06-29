/*
 * StreakService.kt - STITCH SOCIAL DAILY ENGAGEMENT STREAK ("streak or die")
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Port of iOS StreakService.swift. Each day you must clear an ESCALATING
 * engagement bar or the streak resets to 0:
 *   Week 1 (day 1-7):   1 hype OR 1 post
 *   Week 2 (day 8-14):  1 hype AND (1 post OR 1 reply)
 *   Week 3 (day 15-21): 1 hype AND 1 post AND 1 reply
 *   Week 4+ (day 22+):  1 hype AND 2 posts AND 1 reply
 *
 * Persisted on the user doc:
 *   dailyStreak: { current, longest, lastSecuredAt, progressDay,
 *                  todayHypes, todayPosts, todayReplies }
 *
 * recordAction()/load() are fire-and-forget (launch internally) so call sites
 * don't need to be suspend — mirrors iOS `Task { await ... }`.
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.stitchsocial.club.engagement.BoostCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A qualifying streak action, by type (posts vs replies are distinct). */
enum class StreakAction { HYPE, POST, REPLY }

class StreakService private constructor() {

    companion object {
        val shared = StreakService()

        /** Whether the counts satisfy the bar for [day] (1-based day you secure). */
        fun dayMet(day: Int, hypes: Int, posts: Int, replies: Int): Boolean = when {
            day < 8  -> hypes >= 1 || posts >= 1
            day < 15 -> hypes >= 1 && (posts >= 1 || replies >= 1)
            day < 22 -> hypes >= 1 && posts >= 1 && replies >= 1
            else     -> hypes >= 1 && posts >= 2 && replies >= 1
        }

        /** Human label for the bar at [day]. */
        fun dayLabel(day: Int): String = when {
            day < 8  -> "1 hype or 1 post"
            day < 15 -> "1 hype + 1 post or reply"
            day < 22 -> "1 hype + 1 post + 1 reply"
            else     -> "1 hype + 2 posts + 1 reply"
        }
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var loaded = false
    private var progressDay = ""
    private var lastSecuredAt: Date? = null

    private val _current = MutableStateFlow(0)
    val current: StateFlow<Int> = _current.asStateFlow()
    private val _longest = MutableStateFlow(0)
    val longest: StateFlow<Int> = _longest.asStateFlow()
    private val _todayHypes = MutableStateFlow(0)
    val todayHypes: StateFlow<Int> = _todayHypes.asStateFlow()
    private val _todayPosts = MutableStateFlow(0)
    val todayPosts: StateFlow<Int> = _todayPosts.asStateFlow()
    private val _todayReplies = MutableStateFlow(0)
    val todayReplies: StateFlow<Int> = _todayReplies.asStateFlow()
    private val _pendingCelebration = MutableStateFlow(false)
    val pendingCelebration: StateFlow<Boolean> = _pendingCelebration.asStateFlow()

    fun clearCelebration() { _pendingCelebration.value = false }

    // MARK: - Derived (read against current state; UI collects `current` to recompose)

    /** The day number you're trying to secure right now (1-based). */
    val todayTargetDay: Int
        get() {
            val last = lastSecuredAt ?: return 1
            return when {
                isToday(last) -> _current.value
                isYesterday(last) -> _current.value + 1
                else -> 1
            }
        }

    val todaySecured: Boolean get() = lastSecuredAt?.let { isToday(it) } ?: false
    val todayRequirementLabel: String get() = dayLabel(todayTargetDay)
    val weeklyBoostDays: Int get() = BoostCalculator.streakBoostDays(_current.value)
    val hasWeeklyBoost: Boolean get() = _current.value >= 7
    val daysToNextTier: Int?
        get() = when (val c = _current.value) {
            in 0..6 -> 7 - c
            in 7..13 -> 14 - c
            in 14..20 -> 21 - c
            else -> null
        }
    val isAtRisk: Boolean
        get() = _current.value >= 1 && (lastSecuredAt?.let { isYesterday(it) } ?: false)

    /** Whole hours until the streak breaks at local midnight (clamped 1..24). */
    val hoursUntilBreak: Int
        get() {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val ms = cal.timeInMillis - System.currentTimeMillis()
            return (ms / 3_600_000L).toInt().coerceIn(1, 24)
        }

    // MARK: - Load (applies the "die" when a day was missed)

    fun load() { scope.launch { loadSuspend() } }

    private suspend fun loadSuspend() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        try {
            val snap = db.collection("users").document(uid).get().await()
            @Suppress("UNCHECKED_CAST")
            val s = snap.get("dailyStreak") as? Map<String, Any?>
            if (s != null) {
                _current.value = (s["current"] as? Number)?.toInt() ?: 0
                _longest.value = (s["longest"] as? Number)?.toInt() ?: 0
                // back-compat: old field name was lastActivityAt
                lastSecuredAt = ((s["lastSecuredAt"] as? Timestamp) ?: (s["lastActivityAt"] as? Timestamp))?.toDate()
                progressDay = s["progressDay"] as? String ?: ""
                _todayHypes.value = (s["todayHypes"] as? Number)?.toInt() ?: 0
                _todayPosts.value = (s["todayPosts"] as? Number)?.toInt() ?: 0
                _todayReplies.value = (s["todayReplies"] as? Number)?.toInt() ?: 0
            }
            applyRollovers()
            loaded = true
        } catch (_: Exception) { /* offline / no doc — leave defaults */ }
    }

    /** Streak-or-die + new-day reset. Dead if lastSecuredAt older than yesterday. */
    private fun applyRollovers() {
        lastSecuredAt?.let { if (!isToday(it) && !isYesterday(it)) _current.value = 0 }
        val today = dayKey(Date())
        if (progressDay != today) {
            _todayHypes.value = 0; _todayPosts.value = 0; _todayReplies.value = 0
            progressDay = today
        }
    }

    // MARK: - Record (advances only when the day's full bar is cleared)

    fun recordAction(action: StreakAction) { scope.launch { recordActionSuspend(action) } }

    private suspend fun recordActionSuspend(action: StreakAction) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!loaded) loadSuspend()
        applyRollovers()
        val today = dayKey(Date())

        when (action) {
            StreakAction.HYPE -> _todayHypes.value += 1
            StreakAction.POST -> _todayPosts.value += 1
            StreakAction.REPLY -> _todayReplies.value += 1
        }

        val securedToday = lastSecuredAt?.let { isToday(it) } ?: false
        val extending = lastSecuredAt?.let { isYesterday(it) } ?: false
        val targetDay = if (securedToday) _current.value else if (extending) _current.value + 1 else 1

        var didSecure = false
        if (!securedToday && dayMet(targetDay, _todayHypes.value, _todayPosts.value, _todayReplies.value)) {
            _current.value = targetDay
            _longest.value = maxOf(_longest.value, _current.value)
            lastSecuredAt = Date()
            _pendingCelebration.value = true
            didSecure = true
        }

        val data = hashMapOf<String, Any>(
            "current" to _current.value,
            "longest" to _longest.value,
            "progressDay" to today,
            "todayHypes" to _todayHypes.value,
            "todayPosts" to _todayPosts.value,
            "todayReplies" to _todayReplies.value
        )
        if (didSecure) data["lastSecuredAt"] = FieldValue.serverTimestamp()
        else lastSecuredAt?.let { data["lastSecuredAt"] = Timestamp(it) }

        try {
            db.collection("users").document(uid)
                .set(mapOf("dailyStreak" to data), SetOptions.merge()).await()
        } catch (_: Exception) { /* retry next action */ }
    }

    // MARK: - Date helpers

    private fun dayKey(date: Date): String = dayFmt.format(date)
    private fun isToday(d: Date): Boolean = isSameDay(d, Date())
    private fun isYesterday(d: Date): Boolean {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return isSameDay(d, cal.time)
    }
    private fun isSameDay(a: Date, b: Date): Boolean {
        val ca = Calendar.getInstance().apply { time = a }
        val cb = Calendar.getInstance().apply { time = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
                ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}
