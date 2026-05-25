/*
 * CommunityDetailView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Community Detail Screen
 * Mirrors: CommunityDetailView.swift (iOS) — FULL PARITY
 *
 * FIXED vs previous:
 *   - Discussion card: shows pinned/creator post first; "Start One" CTA when empty
 *   - Upper Flow card: onTap opens MemberLeaderboardView sheet (sort by level)
 *   - Top Supporters: onTap opens MemberLeaderboardView (sort by hypeGiven)
 *   - Badge Holders: onTap opens BadgeGalleryView sheet
 *   - Creator header: Go Live FAB for isCreator
 *   - leaderboardSort state added
 *
 * CACHING:
 *   - topMembers: fetched once (limit 10), session-scoped
 *   - posts: fetched once (limit 20), session-scoped
 *   - membership: fetched once on open
 *   - Single Firestore query for members powers cards 3+4+6
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.stitchsocial.club.community.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import com.stitchsocial.club.BuildConfig

// MARK: - Colors (matches iOS mockup exactly)
private val darkBg       = Color(0xFF0D0F1A)
private val cardBg       = Color(0xFF161929)
private val cardBorder   = Color(0xFF2A2F4A)
private val accentCyan   = Color(0xFF00D4FF)
private val accentOrange = Color(0xFFFF8C42)
private val accentPurple = Color(0xFF9C5FFF)
private val accentGold   = Color(0xFFFFD700)
private val accentPink   = Color(0xFFFF4F9A)
private val textPrimary  = Color.White
private val textSecondary = Color(0xFF8B8FA3)
private val textMuted    = Color(0xFF5A5E72)

// MARK: - Leaderboard Sort (matches iOS LeaderboardSort)

enum class LeaderboardSort(val label: String) {
    LEVEL("Level"),
    HYPES_GIVEN("Hype Given"),
    HYPES_RECEIVED("Hype Received"),
    POSTS("Posts"),
    STREAMS("Streams Attended")
}

// MARK: - CommunityDetailView

@Composable
fun CommunityDetailView(
    userID: String,
    communityID: String,
    communityItem: CommunityListItem,
    onDismiss: () -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance("stitchfin") }
    val scope = rememberCoroutineScope()

    var membership by remember { mutableStateOf<CommunityMembership?>(null) }
    var topMembers by remember { mutableStateOf<List<CommunityMembership>>(emptyList()) }
    var posts by remember { mutableStateOf<List<CommunityPost>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showingComposer by remember { mutableStateOf(false) }
    var selectedPost by remember { mutableStateOf<CommunityPost?>(null) }

    // Drill-down sheets — matches iOS @State vars
    var showingLeaderboard by remember { mutableStateOf(false) }
    var showingSupporters by remember { mutableStateOf(false) }
    var showingBadges by remember { mutableStateOf(false) }
    var showingHighlight by remember { mutableStateOf(false) }
    var leaderboardSort by remember { mutableStateOf(LeaderboardSort.LEVEL) }

    // Live stream state — mirrored from a snapshot listener on the community
    // doc so the WATCH pill updates in real time as the creator goes on/off.
    var isCreatorLiveRealtime by remember { mutableStateOf(communityItem.isCreatorLive) }
    var liveStreamID by remember { mutableStateOf<String?>(null) }
    var showingLiveStream by remember { mutableStateOf(false) }
    var showingGoLive by remember { mutableStateOf(false) }

    val isCreator = userID == communityID

    // Initial ghost-recovery check — runs ONCE on community open. Both
    // creator + non-creator paths verify the stream doc actually exists +
    // is LIVE before trusting the community doc's `isCreatorLive` flag.
    //
    // CRITICAL: this used to fire on every listener tick which meant the
    // moment a creator tapped Go Live, the listener fired with
    // isCreatorLive=true and the recovery promptly force-ended the freshly-
    // started stream. Now it's mount-only + verified against the stream doc.
    LaunchedEffect(communityID) {
        val docSnap = runCatching {
            db.collection("communities").document(communityID).get().await()
        }.getOrNull() ?: return@LaunchedEffect
        val data = docSnap.data ?: return@LaunchedEffect
        val flaggedLive = data["isCreatorLive"] as? Boolean ?: false
        val flaggedStreamID = data["activeStreamID"] as? String

        if (!flaggedLive) return@LaunchedEffect

        // Verify the actual stream doc is LIVE before trusting the flag.
        val verified = com.stitchsocial.club.live.LiveStreamService
            .getInstance().fetchActiveStream(creatorID = communityID)

        if (verified != null && verified.id == flaggedStreamID) {
            // Real session — leave it.
            return@LaunchedEffect
        }

        if (BuildConfig.DEBUG) {
            println("🧹 GHOST: $communityID flagged live but no LIVE stream doc found")
        }
        if (isCreator) {
            // I'm the creator → I have write perms, clean up the doc.
            com.stitchsocial.club.live.LiveStreamService.getInstance()
                .forceEndStream(creatorID = communityID)
        }
        // Either way, suppress the WATCH pill locally for this session.
        isCreatorLiveRealtime = false
        liveStreamID = null
    }

    // Live-state listener — passive UI sync only. No ghost recovery here;
    // that runs once in the LaunchedEffect above. Listener just keeps
    // `isCreatorLiveRealtime` and `liveStreamID` in sync with Firestore so
    // the WATCH pill reflects reality in real time as the creator goes on/off.
    DisposableEffect(communityID) {
        val listener = db.collection("communities").document(communityID)
            .addSnapshotListener { snap, _ ->
                val data = snap?.data ?: return@addSnapshotListener
                isCreatorLiveRealtime = data["isCreatorLive"] as? Boolean ?: false
                liveStreamID = data["activeStreamID"] as? String
            }
        onDispose { listener.remove() }
    }

    // Load data — single batch: membership + top members + posts
    LaunchedEffect(communityID) {
        isLoading = true
        try {
            val memDoc = db.collection("communities").document(communityID)
                .collection("members").document(userID).get().await()
            if (memDoc.exists()) membership = parseMembership(memDoc.id, memDoc.data ?: emptyMap())

            val membersSnap = db.collection("communities").document(communityID)
                .collection("members")
                .orderBy("localXP", Query.Direction.DESCENDING)
                .limit(10).get().await()
            topMembers = membersSnap.documents.mapNotNull { doc ->
                parseMembership(doc.id, doc.data ?: emptyMap())
            }

            val postsSnap = db.collection("communities").document(communityID)
                .collection("posts")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(20).get().await()
            posts = postsSnap.documents.mapNotNull { doc ->
                parsePost(doc.id, doc.data ?: emptyMap())
            }

            if (BuildConfig.DEBUG) { println("✅ COMMUNITY DETAIL: ${topMembers.size} members, ${posts.size} posts") }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("❌ COMMUNITY DETAIL: Load failed — ${e.message}") }
        } finally {
            isLoading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(darkBg)) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accentCyan, modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                // 1. Creator Header
                item { CreatorHeaderCard(communityItem = communityItem, memberCount = topMembers.size, isCreator = isCreator, onBack = onDismiss) }

                // 2. XP Progress
                item { XPProgressCard(membership = membership, communityItem = communityItem) }

                // 3. Discussion + Upper Flow
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DiscussionCard(
                            posts = posts,
                            modifier = Modifier.weight(1f),
                            onStartPost = { showingComposer = true }
                        )
                        UpperFlowCard(
                            topMembers = topMembers,
                            modifier = Modifier.weight(1f),
                            onTap = { leaderboardSort = LeaderboardSort.LEVEL; showingLeaderboard = true }
                        )
                    }
                }

                // 4. Highlight Reel + Top Supporters
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HighlightReelCard(modifier = Modifier.weight(1f), onTap = { showingHighlight = true })
                        TopSupportersCard(
                            topMembers = topMembers,
                            modifier = Modifier.weight(1f),
                            onTap = { leaderboardSort = LeaderboardSort.HYPES_GIVEN; showingSupporters = true }
                        )
                    }
                }

                // 5. Live Now Banner
                item {
                    LiveNowCard(
                        creatorDisplayName = communityItem.creatorDisplayName,
                        isCreatorLive = isCreatorLiveRealtime,
                        onJoin = {
                            if (liveStreamID != null) showingLiveStream = true
                        },
                    )
                }

                // 6. Super Hype + Badge Holders
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SuperHypeCard(topMembers = topMembers, modifier = Modifier.weight(1f))
                        BadgeHoldersCard(
                            topMembers = topMembers,
                            modifier = Modifier.weight(1f),
                            onTap = { showingBadges = true }
                        )
                    }
                }

                // 7. Recent Posts label
                item {
                    Text(
                        "RECENT POSTS",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = textMuted, letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }

                // 7. Post cards
                if (posts.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("💬", fontSize = 36.sp)
                                Text("No posts yet", fontSize = 15.sp, color = textMuted)
                                Text("Be the first to post!", fontSize = 13.sp, color = textMuted.copy(alpha = 0.6f))
                            }
                        }
                    }
                } else {
                    items(posts) { post ->
                        PostCard(
                            post = post,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                            onHype = {
                                scope.launch {
                                    try {
                                        db.collection("communities").document(communityID)
                                            .collection("posts").document(post.id)
                                            .update("hypeCount", com.google.firebase.firestore.FieldValue.increment(1L)).await()
                                        posts = posts.map { p ->
                                            if (p.id == post.id) p.copy(hypeCount = p.hypeCount + 1) else p
                                        }
                                    } catch (e: Exception) {
                                        if (BuildConfig.DEBUG) { println("⚠️ POST HYPE: ${e.message}") }
                                    }
                                }
                            },
                            onTap = { selectedPost = post }
                        )
                    }
                }
            }
        }

        // FAB — compose post (+ Go Live for creator)
        Box(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isCreator) {
                    FloatingActionButton(
                        onClick = { showingGoLive = true },
                        containerColor = accentOrange,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Text("🎙️", fontSize = 20.sp)
                    }
                }
                FloatingActionButton(
                    onClick = { showingComposer = true },
                    containerColor = accentCyan,
                    shape = CircleShape,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Post", tint = Color.Black, modifier = Modifier.size(24.dp))
                }
            }
        }

        // Live stream viewer — fullscreen overlay when the user taps WATCH
        // on the LiveNow card. Identity comes from local membership so we
        // don't need a separate user-doc fetch.
        if (showingLiveStream && liveStreamID != null) {
            LiveStreamViewerHost(
                userID = userID,
                communityID = communityID,
                streamID = liveStreamID!!,
                membership = membership,
                onDismiss = { showingLiveStream = false },
            )
        }

        // Creator broadcaster — fullscreen overlay when the creator taps Go
        // Live FAB. Hosts the Agora broadcaster + camera preview.
        if (showingGoLive && isCreator) {
            com.stitchsocial.club.live.LiveStreamCreatorScreen(
                creatorID = communityID,
                creatorUsername = membership?.username ?: communityItem.creatorUsername,
                creatorDisplayName = membership?.displayName ?: communityItem.creatorDisplayName,
                onDismiss = { showingGoLive = false },
            )
        }
    }

    // Compose sheet
    if (showingComposer) {
        ComposePostSheet(
            userID = userID,
            communityID = communityID,
            membership = membership,
            isCreator = isCreator,
            onPost = { body ->
                scope.launch {
                    try {
                        val postData = hashMapOf<String, Any>(
                            "communityID" to communityID,
                            "authorID" to userID,
                            "authorUsername" to (membership?.username ?: "user"),
                            "authorDisplayName" to (membership?.displayName ?: "User"),
                            "authorLevel" to (membership?.level ?: 0),
                            "isCreatorPost" to isCreator,
                            "postType" to "text",
                            "body" to body,
                            "hypeCount" to 0,
                            "replyCount" to 0,
                            "isPinned" to false,
                            "createdAt" to Timestamp.now()
                        )
                        val ref = db.collection("communities").document(communityID)
                            .collection("posts").add(postData).await()
                        val newPost = CommunityPost(
                            id = ref.id, communityID = communityID,
                            authorID = userID, authorUsername = membership?.username ?: "user",
                            authorDisplayName = membership?.displayName ?: "User",
                            authorLevel = membership?.level ?: 0, isCreatorPost = isCreator,
                            postType = CommunityPostType.TEXT,
                            body = body, createdAt = Date()
                        )
                        posts = listOf(newPost) + posts
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) { println("❌ POST: Failed — ${e.message}") }
                    }
                }
            },
            onDismiss = { showingComposer = false }
        )
    }

    // Leaderboard / Supporters drill-down sheet
    if (showingLeaderboard || showingSupporters) {
        MemberLeaderboardView(
            communityID = communityID,
            members = if (showingSupporters)
                topMembers.sortedByDescending { it.totalHypesGiven }
            else
                topMembers.sortedByDescending { it.level },
            sort = leaderboardSort,
            onDismiss = { showingLeaderboard = false; showingSupporters = false }
        )
    }

    // Badge gallery sheet
    if (showingBadges) {
        BadgeGalleryView(
            membership = membership,
            onDismiss = { showingBadges = false }
        )
    }
}

// MARK: - Card 1: Creator Header

@Composable
internal fun CreatorHeaderCard(
    communityItem: CommunityListItem,
    memberCount: Int,
    isCreator: Boolean,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(accentPurple.copy(alpha = 0.2f), Color.Transparent)))
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 48.dp, bottom = 16.dp)) {
            Box(
                modifier = Modifier.size(36.dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(18.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!communityItem.profileImageURL.isNullOrBlank()) {
                    AsyncImage(
                        model = communityItem.profileImageURL,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))
                    )
                } else {
                    MemberAvatar(name = communityItem.creatorDisplayName, size = 72)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(communityItem.creatorDisplayName, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary)
                        if (communityItem.isVerified) Text("✓", fontSize = 14.sp, color = accentCyan)
                    }
                    Text("@${communityItem.creatorUsername}", fontSize = 13.sp, color = textSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("MEMBERS:", "${communityItem.memberCount}", accentCyan)
                StatItem("LIVE NOW:", if (communityItem.isCreatorLive) "🔴" else "—", accentOrange)
                StatItem("TRENDING:", if (communityItem.isCreatorLive) "🔴 Live" else "—", accentPurple)
            }
        }
    }
}

// MARK: - Card 2: XP Progress

@Composable
private fun XPProgressCard(membership: CommunityMembership?, communityItem: CommunityListItem) {
    val level = membership?.level ?: communityItem.userLevel
    val currentXP = membership?.localXP ?: communityItem.userXP
    val xpForNext = xpForLevel(level + 1)
    val progress = if (xpForNext > 0) (currentXP.toFloat() / xpForNext.toFloat()).coerceIn(0f, 1f) else 0f

    ModuleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("⭐", fontSize = 16.sp)
            Text("Lv $level", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accentGold)
            Box(modifier = Modifier.weight(1f).height(8.dp).background(cardBorder, RoundedCornerShape(4.dp))) {
                Box(
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(progress)
                        .background(Brush.horizontalGradient(listOf(accentGold, accentOrange)), RoundedCornerShape(4.dp))
                )
            }
            Text("${formatXP(currentXP)} / ${formatXP(xpForNext)}", fontSize = 10.sp, color = textMuted)
        }
    }
}

// MARK: - Card 3a: Discussion — shows creator/pinned post first, "Start One" CTA when empty

@Composable
private fun DiscussionCard(posts: List<CommunityPost>, modifier: Modifier, onStartPost: () -> Unit) {
    // Prioritise creator post (matches iOS: feedService.currentFeed.first(where: isCreatorPost))
    val pinnedPost = posts.firstOrNull { it.isCreatorPost } ?: posts.firstOrNull()

    ModuleCard(modifier = modifier) {
        CardHeader("💬", "Discussion", accentCyan)
        Spacer(modifier = Modifier.height(10.dp))

        if (pinnedPost != null) {
            Text("@${pinnedPost.authorUsername}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = accentOrange)
            Spacer(Modifier.height(4.dp))
            val preview = if (pinnedPost.body.length > 80) pinnedPost.body.take(80) + "…" else pinnedPost.body
            Text(preview, fontSize = 12.sp, color = textSecondary, lineHeight = 17.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🔥", fontSize = 12.sp)
                    Text("${pinnedPost.hypeCount}", fontSize = 11.sp, color = textMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("💬", fontSize = 12.sp)
                    Text("${pinnedPost.replyCount}", fontSize = 11.sp, color = textMuted)
                }
            }
        } else {
            Text("No active discussions", fontSize = 12.sp, color = textMuted)
            Spacer(Modifier.height(8.dp))
            // "Start One" CTA — matches iOS smallButton
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = accentCyan.copy(alpha = 0.15f),
                modifier = Modifier.clickable { onStartPost() }
            ) {
                Text("Start One", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accentCyan,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

// MARK: - Card 3b: Upper Flow Leaderboard — tappable

@Composable
private fun UpperFlowCard(topMembers: List<CommunityMembership>, modifier: Modifier, onTap: () -> Unit) {
    ModuleCard(modifier = modifier.clickable { onTap() }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CardHeader("⬆️", "Upper Flow", accentPurple)
            Icon(Icons.Default.ChevronRight, null, tint = textMuted, modifier = Modifier.size(14.dp))
        }
        Text("Community Level Leaders", fontSize = 10.sp, color = textMuted)
        Spacer(modifier = Modifier.height(10.dp))
        if (topMembers.isEmpty()) {
            Text("No members yet", fontSize = 12.sp, color = textMuted)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                topMembers.take(4).forEachIndexed { i, member ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textMuted, modifier = Modifier.width(14.dp))
                        MemberAvatar(name = member.displayName, size = 24)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(member.username.take(12), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            Text("Lv ${member.level}", fontSize = 9.sp, color = accentGold)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Card 4a: Highlight Reel — tappable

@Composable
private fun HighlightReelCard(modifier: Modifier, onTap: () -> Unit) {
    ModuleCard(modifier = modifier.clickable { onTap() }) {
        CardHeader("🎬", "Highlights", accentOrange)
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(80.dp)
                .background(cardBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.PlayCircle, contentDescription = null, tint = textMuted, modifier = Modifier.size(28.dp))
                Text("Coming soon", fontSize = 10.sp, color = textMuted)
            }
        }
    }
}

// MARK: - Card 4b: Top Supporters — tappable

@Composable
private fun TopSupportersCard(topMembers: List<CommunityMembership>, modifier: Modifier, onTap: () -> Unit) {
    val supporters = topMembers.sortedByDescending { it.totalHypesGiven }.take(4)
    ModuleCard(modifier = modifier.clickable { onTap() }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CardHeader("🏅", "Supporters", accentPink)
            Icon(Icons.Default.ChevronRight, null, tint = textMuted, modifier = Modifier.size(14.dp))
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (supporters.isEmpty()) {
            Text("No supporters yet", fontSize = 12.sp, color = textMuted)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                supporters.forEach { member ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MemberAvatar(name = member.displayName, size = 24)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(member.username.take(12), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            Text("${member.totalHypesGiven} hypes", fontSize = 9.sp, color = accentOrange)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Card 5: Live Now Banner
@Composable
private fun LiveNowCard(
    creatorDisplayName: String,
    isCreatorLive: Boolean,
    onJoin: () -> Unit,
) {
    ModuleCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        gradient = listOf(accentPurple.copy(0.15f), accentCyan.copy(0.08f))
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(accentOrange.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("🎙️", fontSize = 18.sp)
                }
                Column {
                    Text("Stream Hub", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                    Text(
                        if (isCreatorLive) "${creatorDisplayName.ifBlank { "Creator" }} is live now!" else "No active streams",
                        fontSize = 11.sp,
                        color = if (isCreatorLive) accentOrange else textMuted
                    )
                }
            }
            if (isCreatorLive) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = accentOrange,
                    modifier = Modifier.clickable { onJoin() },
                ) {
                    Text("WATCH", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
        }
    }
}

// MARK: - Card 6a: Super Hype Stats
@Composable
private fun SuperHypeCard(topMembers: List<CommunityMembership>, modifier: Modifier) {
    val totalHypes = topMembers.sumOf { it.totalHypesReceived }
    val topGiver = topMembers.maxByOrNull { it.totalHypesGiven }
    ModuleCard(modifier = modifier) {
        CardHeader("🔥", "Super Hype", accentOrange)
        Spacer(modifier = Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HypeStatRow("🔥", "Total Hypes", "", formatCount(totalHypes))
            HypeStatRow("⬆️", "Top Giver", "", topGiver?.username?.take(10) ?: "—")
            HypeStatRow("💎", "Top Earner", "", topMembers.firstOrNull()?.username?.take(10) ?: "—")
        }
    }
}

// MARK: - Card 6b: Badge Holders — tappable
@Composable
private fun BadgeHoldersCard(topMembers: List<CommunityMembership>, modifier: Modifier, onTap: () -> Unit) {
    ModuleCard(modifier = modifier.clickable { onTap() }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CardHeader("🏆", "Badges", accentGold)
            Icon(Icons.Default.ChevronRight, null, tint = textMuted, modifier = Modifier.size(14.dp))
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (topMembers.isEmpty()) {
            Text("No members yet", fontSize = 12.sp, color = textMuted)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                topMembers.take(3).forEach { member ->
                    val badge = badgeForLevel(member.level)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).background(accentPurple.copy(0.2f), CircleShape), contentAlignment = Alignment.Center) {
                            Text(badge.first, fontSize = 14.sp)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(badge.second, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                            Text("${member.localXP} XP", fontSize = 9.sp, color = accentOrange)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Post Card
@Composable
internal fun PostCard(post: CommunityPost, modifier: Modifier, onHype: () -> Unit, onTap: () -> Unit) {
    ModuleCard(modifier = modifier.clickable { onTap() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            MemberAvatar(name = post.authorDisplayName, size = 32)
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("@${post.authorUsername}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                    if (post.isCreatorPost) {
                        Surface(shape = RoundedCornerShape(4.dp), color = accentCyan.copy(alpha = 0.12f)) {
                            Text("CREATOR", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = accentCyan,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }
                }
                Text("Lv ${post.authorLevel} • ${timeAgo(post.createdAt)}", fontSize = 10.sp, color = textMuted)
            }
            if (post.isPinned) Text("📌", fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Video clip preview — shown when post is a VIDEO_CLIP. Thumbnail
        // fills width with a 9:16 aspect ratio, play button + duration pill
        // overlay. Tap inherits from the row-level onTap (opens detail).
        val isVideoPost = post.postType == CommunityPostType.VIDEO_CLIP
            && !post.videoThumbnailURL.isNullOrEmpty()
        if (isVideoPost) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                AsyncImage(
                    model = post.videoThumbnailURL,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Play icon center
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
                // Duration pill bottom-right
                if (post.videoDurationSeconds > 0) {
                    Text(
                        text = formatDuration(post.videoDurationSeconds),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Caption / body text — shown for text posts and as caption beneath
        // video posts. Skip rendering an empty body to avoid wasted padding.
        if (post.body.isNotBlank()) {
            Text(post.body, fontSize = 13.sp, color = textSecondary, lineHeight = 19.sp)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.clickable { onHype() }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔥", fontSize = 14.sp)
                Text("${post.hypeCount}", fontSize = 12.sp, color = textMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("💬", fontSize = 14.sp)
                Text("${post.replyCount}", fontSize = 12.sp, color = textMuted)
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

// MARK: - Compose Sheet
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposePostSheet(userID: String, communityID: String, membership: CommunityMembership?, isCreator: Boolean, onPost: (String) -> Unit, onDismiss: () -> Unit) {
    var postBody by remember { mutableStateOf("") }
    val isValid = postBody.trim().isNotEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = darkBg) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("New Post", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                TextButton(onClick = onDismiss) { Text("Cancel", color = textSecondary) }
            }
            OutlinedTextField(
                value = postBody, onValueChange = { postBody = it },
                placeholder = { Text("What's on your mind?", color = textMuted) },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accentCyan, unfocusedBorderColor = cardBorder, focusedTextColor = textPrimary, unfocusedTextColor = textPrimary, cursorColor = accentCyan)
            )
            Button(onClick = { onPost(postBody.trim()); onDismiss() }, enabled = isValid, modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentCyan, disabledContainerColor = cardBorder), shape = RoundedCornerShape(14.dp)) {
                Text("Post", fontWeight = FontWeight.Bold, color = if (isValid) Color.Black else textMuted)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// MARK: - Member Leaderboard View (drill-down for Upper Flow + Supporters)
@Composable
fun MemberLeaderboardView(communityID: String, members: List<CommunityMembership>, sort: LeaderboardSort, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().background(darkBg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 48.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, null, tint = accentCyan) }
            Text(sort.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textPrimary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.size(48.dp))
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(members) { idx, member ->
                Row(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(12.dp)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${idx + 1}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (idx < 3) accentGold else textMuted, modifier = Modifier.width(24.dp))
                    MemberAvatar(name = member.displayName, size = 36)
                    Column(Modifier.weight(1f)) {
                        Text(member.username, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                        Text("Lv ${member.level} · ${formatXP(member.localXP)} XP", fontSize = 11.sp, color = textSecondary)
                    }
                    val value = when (sort) {
                        LeaderboardSort.HYPES_GIVEN -> "${member.totalHypesGiven} 🔥"
                        LeaderboardSort.HYPES_RECEIVED -> "${member.totalHypesReceived} 💎"
                        else -> "Lv ${member.level}"
                    }
                    Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accentCyan)
                }
            }
        }
    }
}

// MARK: - Badge Gallery View
@Composable
fun BadgeGalleryView(membership: CommunityMembership?, onDismiss: () -> Unit) {
    val userLevel = membership?.level ?: 0
    Column(Modifier.fillMaxSize().background(darkBg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 48.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, null, tint = accentCyan) }
            Text("Badges", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textPrimary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.size(48.dp))
        }
        data class BadgeDef(val level: Int, val emoji: String, val name: String)
        val badges = listOf(
            BadgeDef(5, "🏅", "Member"), BadgeDef(10, "⚔️", "Veteran"),
            BadgeDef(25, "🦅", "Eagle"), BadgeDef(50, "💎", "Diamond"),
            BadgeDef(100, "👑", "Centurion")
        )
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(badges) { b ->
                val earned = userLevel >= b.level
                Row(Modifier.fillMaxWidth().background(if (earned) accentPurple.copy(0.1f) else cardBg, RoundedCornerShape(12.dp)).padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(b.emoji, fontSize = 28.sp)
                    Column(Modifier.weight(1f)) {
                        Text(b.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (earned) textPrimary else textMuted)
                        Text("Level ${b.level} required", fontSize = 12.sp, color = textMuted)
                    }
                    if (earned) Text("✓", fontSize = 18.sp, color = accentCyan)
                    else Text("🔒", fontSize = 16.sp)
                }
            }
        }
    }
}

// MARK: - Shared Components
@Composable
private fun ModuleCard(modifier: Modifier = Modifier, gradient: List<Color>? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.background(
            if (gradient != null) Brush.linearGradient(gradient) else Brush.linearGradient(listOf(cardBg, cardBg)),
            RoundedCornerShape(16.dp)
        ).border(1.dp, cardBorder, RoundedCornerShape(16.dp)).padding(14.dp),
        content = content
    )
}

@Composable
private fun CardHeader(emoji: String, title: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 14.sp)
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = color, letterSpacing = 1.sp)
    }
}

@Composable
private fun MemberAvatar(name: String, size: Int) {
    val gradients = listOf(listOf(accentCyan, accentPurple), listOf(accentPink, accentOrange), listOf(accentPurple, accentPink), listOf(accentOrange, accentGold))
    val parts = name.trim().split(" ")
    val initials = ((parts.firstOrNull()?.firstOrNull()?.toString() ?: "") + (if (parts.size > 1) parts[1].firstOrNull()?.toString() ?: "" else "")).uppercase()
    val gradient = gradients[kotlin.math.abs(name.hashCode()) % gradients.size]
    Box(modifier = Modifier.size(size.dp).background(Brush.linearGradient(gradient), CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, fontSize = (size * 0.38f).sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = textMuted)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun HypeStatRow(emoji: String, label: String, sub: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
            if (sub.isNotEmpty()) Text(sub, fontSize = 8.sp, color = accentOrange)
        }
        Text(value, fontSize = 11.sp, color = textSecondary)
    }
}

// MARK: - Helpers
private fun xpForLevel(level: Int): Int = if (level <= 0) 0 else level * level * 10
private fun badgeForLevel(level: Int): Pair<String, String> = when {
    level >= 100 -> "👑" to "Centurion"
    level >= 50  -> "💎" to "Diamond"
    level >= 25  -> "🦅" to "Eagle"
    level >= 10  -> "⚔️" to "Veteran"
    level >= 5   -> "🌟" to "Rising"
    else         -> "🏅" to "Member"
}
private fun formatXP(xp: Int): String = when {
    xp >= 1_000_000 -> String.format("%.1fM", xp / 1_000_000.0)
    xp >= 1_000     -> String.format("%.1fK", xp / 1_000.0)
    else            -> "$xp"
}
private fun formatCount(count: Int): String = formatXP(count)
private fun timeAgo(date: Date): String {
    val diff = (System.currentTimeMillis() - date.time) / 1000
    return when {
        diff < 60    -> "now"
        diff < 3600  -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        else         -> "${diff / 86400}d"
    }
}
internal fun parseMembership(id: String, data: Map<String, Any>): CommunityMembership? = try { CommunityMembership.fromFirestore(data.toMutableMap().apply { put("id", id) }) } catch (_: Exception) { null }
internal fun parsePost(id: String, data: Map<String, Any>): CommunityPost? = try { CommunityPost.fromFirestore(id, data) } catch (_: Exception) { null }

/// Thin wrapper that pulls identity off the loaded membership and hands it
/// to the live viewer screen. Centralised here so the call site stays one
/// line and identity fallbacks live in one place.
@Composable
private fun LiveStreamViewerHost(
    userID: String,
    communityID: String,
    streamID: String,
    membership: CommunityMembership?,
    onDismiss: () -> Unit,
) {
    com.stitchsocial.club.live.LiveStreamViewerScreen(
        userID = userID,
        communityID = communityID,
        streamID = streamID,
        userLevel = membership?.level ?: 1,
        userUsername = membership?.username ?: "user",
        userDisplayName = membership?.displayName ?: membership?.username ?: "User",
        onDismiss = onDismiss,
    )
}