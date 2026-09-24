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

class GeminiApiClient {
    class NoSpeechDetectedException : Exception("No speech detected in the recording. Tap the mic and try again.")
    class QuotaExceededException(val retryAfterSeconds: Long, message: String) : Exception(message)

    companion object {
        private const val TAG = "GeminiApiClient"
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val MODELS_ENDPOINT = "$API_BASE/models"
        private const val DEFAULT_MODEL = "gemini-3.8-flash"
        private const val MODEL_CACHE_MS = 6 * 60 * 60 * 1000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedModel: String? = null
    @Volatile private var modelCacheTimestamp = 0L

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
            val model = discoverLatestFlashModel(apiKey)
            DiagnosticLog.add("Using Gemini model: $model")

            val base64Audio = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
            val result = executeMultimodalRequest(model, apiKey, base64Audio, mode)

            if (result.isSuccess) {
                val text = result.getOrNull()?.trim().orEmpty()
                if (text.isNotBlank()) {
                    DiagnosticLog.add("Transcription succeeded: $model")
                    return@withContext Result.success(text)
                }
            }

            val error = result.exceptionOrNull()
            when (error) {
                is QuotaExceededException -> {
                    QuotaCooldownController.start(error.retryAfterSeconds)
                    DiagnosticLog.add("Quota cooldown started: ${error.retryAfterSeconds}s")
                }
                is NoSpeechDetectedException -> {
                    DiagnosticLog.add("No speech detected by $model")
                }
                else -> {
                    DiagnosticLog.add("Transcription failed: ${(error?.message ?: "empty output").take(120)}")
                }
            }

            Result.failure(error ?: Exception("Transcription failed."))
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe audio request failed", e)
            DiagnosticLog.add("Transcription exception: ${e.message?.take(120) ?: "unknown error"}")
            Result.failure(e)
        }
    }

    private fun executeMultimodalRequest(model: String, apiKey: String, base64Audio: String, mode: String): Result<String> {
        return try {
            val promptText = if (mode.equals("verbatim", ignoreCase = true)) {
                "Transcribe this audio exactly as spoken. Preserve filler words, repetitions, false starts, and the speaker's wording. " +
                        "Do not clean up, summarize, paraphrase, infer missing words, or add commentary. " +
                        "If there is no intelligible speech, return an empty response. Output ONLY the transcript."
            } else {
                "You are a voice dictation transcription engine. Listen carefully to the audio and convert the user's speech into clean, ready-to-use text. " +
                        "Remove filler words, stutters, and obvious false starts. Resolve spoken self-corrections while preserving the user's final intended wording. " +
                        "Fix grammar, punctuation, capitalization, sentence structure, and obvious transcription mistakes. " +
                        "Preserve names, technical terms, URLs, email addresses, numbers, and other meaningful details exactly when they are clearly spoken. " +
                        "Do not summarize, paraphrase, invent, explain, or describe the audio. Do not add information. " +
                        "If there is no intelligible speech, return an empty response. Output ONLY the final dictated text."
            }

            val parts = JSONArray().apply {
                put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/mp4")
                        put("data", base64Audio)
                    })
                })
                put(JSONObject().apply { put("text", promptText) })
            }
            val contents = JSONArray().apply {
                put(JSONObject().apply { put("parts", parts) })
            }
            val jsonBody = JSONObject().apply { put("contents", contents) }

            val request = Request.Builder()
                .url("$API_BASE/models/$model:generateContent?key=$apiKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                Log.d(TAG, "Gemini $model response code: ${response.code}, body: ${bodyString.take(300)}")
                if (!response.isSuccessful) {
                    return Result.failure(buildRequestException(bodyString, response.code, response.header("Retry-After")))
                }
                parseCandidatesText(bodyString)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Gemini multimodal request", e)
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
                if (firstCandidate.optString("finishReason", "") == "SAFETY") {
                    return Result.failure(Exception("Transcription blocked by safety filters."))
                }
                val resParts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
                if (resParts != null) {
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

    private fun discoverLatestFlashModel(apiKey: String): String {
        val now = System.currentTimeMillis()
        val cached = cachedModel
        if (cached != null && now - modelCacheTimestamp < MODEL_CACHE_MS) return cached

        return try {
            val request = Request.Builder()
                .url("$MODELS_ENDPOINT?key=$apiKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w(TAG, "Models API returned ${response.code}; using cached/default model")
                    return@use cached ?: DEFAULT_MODEL
                }

                val models = JSONObject(bodyString).optJSONArray("models") ?: JSONArray()
                val candidates = mutableListOf<String>()
                for (i in 0 until models.length()) {
                    val model = models.optJSONObject(i) ?: continue
                    val name = model.optString("name").removePrefix("models/")
                    val methods = model.optJSONArray("supportedGenerationMethods")
                    val supportsGenerateContent = methods?.let { array ->
                        (0 until array.length()).any { array.optString(it) == "generateContent" }
                    } == true
                    if (supportsGenerateContent && isStableFlashModel(name)) candidates += name
                }

                val selected = candidates.maxWithOrNull(
                    compareBy<String> { flashModelVersion(it) }.thenBy { it }
                ) ?: DEFAULT_MODEL
                cachedModel = selected
                modelCacheTimestamp = now
                Log.i(TAG, "Latest stable Flash model selected: $selected")
                selected
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to discover Gemini models; using ${cached ?: DEFAULT_MODEL}", e)
            cached ?: DEFAULT_MODEL
        }
    }

    private fun isStableFlashModel(name: String): Boolean {
        val normalized = name.lowercase()
        if (!normalized.matches(Regex("gemini-\\d+(?:\\.\\d+)*-flash(?:-lite)?"))) return false
        if (normalized.contains("preview") || normalized.contains("exp")) return false
        return true
    }

    private fun flashModelVersion(name: String): Long {
        val match = Regex("gemini-(\\d+)(?:\\.(\\d+))?-flash").find(name.lowercase()) ?: return 0L
        val major = match.groupValues[1].toLongOrNull() ?: 0L
        val minor = match.groupValues[2].toLongOrNull() ?: 0L
        return major * 1000L + minor
    }

    suspend fun testApiKey(apiKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) throw IllegalStateException("No API key configured.")
        try {
            val request = Request.Builder()
                .url("$MODELS_ENDPOINT?key=$trimmedKey")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val models = JSONObject(bodyString).optJSONArray("models")
                    val hasFlash = models != null && (0 until models.length()).any { i ->
                        val model = models.optJSONObject(i) ?: return@any false
                        val name = model.optString("name").removePrefix("models/")
                        isStableFlashModel(name)
                    }
                    if (hasFlash) return@withContext Result.success(true)
                    return@withContext Result.failure(Exception("API key is valid, but no compatible Gemini Flash model is available."))
                }
                val isKeyInvalid = response.code == 403 || (response.code == 400 && (
                        bodyString.contains("API_KEY_INVALID", ignoreCase = true) ||
                                bodyString.contains("API key not valid", ignoreCase = true) ||
                                bodyString.contains("API_KEY", ignoreCase = true)
                        ))
                if (isKeyInvalid) return@withContext Result.failure(Exception("API Key Invalid: ${extractErrorMessage(bodyString, response.code)}"))
                Result.failure(Exception("Validation failed (${response.code}): ${extractErrorMessage(bodyString, response.code)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildRequestException(bodyString: String, statusCode: Int, retryAfterHeader: String?): Exception {
        if (statusCode == 429) {
            val retrySeconds = parseRetryDelay(bodyString, retryAfterHeader)
            return QuotaExceededException(retrySeconds, "HTTP 429: Gemini rate limit or quota exceeded. Try again in about ${retrySeconds}s.")
        }
        return Exception(extractErrorMessage(bodyString, statusCode))
    }

    private fun parseRetryDelay(bodyString: String, retryAfterHeader: String?): Long {
        retryAfterHeader?.toLongOrNull()?.let { return it.coerceIn(1L, 60L) }
        return try {
            val json = JSONObject(bodyString)
            val details = json.optJSONObject("error")?.optJSONArray("details")
            if (details != null) {
                for (i in 0 until details.length()) {
                    val detail = details.optJSONObject(i) ?: continue
                    if (detail.optString("@type").contains("RetryInfo")) {
                        val retryDelay = detail.optString("retryDelay")
                        val match = Regex("(\\d+(?:\\.\\d+)?)s").find(retryDelay)
                        if (match != null) return match.groupValues[1].toDouble().toLong().coerceIn(1L, 60L)
                    }
                }
            }
            30L
        } catch (_: Exception) {
            30L
        }
    }

    private fun extractErrorMessage(bodyString: String, statusCode: Int): String {
        return try {
            val json = JSONObject(bodyString)
            val message = json.optJSONObject("error")?.optString("message")
            if (!message.isNullOrBlank()) return "HTTP $statusCode: $message"
            "HTTP $statusCode: Request failed"
        } catch (_: Exception) {
            "HTTP $statusCode: Request failed"
        }
    }
}
