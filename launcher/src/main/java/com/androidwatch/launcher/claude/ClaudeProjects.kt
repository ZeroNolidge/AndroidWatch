package com.androidwatch.launcher.claude

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One Claude Code project the watch can talk to.
 *
 * Messages are sent by firing a Claude Code *routine* (one routine per project,
 * created at claude.ai/code/routines with an API trigger). Replies are read
 * from a GitHub issue that the routine's session comments on (the "inbox").
 */
data class ClaudeProject(
    val name: String,
    /** Routine ID, starts with "trig_". */
    val routineId: String,
    /** The routine's API trigger token (sk-ant-oat01-…). It can only start this one routine. */
    val token: String,
    val owner: String,
    val repo: String,
    val issue: Int,
    /** Optional read-only GitHub token, needed only if the repo is private. */
    val githubToken: String? = null,
) {
    val inboxUrl get() = "https://github.com/$owner/$repo/issues/$issue"
    val id get() = routineId

    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("name", name)
        .put("routine", routineId)
        .put("token", token)
        .put("inbox", inboxUrl)
        .apply { githubToken?.let { put("gh", it) } }

    companion object {
        private val ISSUE_URL = Regex("""github\.com/([^/\s]+)/([^/\s]+)/issues/(\d+)""")

        /**
         * Parses the setup JSON (from the phone app's QR code or typed by hand).
         * Throws IllegalArgumentException with a readable message if something is wrong.
         */
        fun fromJson(json: JSONObject): ClaudeProject {
            val routine = json.optString("routine").trim()
            val token = json.optString("token").trim()
            val inbox = json.optString("inbox").trim()
            require(routine.startsWith("trig_")) { "Routine ID should start with trig_" }
            require(token.isNotEmpty()) { "Missing routine token" }
            val m = ISSUE_URL.find(inbox) ?: throw IllegalArgumentException("Inbox must be a GitHub issue link")
            return ClaudeProject(
                name = json.optString("name").trim().ifEmpty { m.groupValues[2] },
                routineId = routine,
                token = token,
                owner = m.groupValues[1],
                repo = m.groupValues[2],
                issue = m.groupValues[3].toInt(),
                githubToken = json.optString("gh").trim().ifEmpty { null },
            )
        }
    }
}

/** Saved projects, stored in this app's private storage. */
object ClaudeProjects {
    private fun prefs(c: Context) = c.getSharedPreferences("claude", Context.MODE_PRIVATE)

    fun all(c: Context): List<ClaudeProject> {
        val arr = JSONArray(prefs(c).getString("projects", "[]"))
        return (0 until arr.length()).mapNotNull { runCatching { ClaudeProject.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun current(c: Context): ClaudeProject? {
        val list = all(c)
        val id = prefs(c).getString("current", null)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun setCurrent(c: Context, p: ClaudeProject) = prefs(c).edit().putString("current", p.id).apply()

    /** Adds or replaces (same routine ID) and makes it current. */
    fun save(c: Context, p: ClaudeProject) {
        val list = all(c).filter { it.id != p.id } + p
        prefs(c).edit()
            .putString("projects", JSONArray(list.map { it.toJson() }).toString())
            .putString("current", p.id)
            .apply()
    }

    fun remove(c: Context, p: ClaudeProject) {
        val list = all(c).filter { it.id != p.id }
        prefs(c).edit().putString("projects", JSONArray(list.map { it.toJson() }).toString()).apply()
        ClaudeInbox.clear(c, p)
    }
}
