package com.mt5.aiagent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        statusText = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }

        val btnEnable = Button(this).apply {
            text = "1. Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnOpenMt5 = Button(this).apply {
            text = "2. Open MT5 & Capture Screen"
            setOnClickListener {
                val launchIntent = packageManager.getLaunchIntentForPackage("net.metaquotes.metatrader5")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this@MainActivity, "MT5 is not installed on this phone", Toast.LENGTH_SHORT).show()
                }
            }
        }

        logText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 32, 0, 0)
        }

        val scrollView = ScrollView(this).apply {
            addView(logText)
        }

        layout.addView(statusText)
        layout.addView(btnEnable)
        layout.addView(btnOpenMt5)
        layout.addView(scrollView)

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        val isRunning = MT5AgentService.instance != null
        statusText.text = if (isRunning) {
            "Status: ACTIVE (Ready to control MT5)"
        } else {
            "Status: OFF (Tap button 1 to enable)"
        }
        logText.text = "Last MT5 Screen Read:\n\n${MT5AgentService.lastCapturedScreen}"
    }
}
