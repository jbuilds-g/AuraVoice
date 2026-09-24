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
 * GeminiApiClient handles speech-to-text dictation using Google's Gemini API.
 * Primary Model: gemini-3.5-transcribe with transcription_config { mode: { type: "smart" } }.
 * Fallback Model: gemini-2.5-flash / gemini-2.0-flash with a specialized speech cleanup system prompt
 * if gemini-3.5-transcribe is not accessible on the user's specific API key tier (e.g. HTTP 404).
 */
class GeminiApiClient {

    companion object {
        private const val TAG = "GeminiApiClient"
        private const val PRIMARY_TRANSCRIBE_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-transcribe:generateContent"
        private const val FALLBACK_TRANSCRIBE_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
        private const val SECONDARY_FALLBACK_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

        // Base64-encoded minimal silent AAC/MP4 audio payload for instant key verification
        private const val MINIMAL_VERIFICATION_AUDIO_BASE64 =
            "AAAAHGZ0eXBtcDQyAAAAAW1wNDJtcDQxaXNvbQAAAAxtb292AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAAA" +
            "AAABAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

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
            return@withContext Result.failure(IllegalStateException("Audio capture buffer is empty (0 bytes). Check microphone permissions."))
        }

        Log.d(TAG, "Preparing audio transcription for file: ${audioFile.name}, size: ${audioFile.length()} bytes")

