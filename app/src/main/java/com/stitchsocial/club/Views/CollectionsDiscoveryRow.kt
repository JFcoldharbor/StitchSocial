/*
 * CollectionsDiscoveryRow.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 6: View — Collections horizontal discovery lane
 * Mirror of Swift collectionLaneView + CollectionThumbnailCard
 * Dependencies: VideoCollection, CollectionService, Coil, Compose
 *
 * Used in DiscoveryView when category == COLLECTIONS, PODCASTS, or FILMS.
 * Each card shows cover image, title, creator, segment count, duration.
 * Tap → CollectionPlayerView fullscreen.
 *
 * CACHING: CollectionService handles TTL. This view is stateless — just renders
 *          what the ViewModel provides.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.CollectionContentType
import com.stitchsocial.club.foundation.VideoCollection

// ─────────────────────────────────────────────
// MARK: - Collections Lane (top-level composable used in DiscoveryView)
// ─────────────────────────────────────────────

/**
 * Horizontal scrolling row of collection cards.
 * Call with a pre-loaded list — loading/empty states handled by caller.
 *
 * @param collections   Pre-fetched list from CollectionService
 * @param title         Section header ("Collections", "Podcasts", "Films")
 * @param userID        Current user — passed to CollectionPlayerView for progress
 * @param onCollectionTap  Open the player. Caller typically sets a fullscreen state.
 */
@Composable
fun CollectionsDiscoveryRow(
    collections: List<VideoCollection>,
    title: String = "Collections",
    userID: String,
    onCollectionTap: (VideoCollection) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            if (collections.isNotEmpty()) {
                Text(
                    text = "${collections.size} available",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }

        // ── Cards ──
        when {
            collections.isEmpty() -> CollectionsEmptyState(title)
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(collections, key = { it.id }) { collection ->
                    CollectionThumbnailCard(
                        collection = collection,
                        onTap = { onCollectionTap(collection) }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Thumbnail Card
// ─────────────────────────────────────────────

@Composable
fun CollectionThumbnailCard(
    collection: VideoCollection,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardWidth = 160.dp
    val cardHeight = 220.dp

    Box(
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = contentTypeAccent(collection.contentType).copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onTap() }
    ) {
        // ── Cover image ──
        if (!collection.coverImageURL.isNullOrBlank()) {
            AsyncImage(
                model = collection.coverImageURL,
                contentDescription = collection.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Placeholder gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF2C2C2E), Color(0xFF1C1C1E))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = contentTypeIcon(collection.contentType),
                    contentDescription = null,
                    tint = contentTypeAccent(collection.contentType).copy(alpha = 0.6f),
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        // ── Bottom gradient overlay ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))
                    )
                )
        )

        // ── Content type badge (top-left) ──
        ContentTypeBadge(
            contentType = collection.contentType,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        )

        // ── Play icon (top-right) ──
        Icon(
            imageVector = Icons.Default.PlayCircle,
            contentDescription = "Play",
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(24.dp)
        )

        // ── Info ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = collection.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
            Text(
                text = collection.creatorName,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Segment count + duration row
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillChip(text = collection.segmentCountLabel)
                PillChip(text = collection.formattedDuration)
            }
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Content Type Badge
// ─────────────────────────────────────────────

@Composable
private fun ContentTypeBadge(contentType: CollectionContentType, modifier: Modifier = Modifier) {
    val accent = contentTypeAccent(contentType)
    val label = when (contentType) {
        CollectionContentType.PODCAST -> "PODCAST"
        CollectionContentType.FILM    -> "FILM"
        CollectionContentType.SERIES  -> "SERIES"
        CollectionContentType.COURSE  -> "COURSE"
        CollectionContentType.EVENT   -> "EVENT"
        else                          -> return   // No badge for GENERAL
    }

    Surface(
        modifier = modifier,
        color = accent.copy(alpha = 0.85f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

// ─────────────────────────────────────────────
// MARK: - Pill Chip
// ─────────────────────────────────────────────

@Composable
private fun PillChip(text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

// ─────────────────────────────────────────────
// MARK: - Empty State
// ─────────────────────────────────────────────

@Composable
private fun CollectionsEmptyState(category: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No $category yet",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.4f)
        )
    }
}

// ─────────────────────────────────────────────
// MARK: - Helpers
// ─────────────────────────────────────────────

private fun contentTypeAccent(type: CollectionContentType): Color = when (type) {
    CollectionContentType.PODCAST -> Color(0xFF5856D6)  // purple
    CollectionContentType.FILM    -> Color(0xFFFF9500)  // orange
    CollectionContentType.SERIES  -> Color(0xFF007AFF)  // blue
    CollectionContentType.COURSE  -> Color(0xFF34C759)  // green
    CollectionContentType.EVENT   -> Color(0xFFFF2D55)  // pink
    else                          -> Color(0xFF636366)  // gray
}

private fun contentTypeIcon(type: CollectionContentType) = when (type) {
    CollectionContentType.PODCAST -> Icons.Default.Mic
    CollectionContentType.FILM    -> Icons.Default.PlayCircle
    else                          -> Icons.Default.PlayCircle
}