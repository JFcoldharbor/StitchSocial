package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Date

enum class ReferralStatus(val rawValue: String) {
    PENDING("pending"), COMPLETED("completed"), EXPIRED("expired"), FAILED("failed");
    val displayName: String get() = when (this) {
        PENDING -> "Pending"; COMPLETED -> "Completed"; EXPIRED -> "Expired"; FAILED -> "Failed"
    }
    companion object { fun fromRawValue(v: String): ReferralStatus = values().find { it.rawValue == v } ?: PENDING }
}

enum class ReferralSourceType(val rawValue: String) {
    LINK("link"), DEEPLINK("deeplink"), MANUAL("manual"), SHARE("share"), ORGANIC("organic");
    companion object { fun fromRawValue(v: String): ReferralSourceType = values().find { it.rawValue == v } ?: MANUAL }
}

data class ReferralStats(
    val totalReferrals: Int, val completedReferrals: Int, val pendingReferrals: Int,
    val cloutEarned: Int, val hypeRatingBonus: Double, val rewardsMaxed: Boolean,
    val referralCode: String, val referralLink: String, val monthlyReferrals: Int,
    val recentReferrals: List<ReferralInfo>
)

data class ReferralInfo(
    val id: String, val refereeID: String?, val refereeUsername: String?,
    val status: ReferralStatus, val createdAt: Date, val completedAt: Date?,
    val cloutAwarded: Int, val platform: String, val sourceType: ReferralSourceType
)

data class ReferralLink(val code: String, val universalLink: String, val deepLink: String, val shareText: String, val expiresAt: Date)

data class ReferralProcessingResult(
    val success: Boolean, val referralID: String?, val cloutAwarded: Int,
    val hypeBonus: Double, val rewardsMaxed: Boolean, val message: String,
    val error: String?, val referrerID: String?
)

class ReferralService {

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val cloutPerReferral = 100
    private val maxCloutFromReferrals = 1000
    private val hypeRatingBonusPerReferral = 0.001
    private val referralExpirationDays = 30
    private val baseURL = "https://stitchsocial.app"
    private val deepLinkScheme = "stitchsocial"
    private val validationCache = mutableMapOf<String, Boolean>()

    suspend fun generateReferralLink(userID: String): ReferralLink {
        val userDoc = db.collection("users").document(userID).get().await()
        if (!userDoc.exists()) throw Exception("User not found")
        val data = checkNotNull(userDoc.data) { "User data missing" }

        val existingCode = getString(data, "referralCode")
        val referralCode: String = if (existingCode.isNotBlank()) {
            existingCode
        } else {
            val newCode = generateUniqueReferralCode()
            val updateMap = hashMapOf<String, Any>("referralCode" to newCode, "referralCreatedAt" to Timestamp.now())
            db.collection("users").document(userID).update(updateMap).await()
            newCode
        }

        return ReferralLink(
            code = referralCode,
            universalLink = "$baseURL/invite/$referralCode",
            deepLink = "$deepLinkScheme://invite/$referralCode",
            shareText = generateShareText(referralCode),
            expiresAt = Date(System.currentTimeMillis() + referralExpirationDays.toLong() * 24 * 60 * 60 * 1000)
        )
    }

