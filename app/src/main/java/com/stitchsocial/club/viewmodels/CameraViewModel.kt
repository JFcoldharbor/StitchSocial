/*
 * CameraViewModel.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 7: ViewModels - Camera Recording State with Tier-Based Limits
 * Dependencies: Foundation Layer only
 * UPDATED: Added tier-based recording duration limits
 */

package com.stitchsocial.club.viewmodels

import androidx.lifecycle.ViewModel
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.flow.*

/**
 * Camera ViewModel with tier-based recording limits
 * UPDATED: Added user tier integration for recording duration limits
 */
class CameraViewModel : ViewModel() {

    // MARK: - Recording State

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    private val _lastError = MutableStateFlow<StitchError?>(null)
    val lastError: StateFlow<StitchError?> = _lastError.asStateFlow()

    // MARK: - Tier-Based Recording Limits (NEW)

    private val _maxRecordingDuration = MutableStateFlow(30L)
    val maxRecordingDuration: StateFlow<Long> = _maxRecordingDuration.asStateFlow()

    private val _isUnlimitedRecording = MutableStateFlow(false)
    val isUnlimitedRecording: StateFlow<Boolean> = _isUnlimitedRecording.asStateFlow()

    // MARK: - Simple Recording Actions

    fun startRecording() {
        _isRecording.value = true
        println("CAMERA: Recording started")
    }

    fun stopRecording() {
        _isRecording.value = false
        println("CAMERA: Recording stopped")
    }

    fun clearError() {
        _lastError.value = null
    }

    // MARK: - Tier-Based Recording Methods (NEW)

    fun updateRecordingLimits(userTier: UserTier) {
        val maxDuration = when (userTier) {
            UserTier.FOUNDER, UserTier.CO_FOUNDER -> 0L // Unlimited
            UserTier.PARTNER, UserTier.LEGENDARY, UserTier.TOP_CREATOR -> 120L
            else -> 30L
        }

        _maxRecordingDuration.value = maxDuration
        _isUnlimitedRecording.value = (maxDuration == 0L)

        println("CAMERA: Updated recording limits - Tier: ${userTier.displayName}, Duration: ${if (maxDuration == 0L) "Unlimited" else "${maxDuration}s"}")
    }

    fun getRecordingLimitText(userTier: UserTier): String {
        return when (userTier) {
            UserTier.FOUNDER, UserTier.CO_FOUNDER -> "Unlimited"
            UserTier.PARTNER, UserTier.LEGENDARY, UserTier.TOP_CREATOR -> "2min limit"
            else -> "30s limit"
        }
    }

    fun getTimeRemainingText(): String {
        if (_isUnlimitedRecording.value) return "∞"

        val remaining = _maxRecordingDuration.value - _recordingDuration.value
        if (remaining <= 0) return "00:00"

        val minutes = remaining / 60
        val seconds = remaining % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun getRecordingProgress(): Float {
        if (_isUnlimitedRecording.value) return 0f
        if (_maxRecordingDuration.value == 0L) return 0f

        return (_recordingDuration.value.toFloat() / _maxRecordingDuration.value.toFloat()).coerceIn(0f, 1f)
    }

    fun shouldAutoStop(): Boolean {
        return !_isUnlimitedRecording.value && _recordingDuration.value >= _maxRecordingDuration.value
    }

    fun updateRecordingDuration(duration: Long) {
        _recordingDuration.value = duration
    }
}