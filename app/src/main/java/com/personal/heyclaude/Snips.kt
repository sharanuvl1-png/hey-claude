package com.personal.heyclaude

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pictures of documents Mehitha read: each page is saved as a picture for Claude to read, and
 * Claude can ask for the part of a page that holds the answer with a line
 * "SNIP: d1 | page 2 | 0.30-0.45" (top and bottom as fractions of the page height). The app
 * cuts that band out of the page and shows it in the chat, so you don't have to open the file.
 */
object Snips {
    private val pages = ConcurrentHashMap<String, List<File>>()
    private val next = AtomicInteger(1)
    const val MAX_PAGES = 5
    private const val WIDTH = 1240
    private const val MAX_SIDE = 1600

    private val LINE = Regex("(?im)^[ \\t*_>-]*snip[ \\t*_]*:[ \\t*_]*(d\\d+)[ \\t]*\\|[ \\t]*page[ \\t]*(\\d+)[ \\t]*\\|[ \\t]*([0-9.]+)[ \\t]*[-–to ]+[ \\t]*([0-9.]+)[^\\n]*(\\r?\\n|\\z)")

    /** Remembers a document's page pictures and returns the id Claude uses in SNIP lines. */
    fun register(files: List<File>): String = "d${next.getAndIncrement()}".also { pages[it] = files }

    /** Forgets a document's pages, so no snippet can be cut from it. */
    fun drop(id: String) { pages.remove(id) }

    private val PRIVATE = Regex("(?i)pan|aadha?r|passport|licen[cs]e|voter|card|bank|statement|salary|pay ?slip|itr|tax|form ?16|" +
        "polic|insurance|medical|health|hospital|report|kyc|account|cheque|upi")

    /** True when a file's name suggests confidential details, so its snippets need the passcode first. */
    fun isPrivate(name: String) = PRIVATE.containsMatchIn(name)

    /** One cut-out request from a SNIP line. */
    data class Snip(val id: String, val page: Int, val top: Double, val bottom: Double)

    /** The SNIP lines in [text], and the text without them. */
    fun parse(text: String): Pair<String, List<Snip>> {
        val found = LINE.findAll(text).mapNotNull { m ->
            val top = m.groupValues[3].toDoubleOrNull() ?: return@mapNotNull null
            val bottom = m.groupValues[4].toDoubleOrNull() ?: return@mapNotNull null
            Snip(m.groupValues[1], m.groupValues[2].toInt(), top, bottom)
        }.distinct().take(3).toList()
        return text.replace(LINE, "").replace(Regex("\\n{3,}"), "\n\n").trim() to found
    }

    /**
     * The band to cut, as fractions of the page height: a little wider than asked so nothing
     * is clipped, at least 10% of the page tall, and inside the page.
     */
    fun band(top: Double, bottom: Double): Pair<Double, Double> {
        var t = minOf(top, bottom).coerceIn(0.0, 1.0) - 0.03
        var b = maxOf(top, bottom).coerceIn(0.0, 1.0) + 0.03
        if (b - t < 0.10) { val mid = (t + b) / 2; t = mid - 0.05; b = mid + 0.05 }
        if (t < 0) { b -= t; t = 0.0 }
        if (b > 1) { t -= b - 1; b = 1.0 }
        return t.coerceAtLeast(0.0) to b.coerceAtMost(1.0)
    }

    /** Cuts each SNIP out of its page and saves it; returns the pictures' file:// links for the chat. */
    fun apply(ctx: Context, text: String): Pair<String, List<String>> {
        val (rest, snips) = parse(text)
        val out = snips.mapNotNull { s ->
            try {
                val page = pages[s.id]?.getOrNull(s.page - 1) ?: return@mapNotNull null
                val bmp = BitmapFactory.decodeFile(page.path) ?: return@mapNotNull null
                val (t, b) = band(s.top, s.bottom)
                val y = (t * bmp.height).toInt()
                val h = ((b - t) * bmp.height).toInt().coerceAtLeast(1).coerceAtMost(bmp.height - y)
                val cut = Bitmap.createBitmap(bmp, 0, y, bmp.width, h)
                val dir = File(ctx.cacheDir, "snips").apply { mkdirs() }
                val file = File(dir, UUID.randomUUID().toString() + ".jpg")
                file.outputStream().use { cut.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                "file://" + file.path
            } catch (e: Exception) { null }
        }
        return rest to out
    }

    /** Saves up to [MAX_PAGES] pages of a PDF as pictures; empty if it can't be drawn. */
    fun pdfPages(ctx: Context, pdf: ByteArray): List<File> = try {
        val dir = File(ctx.cacheDir, "pages/" + UUID.randomUUID()).apply { mkdirs() }
        val src = File(dir, "doc.pdf").apply { writeBytes(pdf) }
        ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                (0 until minOf(r.pageCount, MAX_PAGES)).map { i ->
                    r.openPage(i).use { p ->
                        val bmp = Bitmap.createBitmap(WIDTH, (WIDTH.toDouble() * p.height / p.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(dir, "p${i + 1}.jpg").apply { outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                    }
                }
            }
        }.also { src.delete() }
    } catch (e: Exception) { emptyList() }

    /** The picture as a JPEG no bigger than [MAX_SIDE] pixels a side, or null if it can't be read. */
    fun shrink(bytes: ByteArray): ByteArray? = try {
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, size)
        var sample = 1
        while (maxOf(size.outWidth, size.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.let { b ->
            val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(b.width, b.height))
            val fit = if (scale < 1.0) Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true) else b
            java.io.ByteArrayOutputStream().also { fit.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        }
    } catch (e: Exception) { null }

    /** Saves a picture file as a single "page". */
    fun imagePage(ctx: Context, bytes: ByteArray, ext: String): List<File> {
        val dir = File(ctx.cacheDir, "pages/" + UUID.randomUUID()).apply { mkdirs() }
        return listOf(File(dir, "p1.$ext").apply { writeBytes(bytes) })
    }

    /** How many pages a PDF has, or 0 if it can't be opened. */
    fun pageCount(ctx: Context, pdf: ByteArray): Int = try {
        val f = File(ctx.cacheDir, "count-" + UUID.randomUUID() + ".pdf").apply { writeBytes(pdf) }
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }.also { f.delete() }
    } catch (e: Exception) { 0 }
}
