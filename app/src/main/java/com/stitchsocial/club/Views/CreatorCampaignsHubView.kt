package com.stitchsocial.club.views

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stitchsocial.club.services.CreatorCampaign
import com.stitchsocial.club.services.CreatorCampaignService
import kotlinx.coroutines.launch

/**
 * CreatorCampaignsHubView — Mode B marketplace entry point.
 *
 * Role-aware:
 *   - Brand accounts: tabs for "My Campaigns" + "Drafts", + button to create
 *   - Creator accounts: tabs for "Browse" + "My Applications"
 *
 * Tapping a row opens CreatorCampaignDetailView (full-screen Dialog).
 * Brand + button opens CreateCreatorCampaignView (full-screen Dialog).
 *
 * Stripe Connect onboarding is intentionally not surfaced on Android —
 * Android creators set up payouts on web at stitchsocial.me/payouts.
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
    var showingCreate by remember { mutableStateOf(false) }
    var selectedCampaign by remember { mutableStateOf<CreatorCampaign?>(null) }
    val scope = rememberCoroutineScope()

    val openCampaigns by service.openCampaigns.collectAsState()
    val myApplications by service.myApplications.collectAsState()
    val brandCampaigns by service.brandCampaigns.collectAsState()
    val isLoading by service.isLoading.collectAsState()

    val tabs = if (isBrandAccount) listOf("My Campaigns", "Drafts") else listOf("Browse", "My Applications")

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
        isBrandAccount && selectedTab == 0 -> brandCampaigns.filter { it.status != "draft" }
        isBrandAccount && selectedTab == 1 -> brandCampaigns.filter { it.status == "draft" }
        !isBrandAccount && selectedTab == 0 -> openCampaigns
        else -> myApplications
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (isBrandAccount) "Campaigns" else "Marketplace",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (isBrandAccount) {
                        IconButton(onClick = { showingCreate = true }) {
                            Icon(Icons.Default.AddCircle, "New campaign", tint = Color.Cyan)
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
                            color = if (selectedTab == i) Color.White else Color.Gray,
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
            Divider(color = Color.White.copy(alpha = 0.1f))

            // Content
            when {
                isLoading && currentList.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
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

    if (showingCreate) {
        Dialog(
            onDismissRequest = { showingCreate = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            CreateCreatorCampaignView(
                brandID = currentUserID,
                brandName = brandName,
                brandLogoURL = brandLogoURL,
                onCreated = {
                    showingCreate = false
                    scope.launch { loadActiveTab() }
                },
                onDismiss = { showingCreate = false }
            )
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
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    campaign.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2
                )
                campaign.brandName?.let {
                    Text(it, color = Color.Gray, fontSize = 11.sp)
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
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            maxLines = 2
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(campaign.status.replaceFirstChar { it.uppercase() }, statusColor(campaign.status))
            if (campaign.applicationsCount > 0) {
                StatusPill("${campaign.applicationsCount} applied", Color.Cyan)
            }
            if (campaign.approvedCount > 0) {
                StatusPill("${campaign.approvedCount} approved", Color(0xFF1E8E3E))
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

private fun statusColor(status: String): Color = when (status) {
    "open" -> Color(0xFF1E8E3E)
    "reviewing" -> Color(0xFFFFA000)
    "in_progress" -> Color.Cyan
    "completed" -> Color.Gray
    "cancelled" -> Color.Red
    else -> Color.White
}

@Composable
private fun EmptyState(isBrandAccount: Boolean, selectedTab: Int) {
    val msg = when {
        isBrandAccount && selectedTab == 0 -> "No campaigns yet. Tap + to post your first creator brief."
        isBrandAccount -> "No drafts."
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
            tint = Color.Gray.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(msg, color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}
