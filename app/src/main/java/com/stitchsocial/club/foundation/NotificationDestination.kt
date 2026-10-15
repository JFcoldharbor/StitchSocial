package com.stitchsocial.club.foundation

/**
 * Where a notification goes when it's tapped.
 *
 * ONE model, resolved ONCE, used by every entry point.
 *
 * Routing used to be a `when` over raw type strings, duplicated across
 * MainActivity's push-intent handler and NotificationViewModel's in-app tap
 * handler. The two drifted, and the drift is what shipped bugs: go-live opened
 * the creator's PROFILE from the in-app list (it has a senderID and no videoID,
 * so it fell through to the sender fallback) while the push path opened the
 * community. Same notification, two destinations, neither the stream.
 *
 * Both entry points now hand the raw type + payload to [NotificationRouter],
 * and MainScreen resolves it here — so a new type is answered in one place, and
 * the answer is the same however the user arrived.
 */
sealed class NotificationDestination {

    /** The fullscreen player on this exact video. */
    data class Video(val videoID: String, val threadID: String?) : NotificationDestination()

    data class Profile(val userID: String) : NotificationDestination()

    /** Your own profile / progression tab (tier, ambassador, streak). */
    object OwnProfile : NotificationDestination()

    /** A live stream. streamID may be null — resolve the community's active one. */
    data class Live(val communityID: String, val streamID: String?) : NotificationDestination()

    data class Event(val eventID: String) : NotificationDestination()

    /**
     * A restaurant's Landing (stitchsocial.me/l/{slug}?t={spot}). `spot` is
     * the table or counter the QR was printed for, carried onto the post.
     */
    data class Landing(
        val slug: String,
        val spot: String?,
        /** landing_claimWelcome's `source` (LandingSource): a link with `t` is a scan. */
        val source: String = if (spot.isNullOrBlank()) "link" else "scan",
        /** Claim the welcome perk once on open (a sponsored card's CTA). */
        val autoClaim: Boolean = false
    ) : NotificationDestination()

    data class Community(val communityID: String) : NotificationDestination()

    /** One creator campaign's detail screen. Falls back to [Campaigns] if it can't be read. */
    data class Campaign(val campaignID: String) : NotificationDestination()

    /** Brand marketplace (creator campaigns). */
    object Campaigns : NotificationDestination()

    object AdOpportunities : NotificationDestination()

    object Wallet : NotificationDestination()

    /** Nudges and recaps: just open the app. */
    object Home : NotificationDestination()

    /** Your own birthday card — the Birthday sheet. */
    object Birthday : NotificationDestination()

    /** Nothing specific to open — show the inbox rather than doing nothing. */
    object Inbox : NotificationDestination()