    suspend fun processReferralSignup(
        referralCode: String, newUserID: String,
        platform: String = "android", sourceType: String = "manual"
    ): ReferralProcessingResult {
        println("🔥 REFERRAL: Processing $referralCode for $newUserID")

        if (!isValidCodeFormat(referralCode)) return fail("Invalid code format", "INVALID_CODE_FORMAT")

        val referrerQuery = db.collection("users").whereEqualTo("referralCode", referralCode).limit(1).get().await()
        if (referrerQuery.isEmpty) return fail("Code not found", "CODE_NOT_FOUND")

        val referrerDoc = referrerQuery.documents.first()
        val referrerID: String = referrerDoc.id
        if (referrerID == newUserID) return fail("Cannot refer yourself", "SELF_REFERRAL")

        val newUserDoc = db.collection("users").document(newUserID).get().await()
        val newUserData = newUserDoc.data
        if (newUserData != null) {
            val existingInvitedBy = getString(newUserData, "invitedBy")
            if (existingInvitedBy.isNotBlank()) return fail("Already referred", "ALREADY_REFERRED")
        }

        val rd = referrerDoc.data
        val currentReferralCount = if (rd != null) toLong(rd["referralCount"]).toInt() else 0
        val currentCloutEarned = if (rd != null) toLong(rd["referralCloutEarned"]).toInt() else 0
        val currentClout = if (rd != null) toLong(rd["clout"]).toInt() else 0
        val currentHypeBonus = if (rd != null) rd["hypeRatingBonus"] as? Double ?: 0.0 else 0.0
        val rewardsAlreadyMaxed = if (rd != null) rd["referralRewardsMaxed"] as? Boolean ?: false else false

        val cloutToAward = if (rewardsAlreadyMaxed || currentCloutEarned >= maxCloutFromReferrals) 0
        else minOf(cloutPerReferral, maxCloutFromReferrals - currentCloutEarned)
        val newCloutEarned = currentCloutEarned + cloutToAward
        val newHypeBonus = currentHypeBonus + hypeRatingBonusPerReferral
        val newReferralCount = currentReferralCount + 1
        val rewardsMaxed = newCloutEarned >= maxCloutFromReferrals
        val referralID = "ref_${newUserID}_$referrerID"

        return try {
            val referralRef = db.collection("referrals").document(referralID)
            val referrerRef = db.collection("users").document(referrerID)
            val newUserRef = db.collection("users").document(newUserID)
            val followingRef = db.collection("users").document(referrerID).collection("following").document(newUserID)
            val followersRef = db.collection("users").document(newUserID).collection("followers").document(referrerID)

            val referralData = hashMapOf<String, Any>(
                "id" to referralID, "referrerID" to referrerID, "refereeID" to newUserID,
                "referralCode" to referralCode, "status" to ReferralStatus.COMPLETED.rawValue,
                "cloutAwarded" to cloutToAward, "platform" to platform, "sourceType" to sourceType,
                "createdAt" to Timestamp.now(), "completedAt" to Timestamp.now()
            )
            val referrerUpdate = hashMapOf<String, Any>(
                "referralCount" to newReferralCount, "referralCloutEarned" to newCloutEarned,
                "clout" to (currentClout + cloutToAward), "hypeRatingBonus" to newHypeBonus,
                "referralRewardsMaxed" to rewardsMaxed, "followerCount" to FieldValue.increment(1L),
                "updatedAt" to Timestamp.now()
            )
            val newUserUpdate = hashMapOf<String, Any>(
                "invitedBy" to referrerID, "followingCount" to FieldValue.increment(1L)
            )
            val followingData = hashMapOf<String, Any>("userID" to newUserID, "followedAt" to Timestamp.now())
            val followersData = hashMapOf<String, Any>("userID" to referrerID, "followedAt" to Timestamp.now())

            db.runTransaction { tx ->
                tx.set(referralRef, referralData)
                tx.update(referrerRef, referrerUpdate)
                tx.update(newUserRef, newUserUpdate)
                tx.set(followingRef, followingData)
                tx.set(followersRef, followersData)
                null
            }.await()

            println("✅ REFERRAL: $referralCode done — $referrerID +$cloutToAward clout")
            ReferralProcessingResult(true, referralID, cloutToAward, hypeRatingBonusPerReferral, rewardsMaxed,
                if (rewardsMaxed) "Processed! (Cap reached)" else "Success! +$cloutToAward clout", null, referrerID)

        } catch (e: Exception) {
            println("❌ REFERRAL: Transaction failed — ${e.message}")
            ReferralProcessingResult(false, null, 0, 0.0, false, "Transaction failed", e.message, null)
        }
    }

