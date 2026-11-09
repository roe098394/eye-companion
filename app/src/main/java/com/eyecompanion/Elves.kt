package com.eyecompanion

import android.graphics.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Naughty Christmas elves running riot on Pip's screen, 6 Nov to Christmas Day. One bit of mischief at a time,
 * every few minutes. Positions are in the (upright) face frame Pip draws in.
 */
class Elves {
    companion object {
        const val CLIMB = 1; const val NICK = 2; const val NICK_BACK = 3; const val DOODLE = 4; const val BOUNCE = 5
        const val TICKLE = 6; const val PEEK = 7; const val SNOW = 8; const val TINSEL = 9; const val FIX = 10; const val FLEE = 11
        /** Where the real crack in Pip's glass is, as a fraction of the physical screen (from Mark's photos). */
        const val CRACK_X = 0.36f; const val CRACK_Y = 0.45f
        fun season(): Boolean {
            val c = java.util.Calendar.getInstance()
            val m = c.get(java.util.Calendar.MONTH); val d = c.get(java.util.Calendar.DAY_OF_MONTH)
            return (m == java.util.Calendar.NOVEMBER && d >= 6) || (m == java.util.Calendar.DECEMBER && d <= 25)
        }
    }

    /** Tells Pip what happened: "tickle", "found", "snowball", "doodle", "nicked", "returned", "tinsel". */
    var onEvent: (String) -> Unit = {}
    var act = 0; private set
    private var actStart = 0L
    private var nextAt = 0L
    private var nickBackAt = 0L
    private var side = 1f
    private var fired = HashSet<String>()
    /** The cuppa count shown as "?" while an elf has run off with it. */
    @Volatile var cuppaNicked = false; private set
    /** A moustache drawn on Pip while she slept (0..1 how finished), until she notices. */
    @Volatile var doodle = 0f
    /** Tinsel round the kettle side while it boils. */
    @Volatile var tinsel = false
    private var peekFound = 0L
    private var peekX = 0f; private var peekY = 0f; private var peekS = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val r = RectF()

    fun start(which: Int, now: Long) { act = which; actStart = now; fired.clear(); side = if (Random.nextBoolean()) 1f else -1f; peekFound = 0L }
    fun kettleOn(on: Boolean, now: Long) {
        tinsel = false
        if (on && season() && (act == 0 || act == PEEK)) start(TINSEL, now)
    }
    private fun once(tag: String) { if (fired.add(tag)) onEvent(tag) }

    /** Called every frame. sleeping = sleep screen up; busy = Pip talking/thinking/doing something (no new mischief). */
    fun tick(now: Long, sleeping: Boolean, busy: Boolean) {
        if (!season()) { act = 0; cuppaNicked = false; return }
        if (nextAt == 0L) nextAt = now + 40_000
        if (nickBackAt in 1..now && act == 0) { nickBackAt = 0L; start(NICK_BACK, now); return }
        if (act != 0 || busy || now < nextAt) return
        val pick = if (sleeping) listOf(CLIMB, NICK, DOODLE, BOUNCE, CLIMB, BOUNCE, FIX).filter { !(it == NICK && cuppaNicked) && !(it == DOODLE && doodle > 0f) }.random()
            else listOf(TICKLE, PEEK, PEEK, SNOW, FIX).random()
        start(pick, now)
    }
    private fun finish(now: Long) { act = 0; nextAt = now + Random.nextLong(90_000, 240_000) }

    /** Where each elf was drawn this frame (feet x, y, size, facing), so a tap can find them. */
    private val drawn = ArrayList<FloatArray>()
    private var fleeing = ArrayList<FloatArray>()

    /** A tap on an elf? A hiding one is "caught"; any other one gets a fright and runs off. */
    fun hit(x: Float, y: Float): Boolean {
        if (act == PEEK && peekFound == 0L && peekS > 0f &&
            abs(x - peekX) < peekS * 0.6f && y > peekY - peekS * 1.1f && y < peekY + peekS * 0.2f) {
            peekFound = System.currentTimeMillis(); onEvent("found"); return true
        }
        if (act == 0 || act == PEEK || act == FLEE) return false
        val near = drawn.any { e -> abs(x - e[0]) < e[2] * 0.7f && y > e[1] - e[2] * 1.2f && y < e[1] + e[2] * 0.3f }
        if (!near) return false
        fleeing = ArrayList(drawn.map { it.copyOf() })
        if (act == NICK && cuppaNicked) { cuppaNicked = false; nickBackAt = 0L }   // dropped the number in the panic
        if (act == DOODLE && doodle < 1f) doodle = 0f                             // ran off before finishing it
        if (act == TINSEL) tinsel = false
        act = FLEE; actStart = System.currentTimeMillis(); fired.clear()
        onEvent("scared")
        return true
    }

