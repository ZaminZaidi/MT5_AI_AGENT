package com.mt5.aiagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
        var lastBalanceSummary: String = "Open MT5 Trade tab to sync balance"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var expandedPanel: LinearLayout? = null
    private var miniOrb: TextView? = null
    private var statusTv: TextView? = null
    private var wakeBtn: TextView? = null
    private var confirmLayout: LinearLayout? = null
    private var confirmBtn: Button? = null
    private var pendingConfirmAction: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var speechRecognizer: SpeechRecognizer? = null
    private var isBusy = false
    private var isWakeWordActive = false
    private var awaitingPostWakeCommand = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == "net.metaquotes.metatrader5") {
            val root = rootInActiveWindow ?: return
            extractQuickBalance(root)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isWakeWordActive = false
        hideFloatingAssistant()
        speechRecognizer?.destroy()
        instance = null
    }

    private fun extractQuickBalance(root: AccessibilityNodeInfo) {
        val allText = collectAllTexts(root)
        val balIdx = allText.indexOfFirst { it.contains("Balance", ignoreCase = true) }
        if (balIdx != -1 && balIdx + 1 < allText.size) {
            val eqIdx = allText.indexOfFirst { it.contains("Equity", ignoreCase = true) }
            val eqVal = if (eqIdx != -1 && eqIdx + 1 < allText.size) allText[eqIdx + 1] else "--"
            lastBalanceSummary = "Bal: ${allText[balIdx + 1]}  •  Eq: $eqVal"
        }
    }

    private fun neonBg(startHex: String, endHex: String, radius: Float = 28f, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
        ).apply {
            cornerRadius = radius
            if (strokeHex != null) setStroke(3, Color.parseColor(strokeHex))
        }
    }

    fun showFloatingAssistant() {
        mainHandler.post {
            if (overlayView != null) return@post
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val wakeWord = LearningMemory.getWakeWord(this).uppercase()
            val rootBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 0, 20, 0)
            }

            // 1. Minimized Glowing Neon Orb
            miniOrb = TextView(this).apply {
                text = "⚡ AI"
                setTextColor(Color.parseColor("#00F0FF"))
                typeface = Typeface.DEFAULT_BOLD
                textSize = 13f
                setPadding(36, 20, 36, 20)
                background = neonBg("#EE050B14", "#EE0F172A", 60f, "#00F0FF")
                visibility = View.GONE
                setOnClickListener {
                    visibility = View.GONE
                    expandedPanel?.visibility = View.VISIBLE
                }
            }

            // 2. Expanded Futuristic Command Console
            expandedPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = neonBg("#F2070D19", "#F20F172A", 36f, "#00F0FF")
                setPadding(30, 24, 30, 24)
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            statusTv = TextView(this).apply {
                text = "⚡ Ready • Say \"$wakeWord\""
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            wakeBtn = TextView(this).apply {
                text = " WAKE: OFF "
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(16, 8, 16, 8)
                background = neonBg("#1E293B", "#0F172A", 20f, "#334155")
                setOnClickListener { toggleWakeWordMode() }
            }

            val btnMin = TextView(this).apply {
                text = "  —  "
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 16f
                setOnClickListener {
                    expandedPanel?.visibility = View.GONE
                    miniOrb?.visibility = View.VISIBLE
                }
            }

            val btnClose = TextView(this).apply {
                text = " ✕ "
                setTextColor(Color.parseColor("#F87171"))
                textSize = 16f
                setOnClickListener {
                    isWakeWordActive = false
                    speechRecognizer?.destroy()
                    hideFloatingAssistant()
                }
            }

            topRow.addView(statusTv)
            topRow.addView(wakeBtn)
            topRow.addView(btnMin)
            topRow.addView(btnClose)

            val inputRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 0)
            }

            val inputEt = EditText(this).apply {
                hint = "Command or 'Learn x means y'..."
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(Color.WHITE)
                textSize = 13f
                background = neonBg("#0F172A", "#1E293B", 20f, "#334155")
                setPadding(22, 14, 22, 14)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnMic = Button(this).apply {
                text = "🎙"
                background = neonBg("#00C853", "#009624", 20f)
                setTextColor(Color.WHITE)
                setOnClickListener { startSingleVoiceInput(inputEt) }
            }

            val btnGo = Button(this).apply {
                text = "GO"
                background = neonBg("#0284C7", "#2563EB", 20f)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val cmd = inputEt.text.toString().trim()
                    if (cmd.isNotEmpty()) {
                        inputEt.setText("")
                        executeCommand(cmd)
                    }
                }
            }

            inputRow.addView(inputEt)
            inputRow.addView(btnMic)
            inputRow.addView(btnGo)

            // Safety Confirm Bar
            confirmLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                setPadding(0, 16, 0, 0)
            }

            confirmBtn = Button(this).apply {
                text = "CONFIRM"
                background = neonBg("#16A34A", "#15803D", 20f, "#4ADE80")
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    confirmLayout?.visibility = View.GONE
                    val act = pendingConfirmAction
                    pendingConfirmAction = null
                    executor.execute { act?.invoke() }
                }
            }

            val cancelBtn = Button(this).apply {
                text = "CANCEL"
                background = neonBg("#DC2626", "#991B1B", 20f)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    confirmLayout?.visibility = View.GONE
                    pendingConfirmAction = null
                    isBusy = false
                    updateStatus("Action cancelled.")
                }
            }

            confirmLayout?.addView(confirmBtn)
            confirmLayout?.addView(cancelBtn)

            expandedPanel?.addView(topRow)
            expandedPanel?.addView(inputRow)
            expandedPanel?.addView(confirmLayout)

            rootBox.addView(miniOrb)
            rootBox.addView(expandedPanel)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                y = 100
            }

            overlayView = rootBox
            windowManager?.addView(overlayView, params)
        }
    }

    fun hideFloatingAssistant() {
        mainHandler.post {
            overlayView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
            overlayView = null
        }
    }

    private fun updateStatus(msg: String) {
        mainHandler.post { statusTv?.text = msg }
    }

    private fun toggleWakeWordMode() {
        isWakeWordActive = !isWakeWordActive
        val wakeWord = LearningMemory.getWakeWord(this).uppercase()
        if (isWakeWordActive) {
            wakeBtn?.text = " WAKE: ON "
            wakeBtn?.setTextColor(Color.parseColor("#00F0FF"))
            wakeBtn?.background = neonBg("#082F49", "#0C4A6E", 20f, "#00F0FF")
            updateStatus("👂 Listening for \"$wakeWord\"...")
            startWakeWordLoop()
        } else {
            wakeBtn?.text = " WAKE: OFF "
            wakeBtn?.setTextColor(Color.parseColor("#94A3B8"))
            wakeBtn?.background = neonBg("#1E293B", "#0F172A", 20f, "#334155")
            speechRecognizer?.destroy()
            updateStatus("⚡ Wake word paused")
        }
    }

    private fun startWakeWordLoop() {
        if (!isWakeWordActive) return
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                        val wakeWord = LearningMemory.getWakeWord(this@MT5AgentService)

                        if (awaitingPostWakeCommand && spoken.isNotEmpty()) {
                            awaitingPostWakeCommand = false
                            executeCommand(spoken)
                        } else if (spoken.contains(wakeWord)) {
                            val afterWake = spoken.substringAfter(wakeWord).trim()
                            if (afterWake.isNotEmpty()) {
                                executeCommand(afterWake)
                            } else {
                                awaitingPostWakeCommand = true
                                updateStatus("⚡ Yes? Speak your command...")
                            }
                        }
                        if (isWakeWordActive) mainHandler.postDelayed({ startWakeWordLoop() }, 600)
                    }

                    override fun onError(error: Int) {
                        if (isWakeWordActive) mainHandler.postDelayed({ startWakeWordLoop() }, 900)
                    }
                    override fun onReadyForSpeech(params: Bundle?) {}
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
            } catch (_: Exception) {
                if (isWakeWordActive) mainHandler.postDelayed({ startWakeWordLoop() }, 1200)
            }
        }
    }

    private fun startSingleVoiceInput(inputEt: EditText) {
        isWakeWordActive = false
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { updateStatus("🎙 Listening...") }
                    override fun onResults(results: Bundle?) {
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                        if (spoken.isNotEmpty()) {
                            inputEt.setText(spoken)
                            executeCommand(spoken)
                        } else {
                            updateStatus("Didn't hear clearly. Try again.")
                        }
                    }
                    override fun onError(error: Int) { updateStatus("Mic error ($error). Tap 🎙 again.") }
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

    fun executeCommand(rawCmd: String) {
        if (isBusy) return
        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
        val savedList = (prefs.getString("mt5_accounts_auto", "") ?: "")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

        val parsed = MT5LocalBrain.parse(this, rawCmd, savedList)
        updateStatus(parsed.summary)

        if (parsed.type == TaskType.UNKNOWN || parsed.type == TaskType.TEACH_RULE) return
        isBusy = true

        executor.execute {
            try {
                ensureMT5Open()
                when (parsed.type) {
                    TaskType.SCAN_ACCOUNTS -> runAutoScanAccounts()
                    TaskType.SWITCH_ACCOUNT -> runSwitchAccount(parsed.accountQuery)
                    TaskType.READ_STATUS -> {
                        clickByText("Trade")
                        Thread.sleep(900)
                        rootInActiveWindow?.let { extractQuickBalance(it) }
                        updateStatus("💰 $lastBalanceSummary")
                    }
                    TaskType.NAVIGATE -> {
                        clickByText(parsed.navTab)
                        updateStatus("Opened ${parsed.navTab}")
                    }
                    TaskType.OPEN_TRADE -> runOpenTrade(parsed)
                    TaskType.MODIFY_SL_TP -> runModifyTrade(parsed)
                    TaskType.CLOSE_TRADE -> runCloseTrade(parsed)
                    else -> {}
                }
            } catch (e: Exception) {
                updateStatus("Error: ${e.message}")
            } finally {
                if (pendingConfirmAction == null) isBusy = false
            }
        }
    }

    private fun ensureMT5Open() {
        val currentPkg = rootInActiveWindow?.packageName?.toString()
        if (currentPkg != "net.metaquotes.metatrader5") {
            val intent = packageManager.getLaunchIntentForPackage("net.metaquotes.metatrader5")
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) {
                startActivity(intent)
                Thread.sleep(1500)
            }
        }
    }

    private fun openManageAccounts() {
        if (!clickByDesc("Open navigation drawer") && !clickByDesc("Navigate up")) {
            tapCoordinates(75f, 155f)
        }
        Thread.sleep(900)
        clickByText("Manage accounts")
        Thread.sleep(1100)
    }

    private fun runAutoScanAccounts() {
        openManageAccounts()
        val texts = collectAllTexts(rootInActiveWindow)
        val accounts = texts.filter { t ->
            t.length > 4 && !t.equals("Accounts", true) &&
            !t.equals("Manage accounts", true) && !t.contains("MetaQuotes", true)
        }.distinct()

        getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
            .edit().putString("mt5_accounts_auto", accounts.joinToString("\n")).apply()
        updateStatus("✅ Learned ${accounts.size} account items!")
    }

    private fun runSwitchAccount(query: String) {
        openManageAccounts()
        if (clickByText(query)) {
            Thread.sleep(800)
            clickByText("Sign in")
            updateStatus("✅ Switched to $query")
        } else {
            updateStatus("Account '$query' not found.")
        }
    }

    private fun runOpenTrade(cmd: ParsedCommand) {
        clickByText("Quotes")
        Thread.sleep(900)
        if (!clickByText(cmd.symbol)) {
            updateStatus("${cmd.symbol} not visible in Quotes.")
            return
        }
        Thread.sleep(700)
        clickByText("New order")
        Thread.sleep(1000)
        typeIntoFirstNumericField(cmd.lot)
        Thread.sleep(400)

        askSafetyConfirm("CONFIRM ${cmd.side} ${cmd.symbol} (${cmd.lot})") {
            clickByText(cmd.side)
            updateStatus("✅ Executed ${cmd.side} ${cmd.symbol}")
            isBusy = false
        }
    }

    private fun runModifyTrade(cmd: ParsedCommand) {
        clickByText("Trade")
        Thread.sleep(900)
        if (!longClickByText(cmd.symbol)) {
            updateStatus("No open ${cmd.symbol} trade found.")
            return
        }
        Thread.sleep(700)
        clickByText("Modify position")
        Thread.sleep(900)
        val valueToSet = cmd.sl.ifEmpty { cmd.tp }
        if (valueToSet.isNotEmpty()) {
            typeIntoFirstNumericField(valueToSet)
            Thread.sleep(400)
        }
        askSafetyConfirm("CONFIRM MODIFY ${cmd.symbol}") {
            clickByText("Modify")
            updateStatus("✅ Modified ${cmd.symbol}")
            isBusy = false
        }
    }

    private fun runCloseTrade(cmd: ParsedCommand) {
        clickByText("Trade")
        Thread.sleep(900)
        if (!longClickByText(cmd.symbol)) {
            updateStatus("No open ${cmd.symbol} trade found.")
            return
        }
        Thread.sleep(700)
        clickByText("Close position")
        Thread.sleep(900)
        askSafetyConfirm("CONFIRM CLOSE ${cmd.symbol}") {
            clickByText("Close with")
            updateStatus("✅ Closed ${cmd.symbol}")
            isBusy = false
        }
    }

    private fun askSafetyConfirm(label: String, onConfirm: () -> Unit) {
        pendingConfirmAction = onConfirm
        mainHandler.post {
            confirmBtn?.text = label
            confirmLayout?.visibility = View.VISIBLE
            updateStatus("⚠️ Tap CONFIRM to execute live")
        }
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo?): List<String> {
        val list = mutableListOf<String>()
        if (node == null) return list
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { list.add(it) }
        for (i in 0 until node.childCount) list.addAll(collectAllTexts(node.getChild(i)))
        return list
    }

    private fun clickByText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(target)
        for (node in nodes) {
            var cur: AccessibilityNodeInfo? = node
            while (cur != null) {
                if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                cur = cur.parent
            }
        }
        return false
    }

    private fun clickByDesc(desc: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return findAndClickDesc(root, desc)
    }

    private fun findAndClickDesc(node: AccessibilityNodeInfo?, desc: String): Boolean {
        if (node == null) return false
        if (node.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true && node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) if (findAndClickDesc(node.getChild(i), desc)) return true
        return false
    }

    private fun longClickByText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(target)
        for (node in nodes) {
            var cur: AccessibilityNodeInfo? = node
            while (cur != null) {
                if (cur.isLongClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                cur = cur.parent
            }
        }
        return false
    }

    private fun typeIntoFirstNumericField(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = findFirstEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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

    private fun tapCoordinates(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
