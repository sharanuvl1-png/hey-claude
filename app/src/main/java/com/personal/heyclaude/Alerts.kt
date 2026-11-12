package com.personal.heyclaude

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.app.Notification
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Mehitha speaking up first: notifications, each said only once, and a list for "any alerts?". */
object Alerts {
    /**
     * A new channel id, so every phone gets a pop-up (heads-up) channel: Android never raises the
     * importance of a channel that already exists, and the old one may have been set to silent.
     */
    const val CHANNEL = "mehitha_popup"
    private const val OLD_CHANNEL = "mehitha_alerts"

    /** Creates the pop-up channel (high importance, sound and vibration) and removes the old one. */
    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mehitha alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Morning briefing, meetings, deliveries, bills and stock alerts"
                enableVibration(true)
            })
        }
        nm.deleteNotificationChannel(OLD_CHANNEL)
    }

    /** Reminders ring with the alarm sound until you tap or swipe them away. */
    const val ALARM_CHANNEL = "mehitha_reminder_alarm"

    fun ensureAlarmChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ALARM_CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(ALARM_CHANNEL, "Mehitha reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminders you set with Mehitha. They ring like an alarm until you tap or swipe them."
            enableVibration(true)
            setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        })
    }

    /** True when Mehitha's alerts can show and pop up on screen. */
    fun canPopUp(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        ensureChannel(ctx)
        return nm.areNotificationsEnabled() && (nm.getNotificationChannel(CHANNEL)?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH
    }
    private const val PREFS = "alerts"
    private val TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.US)
    /** Set on a tap intent when the answer to EXTRA_ASK should be read aloud straight away. */
    const val EXTRA_SPEAK = "com.personal.heyclaude.SPEAK"

    /** Shows [alert] unless it was shown before. Tapping it asks Mehitha [ask], if given. */
    /** [alarm]: a reminder, which rings with the alarm sound until it is tapped or swiped away. */
    fun post(ctx: Context, alert: Alert, ask: String? = null, speak: Boolean = false, alarm: Boolean = false): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet())!!
        if (alert.id in seen) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        ensureChannel(ctx)
        if (alarm) ensureAlarmChannel(ctx)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun web(url: String, code: Int) = PendingIntent.getActivity(ctx, code,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (ask != null) putExtra(AssistantActivity.EXTRA_ASK, ask); if (speak) putExtra(EXTRA_SPEAK, true) }
        val tap = alert.link?.let { web(it, alert.id.hashCode()) }
            ?: PendingIntent.getActivity(ctx, alert.id.hashCode(), open, flags)
        val chart = alert.image?.let { picture(it) }
        val builder = Notification.Builder(ctx, if (alarm) ALARM_CHANNEL else CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mehitha)
            .setContentTitle(alert.title)
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setContentText(alert.text)
            .setStyle(if (chart != null) Notification.BigPictureStyle().bigPicture(chart).setSummaryText(alert.text)
                      else Notification.BigTextStyle().bigText(alert.text))
            .setContentIntent(tap)
            .setAutoCancel(true)
        if (chart != null) builder.setLargeIcon(chart)
        alert.image?.let { builder.addAction(Notification.Action.Builder(null, "Chart", web(it, alert.id.hashCode() + 1)).build()) }
        alert.link?.let { builder.addAction(Notification.Action.Builder(null, "Full analysis", web(it, alert.id.hashCode() + 2)).build()) }
        val n = builder.build()
        if (alarm) n.flags = n.flags or Notification.FLAG_INSISTENT
        try { nm.notify(alert.id.hashCode(), n) } catch (e: SecurityException) { Log.w("Mehitha", "No notification permission") }
        val recent = (prefs.getString("recent", "") ?: "").lines().filter { it.isNotBlank() }.takeLast(29) +
            "${System.currentTimeMillis()}\t${alert.title}: ${alert.text}"
        prefs.edit().putStringSet("seen", (seen + alert.id).toList().takeLast(500).toSet())
            .putString("recent", recent.joinToString("\n")).apply()
        return true
    }

    /** Downloads a chart picture for a notification; null when it can't be fetched (the text still shows). */
    private fun picture(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        try { if (conn.responseCode == 200) conn.inputStream.use { BitmapFactory.decodeStream(it) } else null } finally { conn.disconnect() }
    } catch (e: Exception) {
        Log.w("Mehitha", "Chart picture failed", e); null
    }

    /** What to say for "any alerts?": the last 2 days' alerts, newest first. */
    fun summary(ctx: Context): String {
        val since = System.currentTimeMillis() - 48L * 3600 * 1000
        val items = (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recent", "") ?: "").lines()
            .mapNotNull { l -> l.split("\t", limit = 2).takeIf { it.size == 2 }?.let { it[0].toLongOrNull()?.to(it[1]) } }
            .filter { it.first >= since }.sortedByDescending { it.first }
        return listText(items.map { it.second })
    }

    /**
     * Alerts as a short list, newest first: one line each ("• Bill due tomorrow: Airtel 799 rupees"),
     * the first sentence only, at most [max] lines. Nudges like "Good morning" are left out.
     */
    fun listText(items: List<String>, max: Int = 6): String {
        val shown = items.filterNot { NUDGE.containsMatchIn(it) }
        if (shown.isEmpty()) return "No new alerts in the last two days."
        val lines = shown.take(max).map { a ->
            val first = a.split(Regex("(?<![Rr][Ss]|[Nn]o|vs)\\.\\s+(?=[A-Z])")).first().trimEnd('.')
            "• " + (if (first.length > 110) first.take(107).trimEnd() + "…" else first)
        }
        val more = shown.size - lines.size
        return "${shown.size} alert${if (shown.size > 1) "s" else ""} in the last two days:\n" + lines.joinToString("\n") +
            (if (more > 0) "\n…and $more more." else "")
    }

    private val NUDGE = Regex("(?i)^(good morning|weekly review|your weekly review)\\b")

    /** Runs the hourly checks (see AlertWorker) and the market-hours breakout checks (see BreakoutWorker). */
    fun schedule(ctx: Context) {
        val work = PeriodicWorkRequestBuilder<AlertWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("mehitha-alerts", ExistingPeriodicWorkPolicy.KEEP, work)
        val today = PeriodicWorkRequestBuilder<TodayWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("mehitha-today", ExistingPeriodicWorkPolicy.KEEP, today)
        val breakouts = PeriodicWorkRequestBuilder<BreakoutWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("mehitha-breakouts", ExistingPeriodicWorkPolicy.KEEP, breakouts)
    }
}

