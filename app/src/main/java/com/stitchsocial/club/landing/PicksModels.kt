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
internal fun numberText(value: Double): String =
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
    val athleteID: String? = null,
    val actual: Double? = null,
    val result: String? = null,
    /** player_not_playing, stat_not_tracked, number_unread or side_unread. */
    val problem: String? = null,
    /** ESPN lists the player out and the pick hasn't settled. */
    val listedOut: Boolean = false,
    /** Put on the card by a swap after the lock. */
    val swappedIn: Boolean = false
) {
    val isListedOut: Boolean get() = listedOut && result == null

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
                else -> if (listedOut) "Listed out" else value ?: "Not started"
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
            athleteID = raw["athleteID"] as? String,
            actual = raw["actual"].doubleOrNull(),
            result = raw["result"] as? String,
            problem = raw["problem"] as? String,
            listedOut = raw["listedOut"] as? Boolean ?: false,
            swappedIn = raw["swappedIn"] as? Boolean ?: false
        )
    }
}

data class PicksCard(
    /** A person can have one card per slate, so this isn't their user ID. */
    val id: String,
    val userID: String,
    /** "main" or "late". */
    val slate: String,
    val username: String,
    val isHost: Boolean,
    val videoID: String?,
    val right: Int,
    val wrong: Int,
    val open: Int,
    val total: Int,
    val picks: List<PicksPick>,
    val swaps: List<Swap> = emptyList(),
    /** 1 to 3. The host can have up to 3 cards on a slate, everyone else 1. */
    val cardIndex: Int = 1,
    /** A co-host's card. They can still add it after the lock, with games that haven't started. */
    val isCoHost: Boolean = false
) {
    /** A player listed out, swapped after the lock. [inPlayer] is null when nothing replaced them. */
    data class Swap(val out: String, val outPick: String?, val inPlayer: String?, val inPick: String?) {
        val text: String get() = inPlayer?.let { "$out out, $it in" } ?: "$out out"
    }

    /** "Swapped: Theo Osei out, Lee Grant in", or null with no swaps. */
    val swapsText: String?
        get() = swaps.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Swapped: ") { it.text }

    companion object {
        fun from(raw: Map<*, *>) = PicksCard(
            id = raw["cardID"] as? String ?: raw["userID"] as? String ?: "",
            userID = raw["userID"] as? String ?: "",
            slate = if (raw["slate"] == "late") "late" else "main",
            username = raw["username"] as? String ?: "",
            isHost = raw["isHost"] as? Boolean ?: false,
            isCoHost = raw["isCoHost"] as? Boolean ?: false,
            cardIndex = raw["cardIndex"].intOrNull()?.coerceIn(1, 3) ?: 1,
            videoID = raw["videoID"] as? String,
            right = raw["right"].int(),
            wrong = raw["wrong"].int(),
            open = raw["open"].int(),
            total = raw["total"].int(),
            picks = raw["picks"].maps().map(PicksPick::from),
            swaps = raw["swaps"].maps().map {
                Swap(
                    out = it["out"] as? String ?: "",
                    outPick = it["outPick"] as? String,
                    inPlayer = it["in"] as? String,
                    inPick = it["inPick"] as? String
                )
            }
        )
    }
}

