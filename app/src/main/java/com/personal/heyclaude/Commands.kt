package com.personal.heyclaude

/** What the spoken words ask Mehitha to do. Plain Kotlin so it can be tested. */
sealed class Command {
    object Briefing : Command()
    object Alerts : Command()
    object SpendingReport : Command()
    object OddCharges : Command()
    object CatchUp : Command()
    object WeeklyReview : Command()
    data class Money(val question: String) : Command()
    data class StockCheck(val stock: String) : Command()
    data class FindDocument(val question: String) : Command()
    data class Remember(val fact: String) : Command()
    data class Reminder(val request: String) : Command()
    data class Ask(val question: String) : Command()

    companion object {
        private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

        /** Words that start an everyday request rather than a stock name ("check my email"). */
        private const val NOT_STOCK = "(my|the|a|an|if|whether|what|when|where|how|who|for|on|in|is|are|it|this|that|e?mails?|mail|weather|whatsapp|slack|calendar|messages?|news|traffic)\\b"

        private val BRIEFING = re("^(good morning|(give me |what'?s |start )?(my )?(morning |daily )?brief(ing)?( me)?|brief me)\\W*$")
        private val ALERTS = re("\\b(any|my|what are (my|the)|show|read)( new)? alerts?\\b|^alerts?\\W*$")
        private val ODD = re("\\b(odd|unusual|strange|suspicious|unknown|wrong|double|fraud(ulent)?)\\b.*\\b(charges?|debits?|transactions?|payments?|spends?)\\b|\\bfraud\\b")
        private val REPORT = re("\\b(spending|expense|expenses|money|monthly) report\\b|where did (all )?my money go")
        private val WEEKLY = re("\\b(weekly|week'?s) review\\b|review (of )?my week|\\bmy week\\b.*\\b(review|summary)\\b")
        private val CATCH_UP = re("\\bwhat did i miss\\b|\\bcatch me up\\b|^catch up\\b|\\bwhat'?s new on (whatsapp|slack)\\b")
        private val REMEMBER = re("^(please )?remember( that)?\\s+(.{3,})$")
        private val REMIND = re("^(please )?(remind me|set (a )?reminder)\\b.{3,}")
        private val MONEY = re("\\b(spen[dt]|spending|expenses?|subscriptions?|how much (did|have) i (pay|paid)|paid for|my money)\\b")
        private val DOC = re("\\b(expire|expires|expiry|expiring|renewal|renew)\\b|^(find|where('s| is)) my\\b.*\\b(policy|document|certificate|card|bill|receipt|invoice|papers?|insurance|passport|licen[cs]e|rc)\\b")
        private val STOCK = listOf(
            re("^(please )?(check|analy[sz]e|look at|review) (the )?(?<s>.+?) (stock|shares?|share price)\\W*$"),
            re("^should i (buy|sell|hold|invest in) (the )?(?<s>.+?) (stock|shares?)\\W*$"),
            re("^should i (look at|invest in) (?!$NOT_STOCK)(?<s>[\\w&.'-]+( [\\w&.'-]+){0,3})\\W*$"),
            re("^how (is|are) (?<s>.+?) (stock|shares?) (doing|looking)\\W*$"),
            re("^(please )?(check|analy[sz]e) (?!$NOT_STOCK)(?<s>[\\w&.'-]+( [\\w&.'-]+){0,3})\\W*$"),
        )

        fun parse(heard: String): Command {
            val t = heard.trim().replace(Regex("\\s+"), " ")
            REMEMBER.find(t)?.let { return Remember(it.groupValues[3].trim().trimEnd('.')) }
            if (REMIND.containsMatchIn(t)) return Reminder(t)
            if (BRIEFING.matches(t)) return Briefing
            if (ALERTS.containsMatchIn(t) && !MONEY.containsMatchIn(t)) return Alerts
            if (ODD.containsMatchIn(t)) return OddCharges
            if (REPORT.containsMatchIn(t)) return SpendingReport
            if (WEEKLY.containsMatchIn(t)) return WeeklyReview
            if (CATCH_UP.containsMatchIn(t)) return CatchUp
            if (MONEY.containsMatchIn(t)) return Money(t)
            if (DOC.containsMatchIn(t)) return FindDocument(t)
            for (p in STOCK) p.find(t)?.groups?.get("s")?.let { return StockCheck(it.value.trim()) }
            return Ask(t)
        }
    }
}
