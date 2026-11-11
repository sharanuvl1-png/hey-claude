package com.personal.heyclaude

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/** One stock you hold in Zerodha. */
data class Holding(val symbol: String, val qty: Int, val avg: Double, val last: Double, val dayChangePct: Double) {
    val invested get() = qty * avg
    val value get() = qty * last
    val pnl get() = value - invested
    val dayChange get() = value - value / (1 + dayChangePct / 100)
}

/**
 * Your Zerodha holdings through the Kite Connect API, READ-ONLY: this code only reads the
 * portfolio and has no way to place, change or cancel orders or GTTs.
 * Zerodha's login lasts until about 6 AM the next day, so it asks to log in once a day.
 */
object Zerodha {
    private const val PREFS = "zerodha"
    private const val API = "https://api.kite.trade"
    const val REDIRECT = "https://127.0.0.1/mehitha"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context) = prefs(ctx).getString("key", "").orEmpty()
    fun isSetUp(ctx: Context) = apiKey(ctx).isNotEmpty() && prefs(ctx).getString("secret", "").orEmpty().isNotEmpty()

    fun saveKeys(ctx: Context, key: String, secret: String) =
        prefs(ctx).edit().putString("key", key.trim()).putString("secret", secret.trim()).remove("token").apply()

    /** True while today's login is still valid. */
    fun isLoggedIn(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getString("token", "").orEmpty().isNotEmpty() && System.currentTimeMillis() < p.getLong("expires", 0)
    }

    fun loginUrl(key: String) = "https://kite.zerodha.com/connect/login?v=3&api_key=" + URLEncoder.encode(key, "UTF-8")

    /** The request token from the page Zerodha sends you back to after logging in. */
    fun requestToken(url: String): String? {
        val u = Uri.parse(url)
        return u.getQueryParameter("request_token")?.takeIf { it.isNotBlank() && u.getQueryParameter("status") != "cancelled" }
    }

    /** Zerodha's checksum: SHA-256 of api key + request token + api secret. */
    fun checksum(key: String, requestToken: String, secret: String): String =
        MessageDigest.getInstance("SHA-256").digest((key + requestToken + secret).toByteArray()).joinToString("") { "%02x".format(it) }

    /** When a login made at [now] (IST) stops working: 6 AM the next morning. */
    fun expiry(now: LocalDateTime): LocalDateTime = now.toLocalDate().plusDays(if (now.hour < 6) 0 else 1).atTime(6, 0)

    /** Swaps the request token for today's access token. Needs the network. */
    fun finishLogin(ctx: Context, requestToken: String): Boolean {
        val p = prefs(ctx)
        val key = apiKey(ctx)
        val secret = p.getString("secret", "").orEmpty()
        val form = listOf("api_key" to key, "request_token" to requestToken, "checksum" to checksum(key, requestToken, secret))
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val conn = URL("$API/session/token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("X-Kite-Version", "3")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        return try {
            conn.outputStream.use { it.write(form.toByteArray()) }
            if (conn.responseCode != 200) { Log.w("Mehitha", "Zerodha login failed: ${conn.responseCode}"); return false }
            val token = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONObject("data").getString("access_token")
            val expires = expiry(PhoneData.now()).toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()
            p.edit().putString("token", token).putLong("expires", expires).apply()
            true
        } finally { conn.disconnect() }
    }

    /** Your holdings, or null when not logged in today. Needs the network. */
    fun holdings(ctx: Context): List<Holding>? {
        if (!isLoggedIn(ctx)) return null
        val p = prefs(ctx)
        val conn = URL("$API/portfolio/holdings").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("X-Kite-Version", "3")
        conn.setRequestProperty("Authorization", "token ${apiKey(ctx)}:${p.getString("token", "")}")
        return try {
            when (conn.responseCode) {
                200 -> parseHoldings(conn.inputStream.bufferedReader().readText()).also { list ->
                    p.edit().putString("symbols", list.joinToString(",") { it.symbol }).apply()
                }
                403 -> { p.edit().remove("token").apply(); null } // login expired early
                else -> throw IllegalStateException("Zerodha answered ${conn.responseCode}")
            }
        } finally { conn.disconnect() }
    }

    fun parseHoldings(json: String): List<Holding> {
        val a = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Holding(it.getString("tradingsymbol"), it.optInt("quantity") + it.optInt("t1_quantity"),
                it.optDouble("average_price", 0.0), it.optDouble("last_price", 0.0), it.optDouble("day_change_percentage", 0.0))
        }.filter { it.qty > 0 }
    }

    /** Symbols from the last holdings fetched, for marking breakouts in stocks you own. */
    fun heldSymbols(ctx: Context): Set<String> =
        prefs(ctx).getString("symbols", "").orEmpty().split(",").filter { it.isNotBlank() }.toSet()

    private fun r(v: Double) = Money.rupees(abs(v))
    private fun pct(v: Double) = "%.1f%%".format(Locale.US, abs(v))
    private fun upDown(v: Double) = if (v >= 0) "up" else "down"

    /** Your portfolio in a few spoken sentences: value, today's move, overall P&L, biggest movers. */
    fun summary(h: List<Holding>): String {
        if (h.isEmpty()) return "You have no holdings in Zerodha."
        val value = h.sumOf { it.value }
        val invested = h.sumOf { it.invested }
        val day = h.sumOf { it.dayChange }
        val pnl = value - invested
        val movers = h.sortedByDescending { abs(it.dayChangePct) }.take(3)
            .joinToString("; ") { "${it.symbol} ${upDown(it.dayChangePct)} ${pct(it.dayChangePct)}" }
        return "Your ${h.size} holdings are worth ${r(value)}, ${upDown(day)} ${r(day)} today. " +
            "Overall you're ${upDown(pnl)} ${r(pnl)} (${pct(if (invested > 0) pnl / invested * 100 else 0.0)}) on ${r(invested)} invested. " +
            "Biggest moves today: $movers. Prices from Zerodha, may be slightly delayed."
    }

    /** Holdings below the scan's stop-loss, one alert each per day. [stops] maps symbol to stop-loss price. */
    fun stopLossAlerts(h: List<Holding>, stops: Map<String, Double>, day: String): List<Alert> = h.mapNotNull { x ->
        val stop = stops[x.symbol] ?: return@mapNotNull null
        if (x.last <= 0 || x.last >= stop) return@mapNotNull null
        Alert("sl:${x.symbol}:$day", "${x.symbol} is below its stop-loss",
            "${x.symbol} at ${r(x.last)} is under the scan's stop-loss of ${r(stop)}. You hold ${x.qty}. The decision is yours.")
    }

    /** The answer for "my portfolio". Needs the network. */
    fun answer(ctx: Context): String = when {
        !isSetUp(ctx) -> "Zerodha isn't connected yet. Add your Kite Connect API key in Mehitha Settings."
        !isLoggedIn(ctx) -> "Please log in to Zerodha in Mehitha Settings. Zerodha asks for this once a day."
        else -> holdings(ctx)?.let(::summary) ?: "Your Zerodha login has expired. Please log in again in Settings."
    }

    /** One line for the briefing, or "" when not logged in. */
    fun briefLine(ctx: Context): String = try {
        if (isLoggedIn(ctx)) holdings(ctx)?.let { "Zerodha portfolio: " + summary(it) }.orEmpty() else ""
    } catch (e: Exception) { "" }
}

/** Zerodha's own login page inside Mehitha; it closes by itself once you're logged in. */
class ZerodhaLoginActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Zerodha.isSetUp(this)) { toast("Add your Kite API key and secret first"); finish(); return }
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val token = Zerodha.requestToken(request.url.toString()) ?: return false
                view.stopLoading()
                Thread {
                    val ok = try { Zerodha.finishLogin(this@ZerodhaLoginActivity, token) } catch (e: Exception) { false }
                    runOnUiThread {
                        toast(if (ok) "Logged in to Zerodha for today" else "Zerodha login didn't work. Check the API key and secret.")
                        finish()
                    }
                }.start()
                return true
            }
        }
        setContentView(web)
        web.loadUrl(Zerodha.loginUrl(Zerodha.apiKey(this)))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
