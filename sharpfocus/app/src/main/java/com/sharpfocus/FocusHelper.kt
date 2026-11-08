package com.sharpfocus

import android.graphics.BitmapFactory
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min

/**
 * Runs as root (Magisk su -> app_process), started by the app.
 * Talks to the projector's focus motor (sysfs) and its built-in camera (V4L2 via libfcam.so).
 *
 * Commands, one per line on stdin; one reply line on stdout:
 *   INFO                 -> "I <text with \n escaped as |>"
 *   OPEN dev w h         -> "OK w h fourcc"  or "ERR ..."
 *   SCORE skip           -> "S bright s0..s8 c0..c8"   (3x3 grid, left-to-right, top-to-bottom;
 *                            s = focus score x1000, c = contrast; cells with low contrast see no picture)
 *   SNAP                 -> "IMG w h <hex grey pixels>" (small preview, 80x60)
 *   MOVE n               -> moves the lens n steps (+ one way, - the other), waits, "OK"
 *   STOP                 -> "OK"
 *   PROP key value       -> sets a system property, "OK <new value>"
 *   Q                    -> quit
 */
object FocusHelper {

    private const val MOTOR = "/sys/devices/platform/motor_ctr/motor_ctrl"
    private const val MOTOR_DIR = "/sys/devices/platform/motor_ctr"
    private var cam = 0L
    private var camW = 0
    private var camH = 0
    private var camFmt = 0

    @JvmStatic
    fun main(args: Array<String>) {
        // args[0] = the app's native library folder
        try {
            System.load(File(args[0], "libfcam.so").absolutePath)
        } catch (t: Throwable) {
            println("ERR can't load camera library: ${t.message}"); System.out.flush(); return
        }
        val input = BufferedReader(InputStreamReader(System.`in`))
        println("READY"); System.out.flush()
        while (true) {
            val line = input.readLine() ?: break
            val p = line.trim().split(Regex("\\s+"))
            if (p.isEmpty() || p[0].isEmpty()) continue
            val reply = try {
                when (p[0]) {
                    "INFO" -> "I " + info().replace("\n", "|")
                    "OPEN" -> open(p[1], p[2].toInt(), p[3].toInt())
                    "SCORE" -> score(p.getOrNull(1)?.toInt() ?: 3)
                    "SNAP" -> snap()
                    "MOVE" -> { move(p[1].toInt()); "OK" }
                    "STOP" -> { writeMotor(768); "OK" }
                    "PROP" -> { setProp(p[1], p[2]); "OK " + getProp(p[1]) }
                    "Q" -> { if (cam != 0L) Cam.close(cam); println("BYE"); System.out.flush(); return }
                    else -> "ERR unknown"
                }
            } catch (t: Throwable) {
                "ERR " + (t.message ?: t.javaClass.simpleName).replace('\n', ' ')
            }
            println(reply); System.out.flush()
        }
        if (cam != 0L) Cam.close(cam)
    }

    private fun readFile(path: String) = try { File(path).readText().trim() } catch (e: Exception) { "?" }

