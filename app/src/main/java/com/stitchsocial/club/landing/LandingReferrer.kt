package com.stitchsocial.club.landing

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.stitchsocial.club.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Landing a new install came from, and whether first-launch routing has run.
 *
 * A guest without the app scans a table QR; the website sends Android to the
 * Play Store with `referrer=landing=<slug>&t=<spot>`. Play hands that string
 * back through the Install Referrer API on the first launch after install —
 * and the guest then has to sign up and pass the age gate before there is a
 * Landing screen to open. So it is read once at app start and held in
 * SharedPreferences (same reasoning as PendingReferral: the process can die
 * between the store and the signup), then consumed by first-launch routing.
 *
 * Layer 4: Core Services
 */
object LandingReferrer {

    private const val PREFS = "stitch_landing"
    private const val KEY_READ = "referrerRead"
    private const val KEY_SLUG = "referrerSlug"
    private const val KEY_SPOT = "referrerSpot"
    private const val KEY_ROUTED = "firstLaunchRouted"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Completes when the one referrer read is done (or was done on an earlier launch). */
    private val readDone = CompletableDeferred<Unit>()

    /**
     * Read the install referrer once per install. Called from
     * MainActivity.onCreate; every later launch returns at the flag.
     */
    fun readOnce(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_READ, false)) { readDone.complete(Unit); return }
        val client = runCatching { InstallReferrerClient.newBuilder(context.applicationContext).build() }
            .getOrNull() ?: run { readDone.complete(Unit); return }
        runCatching {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        val raw = runCatching { client.installReferrer.installReferrer }.getOrNull()
                        parse(raw)?.let { (slug, spot) ->
                            if (BuildConfig.DEBUG) { println("🍽️ LANDING: install referrer → $slug ($spot)") }
                            p.edit().putString(KEY_SLUG, slug).putString(KEY_SPOT, spot).apply()
                        }
                        p.edit().putBoolean(KEY_READ, true).apply()
                    } else if (responseCode == InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED) {
                        // No Play Store (sideload, emulator image): never will be.
                        p.edit().putBoolean(KEY_READ, true).apply()
                    }
                    // SERVICE_UNAVAILABLE etc: leave unread, try next launch.
                    runCatching { client.endConnection() }
                    readDone.complete(Unit)
                }

                override fun onInstallReferrerServiceDisconnected() {
                    readDone.complete(Unit)
                }
            })
        }.onFailure { readDone.complete(Unit) }
    }

    /**
     * `landing=<slug>&t=<spot>`. Play normally hands it back decoded; some
     * paths leave it percent-encoded once, so a string with no `=` in it is
     * decoded before parsing.
     */
    internal fun parse(raw: String?): Pair<String, String?>? {
        if (raw.isNullOrBlank()) return null
        val decoded = if (!raw.contains('=')) Uri.decode(raw) else raw
        val uri = Uri.parse("stitch://r?$decoded")
        val slug = uri.getQueryParameter("landing")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return slug to uri.getQueryParameter("t")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The stored referrer, waiting briefly for the read if it is still in flight. */
    suspend fun pending(context: Context): Pair<String, String?>? {
        withTimeoutOrNull(3_000) { readDone.await() }
        val p = prefs(context)
        val slug = p.getString(KEY_SLUG, null)?.takeIf { it.isNotBlank() } ?: return null
        return slug to p.getString(KEY_SPOT, null)
    }

    fun clearPending(context: Context) {
        prefs(context).edit().remove(KEY_SLUG).remove(KEY_SPOT).apply()
    }

    /** First-launch routing runs once per install, whichever step answers. */
    fun hasRouted(context: Context) = prefs(context).getBoolean(KEY_ROUTED, false)

    fun markRouted(context: Context) {
        prefs(context).edit().putBoolean(KEY_ROUTED, true).apply()
    }
}
