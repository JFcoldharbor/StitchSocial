/*
 * CommunityEditView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Edit Community Settings (creator)
 *
 * Minimum-viable port of iOS CommunityEditView. Settings tab only —
 * lets the creator edit displayName + description and save. Members tab
 * with ban/mod tools is deferred to a follow-up.
 *
 * Replaces the no-op stub on "Edit Community & Mod Tools" button.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.community.Community
import com.stitchsocial.club.community.CommunityService
import kotlinx.coroutines.launch

@Composable
fun CommunityEditView(
    community: Community,
    onDismiss: () -> Unit,
    onSaved: (Community) -> Unit = {},
    communityService: CommunityService = CommunityService.shared,
) {
    val scope = rememberCoroutineScope()

    var displayName by remember { mutableStateOf(community.displayName) }
    var description by remember { mutableStateOf(community.description) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    val isDirty = displayName.trim() != community.displayName
        || description.trim() != community.description
    val canSave = isDirty && displayName.trim().isNotBlank() && !isSaving

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Top bar — Back + Edit Community + Save
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(top = 52.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.Cyan)
            }
            Text(
                "Edit Community",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            TextButton(
                enabled = canSave,
                onClick = {
                    isSaving = true
                    errorMessage = null
                    successMessage = null
                    scope.launch {
                        try {
                            communityService.updateCommunity(
                                creatorID = community.id,
                                displayName = displayName.trim().takeIf { it != community.displayName },
                                description = description.trim().takeIf { it != community.description },
                            )
                            // Build updated model locally so the parent can
                            // refresh without an extra Firestore round-trip.
                            val updated = community.copy(
                                displayName = displayName.trim(),
                                description = description.trim(),
                            )
                            successMessage = "Saved!"
                            onSaved(updated)
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "Save failed"
                        } finally {
                            isSaving = false
                        }
                    }
                },
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        color = Color.Cyan,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        "Save",
                        color = if (canSave) Color.Cyan else Color.Gray.copy(alpha = 0.5f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Header preview chip — shows what subscribers will see
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("🏠", fontSize = 28.sp)
                Column {
                    Text(
                        displayName.ifBlank { "Community name" },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (displayName.isBlank()) Color.Gray else Color.White,
                    )
                    Text(
                        "${community.memberCount} members",
                        fontSize = 12.sp,
                        color = Color.Gray,
                    )
                }
            }

            // Name field
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Community Name",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.8f),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { if (it.length <= 60) displayName = it },
                    placeholder = { Text("Community name", color = Color.Gray) },
                    singleLine = true,
                    colors = darkFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${displayName.length}/60",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                )
            }

            // Description field
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Description",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.8f),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { if (it.length <= 300) description = it },
                    placeholder = { Text("What's your community about?", color = Color.Gray) },
                    colors = darkFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
                Text(
                    "${description.length}/300",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                )
            }

            // Status messages
            errorMessage?.let {
                Text(it, color = Color.Red, fontSize = 13.sp)
            }
            successMessage?.let {
                Text(it, color = Color(0xFF30D158), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            // Future-tools placeholder — mod tools (ban / promote / pinned posts)
            // and image upload land in a follow-up. Showing the intent so the
            // creator knows what's coming.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Coming soon",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Gray,
                    style = androidx.compose.ui.text.TextStyle(letterSpacing = 1.sp),
                )
                Text(
                    "• Profile image upload",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Text(
                    "• Pin a post to the top",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Text(
                    "• Member moderation (ban / mute / promote to mod)",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Color.Cyan,
    unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f),
    focusedContainerColor = Color.White.copy(alpha = 0.05f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
    cursorColor = Color.Cyan,
)
