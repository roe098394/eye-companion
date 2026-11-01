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
    /** The brains Pip can use, from quickest to cleverest. */
    data class BrainChoice(val key: String, val label: String, val url: String, val file: String, val sizeMb: Int)

    val BRAINS = listOf(
        BrainChoice("fast", "Fast (Qwen 0.5B) – quick but simple",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "brain-small.gguf", 490),
        BrainChoice("smart", "Smarter (Llama 3.2 1B) – good balance",
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            "brain.gguf", 810),
        BrainChoice("smartest", "Smartest (Qwen 1.5B) – cleverest, slowest",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "brain-large.gguf", 1120),
    )

    /** Speech models: the big one hears far better but needs a roomier tablet. */
    data class EarsChoice(val url: String, val dir: String, val sizeMb: Int)
    val EARS_BIG = EarsChoice("https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip", "vosk-lgraph", 128)
    val EARS_SMALL = EarsChoice("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", "vosk", 40)

    /** Small model that recognises *who* is speaking. */
    const val SPEAKER_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
    const val SPEAKER_DIR = "vosk-spk"

    fun findSpeakerModel(root: File): File? {
        if (File(root, "final.ext.raw").exists()) return root
        return root.listFiles()?.filter { it.isDirectory }?.firstNotNullOfOrNull { findSpeakerModel(it) }
    }

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
        if (code == 416 && already > 0) { conn.disconnect(); return }   // nothing left to fetch
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
        // A connection that drops early can look like a normal end of file: check the size.
        if (total > 0 && part.length() < total) throw java.io.IOException("download cut off, resuming")
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
