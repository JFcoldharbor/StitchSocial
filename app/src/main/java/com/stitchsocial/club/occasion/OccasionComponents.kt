package com.stitchsocial.club.occasion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.stitchsocial.club.birthday.BirthdayCenter
import com.stitchsocial.club.birthday.BirthdayPalette
import com.stitchsocial.club.birthday.openBirthdayPost
import com.stitchsocial.club.camera.RecordingContextFactory
import com.stitchsocial.club.coordination.ModalState
import com.stitchsocial.club.coordination.NavigationCoordinator
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.NotificationRouter
import com.stitchsocial.club.services.AccountStanding
import com.stitchsocial.club.services.TeenSafety
import kotlinx.coroutines.launch
import java.util.Date

/*
 * OccasionComponents.kt — the holiday pieces other screens use.
 * iOS parity with Occasions/OccasionComponents.swift.
 *
 *  - OccasionDashButton: the button at the top of Dash. A holiday's opens the
 *    camera already stitching onto its card; a birthday's opens the birthday
 *    sheet (or the live card). Hidden on days with neither.
 *  - OccasionDiscoverBanner: the running holiday's thread in Discover, with
 *    "Stitch yours" and the thread itself. Renders nothing when there's no
 *    holiday, like NearYouLandingsRow beside it.
 *  - stitchOntoCard: loads a card and opens the recorder stitching onto it.
 *  - ProfileOccasionButtons: under a profile's avatar, "In costume 🎃" opening
 *    their answer. On a birthday that is also the holiday, the two shrink to a
 *    pair of round buttons, 🎂 and 🎃, so neither hides the other.
 *
 * Layer 8: Views
 */

/** The holiday accent: the cards' own orange, on the app's near-black. */
object OccasionPalette {
    val orange = Color(0xFFFF7A1A)
    val night = Color(0xFF0B0612)
    val violet = Color(0xFF7C3AED)
    val gradient: Brush get() = Brush.linearGradient(listOf(violet.copy(alpha = 0.55f), orange.copy(alpha = 0.55f)))
}

private fun openThread(videoID: String) =
    NotificationRouter.request(NotificationRouter.TYPE_SHARED_LINK, mapOf("videoID" to videoID))

/**
 * Loads the card and opens the recorder stitching onto its thread — the same
 * context the overlay's Stitch button gives a root video.
 */
suspend fun stitchOntoCard(navigationCoordinator: NavigationCoordinator?, cardVideoID: String) {
    // Suspended: the shared sheet explains instead.
    if (!AccountStanding.guardPosting()) return
    val card = OccasionCenter.loadCard(cardVideoID) ?: return
    com.stitchsocial.club.VideoManager.startRecording()
    val ctx = RecordingContextFactory.createStitchToThread(card.threadID ?: card.id, card.creatorName, card.title)
    navigationCoordinator?.showModal(ModalState.RECORDING, mapOf("context" to ctx, "parentVideo" to card))
}

// MARK: - Dash button

@Composable
fun OccasionDashButton(button: OccasionButton, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val isOccasion = button.kind == OccasionButton.Kind.OCCASION
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (isOccasion) OccasionPalette.gradient else BirthdayPalette.accentGradient)
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            button.label,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(if (isOccasion) OccasionPalette.orange else BirthdayPalette.pink),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isOccasion) Icons.Filled.CameraAlt else Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** What a Dash button tap does. */
suspend fun tapOccasionButton(button: OccasionButton, navigationCoordinator: NavigationCoordinator?) {
    when (button.kind) {
        OccasionButton.Kind.OCCASION -> button.videoID?.let { stitchOntoCard(navigationCoordinator, it) }
        // A live card opens its thread; otherwise the birthday sheet.
        OccasionButton.Kind.BIRTHDAY ->
            if (button.videoID != null) openBirthdayPost(button.videoID) else BirthdayCenter.present()
    }
}

// MARK: - Discover banner

@Composable
fun OccasionDiscoverBanner(navigationCoordinator: NavigationCoordinator?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { OccasionCenter.refresh(FirebaseAuth.getInstance().currentUser?.uid) }
    if (TeenSafety.viewerIsTeen) return
    val o = OccasionCenter.state?.occasion ?: return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(OccasionPalette.night.copy(alpha = 0.85f))
            .border(1.dp, OccasionPalette.orange.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable(onClickLabel = "Open the holiday thread") { openThread(o.videoID) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.size(width = 34.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)).background(OccasionPalette.night),
                contentAlignment = Alignment.Center
            ) {
                Text(o.emoji.ifEmpty { "🎉" }, fontSize = 20.sp)
                if (o.thumbnailURL != null) {
                    AsyncImage(model = o.thumbnailURL, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 34.dp, height = 48.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${o.emoji} ${o.name}".trim(), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(o.prompt.ifEmpty { o.label }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            "Stitch yours",
            color = Color.Black,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(OccasionPalette.orange)
                .clickable(role = Role.Button) { scope.launch { stitchOntoCard(navigationCoordinator, o.videoID) } }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

// MARK: - Profile buttons

@Composable
fun ProfileOccasionButtons(badge: OccasionBadge?, birthdayVideoID: String?, birthdayUntil: Date?) {
    if (TeenSafety.viewerIsTeen) return
    val active = badge?.takeIf { it.isActive } ?: return
    val birthdayActive = birthdayUntil != null && birthdayUntil.after(Date()) && !birthdayVideoID.isNullOrEmpty()

    if (birthdayActive) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoundEmojiButton("🎂", "Birthday post", BirthdayPalette.pink) { openBirthdayPost(birthdayVideoID) }
            RoundEmojiButton(active.emoji.ifEmpty { "🎉" }, active.label, OccasionPalette.orange) { openThread(active.videoID) }
        }
    } else {
        Text(
            active.label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(OccasionPalette.night)
                .border(1.dp, OccasionPalette.orange, RoundedCornerShape(50))
                .clickable(onClickLabel = "Open their holiday video", role = Role.Button) { openThread(active.videoID) }
                .padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun RoundEmojiButton(emoji: String, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.75f))
            .border(1.5.dp, tint, CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 15.sp)
    }
}
