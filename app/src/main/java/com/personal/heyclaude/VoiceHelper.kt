package com.personal.heyclaude

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Works the Claude app for Mehitha, and only sees the Claude app:
 * - with no API key, types the spoken question into Claude, sends it and reads back the reply;
 * - for "Open in Claude app", taps Claude's voice button for a few seconds after opening it.
 */
class VoiceHelper : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        pending?.let { startAsk(it, pendingLabel ?: it) }
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- Asking Claude without an API key -------------------------------------------------

    private var job: AskJob? = null

    private fun startAsk(question: String, label: String) {
        pending = null
        armedUntil = 0
        job = AskJob(question, System.currentTimeMillis(), label)
        record("asking: typing the question")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(poll, POLL_MS)
    }

    private val poll = object : Runnable {
        override fun run() {
            val j = job ?: return
            try {
                step(j)
            } catch (e: Exception) {
                Log.w("HeyClaude", "Claude hand-off step failed", e)
            }
            if (job === j) handler.postDelayed(this, POLL_MS)
        }
    }

    private fun step(j: AskJob) {
        val root = rootInActiveWindow
        val onClaude = root?.packageName?.toString() == LaunchClaudeActivity.CLAUDE_PACKAGE
        val nodes = mutableListOf<Pair<ScreenText, AccessibilityNodeInfo>>()
        if (onClaude) screen(root!!, nodes, 0)
        when (val action = j.step(nodes.map { it.first }, System.currentTimeMillis())) {
            is ScreenAction.Wait -> Unit
            is ScreenAction.Type -> {
                val box = nodes[action.index].second
                box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, j.question)
                })
                record("asking: typed the question")
            }
            is ScreenAction.Tap -> { click(nodes[action.index].second); record("asking: tapped ${nodes[action.index].first.text.take(30)}") }
            is ScreenAction.Done -> {
                job = null
                record("last ask: ${action.note}" + (action.reply?.let { " (${it.length} letters)" } ?: ""))
                if (action.reply == null) remember(nodes.map { it.first.text })
                startActivity(Intent(this, AssistantActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(AssistantActivity.EXTRA_QUESTION, j.label)
                    .putExtra(AssistantActivity.EXTRA_REPLY, action.reply))
            }
        }
    }

    /** Everything on Claude's screen in order: message box, buttons and message text. */
    private fun screen(node: AccessibilityNodeInfo, out: MutableList<Pair<ScreenText, AccessibilityNodeInfo>>, depth: Int) {
        if (depth > 60) return
        if (node.isVisibleToUser) {
            val text = if (node.isEditable) node.text?.toString().orEmpty()
            else if (node.isClickable) listOfNotNull(node.contentDescription, node.text).joinToString(" ").trim()
            else (node.text ?: node.contentDescription)?.toString().orEmpty().trim()
            if (node.isEditable || text.isNotEmpty()) out += ScreenText(text, node.isClickable, node.isEditable) to node
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { screen(it, out, depth + 1) }
    }

    private fun record(what: String) {
        getSharedPreferences("voice_helper", Context.MODE_PRIVATE).edit().putString("ask", what).apply()
    }

    // ---- Voice button for "Open in Claude app" --------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (System.currentTimeMillis() > armedUntil) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != LaunchClaudeActivity.CLAUDE_PACKAGE) return
        val buttons = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        collect(root, buttons, 0)
        remember(buttons.map { it.first })
        for (pattern in PRIORITY) {
            val hit = buttons.firstOrNull { pattern.containsMatchIn(it.first) } ?: continue
            if (click(hit.second)) {
                armedUntil = 0
                lastTapped = hit.first
                return
            }
        }
        // Not on a chat screen (e.g. Projects): open a new chat once, then look again.
        if (!openedNewChat) {
            val chat = buttons.firstOrNull { NEW_CHAT.containsMatchIn(it.first) } ?: return
            if (click(chat.second)) openedNewChat = true
        }
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<Pair<String, AccessibilityNodeInfo>>, depth: Int) {
        if (depth > 40) return
        val label = listOfNotNull(node.contentDescription, node.text, node.viewIdResourceName?.substringAfter('/'))
            .joinToString(" ").trim()
        if (label.isNotEmpty() && node.isVisibleToUser && (node.isClickable || node.parent?.isClickable == true)) {
            out += label to node
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    private fun remember(labels: List<String>) {
        if (labels.isEmpty()) return
        getSharedPreferences("voice_helper", Context.MODE_PRIVATE).edit()
            .putString("seen", labels.distinct().take(25).joinToString(" · ") { it.take(40) })
            .apply()
    }

    override fun onInterrupt() {}

    companion object {
        /** Voice mode first, then dictation as a fallback. */
        private val PRIORITY = listOf(
            Regex("(?i)voice\\s*mode"),
            Regex("(?i)(start|use|talk|open).{0,12}voice|voice.{0,12}(chat|conversation)"),
            Regex("(?i)\\bvoice\\b"),
            Regex("(?i)dictat|microphone|\\bmic\\b|speak|record"),
        )
        private val NEW_CHAT = Regex("(?i)new chat|start (a )?new chat|^chat$|^chats$|new conversation")
        private const val ARM_MS = 8_000L
        @Volatile private var openedNewChat = false

        @Volatile private var armedUntil = 0L
        @Volatile var lastTapped: String? = null
            private set
        private const val POLL_MS = 600L
        @Volatile private var instance: VoiceHelper? = null
        /** A question waiting for the service to connect. */
        @Volatile var pending: String? = null
            internal set

        @Volatile private var pendingLabel: String? = null

        /** Hands [question] to Claude, shown back as [label]: call right before opening the Claude app. */
        fun ask(question: String, label: String = question) {
            val service = instance
            if (service == null) { pending = question; pendingLabel = label }
            else service.handler.post { service.startAsk(question, label) }
        }

        fun lastAsk(ctx: Context): String? =
            ctx.getSharedPreferences("voice_helper", Context.MODE_PRIVATE).getString("ask", null)

        fun arm() {
            armedUntil = System.currentTimeMillis() + ARM_MS
            openedNewChat = false
        }

        fun isEnabled(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("${ctx.packageName}/") == true

        fun seenButtons(ctx: Context): String? =
            ctx.getSharedPreferences("voice_helper", Context.MODE_PRIVATE).getString("seen", null)
    }
}
