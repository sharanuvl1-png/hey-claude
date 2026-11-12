package com.personal.heyclaude

import android.content.Context
import android.net.Uri
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shopping help without ever buying: a shopping list kept on the phone, and links that open
 * Blinkit, Amazon or Flipkart search for each item so you can tap Add yourself.
 * Mehitha never opens a cart's checkout and never places an order.
 */
object Shopping {
    private const val PREFS = "shopping"

    /** The shops Mehitha can open, and their search pages (the shop's app opens them when installed). */
    private val SEARCH = mapOf(
        "blinkit" to "https://blinkit.com/s/?q=",
        "amazon" to "https://www.amazon.in/s?k=",
        "flipkart" to "https://www.flipkart.com/search?q=",
    )

    fun shopName(shop: String) = shop.lowercase().replaceFirstChar { it.uppercase() }

    fun searchUrl(shop: String, item: String) = SEARCH.getValue(shop.lowercase()) + Uri.encode(item.trim())

    /** "milk, bread and 2 dozen eggs" → milk; bread; 2 dozen eggs. */
    fun items(text: String): List<String> = text.split(Regex("\\s*(,|;|\\band\\b|&|\\bplus\\b)\\s*", RegexOption.IGNORE_CASE))
        .map { it.trim().removePrefix("some ").removePrefix("a ").removePrefix("an ").trim() }
        .filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    fun list(ctx: Context): List<String> =
        prefs(ctx).getString("list", "").orEmpty().lines().filter { it.isNotBlank() }

    /** Adds [items] to the list; returns the whole list. */
    fun add(ctx: Context, items: List<String>): List<String> {
        val now = list(ctx)
        val all = now + items.filter { i -> now.none { it.equals(i, ignoreCase = true) } }
        prefs(ctx).edit().putString("list", all.joinToString("\n")).apply()
        return all
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove("list").apply()

    /** Takes [items] off the list (any wording of the same item). */
    fun remove(ctx: Context, items: List<String>) {
        val keys = items.map { Groceries.key(it) }.toSet()
        prefs(ctx).edit().putString("list", list(ctx).filter { Groceries.key(it) !in keys }.joinToString("\n")).apply()
    }

    fun said(items: List<String>) = when (items.size) {
        0 -> "nothing"
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    fun listAnswer(ctx: Context): String = list(ctx).let {
        if (it.isEmpty()) "Your shopping list is empty." else "Your shopping list has ${it.size} item${if (it.size > 1) "s" else ""}: ${said(it)}."
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** An order update from a shop: its status and when it came. */
data class OrderUpdate(val shop: String, val status: String, val time: LocalDateTime, val text: String)

/** Where your orders are, from the SMS, WhatsApp, Gmail and shop-app notifications they send. */
object Orders {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.US)

    private val STATUS = listOf(
        "cancelled" to Regex("(?i)\\b(has been |was |is )?(cancell?ed)\\b"),
        "refund started" to Regex("(?i)\\brefund\\b"),
        "delivered" to Regex("(?i)\\b(has been|was|is|successfully|got) delivered\\b|\\bdelivered successfully\\b|\\border delivered\\b"),
        "out for delivery" to Regex("(?i)\\bout for delivery\\b|\\b(rider|delivery partner|valet) is (on the way|arriving|nearby)\\b|\\barriving in \\d+ ?min"),
        "arriving" to Regex("(?i)\\b(arriving|will be delivered|expected delivery|delivery by|arrives) (today|tomorrow|on|by)\\b"),
        "shipped" to Regex("(?i)\\b(shipped|dispatched|in transit|on (the|its) way)\\b"),
        "placed" to Regex("(?i)\\border (is |has been )?(placed|confirmed|received)\\b|\\bthank(s| you) for (your )?(order|shopping)\\b|\\bwe'?ve received your order\\b"),
    )
    private val SHOP_APPS = Regex("(?i)^(amazon( shopping)?|flipkart|blinkit|myntra|meesho|ajio|nykaa|jiomart|bigbasket|zepto|swiggy|tata neu|whatsapp( business)?|gmail|messages)$")
    private val NOT_ORDER = Regex("(?i)\\b(sale|offer|% off|deal|coupon|cashback offer|shop now|wishlist|price drop)\\b")

    /** The update in one message, or null if it isn't about an order. */
    fun update(app: String, sender: String, text: String, time: LocalDateTime): OrderUpdate? {
        if (!SHOP_APPS.matches(app.trim()) || NOT_ORDER.containsMatchIn(text)) return null
        val shop = Today.SHOPS.firstOrNull { s ->
            text.contains(s, ignoreCase = true) || sender.replace(" ", "").contains(s.replace(" ", ""), ignoreCase = true) ||
                app.contains(s, ignoreCase = true)
        } ?: return null
        val status = STATUS.firstOrNull { it.second.containsMatchIn(text) }?.first ?: return null
        return OrderUpdate(shop.replace("BlueDart", "Blue Dart"), status, time, text.take(140))
    }

    /** The newest update per shop from the last [days] days, newest first. */
    fun latest(updates: List<OrderUpdate>): List<OrderUpdate> =
        updates.sortedByDescending { it.time }.distinctBy { it.shop.lowercase() }

    fun answer(updates: List<OrderUpdate>, today: LocalDate, shop: String? = null): String {
        val list = latest(updates).filter { shop == null || it.shop.equals(shop, ignoreCase = true) }
        if (list.isEmpty()) return if (shop == null) "I don't see any order updates from the last 10 days."
            else "I don't see any ${Shopping.shopName(shop)} order updates from the last 10 days."
        return list.joinToString(" ") { u ->
            val whenSaid = when (u.time.toLocalDate()) {
                today -> "at ${u.time.format(TIME)} today"
                today.minusDays(1) -> "yesterday"
                else -> "on ${u.time.format(DAY)}"
            }
            "${u.shop}: ${u.status}, last update $whenSaid."
        }
    }

    /** Order updates from SMS and saved app notifications in the last [days] days. */
    fun recent(data: PhoneData, days: Long = 10): List<OrderUpdate> {
        val sms = try { data.recentSms(days).mapNotNull { (t, sender, body) -> update("messages", sender, body, t) } } catch (e: Exception) { emptyList() }
        val chats = try { data.chats(days).mapNotNull { update(it.app, it.sender, "${it.sender} ${it.text}", it.time) } } catch (e: Exception) { emptyList() }
        return sms + chats
    }
}
