/*
 * ThreadComposer.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Thread Creation Interface
 * Package: com.stitchsocial.club.camera
 * Dependencies: VideoCoordinator, CoreVideoMetadata, SearchService
 * Features: Video preview, hashtag input, metadata editing, AI result integration, user tagging
 *
 * ✅ UPDATED: User tagging fully integrated (all components in single file)
 * ✅ UPDATED: Passes taggedUserIDs to VideoCoordinator.completeVideoCreation()
 */

package com.stitchsocial.club.camera

import com.stitchsocial.club.ui.theme.StitchColors
import com.stitchsocial.club.foundation.BasicUserInfo
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// Import from your existing packages
import com.stitchsocial.club.coordination.VideoCoordinator
import com.stitchsocial.club.services.VideoAnalysisResult
import com.stitchsocial.club.services.NotificationService
import com.stitchsocial.club.services.SearchService
import com.stitchsocial.club.TaggedUserChipById
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.challenge.ChallengeDraft
import com.stitchsocial.club.challenge.ChallengeScope
import com.stitchsocial.club.challenge.ChallengeMetric
import com.stitchsocial.club.challenge.ChallengeService
import com.stitchsocial.club.models.AnnouncementPriority
import com.stitchsocial.club.models.AnnouncementType
import com.stitchsocial.club.models.AnnouncementRepeatMode
import com.stitchsocial.club.events.EventDraft
import com.stitchsocial.club.events.EventService
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import java.util.Date

// Constants
private const val MAX_TAGGED_USERS = 5

