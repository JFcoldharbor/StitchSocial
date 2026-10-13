package com.stitchsocial.club.foundation

/**
 * Display-only follower counts for three accounts, shown in place of the real
 * count on the profile's Stitchers stat and the Stitchers list header.
 *
 * The same table lives in the iOS app (ProfileView.swift and
 * StitchersListView.swift, seededFollowerCounts) and on the server
 * (index.js, DISPLAY_FOLLOWER_COUNTS, used by the /invite page). Change all
 * three together.
 */
object DisplayFollowerCounts {
    private val counts = mapOf(
        "4ifwg1CxDGbZ9amfPOvl0lMR6982" to 11500, // Fortune5ks
        "AZUAsfkobQWSqXzgTR1UM2uogZn2" to 10200, // Teddy Ruks
        "zh4vp0tQJOV15wyJXOu5a2cLBf73" to 8500,  // Tray Chaney
    )

    /** The count to show for [userID]: its display count if it has one, else [actual]. */
    fun forUser(userID: String, actual: Int): Int = counts[userID] ?: actual
}
