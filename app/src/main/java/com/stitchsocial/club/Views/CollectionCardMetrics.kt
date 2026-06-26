package com.stitchsocial.club.views

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Canonical footprint for every card in the profile Collections row
 * (add / standalone collection / show card) so the row is uniform —
 * mirrors iOS CollectionCardMetrics.
 *
 * Cards are sizing, not spacing — exempt from the 8pt Spacing grid.
 */
object CollectionCardMetrics {
    /** Card width (every card type shares it). */
    val width: Dp = 140.dp
    /** Total card height — pins the add box / show card / thumbnail to one footprint. */
    val height: Dp = 168.dp
    /** Cover-image edge inside the show card. */
    val media: Dp = 72.dp
    /** Outer corner radius. */
    val corner: Dp = 10.dp
    val innerCorner: Dp = 8.dp
}
