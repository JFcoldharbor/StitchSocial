package com.stitchsocial.club.landing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Stitch Picks Music (iOS StitchPicksMusicView). Browses like Apple Music: Top
 * Picks, New Releases, Artists, Songs and On Tour rails, with its own search.
 *
 * Every row leads to one place: the artist's own landing page, opened as their
 * profile. A song plays there, not here, so the music keeps the artist's page as
 * its home. The mini player (MusicSession) keeps going on top.
 *
 * Layer 5: Views
 */
private val MusicAccent = StitchPicksVertical.MUSIC.accent
private val MusicField = Color(0xFF1C1C1E)
private val MusicArt = Color(0xFF123036)

private enum class MusicFilter(val label: String) { FOR_YOU("For You"), NEW("New"), ARTISTS("Artists"), TOUR("On Tour") }

private data class MusicSong(val id: String, val title: String, val artist: Landing)
private data class MusicStop(val id: String, val stop: TourStop, val artist: Landing)

/** "NOV", "11" and "Wed, Nov 11 · City, Country" from a stop's YYYY-MM-DD. */
private fun TourStop.monthLabel(): String =
    runCatching { java.time.LocalDate.parse(date).month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US).uppercase() }.getOrDefault("")
private fun TourStop.dayLabel(): String = runCatching { java.time.LocalDate.parse(date).dayOfMonth.toString() }.getOrDefault("")
private fun TourStop.detailLine(): String {
    val day = runCatching {
        java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", java.util.Locale.US))
    }.getOrNull()
    val place = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")
    return listOfNotNull(day, place.ifBlank { null }).joinToString(" · ")
}
private fun TourStop.hasEnded(now: java.util.Date): Boolean =
    closesAt?.let { !now.before(it) }
        ?: runCatching { java.time.LocalDate.parse(date).isBefore(java.time.LocalDate.now()) }.getOrDefault(false)

private val Landing.initial: String get() = name.take(1).uppercase()

