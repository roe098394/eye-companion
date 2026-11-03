package com.kittyeggs

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*
import kotlin.random.Random

/** Kitty: a big friendly cat face that blinks, talks, listens and celebrates, plus egg hatching and confetti. */
class KittyView(context: Context) : View(context) {

    enum class Mood { HAPPY, TALK, LISTEN, WOW, NEARLY, SLEEPY, LOVE }

    @Volatile var mood = Mood.HAPPY
    @Volatile var talking = false
    @Volatile var level = 0f                 // how loud Chloe is (0..1) while listening
    /** where the cat sits: centre x, centre y (fractions of the view) and size (fraction of height) */
    var catX = 0.5f; var catY = 0.42f; var catSize = 0.30f
    private var cx = 0.5f; private var cy = 0.42f; private var cs = 0.30f
    /** eggs filled so far towards the next hatch (0..eggsNeeded) */
    var eggs = 0; var eggsNeeded = 3; var showEggs = false
    var hat = ""                              // "santa" in December

    private val start = System.currentTimeMillis()
    private fun now() = System.currentTimeMillis()
    private var nextBlink = now() + 2500; private var blinkAt = 0L
    private var moodUntil = 0L; private var moodAfter = Mood.HAPPY
    fun feel(m: Mood, ms: Long) { moodAfter = if (mood == Mood.TALK || mood == Mood.LISTEN) Mood.HAPPY else mood; mood = m; moodUntil = now() + ms }

    // ---- confetti / stars
    private class Bit(var x: Float, var y: Float, var vx: Float, var vy: Float, val col: Int, val star: Boolean, var life: Float)
    private val bits = ArrayList<Bit>()
    private val colours = intArrayOf(0xFFFF6FA8.toInt(), 0xFFFFD84A.toInt(), 0xFF5CE1FF.toInt(), 0xFF8BE36B.toInt(), 0xFFB98BFF.toInt())
    fun celebrate() {
        val w = width.toFloat(); val h = height.toFloat()
        repeat(70) {
            bits.add(Bit(w * (0.2f + 0.6f * Random.nextFloat()), h * 0.35f, (Random.nextFloat() - 0.5f) * w * 0.9f,
                -h * (0.4f + Random.nextFloat() * 0.9f), colours.random(), Random.nextFloat() < 0.4f, 1f))
        }
        feel(Mood.WOW, 1400)
    }

