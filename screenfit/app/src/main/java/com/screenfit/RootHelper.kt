package com.screenfit

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs as root, started by the app through Magisk:
 *   su -> CLASSPATH=<this apk> app_process /system/bin com.screenfit.RootHelper
 *
 * It reads lines from stdin and answers on stdout:
 *   K ltx lty rtx rty lbx lby rbx rby   -> apply that keystone and save it
 *   G                                   -> print "V width height" + the 8 saved values
 *   Q                                   -> quit
 *
 * The values are inset distances in screen pixels, measured the same way the projector's
 * own settings app (MagicControlx) measures them:
 *   lt = from the top-left corner (x in from left, y down from top)
 *   rt = from the top-right corner (x in from right, y down from top)
 *   lb = from the bottom-left corner (x in from left, y up from bottom)
 *   rb = from the bottom-right corner (x in from right, y up from bottom)
 *
 * The picture is changed by the same call MagicControlx makes: transaction 36 on the
 * vendor.display.config@1.0::IDisplayConfig HIDL service, with zoom 100/100 and the four
 * corner points (top-left, top-right, bottom-left, bottom-right) in screen pixels.
 */
object RootHelper {

    private const val IFACE = "vendor.display.config@1.0::IDisplayConfig"
    private const val TX_SET_KEYSTONE = 36

    private val KEYS = arrayOf("ltx", "lty", "rtx", "rty", "lbx", "lby", "rbx", "rby")

    @JvmStatic
    fun main(args: Array<String>) {
        val input = BufferedReader(InputStreamReader(System.`in`))
        println("READY")
        System.out.flush()
        while (true) {
            val line = input.readLine() ?: break
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.isEmpty() || parts[0].isEmpty()) continue
            val reply = try {
                when (parts[0]) {
                    "K" -> {
                        val v = IntArray(8) { parts[it + 1].toInt() }
                        apply(v)
                        "OK"
                    }
                    "G" -> {
                        val (w, h) = screenSize()
                        "V $w $h " + KEYS.joinToString(" ") { getProp("persist.display.keystone_$it", "0").toFloatOrNull()?.toInt()?.toString() ?: "0" }
                    }
                    "Q" -> { println("BYE"); System.out.flush(); return }
                    else -> "ERR unknown"
                }
            } catch (t: Throwable) {
                "ERR " + (t.message ?: t.javaClass.simpleName).replace('\n', ' ')
            }
            println(reply)
            System.out.flush()
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val s = getProp("persist.vendor.disp.screensize", "1920x1080").split("x")
        return try { s[0].trim().toInt() to s[1].trim().toInt() } catch (e: Exception) { 1920 to 1080 }
    }

    /** v = ltx lty rtx rty lbx lby rbx rby */
    private fun apply(v: IntArray) {
        val (w, h) = screenSize()
        val ltx = v[0]; val lty = v[1]; val rtx = v[2]; val rty = v[3]
        val lbx = v[4]; val lby = v[5]; val rbx = v[6]; val rby = v[7]

        // Same maths as the projector's own app.
        val tl = intArrayOf(Math.abs(ltx), Math.abs(lty))
        val tr = intArrayOf(w - 1 - Math.abs(rtx), Math.abs(rty))
        val bl = intArrayOf(Math.abs(lbx), h - 1 - Math.abs(lby))
        val br = intArrayOf(w - 1 - Math.abs(rbx), h - 1 - Math.abs(rby))

        sendToDisplay(tl, tr, bl, br)

        // Save so it survives a restart, in both places the projector keeps it.
        setProp("persist.display.keystone_zoom_h", "100.0")
        setProp("persist.display.keystone_zoom_v", "100.0")
        val names = arrayOf("ltx", "lty", "rtx", "rty", "lbx", "lby", "rbx", "rby")
        for (i in 0 until 8) {
            setProp("persist.display.keystone_${names[i]}", v[i].toString())
            // The built-in settings screen keeps its own copy, one step behind.
            setProp("persist.htc.keystone.${names[i]}", Math.max(0, v[i] - 1).toString())
        }
    }

    private fun sendToDisplay(tl: IntArray, tr: IntArray, bl: IntArray, br: IntArray) {
        val hwBinderCls = Class.forName("android.os.HwBinder")
        val iHwBinderCls = Class.forName("android.os.IHwBinder")
        val parcelCls = Class.forName("android.os.HwParcel")
        val blobCls = Class.forName("android.os.HwBlob")

        val service = hwBinderCls.getMethod("getService", String::class.java, String::class.java)
            .invoke(null, IFACE, "default") ?: throw IllegalStateException("display service not found")

        val request = parcelCls.getConstructor().newInstance()
        parcelCls.getMethod("writeInterfaceToken", String::class.java).invoke(request, IFACE)
        val writeFloat = parcelCls.getMethod("writeFloat", Float::class.javaPrimitiveType)
        writeFloat.invoke(request, 100.0f)
        writeFloat.invoke(request, 100.0f)

        val blobCtor = blobCls.getConstructor(Int::class.javaPrimitiveType)
        val putInt32 = blobCls.getMethod("putInt32", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val writeBuffer = parcelCls.getMethod("writeBuffer", blobCls)
        for (p in arrayOf(tl, tr, bl, br)) {
            val blob = blobCtor.newInstance(8)
            putInt32.invoke(blob, 0L, p[0])
            putInt32.invoke(blob, 4L, p[1])
            writeBuffer.invoke(request, blob)
        }

        val reply = parcelCls.getConstructor().newInstance()
        try {
            iHwBinderCls.getMethod("transact", Int::class.javaPrimitiveType, parcelCls, parcelCls, Int::class.javaPrimitiveType)
                .invoke(service, TX_SET_KEYSTONE, request, reply, 0)
            parcelCls.getMethod("verifySuccess").invoke(reply)
            parcelCls.getMethod("releaseTemporaryStorage").invoke(request)
            parcelCls.getMethod("readInt32").invoke(reply)
        } finally {
            parcelCls.getMethod("release").invoke(reply)
        }
    }

    private fun getProp(key: String, def: String): String = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, def) as String
    } catch (e: Exception) { def }

    private fun setProp(key: String, value: String) {
        try {
            Class.forName("android.os.SystemProperties")
                .getMethod("set", String::class.java, String::class.java)
                .invoke(null, key, value)
        } catch (e: Exception) {
            Runtime.getRuntime().exec(arrayOf("setprop", key, value)).waitFor()
        }
    }
}
