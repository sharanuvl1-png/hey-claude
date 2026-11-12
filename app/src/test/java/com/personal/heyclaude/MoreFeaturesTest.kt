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

/** WhatsApp and Gmail messages, shopping, order status and the passcode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoreFeaturesTest {
    private val day = LocalDate.of(2026, 11, 11)

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

    @Test fun shoppingListAndSearchLinks() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(listOf("milk", "bread", "2 dozen eggs"), Shopping.items("milk, bread and 2 dozen eggs"))
        Shopping.add(app, listOf("milk", "bread"))
        assertEquals(listOf("milk", "bread", "eggs"), Shopping.add(app, listOf("Milk", "eggs")))
        assertEquals("Your shopping list has 3 items: milk, bread and eggs.", Shopping.listAnswer(app))
        Shopping.clear(app)
        assertEquals("Your shopping list is empty.", Shopping.listAnswer(app))
        assertEquals("https://blinkit.com/s/?q=amul%20milk", Shopping.searchUrl("Blinkit", "amul milk"))
        assertEquals("https://www.amazon.in/s?k=boat%20earbuds", Shopping.searchUrl("amazon", "boat earbuds"))
    }

    @Test fun orderStatusFromMessages() {
        val t = LocalDateTime.of(2026, 11, 11, 9, 40)
        assertEquals("out for delivery", Orders.update("messages", "AX-AMAZON", "Your package with boAt earbuds is out for delivery today", t)!!.status)
        assertEquals("shipped", Orders.update("Flipkart", "Flipkart", "Your order for Realme buds has been shipped", t)!!.status)
        assertEquals("delivered", Orders.update("blinkit", "blinkit", "Your order has been delivered. Enjoy!", t)!!.status)
        assertNull(Orders.update("messages", "AX-AMAZON", "Big sale! 50% off on earbuds, shop now", t))
        assertNull(Orders.update("Outlook", "Amazon", "Your order has been shipped", t))
        val ups = listOf(
            OrderUpdate("Amazon", "shipped", t.minusDays(1), ""),
            OrderUpdate("Amazon", "out for delivery", t, ""),
            OrderUpdate("Flipkart", "placed", t.minusDays(3), ""),
        )
        assertEquals("Amazon: out for delivery, last update at 9:40 AM today. Flipkart: placed, last update on 8 Nov.",
            Orders.answer(ups, day))
        assertEquals("I don't see any Blinkit order updates from the last 10 days.", Orders.answer(ups, day, "blinkit"))
    }

    @Test fun chatsSaveAndLoad() {
        val app = RuntimeEnvironment.getApplication()
        val entries = listOf(
            ChatEntry(true, "add milk on blinkit"),
            ChatEntry(false, "Opening Blinkit.", "Opening Blinkit.", links = listOf("milk" to "https://blinkit.com/s/?q=milk")),
            ChatEntry(true, "show chart"),
            ChatEntry(false, "Chart for TCS.", images = listOf("https://x/y.png")),
        )
        assertEquals(entries, ChatStore.fromJson(ChatStore.toJson(entries)))
        ChatStore.save(app, SavedChat(5L, entries))
        assertEquals("add milk on blinkit", ChatStore.all(app).single().title)
        assertEquals(listOf(true to "q", false to "a"),
            ChatStore.turns(listOf(ChatEntry(true, "q"), ChatEntry(false, "a"), ChatEntry(false, "oops", problem = true), ChatEntry(true, "••••••"))))
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

    @Test fun alertsAreAShortReadableList() {
        val items = listOf(
            "Good morning: 1 meeting. Tap for your briefing: meetings, messages, bills and stocks.",
            "Renewal due on 17 Nov: Paytm Insurance Broking renewal is due on 17 Nov.",
            "Today's stock scan: PCJEWELLER buy on dip, entry ₹13.7–15.5, stop ₹12.3, target ₹18.0. Nifty 50: Adani Ports buy on dip.",
            "Bill due tomorrow: Airtel bill of Rs. 799 is due tomorrow. Pay in the app.",
        ) + (1..6).map { "Breakout $it: Stock $it crossed its 20-day high." }
        val text = Alerts.listText(items)
        val lines = text.lines()
        assertEquals("9 alerts in the last two days:", lines[0])
        assertEquals("• Renewal due on 17 Nov: Paytm Insurance Broking renewal is due on 17 Nov", lines[1])
        assertEquals("• Today's stock scan: PCJEWELLER buy on dip, entry ₹13.7–15.5, stop ₹12.3, target ₹18.0", lines[2])
        assertEquals("• Bill due tomorrow: Airtel bill of Rs. 799 is due tomorrow", lines[3])
        assertEquals("…and 3 more.", lines.last())
        assertEquals(8, lines.size)
        assertTrue(text, !text.contains("Good morning"))
        assertEquals("No new alerts in the last two days.", Alerts.listText(listOf("Good morning: 2 meetings.")))
    }
}
