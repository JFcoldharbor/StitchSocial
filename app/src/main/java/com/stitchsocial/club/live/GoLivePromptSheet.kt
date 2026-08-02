package com.stitchsocial.club.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.ui.theme.StitchColors

/**
 * Composed BEFORE the stream starts (iOS parity with GoLivePromptSheet).
 *
 * Going live used to be one tap, and on Android members weren't told at all.
 * Even once they are, a generic "Tap to join the stream" says something
 * HAPPENED, not why to come — and the moment the invite has to earn attention
 * is the moment it's sent, which is exactly when the creator is about to be on
 * camera and unable to type. So the message is written first.
 */
@Composable
fun GoLivePromptSheet(
    memberCount: Int,
    onGoLive: (String) -> Unit,
    onCancel: () -> Unit
) {
    var message by remember { mutableStateOf("") }

    // A push notification's usable length. Capping here rather than letting the
    // system truncate is what stops an invite ending mid-word on the lock screen.
    val maxChars = 120

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .background(Color(0xFF141414), RoundedCornerShape(20.dp))
                .padding(22.dp)
        ) {
            Text(
                "Going live",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (memberCount > 0)
                    "$memberCount ${if (memberCount == 1) "member" else "members"} will be notified."
                else
                    "Your community will be notified.",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp
            )

            Spacer(Modifier.height(18.dp))

            OutlinedTextField(
                value = message,
                onValueChange = { if (it.length <= maxChars) message = it },
                placeholder = {
                    Text("What are you going live for?", color = Color.White.copy(alpha = 0.35f))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = StitchColors.primary,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                    cursorColor = StitchColors.primary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(6.dp))
            Text(
                "${message.length}/$maxChars",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.End)
            )

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = { onGoLive(message.trim()) },
                enabled = message.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = StitchColors.primary),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Go live", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(4.dp))

            // A creator who just wants to start shouldn't be held up by a text
            // field — this is why the button above can stay disabled on empty.
            TextButton(
                onClick = { onGoLive("") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip and use the default", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }

            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
            }
        }
    }
}
