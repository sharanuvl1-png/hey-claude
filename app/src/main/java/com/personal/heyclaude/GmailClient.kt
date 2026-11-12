package com.personal.heyclaude

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Read-only link to Sharan's personal Gmail through a small Google Apps Script web app that runs in
 * Sharan's own Google account (tools/gmail-link). Mehitha keeps the web app URL and its key; the
 * script can only search and read mail (gmail.readonly), never send, delete or change anything.
 */
class GmailClient(context: Context) {
    private val prefs = context.getSharedPreferences("gmail_link", Context.MODE_PRIVATE)

    val url: String get() = prefs.getString("url", "").orEmpty()
    val isLinked: Boolean get() = url.isNotBlank() && prefs.getString("key", "").orEmpty().isNotBlank()

    /** Checks [url] and [key] with the script and saves them; returns the linked Gmail address. */
    fun link(url: String, key: String): String {
        val u = url.trim(); val k = key.trim()
        require(u.startsWith("https://script.google.com/")) { "That isn't an Apps Script web app URL (it starts with https://script.google.com/)." }
        require(k.length >= 16) { "That key is too short. Copy the whole key from the script's log." }
        val json = call(u, k, "ping")
        prefs.edit().putString("url", u).putString("key", k).apply()
        return json.optString("email")
    }

    fun unlink() = prefs.edit().clear().apply()

    /** Messages matching a Gmail search such as "from:icici statement newer_than:3m". */
    fun search(query: String, max: Int = 10): List<Mail> =
        parseSearch(call(url, prefs.getString("key", "").orEmpty(), "search", "q" to query.take(300), "max" to max.toString()))

    /** One message's text (and its attachment names). */
    fun read(id: String): Mail = parseMail(call(url, prefs.getString("key", "").orEmpty(), "read", "id" to id))

    private fun call(url: String, key: String, action: String, vararg params: Pair<String, String>): JSONObject {
        val full = Uri.parse(url).buildUpon().appendQueryParameter("action", action).appendQueryParameter("key", key)
            .apply { params.forEach { appendQueryParameter(it.first, it.second) } }.build().toString()
        val conn = (URL(full).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) error("Gmail link said $code. Check the web app URL in Settings.")
        val json = try { JSONObject(body) } catch (e: Exception) {
            error("The Gmail link didn't answer properly. Check the web app is deployed with access \"Anyone\".")
        }
        json.optString("error").takeIf { it.isNotBlank() }?.let {
            error(if (it == "wrong key") "The Gmail key doesn't match. Copy it again from the script's log." else "Gmail: $it")
        }
        return json
    }

    data class Mail(val id: String, val from: String, val subject: String, val date: String, val snippet: String,
                    val body: String = "", val attachments: List<String> = emptyList()) {
        val openUrl get() = "https://mail.google.com/mail/u/0/#all/$id"
    }

    companion object {
        fun parseSearch(json: JSONObject): List<Mail> {
            val list = json.optJSONArray("messages") ?: return emptyList()
            return (0 until list.length()).map { list.getJSONObject(it) }.map { o ->
                Mail(o.optString("id"), o.optString("from"), o.optString("subject"), o.optString("date"), o.optString("snippet"))
            }
        }

        fun parseMail(o: JSONObject): Mail {
            val files = o.optJSONArray("attachments")
            return Mail(o.optString("id"), o.optString("from"), o.optString("subject"), o.optString("date"), "",
                o.optString("body"), (0 until (files?.length() ?: 0)).map { files!!.getString(it) })
        }

        /** One line per message for Claude, newest first as Gmail gives them. */
        fun text(query: String, mails: List<Mail>): String =
            if (mails.isEmpty()) "No Gmail messages match \"$query\". Try other words or a wider date (e.g. newer_than:6m)."
            else "Gmail messages for \"$query\" (${mails.size}, newest first):\n" + mails.joinToString("\n") { m ->
                "id ${m.id} | ${m.date} | from ${m.from} | ${m.subject} | ${snippet(m)}"
            }

        /** Gmail's preview with its HTML entities undone; OTP mails show no preview. */
        fun snippet(m: Mail): String {
            val s = m.snippet.replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            return if (PhoneSync.looksLikeOtp(m.subject) || (PhoneSync.looksLikeOtp(s) && CODE.containsMatchIn(s))) "(one-time code, not shown)" else s
        }

        fun mailText(m: Mail): String = "From ${m.from}\nDate ${m.date}\nSubject ${m.subject}\nOpen link ${m.openUrl}\n" +
            (if (m.attachments.isNotEmpty()) "Attachments: ${m.attachments.joinToString(", ")} (not read here)\n" else "") +
            "\n" + (if (PhoneSync.looksLikeOtp(m.subject)) "(A one-time code mail; not shown.)"
                else m.body.lines().filterNot { PhoneSync.looksLikeOtp(it) && CODE.containsMatchIn(it) }.joinToString("\n"))

        /** A 4 to 8 digit number, as in a one-time code. */
        private val CODE = Regex("\\b\\d{4,8}\\b")
    }
}
