/*
 * LoginView.kt - FULL iOS PARITY
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Matches LoginView.swift exactly:
 *   - Personal / Business account type picker
 *   - Business: brandName, foundedYear, websiteURL, AdCategory picker
 *   - Personal: username only — the display name defaults to the handle
 *   - Referral code (optional, auto-uppercase)
 *   - Terms + Safety + Privacy acceptance checkbox (required)
 *   - Pre-signup age gate (COPPA) — birthdate before the account exists
 *   - saveTermsAcceptance — single merge write post-auth
 *   - processReferralSignup / processOrganicSignup
 *   - No authService state observers (fixes race condition)
 *
 * CACHING: acceptedTermsVersion — add to CachingOptimization if force
 *   re-acceptance is needed; cache version in SharedPreferences to avoid
 *   Firestore read on cold launch.
 */

package com.stitchsocial.club

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.stitchsocial.club.community.CommunityService
import com.stitchsocial.club.foundation.AccountType
import com.stitchsocial.club.foundation.PendingReferral
import com.stitchsocial.club.foundation.ReservedUsernames
import com.stitchsocial.club.foundation.StitchError
import com.stitchsocial.club.services.AdCategory
import com.stitchsocial.club.services.AuthService
import com.stitchsocial.club.services.PasswordPolicy
import com.stitchsocial.club.services.PasswordStrength
import com.stitchsocial.club.services.PrivateProfileService
import com.stitchsocial.club.services.SignupBirthdate
import com.stitchsocial.club.services.SubscriptionService
import com.stitchsocial.club.services.UsernameService
import com.stitchsocial.club.services.ReferralService
import com.stitchsocial.club.ui.theme.StitchColors
import com.stitchsocial.club.ui.theme.screenPadding
import com.stitchsocial.club.views.AGE_GATE_BLOCKED_PREF
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.services.SocialAuth

private const val TERMS_VERSION = "1.0"
private const val TERMS_URL = "https://stitchsocial.me/terms"
private const val SAFETY_URL = "https://stitchsocial.me/terms#aup"
private const val PRIVACY_URL = "https://stitchsocial.me/privacy"

/**
 * Set in "stitch_prefs" at the moment an email signup succeeds; MainScreen
 * consumes it once the main app is up and opens on Profile. Same key as iOS.
 */
const val LAND_ON_PROFILE_AFTER_SIGNUP = "stitch_land_on_profile_after_signup"

/** iOS StitchColors.border — the primary button's not-ready fill. */
private val ButtonNotReady = Color(0xFF333340)

/** SwiftUI `.cyan` in dark mode — the welcome screen's "Log in". */
private val LogInCyan = Color(0xFF64D2FF)

// MARK: - AuthMode

enum class AuthMode(val title: String, val subtitle: String, val buttonText: String) {
    SIGN_IN("Welcome Back", "Sign in to continue your creative journey", "Sign In"),
    SIGN_UP("Join Stitch Social", "Create your conversation account", "Create Account")
}

// MARK: - FloatingParticle

