package com.stitchsocial.club.services

import android.text.format.DateFormat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.google.firebase.storage.StorageException
import com.stitchsocial.club.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The signed-in user's enforcement state, and the one gate every create entry
 * point asks before doing any work.
 *
 * Read from the user's own `users/{uid}` doc (isSuspended, suspensionExpiresAt,
 * suspensionReason, isBanned) through a live listener, so a suspension lifting
 * on the server takes the banner down and re-opens posting without a restart.
 *
 * The server enforces this too (custom claim + Firestore/Storage rules). This
 * exists so the app refuses BEFORE recording, exporting or uploading — the CDN
 * upload isn't covered by those rules, so a client that only learns about the
 * suspension from a rejected doc write has already shipped the file.
 *
 * One state, one sheet: [sheet] is rendered once, at the root of MainScreen
 * (see AccountSuspensionSheetHost), and every surface calls [guardPosting].
 */
object AccountStanding {

    const val TERMS_URL = "https://stitchsocial.me/terms"

    data class Enforcement(
        val isSuspended: Boolean = false,
        val expiresAt: Date? = null,
        val isBanned: Boolean = false,
        val reason: String? = null
    ) {
        fun isSuspendedNow(now: Date = Date()): Boolean =
            isBanned || (isSuspended && (expiresAt == null || expiresAt.after(now)))
    }

    /** A request to show the shared sheet; [message] replaces its headline
     *  (used for an upload that was refused). */
    data class SheetRequest(val message: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val db get() = FirebaseFirestore.getInstance("stitchfin")

    private var registration: ListenerRegistration? = null
    private var listeningUid: String? = null
    private var expiryJob: Job? = null

    private val _state = MutableStateFlow(Enforcement())
    val state: StateFlow<Enforcement> = _state.asStateFlow()

    /** [Enforcement.isSuspendedNow], re-evaluated when a timed suspension
     *  expires even if the server hasn't cleared the flag yet. */
    private val _suspendedNow = MutableStateFlow(false)
    val suspendedNow: StateFlow<Boolean> = _suspendedNow.asStateFlow()

    private val _sheet = MutableStateFlow<SheetRequest?>(null)
    val sheet: StateFlow<SheetRequest?> = _sheet.asStateFlow()

    val isSuspendedNow: Boolean get() = _state.value.isSuspendedNow()

    // MARK: - Listener

    /** Attach to the signed-in user's own doc. Idempotent per uid. */
    fun start(uid: String) {
        if (listeningUid == uid && registration != null) return
        stop()
        listeningUid = uid
        registration = db.collection("users").document(uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    if (BuildConfig.DEBUG) println("⚠️ STANDING: listener error ${err.message}")
                    return@addSnapshotListener
                }
                val data = snap?.data ?: return@addSnapshotListener
                apply(parse(data))
            }
    }

    /** Signed out: forget everything. */
    fun stop() {
        registration?.remove()
        registration = null
        listeningUid = null
        expiryJob?.cancel()
        expiryJob = null
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
        _state.value = Enforcement()
        _suspendedNow.value = false
        _sheet.value = null
    }

    private fun parse(data: Map<String, Any?>): Enforcement = Enforcement(
        isSuspended = data["isSuspended"] == true,
        expiresAt = when (val raw = data["suspensionExpiresAt"]) {
            is Timestamp -> raw.toDate()
            is Date -> raw
            is Number -> Date(raw.toLong())
            else -> null
        },
        isBanned = data["isBanned"] == true,
        reason = (data["suspensionReason"] as? String)?.takeIf { it.isNotBlank() }
    )

    private fun apply(e: Enforcement) {
        val before = _state.value
        _state.value = e
        recompute()
        // The server mirrors this doc into a `suspended` custom claim (read by
        // Storage rules), set by a trigger a moment after the doc changes. The
        // claim only reaches us on an ID-token refresh, so refresh on a flip.
        if (before.isSuspended != e.isSuspended || before.isBanned != e.isBanned) {
            refreshIdToken()
        }
        expiryJob?.cancel()
        val expires = e.expiresAt
        if (!e.isBanned && e.isSuspended && expires != null && expires.after(Date())) {
            expiryJob = scope.launch {
                delay(expires.time - System.currentTimeMillis() + 500)
                recompute()
            }
        }
    }

