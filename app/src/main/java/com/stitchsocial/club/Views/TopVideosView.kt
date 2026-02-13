/*
 * TopVideosView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Full Screen Top Videos / Hype Leaderboard Browser
 * Port of: TopVideosView.swift
 * Features: 2-column video grid with rank badges, thumbnails, hype stats, video navigation
 */

package com.stitchsocial.club.Views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.LeaderboardVideo
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

@Composable
fun TopVideosView(
    onDismiss: () -> Unit,
    onVideoTap: (String) -> Unit
) {
    val scope = rememberCoroutineScope()

    var videos by remember { mutableStateOf<List<LeaderboardVideo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        try {
            val db = FirebaseFirestore.getInstance("stitchfin")
            val cutoff = Timestamp(Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L))
            val snapshot = db.collection("videos")
                .whereGreaterThan("createdAt", cutoff)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()

            videos = snapshot.documents
                .mapNotNull { doc -> LeaderboardVideo.fromFirestore(doc.id, doc.data ?: return@mapNotNull null) }
                .sortedByDescending { it.hypeCount }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load videos"
        }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        // Top bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                "\uD83D\uDD25 Top Videos",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
            ) {
                Text("Done", color = Color(0xFF9C27B0), fontWeight = FontWeight.SemiBold)
            }
        }

        // Subtitle
        Text(
            "Most hyped videos from the last 7 days",
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && videos.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFF9C27B0))
                        Text("Loading top videos...", fontSize = 14.sp, color = Color.Gray)
                    }
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(50.dp))
                        Text(errorMessage!!, fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center)
                        TextButton(onClick = {
                            scope.launch {
                                isLoading = true; errorMessage = null
                                try {
                                    val db = FirebaseFirestore.getInstance("stitchfin")
                                    val cutoff = Timestamp(Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L))
                                    val snapshot = db.collection("videos")
                                        .whereGreaterThan("createdAt", cutoff)
                                        .orderBy("createdAt", Query.Direction.DESCENDING)
                                        .limit(50)
                                        .get()
                                        .await()
                                    videos = snapshot.documents
                                        .mapNotNull { doc -> LeaderboardVideo.fromFirestore(doc.id, doc.data ?: return@mapNotNull null) }
                                        .sortedByDescending { it.hypeCount }
                                } catch (e: Exception) { errorMessage = e.message }
                                isLoading = false
                            }
                        }) { Text("Retry", color = Color(0xFF9C27B0)) }
                    }
                }
                videos.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(Icons.Default.Whatshot, null, tint = Color.Gray, modifier = Modifier.size(50.dp))
                        Text("No trending videos yet", fontSize = 16.sp, color = Color.Gray)
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(videos, key = { _, v -> v.id }) { index, video ->
                            TopVideoCard(
                                video = video,
                                rank = index + 1,
                                onTap = { onVideoTap(video.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===== VIDEO CARD =====

@Composable
private fun TopVideoCard(
    video: LeaderboardVideo,
    rank: Int,
    onTap: () -> Unit
) {
    val rankColor = when (rank) {
        1 -> Color.Yellow
        2 -> Color.Gray
        3 -> Color(0xFFFF9800)
        else -> Color(0xFF9C27B0)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .clickable(onClick = onTap)
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Gray.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                if (!video.thumbnailURL.isNullOrEmpty()) {
                    AsyncImage(
                        model = video.thumbnailURL,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            // Creator name
            Text(
                video.creatorName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // Stats row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Icon(
                    Icons.Default.Whatshot,
                    null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(10.dp)
                )
                Text(
                    formatVideoCount(video.hypeCount),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Spacer(Modifier.width(8.dp))
                Text(video.temperatureEmoji, fontSize = 12.sp)
            }
        }

        // Rank badge overlay (top-left)
        Box(
            modifier = Modifier
                .padding(8.dp)
                .size(32.dp)
                .shadow(8.dp, CircleShape)
                .background(rankColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "#$rank",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

private fun formatVideoCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}