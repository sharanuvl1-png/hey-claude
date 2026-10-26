package com.personal.heyclaude

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Copies phone data into the Dropbox app folder so Claude (through the Dropbox
 * connector) can search it:
 *   /sms/YYYY-MM.md             text messages, one file per month
 *   /notifications/DATE.txt     WhatsApp and other app messages, from notifications
 *   /files/index.tsv            every file under the main folders (path, size, date)
 *   /files/docs/...             documents from Download, Documents and WhatsApp Documents
 *   /requests/NAME.txt          write a phone path or file name here to get that file
 *   /requested/...              where requested files are delivered
 */
object PhoneSync {

    private const val TAG = "PhoneSync"
    private const val MAX_DOC_BYTES = 25L * 1024 * 1024
    private const val MAX_REQUEST_BYTES = 140L * 1024 * 1024
    private val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt")
    private val OTP = Regex("(?i)\\b(otp|one[ -]?time password|verification code|security code|passcode)\\b|\\b\\d{4,8}\\b.{0,30}\\b(code|otp)\\b")

    fun looksLikeOtp(text: String) = OTP.containsMatchIn(text)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun lastStatus(ctx: Context): String = prefs(ctx).getString("status", "Not synced yet") ?: ""

    @Synchronized
    fun runAll(ctx: Context) {
        val box = DropboxClient(ctx)
        if (!box.isLinked) return
        val report = mutableListOf<String>()
        val firstRun = !prefs(ctx).getBoolean("did_full_sync", false)
        fun step(name: String, block: () -> String) {
            try {
                report += "$name: ${block()}"
            } catch (e: Exception) {
                Log.e(TAG, "$name failed", e)
                report += "$name: failed (${e.message?.take(80)})"
            }
        }
        step("SMS") { syncSms(ctx, box, firstRun) }
        step("App messages") { syncNotifications(ctx, box) }
        step("Files") { syncFiles(ctx, box) }
        step("Requests") { handleRequests(ctx, box) }
        prefs(ctx).edit().putBoolean("did_full_sync", true).apply()
        val stamp = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(Date())
        val status = "Last sync $stamp\n" + report.joinToString("\n")
        prefs(ctx).edit().putString("status", status).apply()
        try {
            box.upload("/status.md", "# Phone sync status\n\n" + report.joinToString("\n") { "- $it" } + "\n\n_${stamp}_\n")
        } catch (e: Exception) {
            Log.e(TAG, "status upload failed", e)
        }
    }

    // ------------------------------------------------------------ SMS

