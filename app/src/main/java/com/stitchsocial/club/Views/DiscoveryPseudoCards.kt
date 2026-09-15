/*
 * DiscoveryPseudoCards.kt (was DiscoverySwipeCards.kt)
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * What is left of the swipe deck: the two cards that were never videos.
 *
 * The deck itself is gone. Discovery's browsing surface is the fullscreen feed
 * now (DiscoveryView.DiscoveryFullscreenDeck), so the stacked card layers, the
 * horizontal drag, the auto-advance-after-two-loops and the bespoke mini overlay
 * went with it — that overlay was a second, weaker copy of ContextualVideoOverlay
 * with no hype, cool, stitch or share on it.
 *
 * These two remain because a sponsored slot and a collection are pages in that
 * feed with no video behind them. They draw full-bleed now rather than inside a
 * 20dp-rounded card.
 */


@file:OptIn(ExperimentalFoundationApi::class)
@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.stitchsocial.club.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.platform.LocalContext
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.SponsoredSlot
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.foundation.CollectionContentType
import com.stitchsocial.club.foundation.Temperature
import com.stitchsocial.club.coordination.DiscoveryEngagementTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Discovery swipe cards - EXACT Swift port
 * Tap = fullscreen, Swipe left/right = navigate, Swipe up/down = next
 */
// MARK: - CollectionSwipeCard
// ─────────────────────────────────────────────

/**
 * Static collection card for the swipe feed — no video player.
 * Mirrors Swift DiscoverySwipeCards.collectionCardContent exactly:
 *   - Cover image fills card
 *   - Dark gradient overlay at bottom
 *   - Cyan "SERIES" badge (or PODCAST / FILM / COURSE)
 *   - Title (bold, large)
 *   - Creator + segment count row
 */
@Composable
fun CollectionSwipeCard(
    collection: VideoCollection,
    modifier: Modifier = Modifier
) {
    val badgeLabel = when (collection.contentType) {
        CollectionContentType.PODCAST -> "PODCAST"
        CollectionContentType.FILM    -> "FILM"
        CollectionContentType.SERIES  -> "SERIES"
        CollectionContentType.COURSE  -> "COURSE"
        CollectionContentType.EVENT   -> "EVENT"
        else                          -> "SERIES"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1A1A2E))
    ) {
        // Cover image fills card
        val coverURL = collection.coverImageURL
        if (!coverURL.isNullOrEmpty()) {
            AsyncImage(
                model = coverURL,
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
                        Brush.linearGradient(
                            listOf(Color(0xFF0D3B66), Color(0xFF1A1A2E))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        // Dark gradient overlay at bottom — matches Swift
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f), Color.Black.copy(alpha = 0.92f))
                    )
                )
        )

        // Bottom info panel
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // "SERIES" badge — cyan-to-purple gradient, matches Swift
            Row(
                modifier = Modifier
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF00D9F2), Color(0xFF9966F2))
                        ),
                        shape = RoundedCornerShape(50)
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(10.dp)
                )
                Text(
                    text = badgeLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    letterSpacing = 1.5.sp
                )
            }

            // Title
            Text(
                text = collection.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 26.sp
            )

            // Creator + segment count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "@${collection.creatorName}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (collection.segmentCount > 0) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(13.dp)
                        )
                        val count = collection.segmentCount
                        Text(
                            text = "$count ${if (count == 1) "part" else "parts"}",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}
// ─────────────────────────────────────────────
// MARK: - SponsoredSwipeCard
// ─────────────────────────────────────────────

/** Stitch brand magenta — canonical primary (#E91E63). */
private val SponsoredMagenta = Color(0xFFE91E63)

/**
 * First-party sponsored ad card for the swipe feed — port of iOS sponsored slot card.
 * Static 9:16 creative, NO video player, NO engagement overlay:
 *   - Full-bleed creative image (slot.imageURL)
 *   - "SPONSORED" capsule badge (top-left)
 *   - Advertiser name + title over a bottom gradient
 *   - CTA button in brand magenta with the slot's ctaText
 * The CTA button records the tap + opens ctaURL via the onCtaClick callback;
 * tapping anywhere else on the card routes through DiscoveryView's onVideoTap
 * sponsored branch (same behavior).
 */
@Composable
fun SponsoredSwipeCard(
    slot: SponsoredSlot,
    onCtaClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1A1A2E))
    ) {
        // Full-bleed 9:16 creative
        AsyncImage(
            model = slot.imageURL,
            contentDescription = slot.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // "SPONSORED" capsule badge — top-left
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = "SPONSORED",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 1.5.sp
            )
        }

        // Dark gradient overlay at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.9f))
                    )
                )
        )

        // Bottom info panel: advertiser, title, CTA
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = slot.advertiserName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = slot.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 26.sp
            )

            // CTA button — brand magenta capsule
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(SponsoredMagenta)
                    .clickable { onCtaClick() }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = slot.ctaText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}
