package com.stitchsocial.club.services

import com.google.firebase.functions.FirebaseFunctions
import com.stitchsocial.club.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Direct brand ads — ad_campaigns a brand bought on stitchsocial.me/brand.
 *
 * Every native ad slot asks here first and falls back to AdMob when nothing
 * comes back. The server picks the campaign (active, in-window, budget left,
 * weighted by CPM), enforces the daily frequency cap and never serves a brand
 * its own ad. Each serve carries a token; the impression and click are
 * recorded against it, once each, and the impression is what debits the
 * brand's budget and credits the thread owner.
 *
 * Callables: getBrandAd, recordBrandAdImpression, recordBrandAdClick.
 *
 * Layer 4: Core Services
 */
object BrandAdService {

    /**
     * How long a slot waits for a brand ad before going to AdMob. Short on
     * purpose: the feed never waits on this, and a cold function that misses
     * the window simply loses the slot to AdMob.
     */
    private const val FETCH_TIMEOUT_MS = 1_500L

    private val functions by lazy { FirebaseFunctions.getInstance("us-central1") }

    /**
     * @param placement "feed" or "thread"
     * @param threadOwnerID whose content hosts the slot — they are credited
     * @return the ad, or null for no ad / timeout / any failure (→ AdMob)
     */
    suspend fun getBrandAd(
        placement: String,
        threadOwnerID: String? = null,
        videoID: String? = null
    ): BrandAd? {
        val data = mutableMapOf<String, Any>("placement" to placement)
        threadOwnerID?.takeIf { it.isNotBlank() }?.let { data["threadOwnerID"] = it }
        videoID?.takeIf { it.isNotBlank() }?.let { data["videoID"] = it }
        return try {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                val result = functions.getHttpsCallable("getBrandAd").call(data).await()
                @Suppress("UNCHECKED_CAST")
                val ad = (result.data as? Map<String, Any?>)?.get("ad") as? Map<String, Any?>
                ad?.let { BrandAd.fromMap(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) println("📺 BRAND AD: getBrandAd failed [$placement] — ${e.message}")
            null
        }
    }

    /** Once per token — the server ignores repeats, the card also only sends one. */
    suspend fun recordImpression(token: String) {
        runCatching {
            functions.getHttpsCallable("recordBrandAdImpression").call(mapOf("token" to token)).await()
        }.onFailure { if (BuildConfig.DEBUG) println("📺 BRAND AD: impression failed — ${it.message}") }
    }

    suspend fun recordClick(token: String) {
        runCatching {
            functions.getHttpsCallable("recordBrandAdClick").call(mapOf("token" to token)).await()
        }.onFailure { if (BuildConfig.DEBUG) println("📺 BRAND AD: click failed — ${it.message}") }
    }
}

/** One served brand ad. [token] identifies this serve (valid ~10 min). */
data class BrandAd(
    val token: String,
    val campaignID: String,
    val brandName: String,
    val title: String,
    val videoURL: String?,
    val thumbnailURL: String?,
    val ctaText: String,
    val ctaURL: String,
    /** Creative shape, width ÷ height (getBrandAd `aspectRatio`). Null = 9:16. */
    val aspectRatio: Double? = null,
    /** "auto" | "fill" | "fit" (getBrandAd `fit`). Media fit, MEDIA_FIT_CONTRACT. */
    val fit: String = "auto"
) {
    companion object {
        fun fromMap(m: Map<String, Any?>): BrandAd? {
            val token = m["token"] as? String ?: return null
            val ctaURL = m["ctaURL"] as? String ?: return null
            return BrandAd(
                token = token,
                campaignID = m["campaignID"] as? String ?: "",
                brandName = m["brandName"] as? String ?: "",
                title = m["title"] as? String ?: "",
                videoURL = (m["videoURL"] as? String)?.takeIf { it.isNotBlank() },
                thumbnailURL = (m["thumbnailURL"] as? String)?.takeIf { it.isNotBlank() },
                ctaText = (m["ctaText"] as? String)?.takeIf { it.isNotBlank() } ?: "Learn more",
                ctaURL = ctaURL,
                aspectRatio = com.stitchsocial.club.foundation.MediaFit.aspectFrom(m["aspectRatio"]),
                fit = com.stitchsocial.club.foundation.MediaFit.normalizedFit(m["fit"])
            )
        }
    }
}
