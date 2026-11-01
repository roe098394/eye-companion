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
    private val onError: (String) -> Unit = {},
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

    /** Try the best mic sources in turn; older tablets don't all support VOICE_RECOGNITION. */
    @SuppressLint("MissingPermission")
    private fun openMic(rate: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val builtIn = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        for (source in intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
        )) {
            val rec = try {
                AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, rate))
            } catch (e: Exception) { null } ?: continue
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); continue }
            // Pin to the built-in mic so a Bluetooth speaker stays in high-quality mode.
            builtIn?.let { rec.setPreferredDevice(it) }
            try { rec.startRecording() } catch (e: Exception) { rec.release(); continue }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); continue }
            return rec
        }
        return null
    }

    private fun loop() {
        val rate = 16000
        val rec = openMic(rate)
        if (rec == null) {
            onError("I can't open the microphone. Close other apps using it, check the mic permission, then long-press → Retry setup.")
            running = false
            return
        }
        val recognizer = Recognizer(model, rate.toFloat())
        val buf = ShortArray(rate / 10) // 100 ms
        var lastPartial = ""
        var failedReads = 0
        var silentSince = System.currentTimeMillis()
        var warnedSilent = false
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (++failedReads > 30) { onError("The microphone stopped working. Long-press → Retry setup."); break }
                    Thread.sleep(50)
                    continue
                }
                failedReads = 0

                var sum = 0.0
                var peak = 0
                for (i in 0 until n) { val v = buf[i].toInt(); sum += v * v.toDouble(); if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v) }
                // A mic that only ever returns zeros is blocked (another app, or a privacy switch).
                if (peak > 0) { silentSince = System.currentTimeMillis(); warnedSilent = false }
                else if (!warnedSilent && System.currentTimeMillis() - silentSince > 8000) {
                    warnedSilent = true
                    onError("My microphone is giving me total silence. Is another app using it?")
                }

                if (paused) { onLevel(0f); continue }
                if (needReset) { recognizer.reset(); needReset = false; lastPartial = "" }
                onLevel((sqrt(sum / n) / 2000.0).coerceIn(0.0, 1.0).toFloat())

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
            try { rec.stop() } catch (_: Exception) {}
            rec.release(); recognizer.close()
            running = false
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
