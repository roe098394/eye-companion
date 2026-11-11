package com.eyecompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A big, clever brain online: Claude when there's a Claude key, otherwise Groq's free tier
 * (Groq always does the hearing). Used first when there's internet and a key;
 * if it fails, Pip falls back to the PC brain, then its own.
 */
class OnlineBrain(private val systemPrompt: () -> String) {

    @Volatile var apiKey: String = ""
    /** Claude (Anthropic) key: when set, Claude does the talking and the looking; Groq still does the hearing. */
    @Volatile var claudeKey: String = ""
    /** Claude had a hiccup: leave it alone until then and use Groq instead. */
    @Volatile private var claudeRestUntil = 0L
    val claudeOk get() = claudeKey.isNotBlank() && System.currentTimeMillis() > claudeRestUntil
    @Volatile var maxReplyTokens = 220
    @Volatile var maxTurns = 12
    /** Last problem worth telling the user about (e.g. a rejected key), or "" */
    @Volatile var lastProblem = ""
    /** How much of today's free allowance is used, 0..100 (from Groq's own figures), or -1 if not known yet. */
    @Volatile var usedPercent = -1
    /** Tokens used today per model (Groq's real limit is 200,000 tokens a day per model). */
    val tokensUsed = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile var dailyLimit = 200_000
    /** When each model's daily allowance frees up again (Groq says "try again in 12m30s"). */
    val restUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun resting(m: String) = (restUntil[m] ?: 0L) > System.currentTimeMillis()
    /** The seeing brain (photos) has used up its day: how long until it's back (ms), or 0. */
    val seeingRestMs get() = if (claudeOk) 0L else ((restUntil[VISION_MODEL] ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    /** Remember Groq's "...per day... try again in 12m30s" for a model. */
    fun noteLimit(model: String, err: String) {
        if (!Regex("per day").containsMatchIn(err)) return
        Regex("try again in (?:(\\d+)h)?(?:(\\d+)m)?(?:([\\d.]+)s)?").find(err)?.let { t ->
            val secs = (t.groupValues[1].toIntOrNull() ?: 0) * 3600 + (t.groupValues[2].toIntOrNull() ?: 0) * 60 + (t.groupValues[3].toDoubleOrNull() ?: 0.0)
            if (secs > 0) restUntil[model] = System.currentTimeMillis() + (secs * 1000).toLong() + 5000
        }
    }
    /** How many of the online brains are resting right now (0..3). */
    val restingCount get() = if (!enabled || claudeOk) 0 else MODELS.count { resting(it) }

    /** Photos cost a lot of the daily allowance: send a small one (at most 512 px wide). */
    fun shrink(jpeg: ByteArray): ByteArray = try {
        val bm = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bm == null || bm.width <= 512) jpeg else {
            val small = android.graphics.Bitmap.createScaledBitmap(bm, 512, (bm.height * 512f / bm.width).toInt(), true)
            java.io.ByteArrayOutputStream().also { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
        }
    } catch (_: Exception) { jpeg }
    /** Every model is over its daily limit: nothing to do but wait (Pip naps). */
    val allResting get() = enabled && !claudeOk && MODELS.all { resting(it) }
    /** When the first model is free again (0 = not resting). */
    val backAt get() = if (allResting) MODELS.minOf { restUntil[it] ?: 0L } else 0L
    private fun recompute() {
        // Pip can carry on while ANY model has allowance left, so show the least-used one
        usedPercent = (MODELS.minOf { tokensUsed[it] ?: 0 } * 100L / dailyLimit).toInt().coerceIn(0, 100)
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val history = ArrayList<Pair<String, String>>()
    @Volatile private var stopRequested = false
    @Volatile private var lastFailure = 0L
    @Volatile private var modelIndex = 0
    @Volatile private var turn = 0
    @Volatile private var lastRateLimit = 0L

    val enabled get() = apiKey.isNotBlank() || claudeKey.isNotBlank()
    private val groqOn get() = apiKey.isNotBlank()
    val worthTrying get() = enabled && System.currentTimeMillis() - lastFailure > 20_000
    val modelName get() = if (claudeOk) "Claude" else MODELS[modelIndex]

    fun stop() { stopRequested = true }
    fun forget() = worker.execute { history.clear() }

    fun ask(
        userText: String,
        onToken: (String) -> Boolean,
        onDone: (String) -> Unit,
        onUnavailable: (String) -> Unit,
        /** a JPEG from the camera to look at, for "what can you see?" questions */
        image: ByteArray? = null,
    ) = worker.execute {
        stopRequested = false
        // share the work: alternate between the two chat brains; the seeing brain (photos) only if both are resting
        if (image == null) {
            val order = listOf(turn % 2, (turn + 1) % 2, 2); turn++
            modelIndex = order.firstOrNull { !resting(MODELS[it]) } ?: order[0]
        }
        history.add("user" to (if (image != null) "$userText (shown a photo from your camera)" else userText))
        while (history.size > maxTurns) history.removeAt(0)
        while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)

        val reply = StringBuilder()
        var problem: String? = null
        if (claudeOk) {
            try {
                streamClaude(reply, onToken, image, userText)
                lastProblem = ""
                val text = reply.toString().trim()
                if (text.isNotEmpty()) history.add("assistant" to text) else history.removeAt(history.lastIndex)
                onDone(text)
                return@execute
            } catch (e: BadKey) {
                claudeRestUntil = System.currentTimeMillis() + 30 * 60_000
                lastProblem = "The Claude key wasn't accepted (or it's out of credit): using Groq for now."
            } catch (e: Exception) {
                claudeRestUntil = System.currentTimeMillis() + 60_000
            }
            if (reply.isNotEmpty()) {          // got part of an answer before it broke: keep that
                val text = reply.toString().trim()
                history.add("assistant" to text); onDone(text); return@execute
            }
        }
        if (!groqOn) {
            history.removeAt(history.lastIndex); lastFailure = System.currentTimeMillis()
            onUnavailable("offline"); return@execute
        }
        val tries = if (image != null) 1 else MODELS.size
        if (image == null && allResting) {
            history.removeAt(history.lastIndex); lastFailure = System.currentTimeMillis()
            onUnavailable("resting"); return@execute
        }
        for (attempt in 0 until tries) {
            if (image == null && resting(MODELS[modelIndex])) { modelIndex = (modelIndex + 1) % MODELS.size; continue }
            problem = try {
                if (image != null) stream(VISION_MODEL, reply, onToken, image, userText)
                else stream(MODELS[modelIndex], reply, onToken)
                null
            } catch (e: RateLimited) {
                // free-tier limit on this model: try the next one
                if (reply.isNotEmpty()) null else { modelIndex = (modelIndex + 1) % MODELS.size; lastRateLimit = System.currentTimeMillis(); "busy" }
            } catch (e: BadKey) {
                lastProblem = "The online brain key wasn't accepted. Check it in the menu."
                "bad key"
            } catch (e: Exception) {
                e.message ?: "offline"
            }
            if (problem == null || problem == "bad key" || reply.isNotEmpty()) break
        }
        if (problem == "busy" && reply.isEmpty()) lastProblem = "Online brain busy for a minute (too many questions at once): using my own brain."
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

    /** Ask a yes/no question about a camera picture. null = couldn't tell (offline etc.). */
    fun yesNo(jpeg: ByteArray, question: String): Boolean? =
        lookAt(jpeg, "$question Answer with only the word yes or no.", maxTokens = 20, temperature = 0.0)?.lowercase()?.let {
            when { it.contains("yes") -> true; it.contains("no") -> false; else -> null }
        }

    /** One-off question about a camera picture (not added to the chat memory). null = couldn't. */
    fun lookAt(jpeg: ByteArray, question: String, system: String? = null, maxTokens: Int = 120,
               temperature: Double = 0.7): String? {
        if (claudeOk) claudeLook(jpeg, question, system, maxTokens, temperature)?.let { return it }
        return if (groqOn) groqLook(jpeg, question, system, maxTokens, temperature) else null
    }

    private fun groqLook(jpeg: ByteArray, question: String, system: String?, maxTokens: Int,
               temperature: Double): String? = try {
        val b64 = android.util.Base64.encodeToString(shrink(jpeg), android.util.Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", question))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
        val msgs = JSONArray()
        if (system != null) msgs.put(JSONObject().put("role", "system").put("content", system))
        msgs.put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", VISION_MODEL)
            .put("messages", msgs)
            .put("temperature", temperature).put("max_completion_tokens", maxTokens)
            .put("reasoning_effort", "none").put("reasoning_format", "hidden")
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 4000; readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode == 429) noteLimit(VISION_MODEL, try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" })
        if (conn.responseCode != 200) { conn.disconnect(); null } else {
            restUntil.remove(VISION_MODEL)
            val r = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
        }
    } catch (_: Exception) { null }

    // ---------------- Claude (Anthropic Messages API) ----------------

    private fun claudeImage(jpeg: ByteArray) = JSONObject().put("type", "image").put("source", JSONObject()
        .put("type", "base64").put("media_type", "image/jpeg")
        .put("data", android.util.Base64.encodeToString(shrink(jpeg), android.util.Base64.NO_WRAP)))

    private fun claudeConn(readMs: Int) = (URL(CLAUDE_ENDPOINT).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true; connectTimeout = 5000; readTimeout = readMs
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("x-api-key", claudeKey)
        setRequestProperty("anthropic-version", "2023-06-01")
    }

    private fun claudeCheck(conn: HttpURLConnection) {
        when (conn.responseCode) {
            200 -> {}
            401, 403 -> { conn.disconnect(); throw BadKey() }
            400 -> {   // "credit balance is too low" comes back as a 400
                val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
                conn.disconnect()
                if ("credit" in err.lowercase()) throw BadKey() else throw IllegalStateException("Claude HTTP 400")
            }
            else -> { val c = conn.responseCode; conn.disconnect(); throw IllegalStateException("Claude HTTP $c") }
        }
    }

    /** One-off look at a picture with Claude. null = couldn't. */
    private fun claudeLook(jpeg: ByteArray, question: String, system: String?, maxTokens: Int, temperature: Double): String? = try {
        val content = JSONArray().put(claudeImage(jpeg)).put(JSONObject().put("type", "text").put("text", question))
        val body = JSONObject().put("model", CLAUDE_MODEL).put("max_tokens", maxTokens)
            .put("temperature", temperature)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        if (system != null) body.put("system", system)
        val conn = claudeConn(20_000)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        claudeCheck(conn)
        val r = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        conn.disconnect()
        val parts = r.optJSONArray("content")
        (0 until (parts?.length() ?: 0)).joinToString("") { parts!!.getJSONObject(it).optString("text") }.trim().ifEmpty { null }
    } catch (e: BadKey) {
        claudeRestUntil = System.currentTimeMillis() + 30 * 60_000; null
    } catch (_: Exception) {
        claudeRestUntil = System.currentTimeMillis() + 60_000; null
    }

    /** Chat with Claude, streaming the reply word by word. */
    private fun streamClaude(reply: StringBuilder, onToken: (String) -> Boolean, image: ByteArray?, question: String) {
        // Claude wants user/assistant turns to alternate: glue any repeats together
        val turns = ArrayList<Pair<String, String>>()
        history.forEach { if (turns.isNotEmpty() && turns.last().first == it.first)
            turns[turns.lastIndex] = it.first to (turns.last().second + "\n" + it.second) else turns.add(it) }
        val msgs = JSONArray()
        turns.forEachIndexed { i, it ->
            if (image != null && i == turns.lastIndex) {
                val content = JSONArray().put(claudeImage(image)).put(JSONObject().put("type", "text").put("text",
                    "$question\n(This is a photo from your camera, taken just now. Describe only what is really in it.)"))
                msgs.put(JSONObject().put("role", "user").put("content", content))
            } else msgs.put(JSONObject().put("role", it.first).put("content", it.second))
        }
        val body = JSONObject().put("model", CLAUDE_MODEL).put("max_tokens", maxReplyTokens)
            .put("system", systemPrompt()).put("messages", msgs)
            .put("stream", true).put("temperature", 0.7)
        val conn = claudeConn(30_000)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        claudeCheck(conn)
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val obj = try { JSONObject(line.removePrefix("data:").trim()) } catch (_: Exception) { continue }
                when (obj.optString("type")) {
                    "content_block_delta" -> {
                        val piece = obj.optJSONObject("delta")?.optString("text").orEmpty()
                        if (piece.isNotEmpty()) {
                            reply.append(piece)
                            if (!onToken(piece) || stopRequested) break
                        }
                    }
                    "message_stop" -> break
                    "error" -> throw IllegalStateException("Claude stream error")
                }
            }
        }
        conn.disconnect()
    }

    private class RateLimited : Exception()
    private class BadKey : Exception()

    private fun stream(model: String, reply: StringBuilder, onToken: (String) -> Boolean,
                       image: ByteArray? = null, question: String = "") {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        history.forEachIndexed { i, it ->
            if (image != null && i == history.lastIndex) {
                // the latest question goes in with the photo
                val b64 = android.util.Base64.encodeToString(shrink(image), android.util.Base64.NO_WRAP)
                val content = JSONArray()
                    .put(JSONObject().put("type", "text").put("text",
                        "$question\n(This is a photo from your camera, taken just now. Describe only what is really in it.)"))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                msgs.put(JSONObject().put("role", "user").put("content", content))
            } else msgs.put(JSONObject().put("role", it.first).put("content", it.second))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("temperature", 0.6)
            // reasoning models spend some tokens thinking first, so leave room for that
            .put("max_completion_tokens", maxReplyTokens + 250)   // Groq counts this against the per-minute limit
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
        val code = conn.responseCode
        if (code == 429) {
            // "...tokens per day (TPD): Limit 200000, Used 199289..." -> the real figure
            val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
            Regex("per day.*?Limit (\\d+), Used (\\d+)").find(err)?.let { m ->
                dailyLimit = m.groupValues[1].toInt(); tokensUsed[model] = m.groupValues[2].toInt(); recompute()
                // "...Please try again in 1h2m3.4s" -> rest this model until then
                Regex("try again in (?:(\\d+)h)?(?:(\\d+)m)?(?:([\\d.]+)s)?").find(err)?.let { t ->
                    val secs = (t.groupValues[1].toIntOrNull() ?: 0) * 3600 + (t.groupValues[2].toIntOrNull() ?: 0) * 60 +
                        (t.groupValues[3].toDoubleOrNull() ?: 0.0)
                    if (secs > 0) restUntil[model] = System.currentTimeMillis() + (secs * 1000).toLong() + 5000
                }
            }
        }
        when (code) {
            200 -> { restUntil.remove(model); tokensUsed.remove(model) }
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
                val obj = JSONObject(data)
                // the last chunk says how many tokens this reply used
                (obj.optJSONObject("x_groq")?.optJSONObject("usage") ?: obj.optJSONObject("usage"))?.optInt("total_tokens", 0)
                    ?.takeIf { it > 0 }?.let { t -> tokensUsed[model] = (tokensUsed[model] ?: 0) + t; recompute() }
                val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: continue
                val piece = choice.optJSONObject("delta")?.optString("content").orEmpty()
                if (piece.isNotEmpty() && piece != "null") {
                    reply.append(piece)
                    if (!onToken(piece) || stopRequested) break
                }
            }
        }
        conn.disconnect()
    }

    /**
     * Turn a recorded sentence into text with Whisper on Groq: far better than the offline
     * ears with British (and Geordie) accents. Returns null if it couldn't (no internet etc.).
     */
    fun transcribe(audio: ShortArray, rate: Int): String? {
        if (!groqOn) return null
        // 48 kHz → 16 kHz keeps the upload small and quick
        val (pcm, outRate) = if (rate == 48000) ShortArray(audio.size / 3) { i ->
            ((audio[i * 3] + audio[i * 3 + 1] + audio[i * 3 + 2]) / 3).toShort() } to 16000 else audio to rate
        val wav = wav(pcm, outRate)
        for (model in listOf("whisper-large-v3-turbo", "whisper-large-v3")) {
            try {
                val boundary = "----pip" + System.nanoTime()
                val conn = (URL(STT_ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 4000; readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }
                conn.outputStream.use { out ->
                    fun field(name: String, value: String) = out.write(
                        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                    field("model", model)
                    field("language", "en")
                    field("response_format", "json")
                    field("temperature", "0")
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                    out.write(wav)
                    out.write("\r\n--$boundary--\r\n".toByteArray())
                }
                when (conn.responseCode) {
                    200 -> {}
                    429, 404, 400 -> { conn.disconnect(); continue }    // try the other model
                    else -> { conn.disconnect(); return null }
                }
                val text = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("text").trim()
                conn.disconnect()
                return text
            } catch (_: Exception) { return null }
        }
        return null
    }

    private fun wav(pcm: ShortArray, rate: Int): ByteArray {
        val data = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }

    companion object {
        const val CLAUDE_ENDPOINT = "https://api.anthropic.com/v1/messages"
        /** Claude Sonnet: clever and chatty, and it can see photos */
        const val CLAUDE_MODEL = "claude-sonnet-5-5"
        const val STT_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
        /** Whisper sometimes "hears" these in background noise. */
        val NOISE = setOf("british english", "british english.", "thank you", "thanks", "thank you for watching", "thanks for watching", "you", "bye", "okay", "uh", "um")
        /** the Groq model that can see pictures */
        const val VISION_MODEL = "qwen/qwen3.8-27b"
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        /** quickest first; if the free daily limit is hit, move down the list */
        val MODELS = listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b", "qwen/qwen3.8-27b")
    }
}
