package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import com.example.BuildConfig
import com.example.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    val logEntries by DiagnosticLog.entries.collectAsState()
    var logsExpanded by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }
    var githubProfile by remember { mutableStateOf<GithubProfile?>(null) }
    var githubAvatar by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        val profile = fetchGithubProfile("jbuilds-g")
        githubProfile = profile
        if (profile?.avatarUrl != null) {
            githubAvatar = fetchGithubAvatar(profile.avatarUrl)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 680.dp)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("About VoFlow", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold))
                Text("How it works, permissions, and diagnostics", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(104.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_voflow_waveform),
                            contentDescription = "VoFlow waveform",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(88.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("VoFlow", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("AI Voice Dictation", style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurfaceVariant)
                    }
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colorScheme.primaryContainer)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colorScheme.onPrimaryContainer)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text("Developer", style = MaterialTheme.typography.labelLarge, color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jbuilds-g")))
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerHighest)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(52.dp).clip(CircleShape).background(colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            if (githubAvatar != null) {
                                Image(
                                    bitmap = githubAvatar!!.asImageBitmap(),
                                    contentDescription = "GitHub profile picture",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_github),
                                    contentDescription = "GitHub profile",
                                    tint = colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                githubProfile?.name ?: "JBuilds",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "@${githubProfile?.login ?: "jbuilds-g"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { showLicenseDialog = true }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("MIT License", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jbuilds-g/VoFlow"))) },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_github),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View Source", maxLines = 1)
                    }
                }
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

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text("MIT License") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        """MIT License

Copyright (c) 2026 JBuilds

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.""",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text("Done")
                }
            }
        )
    }
}


private data class GithubProfile(
    val name: String?,
    val login: String,
    val avatarUrl: String?
)

private suspend fun fetchGithubProfile(login: String): GithubProfile? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL("https://api.github.com/users/$login").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "VoFlow")
        }
        connection.use {
            if (it.responseCode !in 200..299) return@runCatching null
            val json = it.inputStream.bufferedReader().use { reader -> reader.readText() }
            val objectJson = JSONObject(json)
            GithubProfile(
                name = objectJson.optString("name").takeIf { value -> value.isNotBlank() },
                login = objectJson.optString("login", login),
                avatarUrl = objectJson.optString("avatar_url").takeIf { value -> value.isNotBlank() }
            )
        }
    }.getOrNull()
}

private suspend fun fetchGithubAvatar(url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "VoFlow")
        }
        connection.use {
            if (it.responseCode !in 200..299) return@runCatching null
            it.inputStream.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()
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
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = colorScheme.onPrimaryContainer)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text("How VoFlow Works", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
            Text(
                text = "VoFlow watches for editable text fields through Android Accessibility, shows the floating microphone only when typing, records your speech, and sends the audio to Gemini for transcription. Dictation is then inserted into the active field, with clipboard fallback when no field is available.",
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorScheme.surface)
                            .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
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
