package com.stitchsocial.club.services

import com.stitchsocial.club.foundation.CoreVideoMetadata
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * One-shot thread refreshes after the viewer's OWN reply/stitch publishes.
 *
 * There are deliberately no snapshot listeners on threads, replies or counts.
 * Instead, the moment VideoCoordinator.completeVideoCreation writes a reply,
 * it calls [publishOwnReply]; every watching surface that is showing that
 * thread (collection player, Discovery, Home, ThreadView, fullscreen players,
 * the overlay's counts) collects [events] and re-reads the thread ONCE.
 *
 * The new reply usually starts as publicVisibility "pending" (moderation),
 * which every public loader filters out. The author still needs to see it,
 * so this also keeps the author's own recent replies in memory and the
 * thread loaders merge them back in via [mergeOwnReplies]. Only the author's
 * client ever holds these, so nobody else sees a pending reply. An entry is
 * dropped as soon as a loader reads the doc back with a settled state
 * ([reconcile]) — public (the loader shows it on its own), hidden, or deleted.
 *
 * Memory only, no Firebase: process death forgets it, and after that the
 * loaders' own "author sees their pending reply" rule takes over.
 */
object ThreadRefreshBus {

    /** The viewer's own reply finished publishing. */
    data class OwnReplyPublished(
        val video: CoreVideoMetadata,
        /** Root thread id (never null for a reply). */
        val threadID: String,
        /** The video it answers directly: replyToVideoID, else the thread head. */
        val parentID: String
    ) {
        val videoID: String get() = video.id

        /** True if a screen showing any of [ids] (thread, parent or video) should re-read. */
        fun touches(vararg ids: String?): Boolean =
            ids.any { it != null && (it == threadID || it == parentID || it == video.id) }
    }

    private val _events = MutableSharedFlow<OwnReplyPublished>(extraBufferCapacity = 16)
    val events: SharedFlow<OwnReplyPublished> = _events.asSharedFlow()

    private val ownRecent = ConcurrentHashMap<String, CoreVideoMetadata>()

    /** Called once, at the success point of the author's own reply publish. */
    fun publishOwnReply(video: CoreVideoMetadata) {
        if (video.conversationDepth <= 0) return
        val threadID = video.threadID ?: return
        val parentID = video.replyToVideoID ?: threadID
        ownRecent[video.id] = video
        _events.tryEmit(OwnReplyPublished(video, threadID, parentID))
    }

    /**
     * Loaders report what the server returned for any doc. A settled state
     * (anything but "pending", or deleted) ends the in-memory override.
     */
    fun reconcile(videoID: String, publicVisibility: String?, isDeleted: Boolean) {
        if (!ownRecent.containsKey(videoID)) return
        if (isDeleted || (publicVisibility ?: "public") != "pending") ownRecent.remove(videoID)
    }

    fun reconcile(videos: Collection<CoreVideoMetadata>) {
        if (ownRecent.isEmpty()) return
        videos.forEach { reconcile(it.id, it.publicVisibility, it.isDeleted) }
    }

    /**
     * [children] plus the author's own pending replies that belong to it but
     * aren't in it yet. [threadID] matches the whole thread (depth > 0);
     * [replyToID] matches only direct replies to that video.
     */
    fun mergeOwnReplies(
        children: List<CoreVideoMetadata>,
        threadID: String? = null,
        replyToID: String? = null,
        viewerID: String?
    ): List<CoreVideoMetadata> {
        if (ownRecent.isEmpty() || viewerID.isNullOrEmpty()) return children
        val have = children.mapTo(HashSet()) { it.id }
        val extra = ownRecent.values.filter { v ->
            v.id !in have && v.creatorID == viewerID && v.conversationDepth > 0 &&
                ((threadID != null && v.threadID == threadID) ||
                    (replyToID != null && v.replyToVideoID == replyToID))
        }.sortedBy { it.createdAt }
        return if (extra.isEmpty()) children else children + extra
    }

    /** Sign-out / account switch: never carry one account's pending replies into another. */
    fun clear() = ownRecent.clear()
}
