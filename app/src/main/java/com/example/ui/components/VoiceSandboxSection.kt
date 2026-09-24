package com.example.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VoiceSandboxSection(
    isRecording: Boolean,
    isProcessing: Boolean,
    recordingTimeSeconds: Int,
    transcribedText: String,
    onTranscribedTextChange: (String) -> Unit,
    selectedMode: String,
    onModeSelect: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onClearText: () -> Unit,
    onCopyText: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_sandbox_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorScheme.primaryContainer)
                            .border(1.dp, colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RecordVoiceOver,
                            contentDescription = "Dictation Sandbox",
                            tint = colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "In-App Dictation Sandbox",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface
                            )
                        )
                        Text(
                            text = "Test voice capture & smart formatting directly",
                            style = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onSurfaceVariant)
                        )
                    }
                }

                // Smart Append indicator badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colorScheme.secondaryContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Smart Append",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    )
                }
            }

            // Mode Selector Chips
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Transcription Engine Mode:",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val modes = listOf(
                        "smart" to "Smart Cleanup",
                        "verbatim" to "Verbatim",
                        "concise" to "Concise",
                        "bullet_points" to "Bullets"
                    )

                    modes.forEach { (modeKey, label) ->
                        val isSelected = selectedMode == modeKey
                        FilterChip(
                            selected = isSelected,
                            onClick = { onModeSelect(modeKey) },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colorScheme.primary,
                                selectedLabelColor = colorScheme.onPrimary,
                                containerColor = colorScheme.surfaceContainerHighest,
                                labelColor = colorScheme.onSurfaceVariant
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = colorScheme.outlineVariant,
                                selectedBorderColor = colorScheme.primary
                            ),
                            modifier = Modifier.testTag("mode_chip_$modeKey")
                        )
                    }
                }
            }

            // Text Output Area
            OutlinedTextField(
                value = transcribedText,
                onValueChange = onTranscribedTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .testTag("sandbox_text_field"),
                shape = RoundedCornerShape(14.dp),
                placeholder = {
                    Text(
                        text = if (isRecording) "Listening... speak naturally with thoughts, filler words, or bullet cues..." else "Transcribed text appears here. Click the mic below to dictate.",
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = colorScheme.surfaceContainerHighest,
                    focusedBorderColor = colorScheme.primary,
                    unfocusedBorderColor = colorScheme.outlineVariant,
                    focusedTextColor = colorScheme.onSurface,
                    unfocusedTextColor = colorScheme.onSurface
                )
            )

            // Animated Processing Skeleton Loader
            AnimatedVisibility(
                visible = isProcessing,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                DictationProcessingSkeleton()
            }

            // Bottom Actions Row: Mic Button + Copy + Clear
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Record / Stop Button
                Button(
                    onClick = {
                        if (isRecording) onStopRecording() else onStartRecording()
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("sandbox_mic_toggle_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) colorScheme.error else colorScheme.primary,
                        contentColor = if (isRecording) colorScheme.onError else colorScheme.onPrimary
                    ),
                    enabled = !isProcessing
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                            contentDescription = if (isRecording) "Stop" else "Record",
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = when {
                                isRecording -> "Stop (${recordingTimeSeconds}s)"
                                isProcessing -> "Processing AI..."
                                else -> "Start Voice Dictation"
                            },
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                // Copy & Clear Utility Icons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onCopyText,
                        enabled = transcribedText.isNotBlank(),
                        modifier = Modifier.testTag("copy_transcribed_text_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "Copy Text",
                            tint = if (transcribedText.isNotBlank()) colorScheme.primary else colorScheme.outline
                        )
                    }

                    IconButton(
                        onClick = onClearText,
                        enabled = transcribedText.isNotBlank(),
                        modifier = Modifier.testTag("clear_transcribed_text_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Clear Text",
                            tint = if (transcribedText.isNotBlank()) colorScheme.onSurfaceVariant else colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animated processing skeleton loader when waiting for model inference.
 */
@Composable
fun DictationProcessingSkeleton() {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.surfaceContainerHighest)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(colorScheme.primary.copy(alpha = 0.5f))
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
            )
        }
    }
}
