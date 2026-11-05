package com.projmapper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Content is drawn into an S x S square, then warped onto the four corners. */
private const val S = 1000f

/** One mapped surface: four corners (normalised 0..1 of the screen), what it shows, and its colour. */
class Surface(
    val pts: FloatArray,          // x0,y0 (top-left), x1,y1 (top-right), x2,y2 (bottom-right), x3,y3 (bottom-left)
    var effect: Int = 1,
    var hue: Float = 200f,
    var image: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("p", JSONArray().apply { pts.forEach { put(it.toDouble()) } })
        put("e", effect); put("h", hue.toDouble())
        image?.let { put("img", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): Surface {
            val a = o.getJSONArray("p")
            val p = FloatArray(8) { a.getDouble(it).toFloat() }
            return Surface(p, o.optInt("e", 1), o.optDouble("h", 200.0).toFloat(),
                if (o.has("img")) o.getString("img") else null)
        }

        fun centred(cx: Float, cy: Float, half: Float, effect: Int, hue: Float) = Surface(
            floatArrayOf(cx - half, cy - half, cx + half, cy - half, cx + half, cy + half, cx - half, cy + half),
            effect, hue)
    }
}

class MapView(context: Context) : View(context) {

    companion object {
        val EFFECTS = arrayOf(
            "White (for lining up)", "Rainbow sweep", "Scrolling stripes", "Colour pulse",
            "Neon edge chase", "Brick wall", "Starfield", "Colour waves", "Eyes", "Picture", "Black (mask)",
        )
        const val FX_PICTURE = 9
    }

    val surfaces = ArrayList<Surface>()
    var current = 0
    /** 0..3 = a corner, 4 = the whole surface */
    var handle = 0
    var editing = true
    var showGrid = false

    /** Pictures loaded by the activity, keyed by their content URI. */
    val pictures = HashMap<String, Bitmap>()

    /** Called whenever the layout changes, so it can be saved. */
    var onChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val warp = Matrix()
    private val inverse = Matrix()
    private val shaderMatrix = Matrix()
    private val src = floatArrayOf(0f, 0f, S, 0f, S, S, 0f, S)
    private val dst = FloatArray(8)
    private val path = Path()
    private val unit = RectF(0f, 0f, S, S)
    private val hsv = FloatArray(3)

