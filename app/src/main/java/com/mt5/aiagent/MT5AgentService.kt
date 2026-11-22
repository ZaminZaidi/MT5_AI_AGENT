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
import android.media.ToneGenerator
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
    private var toneGen: ToneGenerator? = null

    private var isTtsReady = false
    private var isSpeaking = false
    private var isBusy = false
    private var activeSessionUntil: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this, this)
        try { toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 65) } catch (_: Exception) {}

        // Auto-start 24/7 background wake-word listening without opening the app
        mainHandler.postDelayed({
            if (isAlwaysOnEnabled()) {
                startHandsFreeListening()
            }
        }, 1200)
    }

    fun isAlwaysOnEnabled(): Boolean {
        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("jarvis_always_on", true)
    }

    fun setAlwaysOn(enabled: Boolean) {
        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("jarvis_always_on", enabled).apply()
        if (enabled) {
            startHandsFreeListening()
        } else {
            mainHandler.post {
                try { speechRecognizer?.stopListening() } catch (_: Exception) {}
                hideFloatingAssistant()
            }
        }
    }

    private fun isHindi(): Boolean {
        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
        return prefs.getString("jarvis_lang", "en") == "hi"
    }

    private fun playJarvisChime() {
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120) } catch (_: Exception) {}
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            applyVoiceProfile()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeaking = true }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (isAlwaysOnEnabled() && !isBusy) {
                        mainHandler.postDelayed({ startHandsFreeListening() }, 200)
                    }
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    if (isAlwaysOnEnabled() && !isBusy) {
                        mainHandler.postDelayed({ startHandsFreeListening() }, 400)
                    }
                }
            })
        }
    }

    fun applyVoiceProfile() {
        if (!isTtsReady) return
        val voices = tts?.voices ?: emptySet()
        if (isHindi()) {
            tts?.language = Locale("hi", "IN")
            val bestHi = voices.firstOrNull { it.name.contains("hi-in-x-hid", true) }
                ?: voices.firstOrNull { it.name.contains("hi-in-x-hie", true) }
                ?: voices.firstOrNull { it.locale.language == "hi" && it.name.contains("male", true) }
            if (bestHi != null) tts?.voice = bestHi
            tts?.setPitch(0.76f)
            tts?.setSpeechRate(0.98f)
        } else {
            tts?.language = Locale.UK
            val bestUk = voices.firstOrNull { it.name.contains("en-gb-x-gbd", true) }
                ?: voices.firstOrNull { it.name.contains("en-gb-x-rjs", true) }
                ?: voices.firstOrNull { it.locale.language == "en" && it.locale.country == "GB" && it.name.contains("male", true) }
            if (bestUk != null) tts?.voice = bestUk
            tts?.setPitch(0.74f)
            tts?.setSpeechRate(0.97f)
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
            mainHandler.postDelayed({
                val dm = resources.displayMetrics
                val path = Path().apply {
                    moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.85f)
                    lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.25f)
                }
                dispatchGesture(
                    GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(),
                    null, null
                )
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
        toneGen?.release()
        instance = null
    }

    private fun extractQuickBalance(root: AccessibilityNodeInfo) {
        val allText = collectAllTexts(root)
        val balIdx = allText.indexOfFirst { it.contains("Balance", ignoreCase = true) }
        if (balIdx != -1 && balIdx + 1 < allText.size) {
            val eqIdx = allText.indexOfFirst { it.contains("Equity", ignoreCase = true) }
            val eqVal = if (eqIdx != -1 && eqIdx + 1 < allText.size) allText[eqIdx + 1] else "--"
            lastBalanceSummary = "Balance ${allText[balIdx + 1]}, Equity$eqVal"
        }
    }

    private fun pillBg(bgHex: String, strokeHex: String, radius: Float = 60f): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = radius
            setStroke(3, Color.parseColor(strokeHex))
        }
    }

    fun showFloatingAssistant(announce: Boolean = true) {
        mainHandler.post {
            if (overlayView != null) return@post
            activeSessionUntil = System.currentTimeMillis() + 45_000L
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
                text = if (isHindi()) "JARVIS एक्टिव • सीधे बोलिए" else "JARVIS Active • Speak now"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
            }

            val closeBtn = TextView(this).apply {
                text = "   ✕"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 14f
                setOnClickListener {
                    // Hides pill ONLY — background wake-word keeps listening!
                    hideFloatingAssistant()
                }
            }

            pillRow.addView(orb)
            pillRow.addView(
