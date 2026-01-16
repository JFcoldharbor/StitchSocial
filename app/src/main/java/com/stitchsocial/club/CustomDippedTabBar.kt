/*
 * CustomDippedTabBar.kt - CLEAN GLASSMORPHISM TAB BAR
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Static Glassmorphism Tab Bar
 * Dependencies: Foundation Layer, VideoManager
 * Features: Static frosted glass effects, shallow dip, no animations
 *
 * UPDATED: Added static glassmorphism effects
 * UPDATED: Shallower dip - button rests on top, not buried
 */

package com.stitchsocial.club.views

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.stitchsocial.club.VideoManager

// MARK: - Color System (Purple Theme)
object StitchColors {
    val primary = Color(0xFF8B5CF6)        // Purple-500
    val secondary = Color(0xFFA855F7)      // Purple-400
    val accent = Color(0xFF7C3AED)         // Purple-700
    val textSecondary = Color(0xFF9CA3AF)
    val background = Color.Black
    val surface = Color(0xFF1F2937)
}

// MARK: - Glassmorphism Colors (Static)
private object GlassColors {
    // Base glass
    val glassFill = Color(0xFF1A1A2E).copy(alpha = 0.85f)

    // Highlights
    val topHighlight = Color.White.copy(alpha = 0.15f)
    val innerGlow = Color.White.copy(alpha = 0.05f)

    // Purple tint
    val purpleTint = Color(0xFF8B5CF6).copy(alpha = 0.08f)

    // Depth gradient
    val depthTop = Color.White.copy(alpha = 0.1f)
    val depthBottom = Color.Black.copy(alpha = 0.2f)
}

// MARK: - MainAppTab Enum
enum class MainAppTab(
    val route: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector
) {
    HOME("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    DISCOVERY("discovery", "Discover", Icons.Outlined.Search, Icons.Filled.Search),
    PROGRESSION("progression", "Profile", Icons.Outlined.Person, Icons.Filled.Person),
    NOTIFICATIONS("notifications", "Inbox", Icons.Outlined.Notifications, Icons.Filled.Notifications);

    companion object {
        val leftSideTabs = listOf(HOME, DISCOVERY)
        val rightSideTabs = listOf(PROGRESSION, NOTIFICATIONS)
    }
}

// MARK: - Glassmorphism Tab Bar
@Composable
fun CustomDippedTabBar(
    selectedTab: MainAppTab,
    onTabSelected: (MainAppTab) -> Unit,
    onCreateTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current

    // Dimensions
    val tabBarHeight = 70.dp
    val createButtonSize = 64.dp
    val tabBarCornerRadius = 28.dp

    // Create button press animation (only animation we keep)
    var createButtonScale by remember { mutableFloatStateOf(1f) }

    val animatedCreateScale by animateFloatAsState(
        targetValue = createButtonScale,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "create_scale"
    )

    LaunchedEffect(createButtonScale) {
        if (createButtonScale != 1f) {
            delay(150)
            createButtonScale = 1f
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(tabBarHeight)
    ) {
        // Glassmorphism Background
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(tabBarHeight)
                .align(Alignment.BottomCenter)
        ) {
            val dippedPath = createShallowDippedPath(
                size = size,
                createButtonSize = createButtonSize.toPx(),
                cornerRadius = tabBarCornerRadius.toPx()
            )

            // Layer 1: Base dark glass fill
            drawPath(
                path = dippedPath,
                color = GlassColors.glassFill
            )

            // Layer 2: Depth gradient (top lighter, bottom darker)
            drawPath(
                path = dippedPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GlassColors.depthTop,
                        Color.Transparent,
                        GlassColors.depthBottom
                    ),
                    startY = 0f,
                    endY = size.height
                )
            )

            // Layer 3: Subtle purple tint
            drawPath(
                path = dippedPath,
                brush = Brush.radialGradient(
                    colors = listOf(
                        GlassColors.purpleTint,
                        Color.Transparent
                    ),
                    center = Offset(size.width / 2, size.height * 0.3f),
                    radius = size.width * 0.6f
                )
            )

            // Layer 4: Inner glow from top
            drawPath(
                path = dippedPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GlassColors.innerGlow,
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = size.height * 0.4f
                )
            )

            // Layer 5: Top edge highlight (static frosted edge)
            val topEdgePath = createTopEdgePath(
                size = size,
                createButtonSize = createButtonSize.toPx(),
                cornerRadius = tabBarCornerRadius.toPx()
            )
            drawPath(
                path = topEdgePath,
                color = GlassColors.topHighlight,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Tab Content Layout
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tabBarHeight)
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side tabs
            MainAppTab.leftSideTabs.forEach { tab ->
                GlassTabItem(
                    tab = tab,
                    isSelected = selectedTab == tab,
                    onTap = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTabSelected(tab)
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // Create button space
            Spacer(modifier = Modifier.width(createButtonSize + 16.dp))

            // Right side tabs
            MainAppTab.rightSideTabs.forEach { tab ->
                GlassTabItem(
                    tab = tab,
                    isSelected = selectedTab == tab,
                    onTap = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTabSelected(tab)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Create Button - Raised position, rests on top of bar
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-16).dp)  // Raised up ~30% total
                .size(createButtonSize)
                .graphicsLayer {
                    scaleX = animatedCreateScale
                    scaleY = animatedCreateScale
                    shadowElevation = 16f
                    shape = CircleShape
                    clip = false
                }
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            StitchColors.secondary,
                            StitchColors.primary,
                            StitchColors.accent
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(100f, 100f)
                    )
                )
                .clickable {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    createButtonScale = 0.9f

                    println("🎥 CREATE BUTTON: Pausing all videos for recording")
                    VideoManager.startRecording()

                    onCreateTapped()
                },
            contentAlignment = Alignment.Center
        ) {
            // Subtle inner highlight on button
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.3f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.35f, size.height * 0.35f),
                        radius = size.width * 0.4f
                    )
                )
            }

            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Create",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

