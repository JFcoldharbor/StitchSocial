package com.stitchsocial.club.community

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image

/**
 * The 25 community level badges, as real artwork (iOS parity with
 * CommunityLevelBadgeArt).
 *
 * Badges rendered as EMOJI — a waving hand for Welcome, a paint palette for
 * Colorful — which is placeholder art the design handoff replaced. The marks
 * ship as vector drawables converted from the handoff SVGs, so they stay crisp
 * from a 16dp leaderboard chip to a 96dp gallery tile and keep their per-rarity
 * gradients.
 *
 * Resolved by ID rather than a `when` over 25 names: the ids are already
 * badge_01…badge_25 and the drawables are named to match, so a new badge needs
 * a file, not a code change. Returns 0 for an unknown id, and the caller falls
 * back to the emoji rather than crashing on a missing resource.
 */
@Composable
@DrawableRes
fun badgeArtworkRes(badgeID: String): Int {
    val context = LocalContext.current
    return remember(badgeID) {
        context.resources.getIdentifier(badgeID, "drawable", context.packageName)
    }
}

/**
 * One badge mark.
 *
 * @param locked draws the handoff's locked treatment — dimmed, desaturated and
 *   ringed — rather than a blanket alpha, so a locked badge still reads as a
 *   specific thing you haven't earned instead of a smudge.
 */
@Composable
fun CommunityLevelBadgeArt(
    badgeID: String,
    size: Dp,
    locked: Boolean,
    modifier: Modifier = Modifier,
    fallbackEmoji: String? = null
) {
    val res = badgeArtworkRes(badgeID)

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (res != 0) {
            Image(
                painter = painterResource(res),
                contentDescription = null,
                modifier = Modifier.size(size).alpha(if (locked) 0.26f else 1f),
                colorFilter = if (locked) {
                    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                } else null
            )
        } else if (fallbackEmoji != null) {
            // Unknown id — show the old emoji rather than an empty square.
            androidx.compose.material3.Text(fallbackEmoji)
        }

        if (locked) {
            Canvas(Modifier.size(size)) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.28f),
                    radius = this.size.minDimension / 2f - 0.5f,
                    center = Offset(this.size.width / 2f, this.size.height / 2f),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(3.dp.toPx(), 3.dp.toPx())
                        )
                    )
                )
            }
        }
    }
}
