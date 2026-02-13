/*
 * CameraView.kt - FIXED CAMERA CLEANUP
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Camera recording interface with complete VideoCoordinator integration
 * FIXED: Proper camera unbinding in DisposableEffect to remove recording indicator
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
 * CAMERA FIX: Proper resource cleanup removes recording indicator from status bar
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
    // Instance tracking for debugging
    val instanceId = remember { System.currentTimeMillis() }
    println("📷 CAMERA INSTANCE $instanceId: CameraView composing - context=$recordingContext")

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
    var isTorchOn by remember { mutableStateOf(false) }

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
        println("🎬 CAMERA: Start recording pressed!")
        videoCapture?.let { capture ->
            val timestamp = System.currentTimeMillis()
            val videoFile = File(context.cacheDir, "STITCH_${timestamp}.mp4")
            currentVideoFile = videoFile
            println("🎬 CAMERA: Video file will be: ${videoFile.absolutePath}")

            val outputOptions = FileOutputOptions.Builder(videoFile).build()

            recording = capture.output
                .prepareRecording(context, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            println("🎬 CAMERA: Recording STARTED - ${videoFile.name}")
                        }
                        is VideoRecordEvent.Finalize -> {
                            println("🎬 CAMERA INSTANCE $instanceId FINALIZE: Event received!")
                            println("🎬 CAMERA FINALIZE: hasError = ${event.hasError()}")
                            if (!event.hasError()) {
                                println("🎬 CAMERA: Recording completed successfully")
                                println("🎬 CAMERA: File: ${videoFile.absolutePath}")
                                println("🎬 CAMERA: Size: ${videoFile.length() / 1024}KB")
                                println("🎬 CAMERA: Duration: ${recordingDuration}s")

                                val recordedVideoData = createCompleteRecordedVideoData(
                                    recordingContext = recordingContext,
                                    parentVideo = parentVideo,
                                    videoFile = videoFile,
                                    duration = recordingDuration
                                )

                                println("🎬 CAMERA INSTANCE $instanceId: Calling onVideoRecorded callback NOW! [MODAL_CAMERAVIEW]")
                                onVideoRecorded(recordedVideoData)
                                println("🎬 CAMERA INSTANCE $instanceId: onVideoRecorded callback RETURNED! [MODAL_CAMERAVIEW]")

                            } else {
                                println("🎬 CAMERA: Recording failed - ${event.error}")
                                videoFile.delete()
                            }
                            recording = null
                            currentVideoFile = null
                        }
                    }
                }

            isRecording = true
            viewModel.startRecording()
            println("🎬 CAMERA: Recording initiated successfully")
        } ?: run {
            println("❌ CAMERA: videoCapture is NULL - cannot start recording!")
        }
    }

    // Stop recording function
    val stopRecording: () -> Unit = {
        println("🛑 CAMERA: Stop button pressed!")
        println("🛑 CAMERA: recording = $recording")
        recording?.stop()
        isRecording = false
        viewModel.stopRecording()
        println("🛑 CAMERA: Recording stop called, waiting for Finalize event...")
    }

    // Cancel recording function with cleanup
    val cancelRecording: () -> Unit = {
        println("❌ CAMERA: CANCEL button pressed (X button)!")
        recording?.stop()
        isRecording = false
        viewModel.stopRecording()

        currentVideoFile?.let { file ->
            if (file.exists()) {
                file.delete()
                println("❌ CAMERA: Cancelled recording file deleted")
            }
        }
        currentVideoFile = null

        println("❌ CAMERA: Camera cancelled - calling onCancel()")
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

            // Top controls (matches iOS — X left, flip+flash right)
            CameraTopControls(
                recordingContext = recordingContext,
                parentVideo = parentVideo,
                userTier = userTier,
                isRecording = isRecording,
                onCancel = cancelRecording,
                onFlipCamera = flipCamera,
                onToggleTorch = { isTorchOn = !isTorchOn },
                isTorchOn = isTorchOn,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .align(Alignment.TopCenter)
            )

            // Bottom controls (matches iOS — gallery | cinematic button | spacer)
            CameraBottomControls(
                isRecording = isRecording,
                recordingDuration = recordingDuration,
                maxDuration = if (isUnlimitedRecording) 0 else maxRecordingDuration.toInt(),
                isUnlimited = isUnlimitedRecording,
                progress = recordingProgress,
                hasSegments = isRecording,
                onStartRecording = startRecording,
                onStopRecording = stopRecording,
                onCancel = cancelRecording,
                onOpenGallery = handleGalleryRequest,
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

    // ✅ FIXED: Proper cleanup on dispose - UNBINDS CAMERA to remove recording indicator
    DisposableEffect(Unit) {
        onDispose {
            println("📷 CAMERA CLEANUP: DisposableEffect onDispose triggered")

            // 1. Stop any active recording first
            recording?.let { activeRecording ->
                println("📷 CAMERA CLEANUP: Stopping active recording...")
                try {
                    activeRecording.stop()
                } catch (e: Exception) {
                    println("📷 CAMERA CLEANUP: Error stopping recording - ${e.message}")
                }
            }
            recording = null

            // 2. Clean up any temporary file
            currentVideoFile?.let { file ->
                if (file.exists()) {
                    file.delete()
                    println("📷 CAMERA CLEANUP: Deleted temp file")
                }
            }
            currentVideoFile = null

            // 3. ✅ CRITICAL: Unbind camera to release camera/microphone resources
            // This is what removes the green recording indicator!
            cameraProvider?.let { provider ->
                println("📷 CAMERA CLEANUP: Unbinding all camera use cases...")
                try {
                    provider.unbindAll()
                    println("📷 CAMERA CLEANUP: ✅ Camera unbound successfully - indicator should disappear")
                } catch (e: Exception) {
                    println("📷 CAMERA CLEANUP: Error unbinding camera - ${e.message}")
                }
            }

            // 4. Clear references
            preview = null
            videoCapture = null
            cameraProvider = null

            // 5. Shutdown executor
            cameraExecutor.shutdown()

            println("📷 CAMERA CLEANUP: ✅ Complete - all resources released")
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
    isRecording: Boolean,
    onCancel: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleTorch: () -> Unit,
    isTorchOn: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        // Exit button (left) — matches iOS
        IconButton(
            onClick = onCancel,
            enabled = !isRecording,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Cancel",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Right side: Stacked flip + flashlight (matches iOS)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Flip camera
            IconButton(
                onClick = onFlipCamera,
                enabled = !isRecording,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = Icons.Filled.FlipCameraAndroid,
                    contentDescription = "Flip Camera",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Flashlight
            IconButton(
                onClick = onToggleTorch,
                enabled = !isRecording,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = if (isTorchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = "Flashlight",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// RecordingContextInfo removed — context info now shown inline or via badge if needed

// RecordingIndicator removed — duration display now in CameraBottomControls (matches iOS)

@Composable
private fun CameraBottomControls(
    isRecording: Boolean,
    recordingDuration: Int,
    maxDuration: Int,
    isUnlimited: Boolean,
    progress: Float,
    hasSegments: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancel: () -> Unit,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Duration display (matches iOS durationDisplay)
        if (isRecording || hasSegments) {
            val durationText = String.format("%d:%02d", recordingDuration / 60, recordingDuration % 60)
            val limitText = if (isUnlimited) "∞" else String.format("%d:%02d", maxDuration / 60, maxDuration % 60)
            val durationProgress = if (!isUnlimited && maxDuration > 0) recordingDuration.toFloat() / maxDuration.toFloat() else 0f

            Text(
                text = "$durationText / $limitText",
                color = when {
                    durationProgress >= 0.9f -> Color.Red
                    durationProgress >= 0.8f -> Color.Yellow
                    else -> Color.White
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Main controls row — gallery | record | spacer (matches iOS)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LEFT: Gallery button (matches iOS PhotosPicker)
            IconButton(
                onClick = onOpenGallery,
                enabled = !isRecording,
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = "Gallery",
                    tint = Color.White.copy(alpha = if (isRecording) 0.3f else 1f),
                    modifier = Modifier.size(24.dp)
                )
            }

            // CENTER: Cinematic Recording Button (matches iOS CinematicRecordingButton)
            CinematicRecordButton(
                isRecording = isRecording,
                progress = progress,
                isDisabled = !isUnlimited && progress >= 1f,
                onPress = if (isRecording) onStopRecording else onStartRecording
            )

            // RIGHT: Empty spacer to balance (matches iOS)
            Box(modifier = Modifier.size(50.dp))
        }
    }
}

/**
 * Cinematic Recording Button — matches iOS CinematicRecordingButton.swift
 * Progress ring around a gradient circle with state-based center icon
 */
