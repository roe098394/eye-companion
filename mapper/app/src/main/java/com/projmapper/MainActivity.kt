package com.projmapper

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONArray
import kotlin.concurrent.thread
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var map: MapView
    private val prefs by lazy { getSharedPreferences("mapper", MODE_PRIVATE) }
    private val pickPicture = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        map = MapView(this)
        map.onChanged = { save() }
        setContentView(map)
        load()
        map.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        hideBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // ---------------------------------------------------------------- save / load

    private fun save() {
        val arr = JSONArray()
        map.surfaces.forEach { arr.put(it.toJson()) }
        prefs.edit()
            .putString("surfaces", arr.toString())
            .putInt("current", map.current)
            .putBoolean("editing", map.editing)
            .apply()
    }

    private fun load() {
        try {
            val arr = JSONArray(prefs.getString("surfaces", "[]"))
            for (i in 0 until arr.length()) map.surfaces.add(Surface.fromJson(arr.getJSONObject(i)))
        } catch (e: Exception) {
        }
        if (map.surfaces.isEmpty()) {
            map.surfaces.add(Surface.centred(0.5f, 0.5f, 0.2f, 1, 200f))
            map.editing = true
        } else {
            map.editing = prefs.getBoolean("editing", true)
        }
        map.current = prefs.getInt("current", 0).coerceIn(0, map.surfaces.size - 1)
        map.surfaces.forEach { s -> s.image?.let { loadPicture(it) } }
    }

    // ---------------------------------------------------------------- remote control

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { showMenu(); return true }

        if (!map.editing) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    map.editing = true; return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        // hold an arrow down and it speeds up
        val step = 1f + min(event.repeatCount, 30) * 0.8f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> map.nudge(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> map.nudge(0f, step)
            KeyEvent.KEYCODE_DPAD_LEFT -> map.nudge(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> map.nudge(step, 0f)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (event.repeatCount == 0) map.nextHandle()
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> nextSurface(1)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> nextSurface(-1)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (map.editing && keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            save(); return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!map.editing) map.editing = true else showMenu()
    }

    private fun nextSurface(dir: Int) {
        if (map.surfaces.isEmpty()) return
        map.current = (map.current + dir + map.surfaces.size) % map.surfaces.size
        map.handle = 0
    }

    // ---------------------------------------------------------------- menu

    private fun showMenu() {
        val s = map.cur()
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "▶  Play (hide outlines)" to { map.editing = false; map.showGrid = false; save() }
        if (s != null) {
            items += "Change effect  (now: ${MapView.EFFECTS.getOrElse(s.effect) { "?" }})" to { chooseEffect() }
            items += "Choose a picture for this surface" to { choosePicture() }
            items += "Change colour" to { s.hue = (s.hue + 45f) % 360f; save() }
        }
        if (map.surfaces.size > 1) items += "Next surface" to { nextSurface(1) }
        items += "Add a surface" to { addSurface() }
        if (s != null) items += "Copy this surface" to { copySurface(s) }
        if (s != null) items += "Delete this surface" to { confirm("Delete surface ${map.current + 1}?") { deleteSurface() } }
        items += (if (map.showGrid) "Hide lining-up grid" else "Show lining-up grid") to { map.showGrid = !map.showGrid }
        items += "Start again (delete all)" to { confirm("Delete every surface and start again?") { resetAll() } }
        items += "Help" to { showHelp() }
        items += "Exit app" to { save(); finish() }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Projection Mapper")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setOnDismissListener { hideBars(); map.requestFocus() }
            .show()
    }

    private fun chooseEffect() {
        val s = map.cur() ?: return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Effect for surface ${map.current + 1}")
            .setSingleChoiceItems(MapView.EFFECTS, s.effect) { d, which ->
                d.dismiss()
                if (which == MapView.FX_PICTURE && s.image == null) choosePicture()
                else { s.effect = which; save() }
            }
            .setOnDismissListener { hideBars(); map.requestFocus() }
            .show()
    }

    private fun confirm(question: String, action: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(question)
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("No", null)
            .setOnDismissListener { hideBars(); map.requestFocus() }
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("How to use it")
            .setMessage(
                "1. Turn the projector's keystone / auto-correction OFF (or to zero) first.\n\n" +
                "2. Each surface is a shape with 4 corners. Press OK to pick a corner (the yellow dot), " +
                "then use the arrows to move it onto the corner of your object. Hold an arrow to go faster. " +
                "After the 4th corner, OK selects the whole surface so you can move it all at once.\n\n" +
                "3. Back opens the menu: add more surfaces, change what each one shows, or pick a picture.\n\n" +
                "4. Choose Play to hide the outlines. Press OK or Back to edit again.\n\n" +
                "Channel up/down jumps between surfaces. On a touch screen you can drag the corners with your finger.\n\n" +
                "Your layout is saved automatically."
            )
            .setPositiveButton("OK", null)
            .setOnDismissListener { hideBars(); map.requestFocus() }
            .show()
    }

    private fun addSurface() {
        val n = map.surfaces.size
        val off = (n % 4) * 0.05f
        map.surfaces.add(Surface.centred(0.4f + off, 0.4f + off, 0.15f, (n % 8) + 1, (n * 70f) % 360f))
        map.current = map.surfaces.size - 1
        map.handle = 0
        map.editing = true
        save()
    }

    private fun copySurface(s: Surface) {
        val p = FloatArray(8) { s.pts[it] + 0.04f }
        map.surfaces.add(Surface(p, s.effect, (s.hue + 60f) % 360f, s.image))
        map.current = map.surfaces.size - 1
        map.handle = 4
        save()
    }

    private fun deleteSurface() {
        if (map.surfaces.isEmpty()) return
        map.surfaces.removeAt(map.current)
        map.current = map.current.coerceAtMost(map.surfaces.size - 1).coerceAtLeast(0)
        map.handle = 0
        save()
    }

    private fun resetAll() {
        map.surfaces.clear()
        map.surfaces.add(Surface.centred(0.5f, 0.5f, 0.2f, 1, 200f))
        map.current = 0; map.handle = 0; map.editing = true
        save()
    }

    // ---------------------------------------------------------------- pictures

    @Suppress("DEPRECATION")
    private fun choosePicture() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
        }
        try {
            startActivityForResult(open, pickPicture)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                }, pickPicture)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "No file picker on this device", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickPicture || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { // some pickers don't allow it; the picture then lasts until the app restarts
        }
        val s = map.cur() ?: return
        s.image = uri.toString()
        s.effect = MapView.FX_PICTURE
        loadPicture(uri.toString())
        save()
    }

    /** Load a picture in the background, shrunk so it's no bigger than about 1920 px. */
    private fun loadPicture(key: String) {
        if (map.pictures.containsKey(key)) return
        thread {
            val bmp: Bitmap? = try {
                val uri = Uri.parse(key)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1920 || bounds.outHeight / (sample * 2) >= 1920) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bmp != null) map.pictures[key] = bmp
                else Toast.makeText(this, "Couldn't open that picture", Toast.LENGTH_LONG).show()
            }
        }
    }
}
