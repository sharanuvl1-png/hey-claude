package com.personal.heyclaude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

/** Expense sheet, WhatsApp and Gmail messages, Zerodha and the passcode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoreFeaturesTest {
    private val day = LocalDate.of(2026, 11, 11)

    @Test fun expenseRowsAndCategories() {
        assertEquals("Food", ExpenseSheet.category("lunch"))
        assertEquals("Fuel", ExpenseSheet.category("petrol"))
        assertEquals("Other", ExpenseSheet.category("haircut"))
        val s = Spend(LocalDateTime.of(2026, 11, 11, 13, 5), 250.0, "Food", "Swiggy", "UPI")
        assertEquals(listOf("2026-11-11", 250.0, "Food", "Swiggy", "UPI", "from SMS · Mehitha"), ExpenseSheet.row(s))
        assertTrue(ExpenseSheet.isLinkValid("https://script.google.com/macros/s/AKfycbx-12_ab/exec"))
        assertFalse(ExpenseSheet.isLinkValid("https://docs.google.com/spreadsheets/d/1QT/edit"))
    }

    @Test fun onlyNewSpendsAreAdded() {
        val old = Spend(LocalDateTime.of(2026, 11, 10, 9, 0), 100.0, "Food", "Cafe", "UPI")
        val new = Spend(LocalDateTime.of(2026, 11, 11, 9, 0), 200.0, "Food", "Cafe", "UPI")
        val done = Spend(LocalDateTime.of(2026, 11, 11, 10, 0), 300.0, "Food", "Cafe", "UPI")
        val since = LocalDateTime.of(2026, 11, 11, 0, 0).toInstant(java.time.ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()
        assertEquals(listOf(new), ExpenseSheet.pending(listOf(old, new, done), since, setOf(ExpenseSheet.key(done))))
    }

    @Test fun whatsAppBankAlertsCountButFriendsDoNot() {
        val chats = Chats.parse(listOf(
            "13:05 | WhatsApp | HDFC Bank | Rs.500.00 debited from a/c **1234 on 11-11-26 to VPA swiggy@icici (UPI Ref 123)",
            "13:06 | WhatsApp | Ravi | I paid Rs 500 for dinner, send your share",
            "13:07 | Outlook | Accounts Team | Rs 9,000 debited for travel",
        ), day)
        val spends = Chats.spends(chats)
        assertEquals(1, spends.size)
        assertEquals(500.0, spends[0].amount, 0.01)
        // The same payment by SMS a few minutes earlier is counted once.
        val sms = Spend(LocalDateTime.of(2026, 11, 11, 13, 1), 500.0, "Food", "swiggy", "UPI")
        assertEquals(1, Chats.merge(listOf(sms), spends).size)
    }

    @Test fun billsFromWhatsAppAndGmailButNotOfficeMail() {
        val chats = Chats.parse(listOf(
            "09:00 | WhatsApp | BESCOM | Your electricity bill of Rs 1,240 is due on 15/11/2026.",
            "09:10 | Gmail | HDFC Life | Your policy premium of Rs 24,500 is due for renewal on 20-Nov-26",
            "09:20 | Outlook | Finance | Invoice of Rs 50,000 is due on 18/11/2026",
        ), day)
        val bills = Chats.bills(chats)
        assertEquals(listOf("BESCOM", "HDFC Life"), bills.map { it.sender })
        assertEquals(LocalDate.of(2026, 11, 20), bills[1].due)
    }

    @Test fun zerodhaPortfolio() {
        val json = """{"status":"success","data":[
            {"tradingsymbol":"TCS","quantity":10,"t1_quantity":0,"average_price":3000,"last_price":3300,"day_change_percentage":1.5},
            {"tradingsymbol":"PCJEWELLER","quantity":100,"t1_quantity":0,"average_price":20,"last_price":15,"day_change_percentage":-4.0},
            {"tradingsymbol":"SOLD","quantity":0,"t1_quantity":0,"average_price":10,"last_price":12,"day_change_percentage":0}]}"""
        val h = Zerodha.parseHoldings(json)
        assertEquals(listOf("TCS", "PCJEWELLER"), h.map { it.symbol })
        val s = Zerodha.summary(h)
        assertTrue(s, s.startsWith("Your 2 holdings are worth 34,500 rupees"))
        assertTrue(s, s.contains("Overall you're up 2,500 rupees (8.1%) on 32,000 rupees invested"))
        assertTrue(s, s.contains("PCJEWELLER down 4.0%"))
        val alerts = Zerodha.stopLossAlerts(h, mapOf("PCJEWELLER" to 16.0, "TCS" to 3000.0), "2026-11-11")
        assertEquals(listOf("PCJEWELLER is below its stop-loss"), alerts.map { it.title })
        assertTrue(alerts[0].text.endsWith("The decision is yours."))
    }

    @Test fun zerodhaLoginPieces() {
        assertEquals("abc123", Zerodha.requestToken("https://127.0.0.1/mehitha?action=login&status=success&request_token=abc123"))
        assertNull(Zerodha.requestToken("https://kite.zerodha.com/connect/login?v=3&api_key=k"))
        assertEquals(64, Zerodha.checksum("k", "t", "s").length)
        assertEquals(LocalDateTime.of(2026, 11, 12, 6, 0), Zerodha.expiry(LocalDateTime.of(2026, 11, 11, 9, 0)))
        assertEquals(LocalDateTime.of(2026, 11, 11, 6, 0), Zerodha.expiry(LocalDateTime.of(2026, 11, 11, 2, 0)))
    }

    @Test fun passcodeIsCheckedNotStored() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(Passcode.isSet(app))
        Passcode.set(app, "1357 9")
        assertTrue(Passcode.check(app, "13579"))
        assertTrue(Passcode.check(app, "135 79"))
        assertFalse(Passcode.check(app, "13578"))
        val stored = app.getSharedPreferences("passcode", 0).all.values.joinToString()
        assertFalse(stored.contains("13579"))
    }
}
