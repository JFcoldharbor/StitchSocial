package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stitchsocial.club.services.*
import kotlinx.coroutines.launch

/**
 * CreatorCampaignDetailView — role-aware drill-down.
 *
 * Creator view:
 *   - Brief + criteria
 *   - "Apply" sheet (if no application yet) → calls applyToCreatorCampaign
 *   - Status badge once applied (pending / approved / rejected)
 *   - "Submit deliverable" sheet (if approved) → submitCreatorCampaignDeliverable
 *   - Deliverable status block (payout status, revision notes)
 *
 * Brand view:
 *   - Brief
 *   - Applicant list with AI fit %, pitch, approve/reject
 *   - Deliverable list with approve / request revision
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorCampaignDetailView(
    campaign: CreatorCampaign,
    currentUserID: String,
    isBrandAccount: Boolean,
    onDismiss: () -> Unit
) {
    val service = remember { CreatorCampaignService.getInstance() }
    val scope = rememberCoroutineScope()

    var myApplication by remember { mutableStateOf<CreatorCampaignApplication?>(null) }
    var myDeliverable by remember { mutableStateOf<CreatorCampaignDeliverable?>(null) }
    var applications by remember { mutableStateOf<List<CreatorCampaignApplication>>(emptyList()) }
    var deliverables by remember { mutableStateOf<List<CreatorCampaignDeliverable>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var showingApply by remember { mutableStateOf(false) }
    var showingSubmit by remember { mutableStateOf(false) }

    suspend fun reload() {
        isLoading = true
        if (isBrandAccount) {
            applications = service.fetchApplications(campaign.id)
            deliverables = service.fetchDeliverables(campaign.id)
        } else {
            myApplication = service.fetchApplicationStatus(campaign.id, currentUserID)
            myDeliverable = service.fetchMyDeliverable(campaign.id, currentUserID)
        }
        isLoading = false
    }

    LaunchedEffect(campaign.id) { reload() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(campaign.title, color = Color.White, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Color.White) }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                BriefCard(campaign)
                campaign.criteria?.let { CriteriaCard(it) }

                if (isBrandAccount) {
                    BrandSections(
                        applications = applications,
                        deliverables = deliverables,
                        campaignID = campaign.id,
                        onReload = { scope.launch { reload() } }
                    )
                } else {
                    CreatorSections(
                        application = myApplication,
                        deliverable = myDeliverable,
                        onApply = { showingApply = true },
                        onSubmit = { showingSubmit = true }
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showingApply) {
        Dialog(
            onDismissRequest = { showingApply = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            ApplyPitchSheet(
                campaign = campaign,
                onSubmitted = {
                    showingApply = false
                    scope.launch { reload() }
                },
                onDismiss = { showingApply = false }
            )
        }
    }

    if (showingSubmit) {
        Dialog(
            onDismissRequest = { showingSubmit = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            SubmitDeliverableSheet(
                campaignID = campaign.id,
                onSubmitted = {
                    showingSubmit = false
                    scope.launch { reload() }
                },
                onDismiss = { showingSubmit = false }
            )
        }
    }
}

// ============================ SHARED CARDS ============================

@Composable
private fun BriefCard(campaign: CreatorCampaign) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    campaign.brandName ?: "Brand",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                campaign.category?.let {
                    Text(it.replaceFirstChar { c -> c.uppercase() }, color = Color.Gray, fontSize = 11.sp)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Payout", color = Color.Gray, fontSize = 11.sp)
                Text(
                    "$${campaign.payoutDollars.toInt()}",
                    color = Color(0xFF1E8E3E),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        }
        Text(campaign.brief, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
    }
}

@Composable
private fun CriteriaCard(c: CreatorCampaignCriteria) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Requirements", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        c.minTier?.let { Bullet("Minimum tier: ${it.replaceFirstChar { c -> c.uppercase() }}") }
        c.minStitchers?.takeIf { it > 0 }?.let { Bullet("Minimum stitchers: $it") }
        c.minViewsPerVideo?.takeIf { it > 0 }?.let { Bullet("Avg views per video: $it+") }
        c.requiredHashtags?.takeIf { it.isNotEmpty() }?.let {
            Bullet("Required hashtags: " + it.joinToString(", ") { tag -> "#$tag" })
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
    }
}

// ============================ CREATOR SIDE ============================

@Composable
private fun CreatorSections(
    application: CreatorCampaignApplication?,
    deliverable: CreatorCampaignDeliverable?,
    onApply: () -> Unit,
    onSubmit: () -> Unit
) {
    if (application == null) {
        // Apply button
        Button(
            onClick = onApply,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Apply for this campaign", fontWeight = FontWeight.SemiBold)
        }
    } else {
        ApplicationStatusCard(application)
        if (application.status == "approved") {
            if (deliverable?.draftSubmittedAt != null) {
                DeliverableStatusCard(deliverable, onResubmit = onSubmit)
            } else {
                Button(
                    onClick = onSubmit,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan, contentColor = Color.Black),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Submit deliverable", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ApplicationStatusCard(app: CreatorCampaignApplication) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your application", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            StatusBadge(app.status)
        }
        app.pitch?.takeIf { it.isNotEmpty() }?.let {
            Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        }
        app.aiFitScore?.let {
            Text("AI fit: $it%", color = Color.Cyan, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DeliverableStatusCard(d: CreatorCampaignDeliverable, onResubmit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Deliverable", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            StatusBadge(d.approvalStatus)
        }
        d.draftURL?.let {
            Text(it, color = Color.Cyan, fontSize = 12.sp, maxLines = 2)
        }
        if (d.approvalStatus == "revision_requested" && !d.revisionNotes.isNullOrEmpty()) {
            Text("Revision notes:", color = Color(0xFFFFA000), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(d.revisionNotes, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            TextButton(onClick = onResubmit) {
                Text("Submit revision", color = Color(0xFFFFA000), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        d.payoutStatus?.let { status ->
            PayoutStatusRow(status, d.creatorNetCents)
            if (status == "held_no_connect_account") {
                Text(
                    "Set up payouts at stitchsocial.me/payouts to release this payment.",
                    color = Color(0xFFFFA000),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun PayoutStatusRow(status: String, net: Int?) {
    val (label, color, icon) = when (status) {
        "paid", "paid_confirmed" -> Triple("Paid", Color(0xFF1E8E3E), Icons.Default.CheckCircle)
        "held_no_connect_account" -> Triple("Held — set up payouts", Color(0xFFFFA000), Icons.Default.Inbox)
        "failed" -> Triple("Failed", Color.Red, Icons.Default.Error)
        "pending_stripe" -> Triple("Processing", Color.Yellow, Icons.Default.HourglassEmpty)
        else -> Triple(status, Color.Gray, Icons.Default.Circle)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (net != null && status.startsWith("paid")) {
            Text(
                "• $${String.format("%.2f", net / 100.0)}",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
        }
    }
}

// ============================ BRAND SIDE ============================

@Composable
private fun BrandSections(
    applications: List<CreatorCampaignApplication>,
    deliverables: List<CreatorCampaignDeliverable>,
    campaignID: String,
    onReload: () -> Unit
) {
    if (applications.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Applicants (${applications.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            applications.forEach { app ->
                ApplicantRow(app = app, campaignID = campaignID, onReload = onReload)
            }
        }
    }

    if (deliverables.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Deliverables (${deliverables.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            deliverables.forEach { d ->
                DeliverableRow(deliverable = d, campaignID = campaignID, onReload = onReload)
            }
        }
    }

    if (applications.isEmpty() && deliverables.isEmpty()) {
        Text(
            "No applications yet — creators will appear here as they apply.",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 20.dp)
        )
    }
}

@Composable
private fun ApplicantRow(
    app: CreatorCampaignApplication,
    campaignID: String,
    onReload: () -> Unit
) {
    val service = remember { CreatorCampaignService.getInstance() }
    val scope = rememberCoroutineScope()
    var isDeciding by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.creatorName ?: app.creatorID,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                app.creatorTier?.let {
                    Text(it.replaceFirstChar { c -> c.uppercase() }, color = Color.Gray, fontSize = 11.sp)
                }
            }
            app.aiFitScore?.let {
                Text(
                    "$it% fit",
                    color = Color.Cyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(Color.Cyan.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        app.pitch?.takeIf { it.isNotEmpty() }?.let {
            Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 4)
        }

        app.metricSnapshot?.let { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                s.stitcherCount?.let { MetricChip("Stitchers", "$it") }
                s.viewsPerVideoAvg?.let { MetricChip("Views/vid", "$it") }
            }
        }

        if (app.status == "pending") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    "Approve",
                    color = Color(0xFF1E8E3E),
                    onClick = {
                        scope.launch {
                            isDeciding = true
                            try {
                                service.decide(campaignID, app.creatorID, true)
                                onReload()
                            } catch (e: Exception) { /* surface later */ }
                            isDeciding = false
                        }
                    },
                    enabled = !isDeciding,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    "Reject",
                    color = Color.Red,
                    onClick = {
                        scope.launch {
                            isDeciding = true
                            try {
                                service.decide(campaignID, app.creatorID, false)
                                onReload()
                            } catch (e: Exception) { /* surface later */ }
                            isDeciding = false
                        }
                    },
                    enabled = !isDeciding,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Text(
                app.status.replaceFirstChar { it.uppercase() },
                color = Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Color.Gray, fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DeliverableRow(
    deliverable: CreatorCampaignDeliverable,
    campaignID: String,
    onReload: () -> Unit
) {
    val service = remember { CreatorCampaignService.getInstance() }
    val scope = rememberCoroutineScope()
    var isReviewing by remember { mutableStateOf(false) }
    var showRevisionDialog by remember { mutableStateOf(false) }
    var revisionNotes by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                deliverable.creatorID,
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                deliverable.approvalStatus.replace("_", " ").replaceFirstChar { it.uppercase() },
                color = Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        deliverable.draftURL?.let { url ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Link, null, tint = Color.Cyan, modifier = Modifier.size(12.dp))
                Text(url, color = Color.Cyan, fontSize = 12.sp, maxLines = 1)
            }
        }

        if (deliverable.approvalStatus == "awaiting" && deliverable.draftURL != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    "Approve & pay",
                    color = Color(0xFF1E8E3E),
                    onClick = {
                        scope.launch {
                            isReviewing = true
                            try {
                                service.reviewDeliverable(campaignID, deliverable.creatorID, true)
                                onReload()
                            } catch (e: Exception) { }
                            isReviewing = false
                        }
                    },
                    enabled = !isReviewing,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    "Request revision",
                    color = Color(0xFFFFA000),
                    onClick = { showRevisionDialog = true },
                    enabled = !isReviewing,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        deliverable.payoutStatus?.let {
            Text("Payout: ${it.replace("_", " ")}", color = Color(0xFF1E8E3E), fontSize = 11.sp)
        }
    }

    if (showRevisionDialog) {
        AlertDialog(
            onDismissRequest = { showRevisionDialog = false },
            title = { Text("Request revision", color = Color.White) },
            text = {
                BasicTextField(
                    value = revisionNotes,
                    onValueChange = { revisionNotes = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp)
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    cursorBrush = SolidColor(Color.White)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        isReviewing = true
                        try {
                            service.reviewDeliverable(campaignID, deliverable.creatorID, false, revisionNotes)
                            showRevisionDialog = false
                            onReload()
                        } catch (e: Exception) { }
                        isReviewing = false
                    }
                }) { Text("Send", color = Color(0xFFFFA000)) }
            },
            dismissButton = {
                TextButton(onClick = { showRevisionDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1A1A1A)
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.2f),
            contentColor = color,
            disabledContainerColor = color.copy(alpha = 0.1f),
            disabledContentColor = color.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (color, label) = when (status) {
        "pending" -> Color.Yellow to "Pending"
        "approved" -> Color(0xFF1E8E3E) to "Approved"
        "rejected" -> Color.Red to "Rejected"
        "withdrawn" -> Color.Gray to "Withdrawn"
        "awaiting" -> Color.Yellow to "Awaiting review"
        "revision_requested" -> Color(0xFFFFA000) to "Revision requested"
        else -> Color.Gray to status.replaceFirstChar { it.uppercase() }
    }
    Text(
        label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

// ============================ APPLY SHEET ============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApplyPitchSheet(
    campaign: CreatorCampaign,
    onSubmitted: () -> Unit,
    onDismiss: () -> Unit
) {
    val service = remember { CreatorCampaignService.getInstance() }
    val scope = rememberCoroutineScope()
    var pitch by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Apply", color = Color.White) },
                navigationIcon = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.White)
                    }
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Why should they pick you?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Tell the brand what you'd make. Keep it short — they're reviewing many applications.",
                color = Color.Gray,
                fontSize = 13.sp
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 240.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                if (pitch.isEmpty()) {
                    Text(
                        "e.g., I'd build a 3-stitch reaction thread reviewing the product honestly. My fitness audience loves unfiltered first-takes.",
                        color = Color.Gray.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                }
                BasicTextField(
                    value = pitch,
                    onValueChange = { pitch = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color.White)
                )
            }
            errorMsg?.let { Text(it, color = Color.Red, fontSize = 13.sp) }

            Button(
                onClick = {
                    scope.launch {
                        isSubmitting = true
                        errorMsg = null
                        try {
                            service.apply(campaign.id, pitch.trim())
                            onSubmitted()
                        } catch (e: Exception) {
                            errorMsg = e.message ?: "Failed to apply"
                        }
                        isSubmitting = false
                    }
                },
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    if (isSubmitting) "Sending…" else "Submit application",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ============================ SUBMIT DELIVERABLE SHEET ============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubmitDeliverableSheet(
    campaignID: String,
    onSubmitted: () -> Unit,
    onDismiss: () -> Unit
) {
    val service = remember { CreatorCampaignService.getInstance() }
    val scope = rememberCoroutineScope()
    var draftURL by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val canSubmit = draftURL.startsWith("http", ignoreCase = true) && !isSubmitting

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Submit deliverable", color = Color.White) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Submit your content", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Paste a link to your draft (private YouTube, Drive, Dropbox, Stitch video, etc).",
                color = Color.Gray,
                fontSize = 13.sp
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                if (draftURL.isEmpty()) {
                    Text("https://…", color = Color.Gray.copy(alpha = 0.6f), fontSize = 14.sp)
                }
                BasicTextField(
                    value = draftURL,
                    onValueChange = { draftURL = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush = SolidColor(Color.White)
                )
            }

            Text("Notes (optional)", color = Color.Gray, fontSize = 11.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 180.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush = SolidColor(Color.White)
                )
            }

            errorMsg?.let { Text(it, color = Color.Red, fontSize = 13.sp) }

            Button(
                onClick = {
                    scope.launch {
                        isSubmitting = true
                        errorMsg = null
                        try {
                            service.submitDeliverable(campaignID, draftURL, notes.trim())
                            onSubmitted()
                        } catch (e: Exception) {
                            errorMsg = e.message ?: "Failed to submit"
                        }
                        isSubmitting = false
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canSubmit) Color.Cyan else Color.Gray.copy(alpha = 0.3f),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (isSubmitting) "Sending…" else "Submit for review",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
