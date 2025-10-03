/*
 * SpecialUserEntry.kt - COMPLETE SPECIAL USERS CONFIGURATION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Special User Configuration System
 * Dependencies: None (Pure Kotlin data structures)
 * Features: Centralized special user management, auto-follow logic, tier assignments
 *
 * EXACT PORT: SpecialUserEntry.swift with all 11 special users
 * UPDATED: Includes new users (ironmanfitness, dpalance, kiakallen, janpaulmedina)
 * REMOVED: sandra@stitchsocial.me, justin@stitchsocial.me
 * AUTO-FOLLOW: Only James Fortune (founder)
 */

package com.stitchsocial.club.foundation

/**
 * Configuration for special users with custom privileges and starting benefits
 * Matches iOS UserTier and badge system exactly
 */
data class SpecialUserEntry(
    val email: String,
    val role: SpecialUserRole,
    val tierRawValue: String,          // References UserTier.rawValue
    val startingClout: Int,
    val customTitle: String,
    val customBio: String,
    val badgeRawValues: List<String>,  // Badge identifiers
    val specialPerks: List<String>,
    val isAutoFollowed: Boolean,
    val priority: Int                   // Higher = more priority
) {
    // Computed properties for easy access
    val displayRole: String
        get() = role.displayName
    
    val isFounder: Boolean
        get() = role == SpecialUserRole.FOUNDER || role == SpecialUserRole.CO_FOUNDER
    
    val isCelebrity: Boolean
        get() = role == SpecialUserRole.CELEBRITY || role == SpecialUserRole.AMBASSADOR
}

/**
 * Special user role categories - matches Swift enum exactly
 */
enum class SpecialUserRole(val displayName: String, val defaultPriority: Int) {
    FOUNDER("Founder", 1000),
    CO_FOUNDER("Co-Founder", 900),
    EMPLOYEE("Employee", 800),
    CELEBRITY("Celebrity", 700),
    AMBASSADOR("Ambassador", 600),
    INFLUENCER("Influencer", 500),
    ADVISOR("Advisor", 550),
    PARTNER("Special Partner", 400),
    AFFILIATE("Affiliate", 300)
}

/**
 * Centralized registry of all special users - COMPLETE LIST
 * Matches iOS SpecialUsersConfig.specialUsersList exactly
 */
object SpecialUsersConfig {
    
    // MARK: - Special Users Database - COMPLETE UPDATED LIST (11 USERS)
    
