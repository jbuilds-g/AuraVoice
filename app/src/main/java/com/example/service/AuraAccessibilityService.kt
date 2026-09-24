package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AuraAccessibilityService monitors focus events to dynamically show/hide the floating overlay
 * button only when editable text fields are active, and injects/appends transcribed text into targeted inputs.
 */
class AuraAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AuraAccessibility"

        @Volatile
        var instance: AuraAccessibilityService? = null
            private set

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        /**
         * Verifies if AuraAccessibilityService is enabled in Android System Accessibility settings.
         */
        fun isAccessibilitySettingsEnabled(context: Context): Boolean {
            val expectedFullName = "${context.packageName}/${AuraAccessibilityService::class.java.canonicalName}"
            val simpleName = "${context.packageName}/.service.AuraAccessibilityService"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedFullName, ignoreCase = true) ||
                    componentName.equals(simpleName, ignoreCase = true) ||
                    componentName.contains("AuraAccessibilityService")
                ) {
                    return true
                }
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        Log.d(TAG, "AuraAccessibilityService connected.")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info

        checkFocusAndUpdateOverlay(null)
    }

    /**
     * Monitors AccessibilityEvent.TYPE_VIEW_FOCUSED and TYPE_WINDOW_STATE_CHANGED.
     * Sets floating overlay button to VISIBLE only when node.isEditable == true.
     * Automatically sets overlay visibility to GONE when no editable field is focused.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                checkFocusAndUpdateOverlay(event)
            }
        }
    }

    private fun checkFocusAndUpdateOverlay(event: AccessibilityEvent?) {
        val sourceNode = event?.source
        val root = rootInActiveWindow
        val focusedNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        val isEditable = (sourceNode != null && sourceNode.isEditable) ||
                (focusedNode != null && focusedNode.isEditable)

        OverlayService.updateEditableFocusState(isEditable)
    }

    /**
     * Returns the top edge of the active on-screen keyboard in screen coordinates.
     * Used by the floating overlay to keep the drag-to-dismiss target visible above the IME.
     */
    fun getInputMethodTop(): Int? {
        val inputMethodWindow = windows.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } ?: return null

        val bounds = Rect()
        inputMethodWindow.getBoundsInScreen(bounds)
        return bounds.top.takeIf { bounds.height() > 0 }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AuraAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
            _isServiceConnected.value = false
        }
    }

    /**
     * Injects or appends the transcribed string into the active focused text field.
     * APPEND BEHAVIOR: Retrieves existing text from focusedNode.text. If non-empty,
     * appends a space + new transcribed text so previous text is preserved.
     * FALLBACK BEHAVIOR: If no field is focused, copies text to ClipboardManager and shows a Toast.
     */
    fun injectOrAppendTranscribedText(newText: String): InjectionResult {
        if (newText.isBlank()) return InjectionResult.Empty

        val root = rootInActiveWindow
        val targetNode: AccessibilityNodeInfo? = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root)

        if (targetNode != null) {
            val rawExistingText = targetNode.text?.toString() ?: ""
            val hintText = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                targetNode.hintText?.toString()
            } else {
                null
            }
            val existingText = when {
                rawExistingText.isBlank() -> ""
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                        targetNode.isShowingHintText -> ""
                !hintText.isNullOrBlank() && rawExistingText == hintText -> ""
                else -> rawExistingText
            }

            if (rawExistingText.isNotBlank() && existingText.isBlank()) {
                Log.d(TAG, "Ignoring placeholder/hint text during dictation injection: \"$rawExistingText\"")
            }

            val combinedText = if (existingText.isNotBlank()) {
                if (existingText.endsWith(" ") || existingText.endsWith("\n")) {
                    "$existingText$newText"
                } else {
                    "$existingText $newText"
                }
            } else {
                newText
            }

            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combinedText)
            }

            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (success) {
                Log.d(TAG, "Smart append successful! Combined length: ${combinedText.length}")
                return InjectionResult.Appended(wasAppended = existingText.isNotBlank())
            }

            // Fallback to paste if ACTION_SET_TEXT is rejected by the target node
            copyToClipboard(newText)
            val pasteSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (pasteSuccess) {
                Log.d(TAG, "Appended text via ACTION_PASTE.")
                return InjectionResult.PastedViaClipboard
            }
        }

        // FALLBACK BEHAVIOR: No input field focused -> Copy to clipboard & show system Toast
        copyToClipboard(newText)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        return InjectionResult.CopiedToClipboardFallback
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        if (node.isEditable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val match = findFirstEditableNode(child)
            if (match != null) return match
        }
        return null
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AuraVoice Dictation", text)
        clipboard.setPrimaryClip(clip)
    }

    sealed interface InjectionResult {
        data class Appended(val wasAppended: Boolean) : InjectionResult
        data object PastedViaClipboard : InjectionResult
        data object CopiedToClipboardFallback : InjectionResult
        data object Empty : InjectionResult
    }
}
