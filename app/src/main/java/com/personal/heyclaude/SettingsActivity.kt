package com.personal.heyclaude

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
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
    private lateinit var speedText: TextView
    private lateinit var passStatus: TextView
    private lateinit var micRow: Ui.Row
    private lateinit var assistantRow: Ui.Row
    private lateinit var claudeRow: Ui.Row
    private lateinit var voiceRow: Ui.Row
    private lateinit var overlayRow: Ui.Row
    private lateinit var popupRow: Ui.Row
    private lateinit var batteryRow: Ui.Row
    private lateinit var voiceDebug: TextView
    private lateinit var dropboxRow: Ui.Row
    private lateinit var smsRow: Ui.Row
    private lateinit var calendarRow: Ui.Row
    private lateinit var filesRow: Ui.Row
    private lateinit var notifRow: Ui.Row
    private lateinit var linkBox: LinearLayout
    private lateinit var keyField: EditText
    private lateinit var codeField: EditText
    private lateinit var docsRow: Ui.Row
    private lateinit var docsBox: LinearLayout
    private val docs by lazy { DropboxClient(this, PhoneTools.DOCS) }
    private val work by lazy { OneDriveClient(this) }
    private lateinit var workRow: Ui.Row
    private lateinit var workBox: LinearLayout
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
        assistantRow = Ui.Row(this, "Phone's assistant", "Set") { openAssistantSettings() }
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
        speedText = Ui.text(this, "", 12.5f, Ui.MUTED)
        brain.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, d(10), 0, d(10))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(this@SettingsActivity, "Answer speed", 15f))
                addView(speedText)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = d(22) })
            addView(Ui.pill(this@SettingsActivity, "Change") { chooseModel() })
        })
        listOf(assistantRow, micRow, claudeRow, voiceRow, overlayRow).forEach { brain.addView(it.view) }
        // The basic build has no Claude app helper, so these two rows don't apply.
        if (!hasService(VoiceHelper::class.java)) { voiceRow.view.visibility = View.GONE; overlayRow.view.visibility = View.GONE }
        voiceDebug = Ui.text(this, "", 11.5f, Ui.MUTED).apply { setPadding(0, d(8), 0, 0) }
        brain.addView(voiceDebug)
        page.addView(brain)
        page.addView(Ui.spacer(this, 16))

        // Card 2: phone data.
        val data = Ui.card(this, "Phone data", "Lets Mehitha read your calendar, SMS, WhatsApp messages and files on the phone. Dropbox is optional: it copies them so the Claude app can search them too.")
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
        docsRow = Ui.Row(this, "Dropbox documents", "Link") { docsBox.visibility = View.VISIBLE }
        data.addView(docsRow.view)
        data.addView(docsLinkBox().also { docsBox = it })
        workRow = Ui.Row(this, "Work OneDrive", "Link") { workBox.visibility = View.VISIBLE }
        data.addView(workRow.view)
        data.addView(workLinkBox().also { workBox = it })
        smsRow = Ui.Row(this, "Text messages", "Allow") {
            requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS), 2)
        }
        filesRow = Ui.Row(this, "Files and photos", "Allow") {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        notifRow = Ui.Row(this, "WhatsApp & app messages", "Allow") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        calendarRow = Ui.Row(this, "Calendar", "Allow") {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 3)
        }
        popupRow = Ui.Row(this, "Alert pop-ups", "Turn on") { turnOnPopUps() }
        batteryRow = Ui.Row(this, "Alerts on time", "Allow") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        listOf(calendarRow, smsRow, filesRow, notifRow, popupRow, batteryRow).forEach { data.addView(it.view) }
        // The basic build leaves these out so Play Protect lets it install.
        if (!declares(Manifest.permission.READ_SMS)) smsRow.view.visibility = View.GONE
        if (!hasService(NotificationCatcher::class.java)) notifRow.view.visibility = View.GONE
        syncStatus = Ui.text(this, "", 12.5f, Ui.MUTED).apply { setPadding(0, d(8), 0, d(10)) }
        data.addView(syncStatus)
        data.addView(Ui.pill(this, "Sync now") {
            if (dropbox.isLinked) { SyncWorker.runNow(this); toast("Sync started") } else toast("Link Dropbox first")
        })
        page.addView(data)
        page.addView(Ui.spacer(this, 16))
        page.addView(passcodeCard())
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

    /**
     * Linking your own Dropbox for document search: a read-only "Full Dropbox" app you make once,
     * separate from the phone sync above.
     */
    private fun docsLinkBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(Ui.text(this@SettingsActivity, "One time: at dropbox.com/developers/apps tap Create app → Scoped access → Full Dropbox, " +
            "give it any name. On its Permissions tab tick files.metadata.read and files.content.read and tap Submit. " +
            "Then copy the App key from its Settings tab here.", 12.5f, Ui.MUTED))
        addView(Ui.spacer(this@SettingsActivity, 8))
        val key = Ui.field(this@SettingsActivity, "Dropbox app key (Full Dropbox)").apply { setText(docs.appKey) }
        val code = Ui.field(this@SettingsActivity, "Paste the code Dropbox shows")
        addView(key)
        addView(Ui.spacer(this@SettingsActivity, 8))
        addView(Ui.pill(this@SettingsActivity, "1 · Open Dropbox to approve") {
            docs.appKey = key.text.toString()
            if (docs.appKey.isBlank()) toast("Enter the Dropbox app key first")
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(docs.authorizeUrl())))
        })
        addView(Ui.spacer(this@SettingsActivity, 8))
        addView(code)
        addView(Ui.spacer(this@SettingsActivity, 8))
        addView(Ui.pill(this@SettingsActivity, "2 · Finish linking") {
            val c = code.text.toString()
            Thread {
                val msg = try { docs.finishLink(c); "Dropbox documents linked. Ask \"find my car insurance\"." }
                          catch (e: Exception) { "Linking failed: ${e.message?.take(120)}" }
                runOnUiThread { toast(msg); refresh() }
            }.start()
        })
        addView(Ui.spacer(this@SettingsActivity, 8))
    }

    /**
     * Linking the work OneDrive (read-only): needs an app registration in the work directory, then
     * a sign-in with a short code at microsoft.com/devicelogin.
     */
    private fun workLinkBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(Ui.text(this@SettingsActivity, "One time, signed in with your work account: at portal.azure.com open App registrations → " +
            "New registration, any name, \"Accounts in this organizational directory only\", Register. Under Authentication turn on " +
            "\"Allow public client flows\" and Save. Copy the Application (client) ID here. If the portal or the sign-in says " +
            "admin approval is needed, your IT team has to allow it.", 12.5f, Ui.MUTED))
        addView(Ui.spacer(this@SettingsActivity, 8))
        val id = Ui.field(this@SettingsActivity, "Application (client) ID").apply { setText(work.clientId) }
        addView(id)
        addView(Ui.spacer(this@SettingsActivity, 8))
        val status = Ui.text(this@SettingsActivity, "", 13f, Ui.TEXT)
        addView(Ui.pill(this@SettingsActivity, "Sign in with work account") {
            work.clientId = id.text.toString()
            if (work.clientId.isBlank()) { toast("Enter the Application (client) ID first"); return@pill }
            status.text = "Getting a sign-in code…"
            Thread {
                try {
                    val s = work.startSignIn()
                    runOnUiThread {
                        getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("code", s.userCode))
                        status.text = "Code ${s.userCode} (copied). Paste it on the Microsoft page and sign in with your work account, then come back."
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)))
                    }
                    work.waitForSignIn(s)
                    runOnUiThread { status.text = ""; toast("Work OneDrive linked. Ask \"find the Acme SOW in my work files\"."); refresh() }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Linking failed: ${e.message?.take(160)}" }
                }
            }.start()
        })
        addView(Ui.spacer(this@SettingsActivity, 8))
        addView(status)
        addView(Ui.spacer(this@SettingsActivity, 8))
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

    /** Opens Android's "Digital assistant app" setting, where you can pick Mehitha. */
    /** Card: the passcode Mehitha asks for before sharing confidential details. */
    private fun passcodeCard(): LinearLayout {
        val d = { v: Int -> Ui.dp(this, v) }
        val card = Ui.card(this, "Mehitha passcode",
            "Asked before Mehitha shares card, bank, PAN, Aadhaar, salary, policy or other confidential details. It's stored scrambled on this phone and never shown.")
        passStatus = Ui.text(this, "", 12.5f, Ui.MUTED).apply { setPadding(0, d(4), 0, d(8)) }
        card.addView(passStatus)
        val field = Ui.field(this, "New passcode").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        card.addView(field)
        card.addView(Ui.spacer(this, 8))
        card.addView(Ui.pill(this, "Save passcode") {
            val code = Passcode.normalize(field.text.toString())
            if (code.length < 4) toast("Use at least 4 digits")
            else { Passcode.set(this, code); field.setText(""); toast("Passcode saved"); refresh() }
        })
        return card
    }

    /** Pick the Claude model: Haiku is quickest, Opus thinks hardest. */
    private fun chooseModel() {
        val models = AssistantBrain.MODELS
        val current = models.indexOfFirst { it.second == AssistantActivity.model(this) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Answer speed")
            .setSingleChoiceItems(models.map { it.first }.toTypedArray(), current) { dlg, i ->
                AssistantActivity.saveModel(this, models[i].second)
                dlg.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAssistantSettings() {
        toast("Tap \"Digital assistant app\" (or \"Device assistance app\") and choose Mehitha")
        val tries = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
        for (a in tries) {
            try { startActivity(Intent(a)); return } catch (e: Exception) { /* try the next one */ }
        }
    }

    private fun isAssistant(): Boolean = try {
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    } catch (e: Exception) { false }

    /** Asks for notification permission if needed, otherwise opens the alert channel's settings to turn on pop-ups. */
    private fun turnOnPopUps() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
            return
        }
        Alerts.ensureChannel(this)
        startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, Alerts.CHANNEL))
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
        val m = AssistantActivity.model(this)
        speedText.text = (AssistantBrain.MODELS.firstOrNull { it.second == m }?.first ?: m) +
            if (m == AssistantBrain.DEFAULT_MODEL) ": quickest answers" else ": slower, more thorough answers"
        val hasKey = AssistantActivity.apiKey(this).isNotBlank()
        apiRow.set(hasKey, "Saved: Mehitha answers right in the chat. Tap to replace",
            "Recommended. Without a key Mehitha asks the Claude app for you", required = false)
        apiRow.view.setOnClickListener { apiBox.visibility = View.VISIBLE }
        assistantRow.set(isAssistant(), "On: long-press power or home to talk to Mehitha",
            "Make Mehitha open when you long-press power or home. On Samsung also set Side button → Press and hold → Digital assistant",
            required = false)
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
        docsRow.set(docs.isLinked, "Linked: Mehitha searches your Dropbox files (read-only)",
            "Link to let Mehitha find your documents in Dropbox", required = false)
        if (docs.isLinked) docsBox.visibility = View.GONE
        workRow.set(work.isLinked, "Linked: Mehitha searches your work files for work questions (read-only)",
            "Optional: let Mehitha find and read documents in your company OneDrive", required = false)
        if (work.isLinked) workBox.visibility = View.GONE
        calendarRow.set(granted(Manifest.permission.READ_CALENDAR), "Meeting reminders on",
            "Allow for meeting reminders and \"what's on today\". Add your work calendar in the phone's Calendar or Outlook app", required = false)
        smsRow.set(sms, "Syncing (OTPs skipped)", "Allow to search your SMS", required = false)
        filesRow.set(files, "Indexed", "Allow to find documents and photos", required = false)
        notifRow.set(notif, "Capturing new messages", "Allow to save WhatsApp messages", required = false)
        popupRow.set(Alerts.canPopUp(this), "On: alerts pop up on screen",
            "Allow notifications and pop-ups so the morning briefing and other alerts show on screen", required = false)
        val unrestricted = getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        batteryRow.set(unrestricted, "On: battery saver won't delay alerts",
            "Lets the morning briefing and reminders arrive on time instead of being delayed by battery saver", required = false)
        syncStatus.text = PhoneSync.lastStatus(this)
        passStatus.text = if (Passcode.isSet(this)) "Passcode set" else "Not set: confidential details stay hidden"
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
