package com.eyecompanion

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Keeps a Bluetooth speaker (the Echo) connected by streaming an inaudible whisper of sound,
 * so the link never looks idle and never drops (and Alexa never announces reconnecting).
 */
object BtKeepAlive {
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "bt-keepalive") {
            var track: AudioTrack? = null
            try {
                val rate = 16000
                val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(min, rate / 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
                track.play()
                val buf = ShortArray(rate / 10)
                while (running) {
                    // ±2 out of 32767: about -84 dB, far below anything you can hear, but not "digital silence"
                    for (i in buf.indices) buf[i] = (Random.nextInt(5) - 2).toShort()
                    track.write(buf, 0, buf.size)
                }
            } catch (_: Exception) {
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                track?.release(); running = false
            }
        }
    }

    fun stop() { running = false }
    val active get() = running
}