    /**
     * Draw. behind = the pass before the eyes are drawn (peeking elves hide behind the eyes).
     * clockW = width of the big clock text (sleep screen), eye geometry for aiming at the eyes.
     */
    fun draw(c: Canvas, now: Long, w: Float, h: Float, behind: Boolean, cx: Float, cy: Float, gap: Float, eyeW: Float, eyeH: Float,
             clockW: Float, zx: Float, zy: Float, crackX: Float = w * CRACK_X, crackY: Float = h * CRACK_Y) {
        val s = h * 0.085f
        val t = (now - actStart) / 1000f
        val eyeR = cx + gap / 2 + eyeW / 2; val eyeL = cx - gap / 2 - eyeW / 2
        if (!behind && tinsel) drawTinsel(c, w, h, now)
        if (!behind && doodle > 0f) drawMoustache(c, cx, cy + eyeW * 0.55f, eyeW, doodle)
        if (behind) drawn.clear()
        if (act == 0) return
        if (behind != (act == PEEK)) return
        when (act) {
            FLEE -> {                                                    // tapped: scatter to the nearest side, arms up, squeaking
                if (t > 1.6f) { finish(now); return }
                for (e in fleeing) {
                    val dir = if (e[0] < w / 2) -1f else 1f
                    val q = t / 1.6f
                    elf(c, e[0] + dir * q * q * w * 0.7f, e[1] - abs(sin(t * 14f)) * e[2] * 0.15f, e[2], dir, t * 2.5f, arms = 1f)
                }
            }
            CLIMB -> {                                                   // over the big clock and down the Christmas line
                val l = w / 2 - clockW / 2 - s * 0.3f; val rr = w / 2 + clockW / 2 + s * 0.3f; val base = h * 0.26f
                when {
                    t < 3f -> elf(c, lerp(-s, l, t / 3f), base, s, 1f, t)
                    t < 5.5f -> { val q = (t - 3f) / 2.5f; elf(c, lerp(l, rr, q), base - sin(q * Math.PI.toFloat()) * h * 0.17f, s, 1f, t, arms = 1f) }
                    t < 7f -> { val q = (t - 5.5f) / 1.5f; elf(c, rr + q * s * 2f, lerp(base, h * 0.335f, q), s, 1f, 0f, arms = 1f) }      // whee
                    t < 9.5f -> elf(c, lerp(rr + s * 2f, w + s * 2f, (t - 7f) / 2.5f), h * 0.335f, s, 1f, t)
                    else -> finish(now)
                }
            }
            NICK -> {                                                    // pinch the cuppa number and leg it
                val tx = w / 2 + clockW * 0.22f; val ty = h * 0.72f
                when {
                    t < 3.5f -> elf(c, lerp(w + s, tx, t / 3.5f), ty, s, -1f, t)
                    t < 4.3f -> { elf(c, tx, ty - abs(sin(t * 12f)) * s * 0.15f, s, -1f, 0f, arms = 1f); if (t > 4f) { cuppaNicked = true; once("nicked") } }
                    t < 8f -> elf(c, lerp(tx, -s * 2f, (t - 4.3f) / 3.7f), ty, s, -1f, t * 1.5f, carry = "7")
                    else -> { nickBackAt = now + 25_000; finish(now) }
                }
            }
            NICK_BACK -> {
                val tx = w / 2 + clockW * 0.22f; val ty = h * 0.72f
                when {
                    t < 3.5f -> elf(c, lerp(-s * 2f, tx, t / 3.5f), ty, s, 1f, t, carry = "7")
                    t < 4.2f -> { elf(c, tx, ty, s, 1f, 0f, arms = 1f); if (cuppaNicked) { cuppaNicked = false; once("returned") } }
                    t < 7f -> elf(c, lerp(tx, w + s * 2f, (t - 4.2f) / 2.8f), ty, s, 1f, t)
                    else -> finish(now)
                }
            }
            DOODLE -> {                                                  // a moustache on the sleeping Pip
                val mx = cx; val my = cy + eyeW * 0.55f + s * 0.9f
                when {
                    t < 3f -> elf(c, lerp(w + s, mx + eyeW * 0.5f, t / 3f), my, s, -1f, t, carry = "pen")
                    t < 9f -> { val q = (t - 3f) / 6f; doodle = maxOf(doodle, q)
                        elf(c, mx + eyeW * 0.5f - q * eyeW, my - abs(sin(t * 10f)) * s * 0.08f, s, -1f, 0f, arms = sin(t * 14f), carry = "pen") }
                    t < 12f -> { doodle = 1f; once("doodle"); elf(c, lerp(mx - eyeW * 0.5f, -s * 2f, (t - 9f) / 3f), my, s, -1f, t * 1.3f) }
                    else -> finish(now)
                }
            }
            BOUNCE -> {                                                  // trampolining on the Z's
                if (t > 9f) { finish(now); return }
                val hop = abs(sin(t * 3.2f)); val ex = zx + sin(t * 0.8f) * s * 0.6f
                val ey = zy + s * 0.8f - hop * h * 0.12f
                val fade = (minOf(t, 9f - t) / 0.6f).coerceIn(0f, 1f)
                p.alpha = 255; elf(c, ex, ey, s * 0.85f, if (cos(t * 0.8f) > 0) 1f else -1f, 0f, arms = hop, alpha = fade)
            }
            TICKLE -> {                                                  // pops up and tickles an eye
                val ex = if (side > 0) eyeR else eyeL; val by = cy + eyeH * 0.5f + s * 1.05f
                val rise = when { t < 0.8f -> t / 0.8f; t < 4.5f -> 1f; t < 5.3f -> 1f - (t - 4.5f) / 0.8f; else -> { finish(now); return } }
                if (t > 1f) once("tickle")
                elf(c, ex, by + (1f - rise) * s * 1.6f, s, -side, 0f, arms = sin(t * 18f))
            }
            PEEK -> {                                                    // hiding behind an eye; tap to catch it
                val ex = (if (side > 0) eyeR + eyeW * 0.42f else eyeL - eyeW * 0.42f); val by = cy + eyeH * 0.2f
                if (peekFound != 0L) {
                    val q = (now - peekFound) / 1000f
                    if (q > 1.6f) { peekS = 0f; finish(now); return }
                    elf(c, ex + side * q * w * 0.4f, by, s, side, q * 3f)   // squeak! and off it runs
                    return
                }
                if (t > 11f) { peekS = 0f; finish(now); return }
                val bob = sin(t * 2.5f) * s * 0.06f
                peekX = ex; peekY = by; peekS = s
                elf(c, ex, by + bob, s, -side, 0f, arms = 0.2f)
            }
            SNOW -> {                                                    // snowball fight, one hits Pip in the eye
                val ax = w * 0.08f; val bx = w * 0.92f; val gy = h * 0.92f
                if (t > 8f) { finish(now); return }
                elf(c, ax, gy, s, 1f, 0f, arms = if ((t % 2f) < 0.3f) 1f else 0f)
                elf(c, bx, gy, s, -1f, 0f, arms = if (((t + 1f) % 2f) < 0.3f) 1f else 0f)
                p.color = Color.WHITE; p.alpha = 255
                for (k in 0 until 3) {
                    val st = 0.5f + k * 1.6f; val q = (t - st) / 1.2f
                    if (q !in 0f..1f) continue
                    val fromA = k % 2 == 0
                    val x0 = if (fromA) ax else bx; val x1 = if (fromA) bx else ax
                    c.drawCircle(lerp(x0, x1, q), gy - s * 1.1f - sin(q * Math.PI.toFloat()) * h * 0.35f, s * 0.13f, p)
                }
                val q = (t - 5.3f) / 0.7f                                  // the last one: straight at Pip
                if (q in 0f..1f) c.drawCircle(lerp(bx, eyeR, q), lerp(gy - s, cy, q) - sin(q * Math.PI.toFloat()) * h * 0.1f, s * 0.15f, p)
                if (t > 6f) {
                    once("snowball")
                    val a = (1f - (t - 6f) / 2f).coerceIn(0f, 1f)
                    p.alpha = (230 * a).toInt()
                    for (k in 0 until 7) c.drawCircle(eyeR + cos(k * 0.9f) * eyeW * 0.18f, cy + sin(k * 0.9f) * eyeW * 0.18f, eyeW * (0.12f + 0.04f * (k % 3)), p)
                }
            }
            FIX -> {                                                     // fixing the crack in Pip's screen. Badly.
                val gy = crackY + s * 0.55f
                when {
                    t < 3f -> { elf(c, lerp(-s * 2f, crackX - s * 0.55f, t / 3f), gy, s, 1f, t, carry = "hammer")
                        elf(c, lerp(-s * 3.2f, crackX + s * 0.55f, t / 3f), gy, s, 1f, t + 0.3f, carry = "tape") }
                    t < 9f -> {
                        val q = t - 3f
                        val bang = sin(q * 9f)                                // hammering away
                        elf(c, crackX - s * 0.55f, gy, s, 1f, 0f, arms = 0.5f + 0.5f * bang, carry = "hammer")
                        elf(c, crackX + s * 0.55f, gy - abs(sin(q * 5f)) * s * 0.1f, s, -1f, 0f, arms = 1f, carry = "tape")
                        tape(c, crackX, crackY, s, (q / 4.5f).coerceIn(0f, 1f), 0f)
                        if (bang > 0.95f) { p.color = Color.parseColor("#FFE08A"); p.alpha = 220
                            for (k in 0 until 4) c.drawCircle(crackX - s * 0.15f + cos(k * 1.6f) * s * 0.18f, crackY + sin(k * 1.6f) * s * 0.18f, s * 0.03f, p) }
                        if (q > 1f) once("fixing")
                    }
                    t < 12f -> {                                          // stand back... and the tape falls off
                        val q = (t - 9f) / 3f
                        elf(c, crackX - s * 0.9f, gy, s, 1f, 0f, arms = if (q > 0.55f) 1f else 0f)
                        elf(c, crackX + s * 0.9f, gy, s, -1f, 0f, arms = if (q > 0.55f) 1f else 0f)
                        tape(c, crackX, crackY, s, 1f, ((q - 0.35f) / 0.65f).coerceIn(0f, 1f))
                        if (q > 0.6f) once("fixfail")
                    }
                    t < 14.5f -> { val q = (t - 12f) / 2.5f
                        elf(c, lerp(crackX - s * 0.9f, -s * 2f, q), gy, s, -1f, t, carry = "hammer"); elf(c, lerp(crackX + s * 0.9f, -s * 3f, q), gy, s, -1f, t + 0.3f) }
                    else -> finish(now)
                }
            }
            TINSEL -> {                                                  // tinsel round the kettle (it's on Pip's right = screen left)
                if (t > 7f) { tinsel = true; once("tinsel"); finish(now); return }
                val gy = h * 0.9f
                val x1 = lerp(w + s, w * 0.12f, (t / 5f).coerceAtMost(1f)); val x2 = x1 + s * 2.2f
                elf(c, x1, gy, s, -1f, if (t < 5f) t else 0f, arms = 1f); elf(c, x2, gy, s, -1f, if (t < 5f) t + 0.4f else 0f, arms = 1f)
                garland(c, x1 - s * 0.25f, gy - s * 0.55f, x2 - s * 0.25f, gy - s * 0.55f, s * 0.35f, now)
                if (t > 5f) { tinsel = true }
            }
        }
    }

