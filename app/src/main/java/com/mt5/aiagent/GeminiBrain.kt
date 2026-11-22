package com.mt5.aiagent

enum class TaskType {
    SCAN_ACCOUNTS,
    SWITCH_ACCOUNT,
    LOGIN_NEW_ACCOUNT,
    OPEN_TRADE,
    MODIFY_SL_TP,
    CLOSE_TRADE,
    READ_BRIEFING,
    OPEN_WHATSAPP_CHAT,
    NAVIGATE,
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
        "gold" to "XAUUSD", "सोना" to "XAUUSD", "xau" to "XAUUSD", "xauusd" to "XAUUSD",
        "silver" to "XAGUSD", "xag" to "XAGUSD",
        "btc" to "BTCUSD", "bitcoin" to "BTCUSD", "btcusd" to "BTCUSD",
        "nas" to "NAS100", "nasdaq" to "NAS100", "nas100" to "NAS100",
        "us30" to "US30", "eurusd" to "EURUSD", "gbpusd" to "GBPUSD", "usdjpy" to "USDJPY"
    )

    private val fillerWords = setOf(
        "jarvis", "जार्विस", "hey", "ok", "boss", "sir", "please", "open", "switch",
        "to", "account", "accounts", "mt5", "kholo", "खोलो", "wala", "वाला", "karo", "करो",
        "click", "on", "tap", "select", "login", "लॉगिन", "in", "into", "the", "my", "मेरा", "में",
        "whatsapp", "व्हाट्सएप", "chat", "चैट", "ka", "का", "ki", "की", "ke", "के", "message", "मैसेज"
    )

    fun parseCredentials(raw: String): List<SavedCredential> {
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|").map { it.trim() }
            if (parts.size >= 4) SavedCredential(parts[0], parts[1], parts[2], parts[3]) else null
        }
    }

    fun parse(rawInput: String, savedAccounts: List<String>, credentials: List<SavedCredential>): ParsedCommand {
        val text = rawInput.lowercase().trim()

        // 1. WhatsApp Open / Open Specific Chat
        if (text.contains("whatsapp") || text.contains("व्हाट्सएप") || text.contains("chat") || text.contains("चैट")) {
            val cleanWords = text.split(Regex("\\s+"))
                .map { it.replace(Regex("[^a-z0-9\\u0900-\\u097F]"), "") }
                .filter { it.isNotEmpty() && it !in fillerWords }
                .distinct()
            val contact = cleanWords.joinToString(" ").trim()
            return ParsedCommand(
                type = TaskType.OPEN_WHATSAPP_CHAT,
                chatName = contact,
                replyEn = if (contact.isEmpty()) "Opening WhatsApp, boss." else "Opening $contact's chat on WhatsApp, boss.",
                replyHi = if (contact.isEmpty()) "जी बॉस, व्हाट्सएप खोल रहा हूँ।" else "जी बॉस, व्हाट्सएप पर $contact का चैट खोल रहा हूँ।"
            )
        }

        // 2. Full MT5 Trading Status & Drawdown Briefing
        if (text.contains("status") || text.contains("स्टेटस") || text.contains("drawdown") ||
            text.contains("ड्रॉडाउन") || text.contains("briefing") || text.contains("ब्रीफिंग") ||
            text.contains("balance") || text.contains("बैलेंस") || text.contains("equity") || text.contains("profit")) {
            return ParsedCommand(
                TaskType.READ_BRIEFING,
                replyEn = "Checking your MT5 trading status and drawdown, boss.",
                replyHi = "जी बॉस, आपके MT5 का ट्रेडिंग स्टेटस और ड्रॉडाउन चेक कर रहा हूँ।"
            )
        }

        // 3. Auto-Login saved credential
        if (text.contains("login") || text.contains("log in") || text.contains("लॉगिन") || text.contains("लॉग इन")) {
            val matchedCred = credentials.firstOrNull { cred ->
                text.contains(cred.nickname.lowercase()) ||
                text.contains(cred.broker.lowercase()) ||
                text.contains(cred.loginId)
            }
            if (matchedCred != null) {
                return ParsedCommand(
                    type = TaskType.LOGIN_NEW_ACCOUNT,
                    credential = matchedCred,
                    replyEn = "Logging into ${matchedCred.nickname} on ${matchedCred.broker}, boss.",
                    replyHi = "जी बॉस, ${matchedCred.nickname} अकाउंट को MT5 में लॉगिन कर रहा हूँ।"
                )
            }
        }

        // 4. Scan accounts
        if (text.contains("scan") || text.contains("स्कैन") || text.contains("कितने अकाउंट") || text.contains("how many account")) {
            return ParsedCommand(
                TaskType.SCAN_ACCOUNTS,
                replyEn = "Scanning all logged-in MT5 accounts, boss.",
                replyHi = "जी बॉस, चेक कर रहा हूँ कि MT5 में कितने अकाउंट लॉगिन हैं।"
            )
        }

        // 5. Switch existing account
        if (text.contains("account") || text.contains("अकाउंट") || text.contains("switch") ||
            text.contains("kholo") || text.contains("खोलो") || text.contains("open") || text.contains("click")) {

            val cleanWords = text.split(Regex("\\s+"))
                .map { it.replace(Regex("[^a-z0-9\\u0900-\\u097F]"), "") }
                .filter { it.isNotEmpty() && it !in fillerWords }
                .distinct()

            val cleanQuery = cleanWords.joinToString(" ").trim()

            when (cleanQuery) {
                "quotes", "quote" -> return ParsedCommand(TaskType.NAVIGATE, navTab = "Quotes", replyEn = "Opening Quotes, boss.", replyHi = "कोट्स खोल रहा हूँ बॉस।")
                "charts", "chart" -> return ParsedCommand(TaskType.NAVIGATE, navTab = "Charts", replyEn = "Opening Charts, boss.", replyHi = "चार्ट खोल रहा हूँ बॉस।")
                "trade", "trades" -> return ParsedCommand(TaskType.NAVIGATE, navTab = "Trade", replyEn = "Opening Trade tab, boss.", replyHi = "ट्रेड टैब खोल रहा हूँ बॉस।")
                "history" -> return ParsedCommand(TaskType.NAVIGATE, navTab = "History", replyEn = "Opening History, boss.", replyHi = "हिस्ट्री खोल रहा हूँ बॉस।")
            }

            if (cleanQuery.isNotEmpty()) {
                val matchedAcc = savedAccounts.firstOrNull { acc ->
                    cleanWords.any { w -> w.length >= 3 && acc.lowercase().contains(w) }
                }
                val finalQuery = matchedAcc ?: cleanQuery
                return ParsedCommand(
                    type = TaskType.SWITCH_ACCOUNT,
                    accountQuery = finalQuery,
                    replyEn = "Right away boss, switching to $finalQuery.",
                    replyHi = "जी बॉस, $finalQuery अकाउंट स्विच कर रहा हूँ।"
                )
            }
        }

        var symbol = ""
        for ((alias, realSym) in symbolAliases) {
            if (text.contains(alias)) { symbol = realSym; break }
        }

        if (text.contains("sl") || text.contains("stop loss") || text.contains("tp") || text.contains("take profit") || text.contains("modify")) {
            val slVal = Regex("""(?:sl|stop loss)\s*(?:to|at|ko|par)?\s*(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1) ?: ""
            val tpVal = Regex("""(?:tp|take profit)\s*(?:to|at|ko|par)?\s*(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1) ?: ""
            val targetSym = symbol.ifEmpty { "XAUUSD" }
            return ParsedCommand(
                type = TaskType.MODIFY_SL_TP,
                symbol = targetSym, sl = slVal, tp = tpVal,
                replyEn = "Modifying $targetSym position, boss.",
                replyHi = "जी बॉस, $targetSym की पोजीशन मॉडिफाई कर रहा हूँ।"
            )
        }

        if (text.contains("close") || text.contains("exit") || text.contains("hatao") || text.contains("हटाओ") || text.contains("बंद")) {
            val targetSym = symbol.ifEmpty { "ALL" }
            return ParsedCommand(
                type = TaskType.CLOSE_TRADE,
                symbol = targetSym,
                replyEn = "Preparing to close $targetSym, boss.",
                replyHi = "जी बॉस, $targetSym पोजीशन क्लोज़ करने के लिए तैयार हूँ।"
            )
        }

        if (text.contains("buy") || text.contains("बाय") || text.contains("sell") || text.contains("सेल")) {
            val side = if (text.contains("buy") || text.contains("बाय")) "BUY" else "SELL"
            val lot = Regex("""(\d+\.\d+)""").find(text)?.groupValues?.get(1) ?: "0.01"
            val targetSym = symbol.ifEmpty { "XAUUSD" }
            return ParsedCommand(
                type = TaskType.OPEN_TRADE,
                symbol = targetSym, side = side, lot = lot,
                replyEn = "Setting up $side order on $targetSym for $lot lot, boss.",
                replyHi = "जी बॉस, $targetSym पर $lot लॉट का $side ऑर्डर लगा रहा हूँ।"
            )
        }

        return ParsedCommand(
            TaskType.UNKNOWN,
            replyEn = "Sorry boss, I didn't catch that. Please repeat.",
            replyHi = "माफ कीजिए बॉस, मुझे समझ नहीं आया। कृपया दोबारा बोलिए।"
        )
    }
}
