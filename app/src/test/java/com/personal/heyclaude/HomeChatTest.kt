package com.personal.heyclaude

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.personal.heyclaude.FakeApi.Companion.text
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** The chat home screen: typed and spoken questions, answers in bubbles, alerts kept scheduled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeChatTest {

    private lateinit var app: Application
    private var api: FakeApi? = null
    private var controller: ActivityController<MainActivity>? = null

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
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
    }

    private fun open(key: String = "sk-ant-test", replies: List<Pair<Int, String>> = emptyList(), intent: Intent? = null): MainActivity {
        AssistantActivity.saveApiKey(app, key)
        if (replies.isNotEmpty()) {
            api = FakeApi(replies)
            AssistantActivity.apiBaseUrl = api!!.url
        }
        controller = (if (intent != null) Robolectric.buildActivity(MainActivity::class.java, intent)
                      else Robolectric.buildActivity(MainActivity::class.java)).setup()
        ShadowTextToSpeech.getLastTextToSpeechInstance()?.let { shadowOf(it).onInitListener.onInit(TextToSpeech.SUCCESS) }
        idle()
        return controller!!.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun spoken(): List<String> =
        ShadowTextToSpeech.getLastTextToSpeechInstance()?.let { shadowOf(it).spokenTextList } ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(a: MainActivity, name: String): T =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a) as T

    /** Every bit of text in the conversation, in order. */
    private fun chat(a: MainActivity): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(field<ViewGroup>(a, "messages"))
        return out
    }

    private fun type(a: MainActivity, words: String) {
        field<EditText>(a, "input").setText(words)
        field<View>(a, "send").performClick()
        idle()
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            idle()
            if (check()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    @Test fun typedQuestionIsAnsweredInTheChat() {
        val a = open(replies = listOf(200 to text("Your bus leaves at 6:30 PM.")))
        assertTrue(chat(a).contains("How can I help you today?"))
        type(a, "when is my bus")
        waitFor("the answer") { chat(a).contains("Your bus leaves at 6:30 PM.") }
        assertTrue(chat(a).contains("when is my bus"))
        assertFalse(chat(a).contains("How can I help you today?")) // the welcome makes way for the chat
        assertFalse(chat(a).contains("Thinking…"))
        assertEquals("", field<EditText>(a, "input").text.toString())
        // Typed questions are not read out unless you tap Read aloud.
        assertFalse(spoken().contains("Your bus leaves at 6:30 PM."))
    }

    @Test fun followUpKeepsTheConversation() {
        val a = open(replies = listOf(200 to text("First answer."), 200 to text("Second answer.")))
        type(a, "first question")
        waitFor("first answer") { chat(a).contains("First answer.") }
        type(a, "second question")
        waitFor("second answer") { chat(a).contains("Second answer.") }
        assertTrue(api!!.requests[1].contains("First answer.") && api!!.requests[1].contains("second question"))
    }

    @Test fun newChatStartsFresh() {
        val a = open(replies = listOf(200 to text("First answer."), 200 to text("Fresh answer.")))
        type(a, "first question")
        waitFor("first answer") { chat(a).contains("First answer.") }
        a.javaClass.getDeclaredMethod("newChat").apply { isAccessible = true }.invoke(a)
        assertTrue(chat(a).contains("How can I help you today?"))
        type(a, "another question")
        waitFor("fresh answer") { chat(a).contains("Fresh answer.") }
        assertFalse(api!!.requests[1].contains("First answer."))
    }

    @Test fun chatIsStillThereAfterReopeningAndKeepsItsContext() {
        val a = open(replies = listOf(200 to text("Your bus leaves at 6:30 PM."), 200 to text("Platform 4.")))
        type(a, "when is my bus")
        waitFor("the answer") { chat(a).contains("Your bus leaves at 6:30 PM.") }
        controller!!.pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val b = controller!!.get()
        idle()
        assertTrue(chat(b).contains("when is my bus") && chat(b).contains("Your bus leaves at 6:30 PM."))
        type(b, "which platform")
        waitFor("the follow-up") { chat(b).contains("Platform 4.") }
        assertTrue(api!!.requests[1].contains("Your bus leaves at 6:30 PM.") && api!!.requests[1].contains("which platform"))
        assertEquals(1, ChatStore.all(app).size)
    }

    @Test fun saysWhatIsMissingInsteadOfAskingClaudeWithNoData() {
        val a = open()
        type(a, "spending report")
        // Nothing is allowed here: the voice build says to install the full version, the full build says what to allow.
        assertTrue(chat(a).any { (it.contains("can't read your SMS or WhatsApp") && it.contains("full version")) || it.contains("Allow \"Text messages\"") })
        type(a, "what did I miss")
        assertTrue(chat(a).any { it.contains("can't see WhatsApp messages") || it.contains("can't see your WhatsApp messages yet") })
        assertEquals(null, api)
    }

    @Test fun spokenQuestionIsReadAloud() {
        val a = open(replies = listOf(200 to text("Nifty closed higher today.")))
        field<View>(a, "mic").performClick()
        idle()
        val r = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        shadowOf(r).triggerOnResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("how did the market do"))
        })
        idle()
        waitFor("the answer") { chat(a).contains("Nifty closed higher today.") }
        assertEquals("Nifty closed higher today.", spoken().last())
    }

    @Test fun showsAChartWithoutAsking() {
        val a = open()
        type(a, "show chart of PC Jeweller")
        waitFor("the chart") { chat(a).any { it.startsWith("Chart for PCJEWELLER made on 2026-11-10.") } }
        assertEquals(null, api) // straight from the charts, no Claude call
        waitFor("the picture to give up") { chat(a).any { it.startsWith("Couldn't load the picture") } }
    }

    @Test fun usesTheChosenModel() {
        val a = open(replies = listOf(200 to text("Quick answer.")))
        type(a, "hello")
        waitFor("the answer") { chat(a).contains("Quick answer.") }
        assertTrue(api!!.requests[0].contains("claude-haiku-5-5"))
        AssistantActivity.saveModel(app, "claude-opus-5-5")
        api!!.stop()
        api = FakeApi(listOf(200 to text("Deep answer.")))
        AssistantActivity.apiBaseUrl = api!!.url
        type(a, "again")
        waitFor("the answer") { chat(a).contains("Deep answer.") }
        assertTrue(api!!.requests[0].contains("claude-opus-5-5"))
    }

    @Test fun answersWhatsOnTodayByItself() {
        val a = open()
        type(a, "what's on today")
        assertTrue(chat(a).any { it.contains("calendar") && it.contains("deliveries", ignoreCase = true) })
        assertEquals(null, api)
    }

    @Test fun asksForThePasscodeBeforeConfidentialDetails() {
        val a = open(replies = listOf(200 to text("PASSCODE_NEEDED"), 200 to text("Your PAN card is in Dropbox, number on file.")))
        Passcode.set(app, "4321")
        type(a, "what is my PAN number")
        waitFor("the passcode question") { chat(a).contains(Passcode.ASK) }
        type(a, "43 21")
        waitFor("the answer") { chat(a).contains("Your PAN card is in Dropbox, number on file.") }
        assertFalse(chat(a).contains("43 21")) // never shown
        assertFalse(api!!.requests[0].contains("said the right passcode"))
        assertTrue(api!!.requests[1].contains("said the right passcode"))
        assertFalse(api!!.requests.any { it.contains("4321") })
    }

    @Test fun aPasscodeTypedWithoutBeingAskedNeverReachesClaude() {
        val a = open(replies = listOf(200 to text("Please say your Mehitha passcode, and I'll pull them up."),
            200 to text("Your card statement comes on the 5th.")))
        Passcode.set(app, "4321")
        type(a, "my card billing cycle")
        waitFor("the passcode question") { chat(a).contains(Passcode.ASK) }
        type(a, "hello")
        assertTrue(chat(a).contains(Passcode.WRONG))
        type(a, "4321")
        waitFor("the answer") { chat(a).contains("Your card statement comes on the 5th.") }
        assertFalse(chat(a).contains("4321"))
        assertFalse(api!!.requests.any { it.contains("4321") })
        assertTrue(api!!.requests.last().contains("said the right passcode"))
    }

    @Test fun wrongPasscodeSharesNothing() {
        val a = open(replies = listOf(200 to text("PASSCODE_NEEDED")))
        Passcode.set(app, "4321")
        type(a, "what is my PAN number")
        waitFor("the passcode question") { chat(a).contains(Passcode.ASK) }
        type(a, "1111")
        assertTrue(chat(a).contains(Passcode.WRONG))
        assertEquals(1, api!!.requests.size)
    }

    @Test fun answersAlertsByItself() {
        val a = open()
        type(a, "any alerts")
        assertTrue(chat(a).size >= 2)
        assertEquals(null, api)
    }

    @Test fun showsAProblemPlainly() {
        val a = open(replies = listOf(401 to FakeApi.BAD_KEY))
        type(a, "anything")
        waitFor("the error") { chat(a).contains("Claude didn't accept the API key. Please check it in Settings.") }
    }

    @Test fun tappedAlertAsksStraightAway() {
        val i = Intent(app, MainActivity::class.java).putExtra(AssistantActivity.EXTRA_ASK, "any alerts")
        val a = open(intent = i)
        assertTrue(chat(a).contains("any alerts"))
    }

    @Test fun tappedAlertCanReadTheAnswerAloud() {
        val i = Intent(app, MainActivity::class.java)
            .putExtra(AssistantActivity.EXTRA_ASK, "how did the market do")
            .putExtra(MainActivity.EXTRA_SPEAK, true)
        AssistantActivity.saveApiKey(app, "sk-ant-test")
        api = FakeApi(listOf(200 to text("Nifty closed higher today.")))
        AssistantActivity.apiBaseUrl = api!!.url
        controller = Robolectric.buildActivity(MainActivity::class.java, i).setup()
        ShadowTextToSpeech.getLastTextToSpeechInstance()?.let { shadowOf(it).onInitListener.onInit(TextToSpeech.SUCCESS) }
        val a = controller!!.get()
        waitFor("the answer") { chat(a).contains("Nifty closed higher today.") }
        assertEquals("Nifty closed higher today.", spoken().last())
    }

    @Test fun openedAsPhoneAssistantStartsListening() {
        val a = open(intent = Intent(Intent.ACTION_ASSIST).setClass(app, MainActivity::class.java))
        assertTrue(field<Boolean>(a, "listening"))
        assertEquals(Intent.ACTION_MAIN, a.intent.action) // not again on a screen rotation
    }

    @Test fun keepsTheBreakoutAlertsScheduled() {
        open()
        val wm = WorkManager.getInstance(app)
        assertEquals(1, wm.getWorkInfosForUniqueWork("mehitha-breakouts").get().size)
        assertEquals(1, wm.getWorkInfosForUniqueWork("mehitha-alerts").get().size)
        assertEquals(1, wm.getWorkInfosForUniqueWork("mehitha-today").get().size)
    }
}
