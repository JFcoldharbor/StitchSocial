package com.stitchsocial.club.engagement

import com.stitchsocial.club.foundation.UserTier
import kotlin.math.max
import kotlin.math.min

/**
 * ContentAnalyzer.kt - FIXED: Added AMBASSADOR tier
 */

data class VideoAnalysisResult(
    val title: String,
    val description: String,
    val hashtags: List<String>,
    val confidence: Double = 0.0,
    val analysisType: String = "manual",
    val processingTime: Long = 0L
)

data class ContentValidationResult(
    val isValid: Boolean,
    val title: ContentValidation,
    val description: ContentValidation,
    val hashtags: ContentValidation,
    val overallScore: Double
)

data class ContentValidation(
    val isValid: Boolean,
    val score: Double,
    val issues: List<String> = emptyList()
)

data class AIAnalysisMetrics(
    val processingTimeMs: Long,
    val success: Boolean,
    val transcriptLength: Int,
    val generatedTitleLength: Int,
    val generatedDescriptionLength: Int,
    val hashtagCount: Int,
    val errorType: String? = null
)

data class ContentGenerationRequest(
    val transcript: String,
    val userTier: UserTier,
    val contentType: String,
    val maxTitleLength: Int = 100,
    val maxDescriptionLength: Int = 300,
    val maxHashtags: Int = 10
)

object ContentAnalyzer {

    fun validateAnalysisResult(result: VideoAnalysisResult): ContentValidationResult {
        val titleValidation = validateTitle(result.title)
        val descriptionValidation = validateDescription(result.description)
        val hashtagValidation = validateHashtags(result.hashtags)
        val overallScore = (titleValidation.score + descriptionValidation.score + hashtagValidation.score) / 3.0
        val isValid = titleValidation.isValid && descriptionValidation.isValid && hashtagValidation.isValid

        return ContentValidationResult(
            isValid = isValid,
            title = titleValidation,
            description = descriptionValidation,
            hashtags = hashtagValidation,
            overallScore = overallScore
        )
    }

    fun validateTitle(title: String): ContentValidation {
        val issues = mutableListOf<String>()
        var score = 100.0

        when {
            title.isEmpty() -> {
                issues.add("Title cannot be empty")
                score -= 100.0
            }
            title.length < 5 -> {
                issues.add("Title too short (minimum 5 characters)")
                score -= 50.0
            }
            title.length > 100 -> {
                issues.add("Title too long (maximum 100 characters)")
                score -= 30.0
            }
        }

        if (title.count { it == '!' } > 3) {
            issues.add("Too many exclamation marks")
            score -= 20.0
        }

        if (title.uppercase() == title && title.length > 10) {
            issues.add("Avoid all caps")
            score -= 15.0
        }

        if (title.contains(Regex("[0-9]{4,}"))) {
            issues.add("Avoid long number sequences")
            score -= 10.0
        }

        val engagementWords = listOf("how", "why", "what", "when", "where", "who", "best", "worst", "amazing", "incredible")
        if (engagementWords.any { title.lowercase().contains(it) }) {
            score += 10.0
        }

        return ContentValidation(
            isValid = issues.isEmpty(),
            score = max(0.0, min(100.0, score)),
            issues = issues
        )
    }

    fun validateDescription(description: String): ContentValidation {
        val issues = mutableListOf<String>()
        var score = 100.0

        when {
            description.isEmpty() -> {
                issues.add("Description cannot be empty")
                score -= 100.0
            }
            description.length < 10 -> {
                issues.add("Description too short (minimum 10 characters)")
                score -= 40.0
            }
            description.length > 300 -> {
                issues.add("Description too long (maximum 300 characters)")
                score -= 20.0
            }
        }

        val sentences = description.split(Regex("[.!?]")).filter { it.trim().isNotEmpty() }
        if (sentences.size < 2 && description.length > 50) {
            issues.add("Consider adding more sentences for better readability")
            score -= 10.0
        }

        val words = description.lowercase().split(Regex("\\s+"))
        val uniqueWords = words.toSet()
        if (words.size > 10 && uniqueWords.size.toDouble() / words.size < 0.6) {
            issues.add("Too much repetition detected")
            score -= 25.0
        }

        return ContentValidation(
            isValid = issues.isEmpty(),
            score = max(0.0, min(100.0, score)),
            issues = issues
        )
    }

