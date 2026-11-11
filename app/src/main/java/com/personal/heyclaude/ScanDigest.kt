package com.personal.heyclaude

/**
 * The daily stock scan as one short notification: your own picks, then the top pick of
 * Nifty 50, Midcap and Smallcap. Uses only what LATEST_SCAN.md says; null when the file
 * isn't today's (so nothing stale is ever shown).
 */
object ScanDigest {
    fun alert(md: String, day: String): Alert? {
        val date = Regex("scan, (\\d{4}-\\d{2}-\\d{2})").find(md)?.groupValues?.get(1)
        if (date != day) return null
        val parts = mutableListOf<String>()
        rows(section(md, "## Watchlist")).filter { it[0] == "Your picks" }.forEach { c ->
            parts += "${c[1].substringBefore('.')} ${verdict(c[3])}, entry ${rs(c[5])}, stop ${rs(c[6])}, target ${rs(c[7])}"
        }
        for (group in listOf("Nifty 50", "Midcap", "Smallcap")) {
            val c = rows(section(md, "## Top 5 buy setups today: $group")).firstOrNull()
            parts += if (c == null) "$group: no picks"
                     else "$group: ${c[1].substringBefore(" (").removeSuffix(" Limited")} ${verdict(c[3])}, entry ${rs(c[4])}, stop ${rs(c[5])}, target ${rs(c[6])}"
        }
        if (parts.isEmpty()) return null
        return Alert("scan:$day", "Today's stock scan", parts.joinToString(". ") + ". Not investment advice.")
    }

    private fun section(md: String, heading: String) =
        if (heading in md) md.substringAfter(heading).substringBefore("\n## ") else ""

    private fun rows(section: String): List<List<String>> = section.lines()
        .filter { it.startsWith("|") && !it.startsWith("|---") && !it.startsWith("| Group") && !it.startsWith("| #") }
        .map { l -> l.trim().trim('|').split("|").map { it.trim() } }
        .filter { it.size >= 8 }

    private fun verdict(s: String) = s.replace(Regex("[^A-Za-z ]"), "").trim().lowercase()

    /** "₹12.3 (16%)" -> "₹12.3". */
    private fun rs(s: String) = s.substringBefore(" (").trim()
}
