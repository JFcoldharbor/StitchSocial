/*
 * WatchHistoryScreen.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Settings → Watch history. The signed-in user's private history
 * (users/{uid}/watch_history, see WatchHistoryService.kt), newest first,
 * 30 at a time with the next page loaded on reaching the end.
 *
 * Rendered from the denormalized fields on each history doc, so the list
 * costs no video-doc reads. A tap opens the video the way a shared link
 * does — through NotificationRouter, which loads it, clears the screen and
 * presents the player.
 *
 * Removing an entry (one, or all) is also what tells Discover to stop
 * putting those videos at the back, so the confirmation says so.
 */

package com.stitchsocial.club.watchhistory

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.firestore.DocumentSnapshot
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.NotificationRouter
import com.stitchsocial.club.ui.theme.AppTheme
import com.stitchsocial.club.ui.theme.DismissX
import com.stitchsocial.club.ui.theme.Spacing
import com.stitchsocial.club.views.SettingsTokens
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 30

@Composable
fun WatchHistoryScreen(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<WatchHistoryEntry>>(emptyList()) }
    var cursor by remember { mutableStateOf<DocumentSnapshot?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    suspend fun loadFirst() {
        isLoading = true
        loadFailed = false
        runCatching { WatchHistory.page(after = null, limit = PAGE_SIZE) }
            .onSuccess {
                entries = it.entries
                cursor = it.last
                hasMore = it.hasMore
            }
            .onFailure {
                loadFailed = true
                if (BuildConfig.DEBUG) println("⚠️ WATCH HISTORY: first page failed — ${it.message}")
            }
        isLoading = false
    }

    suspend fun loadMore() {
        if (isLoadingMore || !hasMore) return
        val after = cursor ?: return
        isLoadingMore = true
        runCatching { WatchHistory.page(after = after, limit = PAGE_SIZE) }
            .onSuccess { page ->
                // A rewatch moves a doc to the top between pages; don't show it twice.
                val known = entries.map { it.videoID }.toSet()
                entries = entries + page.entries.filter { it.videoID !in known }
                cursor = page.last ?: cursor
                hasMore = page.hasMore
            }
            .onFailure {
                // Stop paging rather than retrying on every recomposition.
                hasMore = false
                if (BuildConfig.DEBUG) println("⚠️ WATCH HISTORY: next page failed — ${it.message}")
            }
        isLoadingMore = false
    }

    LaunchedEffect(Unit) { loadFirst() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.bg),
    ) {
        // Settings sub-page chrome (SettingsSubPage), plus Clear all at the end.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            DismissX(onClick = onDismiss, showsScrim = false)
            Text(
                text = "Watch history",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }, enabled = !isClearing) {
                    Text(
                        if (isClearing) "Clearing…" else "Clear all",
                        fontSize = 15.sp,
                        color = SettingsTokens.destructive,
                    )
                }
            }
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SettingsTokens.brand, strokeWidth = 2.dp)
            }

            loadFailed -> CenteredMessage(
                text = "Couldn't load your watch history.",
                action = "Try again",
                onAction = { scope.launch { loadFirst() } },
            )

            entries.isEmpty() -> CenteredMessage(text = "Videos you watch will show up here.")

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                items(entries, key = { it.videoID }) { entry ->
                    WatchHistoryRow(
                        entry = entry,
                        onOpen = {
                            val data = buildMap {
                                put("videoID", entry.videoID)
                                if (entry.threadID.isNotBlank()) put("threadID", entry.threadID)
                            }
                            NotificationRouter.request(NotificationRouter.TYPE_SHARED_LINK, data)
                        },
                        onRemove = {
                            // Optimistic: gone at once, put back if the delete fails.
                            val before = entries
                            entries = entries.filterNot { it.videoID == entry.videoID }
                            scope.launch {
                                runCatching { WatchHistory.remove(entry.videoID) }
                                    .onFailure {
                                        entries = before
                                        errorText = "Couldn't remove that video. Try again."
                                    }
                            }
                        },
                    )
                }
                if (hasMore) {
                    item(key = "load-more") {
                        // Reaching the end asks for the next page.
                        LaunchedEffect(cursor) { loadMore() }
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = Spacing.md),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = SettingsTokens.brand,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear watch history?") },
            text = { Text("Discover will stop putting these videos at the back.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    isClearing = true
                    scope.launch {
                        runCatching { WatchHistory.clearAll() }
                            .onSuccess {
                                entries = emptyList()
                                cursor = null
                                hasMore = false
                            }
                            .onFailure {
                                errorText = "Couldn't clear your watch history. Try again."
                                // Some batches may have gone; show what's left.
                                loadFirst()
                            }
                        isClearing = false
                    }
                }) {
                    Text("Clear", color = SettingsTokens.destructive)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    errorText?.let { message ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text("Something went wrong") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { errorText = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun WatchHistoryRow(
    entry: WatchHistoryEntry,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Spacing.md, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Small 9:16 poster, like the feed's own tiles.
        Box(
            modifier = Modifier
                .width(54.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(8.dp))
                .background(AppTheme.colors.surfaceStrong),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.thumbnailURL.isNotBlank()) {
                AsyncImage(
                    model = entry.thumbnailURL,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = AppTheme.colors.textTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title.ifBlank { "Untitled" },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.creatorName.isNotBlank()) {
                Text(
                    text = "@${entry.creatorName}",
                    fontSize = 13.sp,
                    color = AppTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            entry.watchedAt?.let { watchedAt ->
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        watchedAt.time,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE,
                    ).toString(),
                    fontSize = 12.sp,
                    color = AppTheme.colors.textTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove from watch history",
                tint = AppTheme.colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            tint = AppTheme.colors.textTertiary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = text,
            fontSize = 15.sp,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            TextButton(onClick = onAction) { Text(action, color = SettingsTokens.link) }
        }
    }
}
