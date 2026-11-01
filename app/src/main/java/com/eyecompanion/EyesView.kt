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

    var mood = Mood.LOADING
        set(value) {
            if (field != value) moodSince = now()
            field = value
        }

    /** 0..1 loudness from the mic or voice, used to make the eyes "breathe". */
    @Volatile var level = 0f

    var caption = ""
    var subCaption = ""
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

        // --- draw ---
        val w = width.toFloat(); val h = height.toFloat()
        val eyeW = min(w * 0.17f, h * 0.30f) * scale * (if (carMode) 1.25f else 1f)
        val eyeH = eyeW * (if (carMode) 0.42f else 1.55f)
        val gap = eyeW * (if (carMode) 0.55f else 0.85f)
        val cx = w / 2 + lookX * w * 0.07f
        val cy = (if (carMode) h * 0.38f else h * 0.46f) + lookY * h * (if (carMode) 0.03f else 0.08f) + bounce * h
        val openNow = max(0.04f, open * blink)

        eyePaint.color = eyeColor
        glowPaint.color = eyeColor; glowPaint.alpha = 40
        for (side in arrayOf(-1f, 1f)) {
            val ex = cx + side * (gap / 2 + eyeW / 2)
            // eyes look a touch "closer" on the side they're glancing toward
            val sideScale = 1f + side * lookX * 0.06f
            val ew = eyeW * sideScale
            val eh = eyeH * sideScale * openNow
            val r = min(ew, eh) * 0.32f
            rect.set(ex - ew / 2 - ew * 0.12f, cy - eh / 2 - ew * 0.12f, ex + ew / 2 + ew * 0.12f, cy + eh / 2 + ew * 0.12f)
            canvas.drawRoundRect(rect, r * 1.4f, r * 1.4f, glowPaint)
            rect.set(ex - ew / 2, cy - eh / 2, ex + ew / 2, cy + eh / 2)
            canvas.drawRoundRect(rect, r, r, eyePaint)
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
            drawWrapped(canvas, caption, w / 2, h * 0.90f, w * 0.85f, textPaint)
            if (subCaption.isNotEmpty()) canvas.drawText(subCaption.take(90), w / 2, h * 0.08f, subPaint)
        }

        postInvalidateOnAnimation()
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
