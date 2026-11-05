package com.stitchsocial.club.landing

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.isPubliclyVisible
import com.stitchsocial.club.services.TeenSafety
import com.stitchsocial.club.services.VideoServiceImpl
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * A callable refused. `reason` is the server's message ("already_claimed",
 * "not_on_site"…); `status` the gRPC-style code ("RESOURCE_EXHAUSTED"), which
 * is all a rate limit carries.
 */
class LandingException(val reason: String, val status: String = "UNKNOWN") : Exception(reason)

/**
 * The app's side of the Landings callables (LANDINGS_CONTRACT) plus the two
 * Firestore reads the guest screen listens on.
 *
 * Every write goes through a callable — perks, claims and visits are
 * server-only, because each one is worth something at a register. The client
 * reads `perks` directly (owner read) so a perk issued by a trigger shows up
 * the moment it exists, rather than on the next poll.
 *
 * Layer 4: Core Services
 */
object LandingService {

    private val functions by lazy { FirebaseFunctions.getInstance("us-central1") }
    private val db by lazy { FirebaseFirestore.getInstance("stitchfin") }
    private val auth get() = FirebaseAuth.getInstance()

    /** Landings run on Atlanta time: visit and perk ids are keyed by this day. */
    private val zone: ZoneId = ZoneId.of("America/New_York")

    /** YYYYMMDD in America/New_York — the day key in visit and post-perk ids. */
    fun todayKey(): String = LocalDate.now(zone).format(DateTimeFormatter.BASIC_ISO_DATE)

    fun welcomePerkID(landingID: String, uid: String) = "welcome_${landingID}_$uid"
    fun todayPostPerkID(landingID: String, uid: String) = "post_${landingID}_${uid}_${todayKey()}"

    /** An artist Landing's post perk and visit are keyed by the stop's day, not New York's. */
    fun postPerkID(landingID: String, uid: String, dayKey: String) = "post_${landingID}_${uid}_$dayKey"
    fun visitID(landingID: String, uid: String, dayKey: String) = "${landingID}_${uid}_$dayKey"

    /** Start of today, local to the Landing — "Tonight at …" means since then. */
    fun startOfToday(): Date = Date.from(LocalDate.now(zone).atStartOfDay(zone).toInstant())

    /**
     * ANDROID_ID for landing_claimWelcome's one-per-device rule. The server
     * hashes it; it is never stored as is.
     */
    @SuppressLint("HardwareIds")
    fun deviceID(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

    // ---- Calls ----

    @Suppress("UNCHECKED_CAST")
    internal suspend fun call(name: String, payload: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        try {
            val result = functions.getHttpsCallable(name)
                .call(payload.filterValues { it != null })
                .await()
            return result.data as? Map<String, Any?> ?: emptyMap()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("🍽️ LANDING: $name failed — ${e.message}") }
            val fe = e as? FirebaseFunctionsException
            // The server puts its reason in the message ("already_claimed");
            // the code alone can't tell device_limit from a rate limit.
            val reason = fe?.message?.trim()?.takeIf { it.isNotBlank() } ?: "unavailable"
            throw LandingException(reason, fe?.code?.name ?: "UNAVAILABLE")
        }
    }

    /** null when there is no Landing at that slug. */
    suspend fun resolve(slug: String): Landing? =
        Landing.from(call("landing_resolve", mapOf("slug" to slug))["landing"])

