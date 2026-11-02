package com.eyecompanion

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.FaceDetector
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.abs

/**
 * Watches the front camera a few times a second and reports where a face is, so the eyes can
 * follow people around the kitchen. Everything stays on the tablet; it only finds *where* a face
 * is, never who. Reports x/y in -1..1 from the screen's point of view, or null for no face.
 */
@Suppress("DEPRECATION")
class FaceTracker(private val onFace: (Float, Float) -> Unit, private val onNoFace: () -> Unit) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var cam: android.hardware.Camera? = null
    private var texture: SurfaceTexture? = null
    @Volatile var running = false
        private set
    @Volatile private var lastFrameAt = 0L
    @Volatile private var busy = false

    /** [displayDegrees] = how far the screen is rotated (0/90/180/270). */
    /** set if this tablet's camera couldn't be opened, so we don't keep trying */
    @Volatile var failed = false
        private set

    fun start(displayDegrees: Int) {
        if (running || failed) return
        running = true
        val t = HandlerThread("faces").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { h -> h.post { open(displayDegrees) } }
    }

    fun stop() {
        if (!running) return
        running = false
        val h = handler ?: return
        val done = java.util.concurrent.CountDownLatch(1)
        h.post { close(); done.countDown() }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        thread?.quitSafely(); thread = null; handler = null
    }

    private fun open(displayDegrees: Int) {
        try {
            val info = android.hardware.Camera.CameraInfo()
            var id = -1
            for (i in 0 until android.hardware.Camera.getNumberOfCameras()) {
                android.hardware.Camera.getCameraInfo(i, info)
                if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) { id = i; break }
            }
            if (id < 0) { running = false; failed = true; thread?.quitSafely(); return }
            val c = android.hardware.Camera.open(id)
            cam = c
            val p = c.parameters
            val size = p.supportedPreviewSizes.minByOrNull { abs(it.width - 640) + abs(it.height - 480) }!!
            p.setPreviewSize(size.width, size.height)
            p.supportedPreviewFpsRange?.minByOrNull { it[1] }?.let { p.setPreviewFpsRange(it[0], it[1]) }
            c.parameters = p
            // same rotation as Snapshot uses for the front camera
            val device = (360 - displayDegrees) % 360
            val rotation = (info.orientation - device + 360) % 360
            texture = SurfaceTexture(11)
            c.setPreviewTexture(texture)
            val w = size.width; val h = size.height
            val buf = ByteArray(w * h * 3 / 2)
            c.addCallbackBuffer(buf)
            c.setPreviewCallbackWithBuffer { data, camera ->
                val now = System.currentTimeMillis()
                if (!busy && now - lastFrameAt > 300) {
                    lastFrameAt = now; busy = true
                    try { look(data, w, h, rotation) } catch (_: Throwable) {}
                    busy = false
                }
                try { camera.addCallbackBuffer(data) } catch (_: Throwable) {}
            }
            c.startPreview()
        } catch (_: Throwable) {
            close(); running = false; failed = true; thread?.quitSafely()
        }
    }

    private fun close() {
        try { cam?.setPreviewCallbackWithBuffer(null) } catch (_: Throwable) {}
        try { cam?.stopPreview() } catch (_: Throwable) {}
        try { cam?.release() } catch (_: Throwable) {}
        cam = null
        texture?.release(); texture = null
    }

    /** Small, upright grey picture from the camera's brightness channel, then find a face in it. */
    private fun look(nv21: ByteArray, w: Int, h: Int, rotation: Int) {
        val step = maxOf(1, w / 200)             // about 200 pixels across is plenty
        val sw = (w / step) and 1.inv(); val sh = h / step
        val upright = rotation == 90 || rotation == 270
        val ow = if (upright) sh and 1.inv() else sw     // FaceDetector wants an even width
        val oh = if (upright) sw else sh
        val px = IntArray(ow * oh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val v = nv21[(y * step) * w + x * step].toInt() and 0xFF
            val (ox, oy) = when (rotation) {
                90 -> (sh - 1 - y) to x
                180 -> (sw - 1 - x) to (sh - 1 - y)
                270 -> y to (sw - 1 - x)
                else -> x to y
            }
            if (ox < ow && oy < oh) px[oy * ow + ox] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bmp = Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.RGB_565, false)
        val faces = arrayOfNulls<FaceDetector.Face>(3)
        val n = FaceDetector(ow, oh, faces.size).findFaces(bmp, faces)
        bmp.recycle()
        // follow the biggest (nearest) face
        val f = faces.take(n).filterNotNull().maxByOrNull { it.eyesDistance() }
        if (f == null) { onNoFace(); return }
        val mid = android.graphics.PointF(); f.getMidPoint(mid)
        // the camera faces the person, so their left is the picture's right: mirror x
        val x = -((mid.x / ow) * 2f - 1f)
        val y = (mid.y / oh) * 2f - 1f
        onFace(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
    }
}
