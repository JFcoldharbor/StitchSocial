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
 * A shared resolver means a new type is answered in one place, and the answer is
 * the same however the user arrived.
 */
sealed class NotificationDestination {

    /** A thread, optionally scrolled to a specific reply. */
    data class Thread(val threadID: String, val targetVideoID: String?) : NotificationDestination()

    data class Profile(val userID: String) : NotificationDestination()

    /** A live stream. streamID may be null — resolve the community's active one. */
    data class Live(val communityID: String, val streamID: String?) : NotificationDestination()

    data class Event(val eventID: String) : NotificationDestination()

    data class Community(val communityID: String) : NotificationDestination()

    /** Nothing specific to open — show the inbox rather than doing nothing. */
    object Inbox : NotificationDestination()

    companion object {

        /**
         * Resolve a destination from whatever the notification carries.
         *
         * PAYLOAD FIRST, type second. Types arrive inconsistently — the backend
         * writes hype and cool as "engagement", stitches as "stitch", and event
         * invites as "system" with the real kind in the payload — so trusting
         * the type alone is how event invites and go-lives ended up on profiles.
         *
         * Order is deliberate: the most specific signal wins, and the sender
         * fallback is LAST because almost every notification carries a senderID.
         * Anything placed after it is unreachable.
         */
        fun resolve(rawType: String?, data: Map<String, Any?>): NotificationDestination {
            fun str(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { k -> (data[k] as? String)?.takeIf { it.isNotBlank() } }

            val type = rawType?.lowercase()?.trim()

            // 1. Event invites: written as SYSTEM with the kind in the payload,
            //    so the eventID is the only dependable signal.
            str("eventID", "eventId", "event_id")?.let { return Event(it) }

            // 2. Live. A streamID in the payload means live regardless of type;
            //    the community falls back to the sender, who IS its owner.
            val streamID = str("streamID", "streamId", "stream_id")
            if (type == "go_live" || streamID != null) {
                val community = str("communityID", "communityId", "community_id")
                    ?: str("senderID", "senderId", "userId", "user_id")
                if (community != null) return Live(community, streamID)
            }

            // 3. Anything carrying a video goes to that thread.
            val videoID = str("videoID", "videoId", "video_id")
            if (videoID != null) {
                val threadID = str("threadID", "threadId", "thread_id") ?: videoID
                return Thread(threadID, videoID)
            }

            // 4. A community post with no video.
            if (type == "community_post" || type == "community_xp") {
                str("communityID", "communityId")?.let { return Community(it) }
            }

            // 5. Sender fallback — LAST, because nearly everything has one.
            str("senderID", "senderId", "userId", "user_id")?.let { return Profile(it) }

            return Inbox
        }
    }
}