@Composable
fun StitchPicksMusicView(onClose: () -> Unit, modifier: Modifier = Modifier) {
    var artists by remember { mutableStateOf<List<Landing>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf(MusicFilter.FOR_YOU) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    LaunchedEffect(Unit) {
        artists = LandingService.artistPages()
        loading = false
    }
    BackHandler {
        if (searching || query.isNotEmpty()) { query = ""; searching = false; focus.clearFocus() } else onClose()
    }

    val releases = remember(artists) { artists.filter { it.release != null }.sortedByDescending { it.release?.releasedOn ?: "" } }
    val songs = remember(artists) {
        artists.flatMap { a ->
            val tracks = a.release?.tracks.orEmpty().mapIndexed { i, t -> MusicSong("${a.landingID}_$i", t.title, a) }
            if (tracks.isEmpty() && a.songThread?.title?.isNotBlank() == true) listOf(MusicSong("${a.landingID}_thread", a.songThread.title, a)) else tracks
        }
    }
    val upcoming = remember(artists) {
        val now = java.util.Date()
        artists.flatMap { a -> a.tourStops.filter { !it.hasEnded(now) }.map { MusicStop("${a.landingID}_${it.stopID}", it, a) } }
            .sortedBy { it.stop.date }
    }
    val q = query.trim().lowercase()

    fun open(a: Landing) {
        focus.clearFocus()
        searching = false
        openArtistPage(a)
    }

    Column(
        modifier.fillMaxSize().background(Color.Black)
            .padding(top = MusicSession.topRoom)
            .displayCutoutPadding()
            .statusBarsPadding()
    ) {
        if (!searching) {
            IconButton(onClick = onClose, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            if (!searching && q.isEmpty()) {
                Text("STITCH PICKS", color = MusicAccent, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp, modifier = Modifier.padding(horizontal = 16.dp))
                Text("Music", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp))
            }

            // Search bar
            Row(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(MusicField).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Artists, songs, cities", color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            cursorBrush = SolidColor(MusicAccent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                            modifier = Modifier.fillMaxWidth()
                                .onFocusChanged { if (it.isFocused) searching = true }
                                .semantics { contentDescription = "Search music" }
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Default.Cancel, contentDescription = "Clear search", tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp).clickable { query = "" }
                        )
                    }
                }
                if (searching || query.isNotEmpty()) {
                    Text("Cancel", color = MusicAccent, fontSize = 15.sp, modifier = Modifier.clickable {
                        query = ""; searching = false; focus.clearFocus()
                    }.padding(vertical = 10.dp))
                }
            }

            if (searching || q.isNotEmpty()) {
                val artistHits = artists.filter { it.name.lowercase().contains(q) || it.release?.title?.lowercase()?.contains(q) == true }
                val songHits = songs.filter { it.title.lowercase().contains(q) || it.artist.name.lowercase().contains(q) }
                val stopHits = upcoming.filter {
                    it.stop.city.lowercase().contains(q) || it.stop.venue.lowercase().contains(q) || it.artist.name.lowercase().contains(q)
                }
                when {
                    q.isEmpty() -> MusicNote("Search every artist on Stitch, their songs and where they're playing.")
                    artistHits.isEmpty() && songHits.isEmpty() && stopHits.isEmpty() -> MusicNote("Nothing for \"$query\".")
                    else -> {
                        artistHits.firstOrNull()?.let { top ->
                            MusicLabel("Top result")
                            Row(
                                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MusicField)
                                    .clickable { open(top) }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                ArtistAvatar(top, 72.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(top.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                                    Text("Artist · Landing page", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                                }
                                Text("Open", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(CircleShape).background(MusicAccent).padding(horizontal = 14.dp, vertical = 9.dp))
                            }
                        }
                        if (artistHits.size > 1) {
                            MusicLabel("Artists")
                            Column(Modifier.padding(horizontal = 16.dp)) { artistHits.drop(1).forEach { ArtistRow(it) { open(it) } } }
                        }
                        if (songHits.isNotEmpty()) {
                            MusicLabel("Songs")
                            Column(Modifier.padding(horizontal = 16.dp)) { songHits.take(20).forEach { s -> SongRow(s) { open(s.artist) } } }
                        }
                        if (stopHits.isNotEmpty()) {
                            MusicLabel("On tour")
                            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                stopHits.take(10).forEach { s -> StopRow(s) { open(s.artist) } }
                            }
                        }
                    }
                }
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MusicFilter.values().forEach { f ->
                        Box(
                            Modifier.heightIn(min = 34.dp).clip(CircleShape).background(if (filter == f) Color.White else MusicField)
                                .clickable { filter = f }.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(f.label, color = if (filter == f) Color.Black else Color.White, fontSize = 13.sp,
                                fontWeight = if (filter == f) FontWeight.Bold else FontWeight.SemiBold)
                        }
                    }
                }

                when {
                    loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                    artists.isEmpty() -> MusicNote("No artists here yet.")
                    else -> when (filter) {
                        MusicFilter.FOR_YOU -> {
                            MusicHeader("Top Picks", null)
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                artists.take(6).forEach { a ->
                                    Box(Modifier.size(300.dp, 340.dp).clip(RoundedCornerShape(16.dp)).clickable { open(a) }) {
                                        ArtistArt(a, Modifier.fillMaxSize())
                                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
                                        Column(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(a.release?.let { "NEW · ${it.title.uppercase()}" } ?: "ARTIST", color = MusicAccent, fontSize = 11.sp,
                                                fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(a.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("Open ${a.name}'s page ›", color = Color.White.copy(alpha = 0.8f), fontSize = 12.5.sp,
                                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            if (releases.isNotEmpty()) NewReleases(releases, onSeeAll = { filter = MusicFilter.NEW }, onOpen = ::open)
                            MusicHeader("Artists on Stitch") { filter = MusicFilter.ARTISTS }
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                artists.forEach { a ->
                                    Column(Modifier.width(96.dp).clickable { open(a) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ArtistAvatar(a, 96.dp)
                                        Text(a.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (songs.isNotEmpty()) {
                                MusicHeader("Songs", if (songs.size > 5) ({ filter = MusicFilter.NEW }) else null)
                                Column(Modifier.padding(horizontal = 16.dp)) { songs.take(5).forEach { s -> SongRow(s) { open(s.artist) } } }
                            }
                            if (upcoming.isNotEmpty()) {
                                MusicHeader("On Tour", if (upcoming.size > 3) ({ filter = MusicFilter.TOUR }) else null)
                                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    upcoming.take(3).forEach { s -> StopRow(s) { open(s.artist) } }
                                }
                            }
                        }
                        MusicFilter.NEW -> if (releases.isEmpty()) MusicNote("No new releases yet.") else {
                            NewReleases(releases, onSeeAll = null, onOpen = ::open)
                            MusicHeader("Songs", null)
                            Column(Modifier.padding(horizontal = 16.dp)) { songs.forEach { s -> SongRow(s) { open(s.artist) } } }
                        }
                        MusicFilter.ARTISTS -> {
                            MusicHeader("Artists on Stitch", null)
                            Column(Modifier.padding(horizontal = 16.dp)) { artists.forEach { ArtistRow(it) { open(it) } } }
                        }
                        MusicFilter.TOUR -> if (upcoming.isEmpty()) MusicNote("No shows on the calendar yet.") else {
                            MusicHeader("On Tour", null)
                            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                upcoming.forEach { s -> StopRow(s) { open(s.artist) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Pieces

@Composable
private fun NewReleases(releases: List<Landing>, onSeeAll: (() -> Unit)?, onOpen: (Landing) -> Unit) {
    MusicHeader("New Releases", onSeeAll)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        releases.forEach { a ->
            Column(Modifier.width(150.dp).clickable { onOpen(a) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ArtistArt(a, Modifier.size(150.dp).clip(RoundedCornerShape(8.dp)))
                Text(a.release?.title ?: "", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(a.name, a.release?.dateLabel).joinToString(" · "), color = Color.White.copy(alpha = 0.55f), fontSize = 12.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ArtistRow(a: Landing, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ArtistAvatar(a, 48.dp)
            Column(Modifier.weight(1f)) {
                Text(a.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Artist", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
            }
            Text("›", color = Color.White.copy(alpha = 0.4f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MusicField))
    }
}

@Composable
private fun SongRow(s: MusicSong, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "${s.title}, song by ${s.artist.name}. Opens the artist's page." }
    ) {
        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ArtistArt(s.artist, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
            Column(Modifier.weight(1f)) {
                Text(s.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Song · ${s.artist.name}", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("Artist page ›", color = MusicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MusicField))
    }
}

@Composable
private fun StopRow(s: MusicStop, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MusicField).clickable(onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MusicArt), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(s.stop.monthLabel(), color = MusicAccent, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Text(s.stop.dayLabel(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, lineHeight = 20.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(s.artist.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(s.stop.venue, s.stop.detailLine()).filter { it.isNotBlank() }.joinToString(" · "),
                color = Color.White.copy(alpha = 0.55f), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = Color.White.copy(alpha = 0.4f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ArtistArt(a: Landing, modifier: Modifier) {
    Box(modifier.background(MusicArt), contentAlignment = Alignment.Center) {
        val url = a.coverURL ?: a.logoURL
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.MusicNote, contentDescription = null, tint = MusicAccent.copy(alpha = 0.6f), modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun ArtistAvatar(a: Landing, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MusicArt), contentAlignment = Alignment.Center) {
        val url = a.logoURL ?: a.coverURL
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(a.initial, color = MusicAccent, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun MusicHeader(title: String, onSeeAll: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
        onSeeAll?.let { Text("See All", color = MusicAccent, fontSize = 15.sp, modifier = Modifier.clickable(onClick = it)) }
    }
}

@Composable
private fun MusicLabel(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 8.dp))
}

@Composable
private fun MusicNote(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(16.dp))
}
