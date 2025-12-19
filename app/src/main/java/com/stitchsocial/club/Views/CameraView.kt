/*
 * CameraView.kt - GALLERY PICKER INTEGRATION FIXED
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Camera recording interface with complete VideoCoordinator integration
 * FIXED: Gallery picker now routes through NavigationCoordinator for proper processing
 * UPDATED: Gallery videos go through same parallel processing pipeline as recorded videos
 */

package com.stitchsocial.club.views

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// COMPLETE IMPORTS - Foundation types
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.camera.RecordingContext
import com.stitchsocial.club.viewmodels.CameraViewModel

/**
 * Complete Camera recording interface with VideoCoordinator pipeline integration
 * GALLERY FIX: Gallery button now triggers NavigationCoordinator for proper processing
 */
@Composable
fun CameraView(
    recordingContext: RecordingContext = RecordingContext.NewThread,
    parentVideo: CoreVideoMetadata? = null,
    userTier: UserTier = UserTier.ROOKIE,
    onVideoRecorded: (CoreVideoMetadata) -> Unit,
    onCancel: () -> Unit,
    onStopAllVideos: () -> Unit = {},
    onDisposeAllVideos: () -> Unit = {},
    onGalleryRequested: () -> Unit = {},  // NEW: Gallery picker callback
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camera state
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0) }
    var hasPermission by remember { mutableStateOf(false) }
    var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var cameraExecutor: ExecutorService by remember { mutableStateOf(Executors.newSingleThreadExecutor()) }

    // Camera flip state
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var isUsingFrontCamera by remember { mutableStateOf(false) }

    // Store the current recording file for cleanup and processing
    var currentVideoFile by remember { mutableStateOf<File?>(null) }

    // Collect tier-based recording state
    val maxRecordingDuration by viewModel.maxRecordingDuration.collectAsState()
    val isUnlimitedRecording by viewModel.isUnlimitedRecording.collectAsState()
    val recordingProgress = viewModel.getRecordingProgress()

    // Initialize tier limits on user tier change
    LaunchedEffect(userTier) {
        viewModel.updateRecordingLimits(userTier)
        println("CAMERA: Updated recording limits for tier: ${userTier.displayName}")
    }

    // Stop and dispose all background videos when camera opens
    LaunchedEffect(Unit) {
        onStopAllVideos()
        onDisposeAllVideos()
        println("CAMERA: Stopped and disposed all background videos")
    }

    // Camera permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions[Manifest.permission.CAMERA] == true &&
                permissions[Manifest.permission.RECORD_AUDIO] == true
    }

    // Check permissions on launch
    LaunchedEffect(Unit) {
        val cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        val audioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)

        hasPermission = cameraPermission == PackageManager.PERMISSION_GRANTED &&
                audioPermission == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ))
        }
    }

    // Recording duration timer with tier-based auto-stop
    LaunchedEffect(isRecording, maxRecordingDuration, isUnlimitedRecording) {
        if (isRecording) {
            while (isRecording) {
                delay(1000)
                recordingDuration++
                viewModel.updateRecordingDuration(recordingDuration.toLong())

                // Auto-stop when tier limit reached (unless unlimited)
                if (viewModel.shouldAutoStop()) {
                    println("CAMERA: Auto-stopping recording at tier limit (${maxRecordingDuration}s)")
                    recording?.stop()
                    isRecording = false
                    break
                }
            }
        } else {
            recordingDuration = 0
            viewModel.updateRecordingDuration(0L)
        }
    }

    // ===== CAMERA FUNCTIONS =====

    // Camera flip function
    val flipCamera: () -> Unit = {
        cameraSelector = if (isUsingFrontCamera) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        isUsingFrontCamera = !isUsingFrontCamera

        println("CAMERA: Flipped to ${if (isUsingFrontCamera) "front" else "back"} camera")

        // Rebind camera with new selector
        cameraProvider?.let { provider ->
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )
                println("CAMERA: Camera rebound successfully")
            } catch (exc: Exception) {
                println("CAMERA: Camera flip failed - ${exc.message}")
            }
        }
    }

    // ===== GALLERY PICKER HANDLER (FIXED) =====

    val handleGalleryRequest: () -> Unit = {
        println("CAMERA: Gallery button pressed - closing camera and triggering gallery picker")
        onCancel() // Close camera first
        onGalleryRequested() // Trigger NavigationCoordinator gallery flow
    }

    // ===== RECORDING PIPELINE =====

    // Start recording function with proper file management
    val startRecording: () -> Unit = {
        videoCapture?.let { capture ->
            val timestamp = System.currentTimeMillis()
            val videoFile = File(context.cacheDir, "STITCH_${timestamp}.mp4")
            currentVideoFile = videoFile

            val outputOptions = FileOutputOptions.Builder(videoFile).build()

            recording = capture.output
                .prepareRecording(context, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            println("CAMERA: Recording started - ${videoFile.name}")
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (!event.hasError()) {
                                println("CAMERA: Recording completed successfully")
                                println("CAMERA: File: ${videoFile.absolutePath}")
                                println("CAMERA: Size: ${videoFile.length() / 1024}KB")
                                println("CAMERA: Duration: ${recordingDuration}s")

                                val recordedVideoData = createCompleteRecordedVideoData(
                                    recordingContext = recordingContext,
                                    parentVideo = parentVideo,
                                    videoFile = videoFile,
                                    duration = recordingDuration
                                )

                                println("CAMERA: Calling onVideoRecorded callback")
                                onVideoRecorded(recordedVideoData)

                            } else {
                                println("CAMERA: Recording failed - ${event.error}")
                                videoFile.delete()
                            }
                            recording = null
                            currentVideoFile = null
                        }
                    }
                }

            isRecording = true
            viewModel.startRecording()
            println("CAMERA: Recording initiated")
        }
    }

    // Stop recording function
    val stopRecording: () -> Unit = {
        recording?.stop()
        isRecording = false
        viewModel.stopRecording()
        println("CAMERA: Recording stopped")
    }

    // Cancel recording function with cleanup
    val cancelRecording: () -> Unit = {
        recording?.stop()
        isRecording = false
        viewModel.stopRecording()

        currentVideoFile?.let { file ->
            if (file.exists()) {
                file.delete()
                println("CAMERA: Cancelled recording file deleted")
            }
        }
        currentVideoFile = null

        println("CAMERA: Camera cancelled - videos will reload on return to feed")
        onCancel()
    }

    // ===== CAMERA UI =====

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasPermission) {
            // Real camera preview
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            cameraProvider = cameraProviderFuture.get()

                            // Preview use case
                            preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(surfaceProvider)
                            }

                            // SAMSUNG FIX: Optimized VideoCapture configuration
                            // Use lower quality settings to prevent REC indicator overlay
                            val qualitySelector = QualitySelector.fromOrderedList(
                                listOf(Quality.SD, Quality.HD),
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                            )

                            val recorder = Recorder.Builder()
                                .setQualitySelector(qualitySelector)
                                .build()

                            val newVideoCapture = VideoCapture.withOutput(recorder)
                            videoCapture = newVideoCapture

                            try {
                                cameraProvider?.unbindAll()

                                cameraProvider?.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    newVideoCapture
                                )

                                println("CAMERA: Camera initialized successfully (Samsung optimized)")

                            } catch (exc: Exception) {
                                println("CAMERA: Camera initialization failed - ${exc.message}")
                            }

                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ===== CAMERA OVERLAY UI =====

            // Top controls
            CameraTopControls(
                recordingContext = recordingContext,
                parentVideo = parentVideo,
                userTier = userTier,
                onCancel = cancelRecording,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter)
            )

            // Recording indicator with tier-based progress
            if (isRecording) {
                RecordingIndicator(
                    duration = recordingDuration,
                    maxDuration = if (isUnlimitedRecording) 0 else maxRecordingDuration.toInt(),
                    progress = recordingProgress,
                    isUnlimited = isUnlimitedRecording,
                    userTier = userTier,
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                )
            }

            // Bottom controls - FIXED: Gallery uses NavigationCoordinator
            CameraBottomControls(
                isRecording = isRecording,
                onStartRecording = startRecording,
                onStopRecording = stopRecording,
                onCancel = cancelRecording,
                onFlipCamera = flipCamera,
                onOpenGallery = handleGalleryRequest,  // FIXED: Routes through NavigationCoordinator
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            )

        } else {
            // Permission request UI
            PermissionRequestScreen(
                onRequestPermissions = {
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                    ))
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            println("CAMERA: Camera disposed - videos will reload on navigation back")
        }
    }
}

