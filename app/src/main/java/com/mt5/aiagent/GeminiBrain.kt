package com.mt5.aiagent

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
        "usdjpy" to "USDJPY", "yen" to "USDJPY",
        "usdchf" to "USDCHF", "usdcad" to "USDCAD", "audusd" to "AUDUSD"
    )

    private val fillerWords = setOf(
        "jarvis", "hey", "ok", "boss", "sir", "please", "open", "switch", "to", "into", "in",
        "account", "accounts", "akaunt", "mt5", "metatrader", "kholo", "khol", "kholna",
        "wala", "wali", "wale", "karo", "kar", "karna", "do", "de", "dena", "karke",
        "click", "on", "tap", "select", "login", "the", "my", "mera", "mere", "meri",
        "mein", "me", "mai", "par", "pe", "ko", "ka", "ki", "ke", "se", "aur", "and", "then",
        "whatsapp", "chat", "message", "dikh", "raha", "hai", "he", "na", "usko", "use",
        "isko", "ye", "wo", "woh", "jara", "zara", "bhai", "screen", "chart", "charta",
        "dikhao", "batao", "check", "lagao", "laga", "hatana", "hatao", "band", "kardo"
    )

    // Devanagari to English transliterator
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
                    if (next == '्') {
                        i++
                    } else if (next in matras) {
                        sb.append(matras[next])
                        i++
                    } else if (next in consonants || next == 'ं' || next == 'ँ') {
                        sb.append("a")
                    }
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

    // Phonetic normalizer so "saqib" == "sakib", "zaidi" == "jaidi", "gold" == "gold"
    fun phonetic(s: String): String {
        return toEnglish(s).lowercase()
            .replace("q", "k")
            .replace("z", "j")
            .replace("ph", "f")
            .replace("w", "v")
            .replace("ee", "i")
            .replace("oo", "u")
            .replace("aa", "a")
            .replace(Regex("[^a-z0-9 ]"), "")
            .trim()
    }

    fun parseCredentials(raw: String): List<SavedCredential> {
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|").map { it.trim() }
            if (parts.size >= 4) SavedCredential(parts[0], parts[1], parts[2], parts[3]) else null
        }
    }

    // Multi-step command splitter (handles "Sakib account open karke gold ka chart open karo")
    fun parsePipeline(rawInput: String, savedAccounts: List<String>, credentials: List<SavedCredential>): List<ParsedCommand> {
        val engFull = toEnglish(rawInput).lowercase().trim()
        val chunks = engFull.split(Regex("\\s*(?:,|\\band\\b|\\bthen\\b|\\baur\\b|\\bkarke\\b|\\bkar ke\\b|\\bphir\\b|\\bfir\\b|\\buske baad\\b)\\s*"))
            .map { it.trim() }
            .filter { it.length >= 2 }

        val commands = mutableListOf<ParsedCommand>()
        for (chunk in chunks) {
            val parsed = parseSingle(chunk, savedAccounts, credentials)
            if (parsed.type != TaskType.UNKNOWN) {
                commands.add(parsed)
            }
        }
        return if (commands.isEmpty()) {
            listOf(
                ParsedCommand(
                    TaskType.UNKNOWN,
                    replyEn = "Sorry boss, I didn't catch that. Please repeat.",
                    replyHi = "माफ कीजिए बॉस, समझ नहीं आया। कृपया दोबारा बोलिए।"
                )
            )
        } else {
            commands
        }
    }

    private fun detectSymbol(text: String): String {
        val pText = phonetic(text)
        for ((alias, realSym) in symbolAliases) {
            if (pText.contains(alias)) return realSym
        }
        return ""
    }

    private fun parseSingle(text: String, savedAccounts: List<String>, credentials: List<SavedCredential>): ParsedCommand {
        val symbol = detectSymbol(text)

        // 1. WhatsApp
        if (text.contains("whatsapp") || text.contains("chat")) {
            val contact = extractCleanQuery(text)
            return ParsedCommand(
                type = TaskType.OPEN_WHATSAPP_CHAT,
                chatName = contact,
                replyEn = if (contact.isEmpty()) "Opening WhatsApp, boss." else "Opening $contact on WhatsApp, boss.",
                replyHi = if (contact.isEmpty()) "जी बॉस, व्हाट्सएप खोल रहा हूँ।" else "जी बॉस, व्हाट्सएप पर $contact का चैट खोल रहा हूँ।"
            )
        }

        // 2. Open Specific Symbol Chart (e.g. "gold ka chart open karo")
        if (text.contains("chart") && symbol.isNotEmpty()) {
            return ParsedCommand(
                type = TaskType.OPEN_CHART,
                symbol = symbol,
                replyEn = "Opening $symbol chart, boss.",
                replyHi = "जी बॉस, $symbol का चार्ट खोल रहा हूँ।"
            )
        }

        // 3. Status & Drawdown Briefing
        if (text.contains("status") || text.contains("drawdown") || text.contains("briefing") ||
            text.contains("balance") || text.contains("equity") || text.contains("profit")) {
            return ParsedCommand(
                TaskType.READ_BRIEFING,
                replyEn = "Checking your live balance and drawdown, boss.",
                replyHi = "जी बॉस, आपका लाइव बैलेंस और ड्रॉडाउन चेक कर रहा हूँ।"
            )
        }

        // 4. Auto-Login saved credential
        if (text.contains("login") || text.contains("log in")) {
            val pText = phonetic(text)
            val matchedCred = credentials.firstOrNull { cred ->
                pText.contains(phonetic(cred.nickname)) ||
                pText.contains(phonetic(cred.broker)) ||
                text.contains(cred.loginId)
            }
            if (matchedCred != null) {
                return ParsedCommand(
                    type = TaskType.LOGIN_NEW_ACCOUNT,
                    credential = matchedCred,
                    replyEn = "Logging into ${matchedCred.nickname}, boss.",
                    replyHi = "जी बॉस, ${matchedCred.nickname} लॉगिन कर रहा हूँ।"
                )
            }
        }

        // 5. Scan accounts
        if (text.contains("scan") || text.contains("kitne account") || text.contains("how many account")) {
            return ParsedCommand(
                TaskType.SCAN_ACCOUNTS,
                replyEn = "Scanning all MT5 accounts, boss.",
                replyHi = "जी बॉस, सारे MT5 अकाउंट्स स्कैन कर रहा हूँ।"
            )
        }

        // 6. Modify SL / TP
        if (text.contains("sl") || text.contains("stop loss") || text.contains("tp") || text.contains("take profit") || text.contains("modify")) {
            val slVal = Regex("""(?:sl|stop loss)\s*(?:to|at|ko|par)?\s*(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1) ?: ""
            val tpVal = Regex("""(?:tp|take profit)\s*(?:to|at|ko|par)?\s*(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1) ?: ""
            val targetSym = symbol.ifEmpty { "XAUUSD" }
            return ParsedCommand(
                type = TaskType.MODIFY_SL_TP,
                symbol = targetSym, sl = slVal, tp = tpVal,
                replyEn = "Modifying $targetSym, boss.",
                replyHi = "जी बॉस, $targetSym मॉडिफाई कर रहा हूँ।"
            )
        }

        // 7. Close Trade
        if (text.contains("close") || text.contains("exit") || text.contains("hatao") || text.contains("band")) {
            val targetSym = symbol.ifEmpty { "ALL" }
            return ParsedCommand(
                type = TaskType.CLOSE_TRADE,
                symbol = targetSym,
                replyEn = "Preparing to close $targetSym, boss.",
                replyHi = "जी बॉस, $targetSym क्लोज़ कर रहा हूँ।"
            )
        }

        // 8. Buy / Sell
        if (text.contains("buy") || text.contains("sell")) {
            val side = if (text.contains("buy")) "BUY" else "SELL"
            val lot = Regex("""(\d+\.\d+)""").find(text)?.groupValues?.get(1) ?: "0.01"
            val targetSym = symbol.ifEmpty { "XAUUSD" }
            return ParsedCommand(
                type = TaskType.OPEN_TRADE,
                symbol = targetSym, side = side, lot = lot,
                replyEn = "Preparing $side order on $targetSym for$lot lot, boss.",
                replyHi = "जी बॉस, $targetSym पर $lot लॉट का $side ऑर्डर लगा रहा हूँ।"
            )
        }

        // 9. Switch Account or Tab Navigation
        if (text.contains("account") || text.contains("akaunt") || text.contains("switch") ||
            text.contains("khol") || text.contains("open") || text.contains("click") || text.contains("chart")) {

            val cleanQuery = extractCleanQuery(text)
            if (cleanQuery.isEmpty() && text.contains("chart")) {
                return ParsedCommand(TaskType.NAVIGATE, navTab = "Charts", replyEn = "Opening Charts, boss.", replyHi = "चार्ट खोल रहा हूँ बॉस।")
            }
            when (cleanQuery) {
                "quotes", "quote" -> return ParsedCommand(TaskType.NAVIGATE, navTab = "Quotes", replyEn = "Opening Quotes, boss.", replyHi = "कोट्स खोल रहा हूँ बॉस।")
                "charts", "chart" -> return ParsedCommand(TaskType.NAVIGATE, navTab = "Charts", replyEn = "Opening Charts, boss.", replyHi = "चार्ट खोल रहा हूँ बॉस।")
                "trade", "trades" -> return ParsedCommand(TaskType.NAVIGATE, navTab = "Trade", replyEn = "Opening Trade tab, boss.", replyHi = "ट्रेड टैब खोल रहा हूँ बॉस।")
                "history" -> return ParsedCommand(TaskType.NAVIGATE, navTab = "History", replyEn = "Opening History, boss.", replyHi = "हिस्ट्री खोल रहा हूँ बॉस।")
            }

            if (cleanQuery.isNotEmpty()) {
                val pWords = phonetic(cleanQuery).split(" ").filter { it.length >= 3 }
                val matchedAcc = savedAccounts.firstOrNull { acc ->
                    val pAcc = phonetic(acc)
                    pWords.any { pAcc.contains(it) }
                }
                val finalQuery = matchedAcc ?: cleanQuery
                return ParsedCommand(
                    type = TaskType.SWITCH_ACCOUNT,
                    accountQuery = finalQuery,
                    replyEn = "Switching to $finalQuery, boss.",
                    replyHi = "जी बॉस, $finalQuery अकाउंट खोल रहा हूँ।"
                )
            }
        }

        return ParsedCommand(TaskType.UNKNOWN)
    }

    private fun extractCleanQuery(text: String): String {
        return text.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-z0-9]"), "") }
            .filter { it.isNotEmpty() && it !in fillerWords }
            .distinct()
            .joinToString(" ")
            .trim()
    }
}
