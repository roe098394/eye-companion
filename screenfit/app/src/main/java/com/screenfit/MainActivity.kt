package com.screenfit

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import kotlin.concurrent.thread
import kotlin.math.min

class MainActivity : Activity() {

    enum class Mode(val title: String, val hint: String) {
        SIZE("Size", "▲ ► bigger   ▼ ◄ smaller"),
        MOVE("Move", "Arrows move the whole picture"),
        TILT("Tilt / keystone", "▲▼ top or bottom narrower   ◄► left or right side shorter"),
        ROTATE("Straighten", "◄► turn the picture a little"),
        CORNERS("Corners", "OK picks a corner   arrows move it"),
    }

    private lateinit var view: FitView
    private lateinit var link: RootLink
    private val prefs by lazy { getSharedPreferences("screenfit", MODE_PRIVATE) }

    private var shape: Shape? = null
    private var opened: Shape? = null        // how it was when the app was opened
    private var mode = Mode.SIZE
    private var corner = 0
    private var status = "Asking for root access… (accept the Magisk prompt)"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = FitView(this)
        setContentView(view)
        view.requestFocus()

        link = RootLink(this)
        link.onStatus = { msg -> runOnUiThread { status = msg; view.invalidate() } }
        thread(name = "root-start") {
            val ok = link.start()
            val saved = if (ok) link.readSaved() else null
            runOnUiThread {
                if (!ok || saved == null || saved.size < 10) {
                    status = (link.lastError ?: "Couldn't read the projector's settings") +
                        "\nScreen Fit needs root (Magisk) to change the projector's keystone. Press Back for the menu."
                } else {
                    val s = Shape(saved[0], saved[1])
                    s.fromInsets(saved.copyOfRange(2, 10))
                    shape = s
                    opened = Shape(saved[0], saved[1]).also { it.copyFrom(s) }
                    if (!prefs.contains("original")) {
                        prefs.edit().putString("original", saved.copyOfRange(2, 10).joinToString(",")).apply()
                    }
                    status = ""
                }
                view.invalidate()
            }
        }
    }

    override fun onResume() { super.onResume(); hideBars() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }
    override fun onDestroy() { link.stop(); super.onDestroy() }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun push() {
        shape?.let { link.apply(it.toInsets()) }
        view.invalidate()
    }

    // ---------------------------------------------------------------- remote control

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { showMenu(); return true }
        val s = shape ?: return super.onKeyDown(keyCode, event)

        // hold an arrow down and it speeds up
        val step = 1f + min(event.repeatCount, 25) * 0.6f
        var dx = 0f; var dy = 0f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> dy = -step
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = step
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = step
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) {
                    if (mode == Mode.CORNERS) corner = (corner + 1) % 4
                    else mode = Mode.values()[(mode.ordinal + 1) % Mode.values().size]
                    view.invalidate()
                }
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { mode = Mode.values()[(mode.ordinal + 1) % Mode.values().size]; view.invalidate(); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { mode = Mode.values()[(mode.ordinal + Mode.values().size - 1) % Mode.values().size]; view.invalidate(); return true }
            else -> return super.onKeyDown(keyCode, event)
        }
        when (mode) {
            Mode.SIZE -> s.grow(if (dx > 0 || dy < 0) step * 2 else -step * 2)
            Mode.MOVE -> s.shift(dx, dy)
            Mode.TILT -> if (dy != 0f) s.tiltVertical(if (dy < 0) step else -step) else s.tiltHorizontal(if (dx < 0) step else -step)
            Mode.ROTATE -> if (dx != 0f) s.rotate(if (dx > 0) step else -step)
            Mode.CORNERS -> s.moveCorner(corner, dx, dy)
        }
        push()
        return true
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { showMenu() }

    // ---------------------------------------------------------------- menu

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showMenu() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (shape != null) {
            for (m in Mode.values()) items += ((if (m == mode) "● " else "    ") + m.title) to { mode = m; view.invalidate() }
            items += "Undo changes (back to how it was when opened)" to {
                opened?.let { o -> shape?.copyFrom(o); push() }
            }
            items += "Straight picture (no correction)" to {
                confirm("Remove all keystone correction? You can line it up again afterwards.") { shape?.flat(); push() }
            }
            val orig = prefs.getString("original", null)
            if (orig != null) items += "Back to the first settings Screen Fit ever saw" to {
                confirm("Put the picture back to how it was before you first used Screen Fit?") {
                    val v = orig.split(",").map { it.trim().toInt() }.toIntArray()
                    shape?.fromInsets(v); push()
                }
            }
        }
        items += "Help" to { showHelp() }
        items += "Exit (keeps your settings)" to { finish() }

        dialog().setTitle("Screen Fit")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setOnDismissListener { hideBars(); view.requestFocus() }
            .show()
    }

    private fun confirm(q: String, action: () -> Unit) {
        dialog().setMessage(q)
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("No", null)
            .setOnDismissListener { hideBars(); view.requestFocus() }
            .show()
    }

    private fun showHelp() {
        dialog().setTitle("How to use Screen Fit")
            .setMessage(
                "The grid fills the whole picture, so its white border shows exactly where the picture's edges are.\n\n" +
                "OK switches between: Size, Move, Tilt, Straighten and Corners. (In Corners, OK picks the next corner.)\n\n" +
                "• Size: ▲/► bigger, ▼/◄ smaller.\n" +
                "• Move: shift the picture around (only once it's smaller than full size).\n" +
                "• Tilt: ▲▼ fixes a picture that's wider at the top or bottom; ◄► fixes one side being taller.\n" +
                "• Straighten: ◄► turns the picture a tiny bit if it's on a slant.\n" +
                "• Corners: move each corner on its own for awkward walls.\n\n" +
                "Hold an arrow to go faster. Changes happen straight away and are saved, so they stay after a restart and work in every app.\n\n" +
                "Back opens the menu: undo, straight picture, or exit."
            )
            .setPositiveButton("OK", null)
            .setOnDismissListener { hideBars(); view.requestFocus() }
            .show()
    }

    // ---------------------------------------------------------------- test pattern

    inner class FitView(context: Context) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
        private val d = resources.displayMetrics.density

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.BLACK)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawColor(Color.rgb(12, 20, 28))

            // grid
            line.color = Color.rgb(60, 80, 96); line.strokeWidth = 1.5f * d
            val cols = 16; val rows = 9
            for (i in 1 until cols) c.drawLine(w * i / cols, 0f, w * i / cols, h, line)
            for (j in 1 until rows) c.drawLine(0f, h * j / rows, w, h * j / rows, line)

            // centre cross and circle (a circle that looks round = no stretching)
            line.color = Color.rgb(46, 196, 182); line.strokeWidth = 3f * d
            c.drawLine(w / 2, 0f, w / 2, h, line); c.drawLine(0f, h / 2, w, h / 2, line)
            c.drawCircle(w / 2, h / 2, h * 0.3f, line)

            // white border right at the edge of the picture
            line.color = Color.WHITE; line.strokeWidth = 6f * d
            c.drawRect(line.strokeWidth / 2, line.strokeWidth / 2, w - line.strokeWidth / 2, h - line.strokeWidth / 2, line)

            // corner markers (highlight the selected one in Corners mode)
            val m = 70f * d
            val corners = arrayOf(floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(w, 0f, -1f, 1f), floatArrayOf(0f, h, 1f, -1f), floatArrayOf(w, h, -1f, -1f))
            for ((i, k) in corners.withIndex()) {
                val sel = mode == Mode.CORNERS && i == corner
                fill.color = if (sel) Color.rgb(255, 209, 102) else Color.rgb(46, 196, 182)
                c.drawRect(min(k[0], k[0] + k[2] * m), min(k[1], k[1] + k[3] * m),
                    maxOf(k[0], k[0] + k[2] * m), maxOf(k[1], k[1] + k[3] * m), fill)
            }

            // labels
            text.textAlign = Paint.Align.CENTER
            text.textSize = 34f * d
            text.color = Color.WHITE
            val s = shape
            if (s == null || status.isNotEmpty()) {
                text.textSize = 22f * d
                var y = h / 2 - 40f * d
                for (l in status.ifEmpty { "Starting…" }.split("\n")) { c.drawText(l, w / 2, y, text); y += 32f * d }
                return
            }
            c.drawText("Screen Fit — ${mode.title}", w / 2, h * 0.30f, text)
            text.textSize = 20f * d
            text.color = Color.rgb(200, 220, 230)
            c.drawText(mode.hint, w / 2, h * 0.30f + 36f * d, text)
            val cornerNames = arrayOf("top-left", "top-right", "bottom-left", "bottom-right")
            if (mode == Mode.CORNERS) {
                text.color = Color.rgb(255, 209, 102)
                c.drawText("Moving the ${cornerNames[corner]} corner", w / 2, h * 0.30f + 70f * d, text)
            }
            text.color = Color.rgb(160, 180, 190)
            text.textSize = 18f * d
            c.drawText("Picture width ${s.widthPercent()}%   •   OK: next control   •   Back: menu", w / 2, h * 0.72f, text)
            link.lastError?.let {
                text.color = Color.rgb(255, 120, 120)
                c.drawText("Problem: $it", w / 2, h * 0.72f + 30f * d, text)
            }
        }
    }
}
