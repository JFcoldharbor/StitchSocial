package com.stitchsocial.club.ads

import android.app.Activity
import android.content.Context
import androidx.annotation.MainThread
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.stitchsocial.club.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts the Mobile Ads SDK, after consent (Android parity with iOS
 * AdMobBootstrap).
 *
 * ORDER MATTERS AND IS THE WHOLE POINT. The UMP consent form must be resolved
 * BEFORE MobileAds.initialize, or the first ad requests go out without a consent
 * signal — which in the EEA/UK is a policy violation and, practically, gets those
 * requests filled at a much lower rate or not at all.
 *
 * iOS hit the mirror-image bug: the SDK start was gated behind the ATT prompt,
 * which fired late, so ads never loaded at all. The lesson both ways is that
 * consent and SDK start have to be sequenced deliberately, not left to whichever
 * screen happens to appear first.
 */
object AdMobBootstrap {

    private val started = AtomicBoolean(false)

    /** True once MobileAds.initialize has actually run. */
    val isStarted: Boolean get() = started.get()

    /**
     * Resolve consent, then start the SDK. Safe to call more than once; only the
     * first call does work.
     *
     * @param activity needed because a consent form is a dialog. Called from the
     *   host Activity rather than Application for exactly that reason.
     */
    @MainThread
    fun start(activity: Activity) {
        if (started.get()) return

        val params = ConsentRequestParameters.Builder()
            .apply {
                if (BuildConfig.DEBUG) {
                    // Without this a debug build outside the EEA never sees the
                    // form, so the consent path ships untested.
                    setConsentDebugSettings(
                        ConsentDebugSettings.Builder(activity)
                            .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                            .build()
                    )
                }
            }
            .build()

        val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
        consentInfo.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    // Start regardless of the form's outcome. A user who
                    // declines still gets ads — contextual, non-personalised —
                    // and the SDK handles that distinction itself. Refusing to
                    // start would just mean no ads and no revenue for a choice
                    // the user is entitled to make.
                    initialize(activity)
                }
            },
            {
                // Consent lookup failed (offline, etc). Start anyway: the SDK
                // defaults to non-personalised without a consent signal, which
                // is the safe side of the policy line.
                initialize(activity)
            }
        )
    }

    private fun initialize(context: Context) {
        if (!started.compareAndSet(false, true)) return
        MobileAds.initialize(context.applicationContext) { }
    }

    /**
     * Whether a consent form is available to re-present. Play policy requires a
     * way for users to CHANGE their choice, which is a settings entry, not a
     * one-time prompt.
     */
    fun isPrivacyOptionsRequired(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context)
            .privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /** Re-present the consent form from a settings row. */
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { onDone() }
    }
}
