/*
 * SubscriptionService.kt - SUBSCRIPTION SERVICE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Subscriptions, perks, subscriber management
 * Dependencies: SubscriptionTier, HypeCoinService
 * Features: Subscribe flow, coin transfer, plan management, perk checking, caching
 *
 * EXACT PORT: SubscriptionService.swift
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
        const val PLANS = "subscription_plans"
        const val SUBSCRIPTIONS = "subscriptions"
        const val SUBSCRIBERS = "subscribers"
        const val EVENTS = "subscription_events"
    }

    private val _mySubscriptions = MutableStateFlow<List<ActiveSubscription>>(emptyList())
    val mySubscriptions: StateFlow<List<ActiveSubscription>> = _mySubscriptions.asStateFlow()

    private val _mySubscribers = MutableStateFlow<List<SubscriberInfo>>(emptyList())
    val mySubscribers: StateFlow<List<SubscriberInfo>> = _mySubscribers.asStateFlow()

    private val _creatorPlan = MutableStateFlow<CreatorSubscriptionPlan?>(null)
    val creatorPlan: StateFlow<CreatorSubscriptionPlan?> = _creatorPlan.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Cache for quick perk lookups
    private val subscriptionCache = mutableMapOf<String, SubscriptionCheckResult>()

    // MARK: - Creator Plan Setup

    suspend fun fetchCreatorPlan(creatorID: String): CreatorSubscriptionPlan? {
        val doc = db.collection(Collections.PLANS).document(creatorID).get().await()
        val data = doc.data ?: return null

        val plan = CreatorSubscriptionPlan(
            id = doc.id,
            creatorID = creatorID,
            isEnabled = data["isEnabled"] as? Boolean ?: false,
            supporterPrice = (data["supporterPrice"] as? Number)?.toInt() ?: SubscriptionTier.SUPPORTER.defaultCoins,
            superFanPrice = (data["superFanPrice"] as? Number)?.toInt() ?: SubscriptionTier.SUPER_FAN.defaultCoins,
            supporterEnabled = data["supporterEnabled"] as? Boolean ?: true,
            superFanEnabled = data["superFanEnabled"] as? Boolean ?: true,
            customWelcomeMessage = data["customWelcomeMessage"] as? String,
            subscriberCount = (data["subscriberCount"] as? Number)?.toInt() ?: 0,
            totalEarned = (data["totalEarned"] as? Number)?.toInt() ?: 0,
            createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date(),
            updatedAt = (data["updatedAt"] as? Timestamp)?.toDate() ?: Date()
        )

        _creatorPlan.value = plan
        return plan
    }

    suspend fun createOrUpdatePlan(
        creatorID: String,
        isEnabled: Boolean,
        supporterPrice: Int,
        superFanPrice: Int,
        supporterEnabled: Boolean,
        superFanEnabled: Boolean,
        welcomeMessage: String?
    ): CreatorSubscriptionPlan {
        // Validate prices
        require(supporterPrice in SubscriptionTier.SUPPORTER.coinRange) {
            throw SubscriptionError.InvalidPrice
        }
        require(superFanPrice in SubscriptionTier.SUPER_FAN.coinRange) {
            throw SubscriptionError.InvalidPrice
        }

        val docRef = db.collection(Collections.PLANS).document(creatorID)
        val existingDoc = docRef.get().await()

        val planData = hashMapOf<String, Any>(
            "creatorID" to creatorID,
            "isEnabled" to isEnabled,
            "supporterPrice" to supporterPrice,
            "superFanPrice" to superFanPrice,
            "supporterEnabled" to supporterEnabled,
            "superFanEnabled" to superFanEnabled,
            "updatedAt" to Timestamp.now()
        )
        welcomeMessage?.let { planData["customWelcomeMessage"] = it }

        if (!existingDoc.exists()) {
            planData["subscriberCount"] = 0
            planData["totalEarned"] = 0
            planData["createdAt"] = Timestamp.now()
        }

        docRef.set(planData, com.google.firebase.firestore.SetOptions.merge()).await()

        val plan = fetchCreatorPlan(creatorID)!!
        println("✅ SUBS: Plan updated for $creatorID")
        return plan
    }

    // MARK: - Subscribe

    suspend fun subscribe(subscriberID: String, creatorID: String, tier: SubscriptionTier): ActiveSubscription {
        _isLoading.value = true
        try {
            val plan = fetchCreatorPlan(creatorID)
                ?: throw SubscriptionError.SubscriptionsNotEnabled
            if (!plan.isEnabled || !plan.isTierEnabled(tier)) {
                throw SubscriptionError.SubscriptionsNotEnabled
            }

            val price = plan.priceForTier(tier)

            // Check existing
            val existing = getSubscription(subscriberID, creatorID)
            if (existing?.isActive == true) throw SubscriptionError.AlreadySubscribed

            // Transfer coins
            coinService.transferCoins(
                fromUserID = subscriberID,
                toUserID = creatorID,
                amount = price,
                type = CoinTransactionType.SUBSCRIPTION_RECEIVED
            )

            // Create subscription
            val subID = "${subscriberID}_${creatorID}"
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.MONTH, 1)
            val expiresAt = calendar.time

            val subscription = ActiveSubscription(
                id = subID,
                subscriberID = subscriberID,
                creatorID = creatorID,
                tier = tier,
                coinsPaid = price,
                status = SubscriptionStatus.ACTIVE,
                startedAt = Date(),
                expiresAt = expiresAt,
                renewalEnabled = true,
                renewalCount = 0
            )

            val subData = hashMapOf<String, Any>(
                "subscriberID" to subscriberID,
                "creatorID" to creatorID,
                "tier" to tier.value,
                "coinsPaid" to price,
                "status" to SubscriptionStatus.ACTIVE.value,
                "startedAt" to Timestamp.now(),
                "expiresAt" to Timestamp(expiresAt),
                "renewalEnabled" to true,
                "renewalCount" to 0
            )

            db.collection(Collections.SUBSCRIPTIONS).document(subID).set(subData).await()

            // Update creator plan stats
            db.collection(Collections.PLANS).document(creatorID).update(
                mapOf(
                    "subscriberCount" to FieldValue.increment(1),
                    "totalEarned" to FieldValue.increment(price.toLong())
                )
            ).await()

            // Record event
            val eventID = UUID.randomUUID().toString()
            val eventData = hashMapOf<String, Any>(
                "subscriptionID" to subID,
                "subscriberID" to subscriberID,
                "creatorID" to creatorID,
                "type" to SubscriptionEventType.NEW_SUBSCRIPTION.value,
                "tier" to tier.value,
                "coinAmount" to price,
                "createdAt" to Timestamp.now()
            )
            db.collection(Collections.EVENTS).document(eventID).set(eventData).await()

            subscriptionCache.remove("${subscriberID}_${creatorID}")
            fetchMySubscriptions(subscriberID)

            println("🎉 SUBS: $subscriberID subscribed to $creatorID at ${tier.displayName}")
            return subscription
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Cancel

    suspend fun cancelSubscription(subscriberID: String, creatorID: String) {
        val subID = "${subscriberID}_${creatorID}"
        db.collection(Collections.SUBSCRIPTIONS).document(subID).update(
            mapOf(
                "renewalEnabled" to false,
                "status" to SubscriptionStatus.CANCELLED.value
            )
        ).await()

        val eventID = UUID.randomUUID().toString()
        val eventData = hashMapOf<String, Any>(
            "subscriptionID" to subID,
            "subscriberID" to subscriberID,
            "creatorID" to creatorID,
            "type" to SubscriptionEventType.CANCELLATION.value,
            "tier" to SubscriptionTier.SUPPORTER.value,
            "coinAmount" to 0,
            "createdAt" to Timestamp.now()
        )
        db.collection(Collections.EVENTS).document(eventID).set(eventData).await()

        subscriptionCache.remove("${subscriberID}_${creatorID}")
        println("❌ SUBS: $subscriberID cancelled subscription to $creatorID")
    }

    // MARK: - Check Subscription

    suspend fun checkSubscription(subscriberID: String, creatorID: String): SubscriptionCheckResult {
        val cacheKey = "${subscriberID}_${creatorID}"
        subscriptionCache[cacheKey]?.let { return it }

        val subscription = getSubscription(subscriberID, creatorID)
        if (subscription?.isActive != true) {
            val result = SubscriptionCheckResult.NONE
            subscriptionCache[cacheKey] = result
            return result
        }

        val result = SubscriptionCheckResult(
            isSubscribed = true,
            tier = subscription.tier,
            perks = subscription.tier.perks,
            hypeBoost = subscription.tier.hypeBoost
        )
        subscriptionCache[cacheKey] = result
        return result
    }

    // MARK: - Get Subscription

    suspend fun getSubscription(subscriberID: String, creatorID: String): ActiveSubscription? {
        val subID = "${subscriberID}_${creatorID}"
        val doc = db.collection(Collections.SUBSCRIPTIONS).document(subID).get().await()
        val data = doc.data ?: return null

        return ActiveSubscription(
            id = subID,
            subscriberID = data["subscriberID"] as? String ?: subscriberID,
            creatorID = data["creatorID"] as? String ?: creatorID,
            tier = SubscriptionTier.fromRawValue(data["tier"] as? String ?: "") ?: SubscriptionTier.SUPPORTER,
            coinsPaid = (data["coinsPaid"] as? Number)?.toInt() ?: 0,
            status = SubscriptionStatus.fromRawValue(data["status"] as? String ?: "") ?: SubscriptionStatus.EXPIRED,
            startedAt = (data["startedAt"] as? Timestamp)?.toDate() ?: Date(),
            expiresAt = (data["expiresAt"] as? Timestamp)?.toDate() ?: Date(),
            renewalEnabled = data["renewalEnabled"] as? Boolean ?: false,
            renewalCount = (data["renewalCount"] as? Number)?.toInt() ?: 0
        )
    }

    // MARK: - Fetch My Subscriptions

    suspend fun fetchMySubscriptions(userID: String): List<ActiveSubscription> {
        val snapshot = db.collection(Collections.SUBSCRIPTIONS)
            .whereEqualTo("subscriberID", userID)
            .whereEqualTo("status", "active")
            .get().await()

        val subscriptions = snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            ActiveSubscription(
                id = doc.id,
                subscriberID = data["subscriberID"] as? String ?: userID,
                creatorID = data["creatorID"] as? String ?: "",
                tier = SubscriptionTier.fromRawValue(data["tier"] as? String ?: "") ?: SubscriptionTier.SUPPORTER,
                coinsPaid = (data["coinsPaid"] as? Number)?.toInt() ?: 0,
                status = SubscriptionStatus.ACTIVE,
                startedAt = (data["startedAt"] as? Timestamp)?.toDate() ?: Date(),
                expiresAt = (data["expiresAt"] as? Timestamp)?.toDate() ?: Date(),
                renewalEnabled = data["renewalEnabled"] as? Boolean ?: false,
                renewalCount = (data["renewalCount"] as? Number)?.toInt() ?: 0
            )
        }

        _mySubscriptions.value = subscriptions
        return subscriptions
    }

    // MARK: - Fetch My Subscribers

    suspend fun fetchMySubscribers(creatorID: String): List<SubscriberInfo> {
        val snapshot = db.collection(Collections.SUBSCRIPTIONS)
            .whereEqualTo("creatorID", creatorID)
            .whereEqualTo("status", "active")
            .orderBy("startedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get().await()

        val subscribers = snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val tier = SubscriptionTier.fromRawValue(data["tier"] as? String ?: "") ?: SubscriptionTier.SUPPORTER
            val renewalCount = (data["renewalCount"] as? Number)?.toInt() ?: 0
            val coinsPaid = (data["coinsPaid"] as? Number)?.toInt() ?: 0

            SubscriberInfo(
                id = doc.id,
                subscriberID = data["subscriberID"] as? String ?: "",
                username = "",  // Fetch separately
                displayName = "", // Fetch separately
                tier = tier,
                subscribedAt = (data["startedAt"] as? Timestamp)?.toDate() ?: Date(),
                totalPaid = coinsPaid * (renewalCount + 1),
                renewalCount = renewalCount
            )
        }

        _mySubscribers.value = subscribers
        return subscribers
    }

    // MARK: - Convenience

    suspend fun getHypeBoost(userID: String, creatorID: String): Double {
        return checkSubscription(userID, creatorID).hypeBoost
    }

    suspend fun hasPerk(perk: SubscriptionPerk, userID: String, creatorID: String): Boolean {
        return checkSubscription(userID, creatorID).perks.contains(perk)
    }

    fun clearCache() { subscriptionCache.clear() }

    fun clearCache(subscriberID: String, creatorID: String) {
        subscriptionCache.remove("${subscriberID}_${creatorID}")
    }
}

// MARK: - Errors

sealed class SubscriptionError(message: String) : Exception(message) {
    object SubscriptionsNotEnabled : SubscriptionError("This creator hasn't enabled subscriptions")
    object InvalidPrice : SubscriptionError("Invalid subscription price")
    object AlreadySubscribed : SubscriptionError("You're already subscribed")
    object NotSubscribed : SubscriptionError("You're not subscribed to this creator")
    object InsufficientCoins : SubscriptionError("Not enough Hype Coins")
}