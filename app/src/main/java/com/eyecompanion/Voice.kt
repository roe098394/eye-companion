package com.eyecompanion

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
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
                    override fun onDone(id: String?) { finishedOne() }
                    @Deprecated("old API") override fun onError(id: String?) { finishedOne() }
                    override fun onError(id: String?, code: Int) { finishedOne() }
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
        tts.stop()
        pending.set(0)
        synchronized(sentence) { sentence.clear() }
    }

    fun shutdown() { tts.shutdown() }

    private fun say(raw: String) {
        val clean = cleanForSpeech(raw)
        if (clean.isBlank()) return
        pending.incrementAndGet()
        tts.speak(clean, TextToSpeech.QUEUE_ADD, null, "u${counter++}")
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
