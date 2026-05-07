/*
 * ShowViewModel.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: ViewModels — Show List, Show Editor, Show Detail
 * Mirrors ShowListView + ShowEditorView + ShowDetailView state logic.
 *
 * CACHING: All read through ShowService.shared — 30-min TTL, shared across ViewModels.
 * BATCHING: Schedule reorder uses ShowService.applyReorderedSchedule (1 batch write).
 */

package com.stitchsocial.club.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.foundation.*
import com.stitchsocial.club.services.ShowService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

// ─────────────────────────────────────────────
// MARK: - Show List ViewModel
// ─────────────────────────────────────────────

class ShowListViewModel : ViewModel() {

    private val service = ShowService.shared

    private val _shows = MutableStateFlow<List<Show>>(emptyList())
    val shows: StateFlow<List<Show>> = _shows.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadShows(creatorID: String) {
        viewModelScope.launch {
            _isLoading.value = true
            service.clearAllCaches()   // always fresh — creator needs to see latest draft state
            try {
                _shows.value = service.getCreatorShows(creatorID)
                println("📚 SHOW LIST VM: ${_shows.value.size} shows — ${_shows.value.map { it.status.rawValue }}")
            } catch (e: Exception) {
                _error.value = e.message
                println("❌ SHOW LIST VM: Failed to load: ${e.message}")
            }
            _isLoading.value = false
        }
    }

    fun insertShow(show: Show) {
        _shows.value = listOf(show) + _shows.value
    }

    fun updateShow(show: Show) {
        _shows.value = _shows.value.map { if (it.id == show.id) show else it }
    }

    fun removeShow(showId: String) {
        _shows.value = _shows.value.filter { it.id != showId }
    }
}

// ─────────────────────────────────────────────
// MARK: - Show Editor ViewModel
// ─────────────────────────────────────────────

class ShowEditorViewModel : ViewModel() {

    private val service = ShowService.shared

    private val _show = MutableStateFlow<Show?>(null)
    val show: StateFlow<Show?> = _show.asStateFlow()

    private val _seasons = MutableStateFlow<List<Season>>(emptyList())
    val seasons: StateFlow<List<Season>> = _seasons.asStateFlow()

    private val _episodesBySeasonId = MutableStateFlow<Map<String, List<VideoCollection>>>(emptyMap())
    val episodesBySeasonId: StateFlow<Map<String, List<VideoCollection>>> = _episodesBySeasonId.asStateFlow()

    private val _scheduleConfig = MutableStateFlow(ShowScheduleConfig.default_)
    val scheduleConfig: StateFlow<ShowScheduleConfig> = _scheduleConfig.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Call with existing show to edit, or newDraft show to create. */
    fun init(show: Show, isNew: Boolean) {
        _show.value = show
        _scheduleConfig.value = show.scheduleConfig ?: ShowScheduleConfig.default_
        if (!isNew) {
            viewModelScope.launch { loadFullShow(show.id) }
        }
    }

    private suspend fun loadFullShow(showId: String) {
        _isLoading.value = true
        try {
            val (s, seasons, episodes) = service.loadFullShow(showId)
            if (s != null) _show.value = s
            _seasons.value = seasons
            _episodesBySeasonId.value = episodes
        } catch (e: Exception) {
            _error.value = e.message
        }
        _isLoading.value = false
    }

    fun updateShowField(update: (Show) -> Show) {
        _show.value = _show.value?.let(update)
    }

    fun updateScheduleConfig(config: ShowScheduleConfig) {
        _scheduleConfig.value = config
        // Also update show's primitives so they encode correctly
        _show.value = _show.value?.copy(
            releaseCadence = config.cadence.rawValue,
            releaseWeekday = config.releaseWeekday,
            releaseHour    = config.releaseHour,
            releaseMinute  = config.releaseMinute
        )
    }

