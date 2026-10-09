package com.stitchsocial.club.foundation

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Carries "open this video" from a stitchsocial.me/v/{videoID} tap to
 * MainActivity's thread overlay.
 *
 * Same shape as [ProfileDeepLink]: the state that presents a thread lives in a
 * composable, while the tap arrives at the Activity — on a cold start, before
 * that composable exists. The video's thread is resolved before it gets here,
 * so the overlay opens the right thread with this video in focus.
 */
object VideoDeepLink {

    data class Target(val threadID: String, val videoID: String)

    /** The video awaiting presentation; null = nothing pending. */
    val pending: MutableState<Target?> = mutableStateOf(null)

    fun request(threadID: String, videoID: String) {
        if (threadID.isNotBlank() && videoID.isNotBlank()) pending.value = Target(threadID, videoID)
    }

    /** Consumed exactly once, as the thread opens. */
    fun consume(): Target? {
        val t = pending.value
        pending.value = null
        return t
    }
}