    suspend fun getUserReferralStats(userID: String): ReferralStats {
        val userDoc = db.collection("users").document(userID).get().await()
        if (!userDoc.exists()) throw Exception("User not found")
        val data = checkNotNull(userDoc.data) { "User data missing" }

        val referralCode = getString(data, "referralCode")
        val referralCount = toLong(data["referralCount"]).toInt()
        val cloutEarned = toLong(data["referralCloutEarned"]).toInt()
        val hypeBonus = data["hypeRatingBonus"] as? Double ?: 0.0
        val rewardsMaxed = data["referralRewardsMaxed"] as? Boolean ?: false

        val thirtyDaysAgo = Date(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)

        val referralDocs = db.collection("referrals")
            .whereEqualTo("referrerID", userID)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(10).get().await()

        var pendingCount = 0
        var completedCount = 0
        var monthlyCount = 0
        val recentReferrals = mutableListOf<ReferralInfo>()

        for (doc in referralDocs.documents) {
            val d = doc.data ?: continue
            val status = ReferralStatus.fromRawValue(getString(d, "status"))
            val createdAtTs = d["createdAt"]
            val createdAt: Date = if (createdAtTs is Timestamp) createdAtTs.toDate() else Date()
            val completedAtTs = d["completedAt"]
            val completedAt: Date? = if (completedAtTs is Timestamp) completedAtTs.toDate() else null

            if (status == ReferralStatus.PENDING) pendingCount++
            if (status == ReferralStatus.COMPLETED) completedCount++
            if (createdAt.after(thirtyDaysAgo)) monthlyCount++

            recentReferrals.add(ReferralInfo(
                id = doc.id, refereeID = d["refereeID"] as? String, refereeUsername = null,
                status = status, createdAt = createdAt, completedAt = completedAt,
                cloutAwarded = toLong(d["cloutAwarded"]).toInt(),
                platform = getString(d, "platform").ifBlank { "unknown" },
                sourceType = ReferralSourceType.fromRawValue(getString(d, "sourceType"))
            ))
        }

        return ReferralStats(
            totalReferrals = referralCount, completedReferrals = completedCount,
            pendingReferrals = pendingCount, cloutEarned = cloutEarned,
            hypeRatingBonus = hypeBonus, rewardsMaxed = rewardsMaxed,
            referralCode = referralCode,
            referralLink = if (referralCode.isNotBlank()) "$baseURL/invite/$referralCode" else "",
            monthlyReferrals = monthlyCount, recentReferrals = recentReferrals
        )
    }

    suspend fun validateReferralCode(code: String): Boolean {
        if (!isValidCodeFormat(code)) return false
        val cached = validationCache[code]
        if (cached != null) return cached
        val query = db.collection("users").whereEqualTo("referralCode", code).limit(1).get().await()
        val isValid = !query.isEmpty
        validationCache[code] = isValid
        return isValid
    }

    suspend fun processOrganicSignup(newUserID: String, platform: String = "android") {
        try {
            val data = hashMapOf<String, Any?>(
                "id" to "organic_$newUserID", "referrerID" to null, "refereeID" to newUserID,
                "referralCode" to null, "status" to ReferralStatus.COMPLETED.rawValue,
                "sourceType" to ReferralSourceType.ORGANIC.rawValue, "platform" to platform,
                "createdAt" to Timestamp.now(), "completedAt" to Timestamp.now(), "cloutAwarded" to 0
            )
            db.collection("referrals").document("organic_$newUserID").set(data).await()
            println("📊 REFERRAL: Organic tracked for $newUserID")
        } catch (e: Exception) {
            println("⚠️ REFERRAL: Organic tracking failed — ${e.message}")
        }
    }

    private suspend fun generateUniqueReferralCode(): String {
        repeat(10) {
            val code = generateCodeString()
            val existing = db.collection("users").whereEqualTo("referralCode", code).limit(1).get().await()
            if (existing.isEmpty) return code
        }
        throw Exception("Failed to generate unique code after 10 attempts")
    }

    private fun generateCodeString(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    private fun isValidCodeFormat(code: String): Boolean =
        code.length in 4..12 && code.matches(Regex("[A-Z0-9]+"))

    private fun getString(data: Map<String, Any>?, key: String): String {
        if (data == null) return ""
        val v = data[key]
        return if (v is String) v else ""
    }

    private fun toLong(value: Any?): Long {
        if (value is Long) return value
        if (value is Int) return value.toLong()
        if (value is Double) return value.toLong()
        return 0L
    }

    private fun fail(message: String, error: String): ReferralProcessingResult =
        ReferralProcessingResult(false, null, 0, 0.0, false, message, error, null)

    private fun generateShareText(code: String) = """
🎬 Welcome to Stitch Social! 🎬

🍎 iPhone: Download TestFlight then https://testflight.apple.com/join/cXbWreGc
🤖 Android: https://play.google.com/store/apps/details?id=com.stitchsocial.club

🎁 Enter invite code at signup: $code

Happy Stitching! 🚀""".trimIndent()
}