    private fun info(): String {
        val sb = StringBuilder()
        for (i in 0..3) {
            val dev = "/dev/video$i"
            if (!File(dev).exists()) continue
            sb.append("name=").append(readFile("/sys/class/video4linux/video$i/name")).append("\n")
            sb.append(Cam.info(dev)).append("\n")
        }
        sb.append("motor:")
        File(MOTOR_DIR).listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isFile && f.name.startsWith("motor")) sb.append(" ").append(f.name).append("=").append(readFile(f.path))
        }
        return sb.toString()
    }

    private fun open(dev: String, w: Int, h: Int): String {
        if (cam != 0L) { Cam.close(cam); cam = 0L }
        val c = Cam.open(dev, w, h)
        if (c == 0L) return "ERR " + Cam.lastError()
        cam = c; camW = Cam.width(c); camH = Cam.height(c); camFmt = Cam.fourcc(c)
        return "OK $camW $camH ${Cam.fourccName(camFmt)}"
    }

    /** One fresh grey frame as w*h bytes (decoding MJPEG if that's what the camera sends). */
    private fun grey(skip: Int): Triple<ByteArray, Int, Int> {
        if (cam == 0L) throw IllegalStateException("camera not open")
        val raw = Cam.grab(cam, skip) ?: throw IllegalStateException("no frame: " + Cam.lastError())
        if (camFmt != Cam.MJPEG) return Triple(raw, camW, camH)
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: throw IllegalStateException("bad jpeg")
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
        val y = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            y[i] = (((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8).toByte()
        }
        return Triple(y, w, h)
    }

    /**
     * Focus score per cell: average gradient energy divided by the cell's contrast, so it doesn't
     * change just because the picture got brighter or darker - only when edges get sharper.
     */
    private fun score(skip: Int): String {
        val (y, w, h) = grey(skip)
        val s = LongArray(9); val v = LongArray(9); val m = LongArray(9); val n = IntArray(9)
        var total = 0L
        // means first
        for (r in 1 until h - 1) {
            val cr = min(2, r * 3 / h)
            for (x in 1 until w - 1) {
                val cell = cr * 3 + min(2, x * 3 / w)
                m[cell] += (y[r * w + x].toInt() and 255).toLong(); n[cell]++
            }
        }
        for (i in 0 until 9) { total += m[i]; m[i] = if (n[i] > 0) m[i] / n[i] else 0 }
        for (r in 1 until h - 1) {
            val cr = min(2, r * 3 / h)
            val row = r * w
            for (x in 1 until w - 1) {
                val cell = cr * 3 + min(2, x * 3 / w)
                val c = y[row + x].toInt() and 255
                val gx = (y[row + x + 1].toInt() and 255) - (y[row + x - 1].toInt() and 255)
                val gy = (y[row + w + x].toInt() and 255) - (y[row - w + x].toInt() and 255)
                s[cell] += (gx * gx + gy * gy).toLong()
                val d = c - m[cell]; v[cell] += d * d
            }
        }
        val out = StringBuilder("S ").append(total / max(1, n.sum()))
        for (i in 0 until 9) {
            val e = s[i].toDouble() / max(1, n[i]); val va = v[i].toDouble() / max(1, n[i])
            out.append(' ').append((e / (va + 25.0) * 1000.0).toInt())
        }
        for (i in 0 until 9) out.append(' ').append((v[i] / max(1, n[i])).toInt())
        return out.toString()
    }

    private fun snap(): String {
        val (y, w, h) = grey(2)
        val tw = 80; val th = 60
        val sb = StringBuilder("IMG $tw $th ")
        for (r in 0 until th) for (x in 0 until tw) {
            val v = y[(r * h / th) * w + (x * w / tw)].toInt() and 255
            sb.append(Character.forDigit(v shr 4, 16)).append(Character.forDigit(v and 15, 16))
        }
        return sb.toString()
    }

    private fun getProp(k: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as String
    } catch (e: Exception) { "?" }

    private fun setProp(k: String, v: String) {
        try { Class.forName("android.os.SystemProperties").getMethod("set", String::class.java, String::class.java).invoke(null, k, v) }
        catch (e: Exception) { Runtime.getRuntime().exec(arrayOf("setprop", k, v)).waitFor() }
    }

    private fun writeMotor(v: Int) {
        FileOutputStream(MOTOR).use { it.write(v.toString().toByteArray()) }
    }

    private fun move(n: Int) {
        if (n == 0) return
        // tell the motion watcher this shake is ours, not someone moving the projector
        try { File("/data/local/tmp/sharpfocus-busy").writeText(System.currentTimeMillis().toString()) } catch (_: Exception) {}
        val steps = min(255, kotlin.math.abs(n))
        writeMotor((if (n > 0) 256 else 512) + steps)
        // wait for the lens to get there and stop wobbling
        val perStep = readFile("$MOTOR_DIR/motor_step_delay").toIntOrNull()?.let { if (it in 4..50) it else 4 } ?: 4
        Thread.sleep(300L + steps * perStep.toLong())     // + time for the camera to catch up
    }
}
