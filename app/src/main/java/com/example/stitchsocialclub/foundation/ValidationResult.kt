package com.example.stitchsocial.foundation

import com.example.stitchsocialclub.foundation.ValidationResult

/**
 * Validation.kt
 * Foundation Layer (1) - Zero dependencies
 * Pure validation functions matching Swift validation patterns exactly
 *
 * BLUEPRINT: Swift AuthService.swift, ThreadCreationRequest.swift, LoginView.swift
 * TRANSLATION: NSPredicate → Regex, computed properties → validation functions
 */

// MARK: - Validation Error Types

/**
 * Validation error for single field validation
 * Uses existing ValidationResult from CoreTypes.kt
 */
data class FieldValidationError(
    val field: String,
    val message: String
)

// MARK: - Email Validation

/**
 * Email validation matching Swift AuthService.isValidEmail exactly
 * Uses same regex pattern as Swift NSPredicate
 */
object EmailValidation {

    private val EMAIL_REGEX = Regex("[A-Z0-9a-z._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,64}")

    /**
     * Validate email format - exact Swift translation
     * @param email Email address to validate
     * @return ValidationResult with success or failure
     */
    fun validateEmail(email: String): ValidationResult {
        return if (isValidEmailFormat(email)) {
            ValidationResult.success()
        } else {
            ValidationResult.failure("Please enter a valid email address")
        }
    }

    /**
     * Check if email format is valid - matches Swift exactly
     */
    fun isValidEmailFormat(email: String): Boolean {
        return EMAIL_REGEX.matches(email)
    }
}

// MARK: - Password Validation

/**
 * Password validation matching Swift AuthService and LoginView requirements
 * 8+ characters, uppercase, lowercase, number - exact Swift logic
 */
object PasswordValidation {

    const val MIN_PASSWORD_LENGTH = 8

    /**
     * Validate password strength - matches Swift isValidPassword exactly
     * @param password Password to validate
     * @return ValidationResult with specific requirement failures
     */
    fun validatePassword(password: String): ValidationResult {
        val errors = mutableListOf<String>()

        if (password.length < MIN_PASSWORD_LENGTH) {
            errors.add("Password must be at least $MIN_PASSWORD_LENGTH characters long")
        }

        if (!password.any { it.isUpperCase() }) {
            errors.add("Password must contain at least one uppercase letter")
        }

        if (!password.any { it.isLowerCase() }) {
            errors.add("Password must contain at least one lowercase letter")
        }

        if (!password.any { it.isDigit() }) {
            errors.add("Password must contain at least one number")
        }

        return if (errors.isEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure(*errors.toTypedArray())
        }
    }

    /**
     * Get password requirements list - matches Swift passwordRequirements
     */
    fun getPasswordRequirements(): List<String> {
        return listOf(
            "At least $MIN_PASSWORD_LENGTH characters long",
            "Contains uppercase letter (A-Z)",
            "Contains lowercase letter (a-z)",
            "Contains number (0-9)"
        )
    }

    /**
     * Check individual password requirements - for UI feedback
     */
    fun checkPasswordRequirements(password: String): Map<String, Boolean> {
        return mapOf(
            "length" to (password.length >= MIN_PASSWORD_LENGTH),
            "uppercase" to password.any { it.isUpperCase() },
            "lowercase" to password.any { it.isLowerCase() },
            "number" to password.any { it.isDigit() }
        )
    }
}

// MARK: - Username Validation

/**
 * Username validation matching Swift AppConstants limits
 * 3-20 characters, alphanumeric + underscore only
 */
object UsernameValidation {

    const val MIN_USERNAME_LENGTH = 3
    const val MAX_USERNAME_LENGTH = 20

    private val USERNAME_REGEX = Regex("^[a-zA-Z0-9_]+$")

    /**
     * Validate username format and length
     * @param username Username to validate
     * @return ValidationResult with specific failures
     */
    fun validateUsername(username: String): ValidationResult {
        val errors = mutableListOf<String>()
        val trimmed = username.trim()

        if (trimmed.isEmpty()) {
            errors.add("Username cannot be empty")
        }

        if (trimmed.length < MIN_USERNAME_LENGTH) {
            errors.add("Username must be at least $MIN_USERNAME_LENGTH characters long")
        }

        if (trimmed.length > MAX_USERNAME_LENGTH) {
            errors.add("Username cannot exceed $MAX_USERNAME_LENGTH characters")
        }

        if (!USERNAME_REGEX.matches(trimmed)) {
            errors.add("Username can only contain letters, numbers, and underscores")
        }

        if (trimmed.startsWith("_") || trimmed.endsWith("_")) {
            errors.add("Username cannot start or end with underscore")
        }

        return if (errors.isEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure(*errors.toTypedArray())
        }
    }

    /**
     * Check if username format is valid
     */
    fun isValidUsernameFormat(username: String): Boolean {
        return validateUsername(username).isValid
    }
}

// MARK: - Display Name Validation

