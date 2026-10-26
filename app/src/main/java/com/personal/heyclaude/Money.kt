package com.personal.heyclaude

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** One spend read from a bank or UPI SMS (see PhoneSync.expenseRow). */
data class Spend(val time: LocalDateTime, val amount: Double, val category: String, val merchant: String, val via: String)

/** A bill found in an SMS, with the date it is due. */
data class Bill(val sender: String, val amount: Double?, val due: LocalDate, val text: String)

/** Something Mehitha should tell you about, with an id so it is only said once. */
data class Alert(val id: String, val title: String, val text: String)

/** Spending maths for voice answers and alerts. Plain Kotlin so it can be tested. */
object Money {

    private val ROW_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.US)

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

    private fun total(list: List<Spend>) = list.sumOf { it.amount }

    private fun monthOf(spends: List<Spend>, day: LocalDate) =
        spends.filter { it.time.year == day.year && it.time.monthValue == day.monthValue }

    /** Totals by category, biggest first: "Food 4,520 rupees (12)". */
    private fun byCategory(list: List<Spend>) = list.groupBy { it.category }
        .entries.sortedByDescending { total(it.value) }
        .joinToString("; ") { "${it.key} ${rupees(total(it.value))} (${it.value.size})" }

    private fun byMerchant(list: List<Spend>, limit: Int) = list.groupBy { it.merchant }
        .entries.sortedByDescending { total(it.value) }.take(limit)
        .joinToString("; ") { "${it.key} ${rupees(total(it.value))} (${it.value.size})" }

    private fun line(s: Spend) = "${s.time.toLocalDate().format(DAY)} ${s.merchant} ${rupees(s.amount)} (${s.category}, ${s.via})"

    /**
     * Facts for Claude to answer a money question from, in one paragraph: this month and last
     * month by category and shop, plus every spend matching the question's words.
     */
    fun summary(spends: List<Spend>, today: LocalDate, question: String): String {
        if (spends.isEmpty()) return "No spends were found in the bank and UPI text messages on the phone."
        val now = monthOf(spends, today)
        val last = monthOf(spends, today.minusMonths(1))
        val words = AssistantText.keywords(question)
        val matched = spends.filter { s -> words.any { w -> s.merchant.contains(w, true) || s.category.contains(w, true) } }
            .sortedByDescending { it.time }
        val month = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
        return buildString {
            append("My spending from bank and UPI text messages. ")
            // Today and this week first, so they survive when the facts are cut short.
            val day = spends.filter { it.time.toLocalDate() == today }.sortedByDescending { it.time }
            append("Today (${today.format(DAY)}): ${rupees(total(day))} in ${day.size} spends")
            append(if (day.isEmpty()) ". " else ": ${day.take(8).joinToString("; ") { "${it.merchant} ${rupees(it.amount)}" }}. ")
            val week = spends.filter { !it.time.toLocalDate().isBefore(today.minusDays(6)) && !it.time.toLocalDate().isAfter(today) }
            append("Last 7 days: ${rupees(total(week))} in ${week.size} spends. ")
            if (words.isNotEmpty()) {
                append("Spends matching ${words.joinToString(", ")}: ")
                append(if (matched.isEmpty()) "none" else matched.take(15).joinToString("; ") { line(it) })
                append(". ")
            }
            append("${today.format(month)} so far: ${rupees(total(now))} in ${now.size} spends; by category: ${byCategory(now).ifEmpty { "none" }}; top shops: ${byMerchant(now, 6).ifEmpty { "none" }}. ")
            append("${today.minusMonths(1).format(month)}: ${rupees(total(last))} in ${last.size} spends; by category: ${byCategory(last).ifEmpty { "none" }}; top shops: ${byMerchant(last, 6).ifEmpty { "none" }}. ")
            append("Only cash withdrawals and card, UPI and bank debits that sent an SMS are included.")
        }
    }

    /** A month-on-month report for "spending report". */
    fun report(spends: List<Spend>, today: LocalDate): String {
        val now = monthOf(spends, today)
        val sameDaysLast = monthOf(spends, today.minusMonths(1)).filter { it.time.dayOfMonth <= today.dayOfMonth }
        return summary(spends, today, "") +
            " For a fair comparison, last month up to day ${today.dayOfMonth} was ${rupees(total(sameDaysLast))} against ${rupees(total(now))} this month." +
            " Subscriptions and repeat payments: " + subscriptions(spends, today).ifEmpty { listOf("none found") }.joinToString("; ") + "."
    }

    /** Same shop, about the same amount, in two months running: likely a subscription or repeat payment. */
    fun subscriptions(spends: List<Spend>, today: LocalDate): List<String> {
        val months = (0L..2L).map { monthOf(spends, today.minusMonths(it)) }
        return listOf(months[0] to months[1], months[1] to months[2])
            .flatMap { (a, b) -> a.filter { x -> x.merchant != "Unknown" && b.any { it.merchant == x.merchant && close(it.amount, x.amount) } } }
            .distinctBy { it.merchant }
            .map { "${it.merchant} about ${rupees(it.amount)} a month" }
    }

    private fun close(a: Double, b: Double) = abs(a - b) <= maxOf(10.0, 0.05 * maxOf(a, b))

    /**
     * Charges worth a look, from the last [days] days: the same amount to the same place twice
     * within 30 minutes, a spend far above your usual, or a new repeat payment.
     */
    fun oddCharges(spends: List<Spend>, now: LocalDateTime, days: Long = 1): List<Alert> {
        val recent = spends.filter { !it.time.isBefore(now.minusDays(days)) && !it.time.isAfter(now) }.sortedBy { it.time }
        val history = spends.filter { it.time.isBefore(now.minusDays(days)) && it.time.isAfter(now.minusDays(90)) }
        val out = mutableListOf<Alert>()
        for ((i, s) in recent.withIndex()) {
            val twin = recent.take(i).lastOrNull {
                it.merchant == s.merchant && it.amount == s.amount && ChronoUnit.MINUTES.between(it.time, s.time) <= 30
            }
            if (twin != null) out += Alert("double:${s.time}:${s.amount}", "Possible double charge",
                "${rupees(s.amount)} to ${s.merchant} twice within ${ChronoUnit.MINUTES.between(twin.time, s.time)} minutes on ${s.time.toLocalDate().format(DAY)}.")
            val usual = history.map { it.amount }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            val big = maxOf(5000.0, (usual ?: 1000.0) * 10)
            if (s.amount >= big && history.none { it.merchant == s.merchant && close(it.amount, s.amount) }) {
                out += Alert("big:${s.time}:${s.amount}", "Large debit", "${rupees(s.amount)} to ${s.merchant} by ${s.via} on ${s.time.toLocalDate().format(DAY)}. Was this you?")
            }
        }
        return out
    }

    /** Category spend this month running at least 40% (and 1,000 rupees) above last month by the same day. */
    fun unusualSpending(spends: List<Spend>, today: LocalDate): List<Alert> {
        if (today.dayOfMonth < 7) return emptyList()
        val now = monthOf(spends, today).groupBy { it.category }.mapValues { total(it.value) }
        val last = monthOf(spends, today.minusMonths(1)).filter { it.time.dayOfMonth <= today.dayOfMonth }
            .groupBy { it.category }.mapValues { total(it.value) }
        return now.mapNotNull { (cat, amt) ->
            val before = last[cat] ?: return@mapNotNull null
            if (before <= 0 || amt < before * 1.4 || amt - before < 1000) return@mapNotNull null
            val pct = ((amt / before - 1) * 100).roundToLong()
            Alert("unusual:${today.withDayOfMonth(1)}:$cat", "Spending up on $cat",
                "You've spent ${rupees(amt)} on $cat this month, $pct% more than by this time last month (${rupees(before)}).")
        }
    }

    /** The last 7 days: total, by category, and each spend. */
    fun week(spends: List<Spend>, now: LocalDateTime): String {
        val list = recent(spends, now, 7)
        if (list.isEmpty()) return "no spends in the last 7 days"
        return "last 7 days ${rupees(total(list))} in ${list.size} spends; by category: ${byCategory(list)}; top shops: ${byMerchant(list, 8)}"
    }

    fun recent(spends: List<Spend>, now: LocalDateTime, days: Long) =
        spends.filter { !it.time.isBefore(now.minusDays(days)) && !it.time.isAfter(now) }.sortedByDescending { it.time }

    fun recentLines(spends: List<Spend>, now: LocalDateTime, days: Long) =
        recent(spends, now, days).take(60).joinToString("; ") { line(it) }.ifEmpty { "none" }

    // ---- Bills due ----------------------------------------------------------------------

    private val DUE = Regex("(?i)\\b(due|payable|pay by|last date)\\b")
    private val PAID = Regex("(?i)\\b(received|thank you for (your )?payment|has been paid|successfully paid|payment of .* received)\\b")
    private val BILL_AMOUNT = Regex("(?i)(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val DATES = listOf(
        Regex("\\b(\\d{1,2})[-/ ]([A-Za-z]{3})[A-Za-z]*[-/ ,]*(\\d{2,4})?\\b") to "dmy-name",
        Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})\\b") to "dmy",
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** A bill due date and amount from an SMS such as "Total amount due Rs 12,400 by 05-Nov-26". */
    fun billDue(sender: String, body: String, received: LocalDate): Bill? {
        if (!DUE.containsMatchIn(body) || PAID.containsMatchIn(body)) return null
        val after = body.substring(DUE.find(body)!!.range.first)
        val due = dateIn(after, received) ?: dateIn(body, received) ?: return null
        if (due.isBefore(received.minusDays(1)) || due.isAfter(received.plusDays(62))) return null
        val amount = BILL_AMOUNT.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        return Bill(sender.substringAfterLast('-').trim(), amount, due, body)
    }

    private fun dateIn(text: String, received: LocalDate): LocalDate? {
        for ((re, kind) in DATES) for (m in re.findAll(text)) {
            val d = m.groupValues[1].toIntOrNull() ?: continue
            val month = if (kind == "dmy-name") MONTHS.indexOf(m.groupValues[2].lowercase()) + 1 else m.groupValues[2].toInt()
            if (month !in 1..12 || d !in 1..31) continue
            val yRaw = m.groupValues.getOrNull(3)?.toIntOrNull()
            val year = when {
                yRaw == null -> received.year.let { y -> if (month < received.monthValue - 1) y + 1 else y }
                yRaw < 100 -> 2000 + yRaw
                else -> yRaw
            }
            return try { LocalDate.of(year, month, d) } catch (e: Exception) { null }
        }
        return null
    }

    /** Bills due in the next [within] days, one alert each. */
    fun billAlerts(bills: List<Bill>, today: LocalDate, within: Long = 3): List<Alert> =
        bills.filter { !it.due.isBefore(today) && !it.due.isAfter(today.plusDays(within)) }
            .distinctBy { "${it.sender}:${it.due}" }
            .map { b ->
                val whenDue = when (ChronoUnit.DAYS.between(today, b.due)) {
                    0L -> "today"; 1L -> "tomorrow"; else -> "on ${b.due.format(DAY)}"
                }
                Alert("bill:${b.sender}:${b.due}", "Bill due $whenDue",
                    "${b.sender} bill" + (b.amount?.let { " of ${rupees(it)}" } ?: "") + " is due $whenDue.")
            }

    fun billsText(bills: List<Bill>, today: LocalDate): String =
        bills.filter { !it.due.isBefore(today) && !it.due.isAfter(today.plusDays(7)) }.distinctBy { "${it.sender}:${it.due}" }
            .joinToString("; ") { "${it.sender}" + (it.amount?.let { a -> " ${rupees(a)}" } ?: "") + " due ${it.due.format(DAY)}" }
            .ifEmpty { "none found in text messages" }
}
