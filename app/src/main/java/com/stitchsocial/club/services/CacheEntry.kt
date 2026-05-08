/*
 * ShowService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Services — Show/Season/Episode management
 * Mirrors ShowService.swift exactly.
 *
 * ARCHITECTURE:
 *   shows/{showId}                         → Show metadata
 *   shows/{showId}/seasons/{seasonId}      → Season metadata
 *   videoCollections/{id}                  → Episodes (showId + seasonId fields)
 *   videos/{id}                            → Segments (collectionID field)
 *
 * CACHING: 30-min TTL for shows and seasons. Invalidated on write.
 *          Schedule slot computation uses in-memory data — zero extra reads.
 * BATCHING: applyReorderedSchedule writes all episode dates in one batch commit.
 * SINGLETON: Use ShowService.shared — prevents multiple instances with empty caches.
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.tasks.await
import java.util.*
import com.stitchsocial.club.BuildConfig

private data class CacheEntry<T>(val data: T, val cachedAt: Long) {
    fun isValid(ttlMs: Long) = System.currentTimeMillis() - cachedAt < ttlMs
}

class ShowService private constructor() {

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val showsCol get() = db.collection("shows")
    private val collectionsCol get() = db.collection("videoCollections")

    // Caches
    private val showCache = mutableMapOf<String, CacheEntry<Show>>()
    private val seasonsCache = mutableMapOf<String, CacheEntry<List<Season>>>()
    private val episodesCache = mutableMapOf<String, CacheEntry<List<VideoCollection>>>()

    private val SHOW_TTL = 30 * 60 * 1000L
    private val SEASON_TTL = 30 * 60 * 1000L
    private val EPISODE_TTL = 10 * 60 * 1000L

    companion object {
        val shared = ShowService()
    }

    fun clearAllCaches() {
        showCache.clear()
        seasonsCache.clear()
        episodesCache.clear()
    }

    // ═══════════════════════════════════════
    // MARK: - Show CRUD
    // ═══════════════════════════════════════

    suspend fun getShow(showId: String): Show? {
        showCache[showId]?.let { if (it.isValid(SHOW_TTL)) return it.data }
        val doc = showsCol.document(showId).get().await()
        if (!doc.exists()) return null
        val show = decodeShow(doc.data ?: return null, doc.id) ?: return null
        showCache[showId] = CacheEntry(show, System.currentTimeMillis())
        return show
    }

    /**
     * All shows for a creator — includes drafts so creator can edit them.
     * Falls back to unordered query if index is missing (Firestore requires
     * creatorID+updatedAt composite index for ordered query).
     */
    suspend fun getCreatorShows(creatorID: String): List<Show> {
        return try {
            val snap = showsCol
                .whereEqualTo("creatorID", creatorID)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .get().await()
            val all = snap.documents.mapNotNull { decodeShow(it.data ?: return@mapNotNull null, it.id) }
            val shows = all.filter { it.status != ShowStatus.REMOVED }
            shows.forEach { showCache[it.id] = CacheEntry(it, System.currentTimeMillis()) }
            if (BuildConfig.DEBUG) { println("📚 SHOW SERVICE: Loaded ${shows.count()} shows for $creatorID") }
            shows
        } catch (e: Exception) {
            // Index missing — fallback without orderBy, sort client-side
            if (BuildConfig.DEBUG) { println("⚠️ SHOW SERVICE: Ordered query failed (${e.message}) — retrying without order") }
            val snap = showsCol.whereEqualTo("creatorID", creatorID).get().await()
            val all = snap.documents.mapNotNull { decodeShow(it.data ?: return@mapNotNull null, it.id) }
            val shows = all.filter { it.status != ShowStatus.REMOVED }
                .sortedByDescending { it.updatedAt }
            shows.forEach { showCache[it.id] = CacheEntry(it, System.currentTimeMillis()) }
            if (BuildConfig.DEBUG) { println("📚 SHOW SERVICE: Fallback loaded ${shows.count()} shows") }
            shows
        }
    }

    suspend fun saveShow(show: Show) {
        val data = encodeShow(show)
        showsCol.document(show.id).set(data).await()
        showCache[show.id] = CacheEntry(show, System.currentTimeMillis())
        if (BuildConfig.DEBUG) { println("✅ SHOW SERVICE: Saved show ${show.id}") }
    }

    suspend fun deleteShow(showId: String) {
        showsCol.document(showId).update(mapOf(
            "status" to ShowStatus.REMOVED.rawValue,
            "updatedAt" to FieldValue.serverTimestamp()
        )).await()
        showCache.remove(showId)
    }

    // ═══════════════════════════════════════
    // MARK: - Season CRUD
    // ═══════════════════════════════════════

    suspend fun getSeasons(showId: String): List<Season> {
        seasonsCache[showId]?.let { if (it.isValid(SEASON_TTL)) return it.data }
        val snap = showsCol.document(showId).collection("seasons")
            .orderBy("number", Query.Direction.ASCENDING)
            .get().await()
        val seasons = snap.documents.mapNotNull { decodeSeason(it.data ?: return@mapNotNull null, it.id, showId) }
        seasonsCache[showId] = CacheEntry(seasons, System.currentTimeMillis())
        return seasons
    }

    suspend fun addSeason(showId: String): Season {
        val existing = getSeasons(showId)
        val season = Season(showId = showId, number = existing.size + 1)
        showsCol.document(showId).collection("seasons")
            .document(season.id).set(encodeSeason(season)).await()
        seasonsCache.remove(showId)
        return season
    }

    suspend fun deleteSeason(showId: String, seasonId: String) {
        showsCol.document(showId).collection("seasons").document(seasonId).delete().await()
        seasonsCache.remove(showId)
    }

    // ═══════════════════════════════════════
    // MARK: - Episode Reads
    // ═══════════════════════════════════════

    /**
     * All episodes for a show across all seasons — mirrors iOS ShowService.getAllEpisodes.
     * COST: 1 query, no index required (single whereEqualTo).
     * CACHING: Not cached — called once on profile load.
     */
    suspend fun getAllEpisodes(showId: String): List<VideoCollection> {
        val snap = collectionsCol
            .whereEqualTo("showId", showId)
            .get().await()
        return snap.documents.mapNotNull { decodeEpisode(it.data ?: return@mapNotNull null, it.id) }
            .sortedBy { it.episodeNumber ?: 0 }
    }

    /** Episodes for a show+season. COST: 1 query, cached 10 min. */
    suspend fun getEpisodes(showId: String, seasonId: String): List<VideoCollection> {
        val key = "$showId:$seasonId"
        episodesCache[key]?.let { if (it.isValid(EPISODE_TTL)) return it.data }
        val snap = collectionsCol
            .whereEqualTo("showId", showId)
            .whereEqualTo("seasonId", seasonId)
            .get().await()
        val episodes = snap.documents.mapNotNull { decodeEpisode(it.data ?: return@mapNotNull null, it.id) }
            .sortedBy { it.episodeNumber ?: 0 }
        episodesCache[key] = CacheEntry(episodes, System.currentTimeMillis())
        return episodes
    }

    /** Loads full show + all seasons + all episodes. COST: 1 + N season queries. */
    suspend fun loadFullShow(showId: String): Triple<Show?, List<Season>, Map<String, List<VideoCollection>>> {
        val show = getShow(showId)
        val seasons = getSeasons(showId)
        val episodeMap = mutableMapOf<String, List<VideoCollection>>()
        for (season in seasons) {
            episodeMap[season.id] = getEpisodes(showId, season.id)
        }
        return Triple(show, seasons, episodeMap)
    }

    // ═══════════════════════════════════════
    // MARK: - Schedule
    // ═══════════════════════════════════════

    /**
     * Next open premiere slot for a show.
     * CACHING: Zero reads — uses caller's in-memory episodes.
     */
    fun nextAvailableSlot(show: Show, existingEpisodes: List<VideoCollection>): Date? {
        val config = show.scheduleConfig ?: return null
        val scheduled = existingEpisodes.filter {
            it.status == CollectionStatus.PUBLISHED || it.status.rawValue == "scheduled"
        }
        return ScheduleService.nextAvailableSlot(config, scheduled)
    }

    /**
     * Batch-updates publishedAt for reordered episodes to preserve cadence spacing.
     * BATCHING: All writes in a single Firestore batch — 1 round trip.
     */
    suspend fun applyReorderedSchedule(episodes: List<VideoCollection>, show: Show) {
        val config = show.scheduleConfig ?: return
        if (config.cadence == ReleaseCadence.CUSTOM) return
        val pairs = ScheduleService.recomputeDates(episodes, config)
        val batch = db.batch()
        for ((id, date) in pairs) {
            val ref = collectionsCol.document(id)
            batch.update(ref, mapOf(
                "publishedAt" to Timestamp(date),
                "status"      to "scheduled",
                "updatedAt"   to FieldValue.serverTimestamp()
            ))
        }
        batch.commit().await()
        if (BuildConfig.DEBUG) { println("📅 SHOW SERVICE: Reordered schedule — ${pairs.size} episodes updated") }
    }

    // ═══════════════════════════════════════
    // MARK: - Encode / Decode
    // ═══════════════════════════════════════

    private fun encodeShow(show: Show): Map<String, Any?> {
        val data = mutableMapOf<String, Any?>(
            "id"           to show.id,
            "title"        to show.title,
            "description"  to show.description,
            "creatorID"    to show.creatorID,
            "creatorName"  to show.creatorName,
            "format"       to show.format.rawValue,
            "genre"        to show.genre.rawValue,
            "contentType"  to show.contentType.rawValue,
            "tags"         to show.tags.map { it.rawValue },
            "coverImageURL" to (show.coverImageURL ?: ""),
            "thumbnailURL" to (show.thumbnailURL ?: ""),
            "status"       to show.status.rawValue,
            "isFeatured"   to show.isFeatured,
            "isFree"       to show.isFree,
            "seasonCount"  to show.seasonCount,
            "totalEpisodes" to show.totalEpisodes,
            "totalViews"   to show.totalViews,
            "totalHypes"   to show.totalHypes,
            "totalCools"   to show.totalCools,
            "createdAt"    to Timestamp(show.createdAt),
            "updatedAt"    to FieldValue.serverTimestamp()
        )
        show.scheduleConfig?.let { config ->
            data["releaseCadence"] = config.cadence.rawValue
            data["releaseWeekday"] = config.releaseWeekday
            data["releaseHour"]    = config.releaseHour
            data["releaseMinute"]  = config.releaseMinute
        }
        return data
    }

    private fun decodeShow(data: Map<String, Any>, id: String): Show? {
        val creatorID = data["creatorID"] as? String ?: return null
        val title        = data["title"]       as? String ?: ""
        val description  = data["description"] as? String ?: ""
        val creatorName  = data["creatorName"] as? String ?: ""
        val format       = ShowFormat.from(data["format"] as? String)
        val genre        = ShowGenre.from(data["genre"] as? String)
        val contentType  = CollectionContentType.from(data["contentType"] as? String)
        val tags         = (data["tags"] as? List<*>)?.mapNotNull { ShowTag.from(it as? String) } ?: emptyList()
        val status       = ShowStatus.from(data["status"] as? String)
        val isFeatured   = data["isFeatured"] as? Boolean ?: false
        val isFree       = data["isFree"]     as? Boolean ?: false
        val seasonCount  = (data["seasonCount"]  as? Long)?.toInt() ?: 0
        val totalEps     = (data["totalEpisodes"] as? Long)?.toInt() ?: 0
        val totalViews   = (data["totalViews"]    as? Long)?.toInt() ?: 0
        val totalHypes   = (data["totalHypes"]    as? Long)?.toInt() ?: 0
        val totalCools   = (data["totalCools"]    as? Long)?.toInt() ?: 0
        val createdAt    = (data["createdAt"]  as? Timestamp)?.toDate() ?: Date()
        val updatedAt    = (data["updatedAt"]  as? Timestamp)?.toDate() ?: Date()
        val cadence      = data["releaseCadence"] as? String
        val weekday      = (data["releaseWeekday"] as? Long)?.toInt()
        val hour         = (data["releaseHour"]    as? Long)?.toInt()
        val minute       = (data["releaseMinute"]  as? Long)?.toInt()

        return Show(
            id = id, title = title, description = description,
            creatorID = creatorID, creatorName = creatorName,
            format = format, genre = genre, contentType = contentType, tags = tags,
            status = status, isFeatured = isFeatured, isFree = isFree,
            seasonCount = seasonCount, totalEpisodes = totalEps,
            totalViews = totalViews, totalHypes = totalHypes, totalCools = totalCools,
            createdAt = createdAt, updatedAt = updatedAt,
            releaseCadence = cadence, releaseWeekday = weekday,
            releaseHour = hour, releaseMinute = minute,
            coverImageURL = data["coverImageURL"] as? String,
            thumbnailURL = data["thumbnailURL"] as? String
        )
    }

    private fun encodeSeason(season: Season): Map<String, Any?> = mapOf(
        "id"          to season.id,
        "showId"      to season.showId,
        "number"      to season.number,
        "title"       to season.displayTitle,
        "description" to season.description,
        "status"      to season.status.rawValue,
        "episodeCount" to season.episodeCount,
        "totalViews"  to season.totalViews,
        "totalHypes"  to season.totalHypes,
        "totalCools"  to season.totalCools,
        "createdAt"   to Timestamp(season.createdAt),
        "updatedAt"   to FieldValue.serverTimestamp()
    )

    private fun decodeSeason(data: Map<String, Any>, id: String, showId: String): Season? {
        return Season(
            id          = id,
            showId      = showId,
            number      = (data["number"]      as? Long)?.toInt() ?: 1,
            title       = data["title"]        as? String ?: "",
            description = data["description"]  as? String ?: "",
            status      = SeasonStatus.from(data["status"] as? String),
            episodeCount = (data["episodeCount"] as? Long)?.toInt() ?: 0,
            totalViews  = (data["totalViews"]  as? Long)?.toInt() ?: 0,
            totalHypes  = (data["totalHypes"]  as? Long)?.toInt() ?: 0,
            totalCools  = (data["totalCools"]  as? Long)?.toInt() ?: 0,
            createdAt   = (data["createdAt"]   as? Timestamp)?.toDate() ?: Date(),
            updatedAt   = (data["updatedAt"]   as? Timestamp)?.toDate() ?: Date()
        )
    }

    private fun decodeEpisode(data: Map<String, Any>, id: String): VideoCollection? {
        val creatorID = data["creatorID"] as? String ?: return null
        val title     = data["title"]     as? String ?: ""
        @Suppress("UNCHECKED_CAST")
        val segmentIDs = (data["segmentIDs"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        return VideoCollection(
            id            = id,
            title         = title,
            description   = data["description"]   as? String ?: "",
            creatorID     = creatorID,
            creatorName   = data["creatorName"]    as? String ?: "",
            coverImageURL = data["coverImageURL"]  as? String,
            segmentIDs    = segmentIDs,
            segmentCount  = (data["segmentCount"]  as? Long)?.toInt() ?: segmentIDs.size,
            totalDuration = (data["totalDuration"] as? Number)?.toDouble() ?: 0.0,
            status        = CollectionStatus.from(data["status"] as? String),
            visibility    = CollectionVisibility.from(data["visibility"] as? String),
            allowReplies  = data["allowReplies"]   as? Boolean ?: true,
            contentType   = CollectionContentType.from(data["contentType"] as? String),
            showId        = data["showId"]         as? String,
            seasonId      = data["seasonId"]       as? String,
            episodeNumber = (data["episodeNumber"] as? Long)?.toInt(),
            publishedAt   = (data["publishedAt"]   as? Timestamp)?.toDate(),
            createdAt     = (data["createdAt"]     as? Timestamp)?.toDate() ?: Date(),
            updatedAt     = (data["updatedAt"]     as? Timestamp)?.toDate() ?: Date(),
            totalViews    = (data["totalViews"]    as? Long)?.toInt() ?: 0,
            totalHypes    = (data["totalHypes"]    as? Long)?.toInt() ?: 0,
            totalCools    = (data["totalCools"]    as? Long)?.toInt() ?: 0,
            totalReplies  = (data["totalReplies"]  as? Long)?.toInt() ?: 0,
            totalShares   = (data["totalShares"]   as? Long)?.toInt() ?: 0
        )
    }
}