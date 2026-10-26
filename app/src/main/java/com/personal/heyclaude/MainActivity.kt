package com.personal.heyclaude

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Home screen: the assistant orb with an on/off switch, then setup cards. */
class MainActivity : Activity() {

    private val dropbox by lazy { DropboxClient(this) }
    private lateinit var orb: OrbView
    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var toggle: Button
    private lateinit var micRow: Ui.Row
    private lateinit var overlayRow: Ui.Row
    private lateinit var batteryRow: Ui.Row
    private lateinit var claudeRow: Ui.Row
    private lateinit var voiceRow: Ui.Row
    private lateinit var apiRow: Ui.Row
    private lateinit var apiBox: LinearLayout
    private lateinit var apiField: EditText
    private lateinit var voiceDebug: TextView
    private lateinit var dropboxRow: Ui.Row
    private lateinit var smsRow: Ui.Row
    private lateinit var filesRow: Ui.Row
    private lateinit var notifRow: Ui.Row
    private lateinit var linkBox: LinearLayout
    private lateinit var keyField: EditText
    private lateinit var codeField: EditText
    private lateinit var syncStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val p = Ui.dp(this, 18)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, Ui.dp(this@MainActivity, 28), p, Ui.dp(this@MainActivity, 40))
        }

        // Hero: orb, status and the main switch.
        orb = OrbView(this)
        page.addView(orb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 220)))
        headline = Ui.text(this, "", 26f, bold = true).apply { gravity = Gravity.CENTER }
        subline = Ui.text(this, "", 14.5f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 20)) }
        page.addView(headline)
        page.addView(subline)
        toggle = Ui.gradientButton(this, "") {
            val on = !WakeWordService.isRunning
            getSharedPreferences(PREFS_MAIN, MODE_PRIVATE).edit().putBoolean(KEY_LISTEN, on).apply()
            if (on) WakeWordService.start(this) else WakeWordService.stop(this)
            toggle.postDelayed({ refresh() }, 300)
        }
        page.addView(toggle)
        page.addView(Ui.spacer(this, 24))

        // Card 1: voice setup.
        val voice = Ui.card(this, "Voice assistant", "Needed once so Mehitha can listen and answer.")
        apiRow = Ui.Row(this, "Claude API key", "Add") { apiBox.visibility = View.VISIBLE }
        apiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        apiField = Ui.field(this, "Paste the key (starts with sk-ant-)")
        apiBox.addView(apiField)
        apiBox.addView(Ui.spacer(this, 8))
        apiBox.addView(Ui.pill(this, "Save key") {
            val k = apiField.text.toString().trim()
            if (!k.startsWith("sk-ant-")) toast("That doesn't look like a Claude API key. It starts with sk-ant-")
            else { AssistantActivity.saveApiKey(this, k); apiField.setText(""); apiBox.visibility = View.GONE; toast("Key saved"); refresh() }
        })
        micRow = Ui.Row(this, "Microphone", "Allow") { requestMic() }
        overlayRow = Ui.Row(this, "Pop up over other apps", "Allow") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        batteryRow = Ui.Row(this, "Always on", "Allow") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        claudeRow = Ui.Row(this, "Claude app", "Install") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${LaunchClaudeActivity.CLAUDE_PACKAGE}")))
        }
        voiceRow = Ui.Row(this, "Let Mehitha use the Claude app", "Turn on") {
            toast("Tap \"Installed apps\" → \"Mehitha voice button\" → turn it on")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        voice.addView(apiRow.view)
        voice.addView(apiBox)
        listOf(micRow, overlayRow, batteryRow, claudeRow, voiceRow).forEach { voice.addView(it.view) }
        if (!hasService(VoiceHelper::class.java)) voiceRow.view.visibility = View.GONE
        voice.addView(Ui.pill(this, "Test now") { startActivity(Intent(this, AssistantActivity::class.java)) })
        voiceDebug = Ui.text(this, "", 11.5f, Ui.MUTED).apply { setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0) }
        voice.addView(voiceDebug)
        page.addView(voice)
        page.addView(Ui.spacer(this, 16))

        // Card 2: phone data.
        val data = Ui.card(this, "Phone data", "Lets Mehitha search your SMS, WhatsApp messages, documents and files through a private Dropbox folder.")
        dropboxRow = Ui.Row(this, "Dropbox", "Link") { linkBox.visibility = View.VISIBLE }
        data.addView(dropboxRow.view)
        linkBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        keyField = Ui.field(this, "Dropbox app key").apply { setText(dropbox.appKey) }
        codeField = Ui.field(this, "Paste the code Dropbox shows")
        linkBox.addView(keyField)
        linkBox.addView(Ui.spacer(this, 8))
        linkBox.addView(Ui.pill(this, "1 · Open Dropbox to approve") {
            dropbox.appKey = keyField.text.toString()
            if (dropbox.appKey.isBlank()) toast("Enter the Dropbox app key first")
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dropbox.authorizeUrl())))
        })
        linkBox.addView(Ui.spacer(this, 8))
        linkBox.addView(codeField)
        linkBox.addView(Ui.spacer(this, 8))
        linkBox.addView(Ui.pill(this, "2 · Finish linking") { finishLink() })
        data.addView(linkBox)
        smsRow = Ui.Row(this, "Text messages", "Allow") {
            requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS), 2)
        }
        filesRow = Ui.Row(this, "Files and photos", "Allow") {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        notifRow = Ui.Row(this, "WhatsApp & app messages", "Allow") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        listOf(smsRow, filesRow, notifRow).forEach { data.addView(it.view) }
        // The basic build leaves these out so Play Protect lets it install.
        if (!declares(Manifest.permission.READ_SMS)) smsRow.view.visibility = View.GONE
        if (!hasService(NotificationCatcher::class.java)) notifRow.view.visibility = View.GONE
        syncStatus = Ui.text(this, "", 12.5f, Ui.MUTED).apply { setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 10)) }
        data.addView(syncStatus)
        data.addView(Ui.pill(this, "Sync now") {
            if (dropbox.isLinked) { SyncWorker.runNow(this); toast("Sync started") } else toast("Link Dropbox first")
        })
        page.addView(data)
        val build = try { packageManager.getPackageInfo(packageName, 0).longVersionCode } catch (e: Exception) { 0L }
        page.addView(Ui.text(this, "Mehitha build $build" + if (hasService(VoiceHelper::class.java)) (if (declares(Manifest.permission.READ_SMS)) " (full)" else " (voice)") else " (basic)", 11.5f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(this@MainActivity, 20), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(page)
        })
        if (dropbox.isLinked) SyncWorker.schedule(this)
        Alerts.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        // Installing an update stops the listener: start it again unless you switched it off.
        if (!WakeWordService.isRunning && granted(Manifest.permission.RECORD_AUDIO) &&
            getSharedPreferences(PREFS_MAIN, MODE_PRIVATE).getBoolean(KEY_LISTEN, true)) {
            try { WakeWordService.start(this) } catch (e: Exception) { Log.w("HeyClaude", "Could not start listening", e) }
            toggle.postDelayed({ refresh() }, 300)
        }
        refresh()
    }

    private fun finishLink() {
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun requestMic() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun hasService(cls: Class<*>) = try {
        packageManager.getServiceInfo(android.content.ComponentName(this, cls), 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    private fun declares(p: String) = try {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(p) == true
    } catch (e: Exception) { false }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val mic = granted(Manifest.permission.RECORD_AUDIO)
        val overlay = Settings.canDrawOverlays(this)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val claude = packageManager.getLaunchIntentForPackage(LaunchClaudeActivity.CLAUDE_PACKAGE) != null
        val listening = WakeWordService.isRunning

        orb.active = listening
        headline.text = if (listening) "Hi, I'm Mehitha" else "Mehitha is paused"
        subline.text = when {
            !mic || !overlay -> "Finish the setup below to start"
            listening -> "Say \"Hi Mehitha\" anytime and ask me anything"
            else -> "Tap Start to listen for \"Hi Mehitha\""
        }
        toggle.text = if (listening) "Pause listening" else "Start listening"
        toggle.isEnabled = mic && overlay
        toggle.alpha = if (toggle.isEnabled) 1f else 0.4f

        val hasKey = AssistantActivity.apiKey(this).isNotBlank()
        apiRow.set(hasKey, "Saved: Mehitha answers by itself. Tap to replace",
            "Optional. Without a key Mehitha asks the Claude app for you", required = false)
        apiRow.view.setOnClickListener { apiBox.visibility = View.VISIBLE }
        micRow.set(mic, "Allowed", "Needed to hear \"Hi Mehitha\"")
        overlayRow.set(overlay, "Allowed", "Lets Mehitha appear when you say \"Hi Mehitha\"")
        batteryRow.set(battery, "No battery limits",
            "Needed so it hears you every time. On Samsung also add Hi Mehitha to Battery → Never sleeping apps")
        claudeRow.set(claude, "Installed", "Install Claude: Mehitha asks it when there is no API key")
        voiceRow.set(VoiceHelper.isEnabled(this), "On: types your question into Claude and reads the reply",
            "Turn on \"Mehitha voice button\" in Accessibility. If it's greyed out: App info → ⋮ → Allow restricted settings")
        voiceDebug.text = listOfNotNull(
            LaunchClaudeActivity.lastLaunch?.let { "Claude opened by: $it" },
            VoiceHelper.lastTapped?.let { "Last tapped in Claude: $it" },
            VoiceHelper.lastAsk(this)?.let { "Claude app: $it" },
            VoiceHelper.seenButtons(this)?.let { "Claude buttons seen: $it" },
        ).joinToString("\n")

        val sms = granted(Manifest.permission.READ_SMS)
        val files = Environment.isExternalStorageManager()
        val notif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        dropboxRow.set(dropbox.isLinked, "Linked", "Link your Dropbox app key")
        if (dropbox.isLinked) linkBox.visibility = View.GONE
        smsRow.set(sms, "Syncing (OTPs skipped)", "Allow to search your SMS", required = false)
        filesRow.set(files, "Indexed", "Allow to find documents and photos", required = false)
        notifRow.set(notif, "Capturing new messages", "Allow to save WhatsApp messages", required = false)
        syncStatus.text = PhoneSync.lastStatus(this)
    }

    companion object {
        private const val PREFS_MAIN = "main"
        private const val KEY_LISTEN = "listen"
    }
}
