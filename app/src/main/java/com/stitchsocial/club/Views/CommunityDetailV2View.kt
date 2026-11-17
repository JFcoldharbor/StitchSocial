/*
 * CommunityDetailV2View.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Community home
 *
 * The approved "B" layout, one scrolling column with pull-to-refresh:
 *   cover (the creator's latest clip, or the live state) → identity →
 *   owner actions + this week (owner) → Into right now → You (member) →
 *   Top members → Highlights → Posts (partner slot after the 3rd), with one
 *   floating record button. Palette is the Stitch brand, not the old navy V2.
 *
 * Data comes from CommunityHomeViewModel (Firestore reads + the
 * community_home callable, each failing on its own). Live state — the ghost
 * recovery, the listener, join/auto-join — stays here unchanged.
 *
 * The old Home/Threads tabs, stats boxes and preview cards are gone; nothing
 * here navigates to Threads/Events/Challenges any more.
 */

package com.stitchsocial.club.views

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.community.CommunityHome
import com.stitchsocial.club.community.CommunityListItem
import com.stitchsocial.club.community.CommunityMembership
import com.stitchsocial.club.community.CommunityPost
import com.stitchsocial.club.community.CommunityUserProfile
import com.stitchsocial.club.community.CommunityXPCurve
import com.stitchsocial.club.community.CommunityXPService
import com.stitchsocial.club.community.InterestHypedVideo
import com.stitchsocial.club.community.InterestPicks
import com.stitchsocial.club.community.InterestWatching
import com.stitchsocial.club.community.LevelUpToastHost
import com.stitchsocial.club.foundation.NotificationRouter
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.live.VideoComment
import com.stitchsocial.club.services.CollectionService
import com.stitchsocial.club.viewmodels.CommunityHomeViewModel
import com.stitchsocial.club.viewmodels.CommunityHomeViewModelFactory
import com.stitchsocial.club.viewmodels.isVideo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

// ─────────────────────────────────────────────────────────────────────────────
// Stitch brand palette (mockup B)
// ─────────────────────────────────────────────────────────────────────────────

private object B {
    val bg = Color(0xFF0D0D14)
    val card = Color(0xFF1A1A26)
    val magenta = Color(0xFFE91E63)
    val blue = Color(0xFF3399FF)
    val blueLight = Color(0xFF66B3FF)
    val live = Color(0xFFFF3B30)
    val txt = Color(0xFFFFFFFF)
    val txt2 = Color(0xFFB3B3BF)
    val txt3 = Color(0xFF80808C)
    val txtSoft = Color(0xFFE6E6EE)
    val green = Color(0xFF4ADE80)
    val gold = Color(0xFFFFD24A)
    val wrong = Color(0xFFFF6B6B)
    val streak = Color(0xFFFF9F43)
    val coverFallback = Color(0xFF2A1030)
    val liveCoverFallback = Color(0xFF3A0F1E)
    val translucent = Color.Black.copy(alpha = 0.45f)

    val brand = Brush.linearGradient(listOf(blue, magenta))
    val brandH = Brush.horizontalGradient(listOf(blue, magenta))

    /** Top-member ring by rank: gold, magenta, blue, indigo, then green. */
    fun rankRing(rank: Int): Color = when (rank) {
        1 -> gold
        2 -> magenta
        3 -> blue
        4 -> Color(0xFF6366F1)
        else -> Color(0xFF34C759)
    }
}

/** Same rule the community list uses for its PARTNER label. */
private fun isPartnerTier(tier: String?): Boolean =
    tier == "partner" || tier == "topCreator" || tier == "founder"

