/*
 * StitchersFollowingSheet.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Reusable Stitchers/Following Modal
 * Matches ProfileView AlertDialog pattern exactly
 * Features: Two tabs, follow/unfollow buttons, profile navigation
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

// Foundation
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.UserTier

// Services
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.FollowManager

// Components
import com.stitchsocial.club.views.components.FollowButton
import com.stitchsocial.club.views.components.FollowButtonStyle

/**
 * Reusable modal for displaying Stitchers (followers) and Following lists
 * Matches ProfileView's AlertDialog pattern exactly
 */
@Composable
fun StitchersFollowingSheet(
    userID: String,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onUserTap: (String) -> Unit
) {
    if (!isVisible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Services
    val userService = remember { UserService(context) }
    val followManager = remember { FollowManager(context) }

    // State
    var selectedTab by remember { mutableStateOf(0) } // 0 = Stitchers, 1 = Following
    var followersList by remember { mutableStateOf<List<BasicUserInfo>>(emptyList()) }
    var followingList by remember { mutableStateOf<List<BasicUserInfo>>(emptyList()) }
    var isLoadingFollowers by remember { mutableStateOf(false) }
    var isLoadingFollowing by remember { mutableStateOf(false) }

    // Observe follow states from FollowManager
    val followingStates by followManager.followingStates.collectAsState()
    val loadingStates by followManager.loadingStates.collectAsState()

    // Load followers
    suspend fun loadFollowers() {
        isLoadingFollowers = true
        try {
            followersList = userService.getFollowers(userID)
            // Load follow states for all users
            if (followersList.isNotEmpty()) {
                followManager.loadFollowStatesForUsers(followersList)
            }
            println("✅ STITCHERS SHEET: Loaded ${followersList.size} followers")
        } catch (e: Exception) {
            println("🚨 STITCHERS SHEET: Failed to load followers - ${e.message}")
        } finally {
            isLoadingFollowers = false
        }
    }

    // Load following
    suspend fun loadFollowing() {
        isLoadingFollowing = true
        try {
            followingList = userService.getFollowing(userID)
            // Load follow states for all users
            if (followingList.isNotEmpty()) {
                followManager.loadFollowStatesForUsers(followingList)
            }
            println("✅ STITCHERS SHEET: Loaded ${followingList.size} following")
        } catch (e: Exception) {
            println("🚨 STITCHERS SHEET: Failed to load following - ${e.message}")
        } finally {
            isLoadingFollowing = false
        }
    }

    // Load data when sheet opens
    LaunchedEffect(isVisible) {
        if (isVisible) {
            loadFollowers()
            loadFollowing()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (selectedTab == 0) "Stitchers (${followersList.size})" else "Following (${followingList.size})",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Tab selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = { selectedTab = 0 }) {
                        Text(
                            "Stitchers",
                            color = if (selectedTab == 0) Color.Cyan else Color.Gray,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    TextButton(onClick = { selectedTab = 1 }) {
                        Text(
                            "Following",
                            color = if (selectedTab == 1) Color.Cyan else Color.Gray,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(8.dp))

                // List content
                val listToShow = if (selectedTab == 0) followersList else followingList
                val isLoadingList = if (selectedTab == 0) isLoadingFollowers else isLoadingFollowing

                if (isLoadingList) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.Cyan)
                    }
                } else if (listToShow.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selectedTab == 0) "No stitchers yet" else "Not following anyone",
                            color = Color.Gray
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(listToShow, key = { it.id }) { user: BasicUserInfo ->
                            SheetUserRow(
                                user = user,
                                isFollowing = followingStates[user.id] ?: false,
                                isLoading = loadingStates.contains(user.id),
                                onFollowToggle = { followManager.toggleFollow(user.id) },
                                onProfileClick = {
                                    onDismiss()
                                    onUserTap(user.id)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.Cyan)
            }
        },
        containerColor = Color.Black,
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

@Composable
private fun SheetUserRow(
    user: BasicUserInfo,
    isFollowing: Boolean,
    isLoading: Boolean,
    onFollowToggle: () -> Unit,
    onProfileClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onProfileClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Profile image
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(user.profileImageURL ?: "")
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.3f)),
            contentScale = ContentScale.Crop
        )

        // User info
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = user.displayName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (user.isVerified) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = "Verified",
                        tint = Color.Red,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(
                text = "@${user.username}",
                color = Color.Gray,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Tier badge
        SheetTierBadge(user.tier)

        // Follow button - using FollowButton component
        FollowButton(
            userID = user.id,
            isFollowing = isFollowing,
            onToggle = onFollowToggle,
            isLoading = isLoading,
            style = FollowButtonStyle.COMPACT
        )
    }
}

@Composable
private fun SheetTierBadge(tier: UserTier) {
    val tierColors = getTierColors(tier)
    val tierIcon = getTierIcon(tier)

    Row(
        modifier = Modifier
            .background(
                brush = if (tierColors.size > 1) {
                    Brush.horizontalGradient(tierColors)
                } else {
                    Brush.horizontalGradient(listOf(tierColors[0], tierColors[0]))
                },
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = tierIcon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(10.dp)
        )
        Text(
            text = tier.displayName,
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// Helper functions
private fun getTierColors(tier: UserTier): List<Color> {
    return when (tier) {
        UserTier.ROOKIE -> listOf(Color(0xFF808080))
        UserTier.RISING -> listOf(Color(0xFF4A90E2), Color(0xFF64B5F6))
        UserTier.VETERAN -> listOf(Color(0xFF50C878), Color(0xFF81C784))
        UserTier.INFLUENCER -> listOf(Color(0xFFFFD700), Color(0xFFFDD835))
        UserTier.AMBASSADOR -> listOf(Color(0xFF9B59B6), Color(0xFFAB47BC))
        UserTier.ELITE -> listOf(Color(0xFF9B59B6), Color(0xFFBA68C8))
        UserTier.PARTNER -> listOf(Color(0xFFE74C3C), Color(0xFFEF5350))
        UserTier.LEGENDARY -> listOf(Color(0xFFFF6B35), Color(0xFFFF8A65))
        UserTier.TOP_CREATOR -> listOf(Color(0xFFFFD700), Color(0xFFFFA726))
        UserTier.FOUNDER -> listOf(Color(0xFFFFD700), Color(0xFFFF6B35), Color(0xFFE91E63))
        UserTier.CO_FOUNDER -> listOf(Color(0xFFFFD700), Color(0xFFFF6B35))
    }
}

private fun getTierIcon(tier: UserTier): ImageVector {
    return when (tier) {
        UserTier.ROOKIE -> Icons.Default.Star
        UserTier.RISING -> Icons.Default.TrendingUp
        UserTier.VETERAN -> Icons.Default.Shield
        UserTier.INFLUENCER -> Icons.Default.Star
        UserTier.AMBASSADOR -> Icons.Default.Public
        UserTier.ELITE -> Icons.Default.Diamond
        UserTier.PARTNER -> Icons.Default.Handshake
        UserTier.LEGENDARY -> Icons.Default.EmojiEvents
        UserTier.TOP_CREATOR -> Icons.Default.WorkspacePremium
        UserTier.FOUNDER -> Icons.Default.Verified
        UserTier.CO_FOUNDER -> Icons.Default.Verified
    }
}