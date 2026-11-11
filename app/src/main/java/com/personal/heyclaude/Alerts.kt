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
    private const val CHANNEL = "mehitha_alerts"
    private const val PREFS = "alerts"
    private val TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.US)

    /** Shows [alert] unless it was shown before. Tapping it asks Mehitha [ask], if given. */
    fun post(ctx: Context, alert: Alert, ask: String? = null): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet())!!
        if (alert.id in seen) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mehitha alerts", NotificationManager.IMPORTANCE_HIGH))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun web(url: String, code: Int) = PendingIntent.getActivity(ctx, code,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (ask != null) putExtra(AssistantActivity.EXTRA_ASK, ask) }
        val tap = alert.link?.let { web(it, alert.id.hashCode()) }
            ?: PendingIntent.getActivity(ctx, alert.id.hashCode(), open, flags)
        val chart = alert.image?.let { picture(it) }
        val builder = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mehitha)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(if (chart != null) Notification.BigPictureStyle().bigPicture(chart).setSummaryText(alert.text)
                      else Notification.BigTextStyle().bigText(alert.text))
            .setContentIntent(tap)
            .setAutoCancel(true)
        if (chart != null) builder.setLargeIcon(chart)
        alert.image?.let { builder.addAction(Notification.Action.Builder(null, "Chart", web(it, alert.id.hashCode() + 1)).build()) }
        alert.link?.let { builder.addAction(Notification.Action.Builder(null, "Full analysis", web(it, alert.id.hashCode() + 2)).build()) }
        val n = builder.build()
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
        if (items.isEmpty()) return "No new alerts in the last two days."
        return "You have ${items.size} alert${if (items.size > 1) "s" else ""}. " + items.joinToString(" ") { it.second.trimEnd('.') + "." }
    }

    /** Runs the hourly checks (see AlertWorker) and the market-hours breakout checks (see BreakoutWorker). */
    fun schedule(ctx: Context) {
        val work = PeriodicWorkRequestBuilder<AlertWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("mehitha-alerts", ExistingPeriodicWorkPolicy.KEEP, work)
        val breakouts = PeriodicWorkRequestBuilder<BreakoutWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("mehitha-breakouts", ExistingPeriodicWorkPolicy.KEEP, breakouts)
    }
}

/**
 * Every hour: odd charges, unusual spending and bills due from SMS; after market close,
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
                Money.billAlerts(data.bills(), today).forEach { Alerts.post(ctx, it) }
                if (now.hour >= 19) Money.unusualSpending(spends, today).forEach { Alerts.post(ctx, it, ask = "spending report") }
            }
        } catch (e: Exception) {
            Log.w("Mehitha", "SMS checks failed", e)
        }
        if (now.hour in 7..10) once("brief") {
            Alerts.post(ctx, Alert("brief:$today", "Good morning", "Tap for your briefing: meetings, messages, bills and stocks."), ask = "good morning")
        }
        if (now.dayOfWeek == DayOfWeek.SUNDAY && now.hour in 18..21) once("weekly") {
            Alerts.post(ctx, Alert("weekly:$today", "Your weekly review is ready", "Money, bills, stocks and the week ahead. Tap to hear it."), ask = "weekly review")
        }
        if (now.hour >= 17 && now.dayOfWeek != DayOfWeek.SATURDAY) once("stocks") {
            stockAlerts(today.toString()).forEach { Alerts.post(ctx, it) }
        }
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
            AlertWorker.parseStockAlerts(AlertWorker.fetch(PhoneData.BREAKOUTS_URL), now.toLocalDate().toString())
                .forEach { Alerts.post(applicationContext, it) }
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

    /** Schedules each reminder; returns a line per reminder for the screen. */
    fun schedule(ctx: Context, notes: List<ReminderNote>): List<String> = notes.mapNotNull { r ->
        val delay = Duration.between(PhoneData.now(), r.at)
        if (delay.isNegative) return@mapNotNull null
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString("text", r.text).putString("at", r.at.toString()).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("remind:${r.at}:${r.text.hashCode()}", ExistingWorkPolicy.REPLACE, work)
        "Reminder set for ${r.at.format(SHOWN)}: ${r.text}"
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val text = inputData.getString("text") ?: return Result.success()
        Alerts.post(applicationContext, Alert("remind:${inputData.getString("at")}:${text.hashCode()}", "Reminder", text))
        return Result.success()
    }
}
