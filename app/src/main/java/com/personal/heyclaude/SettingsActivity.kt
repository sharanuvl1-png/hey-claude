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
    private lateinit var sheetStatus: TextView
    private lateinit var kiteStatus: TextView
    private lateinit var passStatus: TextView
    private lateinit var micRow: Ui.Row
    private lateinit var assistantRow: Ui.Row
    private lateinit var claudeRow: Ui.Row
    private lateinit var voiceRow: Ui.Row
    private lateinit var overlayRow: Ui.Row
    private lateinit var voiceDebug: TextView
    private lateinit var dropboxRow: Ui.Row
    private lateinit var smsRow: Ui.Row
    private lateinit var calendarRow: Ui.Row
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
        listOf(calendarRow, smsRow, filesRow, notifRow).forEach { data.addView(it.view) }
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
        page.addView(moneyCard())
        page.addView(Ui.spacer(this, 16))
        page.addView(zerodhaCard())
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
    /** Card: bank and UPI spends added to the "Mehitha Expenses" Google Sheet by themselves. */
    private fun moneyCard(): LinearLayout {
        val d = { v: Int -> Ui.dp(this, v) }
        val card = Ui.card(this, "Expense sheet",
            "New bank and UPI spends from SMS and WhatsApp go into your Mehitha Expenses sheet by themselves, and so does \"spent 250 on lunch by UPI\". Only new spends, never a backlog.")
        sheetStatus = Ui.text(this, "", 12.5f, Ui.MUTED).apply { setPadding(0, d(4), 0, d(8)) }
        card.addView(sheetStatus)
        card.addView(Ui.text(this, "1. Open the sheet → Extensions → Apps Script, paste the script, Save.\n" +
            "2. Deploy → New deployment → Web app → Execute as: Me, Who has access: Anyone → Deploy, allow.\n" +
            "3. Copy the Web app URL and paste it below.", 12.5f, Ui.MUTED).apply { setPadding(0, 0, 0, d(8)) })
        card.addView(Ui.pill(this, "Copy the script") {
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Apps Script", ExpenseSheet.SCRIPT))
            toast("Script copied")
        })
        card.addView(Ui.spacer(this, 8))
        val field = Ui.field(this, "https://script.google.com/macros/s/…/exec")
        card.addView(field)
        card.addView(Ui.spacer(this, 8))
        card.addView(Ui.pill(this, "Save link") {
            val link = field.text.toString().trim()
            if (!ExpenseSheet.isLinkValid(link)) toast("That doesn't look like an Apps Script web app link (…/exec)")
            else { ExpenseSheet.save(this, link); field.setText(""); toast("Linked. New spends will be added"); refresh() }
        })
        return card
    }

    /** Card: Zerodha holdings, read-only. */
    private fun zerodhaCard(): LinearLayout {
        val d = { v: Int -> Ui.dp(this, v) }
        val card = Ui.card(this, "Zerodha (read-only)",
            "Holdings and P&L in your briefing, \"my portfolio\", and alerts when a stock you hold breaks out or drops below its stop-loss. Mehitha can't place or change orders.")
        kiteStatus = Ui.text(this, "", 12.5f, Ui.MUTED).apply { setPadding(0, d(4), 0, d(8)) }
        card.addView(kiteStatus)
        card.addView(Ui.text(this, "Create a free personal app at developers.kite.trade with redirect URL ${Zerodha.REDIRECT}, then paste its API key and secret.",
            12.5f, Ui.MUTED).apply { setPadding(0, 0, 0, d(8)) })
        val key = Ui.field(this, "API key").apply { setText(Zerodha.apiKey(this@SettingsActivity)) }
        val secret = Ui.field(this, "API secret").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        card.addView(key)
        card.addView(Ui.spacer(this, 8))
        card.addView(secret)
        card.addView(Ui.spacer(this, 8))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Ui.pill(this@SettingsActivity, "Save") {
                if (key.text.isBlank() || secret.text.isBlank()) toast("Paste both the API key and the secret")
                else { Zerodha.saveKeys(this@SettingsActivity, key.text.toString(), secret.text.toString()); secret.setText(""); toast("Saved"); refresh() }
            })
            addView(Ui.pill(this@SettingsActivity, "Log in for today") {
                startActivity(Intent(this@SettingsActivity, ZerodhaLoginActivity::class.java))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = d(8) })
        })
        return card
    }

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
        calendarRow.set(granted(Manifest.permission.READ_CALENDAR), "Meeting reminders on",
            "Allow for meeting reminders and \"what's on today\". Add your work calendar in the phone's Calendar or Outlook app", required = false)
        smsRow.set(sms, "Syncing (OTPs skipped)", "Allow to search your SMS", required = false)
        filesRow.set(files, "Indexed", "Allow to find documents and photos", required = false)
        notifRow.set(notif, "Capturing new messages", "Allow to save WhatsApp messages", required = false)
        syncStatus.text = PhoneSync.lastStatus(this)
        sheetStatus.text = if (ExpenseSheet.url(this).isNotEmpty()) "Linked: new spends are added every hour" else "Not linked yet"
        kiteStatus.text = when {
            !Zerodha.isSetUp(this) -> "Not connected"
            Zerodha.isLoggedIn(this) -> "Logged in for today"
            else -> "Connected. Log in for today (Zerodha asks once a day)"
        }
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
