package com.eyecompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A big, clever brain online (Groq's free tier). Used first when there's internet and a key;
 * if it fails, Pip falls back to the PC brain, then its own.
 */
class OnlineBrain(private val systemPrompt: () -> String) {

    @Volatile var apiKey: String = ""
    @Volatile var maxReplyTokens = 220
    @Volatile var maxTurns = 12
    /** Last problem worth telling the user about (e.g. a rejected key), or "" */
    @Volatile var lastProblem = ""

    private val worker = Executors.newSingleThreadExecutor()
    private val history = ArrayList<Pair<String, String>>()
    @Volatile private var stopRequested = false
    @Volatile private var lastFailure = 0L
    @Volatile private var modelIndex = 0

    val enabled get() = apiKey.isNotBlank()
    val worthTrying get() = enabled && System.currentTimeMillis() - lastFailure > 60_000
    val modelName get() = MODELS[modelIndex]

    fun stop() { stopRequested = true }
    fun forget() = worker.execute { history.clear() }

    fun ask(
        userText: String,
        onToken: (String) -> Boolean,
        onDone: (String) -> Unit,
        onUnavailable: (String) -> Unit,
    ) = worker.execute {
        stopRequested = false
        history.add("user" to userText)
        while (history.size > maxTurns) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)

        val reply = StringBuilder()
        var problem: String? = null
        for (attempt in 0 until MODELS.size) {
            problem = try {
                stream(MODELS[modelIndex], reply, onToken)
                null
            } catch (e: RateLimited) {
                // free-tier limit on this model: try the next one
                if (reply.isNotEmpty()) null else { modelIndex = (modelIndex + 1) % MODELS.size; "busy" }
            } catch (e: BadKey) {
                lastProblem = "The online brain key wasn't accepted. Check it in the menu."
                "bad key"
            } catch (e: Exception) {
                e.message ?: "offline"
            }
            if (problem == null || problem == "bad key" || reply.isNotEmpty()) break
        }
        if (problem != null && reply.isEmpty()) {
            history.removeAt(history.lastIndex)
            lastFailure = System.currentTimeMillis()
            onUnavailable(problem)
            return@execute
        }
        lastProblem = ""
        val text = reply.toString().trim()
        if (text.isNotEmpty()) history.add("assistant" to text) else history.removeAt(history.lastIndex)
        onDone(text)
    }

    private class RateLimited : Exception()
    private class BadKey : Exception()

    private fun stream(model: String, reply: StringBuilder, onToken: (String) -> Boolean) {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        history.forEach { msgs.put(JSONObject().put("role", it.first).put("content", it.second)) }
        val body = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("stream", true)
            .put("temperature", 0.6)
            // reasoning models spend some tokens thinking first, so leave room for that
            .put("max_completion_tokens", maxReplyTokens + 600)
        if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low").put("include_reasoning", false)
        else if (model.startsWith("qwen/")) body.put("reasoning_effort", "none").put("reasoning_format", "hidden")

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 4000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        when (conn.responseCode) {
            200 -> {}
            401, 403 -> throw BadKey()
            429 -> throw RateLimited()
            else -> throw IllegalStateException("online brain HTTP ${conn.responseCode}")
        }
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val choice = JSONObject(data).optJSONArray("choices")?.optJSONObject(0) ?: continue
                val piece = choice.optJSONObject("delta")?.optString("content").orEmpty()
                if (piece.isNotEmpty() && piece != "null") {
                    reply.append(piece)
                    if (!onToken(piece) || stopRequested) break
                }
            }
        }
        conn.disconnect()
    }

    companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        /** quickest first; if the free daily limit is hit, move down the list */
        val MODELS = listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b", "qwen/qwen3.8-27b")
    }
}
