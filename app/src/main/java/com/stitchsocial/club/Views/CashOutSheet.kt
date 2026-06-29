/*
 * CashOutSheet.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Functional shell of Swift CashOutSheet (PayoutInfo.swift). Captures amount +
 * payout method, writes a payoutRequests/{requestID} doc — a Cloud Function
 * picks it up and processes the actual transfer. Full Stripe/PayPal flow on
 * iOS is heavier; this gives users a request-submission path.
 *
 * TODO when porting the rest: payout method storage (saved cards), bank
 * routing/account inputs, multi-step wizard.
 */

package com.stitchsocial.club.views

import com.stitchsocial.club.ui.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.foundation.CashOutLimits
import com.stitchsocial.club.foundation.HypeCoinValue
import com.stitchsocial.club.foundation.UserTier
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private enum class PayoutMethod(val display: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    PAYPAL("PayPal", Icons.Default.AccountBalance),
    BANK("Bank Transfer", Icons.Default.AccountBalance)
}

@Composable
fun CashOutSheet(
    userID: String,
    userTier: UserTier,
    availableCoins: Int,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val db = remember { FirebaseFirestore.getInstance("stitchfin") }

    var amountStr by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(PayoutMethod.PAYPAL) }
    var paypalEmail by remember { mutableStateOf("") }
    var bankName by remember { mutableStateOf("") }
    var bankRouting by remember { mutableStateOf("") }
    var bankAccount by remember { mutableStateOf("") }

    var isSubmitting by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val coinAmount = amountStr.toIntOrNull() ?: 0
    val totalDollars = HypeCoinValue.toDollars(coinAmount)
    // 70% creator share — matches iOS default; iOS reads custom share from user doc.
    val creatorShare = 0.7
    val creatorAmount = totalDollars * creatorShare
    val isValidAmount = coinAmount >= CashOutLimits.MINIMUM_COINS && coinAmount <= availableCoins
    val isPayoutComplete = when (method) {
        PayoutMethod.PAYPAL -> paypalEmail.isNotEmpty() && paypalEmail.contains("@")
        PayoutMethod.BANK -> bankRouting.isNotEmpty() && bankAccount.isNotEmpty() && bankName.isNotEmpty()
    }
    val canSubmit = isValidAmount && isPayoutComplete && !isSubmitting

    fun submit() {
        isSubmitting = true
        scope.launch {
            try {
                val payload = mutableMapOf<String, Any>(
                    "userID" to userID,
                    "coinAmount" to coinAmount,
                    "creatorAmount" to creatorAmount,
                    "method" to method.name.lowercase(),
                    "status" to "pending",
                    "createdAt" to Timestamp.now()
                )
                when (method) {
                    PayoutMethod.PAYPAL -> payload["paypalEmail"] = paypalEmail
                    PayoutMethod.BANK -> {
                        payload["bankAccountName"] = bankName
                        payload["bankRoutingNumber"] = bankRouting
                        payload["bankAccountNumber"] = bankAccount
                    }
                }
                db.collection("payoutRequests").add(payload).await()
                showSuccess = true
            } catch (e: Exception) {
                errorMessage = e.message ?: "Cash-out failed"
            } finally {
                isSubmitting = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(AppTheme.colors.bg)) {

        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 40.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = AppTheme.colors.textSecondary, fontSize = 15.sp) }
                Text(
                    "Cash Out", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.textPrimary, modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(64.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                // Available balance card
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(
                            Brush.linearGradient(listOf(Color(0xFFFFD60A).copy(alpha = 0.15f), Color(0xFFFF9F0A).copy(alpha = 0.1f))),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("AVAILABLE BALANCE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textSecondary, letterSpacing = 0.8.sp)
                    Text("$availableCoins coins", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD60A))
                    Text("≈ $${"%.2f".format(HypeCoinValue.toDollars(availableCoins))}", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
                }

                // Amount field
                LabeledField(label = "AMOUNT TO CASH OUT", placeholder = "Min ${CashOutLimits.MINIMUM_COINS} coins") {
                    BasicTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it.filter { c -> c.isDigit() } },
                        textStyle = TextStyle(color = AppTheme.colors.textPrimary, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color.Cyan),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (amountStr.isEmpty()) {
                                Text("Min ${CashOutLimits.MINIMUM_COINS} coins", color = AppTheme.colors.textPrimary.copy(alpha = 0.25f), fontSize = 16.sp)
                            }
                            inner()
                        }
                    )
                }
                if (coinAmount > 0) {
                    Text(
                        "You'll receive ≈ $${"%.2f".format(creatorAmount)} (70% creator share)",
                        fontSize = 12.sp, color = AppTheme.colors.textSecondary
                    )
                }

                // Method picker
                Text("PAYOUT METHOD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textSecondary, letterSpacing = 0.8.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayoutMethod.values().forEach { m ->
                        val sel = m == method
                        Box(
                            modifier = Modifier.weight(1f)
                                .background(
                                    if (sel) Color.Cyan.copy(alpha = 0.2f) else AppTheme.colors.surface,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { method = m }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(m.icon, null, tint = if (sel) Color.Cyan else AppTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
                                Text(m.display, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (sel) Color.White else AppTheme.colors.textSecondary)
                            }
                        }
                    }
                }

                when (method) {
                    PayoutMethod.PAYPAL -> {
                        LabeledField(label = "PAYPAL EMAIL", placeholder = "you@example.com") {
                            BasicTextField(
                                value = paypalEmail,
                                onValueChange = { paypalEmail = it },
                                textStyle = TextStyle(color = AppTheme.colors.textPrimary, fontSize = 15.sp),
                                cursorBrush = SolidColor(Color.Cyan),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    if (paypalEmail.isEmpty()) Text("you@example.com", color = AppTheme.colors.textPrimary.copy(alpha = 0.25f), fontSize = 15.sp)
                                    inner()
                                }
                            )
                        }
                    }
                    PayoutMethod.BANK -> {
                        LabeledField(label = "ACCOUNT HOLDER", placeholder = "Full name") {
                            BasicTextField(
                                value = bankName, onValueChange = { bankName = it },
                                textStyle = TextStyle(color = AppTheme.colors.textPrimary, fontSize = 15.sp),
                                cursorBrush = SolidColor(Color.Cyan),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner -> if (bankName.isEmpty()) Text("Full name", color = AppTheme.colors.textPrimary.copy(alpha = 0.25f), fontSize = 15.sp); inner() }
                            )
                        }
                        LabeledField(label = "ROUTING NUMBER", placeholder = "9 digits") {
                            BasicTextField(
                                value = bankRouting, onValueChange = { bankRouting = it.filter { c -> c.isDigit() } },
                                textStyle = TextStyle(color = AppTheme.colors.textPrimary, fontSize = 15.sp),
                                cursorBrush = SolidColor(Color.Cyan),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner -> if (bankRouting.isEmpty()) Text("9 digits", color = AppTheme.colors.textPrimary.copy(alpha = 0.25f), fontSize = 15.sp); inner() }
                            )
                        }
                        LabeledField(label = "ACCOUNT NUMBER", placeholder = "Bank account") {
                            BasicTextField(
                                value = bankAccount, onValueChange = { bankAccount = it.filter { c -> c.isDigit() } },
                                textStyle = TextStyle(color = AppTheme.colors.textPrimary, fontSize = 15.sp),
                                cursorBrush = SolidColor(Color.Cyan),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner -> if (bankAccount.isEmpty()) Text("Bank account", color = AppTheme.colors.textPrimary.copy(alpha = 0.25f), fontSize = 15.sp); inner() }
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = ::submit,
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Cyan,
                        disabledContainerColor = AppTheme.colors.textSecondary.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Text("Submit Cash-Out Request", color = Color.Black, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showSuccess) {
        AlertDialog(
            onDismissRequest = { showSuccess = false; onDismiss() },
            title = { Text("Cash Out Submitted!", color = AppTheme.colors.textPrimary) },
            text = {
                Text(
                    "You'll receive ≈ $${"%.2f".format(creatorAmount)} via ${method.display} within 3-5 business days.",
                    color = AppTheme.colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { showSuccess = false; onDismiss() }) {
                    Text("Done", color = Color.Cyan)
                }
            },
            containerColor = AppTheme.colors.surface
        )
    }

    val err = errorMessage
    if (err != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Error", color = AppTheme.colors.textPrimary) },
            text = { Text(err, color = AppTheme.colors.textSecondary) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK", color = Color.Cyan) } },
            containerColor = AppTheme.colors.surface
        )
    }
}

@Composable
private fun LabeledField(
    label: String,
    placeholder: String,
    field: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textSecondary, letterSpacing = 0.8.sp)
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(AppTheme.colors.surface, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) { field() }
    }
}