/**
 * Every hour: odd charges and unusual spending from SMS; bills and renewals due (SMS and
 * Gmail notifications); after market close,
 * the stock alerts from the daily scan; a morning briefing nudge; and the Sunday review nudge.
 */
class AlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val now = PhoneData.now()
        val today = now.toLocalDate()
        val prefs = ctx.getSharedPreferences("alert_worker", Context.MODE_PRIVATE)
        fun once(key: String, block: () -> Unit) {
            if (prefs.getString(key, "") == today.toString()) return
            try { block(); prefs.edit().putString(key, today.toString()).apply() } catch (e: Exception) { Log.w("Mehitha", "$key failed", e) }
        }
        val data = PhoneData(ctx)
        try {
            if (data.canReadSms) {
                val spends = data.spends()
                Money.oddCharges(spends, now, days = 1).forEach { Alerts.post(ctx, it, ask = "any odd charges") }
                if (now.hour >= 19) Money.unusualSpending(spends, today).forEach { Alerts.post(ctx, it, ask = "spending report") }
            }
        } catch (e: Exception) {
            Log.w("Mehitha", "SMS checks failed", e)
        }
        try {
            Money.billAlerts(Bills.upcoming(ctx, data, today), today)
                .forEach { Alerts.post(ctx, it, ask = "which bills and renewals are due this week") }
        } catch (e: Exception) {
            Log.w("Mehitha", "Bill checks failed", e)
        }
        if (now.hour in 7..10) once("brief") {
            val day = try {
                val meetings = Today.meetings(ctx, today)
                val deliveries = Today.deliveries(ctx, data, today)
                listOfNotNull(
                    meetings.size.takeIf { it > 0 }?.let { n -> "$n meeting${if (n > 1) "s" else ""}" + (meetings.firstOrNull { !it.allDay }?.let { ", first at ${Today.time(it.start)}" } ?: "") },
                    deliveries.size.takeIf { it > 0 }?.let { n -> "$n deliver${if (n > 1) "ies" else "y"} expected" },
                ).joinToString(". ").let { if (it.isEmpty()) "" else "$it. " }
            } catch (e: Exception) { "" }
            Alerts.post(ctx, Alert("brief:$today", "Good morning", "${day}Tap for your briefing: meetings, messages, bills and stocks."), ask = "good morning")
        }
        if (now.hour in 8..11) once("groceries") {
            Groceries.nudge(Groceries.log(ctx), today)?.let { Alerts.post(ctx, it, ask = "my usual groceries") }
        }
        if (now.dayOfWeek == DayOfWeek.SUNDAY && now.hour in 18..21) once("weekly") {
            Alerts.post(ctx, Alert("weekly:$today", "Your weekly review is ready", "Money, bills, stocks and the week ahead. Tap to hear it."), ask = "weekly review")
        }
        // The scan file can land any time in the evening, so keep checking each hour until today's is there.
        if (now.hour >= 17 && now.dayOfWeek != DayOfWeek.SATURDAY && now.dayOfWeek != DayOfWeek.SUNDAY &&
            prefs.getString("scan_digest", "") != today.toString()) {
            try {
                ScanDigest.alert(fetch(PhoneData.SCAN_URL), today.toString())?.let {
                    Alerts.post(ctx, it, ask = "today's stock scan", speak = true)
                    prefs.edit().putString("scan_digest", today.toString()).apply()
                }
            } catch (e: Exception) { Log.w("Mehitha", "scan digest failed", e) }
        }
        if (now.hour >= 17 && now.dayOfWeek != DayOfWeek.SATURDAY) once("stocks") {
            stockAlerts(today.toString()).forEach { Alerts.post(ctx, it) }
        }
        try { MehithaWidget.refresh(ctx) } catch (e: Exception) { Log.w("Mehitha", "Widget refresh failed", e) }
        return Result.success()
    }

    /** Alerts the daily scan wrote for today (targets, stop-losses, results, news). */
    private fun stockAlerts(day: String): List<Alert> = parseStockAlerts(fetch(PhoneData.ALERTS_URL), day)

    companion object {
        fun fetch(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            return try { if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else "" } finally { conn.disconnect() }
        }

        /** Reads ALERTS.json; only today's alerts count. */
        fun parseStockAlerts(json: String, day: String): List<Alert> {
            if (json.isBlank()) return emptyList()
            val o = JSONObject(json)
            if (o.optString("date") != day) return emptyList()
            val list = o.optJSONArray("alerts") ?: return emptyList()
            return (0 until list.length()).map { list.getJSONObject(it) }
                .map { Alert(it.getString("id"), it.getString("title"), it.getString("text"),
                    it.optString("image").ifBlank { null }, it.optString("link").ifBlank { null }) }
        }
    }
}

