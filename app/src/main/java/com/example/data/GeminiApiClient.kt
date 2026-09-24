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
 * Primary Model: gemini-3.5-transcribe via the Generate Content API.
 * Fallback Models: general multimodal Gemini Flash models with explicit dictation prompts.
 */
class GeminiApiClient {

    class NoSpeechDetectedException : Exception(
        "No speech detected in the recording. Tap the mic and try again."
    )

    companion object {
        private const val TAG = "GeminiApiClient"
        private const val PRIMARY_TRANSCRIBE_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-transcribe:generateContent"
        private const val FALLBACK_TRANSCRIBE_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
        private const val SECONDARY_FALLBACK_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

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

    suspend fun transcribeAudio(context: Context, audioFile: File, mode: String = "smart"): Result<String> {
        val apiKey = SecurePreferences(context).getApiKey()
        if (apiKey.isBlank()) throw IllegalStateException("No API key configured.")
        return transcribeAudio(apiKey, audioFile, mode)
    }

    suspend fun transcribeAudio(apiKey: String, audioFile: File, mode: String = "smart"): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw IllegalStateException("No API key configured.")
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(IllegalStateException("Audio capture buffer is empty (0 bytes). Check microphone permissions."))
        }

        DiagnosticLog.add("Transcription started (mode: ${mode.lowercase()})")
        try {
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val primaryResult = executePrimaryTranscribeRequest(apiKey, base64Audio, mode)
            if (primaryResult.isSuccess) {
                val text = primaryResult.getOrNull() ?: ""
                if (text.isNotBlank()) {
                    DiagnosticLog.add("Primary model succeeded: gemini-3.5-transcribe")
                    return@withContext Result.success(text)
                }
            }

            val primaryError = primaryResult.exceptionOrNull()?.message ?: "empty output"
            DiagnosticLog.add("Primary model failed: ${primaryError.take(120)}")

            val fallbackResult = executeMultimodalFallback(FALLBACK_TRANSCRIBE_ENDPOINT, apiKey, base64Audio, mode)
            if (fallbackResult.isSuccess) {
                val text = fallbackResult.getOrNull() ?: ""
                if (text.isNotBlank()) {
                    DiagnosticLog.add("Fallback succeeded: gemini-3.8-flash")
                    return@withContext Result.success(text)
                }
            }
            DiagnosticLog.add("Fallback failed: ${fallbackResult.exceptionOrNull()?.message?.take(120) ?: "empty output"}")

            val secondaryResult = executeMultimodalFallback(SECONDARY_FALLBACK_ENDPOINT, apiKey, base64Audio, mode)
            if (secondaryResult.isSuccess) {
                val text = secondaryResult.getOrNull() ?: ""
                if (text.isNotBlank()) {
                    DiagnosticLog.add("Secondary fallback succeeded: gemini-2.5-flash")
                    return@withContext Result.success(text)
                }
            }
            DiagnosticLog.add("Secondary fallback failed: ${secondaryResult.exceptionOrNull()?.message?.take(120) ?: "empty output"}")

            val allAttemptsFoundNoSpeech = isNoSpeechDetected(primaryResult) &&
                    isNoSpeechDetected(fallbackResult) &&
                    isNoSpeechDetected(secondaryResult)

            if (allAttemptsFoundNoSpeech) {
                DiagnosticLog.add("No speech detected across all transcription attempts")
                return@withContext Result.failure(NoSpeechDetectedException())
            }

            val finalError = listOf(primaryResult, fallbackResult, secondaryResult)
                .mapNotNull { it.exceptionOrNull() }
                .firstOrNull { it !is NoSpeechDetectedException }
                ?.message ?: "Transcription failed."
            DiagnosticLog.add("Transcription failed: ${finalError.take(120)}")
            Result.failure(Exception(finalError))
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe audio request failed with exception", e)
            DiagnosticLog.add("Transcription exception: ${e.message?.take(120) ?: "unknown error"}")
            Result.failure(e)
        }
    }

    private fun executePrimaryTranscribeRequest(apiKey: String, base64Audio: String, mode: String): Result<String> {
        return try {
            val jsonBody = JSONObject().apply {
                val contents = JSONArray()
                val contentObj = JSONObject()
                val parts = JSONArray()
                parts.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/mp4")
                        put("data", base64Audio)
                    })
                })
                contentObj.put("parts", parts)
                contents.put(contentObj)
                put("contents", contents)
                val modeType = if (mode.equals("verbatim", ignoreCase = true)) "VERBATIM" else "SMART"
                put("generationConfig", JSONObject().apply {
                    put("audioTranscriptionConfig", JSONObject().apply { put("mode", modeType) })
                })
            }

            val request = Request.Builder()
                .url("$PRIMARY_TRANSCRIBE_ENDPOINT?key=$apiKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                Log.d(TAG, "Primary response code: ${response.code}, body: ${bodyString.take(300)}")
                if (!response.isSuccessful) {
                    return Result.failure(Exception(extractErrorMessage(bodyString, response.code)))
                }
                parseCandidatesText(bodyString)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing primary transcribe request", e)
            Result.failure(e)
        }
    }

    private fun executeMultimodalFallback(endpoint: String, apiKey: String, base64Audio: String, mode: String): Result<String> {
        return try {
            val promptText = if (mode.equals("verbatim", ignoreCase = true)) {
                "Transcribe this audio file word-for-word exactly as spoken. Preserve filler words, repetitions, false starts, and the speaker's wording. " +
                        "If the audio contains no intelligible speech, return an empty response and nothing else. " +
                        "Do not clean up, summarize, paraphrase, infer missing words, or add commentary. Output ONLY the transcript."
            } else {
                "You are a voice dictation transcription engine. Listen to the audio and transcribe the user's spoken words into clean, ready-to-use text. " +
                        "Remove filler words, stutters, and obvious false starts. Resolve spoken self-corrections while preserving the user's final intended wording. " +
                        "Preserve names, technical terms, URLs, email addresses, and other meaningful details. Apply natural punctuation, capitalization, " +
                        "and formatting when clearly indicated by the speech. Do not summarize, paraphrase, invent, or describe the audio. " +
                        "If the audio contains no intelligible speech, return an empty response and nothing else. " +
                        "Output ONLY the final dictated text. Do NOT add a preamble, explanation, commentary, or markdown wrapper."
            }

            val jsonBody = JSONObject().apply {
                val contents = JSONArray()
                val contentObj = JSONObject()
                val parts = JSONArray()
                parts.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/mp4")
                        put("data", base64Audio)
                    })
                })
                parts.put(JSONObject().apply { put("text", promptText) })
                contentObj.put("parts", parts)
                contents.put(contentObj)
                put("contents", contents)
                put("generationConfig", JSONObject().apply { put("temperature", 0.0) })
            }

            val request = Request.Builder()
                .url("$endpoint?key=$apiKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                Log.d(TAG, "Fallback response code: ${response.code}, body: ${bodyString.take(300)}")
                if (!response.isSuccessful) {
                    return Result.failure(Exception(extractErrorMessage(bodyString, response.code)))
                }
                parseCandidatesText(bodyString)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing fallback request", e)
            Result.failure(e)
        }
    }

    private fun parseCandidatesText(bodyString: String): Result<String> {
        return try {
            val responseJson = JSONObject(bodyString)
            val promptFeedback = responseJson.optJSONObject("promptFeedback")
            val blockReason = promptFeedback?.optString("blockReason")
            if (!blockReason.isNullOrBlank() && blockReason != "BLOCK_REASON_UNSPECIFIED") {
                return Result.failure(Exception("Audio prompt was blocked by safety filters ($blockReason)"))
            }
            val candidates = responseJson.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val finishReason = firstCandidate.optString("finishReason", "")
                if (finishReason == "SAFETY") return Result.failure(Exception("Transcription blocked by safety filters."))
                val content = firstCandidate.optJSONObject("content")
                val resParts = content?.optJSONArray("parts")
                if (resParts != null && resParts.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until resParts.length()) {
                        val text = resParts.getJSONObject(i).optString("text", "")
                        if (text.isNotBlank()) sb.append(text)
                    }
                    val finalResult = sb.toString().trim()
                    if (finalResult.isNotBlank()) return Result.success(finalResult)
                }
            }
            val directText = responseJson.optString("text", "").trim()
            if (directText.isNotBlank()) return Result.success(directText)
            Result.failure(NoSpeechDetectedException())
        } catch (e: Exception) {
            Result.failure(Exception("Failed to parse API response: ${e.message}"))
        }
    }

    suspend fun testApiKey(apiKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) throw IllegalStateException("No API key configured.")
        try {
            val jsonBody = JSONObject().apply {
                val contents = JSONArray()
                val contentObj = JSONObject()
                val parts = JSONArray()
                parts.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/mp4")
                        put("data", MINIMAL_VERIFICATION_AUDIO_BASE64)
                    })
                })
                contentObj.put("parts", parts)
                contents.put(contentObj)
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("audioTranscriptionConfig", JSONObject().apply { put("mode", "SMART") })
                })
            }
            val request = Request.Builder()
                .url("$PRIMARY_TRANSCRIBE_ENDPOINT?key=$trimmedKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful) return@withContext Result.success(true)
                val isKeyInvalid = response.code == 403 || (response.code == 400 && (
                        bodyString.contains("API_KEY_INVALID", ignoreCase = true) ||
                                bodyString.contains("API key not valid", ignoreCase = true) ||
                                bodyString.contains("API_KEY", ignoreCase = true)
                        ))
                if (isKeyInvalid) return@withContext Result.failure(Exception("API Key Invalid: ${extractErrorMessage(bodyString, response.code)}"))
                if (response.code == 400) return@withContext Result.success(true)
                if (response.code == 404) return@withContext testKeyWithFallback(trimmedKey)
                Result.failure(Exception("Validation failed (${response.code}): ${extractErrorMessage(bodyString, response.code)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun testKeyWithFallback(apiKey: String): Result<Boolean> {
        return try {
            val jsonBody = JSONObject().apply {
                val contents = JSONArray()
                val contentObj = JSONObject()
                val parts = JSONArray().apply { put(JSONObject().apply { put("text", "ping") }) }
                contentObj.put("parts", parts)
                contents.put(contentObj)
                put("contents", contents)
            }
            val request = Request.Builder()
                .url("$FALLBACK_TRANSCRIBE_ENDPOINT?key=$apiKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 200) Result.success(true)
                else Result.failure(Exception(extractErrorMessage(response.body?.string() ?: "", response.code)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isNoSpeechDetected(result: Result<String>): Boolean =
        result.exceptionOrNull() is NoSpeechDetectedException

    private fun extractErrorMessage(bodyString: String, statusCode: Int): String {
        return try {
            val json = JSONObject(bodyString)
            val error = json.optJSONObject("error")
            val message = error?.optString("message")
            if (!message.isNullOrBlank()) return "HTTP $statusCode: $message"
            "HTTP $statusCode: Request failed"
        } catch (_: Exception) {
            "HTTP $statusCode: Request failed"
        }
    }
}
