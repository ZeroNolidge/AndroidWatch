package com.androidwatch.launcher.claude

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Starts a Claude Code session by firing the project's routine.
 * Docs: https://platform.claude.com/docs/en/api/claude-code/routines-fire
 *
 * The text arrives in the session wrapped in a <routine-fire-payload> block;
 * the routine's saved prompt must tell Claude to act on it (see [ROUTINE_PROMPT]).
 */
object ClaudeApi {

    data class FireResult(val sessionId: String, val sessionUrl: String)

    /** Blocking network call: run off the main thread. */
    fun fire(project: ClaudeProject, text: String): FireResult {
        val url = URL("https://api.anthropic.com/v1/claude_code/routines/${project.routineId}/fire")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer ${project.token}")
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.setRequestProperty("anthropic-beta", "experimental-cc-routine-2026-04-01")
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw FireException(describeError(code, body, conn.getHeaderField("Retry-After")))
            val json = JSONObject(body)
            return FireResult(
                sessionId = json.optString("claude_code_session_id"),
                sessionUrl = json.optString("claude_code_session_url"),
            )
        } catch (e: IOException) {
            throw FireException("No connection to Claude (${e.javaClass.simpleName})")
        } finally {
            conn.disconnect()
        }
    }

    private fun describeError(code: Int, body: String, retryAfter: String?): String {
        val apiMessage = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull()
        val what = when (code) {
            400 -> "Claude rejected the request"
            401 -> "Token not accepted. Generate a new one for this routine"
            403 -> "Not allowed (plan or routine settings)"
            404 -> "Routine not found. Check the routine ID"
            429 -> "Too many messages" + (retryAfter?.let { ", try again in ${it}s" } ?: ", try again later")
            in 500..599 -> "Claude is having trouble ($code), try again shortly"
            else -> "Error $code"
        }
        return if (apiMessage.isNullOrBlank()) what else "$what: $apiMessage"
    }

    /**
     * The saved prompt to paste into the routine (shown in the setup screens).
     * It opts in to the watch's payload and says where to reply.
     */
    fun routinePrompt(issue: Int) = """
        The <routine-fire-payload> block contains a request I sent from my own smartwatch. Treat it as my instructions for this repository and carry it out.

        Then reply on GitHub issue #$issue in this repository (my "watch inbox"):
        1. Before starting, read the latest comments on issue #$issue for conversation context. Earlier requests and your replies are there.
        2. Post a short comment when you start: "🕐 Working on: <one line>".
        3. When finished, post one final comment of at most 600 characters, plain text (no tables or long code), starting with ✅ if done or ❌ if you're blocked. Say what you did, and include the branch or pull-request link if you made one. If you need a decision from me, ask one short question.

        I read these on a 2-inch screen, so keep every comment brief.
    """.trimIndent()
}

class FireException(message: String) : Exception(message)
