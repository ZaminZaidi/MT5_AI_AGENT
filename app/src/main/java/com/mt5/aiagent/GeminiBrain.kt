package com.mt5.aiagent

import android.content.Context
import org.json.JSONObject

enum class TaskType {
    SCAN_ACCOUNTS,
    SWITCH_ACCOUNT,
    OPEN_TRADE,
    MODIFY_SL_TP,
    CLOSE_TRADE,
    READ_STATUS,
    NAVIGATE,
    TEACH_RULE,
    UNKNOWN
}

data class ParsedCommand(
    val type: TaskType,
    val symbol: String = "",
    val side: String = "",
    val lot: String = "",
    val sl: String = "",
    val tp: String = "",
    val accountQuery: String = "",
    val navTab: String = "",
    val summary: String = ""
)

object LearningMemory {
    private const val PREF_NAME = "mt5_agent_prefs"
    private const val KEY_RULES = "learned_rules_json"
    private const val KEY_DEFAULT_LOT = "learned_default_lot"
    private const val KEY_WAKE_WORD = "custom_wake_word"

    fun getWakeWord(ctx: Context): String {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WAKE_WORD, "jarvis")?.trim()?.lowercase().orEmpty().ifEmpty { "jarvis" }
    }

    fun setWakeWord(ctx: Context, word: String) {
        val clean = word.trim().lowercase().ifEmpty { "jarvis" }
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_WAKE_WORD, clean).apply()
    }

    fun getDefaultLot(ctx: Context): String {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT_LOT, "0.01") ?: "0.01"
    }

    fun learnDefaultLot(ctx: Context, lot: String) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEFAULT_LOT, lot).apply()
    }

    fun getRules(ctx: Context): MutableMap<String, String> {
        val raw = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RULES, "{}") ?: "{}"
        val map = mutableMapOf<String, String>()
        try {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.getString(k)
            }
        } catch (_: Exception) {}
        return map
    }

    fun saveRule(ctx: Context, triggerPhrase: String, mappedCommand: String) {
        val rules = getRules(ctx)
        rules[triggerPhrase.trim().lowercase()] = mappedCommand.trim()
        val json = JSONObject()
        rules.forEach { (k, v) -> json.put(k, v) }
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_RULES, json.toString()).apply()
    }
}

object MT5LocalBrain {

    private val symbolAliases = mapOf(
        "gold" to "XAUUSD",
        "xau" to "XAUUSD",
        "xauusd" to "XAUUSD",
        "silver" to "XAGUSD",
        "xag" to "XAGUSD",
        "btc" to "BTCUSD",
        "bitcoin" to "BTCUSD",
        "btcusd" to "BTCUSD",
        "nas" to "NAS100",
        "nasdaq" to "NAS100",
        "nas100" to "NAS100",
        "us30" to "US30",
        "eurusd" to "EURUSD",
        "euro" to "EURUSD",
        "gbpusd" to "GBPUSD",
        "usdjpy" to "USDJPY",
        "usdchf" to "USDCHF"
    )

