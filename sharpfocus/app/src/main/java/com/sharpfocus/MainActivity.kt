package com.sharpfocus

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    enum class Aim(val title: String, val hint: String) {
        WHOLE("Whole picture", "evenly sharp everywhere — best for an angled wall"),
        CENTRE("Centre", "sharpest in the middle"),
        LEFT("Left side", "sharpest on the left"),
        RIGHT("Right side", "sharpest on the right"),
    }

    class Sample(val pos: Int, val bright: Int, val s: IntArray, val c: IntArray)

    private lateinit var view: FocusView
    private lateinit var link: RootLink
    private val prefs by lazy { getSharedPreferences("sharpfocus", MODE_PRIVATE) }

    @Volatile private var busy = false
    @Volatile private var cancel = false
    private var aim = Aim.WHOLE
    private var status = "Starting… (root access needed)"
    private var detail = ""
    private var last: Sample? = null
    private var preview: Bitmap? = null
    private var showCam = false
    private var pos = 0                         // lens position relative to where we started
    private var curve: List<Sample> = emptyList()
    private var camReady = false

    private val logFile by lazy { File(getExternalFilesDir(null), "sharpfocus-log.txt") }
    private fun log(s: String) {
        try { logFile.appendText(SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date()) + " " + s + "\n") } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        aim = Aim.values().getOrElse(prefs.getInt("aim", 0)) { Aim.WHOLE }
        view = FocusView(this)
        setContentView(view)
        view.requestFocus()
        link = RootLink(this)
        thread(name = "start") { startUp() }
    }

    override fun onResume() { super.onResume(); hideBars() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }
    override fun onDestroy() { cancel = true; thread { link.ask("STOP"); link.stop() }; super.onDestroy() }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun ui(s: String, d: String = detail) { status = s; detail = d; runOnUiThread { view.invalidate() } }

    // ---------------------------------------------------------------- start-up

    private fun startUp() {
        if (!link.start()) {
            ui("No root access", (link.lastError ?: "") + "  —  Sharp Focus needs root (Magisk) to move the lens and read the camera.")
            log("root failed: ${link.lastError}")
            return
        }
        val info = link.ask("INFO").removePrefix("I ").replace("|", "\n")
        log("INFO\n$info")
        var opened = ""
        for (dev in listOf("/dev/video0", "/dev/video1", "/dev/video2")) {
            for ((w, h) in listOf(640 to 480, 640 to 360, 320 to 240)) {
                val r = link.ask("OPEN $dev $w $h")
                log("OPEN $dev $w $h -> $r")
                if (r.startsWith("OK")) { opened = "$dev $r"; break }
            }
            if (opened.isNotEmpty()) {
                val s = sample(3)
                if (s != null) break
                opened = ""
            }
        }
        if (opened.isEmpty()) { ui("Couldn't read the camera", "The log has the details. Press Back for the menu."); return }
        camReady = true
        log("camera: $opened")
        ui("Ready", "OK = auto focus   ◄ ► = focus by hand   ▲ ▼ = what to keep sharp   Back = menu")
        refreshLive()
    }

    private fun sample(skip: Int): Sample? {
        val r = link.ask("SCORE $skip")
        if (!r.startsWith("S ")) { log("SCORE -> $r"); return null }
        val v = r.substring(2).trim().split(" ").map { it.toInt() }
        return Sample(pos, v[0], v.subList(1, 10).toIntArray(), v.subList(10, 19).toIntArray()).also { last = it }
    }

    private fun move(n: Int) {
        var left = n
        while (left != 0) {                       // the motor takes at most 255 steps per command
            val chunk = left.coerceIn(-200, 200)
            link.ask("MOVE $chunk")
            pos += chunk; left -= chunk
        }
    }

    private fun refreshLive() {
        if (!camReady || busy) return
        thread {
            sample(2)
            if (showCam) grabPreview()
            runOnUiThread { view.invalidate() }
        }
    }

    private fun grabPreview() {
        val r = link.ask("SNAP")
        if (!r.startsWith("IMG ")) return
        val p = r.split(" ")
        val w = p[1].toInt(); val h = p[2].toInt(); val hex = p[3]
        val px = IntArray(w * h) { i -> val v = Character.digit(hex[i * 2], 16) * 16 + Character.digit(hex[i * 2 + 1], 16); Color.rgb(v, v, v) }
        preview = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------- the focus search

    /** Which cells we care about, given what the camera can actually see. */
    private fun validCells(s: Sample): List<Int> {
        val maxC = s.c.max()
        return (0 until 9).filter { s.c[it] >= max(40, maxC / 4) }
    }

    private fun aimCells(valid: List<Int>): List<Int> {
        val pick = when (aim) {
            Aim.WHOLE -> valid
            Aim.CENTRE -> valid.filter { it == 4 }.ifEmpty { valid.filter { it % 3 == 1 } }
            Aim.LEFT -> valid.filter { it % 3 == 0 }
            Aim.RIGHT -> valid.filter { it % 3 == 2 }
        }
        return pick.ifEmpty { valid }
    }

    private fun total(s: Sample, cells: List<Int>) = cells.sumOf { s.s[it] }

    private fun autoFocus() {
        if (busy || !camReady) return
        busy = true; cancel = false
        thread(name = "autofocus") {
            try {
                runAuto()
            } catch (e: Exception) {
                log("auto failed: $e"); ui("Something went wrong", e.message ?: "")
            } finally { busy = false; runOnUiThread { view.invalidate() } }
        }
    }

    private fun runAuto() {
        log("=== auto focus, aim=$aim")
        ui("Focusing…", "Looking for the sharpest point")
        val start = sample(4) ?: run { ui("Camera stopped", ""); return }
        val valid = validCells(start)
        log("contrast ${start.c.joinToString()}  valid=$valid")
        if (valid.isEmpty()) { ui("The camera can't see the picture", "Is the projector pointing at a wall? Try 'Show camera' in the menu."); return }

        // 1) coarse: find which way gets sharper, then follow it past the peak
        val coarse = 10
        val seen = ArrayList<Sample>(); seen += start
        move(coarse); val plus = sample(4)!!; seen += plus
        var dir = 1
        if (total(plus, valid) < total(start, valid) * 0.97) {
            move(-2 * coarse); val minus = sample(4)!!; seen += minus
            if (total(minus, valid) > total(plus, valid)) dir = -1 else { move(2 * coarse) }
        }
        var best = seen.maxBy { total(it, valid) }
        var fall = 0; var flat = 0; var prev = total(seen.last(), valid)
        for (i in 0 until 40) {
            if (cancel) return
            move(dir * coarse)
            val s = sample(4) ?: return
            seen += s
            val t = total(s, valid)
            if (t > total(best, valid)) best = s
            fall = if (t < prev) fall + 1 else 0
            flat = if (abs(t - prev) <= max(3, prev / 100)) flat + 1 else 0
            prev = t
            ui("Focusing…", "Searching: step ${i + 1}, best so far at ${best.pos}")
            log("coarse pos=$pos total=$t cells=${s.s.joinToString()}")
            if (fall >= 3 && t < total(best, valid) * 0.75) break
            if (flat >= 5) { log("lens stopped moving (end of travel)"); break }
        }
        if (cancel) return

        // 2) fine sweep around the peak, always moving the same way so gear slack doesn't fool us
        val span = 4 * coarse
        val fine = 4
        val from = best.pos - span
        move(from - pos - 12); move(12)            // approach the start of the sweep from below
        val sweep = ArrayList<Sample>()
        while (pos <= best.pos + span && !cancel) {
            val s = sample(4) ?: return
            sweep += s
            log("fine pos=$pos cells=${s.s.joinToString()}")
            ui("Fine tuning…", "Checking every part of the picture")
            move(fine)
        }
        if (cancel || sweep.isEmpty()) return
        curve = sweep

        // 3) choose the lens position
        val cells = aimCells(validCells(sweep.maxBy { it.c.sum() }))
        val peak = IntArray(9) { c -> sweep.maxOf { it.s[c] } }
        fun quality(s: Sample) = when (aim) {
            Aim.WHOLE -> cells.minOf { c -> s.s[c].toDouble() / max(1, peak[c]) }   // the worst part, as sharp as possible
            else -> cells.sumOf { c -> s.s[c].toDouble() / max(1, peak[c]) } / cells.size
        }
        val target = sweep.maxBy { quality(it) }
        log("cells=$cells target=${target.pos} quality=${"%.2f".format(quality(target))}")

        // 4) go there, approaching from the same side as the sweep
        move(target.pos - pos - 12); move(12)
        val fin = sample(4)
        val q = fin?.let { quality(it) } ?: 0.0
        val pct = (q * 100).toInt().coerceIn(0, 100)
        prefs.edit().putInt("lastPos", pos).apply()
        log("done at pos=$pos quality=$pct%")
        ui("Focused", "${aim.title}: worst part is $pct% of its best possible sharpness.  OK = again   ◄ ► = fine tune")
    }

    // ---------------------------------------------------------------- remote

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { showMenu(); return true }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) { if (busy) cancel = true else autoFocus() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (busy || !camReady) return true
                val n = (if (event.repeatCount > 3) 8 else 2) * (if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                busy = true
                thread { try { move(n); sample(2); if (showCam) grabPreview() } finally { busy = false; runOnUiThread { view.invalidate() } } }
                ui("Manual focus", "◄ ► move the lens (hold for bigger steps)   OK = auto focus")
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.repeatCount == 0) {
                    val d = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    aim = Aim.values()[(aim.ordinal + d + Aim.values().size) % Aim.values().size]
                    prefs.edit().putInt("aim", aim.ordinal).apply()
                    ui("Keep sharp: ${aim.title}", aim.hint + "   —   OK to focus")
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (busy) cancel = true else showMenu() }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showMenu() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "Auto focus now" to { autoFocus() }
        for (a in Aim.values()) items += ((if (a == aim) "● " else "    ") + "Keep sharp: " + a.title) to {
            aim = a; prefs.edit().putInt("aim", a.ordinal).apply(); ui("Keep sharp: ${a.title}", a.hint + "   —   OK to focus")
        }
        items += (if (showCam) "Hide camera view" else "Show camera view") to { showCam = !showCam; refreshLive() }
        items += "Help" to { help() }
        items += "Exit" to { finish() }
        dialog().setTitle("Sharp Focus")
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .setOnDismissListener { hideBars(); view.requestFocus() }
            .show()
    }

    private fun help() {
        dialog().setTitle("How Sharp Focus works")
            .setMessage(
                "It shows a fine chequered pattern, watches it with the projector's camera, and moves the focus lens " +
                "to find the sharpest point.\n\n" +
                "Unlike the built-in autofocus it checks 9 parts of the picture separately. On an angled wall one side " +
                "is closer than the other, so no single setting is perfect everywhere — \"Whole picture\" picks the point " +
                "where the blurriest part is as sharp as it can be, so it's evenly sharp across.\n\n" +
                "OK: auto focus (press again to stop)\n◄ ►: focus by hand\n▲ ▼: what to keep sharp (whole picture, centre, left, right)\n" +
                "Back: menu — you can show what the camera sees, which helps if it can't find the picture."
            )
            .setPositiveButton("OK", null)
            .setOnDismissListener { hideBars(); view.requestFocus() }
            .show()
    }

    // ---------------------------------------------------------------- the pattern on the wall

    inner class FocusView(context: Context) : View(context) {
        private val fill = Paint()
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
        private val d = resources.displayMetrics.density
        private val bmpPaint = Paint().apply { isFilterBitmap = false }

        init { isFocusable = true; isFocusableInTouchMode = true }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            // fine chequerboard: lots of sharp edges for the camera to judge
            val sq = 24f
            c.drawColor(Color.BLACK)
            fill.color = Color.WHITE
            var yy = 0f; var row = 0
            while (yy < h) {
                var xx = if (row % 2 == 0) 0f else sq
                while (xx < w) { c.drawRect(xx, yy, xx + sq, yy + sq, fill); xx += sq * 2 }
                yy += sq; row++
            }

            // status panel in the middle
            val pw = min(w * 0.7f, 900f * d / 2f + 300f); val ph = 120f * d
            val panel = RectF(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
            fill.color = Color.argb(225, 10, 16, 24)
            c.drawRoundRect(panel, 16f * d, 16f * d, fill)
            text.textAlign = Paint.Align.CENTER
            text.textSize = 26f * d; text.color = Color.WHITE
            c.drawText("Sharp Focus — $status", w / 2, panel.top + 38f * d, text)
            text.textSize = 15f * d; text.color = Color.rgb(190, 210, 220)
            drawWrapped(c, detail, w / 2, panel.top + 66f * d, pw - 30f * d)
            text.textSize = 14f * d; text.color = Color.rgb(46, 196, 182)
            c.drawText("Keep sharp: ${aim.title}" + (if (busy) "   •   working (OK/Back to stop)" else ""), w / 2, panel.bottom - 14f * d, text)

            // 3x3 sharpness bars (what the camera measures right now)
            last?.let { s ->
                val bw = 18f * d; val bh = 50f * d; val gap = 6f * d
                val ox = panel.right - 3 * (bw + gap) - 14f * d; val oy = panel.top + 12f * d
                val mx = max(1, s.s.max())
                for (i in 0 until 9) {
                    val x = ox + (i % 3) * (bw + gap); val y = oy + (i / 3) * (bh / 3 + gap / 2)
                    fill.color = Color.rgb(40, 50, 60); c.drawRect(x, y, x + bw, y + bh / 3, fill)
                    val f = s.s[i].toFloat() / mx
                    fill.color = if (s.c[i] < max(40, s.c.max() / 4)) Color.rgb(90, 90, 90) else Color.rgb((255 * (1 - f)).toInt(), (220 * f).toInt() + 30, 80)
                    c.drawRect(x, y + (bh / 3) * (1 - f), x + bw, y + bh / 3, fill)
                }
            }

            // what the camera sees (optional)
            if (showCam) preview?.let { b ->
                val pw2 = 240f * d; val ph2 = pw2 * b.height / b.width
                val dst = RectF(panel.left, panel.bottom + 10f * d, panel.left + pw2, panel.bottom + 10f * d + ph2)
                c.drawBitmap(b, Rect(0, 0, b.width, b.height), dst, bmpPaint)
            }
        }

        private fun drawWrapped(c: Canvas, s: String, cx: Float, y0: Float, maxW: Float) {
            var y = y0
            val words = s.split(" ")
            var line = ""
            for (wd in words) {
                val t = if (line.isEmpty()) wd else "$line $wd"
                if (text.measureText(t) > maxW && line.isNotEmpty()) { c.drawText(line, cx, y, text); y += text.textSize * 1.3f; line = wd }
                else line = t
            }
            if (line.isNotEmpty()) c.drawText(line, cx, y, text)
        }
    }
}
