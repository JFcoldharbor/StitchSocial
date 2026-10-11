/*
 * VideoThumbnailPeek.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.CoreVideoMetadata

/**
 * A video's still. [fitBand] is the band the player uses for this video: pass
 * the same one and the still sits in the same rect, over the same backdrop, as
 * the video that replaces it (MEDIA_FIT_CONTRACT). Null keeps the full-bleed crop.
 */
@Composable
fun VideoThumbnailPeek(
    video: CoreVideoMetadata,
    modifier: Modifier = Modifier,
    fitBand: MediaFitBand? = null
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        MediaFitFrame(
            aspect = video.aspectRatio,
            fitOverride = null,
            band = fitBand,
            backdropURL = video.thumbnailURL,
            modifier = Modifier.fillMaxSize()
        ) { isFit ->
            AsyncImage(
                model = video.thumbnailURL,
                contentDescription = video.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (isFit) ContentScale.Fit else ContentScale.Crop
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
        )

        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(40.dp)
            )
        }
    }
}