package com.stitchsocial.club.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.services.NotificationService
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.ui.theme.StitchColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Layer 5: In-app invite picker (iOS parity with ios/Events/EventInviteSheet.swift).
 * Pulls the host's circle (follows + followers), supports free-text search, and
 * sends an in-app invite notification to everyone picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventInviteSheet(vm: EventsViewModel, event: StitchEventEntity, onDismiss: () -> Unit) {
    val blue = StitchColors.primary  // events accent = brand magenta (was blue #3399FF)
    val context = LocalContext.current
    val userService = remember { UserService(context) }
    val notificationService = remember { NotificationService() }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<BasicUserInfo>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<BasicUserInfo>>(emptyList()) }
    var selected by remember { mutableStateOf<Map<String, BasicUserInfo>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var justInvited by remember { mutableStateOf(0) }

    val searching = query.trim().isNotEmpty()
    val rows = if (searching) searchResults else candidates

    LaunchedEffect(Unit) {
        candidates = vm.inviteCandidates(userService)
        isLoading = false
    }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { searchResults = emptyList(); return@LaunchedEffect }
        delay(300)
        searchResults = vm.searchInviteCandidates(userService, query)
    }

    Scaffold(
        containerColor = StitchColors.background,
        topBar = {
            TopAppBar(
                title = { Text("Invite people", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onDismiss) { Text("Done", color = Color.White.copy(alpha = 0.7f)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StitchColors.background)
            )
        },
        bottomBar = {
            if (selected.isNotEmpty() || justInvited > 0) {
                Column(Modifier.background(StitchColors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (justInvited > 0) {
                        Text("Invited $justInvited ${if (justInvited == 1) "person" else "people"} ✓", color = StitchColors.success, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (selected.isNotEmpty()) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isSending = true
                                    val n = vm.inviteUsers(notificationService, selected.values.toList(), event)
                                    isSending = false
                                    justInvited += n
                                    selected = emptyMap(); query = ""; searchResults = emptyList()
                                }
                            },
                            enabled = !isSending,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = blue)
                        ) { Text(if (isSending) "Sending…" else "Invite ${selected.size}", color = Color.Black, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search people", color = Color.White.copy(alpha = 0.3f)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = blue)
            )
            when {
                isLoading && !searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
                rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (searching) "No one found" else "Follow people to invite them fast", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
                }
                else -> LazyColumn {
                    items(rows, key = { it.id }) { user ->
                        val picked = selected.containsKey(user.id)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = if (picked) selected - user.id else selected + (user.id to user)
                            }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                Text(user.username.take(1).uppercase(), color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(user.displayName.ifBlank { user.username }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("@${user.username}", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, maxLines = 1)
                            }
                            Text(if (picked) "☑" else "☐", color = if (picked) blue else Color.White.copy(alpha = 0.25f), fontSize = 20.sp)
                        }
                    }
                }
            }
        }
    }
}
