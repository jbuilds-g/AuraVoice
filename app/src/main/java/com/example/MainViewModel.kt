package com.example

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioCaptureEngine
import com.example.data.GeminiApiClient
import com.example.data.SecurePreferences
import com.example.service.AuraAccessibilityService
import com.example.service.OverlayService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class MainUiState(
    val hasAudioPermission: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val hasAccessibilityPermission: Boolean = false,
    val isOverlayServiceRunning: Boolean = false,
    val apiKey: String = "",
    val hasValidApiKey: Boolean = false,
    val isValidatingApiKey: Boolean = false,
    val apiKeyValidationResult: String? = null,
    val isApiKeyValid: Boolean? = null,
    val transcriptionMode: String = "smart",
    // Sandbox Dictation State
    val isSandboxRecording: Boolean = false,
    val isSandboxProcessing: Boolean = false,
    val sandboxRecordingSeconds: Int = 0,
    val sandboxTranscribedText: String = "",
    val feedbackMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val securePreferences = SecurePreferences(application)
    private val geminiApiClient = GeminiApiClient()
    private val audioCaptureEngine = AudioCaptureEngine(application)

    private val _uiState = MutableStateFlow(
        MainUiState(
            apiKey = securePreferences.getApiKey(),
            hasValidApiKey = securePreferences.hasValidApiKey(),
            transcriptionMode = securePreferences.getTranscriptionMode()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var recordedAudioFile: File? = null

    init {
        refreshPermissionStates()

        viewModelScope.launch {
            OverlayService.isServiceRunning.collect { isRunning ->
                _uiState.update { it.copy(isOverlayServiceRunning = isRunning) }
            }
        }
    }

    fun refreshPermissionStates() {
        val context = getApplication<Application>()
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

        val hasAccessibility = AuraAccessibilityService.isAccessibilitySettingsEnabled(context)
        val validKey = securePreferences.hasValidApiKey()

        _uiState.update {
            it.copy(
                hasAudioPermission = hasAudio,
                hasOverlayPermission = hasOverlay,
                hasAccessibilityPermission = hasAccessibility,
                hasValidApiKey = validKey
            )
        }
    }

    fun updateApiKey(newKey: String) {
        _uiState.update {
            it.copy(
                apiKey = newKey,
                apiKeyValidationResult = null,
                isApiKeyValid = null
            )
        }
    }

    fun validateAndSaveApiKey() {
        val key = _uiState.value.apiKey.trim()
        if (key.isBlank()) {
            _uiState.update {
                it.copy(
                    apiKeyValidationResult = "API Key cannot be blank.",
                    isApiKeyValid = false,
                    hasValidApiKey = false
                )
            }
            return
        }

        _uiState.update { it.copy(isValidatingApiKey = true, apiKeyValidationResult = null) }

        viewModelScope.launch {
            val result = geminiApiClient.testApiKey(key)
            if (result.isSuccess) {
                securePreferences.setApiKey(key)
                _uiState.update {
                    it.copy(
                        isValidatingApiKey = false,
                        apiKeyValidationResult = "API Key validated & securely encrypted with AES-256!",
                        isApiKeyValid = true,
                        hasValidApiKey = true
                    )
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Validation failed"
                _uiState.update {
                    it.copy(
                        isValidatingApiKey = false,
                        apiKeyValidationResult = "Validation failed: $err",
                        isApiKeyValid = false,
                        hasValidApiKey = false
                    )
                }
            }
        }
    }

    fun setTranscriptionMode(mode: String) {
        securePreferences.setTranscriptionMode(mode)
        _uiState.update { it.copy(transcriptionMode = mode) }
    }

    fun toggleOverlayService(enable: Boolean) {
        val context = getApplication<Application>()
        if (enable) {
            if (!_uiState.value.hasAudioPermission || !_uiState.value.hasOverlayPermission) {
                _uiState.update { it.copy(feedbackMessage = "Please grant Microphone and Overlay permissions first.") }
                return
            }
            if (!_uiState.value.hasValidApiKey) {
                _uiState.update { it.copy(feedbackMessage = "Please enter and save your Gemini API Key first.") }
                return
            }
            OverlayService.start(context)
            securePreferences.setOverlayActive(true)
        } else {
            OverlayService.stop(context)
            securePreferences.setOverlayActive(false)
        }
    }

    // --- Sandbox Live Testing Logic with Smart Append ---

    fun startSandboxRecording() {
        val key = securePreferences.getApiKey()
        if (key.isBlank()) {
            _uiState.update { it.copy(feedbackMessage = "Please configure your Gemini API Key in settings.") }
            return
        }

        val res = audioCaptureEngine.startRecording()
        if (res.isSuccess) {
            recordedAudioFile = res.getOrNull()
            _uiState.update {
                it.copy(
                    isSandboxRecording = true,
                    sandboxRecordingSeconds = 0,
                    feedbackMessage = null
                )
            }
            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    _uiState.update { it.copy(sandboxRecordingSeconds = it.sandboxRecordingSeconds + 1) }
                }
            }
        } else {
            _uiState.update { it.copy(feedbackMessage = "Microphone error: ${res.exceptionOrNull()?.message}") }
        }
    }

    fun stopSandboxRecording() {
        timerJob?.cancel()
        val file = audioCaptureEngine.stopRecording()
        _uiState.update { it.copy(isSandboxRecording = false, isSandboxProcessing = true) }

        if (file == null) {
            _uiState.update { it.copy(isSandboxProcessing = false, feedbackMessage = "No audio recorded.") }
            return
        }

        viewModelScope.launch {
            val key = securePreferences.getApiKey()
            val mode = _uiState.value.transcriptionMode
            val result = geminiApiClient.transcribeAudio(key, file, mode)

            if (result.isSuccess) {
                val newText = result.getOrNull() ?: ""
                _uiState.update { current ->
                    val currentText = current.sandboxTranscribedText
                    val combined = if (currentText.isNotBlank()) {
                        if (currentText.endsWith(" ") || currentText.endsWith("\n")) {
                            "$currentText$newText"
                        } else {
                            "$currentText $newText"
                        }
                    } else {
                        newText
                    }
                    current.copy(
                        isSandboxProcessing = false,
                        sandboxTranscribedText = combined,
                        feedbackMessage = "Text appended successfully!"
                    )
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Transcription error"
                _uiState.update {
                    it.copy(
                        isSandboxProcessing = false,
                        feedbackMessage = "Dictation failed: $err"
                    )
                }
            }
        }
    }

    fun updateSandboxText(newText: String) {
        _uiState.update { it.copy(sandboxTranscribedText = newText) }
    }

    fun clearSandboxText() {
        _uiState.update { it.copy(sandboxTranscribedText = "") }
    }

    fun copySandboxText() {
        val text = _uiState.value.sandboxTranscribedText
        if (text.isNotBlank()) {
            val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("AuraVoice", text)
            clipboard.setPrimaryClip(clip)
            _uiState.update { it.copy(feedbackMessage = "Text copied to clipboard") }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        audioCaptureEngine.cancelRecording()
    }
}