// MARK: - Glass Tab Item
@Composable
private fun GlassTabItem(
    tab: MainAppTab,
    isSelected: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "scale"
    )

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(100)
            isPressed = false
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                isPressed = true
                onTap()
            }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .heightIn(min = 56.dp)
            .background(
                brush = if (isSelected) {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.White.copy(alpha = 0.08f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Transparent)
                    )
                },
                shape = RoundedCornerShape(14.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isSelected) tab.selectedIcon else tab.icon,
                contentDescription = tab.title,
                tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = tab.title,
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.55f)
            )
        }
    }
}

// MARK: - Shallow Dipped Path (Button rests on top)
private fun createShallowDippedPath(
    size: Size,
    createButtonSize: Float,
    cornerRadius: Float
): Path {
    val path = Path()

    val width = size.width
    val height = size.height
    val centerX = width / 2

    // Shallow dip - button just rests into the bar ~35%
    val buttonRadius = createButtonSize / 2
    val dipWidth = createButtonSize + 32f   // Slightly wider than button
    val dipDepth = buttonRadius * 0.4f      // Only 40% of button sinks in

    // Start from top-left corner
    path.moveTo(cornerRadius, 0f)

    // Top edge to dip start
    path.lineTo(centerX - dipWidth / 2, 0f)

    // Gentle curve into shallow dip
    path.cubicTo(
        centerX - dipWidth / 2 + dipWidth * 0.2f, 0f,
        centerX - buttonRadius * 0.8f, dipDepth,
        centerX, dipDepth
    )

    // Gentle curve out of dip
    path.cubicTo(
        centerX + buttonRadius * 0.8f, dipDepth,
        centerX + dipWidth / 2 - dipWidth * 0.2f, 0f,
        centerX + dipWidth / 2, 0f
    )

    // Continue top edge to right corner
    path.lineTo(width - cornerRadius, 0f)

    // Top-right corner
    path.cubicTo(
        width, 0f,
        width, cornerRadius,
        width, cornerRadius
    )

    // Right edge
    path.lineTo(width, height)

    // Bottom edge
    path.lineTo(0f, height)

    // Left edge
    path.lineTo(0f, cornerRadius)

    // Top-left corner
    path.cubicTo(
        0f, 0f,
        cornerRadius, 0f,
        cornerRadius, 0f
    )

    path.close()
    return path
}

// MARK: - Top Edge Path (For highlight stroke)
private fun createTopEdgePath(
    size: Size,
    createButtonSize: Float,
    cornerRadius: Float
): Path {
    val path = Path()

    val width = size.width
    val centerX = width / 2

    val buttonRadius = createButtonSize / 2
    val dipWidth = createButtonSize + 32f
    val dipDepth = buttonRadius * 0.4f

    // Just the top edge, for the highlight stroke
    path.moveTo(cornerRadius, 0f)
    path.lineTo(centerX - dipWidth / 2, 0f)

    path.cubicTo(
        centerX - dipWidth / 2 + dipWidth * 0.2f, 0f,
        centerX - buttonRadius * 0.8f, dipDepth,
        centerX, dipDepth
    )

    path.cubicTo(
        centerX + buttonRadius * 0.8f, dipDepth,
        centerX + dipWidth / 2 - dipWidth * 0.2f, 0f,
        centerX + dipWidth / 2, 0f
    )

    path.lineTo(width - cornerRadius, 0f)

    return path
}