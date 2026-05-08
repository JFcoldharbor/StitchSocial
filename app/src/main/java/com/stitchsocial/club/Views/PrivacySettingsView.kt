/*
 * PrivacySettingsView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Mirror of Swift PrivacySettingsView.swift — account visibility, discoverability,
 * default stitch visibility, age group.
 *
 * NOTE: Android doesn't yet have a PrivacyService. Settings are read/written
 * directly to users/{userID}.privacy as a map. Once PrivacyService is ported
 * the read/save calls below should swap to it.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.stitchsocial.club.BuildConfig

// ─────────────────────────────────────────────
// MARK: - Privacy enums (mirror iOS)
// ─────────────────────────────────────────────

enum class AccountVisibility(val raw: String, val label: String) {
    PUBLIC("public", "Public"),
    FOLLOWERS_ONLY("followersOnly", "Followers Only");

    companion object {
        fun from(raw: String?): AccountVisibility =
            values().firstOrNull { it.raw == raw } ?: PUBLIC
    }
}

enum class DiscoverabilityMode(val raw: String, val label: String) {
    PUBLIC("public", "Everyone"),
    FOLLOWERS("followers", "Followers"),
    NONE("none", "Nobody");

    companion object {
        fun from(raw: String?): DiscoverabilityMode =
            values().firstOrNull { it.raw == raw } ?: PUBLIC
    }
}

enum class ContentVisibility(val raw: String, val label: String, val icon: ImageVector) {
    PUBLIC("public", "Public", Icons.Default.Language),
    FOLLOWERS("followers", "Followers", Icons.Default.People),
    TAGGED("tagged", "Tagged", Icons.Default.LocalOffer),
    PRIVATE("private", "Private", Icons.Default.Lock);

    companion object {
        fun from(raw: String?): ContentVisibility =
            values().firstOrNull { it.raw == raw } ?: PUBLIC
    }
}

enum class AgeGroup(val raw: String, val label: String) {
    TEEN("teen", "Teen (Under 18)"),
    ADULT("adult", "Adult (18+)");

    companion object {
        fun from(raw: String?): AgeGroup =
            values().firstOrNull { it.raw == raw } ?: ADULT
    }
}

// ─────────────────────────────────────────────
// MARK: - View
// ─────────────────────────────────────────────

@Composable
fun PrivacySettingsView(
    userID: String,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val db = remember { FirebaseFirestore.getInstance("stitchfin") }

    var accountVisibility by remember { mutableStateOf(AccountVisibility.PUBLIC) }
    var discoverability by remember { mutableStateOf(DiscoverabilityMode.PUBLIC) }
    var defaultStitchVisibility by remember { mutableStateOf(ContentVisibility.PUBLIC) }
    var ageGroup by remember { mutableStateOf(AgeGroup.ADULT) }
    var ageVerified by remember { mutableStateOf(false) }

    var isLoaded by remember { mutableStateOf(false) }
    var pendingAge by remember { mutableStateOf<AgeGroup?>(null) }
    var showAgeConfirm by remember { mutableStateOf(false) }

    // Load
    LaunchedEffect(userID) {
        try {
            val doc = db.collection("users").document(userID).get().await()
            @Suppress("UNCHECKED_CAST")
            val privacy = doc.get("privacy") as? Map<String, Any> ?: emptyMap()
            accountVisibility = AccountVisibility.from(privacy["accountVisibility"] as? String)
            discoverability = DiscoverabilityMode.from(privacy["discoverabilityMode"] as? String)
            defaultStitchVisibility = ContentVisibility.from(privacy["defaultStitchVisibility"] as? String)
            ageGroup = AgeGroup.from(privacy["ageGroup"] as? String)
            ageVerified = privacy["ageVerifiedAt"] != null
        } catch (_: Exception) { }
        isLoaded = true
    }

    fun save() {
        scope.launch {
            try {
                val payload = mutableMapOf<String, Any>(
                    "accountVisibility" to accountVisibility.raw,
                    "discoverabilityMode" to discoverability.raw,
                    "defaultStitchVisibility" to defaultStitchVisibility.raw,
                    "ageGroup" to ageGroup.raw
                )
                if (ageVerified) {
                    payload["ageVerifiedAt"] = com.google.firebase.Timestamp.now()
                }
                db.collection("users").document(userID).update(mapOf("privacy" to payload)).await()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("PRIVACY VIEW: Save failed: ${e.message}") }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 40.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Done", color = Color.Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Privacy", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(60.dp))
            }

            if (!isLoaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    // Account visibility
                    PrivacySection(title = "ACCOUNT VISIBILITY", icon = Icons.Default.Visibility, iconColor = Color(0xFF0A84FF)) {
                        Text("Who can see your profile", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        SegmentedRow(
                            values = AccountVisibility.values().toList(),
                            selected = accountVisibility,
                            label = { it.label }
                        ) { accountVisibility = it; save() }
                    }

                    // Discoverability
                    PrivacySection(title = "DISCOVERABILITY", icon = Icons.Default.Search, iconColor = Color.Cyan) {
                        Text("Show my stitches in Discovery", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        SegmentedRow(
                            values = DiscoverabilityMode.values().toList(),
                            selected = discoverability,
                            label = { it.label }
                        ) { discoverability = it; save() }
                        Text(
                            when (discoverability) {
                                DiscoverabilityMode.PUBLIC -> "Your stitches can appear in everyone's Discovery feed"
                                DiscoverabilityMode.FOLLOWERS -> "Only people who follow you will see your stitches"
                                DiscoverabilityMode.NONE -> "Your stitches won't appear in Discovery — only via your profile"
                            },
                            fontSize = 11.sp, color = Color.Gray
                        )
                    }

                    // Default stitch visibility
                    PrivacySection(title = "DEFAULT STITCH VISIBILITY", icon = Icons.Default.Videocam, iconColor = Color(0xFFBF5AF2)) {
                        Text("New stitches default to", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        SegmentedRow(
                            values = ContentVisibility.values().toList(),
                            selected = defaultStitchVisibility,
                            label = { it.label }
                        ) { defaultStitchVisibility = it; save() }
                        Text(
                            when (defaultStitchVisibility) {
                                ContentVisibility.PUBLIC -> "Anyone can see your new stitches"
                                ContentVisibility.FOLLOWERS -> "Only followers can view your new stitches"
                                ContentVisibility.TAGGED -> "Only people you tag can view"
                                ContentVisibility.PRIVATE -> "Only you can see — saved as draft"
                            },
                            fontSize = 11.sp, color = Color.Gray
                        )
                    }

                    // Age Group
                    PrivacySection(title = "CONTENT SAFETY", icon = Icons.Default.Shield, iconColor = Color(0xFF30D158)) {
                        Text("Age Group", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        SegmentedRow(
                            values = AgeGroup.values().toList(),
                            selected = ageGroup,
                            label = { it.label }
                        ) { picked ->
                            if (picked != ageGroup) {
                                pendingAge = picked
                                showAgeConfirm = true
                            }
                        }
                        Text(
                            when (ageGroup) {
                                AgeGroup.TEEN -> "Content filtered for safety. Uploads go to the teen-safe bucket."
                                AgeGroup.ADULT -> "Full content access. Standard community guidelines apply."
                            },
                            fontSize = 11.sp, color = Color.Gray
                        )
                        if (ageVerified) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.VerifiedUser, null, tint = Color(0xFF30D158), modifier = Modifier.size(12.dp))
                                Text("Age verified", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF30D158))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAgeConfirm) {
        AlertDialog(
            onDismissRequest = { showAgeConfirm = false; pendingAge = null },
            title = { Text("Change Age Group?", color = Color.White) },
            text = {
                Text(
                    "This changes your content routing and cannot be easily reversed. Teen accounts see restricted content only.",
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingAge?.let {
                        ageGroup = it
                        ageVerified = true
                        save()
                    }
                    showAgeConfirm = false
                    pendingAge = null
                }) { Text("Confirm", color = Color(0xFFFF453A)) }
            },
            dismissButton = {
                TextButton(onClick = { showAgeConfirm = false; pendingAge = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}

@Composable
private fun PrivacySection(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(12.dp))
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray, letterSpacing = 0.8.sp)
        }
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun <T> SegmentedRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        values.forEach { v ->
            val isSelected = v == selected
            Box(
                modifier = Modifier.weight(1f)
                    .background(
                        if (isSelected) Color.Cyan.copy(alpha = 0.85f) else Color.Transparent,
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelect(v) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(v),
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) Color.Black else Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
