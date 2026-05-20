package com.stitchsocial.club.Views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * CampaignAnalyticsView — Mode A drill-down for a brand campaign.
 *
 * Mirror of the iOS CampaignAnalyticsView. Reads campaign_stats/{campaignID}
 * via the `getCampaignAnalytics` Cloud Function (auth-gated to campaign
 * owner). Renders: funding row, match funnel, performance section, top
 * creators leaderboard.
 *
 * No Android entry point is wired yet — call this from a future
 * BusinessAdDashboardView equivalent (or stub it from Settings) once that
 * exists. The Composable is parameterized to be reusable from anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampaignAnalyticsView(
    campaignID: String,
    campaignTitle: String,
    onDismiss: () -> Unit
) {
    var analytics by remember { mutableStateOf<CampaignAnalytics?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        isLoading = true
        errorMessage = null
        try {
            analytics = loadCampaignAnalytics(campaignID)
        } catch (e: Exception) {
            errorMessage = e.message ?: "Couldn't load analytics"
        }
        isLoading = false
    }

    LaunchedEffect(campaignID) { reload() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Analytics", color = Color.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onDismiss) { Text("Done", color = Color.White) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        val refreshScope = rememberCoroutineScope()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
                errorMessage != null -> ErrorState(message = errorMessage!!, onRetry = {
                    refreshScope.launch { reload() }
                })
                analytics != null -> Content(
                    analytics = analytics!!,
                    fallbackTitle = campaignTitle,
                    onRefresh = { refreshScope.launch { reload() } }
                )
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(36.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text("Couldn't load analytics", color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(message, color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) { Text("Try again") }
    }
}

@Composable
private fun Content(
    analytics: CampaignAnalytics,
    fallbackTitle: String,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Header(analytics, fallbackTitle, onRefresh)
        FundingRow(analytics)
        FunnelSection(analytics.stats)
        PerformanceSection(analytics.stats)
        if (analytics.topCreators.isNotEmpty()) {
            TopCreatorsSection(analytics.topCreators)
        }
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun Header(a: CampaignAnalytics, fallback: String, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                a.campaign.title ?: fallback,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    a.campaign.status?.replaceFirstChar { it.uppercase() } ?: "Active",
                    color = Color.Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.1f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
                Text("Avg match: ${a.stats.averageMatchScore}%", color = Color.Gray, fontSize = 12.sp)
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, "Refresh", tint = Color.Cyan)
        }
    }
}

