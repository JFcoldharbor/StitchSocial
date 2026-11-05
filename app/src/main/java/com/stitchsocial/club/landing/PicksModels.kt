package com.stitchsocial.club.landing

import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.VideoServiceImpl
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Stitch Picks, a nightly NBA picks page listed in Events. Stitch owns the page
 * and an invited user hosts it. The rules are in the server's picks.js.
 *
 * No money shows here. The server keeps players and numbers only, so there's
 * nothing else for these models to carry.
 *
 * Numbers from a callable can arrive as Int, Long or Double, so they're always
 * read through Number.
 *
 * Layer 1: Models. PicksService below is Layer 4: Core Services.
 */

private fun Any?.int(): Int = (this as? Number)?.toInt() ?: 0
private fun Any?.intOrNull(): Int? = (this as? Number)?.toInt()
private fun Any?.doubleOrNull(): Double? = (this as? Number)?.toDouble()
private fun Any?.dateOrNull(): Date? = (this as? Number)?.toLong()?.let { Date(it) }
private fun Any?.maps(): List<Map<*, *>> = (this as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

/** 24 reads "24", and 24.5 reads "24.5". */
private fun numberText(value: Double): String =
    if (value == Math.rint(value)) value.toLong().toString() else value.toString()

data class PicksGame(
    val id: String,
    val away: String,
    val home: String,
    val awayScore: Int,
    val homeScore: Int,
    val clock: String,
    val state: String,
    val awayFouls: Int,
    val homeFouls: Int,
    val awayTimeouts: Int,
    val homeTimeouts: Int,
    val awayFloor: List<Player>,
    val homeFloor: List<Player>
) {
    data class Player(val id: String, val name: String, val points: Int, val fouls: Int)

    val name: String get() = "$away @ $home"
    val hasStarted: Boolean get() = state != "upcoming"

    companion object {
        private fun players(list: Any?): List<Player> = list.maps().map {
            Player(
                id = it["athleteID"] as? String ?: UUID.randomUUID().toString(),
                name = it["name"] as? String ?: "",
                points = it["pts"].int(),
                fouls = it["pf"].int()
            )
        }

        fun from(raw: Map<*, *>): PicksGame {
            val lineups = raw["lineups"] as? Map<*, *>
            return PicksGame(
                id = raw["gameID"] as? String ?: "",
                away = raw["away"] as? String ?: "",
                home = raw["home"] as? String ?: "",
                awayScore = raw["awayScore"].int(),
                homeScore = raw["homeScore"].int(),
                clock = raw["clock"] as? String ?: "",
                state = raw["state"] as? String ?: "upcoming",
                awayFouls = raw["awayFouls"].int(),
                homeFouls = raw["homeFouls"].int(),
                awayTimeouts = raw["awayTimeouts"].int(),
                homeTimeouts = raw["homeTimeouts"].int(),
                awayFloor = players(lineups?.get("away")),
                homeFloor = players(lineups?.get("home"))
            )
        }
    }
}

data class PicksPick(
    val id: String = UUID.randomUUID().toString(),
    val player: String,
    val side: String,
    val line: Double?,
    val statText: String,
    val gameID: String? = null,
    val actual: Double? = null,
    val result: String? = null,
    /** player_not_playing, stat_not_tracked, number_unread or side_unread. */
    val problem: String? = null
) {
    val lineText: String
        get() {
            val number = line?.let { numberText(it) } ?: "?"
            return "${if (side == "under") "Less than" else "More than"} $number ${statText.lowercase()}"
        }

    val stateText: String
        get() {
            val value = actual?.let { numberText(it) }
            return when (result) {
                "right" -> value?.let { "Right · $it" } ?: "Right"
                "wrong" -> value?.let { "Wrong · $it" } ?: "Wrong"
                "push", "void" -> "Doesn't count"
                else -> value ?: "Not started"
            }
        }

    val payload: Map<String, Any>
        get() = buildMap {
            put("player", player)
            put("side", side)
            put("statText", statText)
            line?.let { put("line", it) }
        }

    val problemText: String?
        get() = when (problem) {
            "player_not_playing" -> "Not playing tonight"
            "stat_not_tracked" -> "We can't follow this stat"
            "number_unread" -> "Couldn't read the number"
            "side_unread" -> "Choose more or less"
            else -> null
        }

    companion object {
        fun from(raw: Map<*, *>) = PicksPick(
            player = raw["player"] as? String ?: "",
            side = raw["side"] as? String ?: "over",
            line = raw["line"].doubleOrNull(),
            statText = raw["statText"] as? String ?: "",
            gameID = raw["gameID"] as? String,
            actual = raw["actual"].doubleOrNull(),
            result = raw["result"] as? String,
            problem = raw["problem"] as? String
        )
    }
}

data class PicksCard(
    val id: String,
    val username: String,
    val isHost: Boolean,
    val videoID: String?,
    val right: Int,
    val wrong: Int,
    val open: Int,
    val total: Int,
    val picks: List<PicksPick>
) {
    companion object {
        fun from(raw: Map<*, *>) = PicksCard(
            id = raw["userID"] as? String ?: "",
            username = raw["username"] as? String ?: "",
            isHost = raw["isHost"] as? Boolean ?: false,
            videoID = raw["videoID"] as? String,
            right = raw["right"].int(),
            wrong = raw["wrong"].int(),
            open = raw["open"].int(),
            total = raw["total"].int(),
            picks = raw["picks"].maps().map(PicksPick::from)
        )
    }
}

data class PicksMoment(
    val id: String,
    val videoURL: String,
    val duration: Double,
    val breaks: List<Break>,
    val right: Int?,
    val total: Int?
) {
    data class Break(val gameID: String, val quarter: String, val game: String) {
        companion object {
            fun list(raw: Any?): List<Break> = raw.maps().map {
                Break(
                    gameID = it["gameID"] as? String ?: "",
                    quarter = it["quarter"] as? String ?: "",
                    game = it["game"] as? String ?: ""
                )
            }
        }
    }

    val lengthText: String
        get() {
            val s = Math.round(duration).toInt()
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }

    companion object {
        fun from(raw: Map<*, *>): PicksMoment {
            val snapshot = raw["snapshot"] as? Map<*, *>
            return PicksMoment(
                id = raw["videoID"] as? String ?: "",
                videoURL = raw["videoURL"] as? String ?: "",
                duration = raw["duration"].doubleOrNull() ?: 0.0,
                breaks = Break.list(raw["breaks"]),
                right = snapshot?.get("right").intOrNull(),
                total = snapshot?.get("total").intOrNull()
            )
        }
    }
}

data class PicksNight(
    val id: String,
    val title: String,
    val status: String,
    val hidden: Boolean,
    val locked: Boolean,
    val hostName: String?,
    val isHostTonight: Boolean,
    val firstTipAt: Date,
    val hideAt: Date?,
    val mainVideoID: String?,
    val mainVideoURL: String?,
    val games: List<PicksGame>,
    val cards: List<PicksCard>,
    val moments: List<PicksMoment>,
    val promptBreaks: List<PicksMoment.Break>
) {
    fun moment(gameID: String, quarter: String): PicksMoment? =
        moments.firstOrNull { m -> m.breaks.any { it.gameID == gameID && it.quarter == quarter } }

    companion object {
        fun from(raw: Map<*, *>) = PicksNight(
            id = raw["id"] as? String ?: "",
            title = raw["title"] as? String ?: "",
            status = raw["status"] as? String ?: "upcoming",
            hidden = raw["hidden"] as? Boolean ?: false,
            locked = raw["locked"] as? Boolean ?: false,
            hostName = raw["hostName"] as? String,
            isHostTonight = raw["isHostTonight"] as? Boolean ?: false,
            firstTipAt = raw["firstTipAt"].dateOrNull() ?: Date(0),
            hideAt = raw["hideAt"].dateOrNull(),
            mainVideoID = raw["mainVideoID"] as? String,
            mainVideoURL = raw["mainVideoURL"] as? String,
            games = raw["games"].maps().map(PicksGame::from),
            cards = raw["cards"].maps().map(PicksCard::from),
            moments = raw["moments"].maps().map(PicksMoment::from),
            promptBreaks = PicksMoment.Break.list((raw["openPrompt"] as? Map<*, *>)?.get("breaks"))
        )
    }
}

data class PicksPastNight(
    val id: String,
    val title: String,
    val date: String,
    val hostName: String?,
    val right: Int,
    val total: Int
) {
    companion object {
        fun from(raw: Map<*, *>) = PicksPastNight(
            id = raw["nightID"] as? String ?: UUID.randomUUID().toString(),
            title = raw["title"] as? String ?: "",
            date = raw["date"] as? String ?: "",
            hostName = raw["hostName"] as? String,
            right = raw["right"].int(),
            total = raw["total"].int()
        )
    }
}

data class PicksPage(
    val hostName: String?,
    val hasHost: Boolean,
    val isAdmin: Boolean,
    val pendingInvite: Boolean,
    val recordRight: Int,
    val recordTotal: Int,
    val recentNights: List<PicksPastNight>,
    val night: PicksNight?
) {
    companion object {
        fun from(raw: Map<*, *>): PicksPage {
            val landing = raw["landing"] as? Map<*, *>
            val record = raw["record"] as? Map<*, *>
            return PicksPage(
                hostName = landing?.get("hostName") as? String,
                hasHost = landing?.get("hostID") is String,
                isAdmin = raw["isAdmin"] as? Boolean ?: false,
                pendingInvite = raw["pendingInvite"] as? Boolean ?: false,
                recordRight = record?.get("right").int(),
                recordTotal = record?.get("total").int(),
                recentNights = raw["recentNights"].maps().map(PicksPastNight::from),
                night = (raw["night"] as? Map<*, *>)?.let(PicksNight::from)
            )
        }
    }
}

/** What picks_beginVideo said about the next video. */
data class PicksVideoStart(val seconds: Long?, val replyToVideoID: String?)

/**
 * How long the next recording can run, when the page says so. A host's pregame
 * video is up to 2:00 and a moment up to 1:00, whatever their tier allows.
 *
 * Read wherever the tier limit is (CameraViewModel.updateRecordingLimits). Set
 * just before the recorder opens, and cleared after it closes.
 */
object PicksRecordingLimit {
    @Volatile
    var current: Long? = null
        private set

    fun set(seconds: Long?) {
        current = seconds
    }
}

class PicksError(val code: String) : Exception(code) {
    override val message: String
        get() = when {
            code == "locked" -> "Picks locked when the first game tipped."
            code == "wrong_app" -> "Only PrizePicks and Underdog sheets work here."
            code == "not_a_sheet" -> "We couldn't find any picks in that picture."
            code == "host_needs_five_picks" -> "The main card needs at least 5 picks."
            code == "no_main_video_yet" -> "The host hasn't posted tonight's video yet."
            code == "add_picks_first" -> "Add your picks first."
            code == "no_break_right_now" -> "That break has passed. The next prompt comes when a game reaches a break."
            code == "no_games_today" -> "There are no games today."
            code == "not_for_teens" -> "Stitch Picks isn't available on this account."
            code == "too_many_reads" -> "That's enough uploads for today. Try again tomorrow."
            code.startsWith("pick_") -> "One of your picks needs fixing."
            else -> "Something went wrong. Please try again."
        }
}

/**
 * The page's callables (picks.js). It reads through the server rather than
 * Firestore, so the screen polls [page]. Everything goes through LandingService's
 * call, on the same Functions instance.
 *
 * Layer 4: Core Services
 */
object PicksService {

    private suspend fun call(name: String, payload: Map<String, Any?>): Map<String, Any?> = try {
        LandingService.call(name, payload)
    } catch (e: LandingException) {
        throw PicksError(e.reason)
    }

    suspend fun page(landingID: String): PicksPage =
        PicksPage.from(call("picks_night", mapOf("landingID" to landingID)))

    /** The image is read and thrown away by the server. */
    suspend fun readSheet(landingID: String, jpegBase64: String): List<PicksPick> =
        call("picks_readSheet", mapOf("landingID" to landingID, "imageBase64" to jpegBase64))["picks"]
            .maps().map(PicksPick::from)

    suspend fun submit(landingID: String, picks: List<PicksPick>) {
        call("picks_submitCard", mapOf("landingID" to landingID, "picks" to picks.map { it.payload }))
    }

    /**
     * Says what the next video is for. Returns the recording limit, if there is
     * one, and the video a fan's stitch replies to.
     */
    suspend fun beginVideo(landingID: String, role: String): PicksVideoStart {
        val data = call("picks_beginVideo", mapOf("landingID" to landingID, "role" to role))
        return PicksVideoStart(
            seconds = (data["seconds"] as? Number)?.toLong(),
            replyToVideoID = data["replyToVideoID"] as? String
        )
    }

    suspend fun respondToInvite(landingID: String, accept: Boolean) {
        call("picks_respondHost", mapOf("landingID" to landingID, "accept" to accept))
    }

    suspend fun invite(landingID: String, userID: String) {
        call("picks_inviteHost", mapOf("landingID" to landingID, "userID" to userID))
    }

    suspend fun removeHost(landingID: String) {
        call("picks_removeHost", mapOf("landingID" to landingID))
    }

    /** A video on the page, to play or to stitch. Null if it can't be read. */
    suspend fun video(videoID: String): CoreVideoMetadata? = VideoServiceImpl().getVideoById(videoID)
}