data class PicksMoment(
    val id: String,
    val videoURL: String,
    val duration: Double,
    val breaks: List<Break>,
    val right: Int?,
    val total: Int?,
    val hostID: String? = null,
    val speakerName: String? = null,
    /** Takes posted at the same break share it. Older moments don't have one. */
    val promptID: String? = null,
    val thumbnailURL: String? = null
) {
    /** Which conversation this take is part of. An old moment is its own. */
    val conversationKey: String get() = promptID ?: "video:$id"

    /**
     * A host's reply to one of the page's videos, not a break take. Its promptID
     * is "video_" and the video it answers, and its breaks are empty.
     */
    val isVideoReply: Boolean get() = promptID?.startsWith(VIDEO_REPLY_PREFIX) == true

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
        const val VIDEO_REPLY_PREFIX = "video_"

        fun from(raw: Map<*, *>): PicksMoment {
            val snapshot = raw["snapshot"] as? Map<*, *>
            return PicksMoment(
                id = raw["videoID"] as? String ?: "",
                videoURL = raw["videoURL"] as? String ?: "",
                duration = raw["duration"].doubleOrNull() ?: 0.0,
                breaks = Break.list(raw["breaks"]),
                right = snapshot?.get("right").intOrNull(),
                total = snapshot?.get("total").intOrNull(),
                hostID = raw["hostID"] as? String,
                speakerName = (raw["speakerName"] as? String)?.takeIf { it.isNotBlank() },
                promptID = (raw["promptID"] as? Any)?.toString()?.takeIf { it.isNotBlank() },
                thumbnailURL = (raw["thumbnailURL"] as? String)?.takeIf { it.isNotBlank() }
            )
        }
    }
}