@Composable
private fun FundingRow(a: CampaignAnalytics) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard("Budget", budgetRange(a.campaign), Color(0xFFFFD60A), Icons.Default.AttachMoney, Modifier.weight(1f))
        MetricCard("Spent", "$${formatMoney(a.stats.totalSpend)}", Color(0xFF1E8E3E), Icons.Default.CreditCard, Modifier.weight(1f))
        MetricCard(
            "CPM",
            if (a.stats.cpmAchieved > 0) "$${String.format("%.2f", a.stats.cpmAchieved)}" else "—",
            Color.Cyan,
            Icons.Default.TrendingUp,
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun FunnelSection(s: CampaignAnalyticsStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Match funnel", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        FunnelRow("Matched", s.matchedCount, s.matchedCount, Color(0xFF0A84FF))
        FunnelRow("Viewed", s.viewedCount, s.matchedCount, Color(0xFFBF5AF2))
        FunnelRow("Accepted", s.acceptedCount, s.matchedCount, Color(0xFF1E8E3E))
        FunnelRow("Declined", s.declinedCount, s.matchedCount, Color.Red.copy(alpha = 0.7f))
        FunnelRow("Expired", s.expiredCount, s.matchedCount, Color.Gray)
        if (s.matchedCount > 0) {
            Text(
                "Acceptance rate: ${(s.acceptanceRate * 100).toInt()}%",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun FunnelRow(label: String, count: Int, total: Int, color: Color) {
    val pct = if (total > 0) count.toFloat() / total.toFloat() else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(80.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = pct.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
        Text(
            count.toString(),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun PerformanceSection(s: CampaignAnalyticsStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Performance", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                "Impressions",
                formatCount(s.totalImpressions),
                Color.White,
                Icons.Default.Visibility,
                Modifier.weight(1f)
            )
            MetricCard(
                "Active partners",
                s.activePartnerships.toString(),
                Color(0xFFFF9F0A),
                Icons.Default.Group,
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TopCreatorsSection(creators: List<CampaignCreatorBreakdown>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Top creators by impressions", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.04f))
        ) {
            creators.take(20).forEachIndexed { index, c ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${index + 1}",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(22.dp)
                    )
                    Text(
                        c.creatorID,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        formatCount(c.impressions ?: 0),
                        color = Color.Cyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (index < creators.size - 1) {
                    Divider(color = Color.White.copy(alpha = 0.05f))
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = Color.Gray, modifier = Modifier.size(11.dp))
            Text(label, color = Color.Gray, fontSize = 10.sp)
        }
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

// ============================ Helpers ============================

private fun budgetRange(c: CampaignAnalyticsSummary): String {
    val lo = c.budgetMin ?: 0.0
    val hi = c.budgetMax ?: 0.0
    return "$${formatMoney(lo)}–$${formatMoney(hi)}"
}

private fun formatMoney(v: Double): String =
    if (v >= 1000) String.format("%.1fK", v / 1000) else String.format("%.0f", v)

private fun formatCount(v: Int): String = when {
    v >= 1_000_000 -> String.format("%.1fM", v / 1_000_000.0)
    v >= 1000 -> String.format("%.1fK", v / 1000.0)
    else -> v.toString()
}

// ============================ Service call ============================

private suspend fun loadCampaignAnalytics(campaignID: String): CampaignAnalytics {
    val functions = FirebaseFunctions.getInstance("us-central1")
    val result = functions.getHttpsCallable("getCampaignAnalytics")
        .call(mapOf("campaignID" to campaignID))
        .await()

    @Suppress("UNCHECKED_CAST")
    val raw = result.data as? Map<String, Any?>
        ?: error("Empty analytics response")

    val campaignMap = raw["campaign"] as? Map<String, Any?> ?: emptyMap()
    val statsMap = raw["stats"] as? Map<String, Any?> ?: emptyMap()
    val topMap = raw["topCreators"] as? List<Map<String, Any?>> ?: emptyList()

    return CampaignAnalytics(
        success = raw["success"] as? Boolean ?: true,
        campaign = CampaignAnalyticsSummary(
            id = campaignMap["id"] as? String ?: campaignID,
            title = campaignMap["title"] as? String,
            status = campaignMap["status"] as? String,
            budgetMin = (campaignMap["budgetMin"] as? Number)?.toDouble(),
            budgetMax = (campaignMap["budgetMax"] as? Number)?.toDouble(),
            cpmRate = (campaignMap["cpmRate"] as? Number)?.toDouble()
        ),
        stats = CampaignAnalyticsStats(
            matchedCount = (statsMap["matchedCount"] as? Number)?.toInt() ?: 0,
            viewedCount = (statsMap["viewedCount"] as? Number)?.toInt() ?: 0,
            acceptedCount = (statsMap["acceptedCount"] as? Number)?.toInt() ?: 0,
            declinedCount = (statsMap["declinedCount"] as? Number)?.toInt() ?: 0,
            expiredCount = (statsMap["expiredCount"] as? Number)?.toInt() ?: 0,
            activePartnerships = (statsMap["activePartnerships"] as? Number)?.toInt() ?: 0,
            totalImpressions = (statsMap["totalImpressions"] as? Number)?.toInt() ?: 0,
            totalSpend = (statsMap["totalSpend"] as? Number)?.toDouble() ?: 0.0,
            averageMatchScore = (statsMap["averageMatchScore"] as? Number)?.toInt() ?: 0,
            cpmAchieved = (statsMap["cpmAchieved"] as? Number)?.toDouble() ?: 0.0,
            acceptanceRate = (statsMap["acceptanceRate"] as? Number)?.toDouble() ?: 0.0
        ),
        topCreators = topMap.map { m ->
            CampaignCreatorBreakdown(
                creatorID = m["creatorID"] as? String ?: "",
                impressions = (m["impressions"] as? Number)?.toInt(),
                earnings = (m["earnings"] as? Number)?.toDouble()
            )
        }
    )
}

// ============================ Models ============================

data class CampaignAnalytics(
    val success: Boolean,
    val campaign: CampaignAnalyticsSummary,
    val stats: CampaignAnalyticsStats,
    val topCreators: List<CampaignCreatorBreakdown>
)

data class CampaignAnalyticsSummary(
    val id: String,
    val title: String?,
    val status: String?,
    val budgetMin: Double?,
    val budgetMax: Double?,
    val cpmRate: Double?
)

data class CampaignAnalyticsStats(
    val matchedCount: Int,
    val viewedCount: Int,
    val acceptedCount: Int,
    val declinedCount: Int,
    val expiredCount: Int,
    val activePartnerships: Int,
    val totalImpressions: Int,
    val totalSpend: Double,
    val averageMatchScore: Int,
    val cpmAchieved: Double,
    val acceptanceRate: Double
)

data class CampaignCreatorBreakdown(
    val creatorID: String,
    val impressions: Int?,
    val earnings: Double?
)
