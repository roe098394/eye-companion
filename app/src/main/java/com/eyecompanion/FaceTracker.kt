package com.eyecompanion

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs

/**
 * Watches the front camera about 8 times a second so the eyes can follow people around the
 * kitchen. Uses Google's on-device face finder (works with heads turned, in poorer light, and
 * notices smiles). With no face in view it follows movement instead, so Pip glances at whoever
 * walks past. Everything stays on the tablet; it finds *where* a face is, never who.
 * x/y are -1..1 from the screen's point of view; size is the face's width as a share of the picture.
 */
@Suppress("DEPRECATION")
class FaceTracker(
    private val onFace: (x: Float, y: Float, size: Float, smile: Float) -> Unit,
    private val onNoFace: () -> Unit,
    private val onMotion: (x: Float, y: Float) -> Unit = { _, _ -> },
) {
    /** set if the picture comes out mirrored on this tablet (eyes look the wrong way) */
    @Volatile var flip = false

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var cam: android.hardware.Camera? = null
    private var texture: SurfaceTexture? = null
    @Volatile var running = false
        private set
    @Volatile var failed = false
        private set
    @Volatile private var lastFrameAt = 0L
    @Volatile private var busy = false
    private var prevGrid: IntArray? = null
    @Volatile private var grab: ((ByteArray?) -> Unit)? = null

    /** Hand back the next camera picture as a small upright JPEG (without stopping tracking). */
    fun grabJpeg(onDone: (ByteArray?) -> Unit) { if (running) grab = onDone else onDone(null) }

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build()
        )
    }

    /** [displayDegrees] = how far the screen is rotated (0/90/180/270). */
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
            if (p.supportedFocusModes?.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true)
                p.focusMode = android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            c.parameters = p
            // the rotation that makes the picture upright (same as Snapshot uses)
            val rotation = (info.orientation + displayDegrees) % 360
            texture = SurfaceTexture(11)
            c.setPreviewTexture(texture)
            val w = size.width; val h = size.height
            c.addCallbackBuffer(ByteArray(w * h * 3 / 2))
            c.addCallbackBuffer(ByteArray(w * h * 3 / 2))
            c.setPreviewCallbackWithBuffer { data, camera ->
                grab?.let { g ->
                    grab = null
                    val jpeg = try {
                        val out = java.io.ByteArrayOutputStream()
                        android.graphics.YuvImage(data, android.graphics.ImageFormat.NV21, w, h, null)
                            .compressToJpeg(android.graphics.Rect(0, 0, w, h), 85, out)
                        Snapshot.shrink(out.toByteArray(), rotation)
                    } catch (_: Throwable) { null }
                    g(jpeg)
                }
                val now = System.currentTimeMillis()
                if (!busy && now - lastFrameAt > 120) {
                    lastFrameAt = now; busy = true
                    val copy = data.copyOf()
                    try { look(copy, w, h, rotation) } catch (_: Throwable) { busy = false }
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
        prevGrid = null
    }

    /** picture position (0..1, upright) → screen -1..1; the camera faces the person, so mirror x */
    private fun toScreenX(u: Float) = (if (flip) 1f else -1f) * (u * 2f - 1f)

    private fun look(nv21: ByteArray, w: Int, h: Int, rotation: Int) {
        val upright = rotation == 90 || rotation == 270
        val uw = if (upright) h else w; val uh = if (upright) w else h
        detector.process(InputImage.fromByteArray(nv21, w, h, rotation, InputImage.IMAGE_FORMAT_NV21))
            .addOnSuccessListener { faces ->
                val f = faces.maxByOrNull { it.boundingBox.width() }
                if (f != null) {
                    val b = f.boundingBox
                    val x = toScreenX(b.exactCenterX() / uw).coerceIn(-1f, 1f)
                    val y = ((b.exactCenterY() / uh) * 2f - 1f).coerceIn(-1f, 1f)
                    onFace(x, y, b.width().toFloat() / uw, f.smilingProbability ?: 0f)
                } else onNoFace()
                movement(nv21, w, h, rotation)          // the eyes follow movement, face or not
            }
            .addOnFailureListener { onNoFace() }
            .addOnCompleteListener { busy = false }
    }

    /** No face: look for something moving (someone walking past) by comparing with the last picture. */
    private fun movement(nv21: ByteArray, w: Int, h: Int, rotation: Int) {
        val gw = 32; val gh = 24
        val grid = IntArray(gw * gh) { i -> nv21[((i / gw) * h / gh) * w + (i % gw) * w / gw].toInt() and 0xFF }
        val prev = prevGrid
        prevGrid = grid
        if (prev == null) return
        var n = 0; var sx = 0f; var sy = 0f
        for (i in grid.indices) if (abs(grid[i] - prev[i]) > 28) { n++; sx += (i % gw + 0.5f) / gw; sy += (i / gw + 0.5f) / gh }
        // a few percent of the picture changed, but not all of it (that's a light switching)
        if (n < gw * gh / 40 || n > gw * gh / 2) return
        val u = sx / n; val v = sy / n
        val (ux, uy) = when (rotation) {
            90 -> (1f - v) to u
            180 -> (1f - u) to (1f - v)
            270 -> v to (1f - u)
            else -> u to v
        }
        onMotion(toScreenX(ux), (uy * 2f - 1f))
    }
}
