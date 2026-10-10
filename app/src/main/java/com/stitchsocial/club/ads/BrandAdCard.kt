package com.stitchsocial.club.ads

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.stitchsocial.club.services.BrandAd
import com.stitchsocial.club.services.BrandAdService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A direct brand ad (BrandAdService), drawn in Compose.
 *
 * Unlike an AdMob native ad there is no SDK view to register — the impression
 * and click are ours to report:
 *   - impression: once per serve token, after the card has been at least half
 *     on screen for one continuous second
 *   - click: on the CTA, recorded and then ctaURL opened in the browser
 *
 * [fullScreen] is the collection-break layout (full-bleed creative, sound on);
 * otherwise it is the in-feed card (muted, plays only while visible).
 */
@Composable
fun BrandAdCard(
    ad: BrandAd,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visibleFraction by remember(ad.token) { mutableFloatStateOf(0f) }
    var impressed by remember(ad.token) { mutableStateOf(false) }
    val halfVisible = visibleFraction >= 0.5f

    // Restarts whenever visibility flips, so the second has to be continuous.
    LaunchedEffect(ad.token, halfVisible) {
        if (!halfVisible || impressed) return@LaunchedEffect
        delay(1_000)
        impressed = true
        BrandAdService.recordImpression(ad.token)
    }

    val tracking = Modifier.onGloballyPositioned { coords ->
        val height = coords.size.height
        visibleFraction = if (height > 0) coords.boundsInWindow().height / height else 0f
    }

    val onCta: () -> Unit = {
        scope.launch { BrandAdService.recordClick(ad.token) }
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ad.ctaURL))) }
    }

    if (fullScreen) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black).then(tracking)) {
            BrandAdMedia(ad, playing = halfVisible, muted = false, modifier = Modifier.fillMaxSize())
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                    .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SponsoredLine(ad.brandName)
                if (ad.title.isNotBlank()) {
                    Text(ad.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                }
                CtaButton(ad.ctaText, onCta, Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp))
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF141418))
                .then(tracking)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SponsoredLine(ad.brandName)
            BrandAdMedia(
                ad,
                playing = halfVisible,
                muted = true,
                modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp))
            )
            if (ad.title.isNotBlank()) {
                Text(ad.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            CtaButton(ad.ctaText, onCta, Modifier.fillMaxWidth().height(40.dp))
        }
    }
}

/** "Sponsored · {brandName}" — always labelled. Policy, and honesty. */
@Composable
private fun SponsoredLine(brandName: String) {
    Text(
        if (brandName.isBlank()) "Sponsored" else "Sponsored · $brandName",
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun CtaButton(text: String, onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63), contentColor = Color.White),
        shape = RoundedCornerShape(24.dp)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** Thumbnail underneath, the video over it once it has a frame. */
@Composable
private fun BrandAdMedia(ad: BrandAd, playing: Boolean, muted: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    Box(modifier = modifier.background(Color.Black)) {
        ad.thumbnailURL?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        val videoURL = ad.videoURL ?: return@Box
        val player = remember(videoURL) {
            ExoPlayer.Builder(context).build().apply {
                volume = if (muted) 0f else 1f
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(videoURL))
                prepare()
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        LaunchedEffect(player, playing) { player.playWhenReady = playing }

        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    // Transparent shutter so the poster shows until the first frame.
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
