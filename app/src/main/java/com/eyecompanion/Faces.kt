package com.eyecompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Faces Pip has photographed, kept on the tablet only.
 *  - "unsorted": people Pip didn't recognise (or a photo taken from the web page), waiting to be named.
 *  - "known/<Name>": photos you've named. The newest one of each person is shown to the online brain
 *    next to a new photo so it can say who's at the bench.
 * Nothing is uploaded except as part of that recognition question.
 */
object Faces {
    private const val MAX_UNSORTED = 24
    private const val MAX_PER_PERSON = 4
    private lateinit var root: File
    private val unsortedDir get() = File(root, "unsorted").apply { mkdirs() }
    private val knownDir get() = File(root, "known").apply { mkdirs() }

    fun init(filesDir: File) { root = File(filesDir, "faces").apply { mkdirs() } }
    val ready get() = ::root.isInitialized

    /** Tidy a typed name: letters, spaces and dashes, capitalised, at most 20 characters. */
    fun cleanName(raw: String): String = raw.filter { it.isLetter() || it == ' ' || it == '-' || it == '\'' }
        .trim().replace(Regex("\\s+"), " ").take(20)
        .split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

    private fun safeId(id: String) = id.filter { it.isDigit() }.take(19)
    private fun personDir(name: String) = File(knownDir, cleanName(name)).takeIf { cleanName(name).isNotEmpty() }

    /** Keep a photo to be named later. Returns its id ("" if it couldn't). */
    @Synchronized fun addUnsorted(jpeg: ByteArray): String {
        if (!ready) return ""
        val id = System.currentTimeMillis().toString()
        File(unsortedDir, "$id.jpg").writeBytes(small(jpeg, 640))
        unsortedDir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_UNSORTED)?.forEach { it.delete() }
        return id
    }
    @Synchronized fun unsorted(): List<String> =
        if (!ready) emptyList() else unsortedDir.listFiles()?.map { it.nameWithoutExtension }?.sortedDescending().orEmpty()

    /** Everyone with at least one named photo, and their photo ids (newest first). */
    @Synchronized fun people(): Map<String, List<String>> = if (!ready) emptyMap() else
        knownDir.listFiles()?.filter { it.isDirectory }?.associate { d ->
            d.name to (d.listFiles()?.map { it.nameWithoutExtension }?.sortedDescending().orEmpty())
        }?.filterValues { it.isNotEmpty() }?.toSortedMap().orEmpty()

    fun names(): List<String> = people().keys.toList()

    /** Move an unsorted photo into a person's folder. Returns the tidy name, or null. */
    @Synchronized fun label(id: String, rawName: String): String? {
        val name = cleanName(rawName); if (name.isEmpty()) return null
        val src = File(unsortedDir, "${safeId(id)}.jpg"); if (!src.exists()) return null
        val dir = File(knownDir, name).apply { mkdirs() }
        src.copyTo(File(dir, src.name), overwrite = true); src.delete()
        dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_PER_PERSON)?.forEach { it.delete() }
        return name
    }
    @Synchronized fun discard(id: String) { File(unsortedDir, "${safeId(id)}.jpg").delete() }
    @Synchronized fun removePhoto(name: String, id: String) {
        val d = personDir(name) ?: return
        File(d, "${safeId(id)}.jpg").delete()
        if (d.listFiles().isNullOrEmpty()) d.delete()
    }
    @Synchronized fun forget(name: String) { personDir(name)?.deleteRecursively() }

    /** A photo for the web page: "u" = unsorted, otherwise a person's name. */
    @Synchronized fun photo(who: String, id: String): ByteArray? {
        if (!ready) return null
        val f = if (who == "u") File(unsortedDir, "${safeId(id)}.jpg") else personDir(who)?.let { File(it, "${safeId(id)}.jpg") }
        return f?.takeIf { it.exists() }?.readBytes()
    }

    /** The newest photo of each known person, small, for the recognition question. */
    @Synchronized fun references(max: Int = 8): List<Pair<String, ByteArray>> = people().entries.take(max).mapNotNull { (name, ids) ->
        ids.firstOrNull()?.let { id -> File(File(knownDir, name), "$id.jpg").takeIf { it.exists() }?.let { name to small(it.readBytes(), 320) } }
    }

    private fun small(jpeg: ByteArray, width: Int): ByteArray = try {
        val bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bm == null || bm.width <= width) jpeg else {
            val s = Bitmap.createScaledBitmap(bm, width, (bm.height * width.toFloat() / bm.width).toInt(), true)
            ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        }
    } catch (_: Exception) { jpeg }
}
