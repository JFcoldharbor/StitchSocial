/*
 * SponsoredSlot.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation — First-party sponsored ad slot model
 * Mirror of iOS SponsoredSlot (Firestore collection `sponsoredSlots`, database "stitchfin").
 *
 * Backend contract:
 *   - Authenticated read; client writes may ONLY increment impressionCount/tapCount
 *     (creative fields are admin-locked by security rules).
 *   - imageURL is a 9:16 creative; videoURL is reserved and currently empty.
 */
package com.stitchsocial.club.foundation

import com.google.firebase.Timestamp
import java.util.Date

data class SponsoredSlot(
    val id: String,
    val advertiserName: String,
    val title: String,
    val ctaText: String = "Learn More",
    val ctaURL: String = "",
    val imageURL: String = "",
    val videoURL: String = "",          // reserved — never handed to a player
    val isActive: Boolean = false,
    val startAt: Date? = null,
    val endAt: Date? = null
) {
    /** Active flag AND now inside the optional [startAt, endAt] window. */
    val isLiveNow: Boolean
        get() {
            if (!isActive) return false
            val now = Date()
            startAt?.let { if (now.before(it)) return false }
            endAt?.let { if (now.after(it)) return false }
            return true
        }

    companion object {
        /** Lenient Firestore decode — returns null only when the doc is unusable. */
        fun fromFirestore(id: String, data: Map<String, Any?>): SponsoredSlot? {
            val imageURL = data["imageURL"] as? String ?: ""
            if (imageURL.isBlank()) return null   // no creative = nothing to render
            return SponsoredSlot(
                id = id,
                advertiserName = data["advertiserName"] as? String ?: "",
                title = data["title"] as? String ?: "",
                ctaText = (data["ctaText"] as? String)?.takeIf { it.isNotBlank() } ?: "Learn More",
                ctaURL = data["ctaURL"] as? String ?: "",
                imageURL = imageURL,
                videoURL = data["videoURL"] as? String ?: "",
                isActive = data["isActive"] as? Boolean ?: false,
                startAt = (data["startAt"] as? Timestamp)?.toDate(),
                endAt = (data["endAt"] as? Timestamp)?.toDate()
            )
        }
    }
}
