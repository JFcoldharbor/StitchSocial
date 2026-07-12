/*
 * BirthdayPromptView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Mirror of Swift BirthdayPromptView.swift — force-collects DOB and writes it
 * to users/{id}.privacySettings.birthdate. A Cloud Function (onUserBirthdateSet)
 * recomputes the ageGroup server-side and sets the audienceLane custom claim.
 *
 * COPPA defensiveness:
 * - The gate is NEUTRAL: no live computed-age readout, no mention of a minimum age.
 * - Under-13 writes ONLY the block marker (ageGroup/blockedAt/blockReason) — the
 *   child's birthdate is never persisted (retention would count as collection).
 * - A device-level SharedPreferences flag ("stitch_age_gate_blocked") prevents
 *   sign-out-and-retry with a new fake birthdate on the same device.
 */

package com.stitchsocial.club.views

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Device-level age-gate rejection flag. Once set, this device never shows the
 * birthday picker again — the gate resolves straight to Under13Blocked.
 */
const val AGE_GATE_BLOCKED_PREF = "stitch_age_gate_blocked"

/** Result the prompt emits up to the parent so it can route the user. */
sealed class AgeGateOutcome {
    data class Adult(val birthdate: Date) : AgeGateOutcome()
    data class Teen(val birthdate: Date)  : AgeGateOutcome()
    object Under13Blocked                 : AgeGateOutcome()
}

@Composable
fun BirthdayPromptView(
    userID: String,
    onCompleted: (AgeGateOutcome) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { FirebaseFirestore.getInstance("stitchfin") }
    val dateFmt = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }
    val prefs = remember { context.getSharedPreferences("stitch_prefs", Context.MODE_PRIVATE) }

    // Device-level retry block: if this device already produced an under-13
    // rejection, block immediately — never show the picker again.
    val deviceBlocked = remember { prefs.getBoolean(AGE_GATE_BLOCKED_PREF, false) }
    LaunchedEffect(deviceBlocked) {
        if (deviceBlocked) {
            try { FirebaseAuth.getInstance().signOut() } catch (_: Exception) { }
            onCompleted(AgeGateOutcome.Under13Blocked)
        }
    }
    if (deviceBlocked) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    // Default selection to 18 years ago — same as iOS picker default.
    val initialCal = remember {
        Calendar.getInstance().apply { add(Calendar.YEAR, -18) }
    }
    var selectedDate by remember { mutableStateOf(initialCal.time) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun showPicker() {
        val cal = Calendar.getInstance().apply { time = selectedDate }
        val maxCal = Calendar.getInstance() // today is the cap
        val dialog = DatePickerDialog(
            context,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                selectedDate = picked.time
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.maxDate = maxCal.timeInMillis
        dialog.show()
    }

    fun save() {
        val age = ageFrom(selectedDate)
        if (age < 0) return
        isSaving = true
        scope.launch {
            try {
                if (age < 13) {
                    // Device-level retry block — set BEFORE any network call so
                    // the device stays blocked even if the write fails.
                    prefs.edit().putBoolean(AGE_GATE_BLOCKED_PREF, true).apply()
                    // COPPA: never persist the child's birthdate or age —
                    // only the block marker.
                    try {
                        db.collection("users").document(userID).set(
                            mapOf("privacySettings" to mapOf(
                                "ageGroup" to "blocked",
                                "blockedAt" to Timestamp(Date()),
                                "blockReason" to "age_policy"
                            )),
                            com.google.firebase.firestore.SetOptions.merge()
                        ).await()
                    } catch (_: Exception) {
                        // Still block locally — do not let a network error
                        // leave a rejected child signed in.
                    }
                    try { FirebaseAuth.getInstance().signOut() } catch (_: Exception) { }
                    onCompleted(AgeGateOutcome.Under13Blocked)
                    return@launch
                }

                val ageGroup = if (age >= 18) "adult" else "teen"
                db.collection("users").document(userID).set(
                    mapOf("privacySettings" to mapOf(
                        "birthdate" to Timestamp(selectedDate),
                        "ageGroup" to ageGroup,
                        "ageVerifiedAt" to Timestamp(Date())
                    )),
                    com.google.firebase.firestore.SetOptions.merge()
                ).await()

                // Force token refresh so onUserBirthdateSet's custom-claim
                // write lands on the next request.
                try {
                    FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await()
                } catch (_: Exception) { }

                if (ageGroup == "adult") onCompleted(AgeGateOutcome.Adult(selectedDate))
                else onCompleted(AgeGateOutcome.Teen(selectedDate))

            } catch (e: Exception) {
                errorMessage = "Couldn't save: ${e.message}"
            } finally {
                isSaving = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.weight(0.5f))

            Icon(
                Icons.Default.CardGiftcard,
                contentDescription = null,
                tint = Color.Cyan,
                modifier = Modifier.size(40.dp)
            )
            Text(
                "When's your birthday?",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
            Text(
                "We use this once to set up your account and never show it on your profile.",
                fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            // Tap-to-pick row (Compose Material3 has a date picker dialog,
            // but the native DatePickerDialog matches Android conventions
            // and works without extra deps).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                    .clickable { showPicker() }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    dateFmt.format(selectedDate),
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Text("Edit", fontSize = 13.sp, color = Color.Cyan, fontWeight = FontWeight.SemiBold)
            }

            // NOTE: deliberately no computed-age readout here and no mention of
            // a minimum age — the gate must stay neutral (COPPA).

            errorMessage?.let {
                Text(it, fontSize = 12.sp, color = Color.Red, textAlign = TextAlign.Center)
            }

            Button(
                onClick = ::save,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Cyan,
                    disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text("Continue", color = Color.Black, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                "By continuing you confirm this is your real date of birth.",
                fontSize = 11.sp, color = Color.Gray.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun ageFrom(birthdate: Date): Int {
    val now = Calendar.getInstance()
    val dob = Calendar.getInstance().apply { time = birthdate }
    var age = now.get(Calendar.YEAR) - dob.get(Calendar.YEAR)
    if (now.get(Calendar.DAY_OF_YEAR) < dob.get(Calendar.DAY_OF_YEAR)) age--
    return age.coerceAtLeast(0)
}
