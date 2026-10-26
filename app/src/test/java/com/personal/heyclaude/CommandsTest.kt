package com.personal.heyclaude

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandsTest {
    private fun c(s: String) = Command.parse(s)

    @Test fun briefing() {
        for (s in listOf("Good morning", "good morning!", "brief me", "my briefing", "morning briefing", "Give me my daily briefing"))
            assertEquals(s, Command.Briefing, c(s))
    }

    @Test fun money() {
        assertEquals(Command.Money("How much did I spend on Swiggy this month?"), c("How much did I spend on Swiggy this month?"))
        assertEquals(Command.Money("what subscriptions am I paying for"), c("what subscriptions am I paying for"))
        assertEquals(Command.SpendingReport, c("spending report"))
        assertEquals(Command.SpendingReport, c("where did my money go"))
        assertEquals(Command.OddCharges, c("any odd charges?"))
        assertEquals(Command.OddCharges, c("any suspicious transactions this week"))
    }

    @Test fun stocks() {
        assertEquals(Command.StockCheck("Tata Motors"), c("Check Tata Motors"))
        assertEquals(Command.StockCheck("Tata Motors"), c("should I look at Tata Motors?"))
        assertEquals(Command.StockCheck("HCL"), c("check HCL stock"))
        assertEquals(Command.StockCheck("Infosys"), c("how is Infosys stock doing"))
        // Not stocks:
        assertEquals(Command.Ask("check the weather in Bangalore"), c("check the weather in Bangalore"))
        assertEquals(Command.Ask("check my email"), c("check my email"))
        assertEquals(Command.Ask("check if I have meetings tomorrow"), c("check if I have meetings tomorrow"))
    }

    @Test fun everythingElse() {
        assertEquals(Command.CatchUp, c("What did I miss today?"))
        assertEquals(Command.CatchUp, c("catch me up"))
        assertEquals(Command.WeeklyReview, c("weekly review"))
        assertEquals(Command.Alerts, c("any alerts?"))
        assertEquals(Command.FindDocument("When does my car insurance expire?"), c("When does my car insurance expire?"))
        assertEquals(Command.FindDocument("find my passport"), c("find my passport"))
        assertEquals(Command.Remember("I parked on level 3"), c("Remember I parked on level 3."))
        assertEquals(Command.Remember("the spare key is in the blue drawer"), c("remember that the spare key is in the blue drawer"))
        assertEquals(Command.Reminder("Remind me to call Ravi tomorrow at 6"), c("Remind me to call Ravi tomorrow at 6"))
        assertEquals(Command.Ask("Where did I park?"), c("Where did I park?"))
        assertEquals(Command.Ask("what's the weather in Bangalore"), c("what's the weather in Bangalore"))
    }
}
