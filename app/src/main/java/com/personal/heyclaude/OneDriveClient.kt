package com.personal.heyclaude

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Read-only link to Sharan's work OneDrive through Microsoft Graph. Sign-in uses the device code
 * flow: Mehitha shows a short code, Sharan enters it at microsoft.com/devicelogin with the work
 * account, and Mehitha keeps the refresh token. It needs an app registration in the work
 * directory (its Application (client) ID, with "Allow public client flows" on); the company's IT
 * may have to approve it. Work files stay separate from personal data.
 */
class OneDriveClient(context: Context) {
    private val prefs = context.getSharedPreferences("onedrive_work", Context.MODE_PRIVATE)

    var clientId: String
        get() = prefs.getString("client_id", "") ?: ""
        set(value) = prefs.edit().putString("client_id", value.trim()).apply()

    val isLinked: Boolean get() = prefs.getString("refresh_token", null) != null

    /** The code to enter at [url]; [deviceCode] and [interval] are for [waitForSignIn]. */
    data class SignIn(val userCode: String, val url: String, val deviceCode: String, val interval: Int, val expiresIn: Int)

    fun startSignIn(): SignIn {
        val (code, body) = post("$LOGIN/devicecode", mapOf("client_id" to clientId, "scope" to SCOPES))
        val json = JSONObject(body)
        if (code !in 200..299) error(json.optString("error_description", "Microsoft refused the sign-in ($code)").lineSequence().first())
        return SignIn(json.getString("user_code"), json.optString("verification_uri", "https://microsoft.com/devicelogin"),
            json.getString("device_code"), json.optInt("interval", 5), json.optInt("expires_in", 900))
    }

    /** Waits until Sharan finishes signing in (or the code expires); slow, run off the screen thread. */
    fun waitForSignIn(s: SignIn) {
        val end = System.currentTimeMillis() + s.expiresIn * 1000L
        var wait = s.interval.coerceAtLeast(2) * 1000L
        while (System.currentTimeMillis() < end) {
            Thread.sleep(wait)
            val (code, body) = post("$LOGIN/token", mapOf("client_id" to clientId,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to s.deviceCode))
            val json = JSONObject(body)
            if (code in 200..299) { save(json, SCOPES); return }
            when (json.optString("error")) {
                "authorization_pending" -> {}
                "slow_down" -> wait += 5000
                else -> error(json.optString("error_description", json.optString("error")).lineSequence().first())
            }
        }
        error("The sign-in code expired. Please try again.")
    }

    fun unlink() = prefs.edit().remove("refresh_token").remove("access_token").remove("expires_at").remove("scopes").apply()

    /** True when the sign-in includes reading work mail and calendar (signed in with build 83 or later). */
    val hasMail: Boolean get() = isLinked && prefs.getString("scopes", "").orEmpty().contains("Mail.Read")

    private fun save(json: JSONObject, scopes: String? = null) {
        val edit = prefs.edit().putString("access_token", json.getString("access_token"))
        if (scopes != null) edit.putString("scopes", scopes)
            .putLong("expires_at", System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000 - 60_000)
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { edit.putString("refresh_token", it) }
        edit.apply()
    }

    private fun token(): String {
        val cached = prefs.getString("access_token", null)
        if (cached != null && System.currentTimeMillis() < prefs.getLong("expires_at", 0)) return cached
        val refresh = prefs.getString("refresh_token", null) ?: error("Work OneDrive is not linked")
        val (code, body) = post("$LOGIN/token", mapOf("client_id" to clientId, "grant_type" to "refresh_token",
            "refresh_token" to refresh, "scope" to (prefs.getString("scopes", null) ?: OLD_SCOPES)))
        if (code !in 200..299) error("Work OneDrive sign-in expired. Link it again in Settings.")
        save(JSONObject(body))
        return prefs.getString("access_token", "")!!
    }

    /** Files in the work OneDrive matching [query] by name or content. */
    fun search(query: String, max: Int = 12): List<WorkFile> {
        val q = query.take(200).replace("'", "''")
        val url = "$GRAPH/me/drive/root/search(q='${Uri.encode(q)}')?\$top=$max" +
            "&\$select=id,name,webUrl,lastModifiedDateTime,size,parentReference,file,folder"
        return parseSearch(get(url))
    }