data class PicksNight(
    val id: String,
    /** "YYYY-MM-DD". */
    val date: String,
    val title: String,
    val status: String,
    val hidden: Boolean,
    val locked: Boolean,
    val hostName: String?,
    val isHostTonight: Boolean,
    val firstTipAt: Date,
    /** False for a listed night opened again as a recap. */
    val isTonight: Boolean,
    val mainVideoID: String?,
    val mainVideoURL: String?,
    val mainVideoThumb: String?,
    /** Set when the night has late games as well as early ones. */
    val lateTipAt: Date?,
    val lateLocked: Boolean,
    /** Cards lock 10 minutes after their slate's first tip. */
    val locksAt: Date,
    val lateLocksAt: Date?,
    val lateVideoID: String?,
    val lateVideoURL: String?,
    val games: List<PicksGame>,
    val cards: List<PicksCard>,
    val moments: List<PicksMoment>,
    /** Only sent to the host and co-hosts, while a break is open. */
    val openPrompt: OpenPrompt?,
    /** For the hosts: picks where your card takes the other side from the other host's. */
    val splits: List<PicksSplit> = emptyList()
) {
    /** A break that's open for takes. It stays open after one, so the other host can answer. */
    data class OpenPrompt(
        val breaks: List<PicksMoment.Break>,
        val takes: Int,
        val lastSpeakerID: String?,
        val lastSpeakerName: String?
    )

    val promptBreaks: List<PicksMoment.Break> get() = openPrompt?.breaks.orEmpty()

    val hasLateSlate: Boolean get() = lateTipAt != null

    fun isLocked(slate: String): Boolean = if (slate == "late") lateLocked else locked
    fun videoID(slate: String): String? = if (slate == "late") lateVideoID else mainVideoID
    fun videoURL(slate: String): String? = if (slate == "late") lateVideoURL ?: mainVideoURL else mainVideoURL
    /** The host's cards on [slate], card 1 first. */
    fun hostCards(slate: String): List<PicksCard> =
        cards.filter { it.isHost && it.slate == slate }.sortedBy { it.cardIndex }

    /**
     * The host's score on [slate]: every one of their cards on it added together,
     * picks and all. Null with no host card. Use this wherever the host's card is a score.
     */
    fun hostTotal(slate: String): PicksCard? {
        val list = hostCards(slate)
        val first = list.firstOrNull() ?: return null
        if (list.size == 1) return first
        return first.copy(
            right = list.sumOf { it.right },
            wrong = list.sumOf { it.wrong },
            open = list.sumOf { it.open },
            total = list.sumOf { it.total },
            picks = list.flatMap { it.picks },
            swaps = list.flatMap { it.swaps }
        )
    }

    /** The video the hero plays for [slate]: the late one, or the main one when the late slate has none. */
    fun heroVideoID(slate: String): String? = if (slate == "late" && lateVideoURL != null) lateVideoID else mainVideoID

    /**
     * The hosts' replies to [slate]'s video, oldest first. They play after it as
     * one conversation, the video being take 1.
     */
    fun videoReplies(slate: String): List<PicksMoment> =
        heroVideoID(slate)?.let { id -> moments.filter { it.promptID == PicksMoment.VIDEO_REPLY_PREFIX + id } }.orEmpty()

    /**
     * What the hero plays for [slate], in order: the slate's video and the hosts'
     * replies to it, then each break's conversation, oldest first. A recap plays
     * the same way.
     */
    fun reel(slate: String): List<PicksReelItem> {
        val items = mutableListOf<PicksReelItem>()
        val heroID = heroVideoID(slate)
        val heroURL = videoURL(slate)?.takeIf { it.isNotBlank() }
        val replies = videoReplies(slate).filter { it.videoURL.isNotBlank() }
        if (heroID != null) {
            val heroKey = PicksMoment.VIDEO_REPLY_PREFIX + heroID
            val count = replies.size + if (heroURL != null) 1 else 0
            if (heroURL != null) {
                // The late video has no thumbnail of its own on the night.
                val thumb = if (heroID == mainVideoID) mainVideoThumb else null
                items += PicksReelItem(heroID, heroURL, thumb, null, heroKey, 1, count)
            }
            replies.forEachIndexed { i, reply ->
                items += PicksReelItem(reply.id, reply.videoURL, reply.thumbnailURL, reply, heroKey, i + 1 + (count - replies.size), count)
            }
        }
        // groupBy keeps the order each conversation first appears in.
        moments.filter { !it.isVideoReply && it.videoURL.isNotBlank() }
            .groupBy { it.conversationKey }
            .forEach { (key, takes) ->
                takes.forEachIndexed { i, take ->
                    items += PicksReelItem(take.id, take.videoURL, take.thumbnailURL, take, key, i + 1, takes.size)
                }
            }
        return items.distinctBy { it.videoID }
    }

    fun moment(gameID: String, quarter: String): PicksMoment? =
        moments.firstOrNull { m -> m.breaks.any { it.gameID == gameID && it.quarter == quarter } }

    /** Every take in [moment]'s conversation, oldest first, as the server sorts them. */
    fun conversation(moment: PicksMoment): List<PicksMoment> =
        moments.filter { it.conversationKey == moment.conversationKey }.ifEmpty { listOf(moment) }

    companion object {
        fun from(raw: Map<*, *>) = PicksNight(
            id = raw["id"] as? String ?: "",
            date = raw["date"] as? String ?: "",
            title = raw["title"] as? String ?: "",
            status = raw["status"] as? String ?: "upcoming",
            hidden = raw["hidden"] as? Boolean ?: false,
            locked = raw["locked"] as? Boolean ?: false,
            hostName = raw["hostName"] as? String,
            isHostTonight = raw["isHostTonight"] as? Boolean ?: false,
            firstTipAt = raw["firstTipAt"].dateOrNull() ?: Date(0),
            isTonight = raw["isTonight"] as? Boolean ?: true,
            mainVideoID = raw["mainVideoID"] as? String,
            mainVideoURL = raw["mainVideoURL"] as? String,
            mainVideoThumb = (raw["mainVideoThumb"] as? String)?.takeIf { it.isNotBlank() },
            lateTipAt = raw["lateTipAt"].dateOrNull(),
            lateLocked = raw["lateLocked"] as? Boolean ?: false,
            // Older servers locked at the tip itself and didn't send these.
            locksAt = raw["locksAt"].dateOrNull() ?: raw["firstTipAt"].dateOrNull() ?: Date(0),
            lateLocksAt = raw["lateLocksAt"].dateOrNull() ?: raw["lateTipAt"].dateOrNull(),
            lateVideoID = raw["lateVideoID"] as? String,
            lateVideoURL = raw["lateVideoURL"] as? String,
            games = raw["games"].maps().map(PicksGame::from),
            cards = raw["cards"].maps().map(PicksCard::from),
            moments = raw["moments"].maps().map(PicksMoment::from),
            openPrompt = (raw["openPrompt"] as? Map<*, *>)?.let {
                OpenPrompt(
                    breaks = PicksMoment.Break.list(it["breaks"]),
                    takes = it["takes"].int(),
                    lastSpeakerID = it["lastSpeakerID"] as? String,
                    lastSpeakerName = (it["lastSpeakerName"] as? String)?.takeIf { n -> n.isNotBlank() }
                )
            },
            splits = raw["splits"].maps().map {
                PicksSplit(
                    player = it["player"] as? String ?: "", who = it["who"] as? String ?: "",
                    mine = it["mine"] as? String ?: "", theirs = it["theirs"] as? String ?: ""
                )
            }
        )
    }
}