    // ---- egg hatching
    @Volatile var hatching = false
    private var hatchStart = 0L; private var hatchAnimal = ""
    fun hatch(animal: String) { hatchAnimal = animal; hatchStart = now(); hatching = true }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()
    private var lastFrame = now()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = now(); val secs = (t - start) / 1000f
        val dt = ((t - lastFrame) / 1000f).coerceIn(0f, 0.05f); lastFrame = t
        if (moodUntil in 1..t) { mood = moodAfter; moodUntil = 0 }
        // background: soft lilac to pink
        p.shader = LinearGradient(0f, 0f, 0f, h, 0xFFD9CCFF.toInt(), 0xFFFFD6EA.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        // a few floating paw prints
        p.color = 0x22FFFFFF
        for (i in 0..7) {
            val px = w * ((i * 0.137f + secs * 0.01f * (1 + i % 3)) % 1f); val py = h * ((i * 0.29f + 0.1f) % 1f)
            c.drawCircle(px, py, h * 0.025f, p)
            for (k in 0..2) c.drawCircle(px + (k - 1) * h * 0.022f, py - h * 0.032f, h * 0.011f, p)
        }
        // glide to where the cat should be
        cx += (catX - cx) * 0.12f; cy += (catY - cy) * 0.12f; cs += (catSize - cs) * 0.12f
        drawCat(c, w * cx, h * cy, h * cs, secs, t)
        if (showEggs) drawEggMeter(c, w, h)
        // confetti
        val it = bits.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.vy += h * 1.6f * dt; b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt * 0.45f
            if (b.life <= 0f || b.y > h) { it.remove(); continue }
            p.color = b.col; p.alpha = (255 * b.life.coerceIn(0f, 1f)).toInt()
            if (b.star) drawStar(c, b.x, b.y, h * 0.018f) else c.drawCircle(b.x, b.y, h * 0.009f, p)
        }
        p.alpha = 255
        if (hatching) {
            val age = (t - hatchStart) / 1000f
            if (age > 5.5f) hatching = false else drawHatch(c, w, h, age)
        }
        postInvalidateOnAnimation()
    }

    private fun drawCat(c: Canvas, x: Float, y0: Float, r: Float, secs: Float, t: Long) {
        val bob = sin(secs * 2f) * r * 0.02f + (if (mood == Mood.WOW) abs(sin(secs * 12f)) * r * -0.06f else 0f)
        val y = y0 + bob
        val tilt = when (mood) { Mood.NEARLY -> -8f; Mood.LISTEN -> 6f * sin(secs * 1.5f); else -> 2f * sin(secs * 0.7f) }
        c.save(); c.rotate(tilt, x, y)
        val fur = 0xFFFFB25B.toInt(); val furDark = 0xFFE8913A.toInt(); val pink = 0xFFFF8FB4.toInt()
        // ears (they twitch now and then; perk up when listening)
        val twitch = if ((secs % 5f) < 0.25f) sin(secs * 60f) * 6f else 0f
        for (side in listOf(-1f, 1f)) {
            val droop = if (mood == Mood.NEARLY && side > 0) 18f else 0f
            val perk = if (mood == Mood.LISTEN) -6f else 0f
            c.save(); c.rotate(side * (12f + droop + perk) + (if (side > 0) twitch else 0f), x + side * r * 0.55f, y - r * 0.55f)
            path.reset(); path.moveTo(x + side * r * 0.25f, y - r * 0.62f); path.lineTo(x + side * r * 0.62f, y - r * 1.25f)
            path.lineTo(x + side * r * 0.92f, y - r * 0.35f); path.close()
            p.color = fur; c.drawPath(path, p)
            path.reset(); path.moveTo(x + side * r * 0.4f, y - r * 0.6f); path.lineTo(x + side * r * 0.62f, y - r * 1.05f)
            path.lineTo(x + side * r * 0.8f, y - r * 0.45f); path.close()
            p.color = pink; c.drawPath(path, p)
            c.restore()
        }
        // head
        p.color = fur; rect.set(x - r, y - r * 0.85f, x + r, y + r * 0.85f); c.drawOval(rect, p)
        // tabby stripes on the forehead
        line.color = furDark; line.strokeWidth = r * 0.06f
        for (k in -1..1) c.drawLine(x + k * r * 0.16f, y - r * 0.8f, x + k * r * 0.12f, y - r * 0.55f, line)
        // cheeks
        p.color = 0x55FF6F9F; c.drawCircle(x - r * 0.58f, y + r * 0.22f, r * 0.15f, p); c.drawCircle(x + r * 0.58f, y + r * 0.22f, r * 0.15f, p)
        // eyes
        if (t > nextBlink) { blinkAt = t; nextBlink = t + Random.nextLong(2200, 5200) }
        val blink = if (t - blinkAt < 140) 0.1f else 1f
        for (side in listOf(-1f, 1f)) {
            val ex = x + side * r * 0.38f; val ey = y - r * 0.12f
            when {
                mood == Mood.HAPPY && (secs % 9f) > 7.5f || mood == Mood.LOVE -> {   // happy ^ ^ eyes
                    line.color = 0xFF2B2240.toInt(); line.strokeWidth = r * 0.07f
                    rect.set(ex - r * 0.16f, ey - r * 0.12f, ex + r * 0.16f, ey + r * 0.16f)
                    c.drawArc(rect, 200f, 140f, false, line)
                    if (mood == Mood.LOVE) { p.color = 0xFFFF4F8B.toInt(); drawHeart(c, ex, ey - r * 0.5f + sin(secs * 4 + side) * r * 0.05f, r * 0.12f) }
                }
                mood == Mood.SLEEPY -> {
                    line.color = 0xFF2B2240.toInt(); line.strokeWidth = r * 0.06f
                    c.drawLine(ex - r * 0.14f, ey + r * 0.04f, ex + r * 0.14f, ey + r * 0.04f, line)
                }
                else -> {
                    val big = if (mood == Mood.WOW) 1.25f else 1f
                    val look = if (mood == Mood.LISTEN) -r * 0.04f else 0f
                    p.color = 0xFF2B2240.toInt()
                    rect.set(ex - r * 0.15f * big, ey - r * 0.2f * big * blink, ex + r * 0.15f * big, ey + r * 0.2f * big * blink)
                    c.drawOval(rect, p)
                    if (blink > 0.5f) {
                        p.color = Color.WHITE
                        c.drawCircle(ex - r * 0.05f, ey - r * 0.08f + look, r * 0.06f * big, p)
                        c.drawCircle(ex + r * 0.05f, ey + r * 0.05f + look, r * 0.025f * big, p)
                    }
                }
            }
        }
        // nose
        p.color = pink; path.reset(); path.moveTo(x - r * 0.08f, y + r * 0.12f); path.lineTo(x + r * 0.08f, y + r * 0.12f)
        path.lineTo(x, y + r * 0.22f); path.close(); c.drawPath(path, p)
        // mouth: a little "w", open when talking or wowing
        val open = when { talking -> 0.5f + 0.5f * abs(sin(secs * 14f)); mood == Mood.WOW -> 1f; else -> 0f }
        line.color = 0xFF2B2240.toInt(); line.strokeWidth = r * 0.04f
        if (open > 0.05f) {
            p.color = 0xFF7A2D44.toInt(); rect.set(x - r * 0.13f, y + r * 0.24f, x + r * 0.13f, y + r * (0.3f + 0.22f * open)); c.drawOval(rect, p)
            p.color = pink; rect.set(x - r * 0.07f, y + r * (0.27f + 0.12f * open), x + r * 0.07f, y + r * (0.3f + 0.2f * open)); c.drawOval(rect, p)
        } else {
            path.reset(); path.moveTo(x - r * 0.18f, y + r * 0.27f); path.quadTo(x - r * 0.09f, y + r * 0.36f, x, y + r * 0.25f)
            path.quadTo(x + r * 0.09f, y + r * 0.36f, x + r * 0.18f, y + r * 0.27f); c.drawPath(path, line)
        }
        // whiskers
        line.color = 0xAA6B4A3A.toInt(); line.strokeWidth = r * 0.02f
        for (side in listOf(-1f, 1f)) for (k in -1..1) {
            val wig = sin(secs * 3f + k) * r * 0.02f
            c.drawLine(x + side * r * 0.3f, y + r * 0.2f + k * r * 0.06f, x + side * r * 0.95f, y + r * 0.12f + k * r * 0.12f + wig, line)
        }
        // listening: little sound rings by the ear
        if (mood == Mood.LISTEN) {
            line.color = 0xFF7B5CFF.toInt(); line.strokeWidth = r * 0.04f
            for (k in 1..3) {
                line.alpha = (255 * (0.3f + level.coerceIn(0f, 1f) * 0.7f) * (1f - k * 0.2f)).toInt()
                val rr = r * (0.2f + k * 0.13f + level * 0.15f)
                rect.set(x + r * 1.05f - rr, y - r * 0.5f - rr, x + r * 1.05f + rr, y - r * 0.5f + rr)
                c.drawArc(rect, -50f, 100f, false, line)
            }
            line.alpha = 255
        }
        if (mood == Mood.SLEEPY) { emoji.textSize = r * 0.35f; c.drawText("z", x + r * 0.9f, y - r * 0.9f - (secs % 2f) * r * 0.2f, emoji) }
        if (hat == "santa") drawSanta(c, x, y, r, secs)
        c.restore()
    }

    private fun drawSanta(c: Canvas, x: Float, y: Float, r: Float, secs: Float) {
        path.reset(); path.moveTo(x - r * 0.6f, y - r * 0.68f); path.quadTo(x - r * 0.1f, y - r * 1.55f, x + r * 0.75f, y - r * 1.05f + sin(secs * 1.5f) * r * 0.05f)
        path.quadTo(x + r * 0.2f, y - r * 1.0f, x + r * 0.55f, y - r * 0.7f); path.close()
        p.color = 0xFFE0262E.toInt(); c.drawPath(path, p)
        p.color = Color.WHITE; rect.set(x - r * 0.7f, y - r * 0.82f, x + r * 0.65f, y - r * 0.6f); c.drawRoundRect(rect, r * 0.1f, r * 0.1f, p)
        c.drawCircle(x + r * 0.75f, y - r * 1.05f + sin(secs * 1.5f) * r * 0.05f, r * 0.11f, p)
    }

    private fun drawEggMeter(c: Canvas, w: Float, h: Float) {
        val r = h * 0.045f
        for (i in 0 until eggsNeeded) {
            val ex = w * 0.5f + (i - (eggsNeeded - 1) / 2f) * r * 2.6f; val ey = h * 0.06f + r
            rect.set(ex - r * 0.78f, ey - r, ex + r * 0.78f, ey + r)
            p.color = if (i < eggs) 0xFFFFF3D6.toInt() else 0x55FFFFFF; c.drawOval(rect, p)
            if (i < eggs) { p.color = 0xFFF7B6D2.toInt(); c.drawCircle(ex - r * 0.25f, ey - r * 0.1f, r * 0.15f, p); c.drawCircle(ex + r * 0.25f, ey + r * 0.3f, r * 0.12f, p) }
            line.color = 0x88FFFFFF.toInt(); line.strokeWidth = r * 0.08f; c.drawOval(rect, line)
        }
    }

    private fun drawStar(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset()
        for (i in 0 until 10) {
            val a = (i * 36 - 90) * PI.toFloat() / 180f; val rr = if (i % 2 == 0) r else r * 0.45f
            if (i == 0) path.moveTo(x + rr * cos(a), y + rr * sin(a)) else path.lineTo(x + rr * cos(a), y + rr * sin(a))
        }
        path.close(); c.drawPath(path, p)
    }

    private fun drawHeart(c: Canvas, x: Float, y: Float, s: Float) {
        path.reset(); path.moveTo(x, y + s); path.cubicTo(x - s * 1.6f, y - s * 0.2f, x - s * 0.6f, y - s * 1.4f, x, y - s * 0.4f)
        path.cubicTo(x + s * 0.6f, y - s * 1.4f, x + s * 1.6f, y - s * 0.2f, x, y + s); c.drawPath(path, p)
    }

    private fun drawHatch(c: Canvas, w: Float, h: Float, age: Float) {
        val x = w / 2; val y = h * 0.52f; val r = h * 0.2f
        c.drawColor(Color.argb((140 * min(1f, age * 3)).toInt(), 40, 20, 70))
        if (age < 2.4f) {
            val wob = sin(age * 18f) * min(1f, age) * 10f
            c.save(); c.rotate(wob, x, y + r)
            p.color = 0xFFFFF3D6.toInt(); rect.set(x - r * 0.8f, y - r * 1.1f, x + r * 0.8f, y + r); c.drawOval(rect, p)
            p.color = 0xFFF7B6D2.toInt()
            for (i in 0..4) c.drawCircle(x + (i - 2) * r * 0.3f, y - r * 0.2f + (i % 2) * r * 0.35f, r * 0.08f, p)
            if (age > 1.0f) {
                line.color = 0xFF8A6A50.toInt(); line.strokeWidth = r * 0.05f
                path.reset(); var xx = x - r * 0.75f; var k = 0; path.moveTo(xx, y - r * 0.05f)
                while (xx < x + r * 0.75f) { xx += r * 0.2f; k++; path.lineTo(xx, y - r * 0.05f + (if (k % 2 == 0) -1 else 1) * r * 0.12f) }
                c.drawPath(path, line)
            }
            c.restore()
        } else {
            val pop = min(1f, (age - 2.4f) / 0.4f)
            val bounce = if (pop < 1f) 0f else abs(sin((age - 2.8f) * 5f)) * r * 0.1f
            emoji.textSize = r * 1.9f * (0.3f + 0.7f * pop)
            c.drawText(hatchAnimal, x, y + r * 0.45f - bounce, emoji)
            p.color = 0xFFFFF3D6.toInt(); rect.set(x - r * 0.85f, y + r * 0.35f, x + r * 0.85f, y + r * 1.15f); c.drawArc(rect, 0f, 180f, true, p)
            for (i in 0..9) {
                val a = Math.toRadians((i * 36f + age * 40f).toDouble()).toFloat()
                val d = r * (1.35f + 0.15f * sin(age * 6f + i))
                p.color = colours[i % colours.size]; drawStar(c, x + d * cos(a), y + d * sin(a), r * 0.12f)
            }
        }
    }
}
