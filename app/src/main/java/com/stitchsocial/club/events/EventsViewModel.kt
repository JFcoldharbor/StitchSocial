package com.stitchsocial.club.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.foundation.BasicUserInfo
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.NotificationService
import com.stitchsocial.club.services.StitchNotificationType
import com.stitchsocial.club.services.UserService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Layer 4: ViewModel for the Events rows index + Event Hub (iOS parity with
 * ios/Events/EventsViewModel.swift). Owns all Firestore access via [EventService];
 * Compose screens never touch the DB.
 *
 * Phase 1 scope = the data spine: rows (live/upcoming/my), RSVP, create/edit/
 * delete, agenda, giveaways. Promo-video hydration ([loadPromo]) and the host
 * recording bridge ([armGoLive]/[armPromo]/[armRecap] via [EventMomentBridge])
 * mirror the iOS ViewModel. Geofence presence still lands in a later phase.
 */
class EventsViewModel : ViewModel() {

    private val service = EventService

    private var currentUserID = ""
    private var currentUsername = ""

    /**
     * Whether [configure] has landed a real user yet. Observable because
     * [currentUserID] is a plain var — a screen that composes before configure()
     * completes reads isHost = false and never recomposes when it flips. The
     * notification deep-link waits on this before opening the Hub, so a host
     * doesn't get their own event rendered as a guest.
     *
     * Declared ABOVE configure() on purpose: this file's sibling VM shipped an
     * NPE from a property declared after its first use.
     */
    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    /** Supply the signed-in user (screen reads it from the auth layer). */
    fun configure(userID: String, username: String) {
        currentUserID = userID
        currentUsername = username
        _isConfigured.value = userID.isNotBlank()
    }

    // MARK: - State

    private val _liveEvents = MutableStateFlow<List<StitchEventEntity>>(emptyList())
    val liveEvents: StateFlow<List<StitchEventEntity>> = _liveEvents.asStateFlow()

    private val _upcomingEvents = MutableStateFlow<List<StitchEventEntity>>(emptyList())
    val upcomingEvents: StateFlow<List<StitchEventEntity>> = _upcomingEvents.asStateFlow()

    private val _myEvents = MutableStateFlow<List<StitchEventEntity>>(emptyList())
    val myEvents: StateFlow<List<StitchEventEntity>> = _myEvents.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** eventID -> the caller's current RSVP (absent = no RSVP yet). */
    private val _myRSVPs = MutableStateFlow<Map<String, EventRSVPStatus>>(emptyMap())
    val myRSVPs: StateFlow<Map<String, EventRSVPStatus>> = _myRSVPs.asStateFlow()

    private val _agenda = MutableStateFlow<List<EventAgendaItem>>(emptyList())
    val agenda: StateFlow<List<EventAgendaItem>> = _agenda.asStateFlow()

    private val _giveaways = MutableStateFlow<List<EventGiveaway>>(emptyList())
    val giveaways: StateFlow<List<EventGiveaway>> = _giveaways.asStateFlow()

    private val _openEvent = MutableStateFlow<StitchEventEntity?>(null)
    val openEvent: StateFlow<StitchEventEntity?> = _openEvent.asStateFlow()

    /** The hydrated pre-event promo teaser for the open event (null = none set). */
    private val _promoVideo = MutableStateFlow<CoreVideoMetadata?>(null)
    val promoVideo: StateFlow<CoreVideoMetadata?> = _promoVideo.asStateFlow()

    /** The hydrated video of the currently-live filled moment (null = none live yet). */
    private val _liveMoment = MutableStateFlow<CoreVideoMetadata?>(null)
    val liveMoment: StateFlow<CoreVideoMetadata?> = _liveMoment.asStateFlow()

    /** The hydrated closing recap — the event's public artifact (null = not posted). */
    private val _recapVideo = MutableStateFlow<CoreVideoMetadata?>(null)
    val recapVideo: StateFlow<CoreVideoMetadata?> = _recapVideo.asStateFlow()