/**
 * One video in the hero's reel. [moment] is null for the slate's own video (the
 * main video, or the late one).
 */
data class PicksReelItem(
    val videoID: String,
    val videoURL: String,
    val thumbnailURL: String?,
    val moment: PicksMoment?,
    /** The conversation it's in: the slate video's, or a break's. */
    val conversationKey: String,
    val takeNumber: Int,
    val takeCount: Int
)

data class PicksPastNight(
    val id: String,
    val title: String,
    val date: String,
    val hostName: String?,
    val right: Int,
    val total: Int,
    /** The main video's thumbnail. */
    val thumb: String?,
    /** Older entries don't have it. */
    val games: Int?
) {
    companion object {
        fun from(raw: Map<*, *>) = PicksPastNight(
            id = raw["nightID"] as? String ?: UUID.randomUUID().toString(),
            title = raw["title"] as? String ?: "",
            date = raw["date"] as? String ?: "",
            hostName = raw["hostName"] as? String,
            right = raw["right"].int(),
            total = raw["total"].int(),
            thumb = (raw["thumb"] as? String)?.takeIf { it.isNotBlank() },
            games = raw["games"].intOrNull()
        )
    }
}

/** One pick two hosts see differently: same player and stat, opposite sides. */
data class PicksSplit(val player: String, val who: String, val mine: String, val theirs: String)

data class PicksCoHost(val id: String, val name: String)

