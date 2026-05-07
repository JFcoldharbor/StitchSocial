/*
 * LinkedAccountManager.kt
 * STITCH SOCIAL — ANDROID KOTLIN
 *
 * Multi-account toggle. Mirror of iOS LinkedAccountManager.swift.
 *
 * Constraints:
 *   • Max 2 linked accounts: 1 personal + 1 business
 *   • Each must have a different email (Firebase enforces unique emails)
 *   • v1 supports email/password sign-in only
 *   • Local-only list — no cross-device sync
 *
 * Toggle mechanism: signOut + signIn(savedCreds) — sub-second round-trip.
 * Every service that listens to FirebaseAuth.AuthStateListener resets
 * automatically (HypeCoinCoordinator already does this).
 */

package com.stitchsocial.club.services

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.auth.FirebaseAuth
import com.stitchsocial.club.foundation.AccountType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

// ─────────────────────────────────────────────
// MARK: - Models
// ─────────────────────────────────────────────

enum class LinkedAuthProvider(val rawValue: String) {
    EMAIL_PASSWORD("email_password");

    companion object {
        fun fromRawValue(v: String?): LinkedAuthProvider =
            values().firstOrNull { it.rawValue == v } ?: EMAIL_PASSWORD
    }
}

/** Helper since the canonical AccountType is in foundation and doesn't
 *  ship a from-raw helper. */
private fun accountTypeFromRaw(v: String?): AccountType =
    AccountType.values().firstOrNull { it.rawValue == v } ?: AccountType.PERSONAL

data class LinkedAccount(
    val uid: String,
    val email: String,
    val accountType: AccountType,
    var displayName: String,
    var profileImageURL: String?,
    val provider: LinkedAuthProvider,
    val addedAt: Date
) {
    val id: String get() = uid

    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("email", email)
        put("accountType", accountType.rawValue)
        put("displayName", displayName)
        put("profileImageURL", profileImageURL ?: JSONObject.NULL)
        put("provider", provider.rawValue)
        put("addedAt", addedAt.time)
    }

    companion object {
        fun fromJson(o: JSONObject): LinkedAccount? = try {
            LinkedAccount(
                uid = o.getString("uid"),
                email = o.getString("email"),
                accountType = accountTypeFromRaw(o.optString("accountType")),
                displayName = o.optString("displayName"),
                profileImageURL = o.optString("profileImageURL").takeIf { it.isNotEmpty() && it != "null" },
                provider = LinkedAuthProvider.fromRawValue(o.optString("provider")),
                addedAt = Date(o.optLong("addedAt", System.currentTimeMillis()))
            )
        } catch (_: Exception) { null }
    }
}

sealed class LinkedAccountError(message: String) : Exception(message) {
    object MaxAccountsReached : LinkedAccountError("You can only link one personal and one business account.")
    object DuplicateAccountType : LinkedAccountError("An account of this type is already linked.")
    object SameEmail : LinkedAccountError("The two linked accounts must use different email addresses.")
    object CredentialsMissing : LinkedAccountError("Saved credentials for this account couldn't be read.")
    object WrongAccountType : LinkedAccountError("That account isn't the type you're trying to add.")
}

// ─────────────────────────────────────────────
// MARK: - Manager
// ─────────────────────────────────────────────

class LinkedAccountManager private constructor(context: Context) {

    companion object {
        @Volatile private var instance: LinkedAccountManager? = null

        /** Initialize once at app startup so non-context-holding services
         *  (AuthService, etc.) can use the static `shared` accessor. */
        fun getInstance(context: Context): LinkedAccountManager =
            instance ?: synchronized(this) {
                instance ?: LinkedAccountManager(context.applicationContext).also { instance = it }
            }

        /** Bootstrapped singleton or null if getInstance() hasn't fired yet. */
        val shared: LinkedAccountManager? get() = instance

        private const val PREFS_NAME = "stitch_linked_accounts_secure"
        private const val LIST_KEY = "linked_accounts_list"
        private const val UNIT_SEP = ""
    }

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _accounts = MutableStateFlow<List<LinkedAccount>>(emptyList())
    val accounts: StateFlow<List<LinkedAccount>> = _accounts.asStateFlow()

    private val _activeUID = MutableStateFlow<String?>(FirebaseAuth.getInstance().currentUser?.uid)
    val activeUID: StateFlow<String?> = _activeUID.asStateFlow()

