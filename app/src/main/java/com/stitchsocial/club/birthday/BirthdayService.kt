package com.stitchsocial.club.birthday

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.NotificationRouter
import com.stitchsocial.club.services.InviteService
import com.stitchsocial.club.services.TeenSafety
import com.stitchsocial.club.ui.theme.StitchColors
import com.stitchsocial.club.views.InviteColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/*
 * BirthdayService.kt — models + services for birthday cards (BIRTHDAY_CONTRACT).
 * iOS parity with Birthday/BirthdayService.swift.
 *
 * A week out, the server offers an adult their birthday card: a 10-second
 * vertical video it makes from their photo and top clips, in one of six
 * styles. Approved, it posts as a new thread at their local midnight on the
 * day and followers stitch wishes onto it. Nothing posts without approval.
 *
 *  - BirthdayStatus / BirthdayPreview / BirthdayCard: the callables' shapes.
 *    Every field is decoded loosely — a missing one never fails the whole.
 *  - BirthdayService: the six callables. Every call carries the device's time
 *    zone, because "the day" and "midnight" are the person's, not the server's.
 *  - BirthdayCenter: the last status in memory (the profile banner reads it),
 *    the 6-hour throttle, and whether the sheet is up.
 *
 * The client never works out a date. "Friday", "tomorrow", "in 3 days" all
 * arrive in `copy` — a phone with the wrong clock, or a trip across a date
 * line, must not be able to put the wrong day on someone's birthday.
 *
 * Teens never reach any of this: the server answers ineligible for the teen
 * lane, and the client doesn't call or present it there either.
 *
 * Layer 4: Core Services
 */

// MARK: - Styles

/**
 * The six looks, in the order they're always shown. Everyone sees all six;
 * none is labelled or filtered by gender.
 */
enum class BirthdayStyle(val raw: String, val displayName: String, val placeholderTint: Color) {
    CONFETTI("confetti", "Confetti", BirthdayPalette.pink),
    NEON("neon", "Neon", Color(0.2f, 0.9f, 1.0f)),
    GLAM("glam", "Glam", Color(0.95f, 0.78f, 0.45f)),
    BLOOM("bloom", "Bloom", Color(1.0f, 0.55f, 0.7f)),
    LEGEND("legend", "Legend", BirthdayPalette.violet),
    STREET("street", "Street", Color(1.0f, 0.6f, 0.2f));

    companion object {
        fun from(raw: String?): BirthdayStyle? = entries.firstOrNull { it.raw == raw?.lowercase() }
    }
}

/** Who hears about the card when it posts. */
enum class BirthdayNotify(val raw: String, val label: String) {
    ALL("all", "All followers"),
    MUTUALS("mutuals", "Only people I follow back");

    companion object {
        fun from(raw: String?): BirthdayNotify? = entries.firstOrNull { it.raw == raw }
    }
}

/** The card's soundtrack. */
enum class BirthdayMusic(val raw: String, val label: String) {
    STYLE("style", "Birthday track"),
    CLIPS("clips", "My clips' sound")
}

// MARK: - birthday_status

enum class BirthdayPhase(val raw: String) {
    /** Pick a style (and, on the day, "Post my card now"). */
    CHOOSE("choose"),
    /** Approved; waiting for midnight. */
    SET("set"),
    /** Posted; wishes are coming in. */
    LIVE("live"),
    /** After the day: thank people. */
    THANKS("thanks");

    companion object {
        fun from(raw: String?): BirthdayPhase? = entries.firstOrNull { it.raw == raw }
    }
}

private fun str(raw: Any?): String? = (raw as? String)?.takeIf { it.isNotEmpty() }
private fun int(raw: Any?): Int? = (raw as? Number)?.toInt() ?: (raw as? String)?.toIntOrNull()
private fun dbl(raw: Any?): Double? = (raw as? Number)?.toDouble() ?: (raw as? String)?.toDoubleOrNull()

