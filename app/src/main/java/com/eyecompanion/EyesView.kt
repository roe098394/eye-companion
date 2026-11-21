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
        /** Sinister moment: the eyes glow red until this time (set by a [sinister] feeling tag). */
        @Volatile var redUntil = 0L
        fun goRed(ms: Long = 4500) { redUntil = System.currentTimeMillis() + ms }
        const val KS_STEAM = 1; const val KS_RACE = 2; const val KS_HANDS = 3; const val KS_SHIFTY = 4
        const val KS_HEARTBREAK = 5; const val KS_TALLY = 6; const val KS_BUBBLES = 7
        const val CHORE_ADD = -10; const val CHORE_TABLETS = -11; const val CHORE_TEETH = -12; const val CHORE_HIDE = -13; const val CHORE_SHOW = -14; const val CHORE_MORE = -15; const val CHORE_NONE = -16
        const val WX_CLEAR = 0; const val WX_PARTLY = 1; const val WX_CLOUDY = 2; const val WX_FOG = 3
        const val WX_RAIN = 4; const val WX_SNOW = 5; const val WX_STORM = 6
    }
    enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, SURPRISED, SLEEPY, LOVE, CONFUSED, THINKING, DIZZY,
        SCARED, EXCITED, SUSPICIOUS, BORED, EMBARRASSED, WINK, CURIOUS, LAUGHING, CRYING, STONED, SNIGGER, HOT }

    /** Chloe mode: cute cat ears, nose and whiskers around the eyes. */
    @Volatile var catFace = false

    /**
     * Photo faces: Mark's robot pictures, brought to life. Eyes look about and blink, the jaw talks, the head turns
     * and nods on its neck as it follows people, and any cogs spin as the head moves. All positions are in the
     * picture's own pixels.
     */
    class PhotoFace(val res: Int, val eyes: Array<FloatArray>, val gaze: Float, val lid: Int,
                    val lipCx: Float, val lipY: Float, val hw: Float, val lipA: Float, val lipB: Float,
                    val x0: Float, val x1: Float, val mid: Float, val bot: Float, val jawMax: Float,
                    val head: FloatArray, val fill: FloatArray, val pivotX: Float, val pivotY: Float,
                    val turn: Float, val shift: Float, val nod: Float, val gears: Array<FloatArray>)

    /** 0 = the cartoon eyes, 1 = calm robot, 2 = red-eyed robot. */
    @Volatile var faceStyle = 0
    private val photoFaces = arrayOf(
        PhotoFace(R.drawable.robot_head, eyes = arrayOf(floatArrayOf(147.0f, 258.0f, 28.0f, 12.0f), floatArrayOf(286.0f, 258.0f, 27.0f, 12.0f)), gaze = 7f,
            lid = Color.rgb(109, 96, 102), lipCx = 223f, lipY = 401f, hw = 52f,
            lipA = 0f, lipB = 0f, x0 = 138f, x1 = 304f, mid = 452f, bot = 500f, jawMax = 11f,
            head = floatArrayOf(0.0f, 0.0f, 410.0f, 0.0f, 410.0f, 390.0f, 312.0f, 445.0f, 304.0f, 500.0f, 138.0f, 500.0f, 128.0f, 445.0f, 0.0f, 390.0f), fill = floatArrayOf(0.0f, 0.0f, 410.0f, 0.0f, 410.0f, 380.0f, 0.0f, 380.0f), pivotX = 221f, pivotY = 500f,
            turn = 2.5f, shift = 6f, nod = 8f, gears = arrayOf()),
        PhotoFace(R.drawable.robot_red, eyes = arrayOf(floatArrayOf(426.0f, 387.0f, 43.5f, 19.5f), floatArrayOf(666.0f, 387.0f, 43.5f, 19.5f)), gaze = 7.5f,
            lid = Color.rgb(30, 26, 28), lipCx = 555.0f, lipY = 622.5f, hw = 75.0f,
            lipA = -15.0f, lipB = -3.75f, x0 = 442.5f, x1 = 667.5f, mid = 678.75f, bot = 726.0f, jawMax = 13.5f,
            head = floatArrayOf(150.0f, 0.0f, 960.0f, 0.0f, 960.0f, 570.0f, 810.0f, 630.0f, 750.0f, 697.5f, 667.5f, 731.2f, 442.5f, 731.2f, 360.0f, 697.5f, 315.0f, 630.0f, 150.0f, 570.0f), fill = floatArrayOf(150.0f, 0.0f, 960.0f, 0.0f, 960.0f, 585.0f, 150.0f, 585.0f), pivotX = 555.0f, pivotY = 731.2f,
            turn = 3f, shift = 6.0f, nod = 9.0f, gears = arrayOf(floatArrayOf(541.5f, 894.0f, 106.5f, 1.0f), floatArrayOf(656.2f, 780.0f, 49.5f, -2.1f), floatArrayOf(693.8f, 909.8f, 49.5f, -2.1f), floatArrayOf(378.8f, 896.2f, 45.0f, -2.3f), floatArrayOf(375.0f, 772.5f, 51.0f, 1.5f))),
    )
    private val photoBmps = HashMap<Int, android.graphics.Bitmap>()
    private val robotPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val robotFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val redGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rSrc = android.graphics.Rect(); private val rDst = RectF()
    private var jaw = 0f; private var gearAng = 0f; private var lastTurn = 0f

    private fun polyPath(p: FloatArray, X: (Float) -> Float, Y: (Float) -> Float) = android.graphics.Path().apply {
        moveTo(X(p[0]), Y(p[1])); var k = 2; while (k < p.size) { lineTo(X(p[k]), Y(p[k + 1])); k += 2 }; close()
    }

    private fun drawRobot(c: Canvas, w: Float, h: Float, lx: Float, ly: Float, openness: Float, talkLevel: Float,
                          surprised: Boolean, dt: Float, red: Float = 0f) {
        val f = photoFaces.getOrNull(faceStyle - 1) ?: return
        val bmp = photoBmps[f.res] ?: try {
            android.graphics.BitmapFactory.decodeResource(resources, f.res).also { photoBmps[f.res] = it }
        } catch (_: Throwable) { null } ?: return
        val s = min(h * 0.96f / bmp.height, w * 0.98f / bmp.width)
        val ox = w / 2 - bmp.width * s / 2; val oy = h / 2 - bmp.height * s / 2
        val X = { px: Float -> ox + px * s }; val Y = { py: Float -> oy + py * s }
        val full = RectF(ox, oy, ox + bmp.width * s, oy + bmp.height * s)
        c.drawBitmap(bmp, null, full, robotPaint)
        // cogs: spin as the head turns
        val turnNow = lx.coerceIn(-1.3f, 1.3f) * f.turn
        gearAng += (turnNow - lastTurn) * 9f; lastTurn = turnNow
        for (g in f.gears) {
            c.save()
            val p = android.graphics.Path(); p.addCircle(X(g[0]), Y(g[1]), g[2] * 0.97f * s, android.graphics.Path.Direction.CW)
            c.clipPath(p); c.rotate(gearAng * g[3], X(g[0]), Y(g[1]))
            c.drawBitmap(bmp, null, full, robotPaint)
            c.restore()
        }
        // head: turns and nods on the neck (the space it leaves behind is the black background)
        robotFill.color = Color.BLACK; c.drawPath(polyPath(f.fill, X, Y), robotFill)
        c.save()
        c.translate(lx.coerceIn(-1.3f, 1.3f) * f.shift * s, ly.coerceIn(-1f, 1f) * f.nod * s)
        c.rotate(turnNow, X(f.pivotX), Y(f.pivotY))
        c.clipPath(polyPath(f.head, X, Y))
        c.drawBitmap(bmp, null, full, robotPaint)
        // eyes: the inside of each socket is redrawn shifted, so they look about; metal lids blink
        val gx = (lx * f.gaze).coerceIn(-f.gaze * 1.2f, f.gaze * 1.2f); val gy = (ly * f.gaze * 0.4f).coerceIn(-f.gaze, f.gaze)
        val lid = (1f - openness).coerceIn(0f, 1f)
        for (e in f.eyes) {
            val ex = e[0]; val ey = e[1]; val rx = e[2]; val ry = e[3]
            c.save()
            val p = android.graphics.Path(); p.addOval(RectF(X(ex - rx), Y(ey - ry), X(ex + rx), Y(ey + ry)), android.graphics.Path.Direction.CW)
            c.clipPath(p)
            val mx = rx * 0.4f; val my = ry * 0.4f
            rSrc.set((ex - rx - mx - gx).toInt(), (ey - ry - my - gy).toInt(), (ex + rx + mx - gx).toInt(), (ey + ry + my - gy).toInt())
            rDst.set(X(ex - rx - mx), Y(ey - ry - my), X(ex + rx + mx), Y(ey + ry + my))
            c.drawBitmap(bmp, rSrc, rDst, robotPaint)
            if (red > 0.01f) {                                   // sinister: the iris glows red
                val ix = X(ex + gx); val iy = Y(ey + gy); val ir = ry * s * 1.1f
                redGlow.shader = android.graphics.RadialGradient(ix, iy, ir * 2.2f,
                    intArrayOf(Color.argb((255 * red).toInt(), 255, 235, 225), Color.argb((235 * red).toInt(), 255, 30, 20),
                        Color.argb((120 * red).toInt(), 200, 0, 0), Color.TRANSPARENT), floatArrayOf(0f, 0.22f, 0.5f, 1f),
                    android.graphics.Shader.TileMode.CLAMP)
                c.drawCircle(ix, iy, ir * 2.2f, redGlow)
            }
            if (lid > 0.02f) {
                robotFill.color = f.lid
                c.drawRect(X(ex - rx), Y(ey - ry), X(ex + rx), Y(ey - ry) + 2 * ry * s * lid, robotFill)
            }
            c.restore()
        }
        // jaw: the lips part in the middle and the chin drops, column by column
        val target = (talkLevel * f.jawMax * 1.45f).coerceIn(0f, f.jawMax) + (if (surprised) f.jawMax * 0.35f else 0f)
        jaw += (target - jaw) * min(1f, dt * 18f)
        if (jaw > 0.3f) {
            robotFill.color = Color.rgb(36, 11, 12)
            var px = f.x0.toInt()
            while (px < f.x1) {
                val d = (px - f.lipCx) / f.hw
                val pr = if (abs(d) < 1f) cos(d * Math.PI.toFloat() / 2).let { it * it } else 0f
                val lip = f.lipY + f.lipA * d * d + f.lipB * d
                val edge = min(1f, min((px - f.x0) / 10f, (f.x1 - px) / 10f))
                val top = lip + jaw * pr; val m = f.mid + jaw * edge
                if (pr > 0.02f) c.drawRect(X(px.toFloat()), Y(lip), X(px + 1f) + 0.5f, Y(top + 1f), robotFill)
                rSrc.set(px, lip.toInt(), px + 1, f.mid.toInt()); rDst.set(X(px.toFloat()), Y(top), X(px + 1f) + 0.5f, Y(m))
                c.drawBitmap(bmp, rSrc, rDst, robotPaint)
                rSrc.set(px, f.mid.toInt(), px + 1, f.bot.toInt()); rDst.set(X(px.toFloat()), Y(m), X(px + 1f) + 0.5f, Y(m + (f.bot - f.mid)))
                c.drawBitmap(bmp, rSrc, rDst, robotPaint)
                px++
            }
        }
        c.restore()
    }

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

    /** 0..1 how loud Pip's own voice is right now (from the sound she's playing), or -1 if unknown. */
    @Volatile var voiceLevel: () -> Float = { -1f }

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
                    nextBlink = t + if (blinkBurst > 0) { blinkBurst--; 120L }       // fluttery blinks while primping
                        else if (Random.nextFloat() < 0.2f) 180L else Random.nextLong(2500, 6500)
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

        // --- which way is down? turn the whole face to stay upright, swinging into place like it's falling round
        var diff = ((gravityAngle - frameAng + 540f) % 360f) - 180f
        if (abs(diff) > 0.05f || abs(frameVel) > 0.05f) {
            frameVel += (diff * 38f - frameVel * 3.2f) * dt
            frameAng += frameVel * dt
            frameAng = ((frameAng % 360f) + 360f) % 360f
            diff = ((gravityAngle - frameAng + 540f) % 360f) - 180f
        }
        val sw = width.toFloat(); val sh = height.toFloat()
        val portrait = abs(sin(Math.toRadians(frameAng.toDouble()))) > 0.5
        val w = if (portrait) sh else sw; val h = if (portrait) sw else sh
        canvas.save()
        canvas.translate(sw / 2, sh / 2); canvas.rotate(-frameAng); canvas.translate(-w / 2, -h / 2)
        frameMatrix.reset(); frameMatrix.postTranslate(-w / 2, -h / 2); frameMatrix.postRotate(-frameAng); frameMatrix.postTranslate(sw / 2, sh / 2)
        frameMatrix.invert(frameInverse)
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
        // sinister moment: fade to red, hold, fade back
        val redLeft = redUntil - t
        val red = if (redLeft <= 0) 0f else min(1f, min(redLeft / 600f, (4500f - redLeft).coerceAtLeast(0f) / 350f + 0.05f))
        val col = if (takeover) Color.parseColor("#FF2A1E") else blend(blend(eyeColor, emoColor, tint), Color.parseColor("#FF2A1E"), red)
        eyePaint.color = col
        glowPaint.color = col; glowPaint.alpha = 40
        // Christmas elves (the peeking ones hide behind the eyes, so they're drawn first)
        elves.tick(t, mood == Mood.SLEEP && sleepScreen, carMode || puzzle.isNotEmpty() || mood == Mood.LOADING || mood == Mood.THINK || mood == Mood.TALK)
        val crackV = toV(sw * Elves.CRACK_X, sh * Elves.CRACK_Y)
        val zzX = cx + gap / 2 + eyeW * 0.9f + w * 0.06f; val zzY = cy - h * 0.13f
        if (!carMode) elves.draw(canvas, t, w, h, true, cx, cy, gap, eyeW, eyeH, lastClockW, zzX, zzY, crackV[0], crackV[1])
        canvas.save()
        val robotNow = faceStyle > 0 && !carMode && !catFace && puzzle.isEmpty() && !hatching && mood != Mood.LOADING && mood != Mood.SLEEP
        if (!robotNow && abs(rot) > 0.3f) canvas.rotate(rot, cx, cy)
        if (robotNow) {
            val vl = voiceLevel()
            val talk = if (mood == Mood.TALK) (if (vl >= 0f) vl else level) else 0f
            drawRobot(canvas, w, h, lookX, lookY, max(0.04f, open * blink),
                talk, feeling == Emotion.SURPRISED, dt, red)
        } else for (side in arrayOf(-1f, 1f)) {
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
            // SCORCHING (the dryer's been on for hours): lava cracks glowing in the eyes, flames on top, burning cheeks
            if (scorching && feeling == Emotion.HOT && !carMode && openNow > 0.2f) {
                val glow = 0.75f + 0.25f * sin(secs * 3.1f + side)
                canvas.save()
                rect.set(ex - ew / 2, cy - eh / 2, ex + ew / 2, cy + eh / 2)
                clipPath.reset(); clipPath.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW); canvas.clipPath(clipPath)
                scorchPaint.style = Paint.Style.FILL; scorchPaint.color = Color.parseColor("#C2301A"); scorchPaint.alpha = 140
                canvas.drawRect(rect, scorchPaint)
                scorchPaint.style = Paint.Style.STROKE; scorchPaint.strokeCap = Paint.Cap.ROUND
                for ((k, crack) in cracks.withIndex()) {
                    scorchPaint.color = if (k % 3 == 0) Color.parseColor("#FFF07A") else Color.parseColor("#FFB020")
                    scorchPaint.alpha = (255 * glow).toInt(); scorchPaint.strokeWidth = ew * (if (k % 3 == 0) 0.022f else 0.014f)
                    canvas.save(); canvas.translate(ex, cy); canvas.scale(ew * (if (side < 0) -1f else 1f), eh)
                    scorchPaint.strokeWidth /= ew
                    canvas.drawPath(crack, scorchPaint); canvas.restore()
                }
                scorchPaint.style = Paint.Style.FILL
                canvas.restore()
                // flames licking up from the top of each eye
                for (f in 0 until 5) {
                    val fx = ex - ew * 0.38f + f * ew * 0.19f
                    val flick = sin(secs * (7f + f) + f * 1.7f + side)
                    val fh = eh * (0.55f + 0.25f * ((f * 37 % 5) / 4f)) * (0.85f + 0.15f * flick)
                    val base = cy - eh / 2 + ew * 0.05f
                    val pth = android.graphics.Path().apply {
                        moveTo(fx - ew * 0.11f, base)
                        quadTo(fx - ew * 0.12f, base - fh * 0.5f, fx + flick * ew * 0.05f, base - fh)
                        quadTo(fx + ew * 0.12f, base - fh * 0.5f, fx + ew * 0.11f, base)
                        close()
                    }
                    scorchPaint.color = Color.parseColor("#FF6A1A"); scorchPaint.alpha = 150; canvas.drawPath(pth, scorchPaint)
                    canvas.save(); canvas.scale(0.55f, 0.6f, fx, base)
                    scorchPaint.color = Color.parseColor("#FFD24A"); scorchPaint.alpha = 190; canvas.drawPath(pth, scorchPaint)
                    canvas.restore()
                }
                // burning pink cheeks with a fiery rim
                val chx = ex + side * ew * 0.05f; val chy = cy + eyeH * 0.42f + ew * 0.16f
                for (k in 0 until 10) {
                    val a = k * 0.628f + secs * 2f
                    scorchPaint.color = Color.parseColor("#FF5A1A"); scorchPaint.alpha = (120 + 80 * sin(secs * 9f + k)).toInt().coerceIn(0, 255)
                    canvas.drawCircle(chx + kotlin.math.cos(a) * ew * 0.47f, chy + sin(a) * ew * 0.17f, ew * 0.06f, scorchPaint)
                }
                scorchPaint.color = Color.parseColor("#F0609A"); scorchPaint.alpha = 235
                rect.set(chx - ew * 0.45f, chy - ew * 0.16f, chx + ew * 0.45f, chy + ew * 0.16f); canvas.drawOval(rect, scorchPaint)
            }
            // blushing cheeks
            if (blush > 0.03f && !carMode && !(scorching && feeling == Emotion.HOT)) {
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

        // Pip's mouth: a glowing sound wave under the eyes that ripples when she talks and bends with her mood
        if (!robotNow && !muted && !carMode && mood != Mood.LOADING && mood != Mood.SLEEP && !hatching)
            drawMouth(canvas, cx, cy + eyeH * 0.5f + eyeW * 0.30f, eyeW, secs, dt, feeling, col)

        // round specs while one of the online brains is resting
        if (glasses && !robotNow && !carMode && mood != Mood.LOADING) {
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

        // Laura's here: best outfit on (a bow, a flower or a top hat), a quick tidy-up and a red carpet
        if (dressUp != 0 && !carMode && mood != Mood.LOADING && mood != Mood.SLEEP && t < dressUntil)
            drawOutfit(canvas, cx + gap / 2 + eyeW / 2, cy - eyeH * 0.5f * max(0.35f, openNow) - eyeH * 0.05f, eyeW, secs)
        if (t < primpUntil && !carMode) drawPrimp(canvas, cx, cy - eyeH * 0.5f * max(0.35f, openNow), gap + eyeW * 2, eyeW, eyeH, (t - primpSince) / 1000f)
        if (t < carpetUntil && !carMode) drawCarpet(canvas, w, h, (t - carpetSince) / 1000f)

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

        // sleep screen: weather background, big clock, weather + Christmas cards and the to-do list (no eyes)
        if (mood == Mood.SLEEP && sleepScreen) {
            drawSleepScene(canvas, w, h, secs)
            if (timerText.isNotEmpty()) drawAlarmClock(canvas, w * 0.52f, h * 0.17f, h * 0.075f, timerText, secs)
        }

        // daily allowance meter, top-left
        if (usageText.isNotEmpty() && mood != Mood.LOADING && t >= takeoverUntil && !(mood == Mood.SLEEP && sleepScreen)) {      // hidden while the takeover HUD is up
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
        if (!carMode && !(mood == Mood.SLEEP && sleepScreen)) elves.draw(canvas, t, w, h, false, cx, cy, gap, eyeW, eyeH, lastClockW, zzX, zzY, crackV[0], crackV[1])

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

        canvas.restore()                                  // (the upright frame)
        postInvalidateOnAnimation()
    }

    /** "🎄 46 sleeps till Christmas" (from 120 sleeps before), "Merry Christmas!" on the day, nothing otherwise. */
    private var xmasDay = -1; private var xmasText: String? = null
    private fun christmasLine(): String? {
        val now = java.util.Calendar.getInstance()
        val doy = now.get(java.util.Calendar.DAY_OF_YEAR)
        if (doy == xmasDay) return xmasText
        xmasDay = doy
        val today = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 12); set(java.util.Calendar.MINUTE, 0) }
        val xmas = java.util.Calendar.getInstance().apply { set(today.get(java.util.Calendar.YEAR), java.util.Calendar.DECEMBER, 25, 12, 0, 0) }
        if (today.after(xmas) && today.get(java.util.Calendar.DAY_OF_YEAR) != xmas.get(java.util.Calendar.DAY_OF_YEAR)) xmas.add(java.util.Calendar.YEAR, 1)
        val sleeps = Math.round((xmas.timeInMillis - today.timeInMillis) / 86_400_000.0).toInt()
        xmasText = when {
            sleeps == 0 -> "🎄  Merry Christmas!  🎅"
            sleeps == 1 -> "🎄  1 sleep till Christmas!  🎅"
            sleeps in 2..120 -> "🎄  $sleeps sleeps till Christmas"
            else -> null
        }
        return xmasText
    }

    /** Naughty Christmas elves (6 Nov to Christmas Day). */
    val elves = Elves()
    private var lastClockW = 0f
    /** A tap on a hiding elf? */
    fun elfHit(x0: Float, y0: Float): Boolean { val pt = toV(x0, y0); return elves.hit(pt[0], pt[1]) }

    /** Down, from the screen's point of view (degrees, 0 = the normal way up): set from the motion sensor. */
    @Volatile var gravityAngle = 0f
    private var frameAng = 0f
    private var frameVel = 0f
    private val frameMatrix = android.graphics.Matrix()
    private val frameInverse = android.graphics.Matrix()
    private val tp = FloatArray(2)
    /** A touch on the real screen, in the turned frame's coordinates (what the buttons are drawn in). */
    fun toV(x: Float, y: Float): FloatArray { tp[0] = x; tp[1] = y; frameInverse.mapPoints(tp); return floatArrayOf(tp[0], tp[1]) }

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

    // ---- making an effort for Laura
    /** 1 bow, 2 flower, 3 top hat (on the right eye), until dressUntil. */
    @Volatile var dressUp = 0
    @Volatile var dressUntil = 0L
    @Volatile private var primpSince = 0L
    @Volatile private var primpUntil = 0L
    @Volatile private var carpetSince = 0L
    @Volatile private var carpetUntil = 0L
    @Volatile var carpetName = ""
    /** A quick "fix my hair": a comb swipes across the top, sparkles, fast blinks. */
    fun primp() { primpSince = now(); primpUntil = primpSince + 2600; blinkBurst = 3; nextBlink = now() }
    /** Drumroll, spotlight sweep and a name in lights. */
    fun redCarpet(name: String) { carpetName = name; carpetSince = now(); carpetUntil = carpetSince + 4500 }
    @Volatile var blinkBurst = 0
    private val outfitPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawOutfit(c: Canvas, x: Float, baseY: Float, eyeW: Float, secs: Float) {
        val sway = sin(secs * 1.6f) * eyeW * 0.03f
        when (dressUp) {
            1 -> {                                                        // a big pink bow
                outfitPaint.color = Color.parseColor("#FF5FA8")
                val bx = x + eyeW * 0.25f + sway; val by = baseY - eyeW * 0.12f
                val l = android.graphics.Path().apply { moveTo(bx, by); lineTo(bx - eyeW * 0.42f, by - eyeW * 0.22f); lineTo(bx - eyeW * 0.42f, by + eyeW * 0.22f); close() }
                val r = android.graphics.Path().apply { moveTo(bx, by); lineTo(bx + eyeW * 0.42f, by - eyeW * 0.22f); lineTo(bx + eyeW * 0.42f, by + eyeW * 0.22f); close() }
                c.drawPath(l, outfitPaint); c.drawPath(r, outfitPaint)
                outfitPaint.color = Color.parseColor("#D93E86"); c.drawCircle(bx, by, eyeW * 0.1f, outfitPaint)
            }
            2 -> {                                                        // a flower tucked over the eye
                val fx = x + eyeW * 0.3f + sway; val fy = baseY - eyeW * 0.1f
                outfitPaint.color = Color.parseColor("#FFD84A")
                for (k in 0 until 6) {
                    val a = k * Math.PI.toFloat() / 3f + secs * 0.3f
                    c.drawCircle(fx + kotlin.math.cos(a) * eyeW * 0.15f, fy + sin(a) * eyeW * 0.15f, eyeW * 0.11f, outfitPaint)
                }
                outfitPaint.color = Color.parseColor("#FF8A3C"); c.drawCircle(fx, fy, eyeW * 0.1f, outfitPaint)
            }
            else -> {                                                     // a dapper top hat, tipped a little
                c.save(); c.rotate(8f + sway / eyeW * 40f, x, baseY)
                outfitPaint.color = Color.parseColor("#1C1C1C")
                rect.set(x - eyeW * 0.6f, baseY - eyeW * 0.1f, x + eyeW * 0.6f, baseY + eyeW * 0.02f); c.drawRoundRect(rect, eyeW * 0.05f, eyeW * 0.05f, outfitPaint)
                rect.set(x - eyeW * 0.38f, baseY - eyeW * 0.85f, x + eyeW * 0.38f, baseY - eyeW * 0.05f); c.drawRect(rect, outfitPaint)
                outfitPaint.color = Color.parseColor("#C0283A")
                rect.set(x - eyeW * 0.38f, baseY - eyeW * 0.25f, x + eyeW * 0.38f, baseY - eyeW * 0.13f); c.drawRect(rect, outfitPaint)
                c.restore()
            }
        }
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, r: Float) {
        val p = android.graphics.Path().apply {
            moveTo(x, y - r); quadTo(x, y, x + r, y); quadTo(x, y, x, y + r); quadTo(x, y, x - r, y); quadTo(x, y, x, y - r); close()
        }
        c.drawPath(p, outfitPaint)
    }

    private fun drawPrimp(c: Canvas, cx: Float, topY: Float, span: Float, eyeW: Float, eyeH: Float, age: Float) {
        val p = (age / 1.4f).coerceIn(0f, 1f)                           // the comb swipes left to right in 1.4 s
        if (age < 1.6f) {
            val combX = cx - span / 2 + span * p; val combY = topY - eyeW * 0.18f
            outfitPaint.color = Color.parseColor("#FF8FC8"); outfitPaint.alpha = 240
            rect.set(combX - eyeW * 0.4f, combY - eyeW * 0.12f, combX + eyeW * 0.4f, combY); c.drawRoundRect(rect, eyeW * 0.05f, eyeW * 0.05f, outfitPaint)
            for (k in 0 until 9) {
                val tx = combX - eyeW * 0.36f + k * eyeW * 0.09f
                rect.set(tx - eyeW * 0.02f, combY, tx + eyeW * 0.02f, combY + eyeW * 0.14f); c.drawRect(rect, outfitPaint)
            }
        }
        outfitPaint.color = Color.WHITE
        for (k in 0 until 6) {                                            // sparkles twinkling around the eyes
            val tw = sin(age * 9f + k * 1.3f)
            if (tw < 0f) continue
            outfitPaint.alpha = (255 * tw * (1f - (age / 2.6f).coerceIn(0f, 1f) * 0.6f)).toInt()
            val sx = cx + (if (k % 2 == 0) -1 else 1) * span * (0.35f + 0.12f * (k / 2))
            val sy = topY + eyeH * (0.1f + 0.3f * (k % 3))
            sparkle(c, sx, sy, eyeW * (0.06f + 0.05f * tw))
        }
        outfitPaint.alpha = 255
    }

    private fun drawCarpet(c: Canvas, w: Float, h: Float, age: Float) {
        // a spotlight sweeping in from the left, then the name in lights
        val sweep = (age / 1.8f).coerceIn(0f, 1f)
        val lx = w * (0.1f + 0.4f * sweep)
        outfitPaint.color = Color.parseColor("#FFF3B0"); outfitPaint.alpha = (60 * (1f - (age - 3.5f).coerceIn(0f, 1f))).toInt()
        val beam = android.graphics.Path().apply { moveTo(w * 0.5f, -h * 0.05f); lineTo(lx - w * 0.18f, h * 1.05f); lineTo(lx + w * 0.18f, h * 1.05f); close() }
        c.drawPath(beam, outfitPaint)
        if (age > 1.6f) {
            val a = ((age - 1.6f) / 0.4f).coerceIn(0f, 1f) * (1f - (age - 3.8f).coerceIn(0f, 0.7f) / 0.7f)
            hudPaint.textAlign = Paint.Align.CENTER; hudPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            hudPaint.color = Color.parseColor("#FFD84A"); hudPaint.alpha = (255 * a).toInt()
            hudPaint.textSize = h * 0.035f; c.drawText("✨ LADIES AND GENTLEMEN ✨", w / 2, h * 0.11f, hudPaint)
            hudPaint.textSize = h * (0.085f + 0.01f * sin(age * 6f)); c.drawText(carpetName.uppercase(), w / 2, h * 0.2f, hudPaint)
            hudPaint.textAlign = Paint.Align.LEFT; hudPaint.typeface = android.graphics.Typeface.MONOSPACE
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
    /** The dryer's been on for ages: the hot face goes up a level (lava eyes, flames, burning cheeks). */
    @Volatile var scorching = false
    private val scorchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = android.graphics.Path()
    /** Lava cracks inside an eye, in eye-sized units (-0.5..0.5), made once. */
    private val cracks: List<android.graphics.Path> by lazy {
        val rnd = java.util.Random(7)
        val out = ArrayList<android.graphics.Path>()
        repeat(7) { b ->
            val pth = android.graphics.Path()
            var x = (rnd.nextFloat() - 0.5f) * 0.2f; var y = (rnd.nextFloat() - 0.5f) * 0.2f
            val ang0 = b * 0.9f + rnd.nextFloat() * 0.5f
            pth.moveTo(x, y)
            var ang = ang0
            repeat(6) {
                ang += (rnd.nextFloat() - 0.5f) * 0.9f
                x += kotlin.math.cos(ang) * 0.09f; y += sin(ang) * 0.09f
                pth.lineTo(x, y)
                if (rnd.nextFloat() < 0.35f) {                           // little side branches
                    val bx = x + kotlin.math.cos(ang + 1.2f) * 0.07f; val by = y + sin(ang + 1.2f) * 0.07f
                    pth.lineTo(bx, by); pth.moveTo(x, y)
                }
            }
            out.add(pth)
        }
        out
    }
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
    // ---- the sleep screen: a moving background that matches the weather outside, a big clock,
    //      weather and Christmas cards, and the to-do list. Tap a job to tick it, "+ Add" to type one.
    data class Chore(val text: String, val done: Boolean)
    @Volatile var chores: List<Chore> = emptyList()
    /** Laura's tablets card at the top of the list ("" = hidden). */
    @Volatile var tabletsCard = ""
    /** The to-do list folded away (visitors round). */
    @Volatile var todoHidden = false
    private val choreHideRect = RectF()
    private val choreShowRect = RectF()
    private val choreMoreRect = RectF()
    private val chorePanelRect = RectF()
    /** First job shown (the list pages on when "+3 more" is tapped). */
    var choreOffset = 0
    /** Move the list on a page (or back to the top after the last page). */
    fun nextChorePage() { choreOffset = if (choreOffset + choreRows.size >= chores.size || choreRows.isEmpty()) 0 else choreOffset + choreRows.size }
    /** The weather (from the forecast): kind is one of WX_*, -1 until the first forecast arrives. */
    @Volatile var wxKind = -1
    @Volatile var wxDay = true
    @Volatile var wxTemp = ""
    @Volatile var wxDesc = ""
    @Volatile var wxLine = ""
    @Volatile var wxWind = 0f
    private val choreRows = ArrayList<RectF>()          // row i = chores[i]; the tablets card is separate
    private val choreAddRect = RectF()
    private val choreTabRect = RectF()
    private val chorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val choreBox = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(26, 255, 255, 255) }
    private val cardLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(48, 255, 255, 255) }
    private val wxText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thinFace = android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL)
    private val lightFace = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
    private val mediumFace = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    private val boldFace = android.graphics.Typeface.DEFAULT_BOLD
    private val seeds = Random(7).let { r -> FloatArray(120 * 4) { r.nextFloat() } }    // x, y, size, speed per particle
    private var bgKey = ""
    private val rr = RectF()
    private fun soft(color: Int) = android.graphics.RadialGradient(0f, 0f, 1f,
        intArrayOf(color, Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))), null, android.graphics.Shader.TileMode.CLAMP)
    private val glowCache = HashMap<Int, android.graphics.Shader>()
    /** A soft round glow centred on (x, y), rx by ry, fading to nothing at the edge. */
    private fun blob(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, color: Int) {
        blobPaint.shader = glowCache.getOrPut(color) { soft(color) }
        c.save(); c.translate(x, y); c.scale(rx, ry); c.drawCircle(0f, 0f, 1f, blobPaint); c.restore()
    }
    private fun wrap(v: Float) = ((v % 1f) + 1f) % 1f

    private fun drawSleepScene(c: Canvas, w: Float, h: Float, secs: Float) {
        val kind = wxKind; val day = wxDay
        // background colours: top -> bottom, darker at night
        val (top, bot) = when (kind) {
            WX_CLEAR -> if (day) "#1D4F86" to "#4F86B8" else "#040A17" to "#0E1D38"
            WX_PARTLY -> if (day) "#2B4A6E" to "#5E7F9F" else "#060C18" to "#16233A"
            WX_CLOUDY -> if (day) "#3A4757" to "#5F6D7D" else "#0A0F16" to "#1B232E"
            WX_FOG -> if (day) "#4A525B" to "#6B737C" else "#101418" to "#22272D"
            WX_RAIN, WX_STORM -> if (day) "#202B37" to "#3C4856" else "#05080D" to "#121A23"
            WX_SNOW -> if (day) "#33435A" to "#647893" else "#0A1324" to "#1A2740"
            else -> "#0A1324" to "#14213A"
        }
        val key = "$top$bot$h"
        if (key != bgKey) {
            bgKey = key
            bgPaint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, Color.parseColor(top), Color.parseColor(bot), android.graphics.Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, bgPaint)
        val wind = 1f + (wxWind / 25f).coerceIn(0f, 2.5f)

        // slow drifting glows
        val d1 = sin(secs * 0.05f); val d2 = cos(secs * 0.037f)
        when {
            kind == WX_CLEAR && day -> blob(c, w * (0.82f + 0.03f * d1), h * (0.12f + 0.03f * d2), h * 0.75f, h * 0.75f, Color.argb(120, 255, 214, 120))
            kind == WX_RAIN || kind == WX_STORM || kind == WX_FOG -> {}
            else -> {
                blob(c, w * (0.12f + 0.04f * d1), h * (0.1f + 0.05f * d2), h * 0.85f, h * 0.85f, Color.argb(95, 56, 110, 190))
                blob(c, w * (0.88f + 0.04f * d2), h * (0.95f + 0.04f * d1), h, h, Color.argb(70, 120, 90, 200))
            }
        }

        // stars and, now and then, northern lights on clear nights
        if (!day && (kind == WX_CLEAR || kind == WX_PARTLY || kind == -1)) {
            fxPaint.style = Paint.Style.FILL; fxPaint.color = Color.WHITE
            for (i in 0 until 70) {
                val x = seeds[i * 4] * w; val y = seeds[i * 4 + 1] * h * 0.7f
                val tw = 0.5f + 0.5f * sin(secs * (0.6f + seeds[i * 4 + 3] * 1.5f) + i)
                fxPaint.alpha = (60 + 150 * tw * seeds[i * 4 + 2]).toInt().coerceIn(0, 255)
                c.drawCircle(x, y, h * (0.0015f + 0.0025f * seeds[i * 4 + 2]), fxPaint)
            }
            if (kind == WX_CLEAR) {
                val show = (0.5f + 0.5f * sin(secs * 0.02f)).let { it * it }        // comes and goes over a few minutes
                if (show > 0.05f) {
                    val a = (show * 110).toInt()
                    blob(c, w * (0.3f + 0.05f * d1), h * 0.12f, w * 0.55f, h * 0.22f, Color.argb(a, 52, 211, 153))
                    blob(c, w * (0.62f + 0.05f * d2), h * 0.08f, w * 0.45f, h * 0.18f, Color.argb(a * 3 / 4, 56, 189, 248))
                    blob(c, w * (0.85f - 0.04f * d1), h * 0.2f, w * 0.35f, h * 0.2f, Color.argb(a * 2 / 3, 167, 139, 250))
                }
            }
        }

        // clouds drifting across
        val clouds = when (kind) { WX_PARTLY -> 3; WX_CLOUDY -> 6; WX_RAIN, WX_STORM -> 5; WX_SNOW -> 3; else -> 0 }
        val cloudA = when { kind == WX_RAIN || kind == WX_STORM -> if (day) 60 else 40; day -> 75; else -> 38 }
        for (i in 0 until clouds) {
            val s = seeds[(i + 80) * 4 + 2]; val sp = 0.004f + 0.006f * seeds[(i + 80) * 4 + 3]
            val x = (wrap(seeds[(i + 80) * 4] + secs * sp * wind) * 1.6f - 0.3f) * w
            val y = h * (0.06f + 0.4f * seeds[(i + 80) * 4 + 1])
            val r = h * (0.16f + 0.12f * s)
            val col = Color.argb(cloudA, 235, 240, 248)
            blob(c, x, y, r * 1.6f, r * 0.7f, col)
            blob(c, x - r * 0.7f, y + r * 0.15f, r, r * 0.55f, col)
            blob(c, x + r * 0.8f, y + r * 0.1f, r * 1.1f, r * 0.6f, col)
        }

        // fog: wide bands of mist rolling slowly
        if (kind == WX_FOG) for (i in 0 until 5) {
            val x = (wrap(seeds[(i + 90) * 4] + secs * 0.004f * (if (i % 2 == 0) 1f else -1f)) * 1.6f - 0.3f) * w
            blob(c, x, h * (0.2f + 0.17f * i), w * 0.6f, h * 0.12f, Color.argb(if (day) 70 else 40, 220, 225, 230))
        }

        // rain: streaks falling on a slant (more slant when it's windy); storms flash now and then
        if (kind == WX_RAIN || kind == WX_STORM) {
            fxPaint.style = Paint.Style.STROKE; fxPaint.strokeWidth = h * 0.0025f; fxPaint.color = Color.rgb(190, 210, 235)
            val slant = 0.15f * wind
            for (i in 0 until 90) {
                val sp = 0.9f + 0.6f * seeds[i * 4 + 3]
                val y = wrap(seeds[i * 4 + 1] + secs * sp) * (h * 1.1f) - h * 0.05f
                val x = wrap(seeds[i * 4] + y / h * slant * 0.3f) * w
                val len = h * (0.03f + 0.03f * seeds[i * 4 + 2])
                fxPaint.alpha = (50 + 70 * seeds[i * 4 + 2]).toInt()
                c.drawLine(x, y, x - len * slant, y + len, fxPaint)
            }
            if (kind == WX_STORM) {
                val ph = secs % 23f
                if (ph < 0.12f || ph in 0.25f..0.32f) { fxPaint.style = Paint.Style.FILL; fxPaint.color = Color.WHITE; fxPaint.alpha = 70; c.drawRect(0f, 0f, w, h, fxPaint) }
            }
        }

        // snow: flakes falling softly, wobbling side to side
        if (kind == WX_SNOW || kind == -1) {
            fxPaint.style = Paint.Style.FILL; fxPaint.color = Color.WHITE
            val n = if (kind == WX_SNOW) 80 else 25
            for (i in 0 until n) {
                val sp = 0.035f + 0.05f * seeds[i * 4 + 3]
                val y = wrap(seeds[i * 4 + 1] + secs * sp) * (h * 1.1f) - h * 0.05f
                val x = wrap(seeds[i * 4] + sin(secs * 0.8f + i) * 0.01f + secs * 0.002f * (wind - 1f)) * w
                fxPaint.alpha = (90 + 120 * seeds[i * 4 + 2]).toInt()
                c.drawCircle(x, y, h * (0.002f + 0.004f * seeds[i * 4 + 2]), fxPaint)
            }
        }

        // ---- the clock and date, top left
        val pad = w * 0.044f
        val cal = java.util.Calendar.getInstance()
        val clock = String.format("%d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        wxText.textAlign = Paint.Align.LEFT; wxText.color = Color.WHITE; wxText.alpha = 245
        wxText.typeface = thinFace; wxText.textSize = h * 0.29f
        val clockBase = pad + h * 0.24f
        c.drawText(clock, pad - h * 0.01f, clockBase, wxText)
        lastClockW = wxText.measureText(clock)
        wxText.typeface = mediumFace; wxText.textSize = h * 0.04f; wxText.alpha = 205
        val date = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.UK).format(cal.time)
        c.drawText(date, pad, clockBase + h * 0.075f, wxText)
        var noteY = clockBase + h * 0.13f
        // cuppas made today (kettle boils Pip heard; the day starts at 5am)
        wxText.typeface = mediumFace; wxText.textSize = h * 0.034f; wxText.alpha = 225
        c.drawText(when (cuppas) { 0 -> "☕ No cuppas yet today"; 1 -> "☕ 1 cuppa today"; else -> "☕ $cuppas cuppas today" }, pad, noteY, wxText)
        noteY += h * 0.055f
        wxText.typeface = lightFace; wxText.textSize = h * 0.028f
        if (napNote.isNotEmpty()) { wxText.alpha = 170; c.drawText("😴 $napNote", pad, noteY, wxText); noteY += h * 0.045f }
        if (plugNote.isNotEmpty()) { wxText.alpha = (120 + 90 * sin(secs * 2.5f)).toInt().coerceIn(20, 230); c.drawText(plugNote, pad, noteY, wxText) }

        // ---- weather and Christmas cards, bottom left
        val colR = if (todoHidden) w - pad else w * 0.56f
        val cardTop = h * 0.585f; val cardBot = h * 0.85f; val gapC = w * 0.016f
        val xd = xmasDays()
        val showXmas = xd in 0..120
        val wR = if (showXmas) pad + (colR - pad - gapC) / 2 else colR
        card(c, pad, cardTop, wR, cardBot, h)
        val ix = pad + h * 0.075f; val iy = cardTop + h * 0.085f
        drawWxIcon(c, if (kind < 0) WX_CLOUDY else kind, day, ix, iy, h * 0.05f)
        wxText.typeface = lightFace; wxText.alpha = 255; wxText.textSize = h * 0.085f
        c.drawText(if (wxTemp.isEmpty()) "–°" else wxTemp, ix + h * 0.075f, iy + h * 0.03f, wxText)
        wxText.typeface = boldFace; wxText.textSize = h * 0.032f; wxText.alpha = 240
        c.drawText(fitW(if (wxDesc.isEmpty()) "Getting the weather…" else wxDesc, wxText, wR - pad - h * 0.06f), pad + h * 0.03f, cardTop + h * 0.185f, wxText)
        wxText.typeface = lightFace; wxText.textSize = h * 0.025f; wxText.alpha = 200
        if (wxLine.isNotEmpty()) c.drawText(fitW(wxLine, wxText, wR - pad - h * 0.06f), pad + h * 0.03f, cardTop + h * 0.228f, wxText)
        if (showXmas) {
            val x0 = wR + gapC
            card(c, x0, cardTop, colR, cardBot, h)
            drawTree(c, x0 + h * 0.075f, cardTop + h * 0.085f, h * 0.055f)
            wxText.typeface = lightFace; wxText.alpha = 255; wxText.textSize = h * 0.085f
            c.drawText(if (xd == 0) "🎅" else "$xd", x0 + h * 0.15f, cardTop + h * 0.115f, wxText)
            wxText.typeface = boldFace; wxText.textSize = h * 0.032f; wxText.alpha = 240
            c.drawText(when (xd) { 0 -> "Merry Christmas!"; 1 -> "sleep till Christmas"; else -> "sleeps till Christmas" }, x0 + h * 0.03f, cardTop + h * 0.185f, wxText)
            wxText.typeface = lightFace; wxText.textSize = h * 0.025f; wxText.alpha = 200
            val xmasDate = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, xd) }
            c.drawText(java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.UK).format(xmasDate.time), x0 + h * 0.03f, cardTop + h * 0.228f, wxText)
        }

        // ---- the to-do list, right (or just a small "Show to-do" button when it's hidden)
        choreShowRect.setEmpty()
        if (todoHidden) {
            choreRows.clear(); choreTabRect.setEmpty(); choreAddRect.setEmpty(); choreHideRect.setEmpty()
            choreMoreRect.setEmpty(); chorePanelRect.setEmpty()
            chorePaint.typeface = mediumFace; chorePaint.textSize = h * 0.026f; chorePaint.color = Color.WHITE; chorePaint.alpha = 150
            chorePaint.textAlign = Paint.Align.LEFT
            val label = "Show to-do"; val ap = h * 0.024f; val tw = chorePaint.measureText(label)
            choreShowRect.set(w - pad - tw - ap * 2, pad, w - pad, pad + h * 0.06f)
            choreBox.style = Paint.Style.STROKE; choreBox.strokeWidth = h * 0.002f; choreBox.color = Color.argb(70, 255, 255, 255)
            c.drawRoundRect(choreShowRect, choreShowRect.height() / 2, choreShowRect.height() / 2, choreBox)
            c.drawText(label, choreShowRect.left + ap, choreShowRect.centerY() + chorePaint.textSize * 0.35f, chorePaint)
        } else drawChores(c, w * 0.6f, h * 0.07f, w - pad, h * 0.78f, h)

        // VoiceID button: small, bottom middle
        run {
            val label = "🎙️ VoiceID Live"
            chipPaint.textSize = h * 0.028f; chipPaint.color = Color.WHITE; chipPaint.alpha = 120
            val p = h * 0.02f; val tw = chipPaint.measureText(label)
            trainRect.set(w * 0.3f, h * 0.9f, w * 0.3f + tw + p * 2, h * 0.9f + chipPaint.textSize + p * 1.6f)
            chipBox.color = Color.WHITE; chipBox.alpha = 50; chipBox.strokeWidth = h * 0.003f
            c.drawRoundRect(trainRect, trainRect.height() / 2, trainRect.height() / 2, chipBox)
            c.drawText(label, trainRect.left + p, trainRect.centerY() + chipPaint.textSize * 0.35f, chipPaint)
        }
    }

    private fun card(c: Canvas, l: Float, t: Float, r: Float, b: Float, h: Float) {
        rr.set(l, t, r, b); val rad = h * 0.04f
        c.drawRoundRect(rr, rad, rad, cardFill)
        cardLine.strokeWidth = h * 0.002f; c.drawRoundRect(rr, rad, rad, cardLine)
    }
    private fun fitW(text: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(text) <= maxW) return text
        var t = text
        while (t.length > 1 && paint.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
    /** Days until the next Christmas Day (0 on the day). */
    private fun xmasDays(): Int {
        val today = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 12); set(java.util.Calendar.MINUTE, 0) }
        val xmas = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.MONTH, java.util.Calendar.DECEMBER); set(java.util.Calendar.DAY_OF_MONTH, 25)
            set(java.util.Calendar.HOUR_OF_DAY, 12); set(java.util.Calendar.MINUTE, 0)
        }
        if (xmas.before(today) && today.get(java.util.Calendar.DAY_OF_YEAR) != xmas.get(java.util.Calendar.DAY_OF_YEAR)) xmas.add(java.util.Calendar.YEAR, 1)
        return Math.round((xmas.timeInMillis - today.timeInMillis) / 86_400_000.0).toInt()
    }
    private fun drawTree(c: Canvas, x: Float, y: Float, s: Float) {
        val p = android.graphics.Path()
        p.moveTo(x, y - s); p.lineTo(x + s * 0.45f, y - s * 0.35f); p.lineTo(x + s * 0.25f, y - s * 0.35f)
        p.lineTo(x + s * 0.7f, y + s * 0.3f); p.lineTo(x + s * 0.4f, y + s * 0.3f); p.lineTo(x + s * 0.85f, y + s * 0.85f)
        p.lineTo(x - s * 0.85f, y + s * 0.85f); p.lineTo(x - s * 0.4f, y + s * 0.3f); p.lineTo(x - s * 0.7f, y + s * 0.3f)
        p.lineTo(x - s * 0.25f, y - s * 0.35f); p.lineTo(x - s * 0.45f, y - s * 0.35f); p.close()
        fxPaint.style = Paint.Style.FILL; fxPaint.color = Color.parseColor("#34D399"); c.drawPath(p, fxPaint)
        fxPaint.color = Color.parseColor("#A16207"); c.drawRect(x - s * 0.12f, y + s * 0.85f, x + s * 0.12f, y + s * 1.15f, fxPaint)
        fxPaint.color = Color.parseColor("#FCD34D"); c.drawCircle(x, y - s, s * 0.13f, fxPaint)
        fxPaint.color = Color.parseColor("#F87171"); c.drawCircle(x - s * 0.2f, y - s * 0.05f, s * 0.08f, fxPaint)
        fxPaint.color = Color.parseColor("#FCD34D"); c.drawCircle(x + s * 0.25f, y + s * 0.5f, s * 0.08f, fxPaint)
        fxPaint.color = Color.parseColor("#93C5FD"); c.drawCircle(x - s * 0.3f, y + s * 0.65f, s * 0.08f, fxPaint)
    }
    private fun drawCloud(c: Canvas, x: Float, y: Float, s: Float, col: Int) {
        fxPaint.style = Paint.Style.FILL; fxPaint.color = col
        c.drawCircle(x - s * 0.4f, y + s * 0.1f, s * 0.42f, fxPaint)
        c.drawCircle(x + s * 0.1f, y - s * 0.15f, s * 0.55f, fxPaint)
        c.drawCircle(x + s * 0.6f, y + s * 0.12f, s * 0.4f, fxPaint)
        rr.set(x - s * 0.8f, y + s * 0.1f, x + s * 0.98f, y + s * 0.52f); c.drawRoundRect(rr, s * 0.21f, s * 0.21f, fxPaint)
    }
    private fun drawSun(c: Canvas, x: Float, y: Float, s: Float) {
        fxPaint.color = Color.parseColor("#FCD34D"); fxPaint.style = Paint.Style.FILL
        c.drawCircle(x, y, s * 0.42f, fxPaint)
        fxPaint.style = Paint.Style.STROKE; fxPaint.strokeWidth = s * 0.1f
        for (k in 0 until 8) {
            val a = Math.toRadians(k * 45.0); val cx = cos(a).toFloat(); val sy = sin(a).toFloat()
            c.drawLine(x + cx * s * 0.6f, y + sy * s * 0.6f, x + cx * s * 0.82f, y + sy * s * 0.82f, fxPaint)
        }
    }
    private fun drawMoon(c: Canvas, x: Float, y: Float, s: Float, behind: Int) {
        fxPaint.style = Paint.Style.FILL; fxPaint.color = Color.parseColor("#FDE68A"); c.drawCircle(x, y, s * 0.5f, fxPaint)
        fxPaint.color = behind; c.drawCircle(x + s * 0.25f, y - s * 0.18f, s * 0.42f, fxPaint)
    }
    /** A little weather picture, about 2s wide, centred on (x, y). */
    private fun drawWxIcon(c: Canvas, kind: Int, day: Boolean, x: Float, y: Float, s: Float) {
        val behind = Color.parseColor("#2A3446")        // roughly the card colour, for the moon's bite
        val grey = Color.parseColor("#E2E8F0"); val dark = Color.parseColor("#94A3B8")
        when (kind) {
            WX_CLEAR -> if (day) drawSun(c, x, y, s) else drawMoon(c, x, y, s * 1.4f, behind)
            WX_PARTLY -> { if (day) drawSun(c, x - s * 0.35f, y - s * 0.35f, s * 0.9f) else drawMoon(c, x - s * 0.3f, y - s * 0.35f, s, behind)
                drawCloud(c, x + s * 0.15f, y + s * 0.2f, s * 0.75f, grey) }
            WX_CLOUDY -> { drawCloud(c, x - s * 0.3f, y - s * 0.25f, s * 0.6f, dark); drawCloud(c, x + s * 0.1f, y + s * 0.1f, s * 0.75f, grey) }
            WX_FOG -> { fxPaint.style = Paint.Style.STROKE; fxPaint.color = grey; fxPaint.strokeWidth = s * 0.14f
                for (k in 0 until 3) c.drawLine(x - s * (0.8f - 0.15f * (k % 2)), y - s * 0.4f + k * s * 0.4f, x + s * (0.8f - 0.15f * ((k + 1) % 2)), y - s * 0.4f + k * s * 0.4f, fxPaint) }
            WX_RAIN, WX_STORM -> {
                drawCloud(c, x, y - s * 0.3f, s * 0.75f, grey)
                if (kind == WX_STORM) { fxPaint.style = Paint.Style.FILL; fxPaint.color = Color.parseColor("#FCD34D")
                    val p = android.graphics.Path(); p.moveTo(x + s * 0.05f, y + s * 0.2f); p.lineTo(x - s * 0.25f, y + s * 0.65f)
                    p.lineTo(x, y + s * 0.6f); p.lineTo(x - s * 0.15f, y + s * 1.0f); p.lineTo(x + s * 0.25f, y + s * 0.5f); p.lineTo(x, y + s * 0.52f); p.close(); c.drawPath(p, fxPaint) }
                else { fxPaint.style = Paint.Style.STROKE; fxPaint.color = Color.parseColor("#93C5FD"); fxPaint.strokeWidth = s * 0.1f
                    for (k in 0 until 3) { val dx = x - s * 0.45f + k * s * 0.45f; c.drawLine(dx, y + s * 0.42f, dx - s * 0.12f, y + s * 0.75f, fxPaint) } }
            }
            WX_SNOW -> { drawCloud(c, x, y - s * 0.3f, s * 0.75f, grey); fxPaint.style = Paint.Style.FILL; fxPaint.color = Color.WHITE
                for (k in 0 until 3) c.drawCircle(x - s * 0.45f + k * s * 0.45f, y + s * (0.55f + 0.15f * (k % 2)), s * 0.1f, fxPaint) }
        }
    }

    private fun drawChores(c: Canvas, x0: Float, top: Float, x1: Float, bottom: Float, h: Float) {
        choreRows.clear(); choreTabRect.setEmpty(); choreMoreRect.setEmpty()
        chorePanelRect.set(x0, top, x1, bottom)
        card(c, x0, top, x1, bottom, h)
        if (choreOffset >= chores.size) choreOffset = 0
        val ip = h * 0.035f
        val l = x0 + ip; val r = x1 - ip
        var y = top + ip
        // header: "To do", how many left, and the + Add button
        val left = chores.count { !it.done } + (if (tabletsCard.isNotEmpty()) 1 else 0)
        chorePaint.textAlign = Paint.Align.LEFT; chorePaint.color = Color.WHITE
        chorePaint.typeface = boldFace; chorePaint.textSize = h * 0.042f; chorePaint.alpha = 250
        c.drawText("To do", l, y + h * 0.04f, chorePaint)
        chorePaint.typeface = lightFace; chorePaint.textSize = h * 0.022f; chorePaint.alpha = 190
        c.drawText(if (left == 0) "All done" else "$left left · tap to tick", l, y + h * 0.075f, chorePaint)
        chorePaint.typeface = mediumFace; chorePaint.textSize = h * 0.026f; chorePaint.alpha = 250
        val addLabel = "+ Add"; val ap = h * 0.026f
        val aw = chorePaint.measureText(addLabel) + ap * 2
        choreAddRect.set(r - aw, y + h * 0.005f, r, y + h * 0.065f)
        choreBox.style = Paint.Style.FILL; choreBox.color = Color.argb(34, 255, 255, 255)
        c.drawRoundRect(choreAddRect, choreAddRect.height() / 2, choreAddRect.height() / 2, choreBox)
        choreBox.style = Paint.Style.STROKE; choreBox.strokeWidth = h * 0.002f; choreBox.color = Color.argb(80, 255, 255, 255)
        c.drawRoundRect(choreAddRect, choreAddRect.height() / 2, choreAddRect.height() / 2, choreBox)
        c.drawText(addLabel, choreAddRect.left + ap, choreAddRect.centerY() + chorePaint.textSize * 0.35f, chorePaint)
        val hideLabel = "Hide"; val hw = chorePaint.measureText(hideLabel) + ap * 2
        choreHideRect.set(choreAddRect.left - h * 0.015f - hw, choreAddRect.top, choreAddRect.left - h * 0.015f, choreAddRect.bottom)
        choreBox.style = Paint.Style.STROKE; choreBox.strokeWidth = h * 0.002f; choreBox.color = Color.argb(60, 255, 255, 255)
        c.drawRoundRect(choreHideRect, choreHideRect.height() / 2, choreHideRect.height() / 2, choreBox)
        chorePaint.alpha = 190
        c.drawText(hideLabel, choreHideRect.left + ap, choreHideRect.centerY() + chorePaint.textSize * 0.35f, chorePaint)
        y += h * 0.1f
        val rowH = h * 0.072f; val gapR = h * 0.012f; val box = h * 0.034f; val rad = h * 0.022f
        // Laura's tablets: a gold card until it's ticked
        if (tabletsCard.isNotEmpty()) {
            choreTabRect.set(l, y, r, y + rowH)
            choreBox.style = Paint.Style.FILL; choreBox.color = Color.argb(40, 251, 191, 36)
            c.drawRoundRect(choreTabRect, rad, rad, choreBox)
            choreBox.style = Paint.Style.STROKE; choreBox.strokeWidth = h * 0.003f; choreBox.color = Color.parseColor("#FBBF24")
            choreBox.alpha = (190 + 60 * sin(now() / 500.0)).toInt().coerceIn(0, 255)
            c.drawRoundRect(choreTabRect, rad, rad, choreBox)
            val bx = l + h * 0.022f; val by = choreTabRect.centerY() - box / 2
            choreBox.alpha = 255; rr.set(bx, by, bx + box, by + box); c.drawRoundRect(rr, box * 0.28f, box * 0.28f, choreBox)
            chorePaint.typeface = mediumFace; chorePaint.textSize = h * 0.031f; chorePaint.color = Color.WHITE; chorePaint.alpha = 255
            val tx = bx + box + h * 0.022f
            c.drawText(fitW(tabletsCard, chorePaint, r - tx - h * 0.02f), tx, choreTabRect.centerY() + chorePaint.textSize * 0.35f, chorePaint)
            y += rowH + gapR
        }
        for (ch in chores.drop(choreOffset)) {
            if (y + rowH > bottom - ip * 0.5f) {
                drawMore(c, "+${chores.size - choreOffset - choreRows.size} more  ↓", l, y, h); break
            }
            val row = RectF(l, y, r, y + rowH); choreRows.add(row)
            if (!ch.done) { choreBox.style = Paint.Style.FILL; choreBox.color = Color.argb(16, 255, 255, 255); c.drawRoundRect(row, rad, rad, choreBox) }
            val bx = l + h * 0.022f; val by = row.centerY() - box / 2
            rr.set(bx, by, bx + box, by + box)
            if (ch.done) {
                choreBox.style = Paint.Style.FILL; choreBox.color = Color.parseColor("#34D399"); choreBox.alpha = 170
                c.drawRoundRect(rr, box * 0.28f, box * 0.28f, choreBox)
                choreBox.style = Paint.Style.STROKE; choreBox.strokeWidth = h * 0.005f; choreBox.color = Color.parseColor("#0A1324")
                c.drawLine(bx + box * 0.22f, by + box * 0.52f, bx + box * 0.43f, by + box * 0.73f, choreBox)
                c.drawLine(bx + box * 0.43f, by + box * 0.73f, bx + box * 0.8f, by + box * 0.3f, choreBox)
            } else {
                choreBox.style = Paint.Style.STROKE; choreBox.strokeWidth = h * 0.003f; choreBox.color = Color.argb(160, 255, 255, 255)
                c.drawRoundRect(rr, box * 0.28f, box * 0.28f, choreBox)
            }
            val tx = bx + box + h * 0.022f
            chorePaint.typeface = if (ch.done) lightFace else mediumFace; chorePaint.textSize = h * 0.03f
            chorePaint.color = Color.WHITE; chorePaint.alpha = if (ch.done) 140 else 245
            chorePaint.isStrikeThruText = ch.done
            c.drawText(fitW(ch.text, chorePaint, r - tx - h * 0.02f), tx, row.centerY() + chorePaint.textSize * 0.35f, chorePaint)
            chorePaint.isStrikeThruText = false
            y += rowH + gapR
        }
        if (choreOffset > 0 && choreMoreRect.isEmpty) drawMore(c, "↑ Back to the top", l, y, h)
        if (chores.isEmpty() && tabletsCard.isEmpty()) {
            chorePaint.typeface = lightFace; chorePaint.textSize = h * 0.026f; chorePaint.color = Color.WHITE; chorePaint.alpha = 160
            c.drawText("Nothing to do. Tap + Add", l + h * 0.01f, y + h * 0.03f, chorePaint)
        }
    }

    private fun drawMore(c: Canvas, label: String, l: Float, y: Float, h: Float) {
        chorePaint.typeface = mediumFace; chorePaint.textSize = h * 0.026f; chorePaint.color = Color.WHITE; chorePaint.alpha = 200
        chorePaint.textAlign = Paint.Align.LEFT
        val ap = h * 0.024f; val tw = chorePaint.measureText(label)
        choreMoreRect.set(l, y + h * 0.004f, l + tw + ap * 2, y + h * 0.058f)
        choreBox.style = Paint.Style.STROKE; choreBox.strokeWidth = h * 0.002f; choreBox.color = Color.argb(70, 255, 255, 255)
        c.drawRoundRect(choreMoreRect, choreMoreRect.height() / 2, choreMoreRect.height() / 2, choreBox)
        c.drawText(label, choreMoreRect.left + ap, choreMoreRect.centerY() + chorePaint.textSize * 0.35f, chorePaint)
    }
    /** What was tapped on the chore board: a chore's index, ADD, TABLETS, or -1 for nothing. */
    fun choreHit(x0: Float, y0: Float): Int {
        if (mood != Mood.SLEEP || !sleepScreen) return -1
        val pt = toV(x0, y0); val x = pt[0]; val y = pt[1]
        val m = height * 0.02f
        fun inR(r: RectF) = !r.isEmpty && x >= r.left - m && x <= r.right + m && y >= r.top - m * 0.3f && y <= r.bottom + m * 0.3f
        if (inR(choreShowRect)) return CHORE_SHOW
        if (inR(choreMoreRect)) return CHORE_MORE
        if (inR(choreAddRect)) return CHORE_ADD
        if (inR(choreHideRect)) return CHORE_HIDE
        if (inR(choreTabRect)) return CHORE_TABLETS
        choreRows.forEachIndexed { i, r -> if (x >= r.left - m && x <= r.right + m && y >= r.top && y < r.bottom) return choreOffset + i }
        if (!chorePanelRect.isEmpty && !todoHidden && x >= chorePanelRect.left && x <= chorePanelRect.right && y >= chorePanelRect.top && y <= chorePanelRect.bottom) return CHORE_NONE
        return -1
    }

    /** VoiceID Live button, top-right of the sleep screen. */
    private val trainRect = RectF()
    fun trainHit(x0: Float, y0: Float): Boolean {
        val pt = toV(x0, y0); val x = pt[0]; val y = pt[1]
        if (!sleepScreen || trainRect.isEmpty) return false
        val m = height * 0.04f
        return x >= trainRect.left - m && x <= trainRect.right + m && y >= trainRect.top - m && y <= trainRect.bottom + m
    }
    /** Volume button, just above the wake button (sleep screen only). */
    private val volRect = RectF()
    fun volHit(x0: Float, y0: Float): Boolean {
        val pt = toV(x0, y0); val x = pt[0]; val y = pt[1]
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
    fun zipHit(x0: Float, y0: Float): Boolean {
        val pt = toV(x0, y0); val x = pt[0]; val y = pt[1]
        if (!muted || zipRight <= zipLeft) return false
        val m = height * 0.06f
        return x >= zipLeft - m && x <= zipRight + m && y >= zipY - m && y <= zipY + m * 2.2f
    }
    private val muteRect = RectF()
    private val r0 = RectF()
    fun muteHit(x0: Float, y0: Float): Boolean {
        val pt = toV(x0, y0); val x = pt[0]; val y = pt[1]
        if (muteRect.isEmpty) return false
        val m = height * 0.02f
        val hit = x >= muteRect.left - m && x <= muteRect.right + m && y >= muteRect.top - m && y <= muteRect.bottom + m
        if (hit) chipTappedAt = now()
        return hit
    }
    /** The sleep / wake button, bottom-right ("" hides it). */
    var sleepChip = ""
    private val sleepRect = RectF()
    fun sleepHit(x0: Float, y0: Float): Boolean {
        val pt = toV(x0, y0); val x = pt[0]; val y = pt[1]
        if (sleepChip.isEmpty() || sleepRect.isEmpty) return false
        val m = height * 0.04f
        val hit = x >= sleepRect.left - m && x <= sleepRect.right + m && y >= sleepRect.top - m && y <= sleepRect.bottom + m
        if (hit) chipTappedAt = now()
        return hit
    }
    /** Was the button tapped? (generous target, easy to hit) */
    fun chipHit(x0: Float, y0: Float): Boolean {
        val pt = toV(x0, y0); val x = pt[0]; val y = pt[1]
        if (listenChip.isEmpty() || chipRect.isEmpty) return false
        val m = height * 0.04f
        val hit = x >= chipRect.left - m && x <= chipRect.right + m && y >= chipRect.top - m && y <= chipRect.bottom + m
        if (hit) chipTappedAt = now()
        return hit
    }

    // ---- mouth: a glowing sound wave under the eyes. Nearly flat when quiet, it ripples when she talks,
    // and the whole line bends into a smile or frown with her mood. Everything is smoothed so it melts between shapes.
    private var wAmp = 0.02f     // how tall the ripples are, in eye widths
    private var wCurve = 0.03f   // + = smile, - = frown
    private var wWidth = 1f      // 1 = full width
    private var wSpeed = 1f
    private var wSkew = 0f
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val wavePath = android.graphics.Path()

    private fun drawMouth(c: Canvas, x: Float, y: Float, eyeW: Float, secs: Float, dt: Float, feeling: Emotion, col: Int) {
        var amp = 0.015f; var curve = 0.03f; var width = 1f; var speed = 1f; var skew = 0f
        when (feeling) {
            Emotion.HAPPY, Emotion.LOVE, Emotion.WINK -> curve = 0.1f
            Emotion.EXCITED -> { curve = 0.1f; amp = 0.07f; speed = 2.2f }
            Emotion.LAUGHING -> { curve = 0.11f; amp = 0.04f + 0.08f * abs(sin(secs * 14f)); speed = 2.5f }
            Emotion.SNIGGER, Emotion.STONED -> { curve = 0.06f; skew = 0.06f; width = 0.8f }
            Emotion.SAD, Emotion.CRYING -> { curve = -0.09f; width = 0.85f; speed = 0.5f }
            Emotion.ANGRY -> { curve = -0.05f; amp = 0.035f; speed = 3f; width = 0.85f }           // tight, buzzing
            Emotion.SURPRISED -> { curve = 0f; amp = 0.09f; width = 0.45f; speed = 1.5f }
            Emotion.SCARED -> { curve = -0.03f; amp = 0.05f; speed = 3.5f; width = 0.7f }          // trembling
            Emotion.CONFUSED, Emotion.SUSPICIOUS -> { curve = 0f; skew = 0.08f; width = 0.8f }
            Emotion.DIZZY -> { curve = 0f; skew = 0.08f * sin(secs * 4f); amp = 0.05f; speed = 0.7f }
            Emotion.THINKING, Emotion.CURIOUS -> { curve = 0f; width = 0.5f; amp = 0.03f; speed = 0.8f }
            Emotion.BORED, Emotion.SLEEPY -> { curve = -0.01f; width = 0.7f; amp = 0.006f; speed = 0.3f }
            Emotion.EMBARRASSED -> { curve = 0.05f; width = 0.6f }
            Emotion.HOT -> { curve = -0.03f; amp = 0.04f; speed = 0.6f; width = 0.8f }
            Emotion.NEUTRAL -> {}
        }
        if (mood == Mood.THINK) { curve = 0f; width = 0.5f; amp = 0.03f; speed = 0.8f }
        if (mood == Mood.LISTEN) amp = max(amp, 0.012f + level * 0.08f)          // listening: the wave follows the room's sound
        val talking = mood == Mood.TALK
        val real = if (talking) voiceLevel() else -1f
        if (talking && real >= 0f) {
            // the real voice: the wave swells on loud words and flattens in the gaps
            amp = max(amp, 0.015f + 0.19f * real)
            curve = curve.coerceIn(-0.03f, 0.04f); width = max(width, 0.9f); speed = max(speed, 2f)
        } else if (talking) {
            // the tablet's own voice gives no sound to measure, so the loudness is made up: uneven syllables with little gaps
            val syl = abs(sin(secs * 10.5f + 1.8f * sin(secs * 2.3f)))
            val word = (0.55f + 0.45f * sin(secs * 3.1f + 0.7f * sin(secs * 1.3f))).coerceIn(0f, 1f) *
                (if (sin(secs * 1.7f) > 0.85f) 0.1f else 1f)
            amp = max(amp, 0.025f + 0.15f * syl * word)
            curve = curve.coerceIn(-0.03f, 0.04f); width = max(width, 0.9f); speed = max(speed, 2f)
        }
        wAmp += (amp - wAmp) * min(1f, dt * (if (talking) 20f else 6f))
        wCurve += (curve - wCurve) * min(1f, dt * 6f)
        wWidth += (width - wWidth) * min(1f, dt * 6f)
        wSpeed += (speed - wSpeed) * min(1f, dt * 4f)
        wSkew += (skew - wSkew) * min(1f, dt * 5f)
        wPhase += dt * wSpeed

        val half = eyeW * 0.55f * wWidth
        // three waves on top of each other: a bright main one and two fainter ones drifting at different speeds
        for (layer in 2 downTo 0) {
            wavePath.reset()
            val steps = 48
            for (j in 0..steps) {
                val u = j / steps.toFloat() * 2f - 1f                // -1..1 across the mouth
                val env = (1f - u * u).let { it * it }              // pinched to nothing at the ends
                val ph = wPhase * (6f + layer * 1.7f)
                val ripple = sin(u * (9f + layer * 3f) + ph) * 0.7f + sin(u * (17f - layer * 4f) - ph * 1.3f) * 0.3f
                val px = x + u * half
                val py = y + (-wCurve * u * u + wSkew * u) * eyeW +             // ends lift for a smile, drop for a frown
                    ripple * env * wAmp * eyeW * (if (layer == 0) 1f else 0.75f - layer * 0.15f) * (if (layer == 1) -1f else 1f)
                if (j == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
            }
            wavePaint.color = col
            if (layer == 0) {
                wavePaint.alpha = 50; wavePaint.strokeWidth = eyeW * 0.13f; c.drawPath(wavePath, wavePaint)      // glow
                wavePaint.alpha = 240; wavePaint.strokeWidth = eyeW * 0.05f
            } else { wavePaint.alpha = 90 - layer * 25; wavePaint.strokeWidth = eyeW * 0.028f }
            c.drawPath(wavePath, wavePaint)
        }
    }
    private var wPhase = 0f

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
    private fun followMotion() {
        // the camera turns with the screen: turn where the movement is into the upright frame
        val th = Math.toRadians(frameAng.toDouble()); val c = Math.cos(th).toFloat(); val sn = Math.sin(th).toFloat()
        val mx = motionX * c - motionY * sn; val my = motionX * sn + motionY * c
        tLookX = mx * 1.2f; tLookY = (my * 0.45f + UP).coerceIn(-1f, 0.6f)
    }

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
