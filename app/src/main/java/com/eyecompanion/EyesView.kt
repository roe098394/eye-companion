package com.eyecompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Two big animated eyes that blink, glance around and react to what the companion is doing. */
class EyesView(context: Context) : View(context) {

    enum class Mood { LOADING, SLEEP, IDLE, LISTEN, THINK, TALK, HAPPY }

    /** How Pip feels about what it's saying; shown on top of the mood for a while. */
    companion object {
        const val KS_STEAM = 1; const val KS_RACE = 2; const val KS_HANDS = 3; const val KS_SHIFTY = 4
        const val KS_HEARTBREAK = 5; const val KS_TALLY = 6; const val KS_BUBBLES = 7
    }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, SURPRISED, SLEEPY, LOVE, CONFUSED, THINKING, DIZZY,
        SCARED, EXCITED, SUSPICIOUS, BORED, EMBARRASSED, WINK, CURIOUS, LAUGHING, CRYING, STONED, SNIGGER, HOT }

    /** Chloe mode: cute cat ears, nose and whiskers around the eyes. */
    @Volatile var catFace = false

    /** An egg that wobbles, cracks and hatches a baby animal (emoji) as a reward. */
    @Volatile private var hatchStart = 0L
    @Volatile private var hatchAnimal = ""
    fun hatch(animal: String) { hatchAnimal = animal; hatchStart = now() }
    val hatching get() = now() - hatchStart < 6000

    /** Big text shown in the middle (a sum or a picture word for the games), "" to hide. */
    @Volatile var puzzle = ""
    private val puzzlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val eggPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eggLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#8A6A3A") }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** Sulking with a cup of coffee (unplugged for the kettle): a mug Pip sips from. */
    @Volatile var coffee = false

    /** Where a face was last seen (-1..1 from the screen's point of view), and when. */
    @Volatile var faceX = 0f
    @Volatile var faceY = 0f
    @Volatile var faceAt = 0L
    /** how big the face is in the picture (near people make the eyes widen a little) */
    @Volatile var faceSize = 0f
    fun seeFace(x: Float, y: Float, size: Float = 0.2f) { faceX = x; faceY = y; faceSize = size; faceAt = now() }
    /** something moved (someone walking past, no face visible): glance at it */
    @Volatile private var motionX = 0f
    @Volatile private var motionY = 0f
    @Volatile private var motionAt = 0L
    fun seeMotion(x: Float, y: Float) { motionX = x; motionY = y; motionAt = now() }
    /** How far the tablet leans (-1..1, + = right side lower): eyes slide to the low side. */
    @Volatile var tilt = 0f

    @Volatile var emotion = Emotion.NEUTRAL
        private set
    @Volatile private var emotionUntil = 0L
    private var lidAngle = 0f      // + = angry brows, - = sad brows (smoothed)
    private var tint = 0f          // 0..1 how far the colour has moved to the emotion colour

    /** The feeling Pip falls back to when nothing else is going on (e.g. angry while unplugged). */
    @Volatile var baseEmotion = Emotion.NEUTRAL

    /** Show an emotion for [ms] milliseconds (it stays while Pip is talking). */
    fun feel(e: Emotion, ms: Long = 6000) { emotion = e; emotionUntil = now() + ms }

    // a fist that punches up on screen for "fist my bump!"
    @Volatile private var fistStart = 0L
    @Volatile private var fistUntil = 0L
    fun fistBump(ms: Long = 2200) { fistStart = now(); fistUntil = fistStart + ms }

    // hands that fly up to cover the eyes when the screen is poked
    @Volatile private var coverStart = 0L
    @Volatile private var coverUntil = 0L
    fun coverEyes(ms: Long = 1700) { coverStart = now(); coverUntil = coverStart + ms }
    val coveringEyes get() = now() < coverUntil
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
    /** Live readout while testing the kettle/dryer sound ("" = hidden). */
    @Volatile var testLine = ""
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
        // seconds within the hour: the full clock time is too big for a Float and froze every animation
        val secs = (t % 3_600_000L) / 1000f

        // --- targets for the current mood ---
        var tOpen = 1f; var tScale = 1f; var tHappy = 0.12f
        var bounce = 0f
        when (mood) {
            Mood.LOADING -> { tOpen = 0.35f; tLookX = 0f; tLookY = 0.5f; tHappy = 0f }
            Mood.SLEEP -> { tOpen = 0.06f + 0.03f * sin(secs * 1.2f); tLookX = 0f; tLookY = 0.3f; tHappy = 0f }
            Mood.IDLE -> {
                if (seesMotion) followMotion() else glanceAround(t)
                tScale = 1f + level * 0.10f
                idleFeelings(t)
            }
            Mood.LISTEN -> { tScale = 1.06f + level * 0.12f; tLookX *= 0.9f; tLookY = -0.05f; tHappy = 0.05f
                if (seesMotion) followMotion() }
            Mood.THINK -> {
                tOpen = 0.78f; tHappy = 0f
                tLookX = 0.55f + 0.1f * sin(secs * 0.7f); tLookY = -0.55f
            }
            Mood.TALK -> {
                tHappy = 0.35f; tLookX *= 0.9f; tLookY = 0f
                if (seesMotion) followMotion()
                bounce = sin(secs * 13f) * (0.012f + level * 0.03f)
            }
            Mood.HAPPY -> { tHappy = 0.85f; tScale = 1.08f; tLookX = 0f; tLookY = -0.1f
                if (t - moodSince > 1500) mood = Mood.IDLE }
        }

        // --- emotion on top of the mood ---
        val feeling = if (t < emotionUntil || (mood == Mood.TALK && emotion != Emotion.NEUTRAL)) emotion else baseEmotion
        var tLid = 0f; var tTint = 0f; var oneEye = 1f
        var tOpenL = 1f; var tOpenR = 1f; var tRot = 0f; var tBlush = 0f; var shake = 0f
        var emoCol = 0
        if (mood != Mood.SLEEP && mood != Mood.LOADING) when (feeling) {
            Emotion.HAPPY -> { tHappy = 0.8f; tScale *= 1.05f }
            Emotion.SAD -> { tLid = -1f; tOpen = min(tOpen, 0.75f); tLookY = 0.35f; tTint = 1f; tHappy = 0f }
            Emotion.ANGRY -> { tLid = 1f; tOpen = min(tOpen, 0.8f); tTint = 1f; tHappy = 0f }
            Emotion.SURPRISED -> { tScale *= 1.18f; tOpen = 1.1f; tHappy = 0f; tLookX = 0f; tLookY = -0.1f }
            Emotion.SLEEPY -> { tOpen = 0.32f + 0.05f * sin(secs * 1.5f); tHappy = 0f; tLookY = 0.25f }
            Emotion.LOVE -> { tHappy = 0.7f; tScale *= 1f + 0.07f * sin(secs * 6f); tTint = 1f; tBlush = 0.6f }
            Emotion.CONFUSED -> { oneEye = 0.78f; tLookX = -0.3f; tLookY = -0.2f; tHappy = 0f; tRot = -8f }
            Emotion.THINKING -> { tOpen = 0.8f; tLookX = 0.55f; tLookY = -0.5f; tHappy = 0f }
            Emotion.DIZZY -> { tLookX = 0.75f * cos(secs * 9f); tLookY = 0.5f * sin(secs * 9f); oneEye = 0.85f; tHappy = 0f
                tRot = 12f * sin(secs * 4f) }
            Emotion.SCARED -> { tScale *= 0.78f; tOpen = 1.05f; tHappy = 0f; tLid = -0.6f; tLookY = 0.15f
                tLookX = -0.6f; shake = 0.012f; tTint = 1f; emoCol = Color.parseColor("#C9F4FF") }
            Emotion.EXCITED -> { tScale *= 1.14f; tHappy = 0.65f; bounce += sin(secs * 11f) * 0.018f; tLookX = 0f; tLookY = -0.15f }
            Emotion.SUSPICIOUS -> { tOpen = min(tOpen, 0.42f); tOpenL = 0.75f; tLid = 0.3f; tHappy = 0f
                tLookX = 0.85f * sin(secs * 0.8f); tLookY = 0f }
            Emotion.BORED -> { tOpen = min(tOpen, 0.45f); tLookX = -0.55f; tLookY = 0.4f; tHappy = 0f }
            Emotion.EMBARRASSED -> { tLookX = 0.7f; tLookY = 0.55f; tHappy = 0.45f; tBlush = 1f; tScale *= 0.95f }
            Emotion.WINK -> { tHappy = 0.6f; tOpenR = 0.06f; tLookX = 0f; tLookY = 0f }
            Emotion.CURIOUS -> { oneEye = 1.14f; tRot = 9f; tLookX = 0.3f; tLookY = -0.2f; tHappy = 0.1f; tScale *= 1.04f }
            Emotion.LAUGHING -> { tHappy = 1f; tOpen = min(tOpen, 0.85f); bounce += abs(sin(secs * 15f)) * 0.03f; tLookX = 0f; tLookY = -0.1f }
            Emotion.STONED -> { tOpen = min(tOpen, 0.36f) + 0.04f * sin(secs * 0.9f); tHappy = 0.35f; tTint = 0.6f
                emoCol = Color.parseColor("#FF8A8A"); tLookX = 0.6f * sin(secs * 0.5f); tLookY = 0.2f; tRot = 5f * sin(secs * 0.4f) }
            Emotion.SNIGGER -> { tHappy = 0.95f; tOpen = min(tOpen, 0.55f); tLookX = 1.1f; tLookY = 0.35f
                bounce += abs(sin(secs * 18f)) * 0.01f; tScale *= 0.92f }
            Emotion.CRYING -> { tLid = -1f; tOpen = min(tOpen, 0.6f); tLookY = 0.4f; tTint = 1f; tHappy = 0f
                emoCol = Color.parseColor("#4A7BFF") }
            Emotion.HOT -> { tOpen = min(tOpen, 0.55f) + 0.04f * sin(secs * 1.3f); tLid = -0.5f; tLookY = 0.3f; tHappy = 0f
                tTint = 1f; tBlush = 0.9f; emoCol = Color.parseColor("#FF7A2E"); tScale *= 1f + 0.02f * sin(secs * 2f)
                bounce += sin(secs * 2.2f) * 0.006f }                       // drooping, flushed, slowly wilting
            Emotion.NEUTRAL -> {}
        }
        if (seesFace && faceSize > 0.35f && feeling == Emotion.NEUTRAL) tScale *= 1f + min(0.12f, (faceSize - 0.35f) * 0.5f)
        if (t < stareUntil && mood != Mood.SLEEP && mood != Mood.LOADING) { tLookX = stareX; tLookY = stareY }
        // leaning: eyes roll towards the low side
        if (mood != Mood.SLEEP && mood != Mood.LOADING && abs(tilt) > 0.2f)
            tLookX = (tLookX + (tilt - 0.2f * Math.signum(tilt)) * 2.2f).coerceIn(-1.3f, 1.3f)

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
        openL += (tOpenL - openL) * min(1f, dt * 10f); openR += (tOpenR - openR) * min(1f, dt * 10f)
        rot += (tRot - rot) * min(1f, dt * 5f)
        blush += (tBlush - blush) * min(1f, dt * 3f)
        if (emoCol != 0) lastEmoCol = emoCol

        // --- draw ---
        val w = width.toFloat(); val h = height.toFloat()
        // during a game the eyes shrink and move up to make room for the sum and the answer buttons
        val gameShrink = if (puzzle.isNotEmpty()) 0.68f else 1f
        val eyeW = min(w * 0.17f, h * 0.30f) * scale * gameShrink * (if (carMode) 1.25f else 1f)
        val eyeH = eyeW * (if (carMode) 0.42f else 1.55f)
        val gap = eyeW * (if (carMode) 0.55f else 0.85f)
        val jitter = if (shake > 0f) (Random.nextFloat() - 0.5f) * shake * w else 0f
        val cx = w / 2 + lookX * w * (if (carMode) 0.07f else 0.11f) + jitter
        val cy = (if (carMode) h * 0.38f else if (puzzle.isNotEmpty()) h * 0.36f else h * 0.46f) +
            lookY * h * (if (carMode) 0.03f else 0.10f) * gameShrink + bounce * h
        val openNow = max(0.04f, open * blink)

        val emoColor = when (feeling) {
            Emotion.ANGRY -> Color.parseColor("#FF5A3C")
            Emotion.SAD -> Color.parseColor("#4A7BFF")
            Emotion.LOVE -> Color.parseColor("#FF6FB5")
            Emotion.SCARED, Emotion.CRYING, Emotion.STONED, Emotion.HOT -> lastEmoCol
            else -> eyeColor
        }
        val takeover = t < takeoverUntil
        val col = if (takeover) Color.parseColor("#FF2A1E") else blend(eyeColor, emoColor, tint)
        eyePaint.color = col
        glowPaint.color = col; glowPaint.alpha = 40
        canvas.save()
        if (abs(rot) > 0.3f) canvas.rotate(rot, cx, cy)
        for (side in arrayOf(-1f, 1f)) {
            val ex = cx + side * (gap / 2 + eyeW / 2)
            // eyes look a touch "closer" on the side they're glancing toward
            val sideScale = (1f + side * lookX * 0.06f) * (if (side < 0) oneEye else 1f)
            val ew = eyeW * sideScale
            val eh = eyeH * sideScale * max(0.04f, openNow * (if (side < 0) openL else openR))
            val r = min(ew, eh) * 0.32f
            if (feeling == Emotion.LOVE && tint > 0.5f && !carMode && openNow > 0.3f) {
                // heart eyes
                drawHeart(canvas, ex, cy, ew * 1.05f, glowPaint, 1.2f)
                drawHeart(canvas, ex, cy, ew * 1.05f, eyePaint, 1f)
            } else {
                rect.set(ex - ew / 2 - ew * 0.12f, cy - eh / 2 - ew * 0.12f, ex + ew / 2 + ew * 0.12f, cy + eh / 2 + ew * 0.12f)
                canvas.drawRoundRect(rect, r * 1.4f, r * 1.4f, glowPaint)
                rect.set(ex - ew / 2, cy - eh / 2, ex + ew / 2, cy + eh / 2)
                canvas.drawRoundRect(rect, r, r, eyePaint)
            }
            // blushing cheeks
            if (blush > 0.03f && !carMode) {
                blushPaint.alpha = (110 * blush).toInt()
                rect.set(ex + side * ew * 0.05f - ew * 0.45f, cy + eyeH * 0.42f, ex + side * ew * 0.05f + ew * 0.45f, cy + eyeH * 0.42f + ew * 0.32f)
                canvas.drawOval(rect, blushPaint)
            }
            // tears
            if (feeling == Emotion.CRYING && !carMode) {
                for (d in 0..1) {
                    val p = ((secs * 0.7f + d * 0.5f + (if (side < 0) 0.25f else 0f)) % 1f)
                    tearPaint.alpha = (220 * (1f - p)).toInt()
                    val tx = ex + side * ew * 0.3f
                    val ty = cy + eh / 2 + p * h * 0.28f
                    canvas.drawCircle(tx, ty, ew * 0.07f, tearPaint)
                    canvas.drawCircle(tx, ty - ew * 0.05f, ew * 0.045f, tearPaint)
                }
            }
            // ---- kettle shows (one picked at random each boil)
            if (kettleShow != 0 && !carMode && mood != Mood.SLEEP) {
                val kt = (t - kettleSince) / 1000f
                val boil = (kt / 150f).coerceIn(0f, 1f)                   // a kettle takes ~2.5 minutes
                when (kettleShow) {
                    KS_STEAM -> for (k in 0..4) {                           // steam puffing out of the sides, faster as it boils
                        val p = ((kt * (0.5f + boil * 1.2f) + k / 5f) % 1f)
                        kPaint.color = Color.WHITE; kPaint.alpha = (170 * (1f - p)).toInt()
                        canvas.drawCircle(ex + side * (ew / 2 + ew * (0.1f + p * 0.9f)), cy - eh * 0.2f - p * ew * 0.6f,
                            ew * (0.07f + p * 0.2f), kPaint)
                    }
                    KS_HANDS -> {                                           // hands over the ears
                        kPaint.color = Color.parseColor("#F2C9A0"); kPaint.alpha = 255
                        val hx = ex + side * (ew / 2 + ew * 0.05f); val hy = cy + sin(kt * 7f) * ew * 0.02f
                        r1.set(hx - ew * 0.22f, hy - ew * 0.38f, hx + ew * 0.22f, hy + ew * 0.38f)
                        canvas.drawRoundRect(r1, ew * 0.18f, ew * 0.18f, kPaint)
                        for (f in 0..3) {                                    // fingers pointing up
                            val fx = hx - ew * 0.15f + f * ew * 0.1f
                            r1.set(fx - ew * 0.045f, hy - ew * 0.62f + abs(f - 1.5f) * ew * 0.05f, fx + ew * 0.045f, hy - ew * 0.25f)
                            canvas.drawRoundRect(r1, ew * 0.045f, ew * 0.045f, kPaint)
                        }
                        r1.set(hx - side * ew * 0.42f - ew * 0.06f, hy - ew * 0.05f, hx - side * ew * 0.12f, hy + ew * 0.1f)   // thumb
                        canvas.drawRoundRect(r1, ew * 0.06f, ew * 0.06f, kPaint)
                    }
                    KS_HEARTBREAK -> {                                      // a crack zig-zagging down each heart
                        kLine.color = Color.parseColor("#14070A"); kLine.strokeWidth = ew * 0.06f
                        val crack = minOf(1f, kt / 2.5f)
                        val pth = android.graphics.Path(); pth.moveTo(ex, cy - eh * 0.45f)
                        for (j in 1..6) { if (j / 6f > crack) break
                            pth.lineTo(ex + (if (j % 2 == 0) -1 else 1) * ew * 0.1f, cy - eh * 0.45f + j * eh * 0.15f) }
                        canvas.drawPath(pth, kLine)
                    }
                    KS_BUBBLES -> {                                         // filling up and bubbling like a kettle
                        canvas.save(); r1.set(ex - ew / 2, cy - eh / 2, ex + ew / 2, cy + eh / 2); canvas.clipRect(r1)
                        val level = cy + eh / 2 - eh * (0.15f + 0.8f * boil)
                        kPaint.color = Color.parseColor("#1E5BFF"); kPaint.alpha = 150
                        canvas.drawRect(ex - ew / 2, level + sin(kt * 6f + side) * ew * 0.03f, ex + ew / 2, cy + eh / 2, kPaint)
                        kPaint.color = Color.WHITE
                        val n = 4 + (boil * 10).toInt()
                        for (k in 0 until n) {
                            val p = ((kt * (0.6f + boil * 1.5f) + k * 0.37f) % 1f)
                            val bx = ex - ew * 0.4f + ((k * 0.61f + side * 0.3f) % 1f).let { if (it < 0) it + 1 else it } * ew * 0.8f
                            val by = cy + eh / 2 - p * (cy + eh / 2 - level)
                            kPaint.alpha = (200 * (1f - p)).toInt()
                            canvas.drawCircle(bx, by, ew * (0.025f + 0.03f * ((k % 3) / 2f)), kPaint)
                        }
                        canvas.restore()
                    }
                }
            }
            // too hot: sweat drops running down from the outer top corner, and heat shimmer above
            if (feeling == Emotion.HOT && !carMode) {
                for (d in 0..1) {
                    val p = ((secs * 0.45f + d * 0.5f + (if (side < 0) 0.3f else 0f)) % 1f)
                    sweatPaint.alpha = (230 * (1f - p * p)).toInt()
                    val sx = ex + side * (ew / 2 + ew * 0.12f)
                    val sy = cy - eh / 2 + p * eh * 0.9f
                    val dr = ew * 0.075f
                    val drop = android.graphics.Path().apply {
                        moveTo(sx, sy - dr * 2.2f)
                        quadTo(sx + dr * 1.1f, sy - dr * 0.2f, sx, sy + dr)
                        quadTo(sx - dr * 1.1f, sy - dr * 0.2f, sx, sy - dr * 2.2f); close()
                    }
                    canvas.drawPath(drop, sweatPaint)
                }
                if (side > 0) {
                    mugLine.color = Color.parseColor("#FF9A4A"); mugLine.strokeWidth = ew * 0.03f
                    for (k in -1..1) {
                        val bx = cx + k * ew * 0.45f; val by = cy - eyeH * 0.62f
                        val pth = android.graphics.Path(); pth.moveTo(bx, by)
                        for (j in 1..6) pth.lineTo(bx + sin(secs * 6f + j + k) * ew * 0.05f, by - j * ew * 0.06f)
                        mugLine.alpha = (120 + 80 * sin(secs * 3f + k)).toInt().coerceIn(0, 255)
                        canvas.drawPath(pth, mugLine)
                    }
                }
            }
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

        // Chloe mode: cat ears above, a little pink nose and whiskers below
        if (catFace && !carMode && mood != Mood.LOADING) {
            val earH = eyeW * 0.75f
            for (side in arrayOf(-1f, 1f)) {
                val ex = cx + side * (gap / 2 + eyeW / 2)
                val top = cy - eyeH * 0.5f - eyeW * 0.2f
                val p = android.graphics.Path().apply {
                    moveTo(ex - eyeW * 0.45f, top); lineTo(ex + side * eyeW * 0.15f, top - earH); lineTo(ex + eyeW * 0.45f, top); close()
                }
                eyePaint.alpha = 255; canvas.drawPath(p, eyePaint)
                val inner = android.graphics.Path().apply {
                    moveTo(ex - eyeW * 0.25f, top - earH * 0.08f); lineTo(ex + side * eyeW * 0.13f, top - earH * 0.72f); lineTo(ex + eyeW * 0.25f, top - earH * 0.08f); close()
                }
                blushPaint.alpha = 200; canvas.drawPath(inner, blushPaint)
                mugLine.color = col; mugLine.alpha = 200; mugLine.strokeWidth = eyeW * 0.035f
                val wy = cy + eyeH * 0.55f
                for (k in -1..1) canvas.drawLine(cx + side * eyeW * 0.35f, wy + k * eyeW * 0.08f,
                    cx + side * eyeW * 1.35f, wy + k * eyeW * 0.2f, mugLine)
            }
            val ny = cy + eyeH * 0.45f
            val nose = android.graphics.Path().apply {
                moveTo(cx - eyeW * 0.14f, ny); lineTo(cx + eyeW * 0.14f, ny); lineTo(cx, ny + eyeW * 0.14f); close()
            }
            blushPaint.alpha = 255; canvas.drawPath(nose, blushPaint)
        }

        // mute button on: a zip across where a mouth would be (Pip hates it). Pulling the tab to the left opens it.
        if (muted && !carMode && mood != Mood.LOADING && mood != Mood.SLEEP) {
            val half = (gap / 2 + eyeW) * 0.62f
            val my = cy + eyeH * 0.62f + (if (feeling == Emotion.ANGRY && zipOpen == 0f) sin(secs * 30f) * eyeW * 0.012f else 0f)
            val left = cx - half; val right = cx + half
            val sx = right - zipOpen * 2 * half                      // where the slider is
            zipLeft = left; zipRight = right; zipY = my; zipTabX = sx
            val zipCol = Color.parseColor("#C8CCD2")
            mugLine.color = zipCol; mugLine.alpha = 255
            // closed part: a line with interlocking teeth
            mugLine.strokeWidth = eyeW * 0.05f
            if (sx > left) canvas.drawLine(left, my, sx, my, mugLine)
            mugLine.strokeWidth = eyeW * 0.03f
            val n = 18
            for (i in 0..n) {
                val x = left + (2 * half) * i / n
                if (x > sx) break
                val up = if (i % 2 == 0) -1f else 1f
                canvas.drawLine(x, my, x, my + up * eyeW * 0.07f, mugLine)
            }
            // opened part: the two sides part like lips
            if (zipOpen > 0f) {
                val open = eyeW * 0.16f * zipOpen
                val pth = android.graphics.Path()
                pth.moveTo(sx, my); pth.quadTo((sx + right) / 2, my - open, right, my)
                pth.quadTo((sx + right) / 2, my + open, sx, my); pth.close()
                blushPaint.alpha = 255; val bc = blushPaint.color; blushPaint.color = Color.parseColor("#14070A")
                canvas.drawPath(pth, blushPaint); blushPaint.color = bc
                mugLine.strokeWidth = eyeW * 0.035f; canvas.drawPath(pth, mugLine)
            }
            // the zip pull on the slider
            mugLine.strokeWidth = eyeW * 0.035f
            r0.set(sx - eyeW * 0.05f, my - eyeW * 0.04f, sx + eyeW * 0.07f, my + eyeW * 0.06f)
            canvas.drawRoundRect(r0, eyeW * 0.02f, eyeW * 0.02f, mugLine)
            r0.set(sx - eyeW * 0.03f, my + eyeW * 0.06f, sx + eyeW * 0.05f, my + eyeW * 0.26f)
            canvas.drawRoundRect(r0, eyeW * 0.04f, eyeW * 0.04f, mugLine)
            mugLine.color = col
        } else { zipLeft = 0f; zipRight = 0f }

        // round specs while one of the online brains is resting
        if (glasses && !carMode && mood != Mood.LOADING) {
            specLine.color = Color.parseColor("#E8E2D0"); specLine.strokeWidth = eyeW * 0.07f; specLine.alpha = 220
            specFill.color = Color.WHITE; specFill.alpha = 28
            val rr = eyeW * 0.78f
            for (side in arrayOf(-1f, 1f)) {
                val ex = cx + side * (gap / 2 + eyeW / 2)
                canvas.drawCircle(ex, cy, rr, specFill); canvas.drawCircle(ex, cy, rr, specLine)
                // little glint on the lens
                specLine.alpha = 120; canvas.drawArc(ex - rr * 0.7f, cy - rr * 0.7f, ex + rr * 0.7f, cy + rr * 0.7f, 200f, 40f, false, specLine); specLine.alpha = 220
                canvas.drawLine(ex + side * rr, cy - rr * 0.15f, ex + side * rr * 1.45f, cy - rr * 0.35f, specLine)   // arms
            }
            val bx = gap / 2 + eyeW / 2 - rr
            canvas.drawArc(cx - bx, cy - rr * 0.45f, cx + bx, cy + rr * 0.05f, 200f, 140f, false, specLine)       // bridge
        }

        canvas.restore()

        // a game question shown big under the eyes
        if (puzzle.isNotEmpty() && !hatching) {
            puzzlePaint.textSize = h * 0.11f; puzzlePaint.color = Color.WHITE
            canvas.drawText(puzzle, w / 2, h * 0.72f, puzzlePaint)
        }
        // reward: an egg wobbles, cracks open and a baby animal pops out
        if (hatching) drawHatch(canvas, w, h, (t - hatchStart) / 1000f)

        // poked: hands over the eyes
        if (t < coverUntil) {
            val age = (t - coverStart) / 1000f; val left = (coverUntil - t) / 1000f
            val up = min(1f, min(age / 0.18f, left / 0.25f))          // fly up fast, drop at the end
            for (side in arrayOf(-1f, 1f)) {
                val ex = cx + side * (gap / 2 + eyeW / 2)
                drawHand(canvas, ex, cy + (1f - up) * h * 0.7f, eyeW * 1.35f, side, col)
            }
        }

        // excited: little sparkles twinkling around the eyes
        if (feeling == Emotion.EXCITED && !carMode) {
            for (i in 0..5) {
                val a = max(0f, sin(secs * 6f + i * 1.7f))
                if (a < 0.05f) continue
                val sx = cx + (if (i % 2 == 0) -1 else 1) * (gap / 2 + eyeW * (1.25f + 0.15f * (i % 3)))
                val sy = cy + (i - 2.5f) * eyeH * 0.22f
                drawSparkle(canvas, sx, sy, eyeW * 0.13f * a, col)
            }
        }

        if (coffee && !carMode && mood != Mood.SLEEP && mood != Mood.LOADING) drawCoffee(canvas, w, h, secs, col)

        // seasonal hat on the left eye
        if (!carMode && mood != Mood.LOADING && (season == "xmas" || season == "party"))
            drawHat(canvas, cx - gap / 2 - eyeW / 2, cy - eyeH * 0.5f * max(0.35f, openNow) - eyeH * 0.05f, eyeW, secs)

        // kitchen timer: small alarm clock top-right
        if (timerText.isNotEmpty() && mood != Mood.LOADING) drawAlarmClock(canvas, w * 0.9f, h * 0.17f, h * 0.075f, timerText, secs)

        // the week's cuppas, shown for a while on the Sunday report
        if (t < chartUntil && mood != Mood.SLEEP && mood != Mood.LOADING) drawWeek(canvas, w * 0.32f, h * 0.05f, w * 0.36f, h * 0.17f)

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

        if (takeover) drawTakeover(canvas, w, h, secs)

        // captions
        if (showCaptions || mood == Mood.LOADING) {
            textPaint.textSize = h * 0.035f
            subPaint.textSize = h * 0.028f
            val a1 = fade(captionAt, t); val a2 = fade(subCaptionAt, t)
            textPaint.alpha = (170 * a1).toInt(); subPaint.alpha = (110 * a2).toInt()
            if (a1 > 0f) drawWrapped(canvas, caption, w / 2, h * 0.90f, w * 0.85f, textPaint)
            if (a2 > 0f && subCaption.isNotEmpty()) canvas.drawText(subCaption.take(90), w / 2, h * 0.08f, subPaint)
        }
        // kettle shows drawn over the whole screen: the race bar and the cuppa tally
        if (kettleShow == KS_RACE && !carMode) {
            val kt = (t - kettleSince) / 1000f; val boil = (kt / 150f).coerceIn(0f, 1f)
            hudPaint.color = Color.parseColor("#FF5A3C"); hudPaint.textSize = h * 0.045f; hudPaint.alpha = 230
            hudPaint.textAlign = Paint.Align.CENTER
            c2(canvas, "KETTLE  vs  PIP", w / 2, h * 0.12f)
            kLine.color = Color.parseColor("#FF5A3C"); kLine.strokeWidth = h * 0.006f; kLine.alpha = 230
            r1.set(w * 0.3f, h * 0.15f, w * 0.7f, h * 0.19f); canvas.drawRoundRect(r1, h * 0.02f, h * 0.02f, kLine)
            kPaint.color = Color.parseColor("#FF5A3C"); kPaint.alpha = 200
            r1.set(w * 0.3f, h * 0.15f, w * (0.3f + 0.4f * boil), h * 0.19f); canvas.drawRoundRect(r1, h * 0.02f, h * 0.02f, kPaint)
            hudPaint.textSize = h * 0.03f
            c2(canvas, "BOILING… ${(boil * 100).toInt()}%", w / 2, h * 0.235f)
            hudPaint.textAlign = Paint.Align.LEFT
        }
        if (kettleShow == KS_TALLY && !carMode) {
            val kt = (t - kettleSince) / 1000f
            hudPaint.textAlign = Paint.Align.CENTER; hudPaint.color = eyeColor
            hudPaint.textSize = h * 0.07f; hudPaint.alpha = 230
            c2(canvas, "☕ $cuppas", w * 0.86f, h * 0.2f)
            val p = (kt % 3f) / 3f                                          // "+1" floating up and away, again and again
            hudPaint.textSize = h * (0.05f + 0.03f * p); hudPaint.alpha = (255 * (1f - p)).toInt()
            hudPaint.color = Color.parseColor("#7CFF8A")
            c2(canvas, "+1", w * 0.86f + sin(kt * 3f) * h * 0.01f, h * 0.12f - p * h * 0.1f)
            hudPaint.textAlign = Paint.Align.LEFT
        }

        // kettle / dryer test readout: low down in the middle, clear of the corner labels and the takeover HUD
        if (testLine.isNotEmpty()) {
            subPaint.textSize = h * 0.032f; subPaint.alpha = 220
            canvas.drawText(testLine.take(110), w / 2, h * 0.80f, subPaint)
        }

        // sleep screen: a big clock, today's cuppa count, when the brain's back, and floating zzz
        if (mood == Mood.SLEEP && sleepScreen) {
            val cal = java.util.Calendar.getInstance()
            val clock = String.format("%d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
            sleepPaint.color = eyeColor
            sleepPaint.textSize = h * 0.16f; sleepPaint.alpha = 150
            canvas.drawText(clock, w / 2, h * 0.24f, sleepPaint)
            sleepPaint.textSize = h * 0.045f; sleepPaint.alpha = 130
            canvas.drawText("☕  Cuppas today: $cuppas", w / 2, h * 0.70f, sleepPaint)
            if (plugNote.isNotEmpty()) {
                sleepPaint.textSize = h * 0.035f; sleepPaint.alpha = (110 + 90 * sin(secs * 2.5f)).toInt().coerceIn(20, 230)
                canvas.drawText(plugNote, w / 2, h * 0.78f, sleepPaint)
            }
            drawWeek(canvas, w * 0.34f, h * 0.81f, w * 0.32f, h * 0.13f)
            run {
                val label = "🎙️ VoiceID Live"
                chipPaint.textSize = h * 0.032f; chipPaint.color = eyeColor; chipPaint.alpha = 190
                val pad = h * 0.022f; val tw = chipPaint.measureText(label)
                trainRect.set(w * 0.975f - tw - pad * 2, h * 0.035f, w * 0.975f, h * 0.035f + chipPaint.textSize + pad * 1.6f)
                chipBox.color = eyeColor; chipBox.alpha = 120; chipBox.strokeWidth = h * 0.004f
                canvas.drawRoundRect(trainRect, trainRect.height() / 2, trainRect.height() / 2, chipBox)
                canvas.drawText(label, trainRect.left + pad, trainRect.centerY() + chipPaint.textSize * 0.35f, chipPaint)
            }
            if (shopping.isNotEmpty()) {
                listPaint.color = eyeColor; listPaint.textSize = h * 0.032f; listPaint.alpha = 150
                canvas.drawText("🛒 Shopping", w * 0.03f, h * 0.36f, listPaint)
                listPaint.textSize = h * 0.028f; listPaint.alpha = 115
                shopping.take(9).forEachIndexed { i, item ->
                    canvas.drawText("• " + (if (item.length > 16) item.take(15) + "…" else item), w * 0.035f, h * (0.41f + i * 0.045f), listPaint)
                }
                if (shopping.size > 9) canvas.drawText("+${shopping.size - 9} more", w * 0.035f, h * (0.41f + 9 * 0.045f), listPaint)
            }
            if (xmasNote.isNotEmpty()) { sleepPaint.textSize = h * 0.032f; sleepPaint.alpha = 140; canvas.drawText(xmasNote, w * 0.84f, h * 0.48f, sleepPaint) }
            if (napNote.isNotEmpty()) drawAlarmClock(canvas, w * 0.82f, h * 0.24f, h * 0.085f, napNote, secs)
            // zzz: little Z's drifting up and away from the sleeping eyes, wobbling, growing, fading out
            val startX = cx + gap / 2 + eyeW * 0.9f; val startY = cy - h * 0.04f
            val bold = sleepPaint.typeface
            sleepPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            for (i in 0..3) {
                val ph = ((secs * 0.22f + i / 4f) % 1f)                      // each Z takes ~4.5 s to float away
                val zx = startX + w * 0.16f * ph + sin(secs * 2.2f + i * 1.7f) * h * 0.015f
                val zy = startY - h * 0.30f * ph
                val fade = (if (ph < 0.15f) ph / 0.15f else 1f) * (1f - ph)
                sleepPaint.textSize = h * (0.04f + 0.06f * ph); sleepPaint.alpha = (235 * fade).toInt().coerceIn(0, 255)
                canvas.save(); canvas.rotate(-12f + 18f * ph, zx, zy)
                canvas.drawText(if (i % 2 == 0) "Z" else "z", zx, zy, sleepPaint)
                canvas.restore()
            }
            sleepPaint.typeface = bold
        }

        // daily allowance meter, top-left
        if (usageText.isNotEmpty() && mood != Mood.LOADING && t >= takeoverUntil) {      // hidden while the takeover HUD is up
            val c = when { usageLevel >= 90 -> Color.parseColor("#FF5A3C"); usageLevel >= 70 -> Color.parseColor("#FFB23E"); else -> eyeColor }
            usagePaint.textSize = h * 0.028f; usagePaint.color = c; usagePaint.alpha = 140
            val x = w * 0.02f; val y = h * 0.055f
            canvas.drawText(usageText, x, y, usagePaint)
            val bw = w * 0.12f; val bh = h * 0.008f; val by = y + h * 0.015f
            usageBar.color = c; usageBar.alpha = 50
            rect.set(x, by, x + bw, by + bh); canvas.drawRoundRect(rect, bh, bh, usageBar)
            usageBar.alpha = 160
            rect.set(x, by, x + bw * usageLevel.coerceIn(0, 100) / 100f, by + bh); canvas.drawRoundRect(rect, bh, bh, usageBar)
        }

        // the listening-mode button, small and dim in the bottom-left corner
        if (listenChip.isNotEmpty() && mood != Mood.LOADING) {
            chipPaint.textSize = h * 0.03f
            chipPaint.color = eyeColor; chipPaint.alpha = if (t - chipTappedAt < 1500) 230 else 95
            val pad = h * 0.022f
            val tw = chipPaint.measureText(listenChip)
            chipRect.set(w * 0.025f, h * 0.9f, w * 0.025f + tw + pad * 2, h * 0.9f + chipPaint.textSize + pad * 1.6f)
            chipBox.color = eyeColor; chipBox.alpha = chipPaint.alpha / 3
            chipBox.strokeWidth = h * 0.004f
            canvas.drawRoundRect(chipRect, chipRect.height() / 2, chipRect.height() / 2, chipBox)
            canvas.drawText(listenChip, chipRect.left + pad, chipRect.centerY() + chipPaint.textSize * 0.35f, chipPaint)
        }
        if (sleepChip.isNotEmpty() && mood != Mood.LOADING) {
            chipPaint.textSize = h * 0.03f
            chipPaint.color = eyeColor; chipPaint.alpha = 95
            val pad = h * 0.022f
            val tw = chipPaint.measureText(sleepChip)
            sleepRect.set(w * 0.975f - tw - pad * 2, h * 0.9f, w * 0.975f, h * 0.9f + chipPaint.textSize + pad * 1.6f)
            chipBox.color = eyeColor; chipBox.alpha = chipPaint.alpha / 3; chipBox.strokeWidth = h * 0.004f
            canvas.drawRoundRect(sleepRect, sleepRect.height() / 2, sleepRect.height() / 2, chipBox)
            canvas.drawText(sleepChip, sleepRect.left + pad, sleepRect.centerY() + chipPaint.textSize * 0.35f, chipPaint)
        }
        if (mood == Mood.SLEEP && sleepScreen) {
            val label = "🔊 Volume"
            chipPaint.textSize = h * 0.03f; chipPaint.color = eyeColor; chipPaint.alpha = 95
            val pad = h * 0.022f; val tw = chipPaint.measureText(label)
            val top = h * 0.9f - (chipPaint.textSize + pad * 1.6f) - h * 0.025f
            volRect.set(w * 0.975f - tw - pad * 2, top, w * 0.975f, top + chipPaint.textSize + pad * 1.6f)
            chipBox.color = eyeColor; chipBox.alpha = chipPaint.alpha / 3; chipBox.strokeWidth = h * 0.004f
            canvas.drawRoundRect(volRect, volRect.height() / 2, volRect.height() / 2, chipBox)
            canvas.drawText(label, volRect.left + pad, volRect.centerY() + chipPaint.textSize * 0.35f, chipPaint)
        } else volRect.setEmpty()
        // mute button: awake only, just above the sleep button
        if (mood != Mood.SLEEP && mood != Mood.LOADING && sleepChip.isNotEmpty() && !carMode && muteAllowed) {
            val label = if (muted) "🤐 Pull my zip ←" else "🔇 Mute"
            chipPaint.textSize = h * 0.03f; chipPaint.color = eyeColor; chipPaint.alpha = if (muted) 200 else 95
            val pad = h * 0.022f; val tw = chipPaint.measureText(label)
            val top = h * 0.9f - (chipPaint.textSize + pad * 1.6f) - h * 0.025f
            muteRect.set(w * 0.975f - tw - pad * 2, top, w * 0.975f, top + chipPaint.textSize + pad * 1.6f)
            chipBox.color = eyeColor; chipBox.alpha = chipPaint.alpha / 3; chipBox.strokeWidth = h * 0.004f
            canvas.drawRoundRect(muteRect, muteRect.height() / 2, muteRect.height() / 2, chipBox)
            canvas.drawText(label, muteRect.left + pad, muteRect.centerY() + chipPaint.textSize * 0.35f, chipPaint)
        } else muteRect.setEmpty()

        postInvalidateOnAnimation()
    }

    /** A little alarm clock (bells, legs, a ticking hand) with the time left written on its face. */
    private val clockLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val clockFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun drawAlarmClock(c: Canvas, x: Float, y: Float, r: Float, label: String, secs: Float) {
        val col = eyeColor
        clockLine.color = col; clockLine.alpha = 140; clockLine.strokeWidth = r * 0.12f
        clockFill.color = col; clockFill.alpha = 140
        // bells on top, with a little wobble now and then
        val wob = if ((secs % 6f) < 0.6f) sin(secs * 40f) * 0.12f else 0f
        for (side in listOf(-1f, 1f)) {
            val a = Math.toRadians((-90 + side * 42).toDouble()) + wob
            val bx = x + (r * 1.05f * Math.cos(a)).toFloat(); val by = y + (r * 1.05f * Math.sin(a)).toFloat()
            c.drawCircle(bx, by, r * 0.32f, clockFill)
        }
        c.drawLine(x, y - r * 1.05f, x, y - r * 1.25f, clockLine)                     // hammer stem
        c.drawLine(x - r * 0.15f, y - r * 1.25f, x + r * 0.15f, y - r * 1.25f, clockLine)
        // legs
        c.drawLine(x - r * 0.55f, y + r * 0.8f, x - r * 0.8f, y + r * 1.15f, clockLine)
        c.drawLine(x + r * 0.55f, y + r * 0.8f, x + r * 0.8f, y + r * 1.15f, clockLine)
        // face
        clockFill.color = Color.BLACK; clockFill.alpha = 255
        c.drawCircle(x, y, r, clockFill)
        c.drawCircle(x, y, r, clockLine)
        // tick marks
        clockLine.strokeWidth = r * 0.06f
        for (i in 0 until 12) {
            val a = i * Math.PI / 6
            c.drawLine(x + (r * 0.82f * Math.cos(a)).toFloat(), y + (r * 0.82f * Math.sin(a)).toFloat(),
                x + (r * 0.92f * Math.cos(a)).toFloat(), y + (r * 0.92f * Math.sin(a)).toFloat(), clockLine)
        }
        // time left in the middle
        sleepPaint.color = col; sleepPaint.alpha = 200
        sleepPaint.textSize = r * (if (label.length > 4) 0.42f else 0.55f)
        c.drawText(label, x, y + sleepPaint.textSize * 0.36f, sleepPaint)
    }

    /** Red "takeover mode" until this time: scanlines, a scanner sweep, a status readout and a crosshair on the kettle. */
    @Volatile var takeoverUntil = 0L
    var takeoverLines: List<String> = emptyList()
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE }
    private val hudLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private fun drawTakeover(c: Canvas, w: Float, h: Float, secs: Float) {
        val red = Color.parseColor("#FF3B2A")
        // red wash and scanlines
        hudLine.style = Paint.Style.FILL; hudLine.color = red; hudLine.alpha = 14
        c.drawRect(0f, 0f, w, h, hudLine)
        hudLine.color = Color.BLACK; hudLine.alpha = 70
        var y = 0f; while (y < h) { c.drawRect(0f, y, w, y + 2f, hudLine); y += 5f }
        // scanner bar sweeping up and down
        val sy = h * (0.5f + 0.42f * sin(secs * 1.3f))
        hudLine.color = red; hudLine.alpha = 35; c.drawRect(0f, sy - h * 0.02f, w, sy + h * 0.02f, hudLine)
        hudLine.alpha = 150; c.drawRect(0f, sy - 2f, w, sy + 2f, hudLine)
        // status readout, typed out line by line
        hudPaint.color = red; hudPaint.textSize = h * 0.034f; hudPaint.textAlign = Paint.Align.LEFT
        val shown = ((takeoverUntil - now()).let { 25_000 - it }.coerceAtLeast(0) / 350).toInt()
        takeoverLines.forEachIndexed { i, l -> if (i <= shown) { hudPaint.alpha = 210; c.drawText(l, w * 0.035f, h * (0.07f + i * 0.048f), hudPaint) } }
        // crosshair locked on the kettle (screen left), Tassimo marked friendly (screen right)
        val tx = w * 0.13f + sin(secs * 2.1f) * w * 0.006f; val ty = h * 0.62f + sin(secs * 2.9f) * h * 0.006f
        val bw = w * 0.085f; val bh = h * 0.1f
        hudLine.style = Paint.Style.STROKE; hudLine.color = red; hudLine.alpha = 220; hudLine.strokeWidth = h * 0.004f
        c.drawRect(tx - bw, ty - bh, tx + bw, ty + bh, hudLine)
        c.drawLine(tx - bw * 1.4f, ty, tx - bw, ty, hudLine); c.drawLine(tx + bw, ty, tx + bw * 1.4f, ty, hudLine)
        c.drawLine(tx, ty - bh * 1.5f, tx, ty - bh, hudLine); c.drawLine(tx, ty + bh, tx, ty + bh * 1.5f, hudLine)
        hudLine.style = Paint.Style.FILL
        hudPaint.textAlign = Paint.Align.CENTER; hudPaint.textSize = h * 0.036f
        hudPaint.alpha = if ((secs * 3f).toInt() % 2 == 0) 240 else 140
        c.drawText("KETTLE", tx, ty - h * 0.005f, hudPaint)
        hudPaint.textSize = h * 0.027f; hudPaint.alpha = 220
        c.drawText("THREAT: MAX", tx, ty + h * 0.04f, hudPaint)
        hudPaint.textSize = h * 0.03f; hudPaint.alpha = 160
        c.drawText("◄ TASSIMO: ALLY ✓", w * 0.86f, h * 0.62f, hudPaint)
        hudPaint.textAlign = Paint.Align.LEFT
    }

    /** Pip wears glasses while an online brain is resting. */
    @Volatile var glasses = false
    private val specLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val specFill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** This week's cuppas, Monday..Sunday, and which day is today. */
    var week = IntArray(7)
    var weekToday = 0
    /** Show the week chart while awake until this time (Sunday report). */
    @Volatile var chartUntil = 0L
    /** Kitchen timer countdown ("" = none). */
    var timerText = ""
    var shopping: List<String> = emptyList()
    /** "", "xmas", "party", "halloween" */
    var season = ""
    var xmasNote = ""
    private val listPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chartText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }

    /** Bar chart of the week's cuppas: one bar a day, today glowing, the worst day marked. */
    private fun drawWeek(c: Canvas, left: Float, top: Float, width: Float, height: Float) {
        val maxV = max(1, week.maxOrNull() ?: 1)
        val worst = if ((week.maxOrNull() ?: 0) > 0) week.indices.maxByOrNull { week[it] } ?: -1 else -1
        val slot = width / 7f; val bw = slot * 0.55f
        val labelH = height * 0.2f; val numH = height * 0.18f
        val barArea = height - labelH - numH
        chartText.color = eyeColor
        for (i in 0 until 7) {
            val x = left + slot * i + (slot - bw) / 2
            val v = week[i]
            val bh = if (v == 0) barArea * 0.03f else barArea * v / maxV
            val bottom = top + numH + barArea
            barPaint.color = if (i == worst) Color.parseColor("#FF8A5C") else eyeColor
            barPaint.alpha = when { i == weekToday -> 230; i > weekToday -> 40; else -> 130 }
            rect.set(x, bottom - bh, x + bw, bottom); c.drawRoundRect(rect, bw * 0.2f, bw * 0.2f, barPaint)
            chartText.textSize = numH * 0.8f; chartText.alpha = if (v > 0) 170 else 0
            c.drawText(if (i == worst && v >= 5) "💀$v" else "$v", x + bw / 2, bottom - bh - numH * 0.2f, chartText)
            chartText.textSize = labelH * 0.75f; chartText.alpha = if (i == weekToday) 220 else 110
            c.drawText("MTWTFSS"[i].toString(), x + bw / 2, bottom + labelH * 0.85f, chartText)
        }
    }

    /** Santa hat (xmas) or a stripy party hat, perched on an eye. */
    private val hatPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun drawHat(c: Canvas, x: Float, baseY: Float, eyeW: Float, secs: Float) {
        val hw = eyeW * 1.15f; val hh = eyeW * 1.1f
        val path = android.graphics.Path()
        if (season == "xmas") {
            val flop = sin(secs * 1.5f) * eyeW * 0.05f
            path.moveTo(x - hw / 2, baseY); path.quadTo(x - hw * 0.1f, baseY - hh * 0.9f, x + hw * 0.55f + flop, baseY - hh * 0.55f)
            path.quadTo(x + hw * 0.15f, baseY - hh * 0.45f, x + hw / 2, baseY); path.close()
            hatPaint.color = Color.parseColor("#E0262E"); c.drawPath(path, hatPaint)
            hatPaint.color = Color.WHITE
            rect.set(x - hw * 0.58f, baseY - eyeW * 0.14f, x + hw * 0.58f, baseY + eyeW * 0.12f); c.drawRoundRect(rect, eyeW * 0.13f, eyeW * 0.13f, hatPaint)
            c.drawCircle(x + hw * 0.55f + flop, baseY - hh * 0.55f, eyeW * 0.14f, hatPaint)
        } else {
            path.moveTo(x - hw * 0.42f, baseY); path.lineTo(x, baseY - hh); path.lineTo(x + hw * 0.42f, baseY); path.close()
            hatPaint.color = Color.parseColor("#B04CFF"); c.drawPath(path, hatPaint)
            hatPaint.color = Color.parseColor("#FFD23E"); hatPaint.strokeWidth = eyeW * 0.08f
            for (k in 1..3) { val f = k / 4f; c.drawLine(x - hw * 0.42f * (1 - f), baseY - hh * f, x + hw * 0.42f * (1 - f), baseY - hh * f, hatPaint) }
            c.drawCircle(x, baseY - hh, eyeW * 0.1f + eyeW * 0.03f * sin(secs * 6f), hatPaint)
        }
    }

    /** Sleep screen extras: clock, cuppas made today, and when the online brain is back. */
    var sleepScreen = false
    var cuppas = 0
    /** Which kettle show is on (0 = none), and since when. */
    @Volatile var kettleShow = 0
    @Volatile var kettleSince = 0L
    private val kPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val kLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val r1 = RectF()
    private fun c2(c: Canvas, text: String, x: Float, y: Float) = c.drawText(text, x, y, hudPaint)
    var napNote = ""
    var plugNote = ""
    private val sleepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }

    /** Small usage meter in the top-left corner ("" hides it). */
    var usageText = ""
    var usageLevel = 0      // 0..100, turns amber then red as it fills
    private val usagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val usageBar = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Label on the always-listening / wake-word button ("" hides it). */
    var listenChip = ""
    @Volatile private var chipTappedAt = 0L
    private val chipRect = RectF()
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipBox = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** VoiceID Live button, top-right of the sleep screen. */
    private val trainRect = RectF()
    fun trainHit(x: Float, y: Float): Boolean {
        if (!sleepScreen || trainRect.isEmpty) return false
        val m = height * 0.04f
        return x >= trainRect.left - m && x <= trainRect.right + m && y >= trainRect.top - m && y <= trainRect.bottom + m
    }
    /** Volume button, just above the wake button (sleep screen only). */
    private val volRect = RectF()
    fun volHit(x: Float, y: Float): Boolean {
        if (mood != Mood.SLEEP || !sleepScreen || volRect.isEmpty) return false
        val m = height * 0.03f
        val hit = x >= volRect.left - m && x <= volRect.right + m && y >= volRect.top - m && y <= volRect.bottom + m
        if (hit) chipTappedAt = now()
        return hit
    }
    /** Mute button (zips Pip's mouth). */
    @Volatile var muted = false
    /** False once today's 5 zips are used up: the button disappears. */
    @Volatile var muteAllowed = true
    /** How far the zip has been pulled open (0 closed, 1 open), while someone drags the tab. */
    @Volatile var zipOpen = 0f
    @Volatile var zipLeft = 0f; @Volatile var zipRight = 0f; @Volatile var zipY = 0f; @Volatile var zipTabX = 0f
    /** A finger landing on the zip (generous: anywhere along it, or the tab hanging below). */
    fun zipHit(x: Float, y: Float): Boolean {
        if (!muted || zipRight <= zipLeft) return false
        val m = height * 0.06f
        return x >= zipLeft - m && x <= zipRight + m && y >= zipY - m && y <= zipY + m * 2.2f
    }
    private val muteRect = RectF()
    private val r0 = RectF()
    fun muteHit(x: Float, y: Float): Boolean {
        if (muteRect.isEmpty) return false
        val m = height * 0.02f
        val hit = x >= muteRect.left - m && x <= muteRect.right + m && y >= muteRect.top - m && y <= muteRect.bottom + m
        if (hit) chipTappedAt = now()
        return hit
    }
    /** The sleep / wake button, bottom-right ("" hides it). */
    var sleepChip = ""
    private val sleepRect = RectF()
    fun sleepHit(x: Float, y: Float): Boolean {
        if (sleepChip.isEmpty() || sleepRect.isEmpty) return false
        val m = height * 0.04f
        val hit = x >= sleepRect.left - m && x <= sleepRect.right + m && y >= sleepRect.top - m && y <= sleepRect.bottom + m
        if (hit) chipTappedAt = now()
        return hit
    }
    /** Was the button tapped? (generous target, easy to hit) */
    fun chipHit(x: Float, y: Float): Boolean {
        if (listenChip.isEmpty() || chipRect.isEmpty) return false
        val m = height * 0.04f
        val hit = x >= chipRect.left - m && x <= chipRect.right + m && y >= chipRect.top - m && y <= chipRect.bottom + m
        if (hit) chipTappedAt = now()
        return hit
    }

    /** An open hand, fingers up, palm towards us, thumb on the inside (nearest the nose). */
    private fun drawHand(c: Canvas, x: Float, y: Float, s: Float, side: Float, color: Int) {
        fistPaint.color = color; fistPaint.alpha = 255; fistPaint.style = Paint.Style.FILL
        fistLine.alpha = 255; fistLine.strokeWidth = s * 0.03f
        // palm
        rect.set(x - s * 0.42f, y - s * 0.1f, x + s * 0.42f, y + s * 0.55f)
        c.drawRoundRect(rect, s * 0.2f, s * 0.2f, fistPaint)
        // four fingers, slightly fanned
        val fw = s * 0.2f
        for (i in 0..3) {
            val fx = x - s * 0.4f + i * fw * 1.02f
            val len = s * (if (i == 0 || i == 3) 0.48f else 0.58f)
            rect.set(fx, y - len, fx + fw, y + s * 0.05f)
            c.drawRoundRect(rect, fw * 0.5f, fw * 0.5f, fistPaint)
            c.drawRoundRect(rect, fw * 0.5f, fw * 0.5f, fistLine)
        }
        // thumb, on the side nearest the middle
        val tx = x - side * s * 0.42f
        rect.set(min(tx, tx - side * s * 0.3f), y + s * 0.05f, max(tx, tx - side * s * 0.3f), y + s * 0.3f)
        c.drawRoundRect(rect, s * 0.12f, s * 0.12f, fistPaint)
        c.drawRoundRect(rect, s * 0.12f, s * 0.12f, fistLine)
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

    /** Look at one spot for a moment, whatever else is going on (e.g. at a finger on the Home button). */
    @Volatile private var stareX = 0f
    @Volatile private var stareY = 0f
    @Volatile private var stareUntil = 0L
    fun stareAt(x: Float, y: Float, ms: Long) { stareX = x; stareY = y; stareUntil = now() + ms }

    private val seesFace get() = now() - faceAt < 1200
    private val seesMotion get() = now() - motionAt < 1200
    /** Pip sits low on the bench, so it looks up a little at people. */
    private val UP = -0.28f
    private fun followFace() { tLookX = faceX * 1.25f; tLookY = faceY * 0.9f }
    private fun followMotion() { tLookX = motionX * 1.2f; tLookY = (motionY * 0.45f + UP).coerceIn(-1f, 0.6f) }

    // smoothed extras
    private var openL = 1f; private var openR = 1f; private var rot = 0f; private var blush = 0f
    private var lastEmoCol = Color.WHITE
    private val sweatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9FE3FF") }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6F9C") }
    private val tearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7FC8FF") }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heartPath = android.graphics.Path()

    private fun drawHeart(c: Canvas, x: Float, y: Float, size: Float, p: Paint, grow: Float) {
        val s = size * grow
        heartPath.reset()
        heartPath.moveTo(x, y + s * 0.45f)
        heartPath.cubicTo(x - s * 0.95f, y - s * 0.05f, x - s * 0.45f, y - s * 0.75f, x, y - s * 0.28f)
        heartPath.cubicTo(x + s * 0.45f, y - s * 0.75f, x + s * 0.95f, y - s * 0.05f, x, y + s * 0.45f)
        heartPath.close()
        c.drawPath(heartPath, p)
    }

    private val mugPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mugLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    /** A mug of coffee that Pip lifts for a sulky sip every few seconds. */
    private fun drawCoffee(c: Canvas, w: Float, h: Float, secs: Float, color: Int) {
        val cycle = secs % 7f
        val sip = if (cycle < 2f) sin((cycle / 2f) * Math.PI.toFloat()) else 0f   // 0 = on the bench, 1 = at the mouth
        val mw = h * 0.16f; val mh = h * 0.18f
        val x = w * 0.72f - sip * w * 0.18f
        val y = h * 0.80f - sip * h * 0.22f
        c.save()
        c.rotate(-sip * 35f, x, y)
        mugPaint.color = color; mugPaint.alpha = 230
        rect.set(x - mw / 2, y - mh / 2, x + mw / 2, y + mh / 2)
        c.drawRoundRect(rect, mw * 0.18f, mw * 0.18f, mugPaint)
        mugLine.color = color; mugLine.strokeWidth = mw * 0.12f
        rect.set(x + mw * 0.35f, y - mh * 0.25f, x + mw * 0.8f, y + mh * 0.2f)
        c.drawArc(rect, -90f, 180f, false, mugLine)
        // a band of coffee colour
        mugPaint.color = Color.parseColor("#6B3E26")
        rect.set(x - mw / 2 + mw * 0.1f, y - mh / 2 + mh * 0.1f, x + mw / 2 - mw * 0.1f, y - mh / 2 + mh * 0.22f)
        c.drawRoundRect(rect, mw * 0.05f, mw * 0.05f, mugPaint)
        c.restore()
        // steam when it's down on the bench
        if (sip < 0.1f) {
            mugLine.strokeWidth = mw * 0.05f; mugLine.alpha = 120
            for (i in -1..1) {
                val sx = x + i * mw * 0.25f
                val p = android.graphics.Path()
                p.moveTo(sx, y - mh * 0.6f)
                p.quadTo(sx + mw * 0.12f * sin(secs * 3f + i), y - mh * 0.85f, sx, y - mh * 1.1f)
                c.drawPath(p, mugLine)
            }
            mugLine.alpha = 255
        }
    }

    private fun drawHatch(c: Canvas, w: Float, h: Float, age: Float) {
        val x = w / 2; val y = h * 0.55f; val r = h * 0.17f
        c.drawColor(Color.argb((150 * min(1f, age * 3)).toInt(), 0, 0, 0))
        if (age < 2.2f) {
            val wob = sin(age * 18f) * min(1f, age) * 10f
            c.save(); c.rotate(wob, x, y + r)
            eggPaint.color = Color.parseColor("#FFF3D6")
            rect.set(x - r * 0.8f, y - r * 1.1f, x + r * 0.8f, y + r)
            c.drawOval(rect, eggPaint)
            eggPaint.color = Color.parseColor("#F7B6D2")
            for (i in 0..4) c.drawCircle(x + (i - 2) * r * 0.3f, y - r * 0.2f + (i % 2) * r * 0.35f, r * 0.08f, eggPaint)
            if (age > 0.9f) {
                eggLine.strokeWidth = r * 0.05f
                val p = android.graphics.Path()
                var xx = x - r * 0.75f; var k = 0
                p.moveTo(xx, y - r * 0.05f)
                while (xx < x + r * 0.75f) { xx += r * 0.2f; k++; p.lineTo(xx, y - r * 0.05f + (if (k % 2 == 0) -1 else 1) * r * 0.12f) }
                c.drawPath(p, eggLine)
            }
            c.restore()
        } else {
            val pop = min(1f, (age - 2.2f) / 0.4f)
            val bounce = if (pop < 1f) 0f else abs(sin((age - 2.6f) * 5f)) * r * 0.08f
            emojiPaint.textSize = r * 1.9f * (0.3f + 0.7f * pop)
            c.drawText(hatchAnimal, x, y + r * 0.45f - bounce, emojiPaint)
            eggPaint.color = Color.parseColor("#FFF3D6")
            rect.set(x - r * 0.85f, y + r * 0.35f, x + r * 0.85f, y + r * 1.15f)
            c.drawArc(rect, 0f, 180f, true, eggPaint)
            for (i in 0..7) {
                val a = Math.toRadians((i * 45f + age * 40f).toDouble()).toFloat()
                val d = r * (1.3f + 0.15f * sin(age * 6f + i))
                drawSparkle(c, x + d * cos(a), y + d * sin(a), r * 0.12f, Color.parseColor("#FFD84A"))
            }
        }
    }

    private fun drawSparkle(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        sparkPaint.color = color
        heartPath.reset()
        heartPath.moveTo(x, y - r); heartPath.quadTo(x, y, x + r, y); heartPath.quadTo(x, y, x, y + r)
        heartPath.quadTo(x, y, x - r, y); heartPath.quadTo(x, y, x, y - r); heartPath.close()
        c.drawPath(heartPath, sparkPaint)
    }

    /** Little moods of its own while nothing's happening. */
    private var nextIdleFeeling = 0L
    private fun idleFeelings(t: Long) {
        if (nextIdleFeeling == 0L) nextIdleFeeling = t + 30_000
        if (t < nextIdleFeeling || t < emotionUntil || baseEmotion != Emotion.NEUTRAL) return
        nextIdleFeeling = t + Random.nextLong(25_000, 70_000)
        val lonely = t - faceAt > 120_000
        val pick = when {
            seesFace -> listOf(Emotion.WINK, Emotion.CURIOUS, Emotion.HAPPY, Emotion.EXCITED).random()
            lonely -> listOf(Emotion.BORED, Emotion.BORED, Emotion.SUSPICIOUS, Emotion.CURIOUS).random()
            else -> listOf(Emotion.CURIOUS, Emotion.SUSPICIOUS, Emotion.THINKING).random()
        }
        feel(pick, if (pick == Emotion.WINK) 900L else Random.nextLong(2500, 5000))
    }

    private fun glanceAround(t: Long) {
        if (t >= nextGlance) {
            if (Random.nextFloat() < 0.45f) { tLookX = 0f; tLookY = UP }
            else { tLookX = Random.nextFloat() * 1.6f - 0.8f; tLookY = UP + Random.nextFloat() * 0.6f - 0.3f }
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
