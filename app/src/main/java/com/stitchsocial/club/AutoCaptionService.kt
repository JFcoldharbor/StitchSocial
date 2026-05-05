/*
 * AutoCaptionService.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Cloud-backed speech-to-text caption generation. Mirrors iOS
 * AutoCaptionService.swift functionally but uses a Firebase Cloud Function
 * (transcribeAudio) wrapping Google Cloud Speech-to-Text since Android's
 * built-in SpeechRecognizer is microphone-only and can't transcribe a
 * recorded file.
 *
 * Flow:
 *   1. Extract audio track from the video to a temp m4a file.
 *   2. Upload the m4a to Firebase Storage at temp_audio/{uid}/{uuid}.m4a
 *   3. Invoke the transcribeAudio callable Cloud Function with that path.
 *   4. The function calls Google Speech-to-Text, deletes the temp upload,
 *      and returns chunked captions matching the iOS layout.
 *   5. Map response → List<VideoCaption>.
 *
 * Failures (no audio track, no signed-in user, network error, no speech
 * detected) all fall through to an empty list silently — the review flow
 * proceeds without captions, never blocks.
 */

package com.stitchsocial.club

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

class AutoCaptionService private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: AutoCaptionService? = null
        fun getInstance(context: Context): AutoCaptionService = instance ?: synchronized(this) {
            instance ?: AutoCaptionService(context.applicationContext).also { instance = it }
        }

        // The Cloud Function in stitchfunctions/index.js. Uses the default
        // codebase, so the callable name is just the function export name.
        private const val FUNCTION_NAME = "transcribeAudio"
    }

    // Published state for the editor UI
    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing

    private val _transcriptionProgress = MutableStateFlow(0.0)
    val transcriptionProgress: StateFlow<Double> = _transcriptionProgress

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val storage: FirebaseStorage get() = FirebaseStorage.getInstance()
    private val functions: FirebaseFunctions get() = FirebaseFunctions.getInstance()

    suspend fun generateCaptions(videoUri: Uri): List<VideoCaption> = withContext(Dispatchers.IO) {
        _isTranscribing.value = true
        _transcriptionProgress.value = 0.0
        try {
            val uid = auth.currentUser?.uid
            if (uid.isNullOrEmpty()) {
                println("⚠️ AUTO CAPTION: not signed in — skipping")
                return@withContext emptyList()
            }

            // 1) Extract audio
            _transcriptionProgress.value = 0.1
            val audioFile = extractAudio(videoUri)
            if (audioFile == null || !audioFile.exists() || audioFile.length() == 0L) {
                println("ℹ️ AUTO CAPTION: no audio track in video")
                return@withContext emptyList()
            }

            // 2) Upload to Storage at a path the function will accept (under
            //    temp_audio/<uid>/...). The function deletes it after use.
            _transcriptionProgress.value = 0.4
            val storagePath = "temp_audio/$uid/${UUID.randomUUID()}.m4a"
            val ref = storage.reference.child(storagePath)
            try {
                ref.putFile(Uri.fromFile(audioFile)).await()
                println("📤 AUTO CAPTION: uploaded audio to $storagePath (${audioFile.length() / 1024} KB)")
            } catch (e: Exception) {
                println("⚠️ AUTO CAPTION: upload failed — ${e.message}")
                audioFile.delete()
                return@withContext emptyList()
            } finally {
                audioFile.delete()
            }

            // 3) Call the Cloud Function
            _transcriptionProgress.value = 0.6
            val response = try {
                functions.getHttpsCallable(FUNCTION_NAME)
                    .call(mapOf("audioPath" to storagePath, "languageCode" to "en-US"))
                    .await()
            } catch (e: Exception) {
                println("⚠️ AUTO CAPTION: function call failed — ${e.message}")
                // Best-effort cleanup if the function didn't run / didn't delete.
                try { ref.delete().await() } catch (_: Exception) {}
                return@withContext emptyList()
            }

            // 4) Parse response into VideoCaption list
            _transcriptionProgress.value = 0.9
            val captions = parseCaptions(response.data)
            println("✅ AUTO CAPTION: received ${captions.size} caption(s) from cloud")
            return@withContext captions
        } catch (e: Exception) {
            println("⚠️ AUTO CAPTION: unexpected — ${e.message}")
            return@withContext emptyList()
        } finally {
            _isTranscribing.value = false
            _transcriptionProgress.value = 1.0
        }
    }

    // ───── Audio extraction ─────────────────────────────────────────────

    private fun extractAudio(videoUri: Uri): File? {
        val outputFile = File(context.cacheDir, "caption_audio_${UUID.randomUUID()}.m4a")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, videoUri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return null

            extractor.selectTrack(audioTrackIndex)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                bufferInfo.offset = 0
                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                extractor.advance()
            }
            return outputFile
        } catch (e: Exception) {
            println("⚠️ AUTO CAPTION: audio extraction failed — ${e.message}")
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    // ───── Response parsing ─────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun parseCaptions(data: Any?): List<VideoCaption> {
        val map = data as? Map<String, Any?> ?: return emptyList()
        val rawCaptions = map["captions"] as? List<Map<String, Any?>> ?: return emptyList()

        return rawCaptions.mapNotNull { entry ->
            val text = entry["text"] as? String ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            val startTime = (entry["startTime"] as? Number)?.toDouble() ?: 0.0
            val duration = (entry["duration"] as? Number)?.toDouble() ?: 3.0
            VideoCaption(
                text = text,
                startTime = startTime,
                duration = duration
            )
        }
    }
}
