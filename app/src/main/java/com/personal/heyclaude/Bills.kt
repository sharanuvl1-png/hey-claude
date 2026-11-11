package com.personal.heyclaude

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Bills and renewals coming up, from text messages and from WhatsApp and Gmail notifications
 * that Mehitha saved (needs "WhatsApp & app messages" allowed). Office mail apps such as
 * Outlook are left out, so work email never mixes with personal bills.
 */
object Bills {
    private val SHORT_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.US)

    /** Bills and renewals due from [today] to [days] days ahead, soonest first (SMS, WhatsApp and Gmail). */
    fun upcoming(ctx: Context, data: PhoneData = PhoneData(ctx), today: LocalDate = PhoneData.today(), days: Long = 7): List<Bill> =
        (try { data.bills() } catch (e: Exception) { Log.w("Mehitha", "Bills failed", e); emptyList() })
            .filter { !it.due.isBefore(today) && !it.due.isAfter(today.plusDays(days)) }
            .distinctBy { "${it.sender.lowercase()}:${it.due}" }
            .sortedBy { it.due }

    /** True when there is any source of bills at all (SMS access or saved app messages). */
    fun hasSource(ctx: Context, data: PhoneData): Boolean =
        data.canReadSms || File(ctx.filesDir, "notifications").list()?.isNotEmpty() == true

    /** One short line for the widget, e.g. "HDFC Life renewal · ₹24,500 · in 5 days". */
    fun short(b: Bill, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, b.due)
        val whenDue = when (days) { 0L -> "today"; 1L -> "tomorrow"; in 2L..6L -> "in $days days"; else -> b.due.format(SHORT_DAY) }
        val amount = b.amount?.let { " · ₹" + "%,d".format(Locale("en", "IN"), Math.round(it)) } ?: ""
        return b.sender + (if (b.isRenewal) " renewal" else "") + amount + " · " + whenDue
    }
}