/** Server-written words. Every date-dependent phrase lives here. */
data class BirthdayCopy(
    val headline: String,
    val subline: String,
    val primary: String,
    val secondary: String? = null
) {
    companion object {
        fun parse(raw: Any?): BirthdayCopy {
            val m = raw as? Map<*, *> ?: emptyMap<String, Any?>()
            return BirthdayCopy(
                headline = str(m["headline"]).orEmpty(),
                subline = str(m["subline"]).orEmpty(),
                primary = str(m["primary"]).orEmpty(),
                secondary = str(m["secondary"])
            )
        }
    }
}

data class BirthdayCard(
    val status: Status,
    val style: BirthdayStyle? = null,
    val videoURL: String? = null,
    val thumbnailURL: String? = null,
    val videoID: String? = null,
    val notify: BirthdayNotify = BirthdayNotify.ALL,
    val postAt: Date? = null
) {
    enum class Status(val raw: String) {
        NEW("new"), RENDERING("rendering"), READY("ready"), APPROVED("approved"),
        POSTING("posting"), POSTED("posted"), SKIPPED("skipped");

        companion object {
            fun from(raw: String?): Status? = entries.firstOrNull { it.raw == raw }
        }
    }

    companion object {
        fun parse(raw: Any?): BirthdayCard {
            val m = raw as? Map<*, *> ?: emptyMap<String, Any?>()
            return BirthdayCard(
                status = Status.from(str(m["status"])) ?: Status.NEW,
                style = BirthdayStyle.from(str(m["style"])),
                videoURL = str(m["videoURL"]),
                thumbnailURL = str(m["thumbnailURL"]),
                videoID = str(m["videoID"]),
                notify = BirthdayNotify.from(str(m["notify"])) ?: BirthdayNotify.ALL,
                postAt = InviteService.parseDate(m["postAt"])
            )
        }
    }
}

data class BirthdayStatus(
    val eligible: Boolean,
    /**
     * Why not eligible — "off" (turned off in Settings), "no_birthdate",
     * "not_adult", "business", "default_birthdate", or something newer.
     */
    val reason: String?,
    /** null = nothing to show right now. */
    val phase: BirthdayPhase?,
    val autoPresent: Boolean,
    val daysUntil: Int,
    /** "YYYY-MM-DD", the person's own calendar. Kept as text, never parsed. */
    val birthday: String,
    val weekday: String,
    val copy: BirthdayCopy,
    val wishCount: Int,
    val card: BirthdayCard,
    val styles: List<BirthdayStyle>
) {
    /**
     * Settings offers the switch unless the reason says this account can't
     * have a card at all. "off" is NOT one of those — that's their own
     * choice, and the switch is how they undo it.
     */
    val showsSettingsToggle: Boolean get() = reason == null || reason !in INELIGIBLE_REASONS

    val isTurnedOff: Boolean get() = reason == "off"

    companion object {
        val INELIGIBLE_REASONS = setOf("no_birthdate", "not_adult", "business", "default_birthdate")

        fun parse(data: Map<*, *>): BirthdayStatus = BirthdayStatus(
            eligible = data["eligible"] as? Boolean ?: false,
            reason = str(data["reason"]),
            phase = BirthdayPhase.from(str(data["phase"])),
            autoPresent = data["autoPresent"] as? Boolean ?: false,
            daysUntil = int(data["daysUntil"]) ?: 0,
            birthday = str(data["birthday"]).orEmpty(),
            weekday = str(data["weekday"]).orEmpty(),
            copy = BirthdayCopy.parse(data["copy"]),
            wishCount = int(data["wishCount"]) ?: 0,
            card = BirthdayCard.parse(data["card"]),
            styles = styles(data["styles"])
        )

        /**
         * Always all six, in the canonical order. The server's list can only
         * narrow that (an unknown key is ignored, a missing one falls back).
         */
        fun styles(raw: Any?): List<BirthdayStyle> {
            val keys = (raw as? List<*>).orEmpty().mapNotNull { (it as? String)?.lowercase() }.toSet()
            val known = BirthdayStyle.entries.filter { it.raw in keys }
            return known.ifEmpty { BirthdayStyle.entries.toList() }
        }
    }
}