    /**
     * Complete list of all special users - Updated to match Swift
     * REMOVED: sandra@stitchsocial.me, justin@stitchsocial.me
     * ADDED: ironmanfitness662@yahoo.com, dpalance28@gmail.com, kiakallen@gmail.com, janpaulmedina@gmail.com
     */
    val specialUsersList: Map<String, SpecialUserEntry> = mapOf(
        
        // MARK: - FOUNDER (AUTO-FOLLOW ONLY)
        
        "james@stitchsocial.me" to SpecialUserEntry(
            email = "james@stitchsocial.me",
            role = SpecialUserRole.FOUNDER,
            tierRawValue = "founder",
            startingClout = 50000,
            customTitle = "Founder & CEO 👑",
            customBio = "Founder of Stitch Social 🎬 | Building the future of social video",
            badgeRawValues = listOf("founder_crown", "verified", "early_adopter"),
            specialPerks = listOf("auto_follow", "unfollow_protection", "priority_support", "admin_access", "clout_per_new_user"),
            isAutoFollowed = true,
            priority = 1000
        ),
        
        // MARK: - CO-FOUNDER (NO AUTO-FOLLOW)
        
        "bernadette@stitchsocial.me" to SpecialUserEntry(
            email = "bernadette@stitchsocial.me",
            role = SpecialUserRole.CO_FOUNDER,
            tierRawValue = "co_founder",
            startingClout = 25000,
            customTitle = "Co-Founder 💎",
            customBio = "Co-Founder of Stitch Social 🎬 | Building community through authentic connections",
            badgeRawValues = listOf("cofounder_crown", "verified", "early_adopter"),
            specialPerks = listOf("priority_support", "exclusive_features", "leadership_access"),
            isAutoFollowed = false,
            priority = 900
        ),
        
        // MARK: - FITNESS INFLUENCER
        
        "ironmanfitness662@yahoo.com" to SpecialUserEntry(
            email = "ironmanfitness662@yahoo.com",
            role = SpecialUserRole.INFLUENCER,
            tierRawValue = "influencer",
            startingClout = 15000,
            customTitle = "Fitness Influencer 💪",
            customBio = "Iron Man Fitness | Transforming lives through fitness and wellness content",
            badgeRawValues = listOf("influencer_crown", "verified", "fitness"),
            specialPerks = listOf("verified_badge", "exclusive_features", "fitness_content"),
            isAutoFollowed = false,
            priority = 500
        ),
        
        // MARK: - SOCIAL INFLUENCERS
        
        "dpalance28@gmail.com" to SpecialUserEntry(
            email = "dpalance28@gmail.com",
            role = SpecialUserRole.INFLUENCER,
            tierRawValue = "influencer",
            startingClout = 15000,
            customTitle = "Social Influencer ⭐",
            customBio = "Social Media Influencer | Creating engaging content and authentic connections",
            badgeRawValues = listOf("influencer_crown", "verified", "social"),
            specialPerks = listOf("verified_badge", "exclusive_features", "social_content"),
            isAutoFollowed = false,
            priority = 500
        ),
        
        "kiakallen@gmail.com" to SpecialUserEntry(
            email = "kiakallen@gmail.com",
            role = SpecialUserRole.INFLUENCER,
            tierRawValue = "influencer",
            startingClout = 15000,
            customTitle = "Social Influencer ⭐",
            customBio = "Content Creator & Social Influencer | Authentic storytelling and community building",
            badgeRawValues = listOf("influencer_crown", "verified", "social"),
            specialPerks = listOf("verified_badge", "exclusive_features", "content_creation"),
            isAutoFollowed = false,
            priority = 500
        ),
        
        // MARK: - STRATEGIC ADVISOR
        
        "janpaulmedina@gmail.com" to SpecialUserEntry(
            email = "janpaulmedina@gmail.com",
            role = SpecialUserRole.ADVISOR,
            tierRawValue = "partner",
            startingClout = 15000,
            customTitle = "Strategic Advisor 🎯",
            customBio = "Strategic Advisor | Helping shape the future of social video and community engagement",
            badgeRawValues = listOf("advisor_crown", "verified", "strategic"),
            specialPerks = listOf("advisor_access", "verified_badge", "strategic_input"),
            isAutoFollowed = false,
            priority = 550
        ),
        
        // MARK: - CELEBRITY AMBASSADORS
        
        "teddyruks@gmail.com" to SpecialUserEntry(
            email = "teddyruks@gmail.com",
            role = SpecialUserRole.CELEBRITY,
            tierRawValue = "top_creator",
            startingClout = 20000,
            customTitle = "Celebrity Ambassador ⭐",
            customBio = "Reality TV Star | Black Ink Crew 🖋️ | Celebrity Ambassador for Stitch Social",
            badgeRawValues = listOf("celebrity_crown", "verified", "early_adopter"),
            specialPerks = listOf("celebrity_support", "exclusive_features", "verified_badge"),
            isAutoFollowed = false,
            priority = 700
        ),
        
        "chaneyvisionent@gmail.com" to SpecialUserEntry(
            email = "chaneyvisionent@gmail.com",
            role = SpecialUserRole.CELEBRITY,
            tierRawValue = "top_creator",
            startingClout = 20000,
            customTitle = "TV Legend Ambassador 📺",
            customBio = "Poot from The Wire 🎭 | Actor & Producer | Chaney Vision Entertainment",
            badgeRawValues = listOf("celebrity_crown", "verified", "early_adopter"),
            specialPerks = listOf("celebrity_support", "exclusive_features", "verified_badge"),
            isAutoFollowed = false,
            priority = 700
        ),
        
        "pumanyc213@gmail.com" to SpecialUserEntry(
            email = "pumanyc213@gmail.com",
            role = SpecialUserRole.AMBASSADOR,
            tierRawValue = "celebrity",
            startingClout = 20000,
            customTitle = "Black Ink Ambassador 🎨",
            customBio = "Black Ink Original Cast Member | Father | Cannabis Expert | Ambassador to Stitch",
            badgeRawValues = listOf("celebrity_crown", "verified", "tv_star"),
            specialPerks = listOf("verified_badge", "exclusive_features", "priority_support"),
            isAutoFollowed = false,
            priority = 700
        ),
        
        // MARK: - MUSIC INDUSTRY / RAE SREMMURD FAMILY
        
        "afterflaspoint@icloud.com" to SpecialUserEntry(
            email = "afterflaspoint@icloud.com",
            role = SpecialUserRole.CELEBRITY,
            tierRawValue = "top_creator",
            startingClout = 25000,
            customTitle = "Diamond Selling Artist, Streamer 🎵",
            customBio = "1/2 of the dynamic group Rae Sremmurd 👑 | Music Industry Veteran",
            badgeRawValues = listOf("celebrity_crown", "verified", "early_adopter"),
            specialPerks = listOf("celebrity_support", "exclusive_features", "music_industry_perks"),
            isAutoFollowed = false,
            priority = 750
        ),
        
        "floydjrsullivan@yahoo.com" to SpecialUserEntry(
            email = "floydjrsullivan@yahoo.com",
            role = SpecialUserRole.AFFILIATE,
            tierRawValue = "influencer",
            startingClout = 12000,
            customTitle = "Veteran, Boss, Gamer 🎵",
            customBio = "Brother of Rae Sremmurd 👑 | King of my own Destiny | Family First",
            badgeRawValues = listOf("verified", "early_adopter"),
            specialPerks = listOf("affiliate_support", "exclusive_features", "family_connection"),
            isAutoFollowed = false,
            priority = 350
        ),
        
        "ohshitsad@gmail.com" to SpecialUserEntry(
            email = "ohshitsad@gmail.com",
            role = SpecialUserRole.AMBASSADOR,
            tierRawValue = "celebrity",
            startingClout = 15000,
            customTitle = "Black Ink Tattoo Artist 🎨",
            customBio = "Original Black Ink Cast | Tattoo Artist Enthusiast | Ambassador to Stitch",
            badgeRawValues = listOf("celebrity_crown", "verified", "tv_star"),
            specialPerks = listOf("verified_badge", "exclusive_features", "priority_support"),
            isAutoFollowed = false,
            priority = 700
        ),
        
        // MARK: - TECH AFFILIATES
        
        "srbentleyga@gmail.com" to SpecialUserEntry(
            email = "srbentleyga@gmail.com",
            role = SpecialUserRole.AFFILIATE,
            tierRawValue = "influencer",
            startingClout = 5000,
            customTitle = "Tech Developer 💻",
            customBio = "Technology Developer | Early Adopter | Building the future",
            badgeRawValues = listOf("verified", "early_adopter"),
            specialPerks = listOf("affiliate_support", "developer_tools", "early_access"),
            isAutoFollowed = false,
            priority = 350
        )
    )
    
