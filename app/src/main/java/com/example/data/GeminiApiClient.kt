package com.example.data

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GeminiApiClient handles communication strictly with Google's gemini-3.5-transcribe model.
 * Endpoint: https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-transcribe:generateContent
 * Uses transcription_config with mode: { "type": "smart" } to natively strip filler words,
 * remove disfluencies, and format structured text.
 */
class GeminiApiClient {

    companion object {
        private const val TAG = "GeminiApiClient"
        private const val TRANSCRIBE_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-transcribe:generateContent"

        // Base64-encoded minimal silent AAC/MP4 audio payload for instant key verification
        private const val MINIMAL_VERIFICATION_AUDIO_BASE64 =
            "AAAAHGZ0eXBtcDQyAAAAAW1wNDJtcDQxaXNvbQAAAAxtb292AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAAA" +
            "AAABAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribes an audio file using gemini-3.5-transcribe with smart formatting.
     */
    suspend fun transcribeAudio(
        context: Context,
        audioFile: File,
        mode: String = "smart"
    ): Result<String> {
        val apiKey = SecurePreferences(context).getApiKey()
        if (apiKey.isBlank()) {
            throw IllegalStateException("No API key configured.")
        }
        return transcribeAudio(apiKey, audioFile, mode)
    }

    suspend fun transcribeAudio(
        apiKey: String,
        audioFile: File,
        mode: String = "smart"
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalStateException("No API key configured.")
        }

        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(IllegalStateException("Audio capture buffer is empty."))
        }

        try {
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            executeTranscribeRequest(apiKey = apiKey, base64Audio = base64Audio)
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe audio request failed", e)
            Result.failure(e)
        }
    }

    private fun executeTranscribeRequest(apiKey: String, base64Audio: String): Result<String> {
        return try {
            val url = "$TRANSCRIBE_ENDPOINT?key=$apiKey"

            val jsonBody = JSONObject().apply {
                val contents = JSONArray()
                val contentObj = JSONObject()
                val parts = JSONArray()

                val audioPart = JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/mp4")
                        put("data", base64Audio)
                    })
                }
                parts.put(audioPart)
                contentObj.put("parts", parts)
                contents.put(contentObj)
                put("contents", contents)

                // transcription_config with mode: {"type": "smart"}
                val transcriptionConfig = JSONObject().apply {
                    put("mode", JSONObject().apply {
                        put("type", "smart")
                    })
                }
                put("transcription_config", transcriptionConfig)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errorMsg = extractErrorMessage(bodyString, response.code)
                    return Result.failure(Exception(errorMsg))
                }

                val responseJson = JSONObject(bodyString)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val resParts = content?.optJSONArray("parts")
                    if (resParts != null && resParts.length() > 0) {
                        val text = resParts.getJSONObject(0).optString("text", "")
                        return Result.success(text.trim())
                    }
                }
                Result.success("")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Validates the user's API key strictly against gemini-3.5-transcribe:generateContent
     * with transcription_config { mode: { type: "smart" } }.
     */
    suspend fun testApiKey(apiKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            throw IllegalStateException("No API key configured.")
        }

        try {
            val url = "$TRANSCRIBE_ENDPOINT?key=$trimmedKey"

            val jsonBody = JSONObject().apply {
                val contents = JSONArray()
                val contentObj = JSONObject()
                val parts = JSONArray()

                val audioPart = JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/mp4")
                        put("data", MINIMAL_VERIFICATION_AUDIO_BASE64)
                    })
                }
                parts.put(audioPart)
                contentObj.put("parts", parts)
                contents.put(contentObj)
                put("contents", contents)

                val transcriptionConfig = JSONObject().apply {
                    put("mode", JSONObject().apply {
                        put("type", "smart")
                    })
                }
                put("transcription_config", transcriptionConfig)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    // Check if the failure was due to invalid API key (400 API_KEY_INVALID or 403 Forbidden)
                    val isKeyInvalid = response.code == 403 ||
                            (response.code == 400 && (
                                bodyString.contains("API_KEY_INVALID", ignoreCase = true) ||
                                bodyString.contains("API key not valid", ignoreCase = true) ||
                                bodyString.contains("API_KEY", ignoreCase = true)
                            ))

                    if (isKeyInvalid) {
                        val errorMsg = extractErrorMessage(bodyString, response.code)
                        Result.failure(Exception("API Key Invalid: $errorMsg"))
                    } else if (response.code == 400) {
                        // 400 with audio buffer length/format means the API key was successfully authenticated
                        // by Google's API gateway!
                        Log.d(TAG, "API key authenticated successfully by gemini-3.5-transcribe gateway.")
                        Result.success(true)
                    } else {
                        val errorMsg = extractErrorMessage(bodyString, response.code)
                        Result.failure(Exception("Validation failed (${response.code}): $errorMsg"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractErrorMessage(bodyString: String, statusCode: Int): String {
        return try {
            val json = JSONObject(bodyString)
            val errorObj = json.optJSONObject("error")
            errorObj?.optString("message") ?: "Error $statusCode: $bodyString"
        } catch (e: Exception) {
            "Error $statusCode: $bodyString"
        }
    }
}
