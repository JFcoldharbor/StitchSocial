package com.stitchsocial.club.services

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.stitchsocial.club.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cuts one recording into episode segments, uploads them, and publishes the
 * collection (iOS parity with EpisodeFinalizeService.swift).
 *
 * Android could not create an episode AT ALL before this: ShowService reads
 * shows, seasons and episodes, and nothing anywhere wrote a collection. Android
 * creators could watch shows and not make them.
 *
 * SEGMENTS ARE WRITTEN TO TOP-LEVEL `videos/{id}`, not a subcollection. That's
 * deliberate and matches iOS — engagement (hype/cool/view/reply) reads from
 * there, so a segment in a subcollection can't be engaged with. They're kept out
 * of feeds by `isCollectionSegment` + `collectionID` instead.
 */
class EpisodeFinalizeService(private val context: Context) {

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val storage = FirebaseStorage.getInstance()

    /** One cut of the source recording. */
    data class Segment(
        val title: String,
        val startMs: Long,
        val endMs: Long,
        /** True when the creator placed this cut by hand rather than auto-split. */
        val locked: Boolean = false
    ) {
        val durationSeconds: Double get() = (endMs - startMs) / 1000.0
    }

    data class Input(
        val episodeID: String,
        val sourceUri: Uri,
        val segments: List<Segment>,
        val title: String,
        val description: String,
        val creatorID: String,
        val creatorName: String,
        val coverImageURL: String?,
        val contentType: String,
        val showId: String,
        val seasonId: String,
        val isFree: Boolean,
        /** Segments playable before the paywall. See VideoCollection.freeSegmentCount. */
        val freeSegmentCount: Int,
        /** "published" or "draft". */
        val status: String,
        val totalDuration: Double
    )

    /** Phases the caller renders. Splitting and uploading carry an index so a
     *  determinate bar is possible — an indeterminate spinner on a multi-minute
     *  upload is how creators conclude the app has hung and kill it. */
    sealed class Phase {
        data class Splitting(val index: Int, val total: Int) : Phase()
        data class Uploading(val index: Int, val total: Int) : Phase()
        object Saving : Phase()
        data class Done(val segments: Int) : Phase()
        data class Failed(val message: String) : Phase()
    }

