package com.stitchsocial.club.services

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
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
 *   - approveCreatorCampaignDeliverable
 *
 * Stripe Connect onboarding is intentionally omitted on Android — Android
 * creators set up payouts on the web at stitchsocial.me/payouts. The
 * server-side held_no_connect_account bucket handles approvals that come
 * before a creator finishes web onboarding.
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
                .whereIn("status", listOf("open", "reviewing"))
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

    suspend fun createCampaign(
        brandID: String,
        brandName: String,
        brandLogoURL: String?,
        title: String,
        brief: String,
        category: String?,
        payoutCents: Int,
        criteria: CreatorCampaignCriteria,
        contentDueDate: Date?
    ): String {
        val ref = db.collection(COL_CAMPAIGNS).document()
        val data = mutableMapOf<String, Any>(
            "brandID" to brandID,
            "brandName" to brandName,
            "title" to title,
            "brief" to brief,
            "payoutCents" to payoutCents,
            "status" to "open",
            "applicationsCount" to 0,
            "approvedCount" to 0,
            "deliveredCount" to 0,
            "paidOutCount" to 0,
            "totalPayoutCents" to 0,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        category?.let { data["category"] = it }
        brandLogoURL?.let { data["brandLogoURL"] = it }
        contentDueDate?.let { data["contentDueDate"] = Timestamp(it) }

        val critMap = mutableMapOf<String, Any>()
        criteria.minTier?.let { critMap["minTier"] = it }
        criteria.minStitchers?.let { critMap["minStitchers"] = it }
        criteria.minViewsPerVideo?.let { critMap["minViewsPerVideo"] = it }
        criteria.requiredHashtags?.takeIf { it.isNotEmpty() }?.let { critMap["requiredHashtags"] = it }
        criteria.preferredCategories?.takeIf { it.isNotEmpty() }?.let { critMap["preferredCategories"] = it }
        data["criteria"] = critMap

        ref.set(data).await()
        return ref.id
    }

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

    suspend fun reviewDeliverable(
        campaignID: String,
        creatorID: String,
        approve: Boolean,
        revisionNotes: String = ""
    ): Map<String, Any?>? {
        val result = functions.getHttpsCallable("approveCreatorCampaignDeliverable").call(
            mapOf(
                "campaignID" to campaignID,
                "creatorID" to creatorID,
                "approved" to approve,
                "revisionNotes" to revisionNotes
            )
        ).await()
        @Suppress("UNCHECKED_CAST")
        return result.data as? Map<String, Any?>
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
    val updatedAt: Date?
) {
    val payoutDollars: Double get() = payoutCents / 100.0
    val isOpen: Boolean get() = status == "open" || status == "reviewing"

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
                updatedAt = (data["updatedAt"] as? Timestamp)?.toDate()
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
    val draftSubmittedAt: Date?,
    val approvalStatus: String,
    val revisionNotes: String?,
    val approvedAt: Date?,
    val payoutAt: Date?,
    val grossAmountCents: Int?,
    val platformFeeCents: Int?,
    val creatorNetCents: Int?,
    val stripeTransferID: String?,
    val payoutStatus: String?,
    val payoutError: String?
) {
    companion object {
        fun fromDoc(doc: DocumentSnapshot): CreatorCampaignDeliverable? {
            if (!doc.exists()) return null
            val data = doc.data ?: return null
            return CreatorCampaignDeliverable(
                creatorID = data["creatorID"] as? String ?: return null,
                draftURL = data["draftURL"] as? String,
                notes = data["notes"] as? String,
                draftSubmittedAt = (data["draftSubmittedAt"] as? Timestamp)?.toDate(),
                approvalStatus = data["approvalStatus"] as? String ?: "awaiting",
                revisionNotes = data["revisionNotes"] as? String,
                approvedAt = (data["approvedAt"] as? Timestamp)?.toDate(),
                payoutAt = (data["payoutAt"] as? Timestamp)?.toDate(),
                grossAmountCents = (data["grossAmountCents"] as? Number)?.toInt(),
                platformFeeCents = (data["platformFeeCents"] as? Number)?.toInt(),
                creatorNetCents = (data["creatorNetCents"] as? Number)?.toInt(),
                stripeTransferID = data["stripeTransferID"] as? String,
                payoutStatus = data["payoutStatus"] as? String,
                payoutError = data["payoutError"] as? String
            )
        }
    }
}
