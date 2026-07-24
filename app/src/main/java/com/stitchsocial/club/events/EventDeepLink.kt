package com.stitchsocial.club.events

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Carries "open this event's Hub" from a notification tap to DiscoveryView
 * (iOS parity with the AppDelegate → pendingEventID deep-link route).
 *
 * DiscoveryView is the ONLY place that can present the Hub: it owns the shared
 * [EventsViewModel], the root zIndex-200 overlay, and the recorder + fullscreen-
 * deck callbacks the Hub needs. But the two tap sources sit on the far side of
 * the tab host — the in-app Notifications tab (a different `when(selectedTab)`
 * branch, so DiscoveryView isn't even composed) and a cold-start FCM intent
 * (before any tab exists) — and this codebase has no NavHost to route through.
 * So the id is parked here and picked up once Discovery is on screen.
 *
 * Compose state rather than a plain var so DiscoveryView can key a
 * LaunchedEffect on it and react the moment it's set — the same shape as
 * `MainActivity.notificationTrigger`. Process-scoped `object` so the id survives
 * the tab switch (and the Activity recreation a cold-start tap goes through),
 * mirroring [EventMomentBridge].
 */
object EventDeepLink {

    /** The eventID awaiting presentation; null = nothing pending. */
    val pending: MutableState<String?> = mutableStateOf(null)

    /** A notification tap asks for this event's Hub. */
    fun request(eventID: String) {
        if (eventID.isNotBlank()) pending.value = eventID
    }

    /**
     * Consumed exactly once, by DiscoveryView, as it opens the Hub. Clearing on
     * open (not on dismiss) is what stops a later return to the Discovery tab
     * from re-presenting a Hub the user already closed.
     */
    fun consume(): String? {
        val id = pending.value
        pending.value = null
        return id
    }
}
