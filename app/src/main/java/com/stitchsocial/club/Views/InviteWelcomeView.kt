/*
 * InviteWelcomeView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views — the first screen after signing up from an invite
 * (INVITE_CONTRACT, Apps §2; the Welcome board). Shown once, after a signup
 * whose completeReferral succeeded (InviteWelcomeStore).
 *
 *   • "Answer {name}'s video" — the recorder as a stitch on the inviter's
 *     hero video, through the same entry the overlay's Stitch button uses,
 *     behind AccountStanding's posting gate.
 *   • "Record something new" — the normal recorder (same gate).
 *   • "Just look around first" — dismiss.
 */

package com.stitchsocial.club.views

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.MainActivity
import com.stitchsocial.club.R
import com.stitchsocial.club.camera.RecordingContextFactory
import com.stitchsocial.club.coordination.ModalState
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.isPubliclyVisible
import com.stitchsocial.club.services.AccountStanding
import com.stitchsocial.club.services.InviteBoostTerms
import com.stitchsocial.club.services.InviteService
import com.stitchsocial.club.services.InviteWelcomeStore
import com.stitchsocial.club.services.VideoServiceImpl
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Everything the Welcome board needs, resolved once. */
private data class InviteWelcomeModel(
    val name: String,
    val username: String,
    val displayName: String,
    val profileImageURL: String?,
    val followed: Boolean,
    val showBoost: Boolean,
    val windowDays: Int,
    val hours: Int,
    /** Fraction of the post-within window still left (the bar). */
    val windowRemaining: Float,
    val heroVideo: CoreVideoMetadata?
)

private suspend fun loadWelcomeModel(pending: InviteWelcomeStore.Pending): InviteWelcomeModel? = coroutineScope {
    val videoService = VideoServiceImpl()
    val resolution = async { InviteService.resolveInvite(pending.code) }.await()
        ?.takeIf { it.found }

    // Name/photo: resolveInvite first; the referrer's own doc if that failed.
    var username = resolution?.username.orEmpty()
    var displayName = resolution?.displayName.orEmpty()
    var photo = resolution?.profileImageURL
    val referrerID = pending.referrerID
    if (username.isBlank() && !referrerID.isNullOrBlank()) {
        runCatching {
            val d = FirebaseFirestore.getInstance("stitchfin").collection("users")
                .document(referrerID).get().await().data
            username = (d?.get("username") as? String).orEmpty()
            displayName = (d?.get("displayName") as? String).orEmpty()
            photo = (d?.get("profileImageURL") as? String)?.takeIf { it.isNotBlank() }
        }
    }
    if (username.isBlank()) return@coroutineScope null

    // The video to answer: the server's hero pick, else their latest public one.
    val hero: CoreVideoMetadata? = resolution?.heroVideo?.videoID
        ?.let { id -> runCatching { videoService.getVideoById(id) }.getOrNull() }
        ?.takeIf { !it.isDeleted && it.isPubliclyVisible }
        ?: referrerID?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { videoService.getUserVideos(id, limit = 30) }.getOrDefault(emptyList())
                .firstOrNull {
                    it.creatorID == id && !it.isDeleted && it.isPubliclyVisible &&
                        it.videoURL.isNotBlank() && !it.isFlyer
                }
        }

    val terms = resolution?.boost ?: InviteBoostTerms.DEFAULT
    val hours = pending.boostHours ?: terms.hours
    val windowMs = terms.windowDays * 24L * 60 * 60 * 1000
    val remaining = pending.boostExpiresAt?.let { exp ->
        ((exp.time - System.currentTimeMillis()).toFloat() / windowMs).coerceIn(0f, 1f)
    } ?: 1f
    val first = displayName.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() } ?: username

    InviteWelcomeModel(
        name = first,
        username = username,
        displayName = displayName,
        profileImageURL = photo,
        followed = pending.followed,
        showBoost = pending.boostExpiresAt != null || pending.boostHours != null,
        windowDays = terms.windowDays,
        hours = hours,
        windowRemaining = remaining,
        heroVideo = hero
    )
}

private fun broadcastVideos(context: Context, pause: Boolean) {
    runCatching {
        LocalBroadcastManager.getInstance(context).sendBroadcast(
            Intent(if (pause) MainActivity.ACTION_PAUSE_ALL_VIDEOS else MainActivity.ACTION_RESUME_ALL_VIDEOS)
        )
    }
}

/**
 * Mounted once in MainScreen's signed-in branch. Renders nothing unless this
 * account has a pending invite Welcome, and only while no modal is up.
 */
