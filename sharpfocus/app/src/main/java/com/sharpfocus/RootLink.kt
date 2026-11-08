package com.sharpfocus

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** One root process (Magisk su) running FocusHelper; simple request/reply. Use off the main thread. */
class RootLink(private val context: Context) {
    @Volatile var ready = false; private set
    var lastError: String? = null; private set
    private var proc: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    fun start(): Boolean {
        if (ready) return true
        return try {
            val p = Runtime.getRuntime().exec("su")
            val w = OutputStreamWriter(p.outputStream)
            val r = BufferedReader(InputStreamReader(p.inputStream))
            val apk = context.packageCodePath
            val lib = context.applicationInfo.nativeLibraryDir
            w.write("export CLASSPATH='$apk'\n")
            w.write("exec app_process /system/bin com.sharpfocus.FocusHelper '$lib'\n")
            w.flush()
            val first = r.readLine()
            if (first != "READY") {
                lastError = first ?: "no answer (root not allowed?)"
                p.destroy(); false
            } else { proc = p; writer = w; reader = r; ready = true; true }
        } catch (e: Exception) { lastError = "No root access: ${e.message}"; false }
    }

    @Synchronized fun ask(cmd: String): String {
        val w = writer ?: return "ERR not started"
        return try {
            w.write(cmd + "\n"); w.flush()
            reader?.readLine() ?: run { ready = false; "ERR helper stopped" }
        } catch (e: Exception) { ready = false; "ERR ${e.message}" }
    }

    fun stop() {
        try { writer?.write("Q\n"); writer?.flush() } catch (_: Exception) {}
        try { proc?.destroy() } catch (_: Exception) {}
        ready = false
    }
}
