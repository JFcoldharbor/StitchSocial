package com.stitchsocial.club.landing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.Show
import com.stitchsocial.club.foundation.ShowGenre
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.services.CollectionService
import com.stitchsocial.club.services.ShowService
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.views.CollectionHighlight
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Stitch Picks Movies and Shows (iOS StitchPicksShelfView). Built on the Sports
 * page's layout: a preview at the top that plays on its own, muted, with the
 * fans' stitches beside it; this week's cards; fan takes; and the full list.
 *
 * No host yet. The top plays previews (a clip from each title) one after
 * another, and fans stitch onto the preview that's playing.
 *
 * Navigation belongs to the host (Discovery): playing, a show's page, a take
 * and the recorder all come back out through the callbacks.
 *
 * Layer 5: Views
 */
private val ShelfBg = Color(0xFF0B0B12)
private val ShelfCard = Color(0xFF171722)
private val ShelfInk = Color(0xFF0C1015)
private const val CLIP_LENGTH = 30.0

private data class ShelfPreview(val clip: CollectionHighlight, val segment: CoreVideoMetadata, val collection: VideoCollection) {
    val id: String get() = clip.id
    /** The thread the fans stitch onto. */
    val threadID: String get() = segment.threadID ?: segment.id
}

private data class ShelfTitle(val id: String, val episodes: List<VideoCollection>, val show: Show?)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun StitchPicksShelfView(
    vertical: StitchPicksVertical,
    episodes: List<VideoCollection>,
    /** Something is drawn over the page (player, show page, recorder): the preview stops. */
    covered: Boolean,
    onPlay: (VideoCollection, Int) -> Unit,
    onOpenShow: (String) -> Unit,
    onOpenTake: (CoreVideoMetadata) -> Unit,
    onStitch: (CoreVideoMetadata) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onClose)
    val accent = vertical.accent
    val isMovies = vertical == StitchPicksVertical.MOVIES

    var showsByID by remember { mutableStateOf<Map<String, Show>>(emptyMap()) }
    var previews by remember { mutableStateOf<List<ShelfPreview>>(emptyList()) }
    var loadedPreviews by remember { mutableStateOf(false) }
    var heroIndex by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var takesByThread by remember { mutableStateOf<Map<String, List<CoreVideoMetadata>>>(emptyMap()) }
    var loadedTakes by remember { mutableStateOf(false) }
    var popular by remember { mutableStateOf(false) }
    var stitchesTab by remember { mutableStateOf(false) }
    var genreFilter by remember { mutableStateOf<ShowGenre?>(null) }

    val takes = remember(takesByThread) { takesByThread.values.flatten().sortedByDescending { it.createdAt } }

    // MARK: - Loading

    suspend fun loadTakes(list: List<ShelfPreview>) {
        val service = VideoServiceImpl()
        takesByThread = coroutineScope {
            list.map { p ->
                async { p.threadID to runCatching { service.getThreadChildren(p.threadID) }.getOrDefault(emptyList()).sortedByDescending { it.createdAt } }
            }.awaitAll().toMap()
        }
        loadedTakes = true
    }

    LaunchedEffect(episodes.map { it.id }) {
        val ids = episodes.mapNotNull { it.showId?.takeIf { s -> s.isNotBlank() } }.distinct()
        showsByID = ids.mapNotNull { id -> runCatching { ShowService.shared.getShow(id) }.getOrNull()?.let { id to it } }.toMap()
        // One preview per title, newest first: the middle segment, as the
        // Collections hero picks it, so it's a scene and not the title card.
        val service = CollectionService()
        val built = mutableListOf<ShelfPreview>()
        val seen = mutableSetOf<String>()
        for (ep in episodes.sortedByDescending { it.publishedAt ?: it.createdAt }) {
            if (built.size >= 8) break
            if (!seen.add(ep.showId ?: ep.id)) continue
            val segs = runCatching { service.getCollectionSegments(ep.id) }.getOrNull().orEmpty()
            val seg = segs.getOrNull(segs.size / 2) ?: segs.firstOrNull() ?: continue
            if (seg.playbackURL.isBlank()) continue
            built += ShelfPreview(
                CollectionHighlight(seg.id, ep.id, seg.playbackURL, seg.thumbnailURL.ifBlank { null } ?: ep.coverImageURL, seg.duration),
                seg, ep
            )
        }
        previews = built
        loadedPreviews = true
        heroIndex = 0
        loadTakes(built)
    }

    // Back from the recorder or a take: the stitches may have changed.
    var wasCovered by remember { mutableStateOf(false) }
    LaunchedEffect(covered) {
        if (wasCovered && !covered && previews.isNotEmpty()) loadTakes(previews)
        wasCovered = covered
    }

    val current = previews.getOrNull(heroIndex.coerceIn(0, (previews.size - 1).coerceAtLeast(0)))

    // One preview's length, then the next. Restarts whenever the index moves.
    LaunchedEffect(heroIndex, previews.size, paused, covered) {
        if (paused || covered || previews.size < 2) return@LaunchedEffect
        delay((CLIP_LENGTH * 1000).toLong())
        heroIndex = (heroIndex + 1) % previews.size
    }

    // MARK: - Derived

    fun showTitle(c: VideoCollection): String =
        c.showId?.let { showsByID[it]?.title }?.takeIf { it.isNotBlank() } ?: c.title

    val titles = remember(episodes, showsByID) {
        episodes.groupBy { it.showId ?: it.id }.map { (key, eps) ->
            ShelfTitle(key, eps.sortedBy { it.episodeNumber ?: 0 }, showsByID[key])
        }.sortedByDescending { t -> t.episodes.maxOfOrNull { (it.publishedAt ?: it.createdAt).time } ?: 0L }
    }
    val cards = remember(titles, popular) {
        val base = if (isMovies) titles.mapNotNull { it.episodes.firstOrNull() } else episodes
        if (popular) base.sortedByDescending { it.totalViews } else base.sortedByDescending { it.publishedAt ?: it.createdAt }
    }
    val genres = remember(titles) {
        val present = titles.mapNotNull { it.show?.genre }.toSet()
        ShowGenre.values().filter { it in present }
    }
    val listed = genreFilter?.let { g -> titles.filter { it.show?.genre == g } } ?: titles

    fun record() {
        val p = current ?: return
        onStitch(p.segment)
    }

    fun open(t: ShelfTitle) {
        if (t.show != null && t.episodes.size > 1) onOpenShow(t.id)
        else t.episodes.firstOrNull()?.let { onPlay(it, 0) }
    }

    fun stitchedOn(t: CoreVideoMetadata): String {
        val thread = takesByThread.entries.firstOrNull { e -> e.value.any { it.id == t.id } }?.key
        val p = previews.firstOrNull { it.threadID == thread } ?: return "stitched on a preview"
        return "stitched on ${showTitle(p.collection)}"
    }

    // MARK: - Hero player

    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(current?.id) {
        val p = current ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(p.clip.videoURL))
        player.prepare()
        player.seekTo((p.clip.teaserStart(CLIP_LENGTH) * 1000).toLong())
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(paused, covered, current?.id) { player.playWhenReady = !paused && !covered && current != null }

    val topInset = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }

    Box(modifier.fillMaxSize().background(ShelfBg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Hero
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(540.dp + topInset)
                    .background(Color(if (isMovies) 0xFF2A1A1C else 0xFF1F1A2E))
                    .pointerInput(previews.size) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (previews.size > 1 && kotlin.math.abs(total) > 50 * density) {
                                    val step = if (total < 0) 1 else -1
                                    heroIndex = (heroIndex + step + previews.size) % previews.size
                                }
                            },
                            onHorizontalDrag = { _, dx -> total += dx }
                        )
                    }
            ) {
                if (current != null) {
                    current.clip.thumbnailURL?.let {
                        AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    AndroidView(
                        factory = {
                            PlayerView(it).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                this.player = player
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { onPlay(current.collection, 0) }
                            .semantics { contentDescription = "Play ${showTitle(current.collection)}" }
                    )
                } else {
                    Text(
                        when {
                            episodes.isEmpty() -> "Nothing here yet"
                            loadedPreviews -> "No previews yet"
                            else -> "Loading previews…"
                        },
                        color = Color.White.copy(alpha = 0.35f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.45f), 0.22f to Color.Transparent,
                            0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )

                // Top bar: back and the reel dots.
                Row(
                    Modifier.fillMaxWidth().padding(top = topInset + 4.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
                        previews.indices.forEach { i ->
                            Box(
                                Modifier.size(width = if (i == heroIndex) 18.dp else 6.dp, height = 4.dp)
                                    .clip(CircleShape)
                                    .background(if (i == heroIndex) Color.White else Color.White.copy(alpha = 0.4f))
                            )
                        }
                    }
                    Spacer(Modifier.size(48.dp))
                }

                // The fans' stitches on this preview, and the open slot.
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 76.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val onThis = current?.let { takesByThread[it.threadID] }.orEmpty().take(3)
                    onThis.forEachIndexed { i, t ->
                        AsyncImage(
                            model = t.thumbnailURL, contentDescription = "${t.creatorName}'s take",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 44.dp, height = 60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                                .border(if (i == 0) 2.dp else 1.dp, if (i == 0) accent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                .clickable { onOpenTake(t) }
                        )
                    }
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                            .dashedBorder(Color.White.copy(alpha = 0.5f), 22.dp)
                            .clickable(enabled = current != null) { record() }
                            .semantics { contentDescription = if (isMovies) "Stitch your take onto this" else "Stitch your call onto this" },
                        contentAlignment = Alignment.Center
                    ) { Text("+", color = Color.White, fontSize = 20.sp) }
                }

                // Caption
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(Modifier.padding(end = 72.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                        Text("Now playing", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        if (previews.isNotEmpty()) {
                            Text("Preview ${heroIndex + 1} of ${previews.size}", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (current != null) {
                        val c = current.collection
                        Text(
                            if (isMovies) "${c.title} · ${c.creatorName}"
                            else "${showTitle(c)} · ${c.creatorName}" + (c.episodeNumber?.let { " · Episode $it" } ?: ""),
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 72.dp)
                        )
                        Text(
                            showTitle(c).uppercase(), color = accent, fontSize = 26.sp, fontWeight = FontWeight.Black,
                            lineHeight = 28.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 72.dp, bottom = 6.dp)
                        )
                    } else {
                        Text("STITCH PICKS ${vertical.label.uppercase()}", color = accent, fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (current != null) {
                            val c = current.collection
                            val badge = if (isMovies) {
                                val mins = (c.totalDuration / 60).toInt()
                                if (mins > 0) "Full movie · $mins min" else "Full movie"
                            } else {
                                val n = titles.firstOrNull { it.id == (c.showId ?: c.id) }?.episodes?.size ?: 1
                                if (n == 1) "1 episode" else "$n episodes"
                            }
                            Text(
                                badge, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(ShelfBg).padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (current != null) {
                            HeroButton(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Play" else "Pause") { paused = !paused }
                            HeroButton(
                                if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                if (muted) "Turn sound on" else "Mute"
                            ) { muted = !muted }
                        }
                    }
                }
            }

            if (!stitchesTab) {
                // Picker
                Row(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SortButton(if (isMovies) "New" else "Latest episodes", !popular, accent, Modifier.weight(1f)) { popular = false }
                    SortButton("Most watched", popular, accent, Modifier.weight(1f)) { popular = true }
                }

                ShelfHeader(if (isMovies) "New movies" else "Episodes", if (cards.size > 1) "Swipe for the next one" else null)
                if (cards.isEmpty()) {
                    Text(if (isMovies) "No movies yet." else "No episodes yet.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        cards.forEach { c ->
                            key(c.id) {
                                Row(
                                    Modifier
                                        .width(300.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(ShelfCard)
                                        .clickable { onPlay(c, 0) }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    ShelfCover(c, showsByID, accent, if (isMovies) 64.dp else 110.dp, if (isMovies) 92.dp else 72.dp)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(if (isMovies) "● Full movie" else "● Episode ${c.episodeNumber ?: 1}", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text(if (isMovies) showTitle(c) else c.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(if (isMovies) c.creatorName else showTitle(c), color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Watch now ›", color = accent, fontSize = 12.5.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    if (isMovies) "Seen it? Stitch your take onto the trailer." else "Call what happens next. Stitch it onto the preview.",
                    color = accent.copy(alpha = 0.95f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(horizontal = 16.dp).padding(top = 14.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )

                Row(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val watchable = current?.collection ?: cards.firstOrNull()
                    Box(
                        Modifier.weight(1f).heightIn(min = 50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = if (watchable != null) 1f else 0.4f))
                            .clickable(enabled = watchable != null) { watchable?.let { onPlay(it, 0) } },
                        contentAlignment = Alignment.Center
                    ) { Text(if (isMovies) "WATCH NOW" else "WATCH EPISODE", color = ShelfInk, fontSize = 15.sp, fontWeight = FontWeight.Black) }
                    Box(
                        Modifier.weight(1f).heightIn(min = 50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable(enabled = current != null) { record() },
                        contentAlignment = Alignment.Center
                    ) { Text(if (isMovies) "RECORD YOUR TAKE" else "RECORD YOUR CALL", color = accent, fontSize = 15.sp, fontWeight = FontWeight.Black) }
                }

                ShelfHeader(if (isMovies) "Fan takes" else "Fan calls", if (loadedTakes) "${takes.size} in so far" else null)
                TakeList(takes.take(5), loadedTakes, isMovies, accent, ::stitchedOn, onOpenTake)

                // ALL MOVIES / ALL SHOWS
                Row(Modifier.padding(horizontal = 16.dp).padding(top = 22.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text(if (isMovies) "ALL MOVIES" else "ALL SHOWS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    Text("Everything on Stitch", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                }
                if (genres.size > 1) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ShelfChip("All", genreFilter == null) { genreFilter = null }
                        genres.forEach { g -> ShelfChip(g.displayName, genreFilter == g) { genreFilter = g } }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listed.forEach { t ->
                        key(t.id) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ShelfCard)
                                    .clickable { open(t) }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                t.episodes.firstOrNull()?.let { ShelfCover(it, showsByID, accent, 48.dp, 68.dp) }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    val name = t.show?.title?.takeIf { it.isNotBlank() } ?: t.episodes.firstOrNull()?.title ?: ""
                                    Text(name, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val parts = listOfNotNull(
                                        (t.show?.creatorName?.takeIf { it.isNotBlank() } ?: t.episodes.firstOrNull()?.creatorName)?.takeIf { it.isNotBlank() },
                                        t.show?.genre?.displayName,
                                        if (!isMovies) (if (t.episodes.size == 1) "1 episode" else "${t.episodes.size} episodes") else null
                                    )
                                    Text(parts.joinToString(" · "), color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (current != null && (current.collection.showId ?: current.collection.id) == t.id) {
                                    Text(
                                        "PLAYING", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black,
                                        modifier = Modifier.clip(CircleShape).background(accent.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                } else {
                                    Text("›", color = Color.White.copy(alpha = 0.5f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Text(
                    if (isMovies) "Takes are fans' opinions. Movies play from the creator's own collection."
                    else "Calls are for fun. Episodes play from the creator's own show page.",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)
                )
            } else {
                ShelfHeader(if (isMovies) "Every fan take" else "Every fan call", if (loadedTakes) "${takes.size} in all" else null)
                if (takes.isEmpty()) {
                    TakeList(emptyList(), loadedTakes, isMovies, accent, ::stitchedOn, onOpenTake)
                } else {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        takes.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { t ->
                                    Box(
                                        Modifier.weight(1f).height(160.dp).clip(RoundedCornerShape(10.dp)).background(ShelfCard)
                                            .clickable { onOpenTake(t) }
                                            .semantics { contentDescription = "${t.creatorName}'s take" }
                                    ) {
                                        AsyncImage(model = t.thumbnailURL, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
                                        Text(t.creatorName, color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(90.dp))
        }

        // Tab bar: This week · Stitches · Record
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(ShelfBg)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E1E2B)))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 6.dp, bottom = 4.dp)) {
                TabLabel("This week", !stitchesTab, accent, Modifier.weight(1f)) { stitchesTab = false }
                TabLabel("Stitches", stitchesTab, accent, Modifier.weight(1f)) { stitchesTab = true }
                TabLabel("Record", false, accent, Modifier.weight(1f), enabled = current != null) { record() }
            }
        }
    }
}

// MARK: - Pieces

@Composable
private fun HeroButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(ShelfBg).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
}

@Composable
private fun SortButton(label: String, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent else ShelfCard).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) ShelfInk else Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Bold)
    }
}

@Composable
private fun ShelfHeader(title: String, note: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
        note?.let { Text(it, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp) }
    }
}

@Composable
private fun ShelfChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 32.dp).clip(CircleShape).background(if (selected) Color.White else ShelfCard)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) ShelfInk else Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
    }
}

@Composable
private fun TabLabel(label: String, selected: Boolean, accent: Color, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) accent else Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Bold)
    }
}

@Composable
private fun ShelfCover(c: VideoCollection, shows: Map<String, Show>, accent: Color, w: Dp, h: Dp) {
    val url = c.coverImageURL ?: c.showId?.let { shows[it]?.coverImageURL ?: shows[it]?.thumbnailURL }
    Box(Modifier.size(w, h).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.18f))) {
        url?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun TakeList(
    list: List<CoreVideoMetadata>,
    loaded: Boolean,
    isMovies: Boolean,
    accent: Color,
    stitchedOn: (CoreVideoMetadata) -> String,
    onOpen: (CoreVideoMetadata) -> Unit
) {
    if (list.isEmpty()) {
        Text(
            if (!loaded) "Loading…"
            else if (isMovies) "No takes yet. Stitch the first one onto the trailer."
            else "No calls yet. Stitch the first one onto the preview.",
            color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)
        )
        return
    }
    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        list.forEach { t ->
            key(t.id) {
                Row(
                    Modifier.fillMaxWidth().background(ShelfCard).clickable { onOpen(t) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AsyncImage(
                        model = t.thumbnailURL, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.08f))
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t.creatorName, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(stitchedOn(t), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** A dashed outline drawn behind the content, for the open "+" slot. */
private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val r = radius.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
    )
}
