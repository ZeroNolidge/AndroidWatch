package com.androidwatch.launcher.claude

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** One line in the watch chat. */
data class ChatMessage(
    /** "me-<time>" for messages sent from the watch, "c-<commentId>" for replies. */
    val id: String,
    val fromMe: Boolean,
    val text: String,
    val time: Long,
    /** For sent messages: the Claude Code session that was started. */
    val sessionUrl: String? = null,
    /** For sent messages: "sending", "started", or an error text. */
    val status: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("me", fromMe).put("text", text).put("time", time)
        .put("session", sessionUrl).put("status", status)

    companion object {
        fun fromJson(j: JSONObject) = ChatMessage(
            id = j.getString("id"),
            fromMe = j.optBoolean("me"),
            text = j.optString("text"),
            time = j.optLong("time"),
            sessionUrl = j.optString("session").takeIf { it.isNotBlank() && it != "null" },
            status = j.optString("status").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

/**
 * Local chat history per project, plus reading Claude's replies from the
 * project's GitHub inbox issue.
 */
object ClaudeInbox {
    private const val MAX_MESSAGES = 120
    private fun prefs(c: Context) = c.getSharedPreferences("claude_inbox", Context.MODE_PRIVATE)

    @Synchronized
    fun messages(c: Context, p: ClaudeProject): List<ChatMessage> {
        val arr = JSONArray(prefs(c).getString("msgs_${p.id}", "[]"))
        return (0 until arr.length()).map { ChatMessage.fromJson(arr.getJSONObject(it)) }
    }

    /** Adds or replaces (same id) messages, keeps them in time order. */
    @Synchronized
    fun upsert(c: Context, p: ClaudeProject, new: List<ChatMessage>) {
        if (new.isEmpty()) return
        val byId = LinkedHashMap<String, ChatMessage>()
        messages(c, p).forEach { byId[it.id] = it }
        new.forEach { byId[it.id] = it }
        val list = byId.values.sortedBy { it.time }.takeLast(MAX_MESSAGES)
        prefs(c).edit().putString("msgs_${p.id}", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun clear(c: Context, p: ClaudeProject) {
        prefs(c).edit().remove("msgs_${p.id}").remove("since_${p.id}").remove("etag_${p.id}").apply()
    }

    /** Number of replies not yet seen in the chat screen (for the watch face badge). */
    fun unread(c: Context, p: ClaudeProject): Int = prefs(c).getInt("unread_${p.id}", 0)

    fun markRead(c: Context, p: ClaudeProject) = prefs(c).edit().putInt("unread_${p.id}", 0).apply()

    /** Keep polling GitHub until this time (set after sending a message). */
    fun pollUntil(c: Context): Long = prefs(c).getLong("poll_until", 0)

    fun extendPolling(c: Context, minutes: Int) =
        prefs(c).edit().putLong("poll_until", System.currentTimeMillis() + minutes * 60_000L).apply()

    /**
     * Fetches new and edited comments on the inbox issue. Blocking: call off the
     * main thread. Returns the replies that are new (not edits), for notifications.
     */
    fun refresh(c: Context, p: ClaudeProject): List<ChatMessage> {
        val prefs = prefs(c)
        // First time: only the last 3 days of the issue.
        val since = prefs.getString("since_${p.id}", null)
            ?: Instant.now().minusSeconds(3 * 24 * 3600).toString()
        val url = URL(
            "https://api.github.com/repos/${p.owner}/${p.repo}/issues/${p.issue}/comments?per_page=100&since=$since",
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "AndroidWatch-ClaudeChat")
        p.githubToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        prefs.getString("etag_${p.id}", null)?.let { conn.setRequestProperty("If-None-Match", it) }
        try {
            val code = conn.responseCode
            if ((code == 403 || code == 429) && conn.getHeaderField("X-RateLimit-Remaining") == "0") {
                throw InboxException("GitHub check limit reached (60/hour without a token). Replies will show up shortly.")
            }
            when (code) {
                304 -> return emptyList()
                200 -> {}
                401, 403, 404 -> throw InboxException(
                    if (p.githubToken == null) "Can't read the inbox issue ($code). Is the repo private? Add a GitHub token."
                    else "GitHub refused the token ($code).",
                )
                else -> throw InboxException("GitHub error $code")
            }
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            val known = messages(c, p).map { it.id }.toSet()
            var newest = since
            val replies = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val updated = o.optString("updated_at")
                if (updated > newest) newest = updated
                ChatMessage(
                    id = "c-${o.getLong("id")}",
                    fromMe = false,
                    text = cleanBody(o.optString("body")),
                    time = runCatching { Instant.parse(o.optString("created_at")).toEpochMilli() }
                        .getOrDefault(System.currentTimeMillis()),
                )
            }
            upsert(c, p, replies)
            val fresh = replies.filter { it.id !in known }
            prefs.edit()
                .putString("since_${p.id}", newest)
                .putString("etag_${p.id}", conn.getHeaderField("ETag"))
                .putInt("unread_${p.id}", unread(c, p) + fresh.size)
                .apply()
            return fresh
        } finally {
            conn.disconnect()
        }
    }

    /** Drops the "Generated by Claude Code" footer and trims whitespace. */
    fun cleanBody(body: String): String {
        val cut = body.lines().indexOfLast { it.trim() == "---" }
        val main = if (cut >= 0 && body.lines().drop(cut).any { "Claude Code" in it }) body.lines().take(cut) else body.lines()
        return main.joinToString("\n").trim()
    }
}

class InboxException(message: String) : Exception(message)
