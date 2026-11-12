package com.personal.heyclaude

import android.content.Context
import android.net.Uri
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Your usual groceries: Mehitha notes each time you add an item to the list, open it in Blinkit
 * or say you bought it, learns how often you buy it, and reminds you when it's usually due.
 * Kept only on the phone. It never orders anything: the reminder opens Blinkit search for you.
 */
object Groceries {
    private const val PREFS = "groceries"
    /** Buying the same thing again the next day is the same shop (on the list, then Blinkit). */
    private const val SAME_SHOP = 1L
    private const val LOOK_BACK = 120L

    /** One purchase: the day and the item as you said it. */
    data class Bought(val day: LocalDate, val item: String)

    /** An item you buy again and again: about every [everyDays] days, last on [last]. */
    data class Usual(val name: String, val everyDays: Int, val last: LocalDate, val times: Int) {
        val dueOn: LocalDate get() = last.plusDays(everyDays.toLong())
        fun isDue(today: LocalDate) = !dueOn.isAfter(today.plusDays(1))
    }

    private val QUANTITY = Regex("(?i)^(?:(?:a|an|one|two|half(?: a)?)\\s+|\\d+(?:\\.\\d+)?\\s*(?:x\\s*)?)" +
        "(?:(?:kgs?|kilos?|g|gms?|grams?|l|ltrs?|litres?|liters?|ml|dozens?|packets?|packs?|pkts?|pcs|pieces?|bottles?|boxes|box|bunch(?:es)?|units?|loaf|loaves)\\b\\s*)?(?:of\\s+)?")

    /** The item without its quantity: "2 dozen eggs" → "eggs". */
    fun name(item: String): String = item.trim().replace(QUANTITY, "").trim().ifEmpty { item.trim() }

    /** The same item however it was said: "Eggs", "2 dozen eggs" and "egg" match. */
    fun key(item: String): String = name(item).lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+"))
        .filter { it.isNotEmpty() }.joinToString(" ") { w -> if (w.length > 3 && w.endsWith("es") && w.dropLast(2).endsWith("o")) w.dropLast(2) else if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w }

