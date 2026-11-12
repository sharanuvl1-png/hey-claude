package com.personal.heyclaude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** App screens: only the picked apps, no OTPs, numbers masked, kept on the phone and searchable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenAppsTest {
    private val day = LocalDate.of(2026, 11, 12)

    @Test fun screenTextIsCleanedBeforeSaving() {
        val text = ScreenApps.clean(listOf("Holdings", "TATAMOTORS  10 qty", "Avg 712.40", "Your OTP is 482913", "A/c 1234567890123456", "PAN ABCDE1234F", "Holdings"))
        assertEquals("Holdings · TATAMOTORS 10 qty · Avg 712.40 · A/c ••••3456 · PAN ••••", text)
        assertFalse(text.contains("482913"))
    }

    @Test fun onlyPickedAppsAndNoRepeats() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(ScreenApps.isChosen(app, "com.zerodha.kite3"))
        ScreenApps.choose(app, setOf("com.zerodha.kite3"))
        assertTrue(ScreenApps.isChosen(app, "com.zerodha.kite3"))
        assertFalse(ScreenApps.isChosen(app, "com.grofers.customerapp"))

        val screen = listOf("Holdings", "TATAMOTORS 10 qty", "Avg 712.40", "LTP 735.10")
        ScreenApps.save(app, "Kite", screen, day, LocalTime.of(10, 5))
        ScreenApps.save(app, "Kite", screen, day, LocalTime.of(10, 6)) // same screen: skipped
        ScreenApps.save(app, "Kite", listOf("ok"), day, LocalTime.of(10, 7)) // too little text: skipped
        val file = File(ScreenApps.dir(app), "$day.txt")
        assertEquals(listOf("10:05 | Kite | Holdings · TATAMOTORS 10 qty · Avg 712.40 · LTP 735.10"), file.readLines())

        // Old screens are deleted after 30 days.
        File(ScreenApps.dir(app), "${day.minusDays(40)}.txt").writeText("old\n")
        ScreenApps.save(app, "Kite", listOf("Positions", "No open positions today"), day, LocalTime.of(11, 0))
        assertFalse(File(ScreenApps.dir(app), "${day.minusDays(40)}.txt").exists())

        val found = PhoneTools(app).searchPhoneData("tatamotors holdings")
        assertTrue(found, found.contains("Screens saved from Sharan's chosen apps") && found.contains("Avg 712.40"))

        ScreenApps.clear(app)
        assertFalse(ScreenApps.dir(app).exists())
        assertTrue(ScreenApps.isChosen(app, "com.zerodha.kite3"))
    }
}
