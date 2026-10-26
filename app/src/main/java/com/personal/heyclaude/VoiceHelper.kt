package com.personal.heyclaude

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Right after the wake word, finds the voice button in the Claude app and taps it,
 * so Claude is already listening. It is armed for a few seconds per wake word and
 * only receives events from the Claude app.
 */
class VoiceHelper : AccessibilityService() {

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