    /** Items you buy at least twice, with how often; most often first. */
    fun usuals(log: List<Bought>, today: LocalDate): List<Usual> =
        log.filter { !it.day.isBefore(today.minusDays(LOOK_BACK)) && !it.day.isAfter(today) }
            .groupBy { key(it.item) }.filterKeys { it.isNotEmpty() }.mapNotNull { (_, buys) ->
                val days = buys.map { it.day }.sorted().fold(mutableListOf<LocalDate>()) { acc, d ->
                    if (acc.isEmpty() || ChronoUnit.DAYS.between(acc.last(), d) > SAME_SHOP) acc += d
                    acc
                }
                if (days.size < 2) return@mapNotNull null
                val gaps = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }.sorted()
                val median = gaps[gaps.size / 2]
                Usual(name(buys.maxBy { it.day }.item), median, days.last(), days.size)
            }.sortedWith(compareBy<Usual> { it.everyDays }.thenBy { it.name.lowercase() })

    /** The usuals due by tomorrow, soonest first. */
    fun due(log: List<Bought>, today: LocalDate): List<Usual> = usuals(log, today).filter { it.isDue(today) }.sortedBy { it.dueOn }

    /**
     * The answer to "my usual groceries": what's due, how often you buy each, and a button per
     * item (saved usuals and learned ones, due first) to open it in Blinkit one at a time.
     */
    fun answer(log: List<Bought>, saved: List<String>, today: LocalDate): String {
        val learned = usuals(log, today)
        if (learned.isEmpty() && saved.isEmpty()) return "You have no usual groceries yet. Say \"add milk, eggs and bread to my usuals\" " +
            "to save them, and I'll also learn what you buy often from your list and Blinkit."
        val due = learned.filter { it.isDue(today) }.sortedBy { it.dueOn }
        val head = if (due.isEmpty()) "Nothing is due yet. Tap an item to open it in Blinkit, one at a time."
            else "Due now: ${Shopping.said(due.map { it.name })}. Tap an item to open it in Blinkit, one at a time."
        val lines = (due + learned.filter { !it.isDue(today) }.sortedBy { it.dueOn }).take(6)
            .map { u -> "• ${u.name}: every ${every(u.everyDays)}, ${dueText(u, today)}" }
        return (listOf(head) + lines).joinToString("\n")
    }

    /** The items to show as buttons: due first, then saved usuals, then the rest you buy often. */
    fun buttons(log: List<Bought>, saved: List<String>, today: LocalDate, max: Int = 12): List<String> {
        val learned = usuals(log, today)
        val due = learned.filter { it.isDue(today) }.sortedBy { it.dueOn }.map { it.name }
        return (due + saved + learned.map { it.name }).distinctBy { key(it) }.take(max)
    }

    /** Your saved usuals, as you said them. */
    fun saved(ctx: Context): List<String> = prefs(ctx).getString("saved", "").orEmpty().lines().filter { it.isNotBlank() }

    /** Saves [items] as usuals; returns them all. */
    fun save(ctx: Context, items: List<String>): List<String> {
        val now = saved(ctx)
        val all = now + items.map { name(it) }.filter { i -> key(i).isNotEmpty() && now.none { key(it) == key(i) } }.distinctBy { key(it) }
        prefs(ctx).edit().putString("saved", all.joinToString("\n")).apply()
        return all
    }

    /** Removes [items] from the saved usuals; returns the ones removed. */
    fun unsave(ctx: Context, items: List<String>): List<String> {
        val keys = items.map { key(it) }.toSet()
        val (gone, kept) = saved(ctx).partition { key(it) in keys }
        prefs(ctx).edit().putString("saved", kept.joinToString("\n")).apply()
        return gone
    }

    /** The morning nudge, or null when nothing is due today. */
    fun nudge(log: List<Bought>, today: LocalDate): Alert? {
        val due = due(log, today).filter { !it.dueOn.isAfter(today) }
        if (due.isEmpty()) return null
        return Alert("groceries:$today", "Groceries due",
            "${Shopping.said(due.take(4).map { it.name }).replaceFirstChar { it.uppercase() }} ${if (due.size > 1) "are" else "is"} usually due. Tap to open them in Blinkit.")
    }

    private fun every(days: Int) = when {
        days <= 1 -> "day"
        days % 7 == 0 -> if (days == 7) "week" else "${days / 7} weeks"
        else -> "$days days"
    }

    private fun dueText(u: Usual, today: LocalDate): String {
        val d = ChronoUnit.DAYS.between(today, u.dueOn)
        return when {
            d < 0 -> "overdue by ${-d} day${if (d < -1) "s" else ""}"
            d == 0L -> "due today"
            d == 1L -> "due tomorrow"
            else -> "due in $d days"
        }
    }

    fun log(ctx: Context): List<Bought> = prefs(ctx).getString("log", "").orEmpty().lines().mapNotNull { line ->
        val (day, item) = line.split("|", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        try { Bought(LocalDate.parse(day), item) } catch (e: Exception) { null }
    }

    /** Notes that you bought or are buying [items] on [day]; keeps the last 120 days. */
    fun record(ctx: Context, items: List<String>, day: LocalDate = PhoneData.today()) {
        val clean = items.map { it.trim().replace("|", " ") }.filter { it.isNotEmpty() && key(it).isNotEmpty() }.distinctBy { key(it) }
        if (clean.isEmpty()) return
        val kept = log(ctx).filter { !it.day.isBefore(day.minusDays(LOOK_BACK)) }
        val all = kept + clean.filter { i -> kept.none { it.day == day && key(it.item) == key(i) } }.map { Bought(day, it) }
        prefs(ctx).edit().putString("log", all.joinToString("\n") { "${it.day}|${it.item}" }).apply()
    }

    /** The item a Blinkit search link is for, or null for other links. */
    fun blinkitItem(url: String): String? =
        if (url.startsWith(Shopping.searchUrl("blinkit", ""))) Uri.decode(url.substringAfter("?q=")).takeIf { it.isNotBlank() } else null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
