package com.kittyeggs

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Listens for one answer from Chloe (offline, with Vosk).
 * With a word list it only listens for those words, which copes much better with little voices.
 * Optionally saves what it heard as a WAV recording (for the Christmas list).
 */
class Listener(private val model: Model) {
    class Result(val text: String, val spoke: Boolean)

    @Volatile private var stopNow = false
    fun stop() { stopNow = true }

    @SuppressLint("MissingPermission")
    fun listen(words: List<String>?, maxMs: Long, saveTo: File?, onLevel: (Float) -> Unit, onDone: (Result) -> Unit) {
        stopNow = false
        thread(name = "listen") {
            val rate = 16000
            var rec: AudioRecord? = null
            var recognizer: Recognizer? = null
            val pcm = java.io.ByteArrayOutputStream()
            var spoke = false
            var text = ""
            try {
                val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, rate))
                if (rec.state != AudioRecord.STATE_INITIALIZED)
                    rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, rate))
                recognizer = if (words != null) {
                    val g = org.json.JSONArray(); words.forEach { g.put(it.lowercase()) }; g.put("[unk]")
                    Recognizer(model, rate.toFloat(), g.toString())
                } else Recognizer(model, rate.toFloat())
                rec.startRecording()
                val buf = ShortArray(rate / 10)
                val bytes = ByteArray(buf.size * 2)
                val t0 = System.currentTimeMillis()
                var floor = 0f; var frames = 0; var lastLoud = 0L
                while (!stopNow) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) { sum += buf[i] * buf[i].toDouble(); bytes[2 * i] = (buf[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                    val rms = sqrt(sum / n).toFloat()
                    frames++
                    if (frames <= 3) floor = if (floor == 0f) rms else (floor + rms) / 2
                    val loud = rms > maxOf(500f, floor * 2.5f)
                    val now = System.currentTimeMillis()
                    if (loud) { spoke = true; lastLoud = now }
                    onLevel((rms / 4000f).coerceIn(0f, 1f))
                    pcm.write(bytes, 0, n * 2)
                    recognizer.acceptWaveForm(bytes, n * 2)
                    // done: a short pause after talking, or out of time
                    if (spoke && now - lastLoud > 1400) break
                    if (now - t0 > maxMs) break
                }
                text = JSONObject(recognizer.finalResult).optString("text").replace("[unk]", "").trim()
            } catch (_: Exception) {
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release(); recognizer?.close()
            }
            if (saveTo != null && pcm.size() > 0) try { writeWav(saveTo, pcm.toByteArray(), rate) } catch (_: Exception) {}
            onLevel(0f)
            onDone(Result(text, spoke))
        }
    }

    private fun writeWav(f: File, data: ByteArray, rate: Int) {
        f.parentFile?.mkdirs()
        RandomAccessFile(f, "rw").use { out ->
            out.setLength(0)
            fun i32(v: Int) { out.write(byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte(), (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte())) }
            fun i16(v: Int) { out.write(byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())) }
            out.write("RIFF".toByteArray()); i32(36 + data.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
            out.write("data".toByteArray()); i32(data.size); out.write(data)
        }
    }
}
