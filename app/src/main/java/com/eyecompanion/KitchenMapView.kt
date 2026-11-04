package com.eyecompanion

import android.content.Context
import android.graphics.*
import android.graphics.BitmapFactory
import android.view.View
import kotlin.math.sin

/**
 * Mark's own drawing of the kitchen (res/drawable/kitchen_layout.jpg), with Pip marked beside the coffee machine
 * and the place to stand glowing. Used by VoiceID Live. Spot positions are fractions of the picture.
 */
class KitchenMapView(context: Context) : View(context) {

    enum class Spot(val label: String, val x: Float, val y: Float) {
        CLOSE("Right in front of Pip", 0.70f, 0.62f),
        FRIDGE("By the end of the fridge", 0.70f, 0.82f),
        DRYER("By the tumble dryer", 0.52f, 0.95f),
        AIR_FRYER("By the air fryer", 0.48f, 0.86f),
        SINK("By the sink", 0.55f, 0.64f),
    }

    @Volatile var target: Spot? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val r = RectF()
    private val start = System.currentTimeMillis()
    private val picture: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.kitchen_layout) } catch (_: Exception) { null }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val secs = (System.currentTimeMillis() - start) / 1000f
        p.color = 0xFF14181A.toInt(); c.drawRect(0f, 0f, w, h, p)
        val bmp = picture
        // fit the picture in the view, centred
        val iw = bmp?.width?.toFloat() ?: 691f; val ih = bmp?.height?.toFloat() ?: 812f
        val sc = minOf(w / iw, h / ih)
        val dw = iw * sc; val dh = ih * sc
        val ox = (w - dw) / 2; val oy = (h - dh) / 2
        r.set(ox, oy, ox + dw, oy + dh)
        if (bmp != null) c.drawBitmap(bmp, null, r, p)
        fun X(f: Float) = ox + dw * f
        fun Y(f: Float) = oy + dh * f
        // Pip, beside the coffee machine
        p.color = 0xFFE8433A.toInt(); r.set(X(0.755f), Y(0.355f), X(0.815f), Y(0.405f)); c.drawRoundRect(r, dh * 0.008f, dh * 0.008f, p)
        p.color = 0xFF5CE1FF.toInt()
        c.drawCircle(X(0.773f), Y(0.38f), dh * 0.007f, p); c.drawCircle(X(0.797f), Y(0.38f), dh * 0.007f, p)
        t.color = 0xFFE8433A.toInt(); t.textSize = dh * 0.03f; c.drawText("Pip", X(0.785f), Y(0.345f), t)
        // where to stand: a glowing, pulsing ring
        target?.let { s ->
            val pulse = 0.5f + 0.5f * sin(secs * 5f)
            val cx = X(s.x); val cy = Y(s.y)
            p.style = Paint.Style.STROKE; p.strokeWidth = dh * 0.01f
            p.color = 0xFFFFC21A.toInt(); p.alpha = (170 + 85 * pulse).toInt()
            c.drawCircle(cx, cy, dh * (0.035f + 0.015f * pulse), p)
            p.alpha = (90 * pulse).toInt(); c.drawCircle(cx, cy, dh * (0.065f + 0.02f * pulse), p)
            p.style = Paint.Style.FILL; p.alpha = 255
            t.textSize = dh * 0.04f
            val ty = if (s.y > 0.85f) cy - dh * 0.075f else cy - dh * 0.075f
            t.color = 0xDD000000.toInt(); c.drawText("🧍 Stand here", cx + 2, ty + 2, t)
            t.color = 0xFFFFC21A.toInt(); c.drawText("🧍 Stand here", cx, ty, t)
        }
        postInvalidateOnAnimation()
    }
}