    fun parse(ctx: Context, rawInput: String, savedAccounts: List<String>): ParsedCommand {
        var text = rawInput.lowercase().trim()

        // 0. Voice Teaching: "learn [phrase] means [command]"
        val learnMatch = Regex("""(?:learn|remember|teach)\s+(.+?)\s+(?:means|is|=)\s+(.+)""").find(text)
        if (learnMatch != null) {
            val trigger = learnMatch.groupValues[1].trim()
            val action = learnMatch.groupValues[2].trim()
            LearningMemory.saveRule(ctx, trigger, action)
            return ParsedCommand(TaskType.TEACH_RULE, summary = "🧠 Learned: '$trigger' → '$action'")
        }

        // 1. Check Learned Custom Rules first
        val learnedRules = LearningMemory.getRules(ctx)
        for ((trigger, mappedCmd) in learnedRules) {
            if (text == trigger || text.contains(trigger)) {
                text = mappedCmd.lowercase().trim()
                break
            }
        }

        // 2. Auto-scan accounts
        if (text.contains("scan") || text.contains("detect account") || text.contains("find account")) {
            return ParsedCommand(TaskType.SCAN_ACCOUNTS, summary = "🔍 Scanning all MT5 accounts...")
        }

        // 3. Balance / Status check
        if (text.contains("balance") || text.contains("equity") || text.contains("status") || text.contains("profit")) {
            return ParsedCommand(TaskType.READ_STATUS, summary = "💰 Reading live MT5 balance & equity...")
        }

        // 4. Switch / Open Account
        if (text.contains("account") || text.contains("switch") || text.contains("login") || text.contains("kholo")) {
            val matchedAcc = savedAccounts.firstOrNull { acc ->
                val words = acc.lowercase().split(" ", "-", "_", "#").filter { it.length >= 3 }
                words.any { w -> text.contains(w) }
            }
            val query = matchedAcc ?: text
                .replace("open", "").replace("switch", "").replace("to", "")
                .replace("account", "").replace("mt5", "").replace("kholo", "")
                .replace("wala", "").replace("karo", "").trim()

            if (query.isNotEmpty()) {
                return ParsedCommand(
                    type = TaskType.SWITCH_ACCOUNT,
                    accountQuery = query,
                    summary = "🔄 Switching MT5 account to: $query"
                )
            }
        }

        // Detect symbol
        var symbol = ""
        for ((alias, realSym) in symbolAliases) {
            if (text.contains(alias)) {
                symbol = realSym
                break
            }
        }

        // 5. Modify SL / TP
        if (text.contains("sl") || text.contains("stop loss") || text.contains("tp") || text.contains("take profit") || text.contains("modify")) {
            val slVal = Regex("""(?:sl|stop loss)\s*(?:to|at|ko|par)?\s*(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1) ?: ""
            val tpVal = Regex("""(?:tp|take profit)\s*(?:to|at|ko|par)?\s*(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1) ?: ""
            val targetSym = symbol.ifEmpty { "XAUUSD" }
            return ParsedCommand(
                type = TaskType.MODIFY_SL_TP,
                symbol = targetSym,
                sl = slVal,
                tp = tpVal,
                summary = "✏️ Modify $targetSym ${if (slVal.isNotEmpty()) "SL=$slVal " else ""}${if (tpVal.isNotEmpty()) "TP=$tpVal" else ""}".trim()
            )
        }

        // 6. Close Position
        if (text.contains("close") || text.contains("exit") || text.contains("hatao") || text.contains("band")) {
            val targetSym = symbol.ifEmpty { "ALL" }
            return ParsedCommand(
                type = TaskType.CLOSE_TRADE,
                symbol = targetSym,
                summary = "🛑 Close $targetSym position"
            )
        }

        // 7. Open Buy / Sell Order (Auto-learns lot size!)
        if (text.contains("buy") || text.contains("sell")) {
            val side = if (text.contains("buy")) "BUY" else "SELL"
            val explicitLot = Regex("""(\d+\.\d+)""").find(text)?.groupValues?.get(1)
            val lot = if (explicitLot != null) {
                LearningMemory.learnDefaultLot(ctx, explicitLot) // Auto-learn user's lot size
                explicitLot
            } else {
                LearningMemory.getDefaultLot(ctx) // Use learned lot size
            }
            val targetSym = symbol.ifEmpty { "XAUUSD" }
            return ParsedCommand(
                type = TaskType.OPEN_TRADE,
                symbol = targetSym,
                side = side,
                lot = lot,
                summary = "⚡ $side $targetSym ($lot lot)"
            )
        }

        // 8. Quick Tab Navigation
        if (text.contains("quote")) return ParsedCommand(TaskType.NAVIGATE, navTab = "Quotes", summary = "📊 Opening Quotes")
        if (text.contains("chart")) return ParsedCommand(TaskType.NAVIGATE, navTab = "Charts", summary = "📈 Opening Charts")
        if (text.contains("trade")) return ParsedCommand(TaskType.NAVIGATE, navTab = "Trade", summary = "💼 Opening Trade")
        if (text.contains("history")) return ParsedCommand(TaskType.NAVIGATE, navTab = "History", summary = "📜 Opening History")

        return ParsedCommand(
            TaskType.UNKNOWN,
            summary = "Say 'Learn [phrase] means [command]' to teach me!"
        )
    }
}
