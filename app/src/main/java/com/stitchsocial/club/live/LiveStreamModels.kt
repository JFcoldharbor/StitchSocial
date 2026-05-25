package com.stitchsocial.club.live

import com.google.firebase.Timestamp

// ─────────────────────────────────────────────────────────────────────────────
// Data models for the live streaming module. Mirrors the iOS structures in
// LiveStream.swift so Firestore docs decode identically on both platforms.
//
// Firestore paths (same on both platforms):
//   communities/{creatorID}/streams/{streamID}            — stream doc
//   communities/{creatorID}/streams/{streamID}/chat       — chat messages
//   communities/{creatorID}/streams/{streamID}/videoComments — queue
//   communities/{creatorID}/streams/{streamID}/viewers    — presence
//
// Storage paths:
//   stream-clips/{creatorID}/{streamID}/{commentID}.mp4
//   stream-clips/{creatorID}/{streamID}/{commentID}_thumb.jpg
// ─────────────────────────────────────────────────────────────────────────────

enum class StreamStatus(val raw: String) {
    LIVE("live"),
    ENDED("ended"),
    CONNECTING("connecting");

    companion object {
        fun fromRaw(raw: String?): StreamStatus =
            entries.firstOrNull { it.raw == raw } ?: ENDED
    }
}

/// Duration tier mirrors iOS — each tier has a max duration in seconds. Spark
/// is the entry tier (30 min); higher tiers unlock as the creator completes
/// streams. Viewer doesn't enforce this — only the creator's start flow does.
enum class StreamDurationTier(val raw: String, val durationSeconds: Int, val displayName: String, val emoji: String) {
    SPARK("spark", 1800, "Spark", "✨"),
    FLAME("flame", 3600, "Flame", "🔥"),
    BLAZE("blaze", 7200, "Blaze", "💥"),
    INFERNO("inferno", 10_800, "Inferno", "🌋"),
    SUPERNOVA("supernova", 14_400, "Supernova", "🌟"),
    MARATHON("marathon", 21_600, "Marathon", "🏃"),
    LEGENDARY("legendary", 28_800, "Legendary", "👑");

    companion object {
        fun fromRaw(raw: String?): StreamDurationTier =
            entries.firstOrNull { it.raw == raw } ?: SPARK
    }
}