// ============================================================================
// MARK: - Thread Composer
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadComposer(
    recordedVideoURL: String,
    recordingContext: RecordingContext,
    aiResult: VideoAnalysisResult?,
    videoCoordinator: VideoCoordinator,
    onVideoCreated: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val notificationService = remember { NotificationService() }

    // Convert String path to Uri
    val videoUri = remember(recordedVideoURL) {
        Uri.parse(recordedVideoURL)
    }

    // State
    var title by remember { mutableStateOf(aiResult?.title ?: "") }
    var description by remember { mutableStateOf(aiResult?.description ?: "") }
    var hashtags by remember { mutableStateOf(aiResult?.hashtags ?: emptyList()) }
    var taggedUserIds by remember { mutableStateOf<List<String>>(emptyList()) }

    var hashtagInput by remember { mutableStateOf("") }
    var showTagSheet by remember { mutableStateOf(false) }
    var showDiscardAlert by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    // MODEL C composer: ONE base screen — a plain post is as fast as today.
    // Giveaway / Announcement launch focused bottom sheets from the "Add to
    // post" rows and attach back as editable cards, so the heavy config never
    // bloats the base scroll (iOS ThreadComposer parity).

    // Challenge / Giveaway config — only meaningful for a brand-new thread head
    // (a challenge can't be a reply/stitch/continuation/spin-off).
    val isNewThread = recordingContext is RecordingContext.NewThread
    var isChallenge by remember { mutableStateOf(false) }
    var challengeDraft by remember { mutableStateOf(ChallengeDraft()) }
    var showGiveawaySheet by remember { mutableStateOf(false) }

    // Event add-on — only meaningful for a brand-new thread head (an event
    // lives on the thread-HEAD video, never a reply/stitch/continuation).
    var isEvent by remember { mutableStateOf(false) }
    var eventDraft by remember { mutableStateOf(EventDraft()) }
    var showEventSheet by remember { mutableStateOf(false) }

    // Announcement add-on — admin-gated (same allowlist as AnnouncementService).
    val capturedUserEmail = remember {
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email ?: ""
    }
    val canCreateAnnouncement = remember(capturedUserEmail) {
        com.stitchsocial.club.services.AnnouncementVideoHelper.canCreateAnnouncement(capturedUserEmail)
    }
    var isAnnouncement by remember { mutableStateOf(false) }
    var announcementDraft by remember { mutableStateOf(AnnouncementDraft()) }
    var showAnnouncementSheet by remember { mutableStateOf(false) }

    // Post is blocked while a challenge or event is attached but not fully configured.
    val canPost = title.isNotBlank() &&
        !(isChallenge && !challengeDraft.isValid) &&
        !(isEvent && !eventDraft.isValid)

    // Video player
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    // Apply AI results when they change
    LaunchedEffect(aiResult) {
        aiResult?.let { result ->
            if (title.isEmpty()) title = result.title
            if (description.isEmpty()) description = result.description
            if (hashtags.isEmpty()) hashtags = result.hashtags
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            TopAppBar(
                title = {
                    Text(
                        text = recordingContext.contextDisplayTitle,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { showDiscardAlert = true }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            // Snapshot the add-on configs before dismissal so
                            // the background completion callback attaches the
                            // right drafts even after this composable is gone.
                            val attachChallengeConfig = isChallenge && isNewThread
                            val draftToAttach = challengeDraft
                            val attachAnnouncementConfig = isAnnouncement && canCreateAnnouncement
                            val announcementToAttach = announcementDraft
                            val attachEventConfig = isEvent && isNewThread && eventDraft.isValid
                            val eventToAttach = eventDraft
                            // An Event Hub moment (Go Live / promo / recap): the
                            // Hub armed EventMomentBridge before opening the
                            // recorder. Take it exactly once, here at queue time,
                            // so an unrelated later post can't inherit it.
                            val eventMoment = com.stitchsocial.club.events.EventMomentBridge.take()
                            val announcementTitle = title.trim()
                            val announcementMessage = description.trim()
                            val creatorEmailSnapshot = capturedUserEmail
                            // Hand off to BackgroundPostManager (app-scoped
                            // coroutine) so the upload survives this composable
                            // being dismissed. Mirror's iOS ThreadComposer:
                            // we dismiss immediately and the tab-bar create
                            // button shows heat-phase + embers + % progress
                            // until the upload finishes.
                            com.stitchsocial.club.services.BackgroundPostManager.submitPost(
                                videoCoordinator = videoCoordinator,
                                userTitle = title.trim(),
                                userDescription = description.trim(),
                                userHashtags = hashtags,
                                taggedUserIDs = taggedUserIds,
                                onCompleted = { createdVideo ->
                                    // Attach the Challenge config onto the real
                                    // head-video id once the post lands. Only for
                                    // a new thread head — guard on isThread too.
                                    if (attachChallengeConfig && createdVideo.isThread) {
                                        kotlinx.coroutines.GlobalScope.launch {
                                            runCatching {
                                                ChallengeService.attachChallenge(createdVideo.id, draftToAttach)
                                            }.onFailure { e ->
                                                if (BuildConfig.DEBUG) {
                                                    println("COMPOSER: attachChallenge failed: ${e.message}")
                                                }
                                            }
                                        }
                                    }
                                    // Attach the Event config onto the real
                                    // head-video id once the post lands. Only for
                                    // a new thread head — guard on isThread too.
                                    if (attachEventConfig && createdVideo.isThread) {
                                        kotlinx.coroutines.GlobalScope.launch {
                                            runCatching {
                                                EventService.attachEvent(createdVideo.id, eventToAttach)
                                            }.onFailure { e ->
                                                if (BuildConfig.DEBUG) {
                                                    println("COMPOSER: attachEvent failed: ${e.message}")
                                                }
                                            }
                                        }
                                    }
                                    // Attach an Event Hub moment (Go Live / promo /
                                    // recap) onto the real video id once the post
                                    // lands. Snapshotted at queue time above.
                                    if (eventMoment != null) {
                                        kotlinx.coroutines.GlobalScope.launch {
                                            runCatching {
                                                when {
                                                    eventMoment.isPromo ->
                                                        EventService.setPromo(eventMoment.eventID, createdVideo)
                                                    eventMoment.isRecap ->
                                                        EventService.setRecap(eventMoment.eventID, createdVideo)
                                                    eventMoment.isPOV ->
                                                        EventService.attachPOV(eventMoment.eventID, eventMoment.agendaItemID, createdVideo.id, createdVideo.creatorID)
                                                    createdVideo.isThread ->
                                                        EventService.fillAgendaSlot(eventMoment.eventID, eventMoment.agendaItemID, createdVideo, eventMoment.hostUserID)
                                                }
                                            }.onFailure { e ->
                                                if (BuildConfig.DEBUG) {
                                                    println("COMPOSER: event moment attach failed: ${e.message}")
                                                }
                                            }
                                        }
                                    }
                                    // Attach the Announcement config (admin-gated)
                                    // onto the real video id once the post lands.
                                    if (attachAnnouncementConfig) {
                                        kotlinx.coroutines.GlobalScope.launch {
                                            runCatching {
                                                com.stitchsocial.club.services.AnnouncementService.shared.createAnnouncement(
                                                    videoId = createdVideo.id,
                                                    creatorEmail = creatorEmailSnapshot,
                                                    creatorId = createdVideo.creatorID,
                                                    title = announcementTitle,
                                                    message = announcementMessage.ifBlank { null },
                                                    priority = announcementToAttach.priority,
                                                    type = announcementToAttach.type,
                                                    startDate = announcementToAttach.startDate,
                                                    endDate = if (announcementToAttach.hasEndDate) announcementToAttach.endDate else null,
                                                    minimumWatchSeconds = announcementToAttach.minimumWatchSeconds,
                                                    repeatMode = announcementToAttach.repeatMode,
                                                    maxDailyShows = announcementToAttach.maxDailyShows,
                                                    minHoursBetweenShows = announcementToAttach.minHoursBetweenShows,
                                                    maxTotalShows = if (announcementToAttach.hasMaxTotalShows) announcementToAttach.maxTotalShows else null
                                                )
                                            }.onFailure { e ->
                                                if (BuildConfig.DEBUG) {
                                                    println("COMPOSER: createAnnouncement failed: ${e.message}")
                                                }
                                            }
                                        }
                                    }
                                    // Notifications still need to fire AFTER
                                    // the post lands. Run on the manager's
                                    // own scope so dismissal doesn't cancel.
                                    com.stitchsocial.club.services.BackgroundPostManager
                                        .let { _ ->
                                            kotlinx.coroutines.GlobalScope.launch {
                                                runCatching {
                                                    sendPostCreationNotifications(
                                                        notificationService = notificationService,
                                                        createdVideo = createdVideo,
                                                        recordingContext = recordingContext,
                                                        taggedUserIds = taggedUserIds
                                                    )
                                                }
                                            }
                                        }
                                },
                                onFailed = { error ->
                                    if (BuildConfig.DEBUG) {
                                        println("COMPOSER: Upload failed: ${error.message}")
                                    }
                                }
                            )
                            com.stitchsocial.club.services.AnalyticsService.videoPosted(
                                context = recordingContext.getBadgeText(),
                                durationSeconds = 0,
                            )
                            // Dismiss the composer immediately — user lands
                            // back on the feed while the upload runs.
                            onVideoCreated()
                        },
                        enabled = canPost,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE91E63),          // brand magenta capsule
                            contentColor = Color.White,
                            disabledContainerColor = Color.Gray.copy(alpha = 0.4f),
                            disabledContentColor = Color.White.copy(alpha = 0.7f)
                        ),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Post", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )

            // Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Video Preview
                VideoPreviewCard(
                    exoPlayer = exoPlayer,
                    isExpanded = isExpanded,
                    onToggleExpand = { isExpanded = !isExpanded }
                )

                // Context Banner
                ContextBanner(recordingContext)

                // Title Input
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 100) title = it },
                    label = { Text("Title", color = Color.Gray) },
                    placeholder = { Text("Add a title...", color = Color.Gray.copy(alpha = 0.5f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00BCD4),
                        unfocusedBorderColor = Color.Gray
                    ),
                    singleLine = true,
                    supportingText = {
                        Text("${title.length}/100", color = Color.Gray, fontSize = 12.sp)
                    }
                )

                // Description Input
                OutlinedTextField(
                    value = description,
                    onValueChange = { if (it.length <= 500) description = it },
                    label = { Text("Description", color = Color.Gray) },
                    placeholder = { Text("Add a description...", color = Color.Gray.copy(alpha = 0.5f)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00BCD4),
                        unfocusedBorderColor = Color.Gray
                    ),
                    maxLines = 5,
                    supportingText = {
                        Text("${description.length}/500", color = Color.Gray, fontSize = 12.sp)
                    }
                )

                // Hashtags Section
                HashtagsSection(
                    hashtags = hashtags,
                    hashtagInput = hashtagInput,
                    onInputChange = { hashtagInput = it },
                    onAddHashtag = {
                        val tag = hashtagInput.trim().removePrefix("#")
                        if (tag.isNotEmpty() && !hashtags.contains(tag) && hashtags.size < 10) {
                            hashtags = hashtags + tag
                            hashtagInput = ""
                        }
                    },
                    onRemoveHashtag = { tag: String ->
                        hashtags = hashtags.filter { it != tag }
                    }
                )

                // User Tag Section
                UserTagSection(
                    taggedUserIds = taggedUserIds,
                    maxTags = MAX_TAGGED_USERS,
                    onEditTags = { showTagSheet = true },
                    onRemoveUser = { userId: String ->
                        taggedUserIds = taggedUserIds.filter { it != userId }
                    }
                )

                // "Add to post" — compact launcher rows; each opens a focused
                // config sheet. A row disappears once its add-on is attached.
                AddToPostSection(
                    showGiveawayRow = isNewThread && !isChallenge,
                    showEventRow = isNewThread && !isEvent,
                    showAnnouncementRow = canCreateAnnouncement && !isAnnouncement,
                    onAddGiveaway = {
                        isChallenge = true
                        showGiveawaySheet = true
                    },
                    onAddEvent = {
                        isEvent = true
                        showEventSheet = true
                    },
                    onAddAnnouncement = {
                        isAnnouncement = true
                        showAnnouncementSheet = true
                    }
                )

                // "Added" — attached add-ons as editable/removable cards with
                // inline finish-setup warnings (replaces bottom-of-scroll hints).
                AddedSection(
                    isChallenge = isChallenge,
                    challengeDraft = challengeDraft,
                    onEditGiveaway = { showGiveawaySheet = true },
                    onRemoveGiveaway = { isChallenge = false },
                    isEvent = isEvent,
                    eventDraft = eventDraft,
                    onEditEvent = { showEventSheet = true },
                    onRemoveEvent = { isEvent = false },
                    isAnnouncement = isAnnouncement,
                    announcementDraft = announcementDraft,
                    onEditAnnouncement = { showAnnouncementSheet = true },
                    onRemoveAnnouncement = { isAnnouncement = false }
                )

                // AI Analysis Badge
                if (aiResult != null) {
                    AIAnalysisBadge()
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        // Discard Alert
        if (showDiscardAlert) {
            AlertDialog(
                onDismissRequest = { showDiscardAlert = false },
                title = { Text("Discard Video?", color = Color.White) },
                text = { Text("Your video and edits will be lost.", color = Color.Gray) },
                confirmButton = {
                    TextButton(onClick = {
                        showDiscardAlert = false
                        onCancel()
                    }) {
                        Text("Discard", color = Color.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscardAlert = false }) {
                        Text("Keep Editing", color = Color(0xFF00BCD4))
                    }
                },
                containerColor = Color(0xFF1E1E1E)
            )
        }

        // User Tag Sheet Modal
        if (showTagSheet) {
            Dialog(
                onDismissRequest = { showTagSheet = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false
                )
            ) {
                UserTagSheetContent(
                    onSelectUsers = { selectedIds: List<String> ->
                        taggedUserIds = selectedIds
                        showTagSheet = false
                    },
                    onDismiss = { showTagSheet = false },
                    alreadyTaggedIDs = emptyList(),
                    initiallySelectedIDs = taggedUserIds
                )
            }
        }

        // Giveaway config sheet (Model C: config lives here, not in the base scroll)
        if (showGiveawaySheet) {
            AddOnSheet(
                title = "Giveaway",
                icon = Icons.Filled.EmojiEvents,
                tint = GIVEAWAY_TINT,
                onDone = { showGiveawaySheet = false }
            ) {
                ChallengeConfigSection(
                    draft = challengeDraft,
                    onDraftChange = { challengeDraft = it }
                )
            }
        }

        // Event config sheet (Model C: config lives here, not in the base scroll)
        if (showEventSheet) {
            AddOnSheet(
                title = "Event",
                icon = Icons.Filled.Event,
                tint = EVENT_TINT,
                onDone = { showEventSheet = false }
            ) {
                EventConfigSection(
                    draft = eventDraft,
                    onDraftChange = { eventDraft = it }
                )
            }
        }

        // Announcement config sheet (admin-gated)
        if (showAnnouncementSheet) {
            AddOnSheet(
                title = "Announcement",
                icon = Icons.Filled.Campaign,
                tint = ANNOUNCEMENT_TINT,
                onDone = { showAnnouncementSheet = false }
            ) {
                AnnouncementConfigSection(
                    draft = announcementDraft,
                    onDraftChange = { announcementDraft = it }
                )
            }
        }
    }
}

