package com.stitchsocial.club.views

// Settings → Earnings & payouts → Payout history. Twin of iOS PayoutHistoryView.
//
// Data: payouts_myHistory (server payoutHistory.js) — Stripe's own record of
// every transfer Stitch sent this creator, the deposits Stripe made to their
// bank, the balance still in their Stripe, and monthly earnings not sent yet.

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.functions.FirebaseFunctions
import com.stitchsocial.club.ui.theme.AppTheme
import com.stitchsocial.club.ui.theme.Spacing
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── L1 ──────────────────────────────────────────────────────────────────────

data class PayoutHistory(
    val connected: Boolean = false,
    val inStripeCents: Int? = null,
    val sent: List<Sent> = emptyList(),
    val deposits: List<Deposit> = emptyList(),
    val upcoming: List<Upcoming> = emptyList(),
) {
    data class Sent(val id: String, val amountCents: Int, val at: Long, val kind: String, val label: String, val reversed: Boolean)
    data class Deposit(val id: String, val amountCents: Int, val arrivesAt: Long, val status: String)
    data class Upcoming(val period: String, val amountCents: Int, val label: String, val note: String)
}

// ── L3 ──────────────────────────────────────────────────────────────────────

object PayoutHistoryService {
    private val functions by lazy { FirebaseFunctions.getInstance("us-central1") }

    private fun int(v: Any?): Int = (v as? Number)?.toInt() ?: 0
    private fun long(v: Any?): Long = (v as? Number)?.toLong() ?: 0L

    @Suppress("UNCHECKED_CAST")
    suspend fun load(): PayoutHistory {
        val d = functions.getHttpsCallable("payouts_myHistory").call().await().data as? Map<String, Any?> ?: emptyMap()
        val stripe = d["inStripe"] as? Map<String, Any?>
        return PayoutHistory(
            connected = d["connected"] as? Boolean ?: false,
            inStripeCents = stripe?.let { int(it["availableCents"]) + int(it["pendingCents"]) },
            sent = (d["sent"] as? List<Map<String, Any?>> ?: emptyList()).map {
                PayoutHistory.Sent(
                    id = it["id"] as? String ?: "", amountCents = int(it["amountCents"]), at = long(it["at"]),
                    kind = it["kind"] as? String ?: "other", label = it["label"] as? String ?: "Payout",
                    reversed = it["reversed"] as? Boolean ?: false,
                )
            },
            deposits = (d["deposits"] as? List<Map<String, Any?>> ?: emptyList()).map {
                PayoutHistory.Deposit(
                    id = it["id"] as? String ?: "", amountCents = int(it["amountCents"]),
                    arrivesAt = long(it["arrivesAt"]), status = it["status"] as? String ?: "pending",
                )
            },
            upcoming = (d["upcoming"] as? List<Map<String, Any?>> ?: emptyList()).map {
                PayoutHistory.Upcoming(
                    period = it["period"] as? String ?: "", amountCents = int(it["amountCents"]),
                    label = it["label"] as? String ?: "", note = it["note"] as? String ?: "",
                )
            },
        )
    }
}

// ── L5 ──────────────────────────────────────────────────────────────────────

private fun money(cents: Int): String = NumberFormat.getCurrencyInstance(Locale.US).format(cents / 100.0)
private fun day(ms: Long): String = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(ms))
private fun depositTitle(status: String) = when (status) {
    "paid" -> "Deposited"
    "in_transit" -> "On its way to your bank"
    "failed" -> "Deposit failed"
    "canceled" -> "Deposit canceled"
    else -> "Deposit scheduled"
}

@Composable
fun PayoutHistoryPage(onDismiss: () -> Unit) {
    var history by remember { mutableStateOf<PayoutHistory?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { PayoutHistoryService.load() }
            .onSuccess { history = it; failed = false }
            .onFailure { failed = true }
    }

    SettingsSubPage(title = "Payout history", onDismiss = onDismiss) {
        val h = history
        when {
            h != null -> {
                if (!h.connected) {
                    Text(
                        "Connect a payout account to get paid. Anything you earn before then is held for you.",
                        fontSize = 14.sp, color = AppTheme.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    )
                }
                val inStripe = h.inStripeCents ?: 0
                if (inStripe > 0) {
                    SettingsSectionHeader("In your Stripe account")
                    SettingsGroupCard {
                        SettingsRow(
                            title = "On its way to your bank",
                            subtitle = "Stripe sends this automatically",
                            value = money(inStripe), showChevron = false,
                        )
                    }
                }
                if (h.upcoming.isNotEmpty()) {
                    SettingsSectionHeader("Coming up")
                    SettingsGroupCard {
                        h.upcoming.forEachIndexed { i, u ->
                            if (i > 0) SettingsDivider(inset = Spacing.md)
                            SettingsRow(title = u.label, subtitle = u.note, value = money(u.amountCents), showChevron = false)
                        }
                    }
                }
                SettingsSectionHeader("Paid by Stitch")
                SettingsGroupCard {
                    if (h.sent.isEmpty()) {
                        SettingsRow(title = "No payouts yet", showChevron = false, titleColor = AppTheme.colors.textSecondary)
                    }
                    h.sent.forEachIndexed { i, s ->
                        if (i > 0) SettingsDivider(inset = Spacing.md)
                        SettingsRow(
                            title = s.label,
                            subtitle = if (s.reversed) "Reversed" else day(s.at),
                            value = money(s.amountCents), showChevron = false,
                        )
                    }
                }
                if (h.deposits.isNotEmpty()) {
                    SettingsSectionHeader("Deposits to your bank")
                    SettingsGroupCard {
                        h.deposits.forEachIndexed { i, d ->
                            if (i > 0) SettingsDivider(inset = Spacing.md)
                            SettingsRow(
                                title = depositTitle(d.status),
                                subtitle = (if (d.status == "paid") "Arrived " else "Arrives ") + day(d.arrivesAt),
                                value = money(d.amountCents), showChevron = false,
                            )
                        }
                    }
                }
            }
            failed -> Text(
                "Couldn't load your payouts. Go back and try again.",
                fontSize = 14.sp, color = AppTheme.colors.textSecondary,
                modifier = Modifier.padding(Spacing.md),
            )
            else -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
