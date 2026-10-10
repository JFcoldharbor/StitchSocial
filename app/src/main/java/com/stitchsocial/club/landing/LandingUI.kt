package com.stitchsocial.club.landing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Small pieces every Landing surface shares, from the approved mockups
 * (landing-design/project): the accent, the logo disc, the big action button
 * and the "Posted for a perk" label.
 *
 * Neutrals come from AppTheme (adaptive); the accent and the two status
 * colours are the mockups' own, because they mean something on every screen
 * — cyan is "do this", green is "done".
 *
 * Layer 6: Views
 */
object LandingColors {
    val accent = Color(0xFF22D3EE)
    val onAccent = Color(0xFF0B0B12)
    val done = Color(0xFF34D399)
    val doneSurface = Color(0xFF0F2A21)
    /** The placeholder behind a logo with no image yet (the mockups' "B"). */
    val logoFallback = Color(0xFFF97316)
    val coverFallback = Color(0xFF3B2417)
}

/**
 * "Posted for a perk" — on every video that was posted from a Landing's post
 * step (`incentivized`), wherever it renders. The label is what makes a
 * paid-for post honest, so it is never optional.
 */
@Composable
fun PostedForPerkChip(modifier: Modifier = Modifier, fontSize: TextUnit = 10.5.sp) {
    Text(
        text = "Posted for a perk",
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFF4F4F8),
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0B0B12).copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

/**
 * "At {landingName}" with a pin — on any video the server tagged to a Landing
 * (`landingSlug`, addendum 1). A tap opens that Landing (source "tag").
 */
@Composable
fun LandingTagChip(
    landingName: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 10.5.sp,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0B0B12).copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            Icons.Filled.Place,
            contentDescription = null,
            tint = LandingColors.accent,
            modifier = Modifier.size((fontSize.value + 2f).dp)
        )
        Text(
            text = "At $landingName",
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF4F4F8),
            maxLines = 1
        )
    }
}

/**
 * The video's Landing labels, side by side: "At {name}" (when tagged) then
 * "Posted for a perk" (when incentivized). Renders nothing when neither.
 */
@Composable
fun VideoLandingChips(
    video: com.stitchsocial.club.foundation.CoreVideoMetadata,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 10.5.sp,
    beforeOpen: () -> Unit = {}
) {
    val slug = video.landingSlug
    val name = video.landingName ?: ""
    val tagged = !slug.isNullOrBlank() && name.isNotBlank()
    if (!tagged && !video.incentivized) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (tagged) {
            LandingTagChip(landingName = name, fontSize = fontSize) {
                beforeOpen()
                LandingPresenter.route(slug = slug!!, source = LandingSource.TAG)
            }
        }
        if (video.incentivized) PostedForPerkChip(fontSize = fontSize)
    }
}

/** The restaurant's logo, or its first letter on the mockups' orange. */
@Composable
fun LandingLogo(name: String, logoURL: String?, size: Dp, modifier: Modifier = Modifier, ring: Color? = null) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(LandingColors.logoFallback)
            .then(if (ring != null) Modifier.border(4.dp, ring, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (!logoURL.isNullOrBlank()) {
            AsyncImage(
                model = logoURL,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "•",
                fontWeight = FontWeight.ExtraBold,
                fontSize = (size.value * 0.42f).sp,
                color = Color(0xFF1A0E06)
            )
        }
    }
}

/** The 54dp action button the mockups use for every primary step. */
@Composable
fun LandingButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = LandingColors.accent,
    content: Color = LandingColors.onAccent,
    enabled: Boolean = true,
    busy: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.35f),
            disabledContentColor = content.copy(alpha = 0.7f)
        ),
        modifier = modifier.fillMaxWidth().height(54.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, maxLines = 1)
        }
    }
}

/** Pill used for status ("Open until 11 PM", "1 · Claimed"). */
@Composable
fun LandingPill(text: String, background: Color, content: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = content, maxLines = 1)
    }
}

/** "80 m" up close, "0.4 mi" past a block. */
fun distanceLabel(meters: Double): String =
    if (meters < 160) "${meters.toInt()} m" else String.format("%.1f mi", meters / 1609.34)
