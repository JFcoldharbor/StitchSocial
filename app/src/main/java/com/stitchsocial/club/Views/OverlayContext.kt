/*
 * ContextualVideoOverlay.kt - ENHANCED METADATA & CENTERED BUTTONS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Universal Contextual Video Overlay with enhanced metadata display
 * Dependencies: Foundation types, EngagementCoordinator, UserService, FollowManager
 * Features: Raised metadata section, description display, centered action buttons
 *
 * âœ… UPDATED: Raised bottom section by 40dp for better accessibility
 * âœ… ADDED: Video description display with proper styling
 * âœ… CENTERED: Action buttons for better UX
 * âœ… ENHANCED: Button sizes and metadata readability
 */

package com.stitchsocial.club.views

import com.stitchsocial.club.services.UserService
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.delay

// Foundation imports
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.Temperature
import com.stitchsocial.club.foundation.InteractionType
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.engagement.EngagementCalculator
import com.stitchsocial.club.FollowManager

// MARK: - Overlay Context and Actions (MATCHING SWIFT EXACTLY)

enum class OverlayContext {
    HOME_FEED,
    DISCOVERY,
    PROFILE_OWN,
    PROFILE_OTHER,
    THREAD_VIEW
}

// MARK: - Helper Functions

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> "${count / 1_000_000}M"
        count >= 1_000 -> "${count / 1_000}K"
        else -> count.toString()
    }
}

enum class EngagementType {
    HYPE,
    COOL,
    REPLY,
    SHARE,
    STITCH,
    THREAD
}

sealed class OverlayAction {
    object NavigateToProfile : OverlayAction()
    object NavigateToThread : OverlayAction()
    object Follow : OverlayAction()
    object Unfollow : OverlayAction()
    data class Engagement(val type: EngagementType) : OverlayAction()
    object Share : OverlayAction()
    object StitchRecording : OverlayAction()
}

// MARK: - Single ContextualVideoOverlay Function (UPDATED LAYOUT)