    // MARK: - Access Methods
    
    /**
     * Get special user entry by email (case insensitive)
     */
    fun getSpecialUser(email: String): SpecialUserEntry? {
        return specialUsersList[email.lowercase()]
    }
    
    /**
     * Check if user is special
     */
    fun isSpecialUser(email: String): Boolean {
        return specialUsersList.containsKey(email.lowercase())
    }
    
    /**
     * Get all special users by role
     */
    fun getUsers(role: SpecialUserRole): List<SpecialUserEntry> {
        return specialUsersList.values.filter { it.role == role }
    }
    
    /**
     * Get all founders (founder + co-founder)
     */
    fun getAllFounders(): List<SpecialUserEntry> {
        return specialUsersList.values.filter { it.isFounder }
    }
    
    /**
     * Get all celebrities (celebrity + ambassador)
     */
    fun getAllCelebrities(): List<SpecialUserEntry> {
        return specialUsersList.values.filter { it.isCelebrity }
    }
    
    /**
     * Get all influencers
     */
    fun getAllInfluencers(): List<SpecialUserEntry> {
        return specialUsersList.values.filter { it.role == SpecialUserRole.INFLUENCER }
    }
    
    /**
     * Get all advisors
     */
    fun getAllAdvisors(): List<SpecialUserEntry> {
        return specialUsersList.values.filter { it.role == SpecialUserRole.ADVISOR }
    }
    
    /**
     * Get all affiliates
     */
    fun getAllAffiliates(): List<SpecialUserEntry> {
        return specialUsersList.values.filter { it.role == SpecialUserRole.AFFILIATE }
    }
    
    /**
     * Get auto-follow users (ONLY JAMES FORTUNE)
     */
    fun getAutoFollowUsers(): List<SpecialUserEntry> {
        return specialUsersList.values.filter { it.isAutoFollowed }
    }
    
    /**
     * Get users sorted by priority (highest first)
     */
    fun getUsersByPriority(): List<SpecialUserEntry> {
        return specialUsersList.values.sortedByDescending { it.priority }
    }
    
    /**
     * Get starting clout for user (with fallback)
     */
    fun getStartingClout(email: String): Int {
        return getSpecialUser(email)?.startingClout ?: 1500 // Default starting clout
    }
    
    /**
     * Get initial badges for user (returns raw values to be converted by existing system)
     */
    fun getInitialBadgeRawValues(email: String): List<String> {
        return getSpecialUser(email)?.badgeRawValues ?: listOf("early_adopter")
    }
    
    /**
     * Get special perks for user
     */
    fun getSpecialPerks(email: String): List<String> {
        return getSpecialUser(email)?.specialPerks ?: emptyList()
    }
    
    /**
     * Get custom title for user
     */
    fun getCustomTitle(email: String): String? {
        return getSpecialUser(email)?.customTitle
    }
    
    /**
     * Get custom bio for user
     */
    fun getCustomBio(email: String): String? {
        return getSpecialUser(email)?.customBio
    }
    
