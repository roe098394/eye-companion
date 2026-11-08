package com.sharpfocus

/** JNI bridge to fcam.c (only used inside the root helper). */
object Cam {
    @JvmStatic external fun info(dev: String): String
    @JvmStatic external fun open(dev: String, w: Int, h: Int): Long
    @JvmStatic external fun width(h: Long): Int
    @JvmStatic external fun height(h: Long): Int
    @JvmStatic external fun fourcc(h: Long): Int
    @JvmStatic external fun grab(h: Long, skip: Int): ByteArray?
    @JvmStatic external fun setControl(h: Long, id: Int, value: Int): Int
    @JvmStatic external fun close(h: Long)
    @JvmStatic external fun lastError(): String

    const val MJPEG = 0x47504A4D // 'MJPG'

    fun fourccName(f: Int) = String(charArrayOf((f and 255).toChar(), (f shr 8 and 255).toChar(), (f shr 16 and 255).toChar(), (f shr 24 and 255).toChar()))
}
