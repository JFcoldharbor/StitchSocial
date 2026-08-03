/*
 * NotificationView.kt - NOTIFICATIONS 2a REDESIGN
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Notification Feed with Discovery & Navigation
 * ✅ Header: bold title + compact Filter pill (progressive disclosure of filter chips)
 * ✅ Amber "N unread" pill + quiet "Mark all read" text button
 * ✅ "Just Joined" rail: NEW chip + "Top Videos" pill + auto-scrolling avatar marquee
 * ✅ Feed grouped into time buckets: NEW / TODAY / THIS WEEK / EARLIER
 * ✅ Profile navigation with follow button & video grid
 * ✅ Video navigation with actual video player
 * ✅ Follow back buttons
 * ✅ Timer cleanup
 *
 * PORT: iOS "Notifications 2a" redesign
 */

package com.stitchsocial.club.views

import com.stitchsocial.club.ui.theme.AppTheme
import com.stitchsocial.club.ui.theme.StitchColors
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Calendar

// Foundation
import com.stitchsocial.club.foundation.RecentUser
import com.stitchsocial.club.foundation.LeaderboardVideo

// Services
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.services.VideoServiceImpl
import com.stitchsocial.club.services.NotificationService
import com.stitchsocial.club.services.DiscoveryService

// ViewModels
import com.stitchsocial.club.viewmodels.NotificationViewModel
import com.stitchsocial.club.viewmodels.NotificationFilter
import com.stitchsocial.club.viewmodels.NotificationItem
import com.stitchsocial.club.viewmodels.NotificationType
import com.stitchsocial.club.viewmodels.NotificationNavigationEvent

// Coordination
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.coordination.EngagementCoordinator
import com.stitchsocial.club.FollowManager

// Firebase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.stitchsocial.club.BuildConfig

// MARK: - Accent Colors (purple stays the accent)

private val PurpleAccent = Color(0xFF8E44AD)
private val PurpleLabel = Color(0xFFBB86FC)
private val AmberAccent = Color(0xFFFF9500)
private val GreenNew = Color(0xFF34C759)

