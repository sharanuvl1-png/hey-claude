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

    @Test fun readsDropboxPagesAndOffersSnippetsOnlyWhenAllowed() {
        val tools = object : MehithaTools by FakeTools() {
            override fun readDropboxFile(path: String) = FileForClaude("Trip plan.pdf has 1 page, attached as pictures.",
                path.substringAfterLast('/'), pages = listOf(byteArrayOf(1, 2, 3)), snipId = Snips.register(emptyList()))
        }
        fun run(path: String, unlocked: Boolean): String {
            val api = FakeApi.ok(
                msg("tool_use", """{"type":"tool_use","id":"tu_1","name":"read_dropbox_file","input":{"path":"$path"}}"""),
                text("Your trip starts on 5 December."),
            )
            val brain = AssistantBrain("test-key", tools, api.url, clock).also { it.unlocked = unlocked }
            assertEquals("Your trip starts on 5 December.", brain.ask("When does my trip start?"))
            api.stop()
            assertTrue(api.requests[0].contains("read_dropbox_file") && api.requests[0].contains("SNIP"))
            return api.requests[1]
        }
        val plain = run("/Travel/Trip plan.pdf", unlocked = false)
        assertTrue(plain, plain.contains("\"tool_use_id\":\"tu_1\"") && plain.contains("Page 1:"))
        assertTrue(plain, plain.contains("\"type\":\"image\"") && plain.contains("AQID"))
        assertTrue(plain, plain.contains("SNIP: d"))
        val policy = run("/Insurance/Car policy.pdf", unlocked = false)
        assertFalse(policy, policy.contains("SNIP: d"))
        assertTrue(policy, policy.contains("Don't add a SNIP line"))
        assertTrue(run("/Insurance/Car policy.pdf", unlocked = true).contains("SNIP: d"))
    }

    @Test fun aFileClaudeCannotTakeIsSentAgainAsWordsOnly() {
        val tools = object : MehithaTools by FakeTools() {
            override fun readDropboxFile(path: String) = FileForClaude("Car policy.pdf has 1 page, attached as pictures.",
                "Car policy.pdf", pages = listOf(byteArrayOf(1, 2, 3)))
        }
        val api = FakeApi(listOf(
            200 to msg("tool_use", """{"type":"tool_use","id":"tu_1","name":"read_dropbox_file","input":{"path":"/Insurance/Car policy.pdf"}}"""),
            400 to BAD_FILE,
            200 to text("I couldn't open the policy. Open it from Dropbox."),
        ))
        val brain = AssistantBrain("k", tools, api.url, clock)
        assertEquals("I couldn't open the policy. Open it from Dropbox.", brain.ask("When is my car insurance due?"))
        api.stop()
        assertTrue(api.requests[1].contains("\"type\":\"image\""))
        assertFalse(api.requests[2], api.requests[2].contains("\"type\":\"image\""))
        assertTrue(api.requests[2], api.requests[2].contains("password-protected") && api.requests[2].contains("\"tool_use_id\":\"tu_1\""))
    }

    @Test fun aFailedQuestionDoesNotBreakTheNextOne() {
        val api = FakeApi(listOf(400 to BAD_FILE, 200 to text("Your bike insurance is in Dropbox.")))
        val brain = AssistantBrain("k", FakeTools(), api.url, clock)
        assertTrue(runCatching { brain.ask("car renewal") }.isFailure)
        assertEquals("Your bike insurance is in Dropbox.", brain.ask("bike insurance"))
        api.stop()
        assertFalse(api.requests[1], api.requests[1].contains("car renewal"))
    }

    @Test fun searchesGmailOnlyWhenLinked() {
        val linked = object : MehithaTools by FakeTools() {
            override val hasGmail = true
            override fun searchGmail(query: String) = "Gmail messages for \"$query\" (1): id 1a | ICICI statement"
        }
        val api = FakeApi.ok(
            msg("tool_use", """{"type":"tool_use","id":"tu_1","name":"search_gmail","input":{"query":"credit card statement newer_than:2m"}}"""),
            text("Your ICICI card is due on 30 Nov."),
        )
        assertEquals("Your ICICI card is due on 30 Nov.", AssistantBrain("k", linked, api.url, clock).ask("my card due dates"))
        api.stop()
        assertTrue(api.requests[0].contains("\"search_gmail\"") && api.requests[0].contains("cover every card"))
        assertTrue(api.requests[1].contains("ICICI statement"))
        val off = FakeApi.ok(text("ok"))
        AssistantBrain("k", FakeTools(), off.url, clock).ask("hi")
        off.stop()
        assertFalse(off.requests[0].contains("\"search_gmail\""))
    }

    @Test fun readsTheCalendarForAnotherDay() {
        var asked = ""
        val tools = object : MehithaTools by FakeTools() {
            override fun calendar(date: String, days: Int): String { asked = "$date/$days"; return "Fri 27 Oct 2026: 10 AM to 11 AM Standup." }
        }
        val api = FakeApi.ok(
            msg("tool_use", """{"type":"tool_use","id":"tu_1","name":"get_calendar","input":{"date":"2026-10-27","days":1}}"""),
            text("Tomorrow you have Standup at 10 AM."),
        )
        assertEquals("Tomorrow you have Standup at 10 AM.", AssistantBrain("k", tools, api.url, clock).ask("how does my calendar look tomorrow"))
        api.stop()
        assertEquals("2026-10-27/1", asked)
        val day = java.time.LocalDate.of(2026, 11, 13)
        assertEquals("Fri 13 Nov 2026: 10 AM to 10:30 AM Standup (Room 4); all day Holiday.", Today.dayText(day, listOf(
            Meeting(1, "Standup", day.atTime(10, 0), day.atTime(10, 30), false, "Room 4"),
            Meeting(2, "Holiday", day.atStartOfDay(), day.plusDays(1).atStartOfDay(), true, ""))))
        assertEquals("Fri 13 Nov 2026: no meetings.", Today.dayText(day, emptyList()))
        assertTrue(api.requests[0].contains("get_calendar") && api.requests[1].contains("Standup"))
    }

    @Test fun fastModelUsesBasicWebSearchAndCanFetchCharts() {
        val api = FakeApi.ok(
            msg("tool_use", """{"type":"tool_use","id":"tu_1","name":"get_stock_chart","input":{"stock":"PC Jeweller"}}"""),
            text("Here it is."),
        )
        val tools = FakeTools()
        AssistantBrain("k", tools, api.url, clock, model = AssistantBrain.DEFAULT_MODEL).ask("show PC Jeweller chart")
        api.stop()
        val first = api.requests[0]
        assertTrue(first.contains("\"model\":\"claude-haiku-5-5\""))
        assertTrue(first.contains("web_search_20250305") && !first.contains("web_search_20260209"))
        assertTrue(first.contains("get_stock_chart"))
        assertEquals(listOf("chart:PC Jeweller"), tools.calls)
        assertTrue(api.requests[1].contains("PCJEWELLER/2026-11-10.png"))
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

    private companion object {
        const val BAD_FILE = """{"type":"error","error":{"type":"invalid_request_error","message":"The PDF specified could not be processed"}}"""
    }
}
