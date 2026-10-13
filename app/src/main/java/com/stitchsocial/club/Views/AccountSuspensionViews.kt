package com.stitchsocial.club.views

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.services.AccountStanding

private val SuspendRed = Color(0xFFFF4D5E)
private val SuspendCard = Color(0xFF161616)

/**
 * Persistent, non-dismissable notice shown at the top of Home and Profile while
 * the account is suspended or banned. Tapping it opens the shared sheet.
 * Renders nothing when posting is allowed.
 */
@Composable
fun AccountSuspensionBanner(modifier: Modifier = Modifier) {
    val suspended by AccountStanding.suspendedNow.collectAsState()
    // Read so the copy follows the doc (reason/date/ban changes).
    AccountStanding.state.collectAsState().value
    if (!suspended) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(SuspendCard)
            .border(1.5.dp, SuspendRed.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
            .clickable { AccountStanding.presentSheet() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(SuspendRed.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Block, null, tint = SuspendRed, modifier = Modifier.size(18.dp))
        }
        Text(
            AccountStanding.bannerText(),
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
    }
}

/**
 * The one sheet every blocked create entry point, the banner, and a refused
 * upload show. Mounted once at the root of the signed-in UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSuspensionSheetHost() {
    val request by AccountStanding.sheet.collectAsState()
    val state by AccountStanding.state.collectAsState()
    val req = request ?: return
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = { AccountStanding.dismissSheet() },
        containerColor = SuspendCard,
        contentColor = Color.White
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Block, null, tint = SuspendRed, modifier = Modifier.size(32.dp))
            Text(
                req.message ?: if (state.isBanned) "Your account has been permanently suspended."
                               else "Your account is suspended",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
            if (!state.isBanned) {
                Text(
                    AccountStanding.endDateLabel()?.let { "Ends $it. You can watch and react until then, but you can't post." }
                        ?: "You can watch and react, but you can't post.",
                    fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)
                )
            }
            state.reason?.let { reason ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Reason", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.5f), letterSpacing = 1.sp)
                    Text(reason, fontSize = 14.sp, color = Color.White)
                }
            }
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(AccountStanding.TERMS_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Read the Terms", color = Color(0xFF22D3EE), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = { AccountStanding.dismissSheet() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.12f))
            ) { Text("OK", color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}
