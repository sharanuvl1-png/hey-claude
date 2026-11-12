package com.personal.heyclaude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

/** WhatsApp and Gmail messages, shopping, order status and the passcode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoreFeaturesTest {
    private val day = LocalDate.of(2026, 11, 11)

    @Test fun whatsAppBankAlertsCountButFriendsDoNot() {
        val chats = Chats.parse(listOf(
            "13:05 | WhatsApp | HDFC Bank | Rs.500.00 debited from a/c **1234 on 11-11-26 to VPA swiggy@icici (UPI Ref 123)",
            "13:06 | WhatsApp | Ravi | I paid Rs 500 for dinner, send your share",
            "13:07 | Outlook | Accounts Team | Rs 9,000 debited for travel",
        ), day)
        val spends = Chats.spends(chats)
        assertEquals(1, spends.size)
        assertEquals(500.0, spends[0].amount, 0.01)
        // The same payment by SMS a few minutes earlier is counted once.
        val sms = Spend(LocalDateTime.of(2026, 11, 11, 13, 1), 500.0, "Food", "swiggy", "UPI")
        assertEquals(1, Chats.merge(listOf(sms), spends).size)
    }

    @Test fun billsFromWhatsAppAndGmailButNotOfficeMail() {
        val chats = Chats.parse(listOf(
            "09:00 | WhatsApp | BESCOM | Your electricity bill of Rs 1,240 is due on 15/11/2026.",
            "09:10 | Gmail | HDFC Life | Your policy premium of Rs 24,500 is due for renewal on 20-Nov-26",
            "09:20 | Outlook | Finance | Invoice of Rs 50,000 is due on 18/11/2026",
        ), day)
        val bills = Chats.bills(chats)
        assertEquals(listOf("BESCOM", "HDFC Life"), bills.map { it.sender })
        assertEquals(LocalDate.of(2026, 11, 20), bills[1].due)
    }

    @Test fun shoppingListAndSearchLinks() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(listOf("milk", "bread", "2 dozen eggs"), Shopping.items("milk, bread and 2 dozen eggs"))
        Shopping.add(app, listOf("milk", "bread"))
        assertEquals(listOf("milk", "bread", "eggs"), Shopping.add(app, listOf("Milk", "eggs")))
        assertEquals("Your shopping list has 3 items: milk, bread and eggs.", Shopping.listAnswer(app))
        Shopping.clear(app)
        assertEquals("Your shopping list is empty.", Shopping.listAnswer(app))
        assertEquals("https://blinkit.com/s/?q=amul%20milk", Shopping.searchUrl("Blinkit", "amul milk"))
        assertEquals("https://www.amazon.in/s?k=boat%20earbuds", Shopping.searchUrl("amazon", "boat earbuds"))
    }

    @Test fun orderStatusFromMessages() {
        val t = LocalDateTime.of(2026, 11, 11, 9, 40)
        assertEquals("out for delivery", Orders.update("messages", "AX-AMAZON", "Your package with boAt earbuds is out for delivery today", t)!!.status)
        assertEquals("shipped", Orders.update("Flipkart", "Flipkart", "Your order for Realme buds has been shipped", t)!!.status)
        assertEquals("delivered", Orders.update("blinkit", "blinkit", "Your order has been delivered. Enjoy!", t)!!.status)
        assertNull(Orders.update("messages", "AX-AMAZON", "Big sale! 50% off on earbuds, shop now", t))
        assertNull(Orders.update("Outlook", "Amazon", "Your order has been shipped", t))
        val ups = listOf(
            OrderUpdate("Amazon", "shipped", t.minusDays(1), ""),
            OrderUpdate("Amazon", "out for delivery", t, ""),
            OrderUpdate("Flipkart", "placed", t.minusDays(3), ""),
        )
        assertEquals("Amazon: out for delivery, last update at 9:40 AM today. Flipkart: placed, last update on 8 Nov.",
            Orders.answer(ups, day))
        assertEquals("I don't see any Blinkit order updates from the last 10 days.", Orders.answer(ups, day, "blinkit"))
    }

    @Test fun chatsSaveAndLoad() {
        val app = RuntimeEnvironment.getApplication()
        val entries = listOf(
            ChatEntry(true, "add milk on blinkit"),
            ChatEntry(false, "Opening Blinkit.", "Opening Blinkit.", links = listOf("milk" to "https://blinkit.com/s/?q=milk")),
            ChatEntry(true, "show chart"),
            ChatEntry(false, "Chart for TCS.", images = listOf("https://x/y.png")),
        )
        assertEquals(entries, ChatStore.fromJson(ChatStore.toJson(entries)))
        ChatStore.save(app, SavedChat(5L, entries))
        assertEquals("add milk on blinkit", ChatStore.all(app).single().title)
        assertEquals(listOf(true to "q", false to "a"),
            ChatStore.turns(listOf(ChatEntry(true, "q"), ChatEntry(false, "a"), ChatEntry(false, "oops", problem = true), ChatEntry(true, "••••••"))))
    }

    @Test fun passcodeIsCheckedNotStored() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(Passcode.isSet(app))
        Passcode.set(app, "1357 9")
        assertTrue(Passcode.check(app, "13579"))
        assertTrue(Passcode.check(app, "135 79"))
        assertFalse(Passcode.check(app, "13578"))
        val stored = app.getSharedPreferences("passcode", 0).all.values.joinToString()
        assertFalse(stored.contains("13579"))
    }

    @Test fun alertsAreAShortReadableList() {
        val items = listOf(
            "Good morning: 1 meeting. Tap for your briefing: meetings, messages, bills and stocks.",
            "Renewal due on 17 Nov: Paytm Insurance Broking renewal is due on 17 Nov.",
            "Today's stock scan: PCJEWELLER buy on dip, entry ₹13.7–15.5, stop ₹12.3, target ₹18.0. Nifty 50: Adani Ports buy on dip.",
            "Bill due tomorrow: Airtel bill of Rs. 799 is due tomorrow. Pay in the app.",
        ) + (1..6).map { "Breakout $it: Stock $it crossed its 20-day high." }
        val text = Alerts.listText(items)
        val lines = text.lines()
        assertEquals("9 alerts in the last two days:", lines[0])
        assertEquals("• Renewal due on 17 Nov: Paytm Insurance Broking renewal is due on 17 Nov", lines[1])
        assertEquals("• Today's stock scan: PCJEWELLER buy on dip, entry ₹13.7–15.5, stop ₹12.3, target ₹18.0", lines[2])
        assertEquals("• Bill due tomorrow: Airtel bill of Rs. 799 is due tomorrow", lines[3])
        assertEquals("…and 3 more.", lines.last())
        assertEquals(8, lines.size)
        assertTrue(text, !text.contains("Good morning"))
        assertEquals("No new alerts in the last two days.", Alerts.listText(listOf("Good morning: 2 meetings.")))
    }

    @Test fun alertsUseAPopUpChannel() {
        val app = RuntimeEnvironment.getApplication()
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("mehitha_alerts", "old", android.app.NotificationManager.IMPORTANCE_LOW))
        Alerts.post(app, Alert("popup:test", "Good morning", "Tap for your briefing."))
        val channel = nm.getNotificationChannel(Alerts.CHANNEL)!!
        assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(nm.getNotificationChannel("mehitha_alerts"))
        val posted = org.robolectric.Shadows.shadowOf(nm).allNotifications.single()
        assertEquals(Alerts.CHANNEL, posted.channelId)
        assertTrue(Alerts.canPopUp(app))
    }

    @Test fun dropboxSearchResultsAndOpenButtons() {
        val json = """{"matches":[
            {"metadata":{".tag":"metadata","metadata":{".tag":"file","name":"Car insurance 2026.pdf","path_display":"/Documents/Insurance/Car insurance 2026.pdf","server_modified":"2026-03-04T10:00:00Z","size":245760}}},
            {"metadata":{".tag":"metadata","metadata":{".tag":"folder","name":"Insurance","path_display":"/Documents/Insurance"}}}
        ]}"""
        val files = DropboxClient.parseSearch(json)
        assertEquals(1, files.size)
        val f = files.single()
        assertEquals("/Documents/Insurance", f.folder)
        assertEquals("2026-03-04", f.modified)
        assertEquals("https://www.dropbox.com/home/Documents/Insurance?preview=Car%20insurance%202026.pdf", f.openUrl)
        val text = PhoneTools.dropboxText(files)
        assertTrue(text, text.contains("Car insurance 2026.pdf | path /Documents/Insurance/Car insurance 2026.pdf | folder /Documents/Insurance | modified 2026-03-04 | 240 KB | open link https://www.dropbox.com/home/"))

        val reply = AssistantText.forSpeech("SPOKEN: Found your car insurance.\nYour car insurance is in Dropbox.\nOPEN: Car insurance 2026.pdf | ${f.openUrl}\n")
        assertEquals(listOf("Car insurance 2026.pdf" to f.openUrl), reply.links)
        assertEquals("Your car insurance is in Dropbox.", reply.display)
        // Not linked: says how to link, never guesses.
        assertTrue(PhoneTools(RuntimeEnvironment.getApplication()).searchDropbox("pan card").contains("aren't linked"))
    }

    @Test fun workOneDriveSearchResults() {
        val json = """{"value":[
            {"id":"01ABC","name":"Acme SOW 2026.docx","webUrl":"https://planview-my.sharepoint.com/personal/x/Documents/Acme%20SOW%202026.docx",
             "lastModifiedDateTime":"2026-10-01T09:00:00Z","size":52000,"file":{},"parentReference":{"path":"/drive/root:/Customers/Acme%20Corp"}},
            {"id":"01DEF","name":"Customers","folder":{"childCount":3},"parentReference":{"path":"/drive/root:"}}
        ]}"""
        val files = OneDriveClient.parseSearch(json)
        assertEquals(1, files.size)
        val f = files.single()
        assertEquals("/Customers/Acme Corp", f.folder)
        assertTrue(f.isOffice)
        val text = OneDriveClient.text(files)
        assertTrue(text, text.contains("Acme SOW 2026.docx | id 01ABC | folder /Customers/Acme Corp | modified 2026-10-01 | 51 KB | open link https://planview-my.sharepoint.com/"))
        assertFalse(OneDriveClient(RuntimeEnvironment.getApplication()).isLinked)
    }

    @Test fun recentMessagesListsEveryMessageNotJustMatches() {
        val chats = Chats.parse(listOf(
            "09:00 | WhatsApp | Paytm Insurance | Your policy for KA32EY7273 is due for renewal on 17 Nov",
            "09:30 | WhatsApp | Tiny Treasures | Homework: Maths notebook page 48",
            "10:00 | WhatsApp | HDFC Bank | Your OTP is 123456",
            "11:00 | Gmail | Amazon | Your order has shipped",
        ), day)
        val all = Chats.listText(chats, 5)
        assertTrue(all, all.startsWith("All 3 app messages saved in the last 5 days (oldest first):"))
        assertTrue(all, all.contains("2026-11-11 09:30 | WhatsApp | Tiny Treasures | Homework: Maths notebook page 48"))
        assertFalse(all, all.contains("123456"))
        val whatsApp = Chats.listText(chats, 5, "whatsapp")
        assertTrue(whatsApp, whatsApp.startsWith("All 2 whatsapp messages") && !whatsApp.contains("Amazon"))
        val cut = Chats.listText(chats, 5, max = 120)
        assertTrue(cut, cut.contains("only the newest 1 fit here") && cut.contains("Amazon"))
        assertTrue(Chats.listText(emptyList(), 5).startsWith("No app messages saved in the last 5 days."))
    }

    @Test fun workMailAndCalendarReadForClaude() {
        val mails = OneDriveClient.parseMail("""
            {"value":[{"id":"AAMk1","subject":"Review deck for Friday","receivedDateTime":"2026-11-12T04:30:00Z","bodyPreview":"Hi Sharan,  please   look at slide 4",
              "webLink":"https://outlook.office365.com/owa/?ItemID=AAMk1","isRead":false,"importance":"high",
              "from":{"emailAddress":{"name":"Priya","address":"priya@work.com"}}},
             {"id":"AAMk2","subject":"Your sign-in code","receivedDateTime":"2026-11-12T05:00:00Z","bodyPreview":"Your verification code is 552901",
              "isRead":false,"importance":"normal","from":{"emailAddress":{"name":"IT","address":"it@work.com"}}}]}
        """.trimIndent())
        val text = OneDriveClient.mailText("", mails)
        assertTrue(text, text.startsWith("Work Outlook email (unread, inbox) (2):"))
        assertTrue(text, text.contains("id AAMk1 | 2026-11-12 04:30 UTC | from Priya <priya@work.com> | Review deck for Friday | high importance | unread | Hi Sharan, please look at slide 4"))
        assertFalse(text, text.contains("552901"))
        assertTrue(OneDriveClient.mailText("budget", emptyList()).startsWith("No work email matches"))
        val read = OneDriveClient.readText(OneDriveClient.parseMail("""{"value":[{"id":"AAMk1","subject":"Review","receivedDateTime":"2026-11-12T04:30:00Z",
            "body":{"contentType":"text","content":"Slide 4 needs numbers.\nOTP 991122 for the portal"},"webLink":"https://outlook.office365.com/x","from":{"emailAddress":{"name":"Priya"}}}]}""").single())
        assertTrue(read, read.contains("Slide 4 needs numbers.") && read.contains("Open link https://outlook.office365.com/x"))
        assertFalse(read, read.contains("991122"))
        val events = OneDriveClient.parseEvents("""
            {"value":[{"subject":"Standup","start":{"dateTime":"2026-11-13T10:00:00.0000000"},"end":{"dateTime":"2026-11-13T10:30:00.0000000"},
              "isAllDay":false,"isCancelled":false,"location":{"displayName":"Room 4"},"organizer":{"emailAddress":{"name":"Priya"}}},
             {"subject":"Old","start":{"dateTime":"2026-11-13T12:00:00"},"end":{"dateTime":"2026-11-13T13:00:00"},"isCancelled":true}]}
        """.trimIndent())
        assertEquals("Work Outlook calendar, 2026-11-13 for 1 day (India time):\n2026-11-13 10:00 to 10:30 | Standup | Room 4 | by Priya",
            OneDriveClient.eventsText(LocalDate.of(2026, 11, 13), 1, events))
    }

    @Test fun gmailResultsReadForClaude() {
        val found = GmailClient.parseSearch(org.json.JSONObject("""
            {"messages":[{"id":"1a1380ae1d0c5808","from":"credit_cards@icici.bank.in","subject":"Amazon Pay ICICI Bank Credit Card Statement for the period September 13, 2026 to October 12, 2026",
              "date":"Wed, 14 Oct 2026 07:03:04 +0530","snippet":"Payment due by October 30, 2026 Total Amount Due: &#8377;2897.10 &amp; more"},
             {"id":"2b","from":"alerts@bank.in","subject":"Your login","date":"Thu","snippet":"Your OTP is 482913 for login"}]}
        """.trimIndent()))
        val text = GmailClient.text("statement newer_than:2m", found)
        assertTrue(text, text.contains("id 1a1380ae1d0c5808 | Wed, 14 Oct 2026") && text.contains("September 13, 2026 to October 12, 2026"))
        assertTrue(text, text.contains("& more"))
        assertFalse(text, text.contains("482913"))
        val mail = GmailClient.parseMail(org.json.JSONObject("""
            {"id":"1a","from":"Statements@sbicard.com","subject":"Monthly Statement","date":"9 Nov","body":"Statement date 07 Nov 2026\nNever share your OTP 123456 with anyone\nDue 28 Nov","attachments":["statement.pdf"]}
        """.trimIndent()))
        val read = GmailClient.mailText(mail)
        assertTrue(read, read.contains("Statement date 07 Nov 2026") && read.contains("Due 28 Nov") && read.contains("statement.pdf"))
        assertTrue(read, read.contains("Open link https://mail.google.com/mail/u/0/#all/1a"))
        assertFalse(read, read.contains("123456"))
        assertTrue(GmailClient.text("x", emptyList()).startsWith("No Gmail messages match"))
    }

    @Test fun remindersRingLikeAnAlarmAndGoInTheClockApp() {
        val app = RuntimeEnvironment.getApplication()
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        Alerts.post(app, Alert("remind:test", "Reminder", "Join meeting"), alarm = true)
        val posted = org.robolectric.Shadows.shadowOf(nm).allNotifications.single()
        assertEquals(Alerts.ALARM_CHANNEL, posted.channelId)
        assertTrue(posted.flags and android.app.Notification.FLAG_INSISTENT != 0)
        assertEquals(android.media.AudioAttributes.USAGE_ALARM, nm.getNotificationChannel(Alerts.ALARM_CHANNEL)!!.audioAttributes.usage)

        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val now = LocalDateTime.of(2026, 11, 12, 16, 30)
        assertTrue(Reminders.clockAlarm(activity, ReminderNote(LocalDateTime.of(2026, 11, 12, 19, 55), "Join meeting"), now))
        val started = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(android.provider.AlarmClock.ACTION_SET_ALARM, started.action)
        assertEquals(19, started.getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, -1))
        assertEquals(55, started.getIntExtra(android.provider.AlarmClock.EXTRA_MINUTES, -1))
        assertEquals("Join meeting", started.getStringExtra(android.provider.AlarmClock.EXTRA_MESSAGE))
        // Further than a day away, or from the background: notification only.
        assertFalse(Reminders.clockAlarm(activity, ReminderNote(LocalDateTime.of(2026, 11, 14, 9, 0), "Pay rent"), now))
        assertFalse(Reminders.clockAlarm(app, ReminderNote(LocalDateTime.of(2026, 11, 12, 19, 55), "Join meeting"), now))
    }
}
