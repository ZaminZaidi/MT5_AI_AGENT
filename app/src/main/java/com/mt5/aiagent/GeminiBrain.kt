package com.mt5.aiagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

enum class TaskType {
    SCAN_ACCOUNTS,
    SWITCH_ACCOUNT,
    LOGIN_NEW_ACCOUNT,
    OPEN_CHART,
    OPEN_TRADE,
    MODIFY_SL_TP,
    CLOSE_TRADE,
    READ_BRIEFING,
    OPEN_WHATSAPP_CHAT,
    NAVIGATE,
    CHAT_REPLY,
    UNKNOWN
}

data class SavedCredential(
    val nickname: String,
    val broker: String,
    val loginId: String,
    val password: String
)

data class ParsedCommand(
    val type: TaskType,
    val symbol: String = "",
    val side: String = "",
    val lot: String = "",
    val sl: String = "",
    val tp: String = "",
    val accountQuery: String = "",
    val chatName: String = "",
    val credential: SavedCredential? = null,
    val navTab: String = "",
    val replyEn: String = "",
    val replyHi: String = ""
)

object MT5LocalBrain {

    fun phonetic(s: String): String {
        return s.lowercase()
            .replace("q", "k").replace("z", "j").replace("ph", "f")
            .replace("w", "v").replace("ee", "i").replace("oo", "u").replace("aa", "a")
            .replace(Regex("[^a-z0-9 ]"), "").trim()
    }

    fun parseCredentials(raw: String): List<SavedCredential> {
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|").map { it.trim() }
            if (parts.size >= 4) SavedCredential(parts[0], parts[1], parts[2], parts[3]) else null
        }
    }

    fun parsePipeline(
        rawInput: String,
        savedAccounts: List<String>,
        credentials: List<SavedCredential>
    ): List<ParsedCommand> {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isNotBlank()) {
            val cloudResult = askGeminiCloud(apiKey, rawInput, savedAccounts)
            if (cloudResult.isNotEmpty()) return cloudResult
        }
        return listOf(
            ParsedCommand(
                TaskType.CHAT_REPLY,
                replyEn = "Cloud brain offline, boss. Check internet or API secret.",
                replyHi = "बॉस, क्लाउड एआई ऑफलाइन है। इंटरनेट या एपीआई की चेक करें।"
            )
        )
    }

    private fun askGeminiCloud(apiKey: String, userSpeech: String, savedAccounts: List<String>): List<ParsedCommand> {
        val prompt = """
            You are JARVIS, an Iron Man style Android & MetaTrader 5 trading assistant.
            The user speaks Hindi, Hinglish, or English. Convert their request into a JSON ARRAY of step-by-step actions.
            
            User said: "$userSpeech"
            Known MT5 accounts: ${savedAccounts.joinToString(", ")}
            
            Return ONLY a valid JSON array (no markdown, no extra text). Each object:
            {
              "type": "SWITCH_ACCOUNT" | "OPEN_CHART" | "OPEN_TRADE" | "MODIFY_SL_TP" | "CLOSE_TRADE" | "READ_BRIEFING" | "OPEN_WHATSAPP_CHAT" | "SCAN_ACCOUNTS" | "NAVIGATE" | "CHAT_REPLY",
              "accountQuery": "English spelling of account name",
              "symbol": "XAUUSD / BTCUSD / NAS100 / EURUSD etc.",
              "side": "BUY or SELL",
              "lot": "0.01",
              "sl": "",
              "tp": "",
              "chatName": "English spelling of WhatsApp contact",
              "navTab": "Quotes / Charts / Trade / History",
              "replyEn": "Short Iron-Man JARVIS English reply (max 12 words)",
              "replyHi": "Short natural Hindi reply calling user बॉस (max 12 words)"
            }
            
            Example: "Saqib ka account khol ke gold ka chart lagao" ->
            [{"type":"SWITCH_ACCOUNT","accountQuery":"Sakib","replyEn":"Switching to Sakib and opening Gold chart, boss.","replyHi":"जी बॉस, साकिब अकाउंट खोलकर गोल्ड का चार्ट लगा रहा हूँ।"},{"type":"OPEN_CHART","symbol":"XAUUSD","replyEn":"Opening Gold chart.","replyHi":"गोल्ड चार्ट खोल रहा हूँ।"}]
            
            For general questions or casual talk, use type "CHAT_REPLY" with a smart answer in replyEn & replyHi.
        """.trimIndent()

        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 15000
            }

            val body = JSONObject().put(
                "contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode !in 200..299) return emptyList()
            val responseText = conn.inputStream.bufferedReader().readText()
            var raw = JSONObject(responseText)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()

            raw = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val arr = JSONArray(raw)
            val list = mutableListOf<ParsedCommand>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = try { TaskType.valueOf(o.optString("type", "CHAT_REPLY")) } catch (_: Exception) { TaskType.CHAT_REPLY }
                list.add(
                    ParsedCommand(
                        type = t,
                        symbol = o.optString("symbol", ""),
                        side = o.optString("side", ""),
                        lot = o.optString("lot", "0.01"),
                        sl = o.optString("sl", ""),
                        tp = o.optString("tp", ""),
                        accountQuery = o.optString("accountQuery", ""),
                        chatName = o.optString("chatName", ""),
                        navTab = o.optString("navTab", ""),
                        replyEn = o.optString("replyEn", "Right away, boss."),
                        replyHi = o.optString("replyHi", "जी बॉस।")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
