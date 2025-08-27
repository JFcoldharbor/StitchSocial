/*
 * CustomDippedTabBar.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Premium Custom Dipped Tab Bar
 * Dependencies: Foundation Layer
 * Features: Glassmorphism, enhanced animations, larger touch targets
 *
 * BLUEPRINT: CustomDippedTabBar.swift enhanced for Android
 */

package com.example.stitchsocialclub.views

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// MARK: - Enhanced Colors (extending existing StitchColors)
private object EnhancedColors {
    val accent = Color(0xFF00CED1)         // Dark Turquoise
    val glassBorder = Color.White.copy(alpha = 0.3f)
    val glassBackground = Color.White.copy(alpha = 0.1f)
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

// MARK: - CustomDippedTabBar Main Component
@Composable
fun CustomDippedTabBar(
    selectedTab: MainAppTab,
    onTabSelected: (MainAppTab) -> Unit,
    onCreateTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current

    // Enhanced Animation States
    var tabBarOffset by remember { mutableFloatStateOf(0f) }
    var createButtonScale by remember { mutableFloatStateOf(1f) }

    // TikTok/Instagram Style Configuration - Lower positioning
    val tabBarHeight = 44.dp // Lower like TikTok/Instagram
    val createButtonSize = 72.dp // Keep button size the same
    val dippedRadius = 28.dp // Adjusted for lower bar
    val bottomSafeArea = 8.dp // Much smaller safe area like TikTok
    val totalHeight = tabBarHeight + bottomSafeArea

    // Continuous glow animation
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )

    // Animation effects
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
        // Enhanced Glassmorphism Background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .shadow(
                    elevation = 24.dp,
                    spotColor = Color.Black.copy(alpha = 0.4f),
                    ambientColor = StitchColors.primary.copy(alpha = 0.2f)
                )
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val dippedPath = createEnhancedDippedPath(size, dippedRadius.toPx(), tabBarHeight.toPx())

                // Multi-layer glassmorphism background
                drawPath(
                    path = dippedPath,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.08f),
                            Color.Black.copy(alpha = 0.15f),
                            Color.Black.copy(alpha = 0.25f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    )
                )

                // Enhanced inner glow with gradient
                drawPath(
                    path = dippedPath,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            StitchColors.primary.copy(alpha = 0.3f * glowPulse),
                            StitchColors.secondary.copy(alpha = 0.2f * glowPulse),
                            Color.Transparent
                        ),
                        radius = size.width * 0.8f,
                        center = Offset(size.width / 2, 0f)
                    )
                )

                // Premium border with multiple layers
                drawPath(
                    path = dippedPath,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.4f),
                            StitchColors.primary.copy(alpha = 0.3f),
                            Color.White.copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    ),
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Outer subtle border
                drawPath(
                    path = dippedPath,
                    color = Color.White.copy(alpha = 0.1f),
                    style = Stroke(width = 0.5.dp.toPx())
                )
            }
        }

        // TikTok/Instagram Style Tab Content Layout - Lower positioning
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(tabBarHeight)
                .padding(top = 4.dp), // Much lower padding like TikTok
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side tabs
            MainAppTab.leftSideTabs.forEach { tab ->
                EnhancedTabBarItem(
                    tab = tab,
                    isSelected = selectedTab == tab,
                    onTap = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTabSelected(tab)
                        tabBarOffset = 3f
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // Create button space (larger)
            Spacer(modifier = Modifier.width(createButtonSize + 24.dp))

            // Right side tabs
            MainAppTab.rightSideTabs.forEach { tab ->
                EnhancedTabBarItem(
                    tab = tab,
                    isSelected = selectedTab == tab,
                    onTap = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTabSelected(tab)
                        tabBarOffset = 3f
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Premium Create Button - TikTok/Instagram positioning
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-14).dp) // Higher offset to accommodate lower bar
                .size(createButtonSize)
                .scale(createButtonScale)
        ) {
            // Multiple shadow layers for depth
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(
                            x = (index * 2).dp,
                            y = (index * 2).dp
                        )
                        .shadow(
                            elevation = (20 - index * 4).dp,
                            spotColor = StitchColors.primary.copy(alpha = 0.6f - index * 0.1f),
                            shape = CircleShape
                        )
                        .background(Color.Transparent, CircleShape)
                )
            }

            // Outer glow ring with enhanced animation
            Box(
                modifier = Modifier
                    .size(createButtonSize + 8.dp)
                    .align(Alignment.Center)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                StitchColors.primary.copy(alpha = 0.6f * glowPulse),
                                StitchColors.secondary.copy(alpha = 0.4f * glowPulse),
                                EnhancedColors.accent.copy(alpha = 0.4f * glowPulse),
                                Color.Transparent
                            ),
                            radius = createButtonSize.value * 1.2f
                        ),
                        shape = CircleShape
                    )
                    .blur(radius = 6.dp)
            )

            // Main glassmorphism button with enhanced styling
            Box(
                modifier = Modifier
                    .size(createButtonSize)
                    .align(Alignment.Center)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.25f),
                                Color.White.copy(alpha = 0.15f),
                                Color.White.copy(alpha = 0.05f)
                            ),
                            start = Offset.Zero,
                            end = Offset.Infinite
                        ),
                        shape = CircleShape
                    )
                    .clickable {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        createButtonScale = 0.85f
                        onCreateTapped()
                    },
                contentAlignment = Alignment.Center
            ) {
                // Enhanced inner gradient with multiple layers
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Base gradient
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                StitchColors.primary.copy(alpha = 0.9f),
                                StitchColors.secondary.copy(alpha = 1.0f),
                                EnhancedColors.accent.copy(alpha = 0.8f)
                            ),
                            radius = size.minDimension * 0.6f
                        )
                    )

                    // Inner highlight
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.4f),
                                Color.Transparent
                            ),
                            radius = size.minDimension * 0.3f
                        ),
                        center = Offset(size.width * 0.3f, size.height * 0.3f)
                    )

                    // Glass border with enhanced gradient
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.8f),
                                Color.White.copy(alpha = 0.4f),
                                Color.Transparent,
                                Color.White.copy(alpha = 0.3f),
                                Color.White.copy(alpha = 0.8f)
                            )
                        ),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Enhanced Plus Icon with multiple effects
                Box(contentAlignment = Alignment.Center) {
                    // Icon glow effect
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create",
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier
                            .size(32.dp) // Larger icon
                            .blur(radius = 4.dp)
                    )

                    // Main icon with enhanced styling
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp) // Larger than original
                    )
                }
            }
        }
    }
}

