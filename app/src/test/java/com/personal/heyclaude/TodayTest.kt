package com.personal.heyclaude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TodayTest {
    private val today = LocalDate.of(2026, 11, 11)

    @Test fun deliveriesFromMessages() {
        assertEquals("Amazon", Today.delivery("AX-AMAZON", "Your package with Boat earbuds is out for delivery. Track: amzn.in/x", today, today)!!.from)
        assertEquals("Flipkart", Today.delivery("Flipkart", "Your order will be delivered today by 9 PM", today, today)!!.from)
        assertEquals("Blue Dart", Today.delivery("VM-BLUDRT", "Shipment 1234 via BlueDart arriving today", today, today)!!.from)
        // "Arriving tomorrow" sent yesterday counts for today.
        assertEquals("Myntra", Today.delivery("JD-MYNTRA", "Your Myntra order is arriving tomorrow", today.minusDays(1), today)!!.from)
        // Already delivered, old news, or a plain offer: not expected.
        assertNull(Today.delivery("AX-AMAZON", "Your package has been delivered. Out for delivery earlier today.", today, today))
        assertNull(Today.delivery("AX-AMAZON", "Your package is out for delivery", today.minusDays(1), today))
        assertNull(Today.delivery("AX-AMAZON", "Big sale today! Free delivery on everything", today, today))
    }

    @Test fun meetingReminderShowsOnceShortlyBefore() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val m = listOf(
            Meeting(1, "Standup", now.plusMinutes(15), now.plusMinutes(30), false, "Teams"),
            Meeting(2, "Review", now.plusHours(2), now.plusHours(3), false, ""),
            Meeting(3, "Holiday", now.minusHours(10), now.plusHours(14), true, ""),
            Meeting(4, "Earlier", now.minusMinutes(30), now.minusMinutes(5), false, ""),
        )
        val alerts = Today.meetingAlerts(m, now)
        assertEquals(1, alerts.size)
        assertEquals("Standup in 15 min", alerts[0].title)
        assertEquals("Starts at 10:15 AM, Teams.", alerts[0].text)
        assertTrue(alerts[0].id.startsWith("meet:1:"))
    }

    @Test fun spokenSummaries() {
        val t = LocalDateTime.of(2026, 11, 11, 10, 0)
        assertEquals("2 meetings today: 10 AM, Standup; 3:30 PM, Client call.",
            Today.meetingsText(listOf(Meeting(1, "Standup", t, t, false, ""), Meeting(2, "Client call", t.plusMinutes(330), t, false, "")), true))
        assertEquals("No meetings on your calendar today.", Today.meetingsText(emptyList(), true))
        assertTrue(Today.meetingsText(emptyList(), false).contains("Allow Calendar"))
        assertEquals("2 deliveries expected today, from Amazon, Flipkart.",
            Today.deliveriesText(listOf(Delivery("Amazon", "a"), Delivery("Flipkart", "b"))))
        assertEquals("No deliveries expected today.", Today.deliveriesText(emptyList()))
    }
}
