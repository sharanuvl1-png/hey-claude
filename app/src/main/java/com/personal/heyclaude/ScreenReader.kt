package com.personal.heyclaude

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Saves the text on screen while you use an app you picked in Settings (App screens), such as
 * your Zerodha holdings or a Blinkit order, so Mehitha can answer from it later. Only the
 * picked apps; never password boxes or OTPs; kept on the phone only (not synced to Dropbox).
 * It reads, never taps or types.
 */
class ScreenReader : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPackage: String? = null
    private val capture = Runnable { pendingPackage?.let { save(it) } }

    override fun onServiceConnected() {
        instance = this
        applyApps()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Listens only to the picked apps, so other apps' screens never reach Mehitha. */
    fun applyApps() {
        val apps = ScreenApps.chosen(this)
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = if (apps.isEmpty()) 0 else AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = if (apps.isEmpty()) arrayOf(packageName) else apps.toTypedArray()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 500
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || !ScreenApps.isChosen(this, pkg)) return
        // Wait until the screen settles, then save it once.
        pendingPackage = pkg
        handler.removeCallbacks(capture)
        handler.postDelayed(capture, SETTLE_MS)
    }

    private fun save(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val lines = mutableListOf<String>()
        collect(root, lines)
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        ScreenApps.save(this, label, lines)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int = 0) {
        if (node == null || depth > 40 || out.size > 400) return
        if (!node.isPassword) {
            listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { out += it }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    override fun onInterrupt() {}

    companion object {
        private const val SETTLE_MS = 1500L
        @Volatile var instance: ScreenReader? = null

        fun isEnabled(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("${ctx.packageName}/${ScreenReader::class.java.name}") == true ||
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?.contains("${ctx.packageName}/.ScreenReader") == true

        fun openSettings(ctx: Context) = ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The apps whose screens Mehitha may save, and the saved screens (phone only). */
object ScreenApps {
    private const val PREFS = "screen_apps"
    private const val KEEP_DAYS = 30L
    private const val MAX_CHARS = 2000
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    /** Card, account and ID numbers are masked before saving: only the last 4 digits stay. */
    private val LONG_NUMBER = Regex("\\b(\\d[ -]?){8,}(\\d{4})\\b")
    private val PAN = Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b")

    fun chosen(ctx: Context): Set<String> = prefs(ctx).getStringSet("apps", emptySet()).orEmpty().toSet()

    fun isChosen(ctx: Context, pkg: String) = pkg in chosen(ctx)

    fun choose(ctx: Context, apps: Set<String>) {
        prefs(ctx).edit().putStringSet("apps", apps).apply()
        ScreenReader.instance?.applyApps()
    }

    /** One screen as a single line: OTP lines dropped, long numbers masked, repeats skipped. */
    fun clean(lines: List<String>): String = lines.asSequence()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() && !PhoneSync.looksLikeOtp(it) }
        .map { it.replace(LONG_NUMBER) { m -> "••••" + m.groupValues[2] }.replace(PAN, "••••") }
        .distinct().joinToString(" · ").take(MAX_CHARS)

    /** Saves a screen of [app] unless it's the same as the last one saved for it. */
    fun save(ctx: Context, app: String, lines: List<String>, day: LocalDate = PhoneData.today(), time: LocalTime = LocalTime.now()) {
        val text = clean(lines)
        if (text.length < 20) return
        val p = prefs(ctx)
        if (p.getString("last:$app", null) == text.hashCode().toString()) return
        p.edit().putString("last:$app", text.hashCode().toString()).apply()
        val dir = dir(ctx).apply { mkdirs() }
        File(dir, "$day.txt").appendText("${time.format(TIME)} | $app | $text\n")
        dir.listFiles()?.filter { f -> f.nameWithoutExtension.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.isBefore(day.minusDays(KEEP_DAYS)) == true }
            ?.forEach { it.delete() }
    }

    fun dir(ctx: Context) = File(ctx.filesDir, "screens")

    /** Forgets every saved screen. */
    fun clear(ctx: Context) { dir(ctx).deleteRecursively(); prefs(ctx).edit().clear().putStringSet("apps", chosen(ctx)).apply() }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