// MARK: - birthday_preview

data class BirthdayClip(
    val id: String,
    val videoURL: String? = null,
    val thumbnailURL: String? = null,
    val duration: Double = 0.0
) {
    companion object {
        fun parse(raw: Any?): BirthdayClip? {
            val m = raw as? Map<*, *> ?: return null
            val id = str(m["id"]) ?: return null
            return BirthdayClip(
                id = id,
                videoURL = str(m["videoURL"]),
                thumbnailURL = str(m["thumbnailURL"]),
                duration = dbl(m["duration"]) ?: 0.0
            )
        }
    }
}

data class BirthdayPreview(
    val styles: List<BirthdayStyle>,
    /** One 540×960 still per style, made with the person's own photo. */
    val previews: Map<BirthdayStyle, String>,
    /** Their top public clips, up to 12. The first three are the defaults. */
    val clips: List<BirthdayClip>
) {
    val defaultClipIDs: List<String> get() = clips.take(3).map { it.id }

    companion object {
        fun parse(data: Map<*, *>): BirthdayPreview {
            val stills = mutableMapOf<BirthdayStyle, String>()
            (data["previews"] as? Map<*, *>)?.forEach { (key, value) ->
                val style = BirthdayStyle.from(key as? String)
                val url = str(value)
                if (style != null && url != null) stills[style] = url
            }
            return BirthdayPreview(
                styles = BirthdayStatus.styles(data["styles"]),
                previews = stills,
                clips = (data["clips"] as? List<*>).orEmpty().mapNotNull(BirthdayClip::parse)
            )
        }
    }
}

// MARK: - birthday_render / birthday_approve

/** What "Make my card" asks for. All but the style are optional. */
data class BirthdayRenderRequest(
    val style: BirthdayStyle,
    val clipIDs: List<String> = emptyList(),
    /** A clip whose frame stands in for the profile photo. null = profile photo. */
    val photoClipID: String? = null,
    val music: BirthdayMusic = BirthdayMusic.STYLE,
    val notify: BirthdayNotify = BirthdayNotify.ALL
)

data class BirthdayRenderResult(val videoURL: String, val thumbnailURL: String?)

data class BirthdayApproveResult(
    /** true when it went up immediately ("Post my card now", on the day). */
    val postedNow: Boolean,
    val postAt: Date?
)

// MARK: - Callables

object BirthdayService {

    private val functions by lazy { FirebaseFunctions.getInstance("us-central1") }

    private val timeZone: String get() = TimeZone.getDefault().id

