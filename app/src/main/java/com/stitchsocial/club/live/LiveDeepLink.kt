package com.stitchsocial.club.live

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Carries "open this live stream" from a notification tap to MainActivity.
 *
 * Same shape and rationale as [com.stitchsocial.club.events.EventDeepLink]: the
 * live viewer is presented from inside the community screen, which isn't
 * composed while the Notifications tab is up — and on a cold FCM tap nothing is
 * composed at all. So the target is parked here and collected once the UI
 * exists.
 *
 * Carries the STREAM id as well as the community. Without it the best a tap can
 * do is open the community and hope the banner is showing; with it the viewer
 * opens directly, which is what the notification promised.
 */
object LiveDeepLink {

    data class Target(val communityID: String, val streamID: String?)

    val pending: MutableState<Target?> = mutableStateOf(null)

    fun request(communityID: String, streamID: String?) {
        if (communityID.isNotBlank()) pending.value = Target(communityID, streamID)
    }

    /**
     * Consumed once, as the stream opens. Cleared on open rather than on
     * dismiss so returning to the tab later can't re-present a stream the user
     * already closed.
     */
    fun consume(): Target? {
        val t = pending.value
        pending.value = null
        return t
    }
}
