package com.eyecompanion

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.sin

/**
 * A cartoon of the kitchen as Pip sees it (drawn from Mark's photo from Pip's spot), with the place
 * to stand glowing. Used by VoiceID Live. The real photo isn't stored anywhere.
 */
class KitchenMapView(context: Context) : View(context) {

    enum class Spot(val label: String, val x: Float, val y: Float) {
        CLOSE("Right in front of Pip", 0.52f, 0.86f),
        FRIDGE("By the fridge", 0.16f, 0.55f),
        AIR_FRYER("By the air fryer", 0.50f, 0.40f),
        WASHER("By the washing machine", 0.33f, 0.40f),
        SINK("By the sink", 0.70f, 0.40f),
    }

    @Volatile var target: Spot? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val r = RectF()
    private val start = System.currentTimeMillis()

    private fun box(c: Canvas, w: Float, h: Float, l: Float, top: Float, rr: Float, b: Float, col: Int, label: String, emoji: String) {
        p.color = col; r.set(w * l, h * top, w * rr, h * b); c.drawRoundRect(r, h * 0.02f, h * 0.02f, p)
        t.color = Color.WHITE; t.textSize = h * 0.07f; c.drawText(emoji, r.centerX(), r.centerY() + h * 0.01f, t)
        t.textSize = h * 0.035f; t.color = 0xCCFFFFFF.toInt(); c.drawText(label, r.centerX(), r.bottom - h * 0.015f, t)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val secs = (System.currentTimeMillis() - start) / 1000f
        // room: back wall, floor, ceiling lights
        p.color = 0xFF1B2A24.toInt(); c.drawRect(0f, 0f, w, h, p)
        p.color = 0xFF2E4238.toInt(); c.drawRect(w * 0.08f, h * 0.08f, w * 0.92f, h * 0.48f, p)          // back wall (green)
        p.color = 0xFF222222.toInt(); val floor = Path().apply {
            moveTo(w * 0.08f, h * 0.48f); lineTo(w * 0.92f, h * 0.48f); lineTo(w, h * 0.78f); lineTo(0f, h * 0.78f); close() }
        c.drawPath(floor, p)
        // window with blind, back right
        p.color = 0xFFBFD8E8.toInt(); r.set(w * 0.62f, h * 0.12f, w * 0.82f, h * 0.30f); c.drawRect(r, p)
        p.color = 0xFF8C8C8C.toInt(); r.set(w * 0.62f, h * 0.12f, w * 0.82f, h * 0.20f); c.drawRect(r, p)
        // back counter
        p.color = 0xFF3A3A3A.toInt(); r.set(w * 0.40f, h * 0.36f, w * 0.90f, h * 0.48f); c.drawRect(r, p)
        box(c, w, h, 0.25f, 0.30f, 0.40f, 0.48f, 0xFF6F7F8A.toInt(), "washer", "🧺")
        box(c, w, h, 0.45f, 0.26f, 0.55f, 0.36f, 0xFF333333.toInt(), "air fryer", "🍟")
        box(c, w, h, 0.62f, 0.32f, 0.80f, 0.40f, 0xFF5A6B78.toInt(), "sink", "🚰")
        // fridge close on the left, kettle right next to Pip on the right
        box(c, w, h, 0.02f, 0.18f, 0.24f, 0.78f, 0xFF585E66.toInt(), "fridge", "🧊")
        box(c, w, h, 0.84f, 0.50f, 0.98f, 0.80f, 0xFF111111.toInt(), "kettle", "☕")
        // the bench and Pip
        p.color = 0xFF2B2B2B.toInt(); r.set(0f, h * 0.80f, w, h); c.drawRect(r, p)
        p.color = 0xFFE8433A.toInt(); r.set(w * 0.44f, h * 0.88f, w * 0.56f, h * 0.98f); c.drawRoundRect(r, h * 0.015f, h * 0.015f, p)
        p.color = 0xFF5CE1FF.toInt()
        c.drawCircle(w * 0.48f, h * 0.93f, h * 0.012f, p); c.drawCircle(w * 0.52f, h * 0.93f, h * 0.012f, p)
        t.textSize = h * 0.03f; t.color = Color.WHITE; c.drawText("Pip", w * 0.5f, h * 0.875f, t)
        // where to stand: a glowing, pulsing ring
        target?.let { s ->
            val pulse = 0.5f + 0.5f * sin(secs * 5f)
            val cx = w * s.x; val cy = h * s.y
            p.style = Paint.Style.STROKE; p.strokeWidth = h * 0.012f
            p.color = 0xFFFFD84A.toInt(); p.alpha = (150 + 105 * pulse).toInt()
            c.drawCircle(cx, cy, h * (0.06f + 0.02f * pulse), p)
            p.alpha = (80 * pulse).toInt(); c.drawCircle(cx, cy, h * (0.1f + 0.03f * pulse), p)
            p.style = Paint.Style.FILL; p.alpha = 255
            t.textSize = h * 0.045f; t.color = 0xFFFFD84A.toInt()
            c.drawText("🧍 Stand here", cx, cy - h * 0.11f, t)
        }
        postInvalidateOnAnimation()
    }
}