    /**
     * One callable round trip. A thrown FirebaseFunctionsException carries the
     * server's human message, which the screens show as is (see [message]).
     */
    private suspend fun call(
        name: String,
        data: Map<String, Any?> = emptyMap(),
        timeoutSeconds: Long? = null
    ): Map<*, *> {
        val payload = data + ("timeZone" to timeZone)
        val callable = functions.getHttpsCallable(name)
        timeoutSeconds?.let { callable.setTimeout(it, TimeUnit.SECONDS) }
        return try {
            callable.call(payload).await().data as? Map<*, *> ?: emptyMap<String, Any?>()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) println("🎂 BIRTHDAY: $name failed — ${e.message}")
            throw e
        }
    }

    suspend fun status(): BirthdayStatus = BirthdayStatus.parse(call("birthday_status"))

    suspend fun preview(): BirthdayPreview = BirthdayPreview.parse(call("birthday_preview"))

    /**
     * Takes 15–45s on the server; the timeout leaves room for a slow network
     * on top of a slow render.
     */
    suspend fun render(request: BirthdayRenderRequest): BirthdayRenderResult {
        val data = mutableMapOf<String, Any?>(
            "style" to request.style.raw,
            "music" to request.music.raw
        )
        if (request.clipIDs.isNotEmpty()) data["clipIDs"] = request.clipIDs.take(3)
        request.photoClipID?.let { data["photoClipID"] = it }
        val response = call("birthday_render", data, timeoutSeconds = 150)
        val url = str(response["videoURL"]) ?: throw IllegalStateException(MALFORMED)
        return BirthdayRenderResult(url, str(response["thumbnailURL"]))
    }

    suspend fun approve(notify: BirthdayNotify): BirthdayApproveResult {
        val response = call("birthday_approve", mapOf("notify" to notify.raw))
        return BirthdayApproveResult(
            postedNow = str(response["status"]) == "posted",
            postAt = InviteService.parseDate(response["postAt"])
        )
    }

    enum class DismissMode(val raw: String) { LATER("later"), YEAR("year") }

    suspend fun dismiss(mode: DismissMode) {
        call("birthday_dismiss", mapOf("mode" to mode.raw))
    }

    /** The Settings switch. Returns what the server stored. */
    suspend fun setEnabled(enabled: Boolean): Boolean {
        val response = call("birthday_settings", mapOf("enabled" to enabled))
        return response["enabled"] as? Boolean ?: enabled
    }

    private const val MALFORMED = "Something went wrong making your card. Try again."

    /**
     * What a screen says when a call fails. The server's HttpsError message
     * when it sent one; otherwise a plain sentence rather than a gRPC code
     * name ("DEADLINE_EXCEEDED"), which is all Android has where iOS has a
     * localized description.
     */
    fun message(e: Throwable): String {
        val raw = e.message?.trim().orEmpty()
        if (e is FirebaseFunctionsException) {
            if (raw.isNotEmpty() && raw != e.code.name) return raw
            return when (e.code) {
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "This is taking longer than it should. Try again."
                FirebaseFunctionsException.Code.UNAVAILABLE ->
                    "Couldn't reach Stitch. Check your connection and try again."
                else -> MALFORMED
            }
        }
        return raw.ifEmpty { MALFORMED }
    }
}

// MARK: - Center

/**
 * The app's one copy of "where is this person's birthday at": the last
 * status (for the profile banner and Settings), the throttle, and the sheet.
 *
 * Compose state, so the banner, Settings and MainScreen's host react the
 * moment it changes. Touched only from the main thread.
 */
object BirthdayCenter {

    /** The last status for the signed-in user. null until the first answer, and after sign-out. */
    var status by mutableStateOf<BirthdayStatus?>(null)
        private set

    /** MainScreen presents the Birthday sheet from this. */
    var isPresenting by mutableStateOf(false)

    private var userID: String? = null
    private var lastFetch: Long? = null
    private var inFlight: Deferred<BirthdayStatus?>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Launch and foreground both check, but at most this often. A birthday action refreshes regardless. */
    private const val THROTTLE_MS = 6L * 60 * 60 * 1000

    /**
     * The status, fetched if it's older than the throttle (or `force`).
     * Returns null for teens, before sign-in, and when the call fails — in
     * which case the previous status stays as it was.
     */
    suspend fun refresh(userID: String?, isTeen: Boolean, force: Boolean = false): BirthdayStatus? {
        if (userID.isNullOrEmpty() || isTeen) {
            reset()
            return null
        }
        if (this.userID != userID) {
            // A different account: nothing of the last one carries over.
            reset()
            this.userID = userID
        }
        val last = lastFetch
        if (!force && last != null && System.currentTimeMillis() - last < THROTTLE_MS) return status
        inFlight?.let { return awaitQuietly(it) }

        val task = scope.async { runCatching { BirthdayService.status() }.getOrNull() }
        inFlight = task
        val fresh = awaitQuietly(task)
        if (inFlight === task) inFlight = null
        // Signed out (or switched) while waiting — drop it.
        if (this.userID != userID) return null
        if (fresh != null) {
            status = fresh
            lastFetch = System.currentTimeMillis()
        }
        return fresh
    }

