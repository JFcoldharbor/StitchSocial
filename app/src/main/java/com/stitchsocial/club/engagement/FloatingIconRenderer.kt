/*
 * FloatingIconRenderer.kt - iOS-STYLE FLOATING FLAMES/SNOWFLAKES
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Renders flames 🔥 floating up on hype
 * Renders snowflakes ❄️ floating up on cool
 *
 * PLACE THIS AT TOP LEVEL of your video feed!
 */

package com.stitchsocial.club.views

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.stitchsocial.club.viewmodels.FloatingIconManager
import com.stitchsocial.club.engagement.FloatingIcon
import com.stitchsocial.club.engagement.FloatingIconType
import com.stitchsocial.club.engagement.IconAnimationType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders all floating icons
 * MUST be placed at top level of video feed to render above buttons
 */
@Composable
fun FloatingIconRenderer(
    iconManager: FloatingIconManager,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        iconManager.activeIcons.forEach { icon ->
            key(icon.id) {
                FloatingFlame(icon = icon)
            }
        }
    }
}

/**
 * Single floating flame/snowflake with iOS-style animation
 * - Floats upward
 * - Gentle side-to-side drift
 * - Slight rotation
 * - Fades out at top
 */
@Composable
private fun FloatingFlame(icon: FloatingIcon) {
    val density = LocalDensity.current

    // Animation values
    var offsetY by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(0.5f) }
    var alpha by remember { mutableFloatStateOf(0f) }

    // Random drift direction
    val driftDirection = remember { if (Random.nextBoolean()) 1f else -1f }
    val driftAmount = remember { Random.nextFloat() * 20f + 10f }

    // Start animations
    LaunchedEffect(icon.id) {
        // Pop in
        launch {
            animate(0f, 1f, animationSpec = tween(150)) { value, _ -> alpha = value }
        }
        launch {
            animate(0.5f, 1.1f, animationSpec = tween(150, easing = FastOutSlowInEasing)) { value, _ -> scale = value }
            animate(1.1f, 1f, animationSpec = tween(100)) { value, _ -> scale = value }
        }

        // Float upward (main animation)
        launch {
            animate(0f, -280f, animationSpec = tween(3000, easing = LinearOutSlowInEasing)) { value, _ ->
                offsetY = value
            }
        }

        // Gentle side drift (sine wave)
        launch {
            var phase = 0f
            while (true) {
                offsetX = sin(phase.toDouble()).toFloat() * driftAmount * driftDirection
                phase += 0.08f
                delay(16)
            }
        }

        // Slow rotation
        launch {
            val targetRotation = driftDirection * (Random.nextFloat() * 30f + 15f)
            animate(0f, targetRotation, animationSpec = tween(3000)) { value, _ ->
                rotation = value
            }
        }

        // Fade out near end
        launch {
            delay(2200)
            animate(1f, 0f, animationSpec = tween(800)) { value, _ -> alpha = value }
        }

        // Shrink at end
        launch {
            delay(2500)
            animate(1f, 0.3f, animationSpec = tween(500)) { value, _ -> scale = value }
        }
    }

    // Render the flame/snowflake
    Box(
        modifier = Modifier
            .offset(
                x = with(density) { (icon.startPosition.x + offsetX).toDp() } - (icon.getIconSize() / 2).dp,
                y = with(density) { (icon.startPosition.y + offsetY).toDp() } - icon.getIconSize().dp
            )
            .size(icon.getIconSize().dp)
            .graphicsLayer {
                this.alpha = alpha
                this.scaleX = scale
                this.scaleY = scale
                this.rotationZ = rotation
            }
    ) {
        // Glow layer (behind)
        Icon(
            imageVector = getFlameIcon(icon.iconType),
            contentDescription = null,
            tint = icon.getGlowColor().copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.scaleX = 1.3f
                    this.scaleY = 1.3f
                    this.alpha = 0.4f
                }
        )

        // Main icon
        Icon(
            imageVector = getFlameIcon(icon.iconType),
            contentDescription = null,
            tint = icon.getGradientColors().first(),
            modifier = Modifier.fillMaxSize()
        )

        // Highlight (top layer)
        Icon(
            imageVector = getFlameIcon(icon.iconType),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.4f),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.scaleX = 0.7f
                    this.scaleY = 0.7f
                    translationY = -2f
                }
        )
    }
}

/**
 * Get the correct icon - FLAME for hype, SNOWFLAKE for cool
 */
private fun getFlameIcon(iconType: FloatingIconType): androidx.compose.ui.graphics.vector.ImageVector {
    return when (iconType) {
        FloatingIconType.HYPE -> Icons.Default.LocalFireDepartment  // 🔥 Flame
        FloatingIconType.COOL -> Icons.Default.AcUnit               // ❄️ Snowflake
    }
}