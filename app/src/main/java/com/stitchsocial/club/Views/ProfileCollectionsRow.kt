/*
 * ProfileCollectionsRow.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Mirrors iOS ProfileCollectionsRow.swift + ShowCard.swift exactly.
 * Groups collections by showId → one ShowCard per show.
 * Standalone collections (no showId) → CollectionThumbnailCard.
 * Contextual: own profile gets + add, long-press delete, See All.
 *             other profile is read-only.
 *
 * CACHING: Data supplied by caller — no Firestore reads here.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.stitchsocial.club.ui.theme.Spacing
import com.stitchsocial.club.ui.theme.StitchColors

// ─────────────────────────────────────────────
// MARK: - Profile Collections Row
// ─────────────────────────────────────────────

@Composable
fun ProfileCollectionsRow(
    collections: List<VideoCollection>,
    /** The shows by id, for their own cover and title. */
    shows: Map<String, com.stitchsocial.club.foundation.Show> = emptyMap(),
    isOwnProfile: Boolean,
    onAddTap: () -> Unit,
    onShowTap: (showId: String, episodes: List<VideoCollection>) -> Unit,
    onCollectionTap: (VideoCollection) -> Unit,
    onSeeAllTap: () -> Unit,
    onCollectionDelete: ((VideoCollection) -> Unit)? = null
) {
    // Group by showId — same logic as iOS showGroups computed var
    val showGroups: List<Pair<String, List<VideoCollection>>> = remember(collections) {
        val withShow = collections.filter { !it.showId.isNullOrEmpty() }
        val grouped = withShow.groupBy { it.showId!! }
        grouped.map { (showId, eps) ->
            val sorted = eps.sortedBy { it.episodeNumber ?: 0 }
            Pair(showId, sorted)
        }.sortedByDescending { (_, eps) -> eps.firstOrNull()?.createdAt }
    }

    val standalone = remember(collections) {
        collections.filter { it.showId.isNullOrEmpty() }
    }

    val shouldShow = collections.isNotEmpty() || isOwnProfile
    if (!shouldShow) return

    var deleteTarget by remember { mutableStateOf<VideoCollection?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm)
    ) {
        // ── Header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Collections",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Your own shows: the way into managing them (My Shows).
                if (isOwnProfile) {
                    Text(
                        "Manage",
                        color = StitchColors.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { onAddTap() }
                            .padding(horizontal = 10.dp, vertical = 12.dp)
                    )
                }
                // A real tap target — this was bare 11sp text, about 14dp tall.
                Text(
                    "See all",
                    color = StitchColors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { onSeeAllTap() }
                        .padding(start = 10.dp, top = 12.dp, bottom = 12.dp)
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        // ── Horizontal scroll ──
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // Add button (own profile only)
            if (isOwnProfile) {
                item { AddShowButton(onTap = onAddTap) }
            }

            // Show-grouped cards
            items(showGroups, key = { it.first }) { group ->
                ShowCard(
                    episodes = group.second,
                    show = shows[group.first],
                    onTap = { onShowTap(group.first, group.second) }
                )
            }

            // Standalone collections (no showId)
            items(standalone, key = { it.id }) { collection: VideoCollection ->
                CollectionThumbnailCard(
                    collection = collection,
                    onTap = { onCollectionTap(collection) },
                    cardWidth = CollectionCardMetrics.width,
                    cardHeight = CollectionCardMetrics.height,
                    corner = CollectionCardMetrics.corner
                )
            }
        }
    }

    // Delete confirmation
    if (showDeleteDialog && deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; deleteTarget = null },
            title = { Text("Delete Collection?", color = Color.White) },
            text = { Text("This will delete \"${deleteTarget!!.title}\". This cannot be undone.", color = Color.Gray) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget?.let { onCollectionDelete?.invoke(it) }
                    showDeleteDialog = false
                    deleteTarget = null
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false; deleteTarget = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}

// ─────────────────────────────────────────────
// MARK: - Show Card (per-show, mirrors iOS ShowCard)
// ─────────────────────────────────────────────

