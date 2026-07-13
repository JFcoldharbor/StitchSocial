/*
 * DraftsScreen.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Saved Drafts grid
 * Dependencies: LocalDraftManager, VideoEditState
 * Mirrors iOS DraftsSheetView: a 9:16 grid of persisted recordings the user
 * backed out of. Selecting one reopens it in VIDEO_REVIEW.
 */

package com.stitchsocial.club

import com.stitchsocial.club.ui.theme.StitchColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Grid of saved drafts. [onSelectDraft] fires with the tapped draft so the host
 * can reopen it in the review/compose flow; [onBack] returns to the camera.
 */
@Composable
fun DraftsScreen(
    onSelectDraft: (VideoEditState) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val manager = remember { LocalDraftManager.getInstance(context) }
    val drafts by manager.drafts.collectAsState()
    val scope = rememberCoroutineScope()

    // Refresh from disk on open (drops any whose bytes went missing).
    LaunchedEffect(Unit) { manager.loadDrafts() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = "Drafts",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (drafts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.VideoLibrary,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No saved drafts",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 15.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(drafts, key = { it.draftId }) { draft ->
                    DraftCell(
                        draft = draft,
                        onClick = { onSelectDraft(draft) },
                        onDelete = { scope.launch { manager.deleteDraft(draft.draftId) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftCell(
    draft: VideoEditState,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val item = DraftListItem.from(draft)
    Box(
        modifier = Modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1A1A1A))
            .clickable { onClick() }
    ) {
        if (item.thumbnailUri != null) {
            AsyncImage(
                model = item.thumbnailUri,
                contentDescription = "Draft",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Duration chip (bottom-left)
        Text(
            text = item.formattedDuration,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )

        // Relative date (top-left)
        Text(
            text = item.formattedDate,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )

        // Delete (top-right)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { onDelete() }
                .padding(4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete draft",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