/**
 * Complete notification screen with discovery and filtering
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationViewComplete(
    navigationCoordinator: NavigationCoordinator,
    onShowThreadView: (String, String?) -> Unit = { _, _ -> },
    onNavigateToProfile: (String) -> Unit = { },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Services and dependencies
    val userService = remember { UserService(context) }
    val videoService = remember { VideoServiceImpl() }
    val notificationService = remember { NotificationService() }
    val discoveryService = remember { DiscoveryService(context) }
    val followManager = remember { FollowManager(context) }
    val engagementCoordinator = remember { EngagementCoordinator(videoService, userService) }

    // ViewModel
    // Retained in the Activity ViewModelStore (viewModel {}), NOT remember {}. The
    // tab host swaps screens with when(selectedTab), disposing this composable on
    // every tab switch — with remember the VM was recreated each time, re-running
    // its init load + restarting the realtime listener (spinner + reload). Retained,
    // the loaded notifications and the live listener survive, so re-entry is instant.
    val viewModel: NotificationViewModel = viewModel {
        NotificationViewModel(
            userService = userService,
            engagementCoordinator = engagementCoordinator,
            navigationCoordinator = navigationCoordinator,
            videoService = videoService,
            context = context
        )
    }

    // State
    val notifications by viewModel.filteredNotifications.collectAsState()
    val unreadCount by viewModel.unreadCount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val profileImages by viewModel.profileImages.collectAsState()

    // Filter chips are hidden until the Filter pill is tapped (progressive disclosure)
    var filtersExpanded by remember { mutableStateOf(false) }

    // Discovery state
    var recentUsers by remember { mutableStateOf<List<RecentUser>>(emptyList()) }

    // Navigation state (JustJoined and TopVideos dialogs only)
    var showingJustJoinedView by remember { mutableStateOf(false) }
    var showingTopVideosView by remember { mutableStateOf(false) }

    // Time-bucketed feed sections
    val sections = remember(notifications) { groupNotifications(notifications) }

    // Observe navigation events from ViewModel
    LaunchedEffect(viewModel) {
        Log.d("NOTIF_NAV", "🔵 Navigation collector STARTED")
        viewModel.navigationEvent.collect { event ->
            Log.d("NOTIF_NAV", "🔵 Event received: $event")
            when (event) {
                is NotificationNavigationEvent.NavigateToProfile -> {
                    Log.d("NOTIF_NAV", "👤 -> Profile: ${event.userId}")
                    onNavigateToProfile(event.userId)
                }
                is NotificationNavigationEvent.NavigateToVideo -> {
                    Log.d("NOTIF_NAV", "-> Thread: ${event.threadId}, target: ${event.videoId}")
                    onShowThreadView(event.threadId ?: event.videoId, event.videoId)
                }
                is NotificationNavigationEvent.NavigateToThread -> {
                    Log.d("NOTIF_NAV", "🧵 -> Thread only: ${event.threadId}")
                    onShowThreadView(event.threadId, null)
                }
                is NotificationNavigationEvent.NavigateToEvent -> {
                    Log.d("NOTIF_NAV", "📅 -> Event hub: ${event.eventId}")
                    // No callback for this one: the Hub is presented by
                    // DiscoveryView, which isn't composed while this tab is up.
                    // Park the id — MainActivity switches to Discovery, which
                    // then opens the Hub and consumes it.
                    com.stitchsocial.club.events.EventDeepLink.request(event.eventId)
                }
                is NotificationNavigationEvent.NavigateToLive -> {
                    Log.d("NOTIF_NAV", "🔴 -> Live: ${event.communityID} / ${event.streamID}")
                    // Parked for the same reason as events: the live viewer is
                    // presented from the community screen, which isn't composed
                    // while this tab is up.
                    com.stitchsocial.club.live.LiveDeepLink.request(event.communityID, event.streamID)
                }
                NotificationNavigationEvent.None -> { }
            }
        }
    }

    // Load initial data
    LaunchedEffect(Unit) {
        // Load discovery data (only recent users are shown in the rail;
        // top videos load on demand inside the Top Videos screen)
        try {
            val (users, _) = discoveryService.refreshDiscoveryData(userLimit = 20, leaderboardLimit = 10)
            recentUsers = users
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("❌ NOTIFICATION VIEW: Failed to load discovery data: ${e.message}") }
        }

        // Load follow states for notifications
        val senderIDs = notifications.mapNotNull { notification ->
            notification.actionData["userId"] as? String
                ?: notification.actionData["senderID"] as? String
        }.distinct()
        if (senderIDs.isNotEmpty()) {
            followManager.loadFollowStates(senderIDs)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.colors.bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header: title + Filter pill, unread pill + mark-all-read
            item {
                NotificationHeader(
                    unreadCount = unreadCount,
                    filterActive = selectedFilter != NotificationFilter.ALL,
                    filtersExpanded = filtersExpanded,
                    onToggleFilters = { filtersExpanded = !filtersExpanded },
                    onMarkAllRead = { viewModel.markAllAsRead() }
                )
            }

            // Filter chips: progressive disclosure, hidden until Filter pill is tapped
            item {
                AnimatedVisibility(
                    visible = filtersExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    NotificationTabSelector(
                        selectedFilter = selectedFilter,
                        unreadCount = unreadCount,
                        onFilterSelected = { filter ->
                            viewModel.setFilter(filter)
                        }
                    )
                }
            }

            // Just Joined rail: header row + auto-scrolling avatar marquee
            item {
                JustJoinedRail(
                    recentUsers = recentUsers,
                    onOpenJustJoined = { showingJustJoinedView = true },
                    onOpenTopVideos = { showingTopVideosView = true },
                    onUserTap = { userId -> onNavigateToProfile(userId) }
                )
            }

            // Notifications feed grouped into time buckets
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = PurpleAccent)
                    }
                }
            } else if (notifications.isEmpty()) {
                item {
                    EmptyStateView(
                        filter = selectedFilter,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp)
                    )
                }
            } else {
                notificationSection(
                    label = "NEW",
                    labelColor = PurpleLabel,
                    sectionItems = sections.newItems,
                    followManager = followManager,
                    profileImages = profileImages,
                    onTap = { viewModel.onNotificationTapped(it) },
                    onProfileTap = onNavigateToProfile
                )
                notificationSection(
                    label = "TODAY",
                    labelColor = null,
                    sectionItems = sections.today,
                    followManager = followManager,
                    profileImages = profileImages,
                    onTap = { viewModel.onNotificationTapped(it) },
                    onProfileTap = onNavigateToProfile
                )
                notificationSection(
                    label = "THIS WEEK",
                    labelColor = null,
                    sectionItems = sections.thisWeek,
                    followManager = followManager,
                    profileImages = profileImages,
                    onTap = { viewModel.onNotificationTapped(it) },
                    onProfileTap = onNavigateToProfile
                )
                notificationSection(
                    label = "EARLIER",
                    labelColor = null,
                    sectionItems = sections.earlier,
                    followManager = followManager,
                    profileImages = profileImages,
                    onTap = { viewModel.onNotificationTapped(it) },
                    onProfileTap = onNavigateToProfile
                )
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }

    // Just Joined Full Screen View
    if (showingJustJoinedView) {
        Dialog(
            onDismissRequest = { showingJustJoinedView = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            JustJoinedView(
                followManager = followManager,
                onDismiss = { showingJustJoinedView = false },
                onUserTap = { userId: String ->
                    showingJustJoinedView = false
                    onNavigateToProfile(userId)
                }
            )
        }
    }

    // Top Videos Full Screen View
    if (showingTopVideosView) {
        Dialog(
            onDismissRequest = { showingTopVideosView = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            // Inline TopVideosView
            val tvScope = rememberCoroutineScope()
            var tvVideos by remember { mutableStateOf<List<LeaderboardVideo>>(emptyList()) }
            var tvLoading by remember { mutableStateOf(true) }
            var tvError by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                tvLoading = true; tvError = null
                try {
                    val db = FirebaseFirestore.getInstance("stitchfin")
                    val cutoff = com.google.firebase.Timestamp(java.util.Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L))
                    val snapshot = db.collection("videos")
                        .whereGreaterThan("createdAt", cutoff)
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .limit(50)
                        .get()
                        .await()
                    tvVideos = snapshot.documents
                        .mapNotNull { doc -> LeaderboardVideo.fromFirestore(doc.id, doc.data ?: return@mapNotNull null) }
                        .sortedByDescending { it.hypeCount }
                } catch (e: Exception) { tvError = e.message }
                tvLoading = false
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppTheme.colors.bg)
                    .statusBarsPadding()
            ) {
                // Top bar
                Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(
                        "🔥 Top Videos",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    TextButton(
                        onClick = { showingTopVideosView = false },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                    ) { Text("Done", color = Color(0xFF9C27B0), fontWeight = FontWeight.SemiBold) }
                }
                Text(
                    "Most hyped videos from the last 7 days",
                    fontSize = 13.sp, color = AppTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        tvLoading && tvVideos.isEmpty() -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(color = Color(0xFF9C27B0))
                                Text("Loading top videos...", fontSize = 14.sp, color = AppTheme.colors.textSecondary)
                            }
                        }
                        tvError != null -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(50.dp))
                                Text(tvError!!, fontSize = 16.sp, color = AppTheme.colors.textSecondary, textAlign = TextAlign.Center)
                                TextButton(onClick = {
                                    tvScope.launch {
                                        tvLoading = true; tvError = null
                                        try {
                                            val db = FirebaseFirestore.getInstance("stitchfin")
                                            val cutoff = com.google.firebase.Timestamp(java.util.Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L))
                                            val snapshot = db.collection("videos")
                                                .whereGreaterThan("createdAt", cutoff)
                                                .orderBy("createdAt", Query.Direction.DESCENDING)
                                                .limit(50).get().await()
                                            tvVideos = snapshot.documents
                                                .mapNotNull { doc -> LeaderboardVideo.fromFirestore(doc.id, doc.data ?: return@mapNotNull null) }
                                                .sortedByDescending { it.hypeCount }
                                        } catch (e: Exception) { tvError = e.message }
                                        tvLoading = false
                                    }
                                }) { Text("Retry", color = Color(0xFF9C27B0)) }
                            }
                        }
                        tvVideos.isEmpty() -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(Icons.Default.Whatshot, null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(50.dp))
                                Text("No trending videos yet", fontSize = 16.sp, color = AppTheme.colors.textSecondary)
                            }
                        }
                        else -> {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(tvVideos.size, key = { tvVideos[it].id }) { idx ->
                                    val video = tvVideos[idx]
                                    val rank = idx + 1
                                    val rankColor = when (rank) {
                                        1 -> Color.Yellow; 2 -> AppTheme.colors.textSecondary; 3 -> Color(0xFFFF9800)
                                        else -> Color(0xFF9C27B0)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(AppTheme.colors.surface, RoundedCornerShape(16.dp))
                                            .clickable {
                                                showingTopVideosView = false
                                                onShowThreadView(video.id, video.id)
                                            }
                                            .padding(8.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(200.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(AppTheme.colors.surfaceStrong),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                val gridImgUrl = video.thumbnailURL
                                                if (!gridImgUrl.isNullOrEmpty()) {
                                                    SubcomposeAsyncImage(
                                                        model = ImageRequest.Builder(context).data(gridImgUrl).crossfade(true).build(),
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    ) {
                                                        when (painter.state) {
                                                            is AsyncImagePainter.State.Loading -> CircularProgressIndicator(color = Color.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                                                            is AsyncImagePainter.State.Error -> Icon(Icons.Default.PlayCircleOutline, null, tint = AppTheme.colors.textSecondary.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
                                                            is AsyncImagePainter.State.Success -> androidx.compose.foundation.Image(painter = painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                            else -> Icon(Icons.Default.PlayCircleOutline, null, tint = AppTheme.colors.textSecondary.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
                                                        }
                                                    }
                                                } else {
                                                    Icon(Icons.Default.PlayCircleOutline, null, tint = AppTheme.colors.textSecondary.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
                                                }
                                            }
                                            Text(video.creatorName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                                                Icon(Icons.Default.Whatshot, null, tint = Color(0xFFFF9800), modifier = Modifier.size(10.dp))
                                                Text(formatCount(video.hypeCount), fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                                                Spacer(Modifier.width(4.dp))
                                                Text(video.temperatureEmoji, fontSize = 12.sp)
                                            }
                                        }
                                        // Rank badge
                                        Box(
                                            modifier = Modifier.padding(8.dp).size(32.dp).background(rankColor, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) { Text("#$rank", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// MARK: - TIME BUCKETS
// ============================================================================

private data class NotificationSections(
    val newItems: List<NotificationItem>,
    val today: List<NotificationItem>,
    val thisWeek: List<NotificationItem>,
    val earlier: List<NotificationItem>
)

/**
 * Group notifications into feed buckets:
 * NEW = all unread; read items split into TODAY / THIS WEEK / EARLIER by createdAt.
 */
