package com.stitchsocial.club.ads

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.stitchsocial.club.BuildConfig

/**
 * A native advanced ad, rendered inside a real NativeAdView (Android parity with
 * the iOS native card).
 *
 * THE RULES THAT COST iOS THE MOST TIME, all of which apply identically here:
 *
 * 1. The ad MUST render inside a NativeAdView with its asset views REGISTERED.
 *    Drawing the same fields in Compose shows an ad and earns NOTHING — no
 *    impression, no click, no revenue.
 * 2. Register an asset view ONLY when the ad actually carries that asset. A
 *    registered-but-empty advertiserView reads to the validator as a MISSING
 *    required element ("ad attribution missing"), which is a policy failure.
 * 3. MediaView must be at least 120x120dp or video creatives are flagged.
 * 4. setNativeAd() goes LAST, after every registration.
 * 5. Retain the loader while a request is in flight, and destroy the NativeAd
 *    when the card leaves composition or it leaks.
 */
@Composable
fun NativeAdCard(
    unitId: String,
    owner: AdConfig.ImpressionOwner,
    placement: String,
    modifier: Modifier = Modifier,
    /** Subscribers bought an ad-free experience — honour it. */
    enabled: Boolean = true
) {
    if (!enabled) return

    val context = LocalContext.current
    var nativeAd by remember(unitId) { mutableStateOf<NativeAd?>(null) }

    // ONE request per visit, not per recomposition. A feed that re-requests on
    // every scroll burns fill and looks like click-fraud from Google's side.
    LaunchedEffect(unitId) {
        if (!AdMobBootstrap.isStarted) return@LaunchedEffect
        loadNativeAd(context, unitId, owner, placement) { ad -> nativeAd = ad }
    }

    DisposableEffect(nativeAd) {
        onDispose { nativeAd?.destroy() }
    }

    val ad = nativeAd ?: return

    AndroidView(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        factory = { ctx -> buildAdView(ctx) },
        update = { root -> bind(root, ad) }
    )
}

/** Retained for the life of the request — an AdLoader collected mid-flight
 *  never calls back, which presents as "no ad, no error, no log". */
private var inFlight: MutableSet<AdLoader> = mutableSetOf()

private fun loadNativeAd(
    context: Context,
    unitId: String,
    owner: AdConfig.ImpressionOwner,
    placement: String,
    onLoaded: (NativeAd) -> Unit
) {
    val videoOptions = VideoOptions.Builder()
        // Muted start is a load-time option, not something you can set later on
        // an already-built ad.
        .setStartMuted(true)
        .build()

    val adOptions = NativeAdOptions.Builder()
        .setVideoOptions(videoOptions)
        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
        .build()

    lateinit var loader: AdLoader
    loader = AdLoader.Builder(context, unitId)
        .forNativeAd { ad ->
            // Revenue is reported here, per impression, with the precision
            // Google actually has — this is what the ledger records so a
            // creator's share is computed from real value rather than an
            // estimate.
            ad.setOnPaidEventListener { value ->
                AdImpressionLedger.record(
                    owner = owner,
                    placement = placement,
                    responseID = ad.responseInfo?.responseId.orEmpty(),
                    valueMicros = value.valueMicros,
                    currencyCode = value.currencyCode,
                    precision = value.precisionType
                )
            }
            onLoaded(ad)
            inFlight.remove(loader)
        }
        .withAdListener(object : AdListener() {
            override fun onAdFailedToLoad(error: LoadAdError) {
                if (BuildConfig.DEBUG) {
                    println("📺 ADMOB: load failed [$placement] — ${error.message}")
                }
                inFlight.remove(loader)
            }
        })
        .withNativeAdOptions(adOptions)
        .build()

    inFlight.add(loader)
    loader.loadAd(AdRequest.Builder().build())
}

/** Programmatic hierarchy so the asset views are unmistakably the registered
 *  ones — an XML id typo silently produces an unregistered asset. */
private fun buildAdView(ctx: Context): NativeAdView {
    val root = NativeAdView(ctx)
    val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(AndroidColor.parseColor("#141418"))
        setPadding(28, 28, 28, 28)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    val label = TextView(ctx).apply {
        text = "SPONSORED"          // Always labelled. Policy, and honesty.
        setTextColor(AndroidColor.parseColor("#8AFFFFFF"))
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
        tag = "label"
    }
    val headline = TextView(ctx).apply {
        setTextColor(AndroidColor.WHITE); textSize = 15f
        typeface = Typeface.DEFAULT_BOLD; tag = "headline"
    }
    val body = TextView(ctx).apply {
        setTextColor(AndroidColor.parseColor("#B3FFFFFF")); textSize = 12f; tag = "body"
    }
    val advertiser = TextView(ctx).apply {
        setTextColor(AndroidColor.parseColor("#8AFFFFFF")); textSize = 11f; tag = "advertiser"
    }
    val icon = ImageView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(96, 96); tag = "icon"
    }
    // >= 120x120dp for video creatives, or the validator flags it.
    val media = MediaView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (140 * ctx.resources.displayMetrics.density).toInt()
        )
        tag = "media"
    }
    val cta = Button(ctx).apply {
        setTextColor(AndroidColor.WHITE)
        setBackgroundColor(AndroidColor.parseColor("#E91E63"))
        textSize = 13f
        gravity = Gravity.CENTER
        tag = "cta"
    }

    listOf(label, headline, body, advertiser, icon, media, cta).forEach { column.addView(it) }
    root.addView(column)

    root.headlineView = headline
    root.bodyView = body
    root.advertiserView = advertiser
    root.iconView = icon
    root.mediaView = media
    root.callToActionView = cta
    return root
}

private fun bind(root: NativeAdView, ad: NativeAd) {
    fun view(tag: String): View? = root.findViewWithTag(tag)

    (view("headline") as? TextView)?.apply {
        text = ad.headline
        visibility = if (ad.headline.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    // Registered ONLY when present. An empty registered asset reads to the
    // validator as a missing required element, which is how iOS failed
    // "ad attribution missing" three times.
    (view("body") as? TextView)?.apply {
        text = ad.body
        visibility = if (ad.body.isNullOrBlank()) View.GONE else View.VISIBLE
    }
    root.bodyView = if (ad.body.isNullOrBlank()) null else view("body")

    (view("advertiser") as? TextView)?.apply {
        text = ad.advertiser
        visibility = if (ad.advertiser.isNullOrBlank()) View.GONE else View.VISIBLE
    }
    root.advertiserView = if (ad.advertiser.isNullOrBlank()) null else view("advertiser")

    (view("icon") as? ImageView)?.apply {
        val drawable = ad.icon?.drawable
        setImageDrawable(drawable)
        visibility = if (drawable == null) View.GONE else View.VISIBLE
    }
    root.iconView = if (ad.icon?.drawable == null) null else view("icon")

    (view("cta") as? Button)?.apply {
        text = ad.callToAction
        visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
    }
    root.callToActionView = if (ad.callToAction.isNullOrBlank()) null else view("cta")

    (view("media") as? MediaView)?.let { mv ->
        root.mediaView = mv
        ad.mediaContent?.let { mv.mediaContent = it }
    }

    // LAST, after every registration — setting it earlier registers nothing.
    root.setNativeAd(ad)
}
