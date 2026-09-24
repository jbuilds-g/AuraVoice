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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.ApiKeySection
import com.example.ui.components.HeroStatusBar
import com.example.ui.components.PermissionCard
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
                    MainScreenContent(
                        uiState = uiState,
                        onToggleOverlay = { viewModel.toggleOverlayService(it) },
                        onApiKeyChange = { viewModel.updateApiKey(it) },
                        onValidateApiKey = { viewModel.validateAndSaveApiKey() },
                        onRequestAudioPermission = {
                            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        onRequestOverlayPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                startActivity(intent)
                            }
                        },
                        onRequestAccessibilityPermission = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            startActivity(intent)
                        },
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
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
    onRequestAudioPermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onModeSelect: (String) -> Unit,
    onStartSandboxRecording: () -> Unit,
    onStopSandboxRecording: () -> Unit,
    onSandboxTextChange: (String) -> Unit,
    onClearSandboxText: () -> Unit,
    onCopySandboxText: () -> Unit,
    onDismissSandboxError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val colorScheme = MaterialTheme.colorScheme
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
            // 1. Mandatory Missing API Key Prompt (Shown when no key is configured in EncryptedSharedPreferences)
            AnimatedVisibility(
                visible = !uiState.hasValidApiKey,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                MissingApiKeyPromptCard()
            }

            // 2. Hero Status & Master Switch
            HeroStatusBar(
                isOverlayRunning = uiState.isOverlayServiceRunning,
                allPermissionsGranted = allPermissionsGranted,
                onToggleOverlay = onToggleOverlay
            )

            // 3. Dynamic Overlay Behavior Info
            DynamicOverlayInfoCard()

            // 4. Google AI Studio API Key Section (Encrypted Storage)
            ApiKeySection(
                apiKey = uiState.apiKey,
                onApiKeyChange = onApiKeyChange,
                isValidating = uiState.isValidatingApiKey,
                validationResult = uiState.apiKeyValidationResult,
                isValidationSuccess = uiState.isApiKeyValid,
                onValidateKey = onValidateApiKey
            )

            // 5. Required Permissions Section
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "System Integration Status",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    ),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                // Permission 1: Record Audio
                PermissionCard(
                    title = "Microphone Access",
                    description = "Captures low-latency 16kHz mono audio stream when the floating overlay button is clicked.",
                    icon = Icons.Rounded.Mic,
                    isGranted = uiState.hasAudioPermission,
                    actionButtonText = "Grant Audio Permission",
                    testTagPrefix = "permission_audio",
                    onActionClick = onRequestAudioPermission
                )

                // Permission 2: Display Over Other Apps
                PermissionCard(
                    title = "Display Over Other Apps",
                    description = "Renders floating dictation button over active applications only when typing.",
                    icon = Icons.Rounded.Layers,
                    isGranted = uiState.hasOverlayPermission,
                    actionButtonText = "Allow Draw Over Apps",
                    testTagPrefix = "permission_overlay",
                    onActionClick = onRequestOverlayPermission
                )

                // Permission 3: Accessibility Service
                PermissionCard(
                    title = "AuraVoice Accessibility Service",
                    description = "Monitors focus to hide overlay when not typing, and smart-appends text into active input fields.",
                    icon = Icons.Rounded.Accessibility,
                    isGranted = uiState.hasAccessibilityPermission,
                    actionButtonText = "Enable Accessibility in Settings",
                    testTagPrefix = "permission_accessibility",
                    onActionClick = onRequestAccessibilityPermission
                )
            }

            // 6. In-App Dictation Sandbox
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

            // 7. How It Works Quick Architecture Guide
            ArchitectureGuideCard()

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Prominent prompt banner displayed when no API key is found in EncryptedSharedPreferences.
 */
@Composable
fun MissingApiKeyPromptCard(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("missing_api_key_prompt_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.errorContainer),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, colorScheme.error.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.error.copy(alpha = 0.15f))
                    .border(1.dp, colorScheme.error.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = null,
                    tint = colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Gemini API Key Required",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onErrorContainer
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "AuraVoice requires a Google AI Studio API key to transcribe speech. Please enter and save your key below to activate dictation.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = colorScheme.onErrorContainer.copy(alpha = 0.9f),
                        lineHeight = 16.sp
                    )
                )
            }
        }
    }
}

/**
 * Card explaining dynamic overlay visibility (hide when not typing).
 */
@Composable
fun DynamicOverlayInfoCard(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("dynamic_visibility_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Visibility,
                    contentDescription = null,
                    tint = colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column {
                Text(
                    text = "Dynamic Overlay Active",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "The floating mic button automatically appears only when an editable text field is focused, and hides (View.GONE) when not typing so it never clutters your screen.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                )
            }
        }
    }
}

@Composable
fun ArchitectureGuideCard(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Text(
                    text = "How AuraVoice Operates",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                )
            }

            Text(
                text = "1. Dynamic Overlay: AuraAccessibilityService monitors focus events. When an editable field is focused (node.isEditable == true), the floating button is set to VISIBLE; otherwise it is set to GONE.\n" +
                        "2. Smart AI Processing: Audio is sent to Google's gemini-3.5-transcribe with mode: {'type': 'smart'} to eliminate filler words, self-corrections, and spoken formatting cues.\n" +
                        "3. Smart Append: AuraAccessibilityService retrieves existing field text and appends new dictation with intelligent spacing.\n" +
                        "4. Universal Clipboard Fallback: If no input field is currently active, text is automatically copied to the clipboard with a confirmation Toast.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            )
        }
    }
}
