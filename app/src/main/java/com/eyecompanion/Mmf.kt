package com.eyecompanion

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Made-up sounds for the zip on Pip's mouth: a muffled "mm-MMF mmf!" hum (talking through closed lips,
 * no words read out) and the zzzip of it being pulled open.
 */
object Mmf {
    @Volatile private var playing = false

    /** Muffled grumbling. cross = higher, louder, more syllables. */
    fun grumble(cross: Boolean = true, volume: Float = 0.55f) = play(volume) { rate -> hum(rate, cross) }

    /** The zip being pulled open. */
    fun unzip(volume: Float = 0.5f) = play(volume) { rate -> zip(rate) }

    private fun play(volume: Float, make: (Int) -> DoubleArray) {
        if (playing) return
        playing = true
        thread(name = "mmf") {
            try {
                val rate = 22050
                val pcm = make(rate).map { (it * 26000 * volume).coerceIn(-32000.0, 32000.0).toInt().toShort() }.toShortArray()
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

    private fun hum(rate: Int, cross: Boolean): DoubleArray {
        val out = ArrayList<Double>()
        val base = 150.0 + Random.nextDouble() * 40                 // Pip's "voice" pitch
        val syll = if (cross) 2 + Random.nextInt(3) else 1 + Random.nextInt(2)
        var phase = 0.0; var lp = 0.0
        for (k in 0 until syll) {
            val last = k == syll - 1
            val secs = if (last && cross) 0.38 else 0.16 + Random.nextDouble() * 0.12
            val up = if (last && cross) 1.45 else 0.9 + Random.nextDouble() * 0.35   // the cross one goes UP: "mmMF!"
            val n = (secs * rate).toInt()
            for (i in 0 until n) {
                val p = i.toDouble() / n
                val f = base * (1.0 + (up - 1.0) * p)
                phase += 2 * PI * f / rate
                // a few harmonics, then a strong low-pass: lips closed
                val raw = sin(phase) + 0.5 * sin(2 * phase) + 0.3 * sin(3 * phase) + 0.15 * sin(4 * phase) +
                    0.03 * (Random.nextDouble() * 2 - 1)
                lp += (raw - lp) * 0.12
                val att = minOf(1.0, p * 12); val rel = minOf(1.0, (1 - p) * 6)
                out.add(lp * 0.9 * att * rel * (if (last && cross) 1.25 else 1.0))
            }
            repeat((0.05 * rate).toInt()) { out.add(0.0) }
        }
        return out.toDoubleArray()
    }

    private fun zip(rate: Int): DoubleArray {
        val n = (0.45 * rate).toInt()
        val out = DoubleArray(n)
        var hp = 0.0; var prev = 0.0; var clickPhase = 0.0
        for (i in 0 until n) {
            val p = i.toDouble() / n
            val white = Random.nextDouble() * 2 - 1
            hp = 0.85 * (hp + white - prev); prev = white           // bright, scratchy noise
            clickPhase += (90 + 160 * p) / rate                      // teeth clicking past, speeding up
            val click = if (clickPhase % 1.0 < 0.18) 1.0 else 0.35
            val env = minOf(1.0, p * 20) * minOf(1.0, (1 - p) * 8)
            out[i] = hp * 0.55 * click * env
        }
        return out
    }
}
