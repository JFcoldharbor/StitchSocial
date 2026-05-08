/*
 * TeenLockedView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Mirror of Swift TeenLockedView. Placeholder gate for under-18 accounts
 * until the teen lane is built. Account stays alive.
 *
 * Also exports Under13BlockedView — hard-stop for COPPA.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth

@Composable
fun TeenLockedView(
    displayName: String,
    onSignedOut: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.linearGradient(
                listOf(Color(red = 0.05f, green = 0.1f, blue = 0.2f), Color.Black)
            )
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = Color.Cyan,
                modifier = Modifier.size(56.dp)
            )

            Text(
                "Hey $displayName!",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White,
                textAlign = TextAlign.Center
            )

            Text(
                "The teen experience is on its way.",
                fontSize = 16.sp, fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )

            Text(
                "We're building a Stitch Social space designed for under-18 creators with safer feeds, age-appropriate content, and stronger privacy defaults. Your account is saved and we'll let you in as soon as it's ready.",
                fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    FirebaseAuth.getInstance().signOut()
                    onSignedOut()
                },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Sign Out", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun Under13BlockedView(onAcknowledged: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = Color(0xFFFF9F0A),
                modifier = Modifier.size(56.dp)
            )

            Text(
                "Sorry, we can't create an account for you",
                fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White,
                textAlign = TextAlign.Center
            )

            Text(
                "Stitch Social is for users 13 and older. Please come back when you're old enough to join.",
                fontSize = 14.sp, color = Color.Gray,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onAcknowledged,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("OK", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
