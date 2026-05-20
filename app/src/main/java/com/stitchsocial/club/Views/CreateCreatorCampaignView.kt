package com.stitchsocial.club.Views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.services.CreatorCampaignCriteria
import com.stitchsocial.club.services.CreatorCampaignService
import kotlinx.coroutines.launch
import java.util.Date

/**
 * CreateCreatorCampaignView — brand-facing campaign creation form.
 *
 * On submit → writes creatorCampaigns/{id} which triggers the
 * onCreatorCampaignCreated Cloud Function server-side (embed brief +
 * match creators + notify top 50).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCreatorCampaignView(
    brandID: String,
    brandName: String,
    brandLogoURL: String?,
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val service = remember { CreatorCampaignService.getInstance() }
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var brief by remember { mutableStateOf("") }
    var payoutDollars by remember { mutableStateOf("100") }
    var category by remember { mutableStateOf("lifestyle") }
    var minTier by remember { mutableStateOf("rising") }
    var minStitchers by remember { mutableStateOf("1000") }
    var minViewsPerVideo by remember { mutableStateOf("") }
    var requiredHashtags by remember { mutableStateOf("") }

    var isSubmitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val categories = listOf("lifestyle", "fitness", "beauty", "technology", "food", "music", "gaming", "fashion", "other")
    val tiers = listOf("rookie", "rising", "veteran", "influencer", "legendary", "founder")

    val payoutInt = payoutDollars.toIntOrNull() ?: 0
    val creatorNet = String.format("%.2f", payoutInt * 0.8)
    val canSubmit = title.isNotBlank() && brief.length >= 20 && payoutInt >= 5 && !isSubmitting

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("New campaign", color = Color.White) },
                navigationIcon = {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Section("Campaign brief") {
                InputField("Title", title, onChange = { title = it }, placeholder = "e.g., Summer skincare reveal")
                MultilineField(
                    "Brief",
                    brief,
                    onChange = { brief = it },
                    placeholder = "What should creators make? Tone, must-include points, hashtags. Min 20 chars."
                )
                DropdownField("Category", category, options = categories, onChange = { category = it })
            }

            Section("Payout") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("$", color = Color(0xFF1E8E3E), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    BasicTextField(
                        value = payoutDollars,
                        onValueChange = { payoutDollars = it.filter { c -> c.isDigit() } },
                        textStyle = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(0.4f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        cursorBrush = SolidColor(Color.White)
                    )
                    Text("per approved deliverable", color = Color.Gray, fontSize = 12.sp)
                }
                Text(
                    "Stitch takes 20% — creator nets $$creatorNet per approved delivery",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }

            Section("Creator requirements") {
                DropdownField("Minimum tier", minTier, options = tiers, onChange = { minTier = it })
                InputField(
                    "Min stitchers (followers)",
                    minStitchers,
                    onChange = { minStitchers = it.filter { c -> c.isDigit() } },
                    keyboard = KeyboardType.Number,
                    placeholder = "1000"
                )
                InputField(
                    "Min views per video",
                    minViewsPerVideo,
                    onChange = { minViewsPerVideo = it.filter { c -> c.isDigit() } },
                    keyboard = KeyboardType.Number,
                    placeholder = "Optional"
                )
                InputField(
                    "Required hashtags (comma-separated)",
                    requiredHashtags,
                    onChange = { requiredHashtags = it },
                    placeholder = "fitness, workout"
                )
            }

            errorMsg?.let { Text(it, color = Color.Red, fontSize = 13.sp) }

            Button(
                onClick = {
                    scope.launch {
                        isSubmitting = true
                        errorMsg = null
                        try {
                            val payoutCents = payoutInt * 100
                            val hashtags = requiredHashtags
                                .split(",")
                                .map { it.trim().lowercase() }
                                .filter { it.isNotEmpty() }
                            val criteria = CreatorCampaignCriteria(
                                minTier = minTier,
                                minStitchers = minStitchers.toIntOrNull(),
                                minViewsPerVideo = minViewsPerVideo.toIntOrNull(),
                                requiredHashtags = hashtags.takeIf { it.isNotEmpty() },
                                preferredCategories = listOf(category)
                            )
                            val id = service.createCampaign(
                                brandID = brandID,
                                brandName = brandName,
                                brandLogoURL = brandLogoURL,
                                title = title,
                                brief = brief,
                                category = category,
                                payoutCents = payoutCents,
                                criteria = criteria,
                                contentDueDate = null as Date?
                            )
                            onCreated(id)
                        } catch (e: Exception) {
                            errorMsg = e.message ?: "Failed to create campaign"
                        }
                        isSubmitting = false
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canSubmit) Color.White else Color.Gray.copy(alpha = 0.3f),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (isSubmitting) "Posting…" else "Post campaign",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }

            Text(
                "By posting, you agree to fund payouts in full upon approval. Creators receive earnings via Stripe Connect.",
                color = Color.Gray,
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title.uppercase(),
            color = Color.Gray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        content()
    }
}

@Composable
private fun InputField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    placeholder: String = ""
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = Color.Gray.copy(alpha = 0.5f), fontSize = 14.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                cursorBrush = SolidColor(Color.White),
                singleLine = true
            )
        }
    }
}

@Composable
private fun MultilineField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 160.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            if (value.isEmpty()) {
                Text(placeholder, color = Color.Gray.copy(alpha = 0.5f), fontSize = 14.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Color.White)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    value.replaceFirstChar { it.uppercase() },
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ArrowDropDown, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color(0xFF1A1A1A))
            ) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt.replaceFirstChar { it.uppercase() }, color = Color.White) },
                        onClick = {
                            onChange(opt)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