// MARK: - Enhanced TabBarItem
@Composable
private fun EnhancedTabBarItem(
    tab: MainAppTab,
    isSelected: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    // Enhanced animation for selection
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.7f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "alpha"
    )

    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
        label = "scale"
    )

    Box(
        modifier = modifier
            .clickable {
                isPressed = true
                onTap()
            }
            .scale(animatedScale)
            .padding(12.dp, 8.dp) // Larger touch targets
            .background(
                brush = if (isSelected) {
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color.White.copy(alpha = 0.15f),
                            StitchColors.primary.copy(alpha = 0.1f)
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.Transparent)
                    )
                },
                shape = RoundedCornerShape(12.dp) // Slightly larger radius
            ),
        contentAlignment = Alignment.Center
    ) {
        // Enhanced glassmorphism for selected state
        if (isSelected) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val path = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            rect = androidx.compose.ui.geometry.Rect(
                                offset = Offset.Zero,
                                size = size
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
                        )
                    )
                }

                // Inner glow for selected state
                drawPath(
                    path = path,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            StitchColors.primary.copy(alpha = 0.3f),
                            StitchColors.secondary.copy(alpha = 0.2f),
                            Color.Transparent
                        ),
                        radius = size.minDimension * 0.8f
                    )
                )

                // Enhanced glass border
                drawPath(
                    path = path,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.5f),
                            Color.White.copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    ),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Enhanced icon with glow effect
            Box {
                if (isSelected) {
                    Icon(
                        imageVector = tab.selectedIcon,
                        contentDescription = tab.title,
                        tint = StitchColors.primary.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(22.dp) // Larger icons
                            .blur(radius = 2.dp)
                    )
                }

                Icon(
                    imageVector = if (isSelected) tab.selectedIcon else tab.icon,
                    contentDescription = tab.title,
                    tint = if (isSelected) Color.White else StitchColors.textSecondary,
                    modifier = Modifier.size(20.dp) // Larger than original
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Enhanced text with better styling
            Text(
                text = tab.title,
                fontSize = 10.sp, // Slightly larger
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isSelected) Color.White else StitchColors.textSecondary,
                modifier = Modifier.alpha(animatedAlpha)
            )
        }
    }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(150)
            isPressed = false
        }
    }
}

// MARK: - Enhanced Path Creation
private fun createEnhancedDippedPath(
    size: Size,
    dippedRadius: Float,
    tabBarHeight: Float
): Path {
    val path = Path()
    val cornerRadius = 16f // Larger corner radius
    val width = size.width
    val height = size.height
    val centerX = width / 2
    val dippedWidth = dippedRadius * 2.8f // Slightly wider dip

    // Enhanced path with smoother curves
    path.moveTo(cornerRadius, 0f)

    // Smooth curve to dip start
    path.lineTo(centerX - dippedWidth / 2, 0f)

    // Enhanced dip with bezier curves for smoother appearance
    path.cubicTo(
        centerX - dippedWidth / 3, 0f,
        centerX - dippedWidth / 4, dippedRadius * 0.8f,
        centerX, dippedRadius
    )
    path.cubicTo(
        centerX + dippedWidth / 4, dippedRadius * 0.8f,
        centerX + dippedWidth / 3, 0f,
        centerX + dippedWidth / 2, 0f
    )

    // Continue to corners with smooth curves
    path.lineTo(width - cornerRadius, 0f)
    path.quadraticBezierTo(width, 0f, width, cornerRadius)
    path.lineTo(width, tabBarHeight)
    path.lineTo(width, height)
    path.lineTo(0f, height)
    path.lineTo(0f, tabBarHeight)
    path.lineTo(0f, cornerRadius)
    path.quadraticBezierTo(0f, 0f, cornerRadius, 0f)
    path.close()

    return path
}