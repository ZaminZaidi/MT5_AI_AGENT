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

    private val symbolAliases = mapOf(
        "gold" to "XAUUSD", "golda" to "XAUUSD", "sona" to "XAUUSD", "xau" to "XAUUSD", "xauusd" to "XAUUSD",
        "silver" to "XAGUSD", "chandi" to "XAGUSD", "xag" to "XAGUSD", "xagusd" to "XAGUSD",
        "btc" to "BTCUSD", "bitcoin" to "BTCUSD", "btcusd" to "BTCUSD",
        "nas" to "NAS100", "nasdaq" to "NAS100", "nas100" to "NAS100", "ustec" to "NAS100",
        "us30" to "US30", "dow" to "US30",
        "eurusd" to "EURUSD", "euro" to "EURUSD",
        "gbpusd" to "GBPUSD", "pound" to "GBPUSD",
        "usdjpy" to "USDJPY", "yen" to "USDJPY"
    )

    // Common SpeechRecognizer mishearings in trading context
    private val navAliases = mapOf(
        "chart" to "Charts", "charts" to "Charts", "charge" to "Charts", "change" to "Charts", "chard" to "Charts",
        "quote" to "Quotes", "quotes" to "Quotes", "coats" to "Quotes", "court" to "Quotes", "codes" to "Quotes",
        "trade" to "Trade", "trades" to "Trade", "tray" to "Trade", "treed" to "Trade",
        "history" to "History", "histri" to "History",
        "messages" to "Messages", "message" to "Messages"
    )

    private val fillerWords = setOf(
        "jarvis", "hey", "ok", "boss", "sir", "please", "open", "switch", "to", "into", "in",
        "account", "accounts", "akaunt", "mt5", "metatrader", "kholo", "khol", "kholna",
        "wala", "wali", "wale", "karo", "kar", "karna", "do", "de", "dena", "karke",
        "click", "on", "tap", "select", "login", "the", "my", "mera", "mere", "meri",
        "mein", "me", "mai", "par", "pe", "ko", "ka", "ki", "ke", "se", "aur", "and", "then",
        "whatsapp", "chat", "dikh", "raha", "hai", "he", "na", "usko", "use",
        "isko", "ye", "wo", "woh", "jara", "zara", "bhai", "screen", "dikhao", "batao", "go"
    )

    fun toEnglish(input: String): String {
        val consonants = mapOf(
            'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
            'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
            'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
            'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
            'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m",
            'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
            'ष' to "sh", 'स' to "s", 'ह' to "h", 'क़' to "k", 'ख़' to "kh",
            'ग़' to "g", 'ज़' to "j", 'ड़' to "d", 'ढ़' to "dh", 'फ़' to "f"
        )
        val vowels = mapOf(
            'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u",
            'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o"
        )
        val matras = mapOf(
            'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u",
            'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ृ' to "ri"
        )
        val sb = StringBuilder()
        val chars = input.toCharArray()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            when {
                c in consonants -> {
                    sb.append(consonants[c])
                    val next = if (i + 1 < chars.size) chars[i + 1] else ' '
                    if (next == '्') i++
                    else if (next in matras) { sb.append(matras[next]); i++ }
                    else if (next in consonants || next == 'ं' || next == 'ँ') sb.append("a")
                }
                c in vowels -> sb.append(vowels[c])
                c == 'ं' || c == 'ँ' -> sb.append("n")
                c == '़' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun phonetic(s: String): String {
        return toEnglish(s).lowercase()
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

    // Production Pipeline: Multi-Hypothesis High-Confidence Fast-Path -> Screen-Aware Cloud AI
    fun parsePipeline(
        speechAlternatives: List<String>,
        screenTexts: List<String>,
        savedAccounts: List<String>,
        credentials: List<SavedCredential>
    ): List<ParsedCommand> {
        val primary = speechAlternatives.firstOrNull()?.trim() ?: ""
        if (primary.isBlank()) return emptyList()

        // 1. Check all top speech hypotheses for a high-confidence instant command
        for (alt in speechAlternatives.take(5)) {
            val fastResult = parseHighConfidenceLocal(alt, savedAccounts)
            if (fastResult.isNotEmpty()) return fastResult
        }

        // 2. Ambiguous or conversational? Send top 3 hypotheses + live screen context to Gemini
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isNotBlank()) {
            val cloudResult = askGeminiSmart(apiKey, speechAlternatives.take(3), screenTexts, savedAccounts)
            if (cloudResult.isNotEmpty()) return cloudResult
        }

        return listOf(
            ParsedCommand(
                TaskType.CHAT_REPLY,
                replyEn = "Didn't catch that clearly, boss. Say it once more.",
                replyHi = "बॉस, आवाज़ साफ नहीं आई। एक बार फिर बोलिए।"
            )
        )
    }

    private fun parseHighConfidenceLocal(rawInput: String, savedAccounts: List<String>): List<ParsedCommand> {
        val engFull = toEnglish(rawInput).lowercase().trim()
        val chunks = engFull.split(Regex("\\s*(?:,|\\band\\b|\\bthen\\b|\\baur\\b|\\bkarke\\b|\\bkar ke\\b|\\bphir\\b|\\bfir\\b|\\buske baad\\b)\\s*"))
            .map { it.trim() }.filter { it.length >= 2 }

        val commands = mutableListOf<ParsedCommand>()
        for (chunk in chunks) {
            val parsed = parseSingleStrict(chunk, savedAccounts)
            if (parsed.type != TaskType.UNKNOWN) commands.add(parsed)
        }
        return commands
    }

    private fun detectSymbol(text: String): String {
        val pText = phonetic(text)
        for ((alias, realSym) in symbolAliases) if (pText.contains(alias)) return realSym
        return ""
    }

    private fun extractCleanQuery(text: String): String {
        return text.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-z0-9]"), "") }
            .filter { it.isNotEmpty() && it !in fillerWords }
            .distinct().joinToString(" ").trim()
    }

    private fun parseSingleStrict(text: String, savedAccounts: List<String>): ParsedCommand {
        val symbol = detectSymbol(text)
        val words = text.split(Regex("\\s+")).map { it.replace(Regex("[^a-z0-9]"), "") }.filter { it.isNotEmpty() }

        // 1. WhatsApp
        if (text.contains("whatsapp")) {
            val contact = extractCleanQuery(text)
            return ParsedCommand(
                type = TaskType.OPEN_WHATSAPP_CHAT, chatName = contact,
                replyEn = if (contact.isEmpty()) "Opening WhatsApp, boss." else "Opening $contact on WhatsApp, boss.",
                replyHi = if (contact.isEmpty()) "जी बॉस, व्हाट्सएप खोल रहा हूँ।" else "जी बॉस, व्हाट्सएप पर $contact का चैट खोल रहा हूँ।"
            )
        }

        // 2. Symbol Chart (e.g. "gold chart", "open xauusd chart")
        if (symbol.isNotEmpty() && words.any { it in setOf("chart", "charts", "charge", "change", "open", "kholo", "dikhao") }) {
            return ParsedCommand(
                type = TaskType.OPEN_CHART, symbol = symbol,
                replyEn = "Opening $symbol chart, boss.",
                replyHi = "जी बॉस, $symbol का चार्ट खोल रहा हूँ।"
            )
        }

        // 3. Direct MT5 Bottom Tab Navigation (catches "charts", "charge", "change", "quotes", "coats", "trade", "history")
        val matchedNav = words.firstOrNull { it in navAliases }
        if (matchedNav != null && !text.contains("account") && !text.contains("buy") && !text.contains("sell")) {
            val tab = navAliases[matchedNav]!!
            return ParsedCommand(
                type = TaskType.NAVIGATE, navTab = tab,
                replyEn = "Opening $tab, boss.",
                replyHi = "जी बॉस, $tab खोल रहा हूँ।"
            )
        }

        // 4. Live Balance / Drawdown Briefing
        if (words.any { it in setOf("status", "drawdown", "balance", "equity", "profit", "briefing") }) {
            return ParsedCommand(
                TaskType.READ_BRIEFING,
                replyEn = "Checking your live balance and drawdown, boss.",
                replyHi = "जी बॉस, आपका लाइव बैलेंस और ड्रॉडाउन चेक कर रहा हूँ।"
            )
        }

        // 5. Scan Accounts
        if (text.contains("scan") || text.contains("kitne account")) {
            return ParsedCommand(
                TaskType.SCAN_ACCOUNTS,
                replyEn = "Scanning all MT5 accounts, boss.",
                replyHi = "जी बॉस, सारे MT5 अकाउंट्स स्कैन कर रहा हूँ।"
            )
        }

        // 6. Trade Actions (Modify / Close / Buy / Sell)
        if (words.any { it in setOf("sl", "tp", "modify") } || text.contains("stop loss") || text.contains("take profit")) {
            val slVal = Regex("""(?:sl|stop loss)\s*(?:to|at|ko|par)?\s*(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1) ?: ""
            val tpVal = Regex("""(?:tp|take profit)\s*(?:to|at|ko|par)?\s*(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1) ?: ""
            val targetSym = symbol.ifEmpty { "XAUUSD" }
            return ParsedCommand(
                type = TaskType.MODIFY_SL_TP, symbol = targetSym, sl = slVal, tp = tpVal,
                replyEn = "Modifying $targetSym, boss.", replyHi = "जी बॉस, $targetSym मॉडिफाई कर रहा हूँ।"
            )
        }

        if (words.any { it in setOf("close", "exit", "hatao", "band") }) {
            val targetSym = symbol.ifEmpty { "ALL" }
            return ParsedCommand(
                type = TaskType.CLOSE_TRADE, symbol = targetSym,
                replyEn = "Preparing to close $targetSym, boss.", replyHi = "जी बॉस, $targetSym क्लोज़ कर रहा हूँ।"
            )
        }

        if (words.any { it in setOf("buy", "sell") }) {
            val side = if (words.contains("buy")) "BUY" else "SELL"
            val lot = Regex("""(\d+\.\d+)""").find(text)?.groupValues?.get(1) ?: "0.01"
            val targetSym = symbol.ifEmpty { "XAUUSD" }
            return ParsedCommand(
                type = TaskType.OPEN_TRADE, symbol = targetSym, side = side, lot = lot,
                replyEn = "Preparing $side order on $targetSym for$lot lot, boss.",
                replyHi = "जी बॉस, $targetSym पर $lot लॉट का $side ऑर्डर लगा रहा हूँ।"
            )
        }

        // 7. Strict Account Switch: ONLY if it matches a learned account OR user explicitly said "account"/"switch"
        val cleanQuery = extractCleanQuery(text)
        if (cleanQuery.isNotEmpty()) {
            val pWords = phonetic(cleanQuery).split(" ").filter { it.length >= 3 }
            val learnedMatch = savedAccounts.firstOrNull { acc ->
                val pAcc = phonetic(acc)
                pWords.any { pAcc.contains(it) }
            }
            if (learnedMatch != null) {
                return ParsedCommand(
                    type = TaskType.SWITCH_ACCOUNT, accountQuery = learnedMatch,
                    replyEn = "Switching to $learnedMatch, boss.",
                    replyHi = "जी बॉस, $learnedMatch अकाउंट खोल रहा हूँ।"
                )
            }
            if (text.contains("account") || text.contains("akaunt") || text.contains("switch")) {
                return ParsedCommand(
                    type = TaskType.SWITCH_ACCOUNT, accountQuery = cleanQuery,
                    replyEn = "Switching to $cleanQuery, boss.",
                    replyHi = "जी बॉस, $cleanQuery अकाउंट खोल रहा हूँ।"
                )
            }
        }

        // 8. Just "open MT5"
        if (text.contains("mt5") || text.contains("metatrader")) {
            return ParsedCommand(TaskType.NAVIGATE, navTab = "Trade", replyEn = "Opening MT5, boss.", replyHi = "जी बॉस, MT5 खोल रहा हूँ।")
        }

        return ParsedCommand(TaskType.UNKNOWN)
    }

    private fun askGeminiSmart(
        apiKey: String,
        speechAlts: List<String>,
        screenTexts: List<String>,
        savedAccounts: List<String>
    ): List<ParsedCommand> {
        val prompt = """
            You are JARVIS, an expert Android & MetaTrader 5 AI assistant.
            The phone microphone can mishear words (e.g. "charge"/"change" = "Charts", "coats" = "Quotes").
            Use the speech hypotheses AND what is currently on screen to infer the user's true intent.

            Speech guesses: ${speechAlts.joinToString(" | ")}
            Known MT5 accounts: ${savedAccounts.take(15).joinToString(", ")}
            Current screen text: ${screenTexts.take(20).joinToString(", ")}

            Return ONLY a JSON array of 1 or more objects:
            {
              "type": "NAVIGATE" | "SWITCH_ACCOUNT" | "OPEN_CHART" | "OPEN_TRADE" | "MODIFY_SL_TP" | "CLOSE_TRADE" | "READ_BRIEFING" | "OPEN_WHATSAPP_CHAT" | "SCAN_ACCOUNTS" | "CHAT_REPLY",
              "navTab": "Quotes / Charts / Trade / History / Messages",
              "accountQuery": "exact account name from known accounts or screen",
              "symbol": "XAUUSD / BTCUSD / NAS100 / EURUSD etc.",
              "side": "BUY or SELL",
              "lot": "0.01",
              "chatName": "",
              "replyEn": "Short natural JARVIS reply (max 12 words)",
              "replyHi": "Short natural Hindi reply calling user बॉस (max 12 words)"
            }
        """.trimIndent()

        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-goog-api-key", apiKey)
                doOutput = true
                connectTimeout = 5000
                readTimeout = 6500
            }

            val body = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                ))
                put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode !in 200..299) return emptyList()
            val responseText = conn.inputStream.bufferedReader().readText()
            var raw = JSONObject(responseText)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()

            raw = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val arr = if (raw.startsWith("[")) JSONArray(raw) else JSONArray().put(JSONObject(raw))
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