    fun persistSchedule(showId: String) {
        val config = _scheduleConfig.value
        viewModelScope.launch {
            try {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance("stitchfin")
                db.collection("shows").document(showId).set(
                    config.toFirestore() + mapOf("updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()),
                    com.google.firebase.firestore.SetOptions.merge()
                ).await()
            } catch (e: Exception) {
                println("⚠️ SHOW EDITOR VM: persistSchedule failed: ${e.message}")
            }
        }
    }

    fun save(onSuccess: (Show) -> Unit) {
        val show = _show.value ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val updated = show.copy(
                    seasonCount = _seasons.value.size,
                    totalEpisodes = _episodesBySeasonId.value.values.sumOf { it.size },
                    updatedAt = Date()
                )
                service.saveShow(updated)
                _show.value = updated
                println(if (show.id == updated.id) "✅ SHOW EDITOR VM: Updated ${updated.id}" else "✅ SHOW EDITOR VM: Created ${updated.id}")
                onSuccess(updated)
            } catch (e: Exception) {
                _error.value = e.message
                println("❌ SHOW EDITOR VM: Save failed: ${e.message}")
            }
            _isSaving.value = false
        }
    }

    fun addSeason() {
        val show = _show.value ?: return
        viewModelScope.launch {
            try {
                service.saveShow(show)
                val season = service.addSeason(show.id)
                _seasons.value = _seasons.value + season
                _episodesBySeasonId.value = _episodesBySeasonId.value + (season.id to emptyList())
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun deleteSeason(season: Season) {
        val show = _show.value ?: return
        viewModelScope.launch {
            try {
                service.deleteSeason(show.id, season.id)
                _seasons.value = _seasons.value.filter { it.id != season.id }
                _episodesBySeasonId.value = _episodesBySeasonId.value - season.id
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun refreshEpisodes(seasonId: String) {
        val show = _show.value ?: return
        viewModelScope.launch {
            try {
                val eps = service.getEpisodes(show.id, seasonId)
                _episodesBySeasonId.value = _episodesBySeasonId.value + (seasonId to eps)
                _show.value = _show.value?.copy(totalEpisodes = _episodesBySeasonId.value.values.sumOf { it.size })
            } catch (e: Exception) { /* non-fatal */ }
        }
    }

    /** Next suggested premiere slot for a new episode. Zero Firestore reads. */
    fun nextAvailableSlot(): Date? {
        val show = _show.value ?: return null
        val allEpisodes = _episodesBySeasonId.value.values.flatten()
        return service.nextAvailableSlot(show, allEpisodes)
    }
}

// ─────────────────────────────────────────────
// MARK: - Show Detail ViewModel
// ─────────────────────────────────────────────

class ShowDetailViewModel : ViewModel() {

    private val service = ShowService.shared

    private val _show = MutableStateFlow<Show?>(null)
    val show: StateFlow<Show?> = _show.asStateFlow()

    private val _seasons = MutableStateFlow<List<Season>>(emptyList())
    val seasons: StateFlow<List<Season>> = _seasons.asStateFlow()

    private val _episodesBySeasonId = MutableStateFlow<Map<String, List<VideoCollection>>>(emptyMap())
    val episodesBySeasonId: StateFlow<Map<String, List<VideoCollection>>> = _episodesBySeasonId.asStateFlow()

    private val _selectedSeasonId = MutableStateFlow<String?>(null)
    val selectedSeasonId: StateFlow<String?> = _selectedSeasonId.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isReordering = MutableStateFlow(false)
    val isReordering: StateFlow<Boolean> = _isReordering.asStateFlow()

    val currentSeasonEpisodes: List<VideoCollection> get() {
        val sid = _selectedSeasonId.value
        return if (sid != null) {
            _episodesBySeasonId.value[sid] ?: emptyList()
        } else {
            _episodesBySeasonId.value.values.flatten()
                .sortedBy { it.episodeNumber ?: 0 }
        }
    }

    fun load(showId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val (show, seasons, episodes) = service.loadFullShow(showId)
                _show.value = show
                _seasons.value = seasons
                _episodesBySeasonId.value = episodes
                _selectedSeasonId.value = seasons.firstOrNull()?.id
            } catch (e: Exception) {
                println("❌ SHOW DETAIL VM: Load failed: ${e.message}")
            }
            _isLoading.value = false
        }
    }

    fun selectSeason(seasonId: String) { _selectedSeasonId.value = seasonId }

    fun reorderEpisodes(newOrder: List<VideoCollection>) {
        val show = _show.value ?: return
        val sid = _selectedSeasonId.value ?: return
        // Optimistic update
        _episodesBySeasonId.value = _episodesBySeasonId.value + (sid to newOrder)
        _isReordering.value = true
        viewModelScope.launch {
            try {
                service.applyReorderedSchedule(newOrder, show)
            } catch (e: Exception) {
                println("❌ SHOW DETAIL VM: Reorder failed: ${e.message}")
            }
            _isReordering.value = false
        }
    }
}

// ─────────────────────────────────────────────
// MARK: - Episode Editor ViewModel (schedule integration)
// ─────────────────────────────────────────────

class EpisodeScheduleViewModel : ViewModel() {

    private val service = ShowService.shared

    private val _suggestedSlot = MutableStateFlow<Date?>(null)
    val suggestedSlot: StateFlow<Date?> = _suggestedSlot.asStateFlow()

    private val _publishIntent = MutableStateFlow<PublishIntent>(PublishIntent.Draft)
    val publishIntent: StateFlow<PublishIntent> = _publishIntent.asStateFlow()

    fun loadSuggestedSlot(show: Show?, existingEpisodes: List<VideoCollection>) {
        if (show == null) return
        val slot = service.nextAvailableSlot(show, existingEpisodes)
        _suggestedSlot.value = slot
        println("📅 EPISODE SCHEDULE VM: Suggested slot = $slot")
    }

    fun setIntent(intent: PublishIntent) { _publishIntent.value = intent }
}

// ─────────────────────────────────────────────
// MARK: - Publish Intent (mirrors PremiereDatePicker.swift PublishIntent)
// ─────────────────────────────────────────────

sealed class PublishIntent {
    object Draft : PublishIntent()
    object PublishNow : PublishIntent()
    data class Scheduled(val date: Date) : PublishIntent()

    val statusString: String get() = when (this) {
        is Draft      -> "draft"
        is PublishNow -> "published"
        is Scheduled  -> "scheduled"
    }

    val publishedAt: Date? get() = when (this) {
        is Scheduled -> date
        else         -> null
    }

    val label: String get() = when (this) {
        is Draft      -> "Draft"
        is PublishNow -> "Publish Now"
        is Scheduled  -> "Premieres ${shortLabel(date)}"
    }

    val isScheduled: Boolean get() = this is Scheduled

    fun firestoreFields(): Map<String, Any> {
        val fields = mutableMapOf<String, Any>("status" to statusString)
        when (this) {
            is PublishNow -> fields["publishedAt"] = com.google.firebase.firestore.FieldValue.serverTimestamp()
            is Scheduled  -> fields["publishedAt"] = com.google.firebase.Timestamp(date)
            else -> {}
        }
        return fields
    }

    companion object {
        fun from(status: String, publishedAt: Date?): PublishIntent = when (status) {
            "published" -> PublishNow
            "scheduled" -> if (publishedAt != null && publishedAt.after(Date())) Scheduled(publishedAt) else PublishNow
            else -> Draft
        }

        private fun shortLabel(date: Date): String {
            val cal = java.util.Calendar.getInstance()
            val now = java.util.Calendar.getInstance()
            cal.time = date
            val fmt = java.text.SimpleDateFormat(
                if (cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) + 1)
                    "'Tomorrow' h:mm a"
                else "EEE MMM d 'at' h:mm a",
                java.util.Locale.getDefault()
            )
            return fmt.format(date)
        }
    }
}