@Composable
fun FloatingParticle(index: Int, screenWidth: Float, screenHeight: Float) {
    val t = rememberInfiniteTransition(label = "p$index")
    val ix = remember { Random.nextFloat() * screenWidth }
    val iy = remember { Random.nextFloat() * screenHeight }
    val dur = remember { Random.nextInt(3000, 6000) }
    val sz = remember { Random.nextFloat() * 6f + 2f }
    val ox by t.animateFloat(ix, ix + Random.nextFloat() * 100f - 50f,
        infiniteRepeatable(tween(dur, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ox$index")
    val oy by t.animateFloat(iy, iy + Random.nextFloat() * 100f - 50f,
        infiniteRepeatable(tween(dur, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "oy$index")
    val al by t.animateFloat(0.1f, 0.3f,
        infiniteRepeatable(tween(dur / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "al$index")
    Box(Modifier.offset(ox.dp, oy.dp).size(sz.dp).clip(CircleShape).background(StitchColors.primary.copy(alpha = al)))
}

// MARK: - LoginView

@Composable
fun LoginView(
    authService: AuthService,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = lifecycleOwner.lifecycleScope
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val prefs = remember { context.getSharedPreferences("stitch_prefs", Context.MODE_PRIVATE) }

    // Form
    // Welcome landing gates the form, mirroring iOS `showWelcome`.
    var showWelcome by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var usernameState by remember { mutableStateOf(UsernameService.Availability.IDLE) }
    // Still here for the social paths, which arrive with a name from Apple or
    // Google. Email signup no longer asks for one.
    var displayName by remember { mutableStateOf("") }
    var pwVisible by remember { mutableStateOf(false) }
    var cpwVisible by remember { mutableStateOf(false) }

    // Account type + business
    var accountType by remember { mutableStateOf(AccountType.PERSONAL) }
    var brandName by remember { mutableStateOf("") }
    var foundedYear by remember { mutableStateOf("") }
    var websiteURL by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(AdCategory.OTHER) }
    var referralCode by remember { mutableStateOf("") }
    // True when the code was prefilled from a tapped referral link rather
    // than typed. Only affects the sourceType the signup is attributed to —
    // a referrer should be able to tell a working link from a copied code.
    var referralCameFromLink by remember { mutableStateOf(false) }

    // Terms
    var acceptedTerms by remember { mutableStateOf(false) }

    // UI
    var isLoading by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
    var animated by remember { mutableStateOf(false) }
    // The button is never disabled for an incomplete form — it says what is
    // missing instead (iOS: a disabled submit was rejected by App Review as a
    // dead button). Each of these is one alert, shown while non-null.
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var authErrorMessage by remember { mutableStateOf<String?>(null) }
    var forgotPasswordMessage by remember { mutableStateOf<String?>(null) }

    // Pre-signup age gate (COPPA): birthdate is collected BEFORE the account
    // exists; under-13 is blocked outright (no account created).
    var birthDate by remember { mutableStateOf<Date?>(null) }
    var showingAgeGate by remember { mutableStateOf(false) }
    var showUnder13Alert by remember { mutableStateOf(false) }

    // Optional by design — a business shouldn't be stopped from signing up
    // because it can't put a year on when it started. A typed year that isn't
    // plausible is said in the validation alert, as on iOS.
    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val parsedFoundedYear = foundedYear.trim()
        .takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in 1800..thisYear }

    // Focus
    val fEmail = remember { FocusRequester() }
    val fUser = remember { FocusRequester() }
    val fBrand = remember { FocusRequester() }
    val fPw = remember { FocusRequester() }
    val fCpw = remember { FocusRequester() }

    LaunchedEffect(Unit) { delay(100); animated = true }
    // iOS fades the screen in over a second rather than switching it on.
    val appear by animateFloatAsState(if (animated) 1f else 0f, tween(800), label = "appear")

    // Someone who arrived from a referral link shouldn't have to find the code
    // and type it back in — it was already in the URL they tapped. Never
    // overwrite something they typed themselves.
    //
    // Sources, in order (INVITE_CONTRACT): an invite link the app was opened
    // with (MainActivity → PendingReferral.capture), then the Play Install
    // Referrer's `invite=CODE` (LandingReferrer → captureIfAbsent). The
    // referrer read is usually still in flight on a first launch, so wait for
    // it briefly. The install referrer is Android's half of the handoff iOS
    // does through the clipboard (InviteClipboard).
    LaunchedEffect(mode) {
        if (referralCode.isEmpty() && PendingReferral.code(context) == null) {
            com.stitchsocial.club.landing.LandingReferrer.awaitRead()
        }
        if (referralCode.isEmpty()) {
            PendingReferral.code(context)?.let {
                referralCode = it
                referralCameFromLink = true
            }
        }
    }

    // Debounced availability. A handle is typed one letter at a time and each
    // check is a read; 400ms is long enough to stop paying for every keystroke
    // and short enough that the answer arrives while you are still looking.
    LaunchedEffect(username, mode, accountType) {
        if (mode != AuthMode.SIGN_UP || accountType != AccountType.PERSONAL || username.isEmpty()) {
            usernameState = UsernameService.Availability.IDLE
            return@LaunchedEffect
        }
        UsernameService.validate(username)?.let {
            usernameState = it
            return@LaunchedEffect
        }
        usernameState = UsernameService.Availability.CHECKING
        delay(400)
        usernameState = UsernameService.availability(username)
    }

    // Same pattern as iOS isValidEmail — a "contains @ and ." check let
    // "a@b." through as valid.
    fun isValidEmail(value: String): Boolean =
        Regex("[A-Z0-9a-z._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,64}").matches(value.trim())

    // Only decides the button's colour now; the button itself stays tappable.
    // Sign-in keeps the old floor: an existing password is whatever it already
    // is, and enforcing the new one there would lock out every account made
    // before today.
    val pwMatch = password == confirmPassword
    val formValid = acceptedTerms && when (mode) {
        AuthMode.SIGN_IN -> isValidEmail(email) && password.length >= 6
        AuthMode.SIGN_UP -> when (accountType) {
            AccountType.BUSINESS -> isValidEmail(email) && brandName.isNotBlank() &&
                    PasswordPolicy.isAcceptable(password) && pwMatch
            else -> isValidEmail(email) && username.isNotEmpty() &&
                    usernameState != UsernameService.Availability.TAKEN &&
                    usernameState != UsernameService.Availability.RESERVED &&
                    PasswordPolicy.isAcceptable(password) && pwMatch
        }
    }

    /**
     * Null when the form can be submitted, otherwise the one sentence that
     * says what to fix. Same checks, same order and same words as iOS
     * firstValidationProblem().
     */
    fun firstValidationProblem(): String? {
        if (!acceptedTerms) {
            return "Please accept the Terms & Conditions and Safety Policy to continue. Tap the checkbox just above this button."
        }
        if (email.isBlank()) return "Please enter your email address."
        if (password.isEmpty()) return "Please enter your password."
        if (mode == AuthMode.SIGN_UP) {
            val personal = accountType == AccountType.PERSONAL
            if (personal && username.isBlank()) return "Please enter a username."
            if (personal && ReservedUsernames.isReserved(username)) {
                return "“${username.trim()}” is a reserved username. Please choose another."
            }
            if (!personal && brandName.isBlank()) return "Please enter your brand name."
            if (!personal && ReservedUsernames.isReserved(brandName)) {
                return "“${brandName.trim()}” is a reserved name. Please choose another."
            }
            if (!personal && foundedYear.isNotBlank() && parsedFoundedYear == null) {
                return "Year founded should be a four-digit year between 1800 and $thisYear."
            }
            if (!PasswordPolicy.meetsLength(password)) {
                return "Password must be at least ${PasswordPolicy.MIN_LENGTH} characters."
            }
            if (!PasswordPolicy.meetsSymbols(password)) {
                return "Password needs at least ${PasswordPolicy.REQUIRED_SYMBOLS} symbols, like ! ? # or $."
            }
            if (personal && usernameState == UsernameService.Availability.TAKEN) {
                return "“${UsernameService.normalise(username)}” is taken. Please choose another."
            }
            if (!pwMatch) return "Passwords don't match."
        }
        return null
    }

    /**
     * The most common Firebase auth failures in one plain sentence each — iOS
     * friendlyAuthErrorMessage(), matched by error code rather than by
     * searching the message text. Anything else falls through to its own
     * message, which is usable, just longer.
     */
    fun friendlyAuthErrorMessage(e: Exception): String {
        when (e) {
            is FirebaseNetworkException -> return "Network error — check your connection and try again."
            is FirebaseTooManyRequestsException -> return "Too many attempts. Wait a moment and try again."
            is FirebaseAuthException -> when (e.errorCode) {
                "ERROR_WRONG_PASSWORD" ->
                    return "Wrong password. Tap “Forgot Password?” if you need a reset link."
                "ERROR_USER_NOT_FOUND" ->
                    return "We couldn’t find an account for that email. Switch to Sign Up to create one."
                "ERROR_INVALID_EMAIL" -> return "That email address isn’t formatted correctly."
                "ERROR_EMAIL_ALREADY_IN_USE" ->
                    return "An account already exists for that email. Switch to Sign In instead."
                "ERROR_WEAK_PASSWORD" -> return "Password must be at least 6 characters."
                "ERROR_USER_DISABLED" -> return "This account has been disabled. Contact support@stitchsocial.me."
                "ERROR_TOO_MANY_REQUESTS" -> return "Too many attempts. Wait a moment and try again."
                "ERROR_INVALID_CREDENTIAL" -> return "Invalid email or password."
            }
            // AuthService's own checks ("Validation Error: …") — the prefix is
            // for logs, not for people.
            is StitchError.ValidationError -> {
                val text = e.message.removePrefix("Validation Error: ")
                return if (text == "Invalid email format") "That email address isn’t formatted correctly."
                       else text.trimEnd('.') + "."
            }
        }
        return e.message ?: "Something went wrong. Please try again."
    }

    /**
     * Persist the pre-signup birthdate for the freshly created account. The
     * date itself goes to the owner-only users/{uid}/private/profile; the
     * public doc gets only the derived ageGroup. The server onUserBirthdateSet
     * function derives the canonical ageGroup/audienceLane from it.
     */
    suspend fun writeBirthdate(uid: String, dob: Date) {
        val group = if (ageOn(dob) >= 18) "adult" else "teen"
        try { PrivateProfileService.writeBirthdate(uid, dob) } catch (e: Exception) { println("⚠️ BIRTHDATE: ${e.message}") }
        try {
            FirebaseFirestore.getInstance("stitchfin").collection("users").document(uid)
                .set(mapOf("privacySettings" to mapOf(
                    "ageGroup" to group,
                    "ageVerifiedAt" to Timestamp(Date())
                )), SetOptions.merge()).await()
        } catch (e: Exception) { println("⚠️ BIRTHDATE: ${e.message}") }
        try { FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await() } catch (_: Exception) { }
    }

    /**
     * acceptedTermsAt + version in one merge write — iOS saveTermsAcceptance(),
     * which runs after every email sign-in as well as every sign-up.
     */
    suspend fun saveTermsAcceptance(uid: String) {
        try {
            FirebaseFirestore.getInstance("stitchfin").collection("users").document(uid)
                .set(mapOf(
                    "acceptedTermsAt" to FieldValue.serverTimestamp(),
                    "acceptedTermsVersion" to TERMS_VERSION,
                    "acceptedSafetyPolicy" to true,
                    "acceptedPrivacyPolicy" to true
                ), SetOptions.merge()).await()
        } catch (e: Exception) { println("⚠️ TERMS: ${e.message}") }
    }

    /**
     * The referral step every NEW account goes through, whichever way it signed
     * up: completeReferral with the code (→ auto-follow, first-post boost, and
     * the one-time invite Welcome), or the organic record when there is none.
     * Returning users never reach this.
     */
    suspend fun redeemReferral(userId: String, code: String, fromLink: Boolean) {
        try {
            val rs = ReferralService()
            if (code.isNotEmpty()) {
                val ref = rs.processReferralSignup(
                    code, userId, "android",
                    if (fromLink) "link" else "manual"
                )
                // Redeemed — don't prefill it into the next signup
                // on this device.
                if (ref.success) {
                    PendingReferral.clear(context)
                    // The one-time invite Welcome, shown once
                    // this account is past the age gate.
                    com.stitchsocial.club.services.InviteWelcomeStore.save(
                        context,
                        com.stitchsocial.club.services.InviteWelcomeStore.Pending(
                            uid = userId, code = code,
                            referrerID = ref.referrerID,
                            followed = ref.followed,
                            boostExpiresAt = ref.boostExpiresAt,
                            boostHours = ref.boostHours
                        )
                    )
                }
                if (BuildConfig.DEBUG) { println(if (ref.success) "🎉 REFERRAL: Redeemed by ${ref.referrerID}" else "⚠️ REFERRAL: ${ref.error}") }
            } else {
                rs.processOrganicSignup(newUserID = userId, platform = "android")
            }
        } catch (e: Exception) { println("⚠️ REFERRAL: ${e.message}") }
    }

    /**
     * Social sign-in that CREATED an account: same referral step as email
     * signup. The invite code is whatever the form holds, else the pending one
     * (link / install referrer) — the Welcome-first flow never shows the field.
     */
    suspend fun redeemReferralForSocial(userId: String) {
        val typed = referralCode.trim().uppercase()
        if (typed.isEmpty() && PendingReferral.code(context) == null) {
            com.stitchsocial.club.landing.LandingReferrer.awaitRead()
        }
        val code = typed.ifEmpty { PendingReferral.code(context).orEmpty() }
        val fromLink = if (typed.isNotEmpty()) referralCameFromLink else code.isNotEmpty()
        redeemReferral(userId, code, fromLink)
    }

    /**
     * Google. The WEB client id comes out of google-services.json (client_type 3)
     * — Firebase rejects an id token minted for the Android client.
     */
    fun doGoogle() {
        isLoading = true
        scope.launch {
            try {
                val social = SocialAuth.signInWithGoogle(context, GOOGLE_WEB_CLIENT_ID)
                val result = authService.signInWithCredential(social.credential, social.displayName)
                // New accounts only — a returning user's referral is long settled.
                if (result.isNewUser) redeemReferralForSocial(result.userId)
                onLoginSuccess()
            } catch (e: SocialAuth.SocialAuthCancelled) {
                // Deliberate back-out. Saying anything here reads as a bug.
            } catch (e: Exception) {
                authErrorMessage = e.message ?: "Google sign-in failed. Please try again."
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Apple, via Firebase's OAuth web flow — Android has no native equivalent.
     * A failure is surfaced rather than swallowed: this needs a Services ID
     * configured in the Firebase console, and a silent no-op button is the exact
     * thing that got the iOS build rejected.
     */
    fun doApple() {
        val activity = context as? android.app.Activity ?: run {
            authErrorMessage = "Couldn't open Apple sign-in."
            return
        }
        isLoading = true
        scope.launch {
            try {
                val social = SocialAuth.signInWithApple(activity)
                val result = authService.signInWithCredential(social.credential, social.displayName)
                // New accounts only — a returning user's referral is long settled.
                if (result.isNewUser) redeemReferralForSocial(result.userId)
                onLoginSuccess()
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (!msg.contains("canceled", true) && !msg.contains("cancelled", true)) {
                    authErrorMessage = "Apple sign-in failed. $msg".trim()
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun doAuth() {
        keyboard?.hide(); focusManager.clearFocus()

        // Validate up front and say what's missing — the button is never a
        // dead tap.
        firstValidationProblem()?.let {
            validationMessage = it
            return
        }

        // PRE-SIGNUP AGE GATE — collect a neutral birthdate before the account
        // is created. Under-13 is blocked at the gate (no account made).
        if (mode == AuthMode.SIGN_UP && birthDate == null) {
            if (prefs.getBoolean(AGE_GATE_BLOCKED_PREF, false)) {
                showUnder13Alert = true          // this device was already blocked
                return
            }
            showingAgeGate = true
            return
        }

        val signingUp = mode == AuthMode.SIGN_UP
        val business = accountType == AccountType.BUSINESS
        val resolvedUsername = if (business) brandName else username.trim().lowercase()
        // Falls back to the handle rather than asking for a second name nobody
        // had a different answer for.
        val resolvedDN = if (business) brandName else displayName.trim().ifBlank { resolvedUsername }
        val typedEmail = email.trim()

        isLoading = true
        scope.launch {
            try {
                val uid: String
                if (!signingUp) {
                    uid = authService.signIn(typedEmail, password).userId
                } else {
                    // Held from before the account exists until its birthdate
                    // is on file, so the launch gate doesn't ask again.
                    SignupBirthdate.begin()
                    try {
                        val r = authService.signUp(
                            email = typedEmail, password = password,
                            displayName = resolvedDN, username = resolvedUsername,
                            accountType = accountType,
                            brandName = if (business) brandName else null,
                            websiteURL = if (business && websiteURL.isNotBlank()) websiteURL else null,
                            businessCategory = if (business) category else null,
                            foundedYear = if (business) parsedFoundedYear else null
                        )
                        uid = r.userId
                        // The handle is claimed by the server —
                        // reserveUsernameOnUserCreate fires on the user document
                        // and writes usernames/{handle} itself, so there is no
                        // window where an account exists without owning its name.

                        // Land this session on Profile. Set HERE, at the one
                        // moment an account is genuinely created. MainScreen
                        // consumes it once. See LAND_ON_PROFILE_AFTER_SIGNUP.
                        prefs.edit().putBoolean(LAND_ON_PROFILE_AFTER_SIGNUP, true).apply()

                        // Persist the pre-signup birthdate now the account exists.
                        birthDate?.let { writeBirthdate(uid, it) }
                    } finally {
                        SignupBirthdate.end()
                    }
                }

                showSuccess = true

                // Post-login setup — iOS runs all of this after sign-in as
                // well as sign-up.
                SubscriptionService.shared.setCurrentUserEmail(typedEmail)
                saveTermsAcceptance(uid)

                // Process referral — mirrors iOS processReferralSignup / processOrganicSignup
                if (signingUp) redeemReferral(uid, referralCode.trim().uppercase(), referralCameFromLink)

                // Auto-join Stitch Social official community. Sign-in reads the
                // name off the user doc; sign-up already has it.
                val (joinName, joinDN) = if (signingUp) resolvedUsername to resolvedDN else try {
                    val doc = FirebaseFirestore.getInstance("stitchfin").collection("users").document(uid).get().await()
                    (doc.getString("username") ?: "") to (doc.getString("displayName") ?: "")
                } catch (_: Exception) { "" to "" }
                CommunityService.shared.autoJoinOfficialCommunity(uid, joinName, joinDN)

                delay(2000); showSuccess = false; onLoginSuccess()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("Authentication error: $e") }
                // ALWAYS surface the failure — a vanishing spinner is what got
                // the iOS build rejected.
                authErrorMessage = friendlyAuthErrorMessage(e)
            } finally { isLoading = false }
        }
    }

    // iOS handleForgotPassword: the email already typed above is where the
    // link goes; there is no second field to fill in.
    fun handleForgotPassword() {
        if (!isValidEmail(email)) {
            forgotPasswordMessage = "Please enter a valid email address above, then tap Forgot Password."
            return
        }
        val target = email.trim()
        scope.launch {
            forgotPasswordMessage = try {
                authService.resetPassword(target)
                "A password reset link has been sent to $target. Check your inbox."
            } catch (e: Exception) {
                "Failed to send reset email: ${e.message}"
            }
        }
    }

    // iOS clearForm(): the same fields and no others. The referral code
    // survives a mode switch, so a prefilled invite isn't lost by tapping
    // Sign In and back.
    fun switchMode(m: AuthMode) {
        mode = m; email = ""; password = ""; confirmPassword = ""
        username = ""; displayName = ""; acceptedTerms = false
        focusManager.clearFocus()
    }

    // System back on the form returns to the welcome landing, as the chevron
    // does; on the landing itself it leaves the app as usual.
    BackHandler(enabled = !showWelcome && !showingAgeGate && !isLoading) { showWelcome = true }

    Box(modifier = modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(Color.Black, Color(0xFF0A0A1A), Color.Black))
    ).pointerInput(Unit) {
        // Dismiss the keyboard when tapping outside a field, as iOS does.
        detectTapGestures(onTap = { focusManager.clearFocus() })
    }) {
        repeat(20) { i -> FloatingParticle(i, 400f, 800f) }

        // WELCOME FIRST (iOS parity). The form used to be the landing screen —
        // an email/password wall as the very first thing anyone saw, while iOS
        // opened on the brand with Continue with Apple/Google and pushed email to
        // a second step. Same order here now.
        if (showWelcome) {
            WelcomeLanding(
                appear = appear,
                isLoading = isLoading,
                onApple = { doApple() },
                onGoogle = { doGoogle() },
                onEmailSignUp = {
                    mode = AuthMode.SIGN_UP
                    showWelcome = false
                },
                onLogIn = {
                    mode = AuthMode.SIGN_IN
                    showWelcome = false
                },
            )
        } else {

        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Back to the welcome landing. Without it the email form is a
            // one-way door — you'd have to kill the app to reach the social
            // buttons again. iOS has the same affordance: a bare chevron.
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = maxOf(20.dp, topInset))) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !isLoading) { showWelcome = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        "Back",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            // Logo
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.scale(0.8f + 0.2f * appear).alpha(appear)) {
                Image(painterResource(R.drawable.stitchsociallogo), "Stitch Social",
                    Modifier.size(120.dp).clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Fit)
                Spacer(Modifier.height(16.dp))
                Text("Stitch Social", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            // Welcome
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 24.dp).screenPadding().alpha(appear)) {
                Text(mode.title, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, textAlign = TextAlign.Center)
                Text(mode.subtitle, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = StitchColors.textSecondary, textAlign = TextAlign.Center)
            }

            // Form card — one margin outside (16) and the same inside, as iOS.
            Card(Modifier.fillMaxWidth().screenPadding().alpha(appear),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, StitchColors.glassBorder)
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {

                    // Email
                    LTextField(email, { email = it.trim() }, "Email", "Enter your email",
                        KeyboardType.Email, ImeAction.Next,
                        { if (mode == AuthMode.SIGN_UP) fUser.requestFocus() else fPw.requestFocus() }, fEmail)

                    if (mode == AuthMode.SIGN_UP) {
                        // Account Type Picker
                        Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Account Type", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(0.8f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AccTypeBtn(Icons.Default.Person, "Personal",
                                    accountType == AccountType.PERSONAL, Modifier.weight(1f)) {
                                    accountType = AccountType.PERSONAL
                                }
                                AccTypeBtn(Icons.Default.Business, "Business",
                                    accountType == AccountType.BUSINESS, Modifier.weight(1f)) {
                                    accountType = AccountType.BUSINESS
                                }
                            }
                        }

                        // Business fields
                        if (accountType == AccountType.BUSINESS) {
                            LTextField(brandName, { brandName = it },
                                "Brand Name", "Your company or brand name",
                                KeyboardType.Text, ImeAction.Next, { fPw.requestFocus() }, fBrand)

                            // The place a founding year belongs. It had no home
                            // before, which is how it ended up in the age gate's
                            // birthday picker.
                            LTextField(foundedYear, { foundedYear = it.filter(Char::isDigit).take(4) },
                                "Year Founded (optional)", thisYear.toString(),
                                KeyboardType.Number, ImeAction.Next, { fPw.requestFocus() })

                            LTextField(websiteURL, { websiteURL = it },
                                "Website (optional)", "https://yourbrand.com",
                                KeyboardType.Uri, ImeAction.Next, { fPw.requestFocus() })

                            Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Business Category", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(0.8f))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(AdCategory.values().toList()) { cat ->
                                        val sel = category == cat
                                        Surface(
                                            onClick = { category = cat },
                                            shape = RoundedCornerShape(20.dp),
                                            color = if (sel) Color.White else Color.White.copy(0.1f)
                                        ) {
                                            Text("${cat.icon} ${cat.displayName}", fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (sel) Color.Black else Color.White.copy(0.7f),
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // Personal fields
                        if (accountType == AccountType.PERSONAL) {
                            LTextField(username,
                                { username = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' }.lowercase() },
                                "Username", "yourhandle",
                                KeyboardType.Text, ImeAction.Next, { fPw.requestFocus() }, fUser)

                            UsernameStatusLine(username, usernameState)

                            // The display-name field is gone, as on iOS. Two
                            // adjacent fields asked for "a username" and "your
                            // display name" with nothing saying which one people
                            // see, and for a new account the answer is almost
                            // always the same word twice. The handle becomes the
                            // name; Edit Profile is where you choose another.
                        }
                    }

                    // Password
                    LTextField(password, { password = it }, "Password", "Enter your password",
                        KeyboardType.Password,
                        if (mode == AuthMode.SIGN_UP) ImeAction.Next else ImeAction.Done,
                        { if (mode == AuthMode.SIGN_UP) fCpw.requestFocus() else doAuth() }, fPw,
                        isPassword = true, pwVisible, { pwVisible = !pwVisible })

                    // Forgot password
                    if (mode == AuthMode.SIGN_IN) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text("Forgot Password?", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = StitchColors.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { handleForgotPassword() }
                                    .padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }

                    // Password requirements
                    if (mode == AuthMode.SIGN_UP && password.isNotEmpty()) {
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PasswordRequirementRow(
                                "At least ${PasswordPolicy.MIN_LENGTH} characters",
                                PasswordPolicy.meetsLength(password)
                            )
                            PasswordRequirementRow(
                                "At least ${PasswordPolicy.REQUIRED_SYMBOLS} symbols (! ? # $ …)",
                                PasswordPolicy.meetsSymbols(password)
                            )
                            PasswordStrengthBar(password)
                        }
                    }

                    // Confirm password + referral
                    if (mode == AuthMode.SIGN_UP) {
                        LTextField(confirmPassword, { confirmPassword = it },
                            "Confirm Password", "Confirm your password",
                            KeyboardType.Password, ImeAction.Done, { doAuth() }, fCpw,
                            isPassword = true, cpwVisible, { cpwVisible = !cpwVisible })

                        if (confirmPassword.isNotEmpty() && !pwMatch) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = StitchColors.error, modifier = Modifier.size(16.dp))
                                Text("Passwords do not match", fontSize = 12.sp, color = StitchColors.error)
                            }
                        }

                        // Referral code
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Referral Code (optional)", fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, color = Color.White.copy(0.8f))
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .background(Color.White.copy(0.08f), RoundedCornerShape(12.dp))
                                    .border(1.dp,
                                        if (referralCode.isEmpty()) Color.Transparent else Color(0xFF9C27B0).copy(0.3f),
                                        RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ConfirmationNumber,
                                    contentDescription = null,
                                    tint = Color(0xFF9C27B0).copy(0.6f),
                                    modifier = Modifier.size(16.dp))
                                Box(Modifier.weight(1f)) {
                                    if (referralCode.isEmpty()) Text("Enter referral code", fontSize = 15.sp, color = Color.Gray)
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = referralCode,
                                        onValueChange = { referralCode = it.uppercase().filter { c -> c.isLetterOrDigit() } },
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Color.White),
                                        singleLine = true,
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(StitchColors.primary),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { doAuth() }),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            Text("Got a code from a creator? Enter it to connect.", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            }

            // Terms — always shown, mirrors iOS isFormValid requiring acceptedTerms
            Column(Modifier.fillMaxWidth().screenPadding().alpha(appear),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().clickable { acceptedTerms = !acceptedTerms },
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        if (acceptedTerms) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (acceptedTerms) StitchColors.primary else StitchColors.textSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    // The three policy names stand out from the sentence, as on iOS.
                    val emphasis = SpanStyle(color = StitchColors.primary, fontWeight = FontWeight.SemiBold)
                    Text(buildAnnotatedString {
                        append("I agree to the ")
                        withStyle(emphasis) { append("Terms & Conditions") }
                        append(", ")
                        withStyle(emphasis) { append("Safety Policy") }
                        append(", and ")
                        withStyle(emphasis) { append("Privacy Policy") }
                        append(", including the zero-tolerance policy for objectionable content and abusive users.")
                    }, fontSize = 13.sp, color = StitchColors.textSecondary, lineHeight = 18.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(
                        Triple("Terms", Icons.Default.Description, TERMS_URL),
                        Triple("Safety", Icons.Default.Shield, SAFETY_URL),
                        Triple("Privacy", Icons.Default.Lock, PRIVACY_URL),
                    ).forEach { (label, icon, url) ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        ) {
                            Icon(icon, contentDescription = null, tint = StitchColors.primary, modifier = Modifier.size(12.dp))
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = StitchColors.primary)
                        }
                    }
                }
            }

            // Action button — below the terms, outside the card, as on iOS.
            // Always tappable except mid-request; an incomplete form gets the
            // validation alert. The colour still says whether it's ready.
            Column(Modifier.fillMaxWidth().screenPadding().alpha(appear),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { doAuth() }, enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (formValid) StitchColors.primary else ButtonNotReady,
                        disabledContainerColor = ButtonNotReady),
                    shape = RoundedCornerShape(16.dp)) {
                    if (isLoading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Text(mode.buttonText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
                if (!acceptedTerms) {
                    Text("Please accept the Terms & Conditions to continue",
                        fontSize = 11.sp, color = StitchColors.textSecondary.copy(0.7f),
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }

            // Mode switcher
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 40.dp).alpha(appear)) {
                Text(if (mode == AuthMode.SIGN_IN) "Don't have an account?" else "Already have an account?",
                    fontSize = 14.sp, color = StitchColors.textSecondary)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { switchMode(if (mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN) }) {
                    Text(if (mode == AuthMode.SIGN_IN) "Sign Up" else "Sign In",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = StitchColors.primary)
                }
            }
        }
        }

        // Success overlay
        if (showSuccess) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.8f)), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Box {
                        Image(painterResource(R.drawable.stitchsociallogo), null,
                            Modifier.size(100.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Fit)
                        Box(
                            modifier = Modifier.align(Alignment.BottomEnd).offset(8.dp, 8.dp)
                                .size(32.dp).clip(CircleShape).background(StitchColors.success),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Welcome to Stitch!", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Your account is ready to go", fontSize = 16.sp, color = StitchColors.textSecondary)
                    }
                }
            }
        }

        // Loading overlay — also covers the welcome landing during Apple /
        // Google, as on iOS, so the buttons themselves keep their labels.
        if (isLoading && !showSuccess) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.5f))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center
            ) {
                Card(shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(0.8f)),
                    border = BorderStroke(1.dp, StitchColors.inputBorder)) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = StitchColors.primary,
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 3.dp
                        )
                        Text("Authenticating...", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
            }
        }

        // Pre-signup age gate — full screen over the form, as iOS's
        // fullScreenCover.
        if (showingAgeGate) {
            PreSignupAgeGate(
                isBusiness = accountType == AccountType.BUSINESS,
                onContinue = { dob ->
                    showingAgeGate = false
                    if (ageOn(dob) < 13) {
                        // Block WITHOUT creating an account; flag the device so a
                        // fresh retry can't slip past. No DOB is retained.
                        prefs.edit().putBoolean(AGE_GATE_BLOCKED_PREF, true).apply()
                        showUnder13Alert = true
                    } else {
                        birthDate = dob
                        // Continue straight into account creation now that the
                        // gate passed (birthDate is set, so it won't re-gate).
                        scope.launch { delay(200); doAuth() }
                    }
                },
                onCancel = { showingAgeGate = false }
            )
        }

        // Alerts — titles and wording as iOS.
        validationMessage?.let {
            LoginAlert("Can't sign in yet", it) { validationMessage = null }
        }
        authErrorMessage?.let {
            LoginAlert(if (mode == AuthMode.SIGN_IN) "Sign in failed" else "Sign up failed", it) { authErrorMessage = null }
        }
        forgotPasswordMessage?.let {
            LoginAlert("Reset Password", it) { forgotPasswordMessage = null }
        }
        if (showUnder13Alert) {
            LoginAlert(
                "You must be 13 or older",
                "Thanks for your interest in Stitch. You need to be at least 13 to create an account."
            ) { showUnder13Alert = false }
        }
    }
}

