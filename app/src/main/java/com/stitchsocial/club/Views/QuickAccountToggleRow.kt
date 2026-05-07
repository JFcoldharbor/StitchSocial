/*
 * QuickAccountToggleRow.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Single-tap toggle row inside Settings → Accounts. When the user has
 * two linked accounts (one personal, one business), this row shows the
 * OTHER account and one-tap swaps to it. When only one is linked, the
 * row collapses — "Manage linked accounts" handles add.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.services.LinkedAccountManager
import kotlinx.coroutines.launch

@Composable
fun QuickAccountToggleRow() {
    val context = LocalContext.current
    val manager = remember { LinkedAccountManager.getInstance(context) }
    val accounts by manager.accounts.collectAsState()
    val activeUID by manager.activeUID.collectAsState()
    val scope = rememberCoroutineScope()

    var isSwitching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Only render when there's a meaningful "other" account to swap to.
    if (accounts.size < 2) return
    val other = accounts.firstOrNull { it.uid != activeUID } ?: return

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !isSwitching) {
                    scope.launch {
                        isSwitching = true
                        error = null
                        try {
                            manager.toggleActive()
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            isSwitching = false
                        }
                    }
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape)
                    .background(Color.Cyan.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SwapHoriz, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Switch to ${other.accountType.displayName}",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White
                )
                Text(
                    other.displayName.ifBlank { other.email },
                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), maxLines = 1
                )
            }
            if (isSwitching) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Cyan)
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = Color.Cyan)
            }
        }
        error?.let {
            Text(
                it, fontSize = 12.sp, color = Color.Red,
                modifier = Modifier.padding(start = 48.dp, top = 4.dp)
            )
        }
    }
}
