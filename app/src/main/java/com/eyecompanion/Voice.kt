package com.eyecompanion

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.AudioFormat
import android.media.AudioTrack
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks replies with Android's built-in (offline) text-to-speech. Audio is tagged as media,
 * so it plays through a connected Bluetooth speaker in full quality.
 */
class Voice(context: Context, onReady: (Boolean) -> Unit) {

    var onStartedTalking: () -> Unit = {}
    var onFinishedTalking: () -> Unit = {}

    private val pending = AtomicInteger(0)
    @Volatile private var replyFinished = true
    private var counter = 0
    private val sentence = StringBuilder()

    private lateinit var tts: TextToSpeech

    /** Online voice (Groq Orpheus). Returns the key, or "" to use the tablet's own voice. */
    @Volatile var cloudKey: () -> String = { "" }
    /** Orpheus voice name, e.g. "troy", or "azure:en-GB-RyanNeural", or "" for the tablet voice. */
    @Volatile var cloudVoice: String = ""
    /** Microsoft Azure Speech key and region (e.g. "uksouth"), for the "azure:" voices. */
    @Volatile var azureKey: () -> String = { "" }
    @Volatile var azureRegion: () -> String = { "uksouth" }
    /** When the free daily limit is hit, use the tablet voice until this time (no flip-flopping). */
    @Volatile private var cloudRestingUntil = 0L

    private val isAzure get() = cloudVoice.startsWith("azure:")
    /** The key for whichever online voice is picked, or "" to use the tablet voice. */
    private fun activeKey(): String = when {
        cloudVoice.isBlank() || System.currentTimeMillis() < cloudRestingUntil -> ""
        isAzure -> azureKey()
        else -> cloudKey()
    }

