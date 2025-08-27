package com.example.stitchsocialclub.firebase

/**
 * FirestoreConfig.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 3: Firebase Foundation - Database Connection Configuration
 * Dependencies: Layer 2 (Protocols), Layer 1 (Foundation) only
 * Database: stitchfin configuration and connection management
 *
 * Exact translation from Swift Config.swift Firebase section
 */

// MARK: - Environment Detection

enum class Environment {
    DEVELOPMENT,
    STAGING,
    PRODUCTION;

    companion object {
        val current: Environment
            get() = when {
                // Check BuildConfig for debug mode (Android equivalent of Swift #if DEBUG)
                BuildConfig.DEBUG -> DEVELOPMENT
                // In Android, you can use BuildConfig.BUILD_TYPE to distinguish staging
                BuildConfig.BUILD_TYPE == "staging" -> STAGING
                else -> PRODUCTION
            }
    }

    val isDevelopment: Boolean get() = this == DEVELOPMENT
    val isStaging: Boolean get() = this == STAGING
    val isProduction: Boolean get() = this == PRODUCTION

    val description: String
        get() = when (this) {
            DEVELOPMENT -> "Development"
            STAGING -> "Staging"
            PRODUCTION -> "Production"
        }
}

// MARK: - Firebase Configuration

object FirestoreConfig {

    // MARK: - Database Configuration

    /** Database name - prevents accidental default database usage */
    const val DATABASE_NAME = "stitchfin"

    /** Project ID for stitchfin database */
    const val PROJECT_ID = "stitchbeta-8bbfe"

    /** Storage bucket name (if different from default) */
    val STORAGE_BUCKET: String? = null // Uses default

    /** Full database path for stitchfin */
    val databasePath: String
        get() = "projects/$PROJECT_ID/databases/$DATABASE_NAME"

    // MARK: - Connection Settings

    /** Connection timeout for Firestore operations */
    const val CONNECTION_TIMEOUT_MS = 15000L // 15 seconds

    /** Upload timeout for large operations */
    const val UPLOAD_TIMEOUT_MS = 60000L // 60 seconds

    /** Maximum concurrent operations */
    const val MAX_CONCURRENT_OPERATIONS = 3

    /** Retry attempts for failed operations */
    const val MAX_RETRY_ATTEMPTS = 3

    /** Retry delay between attempts */
    const val RETRY_DELAY_MS = 1000L // 1 second

    // MARK: - Offline Settings

    /** Enable offline persistence */
    val enableOfflinePersistence: Boolean
        get() = when (Environment.current) {
            Environment.DEVELOPMENT -> true
            Environment.STAGING -> true
            Environment.PRODUCTION -> true
        }

    /** Cache size for offline persistence */
    const val CACHE_SIZE_BYTES = 100L * 1024L * 1024L // 100MB

    /** Cache garbage collection threshold */
    const val CACHE_GC_THRESHOLD = 40L * 1024L * 1024L // 40MB

    // MARK: - Security Settings

    /** Enable logging in debug mode */
    val enableLogging: Boolean
        get() = Environment.current.isDevelopment

    /** Enable security rules emulator */
    val useSecurityRulesEmulator: Boolean
        get() = Environment.current.isDevelopment

    /** Emulator host for development */
    const val EMULATOR_HOST = "localhost"

    /** Emulator port for Firestore */
    const val EMULATOR_PORT = 8080

    // MARK: - Performance Settings

    /** Maximum listeners per view */
    const val MAX_LISTENERS_PER_VIEW = 5

    /** Listener reconnect delay */
    const val LISTENER_RECONNECT_DELAY_MS = 2000L

    /** Maximum reconnect attempts */
    const val LISTENER_MAX_RECONNECT_ATTEMPTS = 10

    /** Batch write size limit */
    const val MAX_BATCH_SIZE = 500

    /** Transaction retry limit */
    const val MAX_TRANSACTION_RETRIES = 5

    /** Transaction timeout */
    const val TRANSACTION_TIMEOUT_MS = 60000L

    // MARK: - Environment-Specific Settings

    /** Get configuration for current environment */
    fun getEnvironmentConfig(): EnvironmentConfig {
        return when (Environment.current) {
            Environment.DEVELOPMENT -> EnvironmentConfig(
                enableDebugLogging = true,
                enableMetrics = true,
                strictValidation = false,
                enableEmulator = false, // Set to true if using Firestore emulator
                maxConnections = 10
            )
            Environment.STAGING -> EnvironmentConfig(
                enableDebugLogging = true,
                enableMetrics = true,
                strictValidation = true,
                enableEmulator = false,
                maxConnections = 20
            )
            Environment.PRODUCTION -> EnvironmentConfig(
                enableDebugLogging = false,
                enableMetrics = true,
                strictValidation = true,
                enableEmulator = false,
                maxConnections = 50
            )
        }
    }

    // MARK: - Validation Methods

    /** Firebase configuration validation */
    fun validateConfiguration(): Boolean {
        if (DATABASE_NAME.isEmpty()) {
            println("❌ FIRESTORE CONFIG: Database name is empty")
            return false
        }

        if (PROJECT_ID.isEmpty()) {
            println("❌ FIRESTORE CONFIG: Project ID is empty")
            return false
        }

        println("✅ FIRESTORE CONFIG: Database configured - $DATABASE_NAME")
        println("✅ FIRESTORE CONFIG: Project configured - $PROJECT_ID")
        return true
    }

