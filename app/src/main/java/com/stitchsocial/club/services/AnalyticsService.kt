/*
 * AnalyticsService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Services — custom Firebase Analytics events.
 *
 * Firebase Analytics was already linked (firebase-analytics + the google-services
 * plugin + google-services.json), so auto-collection — first_open, session_start,
 * screen_view, in-app purchase — has been flowing all along. What was missing was
 * CUSTOM events: nothing in the app called logEvent. This is that layer.
 *
 * Scope is the core funnel, not blanket instrumentation: record -> post, the
 * community join/post/stitch path, collection play/resume, live joins, referral
 * shares. Deliberately small — every event here answers a question someone
 * actually asks ("do people who record finish posting?", "does resume get used?").
 *
 * Usage: call [init] once from MainActivity.onCreate, then the typed helpers.
 * Every call is null-safe before init and swallows its own failures — analytics
 * must never take down a user flow.
 *
 * Event/param names follow Firebase rules: snake_case, <=40 chars, params <=100.
 */

package com.stitchsocial.club.services

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.stitchsocial.club.BuildConfig

object AnalyticsService {

    private const val TAG = "Analytics"
    private var analytics: FirebaseAnalytics? = null

    fun init(context: Context) {
        if (analytics != null) return
        analytics = runCatching { FirebaseAnalytics.getInstance(context.applicationContext) }
            .onFailure { Log.w(TAG, "init failed: ${it.message}") }
            .getOrNull()
    }

    /** Ties events to the signed-in user so funnels can be followed across sessions. */
    fun setUser(userID: String?) {
        runCatching { analytics?.setUserId(userID) }
    }

    // ── Core funnel ──────────────────────────────────────────────────────────

    /** A clip finished recording in the app recorder (before any edit/post). */
    fun videoRecorded(context: String) =
        log("video_recorded", "recording_context" to context)

    /** A global thread/stitch video was published. */
    fun videoPosted(context: String, durationSeconds: Int) =
        log("video_posted", "recording_context" to context, "duration_seconds" to durationSeconds)

    /** A video reply (stitch) was published to a community post. */
    fun stitchCreated(communityID: String) =
        log("stitch_created", "community_id" to communityID)

    /** User joined a community/channel. */
    fun communityJoined(communityID: String) =
        log("community_joined", "community_id" to communityID)

    /** A community-only video post was published. */
    fun communityPostCreated(communityID: String, hasCaption: Boolean) =
        log("community_post_created", "community_id" to communityID, "has_caption" to hasCaption)

    /**
     * A collection started playing. [resumed] separates a fresh play from a
     * resume — the whole point of surfacing watch progress, so it needs to be
     * measurable.
     */
    fun collectionPlayed(collectionID: String, contentType: String, resumed: Boolean) =
        log(
            "collection_played",
            "collection_id" to collectionID,
            "content_type" to contentType,
            "resumed" to resumed,
        )

    /** Viewer opened a live stream. */
    fun liveStreamJoined(streamID: String) =
        log("live_stream_joined", "stream_id" to streamID)

    /** Referral link shared out. */
    fun referralShared(channel: String) =
        log("referral_shared", "channel" to channel)

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private fun log(name: String, vararg params: Pair<String, Any?>) {
        val fa = analytics ?: return
        runCatching {
            val bundle = Bundle().apply {
                params.forEach { (key, value) ->
                    when (value) {
                        null -> Unit
                        is String -> putString(key, value.take(100))
                        is Int -> putLong(key, value.toLong())
                        is Long -> putLong(key, value)
                        is Double -> putDouble(key, value)
                        is Boolean -> putString(key, if (value) "true" else "false")
                        else -> putString(key, value.toString().take(100))
                    }
                }
            }
            fa.logEvent(name, bundle)
            if (BuildConfig.DEBUG) Log.d(TAG, "📊 $name ${params.toList()}")
        }.onFailure { Log.w(TAG, "logEvent($name) failed: ${it.message}") }
    }
}
