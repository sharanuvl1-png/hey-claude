package com.personal.heyclaude

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the text of incoming notifications (WhatsApp, Telegram, bank apps...)
 * to a daily file, so messages can be searched later. Apps don't allow reading
 * chat history directly; this captures messages from the moment it is enabled.
 */
class NotificationCatcher : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName || sbn.isOngoing) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (text.isBlank() || PhoneSync.looksLikeOtp(text)) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        val now = Date(sbn.postTime)
        val line = "${TIME.format(now)} | $app | ${title.oneLine()} | ${text.oneLine()}\n"
        val dir = File(filesDir, "notifications").apply { mkdirs() }
        File(dir, "${DAY.format(now)}.txt").appendText(line)
    }

    private fun String.oneLine() = replace(Regex("\\s+"), " ").trim()

    companion object {
        private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val TIME = SimpleDateFormat("HH:mm", Locale.US)
    }
}
