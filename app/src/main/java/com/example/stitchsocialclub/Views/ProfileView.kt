/*
 * ProfileView.kt - FIXED VERSION
 * StitchSocial Android
 *
 * Layer 8: Views - User Profile Display with FIXED data structures
 * Dependencies: Foundation Layer types only
 * Features: Simplified profile view without dependency issues
 */

package com.example.stitchsocialclub.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.stitchsocialclub.foundation.*

/**
 * Simplified Profile View that works with existing data structures
 * FIXED: Uses only Foundation layer types to avoid crashes
 */
@Composable
fun ProfileView(
    userID: String,
    modifier: Modifier = Modifier
) {
    // Simple state management without complex ViewModels for now
    var isLoading by remember { mutableStateOf(true) }
    var userData by remember { mutableStateOf<BasicUserInfo?>(null) }

    // Simulate loading state
    LaunchedEffect(userID) {
        println("PROFILE VIEW: Loading profile for user $userID")
        kotlinx.coroutines.delay(1000) // Simulate loading

        // Create mock user data using Foundation types
        userData = BasicUserInfo(
            id = userID,
            username = "outtacontext",
            displayName = "OuttaContext",
            tier = UserTier.VETERAN,
            clout = 5000,
            isVerified = true,
            profileImageURL = null,
            createdAt = java.util.Date()
        )
        isLoading = false
        println("PROFILE VIEW: Profile loaded successfully")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            isLoading -> {
                ProfileLoadingView()
            }
            userData != null -> {
                ProfileContentView(user = userData!!)
            }
            else -> {
                ProfileErrorView(
                    onRetry = {
                        isLoading = true
                        // Retry logic would go here
                    }
                )
            }
        }
    }
}

/**
 * Main profile content display
 */
@Composable
private fun ProfileContentView(user: BasicUserInfo) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp) // Space for tab bar
    ) {
        item {
            ProfileHeader(user = user)
        }

        item {
            ProfileStats(user = user)
        }

        item {
            ProfileActions(user = user)
        }

        item {
            ProfileVideoSection()
        }
    }
}

/**
 * Profile header with user info
 */
@Composable
private fun ProfileHeader(user: BasicUserInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Profile image
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            if (user.profileImageURL != null) {
                AsyncImage(
                    model = user.profileImageURL,
                    contentDescription = "Profile Image",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Fallback placeholder
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    getTierColor(user.tier),
                                    getTierColor(user.tier).copy(alpha = 0.7f)
                                )
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = getUserInitials(user),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // User info
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Display name + verification
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = user.displayName,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (user.isVerified) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = "Verified",
                        tint = Color.Cyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Username
            Text(
                text = "@${user.username}",
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.7f)
            )

            // Tier badge
            TierBadge(tier = user.tier)
        }
    }
}

/**
 * Profile statistics
 */
@Composable
private fun ProfileStats(user: BasicUserInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatColumn(
            count = formatClout(user.clout),
            label = "Clout",
            color = getTierColor(user.tier)
        )

        StatColumn(
            count = "0", // Placeholder
            label = "Followers"
        )

        StatColumn(
            count = "0", // Placeholder
            label = "Following"
        )

        StatColumn(
            count = "0", // Placeholder
            label = "Videos"
        )
    }
}

/**
 * Profile action buttons
 */
@Composable
private fun ProfileActions(user: BasicUserInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { println("Edit Profile clicked") },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00BFFF)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Edit Profile",
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }

        Button(
            onClick = { println("Share Profile clicked") },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.1f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Share",
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Video section placeholder
 */
@Composable
private fun ProfileVideoSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = "Videos",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Video grid placeholder
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.height(200.dp)
        ) {
            items(6) { index ->
                VideoThumbnailPlaceholder(index = index)
            }
        }
    }
}

// MARK: - Supporting Components

/**
 * Tier badge
 */
@Composable
private fun TierBadge(tier: UserTier) {
    val tierColor = getTierColor(tier)

    Row(
        modifier = Modifier
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(tierColor, Color(0xFFFFA500))
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = "Tier",
            tint = Color.Black,
            modifier = Modifier.size(14.dp)
        )

        Text(
            text = tier.displayName,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

/**
 * Stat column component
 */
@Composable
private fun StatColumn(
    count: String,
    label: String,
    color: Color = Color.White
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = count,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )

        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

/**
 * Video thumbnail placeholder
 */
@Composable
private fun VideoThumbnailPlaceholder(index: Int) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(
                Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(8.dp)
            )
            .clickable { println("Video $index clicked") },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Video $index",
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
    }
}

// MARK: - Loading and Error States

/**
 * Profile loading view
 */
@Composable
private fun ProfileLoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                color = Color(0xFF00BFFF),
                modifier = Modifier.size(48.dp)
            )

            Text(
                text = "Loading profile...",
                color = Color.White,
                fontSize = 16.sp
            )
        }
    }
}

/**
 * Profile error view
 */
@Composable
private fun ProfileErrorView(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "😞",
                fontSize = 64.sp
            )

            Text(
                text = "Something went wrong",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00BFFF)
                )
            ) {
                Text(
                    text = "Try Again",
                    color = Color.White
                )
            }
        }
    }
}

// MARK: - Helper Functions

/**
 * Get user initials for profile image placeholder
 */
private fun getUserInitials(user: BasicUserInfo): String {
    return user.displayName.take(2).uppercase()
}

/**
 * Get tier color
 */
private fun getTierColor(tier: UserTier): Color {
    return when (tier) {
        UserTier.ROOKIE -> Color.Gray
        UserTier.RISING -> Color.Green
        UserTier.VETERAN -> Color.Blue
        UserTier.INFLUENCER -> Color.Magenta
        UserTier.ELITE -> Color(0xFFFFA500) // Orange
        UserTier.PARTNER -> Color(0xFFFFC0CB) // Pink
        UserTier.LEGENDARY -> Color.Red
        UserTier.TOP_CREATOR -> Color.Yellow
        UserTier.FOUNDER -> Color(0xFFFFD700) // Gold
        UserTier.CO_FOUNDER -> Color(0xFFFFD700) // Gold
    }
}

/**
 * Format clout number for display
 */
private fun formatClout(clout: Int): String {
    return when {
        clout >= 1_000_000 -> String.format("%.1fM", clout.toDouble() / 1_000_000.0)
        clout >= 1_000 -> String.format("%.1fK", clout.toDouble() / 1_000.0)
        else -> clout.toString()
    }
}