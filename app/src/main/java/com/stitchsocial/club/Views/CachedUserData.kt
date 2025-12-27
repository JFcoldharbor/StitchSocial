/*
 * TaggedUsersRow.kt - COMPACT TAGGED USERS DISPLAY COMPONENT
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Compact Tagged Users Display Component
 * Dependencies: Compose UI, Coil for image loading, UserService
 * Features: Stacked avatar circles with expandable sheet
 *
 * EXACT PORT: TaggedUsersRow.swift
 */

package com.stitchsocial.club.Views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.stitchsocial.club.foundation.UserTier

/**
 * Cached user data structure for tagged users
 */
data class CachedUserData(
    val displayName: String,
    val profileImageURL: String?,
    val tier: UserTier?,
    val cachedAt: Long = System.currentTimeMillis()
)

/**
 * Compact tagged users row with stacked avatars
 * Shows first 3 avatars with overlap, plus count badge if more
 *
 * @param taggedUserIDs List of user IDs that are tagged
 * @param getCachedUserData Function to get cached user data by ID
 * @param onUserTap Callback when a user is tapped
 */
@Composable
fun TaggedUsersRow(
    taggedUserIDs: List<String>,
    getCachedUserData: (String) -> CachedUserData?,
    onUserTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // State
    var showingFullList by remember { mutableStateOf(false) }
    val loadedUsers = remember { mutableStateMapOf<String, CachedUserData>() }

    // Constants
    val maxVisibleAvatars = 3
    val avatarSize = 24.dp
    val avatarOverlap = 8.dp

    if (taggedUserIDs.isEmpty()) return

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = Color(0xFF9C27B0).copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { showingFullList = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Purple person icon
        Icon(
            imageVector = Icons.Default.People,
            contentDescription = null,
            tint = Color(0xFF9C27B0),
            modifier = Modifier.size(11.dp)
        )

        // Stacked avatars (first 3)
        Box {
            taggedUserIDs.take(maxVisibleAvatars).forEachIndexed { index, userID ->
                SmartCompactAvatar(
                    userID = userID,
                    size = avatarSize,
                    getCachedUserData = getCachedUserData,
                    loadedUsers = loadedUsers,
                    modifier = Modifier
                        .offset(x = (index * avatarOverlap.value).dp)
                        .zIndex((maxVisibleAvatars - index).toFloat())
                )
            }
        }

        // Count badge (if more than 3)
        if (taggedUserIDs.size > maxVisibleAvatars) {
            Text(
                text = "+${taggedUserIDs.size - maxVisibleAvatars}",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(
                        color = Color(0xFF9C27B0).copy(alpha = 0.9f),
                        shape = RoundedCornerShape(50)
                    )
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }

    // Full list sheet
    if (showingFullList) {
        TaggedUsersSheet(
            taggedUserIDs = taggedUserIDs,
            getCachedUserData = getCachedUserData,
            loadedUsers = loadedUsers,
            onUserTap = { userID ->
                showingFullList = false
                onUserTap(userID)
            },
            onDismiss = { showingFullList = false }
        )
    }
}

/**
 * Smart compact avatar that fetches data if needed
 */
@Composable
fun SmartCompactAvatar(
    userID: String,
    size: androidx.compose.ui.unit.Dp,
    getCachedUserData: (String) -> CachedUserData?,
    loadedUsers: MutableMap<String, CachedUserData>,
    modifier: Modifier = Modifier
) {
    var localUserData by remember { mutableStateOf<CachedUserData?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Get user data from cache or loaded users
    val userData = getCachedUserData(userID) ?: loadedUsers[userID] ?: localUserData

    Box(
        modifier = modifier
            .size(size)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .border(
                width = 2.dp,
                color = Color(0xFF9C27B0).copy(alpha = 0.8f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (userData?.profileImageURL != null) {
            var imageState by remember { mutableStateOf<AsyncImagePainter.State?>(null) }

            AsyncImage(
                model = userData.profileImageURL,
                contentDescription = "User avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                onState = { state -> 
                    imageState = state
                    if (state is AsyncImagePainter.State.Success) {
                        isLoading = false
                    }
                }
            )

            if (imageState is AsyncImagePainter.State.Loading) {
                AvatarPlaceholder(size = size)
            }
        } else if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.5f),
                color = Color(0xFF9C27B0),
                strokeWidth = 1.dp
            )
        } else {
            AvatarPlaceholder(size = size)
        }
    }

    // TODO: Add user loading logic via UserService if needed
    LaunchedEffect(userID) {
        if (userData == null) {
            // Trigger data load - in real implementation, this would call UserService
            isLoading = true
        } else {
            isLoading = false
        }
    }
}

/**
 * Placeholder for avatars without images
 */
@Composable
private fun AvatarPlaceholder(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF9C27B0).copy(alpha = 0.5f),
                        Color(0xFF9C27B0).copy(alpha = 0.3f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(size * 0.4f)
        )
    }
}

/**
 * Full tagged users sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaggedUsersSheet(
    taggedUserIDs: List<String>,
    getCachedUserData: (String) -> CachedUserData?,
    loadedUsers: MutableMap<String, CachedUserData>,
    onUserTap: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Black,
        contentColor = Color.White,
        dragHandle = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(
                            color = Color.Gray.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Tagged People",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color(0xFF111111))
                    )
                )
        ) {
            if (taggedUserIDs.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(
                                color = Color(0xFF9C27B0).copy(alpha = 0.1f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonOff,
                            contentDescription = null,
                            tint = Color(0xFF9C27B0).copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Tagged Users",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(taggedUserIDs) { userID ->
                        TaggedUserRow(
                            userID = userID,
                            getCachedUserData = getCachedUserData,
                            loadedUsers = loadedUsers,
                            onTap = { onUserTap(userID) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual tagged user row in the sheet
 */
@Composable
fun TaggedUserRow(
    userID: String,
    getCachedUserData: (String) -> CachedUserData?,
    loadedUsers: MutableMap<String, CachedUserData>,
    onTap: () -> Unit
) {
    val userData = getCachedUserData(userID) ?: loadedUsers[userID]
    var isPressed by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onTap() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Avatar
        SmartCompactAvatar(
            userID = userID,
            size = 52.dp,
            getCachedUserData = getCachedUserData,
            loadedUsers = loadedUsers
        )

        // User info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (userData != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = userData.displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1
                    )
                }

                userData.tier?.let { tier ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = tierColor(tier),
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = tier.displayName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = tierColor(tier)
                        )
                    }
                }
            } else {
                // Loading state
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(16.dp)
                        .background(
                            color = Color.White.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp)
                        )
                )
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(14.dp)
                        .background(
                            color = Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(4.dp)
                        )
                )
            }
        }

        // View Profile button
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    color = Color(0xFF9C27B0).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(50)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "View",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9C27B0)
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF9C27B0),
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

/**
 * Get color for user tier
 */
private fun tierColor(tier: UserTier): Color {
    return when (tier) {
        UserTier.FOUNDER, UserTier.CO_FOUNDER -> Color.Cyan
        UserTier.TOP_CREATOR -> Color.Yellow
        UserTier.LEGENDARY -> Color.Red
        UserTier.PARTNER -> Color(0xFFE91E63) // Pink
        UserTier.ELITE -> Color(0xFFFF8C00) // Orange
        UserTier.AMBASSADOR -> Color(0xFF3F51B5) // Indigo
        UserTier.INFLUENCER -> Color(0xFF9C27B0) // Purple
        UserTier.VETERAN -> Color.Blue
        UserTier.RISING -> Color.Green
        UserTier.ROOKIE -> Color.Gray
    }
}