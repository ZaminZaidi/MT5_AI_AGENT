package com.mt5.aiagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class AgentAction(
    val action: String,      // CLICK, LONG_CLICK, TYPE, BACK, OPEN_MT5, CONFIRM, DONE
    val target: String = "", // Exact text on screen to click/long-click
    val value: String = "",  // Text to type (e.g. SL price or lot size)
    val message: String = "" // Status message shown to user
)

object GeminiBrain {

    fun decideNextAction(
        apiKey: String,
        userGoal: String,
        accountMemory: String,
        screenTree: String,
        history: List<String>
    ): AgentAction {
        if (apiKey.isBlank()) {
            return AgentAction("DONE", message = "Please save your Gemini API Key in the app first.")
        }

        val prompt = """
            You are an autonomous Android MT5 AI Assistant controlling MetaTrader 5 on the user's phone.
            
            USER GOAL: "$userGoal"
            
            SAVED MT5 ACCOUNTS MEMORY:
            $accountMemory
            
            ACTIONS TAKEN SO FAR:
            ${if (history.isEmpty()) "None" else history.joinToString("\n")}
            
            CURRENT SCREEN UI ELEMENTS:
            $screenTree
            
            RULES:
            1. Return ONLY a valid JSON object. No markdown, no extra text.
            2. Allowed actions:
               - {"action":"OPEN_MT5", "message":"Opening MT5..."}
               - {"action":"CLICK", "target":"Exact text from screen", "message":"Tapping ..."}
               - {"action":"LONG_CLICK", "target":"Exact text from screen", "message":"Long-pressing ..."}
               - {"action":"TYPE", "target":"Exact text or hint of input box", "value":"2650.00", "message":"Typing ..."}
               - {"action":"BACK", "message":"Going back..."}
               - {"action":"CONFIRM", "target":"Exact final button text like Modify/Close/Buy/Sell", "message":"Confirm: [explain exact trade action]?"}
               - {"action":"DONE", "message":"Task completed or explanation"}
            3. SAFETY RULE: Before clicking any final execution button (BUY, SELL, CLOSE, MODIFY), ALWAYS return action "CONFIRM" so the user can approve it with 1 tap.
            4. If the goal is already achieved, return "DONE".
        """.trimIndent()

        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 20000
            }

            val body = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", prompt)
                    ))
                ))
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseText = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                return AgentAction("DONE", message = "API Error: $err")
            }

            val json = JSONObject(responseText)
            var rawReply = json
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            // Strip markdown code fences if present
            rawReply = rawReply.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val actionJson = JSONObject(rawReply)
            AgentAction(
                action = actionJson.optString("action", "DONE"),
                target = actionJson.optString("target", ""),
                value = actionJson.optString("value", ""),
                message = actionJson.optString("message", "Working...")
            )
        } catch (e: Exception) {
            AgentAction("DONE", message = "Error: ${e.message}")
        }
    }
}
