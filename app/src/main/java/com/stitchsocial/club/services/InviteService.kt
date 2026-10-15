package com.stitchsocial.club.services

import android.content.Context
import android.graphics.Bitmap
import com.google.firebase.Timestamp
import com.google.firebase.functions.FirebaseFunctions
import com.stitchsocial.club.AppConfig
import com.stitchsocial.club.BuildConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * Invite links (INVITE_CONTRACT, Apps section).
 *
 * `resolveInvite({code})` is the same no-auth callable the /invite web page
 * reads: who sent the invite, their most-hyped eligible video, and the boost
 * terms. Every field past `found` is optional here — the server shape is
 * additive and an older deploy simply leaves the new ones out.
 *
 * Layer 4: Core Services
 */
data class InviteHeroVideo(
    val videoID: String,
    val videoURL: String,
    val thumbnailURL: String,
    val hypeCount: Int,
    val aspectRatio: Double
)

data class InviteBoostTerms(val windowDays: Int, val hours: Int) {
    companion object {
        /** `app_config/invite` defaults, used when the server leaves the terms out. */
        val DEFAULT = InviteBoostTerms(windowDays = 7, hours = 24)
    }
}

data class InviteResolution(
    val found: Boolean,
    val code: String,
    val username: String,
    val displayName: String,
    val profileImageURL: String?,
    val isVerified: Boolean,
    val bio: String,
    val cloutTotal: Int,
    val stitcherCount: Int,
    val heroVideo: InviteHeroVideo?,
    val boost: InviteBoostTerms?
) {
    /** "Teddy" for "Teddy Ruks"; falls back to the handle. */
    val firstName: String
        get() = displayName.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
            ?: username
}

object InviteService {

    fun inviteURL(code: String): String = "${AppConfig.URLs.BASE}/invite/$code"

    suspend fun resolveInvite(code: String): InviteResolution? {
        val cleaned = code.trim().uppercase()
        if (cleaned.isEmpty()) return null
        return try {
            val result = FirebaseFunctions.getInstance("us-central1")
                .getHttpsCallable("resolveInvite")
                .call(mapOf("code" to cleaned))
                .await()
            @Suppress("UNCHECKED_CAST")
            val data = (result.data as? Map<String, Any?>) ?: return null
            parseResolution(cleaned, data)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) println("⚠️ INVITE: resolveInvite failed — ${e.message}")
            null
        }
    }

    internal fun parseResolution(code: String, data: Map<String, Any?>): InviteResolution {
        val hero = (data["heroVideo"] as? Map<*, *>)?.let { h ->
            val id = h["videoID"] as? String
            val url = h["videoURL"] as? String
            if (id.isNullOrBlank()) null else InviteHeroVideo(
                videoID = id,
                videoURL = url.orEmpty(),
                thumbnailURL = (h["thumbnailURL"] as? String).orEmpty(),
                hypeCount = (h["hypeCount"] as? Number)?.toInt() ?: 0,
                aspectRatio = (h["aspectRatio"] as? Number)?.toDouble() ?: (9.0 / 16.0)
            )
        }
        val boost = (data["boost"] as? Map<*, *>)?.let { b ->
            InviteBoostTerms(
                windowDays = (b["windowDays"] as? Number)?.toInt() ?: InviteBoostTerms.DEFAULT.windowDays,
                hours = (b["hours"] as? Number)?.toInt() ?: InviteBoostTerms.DEFAULT.hours
            )
        }
        return InviteResolution(
            found = data["found"] as? Boolean ?: false,
            code = code,
            username = (data["username"] as? String).orEmpty(),
            displayName = (data["displayName"] as? String).orEmpty(),
            profileImageURL = (data["profileImageURL"] as? String)?.takeIf { it.isNotBlank() },
            isVerified = data["isVerified"] as? Boolean ?: false,
            bio = (data["bio"] as? String).orEmpty(),
            cloutTotal = (data["cloutTotal"] as? Number)?.toInt() ?: 0,
            stitcherCount = (data["stitcherCount"] as? Number)?.toInt() ?: 0,
            heroVideo = hero,
            boost = boost
        )
    }

    /**
     * The raw QR module matrix for an invite link (1 px per module, no quiet
     * zone — callers draw it on white, which is the quiet zone). Error
     * correction H so a mark over the middle doesn't matter. Shared by the
     * invite QR screen and the invite-video end card.
     */
    fun qrBitmap(content: String): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i ->
            if (matrix.get(i % w, i / w)) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /**
     * A callable hands a Firestore Timestamp back as `{_seconds, _nanoseconds}`
     * (or `seconds`), but a server may equally send millis or an ISO string.
     */
    fun parseDate(raw: Any?): Date? = when (raw) {
        null -> null
        is Timestamp -> raw.toDate()
        is Date -> raw
        is Number -> raw.toLong().let { if (it < 100_000_000_000L) Date(it * 1000) else Date(it) }
        is String -> runCatching {
            java.time.Instant.parse(raw).toEpochMilli().let(::Date)
        }.getOrNull() ?: raw.toLongOrNull()?.let { parseDate(it) }
        is Map<*, *> -> ((raw["_seconds"] ?: raw["seconds"]) as? Number)?.let { Date(it.toLong() * 1000) }
        else -> null
    }
}

/**
 * The one-time Welcome after a signup whose `completeReferral` succeeded.
 *
 * LoginView writes it the moment the referral lands; MainScreen shows it once
 * the new account is past the age gate (which is several screens and possibly
 * a process death later — hence SharedPreferences, like PendingReferral).
 * Keyed to the uid so it can never surface on a different account.
 */
object InviteWelcomeStore {

    data class Pending(
        val uid: String,
        val code: String,
        val referrerID: String?,
        val followed: Boolean,
        val boostExpiresAt: Date?,
        val boostHours: Int?
    )

    private const val PREFS = "stitch_invite_welcome"

    /** Bumped on every save, so a Welcome host already on screen re-checks
     *  (a social signup can reach the main UI before its referral returns). */
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    /** True while the Welcome is on screen — MainScreen won't auto-present the Birthday sheet over it. */
    val showing = kotlinx.coroutines.flow.MutableStateFlow(false)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, pending: Pending) {
        prefs(context).edit()
            .putString("uid", pending.uid)
            .putString("code", pending.code)
            .putString("referrerID", pending.referrerID)
            .putBoolean("followed", pending.followed)
            .putLong("boostExpiresAt", pending.boostExpiresAt?.time ?: 0L)
            .putInt("boostHours", pending.boostHours ?: 0)
            .putBoolean("hasBoost", pending.boostExpiresAt != null || pending.boostHours != null)
            .apply()
        _version.value = _version.value + 1
    }

    fun pending(context: Context, uid: String): Pending? {
        val p = prefs(context)
        val storedUid = p.getString("uid", null) ?: return null
        if (storedUid != uid) return null
        val hasBoost = p.getBoolean("hasBoost", false)
        return Pending(
            uid = storedUid,
            code = p.getString("code", null).orEmpty(),
            referrerID = p.getString("referrerID", null),
            followed = p.getBoolean("followed", false),
            boostExpiresAt = p.getLong("boostExpiresAt", 0L).takeIf { hasBoost && it > 0L }?.let(::Date),
            boostHours = p.getInt("boostHours", 0).takeIf { hasBoost && it > 0 }
        )
    }

    /** Shown (or dismissed): never again. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
