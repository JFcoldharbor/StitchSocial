/*
 * BusinessProfile.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 1: Foundation - Business account model and builder
 * Mirrors: BusinessProfile.swift (iOS)
 * Dependencies: AdCategory (com.stitchsocial.club.services.AdCategory — already exists in AdService.kt)
 *
 * NOTE: AdCategory is defined in AdService.kt (services package). Import it from there.
 * AccountType is defined here in the foundation package.
 *
 * CACHING: BusinessProfile embedded in BasicUserInfo — same cache TTL, zero extra reads.
 * BATCHING: Written as part of user doc set() at signup — no separate writes.
 */

package com.stitchsocial.club.foundation

import com.google.firebase.Timestamp
import com.stitchsocial.club.services.AdCategory
import java.util.Date

// MARK: - AccountType

enum class AccountType(val rawValue: String) {
    PERSONAL("personal"),
    BUSINESS("business");

    val displayName: String get() = when (this) {
        PERSONAL -> "Personal"
        BUSINESS -> "Business"
    }

    companion object {
        fun fromRawValue(raw: String): AccountType {
            return values().find { it.rawValue == raw.lowercase() } ?: PERSONAL
        }
    }
}

// MARK: - BusinessProfile

data class BusinessProfile(
    val brandName: String,
    val websiteURL: String?,
    val businessCategory: AdCategory,
    val brandLogoURL: String?,
    val businessDescription: String?,
    val isVerifiedBusiness: Boolean,
    val createdAt: Date
) {
    /** Display-friendly category label — mirrors iOS categoryDisplay */
    val categoryDisplay: String get() = "${businessCategory.icon} ${businessCategory.displayName}"
}

// MARK: - BusinessProfileBuilder

object BusinessProfileBuilder {

    fun build(data: Map<String, Any>): BusinessProfile? {
        val accountType = data["accountType"] as? String ?: return null
        if (accountType != "business") return null

        val brandName = data["brandName"] as? String ?: return null

        // Fuzzy category match — handles case mismatches, mirrors iOS normalization
        val categoryRaw = data["businessCategory"] as? String ?: "other"
        val category = AdCategory.fromRawValue(categoryRaw.lowercase()) ?: AdCategory.OTHER

        val websiteURL = data["websiteURL"] as? String
        val brandLogoURL = data["brandLogoURL"] as? String
        val businessDescription = data["businessDescription"] as? String
        val isVerified = data["isVerifiedBusiness"] as? Boolean ?: false
        val createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date()

        return BusinessProfile(
            brandName = brandName,
            websiteURL = websiteURL,
            businessCategory = category,
            brandLogoURL = brandLogoURL,
            businessDescription = businessDescription,
            isVerifiedBusiness = isVerified,
            createdAt = createdAt
        )
    }
}