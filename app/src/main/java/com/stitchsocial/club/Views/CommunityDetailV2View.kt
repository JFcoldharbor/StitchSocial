/*
 * CommunityDetailV2View.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Community Detail Screen (V2)
 *
 * Mirrors iOS CommunityDetailV2View.swift — TabView pattern with Home +
 * Threads tabs, sticky header, contextual FAB, bottom tab bar. Replaces the
 * v1 single-scroll layout that lived in CommunityDetailView.kt.
 *
 * Surface:
 *  - V2 design tokens (private object)
 *  - V2Tab enum + V2TabBar + V2FAB
 *  - CommunityDetailV2View (root composable, replaces V1)
 *  - CommunityHomeTab (LiveBanner/GoLiveCTA + StatsRow + 4 preview cards
 *    + QuickLinks)
 *  - CommunityThreadsTab + CommunityThreadRow (compact feed rows per the
 *    handoff spec, screen 1e — no longer V1's boxed PostCard)
 *  - GoLiveCTACard / LiveBannerCard / StatsRow / LeaderboardPreviewCard /
 *    TopSupportersPreviewCard / BadgesPreviewCard / HighlightReelPreviewCard
 *    / QuickLinksRow
 *
 * Reuses from V1:
 *  - CreatorHeaderCard (sticky header)
 *  - ComposePostSheet (compose flow)
 *  - parseMembership / parsePost helpers
 */

package com.stitchsocial.club.views

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.itemsIndexed
import coil.compose.AsyncImage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.community.CommunityListItem
import com.stitchsocial.club.community.CommunityMembership
import com.stitchsocial.club.community.CommunityPost
import com.stitchsocial.club.community.CommunityPostType
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

// ─────────────────────────────────────────────────────────────────────────────
// Design tokens — mirrors iOS V2 palette
// ─────────────────────────────────────────────────────────────────────────────

private object V2 {
    val bg = Color(0xFF0F0B1E)
    val card = Color(0xFF1A1432)
    val cardBorder = Color.White.copy(alpha = 0.08f)
    val cyan = Color(0xFF22D3EE)   // handoff cyan
    val purple = Color(0xFFA78BFA) // handoff purple
    val pink = Color(0xFFF0245F)   // handoff brand pink
    val orange = Color(0xFFF59E0B)
    val gold = Color(0xFFFACC15)   // handoff gold
    val red = Color(0xFFEF4444)
    val green = Color(0xFF10B981)
    val txt = Color(0xFFF1F5F9)
    val txt2 = Color(0xFF94A3B8)
    val txt3 = Color(0xFF64748B)
}

private enum class V2Tab(val label: String, val icon: ImageVector, val fabIcon: ImageVector) {
    // Both tabs share the Edit/pencil FAB icon — tapping it on either tab
    // opens the post composer. Go Live is creator-only and lives behind
    // the GoLiveCTACard on the Home tab (not behind this FAB).
    HOME(label = "Home", icon = Icons.Default.Home, fabIcon = Icons.Default.Edit),
    THREADS(label = "Threads", icon = Icons.Default.QuestionAnswer, fabIcon = Icons.Default.Edit),
}

