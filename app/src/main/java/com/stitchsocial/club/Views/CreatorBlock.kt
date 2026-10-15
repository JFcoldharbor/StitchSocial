package com.stitchsocial.club.views

/*
 * CreatorBlock.kt — mirrors iOS Overlays/CreatorBlock.swift.
 *
 * One creator block on every player surface: avatar, name, and the detail
 * line, sitting with the caption at the bottom. It replaces the top-left
 * CreatorPill (and the purple "thread creator" pill beside it), which put the
 * person who made the clip at the far end of the screen from everything else
 * about the post, with Follow a separate cyan pill 600dp away.
 *
 * Follow is a badge on the avatar: an accent plus to follow, a quiet check once
 * you do. Unfollowing asks first — a 16dp target on a scrolling feed gets hit
 * by accident.
 *
 * A missing avatar never draws an empty grey circle: the fallback is a
 * monogram on a colour derived from the user id, so the same account is always
 * the same colour.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/** The Stitch accent (#C9B6E8): the follow badge and the chain rail, nothing else. */
internal val StitchChainAccent = Color(0xFFC9B6E8)

private val MonogramHues = listOf(
    Color(0xFFC9B5E8), // lavender — the Stitch accent
    Color(0xFF8CB8E3),
    Color(0xFF9ED1B5),
    Color(0xFFEDC294),
    Color(0xFFE3A6B5),
    Color(0xFFB3ADDB)
)

/** Text shadow so white text holds over bright video (iOS .shadow radius 3). */
private val BlockTextShadow = Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 2f), blurRadius = 6f)

private fun compactCount(n: Int): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> "$n"
}

/**
 * @param showsDetail views and the thread / collaborator lines show only while
 *   the caption is expanded. The name and avatar are never hidden.
 * @param onViewsTap set for the clip's creator — the count is their way in to
 *   who viewed it. Null for everyone else: same text, not tappable.
 * @param threadOwnerName whose thread this clip belongs to, when that's
 *   somebody else ("on X's thread"). Replaces the purple thread-creator pill.
 * @param onFollowTap null hides the badge (own video, or a surface without
 *   following).
 * @param birthdayUntil users/{id}.birthdayUntil when the caller's user read
 *   carried it. The avatar wears the 🎂 ring until then; null (the usual
 *   case) draws nothing. Visual only here — the avatar still opens the profile.
 */
