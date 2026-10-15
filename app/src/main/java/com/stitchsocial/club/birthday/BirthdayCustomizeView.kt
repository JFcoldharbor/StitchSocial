package com.stitchsocial.club.birthday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.views.InviteAvatar
import kotlin.math.roundToInt

/*
 * BirthdayCustomizeView.kt — Customize, between the style grid and "Make my
 * card". iOS parity with Birthday/BirthdayCustomizeView.swift.
 *
 * Every choice here is optional; "Make my card" straight away gets the chosen
 * style with the profile photo, the top three clips, the style's own track
 * and all followers notified.
 *
 *  - Photo: the profile photo, or a frame from one of their clips
 *    (photoClipID) for anyone whose avatar isn't a face.
 *  - Clips: three slots, filled with the server's defaults. Tap a slot, then
 *    tap any of the (up to 12) clips below to put it there. A clip already in
 *    another slot trades places rather than appearing twice.
 *  - Music: the style's birthday track, or the clips' own sound.
 *  - Who's notified: all followers, or only mutuals.
 *
 * Layer 8: Views
 */

private enum class PhotoSource { PROFILE, CLIP }

/**
 * @param preview null while birthday_preview is still loading (or failed): the
 *   clip choices simply aren't offered and the server's defaults apply.
 */
@Composable
internal fun BirthdayCustomizeView(
    style: BirthdayStyle,
    preview: BirthdayPreview?,
    profileImageURL: String?,
    initials: String,
    initialNotify: BirthdayNotify,
    onBack: () -> Unit,
    onMake: (BirthdayRenderRequest) -> Unit
) {
    val view = LocalView.current
    var photoSource by remember { mutableStateOf(PhotoSource.PROFILE) }
    var photoClipID by remember { mutableStateOf<String?>(null) }
    val slots = remember { mutableStateListOf<String>() }
    var activeSlot by remember { mutableIntStateOf(0) }
    var music by remember { mutableStateOf(BirthdayMusic.STYLE) }
    var notify by remember { mutableStateOf(initialNotify) }

    val clips = preview?.clips.orEmpty()

    // Seeded on open, and again when the preview arrives.
    LaunchedEffect(preview) {
        notify = initialNotify
        slots.clear()
        slots.addAll(preview?.defaultClipIDs.orEmpty())
        activeSlot = 0
    }

    fun swapIn(clip: BirthdayClip) {
        if (activeSlot !in slots.indices) return
        val existing = slots.indexOf(clip.id)
        if (existing >= 0) {
            val tmp = slots[activeSlot]
            slots[activeSlot] = slots[existing]
            slots[existing] = tmp
        } else {
            slots[activeSlot] = clip.id
        }
        view.birthdaySelectionHaptic()
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(BirthdayPalette.closeFill)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back to styles" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ChevronLeft, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Customize", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(style.displayName, color = BirthdayPalette.pink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.size(44.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            // Photo
            Section("Photo") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceCard(
                        title = "Profile photo",
                        selected = photoSource == PhotoSource.PROFILE,
                        modifier = Modifier.weight(1f),
                        icon = { InviteAvatar(imageURL = profileImageURL, initials = initials, size = 44.dp) },
                        onClick = { photoSource = PhotoSource.PROFILE }
                    )
                    if (clips.isNotEmpty()) {
                        ChoiceCard(
                            title = "From a clip",
                            selected = photoSource == PhotoSource.CLIP,
                            modifier = Modifier.weight(1f),
                            icon = {
                                BirthdayCardThumb(
                                    url = (clips.firstOrNull { it.id == photoClipID } ?: clips.first()).thumbnailURL,
                                    cornerRadius = 8.dp,
                                    modifier = Modifier.height(44.dp)
                                )
                            },
                            onClick = {
                                photoSource = PhotoSource.CLIP
                                if (photoClipID == null) photoClipID = clips.firstOrNull()?.id
                            }
                        )
                    }
                }
                if (photoSource == PhotoSource.CLIP) {
                    Hint("Pick the frame to use")
                    ClipStrip(clips, selected = listOfNotNull(photoClipID)) { photoClipID = it.id }
                }
            }

            // Clips
            if (clips.isNotEmpty()) {
                Section("Clips") {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
                    ) {
                        slots.forEachIndexed { index, clipID ->
                            BirthdayCardThumb(
                                url = clips.firstOrNull { it.id == clipID }?.thumbnailURL,
                                tint = BirthdayPalette.violet,
                                label = "${index + 1}",
                                isSelected = activeSlot == index,
                                cornerRadius = 12.dp,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button) { activeSlot = index }
                                    .semantics {
                                        contentDescription = "Clip ${index + 1}"
                                        selected = activeSlot == index
                                    }
                            )
                        }
                    }
                    if (clips.size > slots.size || slots.size > 1) {
                        Hint("Tap a clip above, then pick one to swap in")
                        ClipStrip(clips, selected = slots.toList()) { swapIn(it) }
                    }
                }
            }

            // Music
            Section("Music") {
                Segmented(
                    values = BirthdayMusic.entries.toList(),
                    selected = music,
                    label = { it.label },
                    onSelect = { music = it }
                )
            }

            // Who's notified
            Section("Who's notified") {
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(BirthdayPalette.cardFill)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                ) {
                    BirthdayNotify.entries.forEachIndexed { index, option ->
                        if (index > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        val isOn = notify == option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.RadioButton) { notify = option }
                                .semantics { selected = isOn }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                option.label,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (isOn) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                null,
                                tint = if (isOn) BirthdayPalette.pink else Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        BirthdayPrimaryButton(
            title = "Make my card",
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
        ) {
            onMake(
                BirthdayRenderRequest(
                    style = style,
                    clipIDs = slots.toList(),
                    photoClipID = if (photoSource == PhotoSource.CLIP) (photoClipID ?: clips.firstOrNull()?.id) else null,
                    music = music,
                    notify = notify
                )
            )
        }
    }
}

// MARK: - Pieces

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(title.uppercase())
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
}

@Composable
private fun ChoiceCard(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) BirthdayPalette.pink.copy(alpha = 0.16f) else BirthdayPalette.cardFill)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) BirthdayPalette.pink else Color.White.copy(alpha = 0.08f),
                shape
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        icon()
        Text(
            title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The horizontal picker of all clips. */
@Composable
private fun ClipStrip(clips: List<BirthdayClip>, selected: List<String>, onPick: (BirthdayClip) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(clips, key = { it.id }) { clip ->
            BirthdayCardThumb(
                url = clip.thumbnailURL,
                tint = BirthdayPalette.violet,
                label = if (clip.duration > 0) "${clip.duration.roundToInt()}s" else null,
                isSelected = clip.id in selected,
                cornerRadius = 10.dp,
                modifier = Modifier
                    .height(104.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) { onPick(clip) }
            )
        }
    }
}

/** A two- or three-way choice, iOS segmented-control style on the dark sheet. */
@Composable
private fun <T> Segmented(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(BirthdayPalette.cardFill)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(9.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        values.forEach { v ->
            val isOn = v == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isOn) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(v) }
                    .semantics { this.selected = isOn }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(v),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}