        try {
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            // 1. Attempt with gemini-3.5-transcribe
            val primaryResult = executePrimaryTranscribeRequest(apiKey, base64Audio, mode)
            if (primaryResult.isSuccess) {
                val text = primaryResult.getOrNull() ?: ""
                if (text.isNotBlank()) {
                    Log.d(TAG, "Primary model (gemini-3.5-transcribe) succeeded: '$text'")
                    return@withContext Result.success(text)
                }
            }

            val primaryError = primaryResult.exceptionOrNull()?.message ?: "Empty output"
            Log.w(TAG, "Primary transcribe attempt failed or empty ($primaryError). Trying fallback multimodal audio model...")

            // 2. If 404 (model not found on user's API key tier) or empty result, fallback to gemini-2.5-flash
            val fallbackResult = executeMultimodalFallback(FALLBACK_TRANSCRIBE_ENDPOINT, apiKey, base64Audio, mode)
            if (fallbackResult.isSuccess) {
                val text = fallbackResult.getOrNull() ?: ""
                if (text.isNotBlank()) {
                    Log.d(TAG, "Fallback model succeeded: '$text'")
                    return@withContext Result.success(text)
                }
            }

            // 3. Try secondary fallback gemini-2.0-flash if needed
            val secondaryResult = executeMultimodalFallback(SECONDARY_FALLBACK_ENDPOINT, apiKey, base64Audio, mode)
            if (secondaryResult.isSuccess) {
                val text = secondaryResult.getOrNull() ?: ""
                if (text.isNotBlank()) {
                    return@withContext Result.success(text)
                }
            }

            // If all failed, return the clearest diagnostic error
            val finalError = primaryResult.exceptionOrNull()?.message
                ?: fallbackResult.exceptionOrNull()?.message
                ?: "No speech detected in recording. Please speak louder and closer to the mic."
            Result.failure(Exception(finalError))

        } catch (e: Exception) {
            Log.e(TAG, "Transcribe audio request failed with exception", e)
            Result.failure(e)
        }
    }

    /**
     * Executes a Generate Content request targeting gemini-3.5-transcribe.
     * Transcription options use the Generate Content API's audioTranscriptionConfig schema.
     */
    private fun executePrimaryTranscribeRequest(apiKey: String, base64Audio: String, mode: String): Result<String> {
        return try {
            val url = "$PRIMARY_TRANSCRIBE_ENDPOINT?key=$apiKey"

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

                // Generate Content API uses generationConfig.audioTranscriptionConfig.
                // This endpoint does not accept the Interactions API's transcription_config shape.
                val modeType = if (mode.equals("verbatim", ignoreCase = true)) "VERBATIM" else "SMART"
                put("generationConfig", JSONObject().apply {
                    put("audioTranscriptionConfig", JSONObject().apply {
                        put("mode", modeType)
                    })
                })
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                Log.d(TAG, "Primary response code: ${response.code}, body: ${bodyString.take(300)}")

                if (!response.isSuccessful) {
                    val errorMsg = extractErrorMessage(bodyString, response.code)
                    return Result.failure(Exception(errorMsg))
                }

                parseCandidatesText(bodyString)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing primary transcribe request", e)
            Result.failure(e)
        }
    }

    /**
     * Multimodal audio fallback using general Gemini models with smart speech cleanup instructions.
     */
    private fun executeMultimodalFallback(endpoint: String, apiKey: String, base64Audio: String, mode: String): Result<String> {
        return try {
            val url = "$endpoint?key=$apiKey"

            val promptText = if (mode.equals("verbatim", ignoreCase = true)) {
                "Transcribe this audio file word-for-word exactly as spoken. Output only the transcript."
            } else {
                "You are an AI voice dictation tool like Wispr Flow. Transcribe this audio recording into clean, ready-to-use text. " +
                "Remove filler words (um, uh, like, you know), resolve any spoken self-corrections, and format numbers, punctuation, and bullet points if requested. " +
                "Output ONLY the transcribed text. Do NOT add any preamble, conversational commentary, or markdown formatting."
            }

            val jsonBody = JSONObject().apply {
                val contents = JSONArray()
                val contentObj = JSONObject()
                val parts = JSONArray()

                // Audio part
                parts.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/mp4")
                        put("data", base64Audio)
                    })
                })

                // Prompt part
                parts.put(JSONObject().apply {
                    put("text", promptText)
                })

                contentObj.put("parts", parts)
                contents.put(contentObj)
                put("contents", contents)

                put("generation_config", JSONObject().apply {
                    put("temperature", 0.0)
                })
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                Log.d(TAG, "Fallback response code: ${response.code}, body: ${bodyString.take(300)}")

                if (!response.isSuccessful) {
                    val errorMsg = extractErrorMessage(bodyString, response.code)
                    return Result.failure(Exception(errorMsg))
                }

                parseCandidatesText(bodyString)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing fallback request", e)
            Result.failure(e)
        }
    }

    /**
     * Extracts text from Gemini API response candidates with comprehensive checks.
     */
    private fun parseCandidatesText(bodyString: String): Result<String> {
        return try {
            val responseJson = JSONObject(bodyString)

            // Check for prompt feedback block
            val promptFeedback = responseJson.optJSONObject("promptFeedback")
            val blockReason = promptFeedback?.optString("blockReason")
            if (!blockReason.isNullOrBlank() && blockReason != "BLOCK_REASON_UNSPECIFIED") {
                return Result.failure(Exception("Audio prompt was blocked by safety filters ($blockReason)"))
            }

            val candidates = responseJson.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)

                // Check finish reason
                val finishReason = firstCandidate.optString("finishReason", "")
                if (finishReason == "SAFETY") {
                    return Result.failure(Exception("Transcription blocked by safety filters."))
                }

                val content = firstCandidate.optJSONObject("content")
                val resParts = content?.optJSONArray("parts")
                if (resParts != null && resParts.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until resParts.length()) {
                        val part = resParts.getJSONObject(i)
                        val text = part.optString("text", "")
                        if (text.isNotBlank()) {
                            sb.append(text)
                        }
                    }
                    val finalResult = sb.toString().trim()
                    if (finalResult.isNotBlank()) {
                        return Result.success(finalResult)
                    }
                }
            }

            // Direct text fallback
            val directText = responseJson.optString("text", "").trim()
            if (directText.isNotBlank()) {
                return Result.success(directText)
            }

            Result.failure(Exception("No speech detected in audio recording. Please speak louder or closer to the microphone."))
        } catch (e: Exception) {
            Result.failure(Exception("Failed to parse API response: ${e.message}"))
        }
    }

    /**
     * Validates user API key with informative diagnostic messages.
     */
    suspend fun testApiKey(apiKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            throw IllegalStateException("No API key configured.")
        }

        try {
            // Test against primary endpoint
            val url = "$PRIMARY_TRANSCRIBE_ENDPOINT?key=$trimmedKey"

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

                put("generationConfig", JSONObject().apply {
                    put("audioTranscriptionConfig", JSONObject().apply {
                        put("mode", "SMART")
                    })
                })
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    return@withContext Result.success(true)
                }

                // Check for invalid API key
                val isKeyInvalid = response.code == 403 ||
                        (response.code == 400 && (
                            bodyString.contains("API_KEY_INVALID", ignoreCase = true) ||
                            bodyString.contains("API key not valid", ignoreCase = true) ||
                            bodyString.contains("API_KEY", ignoreCase = true)
                        ))

                if (isKeyInvalid) {
                    val errorMsg = extractErrorMessage(bodyString, response.code)
                    return@withContext Result.failure(Exception("API Key Invalid: $errorMsg"))
                }

                // If 400 with audio length validation, the key is authentic!
                if (response.code == 400) {
                    Log.d(TAG, "API key authenticated successfully by Google API Gateway.")
                    return@withContext Result.success(true)
                }

                // If 404 (gemini-3.5-transcribe preview not yet enabled on this key), check with fallback endpoint!
                if (response.code == 404) {
                    val fallbackCheck = testKeyWithFallback(trimmedKey)
                    return@withContext fallbackCheck
                }

                val errorMsg = extractErrorMessage(bodyString, response.code)
                Result.failure(Exception("Validation failed (${response.code}): $errorMsg"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun testKeyWithFallback(apiKey: String): Result<Boolean> {
        return try {
            val url = "$FALLBACK_TRANSCRIBE_ENDPOINT?key=$apiKey"
            val jsonBody = JSONObject().apply {
                val contents = JSONArray()
                val contentObj = JSONObject()
                val parts = JSONArray().apply {
                    put(JSONObject().apply { put("text", "ping") })
                }
                contentObj.put("parts", parts)
                contents.put(contentObj)
                put("contents", contents)
            }
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 200) {
                    Result.success(true)
                } else {
                    val body = response.body?.string() ?: ""
                    Result.failure(Exception(extractErrorMessage(body, response.code)))
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
            val message = errorObj?.optString("message")
            when {
                !message.isNullOrBlank() -> message
                statusCode == 404 -> "Model endpoint not found (HTTP 404). Please verify API access."
                statusCode == 403 -> "Access forbidden (HTTP 403). Ensure Generative Language API is enabled in your Google Cloud / AI Studio project."
                statusCode == 429 -> "Rate limit exceeded (HTTP 429). Please wait a moment before trying again."
                else -> "HTTP $statusCode: $bodyString"
            }
        } catch (e: Exception) {
            "HTTP $statusCode: $bodyString"
        }
    }
}