    /**
     * Split → upload → publish, as ONE batched write at the end.
     *
     * The batch matters: a partial publish leaves segment docs pointing at a
     * collection that doesn't exist, which shows up later as an episode with
     * missing parts and no way to tell which half failed.
     */
    suspend fun finalize(
        input: Input,
        onProgress: (Phase) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val temps = mutableListOf<File>()
        try {
            require(input.segments.isNotEmpty()) { "An episode needs at least one segment" }

            val batch = db.batch()
            val segmentIds = mutableListOf<String>()
            var firstThumbnailURL: String? = null

            input.segments.forEachIndexed { i, seg ->
                onProgress(Phase.Splitting(i + 1, input.segments.size))
                val cut = exportClip(input.sourceUri, seg, i)
                temps += cut

                onProgress(Phase.Uploading(i + 1, input.segments.size))
                val segId = UUID.randomUUID().toString()
                val videoURL = upload(cut, "collections/${input.episodeID}/$segId.mp4")

                if (firstThumbnailURL == null) firstThumbnailURL = input.coverImageURL

                val segData = mapOf(
                    "id" to segId,
                    "title" to seg.title,
                    "description" to "",
                    "videoURL" to videoURL,
                    "thumbnailURL" to (input.coverImageURL ?: ""),
                    "creatorID" to input.creatorID,
                    "creatorName" to input.creatorName,
                    "createdAt" to Timestamp(Date()),
                    "threadID" to segId,
                    "conversationDepth" to 0,
                    "viewCount" to 0, "hypeCount" to 0, "coolCount" to 0,
                    "replyCount" to 0, "shareCount" to 0, "tipCount" to 0,
                    "temperature" to "neutral",
                    "qualityScore" to 50,
                    "engagementRatio" to 0.5,
                    "velocityScore" to 0.0,
                    "trendingScore" to 0.0,
                    "duration" to seg.durationSeconds,
                    "aspectRatio" to 9.0 / 16.0,
                    "fileSize" to cut.length(),
                    "discoverabilityScore" to 0.5,
                    "isPromoted" to false,
                    "collectionID" to input.episodeID,
                    "segmentNumber" to i + 1,
                    "segmentTitle" to seg.title,
                    // Keeps segments out of the home/search feeds — they're
                    // episode parts, not standalone posts.
                    "isCollectionSegment" to true,
                    "uploadStatus" to "complete",
                    "isManualCut" to seg.locked,
                    "recordingSource" to "cameraRoll",
                    "hashtags" to emptyList<String>(),
                    // Moderate-before-publish, same as every other video write.
                    "publicVisibility" to "pending"
                )
                batch.set(db.collection("videos").document(segId), segData)
                segmentIds += segId
            }

            onProgress(Phase.Saving)

            batch.set(
                db.collection("videoCollections").document(input.episodeID),
                mapOf(
                    "id" to input.episodeID,
                    "title" to input.title,
                    "description" to input.description,
                    "creatorID" to input.creatorID,
                    "creatorName" to input.creatorName,
                    "coverImageURL" to (firstThumbnailURL ?: input.coverImageURL ?: ""),
                    "segmentIDs" to segmentIds,
                    "segmentCount" to input.segments.size,
                    "totalDuration" to input.totalDuration,
                    "status" to input.status,
                    "visibility" to "public",
                    "allowReplies" to true,
                    "isFree" to input.isFree,
                    "freeSegmentCount" to input.freeSegmentCount,
                    "contentType" to input.contentType,
                    "showId" to input.showId,
                    "seasonId" to input.seasonId,
                    "createdAt" to Timestamp(Date()),
                    "updatedAt" to Timestamp(Date()),
                    // publishedAt ONLY when actually published. A draft carrying
                    // a publishedAt would rank in discovery queries that sort by
                    // it; see the CollectionService note about orderBy exclusion.
                    "publishedAt" to if (input.status == "published") Timestamp(Date())
                                     else FieldValue.delete()
                )
            )

            batch.commit().await()
            onProgress(Phase.Done(segmentIds.size))
            if (BuildConfig.DEBUG) {
                println("🎬 FINALIZE: published ${input.episodeID} with ${segmentIds.size} segments")
            }
            Result.success(input.episodeID)
        } catch (e: Exception) {
            onProgress(Phase.Failed(e.message ?: "Publish failed"))
            if (BuildConfig.DEBUG) { println("❌ FINALIZE: ${e.message}") }
            Result.failure(e)
        } finally {
            // Only our own exports — never the creator's source recording.
            temps.forEach { runCatching { it.delete() } }
        }
    }

    /**
     * Export one cut with Transformer's clipping configuration.
     *
     * Clipping rather than re-encoding the whole file per segment: Transformer
     * can pass through the original streams for the requested range, so a
     * ten-minute source doesn't get decoded five times to produce five cuts.
     */
    private suspend fun exportClip(source: Uri, seg: Segment, index: Int): File {
        val out = File(context.cacheDir, "ep_seg_${index}_${System.nanoTime()}.mp4")
        val item = MediaItem.Builder()
            .setUri(source)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(seg.startMs)
                    .setEndPositionMs(seg.endMs)
                    .build()
            )
            .build()

        return suspendCancellableCoroutine { cont ->
            // Transformer MUST be built and started on a thread with a Looper —
            // this runs on Dispatchers.IO, so everything touching it is posted
            // to main. Building it here instead would compile cleanly and throw
            // at runtime on the first export, which is the worst place to find
            // out. Same pattern as ReactionCompositor.
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            if (cont.isActive) cont.resume(out)
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException
                        ) {
                            if (cont.isActive) cont.resumeWithException(exception)
                        }
                    })
                    .build()

                cont.invokeOnCancellation {
                    mainHandler.post { runCatching { transformer.cancel() } }
                }
                transformer.start(item, out.absolutePath)
            }
        }
    }

    private suspend fun upload(file: File, path: String): String {
        val ref = storage.reference.child(path)
        ref.putFile(Uri.fromFile(file)).await()
        return ref.downloadUrl.await().toString()
    }
}
