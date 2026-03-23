/*
 * SubscriptionService.kt - SUBSCRIPTION SERVICE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * REWRITTEN: Full parity with iOS SubscriptionService.swift.
 * Removed SUPPORTER/SUPER_FAN. Now uses CoinPriceTier + TierPricing.
 *
 * CACHING (add to CachingOptimization):
 *   - creatorPlanCache: Map<creatorID, plan+fetchedAt> — 10min TTL
 *   - mySubsFetchedAt: timestamp — 5min TTL for fan subscriptions list
 *   - isSubscribedCache: Map<creatorID, bool+fetchedAt> — 5min TTL
 *   - subscriptionCache: Map<key, SubscriptionCheckResult> — session scope
 *   All caches invalidated on write (subscribe/cancel/update).
 *
 * BATCHING: subscribe() does coin transfer + sub doc + plan stats in sequence.
 * Future: wrap in transaction for atomicity.
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.*

class SubscriptionService private constructor() {

    companion object {
        val shared = SubscriptionService()
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val coinService = HypeCoinService.shared

    private object Collections {
        const val PLANS         = "creator_subscription_plans"
        const val SUBSCRIPTIONS = "subscriptions"
        const val EVENTS        = "subscription_events"
    }

    private val _mySubscriptions = MutableStateFlow<List<ActiveSubscription>>(emptyList())
    val mySubscriptions: StateFlow<List<ActiveSubscription>> = _mySubscriptions.asStateFlow()

    private val _mySubscribers = MutableStateFlow<List<SubscriberInfo>>(emptyList())
    val mySubscribers: StateFlow<List<SubscriberInfo>> = _mySubscribers.asStateFlow()

    private val _creatorPlan = MutableStateFlow<CreatorSubscriptionPlan?>(null)
    val creatorPlan: StateFlow<CreatorSubscriptionPlan?> = _creatorPlan.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Cache — mirrors iOS exactly
    private data class CachedPlan(val plan: CreatorSubscriptionPlan, val fetchedAt: Date)
    private data class CachedBool(val value: Boolean, val fetchedAt: Date)

    private val creatorPlanCache = mutableMapOf<String, CachedPlan>()
    private val isSubscribedCache = mutableMapOf<String, CachedBool>()
    private val subscriptionCache = mutableMapOf<String, SubscriptionCheckResult>()
    private var mySubsFetchedAt: Date? = null
    private var mySubscribersFetchedAt: Date? = null

    private val planTTL: Long = 10 * 60 * 1000L  // 10 min
    private val subsTTL: Long = 5 * 60 * 1000L   // 5 min

    private var currentUserEmail: String? = null

    fun setCurrentUserEmail(email: String?) { currentUserEmail = email }

    // MARK: - Creator Plan

    suspend fun fetchCreatorPlan(creatorID: String): CreatorSubscriptionPlan? {
        val cached = creatorPlanCache[creatorID]
        if (cached != null && (Date().time - cached.fetchedAt.time) < planTTL) {
            return cached.plan
        }

        val doc = db.collection(Collections.PLANS).document(creatorID).get().await()
        val data = doc.data ?: return null

        val tierPricingData = data["tierPricing"]
        val tierPricing = if (tierPricingData is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            TierPricing.fromMap(tierPricingData as Map<String, Any>)
        } else {
            TierPricing()
        }

        val plan = CreatorSubscriptionPlan(
            id = doc.id,
            creatorID = creatorID,
            isEnabled = data["isEnabled"] as? Boolean ?: false,
            tierPricing = tierPricing,
            customWelcomeMessage = data["customWelcomeMessage"] as? String,
            subscriberCount = toInt(data["subscriberCount"]),
            totalEarned = toInt(data["totalEarned"]),
            lastPriceChangeAt = (data["lastPriceChangeAt"] as? Timestamp)?.toDate(),
            nextPriceChangeAllowedAt = (data["nextPriceChangeAllowedAt"] as? Timestamp)?.toDate(),
            createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date(),
            updatedAt = (data["updatedAt"] as? Timestamp)?.toDate() ?: Date()
        )

        creatorPlanCache[creatorID] = CachedPlan(plan, Date())
        _creatorPlan.value = plan
        return plan
    }

    suspend fun createOrUpdatePlan(
        creatorID: String,
        isEnabled: Boolean,
        tierPricing: TierPricing,
        welcomeMessage: String?
    ): CreatorSubscriptionPlan {
        val existing = fetchCreatorPlan(creatorID)

        // 60-day cooldown check — mirrors iOS exactly
        if (existing != null && existing.tierPricing.customPerks != tierPricing.customPerks) {
            if (!existing.canChangePrice) {
                throw SubscriptionError.PriceCooldown(existing.daysUntilPriceChange)
            }
        }

        val planData = hashMapOf<String, Any>(
            "creatorID" to creatorID,
            "isEnabled" to isEnabled,
            "tierPricing" to tierPricing.toMap(),
            "updatedAt" to Timestamp.now()
        )
        welcomeMessage?.let { planData["customWelcomeMessage"] = it }

        // Mark perk change timestamp if changed
        if (existing != null && existing.tierPricing.customPerks != tierPricing.customPerks) {
            val nextAllowed = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 60) }.time
            planData["lastPriceChangeAt"] = Timestamp.now()
            planData["nextPriceChangeAllowedAt"] = Timestamp(nextAllowed)
        }

        if (existing == null) {
            planData["subscriberCount"] = 0
            planData["totalEarned"] = 0
            planData["createdAt"] = Timestamp.now()
        }

        db.collection(Collections.PLANS).document(creatorID)
            .set(planData, com.google.firebase.firestore.SetOptions.merge()).await()

        creatorPlanCache.remove(creatorID)
        val updated = fetchCreatorPlan(creatorID)!!
        println("✅ SUBS: Plan updated for $creatorID")
        return updated
    }

    // MARK: - Subscribe

    /**
     * Subscribe fan to creator at chosen coin tier.
     * Mirrors iOS SubscriptionService.subscribe(subscriberID, creatorID, creatorTier, coinTier)
     */
    suspend fun subscribe(
        subscriberID: String,
        creatorID: String,
        coinTier: CoinPriceTier = CoinPriceTier.STARTER
    ): ActiveSubscription {
        _isLoading.value = true
        try {
            val plan = fetchCreatorPlan(creatorID)
            if (plan == null || !plan.isEnabled) throw SubscriptionError.PlanNotFound

            // Check already subscribed
            val existing = getSubscription(subscriberID, creatorID)
            if (existing?.isActive == true) throw SubscriptionError.AlreadySubscribed

            val price = coinTier.rawValue

            // Debit coins — mirrors iOS HypeCoinService.transferCoins
            coinService.transferCoins(
                fromUserID = subscriberID,
                toUserID = creatorID,
                amount = price,
                type = CoinTransactionType.SUBSCRIPTION_SENT
            )

            val subID = "${subscriberID}_${creatorID}"
            val now = Date()
            val periodEnd = Calendar.getInstance().apply { add(Calendar.MONTH, 1) }.time

            val subData = hashMapOf<String, Any>(
                "subscriberID" to subscriberID,
                "creatorID" to creatorID,
                "coinTier" to coinTier.rawValue,
                "coinsPaid" to price,
                "status" to SubscriptionStatus.ACTIVE.value,
                "subscribedAt" to Timestamp.now(),
                "currentPeriodStart" to Timestamp.now(),
                "currentPeriodEnd" to Timestamp(periodEnd),
                "autoRenew" to true,
                "renewalCount" to 0
            )

            db.collection(Collections.SUBSCRIPTIONS).document(subID).set(subData).await()

            // Update creator plan stats
            db.collection(Collections.PLANS).document(creatorID).update(
                hashMapOf<String, Any>(
                    "subscriberCount" to FieldValue.increment(1L),
                    "totalEarned" to FieldValue.increment(price.toLong())
                )
            ).await()

            // Record event
            val eventData = hashMapOf<String, Any>(
                "subscriptionID" to subID,
                "subscriberID" to subscriberID,
                "creatorID" to creatorID,
                "type" to SubscriptionEventType.NEW_SUBSCRIPTION.value,
                "coinTier" to coinTier.rawValue,
                "coinAmount" to price,
                "createdAt" to Timestamp.now()
            )
            db.collection(Collections.EVENTS).document(UUID.randomUUID().toString()).set(eventData).await()

            // Invalidate caches
            isSubscribedCache.remove(creatorID)
            subscriptionCache.remove("${subscriberID}_${creatorID}")
            mySubsFetchedAt = null

            println("🎉 SUBS: $subscriberID → $creatorID at ${coinTier.displayName} (${price} coins)")

            return ActiveSubscription(
                id = subID,
                subscriberID = subscriberID,
                creatorID = creatorID,
                coinTier = coinTier,
                coinsPaid = price,
                status = SubscriptionStatus.ACTIVE,
                subscribedAt = now,
                currentPeriodStart = now,
                currentPeriodEnd = periodEnd
            )
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Cancel

    suspend fun cancelSubscription(subscriberID: String, creatorID: String) {
        val subID = "${subscriberID}_${creatorID}"

        db.collection(Collections.SUBSCRIPTIONS).document(subID).update(
            hashMapOf<String, Any>(
                "status" to SubscriptionStatus.CANCELLED.value,
                "autoRenew" to false,
                "updatedAt" to Timestamp.now()
            )
        ).await()

        db.collection(Collections.PLANS).document(creatorID).update(
            hashMapOf<String, Any>("subscriberCount" to FieldValue.increment(-1L))
        ).await()

        val eventData = hashMapOf<String, Any>(
            "subscriptionID" to subID,
            "subscriberID" to subscriberID,
            "creatorID" to creatorID,
            "type" to SubscriptionEventType.CANCELLATION.value,
            "coinTier" to CoinPriceTier.STARTER.rawValue,
            "coinAmount" to 0,
            "createdAt" to Timestamp.now()
        )
        db.collection(Collections.EVENTS).document(UUID.randomUUID().toString()).set(eventData).await()

        isSubscribedCache.remove(creatorID)
        subscriptionCache.remove("${subscriberID}_${creatorID}")
        mySubsFetchedAt = null
        mySubscribersFetchedAt = null

        _mySubscriptions.value = _mySubscriptions.value.filter { it.creatorID != creatorID }
        println("❌ SUBS: $subscriberID cancelled → $creatorID")
    }

    // MARK: - Check Subscription

    /**
     * Mirrors iOS SubscriptionService.checkSubscription(subscriberID, creatorID)
     * Returns SubscriptionCheckResult with coinTier and hasNoAds.
     * Used by AdService to gate ads.
     */
    suspend fun checkSubscription(subscriberID: String, creatorID: String): SubscriptionCheckResult {
        val key = "${subscriberID}_${creatorID}"
        subscriptionCache[key]?.let { return it }

        val sub = getSubscription(subscriberID, creatorID)
        if (sub?.isActive != true) {
            val result = SubscriptionCheckResult.NONE
            subscriptionCache[key] = result
            return result
        }

        val result = SubscriptionCheckResult(
            isSubscribed = true,
            coinsPaid = sub.coinsPaid,
            coinTier = sub.coinTier
        )
        subscriptionCache[key] = result
        return result
    }

    // MARK: - Is Subscribed (cached)

    suspend fun isSubscribed(subscriberID: String, creatorID: String): Boolean {
        val cached = isSubscribedCache[creatorID]
        if (cached != null && (Date().time - cached.fetchedAt.time) < subsTTL) return cached.value

        val result = checkSubscription(subscriberID, creatorID).isSubscribed
        isSubscribedCache[creatorID] = CachedBool(result, Date())
        return result
    }

    // MARK: - Get Subscription

    suspend fun getSubscription(subscriberID: String, creatorID: String): ActiveSubscription? {
        val subID = "${subscriberID}_${creatorID}"
        val doc = db.collection(Collections.SUBSCRIPTIONS).document(subID).get().await()
        val data = doc.data ?: return null
        return parseSubscription(doc.id, data)
    }

    // MARK: - Fetch My Subscriptions

    suspend fun fetchMySubscriptions(userID: String): List<ActiveSubscription> {
        val fetched = mySubsFetchedAt
        if (fetched != null && (Date().time - fetched.time) < subsTTL && _mySubscriptions.value.isNotEmpty()) {
            return _mySubscriptions.value
        }

        val snapshot = db.collection(Collections.SUBSCRIPTIONS)
            .whereEqualTo("subscriberID", userID)
            .whereEqualTo("status", SubscriptionStatus.ACTIVE.value)
            .get().await()

        val subs = snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            parseSubscription(doc.id, data)
        }

        mySubsFetchedAt = Date()
        _mySubscriptions.value = subs
        return subs
    }

    // MARK: - Fetch My Subscribers

    suspend fun fetchMySubscribers(creatorID: String): List<SubscriberInfo> {
        val fetched = mySubscribersFetchedAt
        if (fetched != null && (Date().time - fetched.time) < subsTTL && _mySubscribers.value.isNotEmpty()) {
            return _mySubscribers.value
        }

        val snapshot = db.collection(Collections.SUBSCRIPTIONS)
            .whereEqualTo("creatorID", creatorID)
            .whereEqualTo("status", SubscriptionStatus.ACTIVE.value)
            .orderBy("subscribedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get().await()

        val subscribers = snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val tierRaw = toInt(data["coinTier"])
            val coinTier = CoinPriceTier.fromRawValue(tierRaw) ?: CoinPriceTier.STARTER
            val renewalCount = toInt(data["renewalCount"])
            val coinsPaid = toInt(data["coinsPaid"])

            SubscriberInfo(
                id = doc.id,
                subscriberID = data["subscriberID"] as? String ?: "",
                username = "",
                displayName = "",
                coinTier = coinTier,
                subscribedAt = (data["subscribedAt"] as? Timestamp)?.toDate() ?: Date(),
                totalPaid = coinsPaid * maxOf(1, renewalCount),
                renewalCount = renewalCount
            )
        }

        mySubscribersFetchedAt = Date()
        _mySubscribers.value = subscribers
        return subscribers
    }

    // MARK: - Convenience

    suspend fun hasPerk(perk: SubscriptionPerk, userID: String, creatorID: String): Boolean {
        val result = checkSubscription(userID, creatorID)
        val tier = result.coinTier ?: return false
        return SubscriptionPerks.perks(tier).contains(perk)
    }

    val isDeveloper: Boolean
        get() = currentUserEmail?.endsWith("@stitch.dev") == true

    fun clearCache() {
        subscriptionCache.clear()
        isSubscribedCache.clear()
        creatorPlanCache.clear()
        mySubsFetchedAt = null
        mySubscribersFetchedAt = null
    }

    fun clearCache(subscriberID: String, creatorID: String) {
        subscriptionCache.remove("${subscriberID}_${creatorID}")
        isSubscribedCache.remove(creatorID)
    }

    // MARK: - Private Helpers

    private fun parseSubscription(id: String, data: Map<String, Any>): ActiveSubscription? {
        val tierRaw = toInt(data["coinTier"])
        val coinTier = CoinPriceTier.fromRawValue(tierRaw) ?: CoinPriceTier.STARTER
        val status = SubscriptionStatus.fromRawValue(data["status"] as? String ?: "") ?: SubscriptionStatus.EXPIRED
        val subscribedAt = (data["subscribedAt"] as? Timestamp)?.toDate() ?: Date()
        val periodStart = (data["currentPeriodStart"] as? Timestamp)?.toDate() ?: subscribedAt
        val periodEnd = (data["currentPeriodEnd"] as? Timestamp)?.toDate() ?: Date()

        return ActiveSubscription(
            id = id,
            subscriberID = data["subscriberID"] as? String ?: "",
            creatorID = data["creatorID"] as? String ?: "",
            coinTier = coinTier,
            coinsPaid = toInt(data["coinsPaid"]),
            status = status,
            subscribedAt = subscribedAt,
            currentPeriodStart = periodStart,
            currentPeriodEnd = periodEnd,
            autoRenew = data["autoRenew"] as? Boolean ?: false,
            renewalCount = toInt(data["renewalCount"])
        )
    }

    private fun toInt(value: Any?): Int {
        if (value is Long) return value.toInt()
        if (value is Int) return value
        if (value is Double) return value.toInt()
        return 0
    }
}