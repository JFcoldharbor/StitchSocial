/*
 * BadgePageView.kt — Android port of iOS BadgePageView.
 *
 * Layout (mirrors restructured iOS version):
 *   1. Header — earned/total counts + completion bar + rarity legend
 *   2. Filter chips by category
 *   3. EARNED hero grid — 2-col, large gold-tinted cards, pinned first
 *   4. In-progress section
 *   5. Locked catalog grouped by category, smaller dimmed cards
 *
 * Earns happen server-side via onUserStatsChanged; this screen is read-
 * only with pin/markSeen mutations.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.foundation.BadgeCatalog
import com.stitchsocial.club.foundation.BadgeCategoryV2
import com.stitchsocial.club.foundation.BadgeDefinition
import com.stitchsocial.club.foundation.BadgeProgress
import com.stitchsocial.club.foundation.BadgeRarity
import com.stitchsocial.club.foundation.EarnedBadge
import com.stitchsocial.club.services.BadgeService
import com.stitchsocial.club.services.RealUserStats
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────
// BadgeArtwork — emoji-on-colored-circle
// ─────────────────────────────────────────────

@Composable
fun BadgeArtwork(definition: BadgeDefinition, size: Int) {
    val sz = size.dp
    Box(
        modifier = Modifier
            .size(sz)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        definition.rarity.uiColor.copy(alpha = 0.32f),
                        definition.category.uiAccent.copy(alpha = 0.18f),
                        Color.Transparent
                    )
                )
            )
            .border(2.dp, definition.rarity.uiColor.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            definition.emoji,
            fontSize = (size * 0.55).sp,
            color = Color.White
        )
    }
}

// ─────────────────────────────────────────────
// Page
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgePageView(
    userID: String,
    isOwner: Boolean,
    stats: RealUserStats,
    xp: Int,
    tierRaw: String,
    onDismiss: () -> Unit
) {
    val service = remember { BadgeService.shared }
    val earnedByUser by service.earnedByUser.collectAsState()
    val earned = earnedByUser[userID].orEmpty()
    val earnedIDs = remember(earned) { earned.map { it.id }.toSet() }
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf<BadgeDefinition?>(null) }
    var filterCat by remember { mutableStateOf<BadgeCategoryV2?>(null) }

    DisposableEffect(userID) {
        service.listenForBadges(userID)
        onDispose { if (!isOwner) service.stopListening(userID) }
    }

    val totalCatalog = BadgeCatalog.all.size
    val inProgress: List<BadgeProgress> = remember(earned, stats, xp, tierRaw, filterCat) {
        service.badgeProgress(userID, stats, xp, tierRaw)
            .filter { filterCat == null || it.definition.category == filterCat }
            .take(12)
    }

    // Earned pairs sorted: pinned first, then earnedAt desc.
    val earnedPairs: List<Pair<BadgeDefinition, EarnedBadge>> = remember(earned, filterCat) {
        val byID = earned.associateBy { it.id }
        BadgeCatalog.all
            .mapNotNull { def -> byID[def.id]?.let { def to it } }
            .filter { (def, _) -> filterCat == null || def.category == filterCat }
            .sortedWith(compareByDescending<Pair<BadgeDefinition, EarnedBadge>> { it.second.isPinned }
                .thenByDescending { it.second.earnedAt })
    }

    val lockedSections: List<Pair<BadgeCategoryV2, List<BadgeDefinition>>> =
        remember(earnedIDs, filterCat) {
            val cats = filterCat?.let { listOf(it) } ?: BadgeCategoryV2.values().toList()
            cats.mapNotNull { cat ->
                val items = BadgeCatalog.all.filter { it.category == cat && it.id !in earnedIDs }
                if (items.isEmpty()) null else cat to items
            }
        }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF07070B))) {

        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 50.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.Cyan)
            }
            Text(
                "Badges", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = Color.White, modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(48.dp))
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            HeaderBlock(earnedCount = earned.size, totalCount = totalCatalog)

            FilterBar(
                selected = filterCat,
                onSelect = { filterCat = it }
            )

            // EARNED hero grid — manual chunked rows so we don't nest a
            // LazyVerticalGrid inside a vertically scrollable Column
            // (Compose throws "infinity maximum height" for that combo).
            if (earnedPairs.isNotEmpty()) {
                SectionHeader(label = "EARNED", count = earnedPairs.size, accent = Color(0xFFFBBF24))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    earnedPairs.chunked(2).forEach { rowPairs ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowPairs.forEach { (def, eb) ->
                                Box(modifier = Modifier.weight(1f)) {
                                    EarnedHeroCard(def = def, eb = eb) {
                                        selected = def
                                        if (isOwner && eb.isNew) {
                                            scope.launch { service.markSeen(userID, def.id) }
                                        }
                                    }
                                }
                            }
                            // Pad the last row when count is odd so cards
                            // stay column-aligned at half-width.
                            if (rowPairs.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // In progress
            if (inProgress.isNotEmpty()) {
                SectionHeader(label = "IN PROGRESS", count = inProgress.size, accent = Color(0xFF60A5FA))
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(inProgress, key = { it.id }) { progress ->
                        InProgressCard(progress = progress) { selected = progress.definition }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // Locked by category
            lockedSections.forEach { (cat, items) ->
                SectionHeader(label = cat.displayName.uppercase(), count = items.size, accent = cat.uiAccent)
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(items, key = { it.id }) { def ->
                        LockedCard(def = def) { selected = def }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            if (earnedPairs.isEmpty() && lockedSections.isEmpty() && inProgress.isEmpty()) {
                EmptyBadgesView()
            }

            Spacer(Modifier.height(80.dp))
        }
    }

    // Detail sheet
    selected?.let { def ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = sheetState,
            containerColor = Color(0xFF0E0E12)
        ) {
            BadgeDetailSheet(
                def = def,
                isEarned = def.id in earnedIDs,
                isPinned = earned.firstOrNull { it.id == def.id }?.isPinned ?: false,
                isOwner = isOwner,
                onTogglePin = {
                    scope.launch { service.togglePin(userID, def.id) }
                }
            )
        }
    }
}

// ─────────────────────────────────────────────
// Header / Filter
// ─────────────────────────────────────────────

@Composable
private fun HeaderBlock(earnedCount: Int, totalCount: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatPill(value = "$earnedCount", label = "Earned", accent = Color(0xFFFBBF24))
            StatPill(value = "$totalCount", label = "Total", accent = Color.White.copy(alpha = 0.3f))
        }
        // Completion bar
        LinearProgressIndicator(
            progress = {
                if (totalCount == 0) 0f
                else (earnedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Color(0xFFFBBF24),
            trackColor = Color.White.copy(alpha = 0.06f),
            strokeCap = ProgressIndicatorDefaults.LinearStrokeCap
        )
        // Rarity legend
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BadgeRarity.values().forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(modifier = Modifier.size(6.dp).background(r.uiColor, CircleShape))
                    Text(r.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.30f))
                }
            }
        }
    }
}

@Composable
private fun StatPill(value: String, label: String, accent: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.45f))
    }
}

@Composable
private fun FilterBar(selected: BadgeCategoryV2?, onSelect: (BadgeCategoryV2?) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        item {
            FilterChip(label = "All", active = selected == null, accent = Color.White) { onSelect(null) }
        }
        items(BadgeCategoryV2.values()) { cat ->
            FilterChip(label = cat.displayName, active = selected == cat, accent = cat.uiAccent) {
                onSelect(if (selected == cat) null else cat)
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) accent else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (active) accent else Color.White.copy(alpha = 0.09f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) Color.Black else Color.White.copy(alpha = 0.48f)
        )
    }
}

@Composable
private fun SectionHeader(label: String, count: Int, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = accent, letterSpacing = 1.6.sp)
        Text("$count", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color.White.copy(alpha = 0.30f))
        Box(modifier = Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.05f)))
    }
}

// ─────────────────────────────────────────────
// Cards
// ─────────────────────────────────────────────

@Composable
private fun EarnedHeroCard(def: BadgeDefinition, eb: EarnedBadge, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(192.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(listOf(
                    Color(0xFFFBBF24).copy(alpha = 0.06f),
                    Color(0xFF0A0A10)
                ))
            )
            .border(1.8.dp, def.rarity.uiColor.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
            .clickable { onTap() }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (eb.isPinned) {
                    Icon(
                        Icons.Default.PushPin, null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(12.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (eb.isNew) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFFB923C))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text("NEW", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black, letterSpacing = 0.6.sp)
                    }
                }
            }

            BadgeArtwork(definition = def, size = 78)

            Text(
                def.name.replace(Regex(" — .*"), ""),
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2,
                textAlign = TextAlign.Center
            )

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(def.rarity.uiColor.copy(alpha = 0.14f))
                    .border(1.dp, def.rarity.uiColor.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.size(5.dp).background(def.category.uiAccent, CircleShape))
                Text(def.rarity.label.uppercase(), fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold, color = def.rarity.uiColor, letterSpacing = 0.9.sp)
            }
        }
    }
}

@Composable
private fun LockedCard(def: BadgeDefinition, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .width(130.dp)
            .height(160.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF08080C))
            .border(1.5.dp, def.rarity.uiColor.copy(alpha = def.rarity.ringOpacity), RoundedCornerShape(20.dp))
            .clickable { onTap() }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.size(5.dp).background(def.category.uiAccent, CircleShape).align(Alignment.Start))
            Box(modifier = Modifier.alpha(0.35f), contentAlignment = Alignment.Center) {
                BadgeArtwork(definition = def, size = 60)
            }
            Text(
                def.name.replace(Regex(" — .*"), ""),
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 2,
                textAlign = TextAlign.Center
            )
            Text(def.rarity.label.uppercase(), fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold, color = def.rarity.uiColor.copy(alpha = 0.7f), letterSpacing = 0.9.sp)
        }
    }
}

@Composable
private fun InProgressCard(progress: BadgeProgress, onTap: () -> Unit) {
    val def = progress.definition
    Box(
        modifier = Modifier
            .width(220.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0C0C12))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable { onTap() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.alpha(0.5f)) { BadgeArtwork(definition = def, size = 40) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(def.name.replace(Regex(" — .*"), ""), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.9f), maxLines = 1)
                Text("${progress.currentValue} / ${progress.targetValue}", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
                LinearProgressIndicator(
                    progress = { progress.progressFraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = def.rarity.uiColor,
                    trackColor = Color.White.copy(alpha = 0.06f),
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap
                )
            }
        }
    }
}

@Composable
private fun EmptyBadgesView() {
    Box(modifier = Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🏆", fontSize = 44.sp)
            Text("No badges yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.7f))
            Text("Stick around — they show up as you tap, post, and tip.", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BadgeDetailSheet(
    def: BadgeDefinition,
    isEarned: Boolean,
    isPinned: Boolean,
    isOwner: Boolean,
    onTogglePin: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.alpha(if (isEarned) 1f else 0.4f)) {
            BadgeArtwork(definition = def, size = 110)
        }
        Text(def.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        Text(def.description, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Rarity pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(def.rarity.uiColor.copy(alpha = 0.15f))
                    .border(1.dp, def.rarity.uiColor.copy(alpha = 0.4f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(def.rarity.label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = def.rarity.uiColor, letterSpacing = 0.9.sp)
            }
            // Category pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(def.category.uiAccent.copy(alpha = 0.15f))
                    .border(1.dp, def.category.uiAccent.copy(alpha = 0.4f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(def.category.displayName.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = def.category.uiAccent, letterSpacing = 0.9.sp)
            }
        }
        if (isOwner && isEarned) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isPinned) Color(0xFFFBBF24) else Color.White.copy(alpha = 0.08f))
                    .clickable { onTogglePin() }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    if (isPinned) "Unpin from profile" else "Pin to profile",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (isPinned) Color.Black else Color.White
                )
            }
        }
    }
}