/**
 * Display name validation matching Swift AuthService.isValidDisplayName
 * 1-50 characters, trimmed whitespace
 */
object DisplayNameValidation {

    const val MIN_DISPLAY_NAME_LENGTH = 1
    const val MAX_DISPLAY_NAME_LENGTH = 50

    /**
     * Validate display name - exact Swift translation
     * @param displayName Display name to validate
     * @return ValidationResult with failures
     */
    fun validateDisplayName(displayName: String): ValidationResult {
        val trimmed = displayName.trim()
        val errors = mutableListOf<String>()

        if (trimmed.isEmpty()) {
            errors.add("Display name cannot be empty")
        }

        if (trimmed.length < MIN_DISPLAY_NAME_LENGTH) {
            errors.add("Display name must be at least $MIN_DISPLAY_NAME_LENGTH character long")
        }

        if (trimmed.length > MAX_DISPLAY_NAME_LENGTH) {
            errors.add("Display name cannot exceed $MAX_DISPLAY_NAME_LENGTH characters")
        }

        return if (errors.isEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure(*errors.toTypedArray())
        }
    }
}

// MARK: - Video Validation

/**
 * Video validation matching Swift AppConstants and OptimizationConfig
 * Duration, file size, format validation
 */
object VideoValidation {

    const val MIN_VIDEO_DURATION_MS = 1000L // 1 second
    const val MAX_VIDEO_DURATION_MS = 60000L // 60 seconds
    const val MIN_VIDEO_FILE_SIZE = 1024L * 1024L // 1MB
    const val MAX_VIDEO_FILE_SIZE = 100L * 1024L * 1024L // 100MB

    private val SUPPORTED_VIDEO_FORMATS = setOf("mp4", "mov", "m4v")

    /**
     * Validate video duration - matches Swift limits exactly
     * @param durationMs Duration in milliseconds
     * @return ValidationResult
     */
    fun validateVideoDuration(durationMs: Long): ValidationResult {
        val errors = mutableListOf<String>()

        if (durationMs < MIN_VIDEO_DURATION_MS) {
            errors.add("Video must be at least ${MIN_VIDEO_DURATION_MS / 1000} second long")
        }

        if (durationMs > MAX_VIDEO_DURATION_MS) {
            errors.add("Video cannot exceed ${MAX_VIDEO_DURATION_MS / 1000} seconds")
        }

        return if (errors.isEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure(*errors.toTypedArray())
        }
    }

    /**
     * Validate video file size - matches Swift limits exactly
     * @param fileSizeBytes File size in bytes
     * @return ValidationResult
     */
    fun validateVideoFileSize(fileSizeBytes: Long): ValidationResult {
        val errors = mutableListOf<String>()

        if (fileSizeBytes < MIN_VIDEO_FILE_SIZE) {
            errors.add("Video file too small (minimum ${MIN_VIDEO_FILE_SIZE / (1024 * 1024)}MB)")
        }

        if (fileSizeBytes > MAX_VIDEO_FILE_SIZE) {
            errors.add("Video file too large (maximum ${MAX_VIDEO_FILE_SIZE / (1024 * 1024)}MB)")
        }

        return if (errors.isEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure(*errors.toTypedArray())
        }
    }

    /**
     * Validate video format - matches Swift supportedFormats
     * @param filename Video filename or extension
     * @return ValidationResult
     */
    fun validateVideoFormat(filename: String): ValidationResult {
        val extension = filename.substringAfterLast('.', "").lowercase()

        return if (SUPPORTED_VIDEO_FORMATS.contains(extension)) {
            ValidationResult.success()
        } else {
            ValidationResult.failure("Unsupported video format. Supported: ${SUPPORTED_VIDEO_FORMATS.joinToString(", ")}")
        }
    }

    /**
     * Comprehensive video validation
     * @param durationMs Duration in milliseconds
     * @param fileSizeBytes File size in bytes
     * @param filename Video filename
     * @return ValidationResult with all errors
     */
    fun validateVideo(durationMs: Long, fileSizeBytes: Long, filename: String): ValidationResult {
        val allErrors = mutableListOf<String>()

        // Collect all error messages
        val durationResult = validateVideoDuration(durationMs)
        allErrors.addAll(durationResult.errors)

        val sizeResult = validateVideoFileSize(fileSizeBytes)
        allErrors.addAll(sizeResult.errors)

        val formatResult = validateVideoFormat(filename)
        allErrors.addAll(formatResult.errors)

        return if (allErrors.isEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure(*allErrors.toTypedArray())
        }
    }
}

// MARK: - Content Validation

/**
 * Content validation for threads, descriptions, bios
 * Matches Swift ThreadCreationRequest validation
 */
object ContentValidation {

    const val MIN_THREAD_TITLE_LENGTH = 1
    const val MAX_THREAD_TITLE_LENGTH = 100
    const val MAX_BIO_LENGTH = 150
    const val MAX_DESCRIPTION_LENGTH = 500

