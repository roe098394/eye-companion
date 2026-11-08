package com.sharpfocus

import android.os.Process
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Small root background process that watches the projector's motion sensors (the LSM6DSV
 * accelerometer and gyro, exposed as raw input devices) and opens Sharp Focus in auto mode:
 *   - once shortly after power-up (if wanted), and
 *   - after the projector has been moved, turned or knocked, once it has been still for 4 seconds.
 *
 * Started with:  CLASSPATH=<apk> app_process /system/bin com.sharpfocus.MotionWatch <boot 0|1> <move 0|1>
 */
object MotionWatch {

    private const val PIDFILE = "/data/local/tmp/sharpfocus-watch.pid"
    private const val LOG = "/data/local/tmp/sharpfocus-watch.log"

    @Volatile private var moved = false
    @Volatile private var lastMove = 0L
    @Volatile private var lastFocus = System.currentTimeMillis()
    @Volatile private var lastNote = 0L
    private val counts = IntArray(2)

    private fun log(s: String) {
        try {
            val f = File(LOG)
            if (f.length() > 200_000) f.delete()
            f.appendText("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.UK).format(java.util.Date())} $s\n")
        } catch (_: Exception) {}
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val onBoot = args.getOrNull(0) == "1"
        val onMove = args.getOrNull(1) != "0"

        // only one copy at a time
        try {
            val old = File(PIDFILE).readText().trim().toInt()
            if (old != Process.myPid() && File("/proc/$old/cmdline").readText().contains("app_process")) {
                println("already running"); return
            }
        } catch (_: Exception) {}
        File(PIDFILE).writeText(Process.myPid().toString())
        log("started boot=$onBoot move=$onMove 64bit=${Process.is64Bit()}")

        if (onBoot) Thread { Thread.sleep(25_000); launch("power-up") }.start()
        if (!onMove) { while (true) Thread.sleep(60_000) }

        val (accel, gyro) = findSensors()
        log("accelerometer=$accel gyro=$gyro")
        if (accel == null && gyro == null) { log("no motion sensors found"); while (true) Thread.sleep(60_000) }
        accel?.let { d -> Thread { read(d, 0) }.start() }
        gyro?.let { d -> Thread { read(d, 1) }.start() }

        var lastAlive = 0L
        while (true) {
            Thread.sleep(500)
            val now = System.currentTimeMillis()
            if (moved && now - lastMove > 4000 && now - lastFocus > 20_000) {
                moved = false; lastFocus = now
                launch("moved")
            }
            if (now - lastAlive > 600_000) { lastAlive = now; log("alive: accel readings ${counts[0]}, gyro readings ${counts[1]}") }
        }
    }

    private fun findSensors(): Pair<String?, String?> {
        val text = try { File("/proc/bus/input/devices").readText() } catch (e: Exception) { return null to null }
        var accel: String? = null; var gyro: String? = null
        for (block in text.split("\n\n")) {
            val name = Regex("N: Name=\"([^\"]*)\"").find(block)?.groupValues?.get(1)?.lowercase() ?: continue
            val ev = Regex("event(\\d+)").find(block)?.groupValues?.get(1) ?: continue
            val isMotion = name.contains("lsm6") || name.contains("accel") || name.contains("gsensor") || name.contains("gyro")
            if (!isMotion) continue
            if (name.contains("gyro")) gyro = "/dev/input/event$ev" else accel = "/dev/input/event$ev"
        }
        return accel to gyro
    }

    private fun launch(why: String) {
        log("refocus: $why")
        try {
            Runtime.getRuntime().exec(arrayOf("am", "start", "-n", "com.sharpfocus/.MainActivity", "--ez", "auto", "true",
                "--es", "why", why)).waitFor()
        } catch (e: Exception) { log("launch failed: $e") }
    }

    private const val BUSY = "/data/local/tmp/sharpfocus-busy"

    private fun movement(kind: String, amount: Double, limit: Double) {
        val now = System.currentTimeMillis()
        // ignore the lens motor's own vibration while Sharp Focus is working, and just after a refocus
        val busyAt = File(BUSY).lastModified()
        if (now - busyAt < 6000 || now - lastFocus < 30_000) return
        moved = true; lastMove = now
        if (now - lastNote > 3000) { lastNote = now; log("movement ($kind) %.0f > %.0f".format(amount, limit)) }
    }

    /**
     * Read one sensor. Each axis keeps a slow-moving average (its resting value); movement is a reading
     * that jumps well away from it, compared with how much that sensor normally jitters when still.
     */
    private fun read(dev: String, which: Int) {
        val size = if (Process.is64Bit()) 24 else 16      // struct input_event
        val buf = ByteArray(size * 64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val v = DoubleArray(3)
        val rest = DoubleArray(3)
        var jitter = 0.0
        var n = 0
        try {
            FileInputStream(dev).use { input ->
                while (true) {
                    val got = input.read(buf)
                    if (got <= 0) { Thread.sleep(200); continue }
                    var off = 0
                    while (off + size <= got) {
                        val type = bb.getShort(off + size - 8).toInt()
                        val code = bb.getShort(off + size - 6).toInt()
                        val value = bb.getInt(off + size - 4)
                        off += size
                        if (type == 3) { v[code % 3] = value.toDouble(); continue }
                        if (type != 0) continue
                        // one complete reading
                        n++; counts[which]++
                        if (n <= 30) { for (i in 0..2) rest[i] = if (n == 1) v[i] else rest[i] * 0.8 + v[i] * 0.2; continue }
                        var dev2 = 0.0
                        for (i in 0..2) { val d = v[i] - rest[i]; dev2 += d * d }
                        val d = sqrt(dev2)
                        val limit = jitter * 6 + (if (which == 0) 40.0 else 60.0)
                        if (d > limit) movement(if (which == 0) "accelerometer" else "gyro", d, limit)
                        else jitter = if (jitter == 0.0) d else jitter * 0.98 + d * 0.02
                        // follow slow changes (e.g. the projector settling at a new angle)
                        for (i in 0..2) rest[i] = rest[i] * 0.97 + v[i] * 0.03
                    }
                }
            }
        } catch (e: Exception) { log("sensor $dev stopped: $e") }
    }
}