private fun groupNotifications(notifications: List<NotificationItem>): NotificationSections {
    val newItems = notifications.filter { !it.isRead }
    val read = notifications.filter { it.isRead }

    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val startOfToday = cal.timeInMillis
    val startOfWeek = startOfToday - 6L * 24 * 60 * 60 * 1000

    return NotificationSections(
        newItems = newItems,
        today = read.filter { it.timestamp.time >= startOfToday },
        thisWeek = read.filter { it.timestamp.time in startOfWeek until startOfToday },
        earlier = read.filter { it.timestamp.time < startOfWeek }
    )
}

/**
 * One labeled time-bucket section in the feed (skipped when empty)
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.notificationSection(
    label: String,
    labelColor: Color?,
    sectionItems: List<NotificationItem>,
    followManager: FollowManager,
    profileImages: Map<String, String>,
    onTap: (NotificationItem) -> Unit,
    onProfileTap: (String) -> Unit
) {
    if (sectionItems.isEmpty()) return

    item(key = "section_$label") {
        Text(
            text = label,
            color = labelColor ?: AppTheme.colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp)
        )
    }

    items(
        items = sectionItems,
        key = { it.id }
    ) { notification ->
        // FULL-BLEED, no outer margin. Each row used to sit in 20dp side margins
        // with 12dp between rows, on top of its own padding — that gap is what
        // made them read as floating blocks rather than an inbox. The unread
        // tint now reaches the screen edge, which also makes "new" scan faster.
        Column(modifier = Modifier.animateItem()) {
            NotificationRow(
                notification = notification,
                followManager = followManager,
                profileImages = profileImages,
                onTap = {
                    Log.d("NOTIF_NAV", "ROW TAPPED: ${notification.type} | id=${notification.id}")
                    onTap(notification)
                },
                onProfileTap = onProfileTap
            )
            // A hairline instead of a gap: separation without spending height.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 62.dp)   // starts past the avatar, as lists do
                    .height(0.5.dp)
                    .background(AppTheme.colors.hairline)
            )
        }
    }
}

// ============================================================================
// MARK: - JUST JOINED RAIL
// ============================================================================

/**
 * Compact discovery rail: "Just Joined" header + NEW chip, "Top Videos" pill,
 * and a continuously auto-scrolling marquee of new-user avatars.
 */
