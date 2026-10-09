package com.stitchsocial.club.ads

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.services.SubscriptionService
import com.stitchsocial.club.services.TeenSafety
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

/**
 * The part-to-part ad break inside an episode (iOS parity with
 * CollectionAdBreak.swift).
 *
 * A full-screen native video ad between two parts, skippable after 5s.
 * Creator-owned: the episode belongs to whoever made it, so the impression is
 * split by that creator's rank (AdConfig.creatorShare).
 *
 * Rules:
 *  - never before Part 1 or Part 2 — the first break comes after Part 2, then
 *    every 3 parts (before Parts 3, 6, 9 …)
 *  - at most 3 per viewing session
 *  - forward moves only; going back never shows one
 *  - no ad in hand at the break → no break; nobody waits on a request
 *  - nobody sees one who is: a subscriber to the creator (ad-free is what they
 *    pay for), the creator, a founder, in the teen lane, or signed out
 */
object CollectionAdBreakPolicy {
    const val MAX_PER_SESSION = 3
    const val SKIP_AFTER_SECONDS = 5
    const val AUTO_CONTINUE_SECONDS = 15
    const val PLACEMENT = "collectionsBreak"

    /** Whether moving forward onto part [nextIndex] (0-based) is a break point. */
    fun isBreak(nextIndex: Int): Boolean = nextIndex >= 2 && (nextIndex - 2) % 3 == 0
}

/** Per-player state: whether this viewer gets breaks, and the prefetched ads. */
class CollectionAdBreakController {

    var enabled: Boolean = false
        private set
    var owner: AdConfig.ImpressionOwner =
        AdConfig.ImpressionOwner(AdConfig.OwnerType.HOUSE, "", null)
        private set

    private val shownBefore = mutableSetOf<Int>()
    /** Ads by the part they precede; null value = request in flight or failed. */
    private val ads = mutableMapOf<Int, NativeAd?>()

    /** Decides once per open whether this viewer sees breaks, and who earns. */
    suspend fun configure(
        collection: VideoCollection,
        viewerID: String,
        viewerTier: UserTier?,
        viewerIsFounder: Boolean
    ) {
        enabled = false
        fun off(why: String) {
            if (BuildConfig.DEBUG) println("📺 AD BREAK: off — $why")
        }
        if (viewerID.isBlank()) return off("signed out")
        if (viewerID == collection.creatorID) return off("viewer is the creator")
        // Founders are kept off live ads (tapping your own is invalid traffic).
        // DEBUG builds only ever load Google's test unit, so founders can test
        // the break there.
        if (!BuildConfig.DEBUG &&
            (viewerIsFounder || viewerTier == UserTier.FOUNDER || viewerTier == UserTier.CO_FOUNDER)
        ) return off("founder")
        if (TeenSafety.viewerIsTeen) return off("teen lane")

        // Subscribers watch ad-free.
        val subscribed = runCatching {
            SubscriptionService.shared.isSubscribed(viewerID, collection.creatorID)
        }.getOrDefault(false)
        if (subscribed) return off("subscriber")

        // The creator's rank sets their share. Unknown rank → no break, rather
        // than an impression that pays the creator nothing.
        val creatorTier = runCatching {
            FirebaseFirestore.getInstance("stitchfin").collection("users")
                .document(collection.creatorID).get().await()
                .getString("tier")?.let { UserTier.fromRawValue(it) }
        }.getOrNull() ?: return off("creator rank unavailable")

        owner = AdConfig.ImpressionOwner(AdConfig.OwnerType.CREATOR, collection.creatorID, creatorTier)
        enabled = true
        if (BuildConfig.DEBUG) println("📺 AD BREAK: on — creator ${collection.creatorID.take(8)} @${creatorTier.rawValue}")
    }

    /** Call when the current part changes: requests the ad for the next break
     *  if it comes right after this part. */
    fun prefetch(context: Context, currentIndex: Int, segmentCount: Int) {
        val next = currentIndex + 1
        if (!enabled || next >= segmentCount || !CollectionAdBreakPolicy.isBreak(next)) return
        if (next in shownBefore || shownBefore.size >= CollectionAdBreakPolicy.MAX_PER_SESSION) return
        if (ads.containsKey(next) || !AdMobBootstrap.isStarted) return
        ads[next] = null
        if (BuildConfig.DEBUG) println("📺 AD BREAK: prefetching for the break before Part ${next + 1}")
        loadNativeAd(
            context, AdConfig.NativeUnit.collectionBreak, owner, CollectionAdBreakPolicy.PLACEMENT,
            onFailed = { ads.remove(next) }
        ) { ad -> ads[next] = ad }
    }

