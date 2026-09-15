/*
 * DashView.kt - HOME'S SECOND SURFACE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views — the other half of the FRIENDS / DASH toggle.
 *
 * Everything here already existed as a service with nowhere to be seen: the
 * streak, the next event you said you'd go to, the communities you're in, who's
 * live. Dash is a reading of state the app already keeps, not a new system — no
 * section invents data, and a section whose data isn't there does not render at
 * all rather than showing an empty frame.
 *
 * FRIENDS is untouched. The feed, its overlay and the action row are exactly as
 * they ship.
 *
 * Full parity with the iOS surface, badge strip included. Both of its
 * destinations exist here — BadgePageView and RankSheetView — and are the same
 * ones ProfileView opens, so a badge page reached from Dash and one reached from
 * a profile are the same screen.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import com.stitchsocial.club.R
import com.stitchsocial.club.community.CommunityListItem
import com.stitchsocial.club.community.CommunityService
import com.stitchsocial.club.events.EventsViewModel
import com.stitchsocial.club.events.StitchEventEntity
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.BadgeCatalog
import com.stitchsocial.club.foundation.EarnedBadge
import com.stitchsocial.club.services.BadgeService
import com.stitchsocial.club.services.SubscriptionService
import com.stitchsocial.club.services.StreakService
import com.stitchsocial.club.services.UserService
import com.stitchsocial.club.ui.theme.StitchColors
import com.stitchsocial.club.ui.theme.color
import com.stitchsocial.club.services.HypeCoinCoordinator
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext

/**
 * The two accents this surface borrows from Discovery. They're private there, so
 * they're named once here rather than reached for across files — and named, so a
 * third copy is obvious the next time someone needs them.
 */
object DashPalette {
    /** Discovery's section-action cyan. */
    val cyan = Color(0xFF22D3EE)
    /** The selected-tab underline; the same value as the category rail's accent. */
    val railAccent = Color(0xFFC9B6E8)
    /** Read out of the hype coin artwork itself, so the chip behind the coin is
     *  the coin's own gold rather than a guess. */
    val coinGold = Color(0xFFCC8800)
}

