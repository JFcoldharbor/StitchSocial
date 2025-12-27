/*
 * ThreadComposer.kt - FIXED ALL COMPILATION ERRORS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * CRITICAL FIXES:
 * ✅ Uses VideoAnalysisResult from services package (correct properties)
 * ✅ All property references resolve: title, description, hashtags
 * ✅ Continuously observes AI analysis results
 * ✅ Video preview at 50% width
 */

package com.stitchsocial.club.camera

import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

// FIXED IMPORTS - Use services package VideoAnalysisResult
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.services.VideoAnalysisResult
import com.stitchsocial.club.coordination.VideoCoordinator

/**
 * ThreadComposer - Complete video editing UI after parallel processing
 * Uses VideoAnalysisResult from services package with correct properties
 */
@Composable
fun ThreadComposer(
    recordedVideoURL: String,
    recordingContext: RecordingContext = RecordingContext.NewThread,
    aiResult: VideoAnalysisResult? = null,
    videoCoordinator: VideoCoordinator,
    onVideoCreated: (CoreVideoMetadata) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // MARK: - State
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var hashtags by remember { mutableStateOf<List<String>>(emptyList()) }
    var isCreating by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Hashtag input state
    var newHashtagText by remember { mutableStateOf("") }

    // AI Analysis state
    var isAnalyzing by remember { mutableStateOf(false) }
    var hasAnalyzed by remember { mutableStateOf(false) }
    var analysisResult by remember { mutableStateOf(aiResult) }

    // Video player state
    var videoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    // MARK: - Constants
    val maxTitleLength = 100
    val maxDescriptionLength = 300
    val maxHashtags = 10

    // Computed properties
    val canPost = title.isNotBlank() && !isCreating
    val titleCharacterCount = title.length
    val descriptionCharacterCount = description.length

    // Continuously observe AI results from VideoCoordinator
    val aiResultFromCoordinator by videoCoordinator.lastAIResult.collectAsState()

    // MARK: - Functions
    val setupVideoPlayer: () -> Unit = {
        try {
            videoPlayer?.release()
            val newPlayer = ExoPlayer.Builder(context).build().apply {
                val mediaItem = MediaItem.fromUri(recordedVideoURL)
                setMediaItem(mediaItem)
                prepare()
            }
            videoPlayer = newPlayer
            println("THREAD COMPOSER: Video player setup complete")
        } catch (e: Exception) {
            println("THREAD COMPOSER: Video player setup failed: ${e.message}")
        }
    }

    val cleanupVideoPlayer: () -> Unit = {
        videoPlayer?.release()
        videoPlayer = null
        println("THREAD COMPOSER: Video player cleaned up")
    }

    val togglePlayback: () -> Unit = {
        videoPlayer?.let { player ->
            if (isPlaying) {
                player.pause()
                isPlaying = false
            } else {
                player.play()
                isPlaying = true
            }
        }
    }

    val addHashtag: (String) -> Unit = { tag ->
        val cleanTag = tag.trim().removePrefix("#").lowercase()
        if (cleanTag.isNotBlank() &&
            hashtags.size < maxHashtags &&
            !hashtags.contains(cleanTag)) {
            hashtags = hashtags + cleanTag
            newHashtagText = ""
        }
    }

    val removeHashtag: (String) -> Unit = { tag ->
        hashtags = hashtags.filter { it != tag }
    }

    val createVideo: () -> Unit = {
        scope.launch {
            isCreating = true

            try {
                val finalVideo = videoCoordinator.completeVideoCreation(
                    userTitle = title,
                    userDescription = description,
                    userHashtags = hashtags
                )

                println("THREAD COMPOSER: Upload complete - calling onVideoCreated")
                onVideoCreated(finalVideo)

            } catch (e: Exception) {
                println("THREAD COMPOSER: Upload failed: ${e.message}")
                errorMessage = "Failed to upload video: ${e.message}"
                showError = true
                isCreating = false
            }
        }
    }

    // MARK: - Effects

    // Setup video player once
    LaunchedEffect(recordedVideoURL) {
        setupVideoPlayer()
    }

    // FIXED: Observe AI results and use correct property names
    LaunchedEffect(aiResultFromCoordinator) {
        aiResultFromCoordinator?.let { result ->
            // Only update if we don't have content yet or still have mock content
            if (title.isBlank() || title == "AI Generated Title" || !hasAnalyzed) {
                title = result.title
                description = result.description
                hashtags = result.hashtags
                analysisResult = result
                hasAnalyzed = true
                isAnalyzing = false

                println("THREAD COMPOSER: Updated with REAL AI results:")
                println("  Title: '${result.title}'")
                println("  Description: '${result.description}'")
                println("  Hashtags: ${result.hashtags}")
            }
        }
    }

    // Handle passed aiResult parameter (immediate pre-fill)
    LaunchedEffect(aiResult) {
        aiResult?.let { result ->
            if (title.isBlank()) {
                title = result.title
                description = result.description
                hashtags = result.hashtags
                analysisResult = result
                hasAnalyzed = true
                println("THREAD COMPOSER: Pre-filled with passed AI results")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cleanupVideoPlayer()
        }
    }

    // MARK: - Main UI
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Gradient background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black,
                            Color(0xFF1a1a2e)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // MARK: - Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCancel
                ) {
                    Text(
                        text = "Cancel",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp
                    )
                }

                Text(
                    text = when (recordingContext) {
                        RecordingContext.NewThread -> "New Thread"
                        else -> "Reply"
                    },
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = createVideo,
                    enabled = canPost,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (canPost) Color(0xFF00ff88) else Color.Gray,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.height(36.dp)
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "Post",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // MARK: - Content Row (Video + Text Fields)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Video Preview - 50% width
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    // Video Player
                    AndroidView(
                        factory = { context ->
                            PlayerView(context).apply {
                                player = videoPlayer
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Play/Pause Overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { togglePlayback() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!isPlaying) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                }

                // Text Fields Column
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Title Field
                    OutlinedTextField(
                        value = title,
                        onValueChange = { if (it.length <= maxTitleLength) title = it },
                        label = {
                            Text(
                                "Title",
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        },
                        supportingText = {
                            Text(
                                "$titleCharacterCount/$maxTitleLength",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00ff88),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                    )

                    // Description Field
                    OutlinedTextField(
                        value = description,
                        onValueChange = { if (it.length <= maxDescriptionLength) description = it },
                        label = {
                            Text(
                                "Description",
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        },
                        supportingText = {
                            Text(
                                "$descriptionCharacterCount/$maxDescriptionLength",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00ff88),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { keyboardController?.hide() }
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // MARK: - Hashtags Section
            Column {
                Text(
                    text = "Hashtags (${hashtags.size}/$maxHashtags)",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Hashtag Input
                OutlinedTextField(
                    value = newHashtagText,
                    onValueChange = { newHashtagText = it },
                    label = {
                        Text(
                            "Add hashtag",
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    },
                    leadingIcon = {
                        Text(
                            "#",
                            color = Color(0xFF00ff88),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00ff88),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (newHashtagText.isNotBlank()) {
                                addHashtag(newHashtagText)
                            }
                            keyboardController?.hide()
                        }
                    ),
                    trailingIcon = {
                        if (newHashtagText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    if (newHashtagText.isNotBlank()) {
                                        addHashtag(newHashtagText)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add hashtag",
                                    tint = Color(0xFF00ff88)
                                )
                            }
                        }
                    }
                )

                // Hashtag Display
                if (hashtags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(hashtags) { hashtag ->
                            Surface(
                                color = Color(0xFF00ff88).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color(0xFF00ff88).copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "#$hashtag",
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )

                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove hashtag",
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { removeHashtag(hashtag) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // AI Analysis Status
            if (isAnalyzing) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color(0xFF00ff88),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "AI is analyzing your video...",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Error Display
        if (showError) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.BottomCenter),
                color = Color.Red.copy(alpha = 0.9f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = Color.White
                    )

                    Text(
                        text = errorMessage,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = { showError = false }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}