    /** One RSVP write per event at a time — rapid taps dropped, not queued. */
    private val rsvpInFlight = mutableSetOf<String>()

    fun clearError() { _errorMessage.value = null }

    // MARK: - Load

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val rows = service.fetchEventRows()
                prefetchRSVPs(rows)
                applyRows(rows)
                loadMyEvents()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadMyEvents() {
        viewModelScope.launch {
            if (currentUserID.isBlank()) return@launch
            _myEvents.value = try { service.fetchMyEvents(currentUserID) } catch (e: Exception) { emptyList() }
        }
    }

    /** Split by lifecycle, hiding anything the user marked Not Interested. */
    private fun applyRows(rows: List<StitchEventEntity>) {
        val hidden = _myRSVPs.value
        val visible = rows.filter { hidden[it.id] != EventRSVPStatus.NOT_INTERESTED }
        _liveEvents.value = visible.filter { it.isLive }
        _upcomingEvents.value = visible.filter { it.isUpcoming }
    }

    /** Concurrent RSVP prefetch so the row buttons render in the right state. */
    private suspend fun prefetchRSVPs(rows: List<StitchEventEntity>) {
        if (currentUserID.isBlank()) return
        val uid = currentUserID
        val pairs = coroutineScope {
            rows.map { e ->
                async { e.id to (runCatching { service.fetchMyRSVP(e.id, uid) }.getOrNull()?.status) }
            }.awaitAll()
        }
        val next = _myRSVPs.value.toMutableMap()
        for ((id, status) in pairs) if (status != null) next[id] = status
        _myRSVPs.value = next
    }

    fun isHost(event: StitchEventEntity): Boolean =
        currentUserID.isNotBlank() && event.hostUserID == currentUserID

    /**
     * The freshest copy of the open event (rows drop ended events). Backs the
     * notification deep-link: the tap only carries an eventID, so the Hub can't
     * be opened until the entity is hydrated. [openEvent] is a one-shot signal —
     * the screen presents it and calls [clearOpenEvent].
     */
    fun loadEvent(id: String) {
        viewModelScope.launch { _openEvent.value = runCatching { service.fetchEvent(id) }.getOrNull() }
    }

    /** Ack the [openEvent] signal once presented, so it can't re-present later. */
    fun clearOpenEvent() { _openEvent.value = null }

    // MARK: - Create / edit / delete

    /** Host a new event (+ line up prizes), then refresh. Returns false on failure. */
    suspend fun createEvent(draft: EventCreateDraft, giveaways: List<GiveawayDraft> = emptyList()): Boolean {
        if (currentUserID.isBlank()) { _errorMessage.value = "Sign in to host an event"; return false }
        return try {
            val event = service.createEvent(draft, currentUserID, currentUsername)
            for (g in giveaways) {
                runCatching { service.addGiveaway(event.id, g.prize, g.winnerCount, g.entryRule, currentUserID) }
            }
            loadAndAwait()
            true
        } catch (e: Exception) { _errorMessage.value = e.message; false }
    }

    suspend fun updateEvent(draft: EventCreateDraft, eventID: String): Boolean {
        if (currentUserID.isBlank()) { _errorMessage.value = "Sign in to edit an event"; return false }
        return try {
            service.updateEvent(eventID, draft, currentUserID)
            loadAndAwait()
            true
        } catch (e: Exception) { _errorMessage.value = e.message; false }
    }

    suspend fun endEvent(event: StitchEventEntity): Boolean {
        if (currentUserID.isBlank()) { _errorMessage.value = "Sign in to manage this event"; return false }
        return try {
            service.endEvent(event.id, currentUserID)
            loadAndAwait()
            true
        } catch (e: Exception) { _errorMessage.value = e.message; false }
    }

