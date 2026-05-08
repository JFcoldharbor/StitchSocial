/*
 * AdOpportunitiesView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Functional shell mirroring Swift AdOpportunitiesView. Shows the same
 * locked / rev-share-banner / tabbed-empty-states layout. Campaign list
 * + accept/decline flow are TODO until AdOpportunity / AdPartnership /
 * CampaignDetailView are ported.
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
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.services.AdRevenueShare

@Composable
fun AdOpportunitiesView(
    user: BasicUserInfo,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Available", "My Ads", "Earnings")
    val canAccess = AdRevenueShare.canAccessAds(user.tier)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        Column(modifier = Modifier.fillMaxSize()) {

            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 40.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Text(
                    "Ad Opportunities", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(48.dp))
            }

            if (!canAccess) {
                LockedView(currentTier = user.tier.displayName)
            } else {
                // Rev share banner
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Your rev share:", fontSize = 13.sp, color = Color.Gray)
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF30D158).copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "${(AdRevenueShare.creatorShare(user.tier) * 100).toInt()}%",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(user.tier.displayName, fontSize = 11.sp, color = Color(0xFFBF5AF2))
                }

                // Tab bar
                Row(modifier = Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { idx, label ->
                        val sel = idx == tab
                        Column(
                            modifier = Modifier.weight(1f).clickable { tab = idx }.padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                label, fontSize = 13.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (sel) Color.White else Color.Gray
                            )
                            Box(
                                modifier = Modifier.fillMaxWidth().height(2.dp)
                                    .background(if (sel) Color.Cyan else Color.Transparent)
                            )
                        }
                    }
                }

                // Tab content — placeholder empty states until campaign data model is ported.
                when (tab) {
                    0 -> EmptyTab(
                        icon = Icons.Default.Campaign,
                        title = "No campaigns yet",
                        message = "When brands match your audience, opportunities will appear here."
                    )
                    1 -> EmptyTab(
                        icon = Icons.Default.Handshake,
                        title = "No active ads",
                        message = "Accepted partnerships and active campaigns show here."
                    )
                    else -> EmptyTab(
                        icon = Icons.Default.AttachMoney,
                        title = "No earnings yet",
                        message = "Your ad-revenue history and pending payouts will appear here once a campaign runs."
                    )
                }
            }
        }
    }
}

@Composable
private fun LockedView(currentTier: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Lock, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("Reach Influencer Tier", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            "Ad opportunities are available for creators at Influencer tier (100K+ followers) and above. Keep growing!",
            fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Current:", fontSize = 13.sp, color = Color.White.copy(alpha = 0.4f))
            Text(currentTier, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.Cyan)
        }
    }
}

@Composable
private fun EmptyTab(icon: ImageVector, title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = Color.Gray, modifier = Modifier.size(50.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(message, fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center)
    }
}
