/*
 * AIVideoAnalyzer.kt - APPCONFIG INTEGRATION COMPLETE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Services - Enhanced OpenAI integration with centralized configuration
 * Dependencies: AppConfig, OkHttp, JSON, Whisper API
 * Features: Audio transcription, dual processing, enhanced prompts, centralized config
 *
 * UPDATED: Uses AppConfig for API keys, timeouts, and feature flags
 * FIXED: Proper configuration validation and error handling
 */

package com.stitchsocial.club.services

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File as JavaFile
import java.util.concurrent.TimeUnit
import com.stitchsocial.club.camera.RecordingContext
import com.stitchsocial.club.AppConfig

/**
 * Enhanced VideoAnalysisResult with transcript data
 */
data class VideoAnalysisResult(
    val title: String,
    val description: String,
    val hashtags: List<String>,
    val confidence: Double = 0.0,
    val transcript: String = "",
    val analysisType: String = "manual",
    val processingTimeMs: Long = 0L
)

/**
 * Enhanced AI Video Analyzer with centralized configuration
 */
class AIVideoAnalyzer {

    // UPDATED: Use centralized configuration
    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.Performance.CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
        .writeTimeout(AppConfig.Performance.READ_TIMEOUT, TimeUnit.MILLISECONDS)
        .readTimeout(AppConfig.Performance.READ_TIMEOUT, TimeUnit.MILLISECONDS)
        .build()

    // UPDATED: Use AppConfig for API key and URLs
    private val openAIApiKey = AppConfig.API.OpenAI.API_KEY
    private val whisperUrl = "${AppConfig.API.OpenAI.BASE_URL}/audio/transcriptions"
    private val gptUrl = "${AppConfig.API.OpenAI.BASE_URL}/chat/completions"

    /**
     * UPDATED: Check if AI is available using centralized config
     */
    fun isAIAvailable(): Boolean {
        val aiEnabled = AppConfig.Features.enableAIAnalysis
        val apiConfigured = AppConfig.API.OpenAI.isConfigured()

        println("🤖 AI AVAILABLE CHECK:")
        println("   - enableAIAnalysis: $aiEnabled")
        println("   - API isConfigured: $apiConfigured")
        println("   - API Key length: ${openAIApiKey.length}")
        println("   - API Key prefix: ${openAIApiKey.take(10)}...")

        val available = aiEnabled && apiConfigured
        println("   - RESULT: $available")

        return available
    }

    /**
     * NEW: Audio-only analysis for dual processing pipeline
     * This is faster and cheaper than video analysis
     */
    suspend fun analyzeAudioContent(
        audioPath: String,
        recordingContext: RecordingContext
    ): VideoAnalysisResult? {
        return withContext(CoroutineDispatchers.IO) {
            val startTime = System.currentTimeMillis()

            try {
                // UPDATED: Check configuration before proceeding
                if (!isAIAvailable()) {
                    println("⚠️ AI: Analysis disabled or not configured - using fallback")
                    return@withContext generateFallbackContent(recordingContext)
                }

                println("🎵 AI AUDIO: Starting audio-only analysis")

                // Step 1: Transcribe audio
                val transcript = transcribeAudio(audioPath)
                if (transcript.isNullOrBlank()) {
                    println("⚠️ AI AUDIO: No transcript - using context analysis")
                    return@withContext analyzeUserInputOnly("", recordingContext)
                }

                println("✅ AI AUDIO: Transcript complete - ${transcript.length} chars")

                // Step 2: Generate content from transcript
                val result = generateContentFromTranscript(transcript, recordingContext)
                if (result != null) {
                    val processingTime = System.currentTimeMillis() - startTime
                    println("✅ AI AUDIO: Analysis complete in ${processingTime}ms")
                    return@withContext result.copy(
                        transcript = transcript,
                        analysisType = "audio_ai",
                        processingTimeMs = processingTime
                    )
                }

                // Fallback to context analysis
                println("⚠️ AI AUDIO: Generation failed - using context analysis")
                return@withContext analyzeUserInputOnly(transcript, recordingContext)

            } catch (e: Exception) {
                val processingTime = System.currentTimeMillis() - startTime
                println("⛔ AI AUDIO: Exception - ${e.message}")
                return@withContext generateFallbackContent(recordingContext).copy(
                    processingTimeMs = processingTime
                )
            }
        }
    }

