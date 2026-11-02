package com.eyecompanion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Uses the tablet's motion sensor to notice being picked up, shaken, tilted and put back down.
 * Tuned so doors closing or the speaker thumping don't count: a pick-up needs steady movement
 * after the tablet has been still for a while.
 */
class Handling(context: Context, private val displayDegrees: () -> Int) : SensorEventListener {

    var onPickedUp: () -> Unit = {}
    var onShaken: () -> Unit = {}
    var onPutDown: () -> Unit = {}
    /** -1..1, how far the screen leans: + = right side lower */
    var onTilt: (Float) -> Unit = {}

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
            // put down: still for 2.5 s after being handled
            if (handled && now - lastHandledMove > 2500) { handled = false; onPutDown() }
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
    }
}
