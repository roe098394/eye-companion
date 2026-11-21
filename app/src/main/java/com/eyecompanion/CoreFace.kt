package com.eyecompanion

import android.content.res.Resources
import android.graphics.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The "AI core" face: Mark's picture of a glass robot with a glowing core, brought to life.
 * The core is her eye (it drifts towards people, pulses, blinks, swells with her voice, goes red when she's
 * sinister), thin rings of light spin over it, the jaw talks, the head turns and nods on the neck,
 * energy runs down the cables, she breathes, and one of the screens behind her shows the 3D printer's camera.
 * Everything is drawn in the picture's own pixels (1288 x 703).
 */
class CoreFace(private val res: Resources) {
    private var bmp: Bitmap? = null
    private var mask: Bitmap? = null
    @Volatile private var maskReady = false

    /** Latest picture from the printer's camera, shown on the screen bottom-left behind her (null = leave the screen as it is). */
    @Volatile var printerCam: Bitmap? = null
    /** When there's no picture: why (shown small on the screen), or "" to leave the screen as it is. */
    @Volatile var printerNote = ""
    private val camRect = RectF()
    private val noteText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 190, 225, 255); textSize = 11f }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val add = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(floatArrayOf(
            0.55f, 0.75f, 0.45f, 0f, 20f,
            0.05f, 0.08f, 0.05f, 0f, 0f,
            0.08f, 0.10f, 0.08f, 0f, 5f,
            0f, 0f, 0f, 1f, 0f))
    }
    private val camPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(floatArrayOf(       // a cool blue screen tint, to sit in the room
            0.75f, 0f, 0f, 0f, 0f,
            0f, 0.88f, 0f, 0f, 8f,
            0f, 0f, 1.0f, 0f, 22f,
            0f, 0f, 0f, 1f, 0f))
        alpha = 215
    }
    private val src = Rect(); private val dst = RectF(); private val arc = RectF()
    private val m = Matrix()
    private var jaw = 0f; private var lv = 0f; private var ringA = 0f; private var ringB = 0f

    private val CX = 645f; private val CY = 262f; private val PX = 645f; private val PY = 530f
    private val HEAD = floatArrayOf(470f, 0f, 820f, 0f, 840f, 300f, 800f, 440f, 730f, 520f, 690f, 560f, 600f, 560f, 560f, 520f, 490f, 440f, 450f, 300f)
    private val headPath = Path().apply {
        moveTo(HEAD[0], HEAD[1]); var k = 2; while (k < HEAD.size) { lineTo(HEAD[k], HEAD[k + 1]); k += 2 }; close() }
    private val SCREEN = RectF(182f, 422f, 377f, 553f)       // the monitor bottom-left, behind her shoulder

    private fun load(): Bitmap? {
        bmp?.let { return it }
        val b = try { BitmapFactory.decodeResource(res, R.drawable.robot_core) } catch (_: Throwable) { null } ?: return null
        bmp = b
        Thread {                                              // which pixels are glowing cables (for the energy flow)
            try {
                val w = b.width; val h = b.height
                val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
                val a = ByteArray(w * h)
                for (i in px.indices) {
                    val x = i % w; val y = i / w
                    if (x < 380 || x > 910 || y < 60) continue
                    val c = px[i]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
                    if ((bl - r > 55 && bl > 150) || (r > 170 && g > 100 && bl < 110) || (r > 150 && bl > 170 && g < 120)) a[i] = -1
                }
                val mk = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
                mk.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(a))
                mask = mk; maskReady = true
            } catch (_: Throwable) {}
        }.start()
        return b
    }

    /**
     * lx, ly: where she's looking (-1..1). openness: 1 open, 0 mid-blink. talk: 0..1 voice level.
     * red: 0..1 sinister glow. thinking: rings whirl. t: milliseconds.
     */
    fun draw(c: Canvas, w: Float, h: Float, lx0: Float, ly0: Float, openness: Float, talk: Float, red: Float,
             thinking: Boolean, dt: Float, t: Long) {
        val b = load() ?: return
        val lx = lx0.coerceIn(-1.3f, 1.3f); val ly = ly0.coerceIn(-1f, 1f)
        val secs = (t % 3_600_000L) / 1000f
        lv += (talk.coerceIn(0f, 1f) - lv) * min(1f, dt * 14f)
        ringA += ((if (thinking) 3.0f else 0.5f) + lv * 1.8f) * dt
        ringB -= ((if (thinking) 4.8f else 0.7f) + lv * 2.4f) * dt
        val open = (0.15f + 0.85f * openness).coerceIn(0f, 1f)

        // fill the screen (the picture is wide), breathing gently
        val s = max(w / b.width, h / b.height)
        val breathe = 1f + sin(secs * 0.72f) * 0.006f
        c.save()
        c.translate(w / 2, h / 2); c.scale(s * breathe, s * breathe); c.translate(-b.width / 2f, -b.height / 2f)
        c.drawBitmap(b, 0f, 0f, p)

        // the printer's camera on the screen behind her, with faint scanlines
        // (if the tablet's shape crops that monitor off the edge, the picture floats just inside the edge instead)
        val vw = w / (s * breathe); val vh = h / (s * breathe)
        val vx0 = b.width / 2f - vw / 2; val vy1 = b.height / 2f + vh / 2
        camRect.set(SCREEN)
        if (camRect.left < vx0 + 8f) camRect.offset(vx0 + 8f - camRect.left, 0f)
        if (camRect.bottom > vy1 - 8f) camRect.offset(0f, vy1 - 8f - camRect.bottom)
        val cam = printerCam
        if (cam != null) {
            c.drawBitmap(cam, null, camRect, camPaint)
            fill.color = Color.argb(28, 0, 0, 0)
            var y = camRect.top; while (y < camRect.bottom) { c.drawRect(camRect.left, y, camRect.right, y + 1f, fill); y += 3f }
            fill.color = Color.argb(30, 120, 200, 255); c.drawRect(camRect, fill)
        } else if (printerNote.isNotEmpty()) {
            fill.color = Color.argb(150, 8, 18, 28); c.drawRect(camRect, fill)
            c.drawText("PRINTER CAM", camRect.left + 8f, camRect.top + 16f, noteText)
            c.drawText(printerNote, camRect.left + 8f, camRect.top + 32f, noteText)
        }
        // the screens behind her flicker a little
        fill.color = Color.argb((10 + Random.nextInt(10)), 0, 0, 0)
        c.drawRect(0f, 0f, 380f, b.height.toFloat(), fill); c.drawRect(900f, 0f, b.width.toFloat(), b.height.toFloat(), fill)

        // head: turns and nods on the neck to follow people
        c.save()
        c.translate(lx * 6f, ly * 7f + sin(secs * 0.72f) * 2f)
        c.rotate(lx * 1.5f, PX, PY)
        c.clipPath(headPath)
        c.drawBitmap(b, 0f, 0f, p)

        // jaw: lips part in the middle, the chin drops, column by column
        jaw += (lv * 14f - jaw) * min(1f, dt * 18f)
        if (jaw > 0.3f) {
            val lip = 429f; val mid = 470f; val bot = 505f; val x0 = 570; val x1 = 720; val jc = 645f; val hw = 62f
            fill.color = Color.rgb(18, 10, 40)
            for (px in x0 until x1) {
                val d = (px - jc) / hw
                val pr = if (abs(d) < 1f) cos(d * PI.toFloat() / 2).let { it * it } else 0f
                val edge = min(1f, min((px - x0) / 12f, (x1 - px) / 12f))
                val top = lip + jaw * pr; val mm = mid + jaw * edge
                if (pr > 0.02f) c.drawRect(px.toFloat(), lip, px + 1.5f, top + 1f, fill)
                src.set(px, lip.toInt(), px + 1, mid.toInt()); dst.set(px.toFloat(), top, px + 1.5f, mm); c.drawBitmap(b, src, dst, p)
                src.set(px, mid.toInt(), px + 1, bot.toInt()); dst.set(px.toFloat(), mm, px + 1.5f, mm + (bot - mid)); c.drawBitmap(b, src, dst, p)
            }
        }

        // sinister: the core and face turn red
        val gx = lx * 14f; val gy = ly * 8f
        if (red > 0.01f) {
            val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val fade = RadialGradient(CX, CY, 190f, intArrayOf(Color.argb((255 * red).toInt(), 0, 0, 0),
                Color.argb((230 * red).toInt(), 0, 0, 0), Color.TRANSPARENT), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            redPaint.shader = ComposeShader(fade, sh, PorterDuff.Mode.SRC_IN)
            c.drawCircle(CX, CY, 190f, redPaint)
        }
        val cr = (110 + 145 * red).toInt(); val cg = (180 * (1 - red) + 20).toInt(); val cb = (255 * (1 - red) + 40).toInt().coerceAtMost(255)
        val pulse = (0.55f + 0.15f * sin(secs * 2f) + lv * 0.6f + (if (thinking) 0.2f * sin(secs * 8.3f) else 0f)).coerceIn(0f, 1.3f)
        val ex = CX + gx; val ey = CY + gy
        // the core itself
        val rr = 95f * open * (1f + lv * 0.35f)
        add.shader = RadialGradient(ex, ey, rr, intArrayOf(Color.argb(a(0.9f * pulse * open), 255, 255, 255),
            Color.argb(a(0.75f * pulse * open), cr, cg, cb), Color.TRANSPARENT), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(ex, ey, rr, add); add.shader = null
        // thin rings of light spinning over the core
        for ((i, r) in floatArrayOf(62f, 96f, 128f).withIndex()) {
            val n = intArrayOf(3, 4, 2)[i]; val sp = floatArrayOf(ringA, ringB, ringA * 0.6f)[i]
            ring.strokeWidth = floatArrayOf(2f, 1.5f, 2.5f)[i]
            ring.color = Color.argb(a(floatArrayOf(0.5f, 0.35f, 0.3f)[i] * pulse * open), cr, (200 * (1 - red) + 30).toInt(), 255)
            val rad = r * (0.9f + 0.1f * open); arc.set(ex - rad, ey - rad, ex + rad, ey + rad)
            for (k in 0 until n) c.drawArc(arc, Math.toDegrees((sp + k * 2 * PI / n)).toFloat(), 180f / n * 0.9f, false, ring)
        }
        // the light streak across her face
        val fw = (260f + lv * 120f) * open; val fh = 3f + lv * 3f
        add.shader = LinearGradient(ex - fw, 0f, ex + fw, 0f, intArrayOf(Color.TRANSPARENT,
            Color.argb(a(0.55f * pulse * open), cr, (140 * (1 - red)).toInt(), 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(ex - fw, ey - fh, ex + fw, ey + fh, add); add.shader = null
        // a soft glow inside the open mouth
        if (jaw > 0.5f) {
            add.shader = RadialGradient(645f, 429f + jaw / 2, 55f, intArrayOf(Color.argb(a(0.5f * lv), 150, 120, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
            c.drawRect(585f, 410f, 705f, 460f, add); add.shader = null
        }
        c.restore()

        // energy running down the cables
        val mk = mask
        if (maskReady && mk != null) {
            val speed = if (thinking || lv > 0.05f) 160f else 70f
            val off = (secs * speed) % 160f
            val col = if (red > 0.3f) Color.argb(215, 255, 60, 60) else Color.argb(215, 140, 220, 255)
            val bands = LinearGradient(0f, 0f, 0f, 160f, intArrayOf(Color.TRANSPARENT, col, Color.TRANSPARENT, Color.TRANSPARENT),
                floatArrayOf(0f, 0.28f, 0.56f, 1f), Shader.TileMode.REPEAT)
            m.setTranslate(0f, off); bands.setLocalMatrix(m)
            add.shader = ComposeShader(BitmapShader(mk, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), bands, PorterDuff.Mode.SRC_IN)
            add.alpha = a(0.55f + lv * 0.4f)
            c.drawRect(380f, 60f, 910f, b.height.toFloat(), add)
            add.shader = null; add.alpha = 255
        }
        // tiny data specks orbiting her head
        for (k in 0 until 26) {
            val ang = secs / 4f * (if (k % 2 == 1) 1f else -1f) + k * 0.9f; val r = 300f + (k % 5) * 14f
            fill.color = Color.argb(a(0.25f + 0.25f * sin(secs * 3.3f + k)), 120, 200, 255)
            val x = CX + cos(ang) * r; val y = CY + sin(ang) * r * 0.55f
            c.drawRect(x, y, x + 3f, y + 3f, fill)
        }
        c.restore()
    }

    private fun a(f: Float) = (f.coerceIn(0f, 1f) * 255).toInt()
}
