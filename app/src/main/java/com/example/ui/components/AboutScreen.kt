package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DiagnosticLog

@Composable
fun AboutScreen(
    hasAudioPermission: Boolean,
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    val logEntries by DiagnosticLog.entries.collectAsState()
    var logsExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().widthIn(max = 680.dp).verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("About AuraVoice", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold))
                Text("How it works, permissions, and diagnostics", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
            }
        }

        AboutArchitectureCard()

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Permissions", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 4.dp))
            PermissionCard(
                title = "Microphone Access",
                description = "Captures the audio used for voice dictation.",
                icon = Icons.Rounded.Mic,
                isGranted = hasAudioPermission,
                actionButtonText = "Grant Audio Permission",
                testTagPrefix = "about_permission_audio",
                onActionClick = onRequestAudioPermission
            )
            PermissionCard(
                title = "Display Over Other Apps",
                description = "Allows the floating dictation button to appear above other apps.",
                icon = Icons.Rounded.Layers,
                isGranted = hasOverlayPermission,
                actionButtonText = "Allow Draw Over Apps",
                testTagPrefix = "about_permission_overlay",
                onActionClick = onRequestOverlayPermission
            )
            PermissionCard(
                title = "Accessibility Service",
                description = "Detects editable fields and inserts dictated text into the active app.",
                icon = Icons.Rounded.Accessibility,
                isGranted = hasAccessibilityPermission,
                actionButtonText = "Enable Accessibility",
                testTagPrefix = "about_permission_accessibility",
                onActionClick = onRequestAccessibilityPermission
            )
        }

        DiagnosticLogsCard(
            expanded = logsExpanded,
            entries = logEntries,
            onToggle = { logsExpanded = !logsExpanded },
            onClear = { DiagnosticLog.clear() }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun AboutArchitectureCard(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = colorScheme.onPrimaryContainer)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text("How AuraVoice Works", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
            Text(
                text = "AuraVoice watches for editable text fields through Android Accessibility, shows the floating microphone only when typing, records your speech, and sends the audio to Gemini for transcription. Dictation is then inserted into the active field, with clipboard fallback when no field is available.",
                style = MaterialTheme.typography.bodySmall.copy(color = colorScheme.onSurfaceVariant, lineHeight = 20.sp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Visibility, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("The overlay hides automatically when you are not typing.", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DiagnosticLogsCard(
    expanded: Boolean,
    entries: List<DiagnosticLog.Entry>,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
        onClick = onToggle
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Technical Logs", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(
                        if (entries.isEmpty()) "No diagnostic events yet" else "${entries.size} recent event${if (entries.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                if (expanded && entries.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear logs")
                    }
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                if (entries.isEmpty()) {
                    Text(
                        "Logs record model selection, fallback attempts, and errors. API keys, audio, and transcripts are never stored here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp))
                            .background(colorScheme.surface)
                            .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        entries.asReversed().forEach { entry ->
                            Text(
                                "${entry.timestamp}  ${entry.message}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Logs stay in memory and are cleared when the app process ends or when you clear them.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
