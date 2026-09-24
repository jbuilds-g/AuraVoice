package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.FlowApplication
import com.example.MainActivity
import com.example.R
import com.example.audio.AudioCaptureEngine
import com.example.data.GeminiApiClient
import com.example.data.SecurePreferences
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class OverlayState {
    IDLE,
    RECORDING,
    PROCESSING,
    SUCCESS,
    ERROR
}

/**
 * OverlayService renders the floating microphone button using Material 3 Expressive dynamic tokens:
 * - Dynamic Visibility: Automatically hides (View.GONE) when not typing, and appears (View.VISIBLE) when node.isEditable == true.
 * - Draggable Overlay: Real-time pointerInput drag tracking across the display.
 * - Dynamic API Key Security: Loads user key purely from EncryptedSharedPreferences without hardcoded secrets.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001

        private val _overlayState = MutableStateFlow(OverlayState.IDLE)
        val overlayState: StateFlow<OverlayState> = _overlayState.asStateFlow()

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _isEditableFocused = MutableStateFlow(false)
        val isEditableFocused: StateFlow<Boolean> = _isEditableFocused.asStateFlow()

        @Volatile
        private var activeServiceInstance: OverlayService? = null

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.stopService(intent)
        }

        /**
         * Invoked by AuraAccessibilityService when focus changes.
         * Sets floating overlay button to VISIBLE only when node.isEditable == true.
         * Automatically sets overlay visibility to GONE when no editable field is focused.
         */
        fun updateEditableFocusState(isEditable: Boolean) {
            _isEditableFocused.value = isEditable
            activeServiceInstance?.applyVisibilityRules()
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    private lateinit var securePreferences: SecurePreferences
    private lateinit var audioCaptureEngine: AudioCaptureEngine
    private val geminiApiClient = GeminiApiClient()

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        _isServiceRunning.value = true
        _overlayState.value = OverlayState.IDLE

        securePreferences = SecurePreferences(this)
        audioCaptureEngine = AudioCaptureEngine(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startForeground(NOTIFICATION_ID, buildNotification())
        initOverlayView()
        Log.d(TAG, "OverlayService active.")
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, FlowApplication.OVERLAY_CHANNEL_ID)
            .setContentTitle("AuraVoice Dictation Active")
            .setContentText("Appears automatically when typing. Tap to dictate.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun initOverlayView() {
        lifecycleOwner = OverlayLifecycleOwner().apply {
            onCreate()
            onStart()
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 500
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)

            setContent {
                MyApplicationTheme {
                    val state by _overlayState.collectAsState()
                    DraggableFloatingMicButton(
                        state = state,
                        onDragDelta = { dx, dy ->
                            layoutParams.x += dx.toInt()
                            layoutParams.y += dy.toInt()
                            try {
                                windowManager.updateViewLayout(this, layoutParams)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed updating overlay layout position", e)
                            }
                        },
                        onClick = { onMicButtonClicked() }
                    )
                }
            }
        }

        try {
            windowManager.addView(view, layoutParams)
            composeView = view
            applyVisibilityRules()
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay ComposeView to WindowManager", e)
        }
    }

    /**
     * Controls Dynamic Visibility:
     * - VISIBLE only when node.isEditable == true.
     * - Automatically set to GONE when no editable field is focused so it doesn't float around permanently.
     * - Preserves visibility during active RECORDING or PROCESSING sessions.
     */
    fun applyVisibilityRules() {
        mainHandler.post {
            val view = composeView ?: return@post

            if (_overlayState.value != OverlayState.IDLE) {
                view.visibility = View.VISIBLE
                return@post
            }

            val isEditable = _isEditableFocused.value
            view.visibility = if (isEditable) View.VISIBLE else View.GONE
        }
    }

    private fun onMicButtonClicked() {
        when (_overlayState.value) {
            OverlayState.IDLE -> startRecording()
            OverlayState.RECORDING -> stopRecordingAndProcess()
            OverlayState.PROCESSING -> {
                // Ignore click during model processing
            }
            OverlayState.SUCCESS, OverlayState.ERROR -> {
                _overlayState.value = OverlayState.IDLE
                applyVisibilityRules()
            }
        }
    }

    private fun triggerHaptic() {
        if (!securePreferences.isHapticEnabled()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(40)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun startRecording() {
        val apiKey = securePreferences.getApiKey()
        if (apiKey.isBlank()) {
            Toast.makeText(this, "No API key configured. Please set one in AuraVoice.", Toast.LENGTH_LONG).show()
            _overlayState.value = OverlayState.ERROR
            serviceScope.launch {
                delay(2000)
                _overlayState.value = OverlayState.IDLE
                applyVisibilityRules()
            }
            return
        }

        val result = audioCaptureEngine.startRecording()
        if (result.isSuccess) {
            triggerHaptic()
            _overlayState.value = OverlayState.RECORDING
            Log.d(TAG, "Recording started via floating overlay")
        } else {
            Toast.makeText(this, "Microphone error: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            _overlayState.value = OverlayState.ERROR
            serviceScope.launch {
                delay(1500)
                _overlayState.value = OverlayState.IDLE
                applyVisibilityRules()
            }
        }
    }

    private fun stopRecordingAndProcess() {
        triggerHaptic()
        _overlayState.value = OverlayState.PROCESSING
        val recordedFile = audioCaptureEngine.stopRecording()

        if (recordedFile == null) {
            Toast.makeText(this, "No audio captured", Toast.LENGTH_SHORT).show()
            _overlayState.value = OverlayState.ERROR
            serviceScope.launch {
                delay(1500)
                _overlayState.value = OverlayState.IDLE
                applyVisibilityRules()
            }
            return
        }

        serviceScope.launch {
            processAudioFile(recordedFile)
        }
    }

    private suspend fun processAudioFile(file: File) {
        val apiKey = securePreferences.getApiKey()
        val mode = securePreferences.getTranscriptionMode()

        val result = geminiApiClient.transcribeAudio(apiKey, file, mode)

        // Delete audio cache file after processing
        try {
            file.delete()
        } catch (ignored: Exception) {
        }

        if (result.isSuccess) {
            val text = (result.getOrNull() ?: "").trim()
            if (text.isBlank()) {
                Log.w(TAG, "Transcription returned empty string")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OverlayService, "AuraVoice: No speech detected in recording.", Toast.LENGTH_SHORT).show()
                }
                _overlayState.value = OverlayState.ERROR
                delay(1500)
                _overlayState.value = OverlayState.IDLE
                applyVisibilityRules()
                return
            }

            Log.d(TAG, "Transcription succeeded: $text")

            val accessibilityService = AuraAccessibilityService.instance
            if (accessibilityService != null) {
                accessibilityService.injectOrAppendTranscribedText(text)
                withContext(Dispatchers.Main) {
                    val preview = if (text.length > 35) "${text.take(35)}..." else text
                    Toast.makeText(this@OverlayService, "Dictated: \"$preview\"", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("AuraVoice", text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@OverlayService, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            }

            _overlayState.value = OverlayState.SUCCESS
            delay(1200)
            _overlayState.value = OverlayState.IDLE
            applyVisibilityRules()

        } else {
            val errorMsg = result.exceptionOrNull()?.message ?: "Transcription failed"
            Log.e(TAG, "Transcription error: $errorMsg")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@OverlayService, "Dictation Error: $errorMsg", Toast.LENGTH_LONG).show()
            }
            _overlayState.value = OverlayState.ERROR
            delay(2000)
            _overlayState.value = OverlayState.IDLE
            applyVisibilityRules()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeServiceInstance == this) {
            activeServiceInstance = null
        }
        _isServiceRunning.value = false
        _overlayState.value = OverlayState.IDLE
        serviceScope.cancel()

        audioCaptureEngine.cancelRecording()

        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        composeView = null

        lifecycleOwner?.onStop()
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null

        Log.d(TAG, "OverlayService stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@Composable
fun DraggableFloatingMicButton(
    state: OverlayState,
    onDragDelta: (Float, Float) -> Unit,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "overlay_pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when (state) {
            OverlayState.IDLE -> colorScheme.primaryContainer
            OverlayState.RECORDING -> colorScheme.error
            OverlayState.PROCESSING -> colorScheme.surfaceContainerHighest
            OverlayState.SUCCESS -> colorScheme.primary
            OverlayState.ERROR -> colorScheme.errorContainer
        },
        animationSpec = tween(250),
        label = "bg_color"
    )

    val borderColor by animateColorAsState(
        targetValue = when (state) {
            OverlayState.IDLE -> colorScheme.primary
            OverlayState.RECORDING -> colorScheme.errorContainer
            OverlayState.PROCESSING -> colorScheme.primary
            OverlayState.SUCCESS -> colorScheme.onPrimary
            OverlayState.ERROR -> colorScheme.error
        },
        animationSpec = tween(250),
        label = "border_color"
    )

    val contentColor = when (state) {
        OverlayState.IDLE -> colorScheme.onPrimaryContainer
        OverlayState.RECORDING -> colorScheme.onError
        OverlayState.PROCESSING -> colorScheme.primary
        OverlayState.SUCCESS -> colorScheme.onPrimary
        OverlayState.ERROR -> colorScheme.onErrorContainer
    }

    Box(
        modifier = Modifier
            .size(72.dp)
            .padding(6.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (state == OverlayState.RECORDING) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(pulseScale)
            ) {
                drawCircle(
                    color = colorScheme.error.copy(alpha = pulseAlpha),
                    radius = size.minDimension / 2f
                )
            }
        }

        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(
                    elevation = if (state == OverlayState.RECORDING) 12.dp else 6.dp,
                    shape = CircleShape,
                    spotColor = if (state == OverlayState.RECORDING) colorScheme.error else colorScheme.primary
                )
                .clip(CircleShape)
                .background(backgroundColor)
                .border(2.dp, borderColor, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(150))
                },
                label = "state_icon"
            ) { targetState ->
                when (targetState) {
                    OverlayState.IDLE -> {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = "AuraVoice Mic",
                            tint = contentColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    OverlayState.RECORDING -> {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = "Stop Recording",
                            tint = contentColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    OverlayState.PROCESSING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            color = colorScheme.primary,
                            strokeWidth = 2.5.dp,
                            strokeCap = StrokeCap.Round
                        )
                    }
                    OverlayState.SUCCESS -> {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Text Injected",
                            tint = contentColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    OverlayState.ERROR -> {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = "Error",
                            tint = contentColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}
