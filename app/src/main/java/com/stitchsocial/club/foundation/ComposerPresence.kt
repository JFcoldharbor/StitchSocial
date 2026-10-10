package com.stitchsocial.club.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import java.util.concurrent.ConcurrentHashMap

/**
 * Which in-screen composers are open right now, so a notification or link tap
 * can ask "Leave?" before throwing their work away.
 *
 * The main recorder lives in MainActivity's modal state, which the router can
 * read directly. These don't: CommunityVideoComposerSheet, EpisodeComposerView
 * and the rest are local state deep inside a tab, and the route's interrupt
 * (remounting the tab content) discards them silently. So each registers here
 * while it's open and holding work — same idea as CreatorScreenPresence for a
 * live host.
 *
 * Keyed by a per-composition token, so two composers open at once (a sheet
 * over a sheet) each come and go on their own.
 */
object ComposerPresence {

    /** What leaving loses, and how the "Leave?" dialog says it. */
    enum class Kind(val loses: String) {
        /** A camera is open / recording. */
        CAMERA("Your recording will be lost."),
        /** A post, clip or episode being put together. */
        POST("Your post will be lost."),
        /** Edits to something that already exists. */
        CHANGES("Your changes will be lost.")
    }

    private val open = ConcurrentHashMap<Any, Kind>()

    fun enter(token: Any, kind: Kind) { open[token] = kind }
    fun leave(token: Any) { open.remove(token) }

    /** The most serious loss among the open composers; null when none is open. */
    fun lossMessage(): String? {
        val kinds = open.values.toSet()
        return Kind.entries.firstOrNull { it in kinds }?.loses
    }
}

/**
 * Registers the calling screen with [ComposerPresence] while it's composed and
 * [active] (pass a dirty check where there is one, so an untouched form doesn't
 * ask). Call unconditionally at the top of the composable.
 */
@Composable
fun RegisterComposerPresence(kind: ComposerPresence.Kind, active: Boolean = true) {
    val token = remember { Any() }
    DisposableEffect(active, kind) {
        if (active) ComposerPresence.enter(token, kind)
        onDispose { ComposerPresence.leave(token) }
    }
}
