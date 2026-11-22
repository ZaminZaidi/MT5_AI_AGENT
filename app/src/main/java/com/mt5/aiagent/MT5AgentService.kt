package com.mt5.aiagent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class MT5AgentService : AccessibilityService() {

    companion object {
        var instance: MT5AgentService? = null
        var lastCapturedScreen: String = "No MT5 screen captured yet."
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var statusTv: TextView? = null
    private var confirmLayout: LinearLayout? = null
    private var confirmBtn: Button? = null
    private var pendingConfirmTarget: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var speechRecognizer: SpeechRecognizer? = null
    private var isAgentRunning = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == "net.metaquotes.metatrader5") {
            val root = rootInActiveWindow ?: return
            lastCapturedScreen = dumpNodeTree(root)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingAssistant()
        speechRecognizer?.destroy()
        instance = null
    }

    fun showFloatingAssistant() {
        mainHandler.post {
            if (overlayView != null) return@post
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#E61A1A2E"))
                setPadding(24, 20, 24, 20)
            }

            statusTv = TextView(this).apply {
                text = "MT5 AI Ready — Tap Mic or type command"
                setTextColor(Color.WHITE)
                textSize = 13f
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 0)
            }

            val inputEt = EditText(this).apply {
                hint = "e.g. Open Exness, set SL..."
                setHintTextColor(Color.LTGRAY)
                setTextColor(Color.WHITE)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnMic = Button(this).apply {
                text = "MIC"
                setOnClickListener { startVoiceInput(inputEt) }
            }

            val btnGo = Button(this).apply {
                text = "GO"
                setOnClickListener {
                    val cmd = inputEt.text.toString().trim()
                    if (cmd.isNotEmpty()) {
                        inputEt.setText("")
                        runAgentGoal(cmd)
                    }
                }
            }

            val btnClose = Button(this).apply {
                text = "X"
                setOnClickListener { hideFloatingAssistant() }
            }

            row.addView(inputEt)
            row.addView(btnMic)
            row.addView(btnGo)
            row.addView(btnClose)

            confirmLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                setPadding(0, 12, 0, 0)
            }

            confirmBtn = Button(this).apply {
                text = "CONFIRM ACTION"
                setBackgroundColor(Color.parseColor("#2E7D32"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    confirmLayout?.visibility = View.GONE
                    val target = pendingConfirmTarget
                    pendingConfirmTarget = ""
                    if (target.isNotEmpty()) {
                        clickByText(target)
                        updateStatus("Executed: $target")
                    }
                    isAgentRunning = false
                }
            }

            val cancelBtn = Button(this).apply {
                text = "CANCEL"
                setBackgroundColor(Color.parseColor("#C62828"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    confirmLayout?.visibility = View.GONE
                    pendingConfirmTarget = ""
                    isAgentRunning = false
                    updateStatus("Action cancelled.")
                }
            }

            confirmLayout?.addView(confirmBtn)
            confirmLayout?.addView(cancelBtn)

            container.addView(statusTv)
            container.addView(row)
            container.addView(confirmLayout)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
                y = 120
            }

            overlayView = container
            windowManager?.addView(overlayView, params)
        }
    }

    fun hideFloatingAssistant() {
        mainHandler.post {
            overlayView?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            overlayView = null
        }
    }

    private fun updateStatus(msg: String) {
        mainHandler.post { statusTv?.text = msg }
    }

    private fun startVoiceInput(inputEt: EditText) {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { updateStatus("Listening...") }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull() ?: ""
                        if (spoken.isNotEmpty()) {
                            inputEt.setText(spoken)
                            runAgentGoal(spoken)
                        } else {
                            updateStatus("Didn't catch that. Try again.")
                        }
                    }
                    override fun onError(error: Int) { updateStatus("Voice error ($error). Type or tap MIC again.") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                updateStatus("Mic error: ${e.message}")
            }
        }
    }

    private fun runAgentGoal(goal: String) {
        if (isAgentRunning) return
        isAgentRunning = true
        updateStatus("Thinking: $goal")

        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        val accounts = prefs.getString("mt5_accounts", "No accounts saved.") ?: ""

        executor.execute {
            val history = mutableListOf<String>()
            for (step in 1..8) {
                if (!isAgentRunning) break
                val screen = dumpNodeTree(rootInActiveWindow)
                val decision = GeminiBrain.decideNextAction(apiKey, goal, accounts, screen, history)
                updateStatus(decision.message)
                history.add("Step $step: ${decision.action} (${decision.target} ${decision.value})")

                when (decision.action.uppercase()) {
                    "OPEN_MT5" -> {
                        val intent = packageManager.getLaunchIntentForPackage("net.metaquotes.metatrader5")
                        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (intent != null) startActivity(intent)
                    }
                    "CLICK" -> clickByText(decision.target)
                    "LONG_CLICK" -> longClickByText(decision.target)
                    "TYPE" -> typeIntoField(decision.target, decision.value)
                    "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "CONFIRM" -> {
                        pendingConfirmTarget = decision.target
                        mainHandler.post {
                            confirmBtn?.text = "CONFIRM: ${decision.target}"
                            confirmLayout?.visibility = View.VISIBLE
                        }
                        return@execute
                    }
                    else -> {
                        isAgentRunning = false
                        return@execute
                    }
                }
                Thread.sleep(1600)
            }
            isAgentRunning = false
        }
    }

    fun dumpNodeTree(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null) return ""
        val sb = StringBuilder()
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val editable = if (node.isEditable) " [INPUT]" else ""

        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || node.isEditable) {
            val indent = "  ".repeat(depth.coerceAtMost(4))
            val label = listOfNotNull(text, desc).joinToString(" | ").ifEmpty { "EmptyField" }
            val clickable = if (node.isClickable) " [CLICK]" else ""
            sb.append("$indent• $label$clickable$editable\n")
        }

        for (i in 0 until node.childCount) {
            sb.append(dumpNodeTree(node.getChild(i), depth + 1))
        }
        return sb.toString()
    }

    fun clickByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)
        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current = current.parent
            }
        }
        return false
    }

    fun longClickByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)
        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isLongClickable) return current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                current = current.parent
            }
        }
        return false
    }

    fun typeIntoField(targetText: String, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = if (targetText.isNotEmpty()) root.findAccessibilityNodeInfosByText(targetText) else emptyList()
        val targetNode = nodes.firstOrNull { it.isEditable } ?: findFirstEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
