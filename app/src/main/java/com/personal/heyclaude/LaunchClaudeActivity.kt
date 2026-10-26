package com.personal.heyclaude

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Brief assistant pop-up between the wake word and Claude: shows the orb,
 * turns the screen on, asks to unlock if needed, then opens Claude.
 */
class LaunchClaudeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.45f)
        setContentView(sheet())

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = openClaudeSoon()
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        } else {
            openClaudeSoon()
        }
    }

    /** Bottom sheet with the glowing orb, like a standard voice assistant. */
    private fun sheet(): View {
        val d = { v: Int -> Ui.dp(this, v) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(d(24), d(20), d(24), d(36))
            background = GradientDrawable().apply {
                setColor(Ui.CARD)
                cornerRadii = floatArrayOf(d(28).toFloat(), d(28).toFloat(), d(28).toFloat(), d(28).toFloat(), 0f, 0f, 0f, 0f)
            }
            addView(View(this@LaunchClaudeActivity).apply {
                background = GradientDrawable().apply { setColor(Ui.MUTED); cornerRadius = d(3).toFloat() }
            }, LinearLayout.LayoutParams(d(40), d(5)))
            addView(OrbView(this@LaunchClaudeActivity), LinearLayout.LayoutParams(d(150), d(150)).apply { topMargin = d(12) })
            addView(Ui.text(this@LaunchClaudeActivity, "Hi, I'm listening", 22f, bold = true).apply { gravity = Gravity.CENTER })
            addView(Ui.text(this@LaunchClaudeActivity, "Opening Claude voice…", 14f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, d(6), 0, 0) })
        }
        return FrameLayout(this).apply {
            setOnClickListener { finish() }
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    private fun openClaudeSoon() {
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) openClaude() }, SHEET_MS)
    }

    /**
     * Opens Claude on a fresh chat screen. Claude has no public "start voice" link,
     * so the VoiceHelper accessibility service then taps its voice button.
     */
    private fun openClaude() {
        VoiceHelper.arm()
        val launch = packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$CLAUDE_PACKAGE"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch)
        } catch (e: Exception) {
            packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)
                ?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    companion object {
        const val CLAUDE_PACKAGE = "com.anthropic.claude"
        private const val SHEET_MS = 450L
    }
}
