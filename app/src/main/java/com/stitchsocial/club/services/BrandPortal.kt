package com.stitchsocial.club.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * The brand portal at stitchsocial.me/brand, and the one way the app gets there.
 *
 * Brands pay Stitch on the website (Stripe Checkout) for campaigns, promoted
 * posts and ad campaigns. The app never takes a brand payment: "New campaign",
 * "Promote this post" and "Advertise" all open the portal in the browser.
 *
 * Layer 4: Core Services
 */
object BrandPortal {

    /** Opens a portal page in the browser — the app's usual ACTION_VIEW hand-off. */
    fun open(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // A brand is users/{uid}.accountType == "business". Read once per account
    // per process: the post menu asks on every open, and the answer does not
    // change without a sign-out.
    private val businessCache = mutableMapOf<String, Boolean>()

    suspend fun isBusinessAccount(uid: String): Boolean {
        if (uid.isBlank()) return false
        businessCache[uid]?.let { return it }
        val isBusiness = runCatching {
            FirebaseFirestore.getInstance("stitchfin").collection("users")
                .document(uid).get().await()
                .getString("accountType") == "business"
        }.getOrNull() ?: return false   // unknown → not cached, ask again next time
        businessCache[uid] = isBusiness
        return isBusiness
    }
}
