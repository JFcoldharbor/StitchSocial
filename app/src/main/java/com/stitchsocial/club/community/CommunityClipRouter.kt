/*
 * CommunityClipRouter.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Services - routes a clip recorded in the real app recorder back to
 * the community surface that asked for it, instead of publishing a global video.
 *
 * Port of iOS e6f51f6 (`RecordingView.onCommunityClipReady` / `routeFinishedEdit`).
 * On iOS the recorder is a view that can take a closure; on Android the recorder
 * is a modal state machine owned by MainActivity, so the "hand the finished edit
 * back instead of pushing ThreadComposer" contract lives here as a small router.
 *
 * Flow:
 *   community FAB / post Stitch
 *     -> request(communityID, postID)          — arms the router
 *     -> MainActivity shows ModalState.RECORDING (the same cinematic recorder
 *        + VideoReviewView editor the main + button uses)
 *     -> VideoReviewView finishes: MainActivity sees the router is armed and
 *        calls deliver(path) with the export-baked, trimmed clip, then dismisses
 *        the modal — so NO global video doc, no parallel processing, no
 *        ThreadComposer.
 *     -> the community surface collects [finishedClip], uploads it as a
 *        community-only post (or video reply) and calls consume().
 *
 * Posts stay community-only (communities/{id}/posts) — same as iOS.
 */

package com.stitchsocial.club.community

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CommunityClipRouter {

    /**
     * What the pending recording is for.
     *
     * @param communityID the channel the clip belongs to.
     * @param postID when non-null the clip is a video reply (a "stitch") to that
     *   post; when null it's a new community thread.
     */
    data class Target(val communityID: String, val postID: String? = null)

    data class FinishedClip(val target: Target, val videoPath: String)

    private val _target = MutableStateFlow<Target?>(null)

    /** Non-null while a community surface is waiting on the recorder. */
    val target: StateFlow<Target?> = _target.asStateFlow()

    private val _finishedClip = MutableStateFlow<FinishedClip?>(null)
    val finishedClip: StateFlow<FinishedClip?> = _finishedClip.asStateFlow()

    /** Arm the router right before showing the recording modal. */
    fun request(communityID: String, postID: String? = null) {
        _finishedClip.value = null
        _target.value = Target(communityID, postID)
    }

    /**
     * Hand back the fully-processed clip (trim + edits already baked in by the
     * review step). No-op when the router isn't armed, so the normal global
     * recording flow is untouched.
     */
    fun deliver(videoPath: String) {
        val t = _target.value ?: return
        _target.value = null
        _finishedClip.value = FinishedClip(t, videoPath)
    }

    /** User backed out of the recorder/editor — drop the pending request. */
    fun cancel() {
        _target.value = null
    }

    /** Called by the consumer once the clip has been handed to the composer. */
    fun consume() {
        _finishedClip.value = null
    }

    /** True when the recorder should route its result here instead of going global. */
    val isArmed: Boolean get() = _target.value != null
}