// ============================================================================
// MARK: - User Tag Section
// ============================================================================

@Composable
private fun UserTagSection(
    taggedUserIds: List<String>,
    maxTags: Int,
    onEditTags: () -> Unit,
    onRemoveUser: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Tag Users",
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            )

            Text(
                "${taggedUserIds.size}/$maxTags",
                color = if (taggedUserIds.size == maxTags) Color(0xFFFF9800) else Color.Gray,
                fontSize = 14.sp
            )
        }

        // Tagged user chips (if any)
        if (taggedUserIds.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(taggedUserIds, key = { it }) { userId: String ->
                    TaggedUserChipById(
                        userID = userId,
                        onRemove = { onRemoveUser(userId) }
                    )
                }
            }
        }

        // Add/Edit button
        OutlinedButton(
            onClick = onEditTags,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = StitchColors.primary),
            border = androidx.compose.foundation.BorderStroke(1.dp, StitchColors.primary.copy(alpha = 0.5f))
        ) {
            Icon(
                Icons.Filled.PersonAdd,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = StitchColors.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (taggedUserIds.isEmpty()) "Tag Users" else "Edit Tags",
                color = StitchColors.primary
            )
        }
    }
}

// ============================================================================
// MARK: - User Tag Sheet Content
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserTagSheetContent(
    onSelectUsers: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    alreadyTaggedIDs: List<String>,
    initiallySelectedIDs: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val notificationService = remember { NotificationService() }

    // Services
    val searchService = remember { SearchService() }

    // State
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<BasicUserInfo>>(emptyList()) }
    var selectedUsers by remember { mutableStateOf<List<BasicUserInfo>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    // Computed
    val selectedUserIDs = selectedUsers.map { it.id }.toSet()
    val canSelectMore = selectedUsers.size < MAX_TAGGED_USERS

    // Search function with debounce
    fun performSearch(query: String) {
        searchJob?.cancel()

        if (query.isEmpty()) {
            searchResults = emptyList()
            isSearching = false
            return
        }

        searchJob = coroutineScope.launch {
            delay(300) // Debounce
            isSearching = true

            try {
                val results = searchService.searchUsers(query, 30)
                searchResults = results
                if (BuildConfig.DEBUG) { println("🔍 TAG SHEET: Found ${results.size} users for '$query'") }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("❌ TAG SHEET: Search error: ${e.message}") }
                searchResults = emptyList()
            } finally {
                isSearching = false
            }
        }
    }

    // Toggle user selection
    fun toggleSelection(user: BasicUserInfo) {
        if (selectedUserIDs.contains(user.id)) {
            selectedUsers = selectedUsers.filter { it.id != user.id }
        } else if (canSelectMore && !alreadyTaggedIDs.contains(user.id)) {
            selectedUsers = selectedUsers + user
        }
    }

    // Main UI
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            TopAppBar(
                title = {
                    Text(
                        "Tag People",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.Gray
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val finalIDs = selectedUsers.map { it.id }
                            onSelectUsers(finalIDs)
                        },
                        enabled = selectedUsers.isNotEmpty()
                    ) {
                        Text(
                            "Done",
                            color = if (selectedUsers.isNotEmpty()) StitchColors.primary else Color.Gray,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black
                )
            )

            // Search Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(Color(0xFF262626), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(18.dp)
                )

                BasicTextField(
                    value = searchQuery,
                    onValueChange = { newQuery: String ->
                        searchQuery = newQuery
                        performSearch(newQuery)
                    },
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(
                        color = Color.White,
                        fontSize = 16.sp
                    ),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text("Search users...", color = Color.Gray, fontSize = 16.sp)
                            }
                            innerTextField()
                        }
                    }
                )

                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            searchQuery = ""
                            searchResults = emptyList()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = "Clear",
                            tint = Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Selected Users Section
            AnimatedVisibility(
                visible = selectedUsers.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StitchColors.primary.copy(alpha = 0.1f))
                        .padding(vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Selected (${selectedUsers.size}/$MAX_TAGGED_USERS)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(selectedUsers, key = { it.id }) { user: BasicUserInfo ->
                            SelectedUserChip(
                                user = user,
                                onRemove = {
                                    selectedUsers = selectedUsers.filter { it.id != user.id }
                                }
                            )
                        }
                    }
                }
            }

            // Content Area
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isSearching -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = StitchColors.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Searching...", color = Color.Gray)
                        }
                    }
                    searchQuery.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = StitchColors.primary,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Search to tag users", fontSize = 18.sp, color = Color.White)
                        }
                    }
                    searchResults.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.SearchOff,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("No users found", fontSize = 18.sp, color = Color.White)
                        }
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            itemsIndexed(searchResults, key = { _: Int, user: BasicUserInfo -> user.id }) { _: Int, user: BasicUserInfo ->
                                val isSelected = selectedUserIDs.contains(user.id)
                                val isAlreadyTagged = alreadyTaggedIDs.contains(user.id)
                                val isDisabled = isAlreadyTagged || (!isSelected && !canSelectMore)

                                SearchResultRow(
                                    user = user,
                                    isSelected = isSelected,
                                    isDisabled = isDisabled,
                                    onTap = { toggleSelection(user) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedUserChip(
    user: BasicUserInfo,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .background(StitchColors.primary, RoundedCornerShape(50))
            .padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AsyncImage(
            model = user.profileImageURL,
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0xFF4D4D4D)),
            contentScale = ContentScale.Crop
        )

        Text(
            "@${user.username}",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.Black
        )

        Icon(
            Icons.Default.Close,
            contentDescription = "Remove",
            tint = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier
                .size(16.dp)
                .clickable { onRemove() }
        )
    }
}

