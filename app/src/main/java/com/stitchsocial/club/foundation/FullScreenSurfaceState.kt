/*
 * FullScreenSurfaceState.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation — "a full-screen surface is up, hide the app chrome".
 *
 * Community, Events and Collections are full-screen takeovers: the custom dipped
 * tab bar must not be visible on any of them.
 *
 * Why this exists instead of the callback that was here before:
 *
 * The takeovers render at zIndex 200 *inside* DiscoveryView, but CustomDippedTabBar
 * is a later sibling of DiscoveryView's parent Box with an equal zIndex(0f).
 * zIndex doesn't reach across that parent boundary, and between equal-zIndex
 * siblings the LATER one draws on top — so a takeover can never cover the bar by
 * layering. The ONLY thing that hides the bar is a boolean, which makes that
 * boolean load-bearing: if it's wrong for even one frame, the bar shows through.
 *
 * The previous route for that boolean was DiscoveryView -> LaunchedEffect ->
 * onTabBarVisibilityChange -> TabContent -> MainActivity. Two ways that loses:
 *
 *  1. LaunchedEffect runs AFTER composition commits, so on the first frame of a
 *     takeover (and on a deep link that opens straight into Events) the bar can
 *     render before the effect ever runs.
 *  2. MainActivity unmounts TabContent whenever a modal is up, disposing
 *     DiscoveryView. Nothing reset the flag on dispose, so it could latch.
 *
 * This object is written SYNCHRONOUSLY from the click handler that changes the
 * category — no effect, no frame delay, no callback chain to misroute — and is
 * cleared on dispose so it can never latch hidden.
 */

package com.stitchsocial.club.foundation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FullScreenSurfaceState {

    private val _isUp = MutableStateFlow(false)

    /** True while a full-screen takeover owns the screen. */
    val isUp: StateFlow<Boolean> = _isUp.asStateFlow()

    /** Set from the interaction that opens/closes the surface, not from an effect. */
    fun set(up: Boolean) {
        _isUp.value = up
    }

    /** Clear on dispose so the bar can never stay hidden after the host goes away. */
    fun clear() {
        _isUp.value = false
    }
}
