package com.personal.heyclaude

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Telephony
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/** Reads the phone's own data for Mehitha's commands, right on the phone (no Dropbox needed). */
open class PhoneData(private val ctx: Context) {

    open val canReadSms get() = ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Spends from bank and UPI SMS in the last [days] days. */
    open fun spends(days: Long = 100): List<Spend> = sms(days).mapNotNull { (time, sender, body) ->
        PhoneSync.expenseRow(Date.from(time.atZone(IST).toInstant()), sender, body)?.let { Money.parseRow(it) }
    }

    /** Bills with a due date, from SMS in the last [days] days. */
    open fun bills(days: Long = 40): List<Bill> = sms(days).mapNotNull { (time, sender, body) ->
        Money.billDue(sender, body, time.toLocalDate())
    }

    private fun sms(days: Long): List<Triple<LocalDateTime, String, String>> {
        if (!canReadSms) return emptyList()
        val since = System.currentTimeMillis() - days * 24 * 3600 * 1000
        val out = mutableListOf<Triple<LocalDateTime, String, String>>()
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.DATE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val body = (c.getString(2) ?: "").replace(Regex("\\s+"), " ").trim()
                if (PhoneSync.looksLikeOtp(body)) continue
                out += Triple(LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(0)), IST), c.getString(1) ?: "", body)
            }
        }
        return out
    }

    /** Facts about spending for Claude, or why there are none. */
    open fun moneyFacts(question: String): String =
        if (!canReadSms) NO_SMS else Money.summary(spends(), today(), question)

    /** Today's WhatsApp and app messages captured from notifications, newest last. */
    open fun messagesToday(max: Int = 3500): String {
        val file = File(File(ctx.filesDir, "notifications"), "${today()}.txt")
        if (!file.exists()) return "none captured today (needs WhatsApp & app messages turned on in Hi Mehitha)"
        val lines = file.readLines().map { it.split(" | ") }.filter { it.size >= 4 && it[1] != "Hi Mehitha" }
            .map { "${it[0]} ${it[1]}, ${it[2]}: ${it[3]}" }
        return lines.joinToString("; ").takeLast(max).ifEmpty { "none today" }
    }

    /** Phone file names matching the question. */
    open fun files(question: String): String {
        if (!Environment.isExternalStorageManager()) return "not searchable (Files and photos not allowed in Hi Mehitha)"
        val words = AssistantText.keywords(question).filter { it !in setOf("expire", "expires", "expiry", "renewal", "when", "does") }
        return PhoneTools(ctx).searchFiles(words).take(10).joinToString("; ").ifEmpty { "none" }
    }

    /** Today's stock scan as markdown, or "" when it can't be fetched. */
    open fun scan(): String = try {
        val conn = URL(SCAN_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try { if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else "" } finally { conn.disconnect() }
    } catch (e: Exception) { "" }

    companion object {
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        const val SCAN_URL = "https://raw.githubusercontent.com/sharanuvl1-png/hey-claude/charts/LATEST_SCAN.md"
        const val ALERTS_URL = "https://raw.githubusercontent.com/sharanuvl1-png/hey-claude/charts/ALERTS.json"
        const val NO_SMS = "This version of Mehitha can't read text messages, so no spending data is available."
        fun now(): LocalDateTime = LocalDateTime.now(IST)
        fun today(): LocalDate = LocalDate.now(IST)
    }
}
