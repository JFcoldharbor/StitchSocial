/*
 * AdService.kt - AD SYSTEM SERVICE + MODELS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Ad matching, partnerships, placement, revenue share
 * Dependencies: UserTier, SubscriptionService
 * Features: Opportunity fetching, partnership management, impression tracking, match scoring
 *
 * EXACT PORT: AdService.swift + AdRevenueShare.swift (AdModels.swift)
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.foundation.UserTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.*

// ============================================================================
// REVENUE SHARE CONFIGURATION
// ============================================================================

object AdRevenueShare {
    fun creatorShare(tier: UserTier): Double = when (tier) {
        UserTier.INFLUENCER -> 0.25
        UserTier.AMBASSADOR -> 0.28
        UserTier.ELITE -> 0.32
        UserTier.PARTNER -> 0.35
        UserTier.LEGENDARY -> 0.38
        UserTier.TOP_CREATOR -> 0.40
        UserTier.FOUNDER, UserTier.CO_FOUNDER -> 0.50
        else -> 0.0 // Below influencer = no ads
    }

    fun platformShare(tier: UserTier): Double = 1.0 - creatorShare(tier)

    fun canAccessAds(tier: UserTier): Boolean = when (tier) {
        UserTier.INFLUENCER, UserTier.AMBASSADOR, UserTier.ELITE,
        UserTier.PARTNER, UserTier.LEGENDARY, UserTier.TOP_CREATOR,
        UserTier.FOUNDER, UserTier.CO_FOUNDER -> true
        else -> false
    }
}

// ============================================================================
// AD MODELS
// ============================================================================

enum class AdCategory(val value: String) {
    FITNESS("fitness"), GAMING("gaming"), LIFESTYLE("lifestyle"),
    FASHION("fashion"), TECH("tech"), FOOD("food"),
    TRAVEL("travel"), BEAUTY("beauty"), EDUCATION("education"),
    ENTERTAINMENT("entertainment"), SPORTS("sports"), MUSIC("music"),
    OTHER("other");

    val displayName: String get() = value.replaceFirstChar { it.uppercase() }

    val icon: String get() = when (this) {
        FITNESS -> "💪"; GAMING -> "🎮"; LIFESTYLE -> "🌱"
        FASHION -> "👗"; TECH -> "📱"; FOOD -> "🍕"
        TRAVEL -> "✈️"; BEAUTY -> "💄"; EDUCATION -> "📚"
        ENTERTAINMENT -> "🎬"; SPORTS -> "⚽"; MUSIC -> "🎵"
        OTHER -> "📦"
    }

    companion object {
        fun fromRawValue(value: String): AdCategory? = entries.firstOrNull { it.value == value }
    }
}

enum class AdPaymentModel(val value: String) {
    CPM("cpm"), CPA("cpa"), FLAT("flat"), HYBRID("hybrid");
    companion object {
        fun fromRawValue(value: String): AdPaymentModel? = entries.firstOrNull { it.value == value }
    }
}

enum class AdCampaignStatus(val value: String) {
    DRAFT("draft"), ACTIVE("active"), PAUSED("paused"),
    COMPLETED("completed"), CANCELLED("cancelled");
    companion object {
        fun fromRawValue(value: String): AdCampaignStatus? = entries.firstOrNull { it.value == value }
    }
}

enum class AdOpportunityStatus(val value: String) {
    PENDING("pending"), VIEWED("viewed"), ACCEPTED("accepted"),
    DECLINED("declined"), EXPIRED("expired");
    companion object {
        fun fromRawValue(value: String): AdOpportunityStatus? = entries.firstOrNull { it.value == value }
    }
}

enum class AdPartnershipStatus(val value: String) {
    ACTIVE("active"), PAUSED("paused"), ENDED("ended");
    companion object {
        fun fromRawValue(value: String): AdPartnershipStatus? = entries.firstOrNull { it.value == value }
    }
}

enum class SponsoredDisclosure(val value: String) {
    PAID_PARTNERSHIP("paid_partnership"), SPONSORED("sponsored"),
    AD("ad"), GIFTED("gifted");

    val displayText: String get() = when (this) {
        PAID_PARTNERSHIP -> "Paid Partnership"
        SPONSORED -> "Sponsored"
        AD -> "Ad"
        GIFTED -> "Gifted"
    }
}

data class CreatorRequirements(
    val minimumTier: UserTier = UserTier.INFLUENCER,
    val minimumStitchers: Int? = null,
    val minimumHypeScore: Double? = null,
    val minimumHypeRating: Double? = null,
    val minimumEngagementRate: Double? = null,
    val minimumViewCount: Int? = null,
    val minimumCommunityScore: Double? = null,
    val requiredHashtags: List<String>? = null,
    val preferredCategories: List<AdCategory>? = null
)

data class AdCampaign(
    val id: String,
    val brandID: String,
    val brandName: String,
    val brandLogoURL: String? = null,
    val title: String,
    val description: String,
    val category: AdCategory,
    val adVideoURL: String,
    val adThumbnailURL: String,
    val budgetMin: Double,
    val budgetMax: Double,
    val paymentModel: AdPaymentModel,
    val cpmRate: Double? = null,
    val cpaRate: Double? = null,
    val flatFee: Double? = null,
    val requirements: CreatorRequirements,
    val status: AdCampaignStatus,
    val startDate: Date,
    val endDate: Date? = null,
    val createdAt: Date,
    val updatedAt: Date
) {
    val budgetRange: String get() = "$${budgetMin.toInt()}-${budgetMax.toInt()}"
}

data class AdOpportunity(
    val id: String,
    val campaign: AdCampaign,
    val creatorID: String,
    val matchScore: Int,
    val status: AdOpportunityStatus,
    val estimatedEarnings: Double? = null,
    val createdAt: Date,
    val expiresAt: Date? = null
) {
    val isExpired: Boolean get() = expiresAt?.let { Date().after(it) } ?: false
}

data class AdPartnership(
    val id: String,
    val campaignID: String,
    val creatorID: String,
    val brandID: String,
    val brandName: String,
    val adVideoURL: String,
    val adThumbnailURL: String,
    val revenueShareCreator: Double,
    val revenueSharePlatform: Double,
    val status: AdPartnershipStatus,
    val acceptedAt: Date,
    val totalImpressions: Int,
    val totalEarnings: Double,
    val lastPayoutAt: Date? = null
)

data class AdPlacement(
    val id: String,
    val partnershipID: String,
    val threadID: String,
    val position: Int = 2,
    val impressions: Int,
    val earnings: Double,
    val placedAt: Date
)

data class CreatorAdStats(
    val creatorID: String,
    val totalPartnerships: Int = 0,
    val activePartnerships: Int = 0,
    val totalImpressions: Int = 0,
    val totalEarnings: Double = 0.0,
    val pendingPayout: Double = 0.0,
    val lastPayoutDate: Date? = null,
    val lastPayoutAmount: Double? = null
)

data class CreatorMetrics(
    val stitcherCount: Int,
    val hypeRating: Double,
    val hypeScore: Double,
    val engagementRate: Double,
    val totalViews: Int,
    val communityScore: Double,
    val primaryCategory: AdCategory? = null,
    val topHashtags: List<String> = emptyList()
)

data class SponsoredContentTag(
    val brandName: String,
    val brandID: String? = null,
    val disclosureType: SponsoredDisclosure
)

sealed class AdError(message: String) : Exception(message) {
    object InsufficientTier : AdError("You need to be Influencer tier or higher to access ad opportunities")
    object OpportunityExpired : AdError("This opportunity has expired")
    object CampaignNotFound : AdError("Campaign not found")
    object AlreadyAccepted : AdError("You've already accepted this opportunity")
    class NetworkError(msg: String) : AdError("Network error: $msg")
}

// ============================================================================
// AD SERVICE
// ============================================================================

class AdService private constructor() {

    companion object {
        val shared = AdService()
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")

    private object Collections {
        const val CAMPAIGNS = "ad_campaigns"
        const val OPPORTUNITIES = "ad_opportunities"
        const val PARTNERSHIPS = "ad_partnerships"
        const val PLACEMENTS = "ad_placements"
        const val CREATOR_STATS = "creator_ad_stats"
    }

    private val _availableOpportunities = MutableStateFlow<List<AdOpportunity>>(emptyList())
    val availableOpportunities: StateFlow<List<AdOpportunity>> = _availableOpportunities.asStateFlow()

    private val _activePartnerships = MutableStateFlow<List<AdPartnership>>(emptyList())
    val activePartnerships: StateFlow<List<AdPartnership>> = _activePartnerships.asStateFlow()

    private val _creatorStats = MutableStateFlow<CreatorAdStats?>(null)
    val creatorStats: StateFlow<CreatorAdStats?> = _creatorStats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun canAccessAds(tier: UserTier): Boolean = AdRevenueShare.canAccessAds(tier)

    // MARK: - Fetch Opportunities

    suspend fun fetchOpportunities(creatorID: String, tier: UserTier): List<AdOpportunity> {
        if (!canAccessAds(tier)) throw AdError.InsufficientTier
        _isLoading.value = true
        return try {
            val snapshot = db.collection(Collections.OPPORTUNITIES)
                .whereEqualTo("creatorID", creatorID)
                .whereIn("status", listOf("pending", "viewed"))
                .orderBy("matchScore", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(20)
                .get().await()

            val opportunities = snapshot.documents.mapNotNull { doc ->
                parseOpportunity(doc.id, doc.data ?: return@mapNotNull null)
            }.filter { !it.isExpired }

            _availableOpportunities.value = opportunities
            opportunities
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Fetch Active Partnerships

    suspend fun fetchActivePartnerships(creatorID: String): List<AdPartnership> {
        _isLoading.value = true
        return try {
            val snapshot = db.collection(Collections.PARTNERSHIPS)
                .whereEqualTo("creatorID", creatorID)
                .whereEqualTo("status", "active")
                .get().await()

            val partnerships = snapshot.documents.mapNotNull { doc ->
                parsePartnership(doc.id, doc.data ?: return@mapNotNull null)
            }

            _activePartnerships.value = partnerships
            partnerships
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Accept Opportunity

    suspend fun acceptOpportunity(opportunity: AdOpportunity, creatorTier: UserTier): AdPartnership {
        if (!canAccessAds(creatorTier)) throw AdError.InsufficientTier

        val creatorShare = AdRevenueShare.creatorShare(creatorTier)
        val platformShare = AdRevenueShare.platformShare(creatorTier)

        val partnership = AdPartnership(
            id = UUID.randomUUID().toString(),
            campaignID = opportunity.campaign.id,
            creatorID = opportunity.creatorID,
            brandID = opportunity.campaign.brandID,
            brandName = opportunity.campaign.brandName,
            adVideoURL = opportunity.campaign.adVideoURL,
            adThumbnailURL = opportunity.campaign.adThumbnailURL,
            revenueShareCreator = creatorShare,
            revenueSharePlatform = platformShare,
            status = AdPartnershipStatus.ACTIVE,
            acceptedAt = Date(),
            totalImpressions = 0,
            totalEarnings = 0.0
        )

        val partnershipData = hashMapOf<String, Any>(
            "id" to partnership.id,
            "campaignID" to partnership.campaignID,
            "creatorID" to partnership.creatorID,
            "brandID" to partnership.brandID,
            "brandName" to partnership.brandName,
            "adVideoURL" to partnership.adVideoURL,
            "adThumbnailURL" to partnership.adThumbnailURL,
            "revenueShareCreator" to creatorShare,
            "revenueSharePlatform" to platformShare,
            "status" to AdPartnershipStatus.ACTIVE.value,
            "acceptedAt" to Timestamp.now(),
            "totalImpressions" to 0,
            "totalEarnings" to 0.0
        )

        db.collection(Collections.PARTNERSHIPS).document(partnership.id)
            .set(partnershipData).await()

        db.collection(Collections.OPPORTUNITIES).document(opportunity.id)
            .update("status", AdOpportunityStatus.ACCEPTED.value).await()

        _availableOpportunities.value = _availableOpportunities.value.filter { it.id != opportunity.id }
        _activePartnerships.value = _activePartnerships.value + partnership

        println("✅ AD: Partnership created with ${opportunity.campaign.brandName}")
        return partnership
    }

    // MARK: - Decline Opportunity

    suspend fun declineOpportunity(opportunity: AdOpportunity) {
        db.collection(Collections.OPPORTUNITIES).document(opportunity.id)
            .update("status", AdOpportunityStatus.DECLINED.value).await()
        _availableOpportunities.value = _availableOpportunities.value.filter { it.id != opportunity.id }
        println("❌ AD: Declined opportunity from ${opportunity.campaign.brandName}")
    }

    // MARK: - Get Ad for Thread

    suspend fun getAdForThread(threadID: String, creatorID: String, viewerID: String): AdPartnership? {
        val isSubscribed = checkSubscriptionStatus(viewerID, creatorID)
        if (isSubscribed) {
            println("💎 AD: Viewer is subscribed - skipping ad")
            return null
        }

        val snapshot = db.collection(Collections.PARTNERSHIPS)
            .whereEqualTo("creatorID", creatorID)
            .whereEqualTo("status", "active")
            .limit(1)
            .get().await()

        val doc = snapshot.documents.firstOrNull() ?: return null
        return parsePartnership(doc.id, doc.data ?: return null)
    }

    private suspend fun checkSubscriptionStatus(viewerID: String, creatorID: String): Boolean {
        if (viewerID == creatorID) return false
        val result = SubscriptionService.shared.checkSubscription(viewerID, creatorID)
        return result.hasNoAds
    }

    // MARK: - Record Impression

    suspend fun recordImpression(partnershipID: String, threadID: String) {
        val placementID = "${partnershipID}_${threadID}"
        val placementRef = db.collection(Collections.PLACEMENTS).document(placementID)
        val partnershipRef = db.collection(Collections.PARTNERSHIPS).document(partnershipID)

        val placementDoc = placementRef.get().await()

        if (placementDoc.exists()) {
            placementRef.update("impressions", FieldValue.increment(1)).await()
        } else {
            val placementData = hashMapOf<String, Any>(
                "id" to placementID,
                "partnershipID" to partnershipID,
                "threadID" to threadID,
                "position" to 2,
                "impressions" to 1,
                "earnings" to 0.0,
                "placedAt" to Timestamp.now()
            )
            placementRef.set(placementData).await()
        }

        partnershipRef.update("totalImpressions", FieldValue.increment(1)).await()
    }

    // MARK: - Fetch Creator Stats

    suspend fun fetchCreatorStats(creatorID: String): CreatorAdStats {
        val doc = db.collection(Collections.CREATOR_STATS).document(creatorID).get().await()
        val data = doc.data

        if (data != null) {
            val stats = CreatorAdStats(
                creatorID = creatorID,
                totalPartnerships = (data["totalPartnerships"] as? Number)?.toInt() ?: 0,
                activePartnerships = (data["activePartnerships"] as? Number)?.toInt() ?: 0,
                totalImpressions = (data["totalImpressions"] as? Number)?.toInt() ?: 0,
                totalEarnings = (data["totalEarnings"] as? Number)?.toDouble() ?: 0.0,
                pendingPayout = (data["pendingPayout"] as? Number)?.toDouble() ?: 0.0,
                lastPayoutDate = (data["lastPayoutDate"] as? Timestamp)?.toDate(),
                lastPayoutAmount = (data["lastPayoutAmount"] as? Number)?.toDouble()
            )
            _creatorStats.value = stats
            return stats
        }

        val emptyStats = CreatorAdStats(creatorID = creatorID)
        _creatorStats.value = emptyStats
        return emptyStats
    }

    // MARK: - Match Scoring

    fun calculateMatchScore(campaign: AdCampaign, creatorTier: UserTier, metrics: CreatorMetrics): Int {
        var score = 0
        val req = campaign.requirements

        // Tier check (required)
        if (creatorTier.ordinal < req.minimumTier.ordinal) return 0
        score += 20

        // Stitchers
        score += req.minimumStitchers?.let {
            if (metrics.stitcherCount >= it) 15 else -10
        } ?: 10

        // Hype rating
        score += req.minimumHypeRating?.let {
            if (metrics.hypeRating >= it) 15 else -10
        } ?: 10

        // Engagement rate
        score += req.minimumEngagementRate?.let {
            if (metrics.engagementRate >= it) 15 else -10
        } ?: 10

        // View count
        score += req.minimumViewCount?.let {
            if (metrics.totalViews >= it) 10 else -5
        } ?: 5

        // Category match
        score += req.preferredCategories?.let { categories ->
            metrics.primaryCategory?.let { if (categories.contains(it)) 20 else 0 } ?: 0
        } ?: 10

        // Hashtag relevance
        score += req.requiredHashtags?.let { required ->
            if (required.isEmpty()) 5
            else (metrics.topHashtags.count { required.contains(it) } * 5).coerceIn(0, 15)
        } ?: 5

        return score.coerceIn(0, 100)
    }

    // MARK: - End Partnership

    suspend fun endPartnership(partnership: AdPartnership) {
        db.collection(Collections.PARTNERSHIPS).document(partnership.id)
            .update("status", AdPartnershipStatus.ENDED.value).await()
        _activePartnerships.value = _activePartnerships.value.filter { it.id != partnership.id }
        println("📚 AD: Partnership ended with ${partnership.brandName}")
    }

    // MARK: - Firestore Parsers

    private fun parseOpportunity(id: String, data: Map<String, Any>): AdOpportunity? {
        // Simplified - in production would parse nested campaign object
        return null // TODO: Full nested parsing when campaign data structure is finalized
    }

    private fun parsePartnership(id: String, data: Map<String, Any>): AdPartnership? {
        return try {
            AdPartnership(
                id = id,
                campaignID = data["campaignID"] as? String ?: return null,
                creatorID = data["creatorID"] as? String ?: return null,
                brandID = data["brandID"] as? String ?: "",
                brandName = data["brandName"] as? String ?: "",
                adVideoURL = data["adVideoURL"] as? String ?: "",
                adThumbnailURL = data["adThumbnailURL"] as? String ?: "",
                revenueShareCreator = (data["revenueShareCreator"] as? Number)?.toDouble() ?: 0.0,
                revenueSharePlatform = (data["revenueSharePlatform"] as? Number)?.toDouble() ?: 0.0,
                status = AdPartnershipStatus.fromRawValue(data["status"] as? String ?: "") ?: AdPartnershipStatus.ACTIVE,
                acceptedAt = (data["acceptedAt"] as? Timestamp)?.toDate() ?: Date(),
                totalImpressions = (data["totalImpressions"] as? Number)?.toInt() ?: 0,
                totalEarnings = (data["totalEarnings"] as? Number)?.toDouble() ?: 0.0,
                lastPayoutAt = (data["lastPayoutAt"] as? Timestamp)?.toDate()
            )
        } catch (e: Exception) {
            println("❌ AdService: Failed to parse partnership $id: ${e.message}")
            null
        }
    }
}