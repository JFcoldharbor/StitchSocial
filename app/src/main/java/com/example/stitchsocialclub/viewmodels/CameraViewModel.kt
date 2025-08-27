/*
 * CameraViewModel.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 7: ViewModels - Minimal Camera Recording State
 * Dependencies: Foundation Layer only
 * TEMPORARY: Simplified version to fix compilation
 */

package com.example.stitchsocialclub.viewmodels

import androidx.lifecycle.ViewModel
import com.example.stitchsocialclub.foundation.*
import kotlinx.coroutines.flow.*

/**
 * Minimal CameraViewModel for compilation - will expand later
 */
class CameraViewModel : ViewModel() {

    // MARK: - Recording State

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    private val _lastError = MutableStateFlow<StitchError?>(null)
    val lastError: StateFlow<StitchError?> = _lastError.asStateFlow()

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
}