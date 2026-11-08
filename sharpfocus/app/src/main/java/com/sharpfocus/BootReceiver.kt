package com.sharpfocus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** On power-up, start the motion watcher (which also does the power-up refocus if that's switched on). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread { try { Watcher.start(context, boot = true) } finally { pending.finish() } }
    }
}

object Watcher {
    fun prefs(c: Context) = c.getSharedPreferences("sharpfocus", Context.MODE_PRIVATE)

    /** Start (or restart with new settings) the root motion watcher. Blocking; call off the main thread. */
    fun start(c: Context, boot: Boolean) {
        val p = prefs(c)
        val onBoot = p.getBoolean("focusOnBoot", true)
        val onMove = p.getBoolean("focusOnMove", true)
        stop()
        if (!onBoot && !onMove) return
        val apk = c.packageCodePath
        val cmd = "CLASSPATH='$apk' nohup app_process /system/bin com.sharpfocus.MotionWatch ${if (boot && onBoot) 1 else 0} ${if (onMove) 1 else 0} > /dev/null 2>&1 &"
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() } catch (_: Exception) {}
    }

    fun stop() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c",
                "if [ -f /data/local/tmp/sharpfocus-watch.pid ]; then kill \$(cat /data/local/tmp/sharpfocus-watch.pid) 2>/dev/null; rm -f /data/local/tmp/sharpfocus-watch.pid; fi")).waitFor()
        } catch (_: Exception) {}
    }
}
