package com.personal.heyclaude

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Minimal Dropbox API client using the PKCE "no redirect" flow: the user
 * opens a Dropbox page, approves, and pastes the code shown back into the app.
 * [store] keeps each link apart: "dropbox" is the phone sync (an App folder app, which only sees
 * /Apps/<name>/), "dropbox_docs" is the read-only link to search your own documents (a Full Dropbox app).
 */
class DropboxClient(context: Context, store: String = "dropbox") {

    private val prefs = context.getSharedPreferences(store, Context.MODE_PRIVATE)

    var appKey: String
        get() = prefs.getString("app_key", "") ?: ""
        set(value) = prefs.edit().putString("app_key", value.trim()).apply()

    val isLinked: Boolean get() = prefs.getString("refresh_token", null) != null

    fun authorizeUrl(): String {
        val verifier = Base64.encodeToString(ByteArray(48).also { SecureRandom().nextBytes(it) },
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString("verifier", verifier).apply()
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", appKey)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("token_access_type", "offline")
            .build().toString()
    }

    fun finishLink(code: String) {
        val json = postForm("https://api.dropboxapi.com/oauth2/token", mapOf(
            "code" to code.trim(), "grant_type" to "authorization_code",
            "client_id" to appKey, "code_verifier" to (prefs.getString("verifier", "") ?: "")))
        saveToken(json)
        prefs.edit().putString("refresh_token", json.getString("refresh_token")).apply()
    }

    fun unlink() = prefs.edit().remove("refresh_token").remove("access_token").remove("expires_at").apply()

    private fun saveToken(json: JSONObject) {
        prefs.edit().putString("access_token", json.getString("access_token"))
            .putLong("expires_at", System.currentTimeMillis() + json.optLong("expires_in", 14400) * 1000 - 60_000)
            .apply()
    }

    private fun token(): String {
        val cached = prefs.getString("access_token", null)
        if (cached != null && System.currentTimeMillis() < prefs.getLong("expires_at", 0)) return cached
        val refresh = prefs.getString("refresh_token", null) ?: error("Dropbox is not linked")
        saveToken(postForm("https://api.dropboxapi.com/oauth2/token", mapOf(
            "grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to appKey)))
        return prefs.getString("access_token", "")!!
    }

    fun upload(path: String, data: InputStream) {
        val conn = open("https://content.dropboxapi.com/2/files/upload")
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("Dropbox-API-Arg",
            asciiJson(JSONObject().put("path", path).put("mode", "overwrite").put("mute", true)))
        conn.setChunkedStreamingMode(256 * 1024)
        conn.outputStream.use { out -> data.use { it.copyTo(out) } }
        read(conn)
    }

    fun upload(path: String, text: String) = upload(path, text.byteInputStream())

    /** Returns paths of files directly inside [folder]; empty if it doesn't exist yet. */
    fun listFiles(folder: String): List<String> {
        val conn = open("https://api.dropboxapi.com/2/files/list_folder")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(JSONObject().put("path", folder).toString().toByteArray()) }
        val json = try { JSONObject(read(conn)) } catch (e: DropboxError) {
            if (e.body.contains("not_found")) return emptyList() else throw e
        }
        val entries = json.getJSONArray("entries")
        return (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.getString(".tag") == "file" }
            .map { it.getString("path_display") }
    }

    /** Files in the whole Dropbox matching [query] by name or content, best matches first. */
    fun search(query: String, max: Int = 12): List<DropboxFile> {
        val conn = open("https://api.dropboxapi.com/2/files/search_v2")
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("query", query.take(200))
            .put("options", JSONObject().put("max_results", max).put("file_status", "active"))
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        return parseSearch(read(conn))
    }

    /** The file's bytes, or null when it is bigger than [max] bytes. */
    fun downloadBytes(path: String, max: Long): ByteArray? {
        val conn = open("https://content.dropboxapi.com/2/files/download")
        conn.setRequestProperty("Dropbox-API-Arg", asciiJson(JSONObject().put("path", path)))
        val code = conn.responseCode
        if (code !in 200..299) read(conn)
        val result = conn.getHeaderField("Dropbox-API-Result")?.let { JSONObject(it) }
        if ((result?.optLong("size") ?: 0) > max) { conn.disconnect(); return null }
        return conn.inputStream.use { it.readBytes() }.takeIf { it.size <= max }
    }

    fun download(path: String): String {
        val conn = open("https://content.dropboxapi.com/2/files/download")
        conn.setRequestProperty("Dropbox-API-Arg", asciiJson(JSONObject().put("path", path)))
        return read(conn)
    }

    fun delete(path: String) {
        val conn = open("https://api.dropboxapi.com/2/files/delete_v2")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(JSONObject().put("path", path).toString().toByteArray()) }
        read(conn)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer ${token()}")
        }

    private fun postForm(url: String, form: Map<String, String>): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = form.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return JSONObject(read(conn))
    }

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw DropboxError(code, body)
        return body
    }

    /** Dropbox-API-Arg must be ASCII; escape everything else. */
    private fun asciiJson(json: JSONObject): String = buildString {
        json.toString().forEach { c -> if (c.code < 128) append(c) else append("\\u%04x".format(c.code)) }
    }

    data class DropboxFile(val name: String, val path: String, val modified: String, val size: Long) {
        val folder get() = path.substringBeforeLast('/').ifEmpty { "/" }
        /** Opens the file in the Dropbox app or website (needs Sharan signed in); it doesn't expire. */
        val openUrl get() = "https://www.dropbox.com/home" + Uri.encode(folder, "/") + "?preview=" + Uri.encode(name)
    }

    companion object {
        /** Files from a files/search_v2 answer. */
        fun parseSearch(json: String): List<DropboxFile> {
            val matches = JSONObject(json).optJSONArray("matches") ?: return emptyList()
            return (0 until matches.length()).mapNotNull { i ->
                val m = matches.getJSONObject(i).optJSONObject("metadata")?.optJSONObject("metadata") ?: return@mapNotNull null
                if (m.optString(".tag") != "file") return@mapNotNull null
                DropboxFile(m.optString("name"), m.optString("path_display"), m.optString("server_modified").take(10), m.optLong("size"))
            }
        }
    }

    class DropboxError(val code: Int, val body: String) : Exception("Dropbox HTTP $code: ${body.take(200)}")
}
