package com.eyecompanion

import android.content.SharedPreferences
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.abs

/**
 * Hears long, steady machine noises (the tumble dryer, the kettle) by their sound pattern.
 * Each one is learned once from 20 seconds of it running: we keep the average loudness in 16 pitch bands
 * (a pattern, not a recording). After that, every second of background sound is compared with it.
 */
class Appliances(private val prefs: SharedPreferences) {

    class Sound(val name: String) {
        var mean: DoubleArray? = null
        var spread: DoubleArray? = null
        var limit = 2.6
        /** last few minutes of yes/no matches, one per second */
        val history = ArrayDeque<Boolean>()
        @Volatile var on = false
        @Volatile var onSince = 0L
        @Volatile var lastScore = 99.0
        @Volatile var lastLoudEnough = false
        /** share of recent seconds that matched (0..1), for the menu's test readout */
        @Volatile var share = 0.0
    }

    val dryer = Sound("dryer")
    val kettle = Sound("kettle")
    private val all = listOf(dryer, kettle)

    /** Called on the main thread when something switches on or off. */
    @Volatile var onChange: (Sound, Boolean) -> Unit = { _, _ -> }

    private val FREQS = DoubleArray(16) { 80.0 * Math.pow(6000.0 / 80.0, it / 15.0) }   // 80 Hz .. 6 kHz
    private var secAcc = DoubleArray(16); private var secFrames = 0
    private var hann = DoubleArray(0)
    private var learning: Sound? = null
    private var learnRows = ArrayList<DoubleArray>()
    private var learnDone: ((Boolean) -> Unit)? = null

    init { all.forEach { load(it) } }

    val learningNow get() = learning != null

    /** Start learning: the machine should be running now. done(true) after ~20 s. */
    fun learn(which: Sound, done: (Boolean) -> Unit) {
        learnRows = ArrayList(); learnDone = done; learning = which
    }

    /** One chunk of raw mic sound (about 100 ms). Runs on the mic thread. */
    fun feed(buf: ShortArray, n: Int, rate: Int) {
        if (n < 256) return
        // Goertzel: how much sound in each band
        val win = minOf(n, 1024)
        if (hann.size != win) hann = DoubleArray(win) { 0.5 - 0.5 * cos(2 * PI * it / (win - 1)) }
        for (b in FREQS.indices) {
            val k = 2 * cos(2 * PI * FREQS[b] / rate)
            var s1 = 0.0; var s2 = 0.0
            for (i in 0 until win) {
                val s0 = buf[i] * hann[i] + k * s1 - s2; s2 = s1; s1 = s0
            }
            secAcc[b] += s1 * s1 + s2 * s2 - k * s1 * s2
        }
        if (++secFrames < 10) return
        // one second's worth: loudness per band in decibels
        val row = DoubleArray(16) { 10 * log10(secAcc[it] / secFrames + 1.0) }
        secAcc = DoubleArray(16); secFrames = 0
        val avg = row.average()
        roomFloor = if (avg < roomFloor) avg else roomFloor + 0.02
        if (!kettle.on && stage == 0 && !pipTalking) { recent.addLast(avg); while (recent.size > 120) recent.removeFirst() }
        val l = learning
        if (l != null) {
            learnRows.add(row)
            if (learnRows.size >= 20) finishLearning(l)
            return
        }
        for (s in all) check(s, row)
    }

    /**
     * How different this second's sound "shape" is from the learned one (0 = identical). Shape means the
     * balance between low and high pitches, with the overall loudness taken out, so a kettle that's still
     * getting going (quieter) or roaring away (louder) still counts as the kettle.
     */
    private fun score(s: Sound, row: DoubleArray): Double {
        val m = s.mean ?: return 99.0; val sp = s.spread!!
        val rowAvg = row.average(); val mAvg = m.average()
        var tot = 0.0
        for (b in row.indices) tot += abs((row[b] - rowAvg) - (m[b] - mAvg)) / maxOf(sp[b], 2.0)
        return tot / row.size
    }
    /** the quiet room's background level (drifts up slowly, drops straight down to anything quieter) */
    @Volatile var roomFloor = 200.0
        private set
    private fun loudEnough(s: Sound, row: DoubleArray): Boolean {
        val a = row.average()
        return a > s.mean!!.average() - 15.0 && a > roomFloor + 2.5
    }

    /** Pip's own voice is playing: don't let it count as (or against) a machine. */
    @Volatile var pipTalking = false
    private var stage = 0; private var run1 = 0; private var stageAt = 0L
    /** First check passed ("is that the kettle?"): Pip can glance over. */
    @Volatile var onMaybe: (Sound) -> Unit = {}

