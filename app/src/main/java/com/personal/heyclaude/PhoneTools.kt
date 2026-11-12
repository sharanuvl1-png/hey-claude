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

        val screens = ScreenApps.dir(ctx).listFiles()?.sortedByDescending { it.name }.orEmpty()
        if (screens.isNotEmpty()) {
            val lines = screens.asSequence().flatMap { f -> f.readLines().asReversed().asSequence().map { "${f.nameWithoutExtension} $it" } }
            parts += section("Screens saved from Sharan's chosen apps (newest first)", AssistantText.bestLines(lines, words, limit = 5))
        }

        if (try { Environment.isExternalStorageManager() } catch (e: Exception) { false }) {
            parts += section("Files on the phone", searchFiles(words))
        } else {
            notes += "Files: not searchable until 'Files and photos' is allowed in the Mehitha app."
        }

        val found = parts.filter { it.isNotEmpty() }
        return (if (found.isEmpty()) "Nothing matched: ${words.joinToString(", ")}." else found.joinToString("\n\n")) +
            (if (notes.isEmpty()) "" else "\n\n" + notes.joinToString("\n"))
    }

    override fun reminders(): String = Reminders.listText(Reminders.upcoming(ctx))

    override fun calendar(date: String, days: Int): String {
        val from = runCatching { java.time.LocalDate.parse(date.trim()) }.getOrNull() ?: PhoneData.today()
        return Today.calendarText(ctx, from, days.coerceIn(1, 14))
    }

    override fun recentMessages(days: Int, app: String): String {
        val d = days.coerceIn(1, 14).toLong()
        return Chats.listText(Chats.read(java.io.File(ctx.filesDir, "notifications"), PhoneData.today(), d), d, app)
    }

    override fun searchDropbox(query: String): String {
        val box = DropboxClient(ctx, DOCS)
        if (!box.isLinked) return "Sharan's Dropbox documents aren't linked yet. Say they can link them in Mehitha Settings, Phone data, Dropbox documents."
        val files = box.search(query)
        if (files.isEmpty()) return "No Dropbox files match \"$query\". Try other words from the file name."
        return dropboxText(files) { f ->
            // Small text files are read so the answer can quote them; other files are found by name and content.
            if (f.size in 1..200_000 && TEXT_FILE.containsMatchIn(f.name)) try { box.download(f.path).take(1500) } catch (e: Exception) { null } else null
        }
    }

    override fun readDropboxFile(path: String): FileForClaude {
        val box = DropboxClient(ctx, DOCS)
        if (!box.isLinked) return FileForClaude("Sharan's Dropbox documents aren't linked yet.")
        return readFile(path.substringAfterLast('/'), "Dropbox", { box.download(path) }, { max -> box.downloadBytes(path, max) })
    }

    override val hasWorkFiles: Boolean get() = OneDriveClient(ctx).isLinked

    override fun searchWorkOneDrive(query: String): String {
        val drive = OneDriveClient(ctx)
        if (!drive.isLinked) return "Sharan's work OneDrive isn't linked in Mehitha. Say they can link it in Settings, Phone data, Work OneDrive, or use the Claude app for work files."
        val files = drive.search(query)
        return if (files.isEmpty()) "No work OneDrive files match \"$query\". Try other words from the file name." else OneDriveClient.text(files)
    }

    override fun readWorkOneDriveFile(id: String, name: String): FileForClaude {
        val drive = OneDriveClient(ctx)
        if (!drive.isLinked) return FileForClaude("Sharan's work OneDrive isn't linked in Mehitha.")
        val office = OneDriveClient.WorkFile(id, name, "", "", 0, "").isOffice
        // Word, Excel and PowerPoint files are turned into PDF by OneDrive, so they read like any PDF.
        val shown = if (office) name.substringBeforeLast('.') + ".pdf" else name
        return readFile(shown, "work OneDrive", { String(drive.downloadBytes(id, 2_000_000) ?: ByteArray(0)) },
            { max -> drive.downloadBytes(id, max, asPdf = office) })
    }

    /** Reads one document by its [name]'s type: text as text, PDFs as page pictures, photos as a picture. */
    private fun readFile(name: String, where: String, text: () -> String, bytes: (Long) -> ByteArray?): FileForClaude = when {
        TEXT_FILE.containsMatchIn(name) -> FileForClaude("$name:\n" + text().take(20_000), name)
        name.endsWith(".pdf", true) -> bytes(MAX_PDF)?.let { pdf(name, it, where) }
            ?: FileForClaude("$name is over ${MAX_PDF / 1_048_576} MB, too big to read here. Sharan can open it from $where.", name)
        IMAGE_FILE.containsMatchIn(name) -> bytes(MAX_IMAGE)?.let { raw ->
            // Phone photos are made smaller (and plain JPEG) first: Claude refuses pictures over 5 MB.
            val pic = Snips.shrink(raw) ?: return FileForClaude("$name couldn't be opened here. Sharan can open it from $where.", name)
            val id = Snips.register(Snips.imagePage(ctx, pic, "jpg"))
            FileForClaude("The picture $name is attached.", name, image = pic, imageType = "image/jpeg", snipId = id) }
            ?: FileForClaude("$name is over ${MAX_IMAGE / 1_048_576} MB, too big to read here. Sharan can open it from $where.", name)
        else -> FileForClaude("$name can't be read here (only PDFs, Office files, pictures and text files). Sharan can open it from $where.", name)
    }

    /**
     * A short PDF goes to Claude as page pictures, so it can point at the part with the answer;
     * a longer one goes whole, with its first pages still kept for snippets.
     */
    private fun pdf(name: String, bytes: ByteArray, where: String): FileForClaude {
        val pages = Snips.pdfPages(ctx, bytes)
        // A PDF the phone can't open is usually password-protected (many policies and statements are); Claude can't open it either.
        if (pages.isEmpty()) return FileForClaude("$name couldn't be opened here: it may be password-protected. " +
            "Sharan can open it from $where.", name)
        val id = pages.takeIf { it.isNotEmpty() }?.let { Snips.register(it) }
        val count = if (pages.isEmpty()) 0 else Snips.pageCount(ctx, bytes)
        return if (pages.isNotEmpty() && count <= Snips.MAX_PAGES)
            FileForClaude("$name has $count page${if (count > 1) "s" else ""}, attached as pictures.", name, pages = pages.map { it.readBytes() }, snipId = id)
        else FileForClaude("The PDF $name is attached" + (if (id != null) "; snippets can come from its first ${Snips.MAX_PAGES} pages." else "."),
            name, pdf = bytes, snipId = id)
    }

    override fun stockChart(stock: String): String {
        val scan = try { stockScan() } catch (e: Exception) { "" }
        val chart = Charts.find(stock, scan) ?: return "No chart found for $stock. It can be made with the Technical chart action on GitHub."
        val report = try { Charts.text(chart.reportUrl) } catch (e: Exception) { "" }
        return "Chart for ${chart.symbol} made on ${chart.date}.\nIMAGE: ${chart.imageUrl}\n\n" + report.take(6000)
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

        /** The read-only link to Sharan's own Dropbox (Full Dropbox app), kept apart from the phone sync. */
        const val DOCS = "dropbox_docs"
        private val TEXT_FILE = Regex("(?i)\\.(txt|md|csv|json)$")
        private val IMAGE_FILE = Regex("(?i)\\.(jpe?g|png|webp|gif)$")
        private const val MAX_PDF = 8L * 1_048_576
        private const val MAX_IMAGE = 15L * 1_048_576

        fun imageType(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg"
        }

        /** One block per file: name, folder, date, size, open link, and the text when [content] gives it. */
        fun dropboxText(files: List<DropboxClient.DropboxFile>, content: (DropboxClient.DropboxFile) -> String? = { null }): String =
            "Dropbox files found (${files.size}):\n" + files.joinToString("\n\n") { f ->
                val kb = if (f.size >= 1_048_576) "%.1f MB".format(Locale.US, f.size / 1_048_576.0) else "${(f.size + 1023) / 1024} KB"
                "${f.name} | path ${f.path} | folder ${f.folder} | modified ${f.modified} | $kb | open link ${f.openUrl}" +
                    (content(f)?.let { "\nText: $it" } ?: "")
            }
    }
}