/** Whole years from [birthdate] to today. */
private fun ageOn(birthdate: Date): Int {
    val now = Calendar.getInstance()
    val dob = Calendar.getInstance().apply { time = birthdate }
    var age = now.get(Calendar.YEAR) - dob.get(Calendar.YEAR)
    if (now.get(Calendar.DAY_OF_YEAR) < dob.get(Calendar.DAY_OF_YEAR)) age--
    return age.coerceAtLeast(0)
}

/** One-button alert, styled like the rest of the login screen. */
@Composable
private fun LoginAlert(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        titleContentColor = Color.White,
        textContentColor = StitchColors.textSecondary,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK", color = StitchColors.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

// MARK: - Pre-Signup Age Gate (COPPA)

/**
 * Neutral birthdate gate shown BEFORE account creation — iOS PreSignupAgeGate.
 * Deliberately shows NO live age readout and no minimum-age hint — a
 * computed-age preview just teaches kids to dial the picker until it passes.
 * The caller decides the under-13 outcome (block without creating an account).
 *
 * Email signup used to skip this on Android: the account was created first
 * and the birthday asked afterwards (BirthdayPromptView), so an under-13
 * answer left an account behind. Apple/Google still meet that post-auth
 * prompt — OAuth creates the user before there is anything to ask.
 *
 * The picker is the platform DatePickerDialog, as BirthdayPromptView uses,
 * rather than a copy of the iOS wheel.
 */
@Composable
private fun PreSignupAgeGate(
    /**
     * Business signups reach this gate too, and "When's your birthday?"
     * arriving right after a company name invites the founding year instead —
     * which lands this year's date, i.e. age 0, in a gate that blocks the
     * device on anything under 13. COPPA protects the person operating the
     * account, not the entity; only the wording needs to say whose date this is.
     */
    isBusiness: Boolean,
    onContinue: (Date) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val dateFmt = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }
    var selectedDate by remember {
        mutableStateOf(Calendar.getInstance().apply { add(Calendar.YEAR, -18) }.time)
    }
    var showingConfirm by remember { mutableStateOf(false) }

    BackHandler { onCancel() }

    fun showPicker() {
        val cal = Calendar.getInstance().apply { time = selectedDate }
        val dialog = DatePickerDialog(
            context,
            { _, year, month, day ->
                selectedDate = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.time
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.datePicker.minDate = Calendar.getInstance().apply { add(Calendar.YEAR, -120) }.timeInMillis
        dialog.show()
    }

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp)) {
                Text("Cancel", fontSize = 16.sp, color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onCancel() }
                        .padding(vertical = 6.dp))
            }

            Spacer(Modifier.weight(1f))

            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null,
                    tint = Color.Cyan, modifier = Modifier.size(40.dp))
                Text(
                    if (isBusiness) "The owner's birthday" else "When's your birthday?",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    textAlign = TextAlign.Center
                )
                Text(
                    if (isBusiness)
                        "Your own date of birth — not the date the business started. " +
                        "We use it once to set up the account and never show it on the profile."
                    else
                        "We use this once to set up your account and never show it on your profile.",
                    fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }

            // Tap-to-pick row. No computed-age readout and no mention of a
            // minimum age — the gate must stay neutral (COPPA).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .screenPadding()
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showPicker() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(dateFmt.format(selectedDate), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, modifier = Modifier.weight(1f))
                Text("Edit", fontSize = 13.sp, color = Color.Cyan, fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = { showingConfirm = true },
                modifier = Modifier.fillMaxWidth().screenPadding().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Continue", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.weight(1f))

            Text(
                if (isBusiness)
                    "By continuing you confirm this is the real date of birth of the person managing this account."
                else
                    "By continuing you confirm this is your real date of birth.",
                fontSize = 11.sp, color = Color.Gray.copy(alpha = 0.6f), textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 16.dp)
            )
        }

        // An under-13 answer blocks the device permanently, so the picker's
        // default of "18 years ago today" must never be submitted by accident
        // and a mistyped year must be recoverable. The confirmation fires for
        // EVERY date, not just young ones: asking only below the threshold
        // would tell the caller exactly where the threshold is.
        if (showingConfirm) {
            AlertDialog(
                onDismissRequest = { showingConfirm = false },
                title = { Text("Is this right?") },
                text = {
                    Text(
                        if (isBusiness)
                            "You entered ${dateFmt.format(selectedDate)} as the owner's date " +
                            "of birth. You can't change this later, so check the year."
                        else
                            "You entered ${dateFmt.format(selectedDate)}. " +
                            "You can't change this later, so check the year."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showingConfirm = false
                        onContinue(selectedDate)
                    }) { Text(if (isBusiness) "Yes, that's correct" else "Yes, that's my birthday") }
                },
                dismissButton = {
                    TextButton(onClick = { showingConfirm = false }) { Text("Go back") }
                }
            )
        }
    }
}

