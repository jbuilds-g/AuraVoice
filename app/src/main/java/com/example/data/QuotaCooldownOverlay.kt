package com.example.data

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.FlowApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object QuotaCooldownOverlay {
    private const val OVERLAY_X_DP = 108
    private const val OVERLAY_Y_DP = 500

    private val scope = CoroutineScope(Dispatchers.Main)
    private var view: TextView? = null
    private var windowManager: WindowManager? = null
    private var collectJob: Job? = null

    fun show() {
        if (view != null) return

        val context = FlowApplication.instance
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density
        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(Color.argb(225, 35, 35, 35))
            }
            isClickable = true
            setOnClickListener { }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            (38 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (OVERLAY_X_DP * density).toInt()
            y = (OVERLAY_Y_DP * density).toInt()
        }

        try {
            wm.addView(label, params)
            view = label
            windowManager = wm
            collectJob?.cancel()
            collectJob = scope.launch {
                QuotaCooldownController.remainingSeconds.collectLatest { seconds ->
                    if (seconds <= 0) {
                        remove()
                    } else {
                        label.text = "${seconds}s"
                    }
                }
            }
        } catch (_: Exception) {
            view = null
            windowManager = null
        }
    }

    fun remove() {
        collectJob?.cancel()
        collectJob = null
        val currentView = view ?: return
        try {
            windowManager?.removeView(currentView)
        } catch (_: Exception) {
        }
        view = null
        windowManager = null
    }
}