    companion object {

        private val CAMPAIGN_TYPES = setOf(
            "creatorcampaignmatch", "campaignapplication", "campaigndecision",
            "campaigndeliverable", "campaignpayout", "campaignrevision"
        )
        private val OWN_PROFILE_TYPES = setOf(
            "tier_upgrade", "tier_upgraded", "ambassador_locked", "ambassador_reverted", "streak"
        )
        private val PROFILE_TYPES = setOf("follow", "new_follower", "subscription", "user")
        private val HOME_TYPES = setOf(
            "inactive_nudge", "weekly_recap", "reengagement",
            "reengagement_stitches", "reengagement_milestone", "reengagement_followers"
        )

        /**
         * Resolve a destination from whatever the notification carries.
         *
         * PAYLOAD FIRST, type second. Types arrive inconsistently — the backend
         * writes hype and cool as "engagement", stitches as "stitch", and event
         * invites as "system" with the real kind in the payload — so trusting
         * the type alone is how event invites and go-lives ended up on profiles.
         *
         * Order is deliberate: the most specific signal wins. There is no
         * blanket sender fallback any more — almost everything carries a
         * senderID, so "has a sender" opened a profile for notifications that
         * were about something else entirely. Unknown goes to the inbox.
         */
        fun resolve(rawType: String?, data: Map<String, Any?>): NotificationDestination {
            fun str(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { k -> data[k]?.toString()?.takeIf { it.isNotBlank() } }

            val type = rawType?.lowercase()?.trim()
            val kind = str("kind")?.lowercase()

            // 0. Birthday cards (BIRTHDAY_CONTRACT). By kind, and first: the
            //    day-of push carries a videoID and a senderID, and the rules
            //    below would each take it somewhere plausible and wrong.
            //    - birthday_self_week / _dayBefore / _day_of → your own sheet.
            //    - birthday_day_before (a follower's) → the birthday person.
            //    - birthday_day_of (a follower's) → their card's thread.
            if (kind != null && kind.startsWith("birthday_self")) return Birthday
            if (kind == "birthday_day_before") {
                return str("userID", "userId", "user_id")?.let { Profile(it) }
                    ?: str("senderID", "senderId")?.let { Profile(it) }
                    ?: Inbox
            }
            if (kind == "birthday_day_of") {
                str("videoID", "videoId", "video_id")?.let { return Video(it, str("threadID", "threadId", "thread_id")) }
                str("threadID", "threadId", "thread_id")?.let { return Video(it, it) }
                return str("userID", "userId", "user_id")?.let { Profile(it) }
                    ?: str("senderID", "senderId")?.let { Profile(it) }
                    ?: Inbox
            }

            // 1. Moderation and broken uploads carry the removed video's id —
            //    playing it is the one thing not to do. The notice is the inbox.
            if (kind == "broken_upload" ||
                (type == "system" && (data.containsKey("evidence") || data.containsKey("reason")))
            ) return Inbox

            // 2. Landings carry only the slug (a link, or a Landing nudge).
            //    In-app entry points add landingSource / landingAutoClaim;
            //    a bare link derives its source from whether it carries `t`.
            str("landingSlug")?.let { slug ->
                val spot = str("landingSpot")
                return Landing(
                    slug = slug,
                    spot = spot,
                    source = str("landingSource") ?: if (spot == null) "link" else "scan",
                    autoClaim = str("landingAutoClaim") == "true"
                )
            }

            // 3. Events: invites are written as SYSTEM (notificationType
            //    "event_invite", or "eventInvite" from older iOS builds), so the
            //    eventID is the only dependable signal. Reminders/updates too.
            str("eventID", "eventId", "event_id")?.let { return Event(it) }

            // 4. Live. A streamID in the payload means live regardless of type;
            //    the community falls back to the creator, then the sender, who
            //    IS its owner.
            val streamID = str("streamID", "streamId", "stream_id")
            if (type == "go_live" || streamID != null) {
                val community = str("communityID", "communityId", "community_id")
                    ?: str("creatorID", "creatorId")
                    ?: str("senderID", "senderId")
                if (community != null) return Live(community, streamID)
            }

            // 5. Collab invites carry the video, but the decision is what the
            //    notification is asking for, and Accept/Decline live on the
            //    inbox row.
            if (type == "collabinvite") return Inbox

            // 6. Money and progression have their own screens, and may carry a
            //    videoID (a deliverable) that isn't the point of the tap.
            if (type in CAMPAIGN_TYPES) {
                return str("campaignID", "campaignId", "campaign_id")?.let { Campaign(it) } ?: Campaigns
            }
            if (type == "adopportunity") return AdOpportunities
            if (type == "stripeconnectactive") return Wallet
            if (type in OWN_PROFILE_TYPES) return OwnProfile

            // 7. Anything carrying a video plays that video.
            str("videoID", "videoId", "video_id")?.let { videoID ->
                return Video(videoID, str("threadID", "threadId", "thread_id"))
            }

            // 8. A community post with no video.
            if (type == "community_post" || type == "community_xp") {
                str("communityID", "communityId", "community_id")?.let { return Community(it) }
            }

            // 9. Notifications ABOUT a person: the payload's user (a birthday
            //    milestone names the birthday user), else the sender.
            val isFollowEngagement = type == "engagement" && str("engagementType")?.lowercase() == "follow"
            val isBirthday = type == "milestone" && kind == "birthday"
            val isProfileLink = type == NotificationRouter.TYPE_SHARED_LINK
            if (type in PROFILE_TYPES || isFollowEngagement || isBirthday || isProfileLink) {
                str("userID", "user_id", "userId", "senderID", "senderId")?.let { return Profile(it) }
            }

            // 10. Nudges and recaps.
            if (type in HOME_TYPES) return Home

            return Inbox
        }
    }
}