// MARK: - Account Type Button

@Composable
private fun AccTypeBtn(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color.White else Color.White.copy(0.08f),
        border = BorderStroke(1.dp, if (selected) Color.White else Color.White.copy(0.2f)),
        modifier = modifier.height(70.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) Color.Black else Color.White.copy(0.7f), modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (selected) Color.Black else Color.White.copy(0.7f))
        }
    }
}

// MARK: - LoginTextField (public — used by other views)

@Composable
fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onPasswordVisibilityToggle: (() -> Unit)? = null
) = LTextField(value, onValueChange, label, placeholder, keyboardType, imeAction, onImeAction,
    focusRequester, isPassword, passwordVisible, onPasswordVisibilityToggle)

@Composable
private fun LTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onPasswordToggle: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = StitchColors.textSecondary)
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = StitchColors.placeholder) },
            visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (isPassword) {
                { IconButton(onClick = { onPasswordToggle?.invoke() }) {
                    Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null, tint = StitchColors.textSecondary)
                }}
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = StitchColors.primary, unfocusedBorderColor = StitchColors.glassBorder,
                // iOS StitchTextFieldStyle: black at 60% under a white 40% edge.
                focusedContainerColor = Color.Black.copy(alpha = 0.6f), unfocusedContainerColor = Color.Black.copy(alpha = 0.6f),
                cursorColor = StitchColors.primary)
        )
    }
}

