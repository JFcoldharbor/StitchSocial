/*
 * RankSheetView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Views - Creator Rank sheet (iOS parity: RankSheetView.swift).
 * Opened by tapping the tier-colored verification check on a profile
 * (tier-as-verification — there is deliberately NO separate rank pill).
 *
 * Two modes:
 * - Top tier (Top Creator / Founder / Co-Founder): big crest + perks only.
 *   No ladder, no progress — there is nothing left to climb.
 * - Everyone else: crest, current-tier perks, clout AND followers still
 *   needed for the next tier (advancement requires both), then the full
 *   ladder with each rung's requirements. Locked rungs desaturate.
 *
 * All perk copy mirrors the iOS gates:
 *   clip length  → VideoService.getMaxRecordingDuration (copy, not a call)
 *   ad rev share → iOS AdRevenueShare.creatorShare (10/12/15/20/35/45/50/55/65/65)
 *                  NOTE: Android services/AdService.kt AdRevenueShare is stale
 *                  vs iOS — this sheet displays the iOS-canonical numbers.
 *   brand deals  → ambassador and above (AdOpportunities)
 *   crown        → UserTier.crownBadge
 * Crest art: res/drawable-nodpi/rank_1..rank_10.png (copied from iOS RankBadges).
 * Data is all passed in — no Firestore from this view.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.R
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.ui.theme.Spacing
import com.stitchsocial.club.ui.theme.color

// The ten earned rungs, in ladder order. Co-founder renders as Founder;
// business accounts never open this sheet.
private val ladder: List<UserTier> = listOf(
    UserTier.ROOKIE, UserTier.RISING, UserTier.VETERAN, UserTier.INFLUENCER,
    UserTier.AMBASSADOR, UserTier.ELITE, UserTier.PARTNER, UserTier.LEGENDARY,
    UserTier.TOP_CREATOR, UserTier.FOUNDER
)

private val crestRes = listOf(
    R.drawable.rank_1, R.drawable.rank_2, R.drawable.rank_3, R.drawable.rank_4,
    R.drawable.rank_5, R.drawable.rank_6, R.drawable.rank_7, R.drawable.rank_8,
    R.drawable.rank_9, R.drawable.rank_10
)

private val desaturated = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankSheetView(
    user: BasicUserInfo,
    followerCount: Int,
    isOwnProfile: Boolean,
    onDismiss: () -> Unit
) {
    val displayTier = if (user.tier == UserTier.CO_FOUNDER) UserTier.FOUNDER else user.tier
    val currentLevel = (ladder.indexOf(displayTier).takeIf { it >= 0 } ?: 0) + 1
    // Top Creator is the top of the earned ladder; Founder/Co-Founder sit
    // above it. All three get the badge-and-perks-only treatment.
    val isTopTier = displayTier == UserTier.TOP_CREATOR || displayTier == UserTier.FOUNDER
    // Founder is invite-only, not clout-earned.
    val nextTier = ladder.getOrNull(currentLevel)?.takeIf { it != UserTier.FOUNDER }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.xl, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            CurrentRankSection(user, displayTier, currentLevel, isTopTier, isOwnProfile)
            PerksSection(displayTier, isTopTier)
            if (!isTopTier && nextTier != null) {
                NextTierSection(displayTier, nextTier, currentLevel, user.clout, followerCount)
                LadderSection(currentLevel)
            }
        }
    }
}

// ===== CURRENT RANK =====

@Composable
private fun CurrentRankSection(
    user: BasicUserInfo,
    displayTier: UserTier,
    currentLevel: Int,
    isTopTier: Boolean,
    isOwnProfile: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            (if (isOwnProfile) "YOUR RANK" else "${user.displayName.uppercase()}'S RANK"),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp, color = Color.Gray
        )

        // Top tier gets the billboard treatment — it's the whole sheet.
        Image(
            painter = painterResource(crestRes[currentLevel - 1]),
            contentDescription = displayTier.displayName,
            modifier = Modifier.size(if (isTopTier) 220.dp else 150.dp)
        )

        Text(
            displayTier.displayName,
            fontSize = if (isTopTier) 34.sp else 28.sp,
            fontWeight = FontWeight.Bold, color = Color.White
        )

        if (isTopTier) {
            Text(
                if (displayTier == UserTier.FOUNDER) "The crest above the ladder." else "The top of the ladder.",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Gray
            )
        } else {
            Text("Level $currentLevel of 10", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
        }
    }
}

// ===== PERKS =====

private data class Perk(val icon: ImageVector, val title: String, val detail: String)

private fun perks(tier: UserTier): List<Perk> {
    val list = mutableListOf<Perk>()

    list.add(
        Perk(
            Icons.Default.Videocam,
            "${durationLabel(tier)} clips",
            if (tier == UserTier.FOUNDER) "Unlimited recording — 15 min technical cap"
            else "Max recording length per post"
        )
    )

    val share = adRevPercent(tier)
    list.add(
        Perk(
            Icons.Default.MonetizationOn,
            "$share% ad revenue share",
            if (share >= 65) "The top split — beats YouTube's 55%" else "Your cut of ad revenue on your content"
        )
    )

    if (isAmbassadorOrHigher(tier)) {
        list.add(Perk(Icons.Default.Work, "Brand deal marketplace", "Auto-matched sponsorship opportunities"))
    }

    if (tier.crownBadge != null) {
        list.add(Perk(Icons.Default.WorkspacePremium, "${tier.displayName} crown", "Crown badge on your profile"))
    }

    list.add(Perk(Icons.Default.Verified, "${tier.displayName} verification", "Tier-colored check next to your name"))

    return list
}

@Composable
private fun PerksSection(tier: UserTier, isTopTier: Boolean) {
    val items = perks(tier)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            (if (isTopTier) "${tier.displayName} Perks" else "Your Perks").uppercase(),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp, color = Color.Gray
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.05f))
        ) {
            items.forEachIndexed { i, perk ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        perk.icon, null, tint = tier.color,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(perk.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(perk.detail, fontSize = 12.sp, color = Color.Gray)
                    }
                }
                if (i < items.size - 1) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.06f))
                    )
                }
            }
        }
    }
}

// ===== NEXT TIER (clout + followers — advancement needs BOTH) =====

@Composable
private fun NextTierSection(
    displayTier: UserTier,
    next: UserTier,
    currentLevel: Int,
    clout: Int,
    followerCount: Int
) {
    val cloutTarget = next.cloutRange.first
    val cloutFloor = displayTier.cloutRange.first
    val cloutSpan = maxOf(cloutTarget - cloutFloor, 1)
    val cloutProgress = ((clout - cloutFloor).toFloat() / cloutSpan.toFloat()).coerceIn(0f, 1f)

    val followerTarget = next.requiredFollowers
    val followerFloor = displayTier.requiredFollowers
    val followerSpan = maxOf(followerTarget - followerFloor, 1)
    val followerProgress = ((followerCount - followerFloor).toFloat() / followerSpan.toFloat()).coerceIn(0f, 1f)

    val unlocksBrandDeals = isAmbassadorOrHigher(next) && !isAmbassadorOrHigher(displayTier)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(crestRes[currentLevel]), // next level crest
                contentDescription = null,
                modifier = Modifier.size(34.dp)
            )
            Text("Next: ${next.displayName}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = next.color)
            Spacer(Modifier.weight(1f))
            Text("Lv ${currentLevel + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
        }

        RequirementBar(
            label = "Clout",
            current = clout,
            target = cloutTarget,
            progress = cloutProgress,
            met = clout >= cloutTarget,
            tint = next.color
        )

        RequirementBar(
            label = "Followers",
            current = followerCount,
            target = followerTarget,
            progress = followerProgress,
            met = followerCount >= followerTarget,
            tint = next.color
        )

        Text(
            "Unlocks ${durationLabel(next)} clips · ${adRevPercent(next)}% ad revenue" +
                if (unlocksBrandDeals) " · brand deals" else "",
            fontSize = 12.sp, color = Color.Gray
        )
    }
}

@Composable
private fun RequirementBar(
    label: String,
    current: Int,
    target: Int,
    progress: Float,
    met: Boolean,
    tint: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
            Spacer(Modifier.weight(1f))
            if (met) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4ADE80), modifier = Modifier.size(14.dp))
                    Text("Met", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4ADE80))
                }
            } else {
                Text(
                    "${grouped(current)} / ${grouped(target)}",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (met) 1f else maxOf(progress, 0.02f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (met) Color(0xFF4ADE80) else tint)
            )
        }
    }
}

// ===== LADDER =====

@Composable
private fun LadderSection(currentLevel: Int) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            "THE LADDER",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp, color = Color.Gray
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.05f))
        ) {
            ladder.forEachIndexed { index, tier ->
                LadderRow(tier = tier, level = index + 1, currentLevel = currentLevel)
                if (index < ladder.size - 1) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.06f))
                    )
                }
            }
        }
    }
}

@Composable
private fun LadderRow(tier: UserTier, level: Int, currentLevel: Int) {
    val isCurrent = level == currentLevel
    val isLocked = level > currentLevel

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(crestRes[level - 1]),
            contentDescription = null,
            colorFilter = if (isLocked) desaturated else null,
            modifier = Modifier
                .size(40.dp)
                .alpha(if (isLocked) 0.45f else 1f)
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                tier.displayName,
                fontSize = 15.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isLocked) Color.Gray else Color.White
            )
            Text(requirementLabel(tier), fontSize = 11.sp, color = Color.Gray)
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                durationLabel(tier),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = if (isLocked) Color.Gray else Color.White.copy(alpha = 0.85f)
            )
            Text("${adRevPercent(tier)}% ad rev", fontSize = 11.sp, color = Color.Gray)
        }

        if (isCurrent) {
            Text(
                "YOU",
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                color = Color.Black,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(tier.color)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        } else if (isLocked) {
            Icon(
                Icons.Default.Lock, null,
                tint = Color.Gray.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// ===== COPY =====

/** Entry requirements per rung: clout + followers (both must be met). */
private fun requirementLabel(tier: UserTier): String = when (tier) {
    UserTier.FOUNDER -> "Invite only"
    UserTier.ROOKIE -> "Starting rank"
    else -> "${compact(tier.cloutRange.first)} clout · ${compact(tier.requiredFollowers)} followers"
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "${n / 1_000_000}M"
    n >= 1_000 -> {
        val k = n / 1_000.0
        if (k == k.toInt().toDouble()) "${k.toInt()}K" else String.format("%.1fK", k)
    }
    else -> "$n"
}

