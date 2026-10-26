package com.personal.heyclaude

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Listens on the microphone, fully offline, for "Hi Mehitha" and opens the assistant.
 * After a trigger it releases the microphone until the assistant hands it back.
 */
class WakeWordService : Service(), RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var lastTriggerAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // Checks for "fetch this file" requests often, so they arrive within minutes.
    private val requestPoller = Thread {
        while (!Thread.currentThread().isInterrupted) {
            try {
                PhoneSync.handleRequests(this)
                Thread.sleep(REQUEST_POLL_MS)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "Request check failed", e)
                try { Thread.sleep(REQUEST_POLL_MS) } catch (ie: InterruptedException) { break }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        requestPoller.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            // Mehitha's own sheet is using the mic; the sheet resumes us when it closes.
            handler.removeCallbacksAndMessages(null)
            stopListening()
            handler.postDelayed({ startListening() }, ASSISTANT_TIMEOUT_MS)
            return START_STICKY
        }
        if (intent?.action == ACTION_RESUME && model != null) {
            // The assistant sheet closed: listen again right away (or after the given delay).
            handler.removeCallbacksAndMessages(null)
            val delay = intent.getLongExtra(EXTRA_DELAY, 0L)
            if (delay > 0) handler.postDelayed({ startListening() }, delay) else startListening()
            return START_STICKY
        }
        val notification = buildNotification("Listening for \"Hi Mehitha\"")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
        // Keeps the CPU awake so the phone's deep sleep can't pause listening.
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mehitha:listen").apply { acquire() }
        }
        if (model == null) loadModel() else startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        requestPoller.interrupt()
        handler.removeCallbacksAndMessages(null)
        stopListening()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        model?.close()
        model = null
        super.onDestroy()
    }

    private fun loadModel() {
        StorageService.unpack(this, "model-en-us", "model",
            { loaded ->
                model = loaded
                startListening()
            },
            { e ->
                Log.e(TAG, "Could not load speech model", e)
                updateNotification("Could not load the speech model")
            })
    }

    private fun startListening() {
        val m = model ?: return
        if (speechService != null) return
        try {
            // Restricting the vocabulary keeps it light and cuts false triggers.
            val r = Recognizer(m, SAMPLE_RATE, GRAMMAR).also { recognizer = it }
            speechService = SpeechService(r, SAMPLE_RATE).also { it.startListening(this) }
            updateNotification("Listening for \"Hi Mehitha\"")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the microphone", e)
            updateNotification("Microphone unavailable, retrying soon")
            handler.postDelayed({ startListening() }, RETRY_MS)
        }
    }

    private fun stopListening() {
        speechService?.let {
            it.stop()
            it.shutdown()
        }
        speechService = null
        recognizer?.close()
        recognizer = null
    }

    private fun onWakeWord() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < COOLDOWN_MS) return
        lastTriggerAt = now
        stopListening()
        updateNotification("Mehitha is answering. Listening again when it closes")
        startActivity(
            Intent(this, AssistantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
        // The assistant hands the mic back when it closes; this is only a safety net.
        handler.postDelayed({ startListening() }, ASSISTANT_TIMEOUT_MS)
    }

    private fun handle(json: String) {
        val o = JSONObject(json)
        val text = o.optString("text").ifEmpty { o.optString("partial") }
        if (text.isNotEmpty() && WAKE_PHRASES.any { text.contains(it) }) {
            Log.i(TAG, "Wake word heard: $text")
            handler.post { onWakeWord() }
        }
    }

    override fun onResult(hypothesis: String) = handle(hypothesis)
    override fun onFinalResult(hypothesis: String) = handle(hypothesis)
    // Partial results let us react as soon as the phrase is heard, without waiting for silence.
    override fun onPartialResult(hypothesis: String) = handle(hypothesis)
    override fun onTimeout() {}

    override fun onError(exception: Exception) {
        Log.e(TAG, "Recognition error", exception)
        stopListening()
        handler.postDelayed({ startListening() }, RETRY_MS)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Wake word", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mehitha)
            .setContentTitle("Hi Mehitha")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "HeyClaude"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.personal.heyclaude.STOP"
        private const val SAMPLE_RATE = 16000f
        private const val RETRY_MS = 5_000L
        const val RESUME_AFTER_TRIGGER_MS = 8_000L
        private const val ASSISTANT_TIMEOUT_MS = 5 * 60_000L
        private const val ACTION_RESUME = "com.personal.heyclaude.RESUME"
        private const val ACTION_PAUSE = "com.personal.heyclaude.PAUSE"
        private const val EXTRA_DELAY = "delay"
        private const val COOLDOWN_MS = 4_000L
        private const val REQUEST_POLL_MS = 3 * 60_000L

        // "Mehitha" is not in the offline English vocabulary, so listen for
        // the English word sequences it sounds like instead.
        private val GREETINGS = listOf("hi", "hey", "hello", "high")
        private val NAME_SOUNDS = listOf(
            "me hit a", "may hit a", "me heat a", "may heat a", "me heat the", "mahita", "mehitha"
        )
        private val WAKE_PHRASES = GREETINGS.flatMap { g -> NAME_SOUNDS.map { "$g $it" } }
        // Other assistants' wake words, so the recogniser has a better match than "Hi Mehitha"
        // for them. Hearing one of these does nothing.
        private val DECOYS = listOf(
            "hey google", "ok google", "okay google", "hi google", "hello google", "hi there", "hello there"
        )
        private val GRAMMAR = JSONArray(WAKE_PHRASES + DECOYS + "[unk]").toString()

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }

        /** Starts listening again after the assistant has finished with the mic. */
        fun resume(context: Context, delayMs: Long = 0L) = send(context, ACTION_RESUME, delayMs)

        /** Lets go of the mic while the assistant listens. */
        fun pause(context: Context) = send(context, ACTION_PAUSE, 0L)

        private fun send(context: Context, action: String, delayMs: Long) {
            if (!isRunning) return
            try {
                context.startService(Intent(context, WakeWordService::class.java)
                    .setAction(action).putExtra(EXTRA_DELAY, delayMs))
            } catch (e: Exception) {
                Log.w(TAG, "Could not send $action; the safety net will listen again", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
