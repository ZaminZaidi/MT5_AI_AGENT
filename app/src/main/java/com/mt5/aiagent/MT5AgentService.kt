package com.mt5.aiagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

class MT5AgentService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        var instance: MT5AgentService? = null
        var lastBalanceSummary: String = "Open MT5 Trade tab to sync"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var pillStatusTv: TextView? = null
    private var confirmLayout: LinearLayout? = null
    private var confirmBtn: Button? = null
    private var pendingConfirmAction: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isSpeaking = false
    private var isBusy = false
    private var awaitingDirectCommand = false
    private var isOverlayActive = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this, this)
    }

    private fun isHindi(): Boolean {
        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
        return prefs.getString("jarvis_lang", "en") == "hi"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            applyVoiceProfile()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeaking = true }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (isOverlayActive && !isBusy) mainHandler.postDelayed({ startHandsFreeListening() }, 250)
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    if (isOverlayActive && !isBusy) mainHandler.postDelayed({ startHandsFreeListening() }, 500)
                }
            })
        }
    }

    fun applyVoiceProfile() {
        if (!isTtsReady) return
        if (isHindi()) {
            tts?.language = Locale("hi", "IN")
            val hiMale = tts?.voices?.firstOrNull { v ->
                v.locale.language == "hi" && (v.name.contains("male", true) || v.name.contains("hid", true) || v.name.contains("hie", true))
            }
            if (hiMale != null) tts?.voice = hiMale
            tts?.setPitch(0.82f)
            tts?.setSpeechRate(1.02f)
        } else {
            tts?.language = Locale.UK
            val ukMale = tts?.voices?.firstOrNull { v ->
                v.locale.language == "en" && (v.locale.country == "GB" || v.name.contains("gb", true)) &&
                (v.name.contains("male", true) || v.name.contains("gbd", true) || v.name.contains("gbb", true))
            }
            if (ukMale != null) tts?.voice = ukMale
            tts?.setPitch(0.80f)
            tts?.setSpeechRate(1.03f)
        }
    }

    private fun speakJarvis(enText: String, hiText: String) {
        val text = if (isHindi()) hiText else enText
        updatePillText(text)
        if (!isTtsReady) return
        applyVoiceProfile()
        mainHandler.post {
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            isSpeaking = true
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j_${System.currentTimeMillis()}")
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeAndSwipeUnlock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "MT5Jarvis::WakeLock"
            )
            wl.acquire(5000)
            // Swipe up from bottom to dismiss lock screen (works seamlessly with Extend Unlock / Smart Lock)
            mainHandler.postDelayed({
                val dm = resources.displayMetrics
                val path = Path().apply {
                    moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.85f)
                    lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.25f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                    .build()
                dispatchGesture(gesture, null, null)
            }, 600)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == "net.metaquotes.metatrader5") {
            rootInActiveWindow?.let { extractQuickBalance(it) }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingAssistant()
        speechRecognizer?.destroy()
        tts?.shutdown()
        instance = null
    }

    private fun extractQuickBalance(root: AccessibilityNodeInfo) {
        val allText = collectAllTexts(root)
        val balIdx = allText.indexOfFirst { it.contains("Balance", ignoreCase = true) }
        if (balIdx != -1 && balIdx + 1 < allText.size) {
            val eqIdx = allText.indexOfFirst { it.contains("Equity", ignoreCase = true) }
            val eqVal = if (eqIdx != -1 && eqIdx + 1 < allText.size) allText[eqIdx + 1] else "--"
            lastBalanceSummary = "Balance ${allText[balIdx + 1]}, Equity $eqVal"
        }
    }

    private fun pillBg(bgHex: String, strokeHex: String, radius: Float = 60f): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = radius
            setStroke(3, Color.parseColor(strokeHex))
        }
    }

    fun showFloatingAssistant() {
        mainHandler.post {
            if (overlayView != null) return@post
            isOverlayActive = true
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            val pillRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pillBg("#E6060B19", "#00E5FF", 80f)
                setPadding(36, 18, 28, 18)
            }

            val orb = TextView(this).apply {
                text = "◉ "
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 15f
            }

            pillStatusTv = TextView(this).apply {
                text = if (isHindi()) "JARVIS • बोलिए 'जार्विस'" else "JARVIS • Say 'Jarvis'"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
            }

            val closeBtn = TextView(this).apply {
                text = "   ✕"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 14f
                setOnClickListener { hideFloatingAssistant() }
            }

            pillRow.addView(orb)
            pillRow.addView(pillStatusTv)
            pillRow.addView(closeBtn)

            confirmLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                setPadding(0, 12, 0, 0)
            }

            confirmBtn = Button(this).apply {
                text = "CONFIRM"
                background = pillBg("#16A34A", "#4ADE80", 40f)
                setTextColor(Color.WHITE)
                textSize = 12f
                setOnClickListener {
                    confirmLayout?.visibility = View.GONE
                    val act = pendingConfirmAction
                    pendingConfirmAction = null
                    executor.execute { act?.invoke() }
                }
            }

            val cancelBtn = Button(this).apply {
                text = "CANCEL"
                background = pillBg("#DC2626", "#F87171", 40f)
                setTextColor(Color.WHITE)
                textSize = 12f
                setOnClickListener {
                    confirmLayout?.visibility = View.GONE
                    pendingConfirmAction = null
                    isBusy = false
                    speakJarvis("Order cancelled, boss.", "ऑर्डर कैंसिल कर दिया है बॉस।")
                }
            }

            confirmLayout?.addView(confirmBtn)
            confirmLayout?.addView(cancelBtn)
            wrapper.addView(pillRow)
            wrapper.addView(confirmLayout)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 95
            }

            var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f
            pillRow.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        touchX = event.rawX; touchY = event.rawY
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        windowManager?.updateViewLayout(wrapper, params)
                        true
                    }
                    else -> false
                }
            }

            overlayView = wrapper
            windowManager?.addView(overlayView, params)
            speakJarvis("Jarvis online. Ready for your command, boss.", "जार्विस ऑनलाइन है बॉस। बताइए क्या आदेश है?")
        }
    }

    fun hideFloatingAssistant() {
        mainHandler.post {
            isOverlayActive = false
            awaitingDirectCommand = false
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            overlayView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
            overlayView = null
        }
    }

    private fun updatePillText(msg: String) {
        mainHandler.post { pillStatusTv?.text = msg }
    }

    private fun startHandsFreeListening() {
        if (!isOverlayActive || isSpeaking || isBusy) return
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (awaitingDirectCommand) updatePillText(if (isHindi()) "🎙 सुन रहा हूँ बॉस..." else "🎙 Listening, boss...")
                    }
                    override fun onResults(results: Bundle?) {
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                        val lower = spoken.lowercase()

                        if (awaitingDirectCommand) {
                            awaitingDirectCommand = false
                            if (spoken.isNotBlank()) executeCommand(spoken) else startHandsFreeListening()
                            return
                        }

                        if (lower.contains("jarvis") || lower.contains("जार्विस") || lower.contains("service")) {
                            wakeAndSwipeUnlock()
                            val afterWake = lower
                                .substringAfter("jarvis", "")
                                .ifEmpty { lower.substringAfter("जार्विस", "") }
                                .ifEmpty { lower.substringAfter("service", "") }
                                .trim()

                            if (afterWake.length < 3) {
                                awaitingDirectCommand = true
                                speakJarvis("Yes boss, how may I help you?", "जी बॉस, बताइए मैं आपकी क्या मदद कर सकता हूँ?")
                            } else {
                                executeCommand(afterWake)
                            }
                        } else {
                            mainHandler.postDelayed({ startHandsFreeListening() }, 250)
                        }
                    }
                    override fun onError(error: Int) {
                        if (isOverlayActive && !isSpeaking && !isBusy) mainHandler.postDelayed({ startHandsFreeListening() }, 600)
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (isHindi()) "hi-IN" else "en-IN")
                }
                speechRecognizer?.startListening(intent)
            } catch (_: Exception) {}
        }
    }

    fun executeCommand(rawCmd: String) {
        if (isBusy) return
        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
        val savedList = (prefs.getString("mt5_accounts_auto", "") ?: "").lines().filter { it.isNotBlank() }
        val creds = MT5LocalBrain.parseCredentials(prefs.getString("mt5_saved_creds", "") ?: "")

        val parsed = MT5LocalBrain.parse(rawCmd, savedList, creds)
        speakJarvis(parsed.replyEn, parsed.replyHi)

        if (parsed.type == TaskType.UNKNOWN) return
        isBusy = true

        executor.execute {
            try {
                when (parsed.type) {
                    TaskType.OPEN_WHATSAPP_CHAT -> runOpenWhatsApp(parsed.chatName)
                    else -> {
                        ensureMT5Open()
                        when (parsed.type) {
                            TaskType.SCAN_ACCOUNTS -> runAutoScanAccounts()
                            TaskType.SWITCH_ACCOUNT -> runSwitchAccount(parsed.accountQuery)
                            TaskType.LOGIN_NEW_ACCOUNT -> parsed.credential?.let { runAutoLoginAccount(it) }
                            TaskType.READ_BRIEFING -> runMT5Briefing()
                            TaskType.NAVIGATE -> clickByText(parsed.navTab)
                            TaskType.OPEN_TRADE -> runOpenTrade(parsed)
                            TaskType.MODIFY_SL_TP -> runModifyTrade(parsed)
                            TaskType.CLOSE_TRADE -> runCloseTrade(parsed)
                            else -> {}
                        }
                    }
                }
            } catch (_: Exception) {
                speakJarvis("Apologies boss, something went wrong.", "माफ कीजिए बॉस, कोई एरर आ गया।")
            } finally {
                if (pendingConfirmAction == null) {
                    isBusy = false
                    if (isOverlayActive && !isSpeaking) mainHandler.postDelayed({ startHandsFreeListening() }, 900)
                }
            }
        }
    }

    // Full Android Control: Open WhatsApp & Specific Chat
    private fun runOpenWhatsApp(contactName: String) {
        val waIntent = packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
        if (waIntent == null) {
            speakJarvis("WhatsApp is not installed, boss.", "बॉस, इस फोन में व्हाट्सएप इंस्टॉल नहीं है।")
            return
        }
        waIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(waIntent)
        Thread.sleep(1500)

        if (contactName.isNotEmpty()) {
            val chatNode = findBestMatchingNode(rootInActiveWindow, contactName)
            if (chatNode != null) {
                tapNodeCenter(chatNode)
                Thread.sleep(1000)
                speakJarvis("Opened $contactName's chat, boss.", "बॉस, $contactName का चैट खुल गया है।")
            } else {
                // Tap Search icon and type contact name
                clickByDesc("Search")
                Thread.sleep(700)
                findAllEditables(rootInActiveWindow).firstOrNull()?.let {
                    setNodeText(it, contactName)
                    Thread.sleep(1000)
                    findBestMatchingNode(rootInActiveWindow, contactName)?.let { n -> tapNodeCenter(n) }
                }
            }
        }
    }

    // Live MT5 Trading Status + Drawdown % Briefing
    private fun runMT5Briefing() {
        clickByText("Trade")
        Thread.sleep(1000)
        val texts = collectAllTexts(rootInActiveWindow)
        val balIdx = texts.indexOfFirst { it.contains("Balance", true) }
        val eqIdx = texts.indexOfFirst { it.contains("Equity", true) }

        val balStr = if (balIdx != -1 && balIdx + 1 < texts.size) texts[balIdx + 1] else ""
        val eqStr = if (eqIdx != -1 && eqIdx + 1 < texts.size) texts[eqIdx + 1] else ""

        val bal = balStr.replace(" ", "").replace(",", "").toDoubleOrNull()
        val eq = eqStr.replace(" ", "").replace(",", "").toDoubleOrNull()

        if (bal != null && eq != null && bal > 0) {
            val diff = eq - bal
            val ddPercent = if (diff < 0) String.format(Locale.US, "%.2f", (-diff / bal) * 100.0) else "0.00"
            val enMsg = if (diff < 0) {
                "Boss, your balance is $balStr, equity is $eqStr, and current drawdown is $ddPercent percent."
            } else {
                "Boss, your balance is $balStr and equity is $eqStr. You are in profit with zero drawdown."
            }
            val hiMsg = if (diff < 0) {
                "बॉस, आपका बैलेंस $balStr और इक्विटी $eqStr है। अभी आपका ड्रॉडाउन $ddPercent प्रतिशत है।"
            } else {
                "बॉस, आपका बैलेंस $balStr और इक्विटी $eqStr है। अभी कोई ड्रॉडाउन नहीं है, आप प्रॉफिट में हैं।"
            }
            speakJarvis(enMsg, hiMsg)
        } else {
            speakJarvis("Balance is $balStr and Equity is $eqStr, boss.", "बॉस, बैलेंस $balStr और इक्विटी $eqStr है।")
        }
    }

    private fun ensureMT5Open() {
        if (rootInActiveWindow?.packageName?.toString() != "net.metaquotes.metatrader5") {
            val intent = packageManager.getLaunchIntentForPackage("net.metaquotes.metatrader5")
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) { startActivity(intent); Thread.sleep(1400) }
        }
    }

    private fun openManageAccounts() {
        if (collectAllTexts(rootInActiveWindow).any { it.equals("Accounts", true) }) return
        if (!clickByDesc("Open navigation drawer") && !clickByDesc("Navigate up")) tapCoordinates(75f, 155f)
        Thread.sleep(900)
        clickByText("Manage accounts")
        Thread.sleep(1100)
    }

    private fun runAutoLoginAccount(cred: SavedCredential) {
        openManageAccounts()
        if (!clickByDesc("Add") && !clickByDesc("New")) {
            val dm = resources.displayMetrics
            tapCoordinates(dm.widthPixels * 0.80f, dm.heightPixels * 0.08f)
        }
        Thread.sleep(1200)
        findAllEditables(rootInActiveWindow).firstOrNull()?.let { setNodeText(it, cred.broker); Thread.sleep(1400) }
        findBestMatchingNode(rootInActiveWindow, cred.broker)?.let { tapNodeCenter(it); Thread.sleep(1200) }
        val fields = findAllEditables(rootInActiveWindow)
        if (fields.size >= 2) {
            setNodeText(fields[0], cred.loginId)
            Thread.sleep(300)
            setNodeText(fields[1], cred.password)
            Thread.sleep(400)
            clickByText("Login")
            speakJarvis("Logged into ${cred.nickname}, boss.", "बॉस, ${cred.nickname} लॉगिन हो गया है।")
        } else {
            speakJarvis("Please tap the broker name on screen, boss.", "बॉस, कृपया स्क्रीन पर ब्रोकर नाम पर टैप करें।")
        }
    }

    private fun runAutoScanAccounts() {
        openManageAccounts()
        val accounts = collectAllTexts(rootInActiveWindow).filter { t ->
            t.length > 4 && !t.equals("Accounts", true) && !t.equals("Manage accounts", true) && !t.contains("MetaQuotes", true)
        }.distinct()
        getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
            .edit().putString("mt5_accounts_auto", accounts.joinToString("\n")).apply()
        speakJarvis("Scan complete, boss. Found ${accounts.size} entries.", "स्कैन पूरा हुआ बॉस, ${accounts.size} अकाउंट्स मिले।")
    }

    private fun runSwitchAccount(query: String) {
        openManageAccounts()
        Thread.sleep(400)
        val matched = findBestMatchingNode(rootInActiveWindow, query)
        if (matched != null) {
            tapNodeCenter(matched)
            Thread.sleep(900)
            clickByText("Sign in")
            speakJarvis("Switched to $query, boss.", "जी बॉस, $query अकाउंट खुल गया है।")
        } else {
            speakJarvis("Could not see $query on screen, boss.", "बॉस, $query स्क्रीन पर नहीं दिख रहा।")
        }
    }

    private fun runOpenTrade(cmd: ParsedCommand) {
        clickByText("Quotes")
        Thread.sleep(900)
        val symNode = findBestMatchingNode(rootInActiveWindow, cmd.symbol) ?: return
        tapNodeCenter(symNode)
        Thread.sleep(700)
        clickByText("New order")
        Thread.sleep(1000)
        findAllEditables(rootInActiveWindow).firstOrNull()?.let { setNodeText(it, cmd.lot) }
        askSafetyConfirm("CONFIRM ${cmd.side} ${cmd.symbol}") {
            clickByText(cmd.side)
            speakJarvis("${cmd.side} order executed, boss.", "बॉस, ${cmd.side} ऑर्डर लग गया है।")
            isBusy = false
        }
    }

    private fun runModifyTrade(cmd: ParsedCommand) {
        clickByText("Trade")
        Thread.sleep(900)
        if (!longClickByText(cmd.symbol)) return
        Thread.sleep(700)
        clickByText("Modify position")
        Thread.sleep(900)
        val v = cmd.sl.ifEmpty { cmd.tp }
        if (v.isNotEmpty()) findAllEditables(rootInActiveWindow).firstOrNull()?.let { setNodeText(it, v) }
        askSafetyConfirm("CONFIRM MODIFY") {
            clickByText("Modify")
            speakJarvis("Modified successfully, boss.", "बॉस, मॉडिफाई हो गया है।")
            isBusy = false
        }
    }

    private fun runCloseTrade(cmd: ParsedCommand) {
        clickByText("Trade")
        Thread.sleep(900)
        if (!longClickByText(cmd.symbol)) return
        Thread.sleep(700)
        clickByText("Close position")
        Thread.sleep(900)
        askSafetyConfirm("CONFIRM CLOSE") {
            clickByText("Close with")
            speakJarvis("Position closed, boss.", "बॉस, पोजीशन क्लोज़ हो गई है।")
            isBusy = false
        }
    }

    private fun askSafetyConfirm(label: String, onConfirm: () -> Unit) {
        pendingConfirmAction = onConfirm
        mainHandler.post {
            confirmBtn?.text = label
            confirmLayout?.visibility = View.VISIBLE
        }
        speakJarvis("Tap confirm to execute, boss.", "बॉस, कन्फर्म बटन दबाइए।")
    }

    private fun findBestMatchingNode(root: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val txt = n.text?.toString()?.lowercase() ?: ""
            if (txt.isNotEmpty()) {
                val score = words.count { txt.contains(it) }
                if (score > bestScore) { bestScore = score; bestNode = n }
            }
            for (i in 0 until n.childCount) traverse(n.getChild(i))
        }
        traverse(root)
        return bestNode
    }

    private fun findAllEditables(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return list
        if (root.isEditable) list.add(root)
        for (i in 0 until root.childCount) list.addAll(findAllEditables(root.getChild(i)))
        return list
    }

    private fun setNodeText(node: AccessibilityNodeInfo, value: String) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) tapCoordinates(rect.exactCenterX(), rect.exactCenterY())
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
        for (node in root.findAccessibilityNodeInfosByText(target)) {
            var cur: AccessibilityNodeInfo? = node
            while (cur != null) {
                if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                cur = cur.parent
            }
        }
        return false
    }

    private fun clickByDesc(desc: String): Boolean {
        fun search(n: AccessibilityNodeInfo?): Boolean {
            if (n == null) return false
            if (n.contentDescription?.toString()?.contains(desc, true) == true && n.isClickable) {
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            for (i in 0 until n.childCount) if (search(n.getChild(i))) return true
            return false
        }
        return search(rootInActiveWindow)
    }

    private fun longClickByText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (node in root.findAccessibilityNodeInfosByText(target)) {
            var cur: AccessibilityNodeInfo? = node
            while (cur != null) {
                if (cur.isLongClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                cur = cur.parent
            }
        }
        return false
    }

    private fun tapCoordinates(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
