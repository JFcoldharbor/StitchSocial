package com.stitchsocial.club.landing

import com.stitchsocial.club.camera.RecordingContext

/**
 * A drop on a song with a giveaway running is also the entry, so the giveaway's
 * hashtag goes along with the post. The server decides whether it counts.
 * Applies only to a post on the armed thread, once.
 *
 * Layer 4: Core Services
 */
object PendingEntryHashtag {
    private var hashtag: String? = null
    private var threadID: String? = null

    fun arm(hashtag: String, threadID: String) {
        this.hashtag = hashtag
        this.threadID = threadID
    }

    fun clear() {
        hashtag = null
        threadID = null
    }

    /** `tags`, plus the armed hashtag if this post is on the armed thread. */
    fun applied(tags: List<String>, context: RecordingContext): List<String> {
        val tag = hashtag ?: return tags
        if ((context as? RecordingContext.StitchToThread)?.threadId != threadID) return tags
        clear()
        return if (tags.any { it.lowercase() == tag }) tags else tags + tag
    }
}
