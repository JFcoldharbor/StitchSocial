package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * users/{uid}/private/profile — the owner-only home of a user's email and
 * birthdate: { email: String, birthdate: Timestamp, updatedAt }.
 *
 * Neither field may be written to the public users/{uid} doc any more (a
 * server trigger strips them for old builds). Other users' emails are not
 * readable at all; the app never looks a user up by email.
 */
object PrivateProfileService {

    private val db get() = FirebaseFirestore.getInstance("stitchfin")

    private fun ref(uid: String) =
        db.collection("users").document(uid).collection("private").document("profile")

    /** Merge-writes the owner's email. */
    suspend fun writeEmail(uid: String, email: String) {
        if (uid.isEmpty() || email.isBlank()) return
        ref(uid).set(
            mapOf("email" to email, "updatedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge()
        ).await()
    }

    /**
     * Merge-writes the owner's birthdate. Only called from the age gate /
     * birthday prompt, which only appears while no birthdate is on file —
     * the rules allow it to be set once.
     */
    suspend fun writeBirthdate(uid: String, birthdate: Date) {
        ref(uid).set(
            mapOf("birthdate" to Timestamp(birthdate), "updatedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge()
        ).await()
    }

    /** The owner's birthdate, or null if none is on file. */
    suspend fun readBirthdate(uid: String): Date? {
        if (uid.isEmpty()) return null
        val snap = ref(uid).get().await()
        return (snap.get("birthdate") as? Timestamp)?.toDate()
    }

    /**
     * The owner's birthdate for the age gate: the private doc first, then the
     * legacy public privacySettings.birthdate (older accounts, until the
     * server migrates/strips it). A failed private read counts as "not found"
     * so the legacy value and ageGroup can still answer.
     */
    suspend fun resolveOwnBirthdate(uid: String, publicPrivacySettings: Map<String, Any>?): Date? {
        val fromPrivate = try { readBirthdate(uid) } catch (_: Exception) { null }
        return fromPrivate ?: (publicPrivacySettings?.get("birthdate") as? Timestamp)?.toDate()
    }
}