// ===== HELPER FUNCTIONS =====

private fun createCompleteRecordedVideoData(
    recordingContext: RecordingContext,
    parentVideo: CoreVideoMetadata?,
    videoFile: File,
    duration: Int
): CoreVideoMetadata {
    val timestamp = System.currentTimeMillis()
    val videoId = "recorded_${timestamp}"

    println("CAMERA: Creating video data:")
    println("  - Context: $recordingContext")
    println("  - Parent: ${parentVideo?.title ?: "None"}")
    println("  - File: ${videoFile.absolutePath}")
    println("  - Duration: ${duration}s")
    println("  - Size: ${videoFile.length()}bytes")

    return when (recordingContext) {
        RecordingContext.NewThread -> {
            CoreVideoMetadata.newThread(
                id = videoId,
                title = "New Video Recording",
                videoURL = videoFile.absolutePath,
                thumbnailURL = "",
                creatorID = "current_user_id",
                creatorName = "Current User",
                duration = duration.toDouble(),
                fileSize = videoFile.length()
            )
        }
        else -> {
            CoreVideoMetadata.childReply(
                id = videoId,
                title = if (parentVideo != null) "Reply to ${parentVideo.title}" else "Reply Video",
                videoURL = videoFile.absolutePath,
                thumbnailURL = "",
                creatorID = "current_user_id",
                creatorName = "Current User",
                parentThreadID = parentVideo?.id ?: "unknown_thread",
                duration = duration.toDouble(),
                fileSize = videoFile.length()
            )
        }
    }
}

