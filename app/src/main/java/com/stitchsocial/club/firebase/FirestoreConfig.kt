package com.stitchsocial.club.firebase

import com.stitchsocial.club.BuildConfig

/**
 * FirestoreConfig.kt — FIXED
 * Removed fake BuildConfig stub (was shadowing Android's real generated class).
 * Added import for real BuildConfig.
 * printConfigurationStatus() retained — VideoCoordinator.kt calls it.
 */

enum class Environment {
    DEVELOPMENT, STAGING, PRODUCTION;

    companion object {
        val current: Environment
            get() = when {
                BuildConfig.DEBUG                    -> DEVELOPMENT
                BuildConfig.BUILD_TYPE == "staging"  -> STAGING
                else                                 -> PRODUCTION
            }
    }

    val isDevelopment: Boolean get() = this == DEVELOPMENT
    val isStaging:     Boolean get() = this == STAGING
    val isProduction:  Boolean get() = this == PRODUCTION

    val description: String
        get() = when (this) {
            DEVELOPMENT -> "Development"
            STAGING     -> "Staging"
            PRODUCTION  -> "Production"
        }
}

object FirestoreConfig {

    const val DATABASE_NAME  = "stitchfin"
    const val PROJECT_ID     = "stitchbeta-8bbfe"
    val   STORAGE_BUCKET: String? = null
    val   databasePath: String get() = "projects/$PROJECT_ID/databases/$DATABASE_NAME"

    const val CONNECTION_TIMEOUT_MS           = 15_000L
    const val UPLOAD_TIMEOUT_MS               = 60_000L
    const val MAX_CONCURRENT_OPERATIONS       = 3
    const val MAX_RETRY_ATTEMPTS              = 3
    const val RETRY_DELAY_MS                  = 1_000L
    const val CACHE_SIZE_BYTES                = 100L * 1024L * 1024L
    const val CACHE_GC_THRESHOLD             = 40L  * 1024L * 1024L
    const val MAX_LISTENERS_PER_VIEW          = 5
    const val LISTENER_RECONNECT_DELAY_MS     = 2_000L
    const val LISTENER_MAX_RECONNECT_ATTEMPTS = 10
    const val MAX_BATCH_SIZE                  = 500
    const val MAX_TRANSACTION_RETRIES         = 5
    const val TRANSACTION_TIMEOUT_MS          = 60_000L
    const val EMULATOR_HOST                   = "localhost"
    const val EMULATOR_PORT                   = 8080

    val enableOfflinePersistence: Boolean     get() = true
    val enableLogging:            Boolean     get() = Environment.current.isDevelopment
    val useSecurityRulesEmulator: Boolean     get() = Environment.current.isDevelopment

    fun getEnvironmentConfig(): EnvironmentConfig = when (Environment.current) {
        Environment.DEVELOPMENT -> EnvironmentConfig(true,  true, false, false, 10)
        Environment.STAGING     -> EnvironmentConfig(true,  true, true,  false, 20)
        Environment.PRODUCTION  -> EnvironmentConfig(false, true, true,  false, 50)
    }

    fun validateConfiguration(): Boolean {
        if (DATABASE_NAME.isEmpty() || PROJECT_ID.isEmpty()) {
            println("❌ FIRESTORE CONFIG: Invalid config")
            return false
        }
        println("✅ FIRESTORE CONFIG: $DATABASE_NAME / $PROJECT_ID")
        return true
    }

    fun validateEnvironmentConfiguration(): ConfigValidationResult {
        val issues = mutableListOf<String>()
        if (DATABASE_NAME.isEmpty()) issues.add("Database name missing")
        if (PROJECT_ID.isEmpty())    issues.add("Project ID missing")
        when (Environment.current) {
            Environment.PRODUCTION -> {
                if (enableLogging)            issues.add("Debug logging should be off in production")
                if (useSecurityRulesEmulator) issues.add("Emulator should be off in production")
            }
            Environment.DEVELOPMENT -> if (!enableLogging) issues.add("Debug logging should be on")
            else -> {}
        }
        return ConfigValidationResult(issues.isEmpty(), issues, Environment.current)
    }

    /** Called by VideoCoordinator.kt */
    fun printConfigurationStatus() {
        val v = validateEnvironmentConfiguration()
        val e = getEnvironmentConfig()
        println("🔧 FIRESTORE CONFIG STATUS:")
        println("   Environment: ${Environment.current.description}")
        println("   Database: $DATABASE_NAME  Project: $PROJECT_ID")
        println("   Cache: ${CACHE_SIZE_BYTES / (1024 * 1024)}MB  Logging: ${e.enableDebugLogging}")
        if (!v.isValid) v.issues.forEach { println("   ⚠️ $it") }
        else println("   ✅ Config valid")
    }

    fun initializeFirestoreConfig(): Boolean {
        println("🔧 FIRESTORE CONFIG: Initializing stitchfin...")
        val ok = validateConfiguration() && validateEnvironmentConfiguration().isValid
        println(if (ok) "✅ FIRESTORE CONFIG: Ready" else "❌ FIRESTORE CONFIG: Failed")
        return ok
    }

    fun getConnectionSettings(): ConnectionSettings {
        val e = getEnvironmentConfig()
        return ConnectionSettings(
            DATABASE_NAME, PROJECT_ID, enableOfflinePersistence,
            CACHE_SIZE_BYTES, CONNECTION_TIMEOUT_MS, MAX_RETRY_ATTEMPTS,
            e.enableDebugLogging, e.enableEmulator,
            if (e.enableEmulator) EMULATOR_HOST else null,
            if (e.enableEmulator) EMULATOR_PORT else null
        )
    }

    fun getOperationLimits() = OperationLimits(
        MAX_BATCH_SIZE, MAX_TRANSACTION_RETRIES, TRANSACTION_TIMEOUT_MS,
        MAX_LISTENERS_PER_VIEW, LISTENER_RECONNECT_DELAY_MS, MAX_CONCURRENT_OPERATIONS
    )
}

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
    val summary: String get() = if (isValid)
        "✅ Valid for $environment" else "❌ ${issues.size} issue(s) in $environment"
}

data class ConnectionSettings(
    val databaseName: String, val projectId: String,
    val enablePersistence: Boolean, val cacheSizeBytes: Long,
    val timeoutMs: Long, val maxRetries: Int,
    val enableLogging: Boolean, val useEmulator: Boolean,
    val emulatorHost: String?, val emulatorPort: Int?
)

data class OperationLimits(
    val maxBatchSize: Int, val maxTransactionRetries: Int,
    val transactionTimeoutMs: Long, val maxListenersPerView: Int,
    val listenerReconnectDelayMs: Long, val maxConcurrentOperations: Int
)