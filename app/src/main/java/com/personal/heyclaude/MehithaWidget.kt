package com.personal.heyclaude

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home-screen widget: tap the mic to ask Mehitha by voice, or Ask to type, with the bills and
 * renewals due this week and today's Nifty 100 breakouts at a glance.
 * It refreshes every 30 minutes and whenever the alert checks run.
 */
class MehithaWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Reading SMS can take a moment, so build the widget off the main thread.
        val pending = goAsync()
        Thread {
            try { refresh(ctx) } catch (e: Exception) { Log.w(TAG, "Widget update failed", e) } finally { pending.finish() }
        }.start()
    }

    companion object {
        private const val TAG = "Mehitha"
        private const val PREFS = "widget"
        private const val MAX_LINES = 3
        private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

        /** Keeps today's breakout titles for the widget. */
        fun saveBreakouts(ctx: Context, day: LocalDate, alerts: List<Alert>) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = if (prefs.getString("breakouts_day", "") == day.toString()) prefs.getString("breakouts", "").orEmpty().lines() else emptyList()
            val all = (old + alerts.map { it.title }).filter { it.isNotBlank() }.distinct()
            prefs.edit().putString("breakouts_day", day.toString()).putString("breakouts", all.joinToString("\n")).apply()
        }

        /** The breakouts line: today's alert titles, or why there are none. */
        fun breakoutsText(ctx: Context, today: LocalDate): String {
            if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) return "Market closed today"
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val titles = if (prefs.getString("breakouts_day", "") == today.toString())
                prefs.getString("breakouts", "").orEmpty().lines().filter { it.isNotBlank() } else emptyList()
            if (titles.isEmpty()) return "No breakouts yet today"
            return titles.take(MAX_LINES).joinToString("\n") { "• $it" } +
                if (titles.size > MAX_LINES) "\n+${titles.size - MAX_LINES} more" else ""
        }

        /** The bills line: bills and renewals due in the next 7 days, soonest first. */
        fun billsText(ctx: Context, today: LocalDate): String {
            val data = PhoneData(ctx)
            if (!Bills.hasSource(ctx, data)) return "Allow text messages or app messages in Mehitha settings to see bills"
            val bills = Bills.upcoming(ctx, data, today)
            if (bills.isEmpty()) return "Nothing due this week"
            return bills.take(MAX_LINES).joinToString("\n") { "• " + Bills.short(it, today) } +
                if (bills.size > MAX_LINES) "\n+${bills.size - MAX_LINES} more" else ""
        }

        /** Redraws every Mehitha widget on the home screen. Call off the main thread. */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, MehithaWidget::class.java))
            if (ids.isEmpty()) return
            val now = PhoneData.now()
            val today = now.toLocalDate()
            val bills = try { billsText(ctx, today) } catch (e: Exception) { Log.w(TAG, "Widget bills failed", e); "Couldn't read bills" }
            val views = RemoteViews(ctx.packageName, R.layout.widget_mehitha).apply {
                setTextViewText(R.id.widget_bills, bills)
                setTextViewText(R.id.widget_breakouts, breakoutsText(ctx, today))
                setTextViewText(R.id.widget_updated, "Updated ${now.format(TIME)}")
                setOnClickPendingIntent(R.id.widget_mic, open(ctx, 1, Intent(Intent.ACTION_ASSIST)))
                setOnClickPendingIntent(R.id.widget_ask, open(ctx, 2, Intent(Intent.ACTION_MAIN)))
                setOnClickPendingIntent(R.id.widget_bills_box, open(ctx, 3,
                    Intent(Intent.ACTION_VIEW).putExtra(AssistantActivity.EXTRA_ASK, "which bills and renewals are due this week")))
                setOnClickPendingIntent(R.id.widget_breakouts_box, open(ctx, 4,
                    Intent(Intent.ACTION_VIEW).putExtra(AssistantActivity.EXTRA_ASK, "any alerts")))
            }
            mgr.updateAppWidget(ids, views)
        }

        private fun open(ctx: Context, code: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(ctx, code,
                intent.setClass(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
