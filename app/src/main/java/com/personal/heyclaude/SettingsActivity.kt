package com.personal.heyclaude

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Settings: the Claude API key, voice and Claude app setup, and phone data through Dropbox. */
class SettingsActivity : Activity() {

    private val dropbox by lazy { DropboxClient(this) }
    private lateinit var apiRow: Ui.Row
    private lateinit var apiBox: LinearLayout
    private lateinit var apiField: EditText
    private lateinit var micRow: Ui.Row
    private lateinit var claudeRow: Ui.Row
    private lateinit var voiceRow: Ui.Row
    private lateinit var overlayRow: Ui.Row
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
        val d = { v: Int -> Ui.dp(this, v) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(18), d(12), d(18), d(40))
        }
        page.addView(Ui.topBar(this, "Settings", null, back = { finish() }))
        page.addView(Ui.spacer(this, 12))

        // Card 1: how Mehitha answers.
        val brain = Ui.card(this, "Assistant", "How Mehitha answers your questions.")
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
        claudeRow = Ui.Row(this, "Claude app", "Install") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${LaunchClaudeActivity.CLAUDE_PACKAGE}")))
        }
        voiceRow = Ui.Row(this, "Let Mehitha use the Claude app", "Turn on") {
            toast("Tap \"Installed apps\" → \"Mehitha voice button\" → turn it on")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        overlayRow = Ui.Row(this, "Show Claude's reply", "Allow") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        brain.addView(apiRow.view)
        brain.addView(apiBox)
        listOf(micRow, claudeRow, voiceRow, overlayRow).forEach { brain.addView(it.view) }
        // The basic build has no Claude app helper, so these two rows don't apply.
        if (!hasService(VoiceHelper::class.java)) { voiceRow.view.visibility = View.GONE; overlayRow.view.visibility = View.GONE }
        voiceDebug = Ui.text(this, "", 11.5f, Ui.MUTED).apply { setPadding(0, d(8), 0, 0) }
        brain.addView(voiceDebug)
        page.addView(brain)
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
        syncStatus = Ui.text(this, "", 12.5f, Ui.MUTED).apply { setPadding(0, d(8), 0, d(10)) }
        data.addView(syncStatus)
        data.addView(Ui.pill(this, "Sync now") {
            if (dropbox.isLinked) { SyncWorker.runNow(this); toast("Sync started") } else toast("Link Dropbox first")
        })
        page.addView(data)
        page.addView(Ui.text(this, buildLabel(this), 11.5f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, d(20), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(page)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun finishLink() {
        val code = codeField.text.toString()
        Thread {
            val msg = try {
                dropbox.finishLink(code)
                SyncWorker.schedule(this)
                SyncWorker.runNow(this)
                RequestWorker.start(this)
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

    private fun hasService(cls: Class<*>) = hasService(this, cls)

    private fun declares(p: String) = declares(this, p)

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val hasKey = AssistantActivity.apiKey(this).isNotBlank()
        apiRow.set(hasKey, "Saved: Mehitha answers right in the chat. Tap to replace",
            "Recommended. Without a key Mehitha asks the Claude app for you", required = false)
        apiRow.view.setOnClickListener { apiBox.visibility = View.VISIBLE }
        micRow.set(granted(Manifest.permission.RECORD_AUDIO), "Allowed", "Needed to ask by voice", required = false)
        val claude = packageManager.getLaunchIntentForPackage(LaunchClaudeActivity.CLAUDE_PACKAGE) != null
        claudeRow.set(claude, "Installed", "Install Claude: Mehitha asks it when there is no API key", required = !hasKey)
        voiceRow.set(VoiceHelper.isEnabled(this), "On: types your question into Claude and brings back the reply",
            "Turn on \"Mehitha voice button\" in Accessibility. If it's greyed out: App info → ⋮ → Allow restricted settings",
            required = false)
        overlayRow.set(Settings.canDrawOverlays(this), "Allowed", "Lets Claude's reply pop up when it's ready", required = false)
        voiceDebug.text = listOfNotNull(
            LaunchClaudeActivity.lastLaunch?.let { "Claude opened by: $it" },
            VoiceHelper.lastTapped?.let { "Last tapped in Claude: $it" },
            VoiceHelper.lastAsk(this)?.let { "Claude app: $it" },
            VoiceHelper.seenButtons(this)?.let { "Claude buttons seen: $it" },
        ).joinToString("\n")

        val sms = granted(Manifest.permission.READ_SMS)
        val files = Environment.isExternalStorageManager()
        val notif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        dropboxRow.set(dropbox.isLinked, "Linked", "Link your Dropbox app key", required = false)
        if (dropbox.isLinked) linkBox.visibility = View.GONE
        smsRow.set(sms, "Syncing (OTPs skipped)", "Allow to search your SMS", required = false)
        filesRow.set(files, "Indexed", "Allow to find documents and photos", required = false)
        notifRow.set(notif, "Capturing new messages", "Allow to save WhatsApp messages", required = false)
        syncStatus.text = PhoneSync.lastStatus(this)
    }

    companion object {
        fun hasService(ctx: Context, cls: Class<*>) = try {
            ctx.packageManager.getServiceInfo(ComponentName(ctx, cls), 0); true
        } catch (e: PackageManager.NameNotFoundException) { false }

        fun declares(ctx: Context, p: String) = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(p) == true
        } catch (e: Exception) { false }

        fun buildLabel(ctx: Context): String {
            val build = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode } catch (e: Exception) { 0L }
            val flavor = if (hasService(ctx, VoiceHelper::class.java)) (if (declares(ctx, Manifest.permission.READ_SMS)) "full" else "voice") else "basic"
            return "Mehitha build $build ($flavor)"
        }
    }
}