    // cloud speech: sentences are fetched in parallel (2 at a time) but played strictly in order
    private val fetcher = Executors.newFixedThreadPool(2)
    private val player = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                if (tts.isLanguageAvailable(Locale.UK) >= TextToSpeech.LANG_AVAILABLE) tts.language = Locale.UK
                // prefer an on-device voice so it works offline
                tts.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                    ?.sortedByDescending { (if (it.locale.country == "GB") 1000 else 0) + it.quality }
                    ?.firstOrNull()?.let { tts.voice = it }
                tts.setSpeechRate(1.05f)
                tts.setPitch(1.15f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { onStartedTalking() }
                    override fun onDone(id: String?) { fallbackLatches.remove(id)?.countDown(); finishedOne() }
                    @Deprecated("old API") override fun onError(id: String?) { fallbackLatches.remove(id)?.countDown(); finishedOne() }
                    override fun onError(id: String?, code: Int) { fallbackLatches.remove(id)?.countDown(); finishedOne() }
                    override fun onStop(id: String?, interrupted: Boolean) { fallbackLatches.remove(id)?.countDown() }
                })
            }
            onReady(ok)
        }
    }

    /** Start a new reply. */
    // online voice: after the first sentence, gather the rest into fewer, bigger requests
    private val cloudBatch = StringBuilder()
    private var sentencesThisReply = 0

    private fun flushCloudBatch() {
        if (cloudBatch.isNotBlank()) { val t = cloudBatch.toString(); cloudBatch.clear(); sayNow(t) }
    }

    fun beginReply() {
        synchronized(sentence) { cloudBatch.clear(); sentencesThisReply = 0 }
        synchronized(sentence) { sentence.clear() }
        replyFinished = false
    }

    /** Feed streamed text; whole sentences are spoken as soon as they're complete. */
    fun feed(text: String) {
        synchronized(sentence) {
            sentence.append(text)
            while (true) {
                val cut = sentenceEnd(sentence) ?: break
                say(sentence.substring(0, cut))
                sentence.delete(0, cut)
            }
        }
    }

    /** The reply is complete; speak whatever is left. */
    fun endReply() {
        synchronized(sentence) {
            say(sentence.toString())
            sentence.clear()
            flushCloudBatch()
        }
        replyFinished = true
        if (pending.get() == 0) onFinishedTalking()
    }

    /** Still has something to say. */
    val busy get() = pending.get() > 0

    fun shutUp() {
        generation++
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
        tts.stop()
        pending.set(0)
        synchronized(sentence) { sentence.clear() }
    }

    fun shutdown() { generation++; tts.shutdown(); fetcher.shutdownNow(); player.shutdownNow() }

    /** Pitch/speed, e.g. a calmer, deeper voice for car mode. */
    fun setStyle(pitch: Float, rate: Float) {
        try { tts.setPitch(pitch); tts.setSpeechRate(rate) } catch (_: Exception) {}
    }

    /** Early morning (4:00-7:30): whisper so nobody else wakes up. Quieter and, with the online voice, whispered. */
    @Volatile var whisper = false
    /** Evening (after 7:30pm): a bit quieter, but still her normal voice. */
    @Volatile var evening = false
    private val vol get() = if (whisper) 0.3f else if (evening) 0.55f else 1f
    private fun ttsParams(): android.os.Bundle? = if (vol >= 1f) null
        else android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol) }

    /** Mouth zipped (mute button): nothing gets said. */
    @Volatile var muted = false

    /** "Mwahaha" and friends become a cheeky laugh in Pip's own voice (the online voice is told to giggle it). */
    private val GIGGLE = "Hee hee hee! Ha ha ha!"
    private val EVIL = Regex("(?i)\\(?\\bevil laugh(ter)?\\b\\)?|\\b(mw|mu|bw)a+([- ]?h+a+)+h*\\b[!.]*")

    private fun say(raw: String) {
        if (muted) return
        if (EVIL.containsMatchIn(raw)) { say(raw.replace(EVIL, " $GIGGLE ")); return }
        val clean = cleanForSpeech(raw)
        if (clean.isBlank()) return
        val online = activeKey().isNotBlank()
        if (online && sentencesThisReply++ > 0) {
            // later sentences wait and go together (fewer requests; Orpheus takes under 200 characters)
            val max = if (isAzure) 400 else 180
            if (cloudBatch.length + clean.length + 1 > max) flushCloudBatch()
            if (cloudBatch.isNotEmpty()) cloudBatch.append(' ')
            cloudBatch.append(clean)
            if (cloudBatch.length > (if (isAzure) 250 else 120)) flushCloudBatch()
            return
        }
        sayNow(clean)
    }

    private fun sayNow(clean: String) {
        if (muted) return
        val key = activeKey()
        if (key.isNotBlank()) { sayCloud(clean, key, cloudVoice); return }
        sayTablet(clean)
    }

    /**
     * The tablet's own voice goes through the same single player queue as the online voice,
     * so two voices can never talk over each other: everything is spoken strictly one after another.
     */
    private fun sayTablet(text: String) {
        val gen = generation
        pending.incrementAndGet()
        player.execute {
            if (gen != generation) { finishedOne(); return@execute }
            val done = java.util.concurrent.CountDownLatch(1)
            val id = "t${counter++}"
            fallbackLatches[id] = done
            pending.incrementAndGet()                  // balanced by the tts onDone
            if (tts.speak(text, TextToSpeech.QUEUE_ADD, ttsParams(), id) != TextToSpeech.SUCCESS) {
                fallbackLatches.remove(id); finishedOne()             // couldn't speak: don't wait for nothing
            } else done.await(30, java.util.concurrent.TimeUnit.SECONDS)
            finishedOne()
        }
    }

    /** Speak with Groq's Orpheus voice; any piece that fails is spoken by the tablet voice instead. */
    private fun sayCloud(text: String, key: String, voiceName: String) {
        val gen = generation
        // Orpheus takes at most 200 characters per request
        for (piece in chunks(text, if (voiceName.startsWith("azure:")) 900 else 190)) {
            pending.incrementAndGet()
            val audio: Future<ByteArray?> = fetcher.submit<ByteArray?> { fetchSpeech(piece, key, voiceName) }
            player.execute {
                if (gen != generation) { finishedOne(); return@execute }
                val wav = try { audio.get() } catch (_: Exception) { null }
                if (gen != generation) { finishedOne(); return@execute }
                if (wav == null || !playWav(wav, gen)) {
                    // fall back to the tablet voice for this bit, and wait for it to finish
                    val done = java.util.concurrent.CountDownLatch(1)
                    val id = "f${counter++}"
                    fallbackLatches[id] = done
                    pending.incrementAndGet()          // balanced by the tts onDone
                    tts.speak(piece, TextToSpeech.QUEUE_ADD, ttsParams(), id)
                    done.await(30, java.util.concurrent.TimeUnit.SECONDS)
                }
                finishedOne()
            }
        }
    }

    private val fallbackLatches = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

    private fun chunks(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>(); var cur = StringBuilder()
        for (w in text.split(" ")) {
            if (cur.length + w.length + 1 > max && cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun fetchSpeech(text: String, key: String, voiceName: String): ByteArray? {
        if (System.currentTimeMillis() < cloudRestingUntil) return null
        for (attempt in 0..1) {
            val r = if (voiceName.startsWith("azure:")) fetchAzure(text, key, voiceName.removePrefix("azure:"), attempt == 0)
                    else fetchOnce(text, key, voiceName, quietOn429 = attempt == 0)
            if (r !== RETRY) return r
            Thread.sleep(1500)
        }
        // still refused after waiting: the free allowance is used up, so rest the online voice
        val azure = voiceName.startsWith("azure:")
        cloudRestingUntil = System.currentTimeMillis() + if (azure) 10 * 60_000L else REST_MS
        cloudFailed(if (azure) "Azure voice busy. Tablet voice for 10 minutes."
                    else "Online voice limit reached. Using the tablet voice for a few hours.")
        return null
    }

    /** Microsoft Azure neural voice. Returns a WAV, RETRY on a rate limit, or null on failure. */
    private fun fetchAzure(text: String, key: String, name: String, quietOn429: Boolean): ByteArray? = try {
        val region = azureRegion().ifBlank { "uksouth" }.lowercase().replace(" ", "")
        val lang = name.split("-").take(2).joinToString("-")
        val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        val ssml = "<speak version='1.0' xml:lang='$lang'><voice name='$name'>" +
            (if (whisper) "<prosody rate='-8%' volume='x-soft'>$esc</prosody>" else "<prosody rate='-4%'>$esc</prosody>") +
            "</voice></speak>"
        val conn = (URL("https://$region.tts.speech.microsoft.com/cognitiveservices/v1").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 4000; readTimeout = 15_000
            setRequestProperty("Ocp-Apim-Subscription-Key", key)
            setRequestProperty("Content-Type", "application/ssml+xml")
            setRequestProperty("X-Microsoft-OutputFormat", "riff-24khz-16bit-mono-pcm")
            setRequestProperty("User-Agent", "Pip")
        }
        conn.outputStream.use { it.write(ssml.toByteArray(Charsets.UTF_8)) }
        when (conn.responseCode) {
            200 -> conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
            429 -> { conn.disconnect(); if (quietOn429) RETRY else null }
            401, 403 -> { conn.disconnect(); cloudFailed("Azure voice key or region not accepted (check them in the menu)."); null }
            else -> { val c = conn.responseCode; conn.disconnect(); cloudFailed("Azure voice failed ($c)."); null }
        }
    } catch (e: Exception) { cloudFailed("Online voice failed: ${e.message ?: "no internet"}"); null }

    /** Let the online voice try again now (e.g. after picking a voice). */
    fun wakeCloudVoice() { cloudRestingUntil = 0L }

    private val RETRY = ByteArray(0)
    private val REST_MS = 3 * 60 * 60 * 1000L

    private fun fetchOnce(text: String, key: String, voiceName: String, quietOn429: Boolean): ByteArray? = try {
        val said = when {
            text.startsWith("[") -> text
            whisper -> "[whisper] $text"                                    // early morning: actually whisper
            text.contains(GIGGLE) -> "[giggling] $text"
            else -> text
        }
        val body = JSONObject().put("model", "canopylabs/orpheus-v1-english").put("voice", voiceName)
            .put("input", said).put("response_format", "wav")
        val conn = (URL("https://api.groq.com/openai/v1/audio/speech").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 4000; readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
        else if (conn.responseCode == 429 && quietOn429) { conn.disconnect(); RETRY }
        else {
            val why = try {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                JSONObject(err).optJSONObject("error")?.optString("message") ?: err
            } catch (_: Exception) { "" }
            cloudFailed("Online voice failed (${conn.responseCode}): ${why.take(160)}")
            conn.disconnect(); null
        }
    } catch (e: Exception) { cloudFailed("Online voice failed: ${e.message ?: "no internet"}"); null }

    @Volatile var lastCloudError = ""
    /** Told when the online voice fails (shown on screen so it can be fixed). */
    @Volatile var onCloudError: (String) -> Unit = {}
    private fun cloudFailed(msg: String) { lastCloudError = msg; onCloudError(msg) }

    /** Play a WAV file through the media stream (so Bluetooth stays high quality). Blocks until done. */
    private fun playWav(wav: ByteArray, gen: Int): Boolean {
        val bb = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var rate = 24000; var channels = 1; var bits = 16
        var dataStart = -1; var dataLen = 0
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            var len = bb.getInt(pos + 4)
            if (id == "fmt ") { channels = bb.getShort(pos + 10).toInt(); rate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22).toInt() }
            if (id == "data") {
                dataStart = pos + 8
                if (len <= 0 || dataStart + len > wav.size) len = wav.size - dataStart   // streamed WAVs leave size unset
                dataLen = len; break
            }
            pos += 8 + len + (len and 1)
        }
        if (dataStart < 0 || bits != 16) return false
        val chMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(chMask).build())
            .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        if (vol < 1f) try { t.setVolume(vol) } catch (_: Exception) {}
        try {
            t.play()
            onStartedTalking()
            var off = dataStart
            val end = dataStart + dataLen
            while (off < end && gen == generation) {
                val n = minOf(4096, end - off)
                val w = t.write(wav, off, n)
                if (w <= 0) break
                off += w
            }
            if (gen == generation) {
                // let the last of the buffer drain before saying we're done
                val frames = dataLen / (2 * channels)
                val deadline = System.currentTimeMillis() + 2000
                while (gen == generation && t.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) Thread.sleep(20)
            }
        } catch (_: Exception) {
            return false
        } finally {
            try { t.stop() } catch (_: Exception) {}
            t.release()
            if (track === t) track = null
        }
        return true
    }

    private fun finishedOne() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            if (replyFinished) onFinishedTalking()
        }
    }

    private fun sentenceEnd(sb: StringBuilder): Int? {
        for (i in 0 until sb.length - 1) {
            val c = sb[i]
            if ((c == '.' || c == '!' || c == '?' || c == '\n') && sb[i + 1].isWhitespace() && i > 2) return i + 1
        }
        return null
    }

    private fun cleanForSpeech(s: String): String =
        s.replace(Regex("\\[[^\\]]{0,30}\\]"), " ")      // never read out [tags]
            .replace(Regex("[*#_`~>|\\[\\]]"), " ")
            .replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}]"), "")   // emoji & symbols
            .replace(Regex("\\s+"), " ")
            .trim()
}
