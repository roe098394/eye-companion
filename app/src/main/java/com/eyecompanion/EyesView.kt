package com.eyecompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Two big animated eyes that blink, glance around and react to what the companion is doing. */
class EyesView(context: Context) : View(context) {

    enum class Mood { LOADING, SLEEP, IDLE, LISTEN, THINK, TALK, HAPPY }

    /** How Pip feels about what it's saying; shown on top of the mood for a while. */
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, SURPRISED, SLEEPY, LOVE, CONFUSED, THINKING }

    @Volatile var emotion = Emotion.NEUTRAL
        private set
    @Volatile private var emotionUntil = 0L
    private var lidAngle = 0f      // + = angry brows, - = sad brows (smoothed)
    private var tint = 0f          // 0..1 how far the colour has moved to the emotion colour

    /** Show an emotion for [ms] milliseconds (it stays while Pip is talking). */
    fun feel(e: Emotion, ms: Long = 6000) { emotion = e; emotionUntil = now() + ms }

    // a fist that punches up on screen for "fist my bump!"
    @Volatile private var fistStart = 0L
    @Volatile private var fistUntil = 0L
    fun fistBump(ms: Long = 2200) { fistStart = now(); fistUntil = fistStart + ms }
    private val fistPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fistLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    var mood = Mood.LOADING
        set(value) {
            if (field != value) moodSince = now()
            field = value
        }

    /** 0..1 loudness from the mic or voice, used to make the eyes "breathe". */
    @Volatile var level = 0f

    /** Captions fade away 10 seconds after they last changed (not during setup). */
    var caption = ""
        set(v) { if (v != field) captionAt = now(); field = v }
    var subCaption = ""
        set(v) { if (v != field) subCaptionAt = now(); field = v }
    @Volatile private var captionAt = 0L
    @Volatile private var subCaptionAt = 0L
    private fun fade(since: Long, t: Long): Float =
        if (mood == Mood.LOADING) 1f else (1f - ((t - since) - 10_000f) / 1000f).coerceIn(0f, 1f)
    var showCaptions = true
    var eyeColor = Color.parseColor("#5CE1FF")

    /** Car mode: low, wide amber visor eyes with a voice equaliser underneath. */
    var carMode = false
        set(v) { field = v; eyeColor = Color.parseColor(if (v) "#FFB23E" else "#5CE1FF") }

    // smoothed animation state
    private var lookX = 0f; private var lookY = 0f
    private var open = 0.1f; private var scale = 1f; private var happy = 0f
    private var tLookX = 0f; private var tLookY = 0f
    private var nextGlance = 0L
    private var nextBlink = 0L
    private var blinkStart = -1L
    private var moodSince = 0L
    private var lastFrame = 0L

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 200, 220, 230); textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 200, 220, 230); textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    init { setBackgroundColor(Color.BLACK) }

    private fun now() = System.currentTimeMillis()

    override fun onDraw(canvas: Canvas) {
        val t = now()
        val dt = if (lastFrame == 0L) 0.016f else min(0.1f, (t - lastFrame) / 1000f)
        lastFrame = t
        val secs = t / 1000f

        // --- targets for the current mood ---
        var tOpen = 1f; var tScale = 1f; var tHappy = 0.12f
        var bounce = 0f
        when (mood) {
            Mood.LOADING -> { tOpen = 0.35f; tLookX = 0f; tLookY = 0.5f; tHappy = 0f }
            Mood.SLEEP -> { tOpen = 0.06f + 0.03f * sin(secs * 1.2f); tLookX = 0f; tLookY = 0.3f; tHappy = 0f }
            Mood.IDLE -> { glanceAround(t); tScale = 1f + level * 0.10f }
            Mood.LISTEN -> { tScale = 1.06f + level * 0.12f; tLookX *= 0.9f; tLookY = -0.05f; tHappy = 0.05f }
            Mood.THINK -> {
                tOpen = 0.78f; tHappy = 0f
                tLookX = 0.55f + 0.1f * sin(secs * 0.7f); tLookY = -0.55f
            }
            Mood.TALK -> {
                tHappy = 0.35f; tLookX *= 0.9f; tLookY = 0f
                bounce = sin(secs * 13f) * (0.012f + level * 0.03f)
            }
            Mood.HAPPY -> { tHappy = 0.85f; tScale = 1.08f; tLookX = 0f; tLookY = -0.1f
                if (t - moodSince > 1500) mood = Mood.IDLE }
        }

        // --- emotion on top of the mood ---
        val feeling = if (t < emotionUntil || (mood == Mood.TALK && emotion != Emotion.NEUTRAL)) emotion else Emotion.NEUTRAL
        var tLid = 0f; var tTint = 0f; var oneEye = 1f
        if (mood != Mood.SLEEP && mood != Mood.LOADING) when (feeling) {
            Emotion.HAPPY -> { tHappy = 0.8f; tScale *= 1.05f }
            Emotion.SAD -> { tLid = -1f; tOpen = min(tOpen, 0.75f); tLookY = 0.35f; tTint = 1f; tHappy = 0f }
            Emotion.ANGRY -> { tLid = 1f; tOpen = min(tOpen, 0.8f); tTint = 1f; tHappy = 0f }
            Emotion.SURPRISED -> { tScale *= 1.18f; tOpen = 1.1f; tHappy = 0f; tLookX = 0f; tLookY = -0.1f }
            Emotion.SLEEPY -> { tOpen = 0.32f + 0.05f * sin(secs * 1.5f); tHappy = 0f; tLookY = 0.25f }
            Emotion.LOVE -> { tHappy = 0.7f; tScale *= 1f + 0.05f * sin(secs * 6f); tTint = 1f }
            Emotion.CONFUSED -> { oneEye = 0.78f; tLookX = -0.3f; tLookY = -0.2f; tHappy = 0f }
            Emotion.THINKING -> { tOpen = 0.8f; tLookX = 0.55f; tLookY = -0.5f; tHappy = 0f }
            Emotion.NEUTRAL -> {}
        }

        // --- blinking (not while asleep) ---
        if (nextBlink == 0L) nextBlink = t + 2000
        var blink = 1f
        if (mood != Mood.SLEEP && mood != Mood.LOADING) {
            if (blinkStart < 0 && t >= nextBlink) blinkStart = t
            if (blinkStart >= 0) {
                val p = (t - blinkStart) / 150f
                if (p >= 1f) {
                    blinkStart = -1
                    nextBlink = t + if (Random.nextFloat() < 0.2f) 180L else Random.nextLong(2500, 6500)
                } else blink = abs(1f - 2f * p)
            }
        }

        // --- smooth everything ---
        val k = min(1f, dt * 9f)
        lookX += (tLookX - lookX) * k; lookY += (tLookY - lookY) * k
        open += (tOpen - open) * min(1f, dt * 6f)
        scale += (tScale - scale) * k
        happy += (tHappy - happy) * min(1f, dt * 5f)
        lidAngle += (tLid - lidAngle) * min(1f, dt * 7f)
        tint += (tTint - tint) * min(1f, dt * 4f)

        // --- draw ---
        val w = width.toFloat(); val h = height.toFloat()
        val eyeW = min(w * 0.17f, h * 0.30f) * scale * (if (carMode) 1.25f else 1f)
        val eyeH = eyeW * (if (carMode) 0.42f else 1.55f)
        val gap = eyeW * (if (carMode) 0.55f else 0.85f)
        val cx = w / 2 + lookX * w * 0.07f
        val cy = (if (carMode) h * 0.38f else h * 0.46f) + lookY * h * (if (carMode) 0.03f else 0.08f) + bounce * h
        val openNow = max(0.04f, open * blink)

        val emoColor = when (feeling) {
            Emotion.ANGRY -> Color.parseColor("#FF5A3C")
            Emotion.SAD -> Color.parseColor("#4A7BFF")
            Emotion.LOVE -> Color.parseColor("#FF6FB5")
            else -> eyeColor
        }
        val col = blend(eyeColor, emoColor, tint)
        eyePaint.color = col
        glowPaint.color = col; glowPaint.alpha = 40
        for (side in arrayOf(-1f, 1f)) {
            val ex = cx + side * (gap / 2 + eyeW / 2)
            // eyes look a touch "closer" on the side they're glancing toward
            val sideScale = (1f + side * lookX * 0.06f) * (if (side < 0) oneEye else 1f)
            val ew = eyeW * sideScale
            val eh = eyeH * sideScale * openNow
            val r = min(ew, eh) * 0.32f
            rect.set(ex - ew / 2 - ew * 0.12f, cy - eh / 2 - ew * 0.12f, ex + ew / 2 + ew * 0.12f, cy + eh / 2 + ew * 0.12f)
            canvas.drawRoundRect(rect, r * 1.4f, r * 1.4f, glowPaint)
            rect.set(ex - ew / 2, cy - eh / 2, ex + ew / 2, cy + eh / 2)
            canvas.drawRoundRect(rect, r, r, eyePaint)
            // angry / sad brows: a dark wedge cutting the top corner of each eye
            if (abs(lidAngle) > 0.03f) {
                val top = cy - eh / 2 - ew * 0.15f
                val drop = eh * 0.42f * abs(lidAngle)
                val inner = ex - side * ew / 2           // corner nearest the nose
                val outer = ex + side * ew / 2
                val innerY = if (lidAngle > 0) top + drop else top
                val outerY = if (lidAngle > 0) top else top + drop
                val brow = android.graphics.Path().apply {
                    moveTo(inner - side * ew * 0.2f, top - ew)
                    lineTo(inner - side * ew * 0.2f, innerY)
                    lineTo(outer + side * ew * 0.2f, outerY)
                    lineTo(outer + side * ew * 0.2f, top - ew)
                    close()
                }
                canvas.drawPath(brow, lidPaint)
            }
            // happy "cheeks": a dark oval pushing up from below
            if (!carMode && happy > 0.02f && openNow > 0.3f) {
                val lift = eh * 0.55f * happy
                rect.set(ex - ew * 0.9f, cy + eh / 2 - lift, ex + ew * 0.9f, cy + eh / 2 - lift + eh * 1.1f)
                canvas.drawOval(rect, lidPaint)
            }
        }

        // car mode: a mirrored voice equaliser that dances when listening or talking
        if (carMode && mood != Mood.SLEEP && mood != Mood.LOADING) {
            val bars = 17
            val bw = w * 0.022f
            val gapB = bw * 0.7f
            val total = bars * bw + (bars - 1) * gapB
            val baseY = h * 0.68f
            val active = mood == Mood.TALK || mood == Mood.LISTEN
            for (i in 0 until bars) {
                val fromCentre = abs(i - bars / 2).toFloat() / (bars / 2)
                val wave = 0.5f + 0.5f * sin(secs * 9f + i * 1.3f) * sin(secs * 3.7f + i * 0.6f)
                val amp = if (active) (0.15f + level * 1.6f).coerceAtMost(1f) * (1f - fromCentre * 0.7f) * (0.35f + 0.65f * wave)
                          else if (mood == Mood.THINK) 0.08f + 0.06f * max(0f, sin(secs * 5f - i * 0.5f))
                          else 0.04f
                val bh = max(bw, h * 0.22f * amp)
                val x = w / 2 - total / 2 + i * (bw + gapB)
                eyePaint.alpha = (90 + 165 * (1f - fromCentre * 0.6f)).toInt()
                rect.set(x, baseY - bh / 2, x + bw, baseY + bh / 2)
                canvas.drawRoundRect(rect, bw / 2, bw / 2, eyePaint)
            }
            eyePaint.alpha = 255
        }

        // fist bump
        if (t < fistUntil) drawFist(canvas, w, h, (t - fistStart) / 1000f, (fistUntil - t) / 1000f, col)

        // thinking dots
        if (mood == Mood.THINK && !carMode) {
            for (i in 0..2) {
                val a = 0.3f + 0.7f * max(0f, sin(secs * 5f - i * 0.8f))
                eyePaint.alpha = (255 * a).toInt()
                canvas.drawCircle(w / 2 + (i - 1) * eyeW * 0.35f, h * 0.83f, eyeW * 0.07f, eyePaint)
            }
            eyePaint.alpha = 255
        }

        // captions
        if (showCaptions || mood == Mood.LOADING) {
            textPaint.textSize = h * 0.035f
            subPaint.textSize = h * 0.028f
            val a1 = fade(captionAt, t); val a2 = fade(subCaptionAt, t)
            textPaint.alpha = (170 * a1).toInt(); subPaint.alpha = (110 * a2).toInt()
            if (a1 > 0f) drawWrapped(canvas, caption, w / 2, h * 0.90f, w * 0.85f, textPaint)
            if (a2 > 0f && subCaption.isNotEmpty()) canvas.drawText(subCaption.take(90), w / 2, h * 0.08f, subPaint)
        }

        postInvalidateOnAnimation()
    }

    private fun drawFist(c: Canvas, w: Float, h: Float, age: Float, left: Float, color: Int) {
        // punch in fast, overshoot, settle; fade out at the end
        val punch = when {
            age < 0.15f -> 0.4f + age / 0.15f * 0.8f
            age < 0.35f -> 1.2f - (age - 0.15f) / 0.2f * 0.2f
            else -> 1f
        }
        val alpha = (min(1f, left / 0.3f) * 255).toInt()
        val s = min(w, h) * 0.2f * punch
        val cx = w / 2; val cy = h * 0.75f
        fistPaint.color = color; fistPaint.alpha = alpha
        fistLine.alpha = alpha; fistLine.strokeWidth = s * 0.035f
        // palm / back of the hand
        rect.set(cx - s * 0.5f, cy - s * 0.15f, cx + s * 0.5f, cy + s * 0.5f)
        c.drawRoundRect(rect, s * 0.18f, s * 0.18f, fistPaint)
        // four curled fingers along the top
        val fw = s * 0.25f
        for (i in 0..3) {
            val x = cx - s * 0.5f + i * fw
            rect.set(x, cy - s * 0.45f, x + fw, cy + s * 0.05f)
            c.drawRoundRect(rect, fw * 0.45f, fw * 0.45f, fistPaint)
            c.drawRoundRect(rect, fw * 0.45f, fw * 0.45f, fistLine)
        }
        // thumb across the front
        rect.set(cx - s * 0.52f, cy + s * 0.02f, cx + s * 0.18f, cy + s * 0.26f)
        c.drawRoundRect(rect, s * 0.12f, s * 0.12f, fistPaint)
        c.drawRoundRect(rect, s * 0.12f, s * 0.12f, fistLine)
        // impact lines
        if (age < 0.6f) {
            fistPaint.strokeWidth = s * 0.05f; fistPaint.style = Paint.Style.STROKE; fistPaint.strokeCap = Paint.Cap.ROUND
            val r1 = s * 0.75f; val r2 = s * (0.95f + age * 0.4f)
            for (a in listOf(-150f, -120f, -90f, -60f, -30f)) {
                val rad = Math.toRadians(a.toDouble())
                val dx = Math.cos(rad).toFloat(); val dy = Math.sin(rad).toFloat()
                c.drawLine(cx + dx * r1, cy - s * 0.1f + dy * r1, cx + dx * r2, cy - s * 0.1f + dy * r2, fistPaint)
            }
            fistPaint.style = Paint.Style.FILL
        }
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val g = f.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * g).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * g).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * g).toInt())
    }

    private fun glanceAround(t: Long) {
        if (t >= nextGlance) {
            if (Random.nextFloat() < 0.45f) { tLookX = 0f; tLookY = 0f }
            else { tLookX = Random.nextFloat() * 1.6f - 0.8f; tLookY = Random.nextFloat() * 1.0f - 0.5f }
            nextGlance = t + Random.nextLong(1200, 4200)
        }
    }

    private fun drawWrapped(c: Canvas, text: String, x: Float, y: Float, maxW: Float, p: Paint) {
        if (text.isBlank()) return
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(test) > maxW && line.isNotEmpty()) { lines.add(line); line = word } else line = test
        }
        if (line.isNotEmpty()) lines.add(line)
        val shown = lines.takeLast(2)
        val lh = p.textSize * 1.3f
        shown.forEachIndexed { i, s -> c.drawText(s, x, y - (shown.size - 1 - i) * lh, p) }
    }
}
