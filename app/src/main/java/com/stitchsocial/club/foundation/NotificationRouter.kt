package com.stitchsocial.club.foundation

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * The one hand-off for "a notification was tapped" — push (MainActivity) and
 * inbox row (NotificationViewModel) alike — and for a tapped shared link
 * ([TYPE_SHARED_LINK]), so links interrupt and ask "Leave?" the same way.
 *
 * Same shape as [com.stitchsocial.club.events.EventDeepLink]: the tap arrives where the presentation state
 * doesn't live (the Activity, a ViewModel), and on a cold start before
 * MainScreen exists at all. So the raw notification is parked here and MainScreen
 * resolves it through [NotificationDestination.resolve] once the user is signed
 * in and the main UI is up.
 *
 * Raw type + payload rather than a resolved destination, so there is exactly one
 * place that decides where a notification goes.
 *
 * A single slot: newest wins. Two taps before the UI is ready means the user
 * wants the second one. Compose state, so the router reacts the moment it's set
 * (warm taps) and still sees it when it first composes (cold taps).
 */
object NotificationRouter {

    data class Request(
        val type: String?,
        val data: Map<String, String>,
        /** Distinguishes two taps on the same notification. */
        val requestedAt: Long = System.currentTimeMillis()
    )

    /**
     * Type for a tapped stitchsocial.me link (/v/, /u/, /e/, /l/). Links carry only
     * the id, so [NotificationDestination.resolve] reads it from the payload.
     */
    const val TYPE_SHARED_LINK = "shared_link"

    val pending: MutableState<Request?> = mutableStateOf(null)

    fun request(type: String?, data: Map<String, String>) {
        pending.value = Request(type, data)
    }

    /** Consumed exactly once, as the route starts. */
    fun consume(): Request? {
        val r = pending.value
        pending.value = null
        return r
    }
}