    suspend fun deleteEvent(event: StitchEventEntity): Boolean {
        if (currentUserID.isBlank()) { _errorMessage.value = "Sign in to delete an event"; return false }
        return try {
            service.deleteEvent(event.id, currentUserID)
            _myEvents.value = _myEvents.value.filter { it.id != event.id }
            _liveEvents.value = _liveEvents.value.filter { it.id != event.id }
            _upcomingEvents.value = _upcomingEvents.value.filter { it.id != event.id }
            loadAndAwait()
            true
        } catch (e: Exception) { _errorMessage.value = e.message; false }
    }

    /** Suspending reload used by create/edit/delete so the caller can await consistency. */
    private suspend fun loadAndAwait() {
        try {
            val rows = service.fetchEventRows()
            prefetchRSVPs(rows)
            applyRows(rows)
            if (currentUserID.isNotBlank()) _myEvents.value = service.fetchMyEvents(currentUserID)
        } catch (e: Exception) { _errorMessage.value = e.message }
    }

    // MARK: - RSVP

    fun rsvpStatus(eventID: String): EventRSVPStatus? = _myRSVPs.value[eventID]

    /** Toggle Going on/off. Tapping Going while already going clears it. */
    fun toggleGoing(event: StitchEventEntity) {
        val next = if (_myRSVPs.value[event.id] == EventRSVPStatus.GOING) null else EventRSVPStatus.GOING
        viewModelScope.launch { setRSVP(event, next) }
    }

    fun markNotInterested(event: StitchEventEntity) {
        viewModelScope.launch { setRSVP(event, EventRSVPStatus.NOT_INTERESTED) }
    }

    private suspend fun setRSVP(event: StitchEventEntity, status: EventRSVPStatus?) {
        if (currentUserID.isBlank()) { _errorMessage.value = "Sign in to RSVP"; return }
        if (rsvpInFlight.contains(event.id)) return
        rsvpInFlight.add(event.id)
        val previous = _myRSVPs.value[event.id]
        // Optimistic UI.
        setRsvpLocal(event.id, status)
        adjustGoingCount(event.id, wasGoing = previous == EventRSVPStatus.GOING, willGo = status == EventRSVPStatus.GOING)
        if (status == EventRSVPStatus.NOT_INTERESTED) applyHide(event.id)
        try {
            if (status != null) {
                service.setRSVP(event.id, status, currentUserID, currentUsername)
            } else {
                service.clearRSVP(event.id, currentUserID)
            }
        } catch (e: Exception) {
            setRsvpLocal(event.id, previous)
            adjustGoingCount(event.id, wasGoing = status == EventRSVPStatus.GOING, willGo = previous == EventRSVPStatus.GOING)
            _errorMessage.value = e.message
        } finally {
            rsvpInFlight.remove(event.id)
        }
    }

    private fun setRsvpLocal(eventID: String, status: EventRSVPStatus?) {
        val next = _myRSVPs.value.toMutableMap()
        if (status == null) next.remove(eventID) else next[eventID] = status
        _myRSVPs.value = next
    }

    private fun adjustGoingCount(eventID: String, wasGoing: Boolean, willGo: Boolean) {
        val delta = (if (willGo) 1 else 0) - (if (wasGoing) 1 else 0)
        if (delta == 0) return
        mutateEvent(eventID) { it.copy(goingCount = maxOf(0, it.goingCount + delta)) }
    }

    private fun applyHide(eventID: String) {
        _liveEvents.value = _liveEvents.value.filter { it.id != eventID }
        _upcomingEvents.value = _upcomingEvents.value.filter { it.id != eventID }
    }

    private fun mutateEvent(eventID: String, transform: (StitchEventEntity) -> StitchEventEntity) {
        _liveEvents.value = _liveEvents.value.map { if (it.id == eventID) transform(it) else it }
        _upcomingEvents.value = _upcomingEvents.value.map { if (it.id == eventID) transform(it) else it }
    }

    // MARK: - Presence (geofenced — Phase 3)

    private val _isOnsite = MutableStateFlow(false)
    val isOnsite: StateFlow<Boolean> = _isOnsite.asStateFlow()

    private val _isCheckingPresence = MutableStateFlow(false)
    val isCheckingPresence: StateFlow<Boolean> = _isCheckingPresence.asStateFlow()