@Composable
private fun CameraTopControls(
    recordingContext: RecordingContext,
    parentVideo: CoreVideoMetadata?,
    userTier: UserTier,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Cancel",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        RecordingContextInfo(
            recordingContext = recordingContext,
            parentVideo = parentVideo,
            userTier = userTier
        )

        IconButton(
            onClick = {
                println("CAMERA: Settings clicked")
            },
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun RecordingContextInfo(
    recordingContext: RecordingContext,
    parentVideo: CoreVideoMetadata?,
    userTier: UserTier,
    modifier: Modifier = Modifier
) {
    val (contextText, contextColor) = when (recordingContext) {
        RecordingContext.NewThread -> "New Thread" to Color.White
        else -> "Reply" to Color.Cyan
    }

    Column(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.7f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = contextText,
            color = contextColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = when (userTier) {
                UserTier.FOUNDER, UserTier.CO_FOUNDER -> "Unlimited"
                UserTier.PARTNER, UserTier.LEGENDARY, UserTier.TOP_CREATOR -> "2min limit"
                else -> "30s limit"
            },
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp
        )

        if (parentVideo != null) {
            Text(
                text = "to @${parentVideo.creatorName}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun RecordingIndicator(
    duration: Int,
    maxDuration: Int,
    progress: Float,
    isUnlimited: Boolean,
    userTier: UserTier,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                Color.Red.copy(alpha = 0.8f),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )

            Text(
                text = "REC ${String.format("%02d:%02d", duration / 60, duration % 60)}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (!isUnlimited && maxDuration > 0) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .width(80.dp)
                    .height(2.dp),
                color = if (progress > 0.8f) Color.Yellow else Color.White,
                trackColor = Color.White.copy(alpha = 0.3f)
            )

            val remaining = maxDuration - duration
            Text(
                text = if (remaining > 0) "${remaining}s left" else "Time up!",
                color = if (remaining <= 5) Color.Yellow else Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        } else if (isUnlimited) {
            Text(
                text = "Unlimited",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun CameraBottomControls(
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancel: () -> Unit,
    onFlipCamera: () -> Unit,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // GALLERY BUTTON - Routes through NavigationCoordinator
        IconButton(
            onClick = onOpenGallery,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoLibrary,
                contentDescription = "Select Video from Gallery",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        // RECORD BUTTON
        IconButton(
            onClick = if (isRecording) onStopRecording else onStartRecording,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(if (isRecording) Color.Red else Color.White)
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                tint = if (isRecording) Color.White else Color.Red,
                modifier = Modifier.size(if (isRecording) 32.dp else 48.dp)
            )
        }

        // FLIP CAMERA BUTTON
        IconButton(
            onClick = onFlipCamera,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(
                imageVector = Icons.Filled.FlipCameraAndroid,
                contentDescription = "Flip Camera",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun PermissionRequestScreen(
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Camera,
                contentDescription = "Camera",
                tint = Color.White,
                modifier = Modifier.size(64.dp)
            )

            Text(
                text = "Camera and Microphone Access Required",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Stitch Social needs access to your camera and microphone to record videos.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp
            )

            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Red,
                    contentColor = Color.White
                )
            ) {
                Text("Grant Permissions")
            }
        }
    }
}