@Composable
private fun JustJoinedRail(
    recentUsers: List<RecentUser>,
    onOpenJustJoined: () -> Unit,
    onOpenTopVideos: () -> Unit,
    onUserTap: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clickable(onClick = onOpenJustJoined)
            ) {
                Text(
                    text = "Just Joined",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = GreenNew.copy(alpha = 0.18f)
                ) {
                    Text(
                        text = "NEW",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = GreenNew,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = AmberAccent.copy(alpha = 0.15f),
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpenTopVideos)
            ) {
                Text(
                    text = "Top Videos ›",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AmberAccent,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        // Avatar marquee
        if (recentUsers.isEmpty()) {
            Text(
                text = "No new users in the last 24 hours",
                fontSize = 13.sp,
                color = AppTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        } else {
            JustJoinedMarquee(
                users = recentUsers,
                onUserTap = onUserTap
            )
        }

        // Hairline divider below the rail
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(1.dp)
                .background(AppTheme.colors.hairline)
        )
    }
}

/**
 * Continuously auto-scrolling horizontal marquee of new-user avatars.
 * Content is duplicated x2 and translated with an infinite linear animation.
 */
@Composable
private fun JustJoinedMarquee(
    users: List<RecentUser>,
    onUserTap: (String) -> Unit
) {
    val density = LocalDensity.current
    var contentWidth by remember { mutableStateOf(0) }
    val offset = remember { Animatable(0f) }

    LaunchedEffect(contentWidth, users.size) {
        offset.snapTo(0f)
        if (contentWidth > 0) {
            // Constant linear speed of ~36dp/sec regardless of content length
            val speedPxPerSec = with(density) { 36.dp.toPx() }
            val durationMs = ((contentWidth / speedPxPerSec) * 1000f).toInt().coerceAtLeast(1000)
            while (true) {
                offset.animateTo(
                    targetValue = contentWidth.toFloat(),
                    animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
                )
                offset.snapTo(0f)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
    ) {
        Row(
            modifier = Modifier.graphicsLayer { translationX = -offset.value }
        ) {
            repeat(2) { copyIndex ->
                Row(
                    modifier = if (copyIndex == 0) {
                        Modifier
                            .padding(start = 20.dp)
                            .onSizeChanged { contentWidth = it.width }
                    } else {
                        Modifier
                    }
                ) {
                    users.forEach { user ->
                        JustJoinedAvatar(
                            user = user,
                            onTap = { onUserTap(user.id) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 52dp gradient-ring avatar with @handle beneath
 */
@Composable
private fun JustJoinedAvatar(
    user: RecentUser,
    onTap: () -> Unit
) {
    val ringBrush = Brush.linearGradient(
        colors = listOf(StitchColors.secondary, StitchColors.primary)
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .padding(end = 14.dp)
            .width(60.dp)
            .clickable(onClick = onTap)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .border(width = 2.dp, brush = ringBrush, shape = CircleShape)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            val imageUrl = user.profileImageURL
            if (!imageUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = user.username,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceStrong)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceStrong),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = user.username.take(1).uppercase(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary
                    )
                }
            }
        }

        Text(
            text = "@${user.username}",
            fontSize = 10.sp,
            color = AppTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ============================================================================
// MARK: - NOTIFICATION COMPONENTS
// ============================================================================

/**
 * Individual notification row
 */
@Composable
private fun NotificationRow(
    notification: NotificationItem,
    followManager: FollowManager,
    profileImages: Map<String, String> = emptyMap(),
    onTap: () -> Unit,
    onProfileTap: (String) -> Unit
) {
    val followingStates by followManager.followingStates.collectAsState()
    val loadingStates by followManager.loadingStates.collectAsState()

    val userId = notification.actionData["userId"] as? String
        ?: notification.actionData["senderID"] as? String
        ?: ""
    val isFollowing = if (userId.isNotEmpty()) followingStates[userId] ?: false else false
    val isLoadingFollow = if (userId.isNotEmpty()) loadingStates.contains(userId) else false

    // Revamped to iOS's shape (NotificationRowView). Padding was never the
    // problem — the row carried THREE stacked text lines (title, message,
    // timestamp) plus a separate 24dp type chip in its own column. That's a
    // block no matter how tight the padding gets.
    //
    // Now: one text line that reads as a sentence, the timestamp inline at the
    // end of it, and the type as a badge ON THE AVATAR CORNER, which is where
    // iOS puts it — costing zero extra width and zero extra height.
    //
    // The per-row card is gone too. Rounded surfaces stacked in a list read as
    // blocks; a full-bleed row with a hairline under it reads as a list. The
    // unread tint still marks what's new.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (notification.isRead) Color.Transparent
                else PurpleAccent.copy(alpha = 0.10f)
            )
            .clickable(onClick = onTap)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            val profileImageUrl = profileImages[userId]
                ?: (notification.actionData["profileImageURL"] as? String)?.takeIf { it.isNotEmpty() }

            // Avatar with the type badge on its corner.
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clickable { if (userId.isNotEmpty()) onProfileTap(userId) }
            ) {
                if (profileImageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(profileImageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(AppTheme.colors.surfaceStrong),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF6C5CE7)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (notification.title.firstOrNull()?.uppercase() ?: "?"),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Dark base under the mark: the SVGs are light-filled art drawn
                // for a dark disc, so on a light avatar they'd disappear.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF16151C)),
                    contentAlignment = Alignment.Center
                ) {
                    val iconRes = notificationIconRes(notification.type)
                    if (iconRes != 0) {
                        Image(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                    } else {
                        Text(text = notification.type.emoji, fontSize = 9.sp)
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                // Title and message as ONE sentence rather than two stacked
                // lines — "Alex hyped your stitch" is how the inbox is read
                // anyway, and it halves the row.
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(notification.title)
                        }
                        if (notification.message.isNotBlank()) {
                            append("  ")
                            withStyle(SpanStyle(color = AppTheme.colors.textSecondary)) {
                                append(notification.message)
                            }
                        }
                    },
                    color = AppTheme.colors.textPrimary,
                    fontSize = 13.5.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = notification.timeAgo,
                    color = AppTheme.colors.textSecondary.copy(alpha = 0.6f),
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )

                // Follow back button for follow notifications
                if (notification.type == NotificationType.NEW_FOLLOWER && userId.isNotEmpty() && !isFollowing) {
                    Button(
                        onClick = {
                            followManager.toggleFollow(userId)
                        },
                        enabled = !isLoadingFollow,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00BCD4)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        if (isLoadingFollow) {
                            CircularProgressIndicator(
                                color = AppTheme.colors.textPrimary,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonAdd,
                                    contentDescription = "Follow back",
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Follow Back",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Unread indicator
                if (!notification.isRead) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(PurpleAccent, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Header: bold title + compact Filter pill on the right.
 * Beneath: amber unread pill + quiet "Mark all read" text button
 * ("You're all caught up" when there is nothing unread).
 */
@Composable
private fun NotificationHeader(
    unreadCount: Int,
    filterActive: Boolean,
    filtersExpanded: Boolean,
    onToggleFilters: () -> Unit,
    onMarkAllRead: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 20.dp, end = 20.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Notifications",
                color = AppTheme.colors.textPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )

            // Compact Filter pill - highlighted when a non-All filter is active
            val pillHighlighted = filterActive || filtersExpanded
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (filterActive) PurpleAccent else AppTheme.colors.surfaceStrong,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onToggleFilters)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = "Filter notifications",
                        tint = if (filterActive) Color.White else AppTheme.colors.textPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Filter",
                        fontSize = 13.sp,
                        fontWeight = if (pillHighlighted) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (filterActive) Color.White else AppTheme.colors.textPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Unread status row
        if (unreadCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AmberAccent.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "$unreadCount unread",
                        color = AmberAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = "Mark all read",
                    color = PurpleLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onMarkAllRead)
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        } else {
            Text(
                text = "You're all caught up",
                color = AppTheme.colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

/**
 * Notification filter chips (shown via the Filter pill's progressive disclosure)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationTabSelector(
    selectedFilter: NotificationFilter,
    unreadCount: Int,
    onFilterSelected: (NotificationFilter) -> Unit
) {
    val filters = listOf(
        NotificationFilter.ALL,
        NotificationFilter.UNREAD,
        NotificationFilter.ENGAGEMENT,
        NotificationFilter.SOCIAL,
        NotificationFilter.SYSTEM
    )

    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        items(filters) { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterSelected(filter) },
                label = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = filter.displayName,
                            fontSize = 14.sp,
                            fontWeight = if (selectedFilter == filter) FontWeight.SemiBold else FontWeight.Medium
                        )

                        if (filter == NotificationFilter.UNREAD && unreadCount > 0) {
                            Surface(
                                shape = CircleShape,
                                color = AmberAccent
                            ) {
                                Text(
                                    text = unreadCount.toString(),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PurpleAccent,
                    selectedLabelColor = Color.White,
                    containerColor = Color(0xFF1E1E1E),
                    labelColor = AppTheme.colors.textSecondary
                )
            )
        }
    }
}

/**
 * Empty state view
 */
@Composable
private fun EmptyStateView(
    filter: NotificationFilter,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = getEmptyStateIcon(filter),
            contentDescription = null,
            tint = AppTheme.colors.textSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(50.dp)
        )

        Text(
            text = "No notifications",
            color = AppTheme.colors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = getEmptyStateMessage(filter),
            color = AppTheme.colors.textSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp)
        )
    }
}

// ============================================================================
// MARK: - HELPER FUNCTIONS
// ============================================================================

private fun getNotificationColor(type: NotificationType): Color {
    return when (type) {
        NotificationType.HYPE_RECEIVED -> Color(0xFFFF6B6B)
        NotificationType.REPLY_RECEIVED -> Color(0xFF4ECDC4)
        NotificationType.NEW_FOLLOWER -> Color(0xFF95E1D3)
        NotificationType.TAP_MILESTONE -> Color(0xFFFFA502)
        NotificationType.TIER_UPGRADED -> Color(0xFF8E44AD)
        // Money and going-live earn their own colours; everything else can share
        // the generic purple.
        NotificationType.TIP_RECEIVED, NotificationType.SUBSCRIPTION -> Color(0xFFFFC043)
        NotificationType.GO_LIVE -> Color(0xFFFF375F)
        NotificationType.COOL_RECEIVED -> Color(0xFF4EA8FF)
        else -> Color(0xFF5F27CD)
    }
}

private fun getEmptyStateIcon(filter: NotificationFilter): androidx.compose.ui.graphics.vector.ImageVector {
    return when (filter) {
        NotificationFilter.ENGAGEMENT -> Icons.Default.Favorite
        NotificationFilter.SOCIAL -> Icons.Default.People
        NotificationFilter.SYSTEM -> Icons.Default.Settings
        else -> Icons.Default.Notifications
    }
}

private fun getEmptyStateMessage(filter: NotificationFilter): String {
    return when (filter) {
        NotificationFilter.ALL -> "Check back later for new activity."
        NotificationFilter.UNREAD -> "You've read all your notifications."
        NotificationFilter.ENGAGEMENT -> "Share videos to get hype from the community!"
        NotificationFilter.SOCIAL -> "Keep creating content!"
        NotificationFilter.SYSTEM -> "System notifications will appear here."
    }
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}


/**
 * Brand mark for a notification type.
 *
 * The inbox rendered `type.emoji` — placeholder art the design handoff replaced.
 * Resolved by name so a new icon is a file drop, not a code change; returns 0
 * when there's no mark, and the caller falls back to the emoji rather than an
 * empty square.
 */
@Composable
private fun notificationIconRes(type: NotificationType): Int {
    val name = when (type) {
        NotificationType.HYPE_RECEIVED -> "ic_notif_hype"
        NotificationType.REPLY_RECEIVED -> "ic_notif_stitch"
        NotificationType.SHARE_RECEIVED -> "ic_notif_thread"
        NotificationType.NEW_FOLLOWER -> "ic_notif_follow"
        NotificationType.FOLLOWING_VIDEO -> "ic_notif_new_thread"
        NotificationType.TAP_MILESTONE -> "ic_notif_streak"
        NotificationType.TIER_UPGRADED -> "ic_notif_rank_up"
        NotificationType.QUESTION_RECEIVED -> "ic_notif_moment"
        NotificationType.SYSTEM_UPDATE -> "ic_notif_badge"
        // These three marks shipped with the icon set and had nothing to point
        // at, because the display enum had no case for them. A tip is the one
        // that matters: it was rendering as a generic system notice.
        NotificationType.COOL_RECEIVED -> "ic_notif_cool"
        NotificationType.TIP_RECEIVED -> "ic_notif_hype_coin"
        NotificationType.RSVP -> "ic_notif_rsvp"
        NotificationType.SUBSCRIPTION -> "ic_notif_hype_coin"
        NotificationType.MENTION -> "ic_notif_stitch"
        NotificationType.SPIN_OFF -> "ic_notif_thread"
        NotificationType.GO_LIVE -> "ic_notif_moment"
        NotificationType.BADGE_EARNED -> "ic_notif_badge"
        NotificationType.STREAK -> "ic_notif_streak"
        NotificationType.NEW_VIDEO -> "ic_notif_new_thread"
    }
    val context = LocalContext.current
    return remember(name) {
        context.resources.getIdentifier(name, "drawable", context.packageName)
    }
}
