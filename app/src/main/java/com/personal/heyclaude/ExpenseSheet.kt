package com.personal.heyclaude

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Adds spends to your "Mehitha Expenses" Google Sheet through a small Apps Script web app
 * pasted into the sheet (see docs/expense-sheet.gs). Columns: Date, Amount (INR), Category,
 * Description, Paid via, Notes. Only spends after the link was saved are added, never a backlog.
 */
object ExpenseSheet {
    private const val PREFS = "expense_sheet"
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    fun url(ctx: Context): String = prefs(ctx).getString("url", "").orEmpty()

    /** Saves the web app link; spends from now on are added. */
    fun save(ctx: Context, link: String) {
        prefs(ctx).edit().putString("url", link.trim())
            .putLong("since", System.currentTimeMillis()).putStringSet("done", emptySet()).apply()
    }

    fun isLinkValid(link: String) = Regex("^https://script\\.google\\.com/macros/s/[\\w-]+/exec$").matches(link.trim())

    /** The sheet row for a spend read from an SMS. */
    fun row(s: Spend, note: String = "from SMS"): List<Any> =
        listOf(s.time.format(DAY), s.amount, s.category, s.merchant, s.via, "$note · Mehitha")

    /** A stable key so the same spend is never added twice. */
    fun key(s: Spend) = "${s.time}|${"%.2f".format(Locale.US, s.amount)}|${s.merchant.lowercase()}"

    /** Spends not yet in the sheet: after the link was saved and not added before. */
    fun pending(spends: List<Spend>, sinceMillis: Long, done: Set<String>): List<Spend> =
        spends.filter { it.time.toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli() >= sinceMillis && key(it) !in done }

    /** Adds new SMS spends to the sheet. Returns how many were added. Needs the network. */
    fun sync(ctx: Context, data: PhoneData): Int {
        val link = url(ctx)
        if (link.isEmpty() || !data.canReadSms) return 0
        val p = prefs(ctx)
        val done = p.getStringSet("done", emptySet())!!
        val todo = pending(data.spends(days = 7), p.getLong("since", Long.MAX_VALUE), done)
        if (todo.isEmpty()) return 0
        if (!send(link, todo.map { row(it) })) return 0
        p.edit().putStringSet("done", (done + todo.map(::key)).toList().takeLast(1000).toSet()).apply()
        return todo.size
    }

    /** One spend you said out loud ("spent 250 on lunch by UPI"). Returns false if it couldn't be added. */
    fun addSpoken(ctx: Context, amount: Double, what: String, via: String, category: String): Boolean {
        val link = url(ctx)
        if (link.isEmpty()) return false
        return send(link, listOf(listOf(LocalDateTime.now(PhoneData.IST).format(DAY), amount, category, what, via, "said to Mehitha")))
    }

    private fun send(link: String, rows: List<List<Any>>): Boolean = try {
        val body = JSONObject().put("rows", JSONArray(rows.map { JSONArray(it) })).toString()
        val conn = URL(link).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..399
    } catch (e: Exception) {
        Log.w("Mehitha", "Expense sheet failed", e); false
    }

    private val KINDS = listOf(
        "Food" to "lunch|dinner|breakfast|snack|coffee|tea|food|restaurant|swiggy|zomato|meal|biryani|pizza",
        "Groceries" to "grocer|vegetable|fruit|milk|bigbasket|blinkit|zepto|instamart|dmart|kirana",
        "Fuel" to "petrol|diesel|fuel|cng",
        "Transport" to "uber|ola|rapido|auto|cab|taxi|metro|bus|parking|toll|fastag",
        "Bills" to "electricity|bescom|water|gas|recharge|broadband|wifi|internet|rent|emi|dth|bill",
        "Health" to "medicine|pharmacy|doctor|hospital|clinic|lab|apollo|medical|gym",
        "Entertainment" to "movie|netflix|prime|hotstar|spotify|concert|game|bookmyshow",
        "Travel" to "flight|train|irctc|hotel|trip|makemytrip|goibibo|airbnb",
        "Investments" to "sip|mutual fund|stock|shares|zerodha|gold|fd|ppf|nps",
        "Shopping" to "amazon|flipkart|myntra|clothes|shoes|shirt|dress|gift|shopping|electronics",
    )

    /** A sensible category for something you bought. */
    fun category(what: String): String =
        KINDS.firstOrNull { (_, words) -> Regex("(?i)\\b($words)").containsMatchIn(what) }?.first ?: "Other"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The script to paste into the sheet: Extensions → Apps Script. */
    const val SCRIPT = """function doPost(e) {
  var rows = JSON.parse(e.postData.contents).rows || [];
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheets()[0];
  rows.forEach(function (r) { sheet.appendRow(r); });
  return ContentService.createTextOutput(JSON.stringify({ added: rows.length }))
    .setMimeType(ContentService.MimeType.JSON);
}"""
}
