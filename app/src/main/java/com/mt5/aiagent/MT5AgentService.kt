package com.mt5.aiagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MT5AgentService : AccessibilityService() {

    companion object {
        var instance: MT5AgentService? = null
        var lastCapturedScreen: String = "No MT5 screen captured yet."
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        // Capture UI tree whenever MetaTrader 5 is active
        if (pkg == "net.metaquotes.metatrader5") {
            val root = rootInActiveWindow ?: return
            lastCapturedScreen = dumpNodeTree(root)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // Reads all visible text and descriptions on the current screen
    fun dumpNodeTree(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null) return ""
        val sb = StringBuilder()
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty()) {
            val indent = "  ".repeat(depth.coerceAtMost(4))
            val label = listOfNotNull(text, desc).joinToString(" | ")
            val clickable = if (node.isClickable) " [CLICK]" else ""
            sb.append("$indent• $label$clickable\n")
        }

        for (i in 0 until node.childCount) {
            sb.append(dumpNodeTree(node.getChild(i), depth + 1))
        }
        return sb.toString()
    }

    // Finds a button/item by text and clicks it
    fun clickByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)
        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                current = current.parent
            }
        }
        return false
    }

    // Taps exact X, Y coordinates on the screen
    fun tapCoordinates(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
