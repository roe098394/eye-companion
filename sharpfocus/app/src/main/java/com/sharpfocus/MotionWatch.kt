package com.sharpfocus

import android.os.Process
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Small root background process that watches the projector's motion sensor (the LSM6DSV
 * accelerometer, exposed as a raw input device) and opens Sharp Focus in auto mode:
 *   - once shortly after power-up (if wanted), and
 *   - after the projector has been moved or bumped and has then been still for a few seconds.
 *
 * Started with:  CLASSPATH=<apk> app_process /system/bin com.sharpfocus.MotionWatch <boot 0|1> <move 0|1>
 */
object MotionWatch {

    private const val PIDFILE = "/data/local/tmp/sharpfocus-watch.pid"
    private const val LOG = "/data/local/tmp/sharpfocus-watch.log"

    private fun log(s: String) {
        try {
            val f = File(LOG)
            if (f.length() > 200_000) f.delete()
            f.appendText("${System.currentTimeMillis() / 1000} $s\n")
        } catch (_: Exception) {}
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val onBoot = args.getOrNull(0) == "1"
        val onMove = args.getOrNull(1) != "0"

        // only one copy at a time
        try {
            val old = File(PIDFILE).readText().trim().toInt()
            val cmd = File("/proc/$old/cmdline").readText()
            if (cmd.contains("app_process") || cmd.contains("MotionWatch")) { if (old != Process.myPid()) { println("already running"); return } }
        } catch (_: Exception) {}
        File(PIDFILE).writeText(Process.myPid().toString())
        log("started boot=$onBoot move=$onMove 64bit=${Process.is64Bit()}")

        if (onBoot) {
            Thread {
                Thread.sleep(25_000)              // let the launcher settle first
                launch("power-up")
            }.start()
        }
        if (!onMove) { while (true) Thread.sleep(60_000) }

        val dev = findAccel()
        if (dev == null) { log("no accelerometer found"); while (true) Thread.sleep(60_000) }
        log("accelerometer: $dev")
        watch(dev)
    }

    /** The input device whose name is the accelerometer (lsm6dsv, not the _gyro one). */
    private fun findAccel(): String? {
        val text = try { File("/proc/bus/input/devices").readText() } catch (e: Exception) { return null }
        for (block in text.split("\n\n")) {
            val name = Regex("N: Name=\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: continue
            val handler = Regex("event(\\d+)").find(block)?.groupValues?.get(1) ?: continue
            val n = name.lowercase()
            if ((n.contains("lsm6") || n.contains("accel") || n.contains("gsensor")) && !n.contains("gyro")) return "/dev/input/event$handler"
        }
        return null
    }

    private fun launch(why: String) {
        log("refocus: $why")
        try {
            Runtime.getRuntime().exec(arrayOf("am", "start", "-n", "com.sharpfocus/.MainActivity", "--ez", "auto", "true",
                "--es", "why", why)).waitFor()
        } catch (e: Exception) { log("launch failed: $e") }
    }

    private fun watch(dev: String) {
        val size = if (Process.is64Bit()) 24 else 16      // struct input_event
        val buf = ByteArray(size * 64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val v = DoubleArray(3)
        var have = 0

        // gravity seen at the last focus, and a short running average
        var g0: DoubleArray? = null
        val avg = DoubleArray(3); var navg = 0
        var bumped = false
        var lastMove = 0L
        var lastFocus = System.currentTimeMillis()
        var noise = 0.0; var prevMag = 0.0

        FileInputStream(dev).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) { Thread.sleep(200); continue }
                var off = 0
                while (off + size <= n) {
                    val type = bb.getShort(off + size - 8).toInt()
                    val code = bb.getShort(off + size - 6).toInt()
                    val value = bb.getInt(off + size - 4)
                    off += size
                    if (type == 3 && code in 0..2) { v[code] = value.toDouble(); have = have or (1 shl code) }
                    if (type != 0 || have != 7) continue          // act on each complete report (EV_SYN)

                    val now = System.currentTimeMillis()
                    val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                    if (mag <= 0) continue
                    val jerk = abs(mag - prevMag); prevMag = mag
                    noise = if (noise == 0.0) jerk else noise * 0.98 + jerk * 0.02

                    for (i in 0..2) avg[i] = avg[i] * 0.9 + v[i] / mag * 0.1
                    navg++
                    if (navg < 20) continue
                    if (g0 == null) { g0 = avg.copyOf(); continue }

                    // a knock or shove: a jolt well above the usual sensor noise
                    if (jerk > noise * 8 + mag * 0.02) { bumped = true; lastMove = now }
                    val angle = Math.toDegrees(acos(((g0!![0] * avg[0] + g0!![1] * avg[1] + g0!![2] * avg[2]) /
                        (sqrt(avg[0] * avg[0] + avg[1] * avg[1] + avg[2] * avg[2]) * sqrt(g0!![0] * g0!![0] + g0!![1] * g0!![1] + g0!![2] * g0!![2]))).coerceIn(-1.0, 1.0)))
                    if (angle > 0.8) lastMove = now

                    // moved, and has now been still for 4 seconds -> refocus (at most once every 20 s)
                    val still = now - lastMove > 4000
                    if ((bumped || angle > 0.8) && still && now - lastFocus > 20_000) {
                        launch(if (angle > 0.8) "tilted %.1f°".format(angle) else "bumped")
                        lastFocus = now; bumped = false; g0 = avg.copyOf()
                    }
                }
            }
        }
    }
}