    /**
     * Fetch the device fix, test it against the venue geofence (with the GPS-accuracy
     * buffer), and record the caller's onsite flag if they're Going. WhenInUse only.
     */
    fun refreshPresence(event: StitchEventEntity, location: LocationService) {
        viewModelScope.launch { checkPresence(event, location) }
    }

    /**
     * Awaitable presence check — fetches the fix, tests the geofence (with the
     * GPS-accuracy buffer), records onsite if Going, and returns whether the
     * caller is on-site. Backs both refreshPresence and the one-tap join+stitch.
     */
    private suspend fun checkPresence(event: StitchEventEntity, location: LocationService): Boolean {
        _isCheckingPresence.value = true
        return try {
            val fix = location.fetchCurrentLocation()
            val onsite = fix != null && event.isWithinGeofence(fix.lat, fix.lng, fix.accuracyMeters)
            _isOnsite.value = onsite
            if (currentUserID.isNotBlank() && rsvpStatus(event.id) == EventRSVPStatus.GOING) {
                runCatching { service.setOnsite(event.id, currentUserID, onsite) }
            }
            onsite
        } catch (e: Exception) {
            _isOnsite.value = false
            false
        } finally {
            _isCheckingPresence.value = false
        }
    }

    // MARK: - Agenda (each slot is a host thread)

    fun loadAgenda(eventID: String) {
        viewModelScope.launch { _agenda.value = runCatching { service.fetchAgenda(eventID) }.getOrDefault(emptyList()) }
    }

    // MARK: - Promo teaser

    /**
     * Hydrate the pre-event promo teaser (iOS parity with promoVideo(for:)). The
     * event only carries a promoVideoID; resolve it to a playable video so the
     * Hub can show it. Clears immediately when the event has no promo so a stale
     * teaser never lingers across events.
     */
    fun loadPromo(event: StitchEventEntity) {
        val vid = event.promoVideoID
        if (vid.isNullOrBlank()) { _promoVideo.value = null; return }
        viewModelScope.launch { _promoVideo.value = runCatching { service.fetchVideo(vid) }.getOrNull() }
    }

    fun addAgendaItem(event: StitchEventEntity, title: String, scheduledTime: Date) {
        if (!isHost(event)) return
        viewModelScope.launch {
            try {
                service.addAgendaItem(event.id, title, scheduledTime, currentUserID)
                _agenda.value = service.fetchAgenda(event.id)
            } catch (e: Exception) { _errorMessage.value = e.message }
        }
    }

    fun deleteAgendaItem(event: StitchEventEntity, itemID: String) {
        if (!isHost(event)) return
        viewModelScope.launch {
            try {
                service.deleteAgendaItem(event.id, itemID)
                _agenda.value = service.fetchAgenda(event.id)
            } catch (e: Exception) { _errorMessage.value = e.message }
        }
    }

    /** Filled slots (host has posted their video), time-ordered = the Host Threads. */
    fun hostThreads(): List<EventAgendaItem> = _agenda.value.filter { it.isFilled }.byTime()

    // MARK: - Host recording (Go Live / promo) — arm the moment bridge, then the
    // screen opens the recorder. ThreadComposer consumes the bridge on post.

    /**
     * Host "Go Live": ensure a recordable agenda slot exists and arm the bridge so
     * the next recording fills it. Returns true if armed — the caller then opens
     * the recorder (iOS parity with EventHubView.handlePlus(.hostRecord)).
     */
    suspend fun armGoLive(event: StitchEventEntity): Boolean {
        if (!isHost(event)) return false
        val slotID = ensureRecordableSlot(event) ?: return false
        EventMomentBridge.armHostMoment(event.id, slotID, currentUserID, currentUsername)
        return true
    }

    /** Arm the promo bridge — the next recording becomes the pre-event teaser. */
    fun armPromo(event: StitchEventEntity): Boolean {
        if (!isHost(event)) return false
        EventMomentBridge.armPromo(event.id, currentUserID, currentUsername)
        return true
    }

