package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.components.AboutScreen
import com.example.ui.components.ApiKeySection
import com.example.ui.components.HeroStatusBar
import com.example.ui.components.VoiceSandboxSection
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                var showAbout by remember { mutableStateOf(false) }

                val audioPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    viewModel.refreshPermissionStates()
                    if (!isGranted) {
                        Toast.makeText(this, "Microphone permission is required for dictation", Toast.LENGTH_SHORT).show()
                    }
                }

                LaunchedEffect(uiState.feedbackMessage) {
                    uiState.feedbackMessage?.let { msg ->
                        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
                        viewModel.clearFeedback()
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    if (showAbout) {
                        AboutScreen(
                            hasAudioPermission = uiState.hasAudioPermission,
                            hasOverlayPermission = uiState.hasOverlayPermission,
                            hasAccessibilityPermission = uiState.hasAccessibilityPermission,
                            onRequestAudioPermission = {
                                audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            },
                            onRequestOverlayPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName")
                                        )
                                    )
                                }
                            },
                            onRequestAccessibilityPermission = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            onBack = { showAbout = false },
                            modifier = Modifier.fillMaxSize().padding(innerPadding)
                        )
                    } else {
                        MainScreenContent(
                            uiState = uiState,
                            onToggleOverlay = { viewModel.toggleOverlayService(it) },
                            onApiKeyChange = { viewModel.updateApiKey(it) },
                            onValidateApiKey = { viewModel.validateAndSaveApiKey() },
                            onModeSelect = { viewModel.setTranscriptionMode(it) },
                            onStartSandboxRecording = {
                                if (!uiState.hasAudioPermission) {
                                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                } else {
                                    viewModel.startSandboxRecording()
                                }
                            },
                            onStopSandboxRecording = { viewModel.stopSandboxRecording() },
                            onSandboxTextChange = { viewModel.updateSandboxText(it) },
                            onClearSandboxText = { viewModel.clearSandboxText() },
                            onCopySandboxText = { viewModel.copySandboxText() },
                            onDismissSandboxError = { viewModel.clearErrorMessage() },
                            onOpenAbout = { showAbout = true },
                            modifier = Modifier.fillMaxSize().padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionStates()
    }
}

@Composable
fun MainScreenContent(
    uiState: MainUiState,
    onToggleOverlay: (Boolean) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onValidateApiKey: () -> Unit,
    onModeSelect: (String) -> Unit,
    onStartSandboxRecording: () -> Unit,
    onStopSandboxRecording: () -> Unit,
    onSandboxTextChange: (String) -> Unit,
    onClearSandboxText: () -> Unit,
    onCopySandboxText: () -> Unit,
    onDismissSandboxError: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val allPermissionsGranted = uiState.hasAudioPermission &&
            uiState.hasOverlayPermission &&
            uiState.hasAccessibilityPermission

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AnimatedVisibility(
                visible = !uiState.hasValidApiKey,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                MissingApiKeyPromptCard()
            }

            HeroStatusBar(
                isOverlayRunning = uiState.isOverlayServiceRunning,
                allPermissionsGranted = allPermissionsGranted,
                onToggleOverlay = onToggleOverlay,
                onOpenAbout = onOpenAbout
            )

            ApiKeySection(
                apiKey = uiState.apiKey,
                onApiKeyChange = onApiKeyChange,
                isValidating = uiState.isValidatingApiKey,
                validationResult = uiState.apiKeyValidationResult,
                isValidationSuccess = uiState.isApiKeyValid,
                onValidateKey = onValidateApiKey
            )

            VoiceSandboxSection(
                isRecording = uiState.isSandboxRecording,
                isProcessing = uiState.isSandboxProcessing,
                recordingTimeSeconds = uiState.sandboxRecordingSeconds,
                transcribedText = uiState.sandboxTranscribedText,
                onTranscribedTextChange = onSandboxTextChange,
                selectedMode = uiState.transcriptionMode,
                onModeSelect = onModeSelect,
                onStartRecording = onStartSandboxRecording,
                onStopRecording = onStopSandboxRecording,
                onClearText = onClearSandboxText,
                onCopyText = onCopySandboxText,
                lastErrorMessage = uiState.lastErrorMessage,
                lastAudioInfo = uiState.lastAudioInfo,
                onDismissError = onDismissSandboxError
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun MissingApiKeyPromptCard(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    androidx.compose.material3.Card(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = colorScheme.errorContainer),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, colorScheme.error.copy(alpha = 0.6f))
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(colorScheme.error.copy(alpha = 0.15f))
                    .border(1.dp, colorScheme.error.copy(alpha = 0.4f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Rounded.Key,
                    contentDescription = null,
                    tint = colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    text = "Gemini API Key Required",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(2.dp))
                androidx.compose.material3.Text(
                    text = "AuraVoice requires a Google AI Studio API key to transcribe speech. Please enter and save your key below to activate dictation.",
                    style = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onErrorContainer.copy(alpha = 0.9f), lineHeight = 16.dp.value.sp),
                    color = colorScheme.onErrorContainer.copy(alpha = 0.9f)
                )
            }
        }
    }
}
