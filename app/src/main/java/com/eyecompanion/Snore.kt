package com.eyecompanion

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** A real (made-up) snore: a rumbly breath in, a soft whooshy breath out, twice. No words, no "zzz" read out. */
object Snore {
    @Volatile private var playing = false

    fun play(volume: Float = 0.6f) {
        if (playing) return
        playing = true
        thread(name = "snore") {
            try {
                val rate = 22050
                val pcm = make(rate, volume)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / rate + 200)
                track.release()
            } catch (_: Exception) {
            } finally { playing = false }
        }
    }

    private fun make(rate: Int, volume: Float): ShortArray {
        val out = ArrayList<Short>()
        var brown = 0.0; var low = 0.0; var high = 0.0
        fun seg(secs: Double, snore: Boolean) {
            val n = (secs * rate).toInt()
            val rattle = 24.0 + Random.nextDouble() * 8          // how fast the snore "rattles"
            for (i in 0 until n) {
                val t = i.toDouble() / rate; val p = i.toDouble() / n
                val white = Random.nextDouble() * 2 - 1
                brown = (brown + white * 0.04) * 0.996
                low += (brown - low) * 0.08                       // deep rumble
                high += (white - high) * 0.25                     // airy whoosh
                val env = sin(PI * p).let { it * it }
                val s = if (snore) {
                    val buzz = 0.55 + 0.45 * sin(2 * PI * rattle * t)
                    low * 9.0 * buzz * buzz * env
                } else high * 0.35 * env * (1 - p * 0.3)
                out.add((s * 26000 * volume).coerceIn(-32000.0, 32000.0).toInt().toShort())
            }
        }
        fun gap(secs: Double) { repeat((secs * rate).toInt()) { out.add(0) } }
        repeat(2) { seg(1.3, true); gap(0.2); seg(1.6, false); gap(0.7) }
        return out.toShortArray()
    }
}
