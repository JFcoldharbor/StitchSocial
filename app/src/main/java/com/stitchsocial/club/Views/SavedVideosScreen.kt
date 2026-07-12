//
//  SavedVideosScreen.kt
//  StitchSocial
//
//  The user's private save-for-later grid. Mirrors iOS SavedVideosView.swift.
//  Renders from the denormalized snapshots in users/{uid}/savedVideos (no
//  video-doc reads); fetches the full video only on tap-to-play. Presented
//  full-screen from the own-profile Saved button, SettingsView-style.
//

package com.stitchsocial.club.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.SaveService
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.ui.theme.AppTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SavedVideosScreen(
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val videoService = remember { VideoServiceImpl() }

    var rows by remember { mutableStateOf<List<SaveService.SavedVideoRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }
    var playingVideo by remember { mutableStateOf<CoreVideoMetadata?>(null) }
    var removeTarget by remember { mutableStateOf<SaveService.SavedVideoRow?>(null) }

    val savedIds by SaveService.shared.savedVideoIds.collectAsStateWithLifecycle()

    // Initial page
    LaunchedEffect(Unit) {
        SaveService.shared.fetchSavedRows().onSuccess {
            rows = it
            reachedEnd = it.size < SaveService.PAGE_SIZE
        }
        isLoading = false
    }

    // Live-sync when a video is unsaved from the player overlay
    LaunchedEffect(savedIds) {
        rows = rows.filter { savedIds.contains(it.id) }
    }

    fun loadMore() {
        val cursor = rows.lastOrNull()?.savedAt ?: return
        if (isLoadingMore || reachedEnd) return
        isLoadingMore = true
        scope.launch {
            SaveService.shared.fetchSavedRows(after = cursor).onSuccess { more ->
                rows = rows + more
                if (more.size < SaveService.PAGE_SIZE) reachedEnd = true
            }
            isLoadingMore = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Saved",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = AppTheme.colors.textPrimary
                    )
                }
            }

            when {
                isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppTheme.colors.textSecondary)
                }

                rows.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            tint = AppTheme.colors.textSecondary.copy(alpha = 0.5f),
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Nothing saved yet",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Tap ⋯ on any video and choose Save",
                            fontSize = 13.sp,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                        if (index >= rows.size - 6) loadMore()

                        Box(
                            modifier = Modifier
                                .aspectRatio(9f / 16f)
                                .combinedClickable(
                                    onClick = {
                                        scope.launch {
                                            videoService.getVideoById(row.id)?.let {
                                                playingVideo = it
                                            }
                                        }
                                    },
                                    onLongClick = { removeTarget = row }
                                )
                        ) {
                            AsyncImage(
                                model = row.thumbnailURL,
                                contentDescription = row.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0.5f to Color.Transparent,
                                            1f to Color.Black.copy(alpha = 0.55f)
                                        )
                                    )
                            )
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp)
                            ) {
                                Text(
                                    row.title,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1
                                )
                                Text(
                                    "@${row.creatorName}",
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.7f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // Tap-to-play: minimal fullscreen player with a close button.
        playingVideo?.let { video ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                key(video.id) {
                    VideoPlayerComposable(
                        video = video,
                        isActive = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                IconButton(
                    onClick = { playingVideo = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }

        // Long-press remove confirmation
        removeTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { removeTarget = null },
                title = { Text("Remove from Saved?") },
                text = { Text(target.title.ifBlank { "This video" }) },
                confirmButton = {
                    TextButton(onClick = {
                        removeTarget = null
                        scope.launch { SaveService.shared.unsave(target.id) }
                    }) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
                }
            )
        }
    }
}
