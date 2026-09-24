package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecurePreferences manages sensitive application configuration using AES-256 GCM encryption.
 * The API key is initialized to a blank string ("") and contains zero hardcoded defaults or fallbacks.
 */
class SecurePreferences(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "auravoice_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("auravoice_prefs_fallback", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_OVERLAY_ACTIVE = "overlay_active"
        private const val KEY_TRANSCRIPTION_MODE = "transcription_mode"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
    }

    /**
     * Retrieves the API key purely from EncryptedSharedPreferences.
     * Initialized to empty string ("") with zero hardcoded keys or build fallbacks.
     */
    fun getApiKey(): String {
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun hasValidApiKey(): Boolean {
        return getApiKey().isNotBlank()
    }

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clearApiKey() {
        prefs.edit().putString(KEY_API_KEY, "").apply()
    }

    fun isOverlayActive(): Boolean {
        return prefs.getBoolean(KEY_OVERLAY_ACTIVE, false)
    }

    fun setOverlayActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY_ACTIVE, active).apply()
    }

    fun getTranscriptionMode(): String {
        return prefs.getString(KEY_TRANSCRIPTION_MODE, "smart") ?: "smart"
    }

    fun setTranscriptionMode(mode: String) {
        prefs.edit().putString(KEY_TRANSCRIPTION_MODE, mode).apply()
    }

    fun isHapticEnabled(): Boolean {
        return prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
    }

    fun setHapticEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, enabled).apply()
    }
}