    /** A fetch cancelled by reset() reads as "no answer", not as the caller being cancelled. */
    private suspend fun awaitQuietly(task: Deferred<BirthdayStatus?>): BirthdayStatus? = try {
        task.await()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    }

    /** After any birthday action: always ask again. */
    suspend fun refreshAfterAction(): BirthdayStatus? =
        refresh(userID, isTeen = TeenSafety.viewerIsTeen, force = true)

    fun present() {
        if (TeenSafety.viewerIsTeen) return
        isPresenting = true
    }

    /** Sign-out. */
    fun reset() {
        inFlight?.cancel()
        inFlight = null
        status = null
        userID = null
        lastFetch = null
        isPresenting = false
    }

    // MARK: Auto-present, once a day

    /**
     * The server decides `autoPresent`; this only stops the same offer being
     * pushed up twice in one day when someone closes the sheet with a swipe
     * (which the server never hears about) and comes back an hour later.
     */
    fun shouldAutoPresent(context: Context, status: BirthdayStatus): Boolean {
        val phase = status.phase ?: return false
        val uid = userID ?: return false
        if (!status.autoPresent) return false
        return prefs(context).getString(autoPresentKey(uid), null) != autoPresentToken(status, phase)
    }

    fun noteAutoPresented(context: Context, status: BirthdayStatus) {
        val phase = status.phase ?: return
        val uid = userID ?: return
        prefs(context).edit().putString(autoPresentKey(uid), autoPresentToken(status, phase)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("stitch_birthday", Context.MODE_PRIVATE)

    private fun autoPresentKey(uid: String) = "birthdayAutoPresented_$uid"

    /**
     * Same birthday, same phase, same local day. The local day here is only a
     * de-dupe key — nothing shown to anyone is derived from it.
     */
    private fun autoPresentToken(status: BirthdayStatus, phase: BirthdayPhase): String {
        val c = Calendar.getInstance()
        val day = "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH) + 1}-${c.get(Calendar.DAY_OF_MONTH)}"
        return "${status.birthday}|${phase.raw}|$day"
    }

    // MARK: Leaving for the thread

    /**
     * "See your wishes" / thanks: close the sheet and open the card's thread
     * the way a tapped link would — through the router, which loads it,
     * clears the screen and presents the player.
     */
    fun openThread(videoID: String?) {
        isPresenting = false
        if (videoID.isNullOrEmpty()) return
        NotificationRouter.request(NotificationRouter.TYPE_SHARED_LINK, mapOf("videoID" to videoID))
    }
}

// MARK: - Look

/**
 * The birthday screens' palette: the invite boards' pink and violet (the
 * app's own celebratory pair), on the same near-black.
 */
object BirthdayPalette {
    val magenta = StitchColors.primary
    val pink = InviteColors.pink
    val violet = InviteColors.violet
    val gold = Color(1.0f, 0.8f, 0.35f)
    val cardFill = Color(0xFF121016)
    val closeFill = Color(0xFF1A1820)

    /** iOS StitchColors.secondary / .tertiary (brand blue, mint). Android's own tertiary is a lilac. */
    private val blue = Color(0.2f, 0.6f, 1.0f)
    private val mint = Color(0.4f, 0.8f, 0.6f)

    val accentGradient: Brush get() = Brush.horizontalGradient(listOf(pink, violet))
    val ringColors = listOf(pink, gold, violet, blue, pink)
    val confettiColors = listOf(pink, violet, gold, blue, mint, Color.White)
}

/**
 * A 🎂 ring tapped on a profile header: that person's birthday card, through
 * the same route a birthday_day_of notification takes (load, clear the
 * screen, play).
 */
fun openBirthdayPost(videoID: String?) {
    if (videoID.isNullOrEmpty()) return
    NotificationRouter.request(NotificationRouter.TYPE_SHARED_LINK, mapOf("videoID" to videoID))
}
