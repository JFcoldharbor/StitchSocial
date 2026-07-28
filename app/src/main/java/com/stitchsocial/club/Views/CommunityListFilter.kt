/*
 * CommunityListFilter.kt  →  CommunityListView (redesign)
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Community List, rebuilt as a full-screen takeover to match
 * the Events surface. Mirrors iOS CommunityListView.swift (commit 7ad0ade,
 * design_handoff_community/README.md §1): Discover / Mine toggle + featured card
 * carousel + activity-based channel list. Cuts the old 5 emoji filter tabs, the
 * global XP bar, and per-row Lv badges. Activity/live are client-side
 * approximations for v1 (no real posts-today / viewer-count signals yet).
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.VideoManager
import com.stitchsocial.club.community.CommunityListItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import kotlin.math.abs

// MARK: - Handoff color tokens

private object CL {
    val bg = Color(0xFF0A0A0D)
    val pink = Color(0xFFF0245F)
    val cyan = Color(0xFF22D3EE)
    val gold = Color(0xFFFACC15)
    val cardTop = Color(0xFF2A1B3F)
    val cardBot = Color(0xFF12101A)
    val liveTop = Color(0xFF3A1526)
    val ink = Color(0xFF0A0A0D)
}

enum class CommunityListMode { DISCOVER, MINE }

// MARK: - CommunityListView

@Composable
fun CommunityListView(
    userID: String,
    onShowCommunity: (CommunityListItem) -> Unit = {},
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val db = remember { FirebaseFirestore.getInstance("stitchfin") }
    val scope = rememberCoroutineScope()

    var myCommunities by remember { mutableStateOf<List<CommunityListItem>>(emptyList()) }
    var allCommunities by remember { mutableStateOf<List<CommunityListItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var listMode by remember { mutableStateOf(CommunityListMode.MINE) }

    var showingJoinDialog by remember { mutableStateOf(false) }
    var joinCreatorID by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Presented as a full-screen takeover → pause the Discovery deck underneath
    // so its video audio doesn't bleed through (mirrors iOS pauseAllPlayback).
    LaunchedEffect(Unit) { if (onClose != null) VideoManager.pauseAllPlayers() }

    suspend fun load() {
        isLoading = true
        try {
            val commDocs = db.collection("communities").limit(100).get().await()
            val all = commDocs.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                parseCommunityListItem(doc.id, data)
            }
            val joinedIDs = mutableSetOf<String>()
            for (community in all) {
                try {
                    val memberDoc = db.collection("communities").document(community.id)
                        .collection("members").document(userID).get().await()
                    if (memberDoc.exists()) joinedIDs.add(community.id)
                } catch (_: Exception) {}
            }
            myCommunities = all.filter { joinedIDs.contains(it.id) }
            allCommunities = all
        } catch (e: Exception) {
            errorMessage = e.message
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(userID) { load() }

    // Derived
    val mine = remember(myCommunities) {
        myCommunities.sortedWith(
            compareByDescending<CommunityListItem> { it.isCreatorLive }
                .thenByDescending { it.lastActivityAt }
        )
    }
    val suggested = remember(myCommunities, allCommunities) {
        val joined = myCommunities.map { it.id }.toSet()
        allCommunities.filter { !joined.contains(it.id) }
    }
    val featured = remember(mine) { mine.take(8) }

    fun doJoin(item: CommunityListItem) {
        scope.launch {
            try {
                joinCommunity(db, userID, item.id)
                load()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) println("⚠️ COMMUNITY: Join failed — ${e.message}")
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(CL.bg)) {

        CommunityHeader(
            myCount = myCommunities.size,
            listMode = listMode,
            onMode = { listMode = it },
            onClose = onClose,
            onJoin = { showingJoinDialog = true }
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CL.cyan, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (listMode == CommunityListMode.MINE) {
                    if (featured.isNotEmpty()) {
                        item { FeaturedCarousel(featured, onOpen = onShowCommunity) }
                    }
                    if (mine.isNotEmpty()) {
                        item { SectionHeader("YOUR CHANNELS") }
                        items(mine, key = { it.id }) { ChannelRow(it, joinable = false, onOpen = { onShowCommunity(it) }, onJoin = {}) }
                    }
                    if (suggested.isNotEmpty()) {
                        item { SectionHeader("SUGGESTED") }
                        items(suggested.take(6), key = { it.id }) { ChannelRow(it, joinable = true, onOpen = { onShowCommunity(it) }, onJoin = { doJoin(it) }) }
                    }
                    if (mine.isEmpty() && suggested.isEmpty()) item { EmptyState(listMode) }
                } else {
                    if (suggested.isNotEmpty()) {
                        item { SectionHeader("SUGGESTED") }
                        items(suggested, key = { it.id }) { ChannelRow(it, joinable = true, onOpen = { onShowCommunity(it) }, onJoin = { doJoin(it) }) }
                    } else item { EmptyState(listMode) }
                }
                // Bottom breathing room. This used to just clear the app tab bar;
                // Community is full screen now, so the last row instead has to
                // clear the system nav / gesture bar.
                item { Spacer(Modifier.height(20.dp).navigationBarsPadding()) }
            }
        }
    }

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
                            focusedBorderColor = CL.cyan, unfocusedBorderColor = Color.Gray,
                            cursorColor = CL.cyan,
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = joinCreatorID.trim().isNotEmpty() && !isJoining,
                    onClick = {
                        val creatorID = joinCreatorID.trim()
                        if (creatorID.isNotEmpty()) {
                            isJoining = true
                            scope.launch {
                                try {
                                    joinCommunity(db, userID, creatorID)
                                    showingJoinDialog = false; joinCreatorID = ""
                                    load()
                                } catch (e: Exception) {
                                    if (BuildConfig.DEBUG) println("❌ JOIN: ${e.message}")
                                } finally { isJoining = false }
                            }
                        }
                    }
                ) {
                    if (isJoining) CircularProgressIndicator(color = CL.cyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Join", color = CL.cyan)
                }
            },
            dismissButton = { TextButton(onClick = { showingJoinDialog = false; joinCreatorID = "" }) { Text("Cancel", color = Color.Gray) } },
            containerColor = Color(0xFF1C1C1E)
        )
    }

    errorMessage?.let { msg -> LaunchedEffect(msg) { errorMessage = null } }
}

// MARK: - Header (✕ · Discover/Mine·N toggle · +)

@Composable
private fun CommunityHeader(
    myCount: Int,
    listMode: CommunityListMode,
    onMode: (CommunityListMode) -> Unit,
    onClose: (() -> Unit)?,
    onJoin: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleIconButton(Icons.Default.Close, "Close") { onClose?.invoke() }
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.06f))
        ) {
            TogglePill("Discover", listMode == CommunityListMode.DISCOVER, Modifier.weight(1f)) { onMode(CommunityListMode.DISCOVER) }
            TogglePill("Mine · $myCount", listMode == CommunityListMode.MINE, Modifier.weight(1f)) { onMode(CommunityListMode.MINE) }
        }
        CircleIconButton(Icons.Default.Add, "Join", onJoin)
    }
}

@Composable
private fun TogglePill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) CL.ink else Color.White.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun CircleIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.09f)).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = cd, tint = Color.White, modifier = Modifier.size(16.dp)) }
}

// MARK: - Featured carousel + page dots

@Composable
private fun FeaturedCarousel(featured: List<CommunityListItem>, onOpen: (CommunityListItem) -> Unit) {
    val listState = rememberLazyListState()
    Column {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            items(featured, key = { it.id }) { item ->
                FeaturedChannelCard(item, onOpen = { onOpen(item) }, modifier = Modifier.width(256.dp).height(268.dp))
            }
        }
        // Page dots
        val active = listState.firstVisibleItemIndex
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
        ) {
            featured.forEachIndexed { i, _ ->
                val on = i == active
                Box(Modifier.width(if (on) 16.dp else 4.dp).height(4.dp).clip(RoundedCornerShape(50))
                    .background(if (on) Color.White else Color.White.copy(alpha = 0.28f)))
            }
        }
    }
}

@Composable
private fun FeaturedChannelCard(item: CommunityListItem, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(if (item.isCreatorLive) CL.liveTop else CL.cardTop, CL.cardBot)))
            .border(
                width = if (item.isCreatorLive) 2.dp else 1.dp,
                color = if (item.isCreatorLive) CL.pink else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Monogram(item, 46.dp, 19.sp, 14.dp)
            Spacer(Modifier.weight(1f))
            if (item.isCreatorLive) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(CL.pink).padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                    Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            } else if (isPartner(item.creatorTier)) {
                Text(
                    "PARTNER", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CL.gold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(CL.gold.copy(alpha = 0.15f))
                        .border(1.dp, CL.gold.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(item.creatorDisplayName, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        Text(activityLine(item), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.5f))

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) {
                Box(Modifier.weight(1f).height(66.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f)))
            }
        }

        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (item.isCreatorLive) CL.pink else Color.White)
                .clickable { onOpen() }.padding(vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (item.isCreatorLive) "Jump in" else "Open channel",
                fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                color = if (item.isCreatorLive) Color.White else CL.ink
            )
        }
    }
}

// MARK: - Channel row

@Composable
private fun ChannelRow(item: CommunityListItem, joinable: Boolean, onOpen: () -> Unit, onJoin: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { if (joinable) onJoin() else onOpen() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .then(if (joinable) Modifier.alpha(0.85f) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Monogram(item, 44.dp, 15.sp, 13.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.creatorDisplayName, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                if (item.isVerified) Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CL.cyan)
            }
            Text(activityLine(item), fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.45f))
        }
        when {
            joinable -> Text(
                "Join", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.5.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(50))
                    .padding(horizontal = 13.dp, vertical = 6.dp)
            )
            item.unreadCount > 0 -> Box(Modifier.size(8.dp).clip(CircleShape).background(CL.pink))
            else -> Text(ageText(item.lastActivityAt), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.3f))
        }
    }
}

// MARK: - Shared bits

@Composable
private fun Monogram(item: CommunityListItem, size: androidx.compose.ui.unit.Dp, text: androidx.compose.ui.unit.TextUnit, radius: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(radius)).background(monogramGradient(item.id)),
        contentAlignment = Alignment.Center
    ) { Text(monogram(item.creatorDisplayName), fontSize = text, fontWeight = FontWeight.Bold, color = Color.White) }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.38f),
        letterSpacing = 1.6.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 13.dp)
    )
}

@Composable
private fun EmptyState(mode: CommunityListMode) {
    Column(
        Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("💬", fontSize = 40.sp)
        Text(if (mode == CommunityListMode.MINE) "No channels yet" else "Nothing to discover",
            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            if (mode == CommunityListMode.MINE) "Join a creator's community to see it here." else "Check back soon for channels to join.",
            fontSize = 13.sp, color = Color.White.copy(alpha = 0.45f), textAlign = TextAlign.Center
        )
    }
}

// MARK: - Helpers

private fun monogram(name: String): String {
    val parts = name.split(" ").filter { it.isNotBlank() }.take(2).mapNotNull { it.firstOrNull() }
    val s = parts.joinToString("").uppercase()
    return if (s.isEmpty()) "?" else s
}

private fun monogramGradient(seed: String): Brush {
    val hue = (abs(seed.hashCode()) % 360).toFloat()
    return Brush.linearGradient(listOf(
        Color.hsv(hue, 0.55f, 0.65f),
        Color.hsv((hue + 43f) % 360f, 0.6f, 0.5f)
    ))
}

private fun memberCountText(n: Int): String =
    if (n >= 1000) String.format("%.1fk", n / 1000.0) else "$n"

private fun activityLine(item: CommunityListItem): String {
    val members = "${memberCountText(item.memberCount)} members"
    if (item.isCreatorLive) return "$members · live now"
    val hours = (System.currentTimeMillis() - item.lastActivityAt.time) / 3_600_000.0
    val word = if (hours < 2) "very active" else if (hours < 24) "active" else "quiet"
    return "$members · $word"
}

private fun ageText(date: Date): String {
    val s = (System.currentTimeMillis() - date.time) / 1000.0
    return when {
        s < 3600 -> "${maxOf(1, (s / 60).toInt())}m"
        s < 86400 -> "${(s / 3600).toInt()}h"
        else -> "${(s / 86400).toInt()}d"
    }
}

private fun isPartner(tier: String): Boolean =
    tier == "partner" || tier == "topCreator" || tier == "founder"

private fun parseCommunityListItem(id: String, data: Map<String, Any>): CommunityListItem? {
    val creatorUsername = data["creatorUsername"] as? String ?: return null
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
    com.stitchsocial.club.services.AnalyticsService.communityJoined(communityID)
    if (BuildConfig.DEBUG) println("✅ COMMUNITY: Joined $communityID")
}