@Composable
fun InviteWelcomeHost(
    currentUserID: String,
    navigationCoordinator: NavigationCoordinator,
    isModalShowing: Boolean
) {
    val context = LocalContext.current
    var model by remember(currentUserID) { mutableStateOf<InviteWelcomeModel?>(null) }
    var visible by remember(currentUserID) { mutableStateOf(false) }

    val storeVersion by InviteWelcomeStore.version.collectAsState()
    LaunchedEffect(currentUserID, storeVersion) {
        if (visible) return@LaunchedEffect
        val pending = InviteWelcomeStore.pending(context, currentUserID) ?: return@LaunchedEffect
        val loaded = withTimeoutOrNull(10_000) { runCatching { loadWelcomeModel(pending) }.getOrNull() }
        if (loaded == null) {
            if (BuildConfig.DEBUG) println("⚠️ INVITE: welcome skipped — inviter couldn't be resolved")
            InviteWelcomeStore.clear(context)
            return@LaunchedEffect
        }
        model = loaded
        visible = true
        // Once on screen it's been shown; a process death mid-screen
        // shouldn't bring it back.
        InviteWelcomeStore.clear(context)
        broadcastVideos(context, pause = true)
    }

    // Feeds underneath may start a player after the first pause (a late
    // load); keep them quiet for the first few seconds the screen is up.
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        repeat(3) {
            kotlinx.coroutines.delay(1_500)
            if (visible) broadcastVideos(context, pause = true)
        }
    }

    DisposableEffect(visible) {
        InviteWelcomeStore.showing.value = visible
        onDispose { InviteWelcomeStore.showing.value = false }
    }

    val m = model
    if (!visible || m == null || isModalShowing) return

    fun close(resume: Boolean) {
        visible = false
        if (resume) broadcastVideos(context, pause = false)
    }

    InviteWelcomeScreen(
        model = m,
        onAnswer = {
            val video = m.heroVideo ?: return@InviteWelcomeScreen
            // Suspended: the shared sheet explains, and the Welcome stays.
            if (!AccountStanding.guardPosting()) return@InviteWelcomeScreen
            close(resume = false)
            com.stitchsocial.club.VideoManager.startRecording()
            // The overlay's Stitch entry (HomeFeedView.onStitchTap): stitch onto
            // the thread the video heads, with the video as parent.
            val ctx = RecordingContextFactory.createStitchToThread(
                video.threadID ?: video.id, video.creatorName, video.title
            )
            navigationCoordinator.showModal(
                ModalState.RECORDING,
                mapOf("context" to ctx, "parentVideo" to video)
            )
        },
        onRecordNew = {
            if (!AccountStanding.guardPosting()) return@InviteWelcomeScreen
            close(resume = false)
            // Same as the tab bar's create button.
            com.stitchsocial.club.VideoManager.startRecording()
            navigationCoordinator.showModal(ModalState.RECORDING)
        },
        onDismiss = { close(resume = true) }
    )
}

@Composable
private fun InviteWelcomeScreen(
    model: InviteWelcomeModel,
    onAnswer: () -> Unit,
    onRecordNew: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallow taps so nothing underneath reacts.
            .clickable(enabled = false) {}
    ) {
        val minHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    // Mark + heading.
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(
                            painter = painterResource(R.drawable.stitchsociallogo),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            buildAnnotatedString {
                                append("You're in. ")
                                withStyle(
                                    SpanStyle(
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = FontStyle.Italic,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 52.sp,
                                        letterSpacing = 0.em,
                                        brush = Brush.linearGradient(
                                            listOf(InviteColors.pink, InviteColors.violet)
                                        )
                                    )
                                ) { append("${model.name}'s invite worked.") }
                            },
                            color = Color.White,
                            fontSize = 46.sp,
                            lineHeight = 48.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.03).em
                        )
                    }

                    // Following card.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF121016))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        InviteAvatar(
                            imageURL = model.profileImageURL,
                            initials = inviteInitials(model.displayName, model.username),
                            size = 52.dp
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (model.followed) "You're following @${model.username}"
                                else "Invited by @${model.username}",
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (model.followed) "Their videos are in your Home feed."
                                else "Find their videos on their profile.",
                                color = InviteColors.muted, fontSize = 13.sp
                            )
                        }
                        if (model.followed) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(InviteColors.pink.copy(alpha = 0.16f))
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Check, null, tint = InviteColors.pink, modifier = Modifier.size(14.dp))
                                Text("Following", color = InviteColors.pink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Boost card — only when completeReferral granted one.
                    if (model.showBoost) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFF121016))
                                .border(1.dp, InviteColors.violet.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(InviteColors.violet.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Bolt, null, tint = InviteColors.violet, modifier = Modifier.size(20.dp))
                                }
                                Text(
                                    "Your first post gets a boost",
                                    color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp
                                )
                            }
                            val days = if (model.windowDays == 1) "1 day" else "${model.windowDays} days"
                            val hrs = if (model.hours == 1) "1 hour" else "${model.hours} hours"
                            val tail = if (model.heroVideo != null) " Make it count: answer one of ${model.name}'s videos." else ""
                            Text(
                                "Post within $days and we'll show it to more people in Discover for its first $hrs.$tail",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(model.windowRemaining)
                                        .fillMaxHeight()
                                        .background(Brush.horizontalGradient(listOf(InviteColors.pink, InviteColors.violet)))
                                )
                            }
                            Text(
                                "Boost ready · used on your first post",
                                color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp
                            )
                        }
                    }
                }

                // Actions.
                Column(
                    modifier = Modifier.padding(top = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (model.heroVideo != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp)
                                .shadow(
                                    elevation = 18.dp,
                                    shape = RoundedCornerShape(16.dp),
                                    ambientColor = InviteColors.magenta.copy(alpha = 0.45f),
                                    spotColor = InviteColors.magenta.copy(alpha = 0.45f)
                                )
                                .clip(RoundedCornerShape(16.dp))
                                .background(InviteColors.magenta)
                                .clickable(onClick = onAnswer),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Videocam, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Answer ${model.name}'s video",
                                color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .then(
                                if (model.heroVideo == null) Modifier.background(InviteColors.magenta)
                                else Modifier.border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
                            )
                            .clickable(onClick = onRecordNew),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Record something new", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Text(
                        "Just look around first",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onDismiss)
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}
