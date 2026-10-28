package com.personal.heyclaude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** Spending answers from bank SMS, without the Claude app. */
class MoneyTest {
    private val today = LocalDate.of(2026, 10, 28) // a Wednesday
    private fun at(day: Int, month: Int = 10) = LocalDateTime.of(2026, month, day, 13, 0)
    private val spends = listOf(
        Spend(at(28), 450.0, "Food", "Swiggy", "UPI"),
        Spend(at(28), 300.0, "Travel", "Uber", "UPI"),
        Spend(at(27), 1200.0, "Groceries", "Blinkit", "Card"),
        Spend(at(26), 2000.0, "Shopping", "Amazon", "Credit card"),
        Spend(at(20), 650.0, "Food", "Zomato", "UPI"),
        Spend(at(15, 9), 999.0, "Entertainment", "Netflix", "Card"),
    )

    @Test fun recognisesSpendingQuestions() {
        assertTrue(Money.isSpendQuestion("how much did I spend today"))
        assertTrue(Money.isSpendQuestion("what are my expenses this month"))
        assertTrue(Money.isSpendQuestion("how much have I spent on Swiggy"))
        assertFalse(Money.isSpendQuestion("give me the meeting I have tomorrow"))
    }

    @Test fun todayListsEachPayment() {
        assertEquals(
            "Today, 28 Oct, you spent 750 rupees in 2 payments: Swiggy 450 rupees, Uber 300 rupees. " +
                "This counts only card, UPI and bank payments that sent you a text message.",
            Money.answer(spends, today, "how much did I spend today"),
        )
    }

    @Test fun plainQuestionMeansToday() {
        assertTrue(Money.answer(spends, today, "how much have I spent").startsWith("Today, 28 Oct, you spent 750 rupees"))
    }

    @Test fun yesterdayWeekAndMonth() {
        assertTrue(Money.answer(spends, today, "how much did I spend yesterday").startsWith("Yesterday, 27 Oct, you spent 1,200 rupees in 1 payment: Blinkit"))
        // Week starts Monday 26 Oct.
        assertTrue(Money.answer(spends, today, "my spending this week").startsWith("This week, you spent 3,950 rupees in 4 payments"))
        val month = Money.answer(spends, today, "how much did I spend this month")
        assertTrue(month, month.startsWith("This month, you spent 4,600 rupees in 5 payments: mostly Shopping 2,000 rupees, Groceries 1,200 rupees, Food 1,100 rupees."))
        assertTrue(Money.answer(spends, today, "what did I spend last month").startsWith("In September, you spent 999 rupees in 1 payment: Netflix"))
    }

    @Test fun filtersByShopOrCategory() {
        assertTrue(Money.answer(spends, today, "how much did I spend on swiggy this month").startsWith("This month, you spent 450 rupees on swiggy in 1 payment"))
        assertTrue(Money.answer(spends, today, "how much did I spend on food this month").startsWith("This month, you spent 1,100 rupees on food in 2 payments"))
    }

    @Test fun saysSoWhenNothingIsFound() {
        assertEquals("Today, 28 Oct, I found no spends in your bank and UPI text messages.", Money.answer(emptyList(), today, "how much did I spend"))
        assertEquals("Today, 28 Oct, I found no spends on zomato in your bank and UPI text messages.", Money.answer(spends, today, "how much did I spend on zomato today"))
    }

    @Test fun readsRowsMadeFromSms() {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("2026-10-28 09:15")!!
        val row = PhoneSync.expenseRow(date, "AX-HDFCBK", "Rs.450.00 debited from a/c XX1234 on 28-10-26 to VPA swiggy@icici (UPI Ref 1234). Not you? Call 1800")
        val spend = Money.parseRow(row!!)!!
        assertEquals(450.0, spend.amount, 0.001)
        assertEquals(LocalDateTime.of(2026, 10, 28, 9, 15), spend.time)
        assertEquals("UPI", spend.via)
    }
}
