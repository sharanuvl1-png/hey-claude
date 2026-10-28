package com.personal.heyclaude

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToLong

/** One spend read from a bank or UPI SMS (see PhoneSync.expenseRow). */
data class Spend(val time: LocalDateTime, val amount: Double, val category: String, val merchant: String, val via: String)

/**
 * Answers "how much did I spend" from the bank and UPI text messages on the phone, without
 * asking Claude. Plain Kotlin so it can be tested.
 */
object Money {

    private val ROW_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.US)

    private val SPEND_QUESTION = Regex("(?i)\\b(spen[dt]|spending|expenses?|expenditure)\\b")

    /** A question about your own spending, which Mehitha answers from SMS instead of the Claude app. */
    fun isSpendQuestion(text: String) = SPEND_QUESTION.containsMatchIn(text)

    /** Reads a row made by PhoneSync.expenseRow. */
    fun parseRow(row: String): Spend? {
        val cells = Regex("\"((?:[^\"]|\"\")*)\"").findAll(row).map { it.groupValues[1].replace("\"\"", "\"") }.toList()
        if (cells.size < 5) return null
        return try {
            Spend(LocalDateTime.parse(cells[0], ROW_TIME), cells[1].toDouble(), cells[2], cells[3], cells[4])
        } catch (e: Exception) {
            null
        }
    }

    /** "2,500 rupees" style, as a speaker would say it. */
    fun rupees(v: Double): String = "%,d rupees".format(Locale("en", "IN"), v.roundToLong())

    /** The days a question asks about, with how to say them. */
    data class Period(val from: LocalDate, val to: LocalDate, val name: String)

    fun period(question: String, today: LocalDate): Period {
        val q = question.lowercase()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when {
            "yesterday" in q -> today.minusDays(1).let { Period(it, it, "Yesterday, ${it.format(DAY)}") }
            Regex("last 7 days|past 7 days|past week|last seven days").containsMatchIn(q) ->
                Period(today.minusDays(6), today, "In the last 7 days")
            "last week" in q -> Period(monday.minusWeeks(1), monday.minusDays(1), "Last week")
            "week" in q -> Period(monday, today, "This week")
            "last month" in q || "previous month" in q -> today.minusMonths(1).let {
                Period(it.withDayOfMonth(1), it.withDayOfMonth(it.lengthOfMonth()), "In ${it.format(MONTH)}")
            }
            "month" in q -> Period(today.withDayOfMonth(1), today, "This month")
            else -> Period(today, today, "Today, ${today.format(DAY)}")
        }
    }

    /** Words in the question that name a shop or category, e.g. "swiggy" or "food". */
    private fun filterWords(question: String, spends: List<Spend>): List<String> =
        AssistantText.keywords(question).filter { it !in NOT_FILTER }
            .filter { w -> spends.any { it.merchant.contains(w, true) || it.category.equals(w, true) } }

    /** A short spoken answer, or a plain "none found" for the period. Never guesses. */
    fun answer(spends: List<Spend>, today: LocalDate, question: String): String {
        val p = period(question, today)
        val words = filterWords(question, spends)
        val list = spends.filter { !it.time.toLocalDate().isBefore(p.from) && !it.time.toLocalDate().isAfter(p.to) }
            .filter { s -> words.isEmpty() || words.any { s.merchant.contains(it, true) || s.category.equals(it, true) } }
            .sortedByDescending { it.time }
        val on = if (words.isEmpty()) "" else " on ${words.joinToString(" and ")}"
        if (list.isEmpty()) return "${p.name}, I found no spends$on in your bank and UPI text messages."
        val total = list.sumOf { it.amount }
        val count = if (list.size == 1) "1 payment" else "${list.size} payments"
        val detail = if (p.from == p.to || list.size <= 4) {
            list.take(5).joinToString(", ") { "${it.merchant} ${rupees(it.amount)}" } + (if (list.size > 5) ", and more" else "")
        } else {
            "mostly " + list.groupBy { it.category }.entries.sortedByDescending { e -> e.value.sumOf { it.amount } }.take(3)
                .joinToString(", ") { e -> "${e.key} ${rupees(e.value.sumOf { it.amount })}" }
        }
        return "${p.name}, you spent ${rupees(total)}$on in $count: $detail. " +
            "This counts only card, UPI and bank payments that sent you a text message."
    }

    private val NOT_FILTER = setOf(
        "much", "spend", "spent", "spending", "expense", "expenses", "expenditure", "week", "month", "previous",
        "past", "days", "seven", "total", "money", "rupees", "all", "far", "till", "now", "until", "so",
    )
}