// ─────────────────────────────────────────────────────────────────────────────
// Root composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CommunityDetailV2View(
    userID: String,
    communityID: String,
    communityItem: CommunityListItem,
    /**
     * Stream to open immediately, set by a go-live notification tap.
     *
     * Without it a tap could only land on the community and rely on the live
     * banner being visible — and the notification promised the stream.
     */
    autoOpenStreamID: String? = null,
    /**
     * Open the live stream as soon as this view can, for a caller that knows only
     * that the community is live and has no stream id — Dash's LIVE NOW avatars.
     *
     * A bool rather than an id on purpose. The stream, the effective feature level
     * and the viewer's current username are all resolved here and nowhere else, so
     * a caller that assembled the viewer itself would reproduce the faults this
     * file already guards against: a community flagged live with no stream behind
     * it, a raw community level in place of the effective one, and the
     * denormalised membership username that is never refreshed after a rename.
     */
    autoJoinLive: Boolean = false,
    onAutoOpenConsumed: () -> Unit = {},
    onDismiss: () -> Unit,
    /**
     * Opens the real app recorder (iOS parity, e6f51f6) — the same cinematic
     * recorder + editor the main + button uses. `postID` is null for a new
     * community thread, or the post being stitched to for a video reply. The
     * finished clip comes back through [CommunityClipRouter], not as a global
     * video.
     */
    onRecordClip: (communityID: String, postID: String?) -> Unit = { _, _ -> },
) {
    val db = remember { FirebaseFirestore.getInstance("stitchfin") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val vm: CommunityHomeViewModel = viewModel(
        key = "communityHome_${communityID}_$userID",
        factory = CommunityHomeViewModelFactory(communityID, userID),
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(vm) { vm.onAppear() }

    val membership = state.membership
    // The VIEWER's account tier — deliberately not the room owner's. An
    // Ambassador visiting someone else's community is the case this exists
    // for, and reading the community's creatorTier would answer about the
    // wrong person entirely.
    val viewerTier: UserTier? = state.viewer?.tier?.let { UserTier.fromRawValue(it) }
    // Live profile values, preferred over the membership snapshot.
    val viewerUsername: String? = state.viewer?.username
    val viewerDisplayName: String? = state.viewer?.displayName
    // Ad-free is a paid perk — defaults to SHOWING ads so a failed lookup
    // can't accidentally give the product away.
    val viewerHasNoAds = false
    // null = prompt not answered yet; "" = answered with the default.
    // Nullable rather than a second boolean so the two states can't drift.
    var goLiveMessage by remember { mutableStateOf<String?>(null) }
    var showingComposer by remember { mutableStateOf(false) }
    // The clip the app recorder handed back for a NEW thread in this channel.
    var recordedClipUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedPost by remember { mutableStateOf<CommunityPost?>(null) }
    var showingGoLive by remember { mutableStateOf(false) }
    var showingLiveStream by remember { mutableStateOf(false) }
    var showingLeaderboard by remember { mutableStateOf(false) }
    var showingBadges by remember { mutableStateOf(false) }
    var showingSettings by remember { mutableStateOf(false) }
    var openCollection by remember { mutableStateOf<VideoCollection?>(null) }

    // Live state — mirrored from snapshot listener + verified against the
    // actual stream doc (handles ghost flags on the community doc).
    var isCreatorLiveRealtime by remember { mutableStateOf(communityItem.isCreatorLive) }
    var liveStreamID by remember { mutableStateOf<String?>(null) }
    var memberCount by remember { mutableStateOf(communityItem.memberCount) }
    var liveViewerCount by remember { mutableStateOf<Int?>(null) }

    val isCreator = userID == communityID

    // Recorder result for a new thread here (postID == null). Stitches to a
    // specific post are collected by CommunityPostDetailView instead.
    val finishedClip by com.stitchsocial.club.community.CommunityClipRouter
        .finishedClip.collectAsState()
    LaunchedEffect(finishedClip) {
        val clip = finishedClip ?: return@LaunchedEffect
        if (clip.target.communityID != communityID || clip.target.postID != null) return@LaunchedEffect
        com.stitchsocial.club.community.CommunityClipRouter.consume()
        // Let the recording modal finish tearing down before the caption sheet
        // comes up — same reason iOS waits 0.45s (cover -> sheet drop).
        kotlinx.coroutines.delay(400)
        recordedClipUri = android.net.Uri.parse(
            if (clip.videoPath.startsWith("content://") || clip.videoPath.startsWith("file://")) {
                clip.videoPath
            } else {
                "file://${clip.videoPath}"
            }
        )
        showingComposer = true
    }

    // One-shot ghost recovery — see CommunityDetailView.kt for the rationale.
    LaunchedEffect(communityID) {
        val docSnap = runCatching {
            db.collection("communities").document(communityID).get().await()
        }.getOrNull() ?: return@LaunchedEffect
        val data = docSnap.data ?: return@LaunchedEffect
        val flaggedLive = data["isCreatorLive"] as? Boolean ?: false
        val flaggedStreamID = data["activeStreamID"] as? String
        if (!flaggedLive) return@LaunchedEffect
        val verified = com.stitchsocial.club.live.LiveStreamService
            .getInstance().fetchActiveStream(creatorID = communityID)
        if (verified != null && verified.id == flaggedStreamID) return@LaunchedEffect

        // A LIVE STREAM EXISTS, it just isn't the one the community doc names.
        // REPAIR, don't kill.
        //
        // This branch used to force-end the stream, and production shows why
        // that's dangerous: streams were being created in pairs, so
        // activeStreamID recorded one while fetchActiveStream returned the
        // other. The ID comparison failed, this concluded the flag was stale,
        // and it force-ended a creator who was genuinely broadcasting —
        // leaving isCreatorLive false with two live streams running. That's the
        // "creator is live but nobody can join" state: no badge, no entry point.
        //
        // Ghost recovery is for a flag with NO stream behind it. If a stream is
        // live, the flag is simply pointing at the wrong one, and the fix is to
        // point it at the right one.
        if (verified != null) {
            if (BuildConfig.DEBUG) {
                println("🧹 GHOST: V2 repointing $communityID at live stream ${verified.id}")
            }
            isCreatorLiveRealtime = true
            liveStreamID = verified.id
            if (isCreator) {
                runCatching {
                    db.collection("communities").document(communityID).update(
                        mapOf("isCreatorLive" to true, "activeStreamID" to verified.id)
                    ).await()
                }
            }
            return@LaunchedEffect
        }

        if (BuildConfig.DEBUG) println("🧹 GHOST: V2 detected stale live flag on $communityID")
        if (isCreator) {
            com.stitchsocial.club.live.LiveStreamService.getInstance()
                .forceEndStream(creatorID = communityID)
        }
        isCreatorLiveRealtime = false
        liveStreamID = null
    }

    // Live-state listener — passive UI sync only.
    DisposableEffect(communityID) {
        val listener = db.collection("communities").document(communityID)
            .addSnapshotListener { snap, err ->
                // A silently-dropped error here is how the Join button loses its
                // stream id — the banner keeps the cached live flag and nothing
                // ever fills in activeStreamID.
                if (err != null) {
                    if (BuildConfig.DEBUG) {
                        println("⚠️ LIVE: community listener failed for $communityID — ${err.message}")
                    }
                    return@addSnapshotListener
                }
                val data = snap?.data ?: return@addSnapshotListener
                isCreatorLiveRealtime = data["isCreatorLive"] as? Boolean ?: false
                liveStreamID = data["activeStreamID"] as? String
                (data["memberCount"] as? Number)?.toInt()?.let { memberCount = it }
                // Close the viewer when the stream it was showing goes away.
                // Without this, showingLiveStream stayed true after a stream
                // ended, so the NEXT stream re-presented the screen on its own
                // — or left the flag stuck in a state the Watch button couldn't
                // change, which reads as "I can't get into the new live".
                if (!isCreatorLiveRealtime || liveStreamID == null) {
                    showingLiveStream = false
                }
            }
        onDispose { listener.remove() }
    }

    // Go-live tap: open the viewer as soon as we're here.
    LaunchedEffect(autoOpenStreamID) {
        val target = autoOpenStreamID ?: return@LaunchedEffect
        liveStreamID = target
        isCreatorLiveRealtime = true
        showingLiveStream = true
        onAutoOpenConsumed()
    }

    // JOIN MUST NEVER BE A NO-OP.
    //
    // This was `if (liveStreamID != null) showingLiveStream = true`, so whenever
    // the LIVE NOW banner was up without a stream id the button did nothing at
    // all: no screen, no error, no log, no way in. And the banner routinely
    // outlives the id — it seeds from the CACHED list item, while the id only
    // ever arrives from the community-doc snapshot listener (which swallows its
    // error, so a failed read leaves the id null forever), and endStream clears
    // activeStreamID on a doc whose flag can lag.
    //
    // So ask the source of truth instead. fetchActiveStream returns the NEWEST
    // live stream for the creator: if one exists we open it and repair local
    // state, and if none does the banner was a ghost and comes down.
    suspend fun openLiveStream() {
        liveStreamID?.let { showingLiveStream = true; return }

        val verified = com.stitchsocial.club.live.LiveStreamService
            .getInstance().fetchActiveStream(creatorID = communityID)
        if (verified != null) {
            if (BuildConfig.DEBUG) {
                println("🔴 LIVE: join had no streamID — resolved ${verified.id} for $communityID")
            }
            liveStreamID = verified.id
            isCreatorLiveRealtime = true
            showingLiveStream = true
        } else {
            if (BuildConfig.DEBUG) {
                println("🔴 LIVE: join tapped but no live stream exists for $communityID — clearing banner")
            }
            isCreatorLiveRealtime = false
            liveStreamID = null
        }
    }

    // Auto-join, for a caller that only knows the community is live.
    //
    // Goes through openLiveStream() rather than waiting for the listener: the id
    // arrives asynchronously and may never arrive at all, and that function already
    // falls back to the source of truth and takes the banner down if the stream
    // turns out to be a ghost. Latched, so a later listener update cannot drag
    // someone back into a stream they have already left.
    var autoJoinConsumed by remember(communityID) { mutableStateOf(false) }
    LaunchedEffect(autoJoinLive, communityID) {
        if (!autoJoinLive || autoJoinConsumed) return@LaunchedEffect
        autoJoinConsumed = true
        openLiveStream()
    }

    // "N watching" on the live cover — the stream doc's viewerCount, re-read
    // every 30s while live. A separate read, not LiveStreamService's listener,
    // which belongs to the viewer screen.
    LaunchedEffect(isCreatorLiveRealtime, liveStreamID) {
        liveViewerCount = null
        val sid = liveStreamID ?: return@LaunchedEffect
        if (!isCreatorLiveRealtime) return@LaunchedEffect
        while (true) {
            com.stitchsocial.club.community.CommunityHomeService.shared
                .fetchViewerCount(communityID, sid)?.let { liveViewerCount = it }
            delay(30_000)
        }
    }

    val creatorName = state.creator?.displayName ?: communityItem.creatorDisplayName
    val creatorImage = state.creator?.profileImageURL ?: communityItem.profileImageURL
    val isPartner = isPartnerTier(state.creator?.tier ?: communityItem.creatorTier)
    val home: CommunityHome? = state.home

    val record: () -> Unit = { onRecordClip(communityID, null) }
    val share: () -> Unit = {
        val username = state.creator?.username ?: communityItem.creatorUsername
        val text = "Join $creatorName's community on Stitch: https://stitchsocial.me/u/$username"
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    "Share community"
                )
            )
        }
    }
    val openWatching: (InterestWatching) -> Unit = { w ->
        w.collectionID?.let { cid ->
            scope.launch {
                openCollection = runCatching { CollectionService().getCollection(cid) }.getOrNull()
            }
        }
    }

    // The root Box absorbs all unhandled taps so they don't fall through to
    // the underlying Discovery screen (the parent MainActivity Box renders
    // this on top via zIndex but Compose doesn't auto-consume gestures —
    // any tap landing on empty padding would otherwise hit Discovery cards).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(B.bg)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { /* consume */ })
            }
    ) {
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = B.magenta, modifier = Modifier.size(36.dp))
            }
        } else {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                CommunityHomeColumn(
                    communityID = communityID,
                    creatorName = creatorName,
                    creatorImage = creatorImage,
                    isPartner = isPartner,
                    memberCount = memberCount,
                    home = home,
                    isCreator = isCreator,
                    isLive = isCreatorLiveRealtime,
                    liveViewerCount = liveViewerCount,
                    membership = membership,
                    topMembers = state.topMembers,
                    profiles = state.profiles,
                    posts = state.posts,
                    highlights = state.highlights,
                    adOwner = com.stitchsocial.club.ads.AdConfig.ImpressionOwner(
                        // The ROOM's owner earns, split by THEIR tier — not
                        // the viewer's, who is just watching.
                        type = com.stitchsocial.club.ads.AdConfig.OwnerType.CREATOR,
                        creatorID = communityID,
                        tier = UserTier.fromRawValue(communityItem.creatorTier)
                    ),
                    // Subscribers bought ad-free; delivering ads anyway
                    // is selling something we don't hand over.
                    adsEnabled = !viewerHasNoAds,
                    onBack = onDismiss,
                    onShare = share,
                    onSettings = { showingSettings = true },
                    onJoinLive = { scope.launch { openLiveStream() } },
                    onGoLive = {
                        if (com.stitchsocial.club.services.AccountStanding.guardPosting()) showingGoLive = true
                    },
                    onRecord = record,
                    onOpenPicks = { slug ->
                        com.stitchsocial.club.landing.LandingPresenter.route(
                            slug = slug,
                            source = com.stitchsocial.club.landing.LandingSource.PROFILE
                        )
                    },
                    onOpenVideo = { videoID ->
                        // The same route a /v link takes: the fullscreen player.
                        NotificationRouter.request(NotificationRouter.TYPE_SHARED_LINK, mapOf("videoID" to videoID))
                    },
                    onOpenWatching = openWatching,
                    onShowBadges = { showingBadges = true },
                    onShowLeaderboard = { showingLeaderboard = true },
                    onOpenPost = { selectedPost = it },
                    onHype = { vm.hype(it) },
                    onStitch = { post -> onRecordClip(communityID, post.id) },
                )
            }

            // One floating record button (replaces the Home/Threads pill nav).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 18.dp, bottom = 18.dp)
                    .shadow(16.dp, CircleShape, ambientColor = B.magenta, spotColor = B.magenta)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(B.brand)
                    .clickable { record() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Videocam, contentDescription = "Record a post", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }

        // Fullscreen overlays
        if (showingLeaderboard) {
            MemberLeaderboardViewV2(
                topMembers = state.topMembers,
                initialSort = LeaderboardSortV2.LEVEL,
                onDismiss = { showingLeaderboard = false },
            )
        }
        if (showingBadges) {
            BadgeGalleryViewV2(
                currentLevel = membership?.level ?: 1,
                featureLevel = membership?.effectiveFeatureLevel ?: 1,
                earnedBadgeIDs = membership?.earnedBadgeIDs ?: emptyList(),
                onDismiss = { showingBadges = false },
            )
        }
        if (showingSettings && isCreator) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) { detectTapGestures(onTap = { /* consume */ }) }
            ) {
                CreatorCommunitySettingsView(
                    creatorID = communityID,
                    creatorUsername = communityItem.creatorUsername,
                    creatorDisplayName = communityItem.creatorDisplayName,
                    creatorTier = viewerTier
                        ?: UserTier.fromRawValue(communityItem.creatorTier)
                        ?: UserTier.ROOKIE,
                    onDismiss = {
                        showingSettings = false
                        vm.refresh()
                    },
                    onInterestCardsChanged = { vm.applyInterestCards(it) },
                )
            }
        }
        openCollection?.let { coll ->
            CollectionPlayerView(
                collection = coll,
                userID = userID,
                currentUserTier = viewerTier ?: UserTier.ROOKIE,
                onDismiss = { openCollection = null },
            )
        }

        // Live stream overlays (creator + viewer)
        if (showingLiveStream && liveStreamID != null) {
            com.stitchsocial.club.live.LiveStreamViewerScreen(
                userID = userID,
                communityID = communityID,
                streamID = liveStreamID!!,
                // Feeds the live video-comment gate (Lv5+). Must be
                // effectiveFeatureLevel or an owner — real level 1 since
                // privileges no longer inflate it — can't reply in their OWN
                // room. This exact line is what broke on iOS.
                userLevel = liveFeatureLevel(membership, viewerTier),
                // Live profile first; membership only as a fallback for a
                // viewer whose user doc failed to load.
                userUsername = viewerUsername ?: membership?.username ?: "user",
                userDisplayName = viewerDisplayName
                    ?: membership?.displayName ?: membership?.username ?: "User",
                onDismiss = { showingLiveStream = false },
            )
        }
        // Compose the invite BEFORE the camera opens — see GoLivePromptSheet.
        if (showingGoLive && isCreator && goLiveMessage == null) {
            com.stitchsocial.club.live.GoLivePromptSheet(
                memberCount = memberCount,
                onGoLive = { goLiveMessage = it },
                onCancel = { showingGoLive = false }
            )
        }
        if (showingGoLive && isCreator && goLiveMessage != null) {
            com.stitchsocial.club.live.LiveStreamCreatorScreen(
                creatorID = communityID,
                creatorUsername = membership?.username ?: communityItem.creatorUsername,
                creatorDisplayName = membership?.displayName ?: communityItem.creatorDisplayName,
                goLiveMessage = goLiveMessage ?: "",
                onDismiss = { showingGoLive = false; goLiveMessage = null },
            )
        }

        // Progression had no voice: CommunityXPService has always published
        // level-ups to `lastLevelUp` and nothing anywhere read it. Mounted last
        // so it draws above the community content, but inside the root Box so
        // it doesn't cover a live stream or the recorder.
        if (!showingGoLive && !showingLiveStream) {
            LevelUpToastHost(CommunityXPService.shared)
        }
    }

    // Caption + Post step. Reached with the clip the app recorder just produced
    // (recordedClipUri); the library picker inside is only a fallback.
    if (showingComposer) {
        CommunityVideoComposerSheet(
            userID = userID,
            communityID = communityID,
            membership = membership,
            isCreator = isCreator,
            initialUri = recordedClipUri,
            onPosted = { newPost ->
                // Optimistic: prepend so the new post shows up immediately.
                vm.prependPost(newPost)
            },
            onDismiss = {
                showingComposer = false
                recordedClipUri = null
            },
        )
    }

    // Post detail — play the video, stitch to it, delete it (iOS parity, 89ceba4).
    selectedPost?.let { post ->
        CommunityPostDetailView(
            userID = userID,
            communityID = communityID,
            post = post,
            membership = membership,
            isCommunityCreator = isCreator,
            onDismiss = { selectedPost = null },
            onDeleted = { deleted -> vm.removePost(deleted.id) },
            onRecordClip = { cid, postID -> onRecordClip(cid, postID) },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The scrolling column
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CommunityHomeColumn(
    communityID: String,
    creatorName: String,
    creatorImage: String?,
    isPartner: Boolean,
    memberCount: Int,
    home: CommunityHome?,
    isCreator: Boolean,
    isLive: Boolean,
    liveViewerCount: Int?,
    membership: CommunityMembership?,
    topMembers: List<CommunityMembership>,
    profiles: Map<String, CommunityUserProfile>,
    posts: List<CommunityPost>,
    highlights: List<CommunityPost>,
    adOwner: com.stitchsocial.club.ads.AdConfig.ImpressionOwner,
    adsEnabled: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit,
    onJoinLive: () -> Unit,
    onGoLive: () -> Unit,
    onRecord: () -> Unit,
    onOpenPicks: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenWatching: (InterestWatching) -> Unit,
    onShowBadges: () -> Unit,
    onShowLeaderboard: () -> Unit,
    onOpenPost: (CommunityPost) -> Unit,
    onHype: (CommunityPost) -> Unit,
    onStitch: (CommunityPost) -> Unit,
) {
    val top10 = remember(topMembers) { topMembers.take(10) }
    val interests = home?.interests
    val adIndex = if (posts.isEmpty()) -1 else minOf(2, posts.lastIndex)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Room for the floating record button over the last post.
        contentPadding = PaddingValues(bottom = 110.dp),
    ) {
        item(key = "header") {
            CoverAndIdentity(
                coverURL = home?.coverURL,
                creatorName = creatorName,
                creatorImage = creatorImage,
                isPartner = isPartner,
                memberCount = memberCount,
                activeThisWeek = home?.activeThisWeek,
                faces = top10.take(5).map { it to profiles[it.userID]?.profileImageURL },
                isCreator = isCreator,
                isLive = isLive,
                liveViewerCount = liveViewerCount,
                onBack = onBack,
                onShare = onShare,
                onSettings = onSettings,
                onJoinLive = onJoinLive,
            )
        }

        if (isCreator) {
            item(key = "owner") {
                OwnerActions(
                    memberCount = memberCount,
                    isLive = isLive,
                    onGoLive = onGoLive,
                    onPost = onRecord,
                )
                home?.owner?.let { ThisWeekCard(it.newMembers, it.posts, it.activeMembers) }
            }
        }

        if (interests != null && !interests.isEmpty) {
            item(key = "interests") {
                IntoRightNow(
                    picks = interests.picks,
                    hyped = interests.hyped,
                    watching = interests.watching,
                    onOpenPicks = onOpenPicks,
                    onOpenVideo = onOpenVideo,
                    onOpenWatching = onOpenWatching,
                )
            }
        }

        if (membership != null && !isCreator) {
            item(key = "you") { YouCard(membership = membership, onTap = onShowBadges) }
        }

        if (top10.isNotEmpty()) {
            item(key = "top") {
                TopMembersRow(
                    members = top10,
                    profiles = profiles,
                    onLeaderboard = onShowLeaderboard,
                )
            }
        }

        if (highlights.isNotEmpty()) {
            item(key = "highlights") { HighlightsRow(highlights, onOpenPost) }
        }

        item(key = "postsHeader") {
            SectionTitle("Posts", modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 4.dp))
        }

        if (posts.isEmpty()) {
            item(key = "empty") { EmptyPosts(onRecord) }
        } else {
            itemsIndexed(posts, key = { _, p -> p.id }) { index, post ->
                PostCard(
                    post = post,
                    authorImage = profiles[post.authorID]?.profileImageURL,
                    onOpen = { onOpenPost(post) },
                    onHype = { onHype(post) },
                    onStitch = { onStitch(post) },
                )
                // One partner slot, after the 3rd post (or the last, if fewer).
                if (index == adIndex && adsEnabled) {
                    com.stitchsocial.club.ads.NativeAdCard(
                        unitId = com.stitchsocial.club.ads.AdConfig.NativeUnit.community,
                        owner = adOwner,
                        placement = "community_partner_card",
                        compact = true,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1–2. Cover + identity (and 9. the live state)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CoverAndIdentity(
    coverURL: String?,
    creatorName: String,
    creatorImage: String?,
    isPartner: Boolean,
    memberCount: Int,
    activeThisWeek: Int?,
    faces: List<Pair<CommunityMembership, String?>>,
    isCreator: Boolean,
    isLive: Boolean,
    liveViewerCount: Int?,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit,
    onJoinLive: () -> Unit,
) {
    if (isLive) {
        // While live, the cover IS the stream: 330dp, LIVE tag, join button.
        Column {
            Cover(
                height = 330.dp,
                coverURL = coverURL,
                fallback = B.liveCoverFallback,
                fadeHeight = 140.dp,
                onBack = onBack,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "LIVE", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp,
                            color = Color.White,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(B.live)
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                        liveViewerCount?.let {
                            Text(
                                "${formatShort(it)} watching", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                },
                bottom = {
                    Box(
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
                            .fillMaxWidth()
                            .height(52.dp)
                            .shadow(12.dp, RoundedCornerShape(26.dp), ambientColor = B.live, spotColor = B.live)
                            .clip(RoundedCornerShape(26.dp))
                            .background(B.live)
                            .clickable { onJoinLive() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            // The owner's way back to their own stream (as before).
                            if (isCreator) "Back to your live" else "Join the live",
                            fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White
                        )
                    }
                },
            )
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RingAvatar(
                    url = creatorImage, name = creatorName, size = 60.dp,
                    ring = Brush.linearGradient(listOf(B.live, B.live)), ringWidth = 3.dp, gap = 2.dp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    NameRow(creatorName, isPartner, fontSize = 22)
                    MemberLine(memberCount, activeThisWeek)
                }
            }
        }
        return
    }

    Box {
        Cover(
            height = 230.dp,
            coverURL = coverURL,
            fallback = B.coverFallback,
            fadeHeight = 120.dp,
            onBack = onBack,
            trailing = {
                RoundButton(
                    onClick = if (isCreator) onSettings else onShare,
                    contentDescription = if (isCreator) "Community settings" else "Share community",
                ) {
                    Icon(
                        if (isCreator) Icons.Default.Tune else Icons.Default.IosShare,
                        contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)
                    )
                }
            },
            bottom = {},
        )
        // Identity, overlapping the cover by 64dp.
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 230.dp - 64.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RingAvatar(url = creatorImage, name = creatorName, size = 84.dp, ring = B.brand, ringWidth = 3.dp, gap = 3.dp)
            NameRow(creatorName, isPartner, fontSize = 26)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (faces.isNotEmpty()) {
                    FacePile(faces)
                    Spacer(Modifier.width(10.dp))
                }
                MemberLine(memberCount, activeThisWeek)
            }
        }
    }
}

@Composable
private fun Cover(
    height: Dp,
    coverURL: String?,
    fallback: Color,
    fadeHeight: Dp,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit,
    bottom: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(fallback)
    ) {
        if (coverURL != null) {
            AsyncImage(
                model = coverURL,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(fadeHeight)
                .background(Brush.verticalGradient(listOf(B.bg.copy(alpha = 0f), B.bg)))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(onClick = onBack, contentDescription = "Back") {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            bottom()
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, contentDescription: String, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(B.translucent)
            .clickable(onClickLabel = contentDescription) { onClick() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun NameRow(name: String, isPartner: Boolean, fontSize: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            name,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.4).sp,
            color = B.txt,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (isPartner) {
            Text(
                "PARTNER", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp,
                color = B.bg,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(B.magenta)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun MemberLine(memberCount: Int, activeThisWeek: Int?) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = B.txt, fontWeight = FontWeight.Bold)) { append(formatShort(memberCount)) }
            append(if (memberCount == 1) " member" else " members")
            if (activeThisWeek != null) {
                append(" · ")
                withStyle(SpanStyle(color = B.green, fontWeight = FontWeight.Bold)) { append(formatShort(activeThisWeek)) }
                append(" active this week")
            }
        },
        fontSize = 14.sp,
        color = B.txt2,
    )
}

/** Up to five overlapping 26dp avatars of the top members. */
@Composable
private fun FacePile(faces: List<Pair<CommunityMembership, String?>>) {
    val step = 18.dp
    Box(modifier = Modifier.width(step * (faces.size - 1) + 26.dp).height(26.dp)) {
        faces.forEachIndexed { i, (m, url) ->
            Box(
                modifier = Modifier
                    .offset(x = step * i)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(B.bg)
                    .padding(2.dp)
            ) {
                PersonImage(url = url, name = m.displayName.ifBlank { m.username }, size = 22.dp)
            }
        }
    }
}

/** A round avatar inside a [ring] of [ringWidth], separated from it by a [gap] of page colour. */
@Composable
private fun RingAvatar(url: String?, name: String, size: Dp, ring: Brush, ringWidth: Dp, gap: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(ring)
            .padding(ringWidth)
            .clip(CircleShape)
            .background(B.bg)
            .padding(gap),
    ) {
        PersonImage(url = url, name = name, size = size - (ringWidth + gap) * 2)
    }
}

/** Profile image, or initials on magenta. */
@Composable
private fun PersonImage(url: String?, name: String, size: Dp) {
    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).background(B.card),
        )
    } else {
        Box(
            modifier = Modifier.size(size).clip(CircleShape).background(B.magenta),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initials(name),
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
        }
    }
}

private fun initials(name: String): String {
    val parts = name.trim().removePrefix("@").split(" ").filter { it.isNotBlank() }
    val a = parts.getOrNull(0)?.firstOrNull()?.toString() ?: ""
    val b = parts.getOrNull(1)?.firstOrNull()?.toString() ?: ""
    return (a + b).uppercase().ifEmpty { "?" }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Owner actions + this week
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun OwnerActions(memberCount: Int, isLive: Boolean, onGoLive: () -> Unit, onPost: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // While live, the cover carries the way back to the stream instead.
            if (!isLive) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .shadow(10.dp, RoundedCornerShape(25.dp), ambientColor = B.live, spotColor = B.live)
                        .clip(RoundedCornerShape(25.dp))
                        .background(B.live)
                        .clickable { onGoLive() },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(9.dp))
                    Text("Go live", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .background(B.card)
                    .clickable { onPost() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Post", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        if (!isLive) {
            Text(
                "Going live sends a push to all ${formatShort(memberCount)} members",
                fontSize = 12.sp,
                color = B.txt3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ThisWeekCard(newMembers: Int, posts: Int, activeMembers: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(B.card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("THIS WEEK", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, color = B.txt2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeekStat(if (newMembers > 0) "+${formatShort(newMembers)}" else "0", "new members", B.green, Modifier.weight(1f))
            WeekStat(formatShort(posts), "posts", B.blueLight, Modifier.weight(1f))
            WeekStat(formatShort(activeMembers), "active members", B.gold, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WeekStat(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = color)
        Text(label, fontSize = 12.sp, color = B.txt2)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Into right now
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IntoRightNow(
    picks: InterestPicks?,
    hyped: List<InterestHypedVideo>,
    watching: List<InterestWatching>,
    onOpenPicks: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenWatching: (InterestWatching) -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Into right now", modifier = Modifier.padding(horizontal = 16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            picks?.let { PicksCard(it, onOpenPicks) }
            if (hyped.isNotEmpty()) HypedCard(hyped, onOpenVideo)
            watching.forEach { WatchingCard(it, onOpenWatching) }
        }
    }
}

@Composable
private fun InterestCard(
    background: Color,
    border: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .width(210.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .padding(14.dp),
        content = content,
    )
}

@Composable
private fun CardEyebrow(text: String, color: Color) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, color = color)
}

@Composable
private fun PicksCard(picks: InterestPicks, onOpenPicks: (String) -> Unit) {
    InterestCard(
        background = Color(0xFF10241A),
        border = B.green.copy(alpha = 0.35f),
    ) {
        CardEyebrow("STITCH PICKS", B.green)
        Spacer(Modifier.height(8.dp))
        picks.lastNight?.let { night ->
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${night.right}/${night.total}", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = B.txt)
                Text("last night", fontSize = 13.sp, color = B.txt2, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (night.results.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    night.results.forEach { right ->
                        Box(
                            Modifier
                                .weight(1f, fill = false)
                                .width(26.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (right) B.green else B.wrong)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            buildAnnotatedString {
                append("This week ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(if (picks.weekCashed == 1) "1 card cashed" else "${picks.weekCashed} cards cashed")
                }
                append(" of ${picks.weekCards}")
            },
            fontSize = 13.sp,
            color = B.txtSoft,
        )
        picks.landingSlug?.let { slug ->
            Spacer(Modifier.height(8.dp))
            Text(
                "See tonight's card ›",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = B.green,
                modifier = Modifier.clickable { onOpenPicks(slug) },
            )
        }
    }
}

@Composable
private fun HypedCard(videos: List<InterestHypedVideo>, onOpenVideo: (String) -> Unit) {
    InterestCard(
        background = Color(0xFF2A1020),
        border = B.wrong.copy(alpha = 0.35f),
    ) {
        CardEyebrow("RECENTLY HYPED", B.wrong)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            videos.take(3).forEach { v ->
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 84.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF4A1A30))
                        .clickable { onOpenVideo(v.videoID) },
                ) {
                    v.thumbnailURL?.let {
                        AsyncImage(model = it, contentDescription = v.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Videos they hyped this week", fontSize = 12.sp, color = B.txt2)
    }
}

@Composable
private fun WatchingCard(w: InterestWatching, onOpen: (InterestWatching) -> Unit) {
    Row(
        modifier = Modifier
            .width(210.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF16182E))
            .border(1.dp, B.blueLight.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .then(if (w.collectionID != null) Modifier.clickable { onOpen(w) } else Modifier)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2C3460)),
        ) {
            w.coverURL?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardEyebrow("WATCHING", B.blueLight)
            Text(
                w.title.ifBlank { w.episodeTitle },
                fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = B.txt,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            w.seasonEpisodeLabel?.let { Text(it, fontSize = 12.sp, color = B.txt2) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. You
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun YouCard(membership: CommunityMembership, onTap: () -> Unit) {
    val level = membership.level.coerceAtLeast(1)
    val xp = membership.localXP
    val floor = CommunityXPCurve.totalXPForLevel(level)
    val next = CommunityXPCurve.totalXPForLevel(level + 1)
    val toNext = (next - xp).coerceAtLeast(0)
    val progress = if (next > floor) ((xp - floor).toFloat() / (next - floor)).coerceIn(0f, 1f) else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(B.card)
            .clickable { onTap() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(46.dp).clip(CircleShape).background(B.blue),
            contentAlignment = Alignment.Center,
        ) {
            Text("Lv$level", fontSize = if (level >= 100) 12.sp else 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Your level", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = B.txt)
                Spacer(Modifier.weight(1f))
                Text("${formatShort(toNext)} XP to Lv ${level + 1}", fontSize = 13.sp, color = B.txt2)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.1f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .clip(RoundedCornerShape(4.dp))
                        .background(B.brandH)
                )
            }
        }
        if (membership.dailyLoginStreak > 0) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("${membership.dailyLoginStreak}", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = B.streak)
                Text("day streak", fontSize = 10.sp, color = B.txt2)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. Top members
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TopMembersRow(
    members: List<CommunityMembership>,
    profiles: Map<String, CommunityUserProfile>,
    onLeaderboard: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("Top members", modifier = Modifier.weight(1f))
            Text(
                "Leaderboard",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = B.blue,
                modifier = Modifier.clickable { onLeaderboard() },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            members.forEachIndexed { i, m ->
                val rank = i + 1
                val ring = B.rankRing(rank)
                val profile = profiles[m.userID]
                val username = profile?.username ?: m.username
                Column(
                    modifier = Modifier.width(62.dp).clickable { onLeaderboard() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(modifier = Modifier.size(width = 62.dp, height = 69.dp)) {
                        RingAvatar(
                            url = profile?.profileImageURL,
                            name = profile?.displayName ?: m.displayName.ifBlank { username },
                            size = 62.dp,
                            ring = Brush.linearGradient(listOf(ring, ring)),
                            ringWidth = 2.dp,
                            gap = 2.dp,
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .size(width = 30.dp, height = 19.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(B.bg)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(ring),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("#$rank", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "@$username",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = B.txt,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 7. Highlights
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HighlightsRow(posts: List<CommunityPost>, onOpenPost: (CommunityPost) -> Unit) {
    Column(
        modifier = Modifier.padding(top = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Highlights", modifier = Modifier.padding(horizontal = 16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            posts.forEach { post ->
                Box(
                    modifier = Modifier
                        .size(width = 112.dp, height = 168.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF3A1430))
                        .clickable { onOpenPost(post) },
                ) {
                    post.videoThumbnailURL?.takeIf { it.isNotBlank() }?.let {
                        AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(60.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                    )
                    Row(
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = B.wrong, modifier = Modifier.size(12.dp))
                        Text(formatShort(post.hypeCount), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 8. Posts
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PostCard(
    post: CommunityPost,
    authorImage: String?,
    onOpen: () -> Unit,
    onHype: () -> Unit,
    onStitch: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onOpen() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PersonImage(url = authorImage, name = post.authorDisplayName.ifBlank { post.authorUsername }, size = 38.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        post.authorDisplayName.ifBlank { "@${post.authorUsername}" },
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = B.txt,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (post.isCreatorPost) {
                        Text("HOST", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = B.magenta)
                    }
                }
                Text(
                    (if (post.isPinned) "Pinned · " else "") + timeAgoShort(post.createdAt),
                    fontSize = 12.sp,
                    color = B.txt3,
                )
            }
        }

        val thumb = post.videoThumbnailURL?.takeIf { it.isNotBlank() }
        if (post.isVideo || thumb != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF24142E))
                    .clickable { onOpen() },
                contentAlignment = Alignment.Center,
            ) {
                thumb?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (post.isVideo) {
                    Box(
                        modifier = Modifier.size(62.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
                if (post.body.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                            .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp)
                    ) {
                        Text(
                            post.body, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp,
                            color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else if (post.body.isNotBlank()) {
            Text(
                post.body,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = B.txtSoft,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(B.card)
                    .clickable { onOpen() }
                    .padding(16.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(Color(0xFFFF4D4D).copy(alpha = 0.16f))
                    .clickable { onHype() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = B.wrong, modifier = Modifier.size(16.dp))
                Text("Hype ${formatShort(post.hypeCount)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = B.wrong)
            }
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(B.blue.copy(alpha = 0.16f))
                    .clickable { onStitch() }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Stitch · ${formatShort(post.replyCount)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = B.blueLight)
            }
        }
    }
}

@Composable
private fun EmptyPosts(onRecord: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("No posts yet", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = B.txt)
        Text(
            "Be the first. Record a clip for this community.",
            fontSize = 14.sp,
            color = B.txt2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(B.magenta)
                .clickable { onRecord() }
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Record a post", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = B.txt, modifier = modifier)
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** "now" / "5m" / "3h" / "2d" — compact age. */
private fun timeAgoShort(date: Date): String {
    val diff = ((System.currentTimeMillis() - date.time) / 1000).coerceAtLeast(0)
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86_400 -> "${diff / 3600}h"
        else -> "${diff / 86_400}d"
    }
}

private fun formatShort(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

/**
 * Level handed to the live viewer for its video-reply gate.
 *
 * Was the raw `membership.level`, which meant an Ambassador — or the community's
 * own owner, once privileges stopped inflating level — couldn't send a video
 * reply until they'd ground to the gate in that specific room.
 *
 * Ambassador+ is a global rank, so it satisfies a per-community gate the same
 * way the Spark grant satisfies the stream-duration ladder.
 */
private fun liveFeatureLevel(
    membership: CommunityMembership?,
    viewerTier: UserTier?
): Int {
    val base = membership?.effectiveFeatureLevel ?: 1
    return if (viewerTier?.unlocksVideoReplies == true) {
        maxOf(base, VideoComment.MINIMUM_LEVEL)
    } else base
}
