package com.personal.heyclaude

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One bubble in a chat: yours or Mehitha's, with any pictures and shop buttons. */
data class ChatEntry(
    val mine: Boolean,
    val text: String,
    val speech: String? = null,
    val images: List<String> = emptyList(),
    val links: List<Pair<String, String>> = emptyList(),
    val problem: Boolean = false,
)

/** A saved chat: [id] is when it started (milliseconds). */
data class SavedChat(val id: Long, val entries: List<ChatEntry>) {
    val title get() = entries.firstOrNull { it.mine }?.text?.take(60) ?: "Chat"
}

/**
 * Chats kept on the phone (app storage only), one file each, so the chat is still there
 * after the app closes and past chats can be opened from History. The newest 50 are kept.
 */
object ChatStore {
    private const val MAX_CHATS = 50
    private const val PREFS = "chats"
    /** Shown instead of an answer given after the passcode, which is never saved. */
    const val HIDDEN = "🔒 Private answer, not saved. Ask again with your passcode."

    private fun dir(ctx: Context) = File(ctx.filesDir, "chats").apply { mkdirs() }

    fun currentId(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("current", 0L)
        .takeIf { it > 0 } ?: newChat(ctx)

    fun newChat(ctx: Context): Long = System.currentTimeMillis().also { setCurrent(ctx, it) }

    fun setCurrent(ctx: Context, id: Long) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("current", id).apply()

    fun save(ctx: Context, chat: SavedChat) {
        if (chat.entries.isEmpty()) return
        File(dir(ctx), "${chat.id}.json").writeText(toJson(chat.entries))
        (dir(ctx).listFiles() ?: emptyArray()).sortedByDescending { it.name }.drop(MAX_CHATS).forEach { it.delete() }
    }

    fun load(ctx: Context, id: Long): SavedChat {
        val f = File(dir(ctx), "$id.json")
        return SavedChat(id, if (f.exists()) try { fromJson(f.readText()) } catch (e: Exception) { emptyList() } else emptyList())
    }

    /** Past chats, newest first. */
    fun all(ctx: Context): List<SavedChat> = (dir(ctx).listFiles() ?: emptyArray())
        .mapNotNull { it.name.removeSuffix(".json").toLongOrNull() }.sortedDescending().map { load(ctx, it) }
        .filter { it.entries.isNotEmpty() }

    fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

    fun toJson(entries: List<ChatEntry>): String = JSONArray(entries.map { e ->
        JSONObject().put("mine", e.mine).put("text", e.text).put("speech", e.speech ?: JSONObject.NULL)
            .put("images", JSONArray(e.images)).put("problem", e.problem)
            .put("links", JSONArray(e.links.map { (l, u) -> JSONArray(listOf(l, u)) }))
    }).toString()

    fun fromJson(json: String): List<ChatEntry> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val imgs = o.optJSONArray("images") ?: JSONArray()
            val links = o.optJSONArray("links") ?: JSONArray()
            ChatEntry(o.getBoolean("mine"), o.getString("text"), if (o.isNull("speech")) null else o.optString("speech"),
                (0 until imgs.length()).map { imgs.getString(it) },
                (0 until links.length()).map { links.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } },
                o.optBoolean("problem"))
        }
    }

    /** The text turns to give Claude so follow-ups in a reopened chat keep their context. */
    fun turns(entries: List<ChatEntry>): List<Pair<Boolean, String>> =
        entries.filter { it.text != HIDDEN && it.text != "••••••" && !it.problem }.map { it.mine to it.text }
}
