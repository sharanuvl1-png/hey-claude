package com.personal.heyclaude

import android.Manifest
import android.app.Application
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
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
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.LocalDateTime

/**
 * Every suggestion chip on the home screen, asked in one chat with phone data present:
 * each must get a real answer, never an error, a "can't read" note or a hang.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartersTest {
    private lateinit var app: Application
    private var api: FakeApi? = null
    private var controller: ActivityController<MainActivity>? = null

    /** A phone with a bit of everything: spends, bills, scan, messages, files, an order. */
    private inner class FullPhone : PhoneData(RuntimeEnvironment.getApplication()) {
        private val now = PhoneData.now()
        override val canReadSms get() = true
        override val canReadMessages get() = true
        override fun spends(days: Long) = listOf(
            Spend(now.minusDays(1), 450.0, "Food", "Swiggy", "UPI"),
            Spend(now.minusDays(3), 2300.0, "Groceries", "Blinkit", "UPI"),
            Spend(now.minusDays(40), 1800.0, "Food", "Zomato", "Card"),
        )
        override fun bills(days: Long) = listOf(Bill("BESCOM", 1240.0, PhoneData.today().plusDays(3), "due"))
        override fun scan() = """
            # Mehitha daily stock scan, 2026-11-11

            ## Watchlist

            | Group | Stock | Close | Verdict | Grade | Entry | Stop-loss (risk) | Target 1 | Chart |
            |---|---|---|---|---|---|---|---|---|
            | Nifty 50 | Tata Motors Limited | ₹700.0 | 🟢 BUY NOW | A | ₹690–700 | ₹650 (7%) | ₹760 | TATAMOTORS/2026-11-11.png |
        """.trimIndent()
        override fun messagesToday(max: Int) = "09:10 WhatsApp, Ravi: Are we meeting at 6?"
        override fun files(question: String) = "Download/Car_Insurance_Policy_2026.pdf (210 KB, 14 Apr 2026)"
        override fun recentSms(days: Long) = listOf(Triple(now.minusHours(2), "AX-AMAZON", "Your package with boAt earbuds is out for delivery today"))
        override fun chats(days: Long) = listOf(Chat(now.minusHours(1), "Flipkart", "Flipkart", "Your order for Realme buds has been shipped"))
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALENDAR)
        ShadowTextToSpeech.reset()
        AssistantActivity.tools = FakeTools()
        AssistantActivity.phoneData = FullPhone()
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        api?.stop()
        AssistantActivity.apiBaseUrl = null
        AssistantActivity.tools = null
        AssistantActivity.phoneData = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(a: MainActivity, name: String): T =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a) as T

    private fun chat(a: MainActivity, withWelcome: Boolean = true): List<String> {
        val out = mutableListOf<String>()
        val welcome = field<View>(a, "welcome")
        fun walk(v: View) {
            if (!withWelcome && v === welcome) return
            if (v is TextView) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(field<ViewGroup>(a, "messages"))
        return out
    }

    /** The bubbles only (Mehitha's answers and your questions), without Read aloud, Copy and shop buttons. */
    private fun answers(a: MainActivity) = chat(a, withWelcome = false).filter { it != "🔊 Read aloud" && it != "Copy" && !it.startsWith("🛒 ") }

    private val bad = listOf("Something went wrong", "can't read", "can't see", "full version", "isn't linked",
        "couldn't reach", "Sorry", "Thinking…", "didn't accept", "Please check")

    @Test fun everyStarterGetsARealAnswer() {
        AssistantActivity.saveApiKey(app, "sk-ant-test")
        api = FakeApi((1..20).map { 200 to text("Claude answer $it.") })
        AssistantActivity.apiBaseUrl = api!!.url
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowTextToSpeech.getLastTextToSpeechInstance()?.let { shadowOf(it).onInitListener.onInit(TextToSpeech.SUCCESS) }
        idle()
        val a = controller!!.get()
        // Starters that end in a space wait for you to finish the sentence; these finish them.
        val finish = mapOf("check " to "check Tata Motors", "show chart of " to "show chart of PC Jeweller",
            "remind me " to "remind me to call Ravi tomorrow at 6 pm", "find my " to "find my car insurance policy")
        val failures = mutableListOf<String>()
        for ((label, question) in MainActivity.SUGGESTIONS) {
            val q = finish[question] ?: question
            val before = answers(a).size
            field<EditText>(a, "input").setText(q)
            field<View>(a, "send").performClick()
            val until = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < until) {
                idle()
                if (!field<Boolean>(a, "busy") && answers(a).size >= before + 2) break
                Thread.sleep(30)
            }
            val reply = answers(a).drop(before + 1).joinToString(" | ")
            if (reply.isBlank() || bad.any { reply.contains(it) }) failures += "$label ($q) → \"$reply\""
        }
        assertTrue("Starters without a real answer:\n" + failures.joinToString("\n"), failures.isEmpty())
        // The chat with all those answers is saved and the welcome is gone.
        assertTrue(ChatStore.all(app).single().entries.size >= MainActivity.SUGGESTIONS.size * 2)
        assertFalse(chat(a).contains("How can I help you today?"))
    }

    @Test fun localStartersNeedNoClaude() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val a = controller!!.get()
        for (q in listOf("what's on today", "any alerts", "what's on my shopping list", "where is my order")) {
            field<EditText>(a, "input").setText(q)
            field<View>(a, "send").performClick()
            idle()
        }
        val all = chat(a).joinToString("\n")
        assertTrue(all, all.contains("Amazon: out for delivery") && all.contains("Flipkart: shipped"))
        assertTrue(all, all.contains("Your shopping list is empty."))
    }

    @Test fun aQuestionAskedWhileBusyIsAnsweredNext() {
        AssistantActivity.saveApiKey(app, "sk-ant-test")
        api = FakeApi.ok(text("First answer."), text("Second answer."))
        AssistantActivity.apiBaseUrl = api!!.url
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val a = controller!!.get()
        field<EditText>(a, "input").setText("who called me yesterday")
        field<View>(a, "send").performClick()
        assertTrue(field<Boolean>(a, "busy"))
        field<EditText>(a, "input").setText("what is my next bill")
        field<View>(a, "send").performClick()
        assertEquals("", field<EditText>(a, "input").text.toString())
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            idle()
            if (!field<Boolean>(a, "busy") && answers(a).contains("Second answer.")) break
            Thread.sleep(30)
        }
        val shown = answers(a).filter { it in listOf("who called me yesterday", "First answer.", "what is my next bill", "Second answer.") }
        assertEquals(listOf("who called me yesterday", "First answer.", "what is my next bill", "Second answer."), shown)
    }

    @Test fun usualGroceriesGiveABlinkitButtonPerItem() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val a = controller!!.get()
        for (q in listOf("add milk, eggs and bread to my usuals", "my usuals")) {
            field<EditText>(a, "input").setText(q)
            field<View>(a, "send").performClick()
            idle()
        }
        val all = chat(a)
        assertTrue(all.joinToString("\n"), all.any { it.startsWith("Saved milk, eggs and bread to your usuals") })
        assertTrue(all.joinToString("\n"), all.containsAll(listOf("🛒 milk", "🛒 eggs", "🛒 bread", MainActivity.SOMETHING_ELSE)))
        // "Something else" puts "blinkit " in the box to type the extra item.
        all.indexOf(MainActivity.SOMETHING_ELSE).let { _ ->
            fun find(v: View): View? = if (v is TextView && v.text.toString() == MainActivity.SOMETHING_ELSE) v
                else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            find(field<ViewGroup>(a, "messages"))!!.performClick()
        }
        assertEquals("blinkit ", field<EditText>(a, "input").text.toString())
    }
}
