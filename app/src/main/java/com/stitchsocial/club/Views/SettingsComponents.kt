package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.ui.theme.AppTheme
import com.stitchsocial.club.ui.theme.Spacing

/**
 * The pieces every Settings row is made of — iOS parity, and the same handoff.
 *
 * The numbers here are not taste. They come from the design handoff: row 12dp
 * vertical padding (11 with a subtitle), title 17, subtitle 13, value 17
 * secondary, section header 13 uppercase with 32 above and 7 below, groups 22
 * apart, cards radius 14 inset 16, dividers 0.5 inset 58 at root and 16 in a
 * sub-page, icon tiles 30 radius 8 with a white glyph.
 *
 * They live in one file so a row cannot quietly invent its own spacing, which
 * is exactly what the screen this replaces had done: seven sections of
 * full-width cards, four of them shown or hidden by role, so a setting sat at
 * a different height — or nowhere — depending on who you were.
 */

/** Uppercase section header. 32 above, 7 below. */
@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        color = AppTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md + 16.dp, end = Spacing.md)
            .padding(top = 32.dp, bottom = 7.dp),
    )
}

/**
 * The card a group of rows sits on.
 *
 * Named SettingsGroupCard rather than SettingsCard because ShowSettingsView
 * already has a file-private SettingsCard with the same signature, and Kotlin
 * sees both from inside that file.
 */
@Composable
fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .clip(RoundedCornerShape(14.dp))
            .background(AppTheme.colors.surface),
        content = content,
    )
}

/** 0.5dp hairline, inset past the icon tile at root level. */
@Composable
fun SettingsDivider(inset: Dp = 58.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(0.5.dp)
            .background(AppTheme.colors.hairline),
    )
}

/** 30dp rounded tile with a white glyph — root level only. */
@Composable
fun SettingsIconTile(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

/**
 * One row.
 *
 * @param value the live reading on the right — "On", "Public", a count, a
 *   date. The handoff puts real state here rather than inside the sub-page,
 *   so the list answers most questions without being opened.
 * @param badgeCount a red count from a blocking task, mirrored from the
 *   To-finish card onto the permanent row it concerns.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = SettingsTokens.tileHelp,
    badgeCount: Int = 0,
    showChevron: Boolean = true,
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.md)
            .padding(vertical = if (subtitle != null) 11.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (icon != null) SettingsIconTile(icon, iconTint)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 17.sp,
                color = titleColor ?: AppTheme.colors.textPrimary,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = AppTheme.colors.textSecondary,
                    maxLines = 2,
                )
            }
        }

        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(SettingsTokens.destructive),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badgeCount.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }

        if (value != null) {
            Text(value, fontSize = 17.sp, color = AppTheme.colors.textSecondary, maxLines = 1)
        }

        if (showChevron) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = AppTheme.colors.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The gap between two groups. */
@Composable
fun SettingsGroupGap() = Spacer(Modifier.height(22.dp))