    /** The ready ad for a break before [nextIndex], if this move is a break and
     *  one has filled. Taking it uses up the break point. */
    fun take(nextIndex: Int): NativeAd? {
        if (!enabled || !CollectionAdBreakPolicy.isBreak(nextIndex)) return null
        if (nextIndex in shownBefore || shownBefore.size >= CollectionAdBreakPolicy.MAX_PER_SESSION) return null
        val ad = ads[nextIndex] ?: return null
        shownBefore.add(nextIndex)
        ads.remove(nextIndex)
        return ad
    }

    /** Destroys anything prefetched and never shown. */
    fun release() {
        ads.values.forEach { it?.destroy() }
        ads.clear()
    }
}

/**
 * The break itself: the ad full-screen, "Ad · Part N next", and a skip that
 * unlocks after 5s. Ends on its own after 15s.
 */
@Composable
fun CollectionAdBreakOverlay(
    ad: NativeAd,
    nextPartNumber: Int,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    var elapsed by remember(ad) { mutableIntStateOf(0) }
    var finished by remember(ad) { mutableStateOf(false) }
    val finish = {
        if (!finished) {
            finished = true
            onFinish()
        }
    }
    LaunchedEffect(ad) {
        while (!finished && elapsed < CollectionAdBreakPolicy.AUTO_CONTINUE_SECONDS) {
            delay(1_000)
            elapsed += 1
        }
        finish()
    }
    DisposableEffect(ad) { onDispose { ad.destroy() } }

    val remaining = (CollectionAdBreakPolicy.SKIP_AFTER_SECONDS - elapsed).coerceAtLeast(0)

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> buildBreakAdView(ctx) },
            update = { root -> bindBreakAd(root, ad) }
        )

        Text(
            "Ad · Part $nextPartNumber next",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 56.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )

        Text(
            if (remaining > 0) "Skip in $remaining" else "Skip ⏭",
            color = Color.White.copy(alpha = if (remaining > 0) 0.7f else 1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // Clear of the ad's own call-to-action at the bottom of the card.
                .padding(end = 16.dp, bottom = 170.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.6f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                .clickable(enabled = remaining == 0) { finish() }
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

/** Full-bleed creative with the ad's details along the bottom. Same rules as
 *  NativeAdCard: registered asset views, MediaView ≥ 120dp, setNativeAd last. */
private fun buildBreakAdView(ctx: Context): NativeAdView {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    val root = NativeAdView(ctx).apply { setBackgroundColor(AndroidColor.BLACK) }
    val frame = FrameLayout(ctx)

    val media = MediaView(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setImageScaleType(ImageView.ScaleType.CENTER_CROP)
        tag = "media"
    }

    val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(24), dp(20), dp(40))
        background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(AndroidColor.parseColor("#E6000000"), AndroidColor.TRANSPARENT)
        )
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        )
    }

    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val icon = ImageView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) }
        tag = "icon"
    }
    val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val label = TextView(ctx).apply {
        text = "SPONSORED"          // Always labelled. Policy, and honesty.
        setTextColor(AndroidColor.parseColor("#B3FFFFFF"))
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
    }
    val headline = TextView(ctx).apply {
        setTextColor(AndroidColor.WHITE); textSize = 16f
        typeface = Typeface.DEFAULT_BOLD; maxLines = 2; tag = "headline"
    }
    val advertiser = TextView(ctx).apply {
        setTextColor(AndroidColor.parseColor("#B3FFFFFF")); textSize = 12f; tag = "advertiser"
    }
    texts.addView(label); texts.addView(headline); texts.addView(advertiser)
    row.addView(icon); row.addView(texts)

    val body = TextView(ctx).apply {
        setTextColor(AndroidColor.parseColor("#CCFFFFFF")); textSize = 13f; maxLines = 2
        setPadding(0, dp(8), 0, 0)
        tag = "body"
    }
    val cta = Button(ctx).apply {
        setTextColor(AndroidColor.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(AndroidColor.parseColor("#E91E63"))
        }
        textSize = 14f
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(14) }
        tag = "cta"
    }
    panel.addView(row); panel.addView(body); panel.addView(cta)

    frame.addView(media)
    frame.addView(panel)
    root.addView(frame, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
    return root
}

private fun bindBreakAd(root: NativeAdView, ad: NativeAd) {
    fun view(tag: String): View? = root.findViewWithTag(tag)

    (view("headline") as? TextView)?.text = ad.headline
    root.headlineView = view("headline")

    // Registered ONLY when present — an empty registered asset reads to the
    // validator as a missing required element.
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

    // LAST, after every registration.
    root.setNativeAd(ad)
}
