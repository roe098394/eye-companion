package com.eyecompanion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Uses the tablet's motion sensor to notice being picked up, shaken, tilted and put back down.
 * Tuned so doors closing or the speaker thumping don't count: a pick-up needs steady movement
 * after the tablet has been still for a while.
 */
class Handling(context: Context, private val displayDegrees: () -> Int) : SensorEventListener {

    var onPickedUp: () -> Unit = {}
    var onShaken: () -> Unit = {}
    /** [backInPlace] = resting the same way up as before it was picked up (back on the bench). */
    var onPutDown: (backInPlace: Boolean) -> Unit = {}
    private val restG = FloatArray(3)
    private var haveRest = false
    /** Which way is down across the screen, in degrees (0 = normal way up, 90 = turned so its right edge is down). */
    var onDown: (Float) -> Unit = {}
    private var downDeg = 0f
    /** -1..1, how far the screen leans: + = right side lower */
    var onTilt: (Float) -> Unit = {}
    /** lying flat on its back (or face) for a while; called again every few minutes while it stays flat */
    var onLyingFlat: (minutes: Int) -> Unit = {}
    private var flatSince = 0L
    private var lastFlatMoan = 0L

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val available get() = accel != null

    private val g = FloatArray(3)
    private var haveG = false
    private var stillSince = 0L
    private var movingSince = 0L
    private var handled = false
    private var lastPickUp = 0L
    private val shakes = ArrayDeque<Long>()
    private var lastShake = 0L
    private var lastHandledMove = 0L
    private var level = 0f

    fun start() { accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() = sm.unregisterListener(this)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        val a = e.values
        if (!haveG) { a.copyInto(g, 0, 0, 3); haveG = true; stillSince = now; return }
        // slow-moving average = gravity; what's left over = the tablet actually moving
        for (i in 0..2) g[i] += (a[i] - g[i]) * 0.08f
        val lx = a[0] - g[0]; val ly = a[1] - g[1]; val lz = a[2] - g[2]
        val move = sqrt(lx * lx + ly * ly + lz * lz)
        level += (move - level) * 0.15f           // smoothed, so one quiet reading doesn't count as "still"

        // shaken: several hard jolts within a second
        if (move > 11f) {
            if (shakes.isEmpty() || now - shakes.last() > 120) shakes.addLast(now)
            while (shakes.isNotEmpty() && now - shakes.first() > 1200) shakes.removeFirst()
            if (shakes.size >= 4 && now - lastShake > 15_000) {
                lastShake = now; shakes.clear(); handled = true; lastHandledMove = now; onShaken()
            }
        }

        if (level > 0.9f) {
            if (movingSince == 0L) movingSince = now
            lastHandledMove = now
            // picked up: had been still 5 s, then keeps moving for over half a second
            if (!handled && stillSince > 0 && movingSince - stillSince > 5000 && now - movingSince > 500 &&
                now - lastPickUp > 30_000) {
                handled = true; lastPickUp = now; onPickedUp()
            }
        } else if (level < 0.3f) {
            if (movingSince != 0L) { movingSince = 0L; stillSince = now }
            if (stillSince == 0L) stillSince = now
            // remember how it sits when settled (on the bench), to recognise it later
            if (!handled && now - stillSince > (if (haveRest) 600_000L else 5000L)) { g.copyInto(restG); haveRest = true }   // home = where it settles for long
            // put down: still for 3.5 s after being handled; is it sitting the way it did on the bench?
            if (handled && now - lastHandledMove > 3500) {
                handled = false
                onPutDown(!haveRest || angleBetween(g, restG) < 15.0 || onStand())
            }
        } else if (movingSince != 0L && now - movingSince > 1500 && !handled) {
            // gentle wobble that never became a pick-up: start counting stillness again
            movingSince = 0L; stillSince = now
        }

        // tilt, from the screen's point of view
        val sx = when (displayDegrees()) {
            90 -> -g[1]
            180 -> -g[0]
            270 -> g[1]
            else -> g[0]
        }
        onTilt((-sx / 9.81f).coerceIn(-1f, 1f))

        // which way is down across the screen (only when it's standing up, not lying flat)
        val ux: Float; val uy: Float                                 // gravity reading in display axes (x right, y up)
        when (displayDegrees()) {
            90 -> { ux = -g[1]; uy = g[0] }                         // (matches the tilt reading above)
            180 -> { ux = -g[0]; uy = -g[1] }
            270 -> { ux = g[1]; uy = -g[0] }
            else -> { ux = g[0]; uy = g[1] }
        }
        val inPlane = sqrt(ux * ux + uy * uy)
        if (inPlane > 4f) {
            val deg = Math.toDegrees(Math.atan2((-ux).toDouble(), uy.toDouble())).toFloat()   // down on screen = (-ux, +uy)
            // snap to the nearest quarter turn once it's clearly there, with some stickiness so it doesn't flap
            val snapped = (Math.round(deg / 90f) * 90f + 360f) % 360f
            val off = abs(((deg - downDeg + 540f) % 360f) - 180f)
            if (off > 55f) downDeg = snapped
            onDown(downDeg)
        }

        // flat = gravity straight through the screen
        val gl = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (gl > 0f && Math.abs(g[2]) / gl > 0.88f && !handled) {
            if (flatSince == 0L) flatSince = now
            val mins = ((now - flatSince) / 60_000).toInt()
            if (now - flatSince > 120_000 && now - lastFlatMoan > 8 * 60_000) { lastFlatMoan = now; onLyingFlat(mins) }
        } else { flatSince = 0L; lastFlatMoan = 0L }
    }

    /** Pip's 3D-printed stand leans the screen back to 55 degrees from the bench. */
    var standDegrees = 55.0
    /** Sitting at the stand's angle (screen tilted 55 degrees from flat, give or take 10)? */
    fun onStand(): Boolean {
        val gl = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (gl < 5f || g[2] < 0f) return false                       // screen must face up-ish, not down
        val fromFlat = Math.toDegrees(Math.acos((g[2] / gl).toDouble().coerceIn(-1.0, 1.0)))
        return Math.abs(fromFlat - standDegrees) < 10.0
    }

    private fun angleBetween(a: FloatArray, b: FloatArray): Double {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val la = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); val lb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (la == 0f || lb == 0f) return 0.0
        return Math.toDegrees(Math.acos((dot / (la * lb)).toDouble().coerceIn(-1.0, 1.0)))
    }
}