@Composable
private fun SearchResultRow(
    user: BasicUserInfo,
    isSelected: Boolean,
    isDisabled: Boolean,
    onTap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(alpha = if (isDisabled) 0.5f else 1f)
            .background(if (isSelected) StitchColors.primary.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(enabled = !isDisabled) { onTap() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Profile image
        AsyncImage(
            model = user.profileImageURL,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF4D4D4D))
                .then(
                    if (isSelected) Modifier.border(2.dp, StitchColors.primary, CircleShape)
                    else Modifier
                ),
            contentScale = ContentScale.Crop
        )

        // User info
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    user.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (user.isVerified) {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = "Verified",
                        tint = StitchColors.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Text(
                "@${user.username}",
                fontSize = 14.sp,
                color = Color.Gray
            )
        }

        // Selection indicator
        Box(
            modifier = Modifier
                .size(24.dp)
                .border(
                    2.dp,
                    if (isSelected) StitchColors.primary else Color.Gray,
                    CircleShape
                )
                .background(
                    if (isSelected) StitchColors.primary else Color.Transparent,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.Black,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

// ============================================================================
// MARK: - Video Preview Card
// ============================================================================

@Composable
private fun VideoPreviewCard(
    exoPlayer: ExoPlayer,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val aspectRatio = if (isExpanded) 9f / 16f else 16f / 9f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clickable { onToggleExpand() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = "Toggle size",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ============================================================================
// MARK: - Context Banner
// ============================================================================

@Composable
private fun ContextBanner(recordingContext: RecordingContext) {
    when (recordingContext) {
        is RecordingContext.NewThread -> { }
        is RecordingContext.StitchToThread -> {
            ContextInfoBanner(
                icon = Icons.Filled.Link,
                label = "Stitching to",
                title = recordingContext.threadInfo.title,
                creatorName = recordingContext.threadInfo.creatorName
            )
        }
        is RecordingContext.ReplyToVideo -> {
            ContextInfoBanner(
                icon = Icons.Filled.Reply,
                label = "Replying to",
                title = recordingContext.videoInfo.title,
                creatorName = recordingContext.videoInfo.creatorName
            )
        }
        is RecordingContext.ContinueThread -> {
            ContextInfoBanner(
                icon = Icons.Filled.AddCircle,
                label = "Continuing",
                title = recordingContext.threadInfo.title,
                creatorName = recordingContext.threadInfo.creatorName
            )
        }
        is RecordingContext.SpinOffFrom -> {
            ContextInfoBanner(
                icon = Icons.Filled.CallSplit,
                label = "Spinning off from",
                title = recordingContext.videoInfo.title,
                creatorName = recordingContext.videoInfo.creatorName
            )
        }
    }
}

@Composable
private fun ContextInfoBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    title: String,
    creatorName: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF00BCD4).copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF00BCD4),
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = Color(0xFF00BCD4), fontSize = 12.sp)
                Text(title, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("by @$creatorName", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

// ============================================================================
// MARK: - Hashtags Section
// ============================================================================

@Composable
private fun HashtagsSection(
    hashtags: List<String>,
    hashtagInput: String,
    onInputChange: (String) -> Unit,
    onAddHashtag: () -> Unit,
    onRemoveHashtag: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hashtags", color = Color.White, fontWeight = FontWeight.Medium)

        if (hashtags.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hashtags) { tag: String ->
                    HashtagChip(tag = tag, onRemove = { onRemoveHashtag(tag) })
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = hashtagInput,
                onValueChange = onInputChange,
                placeholder = { Text("Add hashtag...", color = Color.Gray.copy(alpha = 0.5f)) },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00BCD4),
                    unfocusedBorderColor = Color.Gray
                ),
                singleLine = true,
                leadingIcon = {
                    Text("#", color = Color(0xFF00BCD4), fontWeight = FontWeight.Bold)
                }
            )

            IconButton(
                onClick = onAddHashtag,
                enabled = hashtagInput.isNotBlank() && hashtags.size < 10
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Add hashtag",
                    tint = if (hashtagInput.isNotBlank()) Color(0xFF00BCD4) else Color.Gray
                )
            }
        }

        Text("${hashtags.size}/10 hashtags", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun HashtagChip(tag: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF00BCD4).copy(alpha = 0.2f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("#$tag", color = Color(0xFF00BCD4), fontSize = 14.sp)
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove",
                tint = Color(0xFF00BCD4),
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onRemove() }
            )
        }
    }
}