    private var tokenRefreshJob: Job? = null

    /** Now, then ~4s and ~15s later — the claim trigger lags the doc write. */
    private fun refreshIdToken() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = scope.launch {
            for (wait in longArrayOf(0L, 4_000L, 11_000L)) {
                if (wait > 0) delay(wait)
                val user = FirebaseAuth.getInstance().currentUser ?: return@launch
                runCatching { user.getIdToken(true).await() }
                    .onFailure { if (BuildConfig.DEBUG) println("⚠️ STANDING: token refresh failed ${it.message}") }
            }
        }
    }

    private fun recompute() {
        val now = _state.value.isSuspendedNow()
        _suspendedNow.value = now
        // Lifted while the sheet was up: nothing left to explain.
        if (!now) _sheet.value = null
    }

    // MARK: - Gate

    /**
     * The check every create entry point makes first. Returns true when posting
     * is allowed; otherwise shows the shared sheet and returns false — the
     * caller must not open the recorder/composer or start any work.
     */
    fun guardPosting(): Boolean {
        if (!isSuspendedNow) return true
        presentSheet()
        return false
    }

    /** For services: throw before any export or upload starts. */
    fun requirePosting() {
        if (isSuspendedNow) throw AccountSuspendedException(blockedUploadMessage())
    }

    fun presentSheet(message: String? = null) {
        _sheet.value = SheetRequest(message)
    }

    fun dismissSheet() {
        _sheet.value = null
    }

    // MARK: - Upload failures

    /**
     * If [error] is a refusal because the account is suspended, the message to
     * show instead of the raw error; null when it's an ordinary failure.
     *
     * A permission-denied while we don't yet know of a suspension may be the
     * state flipping mid-upload, so the doc is re-read from the server once
     * before deciding.
     */
    suspend fun suspensionMessageFor(error: Throwable?): String? {
        if (error == null) return null
        if (error.causeChain().any { it is AccountSuspendedException }) return blockedUploadMessage()
        if (!isPermissionDenied(error)) return null
        if (!isSuspendedNow) refreshFromServer()
        return if (isSuspendedNow) blockedUploadMessage() else null
    }

    private suspend fun refreshFromServer() {
        val uid = listeningUid ?: return
        runCatching {
            val snap = db.collection("users").document(uid).get(Source.SERVER).await()
            snap.data?.let { data -> withContext(Dispatchers.Main) { apply(parse(data)) } }
        }
    }

    fun isPermissionDenied(error: Throwable): Boolean = error.causeChain().any { e ->
        when (e) {
            is FirebaseFirestoreException -> e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
            is StorageException -> e.errorCode == StorageException.ERROR_NOT_AUTHORIZED
            else -> {
                val m = e.message?.lowercase() ?: ""
                "permission_denied" in m || "insufficient permissions" in m ||
                    "not authorized" in m
            }
        }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause.takeIf { c -> c !== it } }.take(8)

    // MARK: - Copy

    /** Local short date, e.g. "Oct 16". */
    fun endDateLabel(): String? {
        val d = _state.value.expiresAt ?: return null
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd")
        return SimpleDateFormat(pattern, Locale.getDefault()).format(d)
    }

    fun bannerText(): String {
        val s = _state.value
        if (s.isBanned) return "Your account has been permanently suspended."
        val until = endDateLabel()?.let { " until $it" } ?: ""
        return "Your account is suspended$until. You can watch and react, but you can't post."
    }

    fun blockedUploadMessage(): String {
        val s = _state.value
        val until = if (s.isBanned) null else endDateLabel()
        return if (until != null) "You can't post while your account is suspended (until $until)."
        else "You can't post while your account is suspended."
    }
}

/** Thrown by services that refuse to start work for a suspended account. */
class AccountSuspendedException(message: String) : Exception(message)
