/*
 * CreatorCommunitySettingsView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views - Creator Community Settings
 * Mirror of Swift CreatorCommunitySettingsView.swift
 *
 * Three states based on CommunityStatus:
 *   - NotCreated  → "Create Community" form (or tier-locked card)
 *   - Inactive    → "Activate Community" form (auto-created on tier-up, awaiting creator opt-in)
 *   - Active      → Stats + edit/deactivate
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.community.Community
import com.stitchsocial.club.community.CommunityService
import com.stitchsocial.club.community.CommunityStatus
import com.stitchsocial.club.foundation.UserTier
import kotlinx.coroutines.launch

@Composable
fun CreatorCommunitySettingsView(
    creatorID: String,
    creatorUsername: String,
    creatorDisplayName: String,
    creatorTier: UserTier,
    onDismiss: () -> Unit,
    communityService: CommunityService = CommunityService.shared
) {
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<CommunityStatus>(CommunityStatus.NotCreated) }
    var communityName by remember { mutableStateOf("") }
    var communityDescription by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDeactivateConfirm by remember { mutableStateOf(false) }

    suspend fun reload() {
        try {
            val s = communityService.fetchCommunityStatus(creatorID)
            status = s
            val community = s.community
            if (community != null) {
                communityName = community.displayName
                communityDescription = community.description
            } else if (communityName.isEmpty()) {
                communityName = "$creatorDisplayName's Community"
            }
        } catch (e: Exception) {
            errorMessage = e.message
        }
    }

    LaunchedEffect(creatorID) {
        isLoading = true
        reload()
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        Column(modifier = Modifier.fillMaxSize()) {

            // Top bar — matches iOS NavigationView title + Done button
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 52.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.size(48.dp))
                Text(
                    "Community", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = onDismiss) {
                    Text("Done", color = Color.Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatusCard(status, creatorTier)

                    when (val s = status) {
                        is CommunityStatus.NotCreated -> NotCreatedSection(
                            creatorTier = creatorTier,
                            communityName = communityName,
                            communityDescription = communityDescription,
                            creatorDisplayName = creatorDisplayName,
                            isSaving = isSaving,
                            onNameChange = { communityName = it },
                            onDescriptionChange = { communityDescription = it },
                            onCreateAndActivate = {
                                isSaving = true
                                scope.launch {
                                    try {
                                        communityService.createCommunity(
                                            creatorID = creatorID,
                                            creatorUsername = creatorUsername,
                                            creatorDisplayName = creatorDisplayName,
                                            creatorTier = creatorTier,
                                            displayName = communityName.trim(),
                                            description = communityDescription.trim()
                                        )
                                        reload()
                                    } catch (e: Exception) {
                                        errorMessage = e.message
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                        )
                        is CommunityStatus.Inactive -> InactiveSection(
                            communityName = communityName,
                            communityDescription = communityDescription,
                            creatorDisplayName = creatorDisplayName,
                            isSaving = isSaving,
                            onNameChange = { communityName = it },
                            onDescriptionChange = { communityDescription = it },
                            onActivate = {
                                isSaving = true
                                scope.launch {
                                    try {
                                        communityService.activateCommunity(
                                            creatorID = creatorID,
                                            displayName = communityName.trim(),
                                            description = communityDescription.trim()
                                        )
                                        reload()
                                    } catch (e: Exception) {
                                        errorMessage = e.message
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                        )
                        is CommunityStatus.Active -> ActiveSection(
                            community = s.data,
                            onDeactivateRequest = { showDeactivateConfirm = true }
                        )
                    }
                }
            }
        }
    }

    if (showDeactivateConfirm) {
        AlertDialog(
            onDismissRequest = { showDeactivateConfirm = false },
            title = { Text("Deactivate Community?", color = Color.White) },
            text = {
                Text(
                    "Members won't be removed but they can't see or interact with the community until you reactivate.",
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeactivateConfirm = false
                    isSaving = true
                    scope.launch {
                        try {
                            communityService.deactivateCommunity(creatorID)
                            reload()
                        } catch (e: Exception) {
                            errorMessage = e.message
                        } finally {
                            isSaving = false
                        }
                    }
                }) { Text("Deactivate", color = Color(0xFFFF453A)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeactivateConfirm = false }) {
                    Text("Keep Active", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }

    val errMsg = errorMessage
    if (errMsg != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Error", color = Color.White) },
            text = { Text(errMsg, color = Color.Gray) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("OK", color = Color.Cyan) }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}

// ─────────────────────────────────────────────
// MARK: - Status Card
// ─────────────────────────────────────────────

@Composable
private fun StatusCard(status: CommunityStatus, tier: UserTier) {
    val (color, icon, title, subtitle, badge) = when (status) {
        is CommunityStatus.NotCreated -> StatusInfo(
            color = Color.Gray,
            icon = Icons.Default.Forum,
            title = "No Community Yet",
            subtitle = if (Community.canCreateCommunity(tier))
                "You're eligible! Create your community below."
            else
                "Reach Influencer tier to unlock.",
            badge = "LOCKED"
        )
        is CommunityStatus.Inactive -> StatusInfo(
            color = Color(0xFFFFD60A),
            icon = Icons.Default.PauseCircle,
            title = "Community Ready",
            subtitle = "Auto-created when you reached ${tier.displayName}. Activate to go live.",
            badge = "INACTIVE"
        )
        is CommunityStatus.Active -> StatusInfo(
            color = Color(0xFF30D158),
            icon = Icons.Default.CheckCircle,
            title = "Community Active",
            subtitle = "${status.data.memberCount} members",
            badge = "LIVE"
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
        }
        Text(
            badge, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color,
            modifier = Modifier
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

private data class StatusInfo(
    val color: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    val badge: String
)

// ─────────────────────────────────────────────
// MARK: - Not Created Section
// ─────────────────────────────────────────────

@Composable
private fun NotCreatedSection(
    creatorTier: UserTier,
    communityName: String,
    communityDescription: String,
    creatorDisplayName: String,
    isSaving: Boolean,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCreateAndActivate: () -> Unit
) {
    if (Community.canCreateCommunity(creatorTier)) {
        NameDescriptionFields(
            creatorDisplayName = creatorDisplayName,
            name = communityName,
            description = communityDescription,
            onNameChange = onNameChange,
            onDescriptionChange = onDescriptionChange
        )
        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            label = "Create Community",
            icon = Icons.Default.AddCircle,
            background = Brush.horizontalGradient(listOf(Color.Cyan, Color.Cyan)),
            isLoading = isSaving,
            enabled = !isSaving && communityName.trim().isNotEmpty(),
            onClick = onCreateAndActivate
        )
    } else {
        TierRequirementCard(creatorTier)
    }
}

@Composable
private fun TierRequirementCard(tier: UserTier) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(18.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.Lock, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(32.dp))
        Text("Reach Influencer Tier", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Communities are available for creators at Influencer tier (100K+ followers) and above. Keep growing!",
            fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Current:", fontSize = 13.sp, color = Color.White.copy(alpha = 0.4f))
            Text(tier.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.Cyan)
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Inactive Section
// ─────────────────────────────────────────────

@Composable
private fun InactiveSection(
    communityName: String,
    communityDescription: String,
    creatorDisplayName: String,
    isSaving: Boolean,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onActivate: () -> Unit
) {
    NameDescriptionFields(
        creatorDisplayName = creatorDisplayName,
        name = communityName,
        description = communityDescription,
        onNameChange = onNameChange,
        onDescriptionChange = onDescriptionChange
    )
    Spacer(Modifier.height(4.dp))
    PrimaryButton(
        label = "Activate Community",
        icon = Icons.Default.Bolt,
        background = Brush.horizontalGradient(listOf(Color(0xFFFF2D55), Color(0xFFBF5AF2))),
        isLoading = isSaving,
        enabled = !isSaving,
        onClick = onActivate
    )
    InfoCard(
        "When you activate, your subscribers can join and start earning XP, unlocking badges, and engaging with your content."
    )
}

// ─────────────────────────────────────────────
// MARK: - Active Section
// ─────────────────────────────────────────────

@Composable
private fun ActiveSection(
    community: Community,
    onDeactivateRequest: () -> Unit
) {
    // Stats row
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp)),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatBlock(value = community.memberCount.toString(), label = "Members", modifier = Modifier.weight(1f))
        StatBlock(value = community.totalPosts.toString(), label = "Posts", modifier = Modifier.weight(1f))
    }

    PrimaryButton(
        label = "Edit Community & Mod Tools",
        icon = Icons.Default.Edit,
        background = Brush.horizontalGradient(listOf(Color.Cyan, Color.Cyan)),
        isLoading = false,
        enabled = true,
        onClick = {
            // TODO: Hook into CommunityEditView once ported.
            // For now this is a no-op so the row doesn't crash.
        }
    )

    TextButton(
        onClick = onDeactivateRequest,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFF453A).copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp)
    ) {
        Text("Deactivate Community", color = Color(0xFFFF453A), fontSize = 14.sp)
    }
}

@Composable
private fun StatBlock(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
    }
}

// ─────────────────────────────────────────────
// MARK: - Shared Components
// ─────────────────────────────────────────────

@Composable
private fun NameDescriptionFields(
    creatorDisplayName: String,
    name: String,
    description: String,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit
) {
    LabeledField(
        label = "Community Name",
        value = name,
        placeholder = "e.g. The $creatorDisplayName Crew",
        singleLine = true,
        onChange = onNameChange
    )
    LabeledField(
        label = "Description",
        value = description,
        placeholder = "What's your community about?",
        singleLine = false,
        onChange = onDescriptionChange
    )
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    singleLine: Boolean,
    onChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.5f))
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Color.Cyan),
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(placeholder, color = Color.White.copy(alpha = 0.25f), fontSize = 15.sp)
                    }
                    inner()
                }
            )
        }
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Brush,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) background else Brush.horizontalGradient(listOf(Color.Gray, Color.Gray)))
            .clickable(enabled = enabled && !isLoading) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                Text(label, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color.Cyan.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Default.Info, null,
            tint = Color.Cyan.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp).padding(top = 2.dp)
        )
        Text(text, fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f), lineHeight = 18.sp)
    }
}
