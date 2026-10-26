package com.personal.heyclaude

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Invisible step between the wake word and Claude: turns the screen on,
 * asks to unlock if the phone is locked, then opens the Claude app.
 */
class LaunchClaudeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = openClaude()
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        } else {
            openClaude()
        }
    }

    /**
     * Prefer Claude's voice entry points so the mic is already listening,
     * then fall back to simply opening the app.
     */
    private fun openClaude() {
        val voiceFirst = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND).setPackage(CLAUDE_PACKAGE),
            Intent(Intent.ACTION_ASSIST).setPackage(CLAUDE_PACKAGE),
        )
        val launch = voiceFirst.firstOrNull { it.resolveActivity(packageManager) != null }
            ?: packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$CLAUDE_PACKAGE"))
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)
                ?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        finish()
    }

    companion object {
        const val CLAUDE_PACKAGE = "com.anthropic.claude"
    }
}
