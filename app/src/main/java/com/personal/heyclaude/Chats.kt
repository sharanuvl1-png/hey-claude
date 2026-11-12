package com.personal.heyclaude

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import kotlin.math.abs

/** One app notification Mehitha saved: when, which app, who from, and the text. */
data class Chat(val time: LocalDateTime, val app: String, val sender: String, val text: String)

/**
 * WhatsApp, Gmail and other app messages saved from notifications (see NotificationCatcher),
 * read for spends and bills the same way as text messages.
 */
object Chats {
    /** Banks, cards and payment apps: only their WhatsApp messages count as spends, never a friend's chat. */
    private val MONEY_SENDER = Regex("(?i)\\b(bank|hdfc|icici|sbi|axis|kotak|yes ?bank|idfc|indusind|federal|rbl|au small|canara|baroda|pnb|union|" +
        "card|credit|upi|paytm|phonepe|gpay|google pay|amazon pay|cred|mobikwik|slice|onecard|jupiter|fi money)\\b")
    private val MONEY_APPS = Regex("(?i)^(whatsapp( business)?|gmail|messages)$")
    private val BILL_APPS = Regex("(?i)^(whatsapp( business)?|gmail)$")

    /** Saved notifications from the last [days] days, oldest first. */
    fun read(dir: File, today: LocalDate, days: Long): List<Chat> = (days downTo 0).flatMap { back ->
        val day = today.minusDays(back)
        val file = File(dir, "$day.txt")
        if (!file.exists()) emptyList() else parse(file.readLines(), day)
    }

    /**
     * Every saved message from the last days, for "WhatsApp messages from the last 5 days":
     * one line each, oldest first, only apps matching [app] when given, OTPs left out. When there
     * is more than [max] characters, the oldest are dropped and the answer says so.
     */
    fun listText(chats: List<Chat>, days: Long, app: String = "", max: Int = 11_000): String {
        val picked = chats.filter { app.isBlank() || it.app.contains(app.trim(), ignoreCase = true) }
            .filter { !PhoneSync.looksLikeOtp(it.text) }
        val what = if (app.isBlank()) "app messages" else "${app.trim()} messages"
        if (picked.isEmpty()) return "No $what saved in the last $days days. Messages are saved from notifications only " +
            "after \"WhatsApp & app messages\" is allowed in Mehitha Settings."
        val lines = picked.map { "${it.time.toLocalDate()} ${it.time.toLocalTime()} | ${it.app} | ${it.sender} | ${it.text.take(400)}" }
        val kept = ArrayDeque<String>()
        var size = 0
        for (l in lines.asReversed()) { if (size + l.length + 1 > max) break; kept.addFirst(l); size += l.length + 1 }
        val head = "All ${picked.size} $what saved in the last $days days (oldest first)" +
            (if (kept.size < picked.size) "; only the newest ${kept.size} fit here" else "") + ":\n"
        return head + kept.joinToString("\n")
    }

    /** Lines `HH:mm | app | sender | text` from one day's file. */
    fun parse(lines: List<String>, day: LocalDate): List<Chat> = lines.mapNotNull { line ->
        val p = line.split(" | ", limit = 4)
        if (p.size < 4) return@mapNotNull null
        val t = try { LocalTime.parse(p[0]) } catch (e: Exception) { return@mapNotNull null }
        Chat(day.atTime(t), p[1].trim(), p[2].trim(), p[3].trim())
    }

    /** Spends from bank and payment messages on WhatsApp or Gmail. */
    fun spends(chats: List<Chat>): List<Spend> = chats
        .filter { MONEY_APPS.matches(it.app) && MONEY_SENDER.containsMatchIn(it.sender) }
        .mapNotNull { c ->
            // expenseRow writes the time in the phone's own time zone, so build the Date the same way.
            PhoneSync.expenseRow(Date.from(c.time.atZone(ZoneId.systemDefault()).toInstant()), c.sender.replace(" ", ""), c.text)
                ?.let { Money.parseRow(it) }
        }

    /** Bills and renewals from WhatsApp and Gmail messages. Office mail apps are left out. */
    fun bills(chats: List<Chat>): List<Bill> = chats.filter { BILL_APPS.matches(it.app) }.mapNotNull { c ->
        Money.billDue(c.sender, c.text, c.time.toLocalDate())?.let { it.copy(sender = c.sender, text = "${c.app}: " + it.text.take(200)) }
    }

    /**
     * SMS spends plus message spends, with a payment that came both ways (same amount within
     * 15 minutes) counted once.
     */
    fun merge(sms: List<Spend>, more: List<Spend>): List<Spend> {
        val extra = more.filter { m -> sms.none { s -> abs(s.amount - m.amount) < 0.01 && abs(java.time.Duration.between(s.time, m.time).toMinutes()) <= 15 } }
        return (sms + extra).sortedBy { it.time }
    }
}
