package com.personal.heyclaude

import android.content.Context
import android.util.Log

/**
 * How Mehitha answers one question, shared by the chat screen and the voice sheet:
 * what it answers by itself, what it sends to Claude, and how it shows the reply.
 */
object Answers {
    private const val TAG = "HeyClaude"
    private const val MAX_DATA = 700

    /** Mehitha's own answer for [command], without Claude; null when Claude is needed. */
    fun local(ctx: Context, command: Command, text: String): String? = when (command) {
        is Command.Remember -> {
            Memory.add(ctx, command.fact)
            "Okay, I'll remember that: ${command.fact}."
        }
        is Command.Alerts -> Alerts.summary(ctx)
        is Command.Ask -> Prompts.recall(Memory.all(ctx), text)
        else -> null
    }

    /** The words sent to Claude for [command], with the phone's own data filled in. Slow: run off the screen thread. */
    fun prompt(ctx: Context, command: Command, text: String): String {
        val t = PhoneData.now()
        val day = t.toLocalDate()
        val data = AssistantActivity.phoneData ?: PhoneData(ctx)
        fun bills() = (if (data.canReadSms) Money.billsText(data.bills(), day) else PhoneData.NO_SMS).take(300)
        return when (command) {
            is Command.Briefing -> Prompts.briefing(t, bills(), Prompts.scanSummary(data.scan()))
            is Command.Money -> Prompts.money(t, command.question, data.moneyFacts(command.question).take(MAX_DATA))
            is Command.SpendingReport -> Prompts.spendingReport(t,
                (if (data.canReadSms) Money.report(data.spends(), day) else PhoneData.NO_SMS).take(MAX_DATA))
            is Command.OddCharges -> {
                val spends = data.spends()
                val flags = Money.oddCharges(spends, t, days = 7).joinToString("; ") { it.text }.ifEmpty { "none" }
                Prompts.oddCharges(t, if (data.canReadSms) flags else PhoneData.NO_SMS, Money.recentLines(spends, t, 7).take(MAX_DATA))
            }
            is Command.StockCheck -> Prompts.stockCheck(t, command.stock, Prompts.scanFor(data.scan(), command.stock))
            is Command.CatchUp -> Prompts.catchUp(t, data.messagesToday(MAX_DATA))
            is Command.FindDocument -> Prompts.findDocument(t, command.question, data.files(command.question))
            is Command.WeeklyReview -> Prompts.weeklyReview(t,
                (if (data.canReadSms) Money.week(data.spends(), t) else PhoneData.NO_SMS).take(MAX_DATA / 2), bills(), Prompts.scanSummary(data.scan()))
            is Command.Reminder -> Prompts.reminder(t, command.request)
            else -> text
        }
    }

    /** [prompt], falling back to the plain question if the phone data can't be read. */
    fun safePrompt(ctx: Context, command: Command, text: String): String =
        try { prompt(ctx, command, text) } catch (e: Exception) { Log.w(TAG, "Phone data failed", e); text }

    /** Claude's [text], with any reminders in it set, ready to show and to say. */
    fun reply(ctx: Context, text: String): AssistantText.Reply {
        val (reminders, rest) = Prompts.reminders(text)
        val set = try { Reminders.schedule(ctx, reminders) } catch (e: Exception) { Log.w(TAG, "Reminder failed", e); emptyList() }
        val reply = AssistantText.forSpeech(rest)
        return reply.copy(display = (listOf(reply.display) + set).joinToString("\n\n"))
    }

    /** A plain-words explanation of why asking Claude failed. */
    fun problem(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName).lowercase()
        return when {
            "401" in m || "authentication" in m || "api key" in m || "x-api-key" in m ->
                "Claude didn't accept the API key. Please check it in Settings."
            "credit" in m || "billing" in m -> "Your Claude API account is out of credit. Please add credit at console dot anthropic dot com."
            "429" in m || "rate" in m -> "Claude is busy right now. Please try again in a minute."
            "unknownhost" in m || "unable to resolve" in m || "timeout" in m || "timed out" in m || "connect" in m ->
                "I couldn't reach Claude. Please check the internet connection."
            else -> "Something went wrong asking Claude. Please try again."
        }
    }
}
