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
    private var autoMode = false              // opened by the motion watcher / power-up: focus, then close

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
        autoMode = intent?.getBooleanExtra("auto", false) == true
        if (autoMode) { status = "Refocusing…"; detail = when (val w = intent?.getStringExtra("why")) {
            null -> "Sharpening the picture"
            "power-up" -> "Just switched on — sharpening the picture"
            else -> "Projector was $w — sharpening the picture"
        } }
        else thread { Watcher.start(this, boot = false) }   // make sure the motion watcher is running with current settings
        thread(name = "start") { startUp() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("auto", false)) { autoMode = true; if (camReady) autoFocus() }
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
            if (autoMode) runOnUiThread { view.postDelayed({ finish() }, 6000) }
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
        if (opened.isEmpty()) {
            ui("Couldn't read the camera", "The log has the details. Press Back for the menu.")
            if (autoMode) runOnUiThread { view.postDelayed({ finish() }, 6000) }
            return
        }
        camReady = true
        log("camera: $opened")
        ui("Ready", "OK = auto focus   ◄ ► = focus by hand   ▲ ▼ = what to keep sharp   Back = menu")
        refreshLive()
        if (autoMode) runOnUiThread { autoFocus() }
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
            } finally {
                busy = false
                runOnUiThread {
                    view.invalidate()
                    if (autoMode) view.postDelayed({ if (!busy) finish() }, 2500)   // back to whatever was on before
                }
            }
        }
    }

    private fun runAuto() {
        log("=== auto focus, aim=$aim")
        ui("Focusing…", "Looking at the picture")
        val first = sample(4) ?: run { ui("Camera stopped", ""); return }
        val valid = validCells(first)
        log("contrast ${first.c.joinToString()}  valid=$valid")
        if (valid.isEmpty()) { ui("The camera can't see the picture", "Is the projector pointing at a wall? Try 'Show camera view' in the menu."); return }

        // 1) run the lens to its end stop: an exact starting point every time
        ui("Focusing…", "Resetting the lens")
        home()

        // 2) sweep across the whole range, measuring every part of the picture
        val step = 5
        val sweep = ArrayList<Sample>()
        var flat = 0
        var bestT = 0
        while (pos < 400 && !cancel) {
            val s = sample(4) ?: return
            sweep += s
            val t = total(s, valid)
            bestT = max(bestT, t)
            log("sweep pos=$pos total=$t cells=${s.s.joinToString()}")
            ui("Focusing…", "Sweeping the lens: ${sweep.size} readings, sharpest so far ${bestT}")
            if (sweep.size >= 2) {
                val prevT = total(sweep[sweep.size - 2], valid)
                flat = if (abs(t - prevT) <= max(3, prevT / 150)) flat + 1 else 0
            }
            // reached the other end stop
            if (flat >= 4 && pos > 40) { log("other end of travel at about ${pos - 4 * step}"); break }
            // well past the sharpest point for every part we care about -> no need to go further
            val peakIdx = sweep.indices.maxBy { total(sweep[it], valid) }
            if (sweep.size - peakIdx >= 5 && t < bestT * 0.55 &&
                valid.all { c -> s.s[c] < sweep.maxOf { it.s[c] } * 0.8 }) break
            move(step)
        }
        if (cancel || sweep.size < 3) return
        curve = sweep

        // 3) choose the lens position
        val cells = aimCells(valid)
        val peak = IntArray(9) { c -> sweep.maxOf { it.s[c] } }
        fun quality(s: Sample) = when (aim) {
            Aim.WHOLE -> cells.minOf { c -> s.s[c].toDouble() / max(1, peak[c]) }   // the worst part, as sharp as possible
            else -> cells.sumOf { c -> s.s[c].toDouble() / max(1, peak[c]) } / cells.size
        }
        // smooth a little so one noisy reading can't win
        val q = sweep.map { quality(it) }
        val qs = q.indices.map { i -> (q[max(0, i - 1)] + 2 * q[i] + q[min(q.size - 1, i + 1)]) / 4 }
        val bi = qs.indices.maxBy { qs[it] }
        // refine between the neighbours with a parabola
        var target = sweep[bi].pos
        if (bi in 1 until sweep.size - 1) {
            val a = qs[bi - 1]; val b = qs[bi]; val c = qs[bi + 1]
            val den = a - 2 * b + c
            if (den < 0) target += (step * 0.5 * (a - c) / den).toInt()
        }
        for (c in 0 until 9) {
            val pk = sweep.maxBy { it.s[c] }.pos
            log("cell $c sharpest at pos=$pk")
        }
        log("cells=$cells target=$target")

        // 4) back to the end stop, then straight to the target: always from the same side, so gear slack doesn't matter
        ui("Focusing…", "Setting the lens")
        home()
        move(target)
        val fin = sample(4)
        val finalQ = fin?.let { quality(it) } ?: 0.0
        val pct = (finalQ * 100).toInt().coerceIn(0, 100)
        prefs.edit().putInt("lastPos", pos).apply()
        // how differently the left and right of the picture want to be focused (the wall's angle)
        val left = listOf(0, 3, 6).filter { it in valid }.map { c -> sweep.maxBy { it.s[c] }.pos }.average()
        val right = listOf(2, 5, 8).filter { it in valid }.map { c -> sweep.maxBy { it.s[c] }.pos }.average()
        val spread = if (left.isNaN() || right.isNaN()) "" else "  (left/right differ by ${abs(right - left).toInt()} lens steps)"
        log("done at pos=$pos quality=$pct%$spread")
        ui("Focused", "${aim.title}: ${if (aim == Aim.WHOLE) "the blurriest part is" else "it's"} $pct% of its sharpest$spread")
    }

    /** Drive the lens to its end stop and call that position 0. */
    private fun home() {
        move(-450)
        pos = 0
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
        val onBoot = prefs.getBoolean("focusOnBoot", true)
        val onMove = prefs.getBoolean("focusOnMove", true)
        items += (if (onBoot) "✓ " else "✗ ") + "Refocus when switched on" to {
            prefs.edit().putBoolean("focusOnBoot", !onBoot).apply(); thread { Watcher.start(this, boot = false) }
            ui("Refocus when switched on: " + (if (!onBoot) "ON" else "OFF"), "")
        }
        items += (if (onMove) "✓ " else "✗ ") + "Refocus after the projector is moved" to {
            prefs.edit().putBoolean("focusOnMove", !onMove).apply(); thread { Watcher.start(this, boot = false) }
            ui("Refocus after moving: " + (if (!onMove) "ON" else "OFF"), "")
        }
        items += "Turn off the built-in autofocus (so they don't both run)" to { builtInAutofocus(false) }
        items += "Turn the built-in autofocus back on" to { builtInAutofocus(true) }
        items += "Help" to { help() }
        items += "Exit" to { finish() }
        dialog().setTitle("Sharp Focus")
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .setOnDismissListener { hideBars(); view.requestFocus() }
            .show()
    }

    private fun builtInAutofocus(on: Boolean) {
        thread {
            val v = if (on) "1" else "0"
            val a = link.ask("PROP persist.sys.vafocus $v")
            val b = link.ask("PROP persist.sys.upvafocus $v")
            log("built-in autofocus -> $v: $a / $b")
            ui("Built-in autofocus " + (if (on) "ON" else "OFF"), if (a.startsWith("OK") && b.startsWith("OK")) "Done." else "Couldn't change it: $a")
        }
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
                "Back: menu — you can show what the camera sees, which helps if it can't find the picture.\n\n" +
                "It also refocuses by itself when the projector is switched on, and after it's been moved or knocked " +
                "(once it's been still for a few seconds), then goes back to what you were watching. Both can be turned " +
                "off in the menu. Turn off the built-in autofocus in the menu so the two don't fight."
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
