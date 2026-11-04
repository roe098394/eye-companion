package com.eyecompanion

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.sin

/**
 * A cartoon of the kitchen as Pip sees it from its corner (drawn from Mark's photos and layout), with the place
 * to stand glowing. Used by VoiceID Live. No real photo is stored anywhere.
 *
 * Pip sits on the back worktop between the Tassimo (left) and the kettle (right). On the left, close, the tall
 * fridge-freezer sticks out (its side covered in paperwork). On the right the worktop runs past the hob (oven
 * underneath) to the corner, then the side run comes towards the room: the sink under the window, and the air
 * fryer at the far end. Straight ahead across the floor: the tumble dryer.
 */
class KitchenMapView(context: Context) : View(context) {

    enum class Spot(val label: String, val x: Float, val y: Float) {
        CLOSE("Right in front of Pip", 0.50f, 0.80f),
        FRIDGE("By the end of the fridge", 0.34f, 0.60f),
        DRYER("By the tumble dryer", 0.50f, 0.42f),
        AIR_FRYER("By the air fryer", 0.72f, 0.45f),
        SINK("By the sink", 0.84f, 0.58f),
    }

    @Volatile var target: Spot? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val r = RectF()
    private val path = Path()
    private val start = System.currentTimeMillis()

    private fun poly(c: Canvas, w: Float, h: Float, col: Int, vararg xy: Float) {
        path.reset(); path.moveTo(w * xy[0], h * xy[1])
        var i = 2; while (i < xy.size) { path.lineTo(w * xy[i], h * xy[i + 1]); i += 2 }
        path.close(); p.color = col; c.drawPath(path, p)
    }
    private fun label(c: Canvas, w: Float, h: Float, x: Float, y: Float, text: String, col: Int = 0xFFE6E6E6.toInt(), size: Float = 0.032f) {
        t.color = col; t.textSize = h * size; c.drawText(text, w * x, h * y, t)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val secs = (System.currentTimeMillis() - start) / 1000f
        p.color = 0xFF1B2A24.toInt(); c.drawRect(0f, 0f, w, h, p)
        // the room seen from Pip's corner: far wall ahead, floor, the side wall with the window on the right
        poly(c, w, h, 0xFF2E4238.toInt(), 0.30f, 0.10f, 0.70f, 0.10f, 0.70f, 0.40f, 0.30f, 0.40f)       // far wall
        poly(c, w, h, 0xFF3A3328.toInt(), 0.30f, 0.40f, 0.70f, 0.40f, 0.86f, 0.88f, 0.14f, 0.88f)       // floor
        poly(c, w, h, 0xFF34493F.toInt(), 0.70f, 0.10f, 1.00f, 0.00f, 1.00f, 0.70f, 0.70f, 0.40f)       // right wall
        // window on the right wall, with the sink run and the air fryer at the far end
        poly(c, w, h, 0xFFBFD8E8.toInt(), 0.76f, 0.13f, 0.95f, 0.07f, 0.95f, 0.34f, 0.76f, 0.33f)
        poly(c, w, h, 0xFF2B2B2B.toInt(), 0.70f, 0.40f, 1.00f, 0.46f, 1.00f, 0.80f, 0.70f, 0.52f)       // side worktop
        poly(c, w, h, 0xFF5A6B78.toInt(), 0.80f, 0.47f, 0.95f, 0.51f, 0.95f, 0.60f, 0.80f, 0.55f)       // sink
        label(c, w, h, 0.88f, 0.66f, "sink")
        p.color = 0xFF151515.toInt(); r.set(w * 0.71f, h * 0.31f, w * 0.77f, h * 0.42f); c.drawRoundRect(r, h * 0.015f, h * 0.015f, p)
        label(c, w, h, 0.74f, 0.29f, "air fryer", size = 0.028f)
        // the tumble dryer straight ahead, a basket and folded clothes on top
        p.color = 0xFFE8E8E8.toInt(); r.set(w * 0.43f, h * 0.22f, w * 0.57f, h * 0.40f); c.drawRoundRect(r, h * 0.012f, h * 0.012f, p)
        p.color = 0xFF9AA4AA.toInt(); c.drawCircle(w * 0.50f, h * 0.31f, h * 0.05f, p)
        p.color = 0xFFB08A5A.toInt(); r.set(w * 0.44f, h * 0.17f, w * 0.49f, h * 0.22f); c.drawRect(r, p)
        p.color = 0xFF2E8B57.toInt(); r.set(w * 0.51f, h * 0.19f, w * 0.56f, h * 0.22f); c.drawRect(r, p)
        label(c, w, h, 0.50f, 0.455f, "dryer", size = 0.028f)
        // the fridge-freezer: tall, close, on the left, paperwork on its side
        poly(c, w, h, 0xFF8E959C.toInt(), 0.04f, 0.00f, 0.30f, 0.06f, 0.30f, 0.66f, 0.04f, 0.92f)
        p.color = 0xFFE6E4D7.toInt()
        for (i in 0..3) { r.set(w * (0.17f + 0.02f * i), h * (0.18f + 0.07f * i), w * (0.25f + 0.01f * i), h * (0.27f + 0.07f * i)); c.drawRect(r, p) }
        label(c, w, h, 0.12f, 0.50f, "fridge", 0xFF222222.toInt(), 0.038f)
        // the back worktop Pip sits on: hob (oven under it) running out to the right, kettle and Tassimo beside Pip
        poly(c, w, h, 0xFF2B2B2B.toInt(), 0.30f, 0.66f, 0.70f, 0.52f, 1.00f, 0.80f, 1.00f, 0.92f, 0.20f, 0.92f)
        poly(c, w, h, 0xFF505050.toInt(), 0.62f, 0.62f, 0.82f, 0.60f, 0.86f, 0.72f, 0.64f, 0.75f)
        label(c, w, h, 0.74f, 0.70f, "hob")
        p.color = 0xFF111111.toInt(); r.set(w * 0.58f, h * 0.70f, w * 0.65f, h * 0.90f); c.drawRoundRect(r, h * 0.02f, h * 0.02f, p)
        label(c, w, h, 0.615f, 0.88f, "kettle", size = 0.026f)
        p.color = 0xFFEEE8DC.toInt(); r.set(w * 0.33f, h * 0.72f, w * 0.42f, h * 0.92f); c.drawRoundRect(r, h * 0.015f, h * 0.015f, p)
        label(c, w, h, 0.375f, 0.89f, "Tassimo", 0xFF3C3C3C.toInt(), 0.026f)
        // Pip in the corner
        p.color = 0xFF242424.toInt(); c.drawRect(0f, h * 0.92f, w, h, p)
        p.color = 0xFFE8433A.toInt(); r.set(w * 0.45f, h * 0.90f, w * 0.55f, h * 0.995f); c.drawRoundRect(r, h * 0.015f, h * 0.015f, p)
        p.color = 0xFF5CE1FF.toInt()
        c.drawCircle(w * 0.48f, h * 0.945f, h * 0.012f, p); c.drawCircle(w * 0.52f, h * 0.945f, h * 0.012f, p)
        // where to stand: a glowing, pulsing ring
        target?.let { s ->
            val pulse = 0.5f + 0.5f * sin(secs * 5f)
            val cx = w * s.x; val cy = h * s.y
            p.style = Paint.Style.STROKE; p.strokeWidth = h * 0.012f
            p.color = 0xFFFFD84A.toInt(); p.alpha = (150 + 105 * pulse).toInt()
            c.drawCircle(cx, cy, h * (0.05f + 0.02f * pulse), p)
            p.alpha = (80 * pulse).toInt(); c.drawCircle(cx, cy, h * (0.09f + 0.03f * pulse), p)
            p.style = Paint.Style.FILL; p.alpha = 255
            t.textSize = h * 0.045f; t.color = 0xFFFFD84A.toInt()
            c.drawText("🧍 Stand here", cx, cy - h * 0.1f, t)
        }
        postInvalidateOnAnimation()
    }
}
