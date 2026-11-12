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
    fun local(ctx: Context, command: Command, text: String): String? = missing(ctx, command) ?: when (command) {
        is Command.Remember -> {
            Memory.add(ctx, command.fact)
            "Okay, I'll remember that: ${command.fact}."
        }
        is Command.Alerts -> Alerts.summary(ctx)
        is Command.Today -> Today.answer(ctx, command.what)
        is Command.ListAdd -> Shopping.add(ctx, command.items).let { "Added ${Shopping.said(command.items)}. Your list has ${it.size} item${if (it.size > 1) "s" else ""}." }
        is Command.ListShow -> Shopping.listAnswer(ctx)
        is Command.ListClear -> { Shopping.clear(ctx); "Cleared your shopping list." }
        is Command.Orders -> Orders.answer(Orders.recent(AssistantActivity.phoneData ?: PhoneData(ctx)), PhoneData.today(), command.shop)
        is Command.Ask -> Prompts.recall(Memory.all(ctx), text)
        else -> null
    }

    /**
     * When [command] needs SMS or WhatsApp that this phone or this version of Mehitha can't read,
     * says plainly what's missing and how to fix it, instead of asking Claude with no data.
     */
    fun missing(ctx: Context, command: Command): String? {
        val needsMoney = command is Command.Money || command is Command.SpendingReport || command is Command.OddCharges
        val needsChats = command is Command.CatchUp
        val needsAny = command is Command.Orders
        if (!needsMoney && !needsChats && !needsAny) return null
        val data = AssistantActivity.phoneData ?: PhoneData(ctx)
        val sms = data.canReadSms
        val chats = data.canReadMessages
        val smsInBuild = SettingsActivity.declares(ctx, android.Manifest.permission.READ_SMS)
        val chatsInBuild = SettingsActivity.hasService(ctx, NotificationCatcher::class.java)
        val full = "Install the full version of Mehitha (the hey-claude-full APK on the latest build page) to get this."
        return when {
            needsChats && !chats -> if (chatsInBuild) "I can't see your WhatsApp messages yet. Turn on \"WhatsApp & app messages\" in Settings, Phone data. It saves new messages from then on."
                else "This version of Mehitha can't see WhatsApp messages. $full"
            (needsMoney || needsAny) && !sms && !chats -> when {
                !smsInBuild && !chatsInBuild -> "This version of Mehitha can't read your SMS or WhatsApp, so I have nothing to go on. $full"
                smsInBuild -> "Allow \"Text messages\" in Settings, Phone data, so I can read your bank and shop messages."
                else -> "Turn on \"WhatsApp & app messages\" in Settings, Phone data, so I can read your bank and shop messages."
            }
            else -> null
        }
    }

    /** The words sent to Claude for [command], with the phone's own data filled in. Slow: run off the screen thread. */
    /** [inApp]: answered by Claude inside Mehitha (API key), which can't reach Outlook, Slack, Gmail or Drive. */
    fun prompt(ctx: Context, command: Command, text: String, inApp: Boolean = false): String {
        val apps = !inApp
        val t = PhoneData.now()
        val day = t.toLocalDate()
        val data = AssistantActivity.phoneData ?: PhoneData(ctx)
        // Claude inside Mehitha takes long prompts; the Claude app needs them short (see Prompts.oneLine).
        val max = if (inApp) 8000 else Prompts.MAX_PROMPT
        val dataMax = if (inApp) 4000 else MAX_DATA
        fun scan() = Prompts.scanSummary(data.scan(), if (inApp) 3000 else 450)
        fun bills() = (if (Bills.hasSource(ctx, data)) Money.billsText(Bills.upcoming(ctx, data, day), day) else PhoneData.NO_SMS).take(300)
        return when (command) {
            is Command.Briefing -> Prompts.briefing(t, bills(), scan(),
                try { Today.answer(ctx, "all") } catch (e: Exception) { "" }, apps, max)
            is Command.Money -> Prompts.money(t, command.question, data.moneyFacts(command.question).take(dataMax), max)
            is Command.SpendingReport -> Prompts.spendingReport(t,
                (if (data.canReadSms) Money.report(data.spends(), day) else PhoneData.NO_SMS).take(dataMax), max)
            is Command.OddCharges -> {
                val spends = data.spends()
                val flags = Money.oddCharges(spends, t, days = 7).joinToString("; ") { it.text }.ifEmpty { "none" }
                Prompts.oddCharges(t, if (data.canReadSms) flags else PhoneData.NO_SMS, Money.recentLines(spends, t, 7).take(dataMax), max)
            }
            is Command.StockCheck -> Prompts.stockCheck(t, command.stock, Prompts.scanFor(data.scan(), command.stock), max)
            is Command.CatchUp -> Prompts.catchUp(t, data.messagesToday(dataMax), apps, max)
            is Command.FindDocument -> Prompts.findDocument(t, command.question, data.files(command.question), apps, max)
            is Command.WeeklyReview -> Prompts.weeklyReview(t,
                (if (data.canReadSms) Money.week(data.spends(), t) else PhoneData.NO_SMS).take(dataMax / 2), bills(), scan(), apps, max)
            is Command.Reminder -> Prompts.reminder(t, command.request)
            else -> text
        }
    }

    /** [prompt], falling back to the plain question if the phone data can't be read. */
    fun safePrompt(ctx: Context, command: Command, text: String, inApp: Boolean = false): String =
        try { prompt(ctx, command, text, inApp) } catch (e: Exception) { Log.w(TAG, "Phone data failed", e); text }

    /** Claude's [text], with any reminders in it set, ready to show and to say. */
    fun reply(ctx: Context, text: String): AssistantText.Reply {
        // The voice sheet can't take a passcode; the chat handles it before calling this.
        if (Passcode.isNeeded(text)) return AssistantText.Reply(Passcode.USE_CHAT, Passcode.USE_CHAT)
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
