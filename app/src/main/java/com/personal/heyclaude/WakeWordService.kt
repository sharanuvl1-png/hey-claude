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
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Listens on the microphone, fully offline, for "Hi Mehitha" and opens the Claude app.
 * After a trigger it releases the microphone for a while so Claude can use it.
 */
class WakeWordService : Service(), RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var lastTriggerAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification("Listening for \"Hi Mehitha\"")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
        if (model == null) loadModel() else startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        stopListening()
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
        if (now - lastTriggerAt < RESUME_AFTER_TRIGGER_MS) return
        lastTriggerAt = now
        stopListening()
        updateNotification("Opened Claude. Listening again in a minute")
        startActivity(
            Intent(this, LaunchClaudeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
        handler.postDelayed({ startListening() }, RESUME_AFTER_TRIGGER_MS)
    }

    private fun handle(json: String) {
        val text = JSONObject(json).optString("text")
        if (WAKE_PHRASES.any { text.contains(it) }) {
            Log.i(TAG, "Wake word heard: $text")
            handler.post { onWakeWord() }
        }
    }

    override fun onResult(hypothesis: String) = handle(hypothesis)
    override fun onFinalResult(hypothesis: String) = handle(hypothesis)
    override fun onPartialResult(hypothesis: String) {}
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
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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
        private const val RESUME_AFTER_TRIGGER_MS = 60_000L

        // "Mehitha" is not in the offline English vocabulary, so listen for
        // the English word sequences it sounds like instead.
        private val GREETINGS = listOf("hi", "hey", "hello")
        private val NAME_SOUNDS = listOf(
            "me hit a", "may hit a", "me heat a", "may heat a", "me eat a",
            "mehta", "meta", "mahita", "mehitha"
        )
        private val WAKE_PHRASES = GREETINGS.flatMap { g -> NAME_SOUNDS.map { "$g $it" } }
        private val GRAMMAR = JSONArray(WAKE_PHRASES + "[unk]").toString()

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
