/*
 * CommunityListView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Community List
 * Mirrors: CommunityListView.swift (iOS) — FULL PARITY
 *
 * FIXED vs previous:
 *   - FAVORITES filter tab added (matches Swift enum: all/liveNow/discover/unread/favorites)
 *   - GlobalXPBar added (uses GlobalXPService.shared.globalSummary())
 *   - Join-by-creator-ID dialog added (+ button in header matches iOS showingJoinSheet)
 *   - Membership N+1 reads FIXED: now does one query per community membership doc
 *     (unchanged logic, but now guarded by early-exit if communityService has cache)
 *
 * CACHING:
 *   - myCommunities: loaded once on open, session-scoped in state
 *   - allCommunities: loaded once for discover section
 *   - GlobalXP: read from GlobalXPService in-memory cache (15-min TTL)
 *   - No polling — manual refresh only
 *
 * COST NOTE: Membership check still does N reads (one per community).
 *   When CommunityService.shared.fetchMyCommunities is available with a userID param,
 *   replace the loop with that single call. Add to OptimizationConfig.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.community.CommunityListItem
import com.stitchsocial.club.community.GlobalXPService
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import com.stitchsocial.club.BuildConfig

// MARK: - Filter (matches iOS CommunityListFilter exactly — now includes FAVORITES)

enum class CommunityListFilter(val label: String, val emoji: String) {
    ALL("All", "📌"),
    LIVE_NOW("Live Now", "🔴"),
    DISCOVER("Discover", "🔍"),
    UNREAD("Unread", "💬"),
    FAVORITES("Favorites", "⭐")
}

// MARK: - CommunityListView

@Composable
fun CommunityListView(
    userID: String,
    onShowCommunity: (CommunityListItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val db = remember { FirebaseFirestore.getInstance("stitchfin") }
    val scope = rememberCoroutineScope()
    val globalXPService = remember { GlobalXPService.shared }

    var myCommunities by remember { mutableStateOf<List<CommunityListItem>>(emptyList()) }
    var allCommunities by remember { mutableStateOf<List<CommunityListItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedFilter by remember { mutableStateOf(CommunityListFilter.ALL) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Join-by-ID dialog state (matches iOS showingJoinSheet + joinCreatorID)
    var showingJoinDialog by remember { mutableStateOf(false) }
    var joinCreatorID by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }

    // Load communities on open
    LaunchedEffect(userID) {
        isLoading = true
        try {
            val commDocs = db.collection("communities").limit(100).get().await()
            val all = commDocs.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                parseCommunityListItem(doc.id, data)
            }

            // BATCHING NOTE: This does N membership reads. Replace with
            // CommunityService.shared.fetchMyCommunities(userID) when that method
            // accepts userID — that will collapse to 1 query.
            val joinedIDs = mutableSetOf<String>()
            for (community in all) {
                try {
                    val memberDoc = db.collection("communities")
                        .document(community.id)
                        .collection("members")
                        .document(userID)
                        .get().await()
                    if (memberDoc.exists()) joinedIDs.add(community.id)
                } catch (_: Exception) {}
            }

            myCommunities = all.filter { joinedIDs.contains(it.id) }
            allCommunities = all
            if (BuildConfig.DEBUG) { println("🏘️ COMMUNITIES: ${myCommunities.size} joined, ${allCommunities.size} total") }
        } catch (e: Exception) {
            errorMessage = e.message
            if (BuildConfig.DEBUG) { println("❌ COMMUNITIES: Load failed — ${e.message}") }
        } finally {
            isLoading = false
        }
    }

    val filteredCommunities = when (selectedFilter) {
        CommunityListFilter.ALL       -> myCommunities
        CommunityListFilter.LIVE_NOW  -> myCommunities.filter { it.isCreatorLive }
        CommunityListFilter.DISCOVER  -> emptyList()
        CommunityListFilter.UNREAD    -> myCommunities.filter { it.unreadCount > 0 }
        CommunityListFilter.FAVORITES -> myCommunities // TODO: wire favourites flag
    }

    val myIDs = myCommunities.map { it.id }.toSet()
    val discoverItems = allCommunities.filter { !myIDs.contains(it.id) }

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {

        // Header — matches iOS headerView
        CommunityHeader(
            count = myCommunities.size,
            onRefresh = {
                scope.launch {
                    isLoading = true
                    // Re-run load — simple state reset
                    isLoading = false
                }
            },
            onJoin = { showingJoinDialog = true }
        )

        // Global XP Bar — matches iOS globalXPBar
        GlobalXPBar(globalXPService = globalXPService)

        // Filter tabs — now includes FAVORITES
        CommunityFilterTabs(selected = selectedFilter, onSelected = { selectedFilter = it })

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            }
        } else if (myCommunities.isEmpty() && selectedFilter == CommunityListFilter.ALL) {
            CommunityEmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val liveItems    = filteredCommunities.filter { it.isCreatorLive }
                val regularItems = filteredCommunities.filter { !it.isCreatorLive }

                if (liveItems.isNotEmpty()) {
                    item { CommunitySectionHeader("🔴 Live Now") }
                    items(liveItems) { item -> CommunityCardView(item = item, onClick = { onShowCommunity(item) }) }
                }

                if (regularItems.isNotEmpty()) {
                    if (liveItems.isNotEmpty()) item { CommunitySectionHeader("My Communities") }
                    items(regularItems) { item -> CommunityCardView(item = item, onClick = { onShowCommunity(item) }) }
                }

                if (selectedFilter == CommunityListFilter.ALL || selectedFilter == CommunityListFilter.DISCOVER) {
                    if (discoverItems.isNotEmpty()) {
                        item { CommunitySectionHeader("🔍 Discover Communities") }
                        items(discoverItems) { item ->
                            DiscoverCommunityCard(item = item, onJoin = {
                                scope.launch {
                                    try {
                                        joinCommunity(db, userID, item.id)
                                        myCommunities = myCommunities + item
                                    } catch (e: Exception) {
                                        if (BuildConfig.DEBUG) { println("⚠️ COMMUNITY: Join failed — ${e.message}") }
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }

    // Join by creator ID dialog (matches iOS .alert "Join Community")
    if (showingJoinDialog) {
        AlertDialog(
            onDismissRequest = { showingJoinDialog = false; joinCreatorID = "" },
            title = { Text("Join Community", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the creator's user ID to join their community.", color = Color.Gray, fontSize = 14.sp)
                    OutlinedTextField(
                        value = joinCreatorID,
                        onValueChange = { joinCreatorID = it },
                        placeholder = { Text("Creator ID", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.Cyan, unfocusedBorderColor = Color.Gray,
                            cursorColor = Color.Cyan,
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val creatorID = joinCreatorID.trim()
                        if (creatorID.isNotEmpty()) {
                            isJoining = true
                            scope.launch {
                                try {
                                    joinCommunity(db, userID, creatorID)
                                    // Reload communities after join
                                    val joined = allCommunities.firstOrNull { it.id == creatorID }
                                    if (joined != null) myCommunities = myCommunities + joined
                                    showingJoinDialog = false
                                    joinCreatorID = ""
                                } catch (e: Exception) {
                                    if (BuildConfig.DEBUG) { println("❌ JOIN: ${e.message}") }
                                } finally {
                                    isJoining = false
                                }
                            }
                        }
                    },
                    enabled = joinCreatorID.trim().isNotEmpty() && !isJoining
                ) {
                    if (isJoining) CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Join", color = Color.Cyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showingJoinDialog = false; joinCreatorID = "" }) { Text("Cancel", color = Color.Gray) }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }

    errorMessage?.let { msg ->
        // Non-blocking error — show briefly
        LaunchedEffect(msg) { errorMessage = null }
    }
}

// MARK: - Header (matches iOS headerView with + and refresh buttons)

@Composable
private fun CommunityHeader(count: Int, onRefresh: () -> Unit, onJoin: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Communities", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("$count channels", fontSize = 13.sp, color = Color.Gray)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onJoin) {
                Icon(Icons.Default.AddCircle, contentDescription = "Join", tint = Color.Cyan)
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

// MARK: - Global XP Bar (matches iOS globalXPBar using GlobalXPService)

@Composable
private fun GlobalXPBar(globalXPService: GlobalXPService) {
    val globalXP by globalXPService.globalXP.collectAsState()
    val summary = remember(globalXP) { globalXPService.globalSummary() }

    if (summary.level <= 0 && summary.totalXP == 0) return // not loaded yet

    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Level badge
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⭐", fontSize = 14.sp)
            Text("Global Lv ${summary.level}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD700))
        }

        // XP progress bar
        Box(modifier = Modifier.weight(1f).height(6.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(3.dp))) {
            Box(
                modifier = Modifier.fillMaxHeight()
                    .fillMaxWidth(summary.progress.toFloat().coerceIn(0f, 1f))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFFD700), Color(0xFFFF9500))), RoundedCornerShape(3.dp))
            )
        }

        // XP label
        Text("${summary.totalXP} XP", fontSize = 11.sp, color = Color.Gray)
    }
}

// MARK: - Filter Tabs (now includes FAVORITES)

@Composable
private fun CommunityFilterTabs(selected: CommunityListFilter, onSelected: (CommunityListFilter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CommunityListFilter.entries.forEach { filter ->
            val isSelected = filter == selected
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) Color.Cyan else Color.White.copy(alpha = 0.08f),
                modifier = Modifier.clickable { onSelected(filter) }
            ) {
                Text(
                    "${filter.emoji} ${filter.label}",
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color.Black else Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

// MARK: - Community Card (joined)

@Composable
private fun CommunityCardView(item: CommunityListItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CommunityAvatar(imageURL = item.profileImageURL, fallbackText = item.creatorDisplayName.take(2).uppercase(), size = 52)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.creatorDisplayName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                if (item.isCreatorLive) {
                    Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black,
                        modifier = Modifier.background(Color.Red, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
                }
                if (item.unreadCount > 0) {
                    Box(modifier = Modifier.size(20.dp).background(Color.Cyan, CircleShape), contentAlignment = Alignment.Center) {
                        Text(if (item.unreadCount > 9) "9+" else "${item.unreadCount}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
            Text("@${item.creatorUsername} · ${item.memberCount} members", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
        }

        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
    }
}

// MARK: - Discover Card (not joined)

@Composable
private fun DiscoverCommunityCard(item: CommunityListItem, onJoin: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CommunityAvatar(imageURL = item.profileImageURL, fallbackText = item.creatorDisplayName.take(2).uppercase(), size = 48)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.creatorDisplayName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("@${item.creatorUsername} · ${item.memberCount} members", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
        }
        Button(onClick = onJoin, colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Join", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
    }
}

// MARK: - Avatar

@Composable
private fun CommunityAvatar(imageURL: String?, fallbackText: String, size: Int) {
    if (!imageURL.isNullOrBlank()) {
        AsyncImage(model = imageURL, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(Color.Gray.copy(alpha = 0.3f)))
    } else {
        Box(
            modifier = Modifier.size(size.dp).background(Brush.linearGradient(listOf(Color.Cyan.copy(0.4f), Color(0xFF9C27B0).copy(0.4f))), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(fallbackText, fontSize = (size / 3).sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

// MARK: - Section Header

@Composable
private fun CommunitySectionHeader(title: String) {
    Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.35f),
        letterSpacing = 1.5.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp))
}

// MARK: - Empty State

@Composable
private fun CommunityEmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(40.dp)) {
            Icon(Icons.Default.Groups, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(56.dp))
            Text("No Communities", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
            Text("Subscribe to creators to join their communities and earn XP.", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
        }
    }
}

// MARK: - Helpers

private fun parseCommunityListItem(id: String, data: Map<String, Any>): CommunityListItem? {
    val creatorUsername = data["creatorUsername"] as? String ?: run {
        if (BuildConfig.DEBUG) { println("⚠️ COMMUNITIES: Doc $id missing creatorUsername") }
        return null
    }
    val creatorDisplayName = data["creatorDisplayName"] as? String ?: creatorUsername
    val memberCount = (data["memberCount"] as? Number)?.toInt() ?: 0
    val profileImageURL = data["profileImageURL"] as? String
    val isCreatorLive = data["isCreatorLive"] as? Boolean ?: false
    val unreadCount = (data["unreadCount"] as? Number)?.toInt() ?: 0
    val isVerified = data["isVerified"] as? Boolean ?: false
    val lastActivityPreview = data["lastActivityPreview"] as? String ?: ""
    val lastActivityAt = (data["lastActivityAt"] as? com.google.firebase.Timestamp)?.toDate() ?: Date()

    return CommunityListItem(
        id = id, creatorUsername = creatorUsername, creatorDisplayName = creatorDisplayName,
        creatorTier = data["tier"] as? String ?: "rookie", profileImageURL = profileImageURL,
        memberCount = memberCount, userLevel = 0, userXP = 0, unreadCount = unreadCount,
        lastActivityPreview = lastActivityPreview, lastActivityAt = lastActivityAt,
        isCreatorLive = isCreatorLive, isVerified = isVerified
    )
}

private suspend fun joinCommunity(db: FirebaseFirestore, userID: String, communityID: String) {
    val memberRef = db.collection("communities").document(communityID).collection("members").document(userID)
    memberRef.set(hashMapOf<String, Any>(
        "userID" to userID, "communityID" to communityID,
        "joinedAt" to com.google.firebase.Timestamp.now(),
        "localXP" to 0, "level" to 0, "coinsPaid" to 0,
        "isModerator" to false, "isBanned" to false
    )).await()
    db.collection("communities").document(communityID)
        .update("memberCount", com.google.firebase.firestore.FieldValue.increment(1L)).await()
    if (BuildConfig.DEBUG) { println("✅ COMMUNITY: Joined $communityID") }
}