// ─────────────────────────────────────────────────────────────────────────────
// Root composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CommunityDetailV2View(
    userID: String,
    communityID: String,
    communityItem: CommunityListItem,
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

    var membership by remember { mutableStateOf<CommunityMembership?>(null) }
    var topMembers by remember { mutableStateOf<List<CommunityMembership>>(emptyList()) }
    var posts by remember { mutableStateOf<List<CommunityPost>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(V2Tab.HOME) }
    var showingComposer by remember { mutableStateOf(false) }
    // The clip the app recorder handed back for a NEW thread in this channel.
    var recordedClipUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedPost by remember { mutableStateOf<CommunityPost?>(null) }
    var showingGoLive by remember { mutableStateOf(false) }
    var showingLiveStream by remember { mutableStateOf(false) }
    var showingLeaderboard by remember { mutableStateOf(false) }
    var showingSupporters by remember { mutableStateOf(false) }
    var showingBadges by remember { mutableStateOf(false) }
    var showingHighlight by remember { mutableStateOf(false) }

    // Live state — mirrored from snapshot listener + verified against the
    // actual stream doc (handles ghost flags on the community doc).
    var isCreatorLiveRealtime by remember { mutableStateOf(communityItem.isCreatorLive) }
    var liveStreamID by remember { mutableStateOf<String?>(null) }

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
            .addSnapshotListener { snap, _ ->
                val data = snap?.data ?: return@addSnapshotListener
                isCreatorLiveRealtime = data["isCreatorLive"] as? Boolean ?: false
                liveStreamID = data["activeStreamID"] as? String
            }
        onDispose { listener.remove() }
    }

    // Data load
    LaunchedEffect(communityID) {
        isLoading = true
        try {
            val memDoc = db.collection("communities").document(communityID)
                .collection("members").document(userID).get().await()
            if (memDoc.exists()) {
                membership = parseMembership(memDoc.id, memDoc.data ?: emptyMap())
            }

            val membersSnap = db.collection("communities").document(communityID)
                .collection("members")
                .orderBy("localXP", Query.Direction.DESCENDING)
                .limit(10).get().await()
            topMembers = membersSnap.documents.mapNotNull { d ->
                parseMembership(d.id, d.data ?: emptyMap())
            }

            val postsSnap = db.collection("communities").document(communityID)
                .collection("posts")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(20).get().await()
            posts = postsSnap.documents.mapNotNull { d ->
                parsePost(d.id, d.data ?: emptyMap())
            }
        } catch (_: Exception) {
        } finally {
            isLoading = false
        }
    }

    // The root Box absorbs all unhandled taps so they don't fall through to
    // the underlying Discovery screen (the parent MainActivity Box renders
    // this on top via zIndex but Compose doesn't auto-consume gestures —
    // any tap landing on empty padding would otherwise hit Discovery cards).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(V2.bg)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { /* consume */ })
            }
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = V2.cyan, modifier = Modifier.size(36.dp))
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Sticky header — reuses V1 CreatorHeaderCard.
                CreatorHeaderCard(
                    communityItem = communityItem,
                    memberCount = topMembers.size,
                    isCreator = isCreator,
                    onBack = onDismiss,
                )

                Box(modifier = Modifier.weight(1f)) {
                    when (tab) {
                        V2Tab.HOME -> CommunityHomeTab(
                            communityItem = communityItem,
                            membership = membership,
                            topMembers = topMembers,
                            isCreator = isCreator,
                            isCreatorLive = isCreatorLiveRealtime,
                            onJoinLive = {
                                if (liveStreamID != null) showingLiveStream = true
                            },
                            onGoLive = { showingGoLive = true },
                            onShowLeaderboard = { showingLeaderboard = true },
                            onShowSupporters = { showingSupporters = true },
                            onShowBadges = { showingBadges = true },
                            onShowHighlight = { showingHighlight = true },
                        )
                        V2Tab.THREADS -> CommunityThreadsTab(
                            posts = posts,
                            onSelectPost = { selectedPost = it },
                            onHype = { post ->
                                scope.launch {
                                    runCatching {
                                        db.collection("communities").document(communityID)
                                            .collection("posts").document(post.id)
                                            .update(
                                                "hypeCount",
                                                com.google.firebase.firestore.FieldValue.increment(1L)
                                            ).await()
                                        posts = posts.map { p ->
                                            if (p.id == post.id) p.copy(hypeCount = p.hypeCount + 1) else p
                                        }
                                    }
                                }
                            },
                            onCompose = { onRecordClip(communityID, null) },
                        )
                    }
                }

                // Channel nav — Home/Threads pills + record FAB beside them
                // (tightening pass; replaces the full-width tab bar + floating FAB).
                // The record FAB now opens the SAME recorder as the main + button
                // (iOS parity, e6f51f6) — not a Record/Pick menu.
                V2ChannelNav(
                    active = tab,
                    onSelect = { tab = it },
                    onRecord = { onRecordClip(communityID, null) },
                )
            }
        }

        // Fullscreen overlays — drill-downs opened from preview cards / chips
        if (showingLeaderboard) {
            MemberLeaderboardViewV2(
                topMembers = topMembers,
                initialSort = LeaderboardSortV2.LEVEL,
                onDismiss = { showingLeaderboard = false },
            )
        }
        if (showingSupporters) {
            MemberLeaderboardViewV2(
                topMembers = topMembers,
                initialSort = LeaderboardSortV2.HYPES_GIVEN,
                onDismiss = { showingSupporters = false },
            )
        }
        if (showingBadges) {
            BadgeGalleryViewV2(
                currentLevel = membership?.level ?: 1,
                earnedBadgeIDs = membership?.earnedBadgeIDs ?: emptyList(),
                onDismiss = { showingBadges = false },
            )
        }
        if (showingHighlight) {
            HighlightPlayerViewV2(
                communityName = communityItem.creatorDisplayName,
                onDismiss = { showingHighlight = false },
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
                userLevel = membership?.effectiveFeatureLevel ?: 1,
                userUsername = membership?.username ?: "user",
                userDisplayName = membership?.displayName ?: membership?.username ?: "User",
                onDismiss = { showingLiveStream = false },
            )
        }
        if (showingGoLive && isCreator) {
            com.stitchsocial.club.live.LiveStreamCreatorScreen(
                creatorID = communityID,
                creatorUsername = membership?.username ?: communityItem.creatorUsername,
                creatorDisplayName = membership?.displayName ?: communityItem.creatorDisplayName,
                onDismiss = { showingGoLive = false },
            )
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
                // Optimistic: prepend so the new thread shows up immediately
                // on the Threads tab without needing a reload.
                posts = listOf(newPost) + posts
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
            onDeleted = { deleted -> posts = posts.filter { it.id != deleted.id } },
            onRecordClip = { cid, postID -> onRecordClip(cid, postID) },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Home Tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CommunityHomeTab(
    communityItem: CommunityListItem,
    membership: CommunityMembership?,
    topMembers: List<CommunityMembership>,
    isCreator: Boolean,
    isCreatorLive: Boolean,
    onJoinLive: () -> Unit,
    onGoLive: () -> Unit,
    onShowLeaderboard: () -> Unit,
    onShowSupporters: () -> Unit,
    onShowBadges: () -> Unit,
    onShowHighlight: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 6.dp, bottom = 24.dp),
    ) {
        when {
            isCreatorLive -> LiveBannerCard(
                creatorName = communityItem.creatorDisplayName,
                onJoin = onJoinLive,
            )
            isCreator -> GoLiveCTACard(onTap = onGoLive)
        }

        StatsRow(
            communityItem = communityItem,
            membership = membership,
        )

        LeaderboardPreviewCard(topMembers = topMembers, membership = membership, onTap = onShowLeaderboard)
        // Collapse empty cards (tightening pass) — don't render Supporters/Badges when empty.
        if (topMembers.any { it.totalHypesGiven > 0 }) {
            TopSupportersPreviewCard(topMembers = topMembers, onTap = onShowSupporters)
        }

        val badgeCount = membership?.earnedBadgeIDs?.size ?: 0
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (badgeCount > 0) {
                BadgesPreviewCard(
                    earnedCount = badgeCount,
                    modifier = Modifier.weight(1f),
                    onTap = onShowBadges,
                )
            }
            HighlightReelPreviewCard(
                modifier = Modifier.weight(1f),
                onTap = onShowHighlight,
            )
        }
        // QuickLinksRow removed in the tightening pass (duplicate chips).
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Threads Tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CommunityThreadsTab(
    posts: List<CommunityPost>,
    onSelectPost: (CommunityPost) -> Unit,
    onHype: (CommunityPost) -> Unit,
    onCompose: () -> Unit,
) {
    if (posts.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("💬", fontSize = 36.sp)
            Spacer(Modifier.height(8.dp))
            Text("No threads yet", fontSize = 15.sp, color = V2.txt2)
            Text(
                "Tap the pencil to start one",
                fontSize = 12.sp,
                color = V2.txt3,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        return
    }
    // Feed rows per the handoff spec (screen 1e): compact rows separated by
    // hairline rules, not V1's boxed PostCard with a full-width 9:16 thumbnail.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 20.dp),
    ) {
        itemsIndexed(posts, key = { _, p -> p.id }) { index, post ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.06f))
                )
            }
            CommunityThreadRow(
                post = post,
                onHype = { onHype(post) },
                onTap = { onSelectPost(post) },
            )
        }
    }
}