    fun validateHashtags(hashtags: List<String>): ContentValidation {
        val issues = mutableListOf<String>()
        var score = 100.0

        when {
            hashtags.isEmpty() -> {
                issues.add("At least one hashtag required")
                score -= 50.0
            }
            hashtags.size > 10 -> {
                issues.add("Too many hashtags (maximum 10)")
                score -= 20.0
            }
        }

        hashtags.forEach { hashtag ->
            val cleanTag = hashtag.removePrefix("#").trim()

            when {
                cleanTag.isEmpty() -> {
                    issues.add("Empty hashtag found")
                    score -= 15.0
                }
                cleanTag.length > 30 -> {
                    issues.add("Hashtag too long: #$cleanTag")
                    score -= 10.0
                }
                cleanTag.length < 2 -> {
                    issues.add("Hashtag too short: #$cleanTag")
                    score -= 10.0
                }
                !cleanTag.matches(Regex("[a-zA-Z0-9_]+")) -> {
                    issues.add("Invalid characters in hashtag: #$cleanTag")
                    score -= 15.0
                }
            }
        }

        val duplicates = hashtags.groupBy { it.lowercase() }.filter { it.value.size > 1 }
        if (duplicates.isNotEmpty()) {
            issues.add("Duplicate hashtags found")
            score -= 20.0
        }

        return ContentValidation(
            isValid = issues.isEmpty(),
            score = max(0.0, min(100.0, score)),
            issues = issues
        )
    }

    fun generateDefaultTitle(contentType: String, userTier: UserTier): String {
        val tierPrefix = when (userTier) {
            UserTier.FOUNDER, UserTier.CO_FOUNDER -> "Founder's"
            UserTier.TOP_CREATOR -> "Creator's"
            UserTier.LEGENDARY -> "Legendary"
            UserTier.PARTNER -> "Partner's"
            UserTier.ELITE -> "Elite"
            UserTier.AMBASSADOR -> "Ambassador's"
            UserTier.INFLUENCER -> "Influencer's"
            UserTier.VETERAN -> "Veteran's"
            UserTier.RISING -> "Rising"
            UserTier.ROOKIE -> "New"
        }

        return when (contentType.lowercase()) {
            "thread" -> "$tierPrefix Thread"
            "child" -> "$tierPrefix Response"
            "stepchild" -> "$tierPrefix Reply"
            else -> "$tierPrefix Video"
        }
    }

    fun generateDefaultDescription(contentType: String): String {
        return when (contentType.lowercase()) {
            "thread" -> "Share your thoughts and start a conversation! What's on your mind?"
            "child" -> "Join the conversation with your unique perspective and insights."
            "stepchild" -> "Continue the discussion with your response to this thread."
            else -> "Express yourself and connect with the community!"
        }
    }

    fun generateDefaultHashtags(contentType: String, userTier: UserTier): List<String> {
        val baseHashtags = mutableListOf("stitch", "social")

        when (contentType.lowercase()) {
            "thread" -> baseHashtags.add("thread")
            "child" -> baseHashtags.add("response")
            "stepchild" -> baseHashtags.add("reply")
            else -> baseHashtags.add("video")
        }

        when (userTier) {
            UserTier.FOUNDER, UserTier.CO_FOUNDER -> baseHashtags.add("founder")
            UserTier.TOP_CREATOR -> baseHashtags.add("creator")
            UserTier.LEGENDARY -> baseHashtags.add("legendary")
            UserTier.PARTNER -> baseHashtags.add("partner")
            UserTier.AMBASSADOR -> baseHashtags.add("ambassador")
            else -> {}
        }

        return baseHashtags.take(5)
    }

    fun calculateAnalysisConfidence(metrics: AIAnalysisMetrics): Double {
        if (!metrics.success) return 0.0

        var confidence = 1.0

        val transcriptQuality = when {
            metrics.transcriptLength >= 100 -> 1.0
            metrics.transcriptLength >= 50 -> 0.8
            metrics.transcriptLength >= 20 -> 0.6
            else -> 0.3
        }
        confidence *= transcriptQuality

        val contentQuality = when {
            metrics.generatedTitleLength >= 10 &&
                    metrics.generatedDescriptionLength >= 20 &&
                    metrics.hashtagCount >= 3 -> 1.0

            metrics.generatedTitleLength >= 5 &&
                    metrics.generatedDescriptionLength >= 10 &&
                    metrics.hashtagCount >= 2 -> 0.8

            else -> 0.5
        }
        confidence *= contentQuality

        val timeQuality = when {
            metrics.processingTimeMs >= 2000 -> 1.0
            metrics.processingTimeMs >= 1000 -> 0.9
            metrics.processingTimeMs >= 500 -> 0.7
            else -> 0.5
        }
        confidence *= timeQuality

        return max(0.0, min(1.0, confidence))
    }

    fun tierAllowsAIFeatures(userTier: UserTier): Boolean {
        return when (userTier) {
            UserTier.ROOKIE, UserTier.RISING -> false
            UserTier.VETERAN, UserTier.INFLUENCER, UserTier.AMBASSADOR,
            UserTier.ELITE, UserTier.PARTNER, UserTier.LEGENDARY,
            UserTier.TOP_CREATOR, UserTier.FOUNDER, UserTier.CO_FOUNDER -> true
        }
    }

    fun cleanTranscript(rawTranscript: String): String {
        return rawTranscript
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[^\\w\\s.,!?-]"), "")
            .trim()
            .take(1000)
    }

    fun sanitizeContent(content: String): String {
        return content
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
    }
}