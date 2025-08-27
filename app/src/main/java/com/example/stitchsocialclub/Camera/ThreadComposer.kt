/*
 * ThreadComposer.kt - MINIMAL VERSION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Basic Video Metadata Input
 * Dependencies: Foundation Layer only
 * Features: Simple title/description input, basic upload simulation
 */

package com.example.stitchsocialclub.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stitchsocialclub.services.VideoServiceImpl.SimpleVideoMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Minimal ThreadComposer - Basic metadata input without complex dependencies
 * Perfect for testing compilation and basic functionality
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadComposer(
    videoMetadata: SimpleVideoMetadata,
    onVideoCreated: (SimpleVideoMetadata) -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // Form state
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf(0f) }

    // Configuration
    val maxTitleLength = 100
    val maxDescriptionLength = 300

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isUploading) {
            // Upload progress screen
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    CircularProgressIndicator(
                        progress = uploadProgress,
                        color = Color(0xFFFF6B35),
                        strokeWidth = 6.dp,
                        modifier = Modifier.size(80.dp)
                    )

                    Text(
                        text = "Uploading Video...",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "${(uploadProgress * 100).toInt()}% complete",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp
                    )
                }
            }
        } else {
            // Main composer interface
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = Color.White
                        )
                    }

                    Text(
                        text = "New Thread",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Button(
                        onClick = {
                            if (title.isNotBlank()) {
                                scope.launch {
                                    // Mock upload process
                                    isUploading = true
                                    for (i in 1..10) {
                                        delay(200)
                                        uploadProgress = i / 10f
                                    }

                                    // Complete upload
                                    val completedVideo = SimpleVideoMetadata(
                                        id = "uploaded_${System.currentTimeMillis()}",
                                        title = title,
                                        creatorName = videoMetadata.creatorName,
                                        videoURL = videoMetadata.videoURL
                                    )
                                    onVideoCreated(completedVideo)
                                }
                            }
                        },
                        enabled = title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF6B35)
                        )
                    ) {
                        Text("Upload", color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Video preview placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🎬",
                            fontSize = 48.sp
                        )
                        Text(
                            text = "Video Preview",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Text(
                            text = videoMetadata.id,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Title input
                OutlinedTextField(
                    value = title,
                    onValueChange = { newTitle ->
                        if (newTitle.length <= maxTitleLength) {
                            title = newTitle
                        }
                    },
                    label = { Text("Title") },
                    placeholder = { Text("What's your thread about?") },
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (title.isBlank()) "Title is required" else "",
                                color = if (title.isBlank()) MaterialTheme.colorScheme.error else Color.Transparent
                            )
                            Text(
                                text = "${title.length}/$maxTitleLength",
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    },
                    isError = title.isBlank(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFF6B35),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                        focusedLabelColor = Color(0xFFFF6B35),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.7f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Description input
                OutlinedTextField(
                    value = description,
                    onValueChange = { newDesc ->
                        if (newDesc.length <= maxDescriptionLength) {
                            description = newDesc
                        }
                    },
                    label = { Text("Description (Optional)") },
                    placeholder = { Text("Tell viewers more about your thread...") },
                    supportingText = {
                        Text(
                            text = "${description.length}/$maxDescriptionLength",
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFF6B35),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                        focusedLabelColor = Color(0xFFFF6B35),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.7f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Simple hashtag suggestions
                Text(
                    text = "Suggested hashtags:",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("thread", "original", "community").forEach { hashtag ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = "#$hashtag",
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}