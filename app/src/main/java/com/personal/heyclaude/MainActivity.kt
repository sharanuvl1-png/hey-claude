package com.personal.heyclaude

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.net.Uri
import android.widget.EditText
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import java.util.Locale

/**
 * Home screen: a chat with Mehitha. Type or tap the mic to ask; answers come from Claude
 * (with your API key) plus the phone's own data, and can be read aloud.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var brain: AssistantBrain? = null
    private var brainKey = ""
    private var brainModel = ""
    /** The question waiting for the passcode, if Mehitha just asked for it. */
    private var waitingPasscodeFor: String? = null
    /** The chat on screen, saved on the phone as it grows. */
    private var chatId = 0L
    private val entries = mutableListOf<ChatEntry>()
    private var restoring = false
    private var busy = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** Speech asked for before the engine finished starting (e.g. a tapped alert on a cold start). */
    private var pendingSpeech: String? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private lateinit var scroll: ScrollView
    private lateinit var messages: LinearLayout
    private lateinit var welcome: View
    private lateinit var keyBanner: TextView
    private lateinit var input: EditText
    private lateinit var mic: TextView
    private lateinit var send: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        setContentView(screen())
        chatId = ChatStore.currentId(this)
        show(ChatStore.load(this, chatId))
        tts = TextToSpeech(this) { code ->
            handler.post {
                ttsReady = code == TextToSpeech.SUCCESS
                if (ttsReady) tts?.language = Locale("en", "IN") else { tts?.shutdown(); tts = null }
                pendingSpeech?.let { if (ttsReady) speak(it) }
                pendingSpeech = null
            }
        }
        // Background jobs: phone sync, file requests, and the alerts (Nifty breakouts, bills, reminders).
        if (DropboxClient(this).isLinked) { SyncWorker.schedule(this); RequestWorker.start(this) }
        Alerts.schedule(this)
        askFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        askFrom(intent)
    }

    /**
     * Tapping an alert opens the chat with its question, read aloud when the alert asks for it.
     * Opened as the phone's assistant (long-press power or home), Mehitha starts listening.
     */
    private fun askFrom(i: Intent?) {
        if (i?.action == Intent.ACTION_ASSIST || i?.action == Intent.ACTION_VOICE_COMMAND) {
            i.action = Intent.ACTION_MAIN // only once, not again when the screen is recreated
            handler.post { if (!listening && !isFinishing) toggleDictation() }
            return
        }
        val asked = i?.getStringExtra(AssistantActivity.EXTRA_ASK) ?: return
        val speak = i.getBooleanExtra(EXTRA_SPEAK, false)
        i.removeExtra(AssistantActivity.EXTRA_ASK)
        i.removeExtra(EXTRA_SPEAK)
        ask(asked, spoken = speak)
    }

    override fun onResume() {
        super.onResume()
        keyBanner.visibility = if (AssistantActivity.apiKey(this).isBlank()) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        super.onPause()
        stopDictation()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---- Asking -----------------------------------------------------------------------------

    /** Sends [raw] to Mehitha. [spoken] answers are read aloud as well. */
    private fun ask(raw: String, spoken: Boolean = false, echo: Boolean = true) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (busy) { toast("One moment, I'm still on the last question"); return }
        input.setText("")
        hideKeyboard()
        stopSpeaking()
        waitingPasscodeFor?.let { question ->
            // The passcode itself is never shown in the chat or sent anywhere.
            waitingPasscodeFor = null
            addMine("••••••")
            if (Passcode.check(this, text)) { brain()?.unlocked = true; ask(question, spoken, echo = false) }
            else addReply(Passcode.WRONG, null, spoken, problem = true)
            return
        }
        if (echo) addMine(text)
        val command = Command.parse(text)
        Answers.local(this, command, text)?.let { addReply(it, it, spoken); return }
        if (command is Command.Chart) { showChart(command.stock, spoken); return }
        if (command is Command.Shop) { openShop(command.items, command.shop, spoken); return }
        if (command is Command.ListSend) {
            val items = Shopping.list(this)
            if (items.isEmpty()) addReply("Your shopping list is empty. Say \"add milk to my list\" first.", null, spoken)
            else openShop(items, command.shop, spoken)
            return
        }

        val b = brain()
        busy = true
        updateSend()
        val thinking = addThinking()
        Thread {
            // Gathering phone data (SMS, scan) can take a moment, so it happens off the screen thread.
            val prompt = Answers.safePrompt(this, command, text, inApp = b != null)
            val result: Result<String>? = if (b == null) null else try {
                Result.success(b.ask(prompt))
            } catch (e: Exception) {
                Log.w(TAG, "Claude request failed", e)
                Result.failure(e)
            }
            handler.post {
                if (isDestroyed) return@post
                messages.removeView(thinking)
                busy = false
                updateSend()
                when {
                    result == null -> handOff(prompt, text)
                    result.isSuccess && Passcode.isNeeded(result.getOrThrow()) -> when {
                        !Passcode.isSet(this) -> addReply(Passcode.NOT_SET, null, spoken)
                        else -> { waitingPasscodeFor = text; addReply(Passcode.ASK, null, spoken) }
                    }
                    result.isSuccess -> Answers.reply(this, result.getOrThrow()).let { addReply(it.display, it.speech, spoken, images = it.images) }
                    else -> addReply(Answers.problem(result.exceptionOrNull()!!), null, spoken, problem = true)
                }
            }
        }.start()
    }

    /**
     * Opens [shop]'s search for the first item and shows a button for each of the rest, so you
     * pick the exact product and tap Add yourself. Mehitha never goes to checkout or orders.
     */
    private fun openShop(items: List<String>, shop: String, spoken: Boolean) {
        val name = Shopping.shopName(shop)
        val links = items.map { it to Shopping.searchUrl(shop, it) }
        val text = if (items.size == 1) "Opening $name for ${items[0]}. Tap Add on the one you want. I never place orders."
            else "Opening $name for ${items[0]} first. Come back and tap the next item. Tap Add on the ones you want; I never place orders."
        addReply(text, text, spoken, links = links)
        openLink(links[0].second)
    }

    private fun openLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { toast("Couldn't open the shop") }
    }

    /** The newest technical chart for [stock], straight from the charts branch (no API key needed). */
    private fun showChart(stock: String, spoken: Boolean) {
        busy = true
        updateSend()
        val thinking = addThinking()
        Thread {
            val found = try { (AssistantActivity.tools ?: PhoneTools(this)).stockChart(stock) } catch (e: Exception) {
                Log.w(TAG, "Chart lookup failed", e); "I couldn't reach the charts right now. Please check the internet connection."
            }
            val (rest, pictures) = AssistantText.images(found)
            val line = rest.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            val say = if (pictures.isEmpty()) line else "$line Ask me to explain it if you'd like the analysis."
            handler.post {
                if (isDestroyed) return@post
                messages.removeView(thinking)
                busy = false
                updateSend()
                addReply(say, say, spoken, problem = pictures.isEmpty(), images = pictures)
            }
        }.start()
    }

    /** One conversation per chat, so follow-up questions keep their context. */
    private fun brain(): AssistantBrain? {
        val key = AssistantActivity.apiKey(this)
        if (key.isBlank()) { brain = null; return null }
        val model = AssistantActivity.model(this)
        if (brain == null || key != brainKey || model != brainModel) {
            brain = AssistantBrain(key, AssistantActivity.tools ?: PhoneTools(this), AssistantActivity.apiBaseUrl, model = model)
                .also { it.seed(ChatStore.turns(entries.dropLastWhile { e -> e.mine })) }
            brainKey = key
            brainModel = model
        }
        return brain
    }

    /** No API key: type the question into the Claude app, which answers with your own login. */
    private fun handOff(prompt: String, label: String) {
        if (VoiceHelper.isEnabled(this)) {
            VoiceHelper.ask(prompt, label)
            addReply("I've asked the Claude app. Its reply will pop up when it's ready.", null, false)
        } else {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Question", prompt))
            addReply("I copied your question for the Claude app. Paste it there. To answer right here instead, add your Claude API key in Settings.", null, false)
        }
        handler.postDelayed({ if (!isFinishing) LaunchClaudeActivity.openNewChat(this) }, 900)
    }

    private fun newChat() {
        if (busy) return
        chatId = ChatStore.newChat(this)
        show(SavedChat(chatId, emptyList()))
    }

    /** Puts [chat] on screen (without reading it out) and makes it the one new questions go to. */
    private fun show(chat: SavedChat) {
        stopSpeaking()
        brain = null
        waitingPasscodeFor = null
        entries.clear()
        messages.removeAllViews()
        messages.addView(welcome, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        restoring = true
        chat.entries.forEach { e ->
            if (e.mine) addMine(e.text) else addReply(e.text, e.speech, false, e.problem, e.images, e.links)
        }
        restoring = false
        entries += chat.entries
    }

    /** Past chats, newest first; tap one to continue it. */
    private fun showHistory() {
        if (busy) return
        val chats = ChatStore.all(this)
        if (chats.isEmpty()) { toast("No saved chats yet"); return }
        val day = java.text.SimpleDateFormat("d MMM, h:mm a", Locale.US)
        android.app.AlertDialog.Builder(this)
            .setTitle("Chat history")
            .setItems(chats.map { "${it.title}\n${day.format(java.util.Date(it.id))}" }.toTypedArray()) { _, i ->
                chatId = chats[i].id
                ChatStore.setCurrent(this, chatId)
                show(chats[i])
            }
            .setNeutralButton("Delete all") { _, _ ->
                ChatStore.clear(this)
                newChat()
                toast("Chat history deleted")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Saves a new bubble in the current chat. Answers given after the passcode are never saved. */
    private fun remember(e: ChatEntry) {
        if (restoring) return
        val kept = if (!e.mine && brain?.unlocked == true) e.copy(text = ChatStore.HIDDEN, speech = null, images = emptyList()) else e
        entries += kept
        try { ChatStore.save(this, SavedChat(chatId, entries.toList())) } catch (e2: Exception) { Log.w(TAG, "Couldn't save the chat", e2) }
    }

    // ---- Chat bubbles -----------------------------------------------------------------------

    private fun addMine(text: String) {
        remember(ChatEntry(true, text))
        if (welcome.parent != null) messages.removeView(welcome)
        add(Ui.bubble(this, text, mine = true), mine = true)
    }

    private fun addThinking(): View = Ui.bubble(this, "Thinking…", mine = false, color = Ui.MUTED).also { add(it, mine = false) }

    /** Mehitha's answer, with Read aloud and Copy underneath. */
    private fun addReply(display: String, speech: String?, readNow: Boolean, problem: Boolean = false,
                         images: List<String> = emptyList(), links: List<Pair<String, String>> = emptyList()) {
        remember(ChatEntry(false, display, speech, images, links, problem))
        if (welcome.parent != null) messages.removeView(welcome)
        val d = { v: Int -> Ui.dp(this, v) }
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block.addView(Ui.bubble(this, display, mine = false, color = if (problem) Ui.WARN else Ui.TEXT))
        val imageWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
        if (links.isNotEmpty()) block.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                links.forEach { (label, url) ->
                    addView(Ui.chip(this@MainActivity, "🛒 $label") { openLink(url) },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = d(8) })
                }
            })
        }, LinearLayout.LayoutParams(imageWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = d(8) })
        images.forEach { url -> block.addView(picture(url), LinearLayout.LayoutParams(imageWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = d(8) }) }
        val say = speech ?: display
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(d(4), d(6), 0, 0)
            addView(small("🔊 Read aloud") { speak(say) })
            addView(small("Copy") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Mehitha", display))
                toast("Copied")
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = d(16) })
        }
        block.addView(actions)
        add(block, mine = false)
        if (readNow) speak(say)
    }

    /** A picture from the web in the chat: loads in the background, tap to open it full size. */
    private fun picture(url: String): View {
        val d = { v: Int -> Ui.dp(this, v) }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Ui.CARD); cornerRadius = d(14).toFloat() }
            setPadding(d(6), d(6), d(6), d(6))
            contentDescription = "Picture: $url"
            isClickable = true
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { toast("No app can open this picture") }
            }
        }
        val status = Ui.text(this, "Loading picture…", 13f, Ui.MUTED).apply { setPadding(d(8), d(8), d(8), d(8)) }
        holder.addView(status)
        Thread {
            val bmp = Charts.picture(url)
            handler.post {
                if (isDestroyed) return@post
                if (bmp == null) { status.text = "Couldn't load the picture. Tap to open it in the browser."; return@post }
                holder.removeView(status)
                holder.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Picture"
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }.start()
        return holder
    }

    private fun small(label: String, onClick: () -> Unit) = Ui.text(this, label, 12.5f, Ui.MUTED).apply {
        setPadding(0, Ui.dp(this@MainActivity, 4), 0, Ui.dp(this@MainActivity, 4))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun add(v: View, mine: Boolean) {
        val d = { x: Int -> Ui.dp(this, x) }
        val maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
        if (v is TextView) v.maxWidth = maxWidth
        if (v is LinearLayout) (v.getChildAt(0) as? TextView)?.maxWidth = maxWidth
        messages.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = d(10)
        })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- Voice --------------------------------------------------------------------------------

    private fun speak(text: String) {
        val engine = tts
        if (engine != null && !ttsReady) { pendingSpeech = text; return }
        if (!ttsReady || engine == null) { toast("Text to speech isn't ready on this phone"); return }
        AssistantText.chunksForSpeech(text).forEachIndexed { i, c ->
            engine.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "chat$i")
        }
    }

    private fun stopSpeaking() { pendingSpeech = null; tts?.stop() }

    /** Tap the mic, speak, and the question is sent when you pause. Tap again to stop. */
    private fun toggleDictation() {
        if (listening) { stopDictation(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("This phone has no speech recognition. Install or update the Google app.")
            return
        }
        stopSpeaking()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { input.hint = "Listening…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { setListening(false); if (error != SpeechRecognizer.ERROR_CLIENT) toast("Didn't catch that. Tap the mic and try again.") }
            override fun onResults(results: Bundle?) {
                setListening(false)
                best(results)?.let { ask(it, spoken = true) }
            }
            override fun onPartialResults(partial: Bundle?) { best(partial)?.let { input.setText(it); input.setSelection(it.length) } }
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
        setListening(true)
    }

    private fun stopDictation() {
        if (!listening) return
        recognizer?.cancel()
        setListening(false)
    }

    private fun setListening(on: Boolean) {
        listening = on
        mic.text = if (on) "■" else "🎤"
        (mic.background as? GradientDrawable)?.setColor(if (on) Ui.ACCENT_1 else Ui.FIELD)
        if (!on) input.hint = HINT
    }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleDictation()
    }

    // ---- Screen -------------------------------------------------------------------------------

    private fun screen(): View {
        val d = { v: Int -> Ui.dp(this, v) }
        val top = Ui.topBar(this, "Mehitha", "Your personal AI assistant", null,
            Ui.iconButton(this, "🕘", "Chat history") { showHistory() },
            Ui.iconButton(this, "＋", "New chat") { newChat() },
            Ui.iconButton(this, "⚙", "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
        ).apply { setPadding(d(16), d(10), d(12), d(6)) }

        welcome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, d(24), 0, d(8))
            addView(OrbView(this@MainActivity).apply { active = true }, LinearLayout.LayoutParams(d(130), d(130)))
            addView(Ui.text(this@MainActivity, "How can I help you today?", 22f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, d(8), 0, 0) })
            addView(Ui.text(this@MainActivity, "Ask about your money, stocks, messages, documents or reminders, or anything else.", 14f, Ui.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(d(12), d(6), d(12), d(18))
            })
            keyBanner = Ui.text(this@MainActivity, "Add your Claude API key in Settings so I can answer right here. Until then I'll pass questions to the Claude app.", 13f, Ui.WARN).apply {
                setPadding(d(14), d(10), d(14), d(10))
                background = GradientDrawable().apply { setColor(Ui.CARD); cornerRadius = d(14).toFloat() }
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            }
            addView(keyBanner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), 0, d(16), d(12))
            addView(welcome, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(messages) }

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(d(16), d(4), d(16), d(4))
            SUGGESTIONS.forEach { (label, question) ->
                addView(Ui.chip(this@MainActivity, label) {
                    if (question.endsWith(" ")) { input.setText(question); input.setSelection(question.length); showKeyboard() }
                    else ask(question)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = d(8) })
            }
        }
        val chipRow = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }

        input = EditText(this).apply {
            hint = HINT
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            textSize = 15.5f
            // One logical line with a Send key on the keyboard, wrapping onto up to five rows.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setHorizontallyScrolling(false)
            maxLines = 5
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { ask(text.toString()); true } else false }
            setPadding(d(16), d(12), d(16), d(12))
            background = GradientDrawable().apply { setColor(Ui.FIELD); cornerRadius = d(24).toFloat() }
        }
        mic = Ui.iconButton(this, "🎤", "Ask by voice") { toggleDictation() }
        send = Ui.iconButton(this, "➤", "Send", gradient = true) { ask(input.text.toString()) }
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(d(12), d(6), d(12), d(12))
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(mic, LinearLayout.LayoutParams(d(46), d(46)).apply { marginStart = d(8) })
            addView(send, LinearLayout.LayoutParams(d(46), d(46)).apply { marginStart = d(8) })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(chipRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(composer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun updateSend() { send.alpha = if (busy) 0.4f else 1f }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun showKeyboard() {
        input.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(input, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "HeyClaude"
        private const val MIC_REQUEST = 1
        private const val HINT = "Ask Mehitha anything"
        /** Set on an alert's tap to read the answer aloud too (same value as Alerts.EXTRA_SPEAK). */
        const val EXTRA_SPEAK = "com.personal.heyclaude.SPEAK"

        /** Chip label to question. A question ending in a space is put in the box to finish typing. */
        val SUGGESTIONS = listOf(
            "☀️ Brief me" to "brief me",
            "🗓️ What's on today?" to "what's on today",
            "🔔 Any alerts?" to "any alerts",
            "💸 Spending report" to "spending report",
            "🧾 Odd charges?" to "any odd charges this week",
            "📈 Check a stock" to "check ",
            "📊 Show a chart" to "show chart of ",
            "🛒 Shopping list" to "what's on my shopping list",
            "📦 Where's my order?" to "where is my order",
            "💬 What did I miss?" to "what did I miss",
            "📅 Weekly review" to "weekly review",
            "⏰ Remind me" to "remind me ",
            "📄 Find a document" to "find my ",
        )
    }
}