@Composable
fun ShowCard(
    episodes: List<VideoCollection>,
    /** The show itself, when loaded: its cover and title win over episode 1's. */
    show: com.stitchsocial.club.foundation.Show? = null,
    onTap: () -> Unit
) {
    val firstEp = episodes.firstOrNull()
    val contentType = firstEp?.contentType ?: CollectionContentType.SERIES
    val color = showCardColor(contentType)
    val title = show?.title?.trim()?.takeIf { it.isNotEmpty() } ?: inferShowTitle(episodes)

    // The cover is left alone: many covers carry their own title art, and the
    // title and count drawn over them (on a dark scrim) hid it. Now: the art,
    // a small count badge in the corner, the title underneath. (iOS parity.)
    Column(
        modifier = Modifier
            .width(CollectionCardMetrics.width)
            .clickable { onTap() },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .width(CollectionCardMetrics.width)
                .height(CollectionCardMetrics.height)
                .clip(RoundedCornerShape(CollectionCardMetrics.corner))
                .background(color.copy(alpha = 0.06f))
                .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(CollectionCardMetrics.corner))
        ) {
            val coverURL = show?.coverImageURL?.takeIf { it.isNotBlank() } ?: firstEp?.coverImageURL
            if (!coverURL.isNullOrBlank()) {
                AsyncImage(
                    model = coverURL,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // No cover: the name on its tint, so the card still says what it is.
                Text(
                    title,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp)
                )
            }

            val epWord = if (episodes.size == 1) "ep" else "eps"
            Text(
                "${episodes.size} $epWord",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }

        Text(
            title,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─────────────────────────────────────────────
// MARK: - Mini Episode Cell
// ─────────────────────────────────────────────

@Composable
private fun MiniEpisodeCell(
    label: String,
    color: Color,
    isNewest: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (isNewest) color.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f))
            .then(
                if (isNewest) Modifier.border(0.5.dp, color.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (isNewest) "NEW" else label,
            color = if (isNewest) color else Color.White.copy(alpha = 0.35f),
            fontSize = 6.sp,
            fontWeight = if (isNewest) FontWeight.Bold else FontWeight.Medium
        )
    }
}

// ─────────────────────────────────────────────
// MARK: - Add Show Button
// ─────────────────────────────────────────────

@Composable
private fun AddShowButton(onTap: () -> Unit) {
    // Same footprint as every other card in the row (iOS parity); the +/New
    // sit centered inside instead of a label hanging below (which made the
    // add card a different height than the rest).
    Box(
        modifier = Modifier
            .size(width = CollectionCardMetrics.width, height = CollectionCardMetrics.height)
            .clip(RoundedCornerShape(CollectionCardMetrics.corner))
            .border(
                width = 1.5.dp,
                color = StitchColors.primary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(CollectionCardMetrics.corner)
            )
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "New Show",
                tint = StitchColors.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(28.dp)
            )
            Text("New", color = Color.Gray, fontSize = 10.sp)
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Helpers
// ─────────────────────────────────────────────

fun contentTypeLabel(type: CollectionContentType): String = when (type) {
    CollectionContentType.PODCAST -> "Podcast"
    CollectionContentType.FILM    -> "Film"
    CollectionContentType.SERIES  -> "Series"
    CollectionContentType.COURSE  -> "Course"
    CollectionContentType.EVENT   -> "Event"
    else                          -> "Collection"
}

fun showCardColor(type: CollectionContentType): Color = when (type) {
    CollectionContentType.PODCAST -> Color(0xFFFF9F0A)  // orange
    CollectionContentType.FILM    -> Color(0xFFBF5AF2)  // purple
    CollectionContentType.SERIES  -> Color(0xFFFF375F)  // pink
    CollectionContentType.COURSE  -> Color(0xFF30D158)  // green
    CollectionContentType.EVENT   -> Color(0xFF64D2FF)  // cyan
    else                          -> Color(0xFF0A84FF)  // blue
}

fun inferShowTitle(episodes: List<VideoCollection>): String {
    val first = episodes.firstOrNull() ?: return "Show"
    val t = first.title
    // Strip "· Ep N" or "- Ep N" or ": Ep N" suffix to get show name
    val separators = listOf(" · ", " - Ep", ": Ep")
    for (sep in separators) {
        val idx = t.indexOf(sep)
        if (idx > 0) return t.substring(0, idx).trim()
    }
    return if (t.isNotBlank()) t else contentTypeLabel(first.contentType)
}