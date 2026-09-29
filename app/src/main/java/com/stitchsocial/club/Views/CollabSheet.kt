package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.CollabConfig
import com.stitchsocial.club.services.CollabException
import com.stitchsocial.club.services.CollabInvite
import com.stitchsocial.club.services.CollabService
import com.stitchsocial.club.services.Collaborator
import com.stitchsocial.club.services.SearchService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Who is on this post — the owner's view of it. iOS parity (CollabSheet.swift).
 *
 * Opened from the post's own more-menu, by its creator only. It lists who has
 * accepted, who has not answered, and how many of the four slots are left,
 * because a creator should learn the cap before choosing somebody rather than
 * after.
 *
 * Pending invites count against the cap here exactly as they do on the server.
 * Otherwise you could invite twenty people, have five accept, and land on a
 * post with five collaborators under a four-collaborator rule.
 *
 * The search is local to this sheet rather than the composer's UserTagSheet,
 * which is private to ThreadComposer.kt — lifting it would have meant editing
 * the upload path to add a feature that does not touch it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollabSheet(
    video: CoreVideoMetadata,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val searchService = remember { SearchService() }

    var collaborators by remember { mutableStateOf(video.collaborators) }
    var pending by remember { mutableStateOf<List<CollabInvite>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<BasicUserInfo>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val slotsLeft = (CollabConfig.MAX_COLLABORATORS - collaborators.size - pending.size)
        .coerceAtLeast(0)

    suspend fun reload() { pending = CollabService.pendingInvites(video.id) }

    LaunchedEffect(video.id) { reload() }

    // Debounced, so a five-letter handle is one query rather than five.
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        results = try {
            val excluded = collaborators.map { it.userID } +
                    pending.map { it.inviteeID } + video.creatorID
            searchService.searchUsers(query, 20).filter { it.id !in excluded }
        } catch (e: Exception) {
            emptyList()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Collaborators", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                "A collaborator shares a third of what this post earns, from the moment they accept. Nothing comes out of your side.",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "$slotsLeft of ${CollabConfig.MAX_COLLABORATORS} slots left",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (slotsLeft == 0) Color.White.copy(alpha = 0.4f) else Color(0xFFE91E63),
            )

            message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 13.sp, color = Color(0xFFFFB74D))
            }

            if (collaborators.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Label("On this post")
                collaborators.forEach { c ->
                    CollabRow(
                        title = c.displayName.ifEmpty { c.handle },
                        trailing = {
                            TextButton(
                                enabled = !working,
                                onClick = {
                                    scope.launch {
                                        working = true
                                        try {
                                            CollabService.leave(video.id, removeUserID = c.userID)
                                            collaborators = collaborators.filterNot { it.userID == c.userID }
                                        } catch (e: CollabException) {
                                            message = e.userMessage
                                        } finally { working = false }
                                    }
                                },
                            ) { Text("Remove", color = Color(0xFFFF5252), fontSize = 13.sp) }
                        },
                    )
                }
            }

            if (pending.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Label("Waiting to answer")
                pending.forEach { _ ->
                    CollabRow(
                        title = "Invite sent",
                        trailing = {
                            Text("waiting", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
                        },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            if (slotsLeft == 0) {
                Text(
                    "No slots left.",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.4f),
                )
            } else {
                Label("Add someone")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search by handle", color = Color.White.copy(alpha = 0.4f)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(results, key = { it.id }) { user ->
                        CollabRow(
                            title = user.displayName.ifEmpty { "@${user.username}" },
                            trailing = {
                                TextButton(
                                    enabled = !working,
                                    onClick = {
                                        scope.launch {
                                            working = true
                                            try {
                                                CollabService.invite(video.id, user.id)
                                                query = ""
                                                results = emptyList()
                                                reload()
                                                message = null
                                            } catch (e: CollabException) {
                                                message = e.userMessage
                                            } finally { working = false }
                                        }
                                    },
                                ) { Text("Invite", color = Color(0xFFE91E63), fontSize = 13.sp) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = 0.4f),
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun CollabRow(title: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}