/**
 * Every 15 minutes on weekdays from 9:30 AM to 5 PM IST: Nifty 100 breakouts by your rules
 * (BREAKOUTS.json from the breakouts branch, same shape as ALERTS.json). Each alert shows once.
 */
class BreakoutWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val now = PhoneData.now()
        val minutes = now.hour * 60 + now.minute
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY || minutes < 9 * 60 + 30 || minutes > 17 * 60) {
            return Result.success()
        }
        try {
            val alerts = AlertWorker.parseStockAlerts(AlertWorker.fetch(PhoneData.BREAKOUTS_URL), now.toLocalDate().toString())
            alerts.forEach { Alerts.post(applicationContext, it) }
            MehithaWidget.saveBreakouts(applicationContext, now.toLocalDate(), alerts)
            MehithaWidget.refresh(applicationContext)
        } catch (e: Exception) {
            Log.w("Mehitha", "Breakout check failed", e)
        }
        return Result.success()
    }
}

/** Notes you asked Mehitha to remember, kept on the phone. */
object Memory {
    private fun file(ctx: Context) = File(ctx.filesDir, "memory.txt")

    fun add(ctx: Context, text: String) {
        file(ctx).appendText("${System.currentTimeMillis()}\t${text.replace(Regex("\\s+"), " ").trim()}\n")
    }

