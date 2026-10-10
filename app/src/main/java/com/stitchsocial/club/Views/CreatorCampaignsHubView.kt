package com.stitchsocial.club.views

import com.stitchsocial.club.ui.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stitchsocial.club.AppConfig
import com.stitchsocial.club.services.BrandPortal
import com.stitchsocial.club.services.CreatorCampaign
import com.stitchsocial.club.services.CreatorCampaignService

/**
 * CreatorCampaignsHubView — Mode B marketplace entry point.
 *
 * Role-aware:
 *   - Brand accounts: tabs for "My Campaigns" + "Unpaid", + button to create
 *   - Creator accounts: tabs for "Browse" + "My Applications", and a Payouts
 *     button for Stripe Connect setup (PayoutsSetupView)
 *
 * Tapping a row opens CreatorCampaignDetailView (full-screen Dialog).
 * The brand + button opens stitchsocial.me/brand/campaigns/new: campaigns are
 * created and paid for on the web, never in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorCampaignsHubView(
    currentUserID: String,
    isBrandAccount: Boolean,
    brandName: String,
    brandLogoURL: String?,
    onDismiss: () -> Unit
) {
    val service = remember { CreatorCampaignService.getInstance() }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showingPayouts by remember { mutableStateOf(false) }
    var selectedCampaign by remember { mutableStateOf<CreatorCampaign?>(null) }
    val context = LocalContext.current

    val openCampaigns by service.openCampaigns.collectAsState()
    val myApplications by service.myApplications.collectAsState()
    val brandCampaigns by service.brandCampaigns.collectAsState()
    val isLoading by service.isLoading.collectAsState()

    val tabs = if (isBrandAccount) listOf("My Campaigns", "Unpaid") else listOf("Browse", "My Applications")

    suspend fun loadActiveTab() {
        if (isBrandAccount) {
            service.fetchBrandCampaigns(currentUserID)
        } else {
            if (selectedTab == 0) service.fetchOpenCampaigns()
            else service.fetchMyApplicationCampaigns(currentUserID)
        }
    }

    LaunchedEffect(selectedTab) { loadActiveTab() }

    val currentList: List<CreatorCampaign> = when {
        isBrandAccount && selectedTab == 0 -> brandCampaigns.filter { !it.isAwaitingPayment && it.status != "draft" }
        isBrandAccount && selectedTab == 1 -> brandCampaigns.filter { it.isAwaitingPayment }
        !isBrandAccount && selectedTab == 0 -> openCampaigns
        else -> myApplications
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (isBrandAccount) "Campaigns" else "Marketplace",
                        color = AppTheme.colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = AppTheme.colors.textPrimary)
                    }
                },
                actions = {
                    if (isBrandAccount) {
                        IconButton(onClick = { BrandPortal.open(context, AppConfig.URLs.BRAND_NEW_CAMPAIGN) }) {
                            Icon(Icons.Default.AddCircle, "New campaign", tint = Color.Cyan)
                        }
                    } else {
                        IconButton(onClick = { showingPayouts = true }) {
                            Icon(Icons.Default.AccountBalance, "Payouts", tint = AppTheme.colors.textPrimary)
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Black
                )
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab bar
            Row(modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { i, label ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = i }
                            .padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            label,
                            color = if (selectedTab == i) Color.White else AppTheme.colors.textSecondary,
                            fontSize = 14.sp,
                            fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(if (selectedTab == i) Color.Cyan else Color.Transparent)
                        )
                    }
                }
            }
            Divider(color = AppTheme.colors.hairline)

            // Content
            when {
                isLoading && currentList.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppTheme.colors.textPrimary)
                    }
                }
                currentList.isEmpty() -> EmptyState(isBrandAccount, selectedTab)
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(currentList, key = { it.id }) { campaign ->
                            CampaignRowCard(
                                campaign = campaign,
                                onClick = { selectedCampaign = campaign }
                            )
                        }
                    }
                }
            }
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

    selectedCampaign?.let { campaign ->
        Dialog(
            onDismissRequest = { selectedCampaign = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            CreatorCampaignDetailView(
                campaign = campaign,
                currentUserID = currentUserID,
                isBrandAccount = isBrandAccount,
                onDismiss = { selectedCampaign = null }
            )
        }
    }
}

@Composable
private fun CampaignRowCard(campaign: CreatorCampaign, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    campaign.title,
                    color = AppTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2
                )
                campaign.brandName?.let {
                    Text(it, color = AppTheme.colors.textSecondary, fontSize = 11.sp)
                }
            }
            Text(
                "$${campaign.payoutDollars.toInt()}",
                color = Color(0xFF1E8E3E),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }

        Text(
            campaign.brief,
            color = AppTheme.colors.textPrimary.copy(alpha = 0.7f),
            fontSize = 12.sp,
            maxLines = 2
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(statusLabel(campaign.status), statusColor(campaign.status))
            if (campaign.applicationsCount > 0) {
                StatusPill("${campaign.applicationsCount} applied", Color.Cyan)
            }
            if (campaign.approvedCount > 0) {
                StatusPill(
                    campaign.slots?.let { "${campaign.approvedCount}/$it hired" } ?: "${campaign.approvedCount} approved",
                    Color(0xFF1E8E3E)
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

private fun statusLabel(status: String): String = when (status) {
    "awaiting_payment" -> "Awaiting payment"
    "in_progress" -> "In progress"
    else -> status.replaceFirstChar { it.uppercase() }
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    "awaiting_payment" -> Color(0xFFFFA000)
    "open" -> Color(0xFF1E8E3E)
    "reviewing" -> Color(0xFFFFA000)
    "in_progress" -> Color.Cyan
    "completed" -> AppTheme.colors.textSecondary
    "cancelled" -> Color.Red
    else -> Color.White
}

@Composable
private fun EmptyState(isBrandAccount: Boolean, selectedTab: Int) {
    val msg = when {
        isBrandAccount && selectedTab == 0 -> "No campaigns yet. Tap + to post your first creator brief on stitchsocial.me."
        isBrandAccount -> "Nothing waiting on payment."
        selectedTab == 0 -> "No open campaigns right now. Check back soon."
        else -> "You haven't applied to anything yet."
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            if (isBrandAccount) Icons.Default.Campaign else Icons.Default.Search,
            null,
            tint = AppTheme.colors.textSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(msg, color = AppTheme.colors.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}
