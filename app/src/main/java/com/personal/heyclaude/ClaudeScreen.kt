package com.personal.heyclaude

/** One piece of text on the Claude app's screen, in screen order. */
data class ScreenText(val text: String, val clickable: Boolean = false, val editable: Boolean = false)

/** What the helper should do next on the Claude screen. */
sealed class ScreenAction {
    object Wait : ScreenAction()
    data class Type(val index: Int) : ScreenAction()
    data class Tap(val index: Int) : ScreenAction()
    data class Done(val reply: String?, val note: String) : ScreenAction()
}

/**
 * Asking Claude through its app, without an API key: types the question, presses send,
 * then waits for Claude's reply to stop changing. Plain Kotlin so it can be tested
 * against made-up screens; the VoiceHelper service does the actual typing and tapping.
 */
class AskJob(val spoken: String, private val startedAt: Long, val label: String = spoken) {
    /** What gets typed: the spoken question plus a request for a short, speakable summary. */
    val question = spoken.trim() + SUMMARY_NOTE

    enum class Phase { TYPE, SEND, WAIT }

    var phase = Phase.TYPE
        private set
    var reply = ""
        private set
    private var before: Set<String> = emptySet()
    private var newChatTapped = false
    private var sendTaps = 0
    private var typeTries = 0
    private var changedAt = 0L

    fun step(items: List<ScreenText>, now: Long): ScreenAction {
        if (now - startedAt > TIMEOUT_MS) {
            return ScreenAction.Done(reply.ifBlank { null }, if (reply.isBlank()) "timed out at: ${phase.label()}" else "timed out, partial reply")
        }
        val input = items.indexOfFirst { it.editable }
        when (phase) {
            Phase.TYPE -> {
                if (input < 0) {
                    // Not on a chat screen: open a new chat once.
                    val chat = items.indexOfFirst { it.clickable && NEW_CHAT.containsMatchIn(it.text) }
                    if (!newChatTapped && chat >= 0) { newChatTapped = true; return ScreenAction.Tap(chat) }
                    return ScreenAction.Wait
                }
                // Type until the box shows the question; the Claude app can ignore typing while a
                // new chat is still loading. If the box never shows it (the app made it an
                // attachment), stop retyping after a few seconds and press send.
                if (!same(items[input].text, question)) {
                    if (typeTries == 0) before = items.filter { !it.editable }.map { it.text.trim() }.toSet()
                    if (++typeTries <= MAX_TYPE_TRIES || items.none { it.clickable && SEND.containsMatchIn(it.text) }) {
                        return ScreenAction.Type(input)
                    }
                } else {
                    before = items.filter { !it.editable }.map { it.text.trim() }.toSet()
                }
                phase = Phase.SEND
                return step(items, now)
            }
            Phase.SEND -> {
                val boxEmpty = input < 0 || items[input].text.isBlank() || !same(items[input].text, question) && sendTaps > 0
                val sent = boxEmpty && (items.any { !it.editable && isQuestion(it.text) } ||
                    sendTaps > 0 && items.none { it.clickable && SEND.containsMatchIn(it.text) })
                if (sent) {
                    phase = Phase.WAIT
                    changedAt = now
                    return step(items, now)
                }
                val send = items.indexOfFirst { it.clickable && SEND.containsMatchIn(it.text) }
                if (send < 0) return ScreenAction.Wait
                if (++sendTaps > MAX_SEND_TAPS) return ScreenAction.Done(null, "send button didn't work")
                return ScreenAction.Tap(send)
            }
            Phase.WAIT -> {
                val current = replyOn(items)
                if (current != reply) { reply = current; changedAt = now }
                val answering = items.any { it.clickable && it.text.length < 40 && STOP.containsMatchIn(it.text) }
                return if (reply.isNotBlank() && !answering && now - changedAt >= SETTLE_MS) ScreenAction.Done(reply, "got the reply")
                else ScreenAction.Wait
            }
        }
    }

    /**
     * Claude's reply: the text below the last copy of the question (skipping the rest of a
     * long question shown as several pieces), minus what was already on screen.
     */
    fun replyOn(items: List<ScreenText>): String {
        val q = items.indexOfLast { !it.editable && isQuestion(it.text) }
        // No copy of the question on screen (sent as an attachment): use whatever is new.
        var start = if (q < 0) 0 else q + 1
        while (start < items.size && !items[start].editable && partOfQuestion(items[start].text)) start++
        return items.drop(start)
            .filter { !it.editable && !(it.clickable && it.text.length < 40) }
            .map { it.text.trim() }
            .filter { it.length > 1 && it !in before && (q >= 0 || !partOfQuestion(it)) }
            .joinToString("\n")
    }

    /** The start of Claude's copy of the question; long messages may be cut short or split on screen. */
    private fun isQuestion(text: String): Boolean {
        val t = shown(text)
        val q = norm(question)
        return t.isNotEmpty() && (t == q || (t.length >= minOf(q.length, 20) && q.startsWith(t)))
    }

    private fun partOfQuestion(text: String): Boolean {
        val t = shown(text)
        return t.isEmpty() || MORE.matches(t) || (t.length >= 8 && norm(question).contains(t))
    }

    /** Text as compared: plain words, without a trailing "show more". */
    private fun shown(text: String) = norm(text).replace(Regex("\\s*(show|see|read) more$"), "").trim()

    private fun Phase.label() = when (this) {
        Phase.TYPE -> "couldn't find Claude's message box"
        Phase.SEND -> "couldn't send the question"
        Phase.WAIT -> "waiting for Claude's reply"
    }

    companion object {
        const val SUMMARY_NOTE = " (Start with one line beginning SPOKEN: that gives a casual one or two sentence gist to say out loud," +
            " then a short summary in 2 to 4 plain sentences, no tables or lists.)"
        const val TIMEOUT_MS = 150_000L
        const val SETTLE_MS = 2_500L
        private const val MAX_SEND_TAPS = 4
        private const val MAX_TYPE_TRIES = 10
        private val SEND = Regex("(?i)\\bsend\\b|\\bsubmit\\b")
        private val STOP = Regex("(?i)\\bstop\\b")
        private val MORE = Regex("(show|see|read) (more|less)")
        private val NEW_CHAT = Regex("(?i)new chat|start (a )?new chat|new conversation")

        private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        fun same(a: String, b: String) = norm(a) == norm(b) && norm(b).isNotEmpty()
    }
}
