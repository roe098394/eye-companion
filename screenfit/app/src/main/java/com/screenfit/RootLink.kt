package com.screenfit

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Keeps one root process open (via Magisk's su) running RootHelper, and sends it the
 * latest keystone values. Changes are coalesced: if the arrows are held down, only the
 * newest values are sent, so the picture keeps up without a backlog.
 */
class RootLink(private val context: Context) {

    @Volatile var ready = false; private set
    @Volatile var lastError: String? = null; private set
    var onStatus: ((String) -> Unit)? = null

    private var proc: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private val pending = AtomicReference<IntArray?>(null)
    private val lock = Object()
    @Volatile private var running = false

    /** Start (blocking, call off the main thread). Returns true when root is working. */
    fun start(): Boolean {
        if (ready) return true
        return try {
            val p = Runtime.getRuntime().exec("su")
            val w = OutputStreamWriter(p.outputStream)
            val r = BufferedReader(InputStreamReader(p.inputStream))
            val apk = context.packageCodePath
            w.write("export CLASSPATH='$apk'\n")
            w.write("exec app_process /system/bin com.screenfit.RootHelper\n")
            w.flush()
            val first = r.readLine()
            if (first != "READY") {
                lastError = "Root helper didn't start (" + (first ?: "no answer — was root allowed?") + ")"
                p.destroy()
                false
            } else {
                proc = p; writer = w; reader = r; ready = true; running = true
                thread(name = "keystone-sender", isDaemon = true) { sendLoop() }
                true
            }
        } catch (e: Exception) {
            lastError = "No root access: ${e.message}"
            false
        }
    }

    /** Ask for [width, height, ltx, lty, rtx, rty, lbx, lby, rbx, rby] as saved on the projector. Blocking. */
    fun readSaved(): IntArray? = synchronized(lock) {
        try {
            val w = writer ?: return null
            w.write("G\n"); w.flush()
            val line = reader?.readLine() ?: return null
            if (!line.startsWith("V ")) return null
            line.substring(2).trim().split(Regex("\\s+")).map { it.toInt() }.toIntArray()
        } catch (e: Exception) { null }
    }

    /** Queue values to apply (ltx lty rtx rty lbx lby rbx rby). */
    fun apply(v: IntArray) {
        pending.set(v.copyOf())
        synchronized(lock) { lock.notifyAll() }
    }

    private fun sendLoop() {
        while (running) {
            var v = pending.getAndSet(null)
            if (v == null) {
                synchronized(lock) { try { lock.wait(500) } catch (_: InterruptedException) {} }
                continue
            }
            synchronized(lock) {
                try {
                    val w = writer ?: return
                    w.write("K " + v!!.joinToString(" ") + "\n"); w.flush()
                    val reply = reader?.readLine()
                    if (reply == null) { ready = false; running = false; lastError = "Root helper stopped"; onStatus?.invoke(lastError!!) }
                    else if (reply.startsWith("ERR")) { lastError = reply.removePrefix("ERR ").trim(); onStatus?.invoke("Couldn't apply: $lastError") }
                    else lastError = null
                } catch (e: Exception) {
                    ready = false; running = false; lastError = e.message; onStatus?.invoke("Lost root link: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        running = false
        try { writer?.write("Q\n"); writer?.flush() } catch (_: Exception) {}
        try { proc?.destroy() } catch (_: Exception) {}
        ready = false
    }
}