@Composable
fun ContextualVideoOverlay(
    video: CoreVideoMetadata,
    overlayContext: OverlayContext = OverlayContext.HOME_FEED,
    currentUserID: String? = null,
    threadVideo: CoreVideoMetadata? = null,
    isVisible: Boolean = true,
    currentUserTier: UserTier = UserTier.ROOKIE,
    // UNIFIED: Support both legacy isFollowing and new followManager approaches
    isFollowing: Boolean = false,  // Legacy parameter for backward compatibility
    followManager: FollowManager? = null, // New parameter for centralized follow management
    onAction: ((OverlayAction) -> Unit)? = null
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current

    // State management
    var realCreatorName by remember { mutableStateOf<String?>(null) }
    var realThreadCreatorName by remember { mutableStateOf<String?>(null) }
    var isLoadingUserData by remember { mutableStateOf(false) }

    // UNIFIED: Follow state logic - use FollowManager if provided, otherwise fall back to isFollowing parameter
    val followingStates by (followManager?.followingStates?.collectAsState() ?: remember { mutableStateOf(emptyMap()) })
    val loadingStates by (followManager?.loadingStates?.collectAsState() ?: remember { mutableStateOf(emptySet()) })
    val actualIsFollowing = followManager?.let { followingStates[video.creatorID] ?: false } ?: isFollowing
    val isFollowLoading = followManager?.let { loadingStates.contains(video.creatorID) } ?: false

    // Temperature calculation for visual effects
    val temperatureString = EngagementCalculator.calculateTemperature(
        hypeCount = video.hypeCount,
        coolCount = video.coolCount,
        viewCount = video.viewCount,
        ageInHours = 24.0
    )

    // Convert string to Temperature enum
    val temperature = when (temperatureString.lowercase()) {
        "blazing" -> Temperature.BLAZING
        "hot" -> Temperature.HOT
        "warm" -> Temperature.WARM
        "cool" -> Temperature.COOL
        "cold" -> Temperature.COLD
        "frozen" -> Temperature.FROZEN
        else -> Temperature.WARM
    }

    // Load data on video change
    LaunchedEffect(video.id) {
        loadUserData(video, threadVideo, context) { creatorName, threadCreatorName ->
            realCreatorName = creatorName
            realThreadCreatorName = threadCreatorName
            isLoadingUserData = false
        }

        // Load follow state for video creator (if followManager provided)
        followManager?.loadFollowState(video.creatorID)
    }

    // Determine if should show follow button
    val shouldShowFollow = currentUserID != null &&
            currentUserID != video.creatorID &&
            overlayContext != OverlayContext.PROFILE_OWN

    if (!isVisible) return

    Box(modifier = Modifier.fillMaxSize()) {
        // Top Section
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 50.dp, start = 16.dp, end = 16.dp)
        ) {
            // Enhanced Creator Pills
            EnhancedCreatorPill(
                video = video,
                realCreatorName = realCreatorName,
                temperature = temperature,
                isThread = false,
                onProfileTap = {
                    onAction?.invoke(OverlayAction.NavigateToProfile)
                }
            )

            // Thread creator pill if different
            if (threadVideo != null && threadVideo.creatorID != video.creatorID) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Thread indicator",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(8.dp)
                    )

                    EnhancedCreatorPill(
                        video = threadVideo,
                        realCreatorName = realThreadCreatorName,
                        temperature = Temperature.WARM,
                        isThread = true,
                        onProfileTap = {
                            onAction?.invoke(OverlayAction.NavigateToProfile)
                        }
                    )
                }
            }
        }

        // Bottom Section - RAISED BY 40DP
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 80.dp, start = 16.dp, end = 16.dp) // CHANGED: Raised from 40dp to 80dp
        ) {
            // Thread indicator (if part of thread)
            if (threadVideo != null && threadVideo.id != video.id) {
                ThreadIndicator()
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Video Title
            if (video.title.isNotEmpty()) {
                Text(
                    text = video.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // ADDED: Video Description Display
            if (video.description.isNotEmpty()) {
                Text(
                    text = video.description,
                    color = Color.White.copy(alpha = 0.8f), // Slightly dimmed
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Enhanced Metadata Row with Follow Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Views - ENHANCED
                MetadataItem(
                    icon = Icons.Default.Visibility,
                    count = video.viewCount,
                    color = Color.White
                )

                // Replies - ENHANCED
                MetadataItem(
                    icon = Icons.Default.Reply,
                    count = video.replyCount,
                    color = Color(0xFFFF8C00) // Orange color
                )

                // Follow button
                if (shouldShowFollow) {
                    FollowButton(
                        isFollowing = actualIsFollowing,
                        isLoading = isFollowLoading,
                        onClick = {
                            // Use FollowManager if available, otherwise just notify action
                            followManager?.let { manager ->
                                manager.toggleFollow(video.creatorID)
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            }

                            // Always notify parent component
                            if (actualIsFollowing) {
                                onAction?.invoke(OverlayAction.Unfollow)
                            } else {
                                onAction?.invoke(OverlayAction.Follow)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(20.dp)) // INCREASED: More space before buttons

            // CENTERED: Enhanced Engagement Buttons
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                EngagementButtonRow(
                    video = video,
                    onEngagement = { type ->
                        onAction?.invoke(OverlayAction.Engagement(type))
                    }
                )
            }
        }
    }
}

// MARK: - Enhanced Creator Pill Component (UNCHANGED)

@Composable
private fun EnhancedCreatorPill(
    video: CoreVideoMetadata,
    realCreatorName: String?,
    temperature: Temperature,
    isThread: Boolean = false,
    onProfileTap: () -> Unit
) {
    val displayName = realCreatorName ?: video.creatorName
    val colors = when (temperature) {
        Temperature.HOT, Temperature.BLAZING -> listOf(Color.Red, Color(0xFFFF8C00))
        Temperature.WARM -> listOf(Color(0xFFFF8C00), Color.Yellow)
        Temperature.COOL -> listOf(Color.Blue, Color.Cyan)
        Temperature.COLD, Temperature.FROZEN -> listOf(Color.Cyan, Color.Blue)
        else -> if (isThread) listOf(Color(0xFF9C27B0), Color(0xFFE91E63)) else listOf(Color.Gray, Color.LightGray)
    }

    Button(
        onClick = onProfileTap,
        modifier = Modifier
            .wrapContentSize()
            .shadow(
                elevation = 6.dp,
                spotColor = colors.first().copy(alpha = 0.5f),
                shape = RoundedCornerShape(if (isThread) 16.dp else 12.dp)
            ),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isThread) 8.dp else 6.dp),
            modifier = Modifier
                .background(
                    Color.Black.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(if (isThread) 16.dp else 12.dp)
                )
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.1f),
                            Color.Transparent
                        ),
                        start = Offset.Zero,
                        end = Offset.Infinite
                    ),
                    shape = RoundedCornerShape(if (isThread) 16.dp else 12.dp)
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = colors.map { it.copy(alpha = 0.6f) },
                        start = Offset.Zero,
                        end = Offset.Infinite
                    ),
                    shape = RoundedCornerShape(if (isThread) 16.dp else 12.dp)
                )
                .padding(
                    horizontal = if (isThread) 12.dp else 8.dp,
                    vertical = if (isThread) 8.dp else 6.dp
                )
        ) {
            // Enhanced Profile Avatar with Shadow
            Box(
                modifier = Modifier
                    .size(if (isThread) 28.dp else 22.dp)
                    .shadow(
                        elevation = 6.dp,
                        spotColor = colors.first().copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            ) {
                // Profile picture background with gradient
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = colors,
                                start = Offset.Zero,
                                end = Offset.Infinite
                            )
                        )
                )

                // Profile picture placeholder (would be AsyncImage in real implementation)
                Box(
                    modifier = Modifier
                        .size(if (isThread) 24.dp else 18.dp)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                )
            }

            // Name and context
            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = displayName,
                    color = Color.White,
                    fontSize = if (isThread) 13.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (isThread) {
                    Text(
                        text = "thread creator",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// MARK: - Thread Indicator Component (UNCHANGED)

@Composable
private fun ThreadIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(
                Color.Black.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            )
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Cyan.copy(alpha = 0.3f),
                        Color.Transparent
                    )
                ),
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = Color.Cyan.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Link,
            contentDescription = "Thread indicator",
            tint = Color.Cyan,
            modifier = Modifier.size(10.dp)
        )

        Text(
            text = "Part of thread",
            color = Color.Cyan.copy(alpha = 0.8f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// MARK: - Follow Button Component (UNCHANGED)

@Composable
private fun FollowButton(
    isFollowing: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = !isLoading,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Black.copy(alpha = 0.2f)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isFollowing) Color.Green.copy(alpha = 0.3f) else Color.Cyan.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier
            .alpha(if (isLoading) 0.6f else 1.0f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.dp,
                    color = Color.White
                )
            } else {
                Icon(
                    imageVector = if (isFollowing) Icons.Default.CheckCircle else Icons.Default.Add,
                    contentDescription = null,
                    tint = if (isFollowing) Color.Green else Color.Cyan.copy(alpha = 0.7f),
                    modifier = Modifier.size(10.dp)
                )
            }

            Text(
                text = when {
                    isLoading -> "Loading..."
                    isFollowing -> "Following"
                    else -> "Follow"
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = if (isFollowing) Color.Green else Color.Cyan.copy(alpha = 0.7f)
            )
        }
    }
}

