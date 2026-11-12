package com.stitchsocial.club.landing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.foundation.ShowGenre
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.services.ShowService

/**
 * Discover's Stitch Picks tab (iOS StitchPicksHubView). One card per vertical:
 * Sports (the Stitch Picks page), Movies, Shows and Music. Each card opens the
 * content where it already lives; nothing here moves or copies it.
 *
 * Movies and Shows are the published episodes the Shows takeover used, split
 * by their show's genre (movie, short film, documentary → Movies).
 *
 * Layer 5: Views
 */
enum class StitchPicksVertical(val label: String, val icon: ImageVector, val accent: Color) {
    SPORTS("Sports", Icons.Default.SportsBasketball, Color(0xFFE8C872)),
    MOVIES("Movies", Icons.Default.Movie, Color(0xFFF0625F)),
    SHOWS("Shows", Icons.Default.LiveTv, Color(0xFFB79BFF)),
    MUSIC("Music", Icons.Default.MusicNote, Color(0xFF5FD8E8));

    companion object {
        val movieGenres = setOf(ShowGenre.MOVIE, ShowGenre.SHORT_FILM, ShowGenre.DOCUMENTARY)
    }
}

/** Episodes split into (movies, shows) by their show's genre. A show that can't be read counts as a show. */
suspend fun splitMoviesAndShows(episodes: List<VideoCollection>): Pair<List<VideoCollection>, List<VideoCollection>> {
    val genres = episodes.mapNotNull { it.showId?.takeIf { id -> id.isNotBlank() } }.distinct()
        .associateWith { id -> runCatching { ShowService.shared.getShow(id)?.genre }.getOrNull() }
    return episodes.partition { ep -> genres[ep.showId]?.let { it in StitchPicksVertical.movieGenres } == true }
}

/** The Stitch Picks cover, drawn so every vertical has one. */
@Composable
fun StitchPicksVerticalCover(vertical: StitchPicksVertical, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2400f / 938f)
            .background(Brush.verticalGradient(listOf(Color(0xFF232833), Color(0xFF0E1117))))
    ) {
        val h = maxHeight
        val hv = h.value
        Row(
            modifier = Modifier.fillMaxSize().padding(start = h * 0.2f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(h * 0.16f)
        ) {
            Box(Modifier.size(h * 0.62f)) {
                Icon(vertical.icon, null, tint = vertical.accent, modifier = Modifier.fillMaxSize())
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = h * 0.04f, y = h * 0.02f)
                        .size(h * 0.24f)
                        .clip(CircleShape)
                        .background(Color(0xFF0C1015))
                        .padding(h * 0.025f)
                        .clip(CircleShape)
                        .background(Color(0xFF5BE3A6)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, null, tint = Color(0xFF0C1015), modifier = Modifier.fillMaxSize(0.7f))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(h * 0.01f)) {
                Text("STITCH PICKS", color = Color.White, fontSize = (hv * 0.15f).sp, fontWeight = FontWeight.Black, maxLines = 1)
                Text(vertical.label.uppercase(), color = vertical.accent, fontSize = (hv * 0.25f).sp, fontWeight = FontWeight.Black, maxLines = 1)
                Text("BY STITCH SOCIAL", color = Color(0xFF9AA3B5), fontSize = (hv * 0.075f).sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StitchPicksVerticalCard(
    vertical: StitchPicksVertical,
    title: String,
    detail: String?,
    badge: String? = null,
    onOpen: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, vertical.accent.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
            .background(Color(0xFF10151C))
            .clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "Stitch Picks ${vertical.label}. $title" }
    ) {
        StitchPicksVerticalCover(vertical)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let {
                    Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            badge?.let {
                Text(
                    it, color = vertical.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
            Text("›", color = Color.White.copy(alpha = 0.5f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The hub. [movies] and [shows] are null until they've been split; the cards
 * show without a count until then. Sports is hidden for teens, as on Dash.
 */
@Composable
fun StitchPicksHubView(
    movies: List<VideoCollection>?,
    shows: List<VideoCollection>?,
    onOpen: (StitchPicksVertical) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showsSports = !com.stitchsocial.club.services.TeenSafety.viewerIsTeen
    var picksPage by remember { mutableStateOf(PicksDash.last) }
    var picksFailed by remember { mutableStateOf(false) }
    var artistCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        if (showsSports) {
            val loaded = PicksDash.load()
            picksFailed = loaded == null && picksPage == null
            if (loaded != null) picksPage = loaded
        }
    }
    LaunchedEffect(Unit) { artistCount = LandingService.artistPages().size }
    BackHandler(onBack = onClose)

    fun titles(eps: List<VideoCollection>) = eps.map { it.showId ?: it.id }.distinct().size
    fun countLine(list: List<VideoCollection>?, noun: String): String? {
        list ?: return null
        val n = titles(list)
        return when (n) { 0 -> "Coming soon"; 1 -> "1 $noun"; else -> "$n ${noun}s" }
    }

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
            Text("Stitch Picks", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)

            if (showsSports) {
                val night = picksPage?.night
                val title = when {
                    picksFailed || picksPage == null -> "Open tonight's slate"
                    night == null -> "Tonight's slate"
                    else -> night.title.ifBlank { "Tonight's slate" }
                }
                StitchPicksVerticalCard(
                    vertical = StitchPicksVertical.SPORTS,
                    title = title,
                    detail = if (picksFailed || picksPage == null) null else picksStatusLine(night),
                    badge = picksPage?.takeIf { it.recordTotal > 0 }?.let { "${it.recordRight} of ${it.recordTotal} right" },
                    onOpen = { onOpen(StitchPicksVertical.SPORTS) }
                )
            }
            StitchPicksVerticalCard(StitchPicksVertical.MOVIES, "Movies on Stitch", countLine(movies, "movie")) {
                onOpen(StitchPicksVertical.MOVIES)
            }
            StitchPicksVerticalCard(StitchPicksVertical.SHOWS, "Shows on Stitch", countLine(shows, "show")) {
                onOpen(StitchPicksVertical.SHOWS)
            }
            StitchPicksVerticalCard(
                StitchPicksVertical.MUSIC, "Artists on Stitch",
                artistCount?.let { if (it == 1) "1 artist" else "$it artists" }
            ) { onOpen(StitchPicksVertical.MUSIC) }
        }
    }
}

/** The Sports card's status line — the same words as Dash's Stitch Picks cover. */
fun picksStatusLine(night: PicksNight?): String {
    night ?: return "No games today"
    val host = night.hostTotal("main")
    return when (night.status) {
        "live" -> host?.let { "LIVE · ${it.right} of ${it.total} right" } ?: "LIVE"
        "final" -> host?.let { "Final · ${it.right} of ${it.total} right" } ?: "Final"
        else -> {
            val count = night.games.size
            val tip = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(night.firstTipAt)
            "$count ${if (count == 1) "game" else "games"} · first tip $tip"
        }
    }
}

/** Opens the Stitch Picks page the way Dash's cover does. */
fun openStitchPicksSports() {
    LandingPresenter.open(
        LandingPresenter.Screen.Guest(
            slug = PicksDash.SLUG,
            spot = null,
            source = LandingSource.PROFILE,
            asProfile = true
        )
    )
}
