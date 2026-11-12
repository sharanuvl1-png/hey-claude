package com.personal.heyclaude

/** Small text helpers for the voice assistant, kept free of Android so they can be tested. */
object AssistantText {

    private val YES = Regex("(?i)\\b(yes|yeah|yep|yup|sure|ok|okay|please|read|go ahead|haan|ha|han|ji|haa)\\b")
    private val NO = Regex("(?i)\\b(no|nope|nah|don't|do not|dont|stop|skip|not now|nahi|nahin|na|cancel)\\b")

    /** true = read it, false = don't, null = unclear. "No" wins when both appear ("no, don't read"). */
    fun yesNo(heard: String): Boolean? = when {
        NO.containsMatchIn(heard) -> false
        YES.containsMatchIn(heard) -> true
        else -> null
    }

    /** What to show on screen, the shorter, casual version to say out loud, and pictures to show. */
    data class Reply(val display: String, val speech: String, val images: List<String> = emptyList(),
                     val links: List<Pair<String, String>> = emptyList())

    private val OPEN = Regex("(?im)^[ \\t*_>-]*open[ \\t*_]*:[ \\t]*(.+?)[ \\t]*\\|[ \\t]*<?(https://\\S+?)>?[ \\t]*(\\r?\\n|\\z)")

    /** Buttons from "OPEN: name | link" lines (a file or page to open), and the text without those lines. */
    fun links(reply: String): Pair<String, List<Pair<String, String>>> {
        val found = OPEN.findAll(reply).map { it.groupValues[1].trim().trim('*', '_') to it.groupValues[2] }.distinctBy { it.second }.take(6).toList()
        return reply.replace(OPEN, "").replace(Regex("\\n{3,}"), "\n\n").trim() to found
    }

    private val IMAGE = Regex("(?im)^[ \\t*_>-]*image[ \\t*_]*:[ \\t]*<?(https://\\S+?)>?[ \\t]*(\\r?\\n|\\z)")

    /** Picture links on "IMAGE: <link>" lines, and the text without those lines. */
    fun images(reply: String): Pair<String, List<String>> {
        val urls = IMAGE.findAll(reply).map { it.groupValues[1] }.distinct().take(MAX_IMAGES).toList()
        return reply.replace(IMAGE, "").replace(Regex("\\n{3,}"), "\n\n").trim() to urls
    }

    private const val MAX_IMAGES = 4

    private val SPOKEN = Regex("(?im)^[\\s*_#>-]*spoken[\\s*_]*:[\\s*_]*(.+)$")

    /**
     * Claude is asked to start with a "SPOKEN:" line: a casual gist to read aloud.
     * Without one, the first two sentences are read.
     */
    fun forSpeech(full: String): Reply {
        val (withLinks, pictures) = images(full)
        val (reply, buttons) = links(withLinks)
        val m = SPOKEN.find(reply)
        if (m != null) {
            val speech = m.groupValues[1].trim().trim('*', '_').trim()
            val display = reply.removeRange(m.range).trim().ifBlank { speech }
            return Reply(display, speech, pictures, buttons)
        }
        val sentences = reply.replace("•", " ").replace(Regex("\\s+"), " ").trim().split(Regex("(?<=[.!?])\\s+"))
        return Reply(reply.trim(), sentences.take(2).joinToString(" "), pictures, buttons)
    }

    /** [text] as it should be said: bullets aren't read, and each line of a list is its own sentence. */
    fun speakable(text: String): String =
        text.replace("•", " ").replace(Regex("(?<![.!?:])[ \\t]*\\n+\\s*(?=\\S)"), ". ").replace(Regex("\\s+"), " ").trim()

    /** Splits text into chunks the speech engine accepts, breaking at sentence ends. */
    fun chunksForSpeech(text: String, max: Int = 3500): List<String> {
        val sentences = speakable(text).split(Regex("(?<=[.!?])\\s+"))
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in sentences) {
            if (s.isEmpty()) continue
            if (sb.length + s.length + 1 > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            if (s.length > max) s.chunked(max).forEach { out += it } else sb.append(if (sb.isEmpty()) s else " $s")
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** Words worth searching for: 3+ letters, minus filler words. */
    fun keywords(query: String): List<String> = query.lowercase()
        .split(Regex("[^\\p{L}\\p{N}@.]+"))
        .map { it.trim('.') }
        .filter { it.length >= 3 && it !in STOP }
        .distinct()

    /** Lines that mention the most keywords, best first, at most [limit]. */
    fun bestLines(lines: Sequence<String>, words: List<String>, limit: Int = 40): List<String> {
        if (words.isEmpty()) return emptyList()
        return lines.mapNotNull { line ->
            val l = line.lowercase()
            val score = words.count { l.contains(it) }
            if (score > 0) score to line else null
        }.sortedByDescending { it.first }.take(limit).map { it.second }.toList()
    }

    private val STOP = setOf(
        "the", "and", "for", "what", "when", "where", "which", "who", "how", "did", "does", "was", "were",
        "is", "are", "my", "me", "you", "your", "about", "with", "from", "this", "that", "there", "any",
        "have", "has", "had", "can", "could", "please", "tell", "show", "find", "check", "message",
        "messages", "today", "yesterday", "phone", "mehitha", "get", "give", "last",
        "much", "many", "spend", "spent", "spending", "pay", "paid", "paying", "month", "week", "year", "this",
        "did", "does", "will", "should", "would",
    )
}
