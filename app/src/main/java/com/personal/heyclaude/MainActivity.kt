package com.personal.heyclaude

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** One-screen setup: grant permissions, then switch listening on or off. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var micButton: Button
    private lateinit var overlayButton: Button
    private lateinit var batteryButton: Button
    private lateinit var toggleButton: Button
    private lateinit var keyField: EditText
    private lateinit var codeField: EditText
    private lateinit var linkButton: Button
    private lateinit var finishButton: Button
    private lateinit var smsButton: Button
    private lateinit var filesButton: Button
    private lateinit var notifButton: Button
    private lateinit var syncStatus: TextView
    private val dropbox by lazy { DropboxClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }
        layout.addView(TextView(this).apply {
            text = "Say \"Hi Mehitha\" to open Claude.\n\nGrant the three permissions below once, then tap Start."
            textSize = 16f
            setPadding(0, 0, 0, pad)
        })
        micButton = addButton(layout, "1. Allow microphone and notifications") { requestMic() }
        overlayButton = addButton(layout, "2. Allow opening Claude from the background") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        batteryButton = addButton(layout, "3. Keep running (no battery limits)") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        toggleButton = addButton(layout, "") {
            if (WakeWordService.isRunning) WakeWordService.stop(this) else WakeWordService.start(this)
            toggleButton.postDelayed({ refresh() }, 300)
        }
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, 0)
        }
        layout.addView(status)

        layout.addView(TextView(this).apply {
            text = "\nPhone data for Mehitha\n\nCopies SMS, WhatsApp/app messages, documents and a list of all your files into a private Dropbox folder, so Claude can search them."
            textSize = 16f
            setPadding(0, pad, 0, pad / 2)
        })
        keyField = EditText(this).apply { hint = "Dropbox app key"; setText(dropbox.appKey); isSingleLine = true }
        layout.addView(keyField)
        linkButton = addButton(layout, "A. Open Dropbox to approve") {
            dropbox.appKey = keyField.text.toString()
            if (dropbox.appKey.isBlank()) toast("Enter the Dropbox app key first")
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dropbox.authorizeUrl())))
        }
        codeField = EditText(this).apply { hint = "Paste the code Dropbox shows"; isSingleLine = true }
        layout.addView(codeField)
        finishButton = addButton(layout, "B. Finish linking") {
            val code = codeField.text.toString()
            Thread {
                val msg = try {
                    dropbox.finishLink(code)
                    SyncWorker.schedule(this)
                    SyncWorker.runNow(this)
                    "Dropbox linked. First sync started."
                } catch (e: Exception) {
                    "Linking failed: ${e.message?.take(120)}"
                }
                runOnUiThread { toast(msg); refresh() }
            }.start()
        }
        smsButton = addButton(layout, "C. Allow SMS and contacts") {
            requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS), 2)
        }
        filesButton = addButton(layout, "D. Allow access to all files") {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        notifButton = addButton(layout, "E. Allow reading notifications (WhatsApp etc.)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addButton(layout, "Sync now") {
            if (dropbox.isLinked) { SyncWorker.runNow(this); toast("Sync started") } else toast("Link Dropbox first")
        }
        syncStatus = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad) }
        layout.addView(syncStatus)

        setContentView(ScrollView(this).apply { addView(layout) })
        if (dropbox.isLinked) SyncWorker.schedule(this)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun notificationAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit): Button {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        parent.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return button
    }

    private fun requestMic() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val claudeInstalled = packageManager.getLaunchIntentForPackage(LaunchClaudeActivity.CLAUDE_PACKAGE) != null

        micButton.isEnabled = !mic
        overlayButton.isEnabled = !overlay
        batteryButton.isEnabled = !battery
        toggleButton.isEnabled = mic && overlay
        toggleButton.text = if (WakeWordService.isRunning) "Stop listening" else "Start listening"

        status.text = buildString {
            appendLine(if (mic) "✓ Microphone allowed" else "✗ Microphone not allowed yet")
            appendLine(if (overlay) "✓ Can open Claude from the background" else "✗ Background opening not allowed yet")
            appendLine(if (battery) "✓ No battery limits" else "• Battery limits on (it may stop when idle)")
            appendLine(if (claudeInstalled) "✓ Claude app found" else "✗ Claude app not installed")
            if (WakeWordService.isRunning) append("\nListening. Say \"Hi Mehitha\".")
        }

        val sms = checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val files = android.os.Environment.isExternalStorageManager()
        val notif = notificationAccess()
        linkButton.isEnabled = !dropbox.isLinked
        finishButton.isEnabled = !dropbox.isLinked
        smsButton.isEnabled = !sms
        filesButton.isEnabled = !files
        notifButton.isEnabled = !notif
        syncStatus.text = buildString {
            appendLine(if (dropbox.isLinked) "✓ Dropbox linked" else "✗ Dropbox not linked")
            appendLine(if (sms) "✓ SMS allowed" else "✗ SMS not allowed")
            appendLine(if (files) "✓ All files allowed" else "✗ Files not allowed")
            appendLine(if (notif) "✓ Capturing app messages" else "✗ Notification access off")
            append("\n").append(PhoneSync.lastStatus(this@MainActivity))
        }
    }
}
