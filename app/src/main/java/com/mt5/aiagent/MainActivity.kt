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
    private lateinit var memoryTv: TextView
    private lateinit var accountsTv: TextView

    private fun gradientCard(startHex: String, endHex: String, radius: Float = 32f, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
        ).apply {
            cornerRadius = radius
            if (strokeHex != null) setStroke(2, Color.parseColor(strokeHex))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#050811"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 130, 48, 70)
        }

        // Header
        val titleTv = TextView(this).apply {
            text = "⚡ MT5 NEURAL AGENT"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 24f
        }

        val subTv = TextView(this).apply {
            text = "Offline Self-Learning AI  •  Custom Wake Word"
            setTextColor(Color.parseColor("#00F0FF"))
            textSize = 13f
            setPadding(0, 6, 0, 32)
        }

        // 1. Live Engine Status Card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradientCard("#0F172A", "#1E293B", 32f, "#00F0FF")
            setPadding(36, 32, 36, 32)
        }
        statusTv = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        statusCard.addView(statusTv)

        fun makeNeonBtn(label: String, startHex: String, endHex: String, strokeHex: String? = null, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                textSize = 14f
                background = gradientCard(startHex, endHex, 26f, strokeHex)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 22, 0, 0) }
                setPadding(24, 34, 24, 34)
                setOnClickListener { onClick() }
            }
        }

        val btnLaunch = makeNeonBtn("🚀 LAUNCH FLOATING AI + OPEN MT5", "#00C853", "#059669", "#4ADE80") {
            val svc = MT5AgentService.instance
            if (svc == null) {
                Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@makeNeonBtn
            }
            svc.showFloatingAssistant()
            val mt5 = packageManager.getLaunchIntentForPackage("net.metaquotes.metatrader5")
            if (mt5 != null) startActivity(mt5)
        }

        val btnScan = makeNeonBtn("🔍 AUTO-SCAN MT5 ACCOUNTS", "#0284C7", "#2563EB", "#38BDF8") {
            val svc = MT5AgentService.instance
            if (svc == null) {
                Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_LONG).show()
                return@makeNeonBtn
            }
            svc.showFloatingAssistant()
            svc.executeCommand("scan accounts")
        }

        // 2. Custom Wake Word Card
        val wakeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradientCard("#0B1120", "#111827", 30f, "#1E293B")
            setPadding(36, 30, 36, 30)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 28, 0, 0) }
        }

        val wakeLabel = TextView(this).apply {
            text = "🎙 CUSTOM WAKE WORD"
            setTextColor(Color.parseColor("#00F0FF"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }

        val wakeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val wakeEt = EditText(this).apply {
            hint = "e.g. Jarvis, Ghost, Boss"
            setText(LearningMemory.getWakeWord(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = gradientCard("#1E293B", "#0F172A", 18f, "#334155")
            setPadding(24, 18, 24, 18)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSaveWake = Button(this).apply {
            text = "SET"
            setTextColor(Color.WHITE)
            background = gradientCard("#0284C7", "#2563EB", 18f)
            setOnClickListener {
                LearningMemory.setWakeWord(this@MainActivity, wakeEt.text.toString())
                Toast.makeText(this@MainActivity, "Wake word saved: ${wakeEt.text}", Toast.LENGTH_SHORT).show()
            }
        }
        wakeRow.addView(wakeEt)
        wakeRow.addView(btnSaveWake)
        wakeCard.addView(wakeLabel)
        wakeCard.addView(wakeRow)

        // 3. Auto-Learning & Custom Shortcuts Card
        val learnCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradientCard("#0B1120", "#111827", 30f, "#1E293B")
            setPadding(36, 30, 36, 30)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 24, 0, 0) }
        }

        val learnHeader = TextView(this).apply {
            text = "🧠 SELF-LEARNING MEMORY"
            setTextColor(Color.parseColor("#A855F7"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }

        val phraseEt = EditText(this).apply {
            hint = "When I say... (e.g. sniper)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = gradientCard("#1E293B", "#0F172A", 18f, "#334155")
            setPadding(24, 16, 24, 16)
        }
        val actionEt = EditText(this).apply {
            hint = "Do this... (e.g. buy gold 0.05)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = gradientCard("#1E293B", "#0F172A", 18f, "#334155")
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 14, 0, 14) }
        }
        val btnTeach = makeNeonBtn("+ TEACH AI THIS SHORTCUT", "#7C3AED", "#6D28D9", "#A855F7") {
            val p = phraseEt.text.toString().trim()
            val a = actionEt.text.toString().trim()
            if (p.isNotEmpty() && a.isNotEmpty()) {
                LearningMemory.saveRule(this, p, a)
                phraseEt.setText("")
                actionEt.setText("")
                refreshDashboard()
                Toast.makeText(this, "Learned: $p → $a", Toast.LENGTH_SHORT).show()
            }
        }
        memoryTv = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, 20, 0, 0)
        }
        learnCard.addView(learnHeader)
        learnCard.addView(phraseEt)
        learnCard.addView(actionEt)
        learnCard.addView(btnTeach)
        learnCard.addView(memoryTv)

        // 4. Permissions Row
        val permRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 24, 0, 0) }
        }
        val btnAcc = Button(this).apply {
            text = "⚙️ ACCESSIBILITY"
            setTextColor(Color.WHITE)
            textSize = 12f
            background = gradientCard("#1E293B", "#0F172A", 20f, "#334155")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val btnMic = Button(this).apply {
            text = "🎙 MIC PERMISSION"
            setTextColor(Color.WHITE)
            textSize = 12f
            background = gradientCard("#1E293B", "#0F172A", 20f, "#334155")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                } else {
                    Toast.makeText(this@MainActivity, "Microphone ready!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        permRow.addView(btnAcc)
        permRow.addView(btnMic)

        // 5. Discovered Accounts Card
        val accCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradientCard("#0B1120", "#111827", 30f, "#1E293B")
            setPadding(36, 30, 36, 30)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 24, 0, 0) }
        }
        val accHeader = TextView(this).apply {
            text = "📂 DISCOVERED MT5 ACCOUNTS"
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        accountsTv = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
        }
        accCard.addView(accHeader)
        accCard.addView(accountsTv)

        layout.addView(titleTv)
        layout.addView(subTv)
        layout.addView(statusCard)
        layout.addView(btnLaunch)
        layout.addView(btnScan)
        layout.addView(wakeCard)
        layout.addView(learnCard)
        layout.addView(permRow)
        layout.addView(accCard)

        scroll.addView(layout)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    private fun refreshDashboard() {
        val isRunning = MT5AgentService.instance != null
        statusTv.text = if (isRunning) {
            "● NEURAL ENGINE ONLINE\n${MT5AgentService.lastBalanceSummary}"
        } else {
            "○ ENGINE OFFLINE — Enable Accessibility below"
        }
        statusTv.setTextColor(if (isRunning) Color.parseColor("#00E676") else Color.parseColor("#F87171"))

        val rules = LearningMemory.getRules(this)
        val defLot = LearningMemory.getDefaultLot(this)
        val rulesText = if (rules.isEmpty()) "None yet (Teach above or say 'Learn x means y')"
        else rules.entries.joinToString("\n") { "• \"${it.key}\" → ${it.value}" }
        memoryTv.text = "Learned Default Lot: $defLot\n\nCustom Shortcuts:\n$rulesText"

        val saved = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)
            .getString("mt5_accounts_auto", "") ?: ""
        accountsTv.text = if (saved.isBlank()) "Tap 'AUTO-SCAN MT5 ACCOUNTS' to detect." else saved
    }
}