// ============================================================================
// MARK: - AI Analysis Badge
// ============================================================================

@Composable
private fun AIAnalysisBadge() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF9C27B0).copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = Color(0xFF9C27B0),
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    "AI-Enhanced",
                    color = Color(0xFF9C27B0),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Text(
                    "Title and hashtags generated from your video",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ============================================================================
// MARK: - Add to Post (Model C launcher rows)
// ============================================================================

/** Add-on accent tints (iOS Model C parity: giveaway = yellow, event = orange, announcement = purple). */
private val GIVEAWAY_TINT = Color(0xFFFFC107)
private val EVENT_TINT = Color(0xFFFF9800)
private val ANNOUNCEMENT_TINT = Color(0xFFAB47BC)

@Composable
private fun AddToPostSection(
    showGiveawayRow: Boolean,
    showEventRow: Boolean,
    showAnnouncementRow: Boolean,
    onAddGiveaway: () -> Unit,
    onAddEvent: () -> Unit,
    onAddAnnouncement: () -> Unit
) {
    if (!showGiveawayRow && !showEventRow && !showAnnouncementRow) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Add to post",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp
        )

        if (showGiveawayRow) {
            AddOnLauncherRow(
                icon = Icons.Filled.EmojiEvents,
                tint = GIVEAWAY_TINT,
                title = "Giveaway",
                subtitle = "Run a prize draw",
                onClick = onAddGiveaway
            )
        }

        if (showEventRow) {
            AddOnLauncherRow(
                icon = Icons.Filled.Event,
                tint = EVENT_TINT,
                title = "Event",
                subtitle = "Date, venue & RSVP",
                onClick = onAddEvent
            )
        }

        if (showAnnouncementRow) {
            AddOnLauncherRow(
                icon = Icons.Filled.Campaign,
                tint = ANNOUNCEMENT_TINT,
                title = "Announcement",
                subtitle = "Pin & schedule",
                onClick = onAddAnnouncement
            )
        }
    }
}

@Composable
private fun AddOnLauncherRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = Color.Gray, fontSize = 12.sp)
        }
        Icon(
            Icons.Filled.AddCircle,
            contentDescription = "Add $title",
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ============================================================================
// MARK: - Added (attached add-on cards + inline finish-setup warnings)
// ============================================================================

@Composable
private fun AddedSection(
    isChallenge: Boolean,
    challengeDraft: ChallengeDraft,
    onEditGiveaway: () -> Unit,
    onRemoveGiveaway: () -> Unit,
    isEvent: Boolean,
    eventDraft: EventDraft,
    onEditEvent: () -> Unit,
    onRemoveEvent: () -> Unit,
    isAnnouncement: Boolean,
    announcementDraft: AnnouncementDraft,
    onEditAnnouncement: () -> Unit,
    onRemoveAnnouncement: () -> Unit
) {
    if (!isChallenge && !isEvent && !isAnnouncement) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Added",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp
        )

        if (isChallenge) {
            val winners = if (challengeDraft.winnerCount == 1) "1 winner"
                          else "${challengeDraft.winnerCount} winners"
            AddedAddOnCard(
                icon = Icons.Filled.EmojiEvents,
                tint = GIVEAWAY_TINT,
                summary = if (challengeDraft.prize.isBlank()) "🏆 Giveaway"
                          else "🏆 ${challengeDraft.prize} · $winners",
                warning = if (challengeDraft.isValid) null
                          else "Needs: ${challengeDraft.missingFields.joinToString(", ")}",
                onEdit = onEditGiveaway,
                onRemove = onRemoveGiveaway
            )
        }

        if (isEvent) {
            val eventParts = listOf(eventDraft.name, eventDraft.city).filter { it.isNotBlank() }
            AddedAddOnCard(
                icon = Icons.Filled.Event,
                tint = EVENT_TINT,
                summary = if (eventParts.isEmpty()) "📅 Event"
                          else "📅 ${eventParts.joinToString(" · ")}",
                warning = if (eventDraft.isValid) null
                          else "Needs: ${eventDraft.missingFields.joinToString(", ")}",
                onEdit = onEditEvent,
                onRemove = onRemoveEvent
            )
        }

        if (isAnnouncement) {
            AddedAddOnCard(
                icon = Icons.Filled.Campaign,
                tint = ANNOUNCEMENT_TINT,
                summary = "📣 ${announcementDraft.type.displayName} · ${announcementDraft.priority.displayName}",
                warning = null,
                onEdit = onEditAnnouncement,
                onRemove = onRemoveAnnouncement
            )
        }
    }
}

@Composable
private fun AddedAddOnCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    summary: String,
    warning: String?,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text(
                summary,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Edit",
                color = Color(0xFF00BCD4),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onEdit() }
            )
            Icon(
                Icons.Filled.Cancel,
                contentDescription = "Remove",
                tint = Color.Gray,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onRemove() }
            )
        }
        if (warning != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(14.dp)
                )
                Text(warning, color = Color(0xFFFF9800), fontSize = 12.sp, maxLines = 2)
            }
        }
    }
}

