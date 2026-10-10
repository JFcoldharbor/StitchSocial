package com.stitchsocial.club.landing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tags the next post with the Landing it was recorded for.
 *
 * Same shape as [com.stitchsocial.club.community.CommunityClipRouter]: the
 * recorder is a modal state machine owned by MainActivity and can't take a
 * closure, so the Landing screen arms this right before opening it and
 * VideoCoordinator.createVideoDocument reads it when it writes the video doc.
 * Unlike the community router the post stays an ordinary global video — the
 * Landing fields just ride along on it:
 *
 *   landingID, landingSpot, landingVisitID, incentivized: true
 *
 * The server trigger (onLandingVideoCreated) checks the visit and issues the
 * post perk; the Landing screen shows "Unlocking…" until that perk appears.
 *
 * Disarmed by every way out of the recorder (cancel, a notification tap), so
 * a later, unrelated post can never pick up a stale tag.
 *
 * Layer 4: Core Services
 */
object LandingPostRouter {

    data class Target(
        val landingID: String,
        val spot: String?,
        val visitID: String,
        val armedAt: Long = System.currentTimeMillis()
    )

    /** A visit is good for its day; past a few hours the tag is stale anyway. */
    private const val MAX_AGE_MS = 4L * 60 * 60 * 1000

    private val _target = MutableStateFlow<Target?>(null)
    val target: StateFlow<Target?> = _target.asStateFlow()

    /** Landing ID → video id, set the moment the tagged doc is written. */
    private val _posted = MutableStateFlow<Map<String, String>>(emptyMap())
    val posted: StateFlow<Map<String, String>> = _posted.asStateFlow()

    fun arm(landingID: String, spot: String?, visitID: String) {
        _target.value = Target(landingID, spot, visitID)
    }

    fun cancel() {
        _target.value = null
    }

    /**
     * Called by createVideoDocument: the fields to merge into the new video,
     * or empty when nothing is armed. Consumes the target — one post per arm.
     */
    fun takeFields(): Pair<Target, Map<String, Any>>? {
        val t = _target.value ?: return null
        _target.value = null
        if (System.currentTimeMillis() - t.armedAt > MAX_AGE_MS) return null
        val fields = buildMap<String, Any> {
            put("landingID", t.landingID)
            t.spot?.let { put("landingSpot", it) }
            put("landingVisitID", t.visitID)
            put("incentivized", true)
        }
        return t to fields
    }

    fun didPost(landingID: String, videoID: String) {
        _posted.value = _posted.value + (landingID to videoID)
    }
}