// MARK: - PasswordRequirementRow (public)

@Composable
fun PasswordRequirementRow(text: String, isMet: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (isMet) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isMet) StitchColors.success else StitchColors.textSecondary,
            modifier = Modifier.size(16.dp)
        )
        Text(text, fontSize = 12.sp, color = if (isMet) StitchColors.success else StitchColors.textSecondary)
    }
}
/**
 * What the handle field says about itself while you type — iOS parity.
 */
@Composable
fun UsernameStatusLine(username: String, state: UsernameService.Availability) {
    val handle = UsernameService.normalise(username)
    val (text, color) = when (state) {
        UsernameService.Availability.IDLE -> return
        UsernameService.Availability.CHECKING ->
            "Checking…" to StitchColors.textSecondary
        UsernameService.Availability.AVAILABLE ->
            "@$handle is available" to StitchColors.success
        UsernameService.Availability.TAKEN ->
            "@$handle is taken" to StitchColors.error
        UsernameService.Availability.RESERVED ->
            "That one is reserved. Try another." to StitchColors.error
        UsernameService.Availability.TOO_SHORT ->
            "At least 3 characters" to StitchColors.textSecondary
        UsernameService.Availability.INVALID_CHARACTERS ->
            "Letters, numbers, dots and underscores only" to StitchColors.error
        UsernameService.Availability.ERROR ->
            "Couldn't check that one — you can still continue" to StitchColors.textSecondary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        // iOS shows "available" as a Label with a tick.
        if (state == UsernameService.Availability.AVAILABLE) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        }
        Text(text, fontSize = 12.sp, color = color)
    }
}