// ============================================================================
// MARK: - Add-on sheet chrome (hosts the config sections + Done button)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddOnSheet(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onDone: () -> Unit,
    content: @Composable () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = sheetState,
        containerColor = Color(0xFF121212)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = Color.White)
            ) {
                Text("Done", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

// ============================================================================
// MARK: - Giveaway config (hosted in the add-on sheet)
// ============================================================================

@Composable
private fun ChallengeConfigSection(
    draft: ChallengeDraft,
    onDraftChange: (ChallengeDraft) -> Unit
) {
    val accent = Color(0xFF00BCD4)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Prize
        OutlinedTextField(
            value = draft.prize,
            onValueChange = { onDraftChange(draft.copy(prize = it)) },
            label = { Text("Prize", color = Color.Gray) },
            placeholder = { Text("e.g. \$100 gift card", color = Color.Gray.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = accent,
                unfocusedBorderColor = Color.Gray
            ),
            singleLine = true
        )

        // Entry hashtag (+ normalized preview)
        OutlinedTextField(
            value = draft.hashtag,
            onValueChange = { onDraftChange(draft.copy(hashtag = it)) },
            label = { Text("Entry hashtag", color = Color.Gray) },
            placeholder = { Text("dancechallenge", color = Color.Gray.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = accent,
                unfocusedBorderColor = Color.Gray
            ),
            singleLine = true,
            leadingIcon = { Text("#", color = accent, fontWeight = FontWeight.Bold) },
            supportingText = {
                if (draft.normalizedHashtag.isNotEmpty()) {
                    Text(
                        "Entries post with #${draft.normalizedHashtag}",
                        color = accent,
                        fontSize = 12.sp
                    )
                }
            }
        )

        // Scope
        Text("Who can enter", color = Color.Gray, fontSize = 13.sp)
        ChallengeSegmented(
            options = listOf(
                "Anyone" to ChallengeScope.ANYONE,
                "Followers" to ChallengeScope.FOLLOWERS,
                "Community" to ChallengeScope.COMMUNITY
            ),
            selected = draft.scope,
            accent = accent,
            onSelect = { onDraftChange(draft.copy(scope = it)) }
        )

        // Metric
        Text("Qualify by", color = Color.Gray, fontSize = 13.sp)
        ChallengeSegmented(
            options = listOf(
                "Hypes" to ChallengeMetric.HYPES,
                "Shares" to ChallengeMetric.SHARES
            ),
            selected = draft.metric,
            accent = accent,
            onSelect = { onDraftChange(draft.copy(metric = it)) }
        )

        // Threshold stepper
        ChallengeStepper(
            label = "Threshold to qualify",
            value = "${draft.threshold} ${draft.metric.displayName}",
            accent = accent,
            onDecrement = {
                onDraftChange(draft.copy(threshold = (draft.threshold - 10).coerceAtLeast(1)))
            },
            onIncrement = { onDraftChange(draft.copy(threshold = draft.threshold + 10)) }
        )

        // Winners stepper
        ChallengeStepper(
            label = "Winners",
            value = if (draft.winnerCount == 1) "1 winner" else "${draft.winnerCount} winners",
            accent = accent,
            onDecrement = {
                onDraftChange(draft.copy(winnerCount = (draft.winnerCount - 1).coerceAtLeast(1)))
            },
            onIncrement = { onDraftChange(draft.copy(winnerCount = draft.winnerCount + 1)) }
        )

        // Deadline (date + time)
        DateTimeEditRow(
            label = "Deadline",
            value = draft.deadline,
            accent = accent,
            onChange = { onDraftChange(draft.copy(deadline = it)) }
        )

        // Anti-farm eligibility (enforced server-side at entry/qualification
        // time — already live; the client just records the creator's gates)
        AntiFarmEligibilityBlock(draft = draft, onDraftChange = onDraftChange)

        // Finish-setup hint (drives the disabled Post button)
        if (!draft.isValid) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Finish setup: ${draft.missingFields.joinToString(", ")}",
                    color = Color(0xFFFF9800),
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ============================================================================
// MARK: - Anti-farm eligibility (giveaway sheet)
// ============================================================================

@Composable
private fun AntiFarmEligibilityBlock(
    draft: ChallengeDraft,
    onDraftChange: (ChallengeDraft) -> Unit
) {
    val green = Color(0xFF4CAF50)
    var ageMenuExpanded by remember { mutableStateOf(false) }
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = green,
        uncheckedThumbColor = Color.Gray,
        uncheckedTrackColor = Color(0xFF2A2A2A)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(green.copy(alpha = 0.06f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Filled.Shield,
                contentDescription = null,
                tint = green,
                modifier = Modifier.size(14.dp)
            )
            Text(
                "Anti-farm eligibility",
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Always-on: the draw is one ticket per person, not per video.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.HowToReg,
                contentDescription = null,
                tint = green,
                modifier = Modifier.size(16.dp)
            )
            Text(
                "One entry per person",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "ALWAYS ON",
                color = green,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(green.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Count unique hypers", color = Color.White, fontSize = 14.sp)
                Text(
                    "Qualify on distinct accounts, not raw taps",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = draft.uniqueHypers,
                onCheckedChange = { onDraftChange(draft.copy(uniqueHypers = it)) },
                colors = switchColors
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Verified accounts only",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = draft.verifiedOnly,
                onCheckedChange = { onDraftChange(draft.copy(verifiedOnly = it)) },
                colors = switchColors
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Min account age",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(green.copy(alpha = 0.15f))
                        .clickable { ageMenuExpanded = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (draft.minAccountAgeDays == 0) "Off" else "${draft.minAccountAgeDays} days",
                        color = green,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = green,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = ageMenuExpanded,
                    onDismissRequest = { ageMenuExpanded = false }
                ) {
                    listOf(0, 7, 30, 90).forEach { days ->
                        DropdownMenuItem(
                            text = { Text(if (days == 0) "Off" else "$days days") },
                            onClick = {
                                onDraftChange(draft.copy(minAccountAgeDays = days))
                                ageMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// MARK: - Event config (hosted in the add-on sheet)
// ============================================================================

@Composable
private fun EventConfigSection(
    draft: EventDraft,
    onDraftChange: (EventDraft) -> Unit
) {
    val accent = EVENT_TINT
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = accent,
        unfocusedBorderColor = Color.Gray
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Event name
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = { Text("Event name", color = Color.Gray) },
            placeholder = { Text("e.g. Rooftop listening party", color = Color.Gray.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
            singleLine = true
        )

        // Venue
        OutlinedTextField(
            value = draft.venueName,
            onValueChange = { onDraftChange(draft.copy(venueName = it)) },
            label = { Text("Venue", color = Color.Gray) },
            placeholder = { Text("e.g. The Loft", color = Color.Gray.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
            singleLine = true
        )

        // City
        OutlinedTextField(
            value = draft.city,
            onValueChange = { onDraftChange(draft.copy(city = it)) },
            label = { Text("City", color = Color.Gray) },
            placeholder = { Text("e.g. Atlanta", color = Color.Gray.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
            singleLine = true
        )

        // RSVP link (optional)
        OutlinedTextField(
            value = draft.rsvpURL,
            onValueChange = { onDraftChange(draft.copy(rsvpURL = it)) },
            label = { Text("RSVP link (optional)", color = Color.Gray) },
            placeholder = { Text("https://…", color = Color.Gray.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        // Starts (date + time; min = now — past picks clamp forward)
        DateTimeEditRow(
            label = "Starts",
            value = draft.startAt,
            accent = accent,
            onChange = { picked ->
                onDraftChange(draft.copy(startAt = if (picked.before(Date())) Date() else picked))
            }
        )

        // Finish-setup hint (drives the disabled Post button)
        if (!draft.isValid) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Finish setup: ${draft.missingFields.joinToString(", ")}",
                    color = Color(0xFFFF9800),
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ============================================================================
// MARK: - Announcement config (admin-gated; hosted in the add-on sheet)
// ============================================================================

/** View-layer state the announcement sheet collects; expanded into a real
 *  announcement doc via AnnouncementService.createAnnouncement once the post
 *  lands (same attach-after-upload pattern as the challenge). */
private data class AnnouncementDraft(
    val priority: AnnouncementPriority = AnnouncementPriority.STANDARD,
    val type: AnnouncementType = AnnouncementType.UPDATE,
    val minimumWatchSeconds: Int = 5,
    val startDate: Date = Date(),
    val hasEndDate: Boolean = false,
    val endDate: Date = Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000),
    val repeatMode: AnnouncementRepeatMode = AnnouncementRepeatMode.ONCE,
    val maxDailyShows: Int = 1,
    val minHoursBetweenShows: Double = 6.0,
    val hasMaxTotalShows: Boolean = false,
    val maxTotalShows: Int = 10
)

@Composable
private fun AnnouncementConfigSection(
    draft: AnnouncementDraft,
    onDraftChange: (AnnouncementDraft) -> Unit
) {
    val accent = ANNOUNCEMENT_TINT
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = accent,
        uncheckedThumbColor = Color.Gray,
        uncheckedTrackColor = Color(0xFF2A2A2A)
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "All users must view this at least once. Pin it to the app and schedule when (and how often) it shows.",
            color = Color.Gray,
            fontSize = 12.sp
        )

        AddOnDropdownRow(
            label = "Priority",
            options = AnnouncementPriority.entries.map { it.displayName to it },
            selectedLabel = draft.priority.displayName,
            tint = accent,
            onSelect = { onDraftChange(draft.copy(priority = it)) }
        )

        AddOnDropdownRow(
            label = "Type",
            options = AnnouncementType.entries.map { it.displayName to it },
            selectedLabel = draft.type.displayName,
            tint = accent,
            onSelect = { onDraftChange(draft.copy(type = it)) }
        )

        ChallengeStepper(
            label = "Min watch time",
            value = "${draft.minimumWatchSeconds}s",
            accent = accent,
            onDecrement = {
                onDraftChange(draft.copy(minimumWatchSeconds = (draft.minimumWatchSeconds - 1).coerceAtLeast(3)))
            },
            onIncrement = {
                onDraftChange(draft.copy(minimumWatchSeconds = (draft.minimumWatchSeconds + 1).coerceAtMost(30)))
            }
        )

        DateTimeEditRow(
            label = "Starts",
            value = draft.startDate,
            accent = accent,
            onChange = { onDraftChange(draft.copy(startDate = it)) }
        )

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Set end date", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = draft.hasEndDate,
                onCheckedChange = { onDraftChange(draft.copy(hasEndDate = it)) },
                colors = switchColors
            )
        }
        if (draft.hasEndDate) {
            DateTimeEditRow(
                label = "Ends",
                value = draft.endDate,
                accent = accent,
                onChange = { onDraftChange(draft.copy(endDate = it)) }
            )
        }

        AddOnDropdownRow(
            label = "Repeat",
            options = AnnouncementRepeatMode.entries.map { it.displayName to it },
            selectedLabel = draft.repeatMode.displayName,
            tint = accent,
            onSelect = { onDraftChange(draft.copy(repeatMode = it)) }
        )
        Text(draft.repeatMode.description, color = Color.Gray, fontSize = 11.sp)

        if (draft.repeatMode != AnnouncementRepeatMode.ONCE) {
            ChallengeStepper(
                label = "Max shows per day",
                value = "${draft.maxDailyShows}x",
                accent = accent,
                onDecrement = {
                    onDraftChange(draft.copy(maxDailyShows = (draft.maxDailyShows - 1).coerceAtLeast(1)))
                },
                onIncrement = {
                    onDraftChange(draft.copy(maxDailyShows = (draft.maxDailyShows + 1).coerceAtMost(10)))
                }
            )
            ChallengeStepper(
                label = "Min hours between shows",
                value = "${draft.minHoursBetweenShows.toInt()}h",
                accent = accent,
                onDecrement = {
                    onDraftChange(draft.copy(minHoursBetweenShows = (draft.minHoursBetweenShows - 1.0).coerceAtLeast(1.0)))
                },
                onIncrement = {
                    onDraftChange(draft.copy(minHoursBetweenShows = (draft.minHoursBetweenShows + 1.0).coerceAtMost(24.0)))
                }
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Lifetime cap", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = draft.hasMaxTotalShows,
                    onCheckedChange = { onDraftChange(draft.copy(hasMaxTotalShows = it)) },
                    colors = switchColors
                )
            }
            if (draft.hasMaxTotalShows) {
                ChallengeStepper(
                    label = "Max total shows",
                    value = "${draft.maxTotalShows}",
                    accent = accent,
                    onDecrement = {
                        onDraftChange(draft.copy(maxTotalShows = (draft.maxTotalShows - 1).coerceAtLeast(2)))
                    },
                    onIncrement = {
                        onDraftChange(draft.copy(maxTotalShows = (draft.maxTotalShows + 1).coerceAtMost(100)))
                    }
                )
            }
        }
    }
}

// ============================================================================
// MARK: - Shared add-on sheet controls
// ============================================================================

@Composable
private fun <T> AddOnDropdownRow(
    label: String,
    options: List<Pair<String, T>>,
    selectedLabel: String,
    tint: Color,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.15f))
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(selectedLabel, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (optionLabel, value) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/** Label + formatted value with Date/Time buttons; hosts its own picker dialogs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeEditRow(
    label: String,
    value: Date,
    accent: Color,
    onChange: (Date) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val format = remember {
        java.text.SimpleDateFormat("MMM d, yyyy · h:mm a", java.util.Locale.getDefault())
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.Gray, fontSize = 13.sp)
            Text(
                format.format(value),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showDatePicker = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp), tint = accent)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Date", color = accent, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { showTimePicker = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = accent)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Time", color = accent, fontSize = 13.sp)
            }
        }
    }

    // Date picker dialog — updates the calendar day, preserving time-of-day.
    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = value.time)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                            .apply { timeInMillis = millis }
                        val cal = java.util.Calendar.getInstance().apply {
                            time = value
                            set(java.util.Calendar.YEAR, utc.get(java.util.Calendar.YEAR))
                            set(java.util.Calendar.MONTH, utc.get(java.util.Calendar.MONTH))
                            set(java.util.Calendar.DAY_OF_MONTH, utc.get(java.util.Calendar.DAY_OF_MONTH))
                        }
                        onChange(cal.time)
                    }
                    showDatePicker = false
                }) { Text("OK", color = accent) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel", color = Color.Gray) }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    // Time picker dialog — updates hour/minute, preserving the calendar day.
    if (showTimePicker) {
        val cal = remember(value) { java.util.Calendar.getInstance().apply { time = value } }
        val timeState = rememberTimePickerState(
            initialHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(java.util.Calendar.MINUTE),
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val c = java.util.Calendar.getInstance().apply {
                        time = value
                        set(java.util.Calendar.HOUR_OF_DAY, timeState.hour)
                        set(java.util.Calendar.MINUTE, timeState.minute)
                    }
                    onChange(c.time)
                    showTimePicker = false
                }) { Text("OK", color = accent) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel", color = Color.Gray) }
            },
            text = { TimePicker(state = timeState) },
            containerColor = Color(0xFF1E1E1E)
        )
    }
}

@Composable
private fun <T> ChallengeSegmented(
    options: List<Pair<String, T>>,
    selected: T,
    accent: Color,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E1E1E))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (label, value) ->
            val isSel = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSel) accent else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (isSel) Color.Black else Color.Gray,
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ChallengeStepper(
    label: String,
    value: String,
    accent: Color,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.Gray, fontSize = 13.sp)
            Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            StepperButton(Icons.Filled.Remove, accent, onDecrement)
            StepperButton(Icons.Filled.Add, accent, onIncrement)
        }
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.15f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
    }
}

// ============================================================================
// MARK: - Recording Context Extension
// ============================================================================

val RecordingContext.contextDisplayTitle: String
    get() = when (this) {
        is RecordingContext.NewThread -> "New Thread"
        is RecordingContext.StitchToThread -> "Stitch"
        is RecordingContext.ReplyToVideo -> "Reply"
        is RecordingContext.ContinueThread -> "Continue Thread"
        else -> "New Thread"
    }
// ============================================================================
// MARK: - Post-Creation Notifications (matches iOS ThreadComposer)
// ============================================================================

/**
 * Send all relevant notifications after video creation.
 * Cloud Functions handle username lookup, message building, and FCM push.
 */
private suspend fun sendPostCreationNotifications(
    notificationService: NotificationService,
    createdVideo: com.stitchsocial.club.foundation.CoreVideoMetadata,
    recordingContext: RecordingContext,
    taggedUserIds: List<String>
) {
    try {
        val currentUserID = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return

        // 1. STITCH / REPLY notifications based on context
        when (recordingContext) {
            is RecordingContext.StitchToThread -> {
                // Notify original thread creator
                notificationService.sendStitchNotification(
                    videoID = createdVideo.id,
                    videoTitle = createdVideo.title,
                    originalCreatorID = recordingContext.threadInfo.creatorId,
                    parentCreatorID = null,
                    threadUserIDs = emptyList()
                )
                if (BuildConfig.DEBUG) { println("COMPOSER NOTIF: Stitch notification sent to thread creator") }
            }

            is RecordingContext.ReplyToVideo -> {
                // Notify video creator being replied to
                notificationService.sendReplyNotification(
                    recipientID = recordingContext.videoInfo.creatorId,
                    videoID = createdVideo.id,
                    videoTitle = createdVideo.title
                )
                if (BuildConfig.DEBUG) { println("COMPOSER NOTIF: Reply notification sent to video creator") }
            }

            is RecordingContext.ContinueThread -> {
                // Notify thread creator
                notificationService.sendStitchNotification(
                    videoID = createdVideo.id,
                    videoTitle = createdVideo.title,
                    originalCreatorID = recordingContext.threadInfo.creatorId,
                    parentCreatorID = null,
                    threadUserIDs = emptyList()
                )
                if (BuildConfig.DEBUG) { println("COMPOSER NOTIF: Continue thread notification sent") }
            }

            is RecordingContext.NewThread -> {
                // No notification needed for new threads
            }

            else -> { }
        }

        // 2. MENTION notifications for tagged users
        taggedUserIds.forEach { taggedUserID ->
            if (taggedUserID != currentUserID) {
                try {
                    notificationService.sendMentionNotification(
                        recipientID = taggedUserID,
                        videoID = createdVideo.id,
                        videoTitle = createdVideo.title,
                        mentionContext = "video"
                    )
                    if (BuildConfig.DEBUG) { println("COMPOSER NOTIF: Mention notification sent to $taggedUserID") }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) { println("COMPOSER NOTIF: Mention failed for $taggedUserID - ${e.message}") }
                }
            }
        }

    } catch (e: Exception) {
        // Non-fatal — video was already created successfully
        if (BuildConfig.DEBUG) { println("COMPOSER NOTIF: Post-creation notifications failed - ${e.message}") }
    }
}