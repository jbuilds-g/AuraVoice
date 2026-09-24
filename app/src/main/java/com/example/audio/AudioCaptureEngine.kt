package com.example.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

class AudioCaptureEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioCaptureEngine"
        const val SAMPLE_RATE_HZ = 16000
    }

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordingStartTimeMs: Long = 0L

    var isRecording: Boolean = false
        private set

    /**
     * Starts audio capture with 16kHz mono AAC/m4a encoding into app cache directory.
     * Uses unique timestamped files to prevent caching or concurrency issues.
     */
    @Synchronized
    fun startRecording(): Result<File> {
        if (isRecording) {
            stopRecording()
        }

        return try {
            val fileName = "aura_audio_${System.currentTimeMillis()}.m4a"
            val file = File(context.cacheDir, fileName)
            if (file.exists()) {
                file.delete()
            }
            outputFile = file

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioChannels(1) // Mono
                setAudioEncodingBitRate(64000) // 64 kbps clean voice bitrate
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            recordingStartTimeMs = SystemClock.elapsedRealtime()
            Log.d(TAG, "Audio recording successfully started -> ${file.absolutePath}")
            Result.success(file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            cleanup()
            Result.failure(Exception("Could not initialize microphone: ${e.message}"))
        }
    }

    /**
     * Stops audio capture and returns the generated m4a audio file.
     * Guarantees a minimum recording duration so MediaRecorder can flush AAC frames without throwing.
     */
    @Synchronized
    fun stopRecording(): File? {
        if (!isRecording) return null

        return try {
            val elapsedMs = SystemClock.elapsedRealtime() - recordingStartTimeMs
            // MediaRecorder requires at least ~500ms of recorded data, otherwise stop() throws
            if (elapsedMs < 600) {
                val sleepNeeded = 600 - elapsedMs
                try {
                    Thread.sleep(sleepNeeded)
                } catch (ignored: InterruptedException) {
                }
            }

            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "MediaRecorder stop called prematurely or failed", e)
                }
                release()
            }
            isRecording = false
            mediaRecorder = null

            val file = outputFile
            if (file != null && file.exists() && file.length() > 0) {
                Log.d(TAG, "Recording completed. File: ${file.name}, Size: ${file.length()} bytes")
                file
            } else {
                Log.w(TAG, "Recording stopped but output file is empty (0 bytes) or missing")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recorder", e)
            cleanup()
            null
        }
    }

    @Synchronized
    fun cancelRecording() {
        cleanup()
    }

    fun getMaxAmplitude(): Int {
        return try {
            if (isRecording) {
                mediaRecorder?.maxAmplitude ?: 0
            } else 0
        } catch (e: Exception) {
            0
        }
    }

    private fun cleanup() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            // ignore
        }
        mediaRecorder = null
        isRecording = false
        outputFile?.let {
            if (it.exists()) it.delete()
        }
        outputFile = null
    }
}
