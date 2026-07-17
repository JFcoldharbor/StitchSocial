package com.stitchsocial.club.views

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/**
 * EmberParticles
 *
 * Mini ember-particle effect drawn inside any Box. Used inside the
 * BackgroundPostManager-driven create button on the tab bar while a post
 * is uploading — heat-phase visual continuity with the iOS PostCompletionView
 * embers.
 *
 * Pure Canvas + Compose state. Particles spawn at the bottom, drift up,
 * fade out, recycle. No external dependencies.
 */
@Composable
fun EmberParticles(
    modifier: Modifier = Modifier,
    intensity: Float = 0.5f,   // 0.0–1.0 — drives spawn rate + brightness
    color: Color = Color(0xFFFF7A26),
) {
    val particleCount = (4 + (intensity * 10f)).toInt().coerceIn(4, 14)
    val particles = remember(particleCount) {
        List(particleCount) { Ember.random() }
    }
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(33L)  // ~30fps — plenty for a 64dp button
            tick += 1
        }
    }

    Canvas(modifier = modifier) {
        // tick is read inside this block so the canvas re-draws each frame.
        @Suppress("UNUSED_VARIABLE")
        val frame = tick
        val w = size.width
        val h = size.height

        particles.forEach { p ->
            p.advance(intensity)

            val x = p.x * w + sin(p.life * 6f + p.seed) * w * 0.06f
            val y = (1f - p.life) * h
            val alpha = (1f - p.life).coerceIn(0f, 1f) * (0.5f + intensity * 0.5f)
            val radius = (0.05f + (1f - p.life) * 0.07f) * w

            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = Offset(x, y),
                blendMode = BlendMode.Plus
            )
        }
    }
}

/**
 * Internal mutable particle state. Each particle drifts upward and
 * recycles when life >= 1.
 */
private class Ember(
    var x: Float,
    var life: Float,
    val speed: Float,
    val seed: Float
) {
    fun advance(intensity: Float) {
        life += speed * (0.6f + intensity * 0.6f)
        if (life >= 1f) {
            life = Random.nextFloat() * 0.2f
            x = Random.nextFloat()
        }
    }

    companion object {
        fun random(): Ember = Ember(
            x = Random.nextFloat(),
            life = Random.nextFloat(),
            speed = 0.008f + Random.nextFloat() * 0.012f,
            seed = Random.nextFloat() * 6.28f
        )
    }
}
