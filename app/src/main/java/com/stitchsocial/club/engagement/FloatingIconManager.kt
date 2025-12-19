/*
 * FloatingIconManager.kt - FLOATING ICON SPAWNING
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Simple: Every tap = floating flame 🔥 or snowflake ❄️
 */

package com.stitchsocial.club.viewmodels

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.foundation.UserTier
import com.stitchsocial.club.engagement.FloatingIcon
import com.stitchsocial.club.engagement.FloatingIconType
import com.stitchsocial.club.engagement.IconAnimationType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

class FloatingIconManager : ViewModel() {

    val activeIcons = mutableStateListOf<FloatingIcon>()

    init {
        viewModelScope.launch {
            while (true) {
                delay(2000)
                val expired = activeIcons.filter { it.isExpired() }
                activeIcons.removeAll(expired)
            }
        }
    }

    /**
     * Spawn hype flame 🔥
     * Founder = explosion, others = single flame
     */
    fun spawnHypeIcon(
        from: Offset,
        userTier: UserTier,
        isFounderFirstTap: Boolean = false
    ) {
        when {
            isFounderFirstTap && (userTier == UserTier.FOUNDER || userTier == UserTier.CO_FOUNDER) -> {
                spawnExplosion(from, FloatingIconType.HYPE, userTier, 8)
            }
            isFounderFirstTap && isPremiumTier(userTier) -> {
                spawnBurst(from, FloatingIconType.HYPE, userTier, 4)
            }
            else -> {
                spawnSingle(from, FloatingIconType.HYPE, userTier)
            }
        }
    }

    /**
     * Spawn cool snowflake ❄️
     * Founder = explosion, others = single snowflake
     */
    fun spawnCoolIcon(
        from: Offset,
        userTier: UserTier,
        isFounderFirstTap: Boolean = false
    ) {
        when {
            isFounderFirstTap && (userTier == UserTier.FOUNDER || userTier == UserTier.CO_FOUNDER) -> {
                spawnExplosion(from, FloatingIconType.COOL, userTier, 8)
            }
            isFounderFirstTap && isPremiumTier(userTier) -> {
                spawnBurst(from, FloatingIconType.COOL, userTier, 4)
            }
            else -> {
                spawnSingle(from, FloatingIconType.COOL, userTier)
            }
        }
    }

    private fun spawnSingle(from: Offset, iconType: FloatingIconType, userTier: UserTier) {
        val icon = FloatingIcon(
            startPosition = from,
            iconType = iconType,
            animationType = IconAnimationType.STANDARD,
            tier = userTier
        )
        activeIcons.add(icon)
        autoRemove(icon)
    }

    private fun spawnBurst(from: Offset, iconType: FloatingIconType, userTier: UserTier, count: Int) {
        repeat(count) { i ->
            viewModelScope.launch {
                delay((i * 50).toLong())
                val spreadAngle = if (count > 1) ((i.toFloat() / (count - 1)) - 0.5f) * 60f else 0f
                val spreadX = kotlin.math.sin(Math.toRadians(spreadAngle.toDouble())).toFloat() * 30f
                val position = Offset(
                    x = from.x + spreadX + Random.nextFloat() * 10f - 5f,
                    y = from.y + Random.nextFloat() * 8f - 4f
                )
                val icon = FloatingIcon(
                    startPosition = position,
                    iconType = iconType,
                    animationType = IconAnimationType.TIER_BOOST,
                    tier = userTier
                )
                activeIcons.add(icon)
                autoRemove(icon)
            }
        }
    }

    private fun spawnExplosion(from: Offset, iconType: FloatingIconType, userTier: UserTier, count: Int) {
        repeat(count) { i ->
            viewModelScope.launch {
                delay((i * 30).toLong())
                val angle = (i.toFloat() / count) * 360f
                val radius = 25f + Random.nextFloat() * 15f
                val spreadX = kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat() * radius
                val spreadY = kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat() * radius * 0.3f
                val position = Offset(x = from.x + spreadX, y = from.y + spreadY)
                val icon = FloatingIcon(
                    startPosition = position,
                    iconType = iconType,
                    animationType = IconAnimationType.FOUNDER_EXPLOSION,
                    tier = userTier
                )
                activeIcons.add(icon)
                autoRemove(icon)
            }
        }
    }

    private fun autoRemove(icon: FloatingIcon) {
        viewModelScope.launch {
            delay(3500)
            activeIcons.remove(icon)
        }
    }

    private fun isPremiumTier(tier: UserTier): Boolean {
        return tier in listOf(
            UserTier.TOP_CREATOR, UserTier.LEGENDARY,
            UserTier.PARTNER, UserTier.ELITE, UserTier.AMBASSADOR
        )
    }

    fun clearAll() {
        activeIcons.clear()
    }

    override fun onCleared() {
        super.onCleared()
        clearAll()
    }
}