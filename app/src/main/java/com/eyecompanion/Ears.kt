package com.eyecompanion

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.sqrt

/**
 * Offline speech recognition (Vosk) that always records from the tablet's OWN microphone,
 * so a Bluetooth speaker can stay in high-quality music mode for output.
 */
class Ears(
    private val context: Context,
    private val model: Model,
    private val onPartial: (String) -> Unit,
    private val onHeard: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    @Volatile var paused = false
        set(value) { if (field && !value) needReset = true; field = value }
    @Volatile private var running = false
    @Volatile private var needReset = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "ears").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate)
        )
        // Pin the recording to the built-in mic so Android never switches the
        // Bluetooth speaker into low-quality "phone call" mode.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?.let { rec.setPreferredDevice(it) }

        val recognizer = Recognizer(model, rate.toFloat())
        val buf = ShortArray(rate / 10) // 100 ms
        var lastPartial = ""
        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (paused) { onLevel(0f); continue }
                if (needReset) { recognizer.reset(); needReset = false; lastPartial = "" }

                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                onLevel((sqrt(sum / n) / 3000.0).coerceIn(0.0, 1.0).toFloat())

                if (recognizer.acceptWaveForm(buf, n)) {
                    val text = JSONObject(recognizer.result).optString("text").trim()
                    lastPartial = ""
                    if (isRealSpeech(text)) onHeard(text)
                } else {
                    val p = JSONObject(recognizer.partialResult).optString("partial").trim()
                    if (p != lastPartial) { lastPartial = p; onPartial(p) }
                }
            }
        } finally {
            rec.stop(); rec.release(); recognizer.close()
        }
    }

    /** Vosk sometimes "hears" a stray word in background noise; ignore those. */
    private fun isRealSpeech(text: String): Boolean {
        if (text.isBlank()) return false
        val words = text.split(' ')
        return !(words.size == 1 && words[0] in NOISE_WORDS)
    }

    companion object {
        private val NOISE_WORDS = setOf("the", "a", "huh", "uh", "um", "hmm", "oh", "ah", "and", "but", "i", "it", "yeah")
    }
}
