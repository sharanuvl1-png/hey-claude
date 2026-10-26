package com.personal.heyclaude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PromptsTest {
    private val t = LocalDateTime.parse("2026-10-26T08:15")
    private val scan = """
        # Mehitha daily stock scan, 2026-10-26

        ## Watchlist

        | Group | Stock | Close | Verdict | Grade | Entry | Stop-loss (risk) | Target 1 | Chart |
        |---|---|---|---|---|---|---|---|---|
        | Your picks | PCJEWELLER.NS | ₹16.5 | 🟡 BUY ON DIP | B | ₹14.0–15.4 | ₹12.2 (17%) | ₹18.4 | PCJEWELLER/2026-10-26.png |
        | Nifty 50 | HCL Technologies Limited | ₹1417.0 | 🟢 BUY NOW | A | ₹1385.9–1406.1 | ₹1162.7 (17%) | ₹1659.4 | HCLTECH/2026-10-26.png |
        | Nifty 50 | Eicher Motors Limited | ₹7099.5 | 🔴 AVOID | C | ₹6900.7–7099.5 | ₹6725.7 (4%) | ₹7393.7 | EICHERMOT/2026-10-26.png |

        ## Top 5 buy setups today: Nifty 50
    """.trimIndent()

    @Test fun promptsAreOneLineWithDateAndRules() {
        val p = Prompts.briefing(t, "ICICIB 12,401 rupees due 28 Oct", Prompts.scanSummary(scan))
        assertFalse(p.contains("\n"))
        assertTrue(p.contains("Monday 26 October 2026, 8:15 AM"))
        assertTrue(p.contains("Outlook calendar") && p.contains("Slack") && p.contains("Gmail"))
        assertTrue(p.contains("ICICIB 12,401 rupees due 28 Oct"))
        assertTrue(p.contains(Prompts.RULES))
        assertTrue(Prompts.money(t, "How much on Swiggy", "facts").startsWith("How much on Swiggy."))
    }

    @Test fun scanSummaryPutsBuysFirst() {
        val s = Prompts.scanSummary(scan)
        assertTrue(s, s.startsWith("scan of 2026-10-26: HCL Technologies Limited 1417.0 rupees BUY NOW, entry 1385.9–1406.1, stop 1162.7 (17%), target 1659.4"))
        assertTrue(s.indexOf("PCJEWELLER") < s.indexOf("Eicher"))
        assertEquals("not available", Prompts.scanSummary("nothing"))
    }

    @Test fun scanForFindsByNameOrSymbol() {
        assertTrue(Prompts.scanFor(scan, "HCL Tech").startsWith("HCL Technologies Limited (HCLTECH) closed 1417.0 rupees, verdict BUY NOW"))
        assertTrue(Prompts.scanFor(scan, "pc jeweller").let { it == "not in my watchlist" || it.contains("PCJEWELLER") })
        assertTrue(Prompts.scanFor(scan, "PCJEWELLER").contains("verdict BUY ON DIP"))
        assertEquals("not in my watchlist", Prompts.scanFor(scan, "Tata Motors"))
    }

    @Test fun recallAnswersFromNotes() {
        val notes = listOf(Note(LocalDateTime.parse("2026-10-26T16:10"), "I parked on level 3"),
            Note(LocalDateTime.parse("2026-10-20T09:00"), "the spare key is in the blue drawer"))
        assertEquals("On 26 Oct, 4:10 PM you told me: I parked on level 3.", Prompts.recall(notes, "Where did I park?"))
        assertEquals("On 20 Oct, 9:00 AM you told me: the spare key is in the blue drawer.", Prompts.recall(notes, "where is the spare key"))
        assertNull(Prompts.recall(notes, "what's the weather in Bangalore"))
    }

    @Test fun remindLinesBecomeReminders() {
        val reply = "SPOKEN: Done, I'll remind you.\nREMIND: 2026-10-27 18:00 | Call Ravi\nI've set a reminder for tomorrow at 6 PM."
        val (list, rest) = Prompts.reminders(reply)
        assertEquals(listOf(ReminderNote(LocalDateTime.parse("2026-10-27T18:00"), "Call Ravi")), list)
        assertEquals("SPOKEN: Done, I'll remind you.\nI've set a reminder for tomorrow at 6 PM.", rest)
        val (list2, _) = Prompts.reminders("**REMIND:** 2027-03-14 | Car insurance expires on 14 April 2027")
        assertEquals(LocalDateTime.parse("2027-03-14T09:00"), list2.single().at)
    }
}
