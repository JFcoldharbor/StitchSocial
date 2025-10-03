/*
 * UploadResult.kt - FIXED PARAMETER NAMES
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Services - Upload processing and coordination
 * Dependencies: VideoServiceImpl, AIVideoAnalyzer (fixed)
 * Features: Video upload workflow, AI integration
 *
 * FIXES:
 * ✅ Fixed parameter names from 'context' to 'recordingContext'
 * ✅ All when statements now use correct parameter name
 * ✅ Consistent parameter naming throughout
 */

package com.stitchsocial.club.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import com.stitchsocial.club.camera.RecordingContext
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ContentType
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.Temperature
/**
 * Upload processing coordinator - FIXED all missing references
 */
class UploadCoordinator(
    private val videoService: VideoServiceImpl,
    private val aiAnalyzer: AIVideoAnalyzer
) {

    /**
     * Process video upload with AI analysis - FIXED all compilation errors
     */
    suspend fun processVideoUpload(
        videoPath: String,
        recordingContext: RecordingContext,
        userTier: UserTier = UserTier.VETERAN, // Default for testing
        manualTitle: String? = null,
        manualDescription: String? = null,
        manualHashtags: List<String>? = null
    ): VideoUploadResult {
        return withContext(Dispatchers.IO) {
            try {
                println("UPLOAD: Processing video upload - $videoPath")

                // Step 1: Determine if AI should be used - FIXED: Use direct tier check
                val shouldUseAI = shouldUseAIForTier(userTier) && aiAnalyzer.isAIAvailable()

                // Step 2: Get video analysis - FIXED: Use correct method names
                val analysisResult = if (shouldUseAI && manualTitle.isNullOrBlank()) {
                    println("UPLOAD: Using AI analysis")
                    aiAnalyzer.analyzeVideoSimple(recordingContext, "")
                } else {
                    println("UPLOAD: Using manual/fallback content")
                    aiAnalyzer.generateFallbackContent(recordingContext)
                }

                // Step 3: Merge manual overrides with AI results - FIXED: Proper null handling
                val finalTitle = manualTitle?.takeIf { it.isNotBlank() } ?: analysisResult?.title ?: "New Video"
                val finalDescription = manualDescription?.takeIf { it.isNotBlank() } ?: analysisResult?.description ?: ""
                val finalHashtags = manualHashtags?.takeIf { it.isNotEmpty() } ?: analysisResult?.hashtags ?: emptyList()

                // Step 4: Create video metadata - FIXED: All property assignments
                val videoMetadata = CoreVideoMetadata(
                    id = "video_${System.currentTimeMillis()}",
                    title = finalTitle,
                    videoURL = videoPath,
                    thumbnailURL = "",
                    creatorID = "current_user", // TODO: Get real user ID
                    creatorName = "Current User",
                    createdAt = Date(),
                    threadID = determineThreadID(recordingContext),
                    replyToVideoID = determineReplyToVideoID(recordingContext),
                    conversationDepth = determineConversationDepth(recordingContext),
                    viewCount = 0,
                    hypeCount = 0,
                    coolCount = 0,
                    replyCount = 0,
                    shareCount = 0,
                    lastEngagementAt = null,
                    duration = 30.0,
                    aspectRatio = 0.5625,
                    fileSize = 5000000,
                    contentType = determineContentType(recordingContext),
                    temperature = Temperature.WARM,
                    qualityScore = 50,
                    engagementRatio = 0.0,
                    velocityScore = 0.0,
                    trendingScore = 0.0,
                    discoverabilityScore = 50.0,
                    isPromoted = false,
                    isProcessing = false,
                    isDeleted = false
                )

                // Step 5: Return success result
                VideoUploadResult(
                    success = true,
                    videoMetadata = videoMetadata,
                    usedAI = shouldUseAI,
                    analysisResult = analysisResult,
                    error = null
                )

            } catch (e: Exception) {
                println("UPLOAD: Error processing video - ${e.message}")
                VideoUploadResult(
                    success = false,
                    videoMetadata = null,
                    usedAI = false,
                    analysisResult = null,
                    error = e.message
                )
            }
        }
    }

    // FIXED: Helper methods for determining video properties - ALL PARAMETER NAMES FIXED

    private fun shouldUseAIForTier(userTier: UserTier): Boolean {
        return when (userTier) {
            UserTier.ROOKIE, UserTier.RISING -> false
            else -> true
        }
    }

    // ✅ FIXED: Parameter name changed from 'context' to 'recordingContext'
    private fun determineThreadID(recordingContext: RecordingContext): String? {
        return when (recordingContext) {
            is RecordingContext.NewThread -> null
            is RecordingContext.StitchToThread -> recordingContext.threadId
            is RecordingContext.ReplyToVideo -> null
            is RecordingContext.ContinueThread -> recordingContext.threadId
        }
    }

    // ✅ FIXED: Parameter name changed from 'context' to 'recordingContext'
    private fun determineReplyToVideoID(recordingContext: RecordingContext): String? {
        return when (recordingContext) {
            is RecordingContext.ReplyToVideo -> recordingContext.videoId
            else -> null
        }
    }

    // ✅ FIXED: Parameter name changed from 'context' to 'recordingContext'
    private fun determineConversationDepth(recordingContext: RecordingContext): Int {
        return when (recordingContext) {
            is RecordingContext.NewThread -> 0
            else -> 1
        }
    }

    // ✅ FIXED: Parameter name changed from 'context' to 'recordingContext'
    private fun determineContentType(recordingContext: RecordingContext): ContentType {
        return when (recordingContext) {
            is RecordingContext.NewThread -> ContentType.THREAD
            else -> ContentType.CHILD
        }
    }
}

/**
 * Video upload result data class - FIXED: Clear structure
 */
data class VideoUploadResult(
    val success: Boolean,
    val videoMetadata: CoreVideoMetadata?,
    val usedAI: Boolean,
    val analysisResult: VideoAnalysisResult?,
    val error: String?
) {
    companion object {
        fun success(
            videoMetadata: CoreVideoMetadata,
            usedAI: Boolean = false,
            analysisResult: VideoAnalysisResult? = null
        ) = VideoUploadResult(
            success = true,
            videoMetadata = videoMetadata,
            usedAI = usedAI,
            analysisResult = analysisResult,
            error = null
        )

        fun failure(error: String) = VideoUploadResult(
            success = false,
            videoMetadata = null,
            usedAI = false,
            analysisResult = null,
            error = error
        )
    }
}