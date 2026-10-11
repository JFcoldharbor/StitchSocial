package com.stitchsocial.club.landing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The full-screen active ticket (Active.dc) — what the server or cashier
 * looks at.
 *
 * Brand-coloured page, the item in large type, "for @username", a countdown
 * ring with mm:ss left and the live clock with seconds. Everything moves
 * every frame, which is the whole anti-screenshot story: there is no staff
 * step and no PIN, so a ticket that visibly ticks is what tells a real one
 * from a photo of one.
 *
 * Reachable again from My perks (and the Landing) while it is active.
 */
@Composable
fun ActivePerkScreen(perk: Perk, username: String, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)

    val activeUntil = perk.activeUntil ?: Date(System.currentTimeMillis() + 15 * 60_000)
    val windowMs = (activeUntil.time - (perk.activatedAt?.time ?: (activeUntil.time - 15 * 60_000)))
        .coerceAtLeast(1L)

    // Frame clock: the ring sweeps smoothly rather than once a second.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(perk.perkID) {
        while (true) {
            withFrameMillis { now = System.currentTimeMillis() }
        }
    }

    val leftMs = (activeUntil.time - now).coerceAtLeast(0L)
    val expired = leftMs == 0L
    val fraction = (leftMs.toFloat() / windowMs).coerceIn(0f, 1f)
    val mmss = "%d:%02d".format(leftMs / 60_000, (leftMs / 1000) % 60)
    val clock = remember(now / 1000) { SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(now)) }
    val accent = LandingColors.accent
    val ink = Color(0xFF0B0B12)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(accent)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = ink)
            }
            Text(
                if (expired) "USED" else "ACTIVE NOW",
                fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp, color = ink,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(48.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(ink)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LandingLogo(perk.landingName, perk.landingLogoURL, 56.dp)
                Text(
                    "${perk.landingName} · ${perk.kindLabel}",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA3A3B8), textAlign = TextAlign.Center
                )
                Text(
                    "Free ${perk.title}",
                    fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFF4F4F8), textAlign = TextAlign.Center
                )
                Text(
                    "for @$username" + if (perk.isWelcome) " · with any order" else "",
                    fontSize = 15.sp, color = Color(0xFFC9C9D6), textAlign = TextAlign.Center
                )
            }

            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(
                        color = Color.White.copy(alpha = 0.12f), startAngle = 0f, sweepAngle = 360f,
                        useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke)
                    )
                    drawArc(
                        color = accent, startAngle = -90f, sweepAngle = 360f * fraction,
                        useCenter = false, topLeft = topLeft, size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                    // A dot that orbits once a second — motion a still photo can't fake.
                    val angle = Math.toRadians(((now % 1000) / 1000.0) * 360.0 - 90.0)
                    val r = (size.width - stroke) / 2
                    drawCircle(
                        color = Color.White,
                        radius = stroke / 3,
                        center = androidx.compose.ui.geometry.Offset(
                            (center.x + r * kotlin.math.cos(angle)).toFloat(),
                            (center.y + r * kotlin.math.sin(angle)).toFloat()
                        )
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(mmss, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 44.sp, color = Color(0xFFF4F4F8))
                    Text(if (expired) "done" else "left", fontSize = 13.sp, color = Color(0xFFA3A3B8))
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(clock, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = accent)
                Text(
                    if (expired) "This perk has been used." else "Show this to your server or cashier.",
                    fontSize = 14.sp, color = Color(0xFFC9C9D6), textAlign = TextAlign.Center
                )
                Text(
                    "The clock and ring keep moving, so screenshots look frozen.",
                    fontSize = 12.sp, color = Color(0xFFA3A3B8), textAlign = TextAlign.Center
                )
            }
        }
    }
}