    /**
     * Get tier raw value for user (to be converted to UserTier by existing system)
     */
    fun getTierRawValue(email: String): String? {
        return getSpecialUser(email)?.tierRawValue
    }
    
    // MARK: - Special User Categories & Statistics
    
    /**
     * Statistics about special users
     */
    data class SpecialUserStatistics(
        val totalSpecialUsers: Int,
        val foundersCount: Int,
        val coFoundersCount: Int,
        val employeesCount: Int,
        val celebritiesCount: Int,
        val ambassadorsCount: Int,
        val influencersCount: Int,
        val advisorsCount: Int,
        val affiliatesCount: Int,
        val autoFollowCount: Int,
        val totalStartingClout: Int
    ) {
        val averageStartingClout: Double
            get() = if (totalSpecialUsers > 0) totalStartingClout.toDouble() / totalSpecialUsers else 0.0
    }
    
    /**
     * Get statistics about the special users system
     */
    fun getStatistics(): SpecialUserStatistics {
        val users = specialUsersList.values
        return SpecialUserStatistics(
            totalSpecialUsers = users.size,
            foundersCount = users.count { it.role == SpecialUserRole.FOUNDER },
            coFoundersCount = users.count { it.role == SpecialUserRole.CO_FOUNDER },
            employeesCount = users.count { it.role == SpecialUserRole.EMPLOYEE },
            celebritiesCount = users.count { it.role == SpecialUserRole.CELEBRITY },
            ambassadorsCount = users.count { it.role == SpecialUserRole.AMBASSADOR },
            influencersCount = users.count { it.role == SpecialUserRole.INFLUENCER },
            advisorsCount = users.count { it.role == SpecialUserRole.ADVISOR },
            affiliatesCount = users.count { it.role == SpecialUserRole.AFFILIATE },
            autoFollowCount = users.count { it.isAutoFollowed },
            totalStartingClout = users.sumOf { it.startingClout }
        )
    }
    
    /**
     * Print current configuration summary
     */
    fun printConfigurationSummary() {
        val stats = getStatistics()
        println("🌟 SPECIAL USERS CONFIG SUMMARY:")
        println("   Total Special Users: ${stats.totalSpecialUsers}")
        println("   Founders: ${stats.foundersCount}")
        println("   Co-Founders: ${stats.coFoundersCount}")
        println("   Influencers: ${stats.influencersCount}")
        println("   Advisors: ${stats.advisorsCount}")
        println("   Celebrities: ${stats.celebritiesCount}")
        println("   Affiliates: ${stats.affiliatesCount}")
        println("   Auto-Follow Users: ${stats.autoFollowCount}")
        println("   Total Starting Clout: ${stats.totalStartingClout}")
        println("   Average Starting Clout: ${stats.averageStartingClout.toInt()}")
    }
    
    /**
     * Get users containing specific text in bio/title
     */
    fun getAllUsers(containing: String): List<SpecialUserEntry> {
        val lowercaseText = containing.lowercase()
        return specialUsersList.values.filter {
            it.customBio.lowercase().contains(lowercaseText) ||
            it.customTitle.lowercase().contains(lowercaseText)
        }
    }
    
    // MARK: - Auto-Follow Integration
    
    /**
     * Detect special user by email
     */
    fun detectSpecialUser(email: String): SpecialUserEntry? {
        return getSpecialUser(email)
    }
    
    /**
     * Get James Fortune's user entry for auto-follow (ONLY AUTO-FOLLOW USER)
     */
    fun getJamesFortune(): SpecialUserEntry? {
        return getSpecialUser("james@stitchsocial.me")
    }
    
    /**
     * Check if user should be protected from unfollowing
     */
    fun isProtectedFromUnfollow(email: String): Boolean {
        val user = getSpecialUser(email) ?: return false
        return user.specialPerks.contains("unfollow_protection")
    }
    
    /**
     * Get all users with unfollow protection (currently only James)
     */
    fun getProtectedUsers(): List<SpecialUserEntry> {
        return specialUsersList.values.filter { it.specialPerks.contains("unfollow_protection") }
    }
    
    /**
     * Validate auto-follow configuration (should only be James)
     */
    fun validateAutoFollowConfig(): Boolean {
        val autoFollowUsers = getAutoFollowUsers()
        val isValid = autoFollowUsers.size == 1 && autoFollowUsers.first().email == "james@stitchsocial.me"
        
        if (!isValid) {
            println("⚠️ AUTO-FOLLOW CONFIG ERROR: Only James Fortune should have auto-follow enabled")
        } else {
            println("✅ AUTO-FOLLOW CONFIG: Correctly configured for James Fortune only")
        }
        
        return isValid
    }
}