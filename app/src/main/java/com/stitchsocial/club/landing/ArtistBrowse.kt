package com.stitchsocial.club.landing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Music: every live artist page, from Discovery's tab and Dash's See all. A card
 * opens the artist's page as their profile, with Report and Block on it.
 *
 * Layer 5: Views
 */
fun openArtistPage(landing: Landing) {
    LandingPresenter.open(
        LandingPresenter.Screen.Guest(landing.slug, null, LandingSource.PROFILE, asProfile = true)
    )
}

@Composable
private fun ArtistPageCard(landing: Landing, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(14.dp))
            .background(LandingColors.coverFallback)
            .clickable { openArtistPage(landing) }
    ) {
        (landing.coverURL ?: landing.logoURL)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            landing.release?.title?.let { title ->
                Text(
                    title.uppercase(),
                    style = TextStyle(
                        brush = Brush.linearGradient(artistGold, Offset.Zero, Offset(400f, 0f)),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(landing.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (landing.verified) {
                    Icon(Icons.Default.Verified, contentDescription = "Verified", tint = LandingColors.accent, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

@Composable
fun MusicBrowseView(onClose: () -> Unit, modifier: Modifier = Modifier) {
    var artists by remember { mutableStateOf<List<Landing>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        artists = LandingService.artistPages()
        loading = false
    }
    BackHandler(onBack = onClose)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .displayCutoutPadding()
            .padding(top = 8.dp)
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Music", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
            when {
                loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
                artists.isEmpty() -> Text("No artists here yet.", fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    artists.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { ArtistPageCard(it, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
