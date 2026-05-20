package com.stitchsocial.club.services

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * ReportService - User-initiated content + account reporting.
 *
 * Wraps the `submitReport` Cloud Function (deployed from
 * StitchSocial-Functions/index.js). The function:
 *   - validates the caller is authenticated
 *   - rejects self-reports
 *   - records the report (deterministic ID prevents spam-tapping)
 *   - increments strikeCount on the offending user
 *   - auto-suspends at REPORT_STRIKE_THRESHOLD = 5
 *
 * Reasons must be one of the values in REASONS below — must match the
 * server-side REPORT_REASONS set.
 *
 * Layer 4: Core Services
 * Dependencies: FirebaseFunctions
 */
class ReportService {

    companion object {
        private const val TAG = "ReportService"

        const val REASON_ADULT = "adult"
        const val REASON_VIOLENCE = "violence"
        const val REASON_HATE = "hate"
        const val REASON_IP_INFRINGEMENT = "ipInfringement"
        const val REASON_SPAM = "spam"
        const val REASON_IMPERSONATION = "impersonation"
        const val REASON_MINOR_SAFETY = "minorSafety"
        const val REASON_OTHER = "other"

        const val TARGET_VIDEO = "video"
        const val TARGET_USER = "user"
    }

    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("us-central1")

    /**
     * Submit a report. Returns the server-side result map on success or
     * throws on failure (network / validation / auth).
     */
    suspend fun submitReport(
        targetType: String,
        targetID: String,
        reason: String,
        note: String? = null
    ): Map<String, Any?> {
        require(targetType in setOf(TARGET_VIDEO, TARGET_USER)) {
            "targetType must be 'video' or 'user'"
        }
        require(reason in setOf(
            REASON_ADULT, REASON_VIOLENCE, REASON_HATE, REASON_IP_INFRINGEMENT,
            REASON_SPAM, REASON_IMPERSONATION, REASON_MINOR_SAFETY, REASON_OTHER
        )) { "invalid reason: $reason" }
        require(targetID.isNotBlank()) { "targetID required" }

        val payload = mutableMapOf<String, Any>(
            "targetType" to targetType,
            "targetID" to targetID,
            "reason" to reason
        )
        val trimmedNote = note?.trim()
        if (!trimmedNote.isNullOrEmpty()) {
            payload["note"] = trimmedNote.take(1000)
        }

        return try {
            val result = functions.getHttpsCallable("submitReport").call(payload).await()
            @Suppress("UNCHECKED_CAST")
            val data = (result.data as? Map<String, Any?>) ?: emptyMap()
            Log.d(TAG, "✅ submitReport OK: target=$targetID reason=$reason")
            data
        } catch (e: Exception) {
            Log.e(TAG, "❌ submitReport failed: ${e.message}")
            throw e
        }
    }
}
