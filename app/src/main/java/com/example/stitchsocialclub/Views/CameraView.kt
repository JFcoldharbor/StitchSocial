/*
 * CameraView.kt - REAL CAMERA IMPLEMENTATION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Real CameraX Camera with Contextual Recording
 * Dependencies: CameraX, Foundation Layer, RecordingContext
 * Features: Real camera preview, contextual video recording, camera switching
 */

package com.example.stitchsocialclub.views

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.stitchsocialclub.services.VideoServiceImpl.SimpleVideoMetadata
import com.example.stitchsocialclub.Camera.*
import java.io.File
import java.util.concurrent.Executors

/**
 * CameraView with real CameraX integration and contextual recording
 */
@Composable
fun CameraView(
    recordingContext: RecordingContext = RecordingContext.NewThread,
    onVideoCreated: (SimpleVideoMetadata) -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camera state
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Permission state
    var hasCameraPermission by remember { mutableStateOf(false) }
    var hasAudioPermission by remember { mutableStateOf(false) }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: false
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: false
    }

    // Check permissions on start
    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        hasAudioPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission || !hasAudioPermission) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ))
        }
    }

    // Setup camera when permissions are granted
    LaunchedEffect(hasCameraPermission, hasAudioPermission, lensFacing) {
        if (hasCameraPermission && hasAudioPermission) {
            isLoading = true
            setupCamera(context, lifecycleOwner, lensFacing) { provider, prev, vidCap, pView ->
                cameraProvider = provider
                preview = prev
                videoCapture = vidCap
                previewView = pView
                isLoading = false
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera preview or permission/loading screens
        when {
            !hasCameraPermission || !hasAudioPermission -> {
                // Permission request screen
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Camera,
                            contentDescription = "Camera",
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "Camera and microphone access needed",
                            color = Color.White,
                            fontSize = 18.sp
                        )
                        Button(
                            onClick = {
                                permissionLauncher.launch(arrayOf(
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.RECORD_AUDIO
                                ))
                            }
                        ) {
                            Text("Grant Permissions")
                        }
                    }
                }
            }

            isLoading -> {
                // Loading screen
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Loading camera...",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            else -> {
                // Real camera preview
                previewView?.let { pView ->
                    AndroidView(
                        factory = { pView },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Top controls (only show if camera is ready)
        if (!isLoading && hasCameraPermission && hasAudioPermission) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Contextual recording badge
                Surface(
                    shape = CircleShape,
                    color = recordingContext.getContextColor()
                ) {
                    Text(
                        text = recordingContext.getBadgeText(),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // Recording indicator
                if (isRecording) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color.Red, CircleShape)
                        )
                        Text(
                            text = "${recordingDuration}s",
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                // Close button
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            // Bottom controls
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery button
                IconButton(
                    onClick = { println("Gallery clicked") }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Gallery",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Gallery",
                            color = Color.White,
                            fontSize = 10.sp
                        )
                    }
                }

                // Recording button
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clickable {
                            if (isRecording) {
                                // Stop recording
                                stopRecording(recording, recordingContext) { success, contextualTitle ->
                                    isRecording = false
                                    recordingDuration = 0
                                    recording = null

                                    if (success) {
                                        val recordedVideo = SimpleVideoMetadata(
                                            id = "recorded_${System.currentTimeMillis()}",
                                            title = contextualTitle ?: "New Recording",
                                            creatorName = "CurrentUser",
                                            videoURL = "recorded_video.mp4"
                                        )
                                        onVideoCreated(recordedVideo)
                                    }
                                }
                            } else {
                                // Start recording
                                startRecording(context, videoCapture, recordingContext) { activeRecording ->
                                    recording = activeRecording
                                    isRecording = true
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Outer ring
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(Color.Transparent, CircleShape)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(if (isRecording) 32.dp else 60.dp)
                                .background(
                                    Color.Red,
                                    if (isRecording) RoundedCornerShape(8.dp) else CircleShape
                                )
                        )
                    }
                }

                // Camera flip button
                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Flip",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Flip",
                            color = Color.White,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Camera Setup Functions

private fun setupCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    lensFacing: Int,
    onCameraReady: (ProcessCameraProvider, Preview, VideoCapture<Recorder>, PreviewView) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            val preview = Preview.Builder().build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            val videoCapture = VideoCapture.withOutput(recorder)

            val previewView = PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            preview.setSurfaceProvider(previewView.surfaceProvider)

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                videoCapture
            )

            onCameraReady(cameraProvider, preview, videoCapture, previewView)

        } catch (e: Exception) {
            println("CAMERA: Setup failed - ${e.message}")
        }
    }, ContextCompat.getMainExecutor(context))
}

// MARK: - Recording Functions with Context

private fun getContextualFileName(context: RecordingContext): String {
    val timestamp = System.currentTimeMillis()
    return when (context) {
        is RecordingContext.NewThread -> "stitch_thread_${timestamp}.mp4"
        is RecordingContext.StitchToThread -> "stitch_reply_${context.threadId}_${timestamp}.mp4"
        is RecordingContext.ReplyToVideo -> "stitch_reply_${context.videoId}_${timestamp}.mp4"
        is RecordingContext.ContinueThread -> "stitch_continue_${context.threadId}_${timestamp}.mp4"
    }
}

private fun startRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>?,
    recordingContext: RecordingContext,
    onRecordingStarted: (Recording) -> Unit
) {
    val videoCapture = videoCapture ?: return

    val outputFile = File(
        context.cacheDir,
        getContextualFileName(recordingContext)
    )

    val outputOptions = FileOutputOptions.Builder(outputFile).build()

    val recording = videoCapture.output
        .prepareRecording(context, outputOptions)
        .withAudioEnabled()
        .start(Executors.newSingleThreadExecutor()) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    println("RECORDING: Started ${getContextualFileName(recordingContext)}")
                }
                is VideoRecordEvent.Finalize -> {
                    if (!event.hasError()) {
                        println("RECORDING: Saved to ${outputFile.absolutePath}")
                    } else {
                        println("RECORDING: Error - ${event.error}")
                    }
                }
            }
        }

    onRecordingStarted(recording)
}

private fun stopRecording(
    recording: Recording?,
    recordingContext: RecordingContext,
    onComplete: (Boolean, String?) -> Unit
) {
    recording?.stop()

    val contextualTitle = when (recordingContext) {
        is RecordingContext.NewThread -> "New Thread Recording"
        is RecordingContext.StitchToThread -> "Stitch to ${recordingContext.threadInfo.creatorName}"
        is RecordingContext.ReplyToVideo -> "Reply to ${recordingContext.videoInfo.creatorName}"
        is RecordingContext.ContinueThread -> "Continue ${recordingContext.threadInfo.creatorName}'s thread"
    }

    onComplete(true, contextualTitle)
}