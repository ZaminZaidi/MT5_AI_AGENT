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
import android.widget.EditText
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
            setPadding(44, 120, 44, 60)
        }

        val title = TextView(this).apply {
            text = "◉ J.A.R.V.I.S  MT5"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        }

        val sub = TextView(this).apply {
            text = "Hands-Free Autonomous Trading Assistant"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, 6, 0, 28)
        }

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg("#0F172A", 28f, "#1E293B")
            setPadding(36, 28, 36, 28)
        }
        statusTv = TextView(this).apply { textSize = 14f }
        statusCard.addView(statusTv)

        val langLabel = TextView(this).apply {
            text = "VOICE LANGUAGE"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
            setPadding(4, 28, 0, 10)
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
                refreshLangButtons(prefs)
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
                refreshLangButtons(prefs)
            }
        }
        langRow.addView(btnEn)
        langRow.addView(btnHi)
        refreshLangButtons(prefs)

        fun makeBtn(label: String, bgHex: String, strokeHex: String? = null, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                background = bg(bgHex, 24f, strokeHex)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 22, 0, 0) }
                setPadding(24, 34, 24, 34)
                setOnClickListener { onClick() }
            }
        }

        val btnLaunch = makeBtn("⚡ ACTIVATE JARVIS + OPEN MT5", "#0284C7", "#38BDF8") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                return@makeBtn
            }
            val svc = MT5AgentService.instance
            if (svc == null) {
                Toast.makeText(this, "Enable Accessibility first", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@makeBtn
            }
            svc.showFloatingAssistant()
            packageManager.getLaunchIntentForPackage("net.metaquotes.metatrader5")?.let { startActivity(it) }
        }

        val btnAcc = makeBtn("⚙️ ACCESSIBILITY SETTINGS", "#1E293B") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val vaultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg("#0F172A", 28f, "#1E293B")
            setPadding(36, 28, 36, 28)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 30, 0, 0) }
        }

        val vaultTitle = TextView(this).apply {
            text = "AUTO-LOGIN VAULT (LOCAL ONLY)"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        val vaultHint = TextView(this).apply {
            text = "Format per line: Nickname | Broker | LoginID | Password\nExample: MyXM | XMGlobal | 34200313 | Pass123"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, 8, 0, 14)
        }
        val credsInput = EditText(this).apply {
            hint = "MyXM | XMGlobal | 123456 | Password"
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            textSize = 13f
            minLines = 3
            background = bg("#060B19", 18f, "#334155")
            setPadding(24, 20, 24, 20)
            setText(prefs.getString("mt5_saved_creds", ""))
        }
        val btnSaveVault = makeBtn("💾 SAVE AUTO-LOGIN ACCOUNTS", "#16A34A") {
            prefs.edit().putString("mt5_saved_creds", credsInput.text.toString().trim()).apply()
            Toast.makeText(this, "Saved securely on phone!", Toast.LENGTH_SHORT).show()
        }

        vaultCard.addView(vaultTitle)
        vaultCard.addView(vaultHint)
        vaultCard.addView(credsInput)
        vaultCard.addView(btnSaveVault)

        layout.addView(title)
        layout.addView(sub)
        layout.addView(statusCard)
        layout.addView(langLabel)
        layout.addView(langRow)
        layout.addView(btnLaunch)
        layout.addView(btnAcc)
        layout.addView(vaultCard)

        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun refreshLangButtons(prefs: android.content.SharedPreferences) {
        val isHi = prefs.getString("jarvis_lang", "en") == "hi"
        btnEn.background = bg(if (!isHi) "#0284C7" else "#1E293B", 20f, if (!isHi) "#38BDF8" else null)
        btnHi.background = bg(if (isHi) "#0284C7" else "#1E293B", 20f, if (isHi) "#38BDF8" else null)
    }

    override fun onResume() {
        super.onResume()
        val running = MT5AgentService.instance != null
        statusTv.text = if (running) "● JARVIS ENGINE READY\n${MT5AgentService.lastBalanceSummary}" else "○ ENGINE OFFLINE — Enable Accessibility"
        statusTv.setTextColor(if (running) Color.parseColor("#4ADE80") else Color.parseColor("#F87171"))
    }
}
