/*
 * Thread3DInfoPanel.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Premium Info Panel for 3D Thread Visualization
 * Dependencies: CoreVideoMetadata
 * Features: Holographic glass design, animated borders, futuristic UI
 *
 * EXACT PORT of iOS Thread3DInfoPanel.swift
 * Design: Floating holographic HUD - premium, sci-fi inspired, elegant futurism
 */

package com.stitchsocial.club.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.stitchsocial.club.foundation.CoreVideoMetadata
import kotlin.math.abs

// ============================================================================
// MARK: - Brand Colors (Match iOS exactly)
// ============================================================================

private val BrandCyan = Color(0xFF00D9F2)
private val BrandPurple = Color(0xFF9966F2)
private val BrandPink = Color(0xFFF266B3)

// Panel background colors
private val PanelDarkTop = Color(0xE6140A1E)    // 0.08, 0.05, 0.12 @ 0.9
private val PanelDarkBottom = Color(0xF20A0514)  // 0.04, 0.02, 0.08 @ 0.95

// ============================================================================
// MARK: - Custom Panel Shape (Matches iOS PanelShape exactly)
// ============================================================================

/**
 * Custom shape with rounded top corners and a subtle notch dip at the top center
 * Exact port of iOS PanelShape
 */
private class PanelShapeOutline(
    private val cornerRadius: Float = 32f,
    private val notchWidth: Float = 100f,
    private val notchHeight: Float = 6f
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): Outline {
        val path = Path().apply {
            val cr = with(density) { cornerRadius.dp.toPx() }
            val nw = with(density) { notchWidth.dp.toPx() }
            val nh = with(density) { notchHeight.dp.toPx() }
            val midX = size.width / 2f

            // Start from bottom left
            moveTo(0f, size.height)

            // Left edge up to top left corner
            lineTo(0f, cr)

            // Top left corner (quad curve)
            quadraticTo(0f, 0f, cr, 0f)

            // Top edge to notch
            lineTo(midX - nw / 2f, 0f)

            // Notch (subtle dip) - two quad curves
            quadraticTo(midX - nw / 4f, -nh, midX, -nh)
            quadraticTo(midX + nw / 4f, -nh, midX + nw / 2f, 0f)

            // Top edge continues
            lineTo(size.width - cr, 0f)

            // Top right corner (quad curve)
            quadraticTo(size.width, 0f, size.width, cr)

            // Right edge down
            lineTo(size.width, size.height)

            // Bottom edge
            lineTo(0f, size.height)

            close()
        }
        return Outline.Generic(path)
    }
}

private val PanelShape = PanelShapeOutline()

// ============================================================================
// MARK: - Main Thread3DInfoPanel
// ============================================================================

@Composable
fun Thread3DInfoPanel(
    parentVideo: CoreVideoMetadata,
    childVideos: List<CoreVideoMetadata>,
    selectedVideo: CoreVideoMetadata?,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onVideoTap: (CoreVideoMetadata) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Animated border rotation (matches iOS: 20 second full rotation)
    val infiniteTransition = rememberInfiniteTransition(label = "borderAnim")
    val borderRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "borderRotation"
    )

    // Static glow (iOS: removed pulse animation, stays at 0.6)
    val glowPulse = 0.6f

    // Drag state for expand/collapse
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Spacer(modifier = Modifier.weight(1f))

        AnimatedContent(
            targetState = isExpanded,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith
                        (slideOutVertically { it } + fadeOut())
            },
            label = "panelTransition"
        ) { expanded ->
            if (expanded) {
                // EXPANDED: Full holographic panel
                ExpandedPanel(
                    parentVideo = parentVideo,
                    childVideos = childVideos,
                    selectedVideo = selectedVideo,
                    borderRotation = borderRotation,
                    glowPulse = glowPulse,
                    onExpandChange = onExpandChange,
                    onVideoTap = onVideoTap,
                    onClose = onClose
                )
            } else {
                // COLLAPSED: Just the handle bar
                CollapsedHandle(
                    childCount = childVideos.size,
                    borderRotation = borderRotation,
                    glowPulse = glowPulse,
                    onExpandChange = onExpandChange
                )
            }
        }
    }
}

// ============================================================================
// MARK: - Expanded Panel
// ============================================================================

