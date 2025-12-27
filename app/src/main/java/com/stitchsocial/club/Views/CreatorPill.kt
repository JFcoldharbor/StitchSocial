/*
 * CreatorPill.kt - CREATOR PROFILE PILL COMPONENT
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Extracted Creator Profile Pill Component
 * Dependencies: Compose UI, Coil for image loading
 * Features: Tappable creator profile with context indicators, thread creator differentiation
 *
 * EXACT PORT: CreatorPill.swift
 */

package com.stitchsocial.club.Views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import coil.compose.AsyncImagePainter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon

/**
 * Tappable creator profile pill with gradient border and profile image
 * Used in video overlays to show video creator and thread creator
 *
 * @param creatorId The creator's user ID
 * @param isThread Whether this is a thread creator pill (slightly larger styling)
 * @param colors Gradient colors for the profile image border
 * @param displayName The creator's display name
 * @param profileImageURL Optional URL for the creator's profile image
 * @param onTap Callback when the pill is tapped
 */
@Composable
fun CreatorPill(
    creatorId: String,
    isThread: Boolean,
    colors: List<Color>,
    displayName: String,
    profileImageURL: String?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageSize = if (isThread) 28.dp else 24.dp
    val cornerRadius = if (isThread) 16.dp else 12.dp
    val horizontalPadding = if (isThread) 12.dp else 8.dp
    val verticalPadding = if (isThread) 8.dp else 6.dp
    val nameFontSize = if (isThread) 13.sp else 11.sp

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color.Black.copy(alpha = 0.4f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(cornerRadius)
            )
            .clickable(onClick = onTap)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Profile Image with gradient border
        Box(
            modifier = Modifier.size(imageSize),
            contentAlignment = Alignment.Center
        ) {
            // Gradient border
            Box(
                modifier = Modifier
                    .size(imageSize)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(colors = colors)
                    )
            )

            // Profile image container
            Box(
                modifier = Modifier
                    .size(imageSize - 4.dp)
                    .clip(CircleShape)
                    .background(Color.Gray.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                if (!profileImageURL.isNullOrEmpty()) {
                    var imageState by remember { mutableStateOf<AsyncImagePainter.State?>(null) }
                    
                    AsyncImage(
                        model = profileImageURL,
                        contentDescription = "Profile image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        onState = { state -> imageState = state }
                    )

                    // Show placeholder on error or loading
                    if (imageState is AsyncImagePainter.State.Error || 
                        imageState is AsyncImagePainter.State.Loading) {
                        ProfilePlaceholderIcon(size = imageSize)
                    }
                } else {
                    ProfilePlaceholderIcon(size = imageSize)
                }
            }
        }

        // Creator Name and Context
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    fontSize = nameFontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1
                )

                if (isThread) {
                    Text(
                        text = "thread creator",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/**
 * Placeholder icon for missing profile images
 */
@Composable
private fun ProfilePlaceholderIcon(size: androidx.compose.ui.unit.Dp) {
    val iconSize = size * 0.5f
    Icon(
        imageVector = Icons.Default.Person,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.size(iconSize)
    )
}

/**
 * Temperature-based color provider for creator pills
 */
object CreatorPillColors {
    
    fun forTemperature(temperature: String): List<Color> {
        return when (temperature.lowercase()) {
            "hot", "blazing" -> listOf(Color.Red, Color(0xFFFF8C00))
            "warm" -> listOf(Color(0xFFFF8C00), Color.Yellow)
            "cool" -> listOf(Color.Blue, Color.Cyan)
            "cold", "frozen" -> listOf(Color.Cyan, Color.Blue)
            else -> listOf(Color.Gray, Color.White)
        }
    }

    fun forThreadCreator(): List<Color> {
        return listOf(Color(0xFF9C27B0), Color(0xFFE91E63)) // Purple to Pink
    }

    fun forHotVideo(): List<Color> {
        return listOf(Color.Red, Color(0xFFFF8C00))
    }

    fun forCoolVideo(): List<Color> {
        return listOf(Color.Cyan, Color.Blue)
    }
}