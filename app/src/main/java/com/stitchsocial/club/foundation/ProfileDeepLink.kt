package com.stitchsocial.club.foundation

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Carries "open this profile" from a stitchsocial.me/u/{username} tap to
 * MainActivity's profile overlay.
 *
 * Same rationale and shape as [com.stitchsocial.club.events.EventDeepLink]: the
 * state that presents a profile (`profileViewUserID` / `isShowingProfileView`)
 * lives inside a composable, while the tap arrives at the Activity — on a cold
 * start, before that composable exists at all. There's no NavHost to route
 * through, so the id is parked here and collected once the UI is up.
 *
 * Holds a userID, NOT the username from the link: every profile surface in the
 * app takes an id, so the link is resolved before it gets here.
 */
object ProfileDeepLink {

    /** The userID awaiting presentation; null = nothing pending. */
    val pending: MutableState<String?> = mutableStateOf(null)

    fun request(userID: String) {
        if (userID.isNotBlank()) pending.value = userID
    }

    /**
     * Consumed exactly once, as the profile opens. Clearing on open rather than
     * on dismiss is what stops a later recomposition from re-presenting a
     * profile the user already closed.
     */
    fun consume(): String? {
        val id = pending.value
        pending.value = null
        return id
    }
}
