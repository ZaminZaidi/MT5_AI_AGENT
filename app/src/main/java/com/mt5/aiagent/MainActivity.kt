package com.mt5.aiagent

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var subStatusTv: TextView
    private lateinit var btnEn: Button
    private lateinit var btnHi: Button
    private lateinit var btnAlwaysOn: Button
    private lateinit var orbGlow: View

    private fun glassCard(radius: Float = 36f, strokeHex: String = "#3338BDF8"): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#1A1F35"), Color.parseColor("#0D1224"))
        ).apply {
            cornerRadius = radius
            setStroke(2, Color.parseColor(strokeHex))
        }
    }

    private fun gradientBtn(c1: String, c2: String, radius: Float = 32f): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor(c1), Color.parseColor(c2))
        ).apply {
            cornerRadius = radius
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = Color.parseColor("#050814")
        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)

        val rootBg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#050814"), Color.parseColor("#0B1126"), Color.parseColor("#050814"))
        )

        val scroll = ScrollView(this).apply { background = rootBg }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(52, 110, 52, 70)
        }

        val orbContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(240, 240).apply { setMargins(0, 10, 0, 24) }
        }

        orbGlow = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#6366F1"), Color.parseColor("#06B6D4"), Color.parseColor("#EC4899"))
            ).apply { shape = GradientDrawable.OVAL }
            alpha = 0.55f
            layoutParams = FrameLayout.LayoutParams(220, 220, Gravity.CENTER)
        }

        val orbCore = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#818CF8"), Color.parseColor("#C084FC"))
            ).apply { shape = GradientDrawable.OVAL }
            layoutParams = FrameLayout.LayoutParams(150, 150, Gravity.CENTER)
        }

        orbContainer.addView(orbGlow)
        orbContainer.addView(orbCore)

        ObjectAnimator.ofPropertyValuesHolder(
            orbGlow,
            PropertyValuesHolder.ofFloat("scaleX", 0.92f, 1.15f),
            PropertyValuesHolder.ofFloat("scaleY", 0.92f, 1.15f),
            PropertyValuesHolder.ofFloat("alpha", 0.35f, 0.75f)
        ).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        val title = TextView(this).apply {
            text = "J.A.R.V.I.S  AI"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.12f
        }

        val subtitle = TextView(this).apply {
            text = "Autonomous MT5 Voice Intelligence"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, 8, 0, 36)
        }

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCard(36f, "#3338BDF8")
            setPadding(40, 34, 40, 34)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        statusTv = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        subStatusTv = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 8, 0, 0)
        }
        statusCard.addView(statusTv)
        statusCard.addView(subStatusTv)

        val langLabel = TextView(this).apply {
            text = "VOICE ENGINE"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(8, 36, 0, 12) }
        }

        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        btnEn = Button(this).apply {
            text = "English (UK)"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 12, 0) }
            setPadding(16, 32, 16, 32)
            setOnClickListener {
                prefs.edit().putString("jarvis_lang", "en").apply()
                MT5AgentService.instance?.applyVoiceProfile()
                refreshButtons(prefs)
            }
        }
        btnHi = Button(this).apply {
            text = "हिंदी (Hindi)"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(12, 0, 0, 0) }
            setPadding(16, 32, 16, 32)
            setOnClickListener {
                prefs.edit().putString("jarvis_lang", "hi").apply()
                MT5AgentService.instance?.applyVoiceProfile()
                refreshButtons(prefs)
            }
        }
        langRow.addView(btnEn)
        langRow.addView(btnHi)

        fun makeActionBtn(label: String, c1: String, c2: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                isAllCaps = false
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = gradientBtn(c1, c2, 32f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 24, 0, 0) }
                setPadding(24, 38, 24, 38)
                setOnClickListener { onClick() }
            }
        }

        btnAlwaysOn = makeActionBtn("🎙  Floating Assistant: ON", "#059669", "#10B981") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                return@makeActionBtn
            }
            val current = prefs.getBoolean("jarvis_always_on", true)
            MT5AgentService.instance?.setAlwaysOn(!current)
            prefs.edit().putBoolean("jarvis_always_on", !current).apply()
            refreshButtons(prefs)
        }

        val btnLaunch = makeActionBtn("✨  Launch Assistant & Open MT5", "#4F46E5", "#06B6D4") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                return@makeActionBtn
            }
            val svc = MT5AgentService.instance
            if (svc == null) {
                Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@makeActionBtn
            }
            svc.showFloatingAssistant(announce = true)
            packageManager.getLaunchIntentForPackage("net.metaquotes.metatrader5")?.let { startActivity(it) }
        }

        val btnAcc = makeActionBtn("⚙️  Accessibility Permissions", "#1E293B", "#334155") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        layout.addView(orbContainer)
        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(statusCard)
        layout.addView(langLabel)
        layout.addView(langRow)
        layout.addView(btnAlwaysOn)
        layout.addView(btnLaunch)
        layout.addView(btnAcc)

        scroll.addView(layout)
        setContentView(scroll)
        refreshButtons(prefs)
    }

    private fun refreshButtons(prefs: android.content.SharedPreferences) {
        val isHi = prefs.getString("jarvis_lang", "en") == "hi"
        btnEn.background = if (!isHi) gradientBtn("#4F46E5", "#06B6D4", 28f) else glassCard(28f, "#1E293B")
        btnHi.background = if (isHi) gradientBtn("#4F46E5", "#06B6D4", 28f) else glassCard(28f, "#1E293B")

        val alwaysOn = prefs.getBoolean("jarvis_always_on", true)
        btnAlwaysOn.text = if (alwaysOn) "🎙  Floating Assistant: ON" else "🔇  Floating Assistant: OFF"
        btnAlwaysOn.background = if (alwaysOn) gradientBtn("#059669", "#10B981", 32f) else glassCard(32f, "#334155")
    }

    override fun onResume() {
        super.onResume()
        val running = MT5AgentService.instance != null
        statusTv.text = if (running) "●  Neural Engine Active" else "○  Engine Offline"
        statusTv.setTextColor(if (running) Color.parseColor("#34D399") else Color.parseColor("#F87171"))
        subStatusTv.text = if (running) MT5AgentService.lastBalanceSummary else "Enable Accessibility Service to activate"
    }
}