    /** The QR scan the website recorded before sending this phone to the store. */
    suspend fun claimHandoff(): Pair<String, String?>? {
        val data = call("landing_claimHandoff")
        val slug = (data["slug"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return slug to (data["spot"] as? String)?.takeIf { it.isNotBlank() }
    }

    suspend fun nearby(lat: Double, lng: Double, accuracyM: Double): List<NearbyLanding> {
        val data = call("landing_nearby", mapOf("lat" to lat, "lng" to lng, "accuracyM" to accuracyM))
        return (data["landings"] as? List<*>)?.mapNotNull { NearbyLanding.from(it) } ?: emptyList()
    }

    /**
     * Live Landings whose name has a word starting with the query (2–40
     * characters; anything else returns nothing without a call).
     */
    suspend fun search(query: String): List<LandingSearchResult> {
        val q = query.trim()
        if (q.length !in 2..40) return emptyList()
        val data = call("landing_search", mapOf("query" to q))
        return (data["landings"] as? List<*>)?.mapNotNull { LandingSearchResult.from(it) } ?: emptyList()
    }

    /** `source` is a [LandingSource] — where the guest came from (addendum 1). */
    suspend fun claimWelcome(context: Context, landingID: String, spot: String?, source: String): Perk? {
        val data = call(
            "landing_claimWelcome",
            mapOf("landingID" to landingID, "spot" to spot, "deviceID" to deviceID(context), "source" to source)
        )
        return Perk.from(data["perk"])
    }

    /**
     * Pass a location, or the access number off the sign. The result carries
     * the visit id and, per addendum 2, the visit card count and any reward
     * the check-in just earned.
     */
    suspend fun checkIn(
        landingID: String,
        spot: String?,
        lat: Double? = null,
        lng: Double? = null,
        accuracyM: Double? = null,
        accessCode: String? = null
    ): CheckInResult {
        val data = call(
            "landing_checkIn",
            mapOf(
                "landingID" to landingID, "spot" to spot,
                "lat" to lat, "lng" to lng, "accuracyM" to accuracyM,
                "accessCode" to accessCode
            )
        )
        val visitID = data["visitID"] as? String ?: throw LandingException("unavailable")
        return CheckInResult(
            visitID = visitID,
            visitDays = (data["visitDays"] as? Number)?.toInt(),
            newVisit = data["newVisit"] as? Boolean ?: false,
            perkIssued = Perk.from(data["perkIssued"]),
            stopID = (data["stopID"] as? String)?.takeIf { it.isNotBlank() },
            dayKey = (data["dayKey"] as? String)?.takeIf { it.isNotBlank() }
        )
    }

    /** The signed-in guest's visit card at this Landing (addendum 2). */
    suspend fun member(landingID: String): LandingMember =
        LandingMember.from(call("landing_member", mapOf("landingID" to landingID)))

    suspend fun activate(perkID: String): Perk? =
        Perk.from(call("perk_activate", mapOf("perkID" to perkID))["perk"])

    // ---- Owner ----

    /** The caller's Landing, or a null landing when the business has none yet. */
    suspend fun owner(): LandingOwnerData {
        val data = call("landing_owner")
        return LandingOwnerData(
            landing = Landing.from(data["landing"]),
            accessCode = data["accessCode"]?.toString(),
            screenCount = (data["screens"] as? List<*>)?.size ?: 0,
            todayStats = LandingStats.from(data["todayStats"]),
            weekStats = LandingStats.from(data["weekStats"]),
            stopAccessCodes = (data["stopAccessCodes"] as? Map<*, *>).orEmpty().entries
                .mapNotNull { (k, v) -> (k as? String)?.let { key -> v?.toString()?.let { key to it } } }
                .toMap()
        )
    }

    suspend fun setStatus(landingID: String, live: Boolean) {
        call("landing_setStatus", mapOf("landingID" to landingID, "status" to if (live) "live" else "paused"))
    }

    suspend fun pendingMoments(landingID: String): List<PendingMoment> {
        val data = call("landing_pendingMoments", mapOf("landingID" to landingID))
        return (data["moments"] as? List<*>)?.mapNotNull { PendingMoment.from(it) } ?: emptyList()
    }

    /** "approve" plays it on the TV; "report" files it with moderation. */
    suspend fun moderate(videoID: String, approve: Boolean) {
        call("landing_moderate", mapOf("videoID" to videoID, "action" to if (approve) "approve" else "report"))
    }

    suspend fun qrKitURL(landingID: String, stopID: String? = null): String? =
        call(
            "landing_qrKit",
            if (stopID != null) mapOf("landingID" to landingID, "stopID" to stopID) else mapOf("landingID" to landingID)
        )["url"] as? String

    /** Pairs the screen showing this code at stitchsocial.me/tv. */
    suspend fun pairScreen(landingID: String, code: String) {
        call("screen_pair", mapOf("landingID" to landingID, "code" to code))
    }

    // ---- Reads ----

    /**
     * The signed-in guest's perks, newest first, live. `where userID == uid`
     * is the one query the perks rule allows, and needs no composite index,
     * so screens filter by Landing on their side.
     */
    fun myPerks(): Flow<List<Perk>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val reg: ListenerRegistration = db.collection("perks")
            .whereEqualTo("userID", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    if (BuildConfig.DEBUG) { println("🍽️ LANDING: perks listener — ${err.message}") }
                    return@addSnapshotListener
                }
                val perks = snap?.documents.orEmpty()
                    .mapNotNull { Perk.from(it.data, it.id) }
                    .sortedByDescending { it.issuedAt ?: Date(0) }
                trySend(perks)
            }
        awaitClose { reg.remove() }
    }

