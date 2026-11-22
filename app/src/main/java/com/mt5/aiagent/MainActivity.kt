package com.mt5.aiagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var btnEn: Button
    private lateinit var btnHi: Button
    private lateinit var btnAlwaysOn: Button

    private fun bg(hex: String, radius: Float = 30f, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(hex))
            cornerRadius = radius
            if (strokeHex != null) setStroke(2, Color.parseColor(strokeHex))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#060B19")) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 130, 48, 60)
        }

        val title = TextView(this).apply {
            text = "◉ J.A.R.V.I.S"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }

        val sub = TextView(this).apply {
            text = "Always-On Autonomous MT5 & Android Assistant"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, 6, 0, 32)
        }

        // Clean Status Card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg("#0F172A", 28f, "#1E293B")
            setPadding(36, 32, 36, 32)
        }
        statusTv = TextView(this).apply { textSize = 14f }
        statusCard.addView(statusTv)

        // Language Toggle
        val langLabel = TextView(this).apply {
            text = "VOICE LANGUAGE"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
            setPadding(4, 32, 0, 12)
        }

        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnEn = Button(this).apply {
            text = "ENGLISH (UK)"
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 12, 0) }
            setOnClickListener {
                prefs.edit().putString("jarvis_lang", "en").apply()
                MT5AgentService.instance?.applyVoiceProfile()
                refreshButtons(prefs)
            }
        }
        btnHi = Button(this).apply {
            text = "हिंदी (HINDI)"
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(12, 0, 0, 0) }
            setOnClickListener {
                prefs.edit().putString("jarvis_lang", "hi").apply()
                MT5AgentService.instance?.applyVoiceProfile()
                refreshButtons(prefs)
            }
        }
        langRow.addView(btnEn)
        langRow.addView(btnHi)

        fun makeBtn(label: String, bgHex: String, strokeHex: String? = null, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                background = bg(bgHex, 24f, strokeHex)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 24, 0, 0) }
                setPadding(24, 36, 24, 36)
                setOnClickListener { onClick() }
            }
        }

        btnAlwaysOn = makeBtn("🎙 24/7 BACKGROUND VOICE: ON", "#16A34A", "#4ADE80") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                return@makeBtn
            }
            val current = prefs.getBoolean("jarvis_always_on", true)
            MT5AgentService.instance?.setAlwaysOn(!current)
            prefs.edit().putBoolean("jarvis_always_on", !current).apply()
            refreshButtons(prefs)
        }

        val btnShowPill = makeBtn("⚡ SHOW FLOATING PILL + OPEN MT5", "#0284C7", "#38BDF8") {
            val svc = MT5AgentService.instance
            if (svc == null) {
                Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@makeBtn
            }
            svc.showFloatingAssistant(announce = true)
            packageManager.getLaunchIntentForPackage("net.metaquotes.metatrader5")?.let { startActivity(it) }
        }

        val btnAcc = makeBtn("⚙️ ACCESSIBILITY SETTINGS", "#1E293B") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val tipTv = TextView(this).apply {
            text = "Tip: Even if you close the floating pill (✕), just say 'Jarvis' anytime from any app and it will pop right back up."
            setTextColor(Color.parseColor("#64748B"))
            textSize = 12f
            setPadding(8, 36, 8, 0)
        }

        layout.addView(title)
        layout.addView(sub)
        layout.addView(statusCard)
        layout.addView(langLabel)
        layout.addView(langRow)
        layout.addView(btnAlwaysOn)
        layout.addView(btnShowPill)
        layout.addView(btnAcc)
        layout.addView(tipTv)

        scroll.addView(layout)
        setContentView(scroll)
        refreshButtons(prefs)
    }

    private fun refreshButtons(prefs: android.content.SharedPreferences) {
        val isHi = prefs.getString("jarvis_lang", "en") == "hi"
        btnEn.background = bg(if (!isHi) "#0284C7" else "#1E293B", 20f, if (!isHi) "#38BDF8" else null)
        btnHi.background = bg(if (isHi) "#0284C7" else "#1E293B", 20f, if (isHi) "#38BDF8" else null)

        val alwaysOn = prefs.getBoolean("jarvis_always_on", true)
        btnAlwaysOn.text = if (alwaysOn) "🎙 24/7 BACKGROUND VOICE: ON" else "🔇 24/7 BACKGROUND VOICE: OFF"
        btnAlwaysOn.background = bg(if (alwaysOn) "#16A34A" else "#334155", 24f, if (alwaysOn) "#4ADE80" else null)
    }

    override fun onResume() {
        super.onResume()
        val running = MT5AgentService.instance != null
        statusTv.text = if (running) {
            "● JARVIS 24/7 ENGINE ACTIVE\n${MT5AgentService.lastBalanceSummary}"
        } else {
            "○ ENGINE OFFLINE — Enable Accessibility"
        }
        statusTv.setTextColor(if (running) Color.parseColor("#4ADE80") else Color.parseColor("#F87171"))
    }
}
