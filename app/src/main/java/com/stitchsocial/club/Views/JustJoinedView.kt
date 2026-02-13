/*
 * JustJoinedView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Full Screen Just Joined Users Browser
 * Port of: JustJoinedView.swift
 * Features: 2-column user grid, follow actions, profile navigation, time ago
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stitchsocial.club.foundation.RecentUser
import com.stitchsocial.club.FollowManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.concurrent.TimeUnit

@Composable
fun JustJoinedView(
    followManager: FollowManager,
    onDismiss: () -> Unit,
    onUserTap: (String) -> Unit
) {
    val scope = rememberCoroutineScope()

    var users by remember { mutableStateOf<List<RecentUser>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val followingStates by followManager.followingStates.collectAsState()
    val loadingStates by followManager.loadingStates.collectAsState()

    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        try {
            val db = FirebaseFirestore.getInstance("stitchfin")
            val cutoff = Timestamp(Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L))
            val snapshot = db.collection("users")
                .whereGreaterThan("createdAt", cutoff)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()

            users = snapshot.documents.mapNotNull { doc ->
                RecentUser.fromFirestore(doc.id, doc.data ?: return@mapNotNull null)
            }

            val userIDs = users.map { it.id }
            if (userIDs.isNotEmpty()) {
                followManager.loadFollowStates(userIDs)
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load users"
        }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        // Top bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                "Just Joined",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
            ) {
                Text("Done", color = Color(0xFF9C27B0), fontWeight = FontWeight.SemiBold)
            }
        }

        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && users.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFF9C27B0))
                        Text("Loading new users...", fontSize = 14.sp, color = Color.Gray)
                    }
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(50.dp))
                        Text(errorMessage!!, fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center)
                        TextButton(onClick = {
                            scope.launch {
                                isLoading = true; errorMessage = null
                                try {
                                    val db = FirebaseFirestore.getInstance("stitchfin")
                                    val cutoff = Timestamp(Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L))
                                    val snapshot = db.collection("users")
                                        .whereGreaterThan("createdAt", cutoff)
                                        .orderBy("createdAt", Query.Direction.DESCENDING)
                                        .limit(50)
                                        .get()
                                        .await()
                                    users = snapshot.documents.mapNotNull { doc ->
                                        RecentUser.fromFirestore(doc.id, doc.data ?: return@mapNotNull null)
                                    }
                                } catch (e: Exception) { errorMessage = e.message }
                                isLoading = false
                            }
                        }) { Text("Retry", color = Color(0xFF9C27B0)) }
                    }
                }
                users.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(Icons.Default.Group, null, tint = Color.Gray, modifier = Modifier.size(50.dp))
                        Text("No new users yet", fontSize = 16.sp, color = Color.Gray)
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(users, key = { it.id }) { user ->
                            JustJoinedUserCard(
                                user = user,
                                isFollowing = followingStates[user.id] ?: false,
                                isFollowLoading = loadingStates.contains(user.id),
                                onTap = { onUserTap(user.id) },
                                onFollow = { followManager.toggleFollow(user.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===== USER CARD =====

@Composable
private fun JustJoinedUserCard(
    user: RecentUser,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    onTap: () -> Unit,
    onFollow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .clickable(onClick = onTap)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(80.dp)
                .border(2.dp, Color(0xFF9C27B0).copy(alpha = 0.3f), CircleShape)
                .padding(2.dp)
        ) {
            val imageUrl = user.profileImageURL
            if (!imageUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color.Gray.copy(alpha = 0.3f))
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color.Gray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                }
            }
        }

        // Username
        Text(
            user.username,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1
        )

        // Joined time
        Text(
            "Joined ${timeAgo(user.joinedAt.time)}",
            fontSize = 11.sp,
            color = Color.Gray
        )

        // Follow button
        Button(
            onClick = onFollow,
            enabled = !isFollowLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isFollowing) Color.Gray.copy(alpha = 0.3f) else Color(0xFF9C27B0)
            ),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.height(32.dp)
        ) {
            if (isFollowLoading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                if (isFollowing) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(10.dp), tint = Color.White)
                } else {
                    Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(10.dp), tint = Color.White)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    if (isFollowing) "Following" else "Follow",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    }
}

// ===== TIME AGO HELPER =====

private fun timeAgo(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs
    val days = TimeUnit.MILLISECONDS.toDays(diff).toInt()

    return when {
        days == 0 -> "today"
        days == 1 -> "yesterday"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}