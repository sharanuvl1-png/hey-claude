package com.personal.heyclaude

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * The technical chart images the "Technical chart" GitHub action saves on the charts branch,
 * one folder per NSE symbol with a `<date>.png` and `<date>.md` per run.
 */
object Charts {
    private const val API = "https://api.github.com/repos/sharanuvl1-png/hey-claude/contents"
    private const val RAW = "https://raw.githubusercontent.com/sharanuvl1-png/hey-claude/charts"

    /** The newest chart for a stock: its date, picture link and analysis link. */
    data class Chart(val symbol: String, val date: String, val imageUrl: String, val reportUrl: String)

    @Volatile private var symbolsCache: List<String>? = null

    /** Letters and digits only, upper case: "Bajaj Auto" and "BAJAJ-AUTO" both become BAJAJAUTO. */
    fun norm(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")

    /**
     * The chart folder for [stock] among [folders]: an exact symbol match first, then a
     * company name from the stock scan ([names] maps names to symbols), then a close prefix.
     */
    fun match(stock: String, folders: List<String>, names: List<Pair<String, String>> = emptyList()): String? {
        val key = norm(stock.removeSuffix(" shares").removeSuffix(" stock"))
        if (key.length < 2) return null
        folders.firstOrNull { norm(it) == key }?.let { return it }
        names.firstOrNull { (name, _) -> norm(name) == key || norm(name).startsWith(key) }?.let { (_, sym) ->
            folders.firstOrNull { norm(it) == norm(sym) }?.let { return it }
        }
        return folders.firstOrNull { val f = norm(it); f.length >= 4 && (f.startsWith(key) || key.startsWith(f)) }
    }

    /** The newest `<date>.png` among file [names]; dates sort as text. */
    fun newestPng(names: List<String>): String? =
        names.filter { Regex("\\d{4}-\\d{2}-\\d{2}\\.png").matches(it) }.maxOrNull()

    /** Finds the newest chart for [stock], or null if there is none. Needs the network. */
    fun find(stock: String, scanMd: String = ""): Chart? {
        val folders = symbolsCache ?: list("").filter { it.second == "dir" }.map { it.first }.also { symbolsCache = it }
        val symbol = match(stock, folders, Prompts.scanNames(scanMd)) ?: return null
        val png = newestPng(list(symbol).map { it.first }) ?: return null
        val date = png.removeSuffix(".png")
        return Chart(symbol, date, "$RAW/$symbol/$png", "$RAW/$symbol/$date.md")
    }

    /** Downloads a picture from any https link (or a file:// snippet saved on the phone); null if it isn't a picture or can't be fetched. */
    fun picture(url: String, maxBytes: Int = 8_000_000): Bitmap? = if (url.startsWith("file://")) {
        try { BitmapFactory.decodeFile(url.removePrefix("file://")) } catch (e: Exception) { null }
    } else try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200 || conn.contentLength > maxBytes) null
            else conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally { conn.disconnect() }
    } catch (e: Exception) { null }

    /** A text file from the web, such as a chart's written analysis. */
    fun text(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        return try { if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else "" } finally { conn.disconnect() }
    }

    /** Names and types in a folder of the charts branch. */
    private fun list(path: String): List<Pair<String, String>> {
        val conn = URL("$API/$path?ref=charts").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (conn.responseCode != 200) return emptyList()
            val a = JSONArray(conn.inputStream.bufferedReader().readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("name") to it.optString("type") }
        } finally { conn.disconnect() }
    }
}
