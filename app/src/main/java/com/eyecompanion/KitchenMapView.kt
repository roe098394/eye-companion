package com.eyecompanion

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.sin

/**
 * A cartoon of the kitchen as Pip sees it from its corner (drawn from Mark's photos), with the place
 * to stand glowing. Used by VoiceID Live. No real photo is stored anywhere.
 *
 * From Pip's corner: the side of the fridge straight ahead and a bit left (paperwork stuck on it), the Tassimo
 * beside Pip on its left, the kettle right next to it on its right, the worktop with the hob running out on the
 * right; past the end of the fridge, across the room: the tumble dryer (basket and folded clothes on top),
 * the air fryer, and the sink under the window.
 */
class KitchenMapView(context: Context) : View(context) {

    enum class Spot(val label: String, val x: Float, val y: Float) {
        CLOSE("Right in front of Pip", 0.52f, 0.80f),
        FRIDGE("By the end of the fridge", 0.44f, 0.56f),
        DRYER("By the tumble dryer", 0.53f, 0.52f),
        AIR_FRYER("By the air fryer", 0.68f, 0.52f),
        SINK("By the sink", 0.87f, 0.52f),
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
    private fun box(c: Canvas, w: Float, h: Float, l: Float, top: Float, rr: Float, b: Float, col: Int, label: String, textCol: Int = 0xFFE6E6E6.toInt()) {
        p.color = col; r.set(w * l, h * top, w * rr, h * b); c.drawRoundRect(r, h * 0.015f, h * 0.015f, p)
        t.color = textCol; t.textSize = h * 0.032f; c.drawText(label, r.centerX(), r.bottom - h * 0.02f, t)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val secs = (System.currentTimeMillis() - start) / 1000f
        p.color = 0xFF1B2A24.toInt(); c.drawRect(0f, 0f, w, h, p)
        // the far side of the room, past the end of the fridge: wall and floor
        poly(c, w, h, 0xFF2E4238.toInt(), 0.40f, 0.10f, 0.98f, 0.04f, 0.98f, 0.46f, 0.40f, 0.46f)
        poly(c, w, h, 0xFF222222.toInt(), 0.40f, 0.46f, 0.98f, 0.46f, 1.0f, 0.62f, 0.40f, 0.66f)
        // window with a blind, counter, sink
        poly(c, w, h, 0xFFBFD8E8.toInt(), 0.80f, 0.10f, 0.95f, 0.09f, 0.95f, 0.27f, 0.80f, 0.27f)
        poly(c, w, h, 0xFF8C8C8C.toInt(), 0.80f, 0.10f, 0.95f, 0.09f, 0.95f, 0.15f, 0.80f, 0.16f)
        p.color = 0xFF3A3A3A.toInt(); c.drawRect(w * 0.62f, h * 0.34f, w * 0.98f, h * 0.46f, p)
        box(c, w, h, 0.80f, 0.30f, 0.94f, 0.38f, 0xFF5A6B78.toInt(), "sink")
        // tumble dryer with a basket and folded clothes on top, air fryer on the counter
        box(c, w, h, 0.46f, 0.28f, 0.60f, 0.46f, 0xFFE8E8E8.toInt(), "dryer", 0xFF333333.toInt())
        p.color = 0xFFB08A5A.toInt(); r.set(w * 0.48f, h * 0.22f, w * 0.53f, h * 0.28f); c.drawRect(r, p)          // basket
        p.color = 0xFF2E8B57.toInt(); r.set(w * 0.54f, h * 0.245f, w * 0.59f, h * 0.28f); c.drawRect(r, p)        // clothes
        box(c, w, h, 0.64f, 0.24f, 0.72f, 0.34f, 0xFF333333.toInt(), "air fryer")
        // the side of the fridge: close, straight ahead and a bit left, paperwork stuck on it
        poly(c, w, h, 0xFF585E66.toInt(), 0.06f, 0.0f, 0.40f, 0.04f, 0.40f, 0.72f, 0.06f, 0.86f)
        p.color = 0xFFE6E4D7.toInt()
        for (i in 0..3) { r.set(w * (0.30f + 0.02f * i), h * (0.20f + 0.06f * i), w * (0.37f + 0.01f * i), h * (0.30f + 0.06f * i)); c.drawRect(r, p) }
        t.color = 0xFFE6E6E6.toInt(); t.textSize = h * 0.038f; c.drawText("fridge", w * 0.2f, h * 0.45f, t)
        // worktop running out on the right with the hob; kettle and Tassimo beside Pip
        poly(c, w, h, 0xFF2B2B2B.toInt(), 0.55f, 0.70f, 1.0f, 0.60f, 1.0f, 0.86f, 0.45f, 0.86f)
        poly(c, w, h, 0xFF505050.toInt(), 0.70f, 0.68f, 0.92f, 0.64f, 0.95f, 0.76f, 0.72f, 0.80f)
        t.textSize = h * 0.032f; c.drawText("hob", w * 0.82f, h * 0.73f, t)
        box(c, w, h, 0.62f, 0.62f, 0.70f, 0.86f, 0xFF111111.toInt(), "kettle")
        box(c, w, h, 0.30f, 0.66f, 0.40f, 0.88f, 0xFFEEE8DC.toInt(), "Tassimo", 0xFF3C3C3C.toInt())
        // Pip in the corner
        p.color = 0xFF242424.toInt(); c.drawRect(0f, h * 0.88f, w, h, p)
        p.color = 0xFFE8433A.toInt(); r.set(w * 0.44f, h * 0.88f, w * 0.56f, h * 0.99f); c.drawRoundRect(r, h * 0.015f, h * 0.015f, p)
        p.color = 0xFF5CE1FF.toInt()
        c.drawCircle(w * 0.475f, h * 0.935f, h * 0.012f, p); c.drawCircle(w * 0.525f, h * 0.935f, h * 0.012f, p)
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
