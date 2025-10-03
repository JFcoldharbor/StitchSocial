/*
 * CustomDippedTabBar.kt - COMPLETE iOS 26 LIQUID GLASS RECREATION + VideoManager INTEGRATION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Exact iOS 26 Liquid Glass Recreation
 * Dependencies: Foundation Layer, VideoManager
 * Features: Real-time glass rendering, specular highlights, organic movement, video cleanup
 *
 * RECREATION: CustomDippedTabBar.swift iOS 26 Liquid Glass
 * AUTHENTIC: All iOS animations, colors, and glass effects recreated
 * UPDATED: Added VideoManager integration for proper video cleanup on recording
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
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
import kotlin.math.*
import com.stitchsocial.club.VideoManager

// MARK: - iOS 26 Exact Color System (Purple Theme)
object StitchColors {
    val primary = Color(0xFF8B5CF6)        // Purple-500 (iOS primary)
    val secondary = Color(0xFFA855F7)      // Purple-400 (iOS secondary)
    val accent = Color(0xFF7C3AED)         // Purple-700 (iOS accent)
    val textSecondary = Color(0xFF9CA3AF)  // iOS text secondary
    val background = Color.Black            // iOS background
    val surface = Color(0xFF1F2937)        // iOS surface
}

// MARK: - iOS 26 Liquid Glass Colors
private object LiquidGlassColors {
    val ultraThinMaterial = Color.White.copy(alpha = 0.08f)  // iOS .ultraThinMaterial equivalent
    val glassHighlight = Color.White.copy(alpha = 0.6f)      // iOS specular highlights
    val glassRefraction = Color.White.copy(alpha = 0.3f)     // iOS refraction
    val environmentalReflection = StitchColors.primary.copy(alpha = 0.1f) // iOS environmental
    val purpleGlow = Color(0xFFBB86FC)                       // iOS purple glow
    val glassBorder = Color.White.copy(alpha = 0.4f)         // iOS glass borders
}

// MARK: - MainAppTab Enum (iOS Match)
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

// MARK: - iOS 26 Liquid Glass Tab Bar Component
@Composable
fun CustomDippedTabBar(
    selectedTab: MainAppTab,
    onTabSelected: (MainAppTab) -> Unit,
    onCreateTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    // iOS 26 Liquid Glass Animation States
    var tabBarOffset by remember { mutableFloatStateOf(0f) }
    var createButtonScale by remember { mutableFloatStateOf(1f) }

    // iOS Configuration (Bottom Edge - No Safe Area)
    val tabBarHeight = 70.dp         // iOS tabBarHeight
    val createButtonSize = 64.dp     // iOS createButtonSize
    val dippedRadius = 20.dp         // iOS dippedRadius
    val tabBarCornerRadius = 28.dp   // iOS tabBarCornerRadius
    val bottomSafeArea = 0.dp        // REMOVED: No safe area padding
    val totalHeight = tabBarHeight   // Just the tab bar height

    // iOS 26 Liquid Glass Continuous Animations
    val infiniteTransition = rememberInfiniteTransition(label = "liquid_glass")

    val glassPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing), // iOS glassAnimationDuration
            repeatMode = RepeatMode.Restart
        ),
        label = "glass_phase"
    )

    val specularHighlight by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = FastOutSlowInEasing), // iOS specular timing
            repeatMode = RepeatMode.Reverse
        ),
        label = "specular_highlight"
    )

    val refractionOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing), // iOS refraction timing
            repeatMode = RepeatMode.Restart
        ),
        label = "refraction_offset"
    )

    // iOS Animation Effects
    LaunchedEffect(tabBarOffset) {
        if (tabBarOffset != 0f) {
            delay(150)
            tabBarOffset = 0f
        }
    }

    LaunchedEffect(createButtonScale) {
        if (createButtonScale != 1f) {
            delay(200)
            createButtonScale = 1f
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .offset(y = tabBarOffset.dp)
    ) {
        // iOS 26 Official Liquid Glass Background (Full Bottom Edge)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(tabBarHeight)
                .align(Alignment.BottomCenter) // Align to bottom edge
                .shadow(
                    elevation = 20.dp,
                    spotColor = Color.Black.copy(alpha = 0.15f),
                    ambientColor = Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(topStart = tabBarCornerRadius, topEnd = tabBarCornerRadius)
                )
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val liquidGlassPath = createLiquidGlassDippedPath(
                    size = size,
                    dippedRadius = dippedRadius.toPx(),
                    glassPhase = glassPhase,
                    refractionOffset = refractionOffset
                )

                // Base iOS .ultraThinMaterial equivalent
                drawPath(
                    path = liquidGlassPath,
                    color = LiquidGlassColors.ultraThinMaterial
                )

                // iOS Content-aware background adaptation
                drawPath(
                    path = liquidGlassPath,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.05f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    )
                )

                // iOS Glass refraction - real optical properties
                drawPath(
                    path = liquidGlassPath,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.3f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.1f)
                        ),
                        center = Offset(
                            size.width * (0.3f + sin(glassPhase * PI.toFloat()) * 0.15f),
                            size.height * (0.2f + cos(glassPhase * PI.toFloat() * 1.2f) * 0.1f)
                        ),
                        radius = 100f
                    )
                )

                // iOS Specular highlights - dynamic reaction
                drawPath(
                    path = liquidGlassPath,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.8f + specularHighlight * 0.2f),
                            Color.White.copy(alpha = 0.4f),
                            Color.Transparent
                        ),
                        start = Offset(
                            size.width * (0.1f + specularHighlight * 0.3f),
                            0f
                        ),
                        end = Offset(
                            size.width * (0.9f - specularHighlight * 0.2f),
                            size.height
                        )
                    ),
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // iOS Environmental reflection - intelligent adaptation
                drawPath(
                    path = liquidGlassPath,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.2f),
                            StitchColors.primary.copy(alpha = 0.1f),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2, size.height / 2),
                        radius = 80f
                    ),
                    blendMode = BlendMode.Screen
                )
            }
        }

        // iOS Tab Content Layout (Bottom Aligned)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tabBarHeight)
                .align(Alignment.BottomCenter) // Align to bottom
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp), // Add top padding to move content down
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side tabs
            MainAppTab.leftSideTabs.forEach { tab ->
                iOS26LiquidGlassTabItem(
                    tab = tab,
                    isSelected = selectedTab == tab,
                    glassPhase = glassPhase,
                    specularHighlight = specularHighlight,
                    onTap = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTabSelected(tab)
                        tabBarOffset = 3f
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // Create button space (iOS spacing)
            Spacer(modifier = Modifier.width(createButtonSize + 16.dp))

            // Right side tabs
            MainAppTab.rightSideTabs.forEach { tab ->
                iOS26LiquidGlassTabItem(
                    tab = tab,
                    isSelected = selectedTab == tab,
                    glassPhase = glassPhase,
                    specularHighlight = specularHighlight,
                    onTap = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTabSelected(tab)
                        tabBarOffset = 3f
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // iOS 26 Liquid Glass Create Button (Bottom Edge Positioning) - UPDATED WITH VideoManager
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-35).dp) // Raised above bottom edge
                .size(createButtonSize)
                .scale(createButtonScale)
                .shadow(
                    elevation = 16.dp,
                    spotColor = StitchColors.primary.copy(alpha = 0.4f),
                    ambientColor = Color.Black.copy(alpha = 0.2f),
                    shape = CircleShape
                )
                .clickable {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    createButtonScale = 0.88f

                    // ADDED: VideoManager integration - pause all videos before recording
                    println("🎥 CREATE BUTTON: Pausing all videos for recording")
                    VideoManager.startRecording()

                    // Call the original callback
                    onCreateTapped()
                },
            contentAlignment = Alignment.Center
        ) {
            // iOS Liquid Glass Create Button with multiple layers
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Base iOS .ultraThinMaterial
                drawCircle(
                    color = LiquidGlassColors.ultraThinMaterial
                )

                // iOS Content-aware background
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            StitchColors.primary.copy(alpha = 0.8f),
                            StitchColors.secondary.copy(alpha = 0.9f),
                            StitchColors.primary.copy(alpha = 0.85f)
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                )

                // iOS Glass refraction effect
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.6f),
                            Color.White.copy(alpha = 0.2f),
                            Color.Transparent
                        ),
                        center = Offset(
                            size.width * (0.3f + sin(glassPhase * PI.toFloat()) * 0.2f),
                            size.height * (0.3f + cos(glassPhase * PI.toFloat() * 1.1f) * 0.15f)
                        ),
                        radius = 25f
                    ),
                    blendMode = BlendMode.Overlay
                )

                // iOS Specular highlight ring
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.9f + specularHighlight * 0.1f),
                            Color.White.copy(alpha = 0.3f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.2f),
                            Color.White.copy(alpha = 0.9f + specularHighlight * 0.1f)
                        ),
                        center = Offset(size.width / 2, size.height / 2)
                    ),
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // iOS Plus icon with glass-like appearance
            Box(contentAlignment = Alignment.Center) {
                // Icon glow effect (iOS style)
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(26.dp)
                        .blur(radius = 2.dp)
                )

                // Main icon with iOS styling
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// MARK: - iOS 26 Liquid Glass Tab Item (Exact Recreation)
@Composable
private fun iOS26LiquidGlassTabItem(
    tab: MainAppTab,
    isSelected: Boolean,
    glassPhase: Float,
    specularHighlight: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    // iOS selection animation
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.75f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "alpha"
    )

    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 800f), // iOS spring
        label = "scale"
    )

    Box(
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                isPressed = true
                onTap()
            }
            .scale(animatedScale)
            .padding(horizontal = 8.dp, vertical = 4.dp) // Reduced padding
            .heightIn(min = 56.dp) // Ensure minimum touch target
            .background(
                brush = if (isSelected) {
                    // iOS .ultraThinMaterial selected state
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color.White.copy(alpha = 0.1f),
                            StitchColors.primary.copy(alpha = 0.15f)
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.Transparent)
                    )
                },
                shape = RoundedCornerShape(14.dp) // iOS corner radius
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                // iOS icon glow for selected state
                if (isSelected) {
                    Icon(
                        imageVector = tab.selectedIcon,
                        contentDescription = tab.title,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier
                            .size(24.dp)
                            .blur(radius = 2.dp)
                    )
                }

                Icon(
                    imageVector = if (isSelected) tab.selectedIcon else tab.icon,
                    contentDescription = tab.title,
                    tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f), // Increased visibility
                    modifier = Modifier.size(22.dp) // iOS icon size
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // iOS text with glass-appropriate contrast
            Text(
                text = tab.title,
                fontSize = 10.sp, // iOS font size
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium, // iOS weight
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f), // Increased visibility
                modifier = Modifier.alpha(animatedAlpha)
            )
        }

        // iOS specular highlight overlay for selected state
        if (isSelected) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.7f + specularHighlight * 0.2f),
                            Color.White.copy(alpha = 0.3f),
                            Color.Transparent
                        ),
                        start = Offset(
                            size.width * (0.0f + specularHighlight * 0.3f),
                            0f
                        ),
                        end = Offset(size.width, size.height)
                    ),
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }
        }
    }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(150)
            isPressed = false
        }
    }
}

// MARK: - iOS 26 Liquid Glass Dipped Path (Exact Recreation)
private fun createLiquidGlassDippedPath(
    size: Size,
    dippedRadius: Float,
    glassPhase: Float,
    refractionOffset: Float
): Path {
    val path = Path()

    val cornerRadius = 28f // iOS tabBarCornerRadius
    val width = size.width
    val height = size.height
    val centerX = width / 2
    val dippedWidth = dippedRadius * 3.2f // iOS dippedWidth

    // iOS subtle glass-like movement - refined organic motion
    val glassMovement = sin(glassPhase * PI.toFloat()) * 1.5f
    val refractionMovement = cos(refractionOffset * PI.toFloat()) * 0.8f

    // iOS start from top-left with subtle glass corner
    path.moveTo(cornerRadius, glassMovement)

    // iOS top edge to dip start with subtle refraction
    path.lineTo(centerX - dippedWidth/2, glassMovement)

    // iOS refined glass dip for center button (exact bezier curves)
    path.cubicTo(
        centerX - dippedWidth/3, glassMovement,
        centerX - dippedWidth/4, dippedRadius * 1.3f + sin(glassPhase * PI.toFloat() * 1.5f) * 1.0f,
        centerX + refractionMovement, dippedRadius * 1.3f + sin(glassPhase * PI.toFloat() * 1.5f) * 1.0f
    )
    path.cubicTo(
        centerX + dippedWidth/4, dippedRadius * 1.3f + sin(glassPhase * PI.toFloat() * 1.5f) * 1.0f,
        centerX + dippedWidth/3, glassMovement,
        centerX + dippedWidth/2, glassMovement
    )

    // iOS continue top edge
    path.lineTo(width - cornerRadius, glassMovement)
    path.cubicTo(
        width, glassMovement,
        width, cornerRadius + glassMovement,
        width, cornerRadius + glassMovement
    )

    // iOS right edge - EXTEND TO BOTTOM
    path.lineTo(width + refractionMovement * 0.5f, height)

    // iOS bottom edge - FULL WIDTH
    path.lineTo(0f, height)

    // iOS left edge - EXTEND TO BOTTOM
    path.lineTo(0f, cornerRadius + glassMovement)
    path.cubicTo(
        0f, glassMovement,
        cornerRadius, glassMovement,
        cornerRadius, glassMovement
    )

    path.close()
    return path
}