// MARK: - Enhanced Metadata Item Component

@Composable
private fun MetadataItem(
    icon: ImageVector,
    count: Int,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp) // INCREASED: From 14.dp to 16.dp
        )
        Text(
            text = formatCount(count),
            color = color,
            fontSize = 13.sp, // INCREASED: From 12.sp to 13.sp
            fontWeight = FontWeight.Medium
        )
    }
}

// MARK: - Enhanced Engagement Button Row (CENTERED & LARGER)

@Composable
private fun EngagementButtonRow(
    video: CoreVideoMetadata,
    onEngagement: (EngagementType) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp), // INCREASED: From 20.dp to 24.dp
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thread View Button
        ContextualEngagementButton(
            icon = Icons.Default.Forum,
            count = video.replyCount,
            color = Color(0xFFFF8C00), // Orange color
            onClick = { onEngagement(EngagementType.THREAD) }
        )

        // Cool Button
        ContextualEngagementButton(
            icon = Icons.Default.AcUnit,
            count = video.coolCount,
            color = Color.Cyan,
            onClick = { onEngagement(EngagementType.COOL) }
        )

        // Hype Button
        ContextualEngagementButton(
            icon = Icons.Default.Whatshot,
            count = video.hypeCount,
            color = Color.Red,
            onClick = { onEngagement(EngagementType.HYPE) }
        )

        // Stitch Button
        ContextualEngagementButton(
            icon = Icons.Default.Add,
            count = 0,
            color = Color(0xFFFF8C00), // Orange color
            onClick = { onEngagement(EngagementType.STITCH) }
        )
    }
}

// MARK: - Enhanced Engagement Button Component

@Composable
private fun ContextualEngagementButton(
    icon: ImageVector,
    count: Int,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Black.copy(alpha = 0.3f)
            ),
            border = BorderStroke(
                width = 1.2.dp,
                color = color.copy(alpha = 0.4f)
            ),
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(54.dp) // INCREASED: From 50.dp to 54.dp
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        if (count > 0) {
            Text(
                text = formatCount(count),
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// MARK: - User Data Loading Helper (UNCHANGED)

private suspend fun loadUserData(
    video: CoreVideoMetadata,
    threadVideo: CoreVideoMetadata?,
    context: Context,
    onDataLoaded: (String?, String?) -> Unit
) {
    try {
        val userService = UserService(context)

        // Load video creator
        val creator = userService.getUserProfile(video.creatorID)
        val creatorName = creator?.displayName

        // Load thread creator if different
        var threadCreatorName: String? = null
        if (threadVideo != null && threadVideo.creatorID != video.creatorID) {
            val threadCreator = userService.getUserProfile(threadVideo.creatorID)
            threadCreatorName = threadCreator?.displayName
        }

        onDataLoaded(creatorName, threadCreatorName)
    } catch (e: Exception) {
        println("OVERLAY: Failed to load user data - ${e.message}")
        onDataLoaded(null, null)
    }
}