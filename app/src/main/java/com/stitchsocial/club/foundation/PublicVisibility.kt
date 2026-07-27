package com.stitchsocial.club.foundation

/**
 * Moderation visibility gate — mirrors iOS Moderation/PublicVisibility.swift.
 *
 * A video may appear on a public / cross-user surface ONLY when moderation has
 * PASSED (publicVisibility == "public"). States:
 *   (missing)             legacy pre-moderation doc -> treated as PUBLIC
 *   "public"              passed -> visible everywhere
 *   "pending"             awaiting scan -> owner's OWN profile only, never public
 *   "hidden_from_public"  flagged / blocked / scan error -> owner-only
 *
 * Public, cross-user surfaces MUST exclude anything that isn't publicly visible.
 * Owner-side profile surfaces show the owner their own pending/hidden content.
 */
fun isVideoPubliclyVisible(data: Map<String, Any>): Boolean =
    (data["publicVisibility"] as? String ?: "public") == "public"

fun videoPublicVisibility(data: Map<String, Any>): String =
    data["publicVisibility"] as? String ?: "public"

/** True only when moderation has passed — use to filter public surfaces. */
val CoreVideoMetadata.isPubliclyVisible: Boolean
    get() = (publicVisibility ?: "public") == "public"
