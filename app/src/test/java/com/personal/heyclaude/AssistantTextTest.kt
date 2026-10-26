package com.personal.heyclaude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTextTest {

    @Test fun yesMeansRead() {
        for (s in listOf("yes", "Yes please", "yeah", "sure", "okay", "OK read it", "read it", "go ahead", "haan", "ji"))
            assertEquals(s, true, AssistantText.yesNo(s))
    }

    @Test fun noMeansDontRead() {
        for (s in listOf("no", "No thanks", "nope", "don't", "do not read it", "no don't read", "skip", "not now", "nahi", "cancel", "stop"))
            assertEquals(s, false, AssistantText.yesNo(s))
    }

    @Test fun unclearAsksAgain() {
        for (s in listOf("", "hmm", "what", "I know", "nothing much"))
            assertEquals(s, null, AssistantText.yesNo(s))
    }

    @Test fun speechChunksKeepEverything() {
        val long = (1..400).joinToString(" ") { "Sentence number $it is here." }
        val chunks = AssistantText.chunksForSpeech(long, max = 500)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 500 })
        assertEquals(long, chunks.joinToString(" "))
        assertEquals(listOf("Short answer."), AssistantText.chunksForSpeech("  Short   answer. "))
    }

    @Test fun keywordsDropFillerWords() {
        assertEquals(listOf("redbus", "ticket"), AssistantText.keywords("When is my redBus ticket?"))
        assertEquals(listOf("salary", "slip", "october"), AssistantText.keywords("find my salary slip for October"))
    }

    @Test fun bestLinesRankByMatches() {
        val lines = sequenceOf("redBus ticket booked", "Swiggy order", "redBus offer", "ticket for movie")
        assertEquals(listOf("redBus ticket booked", "redBus offer", "ticket for movie"),
            AssistantText.bestLines(lines, listOf("redbus", "ticket")))
    }

    @Test fun gistComesFromSpokenLine() {
        val r = AssistantText.forSpeech("**SPOKEN:** Nifty had a good day.\n\nNifty 50 closed at 24,310, up 0.8%. Banks led.")
        assertEquals("Nifty had a good day.", r.speech)
        assertEquals("Nifty 50 closed at 24,310, up 0.8%. Banks led.", r.display)
    }

    @Test fun gistFallsBackToFirstTwoSentences() {
        val r = AssistantText.forSpeech("One. Two! Three? Four.")
        assertEquals("One. Two!", r.speech)
        assertEquals("One. Two! Three? Four.", r.display)
    }
}
