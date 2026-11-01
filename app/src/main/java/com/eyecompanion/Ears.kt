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
    /** Which way of opening the mic to try first (0 = automatic best). */
    private val startMode: Int = 0,
) {
    /** For the on-screen mic test. */
    @Volatile var currentMode = -1
        private set
    @Volatile var peak = 0
        private set
    @Volatile var lastHeardText = ""
        private set
    val modeCount get() = setups.size

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

    /** Forget any half-heard audio (e.g. Pip's own voice) and start a fresh utterance. */
    fun reset() { needReset = true }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    /** Try the best mic sources in turn; older tablets don't all support VOICE_RECOGNITION. */
    /** Ways to open the mic, best first. Some tablets (e.g. Huawei) give silence on the first ones. */
    private data class MicSetup(val source: Int, val effects: Boolean, val pinBuiltIn: Boolean)
    private val setups = listOf(
        MicSetup(MediaRecorder.AudioSource.VOICE_RECOGNITION, effects = true, pinBuiltIn = true),
        MicSetup(MediaRecorder.AudioSource.MIC, effects = false, pinBuiltIn = true),
        MicSetup(MediaRecorder.AudioSource.MIC, effects = false, pinBuiltIn = false),
        MicSetup(MediaRecorder.AudioSource.VOICE_RECOGNITION, effects = false, pinBuiltIn = false),
        MicSetup(MediaRecorder.AudioSource.DEFAULT, effects = false, pinBuiltIn = false),
        MicSetup(MediaRecorder.AudioSource.CAMCORDER, effects = false, pinBuiltIn = false),
    )

    @SuppressLint("MissingPermission")
    private fun openMic(rate: Int, from: Int): Pair<AudioRecord, Int>? {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val builtIn = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        for (i in from until setups.size) {
            val m = setups[i]
            val rec = try {
                AudioRecord(m.source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, rate))
            } catch (e: Exception) { null } ?: continue
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); continue }
            // Pin to the built-in mic so a Bluetooth speaker stays in high-quality mode.
            if (m.pinBuiltIn) builtIn?.let { rec.setPreferredDevice(it) }
            try { rec.startRecording() } catch (e: Exception) { rec.release(); continue }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); continue }
            if (m.effects) try {
                // Helps the mic ignore Pip's own voice coming out of the speaker, where supported.
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
                    android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
                if (android.media.audiofx.NoiseSuppressor.isAvailable())
                    android.media.audiofx.NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
            } catch (_: Exception) {}
            return rec to i
        }
        return null
    }

    private fun loop() {
        val rate = 16000
        var setupIndex = startMode.coerceIn(0, setups.size - 1)
        var opened = openMic(rate, setupIndex) ?: openMic(rate, 0)
        if (opened == null) {
            onError("I can't open the microphone. Check Pip has microphone permission in Settings → Apps, then long-press → Retry setup.")
            running = false
            return
        }
        var rec = opened.first
        setupIndex = opened.second
        currentMode = setupIndex
        val recognizer = Recognizer(model, rate.toFloat())
        val buf = ShortArray(rate / 10) // 100 ms
        var lastPartial = ""
        var failedReads = 0
        var quietSince = System.currentTimeMillis()
        var everHeard = false
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

                // A working mic always picks up a little room noise. Dead silence means this way of
                // opening it doesn't work on this tablet, so try the next one.
                if (peak > 8) { quietSince = System.currentTimeMillis(); everHeard = true }
                else if (!everHeard && System.currentTimeMillis() - quietSince > 4000) {
                    try { rec.stop() } catch (_: Exception) {}
                    rec.release()
                    opened = openMic(rate, setupIndex + 1)
                    if (opened == null) {
                        onError("My microphone only gives me silence. Check the mic permission and that no other app is using it.")
                        break
                    }
                    rec = opened.first
                    setupIndex = opened.second
                    currentMode = setupIndex
                    quietSince = System.currentTimeMillis()
                    continue
                }

                if (paused) { onLevel(0f); continue }
                if (needReset) { recognizer.reset(); needReset = false; lastPartial = "" }
                this.peak = peak
                onLevel((sqrt(sum / n) / 2000.0).coerceIn(0.0, 1.0).toFloat())

                if (recognizer.acceptWaveForm(buf, n)) {
                    val text = JSONObject(recognizer.result).optString("text").trim()
                    if (text.isNotEmpty()) lastHeardText = text
                    lastPartial = ""
                    if (isRealSpeech(text)) onHeard(text)
                } else {
                    val p = JSONObject(recognizer.partialResult).optString("partial").trim()
                    if (p != lastPartial) { lastPartial = p; if (p.isNotEmpty()) lastHeardText = p; onPartial(p) }
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