    fun all(ctx: Context): List<Note> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            val parts = l.split("\t", limit = 2)
            val ms = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            Note(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), PhoneData.IST), parts.getOrNull(1).orEmpty())
        }
    }
}

/** Reminders Claude worked out (REMIND lines), shown as notifications at the time. */
object Reminders {
    private val SHOWN = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.US)
    private val LISTED = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.US)
    private const val PREFS = "reminders"

    /** Schedules each reminder; returns a line per reminder for the screen. */
    /** [quiet]: reminders already set as a Clock alarm, whose notification shouldn't ring too. */
    fun schedule(ctx: Context, notes: List<ReminderNote>, quiet: Set<ReminderNote> = emptySet()): List<String> = notes.mapNotNull { r ->
        val delay = Duration.between(PhoneData.now(), r.at)
        if (delay.isNegative) return@mapNotNull null
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString("text", r.text).putString("at", r.at.toString()).putBoolean("ring", r !in quiet).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("remind:${r.at}:${r.text.hashCode()}", ExistingWorkPolicy.REPLACE, work)
        save(ctx, saved(ctx).filter { it != r } + r)
        "Reminder set for ${r.at.format(SHOWN)}: ${r.text}. It rings until you stop it. Say \"my reminders\" to see them all."
    }

    /**
     * Also sets an alarm in the phone's Clock app for a reminder in the next 24 hours, so it rings
     * until stopped and shows in the Clock app. Only from a screen you're using (Android doesn't
     * let apps open the Clock app in the background). Returns true when the Clock app took it.
     */
    fun clockAlarm(ctx: Context, r: ReminderNote, now: LocalDateTime = PhoneData.now()): Boolean {
        if (ctx !is android.app.Activity || !r.at.isAfter(now) || r.at.isAfter(now.plusHours(24))) return false
        val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, r.at.hour)
            .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, r.at.minute)
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, r.text.take(60))
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
        return try { ctx.startActivity(i); true } catch (e: Exception) { Log.w("Mehitha", "Clock alarm failed", e); false }
    }

    /** Reminders still to come, soonest first. */
    fun upcoming(ctx: Context, now: LocalDateTime = PhoneData.now()): List<ReminderNote> =
        saved(ctx).filter { it.at.isAfter(now) }.sortedBy { it.at }

    /** The answer to "my reminders". */
    fun listText(list: List<ReminderNote>): String =
        if (list.isEmpty()) "You have no reminders set. Say something like \"remind me to call Ravi at 6 pm\"."
        else "You have ${list.size} reminder${if (list.size > 1) "s" else ""}:\n" + list.take(10).joinToString("\n") { "• ${it.at.format(LISTED)}: ${it.text}" }

    /** Forgets a reminder once it has gone off. */
    fun done(ctx: Context, at: String?, text: String) = save(ctx, saved(ctx).filterNot { it.at.toString() == at && it.text == text })

    private fun saved(ctx: Context): List<ReminderNote> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "").orEmpty().lines().mapNotNull { l ->
            val (at, text) = l.split("|", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            try { ReminderNote(LocalDateTime.parse(at), text) } catch (e: Exception) { null }
        }

    private fun save(ctx: Context, list: List<ReminderNote>) {
        val keep = list.filter { it.at.isAfter(PhoneData.now().minusDays(1)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("list", keep.joinToString("\n") { "${it.at}|${it.text.replace("\n", " ")}" }).apply()
    }
}
class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val text = inputData.getString("text") ?: return Result.success()
        Alerts.post(applicationContext, Alert("remind:${inputData.getString("at")}:${text.hashCode()}", "Reminder", text), alarm = inputData.getBoolean("ring", true))
        Reminders.done(applicationContext, inputData.getString("at"), text)
        return Result.success()
    }
}
