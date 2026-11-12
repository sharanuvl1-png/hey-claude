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
    data class Chart(val stock: String) : Command()
    /** Today's meetings and/or deliveries: [what] is "meetings", "deliveries" or "all". */
    data class Today(val what: String) : Command()
    /** Open [shop] search for [items], so you can tap Add. Never orders. */
    data class Shop(val items: List<String>, val shop: String) : Command()
    data class ListAdd(val items: List<String>) : Command()
    object ListShow : Command()
    object ListClear : Command()
    /** Open the shopping list's items in [shop]. */
    data class ListSend(val shop: String) : Command()
    /** Where your orders are; [shop] when you named one. */
    data class Orders(val shop: String?) : Command()
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
        private val MEETINGS = re("\\b(any |my |next |today'?s )meetings?\\b|\\bmeetings? today\\b|\\b(what'?s|anything) on my calendar\\b|\\bwhen is my next meeting\\b")
        private val DELIVERIES = re("\\b(any|my|today'?s) (deliveries|delivery|parcels?|packages?|orders? (arriving|coming))\\b|\\b(deliveries|parcels?|packages?) today\\b|\\bwhat'?s (being )?delivered today\\b|\\bwhat'?s arriving today\\b")
        private val TODAY = re("^(so )?what'?s (on |happening )?(for )?today\\W*$|^what('?s| is) on today\\b|\\bmy (day|schedule) today\\b|^what('?s| is) my (day|schedule)( like)?( today)?\\W*$")
        private const val SHOPS = "(?<shop>blinkit|amazon|flipkart)"
        private const val LIST = "(my |the )?(shopping |grocery |groceries )?list"
        private val LIST_SEND = re("^(please )?(send|open|shop|add|put|take|move) $LIST (on|to|in|into) $SHOPS( cart)?\\W*$")
        private val LIST_ADD = re("^(please )?(add|put) (?<items>.+?) (to|on|in|onto) (my|the) (shopping |grocery |groceries )?list\\W*$")
        private val LIST_CLEAR = re("\\b(clear|empty|delete|reset) $LIST\\b")
        private val LIST_SHOW = re("\\b(what'?s|read|show|tell me)( me)? (on |in )?$LIST\\b|^(my )?(shopping|grocery) list\\W*$")
        private val SHOP = re("^(please )?(add|put|get|buy|search( for)?|find|look for|show me|order)( me)? (?<items>.+?) (on|in|from|to|into)( my)? $SHOPS( cart| app)?\\W*$")
        private val ORDERS = re("\\bwhere('?s| is| are) my (?<s1>blinkit |amazon |flipkart )?(\\w+ )?(orders?|parcels?|packages?|stuff)\\b|\\border status\\b|\\btrack (my )?(?<s2>blinkit |amazon |flipkart )?orders?\\b|\\bmy (?<s3>blinkit|amazon|flipkart|myntra) orders?\\b|\\b(status of|updates? on) my (?<s4>blinkit |amazon |flipkart )?orders?\\b")
        private val REMEMBER = re("^(please )?remember( that)?\\s+(.{3,})$")
        private val REMIND = re("^(please )?(r[ei]?[mn]{1,2}i?nd|set (a )?reminder)\\b.{3,}")
        private val MONEY = re("\\b(spen[dt]|spending|expenses?|subscriptions?|how much (did|have) i (pay|paid)|paid for|my money)\\b")
        private val DOC = re("\\b(expire|expires|expiry|expiring|renewal|renew)\\b|^(find|where('s| is)) my\\b.*\\b(policy|document|certificate|card|bill|receipt|invoice|papers?|insurance|passport|licen[cs]e|rc)\\b")
        private val STOCK = listOf(
            re("^(please )?(check|analy[sz]e|look at|review) (the )?(?<s>.+?) (stock|shares?|share price)\\W*$"),
            re("^should i (buy|sell|hold|invest in) (the )?(?<s>.+?) (stock|shares?)\\W*$"),
            re("^should i (look at|invest in) (?!$NOT_STOCK)(?<s>[\\w&.'-]+( [\\w&.'-]+){0,3})\\W*$"),
            re("^how (is|are) (?<s>.+?) (stock|shares?) (doing|looking)\\W*$"),
            re("^(please )?(check|analy[sz]e) (?!$NOT_STOCK)(?<s>[\\w&.'-]+( [\\w&.'-]+){0,3})\\W*$"),
        )

        private val CHART = listOf(
            re("^(please )?((show|open|get|send|give)( me)? )?(the )?(latest |newest |technical |swing )*chart (of|for) (the )?(?<s>.+?)( stock| shares?)?\\W*$"),
            re("^(please )?((show|open|get|send|give)( me)? )?(the )?(?!$NOT_STOCK|(show|open|get|send|give|me|make|draw|create|plot|pie|bar|line)\\b)(?<s>[\\w&.'-]+( [\\w&.'-]+){0,3}?)( stock| shares?)?( technical| swing)? chart\\W*$"),
        )

        fun parse(heard: String): Command {
            val t = heard.trim().replace(Regex("\\s+"), " ")
            REMEMBER.find(t)?.let { return Remember(it.groupValues[3].trim().trimEnd('.')) }
            if (REMIND.containsMatchIn(t)) return Reminder(t)
            if (BRIEFING.matches(t)) return Briefing
            LIST_SEND.find(t)?.let { return ListSend(it.groups["shop"]!!.value.lowercase()) }
            LIST_ADD.find(t)?.let { return ListAdd(Shopping.items(it.groups["items"]!!.value)) }
            if (LIST_CLEAR.containsMatchIn(t)) return ListClear
            if (LIST_SHOW.containsMatchIn(t)) return ListShow
            SHOP.find(t)?.let { return Shop(Shopping.items(it.groups["items"]!!.value), it.groups["shop"]!!.value.lowercase()) }
            ORDERS.find(t)?.let { m ->
                val shop = listOf("s1", "s2", "s3", "s4").firstNotNullOfOrNull { g -> m.groups[g]?.value?.trim()?.lowercase() }
                return Orders(shop)
            }
            if (ALERTS.containsMatchIn(t) && !MONEY.containsMatchIn(t)) return Alerts
            if (ODD.containsMatchIn(t)) return OddCharges
            if (REPORT.containsMatchIn(t)) return SpendingReport
            if (WEEKLY.containsMatchIn(t)) return WeeklyReview
            if (CATCH_UP.containsMatchIn(t)) return CatchUp
            if (MONEY.containsMatchIn(t)) return Money(t)
            if (TODAY.containsMatchIn(t)) return Today("all")
            if (MEETINGS.containsMatchIn(t)) return Today("meetings")
            if (DELIVERIES.containsMatchIn(t)) return Today("deliveries")
            if (DOC.containsMatchIn(t)) return FindDocument(t)
            for (p in CHART) p.find(t)?.groups?.get("s")?.let { return Chart(it.value.trim()) }
            for (p in STOCK) p.find(t)?.groups?.get("s")?.let { return StockCheck(it.value.trim()) }
            return Ask(t)
        }
    }
}