    /** Check if configuration is valid for current environment */
    fun validateEnvironmentConfiguration(): ConfigValidationResult {
        val issues = mutableListOf<String>()

        // Check database configuration
        if (DATABASE_NAME.isEmpty()) {
            issues.add("Database name is missing")
        }

        if (PROJECT_ID.isEmpty()) {
            issues.add("Project ID is missing")
        }

        // Environment-specific checks
        when (Environment.current) {
            Environment.PRODUCTION -> {
                if (enableLogging) {
                    issues.add("Debug logging should be disabled in production")
                }
                if (useSecurityRulesEmulator) {
                    issues.add("Security rules emulator should be disabled in production")
                }
            }
            Environment.DEVELOPMENT -> {
                if (!enableLogging) {
                    issues.add("Debug logging should be enabled in development")
                }
            }
            Environment.STAGING -> {
                // Staging can have mixed settings
            }
        }

        return ConfigValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            environment = Environment.current
        )
    }

    /** Print configuration status for debugging */
    fun printConfigurationStatus() {
        val validation = validateEnvironmentConfiguration()
        val envConfig = getEnvironmentConfig()

        println("🔧 FIRESTORE CONFIG STATUS:")
        println("   Environment: ${Environment.current.description}")
        println("   Database: $DATABASE_NAME")
        println("   Project: $PROJECT_ID")
        println("   Offline Enabled: $enableOfflinePersistence")
        println("   Cache Size: ${CACHE_SIZE_BYTES / (1024 * 1024)}MB")
        println("   Debug Logging: ${envConfig.enableDebugLogging}")
        println("   Emulator: ${envConfig.enableEmulator}")
        println("   Max Connections: ${envConfig.maxConnections}")

        if (!validation.isValid) {
            println("⚠️ Configuration Issues:")
            validation.issues.forEach { issue ->
                println("   - $issue")
            }
        } else {
            println("✅ Configuration is valid")
        }
    }

    // MARK: - Initialization

    /** Initialize Firestore configuration for stitchfin database */
    fun initializeFirestoreConfig(): Boolean {
        println("🔧 FIRESTORE CONFIG: Initializing for stitchfin database...")

        val configValid = validateConfiguration()
        val envValid = validateEnvironmentConfiguration().isValid

        return if (configValid && envValid) {
            println("✅ FIRESTORE CONFIG: stitchfin database configuration initialized successfully")
            println("📊 FIRESTORE CONFIG: Environment: ${Environment.current.description}")
            println("🔍 FIRESTORE CONFIG: Max operations: $MAX_CONCURRENT_OPERATIONS")
            true
        } else {
            println("❌ FIRESTORE CONFIG: stitchfin database configuration initialization failed")
            false
        }
    }

    // MARK: - Connection Utilities

    /** Get connection settings for current environment */
    fun getConnectionSettings(): ConnectionSettings {
        val envConfig = getEnvironmentConfig()

        return ConnectionSettings(
            databaseName = DATABASE_NAME,
            projectId = PROJECT_ID,
            enablePersistence = enableOfflinePersistence,
            cacheSizeBytes = CACHE_SIZE_BYTES,
            timeoutMs = CONNECTION_TIMEOUT_MS,
            maxRetries = MAX_RETRY_ATTEMPTS,
            enableLogging = envConfig.enableDebugLogging,
            useEmulator = envConfig.enableEmulator,
            emulatorHost = if (envConfig.enableEmulator) EMULATOR_HOST else null,
            emulatorPort = if (envConfig.enableEmulator) EMULATOR_PORT else null
        )
    }

    /** Get operation limits for current environment */
    fun getOperationLimits(): OperationLimits {
        return OperationLimits(
            maxBatchSize = MAX_BATCH_SIZE,
            maxTransactionRetries = MAX_TRANSACTION_RETRIES,
            transactionTimeoutMs = TRANSACTION_TIMEOUT_MS,
            maxListenersPerView = MAX_LISTENERS_PER_VIEW,
            listenerReconnectDelayMs = LISTENER_RECONNECT_DELAY_MS,
            maxConcurrentOperations = MAX_CONCURRENT_OPERATIONS
        )
    }
}

// MARK: - Supporting Data Classes

data class EnvironmentConfig(
    val enableDebugLogging: Boolean,
    val enableMetrics: Boolean,
    val strictValidation: Boolean,
    val enableEmulator: Boolean,
    val maxConnections: Int
)

data class ConfigValidationResult(
    val isValid: Boolean,
    val issues: List<String>,
    val environment: Environment
) {
    val summary: String
        get() = if (isValid) {
            "✅ Configuration is valid for $environment"
        } else {
            "❌ Configuration has ${issues.size} issue(s) in $environment"
        }
}

data class ConnectionSettings(
    val databaseName: String,
    val projectId: String,
    val enablePersistence: Boolean,
    val cacheSizeBytes: Long,
    val timeoutMs: Long,
    val maxRetries: Int,
    val enableLogging: Boolean,
    val useEmulator: Boolean,
    val emulatorHost: String?,
    val emulatorPort: Int?
)

data class OperationLimits(
    val maxBatchSize: Int,
    val maxTransactionRetries: Int,
    val transactionTimeoutMs: Long,
    val maxListenersPerView: Int,
    val listenerReconnectDelayMs: Long,
    val maxConcurrentOperations: Int
)

// MARK: - Configuration Extensions

/** Helper for BuildConfig access */
object BuildConfig {
    // These would be provided by Android build system
    const val DEBUG = true // This gets replaced by actual BuildConfig.DEBUG
    const val BUILD_TYPE = "debug" // This gets replaced by actual BuildConfig.BUILD_TYPE
}