    private fun syncSms(ctx: Context, box: DropboxClient, full: Boolean): String {
        if (!hasPermission(ctx, android.Manifest.permission.READ_SMS)) return "skipped (no SMS permission)"
        val since = if (full) 0L else Calendar.getInstance().apply {
            add(Calendar.MONTH, -1); set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }.timeInMillis
        val names = mutableMapOf<String, String>()
        val months = sortedMapOf<String, StringBuilder>()
        val month = SimpleDateFormat("yyyy-MM", Locale.US)
        val day = SimpleDateFormat("EEE dd MMM yyyy", Locale.US)
        val time = SimpleDateFormat("HH:mm", Locale.US)
        var lastDay = ""
        var count = 0
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.DATE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.TYPE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val date = Date(c.getLong(0))
                val address = c.getString(1) ?: "unknown"
                val body = (c.getString(2) ?: "").replace(Regex("\\s+"), " ").trim()
                if (looksLikeOtp(body)) continue
                val sb = months.getOrPut(month.format(date)) { StringBuilder("# SMS ${month.format(date)}\n") }
                val d = day.format(date)
                if (d != lastDay) { sb.append("\n## $d\n"); lastDay = d }
                val who = names.getOrPut(address) { contactName(ctx, address) ?: address }
                val dir = if (c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "to" else "from"
                sb.append("- ${time.format(date)} $dir $who: $body\n")
                count++
            }
        }
        months.forEach { (m, text) -> box.upload("/sms/$m.md", text.toString()) }
        return "$count messages in ${months.size} month file(s)"
    }

    private fun contactName(ctx: Context, number: String): String? {
        if (!hasPermission(ctx, android.Manifest.permission.READ_CONTACTS) || number.none { it.isDigit() }) return null
        return try {
            ctx.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) "${it.getString(0)} ($number)" else null }
        } catch (e: Exception) {
            null
        }
    }

    // --------------------------------------------------- notifications

    private fun syncNotifications(ctx: Context, box: DropboxClient): String {
        val dir = File(ctx.filesDir, "notifications")
        val files = dir.listFiles()?.sortedBy { it.name } ?: return "nothing captured yet (enable notification access)"
        val last = prefs(ctx).getLong("notif_synced", 0)
        var sent = 0
        files.filter { it.lastModified() > last }.forEach {
            box.upload("/notifications/${it.name}", it.readText()); sent++
        }
        val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
        files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        prefs(ctx).edit().putLong("notif_synced", System.currentTimeMillis()).apply()
        return "$sent day file(s) updated"
    }

    // ----------------------------------------------------------- files

    private fun roots(): List<File> {
        val base = Environment.getExternalStorageDirectory()
        return listOf("Download", "Documents", "DCIM", "Pictures", "Movies", "Music", "Recordings",
            "Android/media/com.whatsapp/WhatsApp/Media").map { File(base, it) }.filter { it.isDirectory }
    }

    private fun canReadAll() = Environment.isExternalStorageManager()

    private fun allFiles(): Sequence<File> = roots().asSequence().flatMap { root ->
        root.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && !it.name.startsWith(".") }
    }

    private fun syncFiles(ctx: Context, box: DropboxClient): String {
        if (!canReadAll()) return "skipped (no all-files access)"
        val base = Environment.getExternalStorageDirectory().absolutePath
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val index = StringBuilder("path\tsize_kb\tmodified\n")
        val last = prefs(ctx).getLong("files_synced", 0)
        var total = 0
        var uploaded = 0
        val docRoots = listOf("$base/Download", "$base/Documents", "$base/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents")
        for (f in allFiles()) {
            total++
            index.append(f.absolutePath.removePrefix("$base/")).append('\t')
                .append(f.length() / 1024).append('\t').append(stamp.format(Date(f.lastModified()))).append('\n')
            val isDoc = f.extension.lowercase() in DOC_EXT && docRoots.any { f.absolutePath.startsWith(it) }
            if (isDoc && f.lastModified() > last && f.length() <= MAX_DOC_BYTES) {
                box.upload("/files/docs/" + f.absolutePath.removePrefix("$base/"), f.inputStream()); uploaded++
            }
        }
        box.upload("/files/index.tsv", index.toString())
        prefs(ctx).edit().putLong("files_synced", System.currentTimeMillis()).apply()
        return "$total files indexed, $uploaded document(s) uploaded"
    }

    // -------------------------------------------------------- requests

    /** A request file holds a phone path (from index.tsv) or part of a file name. */
    @Synchronized
    fun handleRequests(ctx: Context, box: DropboxClient = DropboxClient(ctx)): String {
        if (!box.isLinked) return "not linked"
        val requests = box.listFiles("/requests")
        if (requests.isEmpty()) return "none"
        if (!canReadAll()) return "waiting (no all-files access)"
        val base = Environment.getExternalStorageDirectory()
        var done = 0
        for (req in requests) {
            val wanted = box.download(req).trim()
            val direct = File(base, wanted.removePrefix(base.absolutePath).removePrefix("/"))
            val match = if (direct.isFile) direct else allFiles()
                .filter { it.name.contains(wanted, ignoreCase = true) }
                .maxByOrNull { it.lastModified() }
            val name = req.substringAfterLast('/').removeSuffix(".txt")
            when {
                match == null -> box.upload("/requested/$name - NOT FOUND.txt", "No file on the phone matched: $wanted\n")
                match.length() > MAX_REQUEST_BYTES -> box.upload("/requested/$name - TOO LARGE.txt",
                    "${match.absolutePath} is ${match.length() / 1024 / 1024} MB, over the 140 MB limit.\n")
                else -> { box.upload("/requested/${match.name}", match.inputStream()); done++ }
            }
            box.delete(req)
        }
        return "$done of ${requests.size} delivered"
    }

    private fun hasPermission(ctx: Context, p: String) =
        ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