@Composable
private fun ExpandedPanel(
    parentVideo: CoreVideoMetadata,
    childVideos: List<CoreVideoMetadata>,
    selectedVideo: CoreVideoMetadata?,
    borderRotation: Float,
    glowPulse: Float,
    onExpandChange: (Boolean) -> Unit,
    onVideoTap: (CoreVideoMetadata) -> Unit,
    onClose: () -> Unit
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .graphicsLayer {
                // Apply drag offset for visual feedback
                translationY = dragOffset.coerceAtLeast(0f)
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // Only allow dragging down (positive Y)
                        if (dragAmount.y > 0 || dragOffset > 0) {
                            dragOffset = (dragOffset + dragAmount.y).coerceAtLeast(0f)
                        }
                    },
                    onDragEnd = {
                        val threshold = 50f
                        if (dragOffset > threshold) {
                            onExpandChange(false)
                        }
                        dragOffset = 0f
                    },
                    onDragCancel = {
                        dragOffset = 0f
                    }
                )
            }
    ) {
        // Shadow layer
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                BrandCyan.copy(alpha = 0.2f),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2, 0f),
                            radius = size.width * 0.8f
                        )
                    )
                }
        )

        // Main panel content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PanelShape)
                .drawWithContent {
                    // Background layers
                    drawPanelBackground(this)
                    drawContent()
                    // Animated border overlay
                    drawAnimatedBorder(this, borderRotation, glowPulse)
                }
        ) {
            // Handle bar - tap to collapse
            DragHandle(
                modifier = Modifier.clickable {
                    onExpandChange(false)
                }
            )

            // Content
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header row
                HeaderRow(
                    childCount = childVideos.size,
                    onClose = onClose
                )

                // Selected video info or default creator card
                if (selectedVideo != null) {
                    SelectedVideoCard(
                        video = selectedVideo,
                        isParent = selectedVideo.id == parentVideo.id,
                        borderRotation = borderRotation,
                        onVideoTap = { onVideoTap(selectedVideo) }
                    )
                } else {
                    CreatorInfoCard(
                        parentVideo = parentVideo,
                        borderRotation = borderRotation
                    )
                }

                // Child video strip
                if (childVideos.isNotEmpty()) {
                    ChildVideoStrip(
                        childVideos = childVideos,
                        selectedVideoId = selectedVideo?.id,
                        onVideoTap = onVideoTap
                    )
                }

                // Stats row
                StatsRow(
                    parentVideo = parentVideo,
                    childVideos = childVideos
                )
            }
        }
    }
}

// ============================================================================
// MARK: - Collapsed Handle
// ============================================================================

@Composable
private fun CollapsedHandle(
    childCount: Int,
    borderRotation: Float,
    glowPulse: Float,
    onExpandChange: (Boolean) -> Unit
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .graphicsLayer {
                translationY = (-dragOffset).coerceAtLeast(0f).coerceAtMost(0f)
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // Track upward drag (negative Y)
                        dragOffset = (dragOffset - dragAmount.y).coerceAtLeast(0f)
                    },
                    onDragEnd = {
                        val threshold = 30f
                        if (dragOffset > threshold) {
                            onExpandChange(true)
                        }
                        dragOffset = 0f
                    },
                    onDragCancel = {
                        dragOffset = 0f
                    }
                )
            }
            .clickable {
                onExpandChange(true)
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PanelShape)
                .drawWithContent {
                    drawPanelBackground(this)
                    drawContent()
                    drawAnimatedBorder(this, borderRotation, glowPulse)
                }
        ) {
            // Handle
            DragHandle(modifier = Modifier)

            // Collapsed content: minimal stats
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ViewInAr,
                    contentDescription = null,
                    tint = BrandCyan,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$childCount children",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Default
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Tap to expand →",
                    color = BrandPurple.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

// ============================================================================
// MARK: - Drag Handle (matches iOS exactly)
// ============================================================================

@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left line
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(2.dp)
                .background(BrandCyan.copy(alpha = 0.4f))
        )
        Spacer(modifier = Modifier.width(6.dp))
        // Center capsule
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            BrandCyan.copy(alpha = 0.6f),
                            BrandPurple.copy(alpha = 0.6f)
                        )
                    )
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        // Right line
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(2.dp)
                .background(BrandPurple.copy(alpha = 0.4f))
        )
    }
}

// ============================================================================
// MARK: - Header Row (matches iOS headerRow)
// ============================================================================

