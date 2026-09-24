package com.example.data

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object QuotaCooldownController {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()
    private var cooldownJob: Job? = null

    fun start(seconds: Long) {
        val duration = seconds.coerceIn(1L, 60L)
        cooldownJob?.cancel()
        cooldownJob = scope.launch {
            val endAt = SystemClock.elapsedRealtime() + duration * 1000L
            while (true) {
                val remaining = endAt - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                _remainingSeconds.value = ((remaining + 999L) / 1000L).toInt()
                delay(minOf(1000L, remaining))
            }
            _remainingSeconds.value = 0
        }
    }
}
