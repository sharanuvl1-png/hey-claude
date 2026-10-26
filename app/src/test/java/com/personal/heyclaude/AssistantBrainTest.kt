package com.personal.heyclaude

import com.personal.heyclaude.FakeApi.Companion.msg
import com.personal.heyclaude.FakeApi.Companion.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AssistantBrainTest {
    private val clock = { ZonedDateTime.of(2026, 10, 26, 20, 0, 0, 0, ZoneId.of("Asia/Kolkata")) }

    @Test fun runsToolsThenAnswers() {
        val api = FakeApi.ok(
            msg("tool_use", """{"type":"tool_use","id":"tu_1","name":"get_stock_scan","input":{}}"""),
            msg("tool_use", """{"type":"text","text":"Checking."},{"type":"tool_use","id":"tu_2","name":"search_phone_data","input":{"query":"redbus ticket"}}"""),
            text("Your bus leaves at 6:30 PM."),
        )
        val tools = FakeTools()
        val answer = AssistantBrain("test-key", tools, api.url, clock).ask("When is my bus?")
        api.stop()
        assertEquals("Your bus leaves at 6:30 PM.", answer)
        assertEquals(listOf("scan", "phone:redbus ticket"), tools.calls)
        assertEquals(3, api.requests.size)
        val first = api.requests[0]
        assertTrue(first.contains("\"model\":\"claude-opus-5-5\""))
        assertTrue(first.contains("web_search_20260209"))
        assertTrue(first.contains("search_phone_data") && first.contains("get_stock_scan"))
        assertTrue(first.contains("\"effort\":\"low\""))
        assertTrue(first.contains("Monday 26 October 2026"))
        assertFalse(first.contains("\"thinking\""))
        assertTrue(api.requests[1].contains("\"tool_use_id\":\"tu_1\"") && api.requests[1].contains("HCLTECH BUY NOW"))
        assertTrue(api.requests[2].contains("\"tool_use_id\":\"tu_2\"") && api.requests[2].contains("TVBN78163260"))
    }

    @Test fun followUpKeepsConversation() {
        val api = FakeApi.ok(text("Hello Sharan."))
        val brain = AssistantBrain("k", FakeTools(), api.url, clock)
        brain.ask("Hi")
        brain.ask("And tomorrow?")
        api.stop()
        assertTrue(api.requests[1].contains("Hello Sharan.") && api.requests[1].contains("And tomorrow?"))
    }

    @Test fun pauseTurnIsResumed() {
        val api = FakeApi.ok(msg("pause_turn", """{"type":"text","text":""}"""), text("Done after pause."))
        assertEquals("Done after pause.", AssistantBrain("k", FakeTools(), api.url, clock).ask("q"))
        api.stop()
    }

    @Test fun refusalGivesPoliteLine() {
        val api = FakeApi.ok(msg("refusal", ""))
        assertTrue(AssistantBrain("k", FakeTools(), api.url, clock).ask("q").startsWith("Sorry"))
        api.stop()
    }

    @Test fun toolFailureIsReportedNotThrown() {
        val broken = object : MehithaTools {
            override fun searchPhoneData(query: String): String = throw IllegalStateException("Dropbox not linked")
            override fun stockScan(): String = ""
        }
        val api = FakeApi.ok(
            msg("tool_use", """{"type":"tool_use","id":"tu_9","name":"search_phone_data","input":{"query":"x"}}"""),
            text("I couldn't search your phone data."),
        )
        val a = AssistantBrain("k", broken, api.url, clock).ask("q")
        api.stop()
        assertTrue(api.requests[1].contains("\"is_error\":true") && api.requests[1].contains("Dropbox not linked"))
        assertTrue(a.contains("couldn't"))
    }
}
