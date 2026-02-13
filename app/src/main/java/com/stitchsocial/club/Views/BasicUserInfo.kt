/*
 * SuggestedUsersCard.kt - Fullscreen Suggested Users Card
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Port of iOS SuggestedUsersFullscreenCard.swift
 * Integrates into HomeFeed as a fullscreen card — swipe up/down to dismiss
 */

package com.stitchsocial.club.Views

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Basic user info for suggestion cards (matches iOS BasicUserInfo)
 */
data class BasicUserInfo(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String = "",
    val tier: String = "explorer",
    val clout: Int = 0,
    val isVerified: Boolean = false,
    val isPrivate: Boolean = false,
    val profileImageURL: String? = null
)

/**
 * Fullscreen suggested users card that integrates into HomeFeed's VerticalPager.
 * Swipe vertically within the card to cycle through suggestions.
 */
@Composable
fun SuggestedUsersFullscreenCard(
    suggestions: List<BasicUserInfo>,
    onDismiss: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    onFollowUser: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var currentIndex by remember { mutableStateOf(0) }
    var dragOffset by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "drag"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xCC9B59B6),  // purple 0.8
                        Color(0xCC3498DB)   // blue 0.8
                    )
                )
            )
            .pointerInput(currentIndex) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        val threshold = 100f
                        if (dragOffset < -threshold) {
                            // Swiped up — next user
                            if (currentIndex < suggestions.size - 1) {
                                currentIndex++
                            } else {
                                onDismiss()
                            }
                        } else if (dragOffset > threshold) {
                            // Swiped down — previous or dismiss
                            if (currentIndex > 0) {
                                currentIndex--
                            } else {
                                onDismiss()
                            }
                        }
                        dragOffset = 0f
                    },
                    onVerticalDrag = { _, amount ->
                        dragOffset += amount
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(60.dp))

            // Header
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "People You Might Like",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Swipe up or down to skip • Tap to view profile",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Current user card
            if (currentIndex < suggestions.size) {
                UserCard(
                    user = suggestions[currentIndex],
                    onNavigateToProfile = onNavigateToProfile,
                    onFollow = onFollowUser,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationY = animatedOffset }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Progress indicators (capsules)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 40.dp)
            ) {
                suggestions.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .width(if (index == currentIndex) 30.dp else 8.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (index == currentIndex) Color.White
                                else Color.White.copy(alpha = 0.3f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun UserCard(
    user: BasicUserInfo,
    onNavigateToProfile: (String) -> Unit,
    onFollow: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(30.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Profile image
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            if (user.profileImageURL != null) {
                AsyncImage(
                    model = user.profileImageURL,
                    contentDescription = user.username,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = Color.White
                )
            }
        }

        // Username + display name
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = user.username,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (user.displayName.isNotEmpty()) {
                Text(
                    text = user.displayName,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        // Tier badge
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(15.dp))
                .background(Color.Yellow.copy(alpha = 0.2f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Color.Yellow
            )
            Text(
                text = user.tier.replaceFirstChar { it.uppercase() },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Yellow
            )
        }

        // Clout stat
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${user.clout}",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Clout",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 10.dp)
        ) {
            // View Profile
            Button(
                onClick = { onNavigateToProfile(user.id) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(25.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("View Profile", fontWeight = FontWeight.SemiBold)
            }

            // Follow
            Button(
                onClick = { onFollow(user.id) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3498DB)
                ),
                shape = RoundedCornerShape(25.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Follow", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}