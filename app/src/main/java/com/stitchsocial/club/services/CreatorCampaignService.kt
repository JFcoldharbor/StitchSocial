package com.stitchsocial.club.services

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * CreatorCampaignService — Mode B (influencer marketplace) for Android.
 *
 * Mirrors the iOS CreatorCampaignService. All mutations go through the
 * Cloud Functions deployed in StitchSocial-Functions/index.js:
 *   - applyToCreatorCampaign
 *   - decideCreatorCampaignApplication
 *   - submitCreatorCampaignDeliverable
 *   - brand_acceptDeliverable (accept + optional 1–5 rating and review)
 *   - createCreatorStripeOnboarding / refreshCreatorStripeStatus / retryHeldPayouts
 *
 * Campaigns are created and paid for on the web (stitchsocial.me/brand) —
 * the server writes creatorCampaigns, never the app. The brand pays up front,
 * Stitch holds it, and the creator is paid when the brand accepts the content
 * (or it auto-accepts). There is no reject and no revision: a rating and
 * review is the brand's only quality remedy.
 *
 * Payouts reach a creator through Stripe Connect. Approvals that land before
 * onboarding is finished go to the server's held_no_connect_account bucket.
 *
 * Layer 4: Core Services
 */
class CreatorCampaignService private constructor() {

    companion object {
        @Volatile private var INSTANCE: CreatorCampaignService? = null
        fun getInstance(): CreatorCampaignService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CreatorCampaignService().also { INSTANCE = it }
            }

        private const val TAG = "CreatorCampaignService"
        private const val COL_CAMPAIGNS = "creatorCampaigns"
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val functions = FirebaseFunctions.getInstance("us-central1")

    private val _openCampaigns = MutableStateFlow<List<CreatorCampaign>>(emptyList())
    val openCampaigns: StateFlow<List<CreatorCampaign>> = _openCampaigns.asStateFlow()

    private val _myApplications = MutableStateFlow<List<CreatorCampaign>>(emptyList())
    val myApplications: StateFlow<List<CreatorCampaign>> = _myApplications.asStateFlow()

    private val _brandCampaigns = MutableStateFlow<List<CreatorCampaign>>(emptyList())
    val brandCampaigns: StateFlow<List<CreatorCampaign>> = _brandCampaigns.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ===== Reads (creator side) =====

