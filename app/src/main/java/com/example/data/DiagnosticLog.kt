package com.example.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory diagnostic events for technical users.
 * Never store API keys, audio, transcripts, or request bodies here.
 */
object DiagnosticLog {
    data class Entry(
        val timestamp: String,
        val message: String
    )

    private const val MAX_ENTRIES = 100
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Synchronized
    fun add(message: String) {
        val entry = Entry(formatter.format(Date()), message)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