private fun grouped(n: Int): String = String.format("%,d", n)

/**
 * Display copy for each rung's clip length — mirrors iOS
 * VideoService.getMaxRecordingDuration (kept as copy, not a service call).
 */
private fun durationLabel(tier: UserTier): String = when (tier) {
    UserTier.ROOKIE -> "30 sec"
    UserTier.RISING -> "45 sec"
    UserTier.VETERAN -> "1 min"
    UserTier.INFLUENCER -> "1.5 min"
    UserTier.AMBASSADOR -> "2 min"
    UserTier.ELITE -> "3 min"
    UserTier.PARTNER -> "5 min"
    UserTier.LEGENDARY -> "8 min"
    UserTier.TOP_CREATOR -> "10 min"
    UserTier.FOUNDER, UserTier.CO_FOUNDER -> "15 min"
    UserTier.BUSINESS -> "1 min"
}

/**
 * Ad revenue share per tier — iOS-canonical numbers (AdRevenueShare.creatorShare).
 * NOTE: Android services/AdService.kt AdRevenueShare currently disagrees with
 * iOS (0/0/0/25/28/32/35/38/40/50); this sheet shows the iOS ladder until
 * that service is re-synced.
 */
private fun adRevPercent(tier: UserTier): Int = when (tier) {
    UserTier.ROOKIE -> 10
    UserTier.RISING -> 12
    UserTier.VETERAN -> 15
    UserTier.INFLUENCER -> 20
    UserTier.AMBASSADOR -> 35
    UserTier.ELITE -> 45
    UserTier.PARTNER -> 50
    UserTier.LEGENDARY -> 55
    UserTier.TOP_CREATOR -> 65
    UserTier.FOUNDER, UserTier.CO_FOUNDER -> 65
    UserTier.BUSINESS -> 0
}

/** Brand-deal marketplace gate — ambassador and above (iOS isAmbassadorOrHigher). */
private fun isAmbassadorOrHigher(tier: UserTier): Boolean {
    val idx = ladder.indexOf(if (tier == UserTier.CO_FOUNDER) UserTier.FOUNDER else tier)
    return idx >= ladder.indexOf(UserTier.AMBASSADOR)
}