/// Decoded view of the `communities/{creatorID}/streams/{streamID}` doc.
/// `elapsedSeconds` is computed locally from `startedAt` so both creator and
/// viewer can render a timer without polling Firestore.
data class LiveStream(
    val id: String,
    val creatorID: String,
    val communityID: String,
    val channelName: String,
    val status: StreamStatus,
    val durationTier: StreamDurationTier,
    val startedAt: Timestamp?,
    val endedAt: Timestamp?,
    val viewerCount: Int,
    val hypeCount: Int,
    val totalCoinsSpent: Int,
    val creatorDisplayName: String,
    val creatorUsername: String,
) {
    val maxDurationSeconds: Int get() = durationTier.durationSeconds

    val elapsedSeconds: Int
        get() {
            val start = startedAt?.toDate()?.time ?: return 0
            return ((System.currentTimeMillis() - start) / 1000).toInt().coerceAtLeast(0)
        }

    val durationProgress: Double
        get() = elapsedSeconds.toDouble() / maxDurationSeconds.toDouble()

    companion object {
        fun fromDoc(id: String, data: Map<String, Any?>): LiveStream? {
            val creatorID = data["creatorID"] as? String ?: return null
            val communityID = data["communityID"] as? String ?: creatorID
            val channelName = data["channelName"] as? String ?: id

            return LiveStream(
                id = id,
                creatorID = creatorID,
                communityID = communityID,
                channelName = channelName,
                status = StreamStatus.fromRaw(data["status"] as? String),
                durationTier = StreamDurationTier.fromRaw(data["durationTier"] as? String),
                startedAt = data["startedAt"] as? Timestamp,
                endedAt = data["endedAt"] as? Timestamp,
                viewerCount = (data["viewerCount"] as? Number)?.toInt() ?: 0,
                hypeCount = (data["hypeCount"] as? Number)?.toInt() ?: 0,
                totalCoinsSpent = (data["totalCoinsSpent"] as? Number)?.toInt() ?: 0,
                creatorDisplayName = data["creatorDisplayName"] as? String ?: "",
                creatorUsername = data["creatorUsername"] as? String ?: "",
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PiP mirror — read-only snapshot of the creator's currently-playing video
// comment, written onto the stream doc by `StreamQueueService.syncPipToStream`
// (iOS side, and eventually the Android creator service). Viewer parses this
// out of the same stream doc on every snapshot.
// ─────────────────────────────────────────────────────────────────────────────

data class PipMirrorState(
    val commentID: String,
    val videoURL: String,
    val authorUsername: String,
    val authorLevel: Int,
    val durationSeconds: Int,
    val playbackToken: String,
) {
    companion object {
        fun fromDoc(data: Map<String, Any?>): PipMirrorState? {
            val commentID = data["activePipCommentID"] as? String ?: return null
            val videoURL = data["activePipVideoURL"] as? String ?: return null
            val token = data["pipPlaybackToken"] as? String ?: return null
            if (videoURL.isEmpty()) return null
            return PipMirrorState(
                commentID = commentID,
                videoURL = videoURL,
                authorUsername = data["activePipAuthorUsername"] as? String ?: "",
                authorLevel = (data["activePipAuthorLevel"] as? Number)?.toInt() ?: 0,
                durationSeconds = (data["activePipDurationSeconds"] as? Number)?.toInt() ?: 0,
                playbackToken = token,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stream chat message — flat doc in the `chat` subcollection. Mirrors iOS
// `StreamChatMessage`. Creator messages get a special render (gold username,
// crown emoji) on both platforms.
// ─────────────────────────────────────────────────────────────────────────────

data class StreamChatMessage(
    val id: String,
    val authorID: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val authorLevel: Int,
    val isCreator: Boolean,
    val body: String,
    val createdAt: Timestamp?,
) {
    companion object {
        fun fromDoc(id: String, data: Map<String, Any?>): StreamChatMessage? {
            val authorID = data["authorID"] as? String ?: return null
            val body = data["body"] as? String ?: return null
            return StreamChatMessage(
                id = id,
                authorID = authorID,
                authorUsername = data["authorUsername"] as? String ?: "",
                authorDisplayName = data["authorDisplayName"] as? String ?: "",
                authorLevel = (data["authorLevel"] as? Number)?.toInt() ?: 0,
                isCreator = (data["isCreator"] as? Boolean) ?: false,
                body = body,
                createdAt = data["createdAt"] as? Timestamp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Video comment — queued clip from a viewer. Same schema as iOS VideoComment.
// Viewer side uses this for submission; creator side reads pending entries
// from the queue subcollection and writes accept/reject status updates.
// ─────────────────────────────────────────────────────────────────────────────

enum class VideoCommentStatus(val raw: String) {
    PENDING("pending"),
    ACCEPTED("accepted"),
    DISPLAYED("displayed"),
    REJECTED("rejected");

    companion object {
        fun fromRaw(raw: String?): VideoCommentStatus =
            entries.firstOrNull { it.raw == raw } ?: PENDING
    }
}

data class VideoComment(
    val id: String,
    val streamID: String,
    val communityID: String,
    val authorID: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val authorLevel: Int,
    val videoURL: String,
    val thumbnailURL: String?,
    val durationSeconds: Int,
    val caption: String,
    val isPriority: Boolean,
    val priorityCoinsCost: Int,
    val status: VideoCommentStatus,
    val submittedAt: Timestamp?,
    val reviewedAt: Timestamp?,
) {
    companion object {
        const val MINIMUM_LEVEL = 5
        fun maxClipSeconds(forLevel: Int): Int = when {
            forLevel >= 50 -> 60
            forLevel >= 20 -> 30
            else -> 15
        }

        fun fromDoc(id: String, data: Map<String, Any?>): VideoComment? {
            val authorID = data["authorID"] as? String ?: return null
            val videoURL = data["videoURL"] as? String ?: return null
            return VideoComment(
                id = id,
                streamID = data["streamID"] as? String ?: "",
                communityID = data["communityID"] as? String ?: "",
                authorID = authorID,
                authorUsername = data["authorUsername"] as? String ?: "",
                authorDisplayName = data["authorDisplayName"] as? String ?: "",
                authorLevel = (data["authorLevel"] as? Number)?.toInt() ?: 0,
                videoURL = videoURL,
                thumbnailURL = data["thumbnailURL"] as? String,
                durationSeconds = (data["durationSeconds"] as? Number)?.toInt() ?: 0,
                caption = data["caption"] as? String ?: "",
                isPriority = (data["isPriority"] as? Boolean) ?: false,
                priorityCoinsCost = (data["priorityCoinsCost"] as? Number)?.toInt() ?: 0,
                status = VideoCommentStatus.fromRaw(data["status"] as? String),
                submittedAt = data["submittedAt"] as? Timestamp,
                reviewedAt = data["reviewedAt"] as? Timestamp,
            )
        }
    }
}
