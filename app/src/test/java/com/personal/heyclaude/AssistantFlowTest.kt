package com.personal.heyclaude

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.TextView
import com.personal.heyclaude.FakeApi.Companion.text
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * Plays the whole "Hi Mehitha" conversation against a fake Claude API, with
 * Robolectric standing in for the phone's microphone and speaker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantFlowTest {

    private lateinit var app: Application
    private var api: FakeApi? = null
    private var controller: ActivityController<AssistantActivity>? = null

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        // Pretend the phone has a speech-to-text engine, as the Google app provides.
        val engine = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = "com.google.android.tts"; name = "Recognizer" }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), engine)
        ShadowSpeechRecognizer.reset()
        ShadowTextToSpeech.reset()
        AssistantActivity.tools = FakeTools()
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        api?.stop()
        AssistantActivity.apiBaseUrl = null
        AssistantActivity.tools = null
        VoiceHelper.pending = null
        AssistantActivity.phoneData = null
    }

    private fun start(key: String = "sk-ant-test", replies: List<Pair<Int, String>> = emptyList()): AssistantActivity {
        AssistantActivity.saveApiKey(app, key)
        if (replies.isNotEmpty()) {
            api = FakeApi(replies)
            AssistantActivity.apiBaseUrl = api!!.url
        }
        controller = Robolectric.buildActivity(AssistantActivity::class.java).setup()
        // The speech engine finishes starting up.
        ShadowTextToSpeech.getLastTextToSpeechInstance()?.let { shadowOf(it).onInitListener.onInit(TextToSpeech.SUCCESS) }
        idle()
        return controller!!.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun spoken(): List<String> =
        ShadowTextToSpeech.getLastTextToSpeechInstance()?.let { shadowOf(it).spokenTextList } ?: emptyList()

    private fun status(a: AssistantActivity) = field<TextView>(a, "status").text.toString()
    private fun answerShown(a: AssistantActivity) = field<View>(a, "answerBox").visibility == View.VISIBLE
    private fun answerText(a: AssistantActivity) = field<TextView>(a, "answer").text.toString()

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(a: AssistantActivity, name: String): T =
        AssistantActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a) as T

    /** The mic hears [words]. Returns the recogniser that was listening. */
    private fun hear(words: String?): SpeechRecognizer {
        val r = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        if (words == null) shadowOf(r).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        else shadowOf(r).triggerOnResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(words))
        })
        idle()
        return r
    }

    /** Says a question, then pauses long enough for Mehitha to send it. */
    private fun ask(words: String) {
        hear(words)
        hear(null)
    }

    /** Waits for the background Claude call to come back. */
    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            idle()
            if (check()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    @Test fun greetsThenListensInIndianEnglish() {
        val a = start(replies = listOf(200 to text("x")))
        assertEquals("How can I help you today?", spoken().first())
        val intent = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).lastRecognizerIntent
        assertEquals("en-IN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(status(a).startsWith("How can I help you today?"))
    }

    @Test fun answersThenReadsWhenToldYes() {
        val a = start(replies = listOf(200 to text("Your bus leaves at 6:30 PM from Majestic.")))
        ask("when is my bus")
        waitFor("the answer") { answerShown(a) && status(a).startsWith("Shall I read it out?") }
        assertEquals("Your bus leaves at 6:30 PM from Majestic.", answerText(a))
        assertTrue(api!!.requests.single().contains("when is my bus"))
        // It asks first and does not read the answer on its own.
        assertEquals("I found the answer. Shall I read it out?", spoken().last())
        assertFalse(spoken().contains("Your bus leaves at 6:30 PM from Majestic."))

        hear("yes please")
        assertEquals("Your bus leaves at 6:30 PM from Majestic.", spoken().last())
    }

    @Test fun staysQuietWhenToldNo() {
        val a = start(replies = listOf(200 to text("Nifty closed higher today.")))
        ask("how did the market do")
        waitFor("the answer") { answerShown(a) }
        hear("no thanks")
        assertEquals("Okay.", spoken().last())
        assertFalse(spoken().contains("Nifty closed higher today."))
        assertTrue(answerShown(a)) // still on screen to read
    }

    @Test fun asksAgainWhenReplyIsUnclear() {
        val a = start(replies = listOf(200 to text("It is sunny.")))
        ask("weather in Bangalore")
        waitFor("the answer") { answerShown(a) }
        val first = hear("hmm")
        assertEquals("Please say yes or no.", spoken().last())
        assertNotSame(first, ShadowSpeechRecognizer.getLatestSpeechRecognizer()) // listening again
        hear("yes")
        assertEquals("It is sunny.", spoken().last())
    }

    @Test fun retriesOnceWhenNothingHeard() {
        val a = start(replies = listOf(200 to text("x")))
        hear(null)
        assertEquals("Sorry, I didn't catch that. Please say it again.", spoken().last())
        hear(null)
        assertEquals("Tap Ask to try again.", status(a))
        assertTrue(api!!.requests.isEmpty())
    }

    @Test fun followUpKeepsTheConversation() {
        val a = start(replies = listOf(200 to text("First answer."), 200 to text("Second answer.")))
        ask("first question")
        waitFor("first answer") { answerText(a) == "First answer." }
        hear("no")
        field<View>(a, "buttons").let { assertEquals(View.VISIBLE, it.visibility) }
        // Tap "Ask" for another question.
        (field<android.widget.LinearLayout>(a, "buttons").getChildAt(0)).performClick()
        idle()
        ask("second question")
        waitFor("second answer") { answerText(a) == "Second answer." }
        assertTrue(api!!.requests[1].contains("First answer.") && api!!.requests[1].contains("second question"))
    }

    @Test fun saysWhenTheKeyIsWrong() {
        val a = start(replies = listOf(401 to FakeApi.BAD_KEY))
        ask("anything")
        waitFor("the error") { status(a).startsWith("Claude didn't accept") }
        assertEquals("Claude didn't accept the API key. Please check it in the Mehitha app.", spoken().last())
    }

    @Test fun noKeyHandsQuestionToClaudeApp() {
        android.provider.Settings.Secure.putString(app.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "${app.packageName}/.VoiceHelper")
        val a = start(key = "")
        assertEquals("How can I help you today?", spoken().first())
        ask("when is my bus")
        assertEquals("when is my bus", VoiceHelper.pending)
        assertEquals("Asking Claude.", spoken().last())
        val opened = shadowOf(app).nextStartedActivity
        assertTrue(opened.toUri(0).contains(LaunchClaudeActivity.CLAUDE_PACKAGE))
        assertTrue(a.isFinishing)
    }

    @Test fun noKeyAndNoHelperCopiesQuestion() {
        start(key = "")
        ask("when is my bus")
        val clip = app.getSystemService(android.content.ClipboardManager::class.java).primaryClip
        assertEquals("when is my bus", clip!!.getItemAt(0).text.toString())
        assertTrue(spoken().last().startsWith("I copied your question. To let me type it into Claude"))
    }

    private fun startWithReply(reply: String?): AssistantActivity {
        AssistantActivity.saveApiKey(app, "")
        val i = Intent(app, AssistantActivity::class.java)
            .putExtra(AssistantActivity.EXTRA_QUESTION, "when is my bus")
            .putExtra(AssistantActivity.EXTRA_REPLY, reply)
        controller = Robolectric.buildActivity(AssistantActivity::class.java, i).setup()
        ShadowTextToSpeech.getLastTextToSpeechInstance()?.let { shadowOf(it).onInitListener.onInit(TextToSpeech.SUCCESS) }
        idle()
        return controller!!.get()
    }

    @Test fun claudeAppReplyIsOfferedThenRead() {
        val a = startWithReply("Your bus leaves at 6:30 PM.")
        assertEquals("Your bus leaves at 6:30 PM.", answerText(a))
        assertEquals("I found the answer. Shall I read it out?", spoken().last())
        assertFalse(spoken().contains("How can I help you today?"))
        hear("yes")
        assertEquals("Your bus leaves at 6:30 PM.", spoken().last())
    }

    @Test fun claudeAppReplyMissingSaysSo() {
        val a = startWithReply(null)
        assertEquals("I couldn't read Claude's reply. Please look in the Claude app.", spoken().last())
        assertTrue(status(a).startsWith("I couldn't read"))
    }

    @Test fun pauseInTheMiddleKeepsListening() {
        val a = start(replies = listOf(200 to text("At 6:30 PM.")))
        hear("when is my bus")
        // A short pause ended the first part; Mehitha is still listening for more.
        assertTrue(api!!.requests.isEmpty())
        assertEquals("“when is my bus”", field<TextView>(a, "heard").text.toString())
        hear("to Mysore tomorrow")
        hear(null)
        waitFor("the answer") { answerShown(a) }
        assertTrue(api!!.requests.single().contains("when is my bus to Mysore tomorrow"))
    }

    @Test fun sendsAfterNoMoreSpeech() {
        val a = start(replies = listOf(200 to text("At 6:30 PM.")))
        hear("when is my bus")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(AssistantActivity.MORE_SPEECH_MS + 100))
        waitFor("the answer") { answerShown(a) }
        assertTrue(api!!.requests.single().contains("when is my bus"))
    }

    @Test fun readsTheCasualGistNotTheWholeText() {
        val a = start(replies = listOf(200 to text("SPOKEN: Your bus is at half past six tonight.\\nredBus TVBN78163260 leaves Majestic at 6:30 PM, seat 14.")))
        ask("when is my bus")
        waitFor("the answer") { answerShown(a) }
        assertEquals("redBus TVBN78163260 leaves Majestic at 6:30 PM, seat 14.", answerText(a))
        hear("yes")
        assertEquals("Your bus is at half past six tonight.", spoken().last())
    }

    // ---- Top 10 features --------------------------------------------------------------------

    private inner class FakePhone(val spends: List<Spend> = emptyList(), val scanMd: String = "") : PhoneData(RuntimeEnvironment.getApplication()) {
        override val canReadSms get() = true
        override fun spends(days: Long) = spends
        override fun bills(days: Long) = listOf(Bill("ICICIB", 12400.0, PhoneData.today().plusDays(2), "due"))
        override fun scan() = scanMd
        override fun messagesToday(max: Int) = "09:10 WhatsApp, Ravi: Are we meeting at 6?"
        override fun files(question: String) = "Download/Car_Insurance_Policy_2026.pdf (210 KB, 14 Apr 2026)"
    }

    private val scanMd = """
        # Mehitha daily stock scan, 2026-10-26

        ## Watchlist

        | Group | Stock | Close | Verdict | Grade | Entry | Stop-loss (risk) | Target 1 | Chart |
        |---|---|---|---|---|---|---|---|---|
        | Nifty 50 | HCL Technologies Limited | ₹1417.0 | 🟢 BUY NOW | A | ₹1385.9–1406.1 | ₹1162.7 (17%) | ₹1659.4 | HCLTECH/2026-10-26.png |
    """.trimIndent()

    private fun tapAsk(a: AssistantActivity) {
        field<android.widget.LinearLayout>(a, "buttons").getChildAt(0).performClick()
        idle()
    }

    @Test fun remembersAndRecalls() {
        val a = start(replies = listOf(200 to text("x")))
        ask("remember I parked on level 3")
        assertEquals("Okay, I'll remember that: I parked on level 3.", spoken().last())
        tapAsk(a)
        ask("where did I park")
        assertTrue(spoken().last(), spoken().last().contains("you told me: I parked on level 3."))
        assertTrue(api!!.requests.isEmpty()) // answered on the phone, not by Claude
    }

    @Test fun moneyQuestionSendsPhoneFacts() {
        val now = PhoneData.now()
        AssistantActivity.phoneData = FakePhone(spends = listOf(Spend(now.minusDays(1), 900.0, "Food", "Swiggy", "UPI")))
        val a = start(replies = listOf(200 to text("SPOKEN: About 900 rupees on Swiggy.\\nYou spent 900 rupees on Swiggy this month.")))
        ask("how much did I spend on Swiggy this month")
        waitFor("the answer") { answerShown(a) }
        val sent = api!!.requests.single()
        assertTrue(sent.contains("how much did I spend on Swiggy this month"))
        assertTrue(sent.contains("Spends matching swiggy"))
        assertTrue(sent.contains("Swiggy 900 rupees"))
        hear("yes")
        assertEquals("About 900 rupees on Swiggy.", spoken().last())
    }

    @Test fun briefingIncludesBillsAndScan() {
        AssistantActivity.phoneData = FakePhone(scanMd = scanMd)
        val a = start(replies = listOf(200 to text("SPOKEN: Two meetings and a bill today.")))
        ask("good morning")
        waitFor("the answer") { answerShown(a) }
        val sent = api!!.requests.single()
        assertTrue(sent.contains("morning briefing"))
        assertTrue(sent.contains("ICICIB 12,400 rupees due"))
        assertTrue(sent.contains("HCL Technologies Limited 1417.0 rupees BUY NOW"))
    }

    @Test fun stockCheckUsesScanAndWeb() {
        AssistantActivity.phoneData = FakePhone(scanMd = scanMd)
        val a = start(replies = listOf(200 to text("HCL is in an uptrend.")))
        ask("check HCL Technologies")
        waitFor("the answer") { answerShown(a) }
        val sent = api!!.requests.single()
        assertTrue(sent.contains("Quick stock check on HCL Technologies"))
        assertTrue(sent.contains("verdict BUY NOW"))
        assertTrue(sent.contains("web_search"))
    }

    @Test fun reminderFromClaudeIsSet() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val a = start(replies = listOf(200 to text("SPOKEN: Done, I'll remind you.\\nREMIND: 2030-01-01 18:00 | Call Ravi")))
        ask("remind me to call Ravi on new year's day at 6 pm")
        waitFor("the answer") { answerShown(a) }
        assertTrue(api!!.requests.single().contains("REMIND: YYYY-MM-DD HH:MM"))
        assertTrue(answerText(a), answerText(a).contains("Reminder set for 1 Jan, 6:00 PM: Call Ravi"))
        assertFalse(answerText(a).contains("REMIND:"))
        val work = androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork("remind:2030-01-01T18:00:${"Call Ravi".hashCode()}").get()
        assertEquals(1, work.size)
    }

    @Test fun alertsAreListedAndTapOpensTheAnswer() {
        Alerts.post(app, Alert("bill:test", "Bill due tomorrow", "Airtel bill of 799 rupees is due tomorrow."))
        assertFalse(Alerts.post(app, Alert("bill:test", "Bill due tomorrow", "again"))) // only once
        AssistantActivity.saveApiKey(app, "sk-ant-test")
        val i = Intent(app, AssistantActivity::class.java).putExtra(AssistantActivity.EXTRA_ASK, "any alerts")
        controller = Robolectric.buildActivity(AssistantActivity::class.java, i).setup()
        ShadowTextToSpeech.getLastTextToSpeechInstance()?.let { shadowOf(it).onInitListener.onInit(TextToSpeech.SUCCESS) }
        idle()
        assertEquals("You have 1 alert. Bill due tomorrow: Airtel bill of 799 rupees is due tomorrow.", spoken().last())
    }

    @Test fun noKeySendsTheFullPromptButShowsWhatYouSaid() {
        android.provider.Settings.Secure.putString(app.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "${app.packageName}/.VoiceHelper")
        AssistantActivity.phoneData = FakePhone(scanMd = scanMd)
        start(key = "")
        ask("catch me up")
        waitFor("the hand-off") { VoiceHelper.pending != null }
        assertTrue(VoiceHelper.pending!!.startsWith("Catch me up on what I missed today."))
        assertTrue(VoiceHelper.pending!!.contains("Ravi: Are we meeting at 6?"))
    }

    @Test fun stockAlertsFileIsRead() {
        val json = """{"date":"2026-10-26","alerts":[{"id":"target:HCLTECH:2026-10-26","title":"HCL Technologies reached its target","text":"HCL closed at 1,670."}]}"""
        assertEquals(listOf(Alert("target:HCLTECH:2026-10-26", "HCL Technologies reached its target", "HCL closed at 1,670.")),
            AlertWorker.parseStockAlerts(json, "2026-10-26"))
        assertTrue(AlertWorker.parseStockAlerts(json, "2026-10-27").isEmpty()) // yesterday's alerts are not repeated
    }
}
