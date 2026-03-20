/*
 * AppConfig.kt - CENTRALIZED APP CONFIGURATION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Package: com.stitchsocial.club
 *
 * FIXED: OPENAI_API_KEY read via reflection so it compiles even if the
 * buildConfigField hasn't been added to build.gradle.kts yet.
 * Once you add the field, it will use the real value automatically.
 *
 * TO ENABLE AI: Add to defaultConfig in build.gradle.kts:
 *   buildConfigField("String", "OPENAI_API_KEY",
 *       "\"${localProps["OPENAI_API_KEY"] ?: ""}\"")
 * And add to local.properties:
 *   OPENAI_API_KEY=sk-your-key-here
 */

package com.stitchsocial.club

object AppConfig {

    // MARK: - API

    object API {

        object OpenAI {
            // Safe read — returns "" if buildConfigField not yet added
            val API_KEY: String
                get() = try {
                    Class.forName("com.stitchsocial.club.BuildConfig")
                        .getField("OPENAI_API_KEY")
                        .get(null) as? String ?: ""
                } catch (_: Exception) { "" }

            const val BASE_URL = "https://api.openai.com/v1"

            fun isConfigured(): Boolean = API_KEY.length > 10

            object Whisper {
                const val MODEL           = "whisper-1"
                const val RESPONSE_FORMAT = "text"
            }

            object ChatCompletion {
                const val MODEL       = "gpt-4o"
                const val MAX_TOKENS  = 1000
                const val TEMPERATURE = 0.7
            }
        }
    }

    // MARK: - Performance

    object Performance {
        const val CONNECTION_TIMEOUT = 30_000L
        const val READ_TIMEOUT       = 60_000L
        const val WRITE_TIMEOUT      = 60_000L
    }

    // MARK: - Feature Flags

    object Features {
        val enableAIAnalysis: Boolean
            get() = API.OpenAI.isConfigured()

        val enableDebugLogging: Boolean
            get() = try {
                Class.forName("com.stitchsocial.club.BuildConfig")
                    .getField("DEBUG").get(null) as? Boolean ?: false
            } catch (_: Exception) { false }

        const val enableHypeCoin          = true
        const val enableCommunities       = true
        const val enableCashOut           = true
        const val enablePushNotifications = true
    }

    // MARK: - App Content Limits

    object App {
        const val MAX_TITLE_LENGTH       = 100
        const val MAX_DESCRIPTION_LENGTH = 300
        const val MAX_HASHTAGS           = 10
        const val MAX_VIDEO_DURATION_SEC = 300
        const val MAX_VIDEO_FILE_SIZE_MB = 100
        const val FEED_PAGE_SIZE         = 20
    }

    // MARK: - Firebase

    object Firebase {
        const val DATABASE_NAME  = "stitchfin"
        const val PROJECT_ID     = "stitchbeta-8bbfe"
        const val STORAGE_BUCKET = "stitchbeta-8bbfe.appspot.com"
    }

    // MARK: - Debug

    /** Called by VideoCoordinator.kt during AI analysis init */
    fun printConfigurationStatus() {
        println("🔧 APP CONFIG STATUS:")
        println("   AI Enabled:    ${Features.enableAIAnalysis}")
        println("   AI Configured: ${API.OpenAI.isConfigured()}")
        println("   Debug Logging: ${Features.enableDebugLogging}")
        println("   API Key len:   ${API.OpenAI.API_KEY.length}")
    }

    // MARK: - URLs

    object URLs {
        const val BASE     = "https://stitchsocial.me"
        const val WALLET   = "$BASE/app/account"
        const val SUPPORT  = "$BASE/support"
        const val TERMS    = "$BASE/terms"
        const val PRIVACY  = "$BASE/privacy"
    }
}