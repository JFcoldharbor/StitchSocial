/*
 * SponsoredSlotService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 3: Services — First-party sponsored ad slots (port of iOS SponsoredSlotService)
 *
 * - getActiveSlots(): one fetch per feed load; isActive == true server-side,
 *   isLiveNow window filter client-side.
 * - recordImpression()/recordTap(): FieldValue.increment(1) fire-and-forget,
 *   deduped per app session via companion sets so a feed reload never double-counts.
 *   Security rules only allow these two counter increments from clients.
 */
package com.stitchsocial.club.services

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.SponsoredSlot
import kotlinx.coroutines.tasks.await
import java.util.Collections

class SponsoredSlotService {

    private val db = FirebaseFirestore.getInstance("stitchfin")

    /**
     * Fetch active sponsored slots — one Firestore read per feed load.
     * Query isActive == true, decode, filter live window, take [limit].
     */
    suspend fun getActiveSlots(limit: Int = 4): List<SponsoredSlot> {
        return try {
            val snapshot = db.collection("sponsoredSlots")
                .whereEqualTo("isActive", true)
                .get().await()
            val slots = snapshot.documents
                .mapNotNull { doc -> doc.data?.let { SponsoredSlot.fromFirestore(doc.id, it) } }
                .filter { it.isLiveNow }
                .take(limit)
            if (BuildConfig.DEBUG) { println("📣 SPONSORED: ${slots.size} live slot(s) loaded") }
            slots
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("⚠️ SPONSORED: Slot load failed — ${e.message}") }
            emptyList()
        }
    }

    /** Fire-and-forget impression count. Deduped per app session. */
    fun recordImpression(slotID: String) {
        if (slotID.isBlank()) return
        if (!impressedThisSession.add(slotID)) return  // already counted this session
        db.collection("sponsoredSlots").document(slotID)
            .update("impressionCount", FieldValue.increment(1))
            .addOnFailureListener { e ->
                if (BuildConfig.DEBUG) { println("⚠️ SPONSORED: Impression write failed — ${e.message}") }
            }
        if (BuildConfig.DEBUG) { println("📣 SPONSORED: Impression recorded for $slotID") }
    }

    /** Fire-and-forget tap count. Deduped per app session. */
    fun recordTap(slotID: String) {
        if (slotID.isBlank()) return
        if (!tappedThisSession.add(slotID)) return  // already counted this session
        db.collection("sponsoredSlots").document(slotID)
            .update("tapCount", FieldValue.increment(1))
            .addOnFailureListener { e ->
                if (BuildConfig.DEBUG) { println("⚠️ SPONSORED: Tap write failed — ${e.message}") }
            }
        if (BuildConfig.DEBUG) { println("📣 SPONSORED: Tap recorded for $slotID") }
    }

    companion object {
        // Session-scoped dedupe — survives view/viewmodel recreation so a feed
        // reload or reshuffle never double-counts (mirrors iOS static Sets).
        private val impressedThisSession: MutableSet<String> =
            Collections.synchronizedSet(mutableSetOf<String>())
        private val tappedThisSession: MutableSet<String> =
            Collections.synchronizedSet(mutableSetOf<String>())
    }
}