@Composable
private fun CinematicRecordButton(
    isRecording: Boolean,
    progress: Float,
    isDisabled: Boolean,
    onPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonSize = 80.dp
    val ringWidth = 6.dp
    val totalSize = buttonSize + 12.dp

    // Progress color (matches iOS progressColor)
    val progressColor = when {
        progress >= 0.9f -> Color.Red
        progress >= 0.8f -> Color.Yellow
        else -> Color.White
    }

    // Gradient colors (matches iOS StitchColors)
    val primaryOrange = Color(0xFFFF6B35)
    val secondaryOrange = Color(0xFFFFA500)
    val recordingRed = Color(0xFFFF3B30)

    val scale by animateFloatAsState(
        targetValue = if (isRecording) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(totalSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        // Outer ring track
        Canvas(modifier = Modifier.size(totalSize)) {
            drawArc(
                color = Color.White.copy(alpha = 0.2f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = ringWidth.toPx(), cap = StrokeCap.Round)
            )
        }

        // Progress ring
        if (progress > 0f) {
            Canvas(modifier = Modifier.size(totalSize)) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(width = ringWidth.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // Main button circle with gradient
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(buttonSize)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = if (isRecording) {
                            listOf(recordingRed.copy(alpha = 0.8f), recordingRed.copy(alpha = 0.6f))
                        } else {
                            listOf(primaryOrange.copy(alpha = 0.7f), secondaryOrange)
                        }
                    )
                )
                .then(
                    if (!isDisabled) {
                        Modifier.pointerInput(isRecording) {
                            detectTapGestures(onPress = { onPress() })
                        }
                    } else Modifier
                )
        ) {
            // Center icon — matches iOS states
            if (isRecording) {
                // Recording: white rounded square (matches iOS RoundedRectangle)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White)
                )
            } else if (isDisabled) {
                // Tier limit reached
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Limit reached",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp)
                )
            } else {
                // Idle: record circle (matches iOS nested circles)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .then(
                            Modifier.drawBehind {
                                drawCircle(Color.White, style = Stroke(width = 3.dp.toPx()))
                            }
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(recordingRed.copy(alpha = 0.8f))
                    )
                }
            }
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