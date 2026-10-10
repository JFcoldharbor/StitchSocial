package com.stitchsocial.club.views

import com.stitchsocial.club.ui.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stitchsocial.club.AppConfig
import com.stitchsocial.club.services.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CreatorCampaignDetailView — role-aware drill-down.
 *
 * Creator view:
 *   - Brief, the brand's expectations (prominent), deadline + the delivery policy
 *   - "Apply" sheet (if no application yet) → calls applyToCreatorCampaign
 *   - Status badge once applied (pending / approved / rejected)
 *   - "Submit deliverable" sheet (if approved) → submitCreatorCampaignDeliverable
 *   - Deliverable status: submitted / accepted / auto-accepted / missed, payout
 *
 * Brand view:
 *   - Brief + billing (slots, funded, payment status; pay link if unpaid)
 *   - Applicant list with AI fit %, pitch, approve/decline
 *   - Deliverable list with "Accept content" + optional 1–5 rating and review
 *     (brand_acceptDeliverable). There is no reject and no revision request —
 *     the brand paid up front and a rating is its only quality remedy.
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
    var showingPayouts by remember { mutableStateOf(false) }

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
                title = { Text(campaign.title, color = AppTheme.colors.textPrimary, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = AppTheme.colors.textPrimary)
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
            ) { CircularProgressIndicator(color = AppTheme.colors.textPrimary) }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                BriefCard(campaign, splitLine = if (isBrandAccount) null else creatorSplitLine(campaign, myDeliverable))
                campaign.expectations?.let { ExpectationsCard(it) }
                if (isBrandAccount) {
                    BillingCard(campaign)
                } else {
                    DeadlinePolicyCard(campaign.contentDueDate)
                }
                campaign.criteria?.let { CriteriaCard(it) }

                if (isBrandAccount) {
                    BrandSections(
                        campaign = campaign,
                        applications = applications,
                        deliverables = deliverables,
                        onReload = { scope.launch { reload() } }
                    )
                } else {
                    CreatorSections(
                        application = myApplication,
                        deliverable = myDeliverable,
                        onApply = { showingApply = true },
                        onSubmit = { showingSubmit = true },
                        onSetUpPayouts = { showingPayouts = true }
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

    if (showingPayouts) {
        Dialog(
            onDismissRequest = { showingPayouts = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            PayoutsSetupView(onDismiss = { showingPayouts = false })
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
private fun BriefCard(campaign: CreatorCampaign, splitLine: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    campaign.brandName ?: "Brand",
                    color = AppTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                campaign.category?.let {
                    Text(it.replaceFirstChar { c -> c.uppercase() }, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Payout", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                Text(
                    "$${campaign.payoutDollars.toInt()}",
                    color = Color(0xFF1E8E3E),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                splitLine?.let { Text(it, color = AppTheme.colors.textSecondary, fontSize = 10.sp) }
            }
        }
        Text(campaign.brief, color = AppTheme.colors.textPrimary.copy(alpha = 0.85f), fontSize = 14.sp)
    }
}

/** What the brand expects delivered. It is the contract, so it sits high and
 *  stands out — creators are paid on delivering this, not on taste. */
@Composable
private fun ExpectationsCard(expectations: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Cyan.copy(alpha = 0.08f))
            .border(1.dp, Color.Cyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Checklist, null, tint = Color.Cyan, modifier = Modifier.size(16.dp))
            Text("What the brand expects", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Text(expectations, color = AppTheme.colors.textPrimary, fontSize = 14.sp)
    }
}

@Composable
private fun DeadlinePolicyCard(dueDate: Date?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        dueDate?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Event, null, tint = Color(0xFFFFA000), modifier = Modifier.size(16.dp))
                Text("Deliver by ${formatDate(it)}", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        Text(
            "Brands can't reject delivered work — they can rate it. If you don't deliver by the deadline, the brand is refunded and you won't be paid.",
            color = AppTheme.colors.textSecondary,
            fontSize = 12.sp
        )
    }
}

/** Brand only: what was paid and what is left. Payment itself is on the web. */
@Composable
private fun BillingCard(campaign: CreatorCampaign) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Billing", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            PaymentStatusBadge(if (campaign.isAwaitingPayment) "awaiting_payment" else campaign.paymentStatus)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            campaign.slots?.let { MetricChip("Slots", "${campaign.approvedCount}/$it hired") }
            campaign.fundedCents?.let { MetricChip("Funded", dollars(it)) }
            if (campaign.refundedCents > 0) MetricChip("Refunded", dollars(campaign.refundedCents))
        }
        if (campaign.isAwaitingPayment) {
            TextButton(
                onClick = { BrandPortal.open(context, AppConfig.URLs.BRAND) },
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Color.Cyan, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Pay on stitchsocial.me", color = Color.Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PaymentStatusBadge(status: String?) {
    val (label, color) = when (status) {
        "awaiting_payment" -> "Awaiting payment" to Color(0xFFFFA000)
        "funded" -> "Funded" to Color(0xFF1E8E3E)
        "partially_refunded" -> "Partially refunded" to Color.Cyan
        "refunded" -> "Refunded" to AppTheme.colors.textSecondary
        null -> return
        else -> status.replace("_", " ").replaceFirstChar { it.uppercase() } to AppTheme.colors.textSecondary
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

private fun dollars(cents: Int): String = "$" + String.format("%.2f", cents / 100.0)

private fun formatDate(date: Date): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)

@Composable
private fun CriteriaCard(c: CreatorCampaignCriteria) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Requirements", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
        Text("•", color = AppTheme.colors.textPrimary.copy(alpha = 0.7f), fontSize = 13.sp)
        Text(text, color = AppTheme.colors.textPrimary.copy(alpha = 0.8f), fontSize = 13.sp)
    }
}

// ============================ CREATOR SIDE ============================

@Composable
private fun CreatorSections(
    application: CreatorCampaignApplication?,
    deliverable: CreatorCampaignDeliverable?,
    onApply: () -> Unit,
    onSubmit: () -> Unit,
    onSetUpPayouts: () -> Unit
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
            // Nothing to submit once it is in, accepted or past the deadline —
            // there are no revisions.
            if (deliverable != null && deliverable.status != "awaiting") {
                DeliverableStatusCard(deliverable, onSetUpPayouts = onSetUpPayouts)
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
            .background(AppTheme.colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your application", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            StatusBadge(app.status)
        }
        app.pitch?.takeIf { it.isNotEmpty() }?.let {
            Text(it, color = AppTheme.colors.textPrimary.copy(alpha = 0.8f), fontSize = 13.sp)
        }
        app.aiFitScore?.let {
            Text("AI fit: $it%", color = Color.Cyan, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DeliverableStatusCard(d: CreatorCampaignDeliverable, onSetUpPayouts: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Deliverable", color = AppTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            StatusBadge(d.status)
        }
        d.draftURL?.let {
            Text(it, color = Color.Cyan, fontSize = 12.sp, maxLines = 2)
        }
        when (d.status) {
            "submitted" -> Text(
                d.autoAcceptAt?.let { "Waiting on the brand. Accepted automatically on ${formatDate(it)} if they don't act." }
                    ?: "Waiting on the brand to accept.",
                color = AppTheme.colors.textSecondary,
                fontSize = 12.sp
            )
            "auto_accepted" -> Text(
                "The brand didn't respond in time, so this was accepted automatically.",
                color = AppTheme.colors.textSecondary,
                fontSize = 12.sp
            )
            "missed" -> Text(
                "Not delivered by the deadline. The brand was refunded for this slot.",
                color = Color.Red.copy(alpha = 0.85f),
                fontSize = 12.sp
            )
        }
        d.payoutStatus?.let { status ->
            PayoutStatusRow(status, d.creatorNetCents)
            if (status == "held_no_connect_account") {
                TextButton(onClick = onSetUpPayouts, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        "Set up payouts to release this payment",
                        color = Color(0xFFFFA000),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
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
        else -> Triple(status, AppTheme.colors.textSecondary, Icons.Default.Circle)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (net != null && status.startsWith("paid")) {
            Text(
                "• $${String.format("%.2f", net / 100.0)}",
                color = AppTheme.colors.textPrimary.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
        }
    }
}

// ============================ BRAND SIDE ============================

@Composable
private fun BrandSections(
    campaign: CreatorCampaign,
    applications: List<CreatorCampaignApplication>,
    deliverables: List<CreatorCampaignDeliverable>,
    onReload: () -> Unit
) {
    val campaignID = campaign.id
    // Hiring needs the money in and a free slot; the server enforces both,
    // this only keeps the button from offering what it will refuse.
    val canApprove = !campaign.isAwaitingPayment &&
        (campaign.slots == null || campaign.approvedCount < campaign.slots)
    if (applications.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Applicants (${applications.size})",
                color = AppTheme.colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            applications.forEach { app ->
                ApplicantRow(app = app, campaignID = campaignID, canApprove = canApprove, onReload = onReload)
            }
        }
    }

    if (deliverables.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Deliverables (${deliverables.size})",
                color = AppTheme.colors.textPrimary,
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
            color = AppTheme.colors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 20.dp)
        )
    }
}

@Composable
private fun ApplicantRow(
    app: CreatorCampaignApplication,
    campaignID: String,
    canApprove: Boolean,
    onReload: () -> Unit
) {
    val service = remember { CreatorCampaignService.getInstance() }
    val scope = rememberCoroutineScope()
    var isDeciding by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.colors.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.creatorName ?: app.creatorID,
                    color = AppTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                app.creatorTier?.let {
                    Text(it.replaceFirstChar { c -> c.uppercase() }, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
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
            Text(it, color = AppTheme.colors.textPrimary.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 4)
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
                    enabled = !isDeciding && canApprove,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    "Decline",
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
                color = AppTheme.colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(AppTheme.colors.surfaceStrong, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
        Text(value, color = AppTheme.colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DeliverableRow(
    deliverable: CreatorCampaignDeliverable,
    campaignID: String,
    onReload: () -> Unit
) {
    var showAcceptDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.colors.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                deliverable.creatorID,
                color = AppTheme.colors.textPrimary,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            StatusBadge(deliverable.status)
        }
        deliverable.draftURL?.let { url ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Link, null, tint = Color.Cyan, modifier = Modifier.size(12.dp))
                Text(url, color = Color.Cyan, fontSize = 12.sp, maxLines = 1)
            }
        }

        if (deliverable.status == "submitted") {
            deliverable.autoAcceptAt?.let {
                Text(
                    "Accepted automatically on ${formatDate(it)} if you don't act.",
                    color = AppTheme.colors.textSecondary,
                    fontSize = 11.sp
                )
            }
            ActionButton(
                "Accept content",
                color = Color(0xFF1E8E3E),
                onClick = { showAcceptDialog = true },
                enabled = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        deliverable.payoutStatus?.let {
            Text("Payout: ${it.replace("_", " ")}", color = Color(0xFF1E8E3E), fontSize = 11.sp)
        }
    }

    if (showAcceptDialog) {
        AcceptContentDialog(
            campaignID = campaignID,
            creatorID = deliverable.creatorID,
            onAccepted = {
                showAcceptDialog = false
                onReload()
            },
            onDismiss = { showAcceptDialog = false }
        )
    }
}

/**
 * Accepting pays the creator. The rating and review are optional and are the
 * brand's only say on quality, so they live here rather than on a separate
 * screen nobody comes back to.
 */
@Composable
private fun AcceptContentDialog(
    campaignID: String,
    creatorID: String,
    onAccepted: () -> Unit,
    onDismiss: () -> Unit
) {
    val service = remember { CreatorCampaignService.getInstance() }
    val scope = rememberCoroutineScope()
    var rating by remember { mutableIntStateOf(0) }
    var review by remember { mutableStateOf("") }
    var isAccepting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isAccepting) onDismiss() },
        title = { Text("Accept content", color = AppTheme.colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Accepting releases the creator's payment. You can rate their work too (optional).",
                    color = AppTheme.colors.textSecondary,
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { star ->
                        Icon(
                            if (star <= rating) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "$star star${if (star > 1) "s" else ""}",
                            tint = if (star <= rating) Color(0xFFFFC107) else AppTheme.colors.textSecondary,
                            modifier = Modifier
                                .size(32.dp)
                                .clickable(enabled = !isAccepting) { rating = if (rating == star) 0 else star }
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .background(AppTheme.colors.surface, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    if (review.isEmpty()) {
                        Text(if (rating == 0) "Pick a rating to add a review" else "Review (optional)", color = AppTheme.colors.textSecondary.copy(alpha = 0.6f), fontSize = 14.sp)
                    }
                    BasicTextField(
                        value = review,
                        onValueChange = { review = it.take(1000) },
                        enabled = rating > 0 && !isAccepting,
                        textStyle = TextStyle(color = AppTheme.colors.textPrimary, fontSize = 14.sp),
                        modifier = Modifier.fillMaxWidth(),
                        cursorBrush = SolidColor(Color.White)
                    )
                }
                errorMsg?.let { Text(it, color = Color.Red, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        isAccepting = true
                        errorMsg = null
                        try {
                            service.acceptDeliverable(
                                campaignID = campaignID,
                                creatorID = creatorID,
                                rating = rating.takeIf { it in 1..5 },
                                review = review.takeIf { rating in 1..5 }
                            )
                            onAccepted()
                        } catch (e: Exception) {
                            errorMsg = e.message ?: "Couldn't accept"
                        }
                        isAccepting = false
                    }
                },
                enabled = !isAccepting
            ) { Text(if (isAccepting) "Accepting…" else "Accept", color = Color(0xFF1E8E3E)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isAccepting) {
                Text("Cancel", color = AppTheme.colors.textSecondary)
            }
        },
        containerColor = Color(0xFF1A1A1A)
    )
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
        "withdrawn" -> AppTheme.colors.textSecondary to "Withdrawn"
        // Deliverable states. "awaiting" = approved, nothing submitted yet.
        "awaiting" -> Color.Yellow to "Not submitted"
        "submitted" -> Color.Cyan to "Submitted"
        "accepted" -> Color(0xFF1E8E3E) to "Accepted"
        "auto_accepted" -> Color(0xFF1E8E3E) to "Auto-accepted"
        "missed" -> Color.Red to "Missed deadline"
        else -> AppTheme.colors.textSecondary to status.replaceFirstChar { it.uppercase() }
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
                title = { Text("Apply", color = AppTheme.colors.textPrimary) },
                navigationIcon = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = AppTheme.colors.textPrimary)
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
            Text("Why should they pick you?", color = AppTheme.colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Tell the brand what you'd make. Keep it short — they're reviewing many applications.",
                color = AppTheme.colors.textSecondary,
                fontSize = 13.sp
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 240.dp)
                    .background(AppTheme.colors.surface, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                if (pitch.isEmpty()) {
                    Text(
                        "e.g., I'd build a 3-stitch reaction thread reviewing the product honestly. My fitness audience loves unfiltered first-takes.",
                        color = AppTheme.colors.textSecondary.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                }
                BasicTextField(
                    value = pitch,
                    onValueChange = { pitch = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = TextStyle(color = AppTheme.colors.textPrimary, fontSize = 15.sp),
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
                title = { Text("Submit deliverable", color = AppTheme.colors.textPrimary) },
                navigationIcon = {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = AppTheme.colors.textPrimary) }
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
            Text("Submit your content", color = AppTheme.colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Paste a link to your draft (private YouTube, Drive, Dropbox, Stitch video, etc).",
                color = AppTheme.colors.textSecondary,
                fontSize = 13.sp
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colors.surface, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                if (draftURL.isEmpty()) {
                    Text("https://…", color = AppTheme.colors.textSecondary.copy(alpha = 0.6f), fontSize = 14.sp)
                }
                BasicTextField(
                    value = draftURL,
                    onValueChange = { draftURL = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = AppTheme.colors.textPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(Color.White)
                )
            }

            Text("Notes (optional)", color = AppTheme.colors.textSecondary, fontSize = 11.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 180.dp)
                    .background(AppTheme.colors.surface, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = TextStyle(color = AppTheme.colors.textPrimary, fontSize = 14.sp),
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
                    containerColor = if (canSubmit) Color.Cyan else AppTheme.colors.textSecondary.copy(alpha = 0.3f),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (isSubmitting) "Sending…" else "Submit content",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * What this creator takes home. Once accepted, the split is locked onto their
 * deal; before that it depends on their rank, so show the range. iOS parity.
 */
private fun creatorSplitLine(campaign: CreatorCampaign, deliverable: CreatorCampaignDeliverable?): String {
    val share = deliverable?.creatorShareRate
    return if (share != null) {
        "You keep ${BrandDealSplit.percent(share)} · $" + String.format("%.2f", campaign.payoutDollars * share)
    } else {
        "Creators keep ${BrandDealSplit.percent(BrandDealSplit.LOWEST_CREATOR_SHARE)}–${BrandDealSplit.percent(BrandDealSplit.HIGHEST_CREATOR_SHARE)} by rank"
    }
}
