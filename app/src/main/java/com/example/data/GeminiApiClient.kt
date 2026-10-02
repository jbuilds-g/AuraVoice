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
        private const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        private const val MODEL_CACHE_MS = 6 * 60 * 60 * 1000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedModels: List<String>? = null
    @Volatile private var modelCacheTimestamp = 0L

    suspend fun transcribeAudio(context: Context, audioFile: File, mode: String = "smart"): Result<String> {
        val prefs = SecurePreferences(context)
        val apiKey = prefs.getApiKey()
        if (apiKey.isBlank()) throw IllegalStateException("No API key configured.")
        return transcribeAudio(apiKey, audioFile, mode, prefs.getSelectedModel())
    }

    suspend fun transcribeAudio(
        apiKey: String,
        audioFile: File,
        mode: String = "smart",
        selectedModel: String = DEFAULT_MODEL
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw IllegalStateException("No API key configured.")
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(
                IllegalStateException("Audio capture buffer is empty (0 bytes). Check microphone permissions.")
            )
        }

        DiagnosticLog.add("Transcription started (mode: ${mode.lowercase()})")
        try {
            val model = if (selectedModel == "auto") DEFAULT_MODEL else selectedModel.removePrefix("models/")
            DiagnosticLog.add("Using Gemini model: $model")
            val base64Audio = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
            val result = executeAudioRequest(model, apiKey, base64Audio, mode)

            if (result.isSuccess && result.getOrNull()?.trim().orEmpty().isNotBlank()) {
                return@withContext Result.success(result.getOrNull()!!.trim())
            }

            val error = result.exceptionOrNull()
            when (error) {
                is QuotaExceededException -> {
                    QuotaCooldownController.start(error.retryAfterSeconds)
                    DiagnosticLog.add("Quota cooldown started: ${error.retryAfterSeconds}s")
                }
                is NoSpeechDetectedException -> DiagnosticLog.add("No speech detected by $model")
                else -> DiagnosticLog.add("Transcription failed: ${(error?.message ?: "empty output").take(120)}")
            }
            Result.failure(error ?: Exception("Transcription failed."))
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe audio request failed", e)
            DiagnosticLog.add("Transcription exception: ${e.message?.take(120) ?: "unknown error"}")
            Result.failure(e)
        }
    }

    suspend fun getAvailableFlashModels(
        apiKey: String,
        forceRefresh: Boolean = false
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            Result.success(fetchFlashModels(apiKey, forceRefresh))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeAudioRequest(
        model: String,
        apiKey: String,
        base64Audio: String,
        mode: String
    ): Result<String> {
        return try {
            val isTranscribeModel = model == "gemini-3.5-transcribe"
            val promptText = if (mode.equals("verbatim", ignoreCase = true)) {
                """
                Transcribe the attached audio exactly as spoken.
                Preserve the speaker's words, including filler words, repetitions, false starts, slang, and informal phrasing.
                Do not clean up, rewrite, summarize, paraphrase, infer, or improve anything.
                Do not add or remove meaning.
                Return only the spoken transcript. Do not add labels, explanations, quotes, or commentary.
                If there is no intelligible speech, return an empty response.
                """.trimIndent()
            } else {
                """
                You are a high-accuracy voice dictation engine. The attached audio is raw speech that the user wants converted into polished text for immediate insertion into a text field.

                TRANSCRIPTION RULES:
                - First understand what the speaker actually said. Use the full audio and surrounding context to resolve words that were misheard or transcribed incorrectly.
                - Remove verbal filler that does not carry meaning, including "um", "uh", "like" when used as a filler, "you know", "I mean", and similar speech disfluencies.
                - Remove stutters, repeated words caused by hesitation, abandoned phrases, and false starts when the speaker clearly continues with a correction.
                - Keep intentional repetition when it is clearly part of the meaning or emphasis.
                - Apply natural grammar, punctuation, capitalization, paragraph breaks, and sentence boundaries.
                - Correct obvious speech-to-text mistakes using context, especially homophones and words that do not make sense in the surrounding sentence.
                - Preserve the speaker's intended wording and tone. Do not make the text sound more formal than the speaker intended.
                - Preserve names, usernames, product names, technical terminology, acronyms, URLs, email addresses, numbers, dates, and other specific details when they are clearly spoken.
                - Do not censor, sanitize, summarize, shorten, or rewrite the speaker's message.
                - Never invent facts, words, names, or details that are not supported by the audio.
                - If a phrase is genuinely unclear, use the most acoustically plausible interpretation supported by context rather than silently inventing a different idea.

                OUTPUT RULES:
                - Return ONLY the final polished dictated text.
                - Do not describe the audio or explain corrections.
                - Do not say "Here is the transcription" or add any other wrapper text.
                - Do not use quotation marks around the entire response unless the speaker actually dictated them.
                - If there is no intelligible speech, return an empty response.
                """.trimIndent()
            }

            val audioPart = JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "audio/mp4")
                    put("data", base64Audio)
                })
            }

            val parts = JSONArray().apply {
                put(audioPart)
                if (!isTranscribeModel) put(JSONObject().apply { put("text", promptText) })
            }

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply { put("parts", parts) })
                })
                if (isTranscribeModel) {
                    put("generationConfig", JSONObject().apply {
                        put("audioTranscriptionConfig", JSONObject().apply {
                            put("mode", mode.uppercase())
                        })
                    })
                }
            }

            val request = Request.Builder()
                .url("$API_BASE/models/$model:generateContent?key=$apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return Result.failure(
                        buildRequestException(bodyString, response.code, response.header("Retry-After"))
                    )
                }
                parseCandidatesText(bodyString)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseCandidatesText(bodyString: String): Result<String> {
        return try {
            val json = JSONObject(bodyString)
            val blockReason = json.optJSONObject("promptFeedback")?.optString("blockReason")
            if (!blockReason.isNullOrBlank() && blockReason != "BLOCK_REASON_UNSPECIFIED") {
                return Result.failure(Exception("Audio prompt was blocked by safety filters ($blockReason)"))
            }

            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val candidate = candidates.getJSONObject(0)
                if (candidate.optString("finishReason", "") == "SAFETY") {
                    return Result.failure(Exception("Transcription blocked by safety filters."))
                }
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null) {
                    val text = buildString {
                        for (i in 0 until parts.length()) {
                            append(parts.getJSONObject(i).optString("text", ""))
                        }
                    }.trim()
                    if (text.isNotBlank()) return Result.success(text)
                }
            }

            val direct = json.optString("text", "").trim()
            if (direct.isNotBlank()) Result.success(direct)
            else Result.failure(NoSpeechDetectedException())
        } catch (e: Exception) {
            Result.failure(Exception("Failed to parse API response: ${e.message}"))
        }
    }

    private fun fetchFlashModels(apiKey: String, forceRefresh: Boolean): List<String> {
        val now = System.currentTimeMillis()
        val cached = cachedModels
        if (!forceRefresh && cached != null && now - modelCacheTimestamp < MODEL_CACHE_MS) return cached

        val request = Request.Builder()
            .url("$MODELS_ENDPOINT?key=$apiKey")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception(extractErrorMessage(body, response.code))

            val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
            val candidates = mutableListOf<String>()
            for (i in 0 until models.length()) {
                val model = models.optJSONObject(i) ?: continue
                val name = model.optString("name").removePrefix("models/")
                val methods = model.optJSONArray("supportedGenerationMethods")
                val supports = methods?.let { a ->
                    (0 until a.length()).any { a.optString(it) == "generateContent" }
                } == true
                if (supports && isSupportedDictationModel(name)) candidates += name
            }

            val sorted = candidates
                .distinct()
                .sortedWith(compareByDescending<String> { dictationModelPriority(it) }.thenBy { it })

            cachedModels = sorted
            modelCacheTimestamp = now
            return sorted
        }
    }

    private fun isSupportedDictationModel(name: String): Boolean {
        val normalized = name.lowercase()
        if (normalized == "gemini-3.5-transcribe") return true
        return normalized.matches(Regex("gemini-\\d+(?:\\.\\d+)*-flash(?:-lite)?")) &&
            !normalized.contains("preview") &&
            !normalized.contains("exp")
    }

    private fun dictationModelPriority(name: String): Long {
        val normalized = name.lowercase()
        if (normalized == "gemini-3.5-transcribe") return 35000L

        val match = Regex("gemini-(\\d+)(?:\\.(\\d+))?-flash(?:-lite)?").find(normalized) ?: return 0L
        val major = match.groupValues[1].toLongOrNull() ?: 0L
        val minor = match.groupValues[2].toLongOrNull() ?: 0L
        val liteBonus = if (normalized.endsWith("-lite")) 1L else 0L
        return major * 10000L + minor * 100L + liteBonus
    }

    suspend fun testApiKey(apiKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Result.success(fetchFlashModels(apiKey.trim(), true).isNotEmpty())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildRequestException(bodyString: String, statusCode: Int, retryAfterHeader: String?): Exception {
        if (statusCode == 429) {
            return QuotaExceededException(
                parseRetryDelay(bodyString, retryAfterHeader),
                "HTTP 429: Gemini rate limit or quota exceeded."
            )
        }
        return Exception(extractErrorMessage(bodyString, statusCode))
    }

    private fun parseRetryDelay(bodyString: String, retryAfterHeader: String?): Long {
        retryAfterHeader?.toLongOrNull()?.let { return it.coerceIn(1L, 60L) }
        return try {
            val details = JSONObject(bodyString)
                .optJSONObject("error")
                ?.optJSONArray("details")
            for (i in 0 until (details?.length() ?: 0)) {
                val detail = details?.optJSONObject(i) ?: continue
                if (detail.optString("@type").contains("RetryInfo")) {
                    Regex("(\\d+(?:\\.\\d+)?)s")
                        .find(detail.optString("retryDelay"))
                        ?.let { return it.groupValues[1].toDouble().toLong().coerceIn(1L, 60L) }
                }
            }
            30L
        } catch (_: Exception) {
            30L
        }
    }

    private fun extractErrorMessage(bodyString: String, statusCode: Int): String = try {
        val message = JSONObject(bodyString).optJSONObject("error")?.optString("message")
        if (!message.isNullOrBlank()) "HTTP $statusCode: $message" else "HTTP $statusCode: Request failed"
    } catch (_: Exception) {
        "HTTP $statusCode: Request failed"
    }
}