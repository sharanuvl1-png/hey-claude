package com.personal.heyclaude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

class MoneyTest {
    private val today = LocalDate.of(2026, 10, 26)
    private fun s(day: String, amt: Double, cat: String, shop: String, via: String = "UPI") =
        Spend(LocalDateTime.parse(day), amt, cat, shop, via)

    private val spends = listOf(
        s("2026-08-05T10:00", 199.0, "Entertainment", "Netflix"),
        s("2026-09-05T10:00", 199.0, "Entertainment", "Netflix"),
        s("2026-09-10T13:00", 450.0, "Food", "Swiggy"),
        s("2026-09-20T13:00", 300.0, "Food", "Swiggy"),
        s("2026-10-05T10:00", 199.0, "Entertainment", "Netflix"),
        s("2026-10-08T20:00", 520.0, "Food", "Swiggy"),
        s("2026-10-12T20:00", 1480.0, "Food", "Zomato"),
        s("2026-10-20T20:00", 900.0, "Food", "Swiggy"),
        s("2026-10-21T09:00", 2100.0, "Groceries", "Bigbasket"),
    )

    @Test fun readsTheRowsPhoneSyncWrites() {
        val row = PhoneSync.expenseRow(Date.from(LocalDateTime.parse("2026-10-25T13:05").atZone(ZoneId.systemDefault()).toInstant()),
            "VM-HDFCBK", "Sent Rs.250.00 From HDFC Bank A/C *1234 To SWIGGY On 25/10/26 Ref 123456789")!!
        val spend = Money.parseRow(row)!!
        assertEquals(250.0, spend.amount, 0.001)
        assertEquals("Food", spend.category)
        assertEquals("SWIGGY", spend.merchant)
        assertEquals(LocalDateTime.parse("2026-10-25T13:05"), spend.time)
    }

    @Test fun summaryHasTotalsAndMatchingSpends() {
        val sum = Money.summary(spends, today, "How much did I spend on Swiggy this month?")
        assertTrue(sum, sum.contains("October 2026 so far: 5,199 rupees in 5 spends"))
        assertTrue(sum, sum.contains("Food 2,900 rupees (3)"))
        assertTrue(sum, sum.contains("Spends matching swiggy: 20 Oct Swiggy 900 rupees"))
        assertTrue(sum, sum.contains("20 Oct Swiggy 900 rupees"))
        assertTrue(sum, sum.contains("September 2026: 949 rupees"))
    }

    @Test fun summaryStartsWithTodayAndTheWeek() {
        val withToday = spends + s("2026-10-26T09:30", 120.0, "Food", "Chai Point") + s("2026-10-26T13:00", 350.0, "Food", "Swiggy")
        val sum = Money.summary(withToday, today, "how much did I spend today")
        assertTrue(sum, sum.startsWith("My spending from bank and UPI text messages. Today (26 Oct): 470 rupees in 2 spends: Swiggy 350 rupees; Chai Point 120 rupees."))
        assertTrue(sum, sum.contains("Last 7 days: 3,470 rupees in 4 spends."))
        assertTrue(Money.summary(spends, today, "today").contains("Today (26 Oct): 0 rupees in 0 spends."))
    }

    @Test fun findsSubscriptions() {
        assertEquals(listOf("Netflix about 199 rupees a month"), Money.subscriptions(spends, today))
    }

    @Test fun flagsDoubleAndLargeCharges() {
        val now = LocalDateTime.parse("2026-10-26T21:00")
        val list = spends + s("2026-10-26T13:00", 649.0, "Food", "Zomato") + s("2026-10-26T13:04", 649.0, "Food", "Zomato") +
            s("2026-10-26T15:00", 48000.0, "Shopping", "Croma", "Credit card")
        val alerts = Money.oddCharges(list, now)
        assertEquals(listOf("Possible double charge", "Large debit"), alerts.map { it.title })
        assertTrue(alerts[0].text.contains("649 rupees to Zomato twice within 4 minutes"))
        assertTrue(alerts[1].text.contains("48,000 rupees to Croma by Credit card"))
        assertEquals(alerts, Money.oddCharges(list, now)) // same ids each run, so said only once
        assertTrue(Money.oddCharges(spends, now).isEmpty())
    }

    @Test fun flagsUnusualCategorySpend() {
        val alerts = Money.unusualSpending(spends, today)
        assertEquals(1, alerts.size)
        assertEquals("Spending up on Food", alerts[0].title)
        assertTrue(alerts[0].text, alerts[0].text.contains("2,900 rupees on Food this month, 287% more"))
    }

    @Test fun readsBillDueDates() {
        val rcv = LocalDate.of(2026, 10, 26)
        val b = Money.billDue("AX-ICICIB", "Your ICICI Bank Credit Card XX4321 statement: Total Amt Due Rs 12,400.50, Min Amt Due Rs 620. Due Date 28-Oct-26.", rcv)!!
        assertEquals(LocalDate.of(2026, 10, 28), b.due)
        assertEquals(12400.50, b.amount!!, 0.001)
        assertEquals("ICICIB", b.sender)
        assertEquals(LocalDate.of(2026, 11, 5), Money.billDue("JM-AIRTEL", "Your Airtel bill of Rs 799 is due on 05/11/2026. Pay now.", rcv)!!.due)
        assertNull(Money.billDue("JM-AIRTEL", "Payment of Rs 799 received. Thank you for your payment.", rcv))
        assertNull(Money.billDue("JM-AIRTEL", "Recharge now and get 2GB extra", rcv))

        val alerts = Money.billAlerts(listOf(b), rcv)
        assertEquals("Bill due on 28 Oct", alerts.single().title)
        assertEquals("ICICIB bill of 12,401 rupees is due on 28 Oct.", alerts.single().text)
        assertEquals("Bill due tomorrow", Money.billAlerts(listOf(b), rcv.plusDays(1)).single().title)
        assertTrue(Money.billAlerts(listOf(b), rcv.plusDays(3)).isEmpty())
    }
}
