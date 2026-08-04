package com.stitchsocial.club.services

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.stitchsocial.club.BuildConfig
import kotlinx.coroutines.tasks.await

/**
 * Google and Apple sign-in for Android — the counterpart to iOS `SocialAuth.swift`.
 *
 * Both return a Firebase [AuthCredential] plus the provider's display name, which
 * `AuthService.signInWithCredential` turns into a session and, for a first-time
 * user, a profile. Keeping the credential and the session separate is what lets
 * the login screen stay dumb about Firebase.
 *
 * User cancellation is NOT an error here. It surfaces as [SocialAuthCancelled] so
 * the caller can stay silent — an alert after someone deliberately backs out of a
 * sheet reads as a bug, and on iOS that specific noise is what Apple flagged.
 */
object SocialAuth {

    /** The person dismissed the sheet. Not a failure — say nothing. */
    class SocialAuthCancelled : Exception("cancelled")

    data class SocialCredential(
        val credential: AuthCredential,
        val displayName: String?,
    )

    /**
     * Google via Credential Manager.
     *
     * Uses the WEB client id, not the Android one. This trips everybody up: the
     * Android OAuth client is what signs the request, but the id token has to be
     * minted for the web client or Firebase rejects it as audience-mismatched.
     */
    suspend fun signInWithGoogle(context: Context, webClientID: String): SocialCredential {
        val option = GetGoogleIdOption.Builder()
            // false = show every Google account on the device, including when
            // none has been used with us before. Filtering to authorized accounts
            // shows an empty sheet to a first-time user, which looks broken.
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientID)
            .build()

        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val response = try {
            CredentialManager.create(context).getCredential(context, request)
        } catch (e: GetCredentialCancellationException) {
            throw SocialAuthCancelled()
        }

        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw IllegalStateException("Unexpected credential type: ${credential.type}")
        }

        val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
        return SocialCredential(
            credential = GoogleAuthProvider.getCredential(googleCredential.idToken, null),
            displayName = googleCredential.displayName,
        )
    }

    /**
     * Apple via Firebase's OAuth web flow.
     *
     * Android has no native Sign in with Apple, so this opens Apple's page in a
     * Custom Tab and comes back with a credential. It requires the Apple provider
     * to be configured in the Firebase console with a Services ID and key; if it
     * isn't, Firebase fails the call and the caller shows that message rather
     * than leaving a button that does nothing.
     */
    suspend fun signInWithApple(activity: Activity): SocialCredential {
        val provider = OAuthProvider.newBuilder("apple.com").apply {
            scopes = listOf("email", "name")
        }.build()

        val auth = FirebaseAuth.getInstance()

        // Resume an in-flight flow if the activity was recreated behind the
        // Custom Tab — otherwise a rotation mid-sign-in strands the result.
        val pending = auth.pendingAuthResult
        val result = try {
            (pending ?: auth.startActivityForSignInWithProvider(activity, provider)).await()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) println("SOCIAL AUTH: Apple flow failed — ${e.message}")
            throw e
        }

        val credential = result.credential
            ?: throw IllegalStateException("Apple returned no credential")

        return SocialCredential(
            credential = credential,
            displayName = result.user?.displayName,
        )
    }
}
