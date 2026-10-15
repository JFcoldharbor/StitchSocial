package com.stitchsocial.club.views

/*
 * SwipeTuning.kt — the feel of the sideways stitch swipe, shared by Discover
 * (DiscoveryFullscreenCard) and Home (ThreadVideoCard) so the two can't drift.
 *
 * A swipe completes on a flick faster than SWIPE_FLING_VELOCITY px/s (in the
 * flick's direction), or a drag past SWIPE_DISTANCE_FRACTION of the screen,
 * and settles on a no-bounce spring that carries the finger's speed.
 *
 * Layer 8: Views
 */

internal const val SWIPE_FLING_VELOCITY = 500f
internal const val SWIPE_DISTANCE_FRACTION = 0.18f
internal const val SWIPE_SETTLE_STIFFNESS = 600f