data class PicksPage(
    val hostName: String?,
    val hasHost: Boolean,
    val isAdmin: Boolean,
    val isHost: Boolean,
    val isCoHost: Boolean,
    val coHosts: List<PicksCoHost>,
    val pendingInvite: Boolean,
    /** "host" or "cohost". */
    val pendingInviteRole: String,
    val recordRight: Int,
    val recordTotal: Int,
    val recentNights: List<PicksPastNight>,
    /** The current night's ID, null with no games today. */
    val tonightID: String?,
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
                isHost = raw["isHost"] as? Boolean ?: false,
                isCoHost = raw["isCoHost"] as? Boolean ?: false,
                coHosts = raw["coHosts"].maps().map { PicksCoHost(it["id"] as? String ?: "", it["name"] as? String ?: "") },
                pendingInvite = raw["pendingInvite"] as? Boolean ?: false,
                pendingInviteRole = raw["pendingInviteRole"] as? String ?: "host",
                recordRight = record?.get("right").int(),
                recordTotal = record?.get("total").int(),
                recentNights = raw["recentNights"].maps().map(PicksPastNight::from),
                tonightID = raw["tonightID"] as? String,
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
            code == "locked" -> "Those picks locked when the first game tipped."
            code == "swap_player_not_out" -> "That player isn't listed out, so that pick stays."
            code == "swap_game_started" -> "A pick in a game that's started can't change."
            code == "swap_new_game_started" -> "The new pick's game has already started."
            code == "swap_more_added" -> "You can only swap out as many picks as you add."
            code == "swap_no_change" -> "Nothing changed on your card."
            code == "outs_unavailable" -> "Couldn't check who's out right now. Try again in a minute."
            code == "wrong_app" -> "Only PrizePicks and Underdog sheets work here."
            code == "not_a_sheet" -> "We couldn't find any picks in that picture."
            code == "host_needs_picks" || code == "host_needs_five_picks" -> "Add a card of at least 2 picks before your video."
            code == "picks_too_few" -> "A card needs at least 2 picks."
            code == "card_not_allowed" -> "You can have up to 3 cards a night."
            code == "no_main_video_yet" -> "The host hasn't posted tonight's video yet."
            code == "add_picks_first" -> "Add your picks first."
            code == "no_break_right_now" -> "That break has passed. The next prompt comes when a game reaches a break."
            code == "host_only" -> "Only the host can do that."
            code == "already_host" -> "They're already the host."
            code == "no_games_today" -> "There are no games today."
            code == "night_not_found" -> "That night isn't available."
            code == "not_for_teens" -> "Stitch Picks isn't available on this account."
            code == "too_many_reads" -> "That's enough uploads for today. Try again tomorrow."
            code.endsWith("not_in_late_slate") -> "A late card can only have picks from the late games."
            code.startsWith("pick_") && code.endsWith("_game_started") -> "That pick's game has already started."
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

    /** Tonight, or with [nightID] a listed night, opened again as a recap. */
    suspend fun page(landingID: String, nightID: String? = null): PicksPage =
        PicksPage.from(
            call("picks_night", buildMap {
                put("landingID", landingID)
                nightID?.let { put("nightID", it) }
            })
        )

    /** The image is read and thrown away by the server. */
    suspend fun readSheet(landingID: String, jpegBase64: String): List<PicksPick> =
        call("picks_readSheet", mapOf("landingID" to landingID, "imageBase64" to jpegBase64))["picks"]
            .maps().map(PicksPick::from)

    /** [card] is 1, 2 or 3. Only the host has more than card 1. A swap after the lock names its card too. */
    suspend fun submit(landingID: String, slate: String, picks: List<PicksPick>, card: Int = 1) {
        call("picks_submitCard", mapOf("landingID" to landingID, "slate" to slate, "picks" to picks.map { it.payload }, "card" to card))
    }

    /**
     * Says what the next video is for. Returns the recording limit, if there is
     * one, and the video a fan's stitch replies to.
     */
    suspend fun beginVideo(landingID: String, role: String, slate: String): PicksVideoStart {
        val data = call("picks_beginVideo", mapOf("landingID" to landingID, "role" to role, "slate" to slate))
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

    suspend fun inviteCoHost(landingID: String, userID: String) {
        call("picks_inviteCoHost", mapOf("landingID" to landingID, "userID" to userID))
    }

    suspend fun removeCoHost(landingID: String, userID: String) {
        call("picks_removeCoHost", mapOf("landingID" to landingID, "userID" to userID))
    }

    suspend fun removeHost(landingID: String) {
        call("picks_removeHost", mapOf("landingID" to landingID))
    }

    /** A video on the page, to play or to stitch. Null if it can't be read. */
    suspend fun video(videoID: String): CoreVideoMetadata? = VideoServiceImpl().getVideoById(videoID)
}

/**
 * Tonight on the Stitch Picks page, for the cover on Dash. The page's ID is
 * resolved from its slug once, and the last answer kept in memory so the cover
 * draws straight away the next time Dash comes up.
 *
 * Layer 4: Core Services
 */
object PicksDash {
    const val SLUG = "stitch-picks"

    /** The @stitchpicks account, the page's businessID. Used until the page has been read. */
    const val ACCOUNT_UID_FALLBACK = "cKz55jkSZaYP2R1yc7lbuJ8luPM2"

    private var landingID: String? = null

    /** The show's account, from the resolved page when there is one. */
    @Volatile
    var accountID: String = ACCOUNT_UID_FALLBACK
        private set

    /** The page's own businessID wins over the fallback. */
    fun noteAccount(businessID: String?) {
        if (!businessID.isNullOrBlank()) accountID = businessID
    }

    /**
     * Bumped when the viewer follows or unfollows the show, so Dash reads its
     * following list again and shows or drops the cover.
     */
    val followChanges = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun followChanged() { followChanges.value += 1 }

    @Volatile
    var last: PicksPage? = null
        private set

    /** Null if the page couldn't be read. */
    suspend fun load(): PicksPage? = runCatching {
        val id = landingID ?: LandingService.resolve(SLUG)?.also { noteAccount(it.businessID) }?.landingID?.also { landingID = it }
        id?.let { PicksService.page(it) }?.also { last = it }
    }.getOrNull()
}
