package com.personal.heyclaude

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A note you asked Mehitha to remember. */
data class Note(val at: LocalDateTime, val text: String)

/** A reminder Claude worked out, to be shown as a notification at [at]. */
data class ReminderNote(val at: LocalDateTime, val text: String)

/**
 * The words Mehitha sends to Claude for each command, with the phone's own data filled in.
 * Each is one paragraph (no line breaks) so the Claude app shows it as one message.
 * Plain Kotlin so it can be tested.
 */
object Prompts {

    private val STAMP = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.US)
    private val SHORT = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.US)

    const val RULES = "Facts only: if you can't find something, say so, never guess. Never place orders, trades or payments, and never send messages."
    private const val REMIND_HOW = "If there is a date worth a reminder, add a line exactly like REMIND: YYYY-MM-DD HH:MM | what to remind, and I will set it on my phone."

    private fun now(t: LocalDateTime) = "It is ${t.format(STAMP)} in India."

    fun briefing(t: LocalDateTime, bills: String, scan: String) = oneLine(
        "Give me my morning briefing. ${now(t)} Check my Outlook calendar for today's meetings, my Outlook email and Slack for anything important or waiting on me, " +
            "and my Gmail for bills or payments due this week. Bills due from my text messages: $bills. " +
            "My stock watchlist from today's scan: $scan. Keep it to the 5 things that matter most today. $RULES"
    )

    fun money(t: LocalDateTime, question: String, facts: String) = oneLine(
        "$question. ${now(t)} Answer only from these facts from my phone: $facts. Give the number first. $RULES"
    )

    fun spendingReport(t: LocalDateTime, facts: String) = oneLine(
        "Give me my monthly spending report. ${now(t)} Use only these facts from my phone: $facts. " +
            "Say the total, the top 3 categories, what changed most against last month, and 2 practical ways to save. $RULES"
    )

    fun oddCharges(t: LocalDateTime, found: String, recent: String) = oneLine(
        "Check my recent bank charges for anything odd. ${now(t)} Flags from my phone: $found. My spends from the last 7 days: $recent. " +
            "Point out double charges, unknown merchants, unusually large amounts or new subscriptions, and say what to do. $RULES"
    )

    fun stockCheck(t: LocalDateTime, stock: String, scanLines: String) = oneLine(
        "Quick stock check on $stock (Indian market, NSE). ${now(t)} Search the web for its current price and trend, latest quarterly results, " +
            "upcoming results date, and news from the last 2 weeks. My own daily scan says: $scanLines. " +
            "Give price levels to watch (support, resistance) and the main risk. This is for my information only, not a trade. $RULES"
    )

    fun catchUp(t: LocalDateTime, messages: String) = oneLine(
        "Catch me up on what I missed today. ${now(t)} Check Slack for messages to me, mentions and threads I'm in today. " +
            "My WhatsApp and app messages today from my phone: $messages. Group by person, say what needs a reply, and skip promotions. $RULES"
    )

    fun findDocument(t: LocalDateTime, question: String, files: String) = oneLine(
        "$question ${now(t)} Search my Gmail and Google Drive for it. Matching file names on my phone: $files. " +
            "Tell me the key date and where the document is. $REMIND_HOW Set it one month before an expiry. $RULES"
    )

    fun weeklyReview(t: LocalDateTime, money: String, bills: String, scan: String) = oneLine(
        "Give me my weekly review. ${now(t)} My spending: $money. Bills due soon: $bills. My stock watchlist from the latest scan: $scan. " +
            "Also check my Outlook calendar for next week. Cover money, bills, stocks and the week ahead in a few short points. $RULES"
    )

    fun reminder(t: LocalDateTime, request: String) = oneLine(
        "$request. ${now(t)} Work out the date and time I mean. Reply with a line exactly like REMIND: YYYY-MM-DD HH:MM | what to remind, " +
            "then confirm it in one short sentence. If no time is given use 09:00."
    )

    fun ask(question: String) = question

    /** Answers "where did I park?" from what you told Mehitha, or null if nothing fits. */
    fun recall(notes: List<Note>, question: String): String? {
        val words = AssistantText.keywords(question)
        if (words.isEmpty()) return null
        val hit = notes.filter { n ->
            val noteWords = AssistantText.keywords(n.text)
            words.all { w -> noteWords.any { same(it, w) } }
        }.maxByOrNull { it.at } ?: return null
        return "On ${hit.at.format(SHORT)} you told me: ${hit.text}."
    }

    private fun same(a: String, b: String) = a == b || (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)))

    private val REMIND_LINE = Regex("(?im)^[\\s*_>-]*remind[\\s*_]*:[\\s*_]*(\\d{4}-\\d{2}-\\d{2})(?:[ T,]+(\\d{1,2}):(\\d{2}))?[\\s*_]*\\|\\s*(.+?)[\\s*_]*$")

    /** Pulls REMIND lines out of Claude's reply: the reminders, and the reply without them. */
    fun reminders(reply: String): Pair<List<ReminderNote>, String> {
        val found = REMIND_LINE.findAll(reply).mapNotNull { m ->
            try {
                val day = LocalDate.parse(m.groupValues[1])
                val h = m.groupValues[2].toIntOrNull() ?: 9
                val min = m.groupValues[3].toIntOrNull() ?: 0
                ReminderNote(day.atTime(h, min), m.groupValues[4].trim())
            } catch (e: Exception) { null }
        }.toList()
        return found to Regex(REMIND_LINE.pattern + "\\R?", REMIND_LINE.options).replace(reply, "").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** The watchlist from LATEST_SCAN.md, one short entry per stock, best verdicts first. */
    fun scanSummary(md: String, max: Int = 2500): String {
        val rows = watchlistRows(md)
        if (rows.isEmpty()) return "not available"
        val order = listOf("BUY NOW", "BUY ON DIP", "WAIT", "AVOID")
        val date = Regex("scan, (\\d{4}-\\d{2}-\\d{2})").find(md)?.groupValues?.get(1)
        return ((date?.let { "scan of $it: " } ?: "") + rows.sortedBy { r -> order.indexOfFirst { it in r.verdict }.let { if (it < 0) 9 else it } }
            .joinToString("; ") { "${it.name} ${it.close} ${it.verdict}, entry ${it.entry}, stop ${it.stop}, target ${it.target}" }).take(max)
    }

    /** What the scan says about one stock, matched by name or symbol. */
    fun scanFor(md: String, stock: String): String {
        val words = stock.lowercase().split(Regex("\\W+")).filter { it.length >= 3 && it !in setOf("ltd", "limited", "the") }
        if (words.isEmpty()) return "not in my watchlist"
        val row = watchlistRows(md).firstOrNull { r -> words.all { w -> r.name.lowercase().contains(w) || r.symbol.lowercase().contains(w) } }
            ?: return "not in my watchlist"
        return "${row.name} (${row.symbol}) closed ${row.close}, verdict ${row.verdict}, grade ${row.grade}, entry ${row.entry}, stop-loss ${row.stop}, target ${row.target}"
    }

    data class ScanRow(val name: String, val symbol: String, val close: String, val verdict: String, val grade: String, val entry: String, val stop: String, val target: String)

    private fun watchlistRows(md: String): List<ScanRow> {
        val section = md.substringAfter("## Watchlist", "").substringBefore("\n## ")
        return section.lines().filter { it.startsWith("|") && !it.startsWith("|---") && !it.startsWith("| Group") }.mapNotNull { line ->
            val c = line.trim('|').split("|").map { it.trim() }
            if (c.size < 9) return@mapNotNull null
            val symbol = c[8].substringBefore('/').ifBlank { c[1] }
            val verdict = c[3].replace(Regex("[^A-Za-z ]"), "").trim()
            ScanRow(c[1], symbol, c[2].replace("₹", "") + " rupees", verdict, c[4], c[5].replace("₹", ""), c[6].replace("₹", ""), c[7].replace("₹", ""))
        }
    }

    /** Keeps the prompt on one line and within what the Claude app takes comfortably. */
    fun oneLine(s: String, max: Int = 6000) = s.replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max - 1) + "…" else it }
}
