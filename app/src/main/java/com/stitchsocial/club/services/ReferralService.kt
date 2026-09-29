package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.stitchsocial.club.AppConfig
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.Date
import com.stitchsocial.club.BuildConfig

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
    // stitchsocial.ME. This was hardcoded to `stitchsocial.app`, a live site
    // belonging to an unrelated product, so every referral link Android ever
    // generated sent the invitee to someone else's homepage — and could never
    // open this app, since we don't control that domain's assetlinks. iOS had
    // the identical bug.
    //
    // Reads AppConfig rather than keeping its own copy; the app already had the
    // right value one file away.
    private val baseURL = AppConfig.URLs.BASE
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

    /**
     * The reward is the server's — iOS parity.
     *
     * This ran the whole thing from the new user's phone: a transaction writing
     * referralCount, referralCloutEarned, an absolute clout total,
     * hypeRatingBonus and followerCount onto the REFERRER's user document. The
     * account being paid was edited by somebody else's client and the amount was
     * decided there, so anyone could call it naming themselves as referrer.
     *
     * completeReferral decides who is paid, how much, and whether it already
     * happened. The auto-follow goes with it as follow documents only —
     * onFollowWritten derives the counts from those.
     */
    suspend fun processReferralSignup(
        referralCode: String, newUserID: String,
        platform: String = "android", sourceType: String = "manual"
    ): ReferralProcessingResult {
        if (!isValidCodeFormat(referralCode)) return fail("Invalid code format", "INVALID_CODE_FORMAT")

        return try {
            val result = FirebaseFunctions.getInstance("us-central1")
                .getHttpsCallable("completeReferral")
                .call(mapOf("referralCode" to referralCode, "platform" to platform))
                .await()

            @Suppress("UNCHECKED_CAST")
            val data = (result.data as? Map<String, Any?>) ?: emptyMap()
            ReferralProcessingResult(
                success = data["success"] as? Boolean ?: false,
                referralID = null,
                cloutAwarded = (data["cloutAwarded"] as? Number)?.toInt() ?: 0,
                hypeBonus = 0.0,
                rewardsMaxed = data["rewardsMaxed"] as? Boolean ?: false,
                message = "Referral applied",
                error = null,
                referrerID = data["referrerID"] as? String
            )
        } catch (e: Exception) {
            fail(e.message ?: "Referral failed", "SERVER_ERROR")
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
            if (BuildConfig.DEBUG) { println("📊 REFERRAL: Organic tracked for $newUserID") }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("⚠️ REFERRAL: Organic tracking failed — ${e.message}") }
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

    /**
     * The text that actually gets shared (iOS parity).
     *
     * This used to list both store URLs and ask the invitee to remember a code
     * and type it at signup, while `universalLink` — computed right above — went
     * unused. Nobody ever received a referral link; they received homework, and
     * anyone who forgot the code signed up as organic with the referrer never
     * credited.
     *
     * One link now. With the app installed it opens straight to signup with the
     * code filled in; without it, the invite page carries both store links and
     * shows the code. The code stays in the text for anyone doing it by hand.
     */
    private fun generateShareText(code: String) = """
Come join me on Stitch Social.

$baseURL/invite/$code

That link sets you up automatically. If you'd rather do it the long way, sign up and enter the invite code $code.""".trimIndent()
}