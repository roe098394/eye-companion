package com.eyecompanion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks on GitHub for a newer Pip build, downloads it, and hands it to Android's installer
 * (Android always asks a human to tap Install: apps can't update themselves silently).
 */
object Updater {
    const val ACTION = "com.eyecompanion.INSTALL_STATUS"
    private const val LATEST = "https://api.github.com/repos/roe098394/eye-companion/releases/latest"

    class Release(val build: Int, val apkUrl: String)

    /** The newest Pip build on GitHub, or null (offline, or only Kitty Eggs builds). Call off the main thread. */
    fun latest(): Release? = try {
        val c = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("User-Agent", "Pip-updater")
        }
        if (c.responseCode != 200) { c.disconnect(); null } else {
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
            val n = Regex("^build-(\\d+)$").find(j.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull()
            val assets = j.optJSONArray("assets")
            var url: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
            }
            if (n != null && url != null) Release(n, url) else null
        }
    } catch (_: Exception) { null }

    /** Download the build (resumable, cached). Call off the main thread. */
    fun download(ctx: Context, r: Release): File? = try {
        val f = File(ctx.cacheDir, "pip-build-${r.build}.apk")
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("pip-build-") && it.name != f.name && !it.name.startsWith(f.name) }?.forEach { it.delete() }
        Downloads.fetch(r.apkUrl, f) {}
        f
    } catch (_: Exception) { null }

    /** Hand the APK to Android's installer; it shows the "Install" screen. */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("pip.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ACTION).setPackage(ctx.packageName), flags)
            s.commit(pending.intentSender)
        }
    }
}
