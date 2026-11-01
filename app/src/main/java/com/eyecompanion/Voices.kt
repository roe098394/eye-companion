package com.eyecompanion

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Remembers people by the sound of their voice, using the "voice print" Vosk's speaker model
 * produces for each sentence. Everything is stored only on this tablet.
 */
class Voices(private val prefs: SharedPreferences) {

    private class Person(val name: String, val samples: MutableList<FloatArray>)

    private val people = ArrayList<Person>()

    init { load() }

    val names get() = people.map { it.name }

    /** Best match and how sure (0..1), or null if nobody is close enough. */
    fun identify(print: FloatArray): Pair<String, Float>? {
        var best: Person? = null
        var bestSim = -1f
        for (p in people) for (s in p.samples) {
            val sim = cosine(print, s)
            if (sim > bestSim) { bestSim = sim; best = p }
        }
        val b = best ?: return null
        return if (bestSim >= MATCH) b.name to bestSim else null
    }

    /** Add a voice sample for someone (new or known). Keeps the most recent few per person. */
    fun remember(name: String, print: FloatArray) {
        val p = people.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: Person(name, ArrayList()).also { people.add(it) }
        p.samples.add(print)
        while (p.samples.size > MAX_SAMPLES) p.samples.removeAt(0)
        save()
    }

    fun forgetAll() { people.clear(); save() }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) -1f else (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    private fun save() {
        val arr = JSONArray()
        for (p in people) {
            val samples = JSONArray()
            p.samples.forEach { s -> samples.put(JSONArray().apply { s.forEach { put(it.toDouble()) } }) }
            arr.put(JSONObject().put("name", p.name).put("samples", samples))
        }
        prefs.edit().putString("voices", arr.toString()).apply()
    }

    private fun load() {
        val arr = try { JSONArray(prefs.getString("voices", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val samples = o.getJSONArray("samples")
            val list = ArrayList<FloatArray>()
            for (j in 0 until samples.length()) {
                val s = samples.getJSONArray(j)
                list.add(FloatArray(s.length()) { s.getDouble(it).toFloat() })
            }
            people.add(Person(o.getString("name"), list))
        }
    }

    companion object {
        /** similarity needed to say "that's you" */
        const val MATCH = 0.55f
        const val MAX_SAMPLES = 6
        /** only trust voice prints from sentences with at least this much speech (frames ~10ms) */
        const val MIN_FRAMES = 120

        private val NOT_NAMES = setOf("no", "nobody", "never", "nothing", "what", "whats", "who", "whos", "why", "how",
            "where", "when", "the", "a", "an", "can", "could", "do", "does", "is", "are", "tell", "hey", "hello", "hi",
            "yes", "yeah", "ok", "okay", "please", "just", "not", "nah", "mind", "me", "you", "your", "and", "so", "well", "um", "er")

        /** Pull a name out of answers like "I'm Mark", "it's Sarah", "this is Dave", "Mark". */
        fun parseName(text: String): String? {
            var t = text.lowercase().trim()
            if (t.split(Regex("\\s+")).size > 6) return null       // a sentence, not an introduction
            val lead = listOf("my name is", "my name's", "i'm called", "it is", "it's", "this is", "i am", "i'm", "call me", "its", "im", "it")
            for (l in lead) if (t.startsWith("$l ")) { t = t.removePrefix(l).trim(); break }
            val word = t.split(Regex("\\s+")).firstOrNull()?.filter { it.isLetter() } ?: return null
            if (word.length < 2 || word in NOT_NAMES) return null
            return word.replaceFirstChar { it.uppercase() }
        }
    }
}