    private val rainbow = LinearGradient(0f, 0f, S, 0f,
        intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED),
        null, Shader.TileMode.REPEAT)
    private val sweep = LinearGradient(0f, 0f, 300f, 0f,
        intArrayOf(Color.TRANSPARENT, Color.argb(150, 255, 255, 255), Color.TRANSPARENT),
        null, Shader.TileMode.CLAMP)
    private val stars = Array(140) { floatArrayOf(Random.nextFloat() * S, Random.nextFloat() * S, Random.nextFloat() * 4f + 2f, Random.nextFloat() * 6f) }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    fun cur(): Surface? = surfaces.getOrNull(current)

    private fun colour(hue: Float, sat: Float = 1f, value: Float = 1f): Int {
        hsv[0] = ((hue % 360f) + 360f) % 360f; hsv[1] = sat; hsv[2] = value
        return Color.HSVToColor(hsv)
    }

    private fun screenPts(s: Surface) {
        for (i in 0 until 4) {
            dst[i * 2] = s.pts[i * 2] * width
            dst[i * 2 + 1] = s.pts[i * 2 + 1] * height
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (width == 0 || height == 0) return
        val t = (SystemClock.uptimeMillis() - start) / 1000f

        surfaces.forEachIndexed { i, s ->
            screenPts(s)
            if (!warp.setPolyToPoly(src, 0, dst, 0, 4)) return@forEachIndexed
            canvas.save()
            canvas.concat(warp)
            canvas.clipRect(unit)
            drawEffect(canvas, s, i, t)
            canvas.restore()
        }

        if (showGrid) drawGrid(canvas)
        if (editing) drawEditOverlay(canvas)

        postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- effects

    private fun drawEffect(c: Canvas, s: Surface, index: Int, t: Float) {
        fill.shader = null; fill.alpha = 255; stroke.pathEffect = null; stroke.shader = null
        when (s.effect) {
            0 -> c.drawColor(Color.WHITE)

            1 -> { // rainbow sweep
                shaderMatrix.setTranslate((t * 250f) % S, 0f)
                rainbow.setLocalMatrix(shaderMatrix)
                fill.shader = rainbow
                c.drawRect(unit, fill)
                fill.shader = null
            }

            2 -> { // diagonal stripes scrolling
                c.drawColor(colour(s.hue, 1f, 0.15f))
                fill.color = colour(s.hue + t * 20f)
                c.save()
                c.rotate(45f, S / 2, S / 2)
                val off = (t * 160f) % 200f
                var x = -800f + off
                while (x < S + 800f) { c.drawRect(x, -800f, x + 100f, S + 800f, fill); x += 200f }
                c.restore()
            }

            3 -> { // colour pulse
                val v = 0.25f + 0.75f * (0.5f + 0.5f * sin(t * 2.5f + index * 1.3f))
                c.drawColor(colour(s.hue + t * 10f, 1f, v))
            }

            4 -> { // neon edge chase
                c.drawColor(colour(s.hue, 0.6f, 0.08f))
                path.reset(); path.addRect(40f, 40f, S - 40f, S - 40f, Path.Direction.CW)
                val col = colour(s.hue + t * 30f)
                stroke.pathEffect = DashPathEffect(floatArrayOf(380f, 220f), -t * 700f)
                stroke.color = col
                stroke.strokeWidth = 60f; stroke.alpha = 50; c.drawPath(path, stroke)
                stroke.strokeWidth = 30f; stroke.alpha = 110; c.drawPath(path, stroke)
                stroke.strokeWidth = 12f; stroke.alpha = 255; c.drawPath(path, stroke)
                stroke.color = Color.WHITE; stroke.strokeWidth = 4f; c.drawPath(path, stroke)
                stroke.pathEffect = null
            }

            5 -> { // brick wall with a light sweeping across
                c.drawColor(Color.rgb(70, 70, 70))
                fill.color = Color.rgb(150, 55, 35)
                var row = 0
                var y = 0f
                while (y < S) {
                    val shift = if (row % 2 == 0) 0f else -100f
                    var x = shift
                    while (x < S) { c.drawRect(x + 6f, y + 6f, x + 200f - 6f, y + 100f - 6f, fill); x += 200f }
                    y += 100f; row++
                }
                val pos = (t * 350f) % (S + 900f) - 450f
                shaderMatrix.setTranslate(pos, 0f)
                sweep.setLocalMatrix(shaderMatrix)
                fill.shader = sweep
                c.save(); c.rotate(20f, S / 2, S / 2)
                c.drawRect(-500f, -500f, S + 500f, S + 500f, fill)
                c.restore()
                fill.shader = null
            }

            6 -> { // starfield drifting
                for ((n, st) in stars.withIndex()) {
                    val x = (st[0] - t * st[2] * 8f + S * 10f) % S
                    val y = st[1]
                    val a = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * 2f + st[3] + n))
                    fill.color = Color.WHITE; fill.alpha = (a * 255).toInt()
                    c.drawCircle(x, y, st[2], fill)
                }
                fill.alpha = 255
            }

            7 -> { // colour waves
                val bands = 20
                val h = S / bands
                for (b in 0 until bands) {
                    fill.color = colour(s.hue + 50f * sin(t * 1.5f + b * 0.45f), 1f, 0.6f + 0.4f * sin(t + b * 0.3f).let { abs(it) })
                    c.drawRect(0f, b * h, S, b * h + h + 1f, fill)
                }
            }

            8 -> drawEyes(c, t, index)

            FX_PICTURE -> {
                val bmp = s.image?.let { pictures[it] }
                if (bmp != null) c.drawBitmap(bmp, null, unit, bmpPaint)
                else {
                    c.drawColor(Color.DKGRAY)
                    text.textSize = 70f; text.textAlign = Paint.Align.CENTER
                    c.drawText("No picture yet", S / 2, S / 2, text)
                    text.textAlign = Paint.Align.LEFT
                }
            }

            else -> {} // black / mask
        }
    }

    private fun drawEyes(c: Canvas, t: Float, index: Int) {
        val lookX = sin(t * 0.6f + index) * 55f + sin(t * 1.7f) * 15f
        val lookY = cos(t * 0.45f + index) * 40f
        // blink for a moment roughly every 4 seconds
        val ph = (t + index * 1.1f) % 4f
        val open = if (ph < 0.18f) abs(ph - 0.09f) / 0.09f else 1f
        for (cx in floatArrayOf(310f, 690f)) {
            c.save()
            c.scale(1f, open.coerceAtLeast(0.05f), cx, 500f)
            fill.color = Color.WHITE
            c.drawOval(cx - 170f, 300f, cx + 170f, 700f, fill)
            fill.color = Color.rgb(30, 120, 220)
            c.drawCircle(cx + lookX, 500f + lookY, 95f, fill)
            fill.color = Color.BLACK
            c.drawCircle(cx + lookX, 500f + lookY, 50f, fill)
            fill.color = Color.WHITE
            c.drawCircle(cx + lookX + 25f, 475f + lookY, 18f, fill)
            c.restore()
        }
    }

    private fun drawGrid(c: Canvas) {
        stroke.color = Color.WHITE; stroke.strokeWidth = 1.5f * density; stroke.alpha = 200
        for (i in 0..10) {
            val x = width * i / 10f; c.drawLine(x, 0f, x, height.toFloat(), stroke)
            val y = height * i / 10f; c.drawLine(0f, y, width.toFloat(), y, stroke)
        }
        stroke.color = Color.RED
        c.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), stroke)
        c.drawLine(0f, height / 2f, width.toFloat(), height / 2f, stroke)
        c.drawCircle(width / 2f, height / 2f, min(width, height) / 4f, stroke)
        stroke.alpha = 255
    }

    private fun drawEditOverlay(c: Canvas) {
        val r = 9f * density
        surfaces.forEachIndexed { i, s ->
            screenPts(s)
            val sel = i == current
            path.reset()
            path.moveTo(dst[0], dst[1]); path.lineTo(dst[2], dst[3]); path.lineTo(dst[4], dst[5]); path.lineTo(dst[6], dst[7]); path.close()
            stroke.strokeWidth = if (sel) 3f * density else 1.5f * density
            stroke.color = if (sel && handle == 4) Color.YELLOW else if (sel) Color.WHITE else Color.GRAY
            c.drawPath(path, stroke)
            if (sel) {
                for (k in 0 until 4) {
                    if (k == handle) {
                        fill.color = Color.YELLOW; c.drawCircle(dst[k * 2], dst[k * 2 + 1], r * 1.6f, fill)
                    } else {
                        stroke.color = Color.WHITE; c.drawCircle(dst[k * 2], dst[k * 2 + 1], r, stroke)
                    }
                }
            }
            val cx = (dst[0] + dst[2] + dst[4] + dst[6]) / 4f
            val cy = (dst[1] + dst[3] + dst[5] + dst[7]) / 4f
            if (sel && handle == 4) {
                stroke.color = Color.YELLOW
                c.drawLine(cx - r * 2, cy, cx + r * 2, cy, stroke); c.drawLine(cx, cy - r * 2, cx, cy + r * 2, stroke)
            }
            text.textSize = 14f * density; text.textAlign = Paint.Align.CENTER
            text.color = if (sel) Color.YELLOW else Color.LTGRAY
            c.drawText("${i + 1}. ${EFFECTS.getOrElse(s.effect) { "?" }}", cx, cy + r * 4.5f, text)
            text.textAlign = Paint.Align.LEFT; text.color = Color.WHITE
        }

        // help bar
        val what = if (handle == 4) "whole surface" else "corner ${handle + 1}"
        val help = if (surfaces.isEmpty()) "Back / Menu: open the menu to add a surface"
        else "Surface ${current + 1} of ${surfaces.size} • moving $what  |  OK: next corner  •  Arrows: move (hold = faster)  •  Back: menu"
        text.textSize = 13f * density
        fill.color = Color.argb(170, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), 26f * density, fill)
        c.drawText(help, 10f * density, 18f * density, text)
    }

    // ---------------------------------------------------------------- editing

    fun nextHandle() { if (surfaces.isNotEmpty()) handle = (handle + 1) % 5 }

    /** Move the selected corner (or whole surface) by dx, dy screen pixels. */
    fun nudge(dx: Float, dy: Float) {
        val s = cur() ?: return
        if (width == 0 || height == 0) return
        val nx = dx / width; val ny = dy / height
        if (handle == 4) {
            for (k in 0 until 4) { s.pts[k * 2] += nx; s.pts[k * 2 + 1] += ny }
        } else {
            s.pts[handle * 2] = (s.pts[handle * 2] + nx).coerceIn(-0.5f, 1.5f)
            s.pts[handle * 2 + 1] = (s.pts[handle * 2 + 1] + ny).coerceIn(-0.5f, 1.5f)
        }
    }

    // ---------------------------------------------------------------- touch (phones / tablets)

    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!editing) return true
                dragging = pick(e.x, e.y)
                lastX = e.x; lastY = e.y
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                nudge(e.x - lastX, e.y - lastY)
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onChanged?.invoke()
                dragging = false
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Select the nearest corner within reach, or a surface the finger is inside. */
    private fun pick(x: Float, y: Float): Boolean {
        val reach = 40f * density
        var best = -1; var bestK = -1; var bestD = reach
        surfaces.forEachIndexed { i, s ->
            screenPts(s)
            for (k in 0 until 4) {
                val d = hypot(dst[k * 2] - x, dst[k * 2 + 1] - y)
                if (d < bestD) { bestD = d; best = i; bestK = k }
            }
        }
        if (best >= 0) { current = best; handle = bestK; return true }
        for (i in surfaces.indices.reversed()) {
            screenPts(surfaces[i])
            if (!warp.setPolyToPoly(src, 0, dst, 0, 4) || !warp.invert(inverse)) continue
            val p = floatArrayOf(x, y)
            inverse.mapPoints(p)
            if (p[0] in 0f..S && p[1] in 0f..S) { current = i; handle = 4; return true }
        }
        return false
    }
}
