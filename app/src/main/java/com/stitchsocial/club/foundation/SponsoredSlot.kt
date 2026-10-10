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
 *   - imageURL is a 9:16 creative; a non-empty videoURL makes it a video ad and
 *     the image becomes its poster. Created from the web admin's Sponsored page.
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
    val videoURL: String = "",          // set = video ad, played inside SponsoredSwipeCard
    val isActive: Boolean = false,
    val startAt: Date? = null,
    val endAt: Date? = null,
    /// Landings addendum 1: "landing_claim" when the promotion links the
    /// brand's Landing. The CTA then claims the welcome perk in-app; ctaURL
    /// (stitchsocial.me/l/<slug>) is only the fallback for old builds.
    val ctaKind: String? = null,
    val landingID: String? = null,
    val landingSlug: String? = null
) {
    val hasVideo: Boolean get() = videoURL.isNotBlank()

    /** The CTA claims a Landing's welcome perk in-app rather than opening a URL. */
    val isLandingClaim: Boolean get() = ctaKind == "landing_claim" && !landingSlug.isNullOrBlank()

    /** The creative as something VideoPlayerComposable can play. Never a feed item. */
    fun playerMetadata(): CoreVideoMetadata = CoreVideoMetadata(
        id = "sponsored_$id",
        title = title,
        description = "",
        videoURL = videoURL,
        thumbnailURL = imageURL,
        creatorID = "",
        creatorName = advertiserName,
        createdAt = Date(),
        threadID = null,
        replyToVideoID = null,
        conversationDepth = 0,
        viewCount = 0, hypeCount = 0, coolCount = 0,
        replyCount = 0, shareCount = 0,
        temperature = Temperature.WARM,
        qualityScore = 75,
        engagementRatio = 0.5,
        velocityScore = 0.0, trendingScore = 0.0,
        duration = 0.0, aspectRatio = 9.0 / 16.0, fileSize = 0L,
        discoverabilityScore = 0.0,
        isPromoted = true,
        lastEngagementAt = null,
        collectionID = null,
        segmentNumber = null, segmentTitle = null,
        isCollectionSegment = false,
        contentType = ContentType.THREAD,
        isProcessing = false, isDeleted = false,
        recordingSource = ""
    )

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
                endAt = (data["endAt"] as? Timestamp)?.toDate(),
                ctaKind = (data["ctaKind"] as? String)?.takeIf { it.isNotBlank() },
                landingID = (data["landingID"] as? String)?.takeIf { it.isNotBlank() },
                landingSlug = (data["landingSlug"] as? String)?.takeIf { it.isNotBlank() }
            )
        }
    }
}
