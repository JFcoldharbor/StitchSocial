/*
 * FilterPickerView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Filter Selection Grid
 * Dependencies: VideoEditState, FilterLibrary
 * Features: Grid of filter previews with intensity slider
 *
 * Exact translation from iOS FilterPickerView.swift
 */

package com.stitchsocial.club

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FilterPickerView(
    editState: VideoEditState,
    onEditStateChange: (VideoEditState) -> Unit
) {
    var selectedFilter by remember { mutableStateOf(editState.selectedFilter) }
    var filterIntensity by remember { mutableStateOf(editState.filterIntensity) }

    // Sync with editState
    LaunchedEffect(editState.selectedFilter, editState.filterIntensity) {
        selectedFilter = editState.selectedFilter
        filterIntensity = editState.filterIntensity
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Instructions
        Text(
            text = "Choose a filter to enhance your video",
            color = Color.Gray,
            fontSize = 15.sp,
            modifier = Modifier
                .padding(top = 20.dp, bottom = 16.dp)
                .padding(horizontal = 20.dp)
        )

        // Filter grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(VideoFilter.allCases) { filter ->
                FilterThumbnail(
                    filter = filter,
                    isSelected = selectedFilter == filter,
                    onTap = {
                        if (filter == VideoFilter.NONE) {
                            selectedFilter = null
                            editState.setFilter(null)
                        } else {
                            selectedFilter = filter
                            editState.setFilter(filter, filterIntensity)
                        }
                        onEditStateChange(editState)
                    }
                )
            }
        }

        // Intensity slider (if filter selected)
        AnimatedVisibility(
            visible = selectedFilter != null && selectedFilter != VideoFilter.NONE,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut()
        ) {
            Column {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    color = Color.White.copy(alpha = 0.1f)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(14.dp)
                    )

                    Slider(
                        value = filterIntensity.toFloat(),
                        onValueChange = { value ->
                            filterIntensity = value.toDouble()
                            selectedFilter?.let { filter ->
                                editState.setFilter(filter, filterIntensity)
                                onEditStateChange(editState)
                            }
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.Cyan,
                            activeTrackColor = Color.Cyan
                        )
                    )

                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = null,
                        tint = Color.Cyan,
                        modifier = Modifier.size(14.dp)
                    )

                    Text(
                        text = "${(filterIntensity * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(45.dp)
                    )
                }
            }
        }
    }
}

// MARK: - Filter Thumbnail

@Composable
private fun FilterThumbnail(
    filter: VideoFilter,
    isSelected: Boolean,
    onTap: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .scale(if (isPressed) 0.95f else 1f)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onTap() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Filter preview circle
        Box(
            modifier = Modifier
                .size(70.dp)
                .then(
                    if (isSelected) {
                        Modifier.shadow(
                            elevation = 8.dp,
                            shape = CircleShape,
                            ambientColor = Color.Cyan.copy(alpha = 0.5f)
                        )
                    } else Modifier
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = getFilterGradientColors(filter)
                    )
                )
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) Color.Cyan else Color.White.copy(alpha = 0.2f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = getFilterIcon(filter),
                contentDescription = filter.displayName,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        // Filter name
        Text(
            text = filter.displayName,
            color = if (isSelected) Color.Cyan else Color.White,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

// MARK: - Helper Functions

private fun getFilterGradientColors(filter: VideoFilter): List<Color> {
    return when (filter) {
        VideoFilter.NONE -> listOf(Color(0xFF4D4D4D), Color(0xFF333333))
        VideoFilter.VIVID -> listOf(Color(0xFFFF69B4), Color(0xFFFF8C00), Color(0xFFFFD700))
        VideoFilter.WARM -> listOf(Color(0xFFFF8C00), Color(0xFFFF4500), Color(0xFFFF69B4))
        VideoFilter.COOL -> listOf(Color(0xFF00CED1), Color(0xFF0000FF), Color(0xFF9400D3))
        VideoFilter.DRAMATIC -> listOf(Color.Black, Color.Gray, Color.White)
        VideoFilter.VINTAGE -> listOf(Color(0xFFA0522D), Color(0xFFFF8C00), Color(0xFFFFD700).copy(alpha = 0.5f))
        VideoFilter.MONOCHROME -> listOf(Color.White, Color.Gray, Color.Black)
        VideoFilter.CINEMATIC -> listOf(Color.Black.copy(alpha = 0.8f), Color.Gray.copy(alpha = 0.5f))
        VideoFilter.SUNSET -> listOf(Color(0xFFFF8C00), Color(0xFFFF69B4), Color(0xFF9400D3))
    }
}

private fun getFilterIcon(filter: VideoFilter): androidx.compose.ui.graphics.vector.ImageVector {
    return when (filter) {
        VideoFilter.NONE -> Icons.Filled.Close
        VideoFilter.VIVID -> Icons.Filled.AutoAwesome
        VideoFilter.WARM -> Icons.Filled.WbSunny
        VideoFilter.COOL -> Icons.Filled.AcUnit
        VideoFilter.DRAMATIC -> Icons.Filled.Contrast
        VideoFilter.VINTAGE -> Icons.Filled.Photo
        VideoFilter.MONOCHROME -> Icons.Filled.InvertColors
        VideoFilter.CINEMATIC -> Icons.Filled.Movie
        VideoFilter.SUNSET -> Icons.Filled.WbTwilight
    }
}