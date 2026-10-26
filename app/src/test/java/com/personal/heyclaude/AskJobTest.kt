package com.personal.heyclaude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plays made-up Claude app screens through the no-key hand-off. */
class AskJobTest {
    private val spoken = "when is my bus"
    private fun job() = AskJob(spoken, 0)

    private val home = listOf(
        ScreenText("Menu", clickable = true), ScreenText("New chat", clickable = true),
        ScreenText("How can I help you this evening?"),
        ScreenText("Claude can make mistakes. Please double-check responses."),
    )
    private fun chat(box: String, vararg messages: ScreenText) = home.take(2) + messages.toList() +
        listOf(ScreenText(box, editable = true), ScreenText("Send message", clickable = true), ScreenText("Voice mode", clickable = true)) +
        home.last()

    @Test fun typesSendsAndReadsTheReply() {
        val j = job()
        val q = j.question
        assertTrue(q.startsWith("when is my bus") && q.contains("short summary"))
        // Empty chat: type into the message box (index 3 = box).
        val empty = chat("", home[2])
        assertEquals(ScreenAction.Type(3), j.step(empty, 1_000))
        // Box now holds the question: press send.
        val typed = chat(q, home[2])
        assertEquals(ScreenAction.Tap(4), j.step(typed, 1_600))
        // Sent; Claude is still writing (Stop button showing).
        val writing = chat("", ScreenText(q), ScreenText("Your bus")) + ScreenText("Stop", clickable = true)
        assertEquals(ScreenAction.Wait, j.step(writing, 2_200))
        val done = chat("", ScreenText(q), ScreenText("Your redBus leaves at 6:30 PM."), ScreenText("It's from Majestic, seat 14."),
            ScreenText("Copy", clickable = true), ScreenText("Retry", clickable = true))
        assertEquals(ScreenAction.Wait, j.step(done, 3_000)) // text just changed
        assertEquals(ScreenAction.Wait, j.step(done, 4_000))
        val result = j.step(done, 3_000 + AskJob.SETTLE_MS)
        assertEquals(ScreenAction.Done("Your redBus leaves at 6:30 PM.\nIt's from Majestic, seat 14.", "got the reply"), result)
    }

    @Test fun waitsWhileClaudeIsStillWriting() {
        val j = job()
        j.step(chat("", home[2]), 0)
        j.step(chat(j.question, home[2]), 100)
        val writing = chat("", ScreenText(j.question), ScreenText("Your bus leaves")) + ScreenText("Stop response", clickable = true)
        assertEquals(ScreenAction.Wait, j.step(writing, 200))
        assertEquals(ScreenAction.Wait, j.step(writing, 10_000)) // unchanged but Stop still showing
    }

    @Test fun opensNewChatWhenNoMessageBox() {
        val j = job()
        val projects = listOf(ScreenText("Projects"), ScreenText("New chat", clickable = true), ScreenText("Trip plans", clickable = true))
        assertEquals(ScreenAction.Tap(1), j.step(projects, 0))
        assertEquals(ScreenAction.Wait, j.step(projects, 600)) // only once
    }

    @Test fun findsQuestionEvenWhenCutShortOnScreen() {
        val j = job()
        j.step(chat("", home[2]), 0)
        j.step(chat(j.question, home[2]), 100)
        val cut = chat("", ScreenText("when is my bus (Start with one line beginning SPOKEN…"), ScreenText("At 6:30 PM."))
        j.step(cut, 200)
        assertEquals(ScreenAction.Done("At 6:30 PM.", "got the reply"), j.step(cut, 200 + AskJob.SETTLE_MS))
    }

    @Test fun usesLastCopyOfQuestionInOldChat() {
        val j = job()
        val old = arrayOf(ScreenText(j.question), ScreenText("Yesterday's answer."))
        j.step(chat("", *old), 0)
        j.step(chat(j.question, *old), 100)
        val now = chat("", *old, ScreenText(j.question), ScreenText("Today's answer."))
        j.step(now, 200)
        assertEquals(ScreenAction.Done("Today's answer.", "got the reply"), j.step(now, 200 + AskJob.SETTLE_MS))
    }

    @Test fun givesUpAfterTimeoutWithReason() {
        val j = job()
        val r = j.step(listOf(ScreenText("Loading")), AskJob.TIMEOUT_MS + 1)
        assertEquals(ScreenAction.Done(null, "timed out at: couldn't find Claude's message box"), r)
    }

    @Test fun stopsIfSendNeverWorks() {
        val j = job()
        val typed = chat(j.question, home[2])
        j.step(chat("", home[2]), 0)
        repeat(4) { assertEquals(ScreenAction.Tap(4), j.step(typed, 100L * (it + 1))) }
        assertEquals(ScreenAction.Done(null, "send button didn't work"), j.step(typed, 1_000))
    }
}
