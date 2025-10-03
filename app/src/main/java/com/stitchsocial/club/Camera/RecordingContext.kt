/*
 * RecordingContextManager.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Recording Context Management
 * BLUEPRINT: RecordingContext.swift
 * Dependencies: Foundation only
 * Integration: Works with CameraView.kt and ThreadComposer.kt
 */

package com.stitchsocial.club.camera

import androidx.compose.ui.graphics.Color

// MARK: - Recording Context Types

sealed class RecordingContext {
    object NewThread : RecordingContext()
    data class StitchToThread(val threadId: String, val threadInfo: ThreadInfo) : RecordingContext()
    data class ReplyToVideo(val videoId: String, val videoInfo: VideoInfo) : RecordingContext()
    data class ContinueThread(val threadId: String, val threadInfo: ThreadInfo) : RecordingContext()
}

// MARK: - Context Info Data Classes

data class ThreadInfo(
    val title: String,
    val creatorName: String,
    val creatorId: String,
    val participantCount: Int = 0,
    val stitchCount: Int = 0
)

data class VideoInfo(
    val title: String,
    val creatorName: String,
    val creatorId: String
)

// MARK: - Context Helper Extensions

fun RecordingContext.getTitle(): String {
    return when (this) {
        is RecordingContext.NewThread -> "Create Thread"
        is RecordingContext.StitchToThread -> "Create Stitch"
        is RecordingContext.ReplyToVideo -> "Create Reply"
        is RecordingContext.ContinueThread -> "Continue Thread"
    }
}

fun RecordingContext.getSubtitle(): String {
    return when (this) {
        is RecordingContext.NewThread -> "Share your moment"
        is RecordingContext.StitchToThread -> "Stitching to ${this.threadInfo.creatorName}'s thread"
        is RecordingContext.ReplyToVideo -> "Replying to ${this.videoInfo.creatorName}"
        is RecordingContext.ContinueThread -> "Continuing ${this.threadInfo.creatorName}'s thread"
    }
}

fun RecordingContext.getButtonText(): String {
    return when (this) {
        is RecordingContext.NewThread -> "Create Thread"
        is RecordingContext.StitchToThread -> "Create Stitch"
        is RecordingContext.ReplyToVideo -> "Create Reply" 
        is RecordingContext.ContinueThread -> "Continue Thread"
    }
}

fun RecordingContext.getBadgeText(): String {
    return when (this) {
        is RecordingContext.NewThread -> "Thread"
        is RecordingContext.StitchToThread -> "Stitch"
        is RecordingContext.ReplyToVideo -> "Reply"
        is RecordingContext.ContinueThread -> "Continue"
    }
}

fun RecordingContext.getTitlePlaceholder(): String {
    return when (this) {
        is RecordingContext.NewThread -> "What's your thread about?"
        is RecordingContext.StitchToThread -> "What's your stitch about?"
        is RecordingContext.ReplyToVideo -> "What's your reply?"
        is RecordingContext.ContinueThread -> "Continue the conversation..."
    }
}

fun RecordingContext.getDescriptionPlaceholder(): String {
    return when (this) {
        is RecordingContext.NewThread -> "Add more details..."
        is RecordingContext.StitchToThread -> "Add context to your stitch..."
        is RecordingContext.ReplyToVideo -> "Explain your response..."
        is RecordingContext.ContinueThread -> "Add to the conversation..."
    }
}

fun RecordingContext.getContextHashtags(): List<String> {
    return when (this) {
        is RecordingContext.NewThread -> listOf("thread")
        is RecordingContext.StitchToThread -> listOf("stitch", "thread")
        is RecordingContext.ReplyToVideo -> listOf("reply")
        is RecordingContext.ContinueThread -> listOf("thread", "continue")
    }
}

fun RecordingContext.getContextColor(): Color {
    return when (this) {
        is RecordingContext.NewThread -> Color(0xFFFF6B35)  // Primary orange
        is RecordingContext.StitchToThread -> Color(0xFFFFA500)  // Orange
        is RecordingContext.ReplyToVideo -> Color(0xFF007AFF)    // Blue
        is RecordingContext.ContinueThread -> Color(0xFF34C759) // Green
    }
}

// MARK: - Context Creation Helpers

object RecordingContextFactory {
    
    fun createNewThread(): RecordingContext {
        return RecordingContext.NewThread
    }
    
    fun createStitchToThread(threadId: String, creatorName: String, title: String): RecordingContext {
        val threadInfo = ThreadInfo(
            title = title,
            creatorName = creatorName,
            creatorId = "creator_$threadId",
            participantCount = 1,
            stitchCount = 0
        )
        return RecordingContext.StitchToThread(threadId, threadInfo)
    }
    
    fun createReplyToVideo(videoId: String, creatorName: String, title: String): RecordingContext {
        val videoInfo = VideoInfo(
            title = title,
            creatorName = creatorName,
            creatorId = "creator_$videoId"
        )
        return RecordingContext.ReplyToVideo(videoId, videoInfo)
    }
    
    fun createContinueThread(threadId: String, creatorName: String, title: String): RecordingContext {
        val threadInfo = ThreadInfo(
            title = title,
            creatorName = creatorName,
            creatorId = "creator_$threadId",
            participantCount = 2,
            stitchCount = 1
        )
        return RecordingContext.ContinueThread(threadId, threadInfo)
    }
}