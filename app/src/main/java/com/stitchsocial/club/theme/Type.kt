package com.stitchsocial.club.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Set of Material typography styles to start with
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * StitchType — named text styles mirroring iOS AppFont (Typography.swift).
 * Display tier: 32 / 24 / 20 (sharp, heavy/bold). Body tier: 16 / 14 / 12 / 11.
 * Use these for parity with iOS instead of ad-hoc sizes.
 */
object StitchType {
    /** 32 · heaviest — primary headliner (iOS titleLarge). */
    val titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp
    )
    /** 24 · bold — section title (iOS title). */
    val title = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 24.sp
    )
    /** 20 · bold — headline (iOS headline). */
    val headline = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 20.sp
    )
    /** 16 — body (iOS body). */
    val body = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp
    )
    /** 14 — small body / bio (iOS bodySmall). */
    val bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp
    )
    /** 12 — caption (iOS caption). */
    val caption = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp
    )
    /** 11 — fine print (iOS caption2). */
    val caption2 = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 11.sp
    )
}
