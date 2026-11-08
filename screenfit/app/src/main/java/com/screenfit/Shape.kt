package com.screenfit

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The picture's four corners in screen pixels: top-left, top-right, bottom-left, bottom-right.
 * x[i], y[i] with i = TL 0, TR 1, BL 2, BR 3.
 */
class Shape(val w: Int, val h: Int) {
    val x = FloatArray(4)
    val y = FloatArray(4)

    init { flat() }

    fun flat() {
        x[0] = 0f; y[0] = 0f
        x[1] = (w - 1).toFloat(); y[1] = 0f
        x[2] = 0f; y[2] = (h - 1).toFloat()
        x[3] = (w - 1).toFloat(); y[3] = (h - 1).toFloat()
    }

    /** From the projector's inset values: ltx lty rtx rty lbx lby rbx rby. */
    fun fromInsets(v: IntArray) {
        x[0] = v[0].toFloat(); y[0] = v[1].toFloat()
        x[1] = (w - 1 - v[2]).toFloat(); y[1] = v[3].toFloat()
        x[2] = v[4].toFloat(); y[2] = (h - 1 - v[5]).toFloat()
        x[3] = (w - 1 - v[6]).toFloat(); y[3] = (h - 1 - v[7]).toFloat()
        clamp()
    }

    /** To the projector's inset values: ltx lty rtx rty lbx lby rbx rby. */
    fun toInsets(): IntArray = intArrayOf(
        x[0].roundToInt(), y[0].roundToInt(),
        (w - 1 - x[1]).roundToInt(), y[1].roundToInt(),
        x[2].roundToInt(), (h - 1 - y[2]).roundToInt(),
        (w - 1 - x[3]).roundToInt(), (h - 1 - y[3]).roundToInt(),
    ).map { max(0, it) }.toIntArray()

    fun copyFrom(o: Shape) { o.x.copyInto(x); o.y.copyInto(y) }

    /** Keep every corner on screen and stop the shape folding over itself. */
    fun clamp() {
        val minGap = 0.2f
        for (i in 0 until 4) {
            x[i] = x[i].coerceIn(0f, (w - 1).toFloat())
            y[i] = y[i].coerceIn(0f, (h - 1).toFloat())
        }
        // left corners must stay left of right corners, top above bottom
        val mw = w * minGap; val mh = h * minGap
        if (x[1] - x[0] < mw) x[1] = min((w - 1).toFloat(), x[0] + mw)
        if (x[3] - x[2] < mw) x[3] = min((w - 1).toFloat(), x[2] + mw)
        if (y[2] - y[0] < mh) y[2] = min((h - 1).toFloat(), y[0] + mh)
        if (y[3] - y[1] < mh) y[3] = min((h - 1).toFloat(), y[1] + mh)
    }

    private fun canShift(dx: Float, dy: Float): Boolean {
        for (i in 0 until 4) {
            val nx = x[i] + dx; val ny = y[i] + dy
            if (nx < 0 || nx > w - 1 || ny < 0 || ny > h - 1) return false
        }
        return true
    }

    /** Move one corner. */
    fun moveCorner(i: Int, dx: Float, dy: Float) { x[i] += dx; y[i] += dy; clamp() }

    /** Move the whole picture (stops at the screen edge). */
    fun shift(dx: Float, dy: Float) {
        if (canShift(dx, 0f)) for (i in 0 until 4) x[i] += dx
        if (canShift(0f, dy)) for (i in 0 until 4) y[i] += dy
    }

    /** Make the picture bigger (+) or smaller (-) around its centre, keeping its shape. */
    fun grow(step: Float) {
        val cx = x.average().toFloat(); val cy = y.average().toFloat()
        val size = max(x.max() - x.min(), y.max() - y.min())
        if (size <= 0f) return
        var f = 1f + step / size
        // don't let it grow past the screen edges
        for (i in 0 until 4) {
            val nx = cx + (x[i] - cx) * f; val ny = cy + (y[i] - cy) * f
            if (nx < 0 || nx > w - 1 || ny < 0 || ny > h - 1) {
                if (step > 0) return
            }
        }
        if (step < 0 && (x[1] - x[0]) * f < w * 0.25f) f = 1f
        for (i in 0 until 4) { x[i] = cx + (x[i] - cx) * f; y[i] = cy + (y[i] - cy) * f }
        clamp()
    }

    /**
     * Up/down tilt (vertical keystone). + makes the top narrower than the bottom
     * (for a projector pointing upwards), - makes the bottom narrower.
     */
    fun tiltVertical(step: Float) {
        val half = step / 2f
        // narrow the top / widen the bottom; if the bottom is already full width, just narrow the top
        if (step > 0) {
            if (x[2] - half >= 0 && x[3] + half <= w - 1) { x[2] -= half; x[3] += half; x[0] += half; x[1] -= half }
            else { x[0] += step; x[1] -= step }
        } else {
            val s = -step; val hs = s / 2f
            if (x[0] - hs >= 0 && x[1] + hs <= w - 1) { x[0] -= hs; x[1] += hs; x[2] += hs; x[3] -= hs }
            else { x[2] += s; x[3] -= s }
        }
        clamp()
    }

    /**
     * Left/right tilt (horizontal keystone). + makes the left edge shorter than the right
     * (for a projector pointing to the left), - makes the right edge shorter.
     */
    fun tiltHorizontal(step: Float) {
        val half = step / 2f
        if (step > 0) {
            if (y[1] - half >= 0 && y[3] + half <= h - 1) { y[1] -= half; y[3] += half; y[0] += half; y[2] -= half }
            else { y[0] += step; y[2] -= step }
        } else {
            val s = -step; val hs = s / 2f
            if (y[0] - hs >= 0 && y[2] + hs <= h - 1) { y[0] -= hs; y[2] += hs; y[1] += hs; y[3] -= hs }
            else { y[1] += s; y[3] -= s }
        }
        clamp()
    }

    /** Rotate the picture slightly (+ clockwise) around its centre. */
    fun rotate(step: Float) {
        val cx = x.average().toFloat(); val cy = y.average().toFloat()
        val a = Math.toRadians((step * 0.05).toDouble())
        val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
        val nx = FloatArray(4); val ny = FloatArray(4)
        for (i in 0 until 4) {
            val dx = x[i] - cx; val dy = y[i] - cy
            nx[i] = cx + dx * c - dy * s; ny[i] = cy + dx * s + dy * c
            if (nx[i] < 0 || nx[i] > w - 1 || ny[i] < 0 || ny[i] > h - 1) return
        }
        nx.copyInto(x); ny.copyInto(y)
    }

    fun widthPercent(): Int = ((((x[1] - x[0]) + (x[3] - x[2])) / 2f) / (w - 1) * 100f).roundToInt()
}
