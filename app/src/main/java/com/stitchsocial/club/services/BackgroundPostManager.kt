package com.stitchsocial.club.services

import com.stitchsocial.club.coordination.VideoCoordinator
import com.stitchsocial.club.camera.RecordingContext
import com.stitchsocial.club.foundation.CoreVideoMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BackgroundPostManager
 *
 * Singleton owning an app-scoped coroutine that runs the final post
 * upload (videoCoordinator.completeVideoCreation + notifications) so the
 * ThreadComposer can dismiss immediately and the user can keep scrolling
 * while the upload finishes. Mirrors iOS BackgroundPostManager.
 *
 * The create button in CustomDippedTabBar observes `isPosting` + `progress`
 * and fills the button with the heat-phase color + embers + percentage
 * while a post is in flight.
 *
 * Lifetime: the SupervisorJob lives for the lifetime of the process — it's
 * NOT tied to any Composable scope, so dismissing ThreadComposer can't
 * cancel the upload.
 */
object BackgroundPostManager {

    private val managerJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + managerJob)

    // MARK: - Published state (read by CustomDippedTabBar)

    enum class PostStatus { IDLE, COMPRESSING, UPLOADING, COMPLETE, FAILED }

    private val _isPosting = MutableStateFlow(false)
    val isPosting: StateFlow<Boolean> = _isPosting.asStateFlow()

    /** 0.0–1.0 capped at 0.99 while the upload is still in flight, then 1.0 on completion. */
    private val _progress = MutableStateFlow(0.0)
    val progress: StateFlow<Double> = _progress.asStateFlow()

    private val _status = MutableStateFlow(PostStatus.IDLE)
    val status: StateFlow<PostStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastCreatedVideoID = MutableStateFlow<String?>(null)
    val lastCreatedVideoID: StateFlow<String?> = _lastCreatedVideoID.asStateFlow()

    // MARK: - Submit a post (called from ThreadComposer)

    /**
     * Kicks off the final upload + notifications in the manager's app-scope.
     * Returns immediately — the caller (ThreadComposer) should dismiss as
     * soon as this returns so the user lands back on the feed.
     */
    fun submitPost(
        videoCoordinator: VideoCoordinator,
        userTitle: String,
        userDescription: String,
        userHashtags: List<String>,
        taggedUserIDs: List<String>,
        onCompleted: (CoreVideoMetadata) -> Unit = {},
        onFailed: (Throwable) -> Unit = {}
    ) {
        // Reset state explicitly so a previous post's COMPLETE/1.0 can't
        // leak into the new post's first observed frame.
        _status.value = PostStatus.COMPRESSING
        _progress.value = 0.0
        _lastError.value = null
        _isPosting.value = true

        // Mirror videoCoordinator.progress into our own StateFlow with the
        // 0.99 clamp — phase 4 ("Complete!") should fire ONLY after the
        // completeVideoCreation suspension actually returns.
        val mirrorJob = scope.launch {
            videoCoordinator.progress.collect { value ->
                if (_isPosting.value && _status.value != PostStatus.COMPLETE) {
                    _progress.value = value.coerceAtMost(0.99)
                }
            }
        }

        scope.launch {
            try {
                _status.value = PostStatus.UPLOADING
                val created = videoCoordinator.completeVideoCreation(
                    userTitle = userTitle,
                    userDescription = userDescription,
                    userHashtags = userHashtags,
                    taggedUserIDs = taggedUserIDs
                )

                _status.value = PostStatus.COMPLETE
                _progress.value = 1.0
                _lastCreatedVideoID.value = created.id

                onCompleted(created)

                // Hold the COMPLETE state briefly so the create-button ring
                // can flash a green checkmark, then idle.
                delay(2_500)
                _status.value = PostStatus.IDLE
                _isPosting.value = false
                _progress.value = 0.0
            } catch (e: Throwable) {
                _status.value = PostStatus.FAILED
                _lastError.value = e.message
                _isPosting.value = false
                onFailed(e)
            } finally {
                mirrorJob.cancel()
            }
        }
    }
}
