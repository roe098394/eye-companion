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
    /** Orpheus voice name, e.g. "troy", or "" for the tablet voice. */
    @Volatile var cloudVoice: String = ""

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
    fun beginReply() {
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
        }
        replyFinished = true
        if (pending.get() == 0) onFinishedTalking()
    }

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

    private fun say(raw: String) {
        val clean = cleanForSpeech(raw)
        if (clean.isBlank()) return
        val key = cloudKey()
        if (key.isNotBlank() && cloudVoice.isNotBlank()) { sayCloud(clean, key, cloudVoice); return }
        pending.incrementAndGet()
        tts.speak(clean, TextToSpeech.QUEUE_ADD, null, "u${counter++}")
    }

    /** Speak with Groq's Orpheus voice; any piece that fails is spoken by the tablet voice instead. */
    private fun sayCloud(text: String, key: String, voiceName: String) {
        val gen = generation
        // Orpheus takes at most 200 characters per request
        for (piece in chunks(text, 190)) {
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
                    tts.speak(piece, TextToSpeech.QUEUE_ADD, null, id)
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

    private fun fetchSpeech(text: String, key: String, voiceName: String): ByteArray? = try {
        val body = JSONObject().put("model", "canopylabs/orpheus-v1-english").put("voice", voiceName)
            .put("input", text).put("response_format", "wav")
        val conn = (URL("https://api.groq.com/openai/v1/audio/speech").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 4000; readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
        else { lastCloudError = "voice HTTP ${conn.responseCode}"; conn.disconnect(); null }
    } catch (e: Exception) { lastCloudError = e.message ?: "voice offline"; null }

    @Volatile var lastCloudError = ""

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
        s.replace(Regex("[*#_`~>|]"), " ")
            .replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}]"), "")   // emoji & symbols
            .replace(Regex("\\s+"), " ")
            .trim()
}
