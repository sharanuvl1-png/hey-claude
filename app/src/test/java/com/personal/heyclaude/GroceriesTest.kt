package com.personal.heyclaude

import com.personal.heyclaude.Groceries.Bought
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Usual groceries: saved items, what Mehitha learns you buy, and the nudge when they're due. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroceriesTest {
    private val today = LocalDate.of(2026, 11, 12)
    private fun ago(days: Long, item: String) = Bought(today.minusDays(days), item)

    @Test fun sameItemHoweverItIsSaid() {
        assertEquals("eggs", Groceries.name("2 dozen eggs"))
        assertEquals("eggs", Groceries.name("half a dozen eggs"))
        assertEquals("onions", Groceries.name("1kg onions"))
        assertEquals("paneer", Groceries.name("500 g of paneer"))
        assertEquals("apples", Groceries.name("apples"))
        assertEquals("amul milk", Groceries.name("Amul milk").lowercase())
        assertEquals(Groceries.key("egg"), Groceries.key("2 dozen Eggs"))
        assertEquals(Groceries.key("tomato"), Groceries.key("tomatoes"))
        assertEquals("glass", Groceries.key("glass"))
    }

    @Test fun learnsHowOftenAndWhatIsDue() {
        val log = listOf(
            ago(9, "milk"), ago(6, "milk"), ago(3, "2 milk"),           // every 3 days, due today
            ago(20, "eggs"), ago(13, "1 dozen eggs"), ago(6, "eggs"),  // every week, due tomorrow
            ago(30, "atta"), ago(2, "atta"),                            // every 28 days, not due
            ago(5, "bread"),                                            // once: not a usual yet
            ago(4, "curd"), ago(3, "curd"),                             // next day is the same shop
        )
        val usuals = Groceries.usuals(log, today)
        assertEquals(listOf("milk", "eggs", "atta"), usuals.map { it.name })
        assertEquals(3, usuals[0].everyDays)
        assertEquals(7, usuals[1].everyDays)
        assertEquals(listOf("milk", "eggs"), Groceries.due(log, today).map { it.name })

        val text = Groceries.answer(log, listOf("bread"), today)
        assertTrue(text, text.startsWith("Due now: milk and eggs."))
        assertTrue(text, text.contains("• milk: every 3 days, due today"))
        assertTrue(text, text.contains("• eggs: every week, due tomorrow"))
        assertTrue(text, text.contains("• atta: every 4 weeks, due in 26 days"))
        assertEquals(listOf("milk", "eggs", "bread", "atta"), Groceries.buttons(log, listOf("bread", "Milk"), today))

        val nudge = Groceries.nudge(log, today)!!
        assertEquals("Groceries due", nudge.title)
        assertTrue(nudge.text, nudge.text.startsWith("Milk is usually due."))
        assertNull(Groceries.nudge(log, today.minusDays(1)))
    }

    @Test fun savedUsualsAndPurchasesStayOnThePhone() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(Groceries.answer(emptyList(), emptyList(), today).contains("add milk, eggs and bread to my usuals"))
        assertEquals(listOf("milk", "eggs"), Groceries.save(app, listOf("milk", "2 dozen eggs", "Milk")))
        assertEquals(listOf("eggs"), Groceries.unsave(app, listOf("egg")))
        assertEquals(listOf("milk"), Groceries.saved(app))

        Groceries.record(app, listOf("milk", "bread"), today.minusDays(4))
        Groceries.record(app, listOf("milk", "Milk"), today)
        assertEquals(3, Groceries.log(app).size)
        assertEquals("milk", Groceries.usuals(Groceries.log(app), today).single().name)
        assertEquals("2 kg onions", Groceries.blinkitItem(Shopping.searchUrl("blinkit", "2 kg onions")))
        assertNull(Groceries.blinkitItem(Shopping.searchUrl("amazon", "onions")))

        Shopping.add(app, listOf("milk", "bread", "soap"))
        Shopping.remove(app, listOf("Milk", "2 breads"))
        assertEquals(listOf("soap"), Shopping.list(app))
    }

    @Test fun groceryWordsAreUnderstood() {
        assertEquals(Command.Usuals, Command.parse("my usuals"))
        assertEquals(Command.Usuals, Command.parse("show my usual groceries"))
        assertEquals(Command.Usuals, Command.parse("what groceries do I need"))
        assertEquals(Command.UsualsAdd(listOf("milk", "eggs", "bread")), Command.parse("add milk, eggs and bread to my usuals"))
        assertEquals(Command.UsualsRemove(listOf("bread")), Command.parse("remove bread from my usuals"))
        assertEquals(Command.Bought(listOf("milk", "eggs")), Command.parse("I bought milk and eggs on Blinkit"))
        assertEquals(Command.Bought(listOf("curd")), Command.parse("I got curd today"))
        assertEquals(Command.Shop(listOf("bread"), "blinkit"), Command.parse("blinkit bread"))
        assertTrue(Command.parse("blinkit order status") is Command.Orders)
        assertTrue(Command.parse("I ordered a phone on Amazon, where is it?") !is Command.Bought)
        assertTrue(Command.parse("I got charged twice") !is Command.Bought)
        assertEquals(Command.ListAdd(listOf("milk")), Command.parse("add milk to my list"))
    }
}
