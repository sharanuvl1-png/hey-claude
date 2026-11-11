package com.personal.heyclaude

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Your Mehitha passcode, needed before Mehitha shares confidential details (card, bank, PAN,
 * Aadhaar, salary, policy numbers...). Only a salted hash is kept on the phone, never the passcode.
 */
object Passcode {
    private const val PREFS = "passcode"
    /** What Claude answers when a question needs the passcode first. */
    const val NEEDED = "PASSCODE_NEEDED"
    const val ASK = "Please say your Mehitha passcode."
    const val WRONG = "Sorry, that's not right, so I can't share that."
    const val NOT_SET = "That's private. Set your Mehitha passcode in Settings first, then ask again."
    const val USE_CHAT = "That's private. Please ask in the Mehitha chat, where I can check your passcode."

    fun isSet(ctx: Context) = prefs(ctx).contains("hash")

    /** Keeps only digits and letters, so "247 249" and "247249" are the same. */
    fun normalize(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

    fun set(ctx: Context, code: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString("salt", salt).putString("hash", hash(salt, normalize(code))).apply()
    }

    fun check(ctx: Context, said: String): Boolean {
        val p = prefs(ctx)
        val salt = p.getString("salt", null) ?: return false
        val want = p.getString("hash", null) ?: return false
        return MessageDigest.isEqual(hash(salt, normalize(said)).toByteArray(), want.toByteArray())
    }

    /** True when Claude's reply is asking for the passcode. */
    fun isNeeded(reply: String) = reply.contains(NEEDED)

    fun hash(salt: String, code: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$code".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
