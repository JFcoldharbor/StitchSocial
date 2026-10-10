package com.stitchsocial.club.landing

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.stitchsocial.club.foundation.NotificationRouter

/**
 * Where a guest came from, sent to landing_claimWelcome as `source`
 * (addendum 1). The server counts claims by it, and only scan, link and
 * handoff claims are weighed against scans by the risk sweep.
 */
object LandingSource {
    const val SCAN = "scan"
    const val LINK = "link"
    const val SPONSORED = "sponsored"
    const val NEARBY = "nearby"
    const val SEARCH = "search"
    const val TAG = "tag"
    const val PROFILE = "profile"
    const val HANDOFF = "handoff"

    /** A /l/<slug> link: with a `t` it was printed on a table — a scan. */
    fun forLink(spot: String?): String = if (spot.isNullOrBlank()) LINK else SCAN
}

/**
 * Which Landing surface is up, held where every entry point can reach it.
 *
 * A Landing opens from a link (through NotificationRouter), from first-launch
 * routing, from the Home banner, from Discovery's "Near you" row and from the
 * profile — five places, none of which own MainScreen's layers. So the
 * request is parked here and MainScreen renders it at the ProfileView layer,
 * the same way notification screens are.
 *
 * Compose state, so MainScreen reacts the moment one is set.
 *
 * Layer 5: Navigation
 */
object LandingPresenter {

    sealed class Screen {
        /**
         * The guest Landing page. `spot` is the `t` on the QR (a table,
         * "counter"); `source` is a [LandingSource]; `autoClaim` claims the
         * welcome perk once on open (a sponsored card's CTA).
         */
        data class Guest(
            val slug: String,
            val spot: String?,
            val source: String = LandingSource.forLink(spot),
            val autoClaim: Boolean = false
        ) : Screen()
        /** Profile → Perks: every ticket, grouped Active / Ready / Used & expired. */
        object MyPerks : Screen()
        /** The business owner's Landing tab. */
        object Owner : Screen()
    }

    val screen: MutableState<Screen?> = mutableStateOf(null)

    /** The full-screen active ticket — above whichever screen opened it. */
    val activePerk: MutableState<Perk?> = mutableStateOf(null)

    /** First-launch fallback: the nearest Landing, offered as a banner on Home. */
    val homeBanner: MutableState<NearbyLanding?> = mutableStateOf(null)

    fun open(screen: Screen) {
        this.screen.value = screen
    }

    /**
     * Open a Landing through NotificationRouter, so it interrupts (and asks
     * "Leave?") like any link. Used by in-feed entry points — the "At …" tag,
     * search, a sponsored card.
     */
    fun route(slug: String, spot: String? = null, source: String, autoClaim: Boolean = false) {
        val data = mutableMapOf(
            "landingSlug" to slug,
            "landingSource" to source,
            "landingAutoClaim" to autoClaim.toString()
        )
        spot?.let { data["landingSpot"] = it }
        NotificationRouter.request(NotificationRouter.TYPE_SHARED_LINK, data)
    }

    fun close() {
        screen.value = null
    }

    /** A notification tap or link interrupts everything, Landing screens included. */
    fun dismissAll() {
        screen.value = null
        activePerk.value = null
    }
}