/**
 * One thread in the channel feed. Spec (1e): 72x96 rounded-12 thumbnail, 20pt
 * author avatar, handle + age, title, then a metric row of hype + stitches.
 * Rows older than a day render at 60% opacity so today's activity reads first.
 */
@Composable
private fun CommunityThreadRow(
    post: CommunityPost,
    onHype: () -> Unit,
    onTap: () -> Unit,
) {
    val isOlder = System.currentTimeMillis() - post.createdAt.time > 86_400_000L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isOlder) 0.6f else 1f)
            .clickable { onTap() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!post.videoThumbnailURL.isNullOrEmpty()) {
                AsyncImage(
                    model = post.videoThumbnailURL,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (post.postType == CommunityPostType.VIDEO_CLIP) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MemberAvatar(name = post.authorDisplayName, size = 20)
                Text(
                    "@${post.authorUsername}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    timeAgoShort(post.createdAt),
                    fontSize = 10.5.sp,
                    color = Color.White.copy(alpha = 0.35f),
                )
                if (post.isPinned) Text("📌", fontSize = 11.sp)
            }

            if (post.body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    post.body,
                    fontSize = 12.5.sp,
                    color = Color.White.copy(alpha = 0.78f),
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MetricChip(
                    icon = Icons.Default.LocalFireDepartment,
                    label = "${post.hypeCount}",
                    onTap = onHype,
                )
                MetricChip(
                    icon = Icons.Default.ContentCut,
                    label = if (post.replyCount == 1) "1 stitch" else "${post.replyCount} stitches",
                )
            }
        }
    }
}

