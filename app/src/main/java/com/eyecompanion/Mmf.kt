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

    /** "MWAHAHAHA": an evil laugh, made up from scratch (no text-to-speech saying "mwahaha"). Blocks while it plays. */
    fun evilLaughNow(volume: Float = 0.7f) {
        try {
            val rate = 22050
            val pcm = laugh(rate).map { (it * 26000 * volume).coerceIn(-32000.0, 32000.0).toInt().toShort() }.toShortArray()
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / rate + 150)
            track.release()
        } catch (_: Exception) {}
    }

    /** A tiny formant synthesiser: a buzzy voice through three resonances ("ah"), with an "m" or "h" in front. */
    private fun laugh(rate: Int): DoubleArray {
        val out = ArrayList<Double>()
        var phase = 0.0
        class Res(f: Double, bw: Double) {
            val c = -Math.exp(-2 * PI * bw / rate); val b = 2 * Math.exp(-PI * bw / rate) * Math.cos(2 * PI * f / rate); val a = 1 - b - c
            var y1 = 0.0; var y2 = 0.0
            fun run(x: Double): Double { val y = a * x + b * y1 + c * y2; y2 = y1; y1 = y; return y }
        }
        fun syll(secs: Double, f0a: Double, f0b: Double, start: Char, loud: Double) {
            val n = (secs * rate).toInt()
            val r1 = Res(760.0, 90.0); val r2 = Res(1220.0, 110.0); val r3 = Res(2600.0, 170.0)
            val m1 = Res(260.0, 60.0)                                     // the closed-lips "m"
            val cons = (if (start == 'm') 0.07 else 0.05) * rate
            for (i in 0 until n) {
                val p = i.toDouble() / n
                val f0 = (f0a + (f0b - f0a) * p) * (1 + 0.025 * sin(2 * PI * 6.0 * i / rate))   // a bit of wobble
                phase += f0 / rate
                val pulse = if (phase % 1.0 < f0 / rate) 1.0 else 0.0
                val noise = Random.nextDouble() * 2 - 1
                val s = when {
                    i < cons && start == 'm' -> m1.run(pulse * 6) * 0.6
                    i < cons -> (r2.run(noise * 0.25) + r3.run(noise * 0.2)) * (i / cons)          // breathy "h"
                    else -> r1.run(pulse * 9) + 0.55 * r2.run(pulse * 9) + 0.2 * r3.run(pulse * 9) + 0.04 * noise
                }
                val env = minOf(1.0, (1 - p) * 7) * minOf(1.0, p * 40)
                out.add(s * env * loud)
            }
        }
        fun gap(secs: Double) { repeat((secs * rate).toInt()) { out.add(0.0) } }
        syll(0.30, 120.0, 135.0, 'm', 1.0); gap(0.06)                    // "MWA"
        val n = 5 + Random.nextInt(2)
        for (k in 0 until n) {                                            // "ha ha ha ha", climbing, getting madder
            val f = 150.0 + 22.0 * k
            syll(0.15, f, f * 0.92, 'h', 0.85 + 0.04 * k); gap(0.045)
        }
        syll(0.55, 260.0, 150.0, 'h', 1.0)                                // a long "haaaa" sliding down
        // a dark echo, like a villain's lair
        val dry = out.toDoubleArray()
        val d = (0.13 * rate).toInt()
        val wet = DoubleArray(dry.size + d * 3)
        for (i in dry.indices) { wet[i] += dry[i]; wet[i + d] += dry[i] * 0.35; wet[i + 2 * d] += dry[i] * 0.15 }
        val peak = wet.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6)
        return DoubleArray(wet.size) { wet[it] / peak * 0.9 }
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
