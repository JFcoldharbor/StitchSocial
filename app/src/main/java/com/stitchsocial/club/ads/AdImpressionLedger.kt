package com.stitchsocial.club.ads

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records what a rendered ad actually earned (Android parity with iOS
 * AdImpressionLedger).
 *
 * Writes to adImpressions/{admobResponseId}. THE DOC ID IS THE DEDUPE KEY:
 * Firestore turns a repeat write with the same response id into an update rather
 * than a second row, so a recomposition or a retry can't double-pay a creator.
 *
 * The aggregating Cloud Function (aggregateAdImpression) is already deployed and
 * platform-neutral, so Android writes the SAME shape iOS does — any divergence
 * here shows up as Android impressions silently missing from creator earnings.
 */
object AdImpressionLedger {

    private val db by lazy { FirebaseFirestore.getInstance("stitchfin") }

    fun record(
        owner: AdConfig.ImpressionOwner,
        placement: String,
        responseID: String,
        valueMicros: Long,
        currencyCode: String,
        precision: Int,
        videoID: String? = null
    ) {
        if (responseID.isBlank()) return
        val viewerID = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (viewerID.isBlank()) return

        // A creator must never earn from viewing their own content. Cheap to
        // check here; the trigger enforces it again server-side.
        if (owner.type == AdConfig.OwnerType.CREATOR && owner.creatorID == viewerID) return

        val data = mutableMapOf<String, Any>(
            "ownerType" to owner.type.raw,
            "creatorID" to owner.creatorID,
            "creatorTier" to (owner.tier?.rawValue ?: ""),
            // Snapshotted at render time: the tier table is tunable and a
            // creator can be promoted, so the rate owed is frozen WITH the event
            // rather than recomputed from whatever their tier is at payout.
            "creatorShareRate" to owner.creatorShare,
            "viewerID" to viewerID,
            "placement" to placement,
            "fillSource" to "admob",
            "admobResponseId" to responseID,
            "valueMicros" to valueMicros,
            "currencyCode" to currencyCode,
            "precision" to precision,
            "platform" to "android",
            "createdAt" to FieldValue.serverTimestamp(),
            "period" to currentPeriod()
        )
        videoID?.let { data["videoID"] = it }

        db.collection("adImpressions").document(responseID).set(data)
            .addOnFailureListener {
                if (BuildConfig.DEBUG) { println("📺 LEDGER: write failed — ${it.message}") }
            }
    }

    /** yyyy-MM, matching the payout period the trigger aggregates into. */
    private fun currentPeriod(): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
}