    init {
        loadAccountsFromDisk()
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            _activeUID.value = auth.currentUser?.uid
        }
    }

    // MARK: - List + lookup

    fun activeAccount(): LinkedAccount? {
        val uid = _activeUID.value ?: return null
        return _accounts.value.firstOrNull { it.uid == uid }
    }

    fun otherAccount(): LinkedAccount? {
        val uid = _activeUID.value ?: return _accounts.value.firstOrNull()
        return _accounts.value.firstOrNull { it.uid != uid }
    }

    fun hasLinked(accountType: AccountType): Boolean =
        _accounts.value.any { it.accountType == accountType }

    val canAddAnother: Boolean get() = _accounts.value.size < 2

    // MARK: - Add

    /** Adds an email/password account AFTER the caller has already
     *  authenticated it via FirebaseAuth. Caller is responsible for
     *  verifying that account.accountType matches the new user's
     *  Firestore accountType. */
    fun addEmailPasswordAccount(account: LinkedAccount, email: String, password: String) {
        val current = _accounts.value
        if (current.size >= 2) throw LinkedAccountError.MaxAccountsReached
        if (current.any { it.accountType == account.accountType }) throw LinkedAccountError.DuplicateAccountType
        if (current.any { it.email.equals(email, ignoreCase = true) }) throw LinkedAccountError.SameEmail
        saveCreds(account.uid, email, password)
        _accounts.value = current + account
        persist()
    }

    /** Add the just-signed-in user if not already linked. Used by
     *  AuthService.signIn so existing users automatically get their
     *  current account into the list without an explicit step. */
    fun seedActiveIfMissing(
        uid: String,
        email: String,
        password: String,
        accountType: AccountType,
        displayName: String,
        profileImageURL: String?
    ) {
        if (uid.isEmpty()) return
        if (_accounts.value.any { it.uid == uid }) return
        val entry = LinkedAccount(
            uid = uid,
            email = email,
            accountType = accountType,
            displayName = displayName,
            profileImageURL = profileImageURL,
            provider = LinkedAuthProvider.EMAIL_PASSWORD,
            addedAt = Date()
        )
        saveCreds(uid, email, password)
        _accounts.value = _accounts.value + entry
        persist()
    }

    // MARK: - Remove

    fun removeAccount(uid: String) {
        deleteCreds(uid)
        _accounts.value = _accounts.value.filterNot { it.uid == uid }
        persist()
    }

    fun clearAll() {
        for (acc in _accounts.value) deleteCreds(acc.uid)
        _accounts.value = emptyList()
        persist()
    }

    // MARK: - Switch

    /** Signs the current Firebase user out and re-signs in as targetUID.
     *  Throws if credentials aren't on this device. All services that
     *  listen to FirebaseAuth.AuthStateListener will reset to the new
     *  uid (HypeCoinCoordinator already does this). */
    suspend fun switchTo(uid: String) {
        val account = _accounts.value.firstOrNull { it.uid == uid }
            ?: throw LinkedAccountError.CredentialsMissing
        val creds = loadCreds(uid) ?: throw LinkedAccountError.CredentialsMissing
        when (account.provider) {
            LinkedAuthProvider.EMAIL_PASSWORD -> {
                FirebaseAuth.getInstance().signOut()
                FirebaseAuth.getInstance()
                    .signInWithEmailAndPassword(creds.first, creds.second)
                    .await()
            }
        }
    }

    /** Toggle to the other linked account if there is one. No-op if only
     *  one is linked. */
    suspend fun toggleActive() {
        val other = otherAccount() ?: return
        switchTo(other.uid)
    }

    // MARK: - Profile metadata sync

    fun updateProfileMetadata(uid: String, displayName: String?, profileImageURL: String?) {
        val list = _accounts.value.toMutableList()
        val idx = list.indexOfFirst { it.uid == uid }
        if (idx == -1) return
        val cur = list[idx]
        list[idx] = cur.copy(
            displayName = displayName ?: cur.displayName,
            profileImageURL = profileImageURL ?: cur.profileImageURL
        )
        _accounts.value = list
        persist()
    }

    // MARK: - Storage

    private fun saveCreds(uid: String, email: String, password: String) {
        prefs.edit().putString("creds_$uid", "$email$UNIT_SEP$password").apply()
    }

    private fun loadCreds(uid: String): Pair<String, String>? {
        val raw = prefs.getString("creds_$uid", null) ?: return null
        val parts = raw.split(UNIT_SEP, limit = 2)
        if (parts.size != 2) return null
        return parts[0] to parts[1]
    }

    private fun deleteCreds(uid: String) {
        prefs.edit().remove("creds_$uid").apply()
    }

    private fun loadAccountsFromDisk() {
        val raw = prefs.getString(LIST_KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            val list = mutableListOf<LinkedAccount>()
            for (i in 0 until arr.length()) {
                LinkedAccount.fromJson(arr.getJSONObject(i))?.let { list += it }
            }
            _accounts.value = list
        } catch (_: Exception) { /* corrupt prefs — start fresh */ }
    }

    private fun persist() {
        val arr = JSONArray()
        for (acc in _accounts.value) arr.put(acc.toJson())
        prefs.edit().putString(LIST_KEY, arr.toString()).apply()
    }
}
