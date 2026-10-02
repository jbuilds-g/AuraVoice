package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

private fun modelLabel(model: String): String = when (model) {
    DEFAULT_MODEL -> "Gemini 3.5 Flash-Lite (Default)"
    "gemini-3.5-transcribe" -> "Gemini 3.5 Transcribe"
    else -> model
}

@Composable
fun ModelSelectionSection(
    selectedModel: String,
    models: List<String>,
    onModelSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Gemini Model")
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(modelLabel(selectedModel))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(modelLabel(model)) },
                            onClick = {
                                onModelSelect(model)
                                expanded = false
                            }
                        )
                    }
                }
            }
            Text("Choose a model for dictation. Gemini 3.5 Flash-Lite is the default.")
        }
    }
}
