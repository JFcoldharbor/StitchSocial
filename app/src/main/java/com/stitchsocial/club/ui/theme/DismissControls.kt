/*
 * DismissControls.kt — one way out, and one edge to stop at.
 *
 * iOS parity with The Core/DismissControls.swift, and the same rule:
 *
 *   • A full-screen surface closes with an X, top-START. DismissX.
 *   • A sheet finishes with "Done", top-END — and "Cancel" top-start when
 *     there is work to throw away.
 *   • Screens start at Spacing.md (16) from the edge. Modifier.screenPadding().
 *
 * Both are deliberately tiny. Their value is that every screen using them
 * agrees, not that either one is clever.
 */

package com.stitchsocial.club.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The close button for a full-screen surface. Top-start, always this size.
 *
 * Sized for a thumb rather than for the glyph: 32dp of circle around a 15dp
 * mark, large enough to hit without aiming.
 *
 * @param showsScrim some hosts draw their own chrome and want the glyph alone.
 * The colour follows the scrim, and has to: white is correct on the dark disc
 * these wear over video and artwork, but without the disc there is no dark
 * behind the glyph — the host's own background is — so a white mark vanishes
 * on a light theme. That shipped once on iOS and left Notifications with no
 * visible way out in light mode.
 */
@Composable
fun DismissX(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showsScrim: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (showsScrim) Color.Black.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            tint = if (showsScrim) Color.White else AppTheme.colors.textPrimary,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * The canonical screen margin: Spacing.md, 16dp. Reach for this rather than
 * an ad-hoc value, so the content edge stops moving between screens.
 */
fun Modifier.screenPadding(): Modifier = this.padding(horizontal = Spacing.md)