@Composable
private fun HeaderRow(
    childCount: Int,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 3D Badge
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, BrandCyan.copy(alpha = 0.3f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ViewInAr,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = BrandCyan
            )
            Text(
                text = "3D",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Stitch count
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = null,
                tint = BrandCyan,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = "${childCount + 1}",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Close button
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            BrandCyan.copy(alpha = 0.5f),
                            BrandPurple.copy(alpha = 0.5f)
                        )
                    ),
                    shape = CircleShape
                )
                .clickable { onClose() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

// ============================================================================
// MARK: - Creator Info Card (matches iOS creatorInfoCard)
// ============================================================================

@Composable
private fun CreatorInfoCard(
    parentVideo: CoreVideoMetadata,
    borderRotation: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail with animated glow ring
        Box(contentAlignment = Alignment.Center) {
            // Glow ring (animated angular gradient)
            Canvas(
                modifier = Modifier.size(56.dp, 72.dp)
            ) {
                rotate(borderRotation * 2) {
                    drawRoundRect(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                BrandCyan,
                                BrandPurple,
                                BrandPink,
                                BrandCyan
                            )
                        ),
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // Actual thumbnail
            Box(
                modifier = Modifier
                    .size(50.dp, 66.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                BrandCyan.copy(alpha = 0.3f),
                                BrandPurple.copy(alpha = 0.3f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (parentVideo.thumbnailURL.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(parentVideo.thumbnailURL)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = parentVideo.creatorName,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = null,
                    tint = BrandCyan,
                    modifier = Modifier.size(12.dp)
                )
            }
            Text(
                text = parentVideo.title,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Thread indicator
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Forum,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = BrandCyan.copy(alpha = 0.8f)
            )
            Text(
                text = "ORIGIN",
                color = BrandCyan.copy(alpha = 0.6f),
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ============================================================================
// MARK: - Selected Video Card (matches iOS selectedVideoCard)
// ============================================================================

@Composable
private fun SelectedVideoCard(
    video: CoreVideoMetadata,
    isParent: Boolean,
    borderRotation: Float,
    onVideoTap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        BrandCyan.copy(alpha = 0.3f),
                        BrandPurple.copy(alpha = 0.3f)
                    )
                ),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Video thumbnail
        Box(
            modifier = Modifier
                .size(60.dp, 80.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            BrandPurple.copy(alpha = 0.3f),
                            BrandCyan.copy(alpha = 0.2f)
                        )
                    )
                )
                .border(1.dp, BrandCyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (video.thumbnailURL.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(video.thumbnailURL)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Video info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Type badge
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (isParent) BrandCyan else BrandPurple)
                )
                Text(
                    text = if (isParent) "ORIGINAL" else "REPLY",
                    color = if (isParent) BrandCyan else BrandPurple,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = video.creatorName,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = video.title,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Stats inline
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Whatshot,
                        contentDescription = null,
                        tint = Color(0xFFFF9500),
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "${video.hypeCount}",
                        color = Color(0xFFFF9500),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AcUnit,
                        contentDescription = null,
                        tint = BrandCyan,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "${video.coolCount}",
                        color = BrandCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Watch button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(BrandCyan, BrandPurple)
                        )
                    )
                    .clickable { onVideoTap() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Watch",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = "WATCH",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ============================================================================
// MARK: - Child Video Strip (matches iOS childVideoStrip)
// ============================================================================

@Composable
private fun ChildVideoStrip(
    childVideos: List<CoreVideoMetadata>,
    selectedVideoId: String?,
    onVideoTap: (CoreVideoMetadata) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "REPLIES",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                BrandPurple.copy(alpha = 0.4f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Horizontal scroll of child thumbnails
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            itemsIndexed(childVideos) { index, child ->
                ChildVideoThumbnail(
                    video = child,
                    index = index,
                    isSelected = selectedVideoId == child.id,
                    onTap = { onVideoTap(child) }
                )
            }
        }
    }
}

// ============================================================================
// MARK: - Child Video Thumbnail (matches iOS ChildVideoThumbnail)
// ============================================================================

@Composable
private fun ChildVideoThumbnail(
    video: CoreVideoMetadata,
    index: Int,
    isSelected: Boolean,
    onTap: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(48.dp, 64.dp)
                .graphicsLayer {
                    scaleX = if (isPressed) 0.92f else 1f
                    scaleY = if (isPressed) 0.92f else 1f
                }
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            BrandPurple.copy(alpha = 0.2f + (index % 3) * 0.1f),
                            BrandCyan.copy(alpha = 0.15f)
                        )
                    )
                )
                .then(
                    if (isSelected) {
                        Modifier
                            .border(2.dp, BrandCyan, RoundedCornerShape(8.dp))
                            .drawBehind {
                                drawRoundRect(
                                    color = BrandCyan.copy(alpha = 0.3f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                                    style = Stroke(width = 4.dp.toPx()),
                                    blendMode = BlendMode.Screen
                                )
                            }
                    } else {
                        Modifier.border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    }
                )
                .clickable {
                    isPressed = true
                    onTap()
                    isPressed = false
                },
            contentAlignment = Alignment.Center
        ) {
            if (video.thumbnailURL.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(video.thumbnailURL)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = "${index + 1}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Index badge overlay (bottom-right)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "${index + 1}",
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Creator name
        Text(
            text = video.creatorName,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.Center
        )
    }
}

// ============================================================================
// MARK: - Stats Row (matches iOS statsRow)
// ============================================================================

@Composable
private fun StatsRow(
    parentVideo: CoreVideoMetadata,
    childVideos: List<CoreVideoMetadata>
) {
    val allVideos = listOf(parentVideo) + childVideos
    val totalHype = allVideos.sumOf { it.hypeCount }
    val totalCool = allVideos.sumOf { it.coolCount }
    val totalViews = allVideos.sumOf { it.viewCount }
    val uniqueCreators = allVideos.map { it.creatorID }.toSet().size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.02f))
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatItem(
            icon = Icons.Default.Whatshot,
            value = totalHype,
            label = "HYPE",
            color = Color(0xFFFF9500)
        )

        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(24.dp)
                .background(Color.White.copy(alpha = 0.1f))
        )
        Spacer(modifier = Modifier.weight(1f))

        StatItem(
            icon = Icons.Default.AcUnit,
            value = totalCool,
            label = "COOL",
            color = BrandCyan
        )

        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(24.dp)
                .background(Color.White.copy(alpha = 0.1f))
        )
        Spacer(modifier = Modifier.weight(1f))

        StatItem(
            icon = Icons.Default.RemoveRedEye,
            value = totalViews,
            label = "VIEWS",
            color = Color.White.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(24.dp)
                .background(Color.White.copy(alpha = 0.1f))
        )
        Spacer(modifier = Modifier.weight(1f))

        StatItem(
            icon = Icons.Default.People,
            value = uniqueCreators,
            label = "CREATORS",
            color = BrandPurple
        )
    }
}