    /**
     * Validate thread title - matches Swift ThreadCreationRequest exactly
     * @param title Thread title to validate
     * @return ValidationResult
     */
    fun validateThreadTitle(title: String): ValidationResult {
        val trimmed = title.trim()
        val errors = mutableListOf<String>()

        if (trimmed.isEmpty()) {
            errors.add("Title cannot be empty")
        }

        if (trimmed.length < MIN_THREAD_TITLE_LENGTH) {
            errors.add("Title too short (minimum $MIN_THREAD_TITLE_LENGTH characters)")
        }

        if (trimmed.length > MAX_THREAD_TITLE_LENGTH) {
            errors.add("Title too long (maximum $MAX_THREAD_TITLE_LENGTH characters)")
        }

        return if (errors.isEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure(*errors.toTypedArray())
        }
    }

    /**
     * Validate user bio - matches Swift AppConstants limits
     * @param bio User bio to validate
     * @return ValidationResult
     */
    fun validateBio(bio: String): ValidationResult {
        val trimmed = bio.trim()

        return if (trimmed.length <= MAX_BIO_LENGTH) {
            ValidationResult.success()
        } else {
            ValidationResult.failure("Bio cannot exceed $MAX_BIO_LENGTH characters")
        }
    }

    /**
     * Validate description text
     * @param description Description to validate
     * @return ValidationResult
     */
    fun validateDescription(description: String): ValidationResult {
        val trimmed = description.trim()

        return if (trimmed.length <= MAX_DESCRIPTION_LENGTH) {
            ValidationResult.success()
        } else {
            ValidationResult.failure("Description cannot exceed $MAX_DESCRIPTION_LENGTH characters")
        }
    }
}

// MARK: - URL Validation

/**
 * URL validation for video and thumbnail URLs
 * Matches Swift URL validation patterns
 */
object URLValidation {

    private val URL_REGEX = Regex("^https?://[\\w\\-]+(\\.[\\w\\-]+)+([\\w\\-\\.,@?^=%&:/~\\+#]*[\\w\\-\\@?^=%&/~\\+#])?$")

    /**
     * Validate URL format
     * @param url URL to validate
     * @return ValidationResult
     */
    fun validateURL(url: String): ValidationResult {
        return if (isValidURLFormat(url)) {
            ValidationResult.success()
        } else {
            ValidationResult.failure("Invalid URL format")
        }
    }

    /**
     * Check if URL format is valid
     */
    fun isValidURLFormat(url: String): Boolean {
        return url.isNotBlank() && URL_REGEX.matches(url)
    }

    /**
     * Validate video URL specifically
     * @param videoURL Video URL to validate
     * @return ValidationResult
     */
    fun validateVideoURL(videoURL: String): ValidationResult {
        val errors = mutableListOf<String>()

        if (videoURL.isBlank()) {
            errors.add("Video URL is required")
        } else if (!isValidURLFormat(videoURL)) {
            errors.add("Invalid video URL format")
        }

        return if (errors.isEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure(*errors.toTypedArray())
        }
    }

    /**
     * Validate thumbnail URL specifically
     * @param thumbnailURL Thumbnail URL to validate
     * @return ValidationResult
     */
    fun validateThumbnailURL(thumbnailURL: String): ValidationResult {
        val errors = mutableListOf<String>()

        if (thumbnailURL.isBlank()) {
            errors.add("Thumbnail URL is required")
        } else if (!isValidURLFormat(thumbnailURL)) {
            errors.add("Invalid thumbnail URL format")
        }

        return if (errors.isEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure(*errors.toTypedArray())
        }
    }
}

// MARK: - Validation Extensions

/**
 * String extension functions for quick validation checks
 */
fun String.isValidEmail(): Boolean = EmailValidation.validateEmail(this).isValid
fun String.isValidUsername(): Boolean = UsernameValidation.validateUsername(this).isValid
fun String.isValidURL(): Boolean = URLValidation.validateURL(this).isValid

/**
 * Validation helper for multiple fields
 */
object ValidationHelper {

    /**
     * Combine multiple validation results
     * @param validations List of validation results
     * @return Combined ValidationResult
     */
    fun combineValidations(vararg validations: ValidationResult): ValidationResult {
        val allErrors = validations.flatMap { it.errors }

        return if (allErrors.isEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure(*allErrors.toTypedArray())
        }
    }

    /**
     * Validate field with custom validator
     * @param fieldName Name of field being validated
     * @param value Value to validate
     * @param validator Validation function
     * @return ValidationResult with field name in error
     */
    fun validateField(
        fieldName: String,
        value: String,
        validator: (String) -> ValidationResult
    ): ValidationResult {
        val result = validator(value)
        return if (result.isValid) {
            result
        } else {
            val prefixedErrors = result.errors.map { "$fieldName: $it" }
            ValidationResult.failure(*prefixedErrors.toTypedArray())
        }
    }
}