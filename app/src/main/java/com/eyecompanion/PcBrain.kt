package com.eyecompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A much bigger brain running on a PC on the same Wi-Fi, through Ollama (https://ollama.com).
 * The tablet still does the eyes, ears and voice; the PC only does the thinking.
 */
class PcBrain(private val systemPrompt: () -> String) {

    @Volatile var host: String? = null          // e.g. "192.168.1.20"
    @Volatile var model: String? = null         // e.g. "llama3.2:3b"
    @Volatile var maxReplyTokens = 220
    @Volatile var maxTurns = 12

    private val worker = Executors.newSingleThreadExecutor()
    private val history = ArrayList<Pair<String, String>>()
    @Volatile private var stopRequested = false
    @Volatile private var lastFailure = 0L

    /** Skip the PC for a minute after it couldn't be reached (e.g. out in the car). */
    val worthTrying get() = host != null && System.currentTimeMillis() - lastFailure > 60_000

    fun stop() { stopRequested = true }
    fun forget() = worker.execute { history.clear() }

    /**
     * Ask the PC. Streams pieces to onToken (return false to stop). If the PC can't be reached
     * before any words arrive, onUnavailable is called instead so the tablet's own brain can answer.
     */
    fun ask(
        userText: String,
        onToken: (String) -> Boolean,
        onDone: (String) -> Unit,
        onUnavailable: (String) -> Unit,
    ) = worker.execute {
        stopRequested = false
        val h = host ?: run { onUnavailable("no PC set"); return@execute }
        history.add("user" to userText)
        trim()
        val reply = StringBuilder()
        try {
            val m = model ?: pickModel(h) ?: throw IllegalStateException("no model installed on the PC")
            model = m
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
            history.forEach { msgs.put(JSONObject().put("role", it.first).put("content", it.second)) }
            val body = JSONObject()
                .put("model", m)
                .put("messages", msgs)
                .put("stream", true)
                .put("keep_alive", "60m")
                .put("options", JSONObject().put("num_predict", maxReplyTokens).put("temperature", 0.6))
            val conn = (URL("http://$h:$PORT/api/chat").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 1500
                readTimeout = 90_000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) throw IllegalStateException("PC said HTTP ${conn.responseCode}")
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    val j = JSONObject(line)
                    val piece = j.optJSONObject("message")?.optString("content").orEmpty()
                    if (piece.isNotEmpty()) {
                        reply.append(piece)
                        if (!onToken(piece) || stopRequested) break
                    }
                    if (j.optBoolean("done")) break
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            if (reply.isEmpty()) {
                history.removeAt(history.lastIndex)
                lastFailure = System.currentTimeMillis()
                onUnavailable(e.message ?: "PC not reachable")
                return@execute
            }
        }
        val text = reply.toString().trim()
        if (text.isNotEmpty()) history.add("assistant" to text) else history.removeAt(history.lastIndex)
        onDone(text)
    }

    private fun trim() {
        while (history.size > maxTurns) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
    }

    companion object {
        const val PORT = 11434

        /** Models installed on the PC, or null if nothing answers at that address. */
        fun listModels(host: String, timeoutMs: Int = 1500): List<String>? = try {
            val conn = (URL("http://$host:$PORT/api/tags").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = timeoutMs * 2
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val arr = JSONObject(text).optJSONArray("models") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
        } catch (_: Exception) { null }

        /** Prefer a good conversational model if several are installed. */
        fun pickModel(host: String): String? {
            val models = listModels(host) ?: return null
            val preferred = listOf("llama3.2:3b", "qwen2.5:7b", "llama3.1:8b", "gemma3:4b", "qwen2.5:3b", "phi3:mini")
            return preferred.firstOrNull { p -> models.any { it == p || it.startsWith("$p-") } }
                ?.let { p -> models.first { it == p || it.startsWith("$p-") } }
                ?: models.firstOrNull { !it.contains("embed") }
        }

        /** Look round the home Wi-Fi for a PC running Ollama. Takes a few seconds. */
        fun findPc(): String? {
            val myIps = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.startsWith("192.168.") || it.startsWith("10.") || it.matches(Regex("172\\.(1[6-9]|2\\d|3[01])\\..*")) }
            if (myIps.isEmpty()) return null
            val pool = Executors.newFixedThreadPool(48)
            try {
                val cs = ExecutorCompletionService<String?>(pool)
                var jobs = 0
                for (ip in myIps) {
                    val prefix = ip.substringBeforeLast('.')
                    for (last in 1..254) {
                        val candidate = "$prefix.$last"
                        if (candidate == ip) continue
                        cs.submit(Callable { if (listModels(candidate, 400) != null) candidate else null })
                        jobs++
                    }
                }
                val deadline = System.currentTimeMillis() + 15_000
                repeat(jobs) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) return null
                    val f = cs.poll(left, TimeUnit.MILLISECONDS) ?: return null
                    f.get()?.let { return it }
                }
                return null
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
