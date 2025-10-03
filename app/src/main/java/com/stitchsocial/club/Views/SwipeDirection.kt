/*
 * SwipeDirection.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Simple swipe utilities - NO ENUM DECLARATIONS
 * Used by discovery swipe functionality
 *
 * ✅ NO ENUMS: Just utility functions to avoid conflicts
 * ✅ SIMPLE: Helper functions for swipe detection
 */

package com.example.stitchsocialclub.views

/**
 * Simple swipe utilities - NO ENUM CONFLICTS
 * Contains only helper functions for swipe detection
 */
object SwipeHelper {

    // Constants for swipe detection
    private const val DEFAULT_THRESHOLD = 100f
    private const val VELOCITY_THRESHOLD = 1000f

    /**
     * Determine if drag is significant enough to be a swipe
     */
    fun isSignificantSwipe(dragX: Float, dragY: Float, threshold: Float = DEFAULT_THRESHOLD): Boolean {
        return kotlin.math.abs(dragX) > threshold || kotlin.math.abs(dragY) > threshold
    }

    /**
     * Check if swipe is horizontal (left/right)
     */
    fun isHorizontalSwipe(dragX: Float, dragY: Float): Boolean {
        return kotlin.math.abs(dragX) > kotlin.math.abs(dragY)
    }

    /**
     * Check if swipe is vertical (up/down)
     */
    fun isVerticalSwipe(dragX: Float, dragY: Float): Boolean {
        return kotlin.math.abs(dragY) > kotlin.math.abs(dragX)
    }

    /**
     * Check if swipe is to the right
     */
    fun isRightSwipe(dragX: Float, threshold: Float = DEFAULT_THRESHOLD): Boolean {
        return dragX > threshold
    }

    /**
     * Check if swipe is to the left
     */
    fun isLeftSwipe(dragX: Float, threshold: Float = DEFAULT_THRESHOLD): Boolean {
        return dragX < -threshold
    }

    /**
     * Check if swipe is upward
     */
    fun isUpSwipe(dragY: Float, threshold: Float = DEFAULT_THRESHOLD): Boolean {
        return dragY < -threshold
    }

    /**
     * Check if swipe is downward
     */
    fun isDownSwipe(dragY: Float, threshold: Float = DEFAULT_THRESHOLD): Boolean {
        return dragY > threshold
    }

    /**
     * Check if velocity indicates a swipe
     */
    fun isSwipeVelocity(velocityX: Float, velocityY: Float): Boolean {
        return kotlin.math.abs(velocityX) > VELOCITY_THRESHOLD ||
                kotlin.math.abs(velocityY) > VELOCITY_THRESHOLD
    }

    /**
     * Get swipe strength (0.0 to 1.0)
     */
    fun getSwipeStrength(drag: Float, threshold: Float = DEFAULT_THRESHOLD): Float {
        return (kotlin.math.abs(drag) / threshold).coerceIn(0f, 1f)
    }
}