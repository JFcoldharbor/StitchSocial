package com.stitchsocial.club.services

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Is this handle free, and can I have it? — iOS parity (UsernameService.swift).
 *
 * Nothing enforced username uniqueness before this on either platform. Signup
 * checked a reserved-word list at most, and firestore.rules had no opinion, so
 * two accounts could hold the same handle. Share links are /u/{username} and
 * resolve by querying for the name, so a duplicate sends a link to whichever
 * document answers first.
 *
 * The guarantee is in the rules, not here: usernames/{handle} allows create and
 * denies update, so writing a handle somebody already holds is an update and
 * fails. A check-then-write in this file would lose the race it exists to win —
 * two people can both read "free" in the same instant.
 */
object UsernameService {

    enum class Availability {
        IDLE, CHECKING, AVAILABLE, TAKEN, TOO_SHORT, INVALID_CHARACTERS, ERROR
    }

    private val db by lazy { FirebaseFirestore.getInstance("stitchfin") }

    /** Lowercased: @James and @james are one person to everyone but a database. */
    fun normalise(raw: String): String = raw.trim().lowercase()

    private val ALLOWED = Regex("^[a-z0-9._]+$")

    /** Null when the handle is shaped correctly; a verdict when it is not. */
    fun validate(raw: String): Availability? {
        val handle = normalise(raw)
        return when {
            handle.length < 3 -> Availability.TOO_SHORT
            !ALLOWED.matches(handle) -> Availability.INVALID_CHARACTERS
            else -> null
        }
    }

    /**
     * Reservations first, then the user list — the second is what catches the
     * handles that existed before reservations did.
     */
    suspend fun availability(raw: String): Availability {
        validate(raw)?.let { return it }
        val handle = normalise(raw)
        return try {
            if (db.collection("usernames").document(handle).get().await().exists()) {
                Availability.TAKEN
            } else {
                val existing = db.collection("users")
                    .whereEqualTo("username", handle)
                    .limit(1).get().await()
                if (existing.isEmpty) Availability.AVAILABLE else Availability.TAKEN
            }
        } catch (e: Exception) {
            Availability.ERROR
        }
    }

    // reserve() lived here and has moved to the server —
    // reserveUsernameOnUserCreate in index.js. The rule refuses client writes to
    // usernames/{handle} outright now.
}

/**
 * How good is the password someone just typed — iOS parity
 * (PasswordStrength.swift).
 *
 * Length carries most of the real strength; variety is the part a person can
 * act on while standing in a signup form.
 */
enum class PasswordStrength {
    WEAK, FAIR, STRONG;

    companion object {
        /** Anything that is not a letter or a number. Spaces count. */
        fun symbolCount(password: String): Int =
            password.count { !it.isLetterOrDigit() }

        fun of(password: String): PasswordStrength {
            var variety = 0
            if (password.any { it.isLowerCase() }) variety++
            if (password.any { it.isUpperCase() }) variety++
            if (password.any { it.isDigit() }) variety++
            if (symbolCount(password) > 0) variety++

            return when {
                password.length >= 16 -> STRONG
                password.length >= 12 -> if (variety >= 2) STRONG else FAIR
                password.length >= 8 -> if (variety >= 3) FAIR else WEAK
                else -> WEAK
            }
        }
    }

    val label: String
        get() = when (this) {
            WEAK -> "Weak"
            FAIR -> "Fair"
            STRONG -> "Strong"
        }

    val filledSegments: Int
        get() = when (this) {
            WEAK -> 1
            FAIR -> 2
            STRONG -> 3
        }
}

/** The floor, and the symbol rule, in one place on each platform. */
object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val REQUIRED_SYMBOLS = 2

    fun meetsLength(password: String) = password.length >= MIN_LENGTH
    fun meetsSymbols(password: String) =
        PasswordStrength.symbolCount(password) >= REQUIRED_SYMBOLS
    fun isAcceptable(password: String) = meetsLength(password) && meetsSymbols(password)
}
