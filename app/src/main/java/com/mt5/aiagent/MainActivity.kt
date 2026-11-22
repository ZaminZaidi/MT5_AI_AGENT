package com.mt5.aiagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("mt5_agent_prefs", Context.MODE_PRIVATE)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Extra top padding so Android 15 status bar never overlaps
            setPadding(44, 160, 44, 60)
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }

        val apiKeyLabel = TextView(this).apply { text = "Gemini API Key (saved locally on phone):" }
        val apiKeyInput = EditText(this).apply {
            hint = "Paste AIza... key here"
            setText(prefs.getString("gemini_api_key", ""))
        }

        val accountsLabel = TextView(this).apply {
            text = "Your MT5 Accounts Memory (Nickname = Broker / Login):"
            setPadding(0, 24, 0, 0)
        }
        val accountsInput = EditText(this).apply {
            hint = "e.g.\nGold Account = Exness-MT5Real7 #123456\nProp Account = Vantage #789012"
            minLines = 3
            setText(prefs.getString("mt5_accounts", ""))
        }

        val btnSave = Button(this).apply {
            text = "Save Settings"
            setOnClickListener {
                prefs.edit()
                    .putString("gemini_api_key", apiKeyInput.text.toString().trim())
                    .putString("mt5_accounts", accountsInput.text.toString().trim())
                    .apply()
                Toast.makeText(this@MainActivity, "Saved!", Toast.LENGTH_SHORT).show()
            }
        }

        val btnEnableAcc = Button(this).apply {
            text = "1. Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val btnMicPerm = Button(this).apply {
            text = "2. Allow Microphone Permission"
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                } else {
                    Toast.makeText(this@MainActivity, "Microphone already allowed!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnStartOverlay = Button(this).apply {
            text = "3. Launch Floating AI + Open MT5"
            setOnClickListener {
                val svc = MT5AgentService.instance
                if (svc == null) {
                    Toast.makeText(this@MainActivity, "Turn ON Accessibility Service first!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                svc.showFloatingAssistant()
                val mt5 = packageManager.getLaunchIntentForPackage("net.metaquotes.metatrader5")
                if (mt5 != null) startActivity(mt5)
            }
        }

        layout.addView(statusText)
        layout.addView(apiKeyLabel)
        layout.addView(apiKeyInput)
        layout.addView(accountsLabel)
        layout.addView(accountsInput)
        layout.addView(btnSave)
        layout.addView(btnEnableAcc)
        layout.addView(btnMicPerm)
        layout.addView(btnStartOverlay)

        scroll.addView(layout)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val isRunning = MT5AgentService.instance != null
        statusText.text = if (isRunning) "Status: ACTIVE (Ready)" else "Status: OFF (Enable Accessibility)"
    }
}
