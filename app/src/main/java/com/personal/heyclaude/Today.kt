package com.personal.heyclaude

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A meeting from the phone's calendar (Google, Outlook or any synced account). */
data class Meeting(val id: Long, val title: String, val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean, val place: String)

/** A parcel or order expected today, from an SMS or app notification. */
data class Delivery(val from: String, val text: String)

/** Today's meetings and deliveries: for "what's on today", the morning nudge and reminders. */
object Today {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun canReadCalendar(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Meetings on [day] from every calendar on the phone, earliest first. */
    fun meetings(ctx: Context, day: LocalDate): List<Meeting> {
        if (!canReadCalendar(ctx)) return emptyList()
        val from = day.atStartOfDay(PhoneData.IST).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(PhoneData.IST).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to)
        }.build()
        val out = mutableListOf<Meeting>()
        ctx.contentResolver.query(uri, arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        ), null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                fun time(i: Int) = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(i)), PhoneData.IST)
                out += Meeting(c.getLong(0), c.getString(1)?.trim().orEmpty().ifEmpty { "Busy" }, time(2), time(3),
                    c.getInt(4) == 1, c.getString(5)?.trim().orEmpty())
            }
        }
        return out
    }

    private val DELIVERY = Regex("(?i)\\b(out for delivery|arriving today|arrives today|will (be delivered|arrive|reach you) today|" +
        "delivery (is )?(scheduled |expected )?(for )?today|delivered today by|today by \\d{1,2}(:\\d{2})? ?(am|pm))\\b")
    private val TOMORROW = Regex("(?i)\\b((arriving|arrives|will (be delivered|arrive)) |delivery (is )?(scheduled |expected )?(for )?)tomorrow\\b")
    private val DONE = Regex("(?i)\\b(has been delivered|was delivered|delivered successfully|successfully delivered|cancelled|canceled|returned|refund)\\b")
    private val SHOPS = listOf("Amazon", "Flipkart", "Myntra", "Meesho", "Ajio", "Nykaa", "Tata CLiQ", "JioMart", "BigBasket", "Blinkit",
        "Zepto", "Swiggy", "Instamart", "FirstCry", "Lenskart", "Croma", "Reliance Digital", "Pepperfry", "IKEA", "Decathlon",
        "Blue Dart", "BlueDart", "Delhivery", "Ekart", "DTDC", "Shadowfax", "Xpressbees", "Ecom Express", "India Post", "Shiprocket")

    /**
     * A delivery expected [today] from a message: "out for delivery" or "arriving today" sent today,
     * or "arriving tomorrow" sent yesterday. Messages that say it already came are skipped.
     */
    fun delivery(sender: String, body: String, received: LocalDate, today: LocalDate): Delivery? {
        if (DONE.containsMatchIn(body)) return null
        val due = when {
            received == today && DELIVERY.containsMatchIn(body) -> true
            received == today.minusDays(1) && TOMORROW.containsMatchIn(body) -> true
            else -> false
        }
        if (!due) return null
        val shop = SHOPS.firstOrNull { body.contains(it, ignoreCase = true) || sender.contains(it.replace(" ", ""), ignoreCase = true) }
            ?: sender.substringAfterLast('-').trim().ifEmpty { "A parcel" }
        return Delivery(shop.replace("BlueDart", "Blue Dart"), body.take(160))
    }

    /** Deliveries expected today, from text messages and captured app notifications. */
    fun deliveries(ctx: Context, data: PhoneData, today: LocalDate): List<Delivery> {
        val found = mutableListOf<Delivery>()
        try {
            data.recentSms(2).forEach { (time, sender, body) -> delivery(sender, body, time.toLocalDate(), today)?.let { found += it } }
        } catch (e: Exception) { Log.w("Mehitha", "Delivery SMS check failed", e) }
        for (day in listOf(today.minusDays(1), today)) {
            val file = File(File(ctx.filesDir, "notifications"), "$day.txt")
            if (!file.exists()) continue
            file.readLines().map { it.split(" | ") }.filter { it.size >= 4 }.forEach {
                delivery(it[1], "${it[2]} ${it[3]}", day, today)?.let { d -> found += d }
            }
        }
        return found.distinctBy { it.from.lowercase() + it.text.lowercase().filter(Char::isLetterOrDigit).take(60) }
    }

    fun time(t: LocalDateTime): String = t.format(TIME).replace(":00 ", " ")

    /** "3 meetings today: 10 AM Standup, ..." in plain spoken words. */
    fun meetingsText(meetings: List<Meeting>, calendarAllowed: Boolean): String = when {
        !calendarAllowed -> "I can't see your calendar yet. Allow Calendar in Mehitha settings."
        meetings.isEmpty() -> "No meetings on your calendar today."
        else -> "${meetings.size} meeting${if (meetings.size > 1) "s" else ""} today: " + meetings.joinToString("; ") {
            (if (it.allDay) "all day, " else time(it.start) + ", ") + it.title
        } + "."
    }

    fun deliveriesText(deliveries: List<Delivery>): String = when {
        deliveries.isEmpty() -> "No deliveries expected today."
        deliveries.size == 1 -> "1 delivery expected today, from ${deliveries[0].from}."
        else -> "${deliveries.size} deliveries expected today, from " + deliveries.map { it.from }.distinct().joinToString(", ") + "."
    }

    /** Mehitha's own answer for "what's on today", "any deliveries today" or "my meetings". */
    fun answer(ctx: Context, what: String): String {
        val day = PhoneData.today()
        val data = AssistantActivity.phoneData ?: PhoneData(ctx)
        val m = { meetingsText(meetings(ctx, day).filter { it.allDay || it.end.isAfter(PhoneData.now()) }, canReadCalendar(ctx)) }
        val d = { deliveriesText(deliveries(ctx, data, day)) }
        return when (what) {
            "meetings" -> m()
            "deliveries" -> d()
            else -> m() + " " + d()
        }
    }

    /** Alerts for meetings starting in the next [ahead] minutes, each shown once. */
    fun meetingAlerts(meetings: List<Meeting>, now: LocalDateTime, ahead: Long = 20): List<Alert> =
        meetings.filter { !it.allDay && it.start.isAfter(now) && !it.start.isAfter(now.plusMinutes(ahead)) }.map {
            val mins = java.time.Duration.between(now, it.start).toMinutes().coerceAtLeast(1)
            Alert("meet:${it.id}:${it.start}", "${it.title} in $mins min",
                "Starts at ${time(it.start)}" + (if (it.place.isNotEmpty()) ", ${it.place}" else "") + ".")
        }

    fun deliveryAlerts(deliveries: List<Delivery>, today: LocalDate): List<Alert> = deliveries.map {
        Alert("delivery:$today:${it.from.lowercase()}:${it.text.lowercase().filter(Char::isLetterOrDigit).take(40).hashCode()}",
            "Delivery today: ${it.from}", it.text)
    }
}

/** Every 15 minutes: a heads-up before each meeting, and a note for each delivery expected today (7 AM to 9 PM). */
class TodayWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val now = PhoneData.now()
        val today = now.toLocalDate()
        try {
            Today.meetingAlerts(Today.meetings(ctx, today), now).forEach { Alerts.post(ctx, it, ask = "what's on today") }
        } catch (e: Exception) { Log.w("Mehitha", "Meeting check failed", e) }
        if (now.hour in 7..20) try {
            Today.deliveryAlerts(Today.deliveries(ctx, PhoneData(ctx), today), today).forEach { Alerts.post(ctx, it) }
        } catch (e: Exception) { Log.w("Mehitha", "Delivery check failed", e) }
        return Result.success()
    }
}
