/*
 * CollectionRowView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 6: View — Reusable collection card with 3 display styles
 * Mirror of Swift CollectionRowView exactly.
 * Dependencies: CollectionRowViewModel, VideoCollection, Coil
 *
 * Styles:
 *   CARD    — Full width: cover image (16:9), segment preview strip, title, creator, stats
 *   COMPACT — Horizontal list row: 80×60 thumbnail, title, summary, hype/view counts
 *   GRID    — Square 160dp thumbnail: duration badge, segment count badge, title, time ago
 *
 * Usage:
 *   CollectionRowView(collection, CollectionRowStyle.CARD, onTap = { ... })
 *   CollectionRowView(collection, CollectionRowStyle.GRID, onTap = { ... })
 *
 * CACHING:
 *   - Coil caches cover + segment thumbnails in its disk/memory cache automatically.
 *   - ViewModel.loadSegmentPreviews() served from CollectionService.segmentCache (10-min TTL).
 *   - No reads on re-render — all state in ViewModel.
 *
 * BATCHING:
 *   - Preview strip loads up to 4 thumbnails from a single CollectionService call.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.services.CollectionService
import com.stitchsocial.club.viewmodels.CollectionRowViewModel
import com.stitchsocial.club.viewmodels.CollectionRowViewModelFactory
import com.stitchsocial.club.viewmodels.SegmentPreview

// ─────────────────────────────────────────────
// MARK: - Display Style Enum
// ─────────────────────────────────────────────

enum class CollectionRowStyle {
    CARD,       // Full width with cover image + preview strip
    COMPACT,    // Horizontal list row
    GRID        // Square thumbnail for 2-column grid
}

// ─────────────────────────────────────────────
// MARK: - CollectionRowView
// ─────────────────────────────────────────────

@Composable
fun CollectionRowView(
    collection: VideoCollection,
    style: CollectionRowStyle = CollectionRowStyle.CARD,
    onTap: () -> Unit,
    onCreatorTap: (() -> Unit)? = null,
    collectionService: CollectionService = remember { CollectionService() }
) {
    val vm: CollectionRowViewModel = viewModel(
        key = collection.id,
        factory = CollectionRowViewModelFactory(collection, collectionService)
    )

    val segmentPreviews by vm.segmentPreviews.collectAsState()

    // Load previews on first composition — 0 reads if cache warm
    LaunchedEffect(collection.id) {
        vm.loadSegmentPreviews()
    }

    // Press scale effect (matches Swift CollectionCardButtonStyle)
    var isPressed by remember { mutableStateOf(false) }
    val scale = if (isPressed) 0.98f else 1.0f

    Box(
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true)
            ) {
                isPressed = true
                onTap()
            }
    ) {
        when (style) {
            CollectionRowStyle.CARD -> CardLayout(vm, segmentPreviews, onCreatorTap)
            CollectionRowStyle.COMPACT -> CompactLayout(vm)
            CollectionRowStyle.GRID -> GridLayout(vm)
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - CARD Layout
// ─────────────────────────────────────────────

@Composable
private fun CardLayout(
    vm: CollectionRowViewModel,
    segmentPreviews: List<SegmentPreview>,
    onCreatorTap: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E), shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Cover image (16:9)
        CoverImageSection(vm, height = 180)

        // Segment preview strip
        if (segmentPreviews.isNotEmpty()) {
            SegmentPreviewStrip(vm, segmentPreviews)
        }

        // Info section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Title
            Text(
                text = vm.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Creator + summary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val creatorText = @Composable {
                    Text(
                        text = vm.creatorDisplayName,
                        fontSize = 13.sp,
                        color = Color(0xFF0A84FF),
                        maxLines = 1
                    )
                }
                if (onCreatorTap != null) {
                    Box(modifier = Modifier.clickable { onCreatorTap() }) { creatorText() }
                } else {
                    creatorText()
                }
                Text("•", color = Color.Gray, fontSize = 12.sp)
                Text(
                    text = vm.summaryText,
                    fontSize = 13.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Engagement row
            EngagementRow(vm)
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - COMPACT Layout
// ─────────────────────────────────────────────

@Composable
private fun CompactLayout(vm: CollectionRowViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2C2C2E), shape = RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Compact thumbnail 80×60
        Box(
            modifier = Modifier
                .width(80.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF3A3A3C))
        ) {
            val url = vm.coverImageURL
            if (!url.isNullOrEmpty()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.align(Alignment.Center).size(24.dp)
                )
            }
            // Segment count badge
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(vm.segmentCountText, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        // Info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(vm.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(vm.summaryText, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(12.dp))
                    Text(vm.hypeCountText, fontSize = 11.sp, color = Color(0xFFFF9F0A))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                    Text(vm.viewCountText, fontSize = 11.sp, color = Color.Gray)
                }
            }
        }

        Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color(0xFF0A84FF), modifier = Modifier.size(28.dp))
    }
}

// ─────────────────────────────────────────────
// MARK: - GRID Layout
// ─────────────────────────────────────────────

@Composable
private fun GridLayout(vm: CollectionRowViewModel) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Square thumbnail 160dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF3A3A3C))
        ) {
            val url = vm.coverImageURL
            if (!url.isNullOrEmpty()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                )
            }

            // Duration badge (bottom-left)
            DurationBadge(vm.durationText, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))

            // Segment count badge (top-right)
            SegmentCountBadge(vm.segmentCountText, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))

            // Status badge (top-left)
            val statusText = vm.statusBadgeText
            if (statusText != null) {
                StatusBadge(statusText, vm.statusBadgeColor, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
            }
        }

        // Title
        Text(
            text = vm.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(12.dp))
                Text(vm.hypeCountText, fontSize = 11.sp, color = Color(0xFFFF9F0A))
            }
            Text(vm.timeAgoText, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Shared Sub-components
// ─────────────────────────────────────────────

@Composable
private fun CoverImageSection(vm: CollectionRowViewModel, height: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(
                Brush.linearGradient(listOf(Color(0xFF4A3080), Color(0xFF1A3060)))
            )
    ) {
        val url = vm.coverImageURL
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
                Text("Collection", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.6f))
            }
        }

        // Duration badge bottom-right
        DurationBadge(vm.durationText, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))

        // Status badge top-left
        val statusText = vm.statusBadgeText
        if (statusText != null) {
            StatusBadge(statusText, vm.statusBadgeColor, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        }
    }
}

@Composable
private fun SegmentPreviewStrip(vm: CollectionRowViewModel, previews: List<SegmentPreview>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(previews, key = { it.id }) { preview ->
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF3A3A3C))
            ) {
                if (!preview.thumbnailURL.isNullOrEmpty()) {
                    AsyncImage(
                        model = preview.thumbnailURL,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.align(Alignment.Center).size(20.dp)
                    )
                }
                // Part label overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(3.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(preview.partLabel, fontSize = 8.sp, color = Color.White)
                }
                // Duration overlay
                preview.formattedDuration?.let { dur ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 3.dp, vertical = 2.dp)
                    ) {
                        Text(dur, fontSize = 8.sp, color = Color.White)
                    }
                }
            }
        }

        // "+N more" indicator
        vm.additionalSegmentsText?.let { more ->
            item {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3A3A3C)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(more, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun EngagementRow(vm: CollectionRowViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(14.dp))
            Text(vm.hypeCountText, fontSize = 12.sp, color = Color(0xFFFF9F0A))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Visibility, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
            Text(vm.viewCountText, fontSize = 12.sp, color = Color.Gray)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
            Text(vm.replyCountText, fontSize = 12.sp, color = Color.Gray)
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(vm.timeAgoText, fontSize = 11.sp, color = Color.Gray)
    }
}

// ─────────────────────────────────────────────
// MARK: - Badge Components
// ─────────────────────────────────────────────

@Composable
private fun DurationBadge(duration: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
        Text(duration, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

@Composable
private fun SegmentCountBadge(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

@Composable
private fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}