    /**
     * The file's bytes, or null when it is bigger than [max]. Word, Excel and PowerPoint files
     * come back as PDF when [asPdf] is true (Graph converts them).
     */
    fun downloadBytes(id: String, max: Long, asPdf: Boolean = false): ByteArray? {
        val conn = (URL("$GRAPH/me/drive/items/${Uri.encode(id)}/content" + if (asPdf) "?format=pdf" else "").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer ${token()}")
        }
        val code = conn.responseCode
        val file = if (code in 300..399) {
            val to = conn.getHeaderField("Location") ?: error("OneDrive gave no download link")
            conn.disconnect()
            // The download link is pre-signed and must not get the Graph token.
            (URL(to).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 120_000 }
        } else conn
        if (file.responseCode !in 200..299) error("OneDrive download failed (${file.responseCode})")
        if (file.contentLengthLong > max) { file.disconnect(); return null }
        return file.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) return null
            }
            out.toByteArray()
        }
    }

    /** Work mail: [query] searched across the mailbox, or with an empty query the unread inbox mail of the last [days] days. */
    fun mail(query: String, days: Int = 2, max: Int = 15): List<WorkMail> {
        val select = "\$select=id,subject,from,receivedDateTime,bodyPreview,webLink,isRead,importance"
        val url = if (query.isNotBlank())
            "$GRAPH/me/messages?\$search=${Uri.encode("\"" + query.take(200).replace("\"", "") + "\"")}&\$top=$max&$select"
        else {
            val since = java.time.Instant.now().minus(java.time.Duration.ofDays(days.toLong().coerceIn(1, 14))).toString()
            "$GRAPH/me/mailFolders/inbox/messages?\$filter=${Uri.encode("receivedDateTime ge $since and isRead eq false")}" +
                "&\$orderby=${Uri.encode("receivedDateTime desc")}&\$top=$max&$select"
        }
        return parseMail(get(url))
    }

    /** One work email's text. */
    fun readMail(id: String): WorkMail = parseMail("{\"value\":[" + get("$GRAPH/me/messages/${Uri.encode(id)}" +
        "?\$select=id,subject,from,receivedDateTime,body,webLink,isRead,importance", "outlook.body-content-type=\"text\"") + "]}").single()

    /** Work calendar events from [from] for [days] days, in India time. */
    fun calendar(from: java.time.LocalDate, days: Int): List<WorkEvent> {
        val start = Uri.encode(from.atStartOfDay(PhoneData.IST).toOffsetDateTime().toString())
        val end = Uri.encode(from.plusDays(days.toLong()).atStartOfDay(PhoneData.IST).toOffsetDateTime().toString())
        return parseEvents(get("$GRAPH/me/calendarView?startDateTime=$start&endDateTime=$end&\$top=60" +
            "&\$orderby=${Uri.encode("start/dateTime")}&\$select=subject,start,end,location,isAllDay,isCancelled,organizer,showAs",
            "outlook.timezone=\"India Standard Time\""))
    }

    private fun get(url: String, prefer: String? = null): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer ${token()}")
            setRequestProperty("Accept", "application/json")
            if (prefer != null) setRequestProperty("Prefer", prefer)
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) error("OneDrive said $code: " + (runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull() ?: body.take(160)))
        return body
    }

    private fun post(url: String, form: Map<String, String>): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { o -> o.write(form.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }.toByteArray()) }
        val code = conn.responseCode
        return code to ((if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}")
    }

    data class WorkFile(val id: String, val name: String, val folder: String, val modified: String, val size: Long, val webUrl: String) {
        /** Word, Excel and PowerPoint files, which Graph can turn into PDF. */
        val isOffice get() = OFFICE.containsMatchIn(name)
    }

    data class WorkMail(val id: String, val subject: String, val from: String, val received: String, val preview: String,
                        val body: String, val webUrl: String, val unread: Boolean, val important: Boolean)

    data class WorkEvent(val subject: String, val start: String, val end: String, val allDay: Boolean, val location: String, val organizer: String)

    companion object {
        private const val LOGIN = "https://login.microsoftonline.com/organizations/oauth2/v2.0"
        private const val GRAPH = "https://graph.microsoft.com/v1.0"
        const val SCOPES = "offline_access User.Read Files.Read Mail.Read Calendars.Read"
        /** What sign-ins before work mail asked for. */
        private const val OLD_SCOPES = "offline_access User.Read Files.Read"
        private val OFFICE = Regex("(?i)\\.(docx?|xlsx?|pptx?|rtf|odt|ods|odp)$")

        /** Files (not folders) from a Graph drive search answer. */
        fun parseSearch(json: String): List<WorkFile> {
            val items = JSONObject(json).optJSONArray("value") ?: return emptyList()
            return (0 until items.length()).map { items.getJSONObject(it) }.filter { !it.has("folder") }.map { o ->
                val parent = o.optJSONObject("parentReference")?.optString("path").orEmpty()
                WorkFile(o.optString("id"), o.optString("name"),
                    parent.substringAfter("root:", "").ifEmpty { "/" }.let { Uri.decode(it) },
                    o.optString("lastModifiedDateTime").take(10), o.optLong("size"), o.optString("webUrl"))
            }
        }

        fun parseMail(json: String): List<WorkMail> {
            val items = JSONObject(json).optJSONArray("value") ?: return emptyList()
            return (0 until items.length()).map { items.getJSONObject(it) }.map { o ->
                val from = o.optJSONObject("from")?.optJSONObject("emailAddress")
                WorkMail(o.optString("id"), o.optString("subject"),
                    listOf(from?.optString("name").orEmpty(), from?.optString("address")?.let { "<$it>" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
                    o.optString("receivedDateTime"), o.optString("bodyPreview"),
                    o.optJSONObject("body")?.optString("content").orEmpty(), o.optString("webLink"),
                    !o.optBoolean("isRead", true), o.optString("importance") == "high")
            }
        }

        fun parseEvents(json: String): List<WorkEvent> {
            val items = JSONObject(json).optJSONArray("value") ?: return emptyList()
            return (0 until items.length()).map { items.getJSONObject(it) }.filter { !it.optBoolean("isCancelled") }.map { o ->
                WorkEvent(o.optString("subject").ifBlank { "Busy" }, o.optJSONObject("start")?.optString("dateTime").orEmpty().take(16),
                    o.optJSONObject("end")?.optString("dateTime").orEmpty().take(16), o.optBoolean("isAllDay"),
                    o.optJSONObject("location")?.optString("displayName").orEmpty(),
                    o.optJSONObject("organizer")?.optJSONObject("emailAddress")?.optString("name").orEmpty())
            }
        }

        /** Work mail for Claude: one line each, newest first, OTP-looking previews hidden. */
        fun mailText(query: String, mails: List<WorkMail>): String =
            if (mails.isEmpty()) (if (query.isBlank()) "No unread work email in the inbox for that time." else "No work email matches \"$query\".")
            else "Work Outlook email${if (query.isBlank()) " (unread, inbox)" else " for \"$query\""} (${mails.size}):\n" + mails.joinToString("\n") { m ->
                "id ${m.id} | ${m.received.take(16).replace('T', ' ')} UTC | from ${m.from} | ${m.subject}" +
                    (if (m.important) " | high importance" else "") + (if (m.unread) " | unread" else "") +
                    " | " + (if (PhoneSync.looksLikeOtp(m.subject + " " + m.preview)) "(code not shown)" else m.preview.replace(Regex("\\s+"), " ").take(200))
            }

        fun readText(m: WorkMail): String = "From ${m.from}\nReceived ${m.received.take(16).replace('T', ' ')} UTC\nSubject ${m.subject}\n" +
            "Open link ${m.webUrl}\n\n" + m.body.lines().filterNot { PhoneSync.looksLikeOtp(it) && Regex("\\b\\d{4,8}\\b").containsMatchIn(it) }
                .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").take(12_000)

        fun eventsText(from: java.time.LocalDate, days: Int, events: List<WorkEvent>): String =
            "Work Outlook calendar, ${from} for $days day${if (days > 1) "s" else ""} (India time):\n" +
                (if (events.isEmpty()) "No events." else events.joinToString("\n") { e ->
                    (if (e.allDay) e.start.take(10) + " all day" else e.start.replace('T', ' ') + " to " + e.end.substringAfter('T')) +
                        " | ${e.subject}" + (if (e.location.isNotBlank()) " | ${e.location}" else "") + (if (e.organizer.isNotBlank()) " | by ${e.organizer}" else "")
                })

        /** One line per file for Claude: name, id, folder, date, size and open link. */
        fun text(files: List<WorkFile>): String = "Work OneDrive files found (${files.size}):\n" + files.joinToString("\n") { f ->
            val kb = if (f.size >= 1_048_576) "%.1f MB".format(Locale.US, f.size / 1_048_576.0) else "${(f.size + 1023) / 1024} KB"
            "${f.name} | id ${f.id} | folder ${f.folder} | modified ${f.modified} | $kb | open link ${f.webUrl}"
        }
    }
}
