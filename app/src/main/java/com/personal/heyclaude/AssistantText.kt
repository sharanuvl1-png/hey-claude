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

    /** Splits text into chunks the speech engine accepts, breaking at sentence ends. */
    fun chunksForSpeech(text: String, max: Int = 3500): List<String> {
        val sentences = text.replace(Regex("\\s+"), " ").trim().split(Regex("(?<=[.!?])\\s+"))
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
    )
}
