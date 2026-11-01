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
    /** text, and average word confidence 0..1 */
    private val onHeard: (String, Float) -> Unit,
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

    /** Ways to open the mic, best first. Some tablets (e.g. Huawei) need a different source or rate. */
    private data class MicSetup(val source: Int, val rate: Int, val effects: Boolean, val pinBuiltIn: Boolean)
    private val setups = listOf(
        MicSetup(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, effects = true, pinBuiltIn = true),
        MicSetup(MediaRecorder.AudioSource.MIC, 16000, effects = false, pinBuiltIn = true),
        MicSetup(MediaRecorder.AudioSource.MIC, 48000, effects = false, pinBuiltIn = false),
        MicSetup(MediaRecorder.AudioSource.MIC, 44100, effects = false, pinBuiltIn = false),
        MicSetup(MediaRecorder.AudioSource.VOICE_RECOGNITION, 48000, effects = false, pinBuiltIn = false),
        MicSetup(9 /* UNPROCESSED */, 48000, effects = false, pinBuiltIn = false),
        MicSetup(MediaRecorder.AudioSource.CAMCORDER, 48000, effects = false, pinBuiltIn = false),
        MicSetup(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, effects = false, pinBuiltIn = false),
        MicSetup(MediaRecorder.AudioSource.DEFAULT, 16000, effects = false, pinBuiltIn = false),
    )

    private class Opened(val rec: AudioRecord, val index: Int, val rate: Int)

    @SuppressLint("MissingPermission")
    private fun openMic(from: Int): Opened? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        // A plugged-in USB or wired mic wins; otherwise the tablet's own mic.
        // (Bluetooth mics are skipped on purpose: using one drops the speaker to call quality.)
        val builtIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        for (i in from until setups.size) {
            val m = setups[i]
            val minBuf = AudioRecord.getMinBufferSize(m.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val rec = try {
                AudioRecord(m.source, m.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, m.rate))
            } catch (e: Exception) { null } ?: continue
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); continue }
            // Pin to a wired/built-in mic so a Bluetooth speaker stays in high-quality mode.
            if (m.pinBuiltIn || builtIn?.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) builtIn?.let { rec.setPreferredDevice(it) }
            try { rec.startRecording() } catch (e: Exception) { rec.release(); continue }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); continue }
            try {
                val sid = rec.audioSessionId
                if (m.effects) {
                    // Helps the mic ignore Pip's own voice coming out of the speaker, where supported.
                    if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
                        android.media.audiofx.AcousticEchoCanceler.create(sid)?.enabled = true
                    if (android.media.audiofx.NoiseSuppressor.isAvailable())
                        android.media.audiofx.NoiseSuppressor.create(sid)?.enabled = true
                }
                if (android.media.audiofx.AutomaticGainControl.isAvailable())
                    android.media.audiofx.AutomaticGainControl.create(sid)?.enabled = true
            } catch (_: Exception) {}
            return Opened(rec, i, m.rate)
        }
        return null
    }

    private fun loop() {
        var mic = openMic(startMode.coerceIn(0, setups.size - 1)) ?: openMic(0)
        if (mic == null) {
            onError("I can't open the microphone. Check Pip has microphone permission in Settings → Apps, then long-press → Retry setup.")
            running = false
            return
        }
        currentMode = mic.index
        var recognizer = Recognizer(model, mic.rate.toFloat()).apply { setWords(true) }
        var buf = ShortArray(mic.rate / 10) // 100 ms
        var lastPartial = ""
        var failedReads = 0
        var openedAt = System.currentTimeMillis()
        var loudest = 0
        // Software volume boost: some tablets record very quietly, which the speech model can't hear.
        var recentPeak = 0f
        try {
            while (running) {
                val m = mic ?: break
                val n = m.rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (++failedReads > 30) { onError("The microphone stopped working. Long-press → Retry setup."); break }
                    Thread.sleep(50)
                    continue
                }
                failedReads = 0

                var peak = 0
                for (i in 0 until n) { val v = kotlin.math.abs(buf[i].toInt()); if (v > peak) peak = v }
                if (peak > loudest) loudest = peak

                // A mic that's essentially dead (nothing above a whisper of noise) after 6 seconds:
                // this way of opening it doesn't work on this tablet, so try the next one.
                if (loudest < 40 && System.currentTimeMillis() - openedAt > 6000) {
                    try { m.rec.stop() } catch (_: Exception) {}
                    m.rec.release()
                    val next = openMic(m.index + 1)
                    if (next == null) {
                        onError("My microphone gives me almost nothing in every mode. Is the tablet's mic covered, or blocked in settings?")
                        mic = null
                        break
                    }
                    mic = next
                    currentMode = next.index
                    recognizer.close()
                    recognizer = Recognizer(model, next.rate.toFloat()).apply { setWords(true) }
                    buf = ShortArray(next.rate / 10)
                    openedAt = System.currentTimeMillis(); loudest = 0
                    continue
                }

                this.peak = peak
                if (paused) { onLevel(0f); continue }
                if (needReset) { recognizer.reset(); needReset = false; lastPartial = "" }

                // Boost quiet audio so speech peaks around 9000 (max 30x), without clipping.
                recentPeak = maxOf(peak.toFloat(), recentPeak * 0.985f)
                val gain = (8000f / maxOf(recentPeak, 400f)).coerceIn(1f, 12f)
                var sum = 0.0
                if (gain > 1.05f) for (i in 0 until n) {
                    val v = (buf[i] * gain).toInt().coerceIn(-32767, 32767)
                    buf[i] = v.toShort()
                }
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                onLevel((sqrt(sum / n) / 2000.0).coerceIn(0.0, 1.0).toFloat())

                if (recognizer.acceptWaveForm(buf, n)) {
                    val res = JSONObject(recognizer.result)
                    val text = res.optString("text").trim()
                    val words = res.optJSONArray("result")
                    val conf = if (words == null || words.length() == 0) 1f
                        else (0 until words.length()).map { words.getJSONObject(it).optDouble("conf", 1.0) }.average().toFloat()
                    if (text.isNotEmpty()) lastHeardText = text
                    lastPartial = ""
                    if (isRealSpeech(text)) onHeard(text, conf)
                } else {
                    val p = JSONObject(recognizer.partialResult).optString("partial").trim()
                    if (p != lastPartial) { lastPartial = p; if (p.isNotEmpty()) lastHeardText = p; onPartial(p) }
                }
            }
        } finally {
            val last = mic
            if (last != null) { try { last.rec.stop() } catch (_: Exception) {}; last.rec.release() }
            recognizer.close()
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
