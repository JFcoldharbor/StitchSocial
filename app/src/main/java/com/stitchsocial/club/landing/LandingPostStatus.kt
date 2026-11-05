package com.stitchsocial.club.landing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.services.BackgroundPostManager
import com.stitchsocial.club.services.BackgroundPostManager.PostStatus

/**
 * The upload ring and "Posted!" for a post made from a Landing, which covers the
 * tab bar where they normally show. A failure stays until tapped away.
 */
@Composable
fun LandingPostStatus(modifier: Modifier = Modifier) {
    val isPosting by BackgroundPostManager.isPosting.collectAsState()
    val status by BackgroundPostManager.status.collectAsState()
    val progress by BackgroundPostManager.progress.collectAsState()
    var failureSeen by remember { mutableStateOf(false) }
    LaunchedEffect(isPosting) { if (isPosting) failureSeen = false }

    val failed = status == PostStatus.FAILED && !failureSeen
    val done = status == PostStatus.COMPLETE
    val text = when {
        failed -> "Didn't post. Try again from the recorder."
        done -> "Posted!"
        else -> (progress * 100).toInt().let { if (it > 0) "Posting… $it%" else "Posting…" }
    }
    AnimatedVisibility(
        visible = isPosting || done || failed,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(50))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                .clickable(enabled = failed) { failureSeen = true }
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { contentDescription = text },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                when {
                    failed -> Text("!", color = Color(0xFFFF5A5A), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    done -> Text("✓", color = Color(0xFF5BE3A6), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    else -> CircularProgressIndicator(
                        progress = { progress.toFloat().coerceIn(0.04f, 1f) },
                        modifier = Modifier.size(22.dp), strokeWidth = 3.dp, color = Color(0xFF22D3EE),
                        trackColor = Color.White.copy(alpha = 0.15f)
                    )
                }
            }
            Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
