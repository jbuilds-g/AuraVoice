package com.example.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioCaptureEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioCaptureEngine"
        private const val FILE_NAME = "wispr_flow_input.m4a"
        const val SAMPLE_RATE_HZ = 16000
    }

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    var isRecording: Boolean = false
        private set

    /**
     * Starts audio capture with 16kHz mono AAC/m4a encoding into app cache directory.
     */
    @Synchronized
    fun startRecording(): Result<File> {
        if (isRecording) {
            stopRecording()
        }

        return try {
            val file = File(context.cacheDir, FILE_NAME)
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
            Log.d(TAG, "Recording started -> ${file.absolutePath}")
            Result.success(file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Stops audio capture and returns the generated m4a audio file.
     */
    @Synchronized
    fun stopRecording(): File? {
        if (!isRecording) return null

        return try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: RuntimeException) {
                    // Happens if stopped immediately after start
                    Log.w(TAG, "MediaRecorder stop called prematurely", e)
                }
                release()
            }
            isRecording = false
            mediaRecorder = null

            val file = outputFile
            if (file != null && file.exists() && file.length() > 0) {
                Log.d(TAG, "Recording completed. File size: ${file.length()} bytes")
                file
            } else {
                Log.w(TAG, "Recording stopped but output file is empty or missing")
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
