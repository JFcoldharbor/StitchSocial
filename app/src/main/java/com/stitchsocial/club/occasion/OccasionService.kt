package com.stitchsocial.club.occasion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.Timestamp
import com.google.firebase.functions.FirebaseFunctions
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.TeenSafety
import com.stitchsocial.club.services.VideoServiceImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.TimeZone

/*
 * OccasionService.kt — models + services for holiday cards (occasions).
 * iOS parity with Occasions/OccasionService.swift.
 *
 * A holiday is one official card Stitch posts for everyone to stitch onto
 * ("🎃 Show us your costume"); birthdays are each person's own. The server
 * decides everything date-shaped:
 *
 *  - OccasionState: `occasion_current`'s answer — the button at the top of
 *    Dash (your birthday week wins over a holiday, so it may be a birthday
 *    button; with neither, a holiday under two weeks away shows as a
 *    heads-up) and the holiday thread Discover features (shown even during your
 *    birthday week). Both null most days, and always for teens.
 *  - OccasionService: the callable, with the device's time zone.
 *  - OccasionCenter: the last answer in memory, a 30-minute throttle, and
 *    loading a card's video so the recorder can stitch onto it.
 *
 * The profile button ("In costume 🎃") is not here: it rides on the user doc
 * (users/{id}.occasionBadge → BasicUserInfo.occasionBadge), written by the
 * server when someone stitches onto a card.
 *
 * Layer 4: Core Services
 */

private fun str(raw: Any?): String? = (raw as? String)?.trim()?.takeIf { it.isNotEmpty() }

data class OccasionButton(
    val kind: Kind,
    val label: String,
    val occasionID: String? = null,
    val videoID: String? = null
) {
    /** UPCOMING: a holiday whose cards haven't posted yet ("🎃 Halloween cards
     *  drop tomorrow") — a heads-up with nothing to open. */
    enum class Kind(val raw: String) { BIRTHDAY("birthday"), OCCASION("occasion"), UPCOMING("upcoming") }

    companion object {
        fun parse(raw: Any?): OccasionButton? {
            val m = raw as? Map<*, *> ?: return null
            val kind = Kind.entries.firstOrNull { it.raw == str(m["kind"]) } ?: return null
            val label = str(m["label"]) ?: return null
            return OccasionButton(kind, label, str(m["occasionID"]), str(m["videoID"]))
        }
    }
}

/** The running holiday prompt: its card is a thread everyone stitches onto. */
data class OccasionThread(
    val occasionID: String,
    val name: String,
    val emoji: String,
    /** The card's video — also its thread. */
    val videoID: String,
    val prompt: String,
    val label: String,
    val thumbnailURL: String?
) {
    companion object {
        fun parse(raw: Any?): OccasionThread? {
            val m = raw as? Map<*, *> ?: return null
            val id = str(m["occasionID"]) ?: return null
            val videoID = str(m["videoID"]) ?: return null
            val name = str(m["name"]) ?: ""
            val emoji = str(m["emoji"]) ?: ""
            return OccasionThread(
                occasionID = id, name = name, emoji = emoji, videoID = videoID,
                prompt = str(m["prompt"]) ?: "",
                label = str(m["label"]) ?: "$emoji $name".trim(),
                thumbnailURL = str(m["thumbnailURL"])
            )
        }
    }
}

data class OccasionState(val button: OccasionButton?, val occasion: OccasionThread?) {
    companion object {
        fun parse(data: Map<*, *>) = OccasionState(OccasionButton.parse(data["button"]), OccasionThread.parse(data["occasion"]))
    }
}

/**
 * users/{id}.occasionBadge: the profile button for someone who stitched onto a
 * holiday card. It opens their own answer.
 */
data class OccasionBadge(
    val emoji: String,
    val label: String,
    val videoID: String,
    val until: Date
) {
    /** Checked at composition, like the birthday ring. */
    val isActive: Boolean get() = until.after(Date())

    companion object {
        fun parse(raw: Any?): OccasionBadge? {
            val m = raw as? Map<*, *> ?: return null
            val videoID = str(m["videoID"]) ?: return null
            val until = (m["until"] as? Timestamp)?.toDate() ?: (m["until"] as? Date) ?: return null
            val emoji = str(m["emoji"]) ?: ""
            return OccasionBadge(emoji, str(m["label"]) ?: emoji, videoID, until)
        }
    }
}

object OccasionService {
    suspend fun current(): OccasionState {
        val callable = FirebaseFunctions.getInstance("us-central1").getHttpsCallable("occasion_current")
        val data = callable.call(mapOf("timeZone" to TimeZone.getDefault().id)).await().data as? Map<*, *>
        return OccasionState.parse(data ?: emptyMap<String, Any?>())
    }
}

object OccasionCenter {

    /** null until the first answer arrives, and after sign-out. */
    var state by mutableStateOf<OccasionState?>(null)
        private set

    private var userID: String? = null
    private var lastFetch: Long? = null
    private var inFlight: Deferred<OccasionState?>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Dash and Discover both ask on appear; a holiday changes by the day. */
    private const val THROTTLE_MS = 30L * 60 * 1000

    suspend fun refresh(userID: String?, force: Boolean = false) {
        if (userID.isNullOrEmpty() || TeenSafety.viewerIsTeen) {
            reset()
            return
        }
        if (this.userID != userID) {
            reset()
            this.userID = userID
        }
        val last = lastFetch
        if (!force && last != null && System.currentTimeMillis() - last < THROTTLE_MS) return
        inFlight?.let { awaitQuietly(it); return }

        val task = scope.async {
            runCatching { OccasionService.current() }
                .onFailure { if (BuildConfig.DEBUG) println("🎃 OCCASION: occasion_current failed — ${it.message}") }
                .getOrNull()
        }
        inFlight = task
        val fresh = awaitQuietly(task)
        if (inFlight === task) inFlight = null
        if (this.userID != userID) return
        if (fresh != null) {
            state = fresh
            lastFetch = System.currentTimeMillis()
        }
    }

    private suspend fun awaitQuietly(task: Deferred<OccasionState?>): OccasionState? = try {
        task.await()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    }

    /** Sign-out. */
    fun reset() {
        inFlight?.cancel()
        inFlight = null
        state = null
        userID = null
        lastFetch = null
    }

    /** The card, as the recorder needs it to stitch onto its thread. */
    suspend fun loadCard(videoID: String): CoreVideoMetadata? =
        runCatching { VideoServiceImpl().getVideoById(videoID) }.getOrNull()
            ?.takeIf { it.videoURL.isNotBlank() && !TeenSafety.viewerIsTeen }
}