@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Int,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = formatNumber(value),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 7.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ============================================================================
// MARK: - Drawing Functions (Panel Background, Grid, Animated Border)
// ============================================================================

/**
 * Draw the panel background layers matching iOS exactly:
 * 1. Dark gradient overlay
 * 2. Subtle grid pattern (0.03 opacity)
 * 3. Top highlight
 */
private fun drawPanelBackground(scope: DrawScope) {
    with(scope) {
        // Base dark gradient (matches iOS dark overlay)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(PanelDarkTop, PanelDarkBottom)
            )
        )

        // Subtle grid pattern (matches iOS gridPattern at 0.03 opacity)
        val spacing = 20.dp.toPx()
        val gridColor = BrandCyan.copy(alpha = 0.015f)
        val gridStroke = 0.5.dp.toPx()

        // Vertical lines
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = gridColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = gridStroke
            )
            x += spacing
        }
        // Horizontal lines
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = gridStroke
            )
            y += spacing
        }

        // Top highlight (matches iOS white.opacity(0.1) -> clear gradient)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.1f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = 60.dp.toPx()
            ),
            size = Size(size.width, 60.dp.toPx())
        )
    }
}

/**
 * Draw animated angular gradient border matching iOS exactly
 * Uses sweepGradient rotated by borderRotation degrees
 */
private fun drawAnimatedBorder(scope: DrawScope, rotation: Float, glowPulse: Float) {
    with(scope) {
        // Create the panel path for border stroke
        val cr = 32.dp.toPx()
        val nw = 100.dp.toPx()
        val nh = 6.dp.toPx()
        val midX = size.width / 2f

        val borderPath = Path().apply {
            moveTo(0f, size.height)
            lineTo(0f, cr)
            quadraticTo(0f, 0f, cr, 0f)
            lineTo(midX - nw / 2f, 0f)
            quadraticTo(midX - nw / 4f, -nh, midX, -nh)
            quadraticTo(midX + nw / 4f, -nh, midX + nw / 2f, 0f)
            lineTo(size.width - cr, 0f)
            quadraticTo(size.width, 0f, size.width, cr)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }

        // Animated angular gradient (matches iOS AngularGradient rotating)
        rotate(rotation) {
            drawPath(
                path = borderPath,
                brush = Brush.sweepGradient(
                    colors = listOf(
                        BrandCyan.copy(alpha = 0.8f),
                        BrandPurple.copy(alpha = 0.6f),
                        BrandPink.copy(alpha = 0.4f),
                        BrandCyan.copy(alpha = 0.2f),
                        BrandCyan.copy(alpha = 0.8f)
                    ),
                    center = Offset(size.width / 2f, size.height / 2f)
                ),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

// ============================================================================
// MARK: - Utility
// ============================================================================

private fun formatNumber(num: Int): String {
    return when {
        num >= 1_000_000 -> String.format("%.1fM", num / 1_000_000.0)
        num >= 1_000 -> String.format("%.1fK", num / 1_000.0)
        else -> "$num"
    }
}