/*
 * TaggedUserChip.kt - TAGGED USER CHIP COMPONENTS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Standalone composables for displaying tagged users as chips.
 * Loads user data from Firebase by ID, shows loading/error states.
 * Used in ThreadComposer's UserTagSection.
 *
 * CACHING: BasicUserInfo is loaded once per userID via LaunchedEffect keyed
 * on userID — won't re-fetch unless the ID changes. If you tag the same user
 * across multiple threads in one session, consider adding an in-memory
 * Map<String, BasicUserInfo> cache at the ViewModel/coordinator level to
 * avoid redundant Firestore reads. Add to CachingOptimization file.
 */

package com.stitchsocial.club

import com.stitchsocial.club.foundation.BasicUserInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Loads a user by ID from Firebase and renders the appropriate chip state.
 * CACHING: LaunchedEffect keyed on userID — single fetch per mount.
 */
@Composable
fun TaggedUserChipById(
    userID: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var user by remember { mutableStateOf<BasicUserInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

    LaunchedEffect(userID) {
        isLoading = true
        hasError = false

        try {
            val db = FirebaseFirestore.getInstance("stitchfin")
            val doc = db.collection("users").document(userID).get().await()

            if (doc.exists()) {
                user = BasicUserInfo.fromFirebaseDocument(doc)
                if (user == null) hasError = true
            } else {
                hasError = true
            }
        } catch (e: Exception) {
            println("❌ TAGGED CHIP: Failed to load user $userID: ${e.message}")
            hasError = true
        } finally {
            isLoading = false
        }
    }

    when {
        isLoading -> LoadingChip(modifier)
        hasError -> ErrorChip(onRemove = onRemove, modifier = modifier)
        user != null -> TaggedUserChipContent(
            user = user!!,
            onRemove = onRemove,
            modifier = modifier
        )
    }
}

@Composable
fun TaggedUserChipContent(
    user: BasicUserInfo,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0xFF2196F3), Color(0xFF2196F3).copy(alpha = 0.8f))
                ),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Profile image
        AsyncImage(
            model = user.profileImageURL,
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0xFF4D4D4D)),
            contentScale = ContentScale.Crop
        )

        // Username
        Text(
            "@${user.username}",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        // Remove button
        Icon(
            Icons.Default.Cancel,
            contentDescription = "Remove",
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .size(16.dp)
                .clickable { onRemove() }
        )
    }
}

@Composable
fun LoadingChip(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0xFF333333), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            color = Color.Cyan,
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Text("Loading...", fontSize = 13.sp, color = Color.Gray)
    }
}

@Composable
fun ErrorChip(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0xFF5C2828), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = Color(0xFFFF6B6B),
            modifier = Modifier.size(14.dp)
        )
        Text("User not found", fontSize = 13.sp, color = Color(0xFFFF6B6B))
        Icon(
            Icons.Default.Cancel,
            contentDescription = "Remove",
            tint = Color(0xFFFF6B6B),
            modifier = Modifier
                .size(16.dp)
                .clickable { onRemove() }
        )
    }
}