    /**
     * ENHANCED: Analyze user input only (no audio file)
     * Used when audio transcription fails or isn't available
     */
    suspend fun analyzeUserInputOnly(
        userInput: String,
        recordingContext: RecordingContext
    ): VideoAnalysisResult? {
        return withContext(CoroutineDispatchers.IO) {
            try {
                // UPDATED: Check configuration
                if (!isAIAvailable()) {
                    println("⚠️ AI: Analysis disabled - using fallback")
                    return@withContext generateFallbackContent(recordingContext)
                }

                println("🧠 SIMPLE AI: Analyzing with context")

                // Create enhanced context-based prompt
                val contextPrompt = createEnhancedContextPrompt(recordingContext, userInput)

                // Generate content with GPT
                val result = generateContentWithGPT(contextPrompt)
                if (result != null) {
                    println("✅ AI: Analysis complete")
                    result.copy(analysisType = "context_ai")
                } else {
                    println("⚠️ AI: Using fallback")
                    generateFallbackContent(recordingContext)
                }

            } catch (e: Exception) {
                println("⛔ AI: Error - ${e.message}")
                generateFallbackContent(recordingContext)
            }
        }
    }

    /**
     * FIXED: Transcribe audio/video file using OpenAI Whisper API
     * Whisper supports: mp3, mp4, mpeg, mpga, m4a, wav, webm
     */
    private suspend fun transcribeAudio(filePath: String): String? {
        return try {
            val file = JavaFile(filePath)
            if (!file.exists()) {
                println("⛔ WHISPER: File not found: $filePath")
                return null
            }

            // Determine correct media type based on file extension
            val extension = file.extension.lowercase()
            val mediaType = when (extension) {
                "mp3" -> "audio/mpeg"
                "mp4", "m4v" -> "video/mp4"
                "m4a" -> "audio/m4a"
                "wav" -> "audio/wav"
                "webm" -> "video/webm"
                "mpeg", "mpg" -> "video/mpeg"
                "mpga" -> "audio/mpeg"
                else -> "video/mp4"  // Default to video/mp4 for unknown
            }

            println("🎵 WHISPER: Transcribing ${file.length() / 1024}KB file ($extension -> $mediaType)")
            println("🎵 WHISPER: API Key configured: ${openAIApiKey.isNotEmpty()}")
            println("🎵 WHISPER: URL: $whisperUrl")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody(mediaType.toMediaType())
                )
                .addFormDataPart("model", AppConfig.API.OpenAI.Whisper.MODEL)
                .addFormDataPart("response_format", AppConfig.API.OpenAI.Whisper.RESPONSE_FORMAT)
                .build()

            val request = Request.Builder()
                .url(whisperUrl)
                .addHeader("Authorization", "Bearer $openAIApiKey")
                .post(requestBody)
                .build()

            println("🎵 WHISPER: Sending request...")
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            println("🎵 WHISPER: Response code: ${response.code}")

            if (response.isSuccessful && responseBody != null) {
                // Whisper returns plain text when response_format is "text"
                val transcript = if (responseBody.startsWith("{")) {
                    // JSON response
                    val jsonResponse = JSONObject(responseBody)
                    jsonResponse.optString("text", "")
                } else {
                    // Plain text response
                    responseBody.trim()
                }

                println("✅ WHISPER: Transcription complete - ${transcript.length} chars")
                println("✅ WHISPER: \"${transcript.take(100)}...\"")
                transcript.takeIf { it.isNotBlank() }
            } else {
                println("⛔ WHISPER: API error - ${response.code}: ${response.message}")
                println("⛔ WHISPER: Response body - $responseBody")
                null
            }

        } catch (e: Exception) {
            println("⛔ WHISPER: Exception - ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Generate content from transcript using enhanced prompts
     */
    private suspend fun generateContentFromTranscript(
        transcript: String,
        recordingContext: RecordingContext
    ): VideoAnalysisResult? {
        return try {
            val enhancedPrompt = createTranscriptPrompt(transcript, recordingContext)
            generateContentWithGPT(enhancedPrompt)

        } catch (e: Exception) {
            println("⛔ TRANSCRIPT AI: Error - ${e.message}")
            null
        }
    }

    /**
     * UPDATED: Enhanced GPT content generation with AppConfig settings
     */
    private suspend fun generateContentWithGPT(prompt: String): VideoAnalysisResult? {
        return try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", """
                        You are a social media expert specializing in short-form video content.
                        Create engaging, authentic content that performs well on social platforms.
                        Focus on clarity, engagement, and discoverability.
                        
                        RESPONSE FORMAT: Always respond with valid JSON in this exact format:
                        {
                            "title": "Your engaging title here",
                            "description": "Your compelling description here",
                            "hashtags": ["#trending", "#viral", "#fyp"]
                        }
                        
                        RULES:
                        - Title: ${AppConfig.App.MAX_TITLE_LENGTH} characters max, engaging but not clickbait
                        - Description: ${AppConfig.App.MAX_DESCRIPTION_LENGTH} characters max, conversational tone
                        - Hashtags: ${AppConfig.App.MAX_HASHTAGS} max, mix of trending and niche
                        - Be authentic and avoid overly promotional language
                        - ALWAYS respond with valid JSON only
                    """.trimIndent())
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            val requestBody = JSONObject().apply {
                put("model", AppConfig.API.OpenAI.ChatCompletion.MODEL)
                put("messages", messages)
                put("max_tokens", AppConfig.API.OpenAI.ChatCompletion.MAX_TOKENS)
                put("temperature", AppConfig.API.OpenAI.ChatCompletion.TEMPERATURE)
                put("response_format", JSONObject().put("type", "json_object"))
            }

            val request = Request.Builder()
                .url(gptUrl)
                .addHeader("Authorization", "Bearer $openAIApiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                parseGPTResponse(responseBody)
            } else {
                println("⛔ GPT: API error - ${response.code}: ${response.message}")
                if (AppConfig.Features.enableDebugLogging) {
                    println("⛔ GPT: Response body - $responseBody")
                }
                null
            }

        } catch (e: Exception) {
            println("⛔ GPT: Exception - ${e.message}")
            if (AppConfig.Features.enableDebugLogging) {
                e.printStackTrace()
            }
            null
        }
    }

    /**
     * UPDATED: Parse GPT response into structured result with better error handling
     */
    private fun parseGPTResponse(responseBody: String): VideoAnalysisResult? {
        return try {
            val response = JSONObject(responseBody)
            val choices = response.getJSONArray("choices")
            if (choices.length() == 0) {
                println("⛔ GPT PARSE: No choices in response")
                return null
            }

            val choice = choices.getJSONObject(0)
            val message = choice.getJSONObject("message")
            val content = message.getString("content")

            // Parse the JSON content
            val contentJson = JSONObject(content)

            val title = contentJson.optString("title", "").take(AppConfig.App.MAX_TITLE_LENGTH)
            val description = contentJson.optString("description", "").take(AppConfig.App.MAX_DESCRIPTION_LENGTH)

            val hashtagsArray = contentJson.optJSONArray("hashtags")
            val hashtags = if (hashtagsArray != null) {
                (0 until hashtagsArray.length())
                    .map { hashtagsArray.getString(it) }
                    .take(AppConfig.App.MAX_HASHTAGS)
                    .map { if (it.startsWith("#")) it else "#$it" }
            } else {
                listOf("#trending", "#fyp")
            }

            // Validate parsed content
            if (title.isBlank()) {
                println("⛔ GPT PARSE: Empty title")
                return null
            }

            println("✅ GPT PARSE: Success - '$title' with ${hashtags.size} hashtags")

            VideoAnalysisResult(
                title = title,
                description = description,
                hashtags = hashtags,
                confidence = 0.8,
                analysisType = "ai_generated"
            )

        } catch (e: Exception) {
            println("⛔ GPT PARSE: Failed to parse response - ${e.message}")
            if (AppConfig.Features.enableDebugLogging) {
                println("⛔ GPT PARSE: Raw response - $responseBody")
            }
            null
        }
    }

    /**
     * Create enhanced context-based prompt for user input analysis
     */
    private fun createEnhancedContextPrompt(recordingContext: RecordingContext, userInput: String): String {
        val contextDescription = when (recordingContext) {
            is RecordingContext.NewThread -> "creating a new thread video"
            is RecordingContext.StitchToThread -> "stitching to an existing thread by ${recordingContext.threadInfo.creatorName}"
            is RecordingContext.ReplyToVideo -> "replying to a video by ${recordingContext.videoInfo.creatorName}"
            is RecordingContext.ContinueThread -> "continuing a thread by ${recordingContext.threadInfo.creatorName}"
        }

        return """
            I am $contextDescription. 
            ${if (userInput.isNotBlank()) "Context: $userInput" else ""}
            
            Based on this context, create engaging social media content that would perform well as a short-form video.
            Focus on current trends, relatable content, and engaging hooks.
        """.trimIndent()
    }

    /**
     * Create transcript-based prompt for AI analysis
     */
    private fun createTranscriptPrompt(transcript: String, recordingContext: RecordingContext): String {
        val contextDescription = when (recordingContext) {
            is RecordingContext.NewThread -> "This is a new thread video"
            is RecordingContext.StitchToThread -> "This is a stitch to ${recordingContext.threadInfo.creatorName}'s thread: ${recordingContext.threadInfo.title}"
            is RecordingContext.ReplyToVideo -> "This is a reply to ${recordingContext.videoInfo.creatorName}'s video: ${recordingContext.videoInfo.title}"
            is RecordingContext.ContinueThread -> "This is continuing ${recordingContext.threadInfo.creatorName}'s thread: ${recordingContext.threadInfo.title}"
        }

        return """
            $contextDescription with the following spoken content:
            
            "$transcript"
            
            Create compelling social media content based on what was said. Focus on the key points, 
            emotions, and topics mentioned. Make it engaging and discoverable.
        """.trimIndent()
    }

    /**
     * ADDED: Simple video analysis method for UploadResult.kt compatibility
     * Alias for analyzeUserInputOnly() with simplified interface
     */
    suspend fun analyzeVideoSimple(
        recordingContext: RecordingContext,
        userInput: String = ""
    ): VideoAnalysisResult? {
        return analyzeUserInputOnly(userInput, recordingContext)
    }

    /**
     * FIXED: Generate fallback content when AI fails - returns BLANK fields
     * User will enter their own title/description/hashtags manually
     * Matches iOS behavior when AI is not available
     */
    fun generateFallbackContent(recordingContext: RecordingContext): VideoAnalysisResult {
        // Return BLANK fields - let user fill in manually
        // This matches iOS behavior when AI analysis fails or is unavailable
        println("⚠️ AI FALLBACK: Returning blank fields for manual entry")

        return VideoAnalysisResult(
            title = "",  // Blank - user enters manually
            description = "",  // Blank - user enters manually
            hashtags = emptyList(),  // Empty - user adds manually
            confidence = 0.0,
            analysisType = "manual"  // Changed from "fallback" to "manual"
        )
    }
}