@Composable
internal fun CreatorBlock(
    displayName: String,
    handle: String?,
    profileImageURL: String?,
    identityKey: String,
    birthdayUntil: java.util.Date? = null,
    viewCount: Int? = null,
    showsDetail: Boolean = true,
    onViewsTap: (() -> Unit)? = null,
    threadOwnerName: String? = null,
    onThreadOwnerTap: (() -> Unit)? = null,
    collaborators: List<com.stitchsocial.club.services.Collaborator> = emptyList(),
    isSponsored: Boolean = false,
    avatarSize: Dp = 36.dp,
    isFollowing: Boolean = false,
    onFollowTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onTap: () -> Unit
) {
    val name = displayName.ifEmpty { handle ?: "Unknown" }
    val nameStyle = TextStyle(color = Color.White, fontSize = 15.fixedSp(), fontWeight = FontWeight.SemiBold, shadow = BlockTextShadow)
    val lineStyle = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 13.fixedSp(), shadow = BlockTextShadow)
    val smallStyle = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.fixedSp(), fontWeight = FontWeight.Medium, shadow = BlockTextShadow)

    // The caller sizes the block (the creator row gives it the width that's
    // left); the tap target is only the avatar and text, not the empty space
    // after a short name.
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable(onClick = onTap),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CreatorAvatar(
                displayName = name,
                profileImageURL = profileImageURL,
                identityKey = identityKey,
                birthdayUntil = birthdayUntil,
                size = avatarSize,
                isFollowing = isFollowing,
                onFollowTap = onFollowTap
            )
            // Bounded by the row, so a long name ellipsizes here instead of
            // pushing the share/3-dots column the caller pins beside the block.
            Column(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (isSponsored) SponsoredChip(fontSize = 11.fixedSp())
                }
                if (showsDetail && viewCount != null && viewCount > 0) {
                    Text(
                        "${compactCount(viewCount)} views",
                        style = lineStyle.copy(color = Color.White.copy(alpha = if (onViewsTap == null) 0.7f else 0.9f)),
                        maxLines = 1,
                        modifier = if (onViewsTap != null) Modifier.clickable(onClick = onViewsTap) else Modifier
                    )
                }
                // Attribution for the thread, under the person who made the clip.
                // Quiet, because it is context rather than the subject.
                if (showsDetail && threadOwnerName != null) {
                    Text(
                        "on $threadOwnerName's thread",
                        style = smallStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (onThreadOwnerTap != null) Modifier.clickable(onClick = onThreadOwnerTap) else Modifier
                    )
                }
                // Credit under the creator: a collaborator shares the clout and
                // the reach, not the authorship.
                if (showsDetail && collaborators.isNotEmpty()) {
                    val handles = collaborators.map { it.handle }
                    val credit = when (handles.size) {
                        1 -> "with ${handles[0]}"
                        2 -> "with ${handles[0]} and ${handles[1]}"
                        else -> "with ${handles[0]} and ${handles.size - 1} others"
                    }
                    Text(credit, style = smallStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CreatorAvatar(
    displayName: String,
    profileImageURL: String?,
    identityKey: String,
    birthdayUntil: java.util.Date?,
    size: Dp,
    isFollowing: Boolean,
    onFollowTap: (() -> Unit)?
) {
    var confirmingUnfollow by remember { mutableStateOf(false) }

    Box(contentAlignment = Alignment.BottomCenter) {
        com.stitchsocial.club.birthday.BirthdayRing(until = birthdayUntil, diameter = size) {
            Box(
                modifier = Modifier
                    .size(size)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
            ) {
                if (!profileImageURL.isNullOrEmpty()) {
                    SubcomposeAsyncImage(
                        model = profileImageURL,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        loading = { Monogram(displayName, identityKey, size) },
                        error = { Monogram(displayName, identityKey, size) }
                    )
                } else {
                    Monogram(displayName, identityKey, size)
                }
            }
        }
        // Sits ON the avatar's lower edge, so it reads as "add this person"
        // rather than as a control that happens to be nearby. It stays once
        // you follow and changes state, so a feed can also unfollow.
        if (onFollowTap != null) {
            Box(
                modifier = Modifier
                    .offset(y = 7.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (isFollowing) Color.Black.copy(alpha = 0.55f) else StitchChainAccent)
                    .border(1.5.dp, Color.Black.copy(alpha = 0.55f), CircleShape)
                    .clickable {
                        if (isFollowing) confirmingUnfollow = true else onFollowTap()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isFollowing) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = if (isFollowing) "Following $displayName. Unfollow" else "Follow $displayName",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }

    if (confirmingUnfollow && onFollowTap != null) {
        AlertDialog(
            onDismissRequest = { confirmingUnfollow = false },
            title = { Text("Unfollow $displayName?") },
            confirmButton = {
                TextButton(onClick = { confirmingUnfollow = false; onFollowTap() }) {
                    Text("Unfollow", color = Color(0xFFFF453A))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnfollow = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Monogram(displayName: String, identityKey: String, size: Dp) {
    // Sum of code points, not hashCode-with-salt: the same account must get the
    // same colour on every launch and on both platforms.
    val seed = identityKey.sumOf { it.code }
    val hue = MonogramHues[Math.abs(seed) % MonogramHues.size]
    val letter = displayName.trim().take(1).uppercase().ifEmpty { "?" }
    Box(
        modifier = Modifier.fillMaxSize().background(hue),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter,
            color = Color(0xFF241C2E),
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.44f).fixedSp()
        )
    }
}
