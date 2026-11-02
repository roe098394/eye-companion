package com.eyecompanion

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the 3D printer's Klipper/Moonraker (the same thing Fluidd uses) on the home Wi-Fi.
 * Read-only: Pip can look at the printer, never start, stop or change anything.
 */
class Printer(initialHost: String?) {

    @Volatile var host: String? = initialHost

    class Status(
        val state: String,          // printing, paused, complete, standby, cancelled, error
        val file: String,
        val progress: Float,        // 0..1
        val printedSecs: Double,
        val leftSecs: Double?,
        val nozzle: Double, val nozzleTarget: Double,
        val bed: Double, val bedTarget: Double,
        val layer: Int?, val layers: Int?,
        val message: String,
    ) {
        val busy get() = state == "printing" || state == "paused"

        /** Short lines for the on-screen card. */
        fun card(): String = buildString {
            append("🖨  ").append(state.replaceFirstChar { it.uppercase() })
            if (busy || state == "complete") append("  ·  ").append((progress * 100).toInt()).append("%")
            if (file.isNotEmpty()) append("\n").append(file.removeSuffix(".gcode"))
            if (busy) {
                append("\nPrinted ").append(hm(printedSecs))
                leftSecs?.let { append("  ·  left ~").append(hm(it)) }
                if (layer != null && layers != null && layers > 0) append("\nLayer $layer / $layers")
            }
            append("\nNozzle ${nozzle.toInt()}°")
            if (nozzleTarget > 0) append(" / ${nozzleTarget.toInt()}°")
            append("   Bed ${bed.toInt()}°")
            if (bedTarget > 0) append(" / ${bedTarget.toInt()}°")
            if (message.isNotEmpty() && state == "error") append("\n").append(message.take(80))
        }

        /** A plain description for Pip's brain to talk about. */
        fun forBrain(): String = buildString {
            append("3D printer (Ender-3) is $state")
            if (file.isNotEmpty()) append(", file \"${file.removeSuffix(".gcode")}\"")
            if (busy || state == "complete") append(", ${(progress * 100).toInt()}% done")
            if (busy) {
                append(", printing for ${hm(printedSecs)}")
                leftSecs?.let { append(", about ${hm(it)} left") }
                if (layer != null && layers != null) append(", layer $layer of $layers")
            }
            append(". Nozzle ${nozzle.toInt()}°C (target ${nozzleTarget.toInt()}), bed ${bed.toInt()}°C (target ${bedTarget.toInt()}).")
            if (state == "error" && message.isNotEmpty()) append(" Error: $message.")
        }
    }

    /** Current status, or null if the printer can't be reached. */
    fun status(): Status? {
        val h = host ?: return null
        val j = get(h, "/printer/objects/query?print_stats&virtual_sdcard&display_status&extruder&heater_bed") ?: return null
        return try {
            val s = j.getJSONObject("result").getJSONObject("status")
            val ps = s.optJSONObject("print_stats") ?: JSONObject()
            val vs = s.optJSONObject("virtual_sdcard") ?: JSONObject()
            val ex = s.optJSONObject("extruder") ?: JSONObject()
            val bd = s.optJSONObject("heater_bed") ?: JSONObject()
            val info = ps.optJSONObject("info")
            val progress = vs.optDouble("progress", 0.0).toFloat()
            val printed = ps.optDouble("print_duration", 0.0)
            val left = if (progress > 0.02f) printed / progress - printed else null
            Status(
                state = ps.optString("state", "unknown"),
                file = ps.optString("filename", ""),
                progress = progress,
                printedSecs = printed,
                leftSecs = left,
                nozzle = ex.optDouble("temperature", 0.0), nozzleTarget = ex.optDouble("target", 0.0),
                bed = bd.optDouble("temperature", 0.0), bedTarget = bd.optDouble("target", 0.0),
                layer = info?.optInt("current_layer", -1)?.takeIf { it >= 0 },
                layers = info?.optInt("total_layer", -1)?.takeIf { it > 0 },
                message = ps.optString("message", ""),
            )
        } catch (_: Exception) { null }
    }

    /** Find the printer: the name it had, the usual name, its last known address, then search the Wi-Fi. */
    fun find(): String? {
        val tries = listOfNotNull(host, "ender3pi3.local", "192.168.0.12").distinct()
        for (t in tries) if (get(t, "/server/info", 1500) != null) { host = t; return t }
        val base = PcBrain.localSubnet() ?: return null
        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(32)
        for (i in 1..254) pool.execute {
            if (found.get() == null) {
                val ip = "$base.$i"
                if (get(ip, "/server/info", 700)?.optJSONObject("result")?.has("klippy_state") == true) found.compareAndSet(null, ip)
            }
        }
        pool.shutdown(); pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)
        found.get()?.let { host = it }
        return found.get()
    }

    private fun get(h: String, path: String, timeout: Int = 3000): JSONObject? = try {
        val base = if (h.contains(":")) h else h          // Fluidd's web server passes the API through on port 80
        val c = (URL("http://$base$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout; readTimeout = timeout + 1000
        }
        if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().use { it.readText() }).also { c.disconnect() }
        else { c.disconnect(); null }
    } catch (_: Exception) { null }

    companion object {
        fun hm(secs: Double): String {
            val m = (secs / 60).toInt()
            return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
        }
    }
}
