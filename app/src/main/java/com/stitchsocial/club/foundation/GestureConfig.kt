/*
 * SimplifiedGestures.kt - CRASH-FREE GESTURE SYSTEM
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * EMERGENCY FIX: Replace complex gesture system with simple working version
 * No complex patterns, no memory issues, just basic swipe detection
 */

package com.example.stitchsocialclub.gestures

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.*

/**
 * Simple gesture results
 */
enum class SimpleGestureResult {
    NONE,
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT
}

/**
 * CRASH-FREE gesture detection
 * No complex state, no memory allocation during gestures
 */
@Composable
fun SimplifiedSwipeDetector(
    onSwipe: (SimpleGestureResult) -> Unit,
    content: @Composable () -> Unit
) {
    var startPosition by remember { mutableStateOf(Offset.Zero) }
    var endPosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        startPosition = offset
                        endPosition = offset
                    },
                    onDragEnd = {
                        val deltaX = endPosition.x - startPosition.x
                        val deltaY = endPosition.y - startPosition.y

                        val threshold = 50f // Minimum swipe distance

                        val result = when {
                            abs(deltaX) > abs(deltaY) -> {
                                // Horizontal swipe
                                if (abs(deltaX) > threshold) {
                                    if (deltaX > 0) SimpleGestureResult.SWIPE_RIGHT
                                    else SimpleGestureResult.SWIPE_LEFT
                                } else SimpleGestureResult.NONE
                            }
                            abs(deltaY) > threshold -> {
                                // Vertical swipe
                                if (deltaY > 0) SimpleGestureResult.SWIPE_DOWN
                                else SimpleGestureResult.SWIPE_UP
                            }
                            else -> SimpleGestureResult.NONE
                        }

                        if (result != SimpleGestureResult.NONE) {
                            println("SIMPLE GESTURE: $result")
                            onSwipe(result)
                        }

                        // Reset
                        startPosition = Offset.Zero
                        endPosition = Offset.Zero
                    }
                ) { change, _ ->
                    endPosition = change.position
                }
            }
    ) {
        content()
    }
}