    private fun check(s: Sound, row: DoubleArray) {
        if (s.mean == null && s !== kettle) return
        if (pipTalking) return
        val now = System.currentTimeMillis()
        val sc = if (s.mean != null) score(s, row) else 99.0; s.lastScore = sc
        val loud = s.mean != null && loudEnough(s, row)
        if (s === kettle) { checkKettle(row, sc < s.limit && loud); return }
        // the dryer: most of the last 3 minutes sounded like it
        s.lastLoudEnough = loud
        s.history.addLast(sc < s.limit && loud)
        while (s.history.size > 180) s.history.removeFirst()
        val share = s.history.count { it }.toDouble() / maxOf(1, s.history.size)
        s.share = share
        if (s.history.size < 90) return
        if (!s.on && share > 0.5) { s.on = true; s.onSince = now; onChange(s, true) }
        else if (s.on && share < 0.2) { s.on = false; onChange(s, false) }
    }

    /**
     * The kettle sits right next to Pip, so it's a sudden jump in loudness over whatever the kitchen sounded like
     * a minute or two ago (that way the dryer droning away doesn't count), or its learned pattern (unless it
     * sounds more like the dryer). Two quick checks: 4 seconds of it = "is that the kettle?", a short pause,
     * then 4 more seconds = confirmed. One odd second in the middle is forgiven.
     */
    private fun checkKettle(row: DoubleArray, patternHit: Boolean) {
        val s = kettle; val now = System.currentTimeMillis()
        val avg = row.average()
        val base = if (recent.size >= 20) baseline() else roomFloor
        val roaring = recent.size >= 20 && avg > base + 8.0
        val newSound = avg > base + 3.0                             // something louder than the kitchen was a minute ago
        val d = dryer
        val dryerLike = d.mean != null && score(d, row) < d.limit && !patternHit
        val hit = (patternHit && !dryerLike && newSound) || roaring
        s.lastLoudEnough = roaring || patternHit
        s.history.addLast(hit); while (s.history.size > 20) s.history.removeFirst()
        s.share = s.history.count { it }.toDouble() / s.history.size
        if (s.on) {
            quiet = if (hit) 0 else quiet + 1
            if (quiet >= 15 || now - s.onSince > 6 * 60_000) { s.on = false; quiet = 0; onChange(s, false) }   // clicked off
            return
        }
        fun counted(): Boolean {                                   // 4 hits, forgiving one miss at a time
            if (hit) { run1++; misses = 0 } else if (++misses >= 2) { run1 = 0; misses = 0 }
            return run1 >= 4
        }
        when (stage) {
            0 -> if (counted()) { stage = 1; stageAt = now; run1 = 0; misses = 0; onMaybe(s) }
            1 -> if (now - stageAt >= 3000) { stage = 2; run1 = 0; misses = 0 }          // a little pause, then check again
            2 -> if (counted()) { stage = 0; run1 = 0; s.on = true; s.onSince = now; quiet = 0; onChange(s, true) }
                 else if (now - stageAt > 18_000) { stage = 0; run1 = 0 }               // didn't confirm: never mind
        }
    }

    /** what the kitchen has sounded like over the last two minutes (frozen while the kettle's going) */
    private val recent = ArrayDeque<Double>()
    private fun baseline(): Double { val l = recent.sorted(); return l[l.size / 4] }
    private var quiet = 0; private var misses = 0

    private fun finishLearning(s: Sound) {
        val rows = learnRows; learning = null
        val mean = DoubleArray(16) { b -> rows.sumOf { it[b] } / rows.size }
        val spread = DoubleArray(16) { b -> Math.sqrt(rows.sumOf { (it[b] - mean[b]).let { d -> d * d } } / rows.size) }
        s.mean = mean; s.spread = spread
        // how far its own seconds were from the average: allow a bit more than that
        val own = rows.map { score(s, it) }.sorted()
        s.limit = maxOf(3.0, own[(own.size * 0.9).toInt().coerceAtMost(own.size - 1)] * 2.5)
        s.history.clear(); s.on = false
        save(s)
        val ok = mean.average() > roomFloor + 2.5                       // clearly louder than the quiet room
        learnDone?.invoke(ok); learnDone = null
    }

    private fun save(s: Sound) {
        prefs.edit().putString("snd2_${s.name}", s.mean!!.joinToString(",") + "|" + s.spread!!.joinToString(",") + "|" + s.limit + "|v3").apply()
    }
    private fun load(s: Sound) {
        val t = prefs.getString("snd2_${s.name}", null) ?: return
        try {
            val p = t.split("|")
            s.mean = p[0].split(",").map { it.toDouble() }.toDoubleArray()
            s.spread = p[1].split(",").map { it.toDouble() }.toDoubleArray()
            s.limit = if (p.size > 3) p[2].toDouble()
                else maxOf(3.0, p[2].toDouble() * 2.5 / 1.8)          // learned on build 108: that was too strict
        } catch (_: Exception) { s.mean = null }
    }
    fun learned(s: Sound) = s.mean != null
    fun forget(s: Sound) { s.mean = null; s.on = false; s.history.clear(); prefs.edit().remove("snd2_${s.name}").apply() }

    companion object {
        /** The mic hands every chunk here (whichever Ears is running). */
        @Volatile var tap: ((ShortArray, Int, Int) -> Unit)? = null
    }
}
