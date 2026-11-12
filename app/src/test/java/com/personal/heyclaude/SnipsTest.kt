package com.personal.heyclaude

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Snippets: the part of a document page that holds the answer, shown in the chat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SnipsTest {
    @Test fun snipLinesAreReadAndRemoved() {
        val (rest, snips) = Snips.parse("Your car policy ends on 5 Aug 2028.\n• Insurer: Digit\nSNIP: d3 | page 2 | 0.30-0.38\n")
        assertEquals("Your car policy ends on 5 Aug 2028.\n• Insurer: Digit", rest)
        assertEquals(listOf(Snips.Snip("d3", 2, 0.30, 0.38)), snips)
        assertEquals(1, Snips.parse("**SNIP:** d1 | Page 1 | 0.1 – 0.2").second.size)
        assertTrue(Snips.parse("No snippet here.").second.isEmpty())
    }

    @Test fun bandIsPaddedAndStaysOnThePage() {
        val (t, b) = Snips.band(0.40, 0.42)
        assertTrue(t <= 0.37 && b >= 0.45 && b - t >= 0.099)
        assertEquals(0.0, Snips.band(0.0, 0.02).first, 1e-9)
        assertEquals(1.0, Snips.band(0.97, 1.0).second, 1e-9)
        val (t2, b2) = Snips.band(0.6, 0.2) // swapped
        assertTrue(t2 < 0.2 && b2 > 0.6)
    }

    @Test fun confidentialFilesAreSpotted() {
        assertTrue(Snips.isPrivate("Insurance 6th Aug2025 to 2028 Aug (Digit car policy).pdf"))
        assertTrue(Snips.isPrivate("PAN card.jpg"))
        assertTrue(Snips.isPrivate("HDFC bank statement Oct.pdf"))
        assertFalse(Snips.isPrivate("Goa trip plan.pdf"))
    }

    @Test fun cutsTheAskedPartOfThePage() {
        val app = RuntimeEnvironment.getApplication()
        val page = File(app.cacheDir, "page-test.png")
        val bmp = Bitmap.createBitmap(200, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        page.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val id = Snips.register(listOf(page))
        val (rest, links) = Snips.apply(app, "Expires 5 Aug 2028.\nSNIP: $id | page 1 | 0.50-0.60\nSNIP: $id | page 9 | 0.1-0.2")
        assertEquals("Expires 5 Aug 2028.", rest)
        assertEquals(1, links.size) // page 9 doesn't exist
        val cut = BitmapFactory.decodeFile(links[0].removePrefix("file://"))
        assertEquals(200, cut.width)
        assertNear(160, cut.height, 2) // 0.47 to 0.63 of 1000
        assertTrue(Charts.picture(links[0]) != null)

        Snips.drop(id)
        assertTrue(Snips.apply(app, "SNIP: $id | page 1 | 0.5-0.6").second.isEmpty())
    }

    private fun assertNear(expected: Int, actual: Int, delta: Int) = assertTrue("$actual", kotlin.math.abs(expected - actual) <= delta)
}
