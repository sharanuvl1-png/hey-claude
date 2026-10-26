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
import android.widget.LinearLayout
import android.widget.TextView

/** One-screen setup: grant permissions, then switch listening on or off. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var micButton: Button
    private lateinit var overlayButton: Button
    private lateinit var batteryButton: Button
    private lateinit var toggleButton: Button

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
        setContentView(layout)
    }

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
    }
}
