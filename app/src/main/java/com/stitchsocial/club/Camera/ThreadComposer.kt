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
    var isPosting by remember { mutableStateOf(false) }
    var showTagSheet by remember { mutableStateOf(false) }
    var showDiscardAlert by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

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
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                isPosting = true
                                try {
                                    // Complete video creation
                                    val createdVideo = videoCoordinator.completeVideoCreation(
                                        userTitle = title.trim(),
                                        userDescription = description.trim(),
                                        userHashtags = hashtags,
                                        taggedUserIDs = taggedUserIds
                                    )

                                    // Send notifications via Cloud Functions (matches iOS)
                                    sendPostCreationNotifications(
                                        notificationService = notificationService,
                                        createdVideo = createdVideo,
                                        recordingContext = recordingContext,
                                        taggedUserIds = taggedUserIds
                                    )

                                    onVideoCreated()
                                } catch (e: Exception) {
                                    println("COMPOSER: Upload failed: ${e.message}")
                                } finally {
                                    isPosting = false
                                }
                            }
                        },
                        enabled = !isPosting && title.isNotBlank()
                    ) {
                        if (isPosting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFF00BCD4),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "Post",
                                color = if (title.isNotBlank()) Color(0xFF00BCD4) else Color.Gray,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Cyan),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Cyan.copy(alpha = 0.5f))
        ) {
            Icon(
                Icons.Filled.PersonAdd,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color.Cyan
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (taggedUserIds.isEmpty()) "Tag Users" else "Edit Tags",
                color = Color.Cyan
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
                println("🔍 TAG SHEET: Found ${results.size} users for '$query'")
            } catch (e: Exception) {
                println("❌ TAG SHEET: Search error: ${e.message}")
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
                            color = if (selectedUsers.isNotEmpty()) Color.Cyan else Color.Gray,
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
                        .background(Color.Cyan.copy(alpha = 0.1f))
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
                            CircularProgressIndicator(color = Color.Cyan)
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
                                tint = Color.Cyan,
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
            .background(Color.Cyan, RoundedCornerShape(50))
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
            .background(if (isSelected) Color.Cyan.copy(alpha = 0.1f) else Color.Transparent)
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
                    if (isSelected) Modifier.border(2.dp, Color.Cyan, CircleShape)
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
                        tint = Color.Cyan,
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
                    if (isSelected) Color.Cyan else Color.Gray,
                    CircleShape
                )
                .background(
                    if (isSelected) Color.Cyan else Color.Transparent,
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
                println("COMPOSER NOTIF: Stitch notification sent to thread creator")
            }

            is RecordingContext.ReplyToVideo -> {
                // Notify video creator being replied to
                notificationService.sendReplyNotification(
                    recipientID = recordingContext.videoInfo.creatorId,
                    videoID = createdVideo.id,
                    videoTitle = createdVideo.title
                )
                println("COMPOSER NOTIF: Reply notification sent to video creator")
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
                println("COMPOSER NOTIF: Continue thread notification sent")
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
                    println("COMPOSER NOTIF: Mention notification sent to $taggedUserID")
                } catch (e: Exception) {
                    println("COMPOSER NOTIF: Mention failed for $taggedUserID - ${e.message}")
                }
            }
        }

    } catch (e: Exception) {
        // Non-fatal — video was already created successfully
        println("COMPOSER NOTIF: Post-creation notifications failed - ${e.message}")
    }
}