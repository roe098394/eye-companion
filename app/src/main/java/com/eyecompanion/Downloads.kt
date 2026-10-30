package com.eyecompanion

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * One-time downloads of the speech and language models. After this the app runs fully offline.
 * Downloads resume if interrupted.
 */
object Downloads {
    const val LLM_URL =
        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
    const val VOSK_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    /** progress: 0..1, or -1 if unknown size */
    fun fetch(url: String, dest: File, progress: (Float) -> Unit) {
        if (dest.exists() && dest.length() > 0) return
        val part = File(dest.path + ".part")
        var attempt = 0
        while (true) {
            try {
                download(url, part, progress)
                part.renameTo(dest)
                return
            } catch (e: Exception) {
                attempt++
                if (attempt >= 5) throw e
                Thread.sleep(3000L * attempt)
            }
        }
    }

    private fun download(url: String, part: File, progress: (Float) -> Unit) {
        val already = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "EyeCompanion/1.0")
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
        }
        val code = conn.responseCode
        val resuming = code == 206
        if (code != 200 && code != 206) throw RuntimeException("HTTP $code for $url")
        val start = if (resuming) already else 0L
        val total = conn.contentLengthLong.let { if (it > 0) it + start else -1L }

        conn.inputStream.use { input ->
            FileOutputStream(part, resuming).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = start
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250) {
                        lastReport = now
                        progress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
        }
        conn.disconnect()
    }

    fun unzip(zip: File, intoDir: File) {
        intoDir.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(intoDir, entry.name)
                // guard against paths escaping the folder
                if (!out.canonicalPath.startsWith(intoDir.canonicalPath)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
            }
        }
    }

    /** The Vosk model folder is whichever folder holds a "conf" directory. */
    fun findVoskModel(root: File): File? {
        if (File(root, "conf").isDirectory) return root
        return root.listFiles()?.filter { it.isDirectory }?.firstNotNullOfOrNull { findVoskModel(it) }
    }
}