    /** Arm the recap bridge — the next recording becomes the event's public recap. */
    fun armRecap(event: StitchEventEntity): Boolean {
        if (!isHost(event)) return false
        EventMomentBridge.armRecap(event.id, currentUserID, currentUsername)
        return true
    }

    /** Hydrate the closing recap video for display (null if the host hasn't posted one). */
    fun loadRecap(event: StitchEventEntity) {
        val vid = event.recapVideoID
        if (vid.isNullOrBlank()) { _recapVideo.value = null; return }
        viewModelScope.launch { _recapVideo.value = runCatching { service.fetchVideo(vid) }.getOrNull() }
    }

    /** The current unfilled agenda slot the host can go live on, if any. */
    private fun recordableSlot(event: StitchEventEntity): EventAgendaItem? {
        if (!isHost(event)) return null
        val live = _agenda.value.liveItem() ?: return null
        return if (!live.isFilled) live else null
    }

    /**
     * The slot the host records to: the current unfilled slot, else a fresh one
     * created now (first = "Live" opener, later = "Moment"). Lets the host go live
     * with one tap, no pre-built agenda required.
     */
    private suspend fun ensureRecordableSlot(event: StitchEventEntity): String? {
        recordableSlot(event)?.let { return it.id }
        val title = if (event.openerVideoID == null) "Live" else "Moment"
        return runCatching {
            val item = service.addAgendaItem(event.id, title, Date(), currentUserID)
            _agenda.value = service.fetchAgenda(event.id)
            item.id
        }.getOrNull()
    }

    // MARK: - Guest POV (on-site stitch onto the live moment's thread)

    /** The live (stitchable) moment = the newest FILLED slot. See [liveMomentItem]. */
    fun liveSlot(): EventAgendaItem? = _agenda.value.liveMomentItem()

    /**
     * Hydrate the live moment's video for display in the Hub so a guest can see
     * what they're about to stitch to. Newest FILLED slot (not the time-based
     * slot) so an empty Go-Live slot never blanks the hero. Called on agenda change.
     */
    fun loadLiveMoment() {
        val vid = _agenda.value.liveMomentItem()?.momentVideoID
        if (vid == null) { _liveMoment.value = null; return }
        viewModelScope.launch { _liveMoment.value = runCatching { service.fetchVideo(vid) }.getOrNull() }
    }

    /**
     * Hydrate the live moment's video so the screen can build a stitch context
     * (iOS parity with liveMomentVideo(for:)). Newest FILLED slot.
     */
    suspend fun liveMomentVideo(): CoreVideoMetadata? {
        val live = _agenda.value.liveMomentItem() ?: return null
        val vid = live.momentVideoID ?: return null
        return service.fetchVideo(vid)
    }

    /** Arm the POV bridge — the next stitch attaches as a POV on the live moment's thread. */
    fun armPOV(event: StitchEventEntity, agendaItemID: String) {
        EventMomentBridge.armPOV(event.id, agendaItemID, currentUserID, currentUsername)
    }

    /** Hydrate a filled agenda slot's video for view-only fullscreen playback. */
    suspend fun momentVideo(item: EventAgendaItem): CoreVideoMetadata? {
        val vid = item.momentVideoID ?: return null
        return service.fetchVideo(vid)
    }

    /**
     * Host-only "Make live again": bump a past/locked moment's time to now so it
     * becomes the newest filled slot → live again (whatever was live drops to
     * "earlier"). For the accidental-lock recovery case.
     */
    fun reopenMoment(event: StitchEventEntity, itemID: String) {
        if (!isHost(event)) return
        viewModelScope.launch {
            try {
                service.reopenMoment(event.id, itemID)
                _agenda.value = service.fetchAgenda(event.id)
            } catch (e: Exception) { _errorMessage.value = e.message }
        }
    }

