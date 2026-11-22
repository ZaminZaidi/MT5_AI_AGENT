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
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MT5AgentService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        var instance: MT5AgentService? = null
        var lastBalanceSummary: String = "Open MT5 Trade tab to sync"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var pillRowView: LinearLayout? = null
    private var pillOrbTv: TextView? = null
    private var pillStatusTv: TextView? = null
    private var confirmLayout: LinearLayout? = null
    private var confirmBtn: Button? = null
    private var pendingConfirmAction: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var toneGen: ToneGenerator? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    private var isTtsReady = false
    private var isRecording = false
    private var isBusy = false
    private val autoStopRunnable = Runnable { if (isRecording) stopRecordingAndProcess() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this, this)
        try { toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 65) } catch (_: Exception) {}
    }

    fun isAlwaysOnEnabled(): Boolean {
        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("jarvis_always_on", true)
    }

    fun setAlwaysOn(enabled: Boolean) {
        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("jarvis_always_on", enabled).apply()
        if (enabled) showFloatingAssistant(announce = false) else hideFloatingAssistant()
    }

    private fun isHindi(): Boolean {
        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
        return prefs.getString("jarvis_lang", "en") == "hi"
    }

    private fun playChime(start: Boolean = true) {
        try {
            val tone = if (start) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK
            toneGen?.startTone(tone, 110)
        } catch (_: Exception) {}
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            applyVoiceProfile()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    fun applyVoiceProfile() {
        if (!isTtsReady) return
        val voices = tts?.voices ?: emptySet()
        if (isHindi()) {
            tts?.language = Locale("hi", "IN")
            val bestHi = voices
                .filter { it.locale.language == "hi" && !it.isNetworkConnectionRequired }
                .sortedByDescending { it.quality }
                .firstOrNull { it.name.contains("hid", true) || it.name.contains("hie", true) || it.name.contains("male", true) }
                ?: voices.filter { it.locale.language == "hi" }.maxByOrNull { it.quality }
            if (bestHi != null) tts?.voice = bestHi
            tts?.setPitch(0.96f)
            tts?.setSpeechRate(1.02f)
        } else {
            tts?.language = Locale.UK
            val bestUk = voices
                .filter { it.locale.language == "en" && it.locale.country == "GB" }
                .sortedByDescending { it.quality }
                .firstOrNull { it.name.contains("gbd", true) || it.name.contains("rjs", true) || it.name.contains("male", true) }
                ?: voices.filter { it.locale.language == "en" && it.locale.country == "GB" }.maxByOrNull { it.quality }
            if (bestUk != null) tts?.voice = bestUk
            tts?.setPitch(0.95f)
            tts?.setSpeechRate(1.02f)
        }
    }

    private fun speakJarvis(enText: String, hiText: String) {
        val text = if (isHindi()) hiText else enText
        updatePillState(text, "#00E5FF")
        if (!isTtsReady) return
        applyVoiceProfile()
        mainHandler.post {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j_${System.currentTimeMillis()}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == "net.metaquotes.metatrader5") {
            rootInActiveWindow?.let { root ->
                val allText = collectAllTexts(root)
                extractQuickBalance(allText)
                if (allText.any { it.equals("Accounts", true) }) {
                    autoLearnAccounts(allText)
                }
            }
        }
    }

    private fun autoLearnAccounts(allText: List<String>) {
        val ignore = setOf("Accounts", "Quotes", "Charts", "Trade", "History", "Messages", "Read Only", "Demo", "Real")
        val candidates = allText.filter { t ->
            t.length in 4..40 &&
            t !in ignore &&
            !t.contains("USD", true) &&
            !t.contains("Hedge", true) &&
            !t.contains("Hedging", true) &&
            !t.matches(Regex("^[0-9.,\\s-]+$"))
        }
        if (candidates.isEmpty()) return
        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
        val existing = (prefs.getString("mt5_accounts_auto", "") ?: "").lines().filter { it.isNotBlank() }.toMutableSet()
        if (existing.addAll(candidates)) {
            prefs.edit().putString("mt5_accounts_auto", existing.joinToString("\n")).apply()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        stopRecorderSafe()
        hideFloatingAssistant()
        tts?.shutdown()
        toneGen?.release()
        instance = null
    }

    private fun extractQuickBalance(allText: List<String>) {
        val balIdx = allText.indexOfFirst { it.contains("Balance", ignoreCase = true) }
        if (balIdx != -1 && balIdx + 1 < allText.size) {
            val eqIdx = allText.indexOfFirst { it.contains("Equity", ignoreCase = true) }
            val eqVal = if (eqIdx != -1 && eqIdx + 1 < allText.size) allText[eqIdx + 1] else "--"
            lastBalanceSummary = "Balance ${allText[balIdx + 1]}, Equity$eqVal"
        }
    }

    private fun pillBg(bgHex: String, strokeHex: String, radius: Float = 80f): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = radius
            setStroke(3, Color.parseColor(strokeHex))
        }
    }

    private fun updatePillState(msg: String, strokeHex: String = "#00E5FF") {
        mainHandler.post {
            pillStatusTv?.text = msg
            pillOrbTv?.setTextColor(Color.parseColor(strokeHex))
            pillRowView?.background = pillBg("#EE080D1F", strokeHex, 80f)
        }
    }

    fun showFloatingAssistant(announce: Boolean = true) {
        mainHandler.post {
            if (overlayView != null) return@post
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            pillRowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pillBg("#EE080D1F", "#00E5FF", 80f)
                setPadding(36, 20, 28, 20)
            }

            pillOrbTv = TextView(this).apply {
                text = "◉ "
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 16f
            }

            pillStatusTv = TextView(this).apply {
                text = if (isHindi()) "JARVIS • बोलने के लिए टैप करें" else "JARVIS • Tap to speak"
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

            pillRowView?.addView(pillOrbTv)
            pillRowView?.addView(pillStatusTv)
            pillRowView?.addView(closeBtn)

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
            wrapper.addView(pillRowView)
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
            var isDragging = false
            pillRowView?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        touchX = event.rawX; touchY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (abs(dx) > 12 || abs(dy) > 12) isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(wrapper, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging && !isBusy) {
                            if (isRecording) stopRecordingAndProcess() else startWhisperRecording()
                        }
                        true
                    }
                    else -> false
                }
            }

            overlayView = wrapper
            windowManager?.addView(overlayView, params)
            if (announce) {
                playChime(true)
                speakJarvis("Jarvis ready, boss. Tap pill to speak.", "जार्विस तैयार है बॉस। बोलने के लिए टैप करें।")
            }
        }
    }

    fun hideFloatingAssistant() {
        mainHandler.post {
            stopRecorderSafe()
            overlayView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
            overlayView = null
        }
    }

    @Suppress("DEPRECATION")
    private fun startWhisperRecording() {
        try {
            tts?.stop()
            val outFile = File(cacheDir, "jarvis_cmd.m4a")
            if (outFile.exists()) outFile.delete()
            audioFile = outFile

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(16000)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            playChime(true)
            updatePillState(
                if (isHindi()) "🎙 सुन रहा हूँ... (भेजने को टैप करें)" else "🎙 Listening... (Tap to send)",
                "#EC4899"
            )
            mainHandler.removeCallbacks(autoStopRunnable)
            mainHandler.postDelayed(autoStopRunnable, 12000L) // Auto-send after 12s
        } catch (e: Exception) {
            isRecording = false
            updatePillState("Mic error: Allow Microphone permission", "#EF4444")
        }
    }

    private fun stopRecorderSafe() {
        mainHandler.removeCallbacks(autoStopRunnable)
        isRecording = false
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
    }

    private fun stopRecordingAndProcess() {
        stopRecorderSafe()
        playChime(false)
        val file = audioFile ?: return
        isBusy = true
        updatePillState(if (isHindi()) "⚡ Whisper सुन रहा है..." else "⚡ Transcribing...", "#A855F7")

        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
        val savedList = (prefs.getString("mt5_accounts_auto", "") ?: "").lines().filter { it.isNotBlank() }
        val creds = MT5LocalBrain.parseCredentials(prefs.getString("mt5_saved_creds", "") ?: "")
        val currentScreen = collectAllTexts(rootInActiveWindow)

        executor.execute {
            try {
                val transcript = MT5LocalBrain.transcribeWithWhisper(file)
                if (transcript.isBlank()) {
                    speakJarvis("Couldn't hear clearly, boss. Tap and try again.", "बॉस, आवाज़ साफ नहीं आई। दोबारा टैप करके बोलें।")
                    return@execute
                }

                updatePillState("🧠 \"$transcript\"", "#38BDF8")
                val pipeline = MT5LocalBrain.parsePipeline(transcript, currentScreen, savedList, creds)
                if (pipeline.isEmpty()) return@execute

                val first = pipeline.first()
                speakJarvis(first.replyEn, first.replyHi)

                if (first.type == TaskType.UNKNOWN || first.type == TaskType.CHAT_REPLY) return@execute

                for (step in pipeline) {
                    when (step.type) {
                        TaskType.OPEN_WHATSAPP_CHAT -> runOpenWhatsApp(step.chatName)
                        else -> {
                            ensureMT5Open()
                            when (step.type) {
                                TaskType.SCAN_ACCOUNTS -> runAutoScanAccounts()
                                TaskType.SWITCH_ACCOUNT -> runSwitchAccount(step.accountQuery)
                                TaskType.LOGIN_NEW_ACCOUNT -> step.credential?.let { runAutoLoginAccount(it) }
                                TaskType.OPEN_CHART -> runOpenSymbolChart(step.symbol)
                                TaskType.READ_BRIEFING -> runMT5Briefing()
                                TaskType.NAVIGATE -> waitForAndClickText(step.navTab, 3500)
                                TaskType.OPEN_TRADE -> runOpenTrade(step)
                                TaskType.MODIFY_SL_TP -> runModifyTrade(step)
                                TaskType.CLOSE_TRADE -> runCloseTrade(step)
                                else -> {}
                            }
                        }
                    }
                    Thread.sleep(700)
                }

                if (pipeline.size > 1 && pendingConfirmAction == null) {
                    playChime(true)
                    speakJarvis("All steps done, boss.", "जी बॉस, सारे काम पूरे हो गए।")
                }
            } catch (_: Exception) {
                speakJarvis("Encountered an error, boss.", "माफ कीजिए बॉस, कोई एरर आ गया।")
            } finally {
                if (pendingConfirmAction == null) {
                    isBusy = false
                    mainHandler.postDelayed({
                        if (!isRecording && !isBusy) {
                            updatePillState(
                                if (isHindi()) "JARVIS • बोलने के लिए टैप करें" else "JARVIS • Tap to speak",
                                "#00E5FF"
                            )
                        }
                    }, 3000)
                }
            }
        }
    }

    // Auto-Wait Helper: polls screen until button appears instead of blind clicking
    private fun waitForAndClickText(target: String, timeoutMs: Long = 3500): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (clickByText(target)) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun waitForBestNode(query: String, timeoutMs: Long = 3500): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val node = findBestMatchingNode(rootInActiveWindow, query)
            if (node != null) return node
            Thread.sleep(250)
        }
        return null
    }

    private fun runOpenSymbolChart(symbol: String) {
        waitForAndClickText("Quotes", 3000)
        val symNode = waitForBestNode(symbol, 3500)
        if (symNode != null) {
            tapNodeCenter(symNode)
            waitForAndClickText("Chart", 2500)
            speakJarvis("$symbol chart is open, boss.", "बॉस, $symbol का चार्ट खुल गया है।")
        } else {
            speakJarvis("Could not find $symbol in Quotes, boss.", "बॉस, Quotes में $symbol नहीं मिला।")
        }
    }

    private fun runOpenWhatsApp(contactName: String) {
        val waIntent = packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
        if (waIntent == null) {
            speakJarvis("WhatsApp is not installed, boss.", "बॉस, व्हाट्सएप इंस्टॉल नहीं है।")
            return
        }
        waIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(waIntent)
        Thread.sleep(1500)
        if (contactName.isNotEmpty()) {
            val chatNode = waitForBestNode(contactName, 2500)
            if (chatNode != null) {
                tapNodeCenter(chatNode)
                speakJarvis("Opened $contactName's chat, boss.", "बॉस, $contactName का चैट खुल गया है।")
            } else {
                clickByDesc("Search")
                Thread.sleep(600)
                findAllEditables(rootInActiveWindow).firstOrNull()?.let {
                    setNodeText(it, contactName)
                    waitForBestNode(contactName, 2500)?.let { n -> tapNodeCenter(n) }
                }
            }
        }
    }

    private fun runMT5Briefing() {
        waitForAndClickText("Trade", 3000)
        Thread.sleep(800)
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
            val enMsg = if (diff < 0) "Balance $balStr, equity $eqStr, drawdown$ddPercent percent."
            else "Balance $balStr, equity$eqStr. Zero drawdown."
            val hiMsg = if (diff < 0) "बॉस, बैलेंस $balStr, इक्विटी $eqStr और ड्रॉडाउन $ddPercent प्रतिशत है।"
            else "बॉस, बैलेंस $balStr और इक्विटी $eqStr है। कोई ड्रॉडाउन नहीं है।"
            speakJarvis(enMsg, hiMsg)
        } else {
            speakJarvis("Balance is $balStr and Equity is$eqStr, boss.", "बॉस, बैलेंस $balStr और इक्विटी $eqStr है।")
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
        waitForAndClickText("Manage accounts", 3000)
        Thread.sleep(800)
    }

    private fun runAutoLoginAccount(cred: SavedCredential) {
        openManageAccounts()
        if (!clickByDesc("Add") && !clickByDesc("New")) {
            val dm = resources.displayMetrics
            tapCoordinates(dm.widthPixels * 0.80f, dm.heightPixels * 0.08f)
        }
        Thread.sleep(1000)
        findAllEditables(rootInActiveWindow).firstOrNull()?.let { setNodeText(it, cred.broker) }
        waitForBestNode(cred.broker, 3000)?.let { tapNodeCenter(it); Thread.sleep(1000) }
        val fields = findAllEditables(rootInActiveWindow)
        if (fields.size >= 2) {
            setNodeText(fields[0], cred.loginId)
            Thread.sleep(250)
            setNodeText(fields[1], cred.password)
            waitForAndClickText("Login", 2500)
            speakJarvis("Logged into ${cred.nickname}, boss.", "बॉस, ${cred.nickname} लॉगिन हो गया है।")
        }
    }

    private fun runAutoScanAccounts() {
        openManageAccounts()
        val allText = collectAllTexts(rootInActiveWindow)
        autoLearnAccounts(allText)
        val saved = (getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
            .getString("mt5_accounts_auto", "") ?: "").lines().filter { it.isNotBlank() }
        speakJarvis("Learned ${saved.size} accounts, boss.", "स्कैन पूरा हुआ बॉस, ${saved.size} अकाउंट्स याद कर लिए।")
    }

    private fun runSwitchAccount(query: String) {
        openManageAccounts()
        val matched = waitForBestNode(query, 3000)
        if (matched != null) {
            tapNodeCenter(matched)
            waitForAndClickText("Sign in", 3000)
            Thread.sleep(1000)
            speakJarvis("Switched to $query, boss.", "जी बॉस, $query अकाउंट खुल गया है।")
        } else {
            speakJarvis("Could not see $query on screen, boss.", "बॉस, $query स्क्रीन पर नहीं दिख रहा।")
        }
    }

    private fun runOpenTrade(cmd: ParsedCommand) {
        waitForAndClickText("Quotes", 3000)
        val symNode = waitForBestNode(cmd.symbol, 3500) ?: return
        tapNodeCenter(symNode)
        waitForAndClickText("New order", 2500)
        Thread.sleep(800)
        findAllEditables(rootInActiveWindow).firstOrNull()?.let { setNodeText(it, cmd.lot) }
        askSafetyConfirm("CONFIRM ${cmd.side}${cmd.symbol}") {
            waitForAndClickText(cmd.side, 2500)
            speakJarvis("${cmd.side} order executed, boss.", "बॉस, ${cmd.side} ऑर्डर लग गया है।")
            isBusy = false
        }
    }

    private fun runModifyTrade(cmd: ParsedCommand) {
        waitForAndClickText("Trade", 3000)
        Thread.sleep(700)
        if (!longClickByText(cmd.symbol)) return
        waitForAndClickText("Modify position", 2500)
        Thread.sleep(700)
        val v = cmd.sl.ifEmpty { cmd.tp }
        if (v.isNotEmpty()) findAllEditables(rootInActiveWindow).firstOrNull()?.let { setNodeText(it, v) }
        askSafetyConfirm("CONFIRM MODIFY") {
            waitForAndClickText("Modify", 2500)
            speakJarvis("Modified successfully, boss.", "बॉस, मॉडिफाई हो गया है।")
            isBusy = false
        }
    }

    private fun runCloseTrade(cmd: ParsedCommand) {
        waitForAndClickText("Trade", 3000)
        Thread.sleep(700)
        if (!longClickByText(cmd.symbol)) return
        waitForAndClickText("Close position", 2500)
        Thread.sleep(700)
        askSafetyConfirm("CONFIRM CLOSE") {
            waitForAndClickText("Close with", 2500)
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
        val pWords = MT5LocalBrain.phonetic(query).split(Regex("\\s+")).filter { it.length >= 2 }
        if (pWords.isEmpty()) return null
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val rawTxt = n.text?.toString() ?: ""
            if (rawTxt.isNotEmpty()) {
                val pTxt = MT5LocalBrain.phonetic(rawTxt)
                val score = pWords.count { pTxt.contains(it) }
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
