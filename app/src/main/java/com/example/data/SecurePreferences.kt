package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecurePreferences manages application configuration using encrypted preferences.
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
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""

    fun hasValidApiKey(): Boolean = getApiKey().isNotBlank()

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clearApiKey() {
        prefs.edit().putString(KEY_API_KEY, "").apply()
    }

    fun isOverlayActive(): Boolean = prefs.getBoolean(KEY_OVERLAY_ACTIVE, false)

    fun setOverlayActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY_ACTIVE, active).apply()
    }

    fun getTranscriptionMode(): String = prefs.getString(KEY_TRANSCRIPTION_MODE, "smart") ?: "smart"

    fun setTranscriptionMode(mode: String) {
        prefs.edit().putString(KEY_TRANSCRIPTION_MODE, mode).apply()
    }

    fun isHapticEnabled(): Boolean = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)

    fun setHapticEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, enabled).apply()
    }

    fun getSelectedModel(): String {
        val stored = prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        return if (stored == "auto") DEFAULT_MODEL else stored
    }

    fun setSelectedModel(model: String) {
        prefs.edit().putString(KEY_SELECTED_MODEL, model).apply()
    }
}