@Composable
private fun MetricChip(
    icon: ImageVector,
    label: String,
    onTap: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = if (onTap != null) Modifier.clickable { onTap() } else Modifier,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(13.dp),
        )
        Text(
            label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

/** "now" / "5m" / "3h" / "2d" — the compact age the spec's feed rows use. */
private fun timeAgoShort(date: Date): String {
    val diff = ((System.currentTimeMillis() - date.time) / 1000).coerceAtLeast(0)
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86_400 -> "${diff / 3600}h"
        else -> "${diff / 86_400}d"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Home Tab — Cards
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GoLiveCTACard(onTap: () -> Unit) {
    // Subtle pulse on the icon — mirrors iOS animation.
    val transition = rememberInfiniteTransition(label = "goLivePulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    listOf(V2.red.copy(alpha = 0.18f), V2.orange.copy(alpha = 0.08f))
                )
            )
            .border(0.5.dp, V2.red.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .clickable { onTap() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size((44f * pulse).dp)
                .clip(CircleShape)
                .background(V2.red.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Podcasts,
                contentDescription = null,
                tint = V2.red,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Start a Live",
                color = V2.txt,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Your community gets a push the moment you go on",
                color = V2.txt2,
                fontSize = 11.sp,
            )
        }
        Text("›", color = V2.txt3, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LiveBannerCard(creatorName: String, onJoin: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "liveDot")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    listOf(V2.red.copy(alpha = 0.18f), V2.purple.copy(alpha = 0.10f))
                )
            )
            .border(0.5.dp, V2.red.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(V2.red.copy(alpha = dotAlpha), CircleShape),
                )
                Text("LIVE NOW", color = V2.red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "$creatorName is LIVE!",
                color = V2.txt,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(V2.red)
                .clickable { onJoin() }
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text("Join", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatsRow(
    communityItem: CommunityListItem,
    membership: CommunityMembership?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatBox(
            num = formatShort(communityItem.memberCount),
            label = "MEMBERS",
            color = V2.cyan,
            modifier = Modifier.weight(1f),
        )
        // "YOUR LEVEL" → "TODAY": level already shows in the leaderboard, so the
        // tile was redundant. APPROX: no real posts-today signal yet (client proxy).
        StatBox(
            num = "0",
            label = "TODAY",
            color = V2.gold,
            modifier = Modifier.weight(1f),
        )
        StatBox(
            num = "${membership?.dailyLoginStreak ?: 0}d",
            label = "STREAK",
            color = V2.purple,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatBox(
    num: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(V2.card)
            .border(0.5.dp, V2.cardBorder, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(num, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = V2.txt3, fontSize = 8.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun QuickLinksRow(
    onLeaderboard: () -> Unit,
    onBadges: () -> Unit,
    onSupporters: () -> Unit,
    onHighlight: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(Icons.Default.BarChart, "Leaderboard", onLeaderboard)
        Chip(Icons.Default.Star, "Badges", onBadges)
        Chip(Icons.Default.Favorite, "Supporters", onSupporters)
        Chip(Icons.Default.AutoAwesome, "Highlights", onHighlight)
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(V2.card)
            .border(0.5.dp, V2.cardBorder, RoundedCornerShape(8.dp))
            .clickable { onTap() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = V2.txt2, modifier = Modifier.size(12.dp))
        Text(label, color = V2.txt2, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LeaderboardPreviewCard(
    topMembers: List<CommunityMembership>,
    membership: CommunityMembership?,
    onTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(V2.card)
            .border(0.5.dp, V2.cardBorder, RoundedCornerShape(14.dp))
            .clickable { onTap() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BarChart, null, tint = V2.cyan, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text("Leaderboard", color = V2.txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("See all", color = V2.cyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        if (topMembers.isEmpty()) {
            Text("No members ranked yet", color = V2.txt3, fontSize = 11.sp)
        } else {
            topMembers.take(3).forEachIndexed { idx, m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${idx + 1}", color = V2.txt3, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("@${m.username}", color = V2.txt, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text("Lv ${m.level}", color = V2.cyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // Your own standing — always shown, in brand pink, separated by a rule, so
        // you see where you rank even outside the top 3 (tightening pass).
        membership?.let { me ->
            val myRank = topMembers.indexOfFirst { it.userID == me.userID }.takeIf { it >= 0 }?.plus(1)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(myRank?.toString() ?: "—", color = V2.pink, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("You", color = V2.pink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("Lv ${me.level}", color = V2.pink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TopSupportersPreviewCard(
    topMembers: List<CommunityMembership>,
    onTap: () -> Unit,
) {
    val sorted = remember(topMembers) {
        topMembers.sortedByDescending { it.totalHypesGiven }
    }
    val medals = listOf("🥇", "🥈", "🥉")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(V2.card)
            .border(0.5.dp, V2.cardBorder, RoundedCornerShape(14.dp))
            .clickable { onTap() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, null, tint = V2.orange, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text("Top supporters", color = V2.txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("See all", color = V2.orange, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        val top3 = sorted.take(3)
        if (top3.isEmpty() || top3.all { it.totalHypesGiven == 0 }) {
            Text("No supporters yet — be the first", color = V2.txt3, fontSize = 11.sp)
        } else {
            top3.forEachIndexed { idx, m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(medals[idx.coerceAtMost(2)], fontSize = 13.sp, modifier = Modifier.width(20.dp))
                    Text("@${m.username}", color = V2.txt, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text("${m.totalHypesGiven} 🔥", color = V2.orange, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun BadgesPreviewCard(
    earnedCount: Int,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(V2.card)
            .border(0.5.dp, V2.cardBorder, RoundedCornerShape(14.dp))
            .clickable { onTap() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Star, null, tint = V2.gold, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Badges", color = V2.txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(earnedCount.toString(), color = V2.gold, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("earned", color = V2.txt3, fontSize = 10.sp)
    }
}

@Composable
private fun HighlightReelPreviewCard(
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(V2.card)
            .border(0.5.dp, V2.cardBorder, RoundedCornerShape(14.dp))
            .clickable { onTap() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = V2.purple, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Highlights", color = V2.txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Icon(Icons.Default.PlayCircleFilled, null, tint = V2.purple, modifier = Modifier.size(24.dp))
        Text("Tap to watch", color = V2.txt3, fontSize = 10.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab Bar + FAB
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun V2ChannelNav(active: V2Tab, onSelect: (V2Tab) -> Unit, onRecord: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(V2.bg.copy(alpha = 0.98f))
            .border(0.5.dp, V2.cardBorder, RoundedCornerShape(0.dp))
            // The app tab bar used to sit on top of this row, so it never needed
            // insets. Now that Community is genuinely full screen the nav is the
            // bottom-most chrome and has to clear the system nav / gesture bar.
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(V2Tab.HOME, V2Tab.THREADS).forEach { t ->
            val isActive = active == t
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isActive) Color.White else Color.White.copy(alpha = 0.09f))
                    .clickable { onSelect(t) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label,
                    color = if (isActive) Color(0xFF0A0A0D) else Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                )
            }
        }
        // Record FAB (pink) beside the pills.
        V2FAB(icon = active.fabIcon, onClick = onRecord)
    }
}

@Composable
private fun V2FAB(icon: ImageVector, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = V2.pink,   // record FAB recolored cyan → pink (tightening pass)
        shape = CircleShape,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatShort(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
