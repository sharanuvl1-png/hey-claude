package com.personal.heyclaude

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Mehitha's own voice assistant, opened by "Hi Mehitha":
 * greets, listens to the question, asks Claude, then asks whether to read the answer out.
 */
class AssistantActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: (() -> Unit)? = null
    private var recognizer: SpeechRecognizer? = null
    private var brain: AssistantBrain? = null
    private var misses = 0
    private var ready = false
    private var gist = ""
    private var resumeDelayMs = 0L

    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var answer: TextView
    private lateinit var answerBox: ScrollView
    private lateinit var buttons: LinearLayout
    private lateinit var readButtons: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDimAmount(0.45f)
        setContentView(sheet())
        WakeWordService.pause(this) // the wake word lets go of the mic while Mehitha listens

        tts = TextToSpeech(this) { code ->
            handler.post {
                ttsReady = code == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale("en", "IN")
                    tts?.setOnUtteranceProgressListener(speechDone)
                }
                pendingSpeech?.invoke()
                pendingSpeech = null
            }
        }

        // Answers can hold private data, so ask to unlock first when the phone is locked.
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = start()
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        } else {
            start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stopSpeaking()
        cancelListening()
        start()
    }

    private fun start() {
        val key = apiKey(this)
        when {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                show("Allow the microphone in the Mehitha app first.", showButtons = true)
                say("Please allow the microphone in the Mehitha app first.")
            }
            !SpeechRecognizer.isRecognitionAvailable(this) -> {
                show("This phone has no speech recognition. Install or update the Google app.", showButtons = true)
                say("I can't hear you because speech recognition is missing. Please install or update the Google app.")
            }
            else -> {
                ready = true
                if (key.isNotBlank()) brain = AssistantBrain(key, tools ?: PhoneTools(this), apiBaseUrl)
                val asked = intent.getStringExtra(EXTRA_ASK)
                intent.removeExtra(EXTRA_ASK)
                when {
                    intent.hasExtra(EXTRA_QUESTION) -> showClaudeReply()
                    asked != null -> onQuestion(asked) // tapped an alert
                    else -> greet()
                }
            }
        }
    }

    /** Back from the Claude app with its reply (no API key). */
    private fun showClaudeReply() {
        val question = intent.getStringExtra(EXTRA_QUESTION).orEmpty()
        val reply = intent.getStringExtra(EXTRA_REPLY)
        intent.removeExtra(EXTRA_QUESTION)
        heard.text = "“$question”"
        heard.visibility = View.VISIBLE
        if (reply.isNullOrBlank()) {
            val msg = "I couldn't read Claude's reply. Please look in the Claude app."
            show(msg, showButtons = true)
            say(msg)
        } else {
            onAnswer(Result.success(reply))
        }
    }

    /** No API key: type the question into the Claude app, which answers with your own login. */
    private fun handOff(question: String, label: String) {
        status.text = "Asking Claude…"
        orb.active = true
        if (VoiceHelper.isEnabled(this)) {
            VoiceHelper.ask(question, label)
            say("Asking Claude.") { LaunchClaudeActivity.openNewChat(this); finish() }
        } else {
            // Without the helper Mehitha can't type into Claude, so copy the question instead.
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Question", question))
            status.text = "Question copied. Turn on \"Let Mehitha use the Claude app\" in Hi Mehitha so I can type it for you."
            say("I copied your question. To let me type it into Claude for you, turn on Let Mehitha use the Claude app, in the Mehitha app.") {
                LaunchClaudeActivity.openNewChat(this); finish()
            }
        }
    }

    private fun greet() {
        misses = 0
        status.text = GREETING
        say(GREETING) { listenForQuestion() }
    }

    // ---- Flow -------------------------------------------------------------------------------

    // The phone's recogniser stops at a short pause, so keep listening after each part and
    // only send the question once a further pause brings nothing new.
    private val questionParts = mutableListOf<String>()

    private fun listenForQuestion() {
        questionParts.clear()
        listen(::onQuestionPart)
    }

    private fun onQuestionPart(text: String?) {
        if (text.isNullOrBlank()) {
            onQuestion(questionParts.joinToString(" ").ifBlank { null })
            return
        }
        questionParts += text.trim()
        listen(::onQuestionPart, prefix = questionParts.joinToString(" "), waitForSpeechMs = MORE_SPEECH_MS)
    }

    private fun onQuestion(text: String?) {
        if (text.isNullOrBlank()) {
            if (++misses < 2) {
                status.text = "Sorry, I didn't catch that."
                say("Sorry, I didn't catch that. Please say it again.") { listenForQuestion() }
            } else {
                show("Tap Ask to try again.", showButtons = true)
            }
            return
        }
        heard.text = "“$text”"
        heard.visibility = View.VISIBLE
        answerBox.visibility = View.GONE
        val command = Command.parse(text)
        // Things Mehitha answers itself, without Claude.
        when (command) {
            is Command.Remember -> {
                Memory.add(this, command.fact)
                return localAnswer("Okay, I'll remember that: ${command.fact}.")
            }
            is Command.Alerts -> return localAnswer(Alerts.summary(this))
            is Command.Ask -> Prompts.recall(Memory.all(this), text)?.let { return localAnswer(it) }
            else -> Unit
        }
        status.text = "Looking that up…"
        orb.active = true
        val b = brain
        Thread {
            // Gathering phone data (SMS, scan) can take a moment, so it happens off the screen thread.
            val prompt = try { promptFor(command, text) } catch (e: Exception) { Log.w(TAG, "Phone data failed", e); text }
            if (b == null) {
                handler.post { if (!isDestroyed) handOff(prompt, text) }
                return@Thread
            }
            val result = try {
                Result.success(b.ask(prompt))
            } catch (e: Exception) {
                Log.w(TAG, "Claude request failed", e)
                Result.failure(e)
            }
            handler.post { if (!isDestroyed) onAnswer(result) }
        }.start()
    }

    /** The words sent to Claude for [command], with the phone's own data filled in. */
    private fun promptFor(command: Command, text: String): String {
        val t = PhoneData.now()
        val day = t.toLocalDate()
        val data = phoneData ?: PhoneData(this)
        fun bills() = (if (data.canReadSms) Money.billsText(data.bills(), day) else PhoneData.NO_SMS).take(300)
        return when (command) {
            is Command.Briefing -> Prompts.briefing(t, bills(), Prompts.scanSummary(data.scan()))
            is Command.Money -> Prompts.money(t, command.question, data.moneyFacts(command.question).take(MAX_DATA))
            is Command.SpendingReport -> Prompts.spendingReport(t,
                (if (data.canReadSms) Money.report(data.spends(), day) else PhoneData.NO_SMS).take(MAX_DATA))
            is Command.OddCharges -> {
                val spends = data.spends()
                val flags = Money.oddCharges(spends, t, days = 7).joinToString("; ") { it.text }.ifEmpty { "none" }
                Prompts.oddCharges(t, if (data.canReadSms) flags else PhoneData.NO_SMS, Money.recentLines(spends, t, 7).take(MAX_DATA))
            }
            is Command.StockCheck -> Prompts.stockCheck(t, command.stock, Prompts.scanFor(data.scan(), command.stock))
            is Command.CatchUp -> Prompts.catchUp(t, data.messagesToday(MAX_DATA))
            is Command.FindDocument -> Prompts.findDocument(t, command.question, data.files(command.question))
            is Command.WeeklyReview -> Prompts.weeklyReview(t,
                (if (data.canReadSms) Money.week(data.spends(), t) else PhoneData.NO_SMS).take(MAX_DATA / 2), bills(), Prompts.scanSummary(data.scan()))
            is Command.Reminder -> Prompts.reminder(t, command.request)
            else -> text
        }
    }

    /** Mehitha's own answer: shown and said straight away. */
    private fun localAnswer(text: String) {
        answer.text = text
        gist = text
        answerBox.visibility = View.VISIBLE
        done("Ask me something else, or close.")
        say(text)
    }

    private fun onAnswer(result: Result<String>) {
        val text = result.getOrElse { e ->
            val msg = problem(e)
            show(msg, showButtons = true)
            say(msg)
            return
        }
        val (reminders, rest) = Prompts.reminders(text)
        val set = try { Reminders.schedule(this, reminders) } catch (e: Exception) { Log.w(TAG, "Reminder failed", e); emptyList() }
        val reply = AssistantText.forSpeech(rest)
        answer.text = (listOf(reply.display) + set).joinToString("\n\n")
        gist = reply.speech
        answerBox.visibility = View.VISIBLE
        misses = 0
        askToRead()
    }

    private fun askToRead() {
        status.text = "Shall I read it out?"
        readButtons.visibility = View.VISIBLE
        buttons.visibility = View.GONE
        say("I found the answer. Shall I read it out?") { listen(::onReadReply) }
    }

    private fun onReadReply(text: String?) {
        when (text?.let { AssistantText.yesNo(it) }) {
            true -> readAnswer()
            false -> { done("Okay, it's on the screen."); say("Okay.") }
            null -> if (++misses < 2) {
                status.text = "Please say yes or no."
                say("Please say yes or no.") { listen(::onReadReply) }
            } else {
                status.text = "Tap Read it or No thanks."
            }
        }
    }

    private fun readAnswer() {
        done("Reading it out…")
        val chunks = AssistantText.chunksForSpeech(gist.ifBlank { answer.text.toString() })
        chunks.forEachIndexed { i, c ->
            val last = i == chunks.lastIndex
            say(c, flush = i == 0) { if (last) status.text = "Ask me something else, or close." }
        }
    }

    private fun done(line: String) {
        cancelListening()
        status.text = line
        orb.active = false
        readButtons.visibility = View.GONE
        buttons.visibility = View.VISIBLE
    }

    private fun show(line: String, showButtons: Boolean) {
        status.text = line
        orb.active = false
        if (showButtons) { readButtons.visibility = View.GONE; buttons.visibility = View.VISIBLE }
    }

    private fun problem(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName).lowercase()
        return when {
            "401" in m || "authentication" in m || "api key" in m || "x-api-key" in m ->
                "Claude didn't accept the API key. Please check it in the Mehitha app."
            "credit" in m || "billing" in m -> "Your Claude API account is out of credit. Please add credit at console dot anthropic dot com."
            "429" in m || "rate" in m -> "Claude is busy right now. Please try again in a minute."
            "unknownhost" in m || "unable to resolve" in m || "timeout" in m || "timed out" in m || "connect" in m ->
                "I couldn't reach Claude. Please check the internet connection."
            else -> "Something went wrong asking Claude. Please try again."
        }
    }

    // ---- Speech in --------------------------------------------------------------------------

    /**
     * Listens once. [prefix] is what was already heard (shown before the new words).
     * With [waitForSpeechMs], gives up quietly if no new speech starts in that time.
     */
    private fun listen(then: (String?) -> Unit, prefix: String = "", waitForSpeechMs: Long = 0) {
        if (isFinishing) return
        cancelListening()
        orb.active = true
        heard.text = if (prefix.isEmpty()) "" else "“$prefix”"
        heard.visibility = if (prefix.isEmpty()) View.GONE else View.VISIBLE
        val shown = { words: String -> "“" + listOf(prefix, words).filter { it.isNotBlank() }.joinToString(" ") + "”" }
        val r = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        var delivered = false
        fun deliver(text: String?) {
            if (delivered) return
            delivered = true
            handler.removeCallbacks(noMoreSpeech)
            handler.post { orb.active = false; then(text) }
        }
        noMoreSpeech = Runnable { r.cancel(); deliver(null) }
        if (waitForSpeechMs > 0) handler.postDelayed(noMoreSpeech, waitForSpeechMs)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = status.text.toString().let { if (it.endsWith("🎤")) it else "$it  🎤" } }
            override fun onBeginningOfSpeech() { handler.removeCallbacks(noMoreSpeech) }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { Log.i(TAG, "Recognizer error $error"); deliver(null) }
            override fun onResults(results: Bundle?) { deliver(best(results)) }
            override fun onPartialResults(partial: Bundle?) {
                best(partial)?.let { heard.text = shown(it); heard.visibility = View.VISIBLE }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        })
    }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private var noMoreSpeech = Runnable {}

    private fun cancelListening() {
        handler.removeCallbacks(noMoreSpeech)
        recognizer?.let { it.cancel(); it.destroy() }
        recognizer = null
    }

    // ---- Speech out -------------------------------------------------------------------------

    private val afterSpeech = mutableMapOf<String, () -> Unit>()
    private var utteranceId = 0

    private val speechDone = object : UtteranceProgressListener() {
        override fun onStart(id: String) {}
        override fun onDone(id: String) { handler.post { afterSpeech.remove(id)?.invoke() } }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String) { onDone(id) }
        override fun onStop(id: String, interrupted: Boolean) { handler.post { afterSpeech.remove(id) } }
    }

    /** Speaks [text], then runs [then]. If speech isn't working, shows the text and carries on. */
    private fun say(text: String, flush: Boolean = true, then: (() -> Unit)? = null) {
        if (tts != null && !ttsReady && pendingSpeech == null) {
            pendingSpeech = { say(text, flush, then) }
            return
        }
        val engine = tts
        if (!ttsReady || engine == null) { then?.let { handler.postDelayed(it, 300) }; return }
        val id = "u${++utteranceId}"
        if (then != null) afterSpeech[id] = then
        if (flush) afterSpeech.keys.retainAll(setOf(id))
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (engine.speak(text, mode, null, id) != TextToSpeech.SUCCESS) afterSpeech.remove(id)?.let { handler.post(it) }
    }

    private fun stopSpeaking() {
        afterSpeech.clear()
        tts?.stop()
    }

    // ---- Screen -----------------------------------------------------------------------------

    private fun sheet(): View {
        val d = { v: Int -> Ui.dp(this, v) }
        orb = OrbView(this).apply { active = true }
        status = Ui.text(this, GREETING, 21f, bold = true).apply { gravity = Gravity.CENTER }
        heard = Ui.text(this, "", 15f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, d(8), 0, 0); visibility = View.GONE
        }
        answer = Ui.text(this, "", 16f).apply { setTextIsSelectable(true) }
        answerBox = ScrollView(this).apply {
            visibility = View.GONE
            setPadding(d(14), d(12), d(14), d(12))
            background = GradientDrawable().apply { setColor(Ui.FIELD); cornerRadius = d(16).toFloat() }
            addView(answer)
        }
        readButtons = row(
            Ui.gradientButton(this, "Read it") { cancelListening(); readAnswer() },
            Ui.pill(this, "No thanks") { cancelListening(); done("Okay, it's on the screen.") },
        ).apply { visibility = View.GONE }
        buttons = row(
            Ui.gradientButton(this, "Ask") { stopSpeaking(); if (ready) greet() else start() },
            Ui.pill(this, "Open in Claude app") { openClaudeApp() },
            Ui.pill(this, "Close") { finish() },
        ).apply { visibility = View.GONE }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(d(22), d(16), d(22), d(28))
            background = GradientDrawable().apply {
                setColor(Ui.CARD)
                cornerRadii = floatArrayOf(d(28).toFloat(), d(28).toFloat(), d(28).toFloat(), d(28).toFloat(), 0f, 0f, 0f, 0f)
            }
            isClickable = true // taps on the sheet don't close it
            addView(View(this@AssistantActivity).apply {
                background = GradientDrawable().apply { setColor(Ui.MUTED); cornerRadius = d(3).toFloat() }
            }, LinearLayout.LayoutParams(d(40), d(5)))
            addView(orb, LinearLayout.LayoutParams(d(120), d(120)).apply { topMargin = d(8) })
            addView(status)
            addView(heard)
            val maxAnswer = (resources.displayMetrics.heightPixels * 0.42).toInt()
            addView(answerBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = d(14) })
            answerBox.viewTreeObserver.addOnGlobalLayoutListener {
                if (answerBox.height > maxAnswer) answerBox.layoutParams = answerBox.layoutParams.apply { height = maxAnswer }
            }
            addView(readButtons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = d(16) })
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = d(16) })
        }
        return FrameLayout(this).apply {
            setOnClickListener { finish() }
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEach { v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(this@AssistantActivity, 4); marginEnd = Ui.dp(this@AssistantActivity, 4)
            })
        }
    }

    private fun openClaudeApp() {
        stopSpeaking()
        cancelListening()
        resumeDelayMs = WakeWordService.RESUME_AFTER_TRIGGER_MS // leave the mic to Claude for a while
        startActivity(Intent(this, LaunchClaudeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the sheet (home button, another app) ends the session so "Hi Mehitha" works again.
        val unlocking = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!isChangingConfigurations && !unlocking) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancelListening()
        tts?.stop()
        tts?.shutdown()
        tts = null
        WakeWordService.resume(this, resumeDelayMs)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HeyClaude"
        const val GREETING = "How can I help you today?"
        const val EXTRA_QUESTION = "question"
        /** Asks Mehitha this straight away (from tapping an alert). */
        const val EXTRA_ASK = "ask"
        private const val MAX_DATA = 700
        /** How long to wait for more words after a pause before sending the question. */
        const val MORE_SPEECH_MS = 2_500L
        const val EXTRA_REPLY = "reply"
        private const val PREFS = "assistant"
        private const val KEY = "claude_api_key"

        // Tests point these at a fake Claude API and fake phone data.
        @Volatile var apiBaseUrl: String? = null
        @Volatile var tools: MehithaTools? = null
        @Volatile var phoneData: PhoneData? = null

        fun apiKey(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

        fun saveApiKey(ctx: Context, key: String) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, key.trim()).apply()
    }
}
