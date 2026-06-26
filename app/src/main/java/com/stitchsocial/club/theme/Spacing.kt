/*
 * Spacing.kt - STITCH SOCIAL SPACING SCALE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation — canonical spacing scale, mirrors iOS Spacing.swift.
 * 8pt grid with 4pt half-steps. `md` (16) is the standard screen margin.
 * Use these tokens instead of ad-hoc dp values so layout stays on a grid.
 */

package com.stitchsocial.club.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Spacing {
    /** 4 — hairline gaps, tight stacks. */
    val xxs: Dp = 4.dp
    /** 8 — small gaps. */
    val xs: Dp = 8.dp
    /** 12 — compact spacing (half-step). */
    val sm: Dp = 12.dp
    /** 16 — default / canonical screen margin. */
    val md: Dp = 16.dp
    /** 20 — generous spacing (half-step). */
    val lg: Dp = 20.dp
    /** 24 — section spacing. */
    val xl: Dp = 24.dp
    /** 32 — large section breaks. */
    val xxl: Dp = 32.dp
    /** 40 — extra-large. */
    val xxxl: Dp = 40.dp
}
