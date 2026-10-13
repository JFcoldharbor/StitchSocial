package com.stitchsocial.club.services

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * What the app-launch age gate falls back on when its read of users/{uid}
 * fails (offline launch, a cold-start permission hiccup). It used to open the
 * full app, so a teen who could make the read fail was an adult for that
 * session. In order, it now trusts:
 *
 *   1. the `audienceLane` claim the server writes on the ID token
 *      (applyAgeLane) — read from the cached token, so no network while the
 *      token is still valid, and a client can't forge it;
 *   2. the last state this device resolved for THIS account;
 *   3. otherwise "teen" — fail closed. An adult with neither (a first launch
 *      offline with an expired token) sees the teen lane for that session;
 *      the next successful read restores the full app.
 *
 * States are MainScreen's ageGateState values: "ok" | "teen" | "blocked".
 */
object AgeGateFallback {

    private const val PREFS = "stitch_age_gate"

    /** Remember a state the gate resolved from a real read (or the prompt). */
    fun remember(context: Context, uid: String, state: String) {
        if (state != "ok" && state != "teen" && state != "blocked") return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(uid, state).apply()
    }

    suspend fun resolve(context: Context, uid: String): String {
        val claim = try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user?.uid == uid) user.getIdToken(false).await().claims["audienceLane"] as? String else null
        } catch (e: Exception) {
            null
        }
        when (claim) {
            "adult" -> return "ok"
            "teen" -> return "teen"
            "blocked" -> return "blocked"
        }
        val cached = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(uid, null)
        if (cached == "ok" || cached == "teen" || cached == "blocked") return cached
        return "teen"
    }

    /** The TeenSafety lane for a gate state. */
    fun laneFor(state: String): String = if (state == "ok") "adult" else "teen"
}