    suspend fun fetchOpenCampaigns(limit: Int = 50) {
        _isLoading.value = true
        try {
            val snap = db.collection(COL_CAMPAIGNS)
                .whereIn("status", listOf("open", "in_progress", "reviewing"))
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get().await()
            _openCampaigns.value = snap.documents.mapNotNull { CreatorCampaign.fromDoc(it) }
        } catch (e: Exception) {
            Log.e(TAG, "fetchOpenCampaigns failed: ${e.message}")
            _openCampaigns.value = emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    /** One campaign by id — a notification tap opens it directly. Null if gone or unreadable. */
    suspend fun fetchCampaign(campaignID: String): CreatorCampaign? =
        try {
            CreatorCampaign.fromDoc(db.collection(COL_CAMPAIGNS).document(campaignID).get().await())
        } catch (e: Exception) {
            Log.e(TAG, "fetchCampaign($campaignID) failed: ${e.message}")
            null
        }

    suspend fun fetchMyApplicationCampaigns(creatorID: String) {
        try {
            val appsSnap = db.collectionGroup("applications")
                .whereEqualTo("creatorID", creatorID)
                .orderBy("appliedAt", Query.Direction.DESCENDING)
                .limit(50)
                .get().await()

            val campaignIDs = appsSnap.documents.mapNotNull { doc ->
                doc.reference.parent.parent?.id
            }

            val campaigns = mutableListOf<CreatorCampaign>()
            for (id in campaignIDs) {
                val campaignDoc = db.collection(COL_CAMPAIGNS).document(id).get().await()
                CreatorCampaign.fromDoc(campaignDoc)?.let { campaigns.add(it) }
            }
            _myApplications.value = campaigns
        } catch (e: Exception) {
            Log.e(TAG, "fetchMyApplicationCampaigns failed: ${e.message}")
            _myApplications.value = emptyList()
        }
    }

    suspend fun fetchApplicationStatus(campaignID: String, creatorID: String): CreatorCampaignApplication? {
        return try {
            val doc = db.collection(COL_CAMPAIGNS).document(campaignID)
                .collection("applications").document(creatorID).get().await()
            CreatorCampaignApplication.fromDoc(doc)
        } catch (e: Exception) { null }
    }

    suspend fun fetchMyDeliverable(campaignID: String, creatorID: String): CreatorCampaignDeliverable? {
        return try {
            val doc = db.collection(COL_CAMPAIGNS).document(campaignID)
                .collection("deliverables").document(creatorID).get().await()
            CreatorCampaignDeliverable.fromDoc(doc)
        } catch (e: Exception) { null }
    }

    // ===== Reads (brand side) =====

    suspend fun fetchBrandCampaigns(brandID: String) {
        _isLoading.value = true
        try {
            val snap = db.collection(COL_CAMPAIGNS)
                .whereEqualTo("brandID", brandID)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(100)
                .get().await()
            _brandCampaigns.value = snap.documents.mapNotNull { CreatorCampaign.fromDoc(it) }
        } catch (e: Exception) {
            Log.e(TAG, "fetchBrandCampaigns failed: ${e.message}")
            _brandCampaigns.value = emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun fetchApplications(campaignID: String): List<CreatorCampaignApplication> {
        return try {
            val snap = db.collection(COL_CAMPAIGNS).document(campaignID)
                .collection("applications")
                .orderBy("appliedAt", Query.Direction.DESCENDING)
                .get().await()
            snap.documents.mapNotNull { CreatorCampaignApplication.fromDoc(it) }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun fetchDeliverables(campaignID: String): List<CreatorCampaignDeliverable> {
        return try {
            val snap = db.collection(COL_CAMPAIGNS).document(campaignID)
                .collection("deliverables").get().await()
            snap.documents.mapNotNull { CreatorCampaignDeliverable.fromDoc(it) }
        } catch (e: Exception) { emptyList() }
    }

    // ===== Writes =====

    suspend fun apply(campaignID: String, pitch: String): Int? {
        val result = functions.getHttpsCallable("applyToCreatorCampaign").call(
            mapOf("campaignID" to campaignID, "pitch" to pitch)
        ).await()
        @Suppress("UNCHECKED_CAST")
        val payload = result.data as? Map<String, Any?>
        return (payload?.get("aiFitScore") as? Number)?.toInt()
    }

    suspend fun submitDeliverable(campaignID: String, draftURL: String, notes: String) {
        functions.getHttpsCallable("submitCreatorCampaignDeliverable").call(
            mapOf("campaignID" to campaignID, "draftURL" to draftURL, "notes" to notes)
        ).await()
    }

    suspend fun decide(campaignID: String, creatorID: String, approve: Boolean) {
        functions.getHttpsCallable("decideCreatorCampaignApplication").call(
            mapOf(
                "campaignID" to campaignID,
                "creatorID" to creatorID,
                "decision" to if (approve) "approved" else "rejected"
            )
        ).await()
    }

    /**
     * The brand accepts a submitted deliverable, which pays the creator. The
     * rating (1–5) and review are optional and recorded once.
     */
    suspend fun acceptDeliverable(
        campaignID: String,
        creatorID: String,
        rating: Int?,
        review: String?
    ): Map<String, Any?>? {
        val data = mutableMapOf<String, Any>("campaignID" to campaignID, "creatorID" to creatorID)
        rating?.let { data["rating"] = it }
        review?.trim()?.takeIf { it.isNotEmpty() }?.let { data["review"] = it }
        val result = functions.getHttpsCallable("brand_acceptDeliverable").call(data).await()
        @Suppress("UNCHECKED_CAST")
        return result.data as? Map<String, Any?>
    }

    // ===== Stripe Connect (creator payouts) =====

    /** A fresh Stripe-hosted onboarding link. They expire within minutes, so
     *  ask for one per tap rather than caching it. */
    suspend fun createStripeOnboarding(): String {
        val result = functions.getHttpsCallable("createCreatorStripeOnboarding").call().await()
        @Suppress("UNCHECKED_CAST")
        val payload = result.data as? Map<String, Any?>
        return payload?.get("onboardingURL") as? String
            ?: throw IllegalStateException("No onboarding link came back")
    }

    /** Re-reads the Connect account from Stripe and mirrors it onto the user doc. */
    suspend fun refreshStripeStatus(): StripeAccountStatus {
        val result = functions.getHttpsCallable("refreshCreatorStripeStatus").call().await()
        @Suppress("UNCHECKED_CAST")
        val payload = result.data as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        return StripeAccountStatus(
            status = payload["stripeAccountStatus"] as? String ?: "none",
            payoutsEnabled = payload["payoutsEnabled"] as? Boolean ?: false,
            requirements = (payload["requirements"] as? List<String>).orEmpty()
        )
    }

    /** Releases payouts held while the creator had no active Connect account. */
    suspend fun retryHeldPayouts(): Pair<Int, Int> {
        val result = functions.getHttpsCallable("retryHeldPayouts").call().await()
        @Suppress("UNCHECKED_CAST")
        val payload = result.data as? Map<String, Any?> ?: emptyMap()
        return ((payload["succeeded"] as? Number)?.toInt() ?: 0) to
            ((payload["failed"] as? Number)?.toInt() ?: 0)
    }
}

// ===== Models =====

data class CreatorCampaign(
    val id: String,
    val brandID: String,
    val brandName: String?,
    val brandLogoURL: String?,
    val title: String,
    val brief: String,
    /** What the brand expects delivered — format, length, talking points. */
    val expectations: String?,
    val category: String?,
    val payoutCents: Int,
    val status: String,
    val applicationDeadline: Date?,
    val contentDueDate: Date?,
    val applicationsCount: Int,
    val approvedCount: Int,
    val deliveredCount: Int,
    val paidOutCount: Int,
    val criteria: CreatorCampaignCriteria?,
    val createdAt: Date?,
    val updatedAt: Date?,
    // Brand billing (billingVersion 2). Older campaigns leave these empty.
    val slots: Int? = null,
    val fundedCents: Int? = null,
    val refundedCents: Int = 0,
    val acceptedCount: Int = 0,
    /** awaiting_payment | funded | partially_refunded | refunded */
    val paymentStatus: String? = null
) {
    val payoutDollars: Double get() = payoutCents / 100.0
    val isOpen: Boolean get() = status == "open" || status == "in_progress" || status == "reviewing"
    val isAwaitingPayment: Boolean
        get() = status == "awaiting_payment" || paymentStatus == "awaiting_payment"

    companion object {
        fun fromDoc(doc: DocumentSnapshot): CreatorCampaign? {
            if (!doc.exists()) return null
            val data = doc.data ?: return null
            val criteriaMap = data["criteria"] as? Map<String, Any?>
            return CreatorCampaign(
                id = doc.id,
                brandID = data["brandID"] as? String ?: return null,
                brandName = data["brandName"] as? String,
                brandLogoURL = data["brandLogoURL"] as? String,
                title = data["title"] as? String ?: "",
                brief = data["brief"] as? String ?: "",
                expectations = (data["expectations"] as? String)?.takeIf { it.isNotBlank() },
                category = data["category"] as? String,
                payoutCents = (data["payoutCents"] as? Number)?.toInt() ?: 0,
                status = data["status"] as? String ?: "draft",
                applicationDeadline = (data["applicationDeadline"] as? Timestamp)?.toDate(),
                contentDueDate = (data["contentDueDate"] as? Timestamp)?.toDate(),
                applicationsCount = (data["applicationsCount"] as? Number)?.toInt() ?: 0,
                approvedCount = (data["approvedCount"] as? Number)?.toInt() ?: 0,
                deliveredCount = (data["deliveredCount"] as? Number)?.toInt() ?: 0,
                paidOutCount = (data["paidOutCount"] as? Number)?.toInt() ?: 0,
                criteria = criteriaMap?.let { CreatorCampaignCriteria.fromMap(it) },
                createdAt = (data["createdAt"] as? Timestamp)?.toDate(),
                updatedAt = (data["updatedAt"] as? Timestamp)?.toDate(),
                slots = (data["slots"] as? Number)?.toInt(),
                fundedCents = (data["fundedCents"] as? Number)?.toInt(),
                refundedCents = (data["refundedCents"] as? Number)?.toInt() ?: 0,
                acceptedCount = (data["acceptedCount"] as? Number)?.toInt() ?: 0,
                paymentStatus = data["paymentStatus"] as? String
            )
        }
    }
}

data class CreatorCampaignCriteria(
    val minTier: String? = null,
    val minStitchers: Int? = null,
    val minViewsPerVideo: Int? = null,
    val requiredHashtags: List<String>? = null,
    val preferredCategories: List<String>? = null
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>): CreatorCampaignCriteria = CreatorCampaignCriteria(
            minTier = m["minTier"] as? String,
            minStitchers = (m["minStitchers"] as? Number)?.toInt(),
            minViewsPerVideo = (m["minViewsPerVideo"] as? Number)?.toInt(),
            requiredHashtags = m["requiredHashtags"] as? List<String>,
            preferredCategories = m["preferredCategories"] as? List<String>
        )
    }
}

data class CreatorCampaignApplication(
    val creatorID: String,
    val creatorName: String?,
    val creatorTier: String?,
    val pitch: String?,
    val aiFitScore: Int?,
    val metricSnapshot: ApplicationMetricSnapshot?,
    val status: String,
    val appliedAt: Date?,
    val decidedAt: Date?
) {
    companion object {
        fun fromDoc(doc: DocumentSnapshot): CreatorCampaignApplication? {
            if (!doc.exists()) return null
            val data = doc.data ?: return null
            val snapMap = data["metricSnapshot"] as? Map<String, Any?>
            return CreatorCampaignApplication(
                creatorID = data["creatorID"] as? String ?: return null,
                creatorName = data["creatorName"] as? String,
                creatorTier = data["creatorTier"] as? String,
                pitch = data["pitch"] as? String,
                aiFitScore = (data["aiFitScore"] as? Number)?.toInt(),
                metricSnapshot = snapMap?.let { ApplicationMetricSnapshot.fromMap(it) },
                status = data["status"] as? String ?: "pending",
                appliedAt = (data["appliedAt"] as? Timestamp)?.toDate(),
                decidedAt = (data["decidedAt"] as? Timestamp)?.toDate()
            )
        }
    }
}

data class ApplicationMetricSnapshot(
    val stitcherCount: Int?,
    val hypeRating: Double?,
    val viewsPerVideoAvg: Int?
) {
    companion object {
        fun fromMap(m: Map<String, Any?>): ApplicationMetricSnapshot = ApplicationMetricSnapshot(
            stitcherCount = (m["stitcherCount"] as? Number)?.toInt(),
            hypeRating = (m["hypeRating"] as? Number)?.toDouble(),
            viewsPerVideoAvg = (m["viewsPerVideoAvg"] as? Number)?.toInt()
        )
    }
}

data class CreatorCampaignDeliverable(
    val creatorID: String,
    val draftURL: String?,
    val notes: String?,
    val submittedAt: Date?,
    /** As written. Read [status] instead — it folds in legacy values. */
    val approvalStatus: String,
    val acceptedAt: Date?,
    val autoAcceptAt: Date?,
    val approvedAt: Date?,
    val payoutAt: Date?,
    val grossAmountCents: Int?,
    /** The creator's share, locked when the brand accepted them (by rank). */
    val creatorShareRate: Double? = null,
    val platformFeeCents: Int?,
    val creatorNetCents: Int?,
    val stripeTransferID: String?,
    val payoutStatus: String?,
    val payoutError: String?
) {
    /**
     * awaiting (approved, nothing submitted) | submitted | accepted |
     * auto_accepted | missed. The old revision_requested is no longer produced
     * and reads as submitted, as does an old "awaiting" row that has a draft.
     */
    val status: String
        get() = when {
            approvalStatus == "revision_requested" -> "submitted"
            approvalStatus == "awaiting" && submittedAt != null -> "submitted"
            else -> approvalStatus
        }

    companion object {
        fun fromDoc(doc: DocumentSnapshot): CreatorCampaignDeliverable? {
            if (!doc.exists()) return null
            val data = doc.data ?: return null
            return CreatorCampaignDeliverable(
                creatorID = data["creatorID"] as? String ?: return null,
                draftURL = data["draftURL"] as? String,
                notes = data["notes"] as? String,
                submittedAt = ((data["submittedAt"] ?: data["draftSubmittedAt"]) as? Timestamp)?.toDate(),
                approvalStatus = data["approvalStatus"] as? String ?: "awaiting",
                acceptedAt = (data["acceptedAt"] as? Timestamp)?.toDate(),
                autoAcceptAt = (data["autoAcceptAt"] as? Timestamp)?.toDate(),
                approvedAt = (data["approvedAt"] as? Timestamp)?.toDate(),
                payoutAt = (data["payoutAt"] as? Timestamp)?.toDate(),
                grossAmountCents = (data["grossAmountCents"] as? Number)?.toInt(),
                creatorShareRate = (data["creatorShareRate"] as? Number)?.toDouble(),
                platformFeeCents = (data["platformFeeCents"] as? Number)?.toInt(),
                creatorNetCents = (data["creatorNetCents"] as? Number)?.toInt(),
                stripeTransferID = data["stripeTransferID"] as? String,
                payoutStatus = data["payoutStatus"] as? String,
                payoutError = data["payoutError"] as? String
            )
        }
    }
}

/** The creator's Stripe Connect state, from refreshCreatorStripeStatus. */
data class StripeAccountStatus(
    /** none | onboarding | pending_verification | active */
    val status: String,
    val payoutsEnabled: Boolean,
    val requirements: List<String>
)
