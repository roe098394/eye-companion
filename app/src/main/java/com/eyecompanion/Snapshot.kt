package com.eyecompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/** Takes one photo with the front camera, only when asked ("what can you see?"). Nothing is stored. */
@Suppress("DEPRECATION")
object Snapshot {

    /** [displayDegrees] = how far the screen is rotated (0/90/180/270). Result is a small JPEG, or null. */
    fun take(displayDegrees: Int, onDone: (ByteArray?) -> Unit) = thread(name = "snapshot") {
        var cam: android.hardware.Camera? = null
        try {
            val info = android.hardware.Camera.CameraInfo()
            var id = -1
            for (i in 0 until android.hardware.Camera.getNumberOfCameras()) {
                android.hardware.Camera.getCameraInfo(i, info)
                if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) { id = i; break }
            }
            if (id < 0) { id = 0; android.hardware.Camera.getCameraInfo(0, info) }
            cam = android.hardware.Camera.open(id)
            val p = cam.parameters
            p.supportedPictureSizes.minByOrNull { abs(it.width - 1280) }?.let { p.setPictureSize(it.width, it.height) }
            p.jpegQuality = 85
            cam.parameters = p
            cam.setPreviewTexture(SurfaceTexture(10))
            cam.startPreview()
            Thread.sleep(900)                       // let the exposure settle
            val latch = CountDownLatch(1)
            var jpeg: ByteArray? = null
            cam.takePicture(null, null) { data, _ -> jpeg = data; latch.countDown() }
            latch.await(6, TimeUnit.SECONDS)
            val device = (360 - displayDegrees) % 360
            val rotation = if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT)
                (info.orientation - device + 360) % 360 else (info.orientation + device) % 360
            onDone(jpeg?.let { shrink(it, rotation) })
        } catch (_: Throwable) {
            onDone(null)
        } finally {
            try { cam?.stopPreview() } catch (_: Throwable) {}
            cam?.release()
        }
    }

    /** Upright, at most 768 px across: plenty for describing, quick to send. */
    fun shrink(jpeg: ByteArray, rotation: Int): ByteArray {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val scale = 768f / maxOf(src.width, src.height)
        val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (rotation != 0) postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }
}
