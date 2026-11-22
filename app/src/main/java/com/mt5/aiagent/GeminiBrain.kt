package com.mt5.aiagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
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

    fun transcribeWithWhisper(audioFile: File): String {
        val apiKey = BuildConfig.GROQ_API_KEY.trim()
        if (apiKey.isBlank() || !audioFile.exists()) return ""

        val boundary = "----JarvisBoundary${System.currentTimeMillis()}"
        return try {
            val url = URL("https://api.groq.com/openai/v1/audio/transcriptions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connectTimeout = 8000
                readTimeout = 12000
            }

            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                out.writeBytes("whisper-large-v3-turbo\r\n")

                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n")
                out.writeBytes("MetaTrader 5, MT5, XAUUSD, Gold, BTCUSD, NAS100, Charts, Quotes, Trade, History, Buy, Sell, Lot, SL, TP, Account, WhatsApp.\r\n")

                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"voice.m4a\"\r\n")
                out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
                FileInputStream(audioFile).use { fis ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }
                }
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }

            if (conn.responseCode !in 200..299) return ""
            val resp = conn.inputStream.bufferedReader().readText()
            JSONObject(resp).optString("text", "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    fun parsePipeline(
        speechText: String,
        screenTexts: List<String>,
        savedAccounts: List<String>,
        credentials: List<SavedCredential>
    ): List<ParsedCommand> {
        val cleanSpeech = speechText.trim()
        if (cleanSpeech.length < 2) return emptyList()

        val apiKey = BuildConfig.GROQ_API_KEY.trim()
        if (apiKey.isBlank()) {
            return listOf(
                ParsedCommand(
                    TaskType.CHAT_REPLY,
                    replyEn = "Add GROQ_API_KEY in GitHub Secrets, boss.",
                    replyHi = "बॉस, GitHub Secrets में GROQ_API_KEY जोड़ें।"
                )
            )
        }

        val systemPrompt = """
            You are JARVIS, a world-class MetaTrader 5 & Android autonomous AI agent.
            The user speaks Hindi, Hinglish, or English. Break down even complex multi-step voice commands into an exact sequential JSON array under key "steps".
            
            Example: "Kapil ka account kholo aur gold ka chart laga ke 0.01 buy karo"
            -> Step 1: SWITCH_ACCOUNT (accountQuery: "Kapil")
            -> Step 2: OPEN_CHART (symbol: "XAUUSD")
            -> Step 3: OPEN_TRADE (symbol: "XAUUSD", side: "BUY", lot: "0.01")

            Known MT5 accounts: ${savedAccounts.take(15).joinToString(", ")}
            Current screen text: ${screenTexts.take(20).joinToString(", ")}

            Return ONLY valid JSON:
            {
              "steps": [
                {
                  "type": "NAVIGATE" | "SWITCH_ACCOUNT" | "OPEN_CHART" | "OPEN_TRADE" | "MODIFY_SL_TP" | "CLOSE_TRADE" | "READ_BRIEFING" | "OPEN_WHATSAPP_CHAT" | "SCAN_ACCOUNTS" | "CHAT_REPLY",
                  "navTab": "Quotes / Charts / Trade / History / Messages",
                  "accountQuery": "exact account name",
                  "symbol": "XAUUSD / BTCUSD / NAS100 / EURUSD / XAGUSD",
                  "side": "BUY or SELL",
                  "lot": "0.01",
                  "sl": "",
                  "tp": "",
                  "chatName": "",
                  "replyEn": "Short crisp Jarvis English reply (max 12 words)",
                  "replyHi": "Natural Hindi reply calling user बॉस (max 12 words)"
                }
              ]
            }
        """.trimIndent()

        return try {
            val url = URL("https://api.groq.com/openai/v1/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = 6000
                readTimeout = 10000
            }

            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", cleanSpeech))
            }

            val body = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("temperature", 0.05)
                put("response_format", JSONObject().put("type", "json_object"))
                put("messages", messages)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode !in 200..299) {
                return listOf(
                    ParsedCommand(
                        TaskType.CHAT_REPLY,
                        replyEn = "Groq HTTP ${conn.responseCode}, boss.",
                        replyHi = "बॉस, Groq एरर ${conn.responseCode} आया है।"
                    )
                )
            }

            val responseText = conn.inputStream.bufferedReader().readText()
            val content = JSONObject(responseText)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()

            val arr = JSONObject(content).getJSONArray("steps")
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
        } catch (e: Exception) {
            listOf(
                ParsedCommand(
                    TaskType.CHAT_REPLY,
                    replyEn = "Network error: ${e.javaClass.simpleName}",
                    replyHi = "बॉस, नेटवर्क एरर आया है।"
                )
            )
        }
    }
}