@Composable
fun DashView(
    userID: String,
    /** Opens the recorder. Passed down rather than broadcast — posting is what
     *  keeps a streak alive, so the streak tile is the one thing here that leads
     *  straight to it. */
    onCreate: () -> Unit = {},
    /**
     * "All events" and "See all". Null means the caller has nowhere to send
     * them, and the section header then renders no action at all rather than a
     * link that does nothing.
     */
    onOpenEvents: (() -> Unit)? = null,
    onOpenCommunities: (() -> Unit)? = null,
    onOpenEvent: ((StitchEventEntity) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val eventsVM: EventsViewModel = viewModel()
    val streak = StreakService.shared

    val streakDays by streak.current.collectAsState()
    val liveEvents by eventsVM.liveEvents.collectAsState()
    val upcomingEvents by eventsVM.upcomingEvents.collectAsState()

    var communities by remember { mutableStateOf<List<CommunityListItem>>(emptyList()) }
    var suggested by remember { mutableStateOf<List<CommunityListItem>>(emptyList()) }
    var user by remember { mutableStateOf<BasicUserInfo?>(null) }
    var subscriberCount by remember { mutableStateOf(0) }

    var showingRank by remember { mutableStateOf(false) }
    var showingBadges by remember { mutableStateOf(false) }

    // The service keeps a live listener, so a badge awarded while Dash is open
    // appears without a refresh.
    val badgeService = remember { BadgeService.shared }
    val earnedByUser by badgeService.earnedByUser.collectAsState()

    /**
     * Pinned first, then newest. Pinned is the creator saying "this is the one I
     * want seen", and on a strip that only shows a handful that intent has to win
     * over recency.
     */
    val badges = remember(earnedByUser, userID) {
        earnedByUser[userID].orEmpty().sortedWith(
            compareByDescending<EarnedBadge> { it.isPinned }.thenByDescending { it.earnedAt }
        )
    }

    /**
     * The community a card opened, and whether it was opened to watch. Intent
     * rides IN the item rather than beside it in a second flag — a flag plus a
     * stashed id that must agree is how you end up opening whatever you opened
     * last.
     */
    data class CommunityRoute(val item: CommunityListItem, val joinLive: Boolean)
    var openCommunity by remember { mutableStateOf<CommunityRoute?>(null) }

    /** The next thing on the calendar: live first, then the soonest upcoming. */
    val nextEvent: StitchEventEntity? = liveEvents.firstOrNull()
        ?: upcomingEvents.minByOrNull { it.doorsAt }

    val liveCommunities = communities.filter { it.isCreatorLive }

    LaunchedEffect(userID) {
        streak.load()
        eventsVM.load()

        runCatching { CommunityService.shared.fetchMyCommunities(userID) }
            .getOrNull()
            ?.let { list -> communities = list.sortedByDescending { it.lastActivityAt } }

        // Only when there's nothing of your own to show. fetchAllCommunities is
        // already ordered by memberCount and cached, so the busiest rooms come
        // first and this costs one read.
        if (communities.isEmpty()) {
            runCatching { CommunityService.shared.fetchAllCommunities() }
                .getOrNull()
                ?.let { all -> suggested = all.take(10) }
        }

        runCatching { UserService(context).getUserProfile(userID) }
            .getOrNull()
            ?.let { user = it }

        badgeService.listenForBadges(userID)

        // Only shown once it means something — see the header.
        runCatching { SubscriptionService.shared.fetchCreatorPlan(userID) }
            .getOrNull()
            ?.let { subscriberCount = it.subscriberCount }
    }

    val tier = user?.tier ?: UserTier.ROOKIE

    Box(
        modifier = modifier
            .fillMaxSize()
            // Flat black is what made this read as unfinished — every card on the
            // same dead ground. A single wash of brand colour at the top, falling
            // to black well before the fold, gives the surface a direction to
            // start from. Anchored to the top because that's where the greeting
            // is: the light is behind the person.
            .background(
                Brush.verticalGradient(
                    0.0f to StitchColors.primary.copy(alpha = 0.16f),
                    0.25f to DashPalette.railAccent.copy(alpha = 0.05f),
                    0.5f to Color.Black,
                    1.0f to Color.Black
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Clears the FRIENDS / DASH tabs layered above this. Dash scrolls
                // under them, so the first card starts below where they sit:
                // 22dp offset + the label and its underline, plus breathing room.
                .padding(top = 74.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            DashHeader(
                displayName = user?.let { it.displayName.ifBlank { it.username } } ?: "",
                avatarURL = user?.profileImageURL,
                tier = tier,
                subscriberCount = subscriberCount
            )

            // Streak and tier as a pair, and both always render — including at
            // zero. Hiding the streak card at zero left the tier tile alone at
            // half width looking like a layout bug, and hid the one card that
            // tells a new user a streak is a thing they could have.
            DashStatRow(
                streakDays = streakDays,
                isAtRisk = streak.isAtRisk,
                hoursUntilBreak = streak.hoursUntilBreak,
                tier = tier,
                clout = user?.clout ?: 0,
                onCreate = onCreate,
                onTierTap = { if (user != null) showingRank = true }
            )

            // Under the stats, because a badge is the RESULT of the things those
            // two measure. Hidden when there are none rather than showing an empty
            // rack: unlike tier and streak, a badge has no meaningful zero state —
            // "no badges" teaches nothing, where "0 day streak, post to start" does.
            if (badges.isNotEmpty()) {
                DashBadges(
                    badges = badges,
                    onSeeAll = if (badges.size > 6) ({ showingBadges = true }) else null
                )
            }

            nextEvent?.let { event ->
                DashNextUp(
                    event = event,
                    isGoing = eventsVM.rsvpStatus(event.id) != null,
                    onOpenEvents = onOpenEvents,
                    onOpenEvent = onOpenEvent
                )
            }

            // A new account is in no communities, has no streak and has no next
            // event, so every other section is hidden and Dash renders as a
            // greeting over empty space. Suggestions aren't a nicety here — on
            // day one they're the only content the surface has.
            if (communities.isNotEmpty()) {
                DashCommunities(
                    communities = communities,
                    onOpenCommunities = onOpenCommunities,
                    onTap = { openCommunity = CommunityRoute(it, joinLive = false) }
                )
            } else if (suggested.isNotEmpty()) {
                DashSuggested(
                    suggested = suggested,
                    onOpenCommunities = onOpenCommunities,
                    onTap = { openCommunity = CommunityRoute(it, joinLive = false) }
                )
            }

            if (liveCommunities.isNotEmpty()) {
                DashLiveNow(
                    live = liveCommunities,
                    // This row says LIVE, so it joins the stream. Opening the
                    // community page from here answers a different question than
                    // the one the avatar asks.
                    onTap = { openCommunity = CommunityRoute(it, joinLive = true) }
                )
            }

            // Deliberately no "Friends are going". It needs event attendance
            // crossed with the follow graph, and that index doesn't exist — a
            // section that can only ever be empty is worse than no section.

            Spacer(Modifier.height(90.dp))   // clear the dipped tab bar
        }
    }

    // The same screen CommunityListView opens for a tapped row, so a community
    // reached from Dash and one reached from the list are the same view with the
    // same state. By item, not a flag plus a stashed id.
    openCommunity?.let { route ->
        CommunityDetailV2View(
            userID = userID,
            communityID = route.item.id,
            communityItem = route.item,
            autoJoinLive = route.joinLive,
            onDismiss = { openCommunity = null }
        )
    }

    // The rank sheet already explains the perks and the ladder — the tier tile is
    // a summary of it, not a second copy.
    if (showingRank) {
        user?.let { u ->
            RankSheetView(
                user = u,
                followerCount = u.followerCount ?: 0,
                isOwnProfile = true,
                onDismiss = { showingRank = false }
            )
        }
    }

    if (showingBadges) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // The same partial stats ProfileView passes — hypes, threads and
            // engagementRate go in as zero there too, so progress toward UNEARNED
            // badges is understated on both surfaces. Earned badges, which is what
            // the strip is about, render correctly.
            BadgePageView(
                userID = userID,
                isOwner = true,
                stats = com.stitchsocial.club.services.RealUserStats(
                    clout = user?.clout ?: 0,
                    followers = user?.followerCount ?: 0,
                    posts = 0
                ),
                xp = user?.clout ?: 0,
                tierRaw = tier.rawValue,
                onDismiss = { showingBadges = false }
            )
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Badges
// ─────────────────────────────────────────────

/**
 * A strip of what has actually been earned.
 *
 * Reuses BadgeService's cache and BadgeArtwork's own rendering — no second source
 * of truth for what a badge looks like, and no extra read: the service already
 * listens, so this updates the moment one is awarded.
 */
@Composable
private fun DashBadges(
    badges: List<EarnedBadge>,
    onSeeAll: (() -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        DashSectionHeader("BADGES", action = onSeeAll?.let { "See all" }, onAction = onSeeAll)

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            badges.take(12).forEach { badge ->
                val def = BadgeCatalog.find(badge.id) ?: return@forEach
                Column(
                    modifier = Modifier.width(58.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box {
                        BadgeArtwork(definition = def, size = 52)
                        // A NEW badge is the one thing here worth interrupting
                        // for, so it gets the only accent on the strip.
                        if (badge.isNew) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(StitchColors.primary)
                                    .border(1.5.dp, Color.Black, CircleShape)
                            )
                        }
                    }
                    Text(
                        def.name,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Header
// ─────────────────────────────────────────────

@Composable
private fun DashHeader(
    displayName: String,
    avatarURL: String?,
    tier: UserTier,
    subscriberCount: Int
) {
    val coinBalance by HypeCoinCoordinator.getInstance(LocalContext.current).balance.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The real avatar, with the initial as the fallback rather than the
        // default. A greeting carrying a grey circle with a letter in it reads
        // as a placeholder even when everything behind it loaded fine.
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
                // A ring in the tier's own colour, which ties the person at the
                // top to the tier tile below without repeating a label.
                .border(2.dp, tier.color.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                displayName.take(1).uppercase(),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            if (!avatarURL.isNullOrBlank()) {
                AsyncImage(
                    model = avatarURL,
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                if (displayName.isBlank()) "Hi" else "Hi, $displayName",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Only once it means something — "0 subscribers" on your own home
            // screen is a worse greeting than none.
            if (subscriberCount > 0) {
                Text(
                    "$subscriberCount subscriber${if (subscriberCount == 1) "" else "s"}",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }

        coinBalance?.let { balance ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(DashPalette.coinGold.copy(alpha = 0.15f))
                    .border(1.dp, DashPalette.coinGold.copy(alpha = 0.38f), RoundedCornerShape(50))
                    .padding(start = 7.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The real coin, not a stand-in glyph — it keeps its own gold and
                // flame rather than being flattened to a tint.
                Image(
                    painter = painterResource(id = R.drawable.ic_notif_hype_coin),
                    contentDescription = "Hype coins",
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    "${balance.availableCoins}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Stat row
// ─────────────────────────────────────────────

/**
 * Streak and tier, side by side.
 *
 * Stacked full-width they read as a list of alerts — two rows of the same shape,
 * one after the other. Side by side they read as a dashboard, which is what this
 * surface is. The layout inside each had to change for it: at half the screen
 * there's no room for a circle beside "1,240 to Rising" and a bar, so the tiles
 * are vertical — mark on top, the number that matters, then the line explaining it.
 */
@Composable
private fun DashStatRow(
    streakDays: Int,
    isAtRisk: Boolean,
    hoursUntilBreak: Int,
    tier: UserTier,
    clout: Int,
    onCreate: () -> Unit,
    onTierTap: () -> Unit
) {
    val live = streakDays > 0
    val streakTint = if (live) StitchColors.primary else Color.White.copy(alpha = 0.35f)

    val nextTier = tier.nextTier
    val cloutNeeded = nextTier?.cloutRange?.first?.minus(clout)?.coerceAtLeast(0) ?: 0
    val progress = if (nextTier != null) {
        val start = tier.cloutRange.first
        val end = nextTier.cloutRange.first
        if (end > start) ((clout - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f) else 1f
    } else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Tapping opens the recorder, because posting is the thing that keeps a
        // streak alive.
        DashStatTile(
            tint = streakTint,
            value = "$streakDays",
            valueSuffix = if (streakDays == 1) "day" else "days",
            caption = when {
                live && isAtRisk -> "Ends in ${hoursUntilBreak}h"
                live -> "Secured for today"
                else -> "Post or react to start"
            },
            emphasised = live && isAtRisk,
            modifier = Modifier.weight(1f).clickable { onCreate() }
        )

        DashStatTile(
            tint = tier.color,
            value = tier.displayName,
            valueSuffix = null,
            // Leads with the GAP, not the percentage. Clout bands are wide, so a
            // fill that hasn't visibly moved in a week reads as a verdict.
            // "1,240 to Rising" is a target; "12%" is a score.
            caption = nextTier?.let { "$cloutNeeded to ${it.displayName}" } ?: "Top tier",
            emphasised = false,
            modifier = Modifier.weight(1f).clickable { onTierTap() }
        ) {
            // The bar supports the number rather than replacing it.
            if (nextTier != null) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.10f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceAtLeast(0.02f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(tier.color)
                    )
                }
            }
        }
    }
}

/**
 * One tile. Shared so the pair can't drift apart in height or treatment — two
 * dashboard tiles that don't match are worse than one wide card.
 */
@Composable
private fun DashStatTile(
    tint: Color,
    value: String,
    valueSuffix: String?,
    caption: String,
    emphasised: Boolean,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier
            .heightIn(min = 132.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        tint.copy(alpha = if (emphasised) 0.18f else 0.10f),
                        tint.copy(alpha = 0.03f)
                    )
                )
            )
            .border(
                1.dp,
                tint.copy(alpha = if (emphasised) 0.32f else 0.16f),
                RoundedCornerShape(16.dp)
            )
            .padding(13.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.18f))
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            valueSuffix?.let {
                Text(
                    " $it",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Text(
            caption,
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )

        footer()
    }
}

// ─────────────────────────────────────────────
// MARK: - Next up
// ─────────────────────────────────────────────

@Composable
private fun DashNextUp(
    event: StitchEventEntity,
    isGoing: Boolean,
    onOpenEvents: (() -> Unit)?,
    onOpenEvent: ((StitchEventEntity) -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        DashSectionHeader("NEXT UP", action = onOpenEvents?.let { "All events" }, onAction = onOpenEvents)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(148.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
                .then(
                    if (onOpenEvent != null) Modifier.clickable { onOpenEvent(event) } else Modifier
                )
        ) {
            DashCoverArt(
                url = event.coverImageURL ?: event.promoThumbnailURL,
                fallbackName = event.name,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.88f)
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    event.whenLine.uppercase(),
                    color = DashPalette.cyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    event.name,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${event.goingCount} going · ${event.whereLine}",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    DashRsvpChip(if (isGoing) "Going" else "I'm going", filled = !isGoing)
                }
            }
        }
    }
}

@Composable
private fun DashRsvpChip(text: String, filled: Boolean) {
    Text(
        text,
        color = if (filled) Color.Black else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.20f))
            .padding(horizontal = 15.dp, vertical = 7.dp)
    )
}

// ─────────────────────────────────────────────
// MARK: - Communities
// ─────────────────────────────────────────────

@Composable
private fun DashCommunities(
    communities: List<CommunityListItem>,
    onOpenCommunities: (() -> Unit)?,
    onTap: (CommunityListItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        DashSectionHeader(
            "MY COMMUNITIES",
            action = onOpenCommunities?.let { "See all" },
            onAction = onOpenCommunities
        )

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            communities.forEach { c ->
                val name = c.creatorDisplayName.ifBlank { c.creatorUsername }
                Column(
                    modifier = Modifier
                        .width(86.dp)
                        .clickable { onTap(c) },
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(86.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
                    ) {
                        DashCoverArt(c.profileImageURL, name, Modifier.fillMaxSize())

                        if (c.isCreatorLive) {
                            Text(
                                "LIVE",
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(StitchColors.primary)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        Text(
                            "LV${c.userLevel}",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        name,
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * The same card as MY COMMUNITIES, minus the two things a non-member has no use
 * for: their level, and the live badge on a room they haven't joined. Tapping
 * opens the community, which is where joining happens — this doesn't try to be a
 * join button, because a one-tap join from a card you haven't read is a decision
 * nobody asked to make.
 */
@Composable
private fun DashSuggested(
    suggested: List<CommunityListItem>,
    onOpenCommunities: (() -> Unit)?,
    onTap: (CommunityListItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        DashSectionHeader(
            "COMMUNITIES TO JOIN",
            action = onOpenCommunities?.let { "See all" },
            onAction = onOpenCommunities
        )

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            suggested.forEach { c ->
                val name = c.creatorDisplayName.ifBlank { c.creatorUsername }
                Column(
                    modifier = Modifier
                        .width(86.dp)
                        .clickable { onTap(c) },
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(86.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
                    ) {
                        DashCoverArt(c.profileImageURL, name, Modifier.fillMaxSize())
                    }

                    Text(
                        name,
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Member count rather than level: your level in a community
                    // you haven't joined is zero and tells a newcomer nothing,
                    // while member count says whether a room is worth walking into.
                    Text(
                        "${c.memberCount} member${if (c.memberCount == 1) "" else "s"}",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 9.5.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Live now
// ─────────────────────────────────────────────

@Composable
private fun DashLiveNow(
    live: List<CommunityListItem>,
    onTap: (CommunityListItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        DashSectionHeader("LIVE NOW", action = null, onAction = null)

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            live.forEach { c ->
                val name = c.creatorDisplayName.ifBlank { c.creatorUsername }
                Column(
                    modifier = Modifier
                        .width(58.dp)
                        .clickable { onTap(c) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .border(2.dp, StitchColors.primary, CircleShape)
                    ) {
                        DashCoverArt(c.profileImageURL, name, Modifier.fillMaxSize().clip(CircleShape))
                    }
                    Text(
                        c.creatorUsername,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Pieces
// ─────────────────────────────────────────────

@Composable
private fun DashSectionHeader(
    title: String,
    action: String?,
    onAction: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 11sp at 62%, not 9.5 at 40%. The quieter values read as disabled, which
        // flattened every section into one texture — nothing announced itself, so
        // nothing had weight.
        Text(
            title,
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (action != null && onAction != null) {
            Text(
                action,
                color = DashPalette.cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { onAction() }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
            )
        }
    }
}

/**
 * Cover art, with a letter standing in when there's none.
 *
 * A flat translucent rectangle reads as broken rather than as empty — a blank
 * tile looks like an image that failed, not like a community without a picture.
 * The initial is the same convention the greeting avatar uses, so an absent
 * image looks deliberate in both places. The tint is derived from the name, so
 * the same community is the same colour every time.
 */
@Composable
private fun DashCoverArt(
    url: String?,
    fallbackName: String,
    modifier: Modifier = Modifier
) {
    val letter = fallbackName.trim().take(1).uppercase()
    val tint = dashPlaceholderTint(fallbackName)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.22f))
                    )
                )
        )

        if (letter.isNotEmpty()) {
            Text(
                letter,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = fallbackName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * A stable colour per name — summing the characters rather than using hashCode,
 * so a community doesn't change colour between launches.
 */
private fun dashPlaceholderTint(name: String): Color {
    val palette = listOf(
        StitchColors.primary,
        DashPalette.railAccent,
        DashPalette.cyan,
        Color(0xFFF28C40),
        Color(0xFF66BF80)
    )
    if (name.isEmpty()) return palette[0]
    val sum = name.sumOf { it.code }
    return palette[sum % palette.size]
}