    private fun lerp(a: Float, b: Float, q: Float) = a + (b - a) * q.coerceIn(0f, 1f)

    /** One elf. x,y = feet. facing 1 right / -1 left. walk = phase for the legs (0 = standing). arms 0..1 = raised. */
    private fun elf(c: Canvas, x: Float, y: Float, s: Float, facing: Float, walk: Float, arms: Float = 0f, carry: String? = null, alpha: Float = 1f) {
        if (act != FLEE) drawn.add(floatArrayOf(x, y, s, facing))
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        fun col(v: Int) { p.color = v; p.alpha = a }
        val sw = sin(walk * 9f) * s * 0.07f
        // legs (red and white stripes) and curly green shoes
        line.strokeWidth = s * 0.08f
        for ((k, dx) in listOf(-1f to -s * 0.07f, 1f to s * 0.07f)) {
            val off = if (walk != 0f) k * sw else 0f
            line.color = Color.parseColor("#E8433A"); line.alpha = a
            c.drawLine(x + dx, y - s * 0.24f, x + dx + off, y - s * 0.04f, line)
            col(Color.parseColor("#1E7A3C"))
            r.set(x + dx + off - s * 0.05f, y - s * 0.07f, x + dx + off + s * 0.1f * facing.coerceAtLeast(0f) + s * 0.05f + (if (facing < 0) 0f else 0f), y)
            c.drawOval(x + dx + off - s * 0.07f, y - s * 0.06f, x + dx + off + s * 0.07f, y + s * 0.01f, p)
            c.drawCircle(x + dx + off + facing * s * 0.08f, y - s * 0.07f, s * 0.025f, p)
        }
        // tunic
        col(Color.parseColor("#2FA84F"))
        path.reset(); path.moveTo(x - s * 0.13f, y - s * 0.6f); path.lineTo(x + s * 0.13f, y - s * 0.6f)
        path.lineTo(x + s * 0.2f, y - s * 0.22f); path.lineTo(x - s * 0.2f, y - s * 0.22f); path.close(); c.drawPath(path, p)
        col(Color.parseColor("#1B1B1B")); c.drawRect(x - s * 0.17f, y - s * 0.36f, x + s * 0.17f, y - s * 0.31f, p)
        col(Color.parseColor("#FFD24A")); c.drawRect(x - s * 0.03f, y - s * 0.37f, x + s * 0.03f, y - s * 0.30f, p)
        // arms
        line.color = Color.parseColor("#2FA84F"); line.alpha = a; line.strokeWidth = s * 0.07f
        for (k in listOf(-1f, 1f)) {
            val ang = (0.35f + arms * 2.2f)                               // down by the side .. up in the air
            c.drawLine(x + k * s * 0.13f, y - s * 0.55f, x + k * (s * 0.13f + sin(ang) * s * 0.2f), y - s * 0.55f + cos(ang) * s * 0.2f, line)
        }
        // head, ears, face
        val hy = y - s * 0.72f
        col(Color.parseColor("#F4C9A0"))
        for (k in listOf(-1f, 1f)) {
            path.reset(); path.moveTo(x + k * s * 0.1f, hy - s * 0.03f); path.lineTo(x + k * s * 0.22f, hy - s * 0.1f); path.lineTo(x + k * s * 0.1f, hy + s * 0.04f); path.close(); c.drawPath(path, p)
        }
        c.drawCircle(x, hy, s * 0.12f, p)
        col(Color.parseColor("#222222")); c.drawCircle(x + facing * s * 0.04f - s * 0.035f, hy - s * 0.01f, s * 0.018f, p); c.drawCircle(x + facing * s * 0.04f + s * 0.035f, hy - s * 0.01f, s * 0.018f, p)
        col(Color.parseColor("#FF8FA0")); c.drawCircle(x + facing * s * 0.05f + s * 0.07f, hy + s * 0.04f, s * 0.025f, p); c.drawCircle(x + facing * s * 0.05f - s * 0.07f, hy + s * 0.04f, s * 0.025f, p)
        line.color = Color.parseColor("#7A3A2A"); line.alpha = a; line.strokeWidth = s * 0.02f
        c.drawArc(x + facing * s * 0.04f - s * 0.04f, hy + s * 0.02f, x + facing * s * 0.04f + s * 0.04f, hy + s * 0.07f, 10f, 160f, false, line)   // cheeky grin
        // floppy red hat with a white bobble
        col(Color.parseColor("#D7263D"))
        path.reset(); path.moveTo(x - s * 0.13f, hy - s * 0.06f); path.lineTo(x + s * 0.13f, hy - s * 0.06f)
        path.quadTo(x + s * 0.02f, hy - s * 0.3f, x - facing * s * 0.22f, hy - s * 0.28f + sin(walk * 6f) * s * 0.02f); path.close(); c.drawPath(path, p)
        col(Color.WHITE); c.drawRect(x - s * 0.14f, hy - s * 0.1f, x + s * 0.14f, hy - s * 0.05f, p)
        c.drawCircle(x - facing * s * 0.22f, hy - s * 0.28f + sin(walk * 6f) * s * 0.02f, s * 0.045f, p)
        // whatever it's carrying
        when (carry) {
            "pen" -> { line.color = Color.parseColor("#111111"); line.alpha = a; line.strokeWidth = s * 0.04f
                c.drawLine(x + facing * s * 0.3f, y - s * 0.45f, x + facing * s * 0.42f, y - s * 0.3f, line) }
            "hammer" -> { line.color = Color.parseColor("#8B5A2B"); line.alpha = a; line.strokeWidth = s * 0.04f
                val hx = x + facing * (s * 0.13f + sin(0.35f + arms * 2.2f) * s * 0.2f); val hy2 = y - s * 0.55f + cos(0.35f + arms * 2.2f) * s * 0.2f
                c.drawLine(hx, hy2, hx + facing * s * 0.05f, hy2 - s * 0.25f, line)
                col(Color.parseColor("#9AA3AD")); c.drawRect(hx + facing * s * 0.05f - s * 0.08f, hy2 - s * 0.31f, hx + facing * s * 0.05f + s * 0.08f, hy2 - s * 0.22f, p) }
            "tape" -> { col(Color.parseColor("#F3E7B5")); c.drawCircle(x + facing * s * 0.25f, y - s * 0.5f, s * 0.09f, p)
                col(Color.parseColor("#C9B98A")); c.drawCircle(x + facing * s * 0.25f, y - s * 0.5f, s * 0.04f, p) }
            null -> {}
            else -> { col(Color.parseColor("#5CE1FF")); val tp = Paint(p).apply { textSize = s * 0.45f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
                c.drawText(carry, x, y - s * 0.95f, tp) }
        }
    }

    /** Sticky tape across the crack in an X (done = how much is stuck on, fall = 0..1 peeling off and dropping). */
    private fun tape(c: Canvas, x: Float, y: Float, s: Float, done: Float, fall: Float) {
        if (done <= 0f) return
        val drop = fall * fall * s * 3f; val spin = fall * 40f
        c.save(); c.translate(x, y + drop); c.rotate(spin)
        p.color = Color.parseColor("#F3E7B5"); p.alpha = (200 * (1f - fall)).toInt()
        for ((k, a) in listOf(35f, -35f).withIndex()) {
            if (done < (k + 1) * 0.5f - 0.5f) continue
            c.save(); c.rotate(a); r.set(-s * 0.55f * minOf(1f, done * 2f - k), -s * 0.08f, s * 0.55f * minOf(1f, done * 2f - k), s * 0.08f); c.drawRect(r, p); c.restore()
        }
        c.restore()
    }

    private fun garland(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, sag: Float, now: Long) {
        path.reset(); path.moveTo(x1, y1); path.quadTo((x1 + x2) / 2, (y1 + y2) / 2 + sag, x2, y2)
        line.strokeWidth = sag * 0.35f; line.color = Color.parseColor("#FFC93C"); line.alpha = 255; c.drawPath(path, line)
        line.strokeWidth = sag * 0.12f; line.color = Color.parseColor("#FFF2A8"); line.alpha = (150 + 100 * sin(now / 120f)).toInt().coerceIn(0, 255); c.drawPath(path, line)
    }
    private fun drawTinsel(c: Canvas, w: Float, h: Float, now: Long) {
        val x = w * 0.035f
        for (k in 0 until 5) garland(c, x + (k % 2) * w * 0.03f, h * (0.15f + k * 0.16f), x + ((k + 1) % 2) * w * 0.03f, h * (0.31f + k * 0.16f), w * 0.025f, now + k * 90)
    }
    private fun drawMoustache(c: Canvas, mx: Float, my: Float, eyeW: Float, done: Float) {
        line.color = Color.parseColor("#2A1A12"); line.alpha = 255; line.strokeWidth = eyeW * 0.09f
        val half = eyeW * 0.55f
        for (k in listOf(-1f, 1f)) {
            path.reset(); path.moveTo(mx, my)
            path.cubicTo(mx + k * half * 0.4f, my - eyeW * 0.12f, mx + k * half * 0.8f, my + eyeW * 0.12f, mx + k * half, my - eyeW * 0.08f)
            path.quadTo(mx + k * half * 1.15f, my - eyeW * 0.2f, mx + k * half * 0.98f, my - eyeW * 0.24f)
            val pm = PathMeasure(path, false); val seg = Path(); pm.getSegment(0f, pm.length * done.coerceIn(0f, 1f), seg, true)
            c.drawPath(seg, line)
        }
    }
}