/**
 * Three segments and a word. The ticks above say whether a password is allowed;
 * this says whether it is any good, which is not the same question.
 */
@Composable
fun PasswordStrengthBar(password: String) {
    if (password.isEmpty()) return
    val strength = PasswordStrength.of(password)
    val color = when (strength) {
        PasswordStrength.WEAK -> StitchColors.error
        PasswordStrength.FAIR -> Color(0xFFFF9500)
        PasswordStrength.STRONG -> StitchColors.success
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        repeat(3) { segment ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(
                        if (segment < strength.filledSegments) color
                        else StitchColors.textSecondary.copy(alpha = 0.25f),
                        RoundedCornerShape(2.dp)
                    )
            )
        }
        Text(strength.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}


// MARK: - Welcome Landing (iOS parity)

/**
 * Web OAuth client from google-services.json (`client_type: 3`). Google's ID
 * token must be minted for the WEB client or Firebase rejects it as
 * audience-mismatched, even though the Android client is what signs the request.
 */
private const val GOOGLE_WEB_CLIENT_ID =
    "854189118304-cf785f2n1i0p3psjidr1ib27c51ho346.apps.googleusercontent.com"

/**
 * Brand-first landing: logo, name, tagline, the two social buttons, then email.
 *
 * Deliberately mirrors iOS `welcomeLanding` — same order, same copy, same
 * hierarchy — because this is the first screen a new user sees and the two
 * platforms were telling different stories about what the app is. Android
 * opened straight into an email/password form; iOS opened on the brand.
 *
 * SCROLLABLE, with a minimum height of the screen (iOS "Login buttons stay
 * reachable on short screens"). A Column of weighted Spacers with no scroll is
 * exactly what pushed the auth buttons off the bottom of a short iPhone with
 * no way to reach them — locked out of the app entirely. With the inner
 * Column at least the viewport tall, the Spacers still centre the brand on a
 * tall screen and nothing scrolls; a short one scrolls because it has to.
 */
@Composable
private fun WelcomeLanding(
    appear: Float,
    isLoading: Boolean,
    onApple: () -> Unit,
    onGoogle: () -> Unit,
    onEmailSignUp: () -> Unit,
    onLogIn: () -> Unit,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = viewport),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.alpha(appear),
                ) {
                    Image(
                        painterResource(R.drawable.stitchsociallogo),
                        "Stitch Social",
                        Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        "Stitch Social",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                    )
                    Text(
                        "Where conversations become creativity.",
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                // Errors from Apple / Google go to the same alert as the form's
                // (iOS surfaceSocialError), not a line of red text here.
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().screenPadding(),
                ) {
                    // No icons on either button. Material has no Apple mark (it's
                    // a trademark, deliberately absent) and Google's own mark has
                    // brand rules a Material glyph would violate. iOS draws the
                    // SF Symbol Apple logo and a globe in place of a Google logo
                    // asset it doesn't ship; both want the real marks dropped in.
                    SocialButton("Continue with Apple", null, isLoading, onApple)
                    SocialButton("Continue with Google", null, isLoading, onGoogle)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.15f)))
                        Text("or", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.4f))
                        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.15f)))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                            .clickable(enabled = !isLoading) { onEmailSignUp() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Sign up with email", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Have an account?", fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))
                    Text(
                        "Log in",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LogInCyan,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isLoading) { onLogIn() }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }

                Spacer(Modifier.height(maxOf(24.dp, bottomInset + 12.dp)))
            }
        }
    }
}

@Composable
private fun SocialButton(
    title: String,
    icon: ImageVector?,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    // The label stays put while signing in; the screen-wide "Authenticating..."
    // overlay is the progress indicator, as on iOS.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .clickable(enabled = !isLoading) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon?.let { Icon(it, null, tint = Color.Black, modifier = Modifier.size(19.dp)) }
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
        }
    }
}