    /**
     * Today's moments tagged to a Landing, newest first. Equality only (no
     * index needed); the day cut is applied here. Moderation-pending posts
     * stay out except the viewer's own, so a guest sees the moment they just
     * posted without anyone else seeing it before the scan.
     */
    suspend fun todaysMoments(landingID: String): List<CoreVideoMetadata> = runCatching {
        val uid = auth.currentUser?.uid
        val snap = db.collection("videos")
            .whereEqualTo("landingID", landingID)
            .limit(80)
            .get().await()
        val since = startOfToday()
        val docs = snap.documents.filter { TeenSafety.allows(it.data) }
        VideoServiceImpl().convertFirebaseToVideoMetadata(docs)
            .filter { !it.isDeleted && !it.createdAt.before(since) }
            .filter { it.isPubliclyVisible || it.creatorID == uid }
            .sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    /** Moments tagged to a Landing inside a show's window, newest first. Same rules as [todaysMoments]. */
    suspend fun moments(landingID: String, opensAt: Date, closesAt: Date): List<CoreVideoMetadata> = runCatching {
        val uid = auth.currentUser?.uid
        val snap = db.collection("videos")
            .whereEqualTo("landingID", landingID)
            .limit(80)
            .get().await()
        val docs = snap.documents.filter { TeenSafety.allows(it.data) }
        VideoServiceImpl().convertFirebaseToVideoMetadata(docs)
            .filter { !it.isDeleted && !it.createdAt.before(opensAt) && it.createdAt.before(closesAt) }
            .filter { it.isPubliclyVisible || it.creatorID == uid }
            .sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    /** The song's hook video. Null when it's gone, not public, or not for the teen lane. */
    suspend fun songHook(videoID: String): CoreVideoMetadata? = runCatching {
        val doc = db.collection("videos").document(videoID).get().await()
        if (!doc.exists() || !TeenSafety.allows(doc.data)) return@runCatching null
        VideoServiceImpl().convertFirebaseToVideoMetadata(listOf(doc))
            .firstOrNull { !it.isDeleted && it.isPubliclyVisible }
    }.getOrNull()

    /** Fan replies in the song's thread, newest first, at most 50. */
    suspend fun songReplies(hook: CoreVideoMetadata): List<CoreVideoMetadata> = runCatching {
        val uid = auth.currentUser?.uid
        val snap = db.collection("videos")
            .whereEqualTo("threadID", hook.threadID ?: hook.id)
            .limit(50)
            .get().await()
        val docs = snap.documents.filter { it.id != hook.id && TeenSafety.allows(it.data) }
        VideoServiceImpl().convertFirebaseToVideoMetadata(docs)
            .filter { !it.isDeleted && (it.isPubliclyVisible || it.creatorID == uid) }
            .sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    /** Whether a teen may see this artist's page. A failed read counts as no. */
    suspend fun accountAllowsTeens(businessID: String): Boolean = runCatching {
        if (businessID.isBlank()) return@runCatching false
        TeenSafety.allowsAccount(db.collection("users").document(businessID).get().await().data)
    }.getOrDefault(false)

    /** An artist's own posts. A teen gets only the ones marked safe. */
    suspend fun artistPosts(businessID: String): List<CoreVideoMetadata> = runCatching {
        if (businessID.isBlank()) return@runCatching emptyList()
        if (!TeenSafety.viewerIsTeen) return@runCatching restaurantPosts(businessID)
        val snap = db.collection("videos")
            .whereEqualTo("creatorID", businessID)
            .whereEqualTo("teenSafe", true)
            .limit(24)
            .get().await()
        VideoServiceImpl().convertFirebaseToVideoMetadata(snap.documents)
            .filter { !it.isDeleted && it.isPubliclyVisible && it.conversationDepth == 0 }
            .sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    private var ownsArtistCache: Pair<String, Boolean>? = null

    /**
     * An artist's account can be a personal one, which wouldn't otherwise get
     * the owner entry on its profile. Cached per account for the session.
     */
    suspend fun ownsArtistLanding(uid: String): Boolean {
        if (uid.isBlank()) return false
        ownsArtistCache?.takeIf { it.first == uid }?.let { return it.second }
        return runCatching {
            val owns = db.collection("landings")
                .whereEqualTo("businessID", uid)
                .limit(5)
                .get().await()
                .documents
                .any { it.getString("kind") == "artist" && it.getString("status") != "removed" }
            ownsArtistCache = uid to owns
            owns
        }.getOrDefault(false)
    }

    /**
     * A business's live Landing, for the Landing tab on its profile. Landings
     * are public read; equality only, so no index.
     */
    suspend fun liveLandingFor(businessID: String): Landing? = runCatching {
        if (businessID.isBlank()) return@runCatching null
        db.collection("landings")
            .whereEqualTo("businessID", businessID)
            .limit(5)
            .get().await()
            .documents
            .mapNotNull { Landing.from(it.data, it.id) }
            .firstOrNull { it.isLive && it.slug.isNotBlank() }
    }.getOrNull()

    /**
     * Every live artist page a viewer may see, by name. Artists are few, so one
     * query covers Music and the followed list on Dash.
     */
    suspend fun artistPages(): List<Landing> = runCatching {
        val blocked = com.stitchsocial.club.services.BlockService.shared.blockedUserIds.value
        db.collection("landings")
            .whereEqualTo("kind", "artist")
            .whereEqualTo("status", "live")
            .limit(200)
            .get().await()
            .documents
            .mapNotNull { Landing.from(it.data, it.id) }
            .filter { it.isArtist && it.isLive && it.slug.isNotBlank() && it.businessID.isNotBlank() && it.businessID !in blocked }
            .filter { !TeenSafety.viewerIsTeen || accountAllowsTeens(it.businessID) }
            .sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    /** The restaurant's own posts, under the strip. */
    suspend fun restaurantPosts(businessID: String): List<CoreVideoMetadata> =
        if (businessID.isBlank()) emptyList()
        else VideoServiceImpl().getUserVideos(businessID, limit = 24)
            .filter { it.isPubliclyVisible && it.conversationDepth == 0 }
}