    /**
     * One-tap Join + stitch (iOS parity with handlePlus(.guestJoinStitch)): set
     * Going, check presence, and if on-site with a live filled moment, arm the POV
     * bridge and return its video so the caller opens the stitch recorder. Returns
     * null when not eligible — the user is left Going and the staged UI takes over.
     * Requires location permission already granted (caller checks hasPermission).
     */
    suspend fun joinAndStitch(event: StitchEventEntity, location: LocationService): CoreVideoMetadata? {
        if (isHost(event)) return null
        if (rsvpStatus(event.id) != EventRSVPStatus.GOING) setRSVP(event, EventRSVPStatus.GOING)
        if (!checkPresence(event, location)) return null
        val live = _agenda.value.liveMomentItem() ?: return null
        val vid = live.momentVideoID ?: return null
        val video = service.fetchVideo(vid) ?: return null
        armPOV(event, live.id)
        return video
    }

    // MARK: - Invite

    /** People the host can invite — union of who they follow and who follows them. */
    suspend fun inviteCandidates(userService: UserService): List<BasicUserInfo> {
        if (currentUserID.isBlank()) return emptyList()
        return try {
            val following = userService.getFollowing(currentUserID, 100)
            val followers = userService.getFollowers(currentUserID, 100)
            (following + followers).distinctBy { it.id }
                .filter { it.id != currentUserID }
                .sortedBy { it.username.lowercase() }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun searchInviteCandidates(userService: UserService, query: String): List<BasicUserInfo> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return try { userService.searchUsers(q, 20) } catch (e: Exception) { emptyList() }
    }

    /** Send an in-app invite notification to each picked user. Returns how many landed. */
    suspend fun inviteUsers(notificationService: NotificationService, users: List<BasicUserInfo>, event: StitchEventEntity): Int {
        if (currentUserID.isBlank()) return 0
        var sent = 0
        for (u in users) {
            if (u.id == currentUserID) continue
            val ok = runCatching {
                notificationService.createNotificationDirect(
                    type = StitchNotificationType.SYSTEM,
                    title = "Event invite",
                    message = "@$currentUsername invited you to ${event.name}",
                    senderID = currentUserID,
                    recipientID = u.id,
                    payload = mapOf("notificationType" to "event_invite", "eventID" to event.id, "eventName" to event.name)
                )
            }.getOrDefault(false)
            if (ok) sent++
        }
        return sent
    }

    // MARK: - Giveaways

    private val _drawingGiveawayID = MutableStateFlow<String?>(null)
    val drawingGiveawayID: StateFlow<String?> = _drawingGiveawayID.asStateFlow()

    fun loadGiveaways(eventID: String) {
        viewModelScope.launch { _giveaways.value = runCatching { service.fetchGiveaways(eventID) }.getOrDefault(emptyList()) }
    }

    fun addGiveaway(event: StitchEventEntity, prize: String, winnerCount: Int, entryRule: GiveawayEntryRule) {
        if (!isHost(event)) return
        viewModelScope.launch {
            try {
                service.addGiveaway(event.id, prize, winnerCount, entryRule, currentUserID)
                _giveaways.value = service.fetchGiveaways(event.id)
            } catch (e: Exception) { _errorMessage.value = e.message }
        }
    }

    fun deleteGiveaway(event: StitchEventEntity, giveawayID: String) {
        if (!isHost(event)) return
        viewModelScope.launch {
            try {
                service.deleteGiveaway(event.id, giveawayID)
                _giveaways.value = service.fetchGiveaways(event.id)
            } catch (e: Exception) { _errorMessage.value = e.message }
        }
    }

    fun drawGiveaway(event: StitchEventEntity, giveaway: EventGiveaway, seed: String) {
        if (!isHost(event)) return
        viewModelScope.launch {
            _drawingGiveawayID.value = giveaway.id
            try {
                service.drawGiveaway(event.id, giveaway, seed)
                _giveaways.value = service.fetchGiveaways(event.id)
            } catch (e: Exception) {
                _errorMessage.value = e.message
            } finally {
                _drawingGiveawayID.value = null
            }
        }
    }
}
