package com.stitchsocial.club.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.foundation.BasicUserInfo
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
 * delete, agenda, giveaways. Recording-bridge, geofence-presence, invite, and
 * promo/recap video hydration land in later phases (Android has no recording
 * bridge or LocationService yet).
 */
class EventsViewModel : ViewModel() {

    private val service = EventService

    private var currentUserID = ""
    private var currentUsername = ""

    /** Supply the signed-in user (screen reads it from the auth layer). */
    fun configure(userID: String, username: String) {
        currentUserID = userID
        currentUsername = username
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

    /** The freshest copy of the open event (rows drop ended events). */
    fun loadEvent(id: String) {
        viewModelScope.launch { _openEvent.value = runCatching { service.fetchEvent(id) }.getOrNull() }
    }

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
        viewModelScope.launch {
            _isCheckingPresence.value = true
            try {
                val fix = location.fetchCurrentLocation()
                val onsite = fix != null && event.isWithinGeofence(fix.lat, fix.lng, fix.accuracyMeters)
                _isOnsite.value = onsite
                if (currentUserID.isNotBlank() && rsvpStatus(event.id) == EventRSVPStatus.GOING) {
                    runCatching { service.setOnsite(event.id, currentUserID, onsite) }
                }
            } catch (e: Exception) {
                _isOnsite.value = false
            } finally {
                _isCheckingPresence.value = false
            }
        }
    }

    // MARK: - Agenda (each slot is a host thread)

    fun loadAgenda(eventID: String) {
        viewModelScope.launch { _agenda.value = runCatching { service.fetchAgenda(eventID) }.getOrDefault(emptyList()) }
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
