package com.personal.heyclaude

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Telephony
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The assistant's tools, searched right on the phone so they work without Dropbox:
 * SMS (full build), captured WhatsApp/app messages (full build), and file names.
 */
class PhoneTools(private val ctx: Context) : MehithaTools {

    override fun searchPhoneData(query: String): String {
        val words = AssistantText.keywords(query)
        if (words.isEmpty()) return "Give a name, company, amount or file name to search for."
        val parts = mutableListOf<String>()
        val notes = mutableListOf<String>()

        if (granted(Manifest.permission.READ_SMS)) {
            parts += section("Text messages", searchSms(words))
        } else {
            notes += "Text messages: not available (this version can't read SMS)."
        }

        val dir = File(ctx.filesDir, "notifications")
        val days = dir.listFiles()?.sortedByDescending { it.name }.orEmpty()
        if (days.isNotEmpty()) {
            val lines = days.asSequence().flatMap { f -> f.readLines().asSequence().map { "${f.nameWithoutExtension} $it" } }
            parts += section("WhatsApp and app messages", AssistantText.bestLines(lines, words))
        } else {
            notes += "WhatsApp and app messages: none captured yet."
        }

        if (Environment.isExternalStorageManager()) {
            parts += section("Files on the phone", searchFiles(words))
        } else {
            notes += "Files: not searchable until 'Files and photos' is allowed in the Mehitha app."
        }

        val found = parts.filter { it.isNotEmpty() }
        return (if (found.isEmpty()) "Nothing matched: ${words.joinToString(", ")}." else found.joinToString("\n\n")) +
            (if (notes.isEmpty()) "" else "\n\n" + notes.joinToString("\n"))
    }

    override fun stockScan(): String {
        val conn = URL(SCAN_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        return try {
            if (conn.responseCode != 200) throw IllegalStateException("stock scan not available (HTTP ${conn.responseCode})")
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun searchSms(words: List<String>): List<String> {
        val day = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US)
        val where = words.joinToString(" OR ") { "${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?" }
        val args = words.flatMap { listOf("%$it%", "%$it%") }.toTypedArray()
        val lines = mutableListOf<String>()
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.DATE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.TYPE),
            where, args, "${Telephony.Sms.DATE} DESC LIMIT 200"
        )?.use { c ->
            while (c.moveToNext()) {
                val body = (c.getString(2) ?: "").replace(Regex("\\s+"), " ")
                if (PhoneSync.looksLikeOtp(body)) continue
                val dir = if (c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "to" else "from"
                lines += "${day.format(Date(c.getLong(0)))} $dir ${c.getString(1)}: $body"
            }
        }
        return AssistantText.bestLines(lines.asSequence(), words)
    }

    internal fun searchFiles(words: List<String>): List<String> {
        val base = Environment.getExternalStorageDirectory()
        val day = SimpleDateFormat("dd MMM yyyy", Locale.US)
        val roots = listOf("Download", "Documents", "DCIM", "Pictures", "Recordings",
            "Android/media/com.whatsapp/WhatsApp/Media").map { File(base, it) }.filter { it.isDirectory }
        val hits = roots.asSequence()
            .flatMap { it.walkTopDown().maxDepth(6).filter { f -> f.isFile } }
            .take(60_000)
            .map { "${it.path.removePrefix(base.path + "/")} (${it.length() / 1024} KB, ${day.format(Date(it.lastModified()))})" }
        return AssistantText.bestLines(hits, words, limit = 25)
    }

    private fun section(title: String, lines: List<String>) =
        if (lines.isEmpty()) "" else "$title:\n" + lines.joinToString("\n")

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val SCAN_URL = "https://raw.githubusercontent.com/sharanuvl1-png/hey-claude/charts/LATEST_SCAN.md"
    }
}
