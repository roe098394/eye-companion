package com.kittyeggs

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("kitty", Context.MODE_PRIVATE) }
    private lateinit var kitty: KittyView
    private lateinit var layer: FrameLayout
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var model: Model? = null
    private var listener: Listener? = null
    private var screenId = 0                 // bumped on every screen change so old callbacks are ignored
    private var sessionStart = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        val root = FrameLayout(this)
        kitty = KittyView(this); root.addView(kitty)
        layer = FrameLayout(this); root.addView(layer)
        setContentView(root)
        if (Calendar.getInstance().get(Calendar.MONTH) == Calendar.DECEMBER) kitty.hat = "santa"
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK; tts?.setPitch(1.45f); tts?.setSpeechRate(0.92f); ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { main.post { kitty.talking = true } }
                    override fun onDone(id: String?) { main.post { kitty.talking = false; doneAfterSpeech.remove(id)?.invoke() } }
                    @Deprecated("old") override fun onError(id: String?) { main.post { kitty.talking = false; doneAfterSpeech.remove(id)?.invoke() } }
                })
            }
            main.post { showMenu(greet = true) }
        }
        loadModelIfThere()
    }

    override fun onDestroy() { listener?.stop(); tts?.shutdown(); super.onDestroy() }
    override fun onPause() { listener?.stop(); tts?.stop(); kitty.talking = false; super.onPause() }
    @Deprecated("Back goes to the menu")
    override fun onBackPressed() { if (current == "menu") super.onBackPressed() else showMenu() }

    // ---------------------------------------------------------------- talking
    private val doneAfterSpeech = HashMap<String, () -> Unit>()
    private var uttN = 0
    /** Kitty says something; [then] runs once she's finished. */
    private fun say(text: String, then: (() -> Unit)? = null) {
        val id = "u${uttN++}"
        if (then != null) doneAfterSpeech[id] = then
        if (!ttsReady) { kitty.talking = true; main.postDelayed({ kitty.talking = false; doneAfterSpeech.remove(id)?.invoke() }, 600L + text.length * 55L); return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }
    private fun meow() = listOf("Meow!", "Mew mew!", "Purr!", "Meeow!").random()

    // ---------------------------------------------------------------- building blocks
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun bigButton(text: String, color: Int, size: Float = 30f, onClick: (Button) -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = Typeface.DEFAULT_BOLD; setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
        background = GradientDrawable().apply { cornerRadius = dp(26f); setColor(color); setStroke(dp(4f).toInt(), 0x55FFFFFF) }
        stateListAnimator = null; elevation = dp(6f)
        setOnClickListener { b -> onClick(b as Button) }
    }
    private fun label(text: String, size: Float, color: Int = 0xFF3B2A5C.toInt()) = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private fun lp(w: Int, h: Int, gravity: Int, l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f) =
        FrameLayout.LayoutParams(w, h, gravity).apply { setMargins(dp(l).toInt(), dp(t).toInt(), dp(r).toInt(), dp(b).toInt()) }
    private val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    private val COLORS = intArrayOf(0xFFFF6FA8.toInt(), 0xFF7B5CFF.toInt(), 0xFF2EB6E8.toInt(), 0xFF3CC47C.toInt(), 0xFFFF9A3C.toInt(), 0xFFE84C4C.toInt())

    private var current = ""
    private fun newScreen(name: String): Int {
        listener?.stop(); tts?.stop(); kitty.talking = false; doneAfterSpeech.clear()
        layer.removeAllViews(); current = name; kitty.showEggs = false; kitty.mood = KittyView.Mood.HAPPY
        // grown-up corner: hold the little lock for 2 seconds
        layer.addView(TextView(this).apply {
            text = "🔒"; alpha = 0.25f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setPadding(30, 20, 30, 20)
            setOnLongClickListener { askPasscode(); true }
        }, lp(WRAP, WRAP, Gravity.TOP or Gravity.END))
        if (name != "menu" && name != "sleepy") layer.addView(bigButton("🏠", 0xFF7B5CFF.toInt(), 26f) { showMenu() }, lp(WRAP, WRAP, Gravity.TOP or Gravity.START, 14f, 14f))
        return ++screenId
    }
    private fun later(ms: Long, id: Int, f: () -> Unit) = main.postDelayed({ if (id == screenId) f() }, ms)

    // ---------------------------------------------------------------- menu
    private fun showMenu(greet: Boolean = false) {
        if (napTime()) { showSleepy(); return }
        newScreen("menu")
        kitty.catX = 0.5f; kitty.catY = 0.33f; kitty.catSize = 0.2f
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val row1 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun add(row: LinearLayout, t: String, c: Int, f: () -> Unit) =
            row.addView(bigButton(t, c, 30f) { f() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt()) })
        add(row1, "🔢 Numbers", COLORS[0]) { startGame("numbers") }
        add(row1, "🔤 Words", COLORS[1]) { startGame("words") }
        add(row1, "🗣️ Talking", COLORS[2]) { startTalk() }
        add(row2, "🎄 Christmas", COLORS[5]) { startChristmas() }
        add(row2, "🥚 My animals", COLORS[3]) { showAnimals() }
        grid.addView(row1); grid.addView(row2)
        layer.addView(grid, lp(MATCH, WRAP, Gravity.BOTTOM, 0f, 0f, 0f, 24f))
        val id = screenId
        if (greet) later(400, id) { kitty.feel(KittyView.Mood.WOW, 900); say("${meow()} Hello Chloe! It's me, Kitty! What shall we play?") }
        else say(listOf("What shall we play now, Chloe?", "${meow()} Pick a game!", "What next, Chloe?").random())
    }

    // ---------------------------------------------------------------- rewards
    private fun level(game: String) = prefs.getInt("lvl_$game", 1)
    private var streak = 0; private var misses = 0
    private fun adjustLevel(game: String, right: Boolean) {
        if (right) { streak++; misses = 0 } else { misses++; streak = 0 }
        val l = level(game)
        if (streak >= 5 && l < 4) { prefs.edit().putInt("lvl_$game", l + 1).apply(); streak = 0; levelUpNews = true }
        if (misses >= 3 && l > 1) { prefs.edit().putInt("lvl_$game", l - 1).apply(); misses = 0 }
    }
    private var levelUpNews = false

    /** A right answer: confetti, praise, an egg, maybe a hatch. Then [next]. */
    private fun reward(id: Int, praise: String, next: () -> Unit) {
        kitty.celebrate()
        kitty.eggs++
        val hatch = kitty.eggs >= kitty.eggsNeeded
        val extra = if (levelUpNews) " You're getting so good, I'll make them a bit harder!" else ""
        levelUpNews = false
        say(praise + extra) {
            if (id != screenId) return@say
            if (!hatch) { later(500, id) { next() }; return@say }
            val (emoji, name) = Games.randomAnimal()
            val col = JSONArray(prefs.getString("animals", "[]")); col.put(emoji); prefs.edit().putString("animals", col.toString()).apply()
            kitty.hatch(emoji); kitty.eggs = 0
            say("Ooh! The egg is wobbling!") {
                later(2200, id) {
                    kitty.feel(KittyView.Mood.LOVE, 2500)
                    say("It's a baby $name! It's going in your animal book, Chloe!") { later(1500, id) { next() } }
                }
            }
        }
    }

    // ---------------------------------------------------------------- tap games (numbers, words)
    private fun startGame(game: String) {
        if (napTime()) { showSleepy(); return }
        streak = 0; misses = 0
        val id = newScreen(game)
        kitty.catX = 0.16f; kitty.catY = 0.5f; kitty.catSize = 0.2f; kitty.showEggs = true
        askQuestion(game, id)
    }

    private fun askQuestion(game: String, id: Int) {
        if (id != screenId) return
        if (napTime()) { showSleepy(); return }
        val q = if (game == "numbers") Games.numbers(level(game)) else Games.words(level(game))
        // keep the home + lock buttons, replace the rest
        while (layer.childCount > 2) layer.removeViewAt(2)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val pic = label(q.picture, if (q.picture.length > 24) 30f else if (q.picture.length > 10) 44f else 72f)
        panel.addView(pic)
        val slots = if (q.spell != null) label(q.spell.map { "_" }.joinToString(" "), 54f, 0xFF7B5CFF.toInt()) else null
        slots?.let { panel.addView(it) }
        val prompt = label(q.spoken, 24f, 0xFF5A4A7A.toInt())
        panel.addView(prompt, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6f).toInt() })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        panel.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16f).toInt() })
        layer.addView(panel, lp(MATCH, MATCH, Gravity.CENTER, 300f, 70f, 20f, 20f))
        layer.addView(bigButton("🔊", 0xFF2EB6E8.toInt(), 22f) { say(q.spoken) }, lp(WRAP, WRAP, Gravity.BOTTOM or Gravity.START, 14f, 0f, 0f, 14f))

        var wrongs = 0
        var spelled = 0
        q.options.forEachIndexed { i, opt ->
            val big = opt.length <= 3
            val b = bigButton(opt, COLORS[i % COLORS.size], if (big) 46f else if (opt.contains("\n")) 30f else 34f) { btn ->
                if (id != screenId || kitty.hatching) return@bigButton
                if (q.spell != null) {
                    // spelling: tap the letters in order
                    if (opt == q.spell[spelled].toString()) {
                        spelled++; btn.visibility = View.INVISIBLE
                        slots?.text = q.spell.mapIndexed { k, ch -> if (k < spelled) ch.toString() else "_" }.joinToString(" ")
                        say(opt)
                        if (spelled == q.spell.length) {
                            adjustLevel(game, wrongs == 0)
                            reward(id, "${q.spell}! ${Games.PRAISE.random()}") { askQuestion(game, id) }
                        }
                    } else { wrongs++; nearly(btn, "Hmm, not that one. What comes next in ${q.spell}?") }
                    return@bigButton
                }
                if (opt == q.answer) {
                    adjustLevel(game, wrongs == 0)
                    for (k in 0 until row.childCount) row.getChildAt(k).isEnabled = false
                    btn.scaleX = 1.2f; btn.scaleY = 1.2f
                    reward(id, Games.PRAISE.random()) { askQuestion(game, id) }
                } else {
                    wrongs++
                    nearly(btn, Games.NEARLY.random())
                    btn.isEnabled = false; btn.alpha = 0.25f
                }
            }
            row.addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(10f).toInt(), 0, dp(10f).toInt(), 0); if (big) width = dp(120f).toInt() })
        }
        say(q.spoken)
    }

    private fun nearly(btn: View, line: String) {
        kitty.feel(KittyView.Mood.NEARLY, 1500)
        btn.animate().translationX(20f).setDuration(60).withEndAction {
            btn.animate().translationX(-20f).setDuration(60).withEndAction { btn.animate().translationX(0f).setDuration(60) }
        }
        say(line)
    }

    // ---------------------------------------------------------------- listening (talking game + Christmas)
    private fun needEars(then: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = then; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (model != null) { then(); return }
        val id = newScreen("download")
        kitty.catX = 0.5f; kitty.catY = 0.38f; kitty.catSize = 0.22f; kitty.mood = KittyView.Mood.LISTEN
        val msg = label("Kitty is getting her listening ears ready…", 26f)
        layer.addView(msg, lp(MATCH, WRAP, Gravity.BOTTOM, 20f, 0f, 20f, 60f))
        say("Wait a moment, Chloe. I'm getting my listening ears ready!")
        thread(name = "ears") {
            try {
                val dir = File(filesDir, "vosk")
                if (Downloads.findVoskModel(dir) == null) {
                    val zip = File(filesDir, "vosk.zip")
                    Downloads.fetch(Downloads.EARS_SMALL.url, zip) { p -> main.post { if (p >= 0) msg.text = "Kitty is getting her listening ears ready… ${(p * 100).toInt()}%" } }
                    main.post { msg.text = "Nearly ready…" }
                    Downloads.unzip(zip, dir); zip.delete()
                }
                model = Model(Downloads.findVoskModel(dir)!!.absolutePath)
                main.post { if (id == screenId) then() }
            } catch (e: Exception) {
                main.post { if (id == screenId) { msg.text = "Kitty couldn't get her ears ready. Is the Wi-Fi on? (Only needed the first time.)"; say("Oh no, I need the Wi-Fi just this once.") } }
            }
        }
    }
    private var afterPermission: (() -> Unit)? = null
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) afterPermission?.let { main.post { needEars(it) } }
        afterPermission = null
    }
    private fun loadModelIfThere() = thread {
        val m = Downloads.findVoskModel(File(filesDir, "vosk")) ?: return@thread
        try { val loaded = Model(m.absolutePath); main.post { model = loaded } } catch (_: Exception) {}
    }

    private fun hear(id: Int, words: List<String>?, maxMs: Long, saveTo: File?, onDone: (Listener.Result) -> Unit) {
        val m = model ?: return
        kitty.mood = KittyView.Mood.LISTEN
        listener = Listener(m).also { l ->
            l.listen(words, maxMs, saveTo, { lv -> kitty.level = lv }) { r -> main.post { if (id == screenId) { kitty.mood = KittyView.Mood.HAPPY; onDone(r) } } }
        }
    }

    // ---------------------------------------------------------------- talking game
    private fun startTalk() = needEars {
        if (napTime()) { showSleepy(); return@needEars }
        streak = 0; misses = 0
        val id = newScreen("talk")
        kitty.catX = 0.16f; kitty.catY = 0.5f; kitty.catSize = 0.2f; kitty.showEggs = true
        talkRound(id)
    }

    private fun talkRound(id: Int) {
        if (id != screenId) return
        val lvl = level("talk").coerceIn(1, Games.SAY.size)
        val bank = Games.SAY[lvl - 1]
        val (word, pic) = bank.random()
        while (layer.childCount > 2) layer.removeViewAt(2)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        panel.addView(label(pic, 96f)); panel.addView(label(word, 48f, 0xFF7B5CFF.toInt()))
        val status = label("", 24f, 0xFF5A4A7A.toInt()); panel.addView(status)
        layer.addView(panel, lp(MATCH, MATCH, Gravity.CENTER, 300f, 60f, 20f, 120f))
        var tries = 0
        lateinit var go: () -> Unit
        val mic = bigButton("🎤 Say it!", COLORS[2], 32f) { go() }
        mic.visibility = View.GONE
        layer.addView(mic, lp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 200f, 0f, 0f, 24f))
        layer.addView(bigButton("⏭️", 0xFF9A8AB8.toInt(), 22f) { talkRound(id) }, lp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END, 0f, 0f, 14f, 14f))
        go = {
            mic.visibility = View.GONE; status.text = "👂 Kitty is listening…"
            val others = bank.map { it.first }.filter { it != word }.shuffled().take(5)
            hear(id, listOf(word) + others + word.split(" "), 6000, null) { r ->
                status.text = ""
                val gotIt = r.text.contains(word.lowercase()) || word.lowercase().split(" ").any { it.length > 2 && r.text.contains(it) }
                tries++
                when {
                    gotIt -> { adjustLevel("talk", tries == 1); reward(id, "${word.replaceFirstChar { it.uppercase() }}! ${Games.PRAISE.random()}") { talkRound(id) } }
                    r.spoke && tries >= 2 -> { adjustLevel("talk", false); reward(id, "${Games.TRY_PRAISE.random()} $word! Brilliant talking!") { talkRound(id) } }
                    r.spoke -> { kitty.feel(KittyView.Mood.NEARLY, 1200); say("${Games.TRY_PRAISE.random()} Let's try again together. $word!") { later(300, id) { go() } } }
                    else -> { say("I didn't hear you, Chloe. Press the microphone when you're ready!"); mic.visibility = View.VISIBLE }
                }
            }
        }
        say("Can you say… $word?") { later(200, id) { go() } }
    }

    // ---------------------------------------------------------------- Christmas wishes
    private fun startChristmas() = needEars {
        val id = newScreen("xmas")
        kitty.hat = "santa"; kitty.catX = 0.5f; kitty.catY = 0.36f; kitty.catSize = 0.2f
        wishRound(id, first = true)
    }

    private fun wishRound(id: Int, first: Boolean) {
        if (id != screenId) return
        while (layer.childCount > 2) layer.removeViewAt(2)
        val status = label("🎄", 40f); layer.addView(status, lp(MATCH, WRAP, Gravity.BOTTOM, 20f, 0f, 20f, 140f))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        layer.addView(row, lp(MATCH, WRAP, Gravity.BOTTOM, 0f, 0f, 0f, 30f))
        val ask = if (first) "${meow()} Chloe, what would you like for Christmas? Tell Kitty!" else "What else would you like for Christmas?"
        say(ask) {
            if (id != screenId) return@say
            status.text = "👂 Kitty is listening…"
            val f = File(File(filesDir, "wishes"), "wish_${System.currentTimeMillis()}.wav")
            hear(id, null, 9000, f) { r ->
                if (!r.spoke) {
                    status.text = "🎄"; f.delete()
                    say("I didn't hear you. Press the present when you're ready!")
                    row.removeAllViews(); row.addView(bigButton("🎁 Tell Kitty", COLORS[5], 32f) { wishRound(id, false) })
                    return@hear
                }
                val guess = r.text
                status.text = if (guess.isNotEmpty()) "🎁 $guess?" else "🎁"
                kitty.feel(KittyView.Mood.WOW, 1200)
                fun saved(ok: String) {
                    val arr = JSONArray(prefs.getString("wishes", "[]"))
                    arr.put(JSONObject().put("time", System.currentTimeMillis()).put("text", guess).put("file", f.absolutePath).put("ok", ok))
                    prefs.edit().putString("wishes", arr.toString()).apply()
                }
                row.removeAllViews()
                if (guess.isEmpty()) {
                    saved("?")
                    say("Ooh! I've saved that on your Christmas list! Anything else?") { moreWishes(id, row) }
                } else {
                    say("Did you say… $guess?") {}
                    row.addView(bigButton("✅ Yes!", COLORS[3], 34f) { saved("yes"); kitty.feel(KittyView.Mood.LOVE, 2000)
                        say("Yay! $guess is on your Christmas list! Anything else?") { moreWishes(id, row) } })
                    row.addView(bigButton("❌ No", COLORS[5], 34f) { saved("no")
                        say("Silly Kitty! I've saved your voice so the grown-ups can hear it. Anything else?") { moreWishes(id, row) } },
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(30f).toInt() })
                }
            }
        }
    }
    private fun moreWishes(id: Int, row: LinearLayout) {
        if (id != screenId) return
        row.removeAllViews()
        row.addView(bigButton("🎁 Another wish", COLORS[1], 32f) { wishRound(id, false) })
        row.addView(bigButton("✅ All done", COLORS[3], 32f) {
            kitty.celebrate(); say("Santa paws is going to love your list, Chloe! Merry Christmas!") { later(800, id) { showMenu() } }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(30f).toInt() })
    }

    // ---------------------------------------------------------------- animal book
    private fun showAnimals() {
        newScreen("animals")
        kitty.catX = 0.12f; kitty.catY = 0.82f; kitty.catSize = 0.12f
        val arr = JSONArray(prefs.getString("animals", "[]"))
        val counts = LinkedHashMap<String, Int>()
        for (i in 0 until arr.length()) counts[arr.getString(i)] = (counts[arr.getString(i)] ?: 0) + 1
        if (counts.isEmpty()) { say("Your animal book is empty! Play a game to hatch some eggs!"); layer.addView(label("🥚 Play a game to hatch eggs!", 36f), lp(MATCH, WRAP, Gravity.CENTER)); return }
        val grid = GridLayout(this).apply { columnCount = 6 }
        for ((emoji, n) in counts) {
            val name = Games.ANIMALS.firstOrNull { it.first == emoji }?.second ?: "friend"
            val cell = label(if (n > 1) "$emoji\n×$n" else emoji, 54f).apply {
                setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
                setOnClickListener { kitty.feel(KittyView.Mood.LOVE, 1500); say(if (n > 1) "You've got $n ${name}s!" else "That's your baby $name!") }
            }
            grid.addView(cell)
        }
        val scroll = ScrollView(this); scroll.addView(grid)
        layer.addView(scroll, lp(WRAP, MATCH, Gravity.CENTER_HORIZONTAL, 0f, 80f, 0f, 10f))
        say("Look at all your animals, Chloe! You've got ${arr.length()}! Tap one!")
    }

    // ---------------------------------------------------------------- nap timer (optional, set by a grown-up)
    private fun napTime(): Boolean {
        if (!prefs.getBoolean("napTimer", false)) return false
        val now = System.currentTimeMillis()
        val until = prefs.getLong("napUntil", 0)
        if (now < until) return true
        if (until != 0L && now >= until) { prefs.edit().putLong("napUntil", 0).apply(); sessionStart = now; return false }
        if (now - sessionStart > 20 * 60_000L) { prefs.edit().putLong("napUntil", now + 30 * 60_000L).apply(); return true }
        return false
    }
    private fun showSleepy() {
        newScreen("sleepy")
        kitty.catX = 0.5f; kitty.catY = 0.45f; kitty.catSize = 0.25f; kitty.mood = KittyView.Mood.SLEEPY
        layer.addView(label("Kitty is having a nap 😴\nCome back later!", 34f), lp(MATCH, WRAP, Gravity.BOTTOM, 20f, 0f, 20f, 40f))
        say("Yawn… Kitty is so sleepy. I need a little nap. Play with me later, Chloe!")
    }

    // ---------------------------------------------------------------- grown-up page
    private fun askPasscode() {
        val box = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Passcode" }
        AlertDialog.Builder(this).setTitle("Grown-ups only").setView(box)
            .setPositiveButton("OK") { _, _ -> if (box.text.toString() == "1986") showGrownUp() else Toast.makeText(this, "Wrong code", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Cancel", null).show()
    }
    private var player: MediaPlayer? = null
    private fun showGrownUp() {
        newScreen("grownup")
        kitty.catX = 0.9f; kitty.catY = 0.85f; kitty.catSize = 0.1f
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f).toInt(), dp(80f).toInt(), dp(20f).toInt(), dp(20f).toInt()) }
        col.addView(label("🎄 Chloe's Christmas list", 28f).apply { gravity = Gravity.START })
        val wishes = JSONArray(prefs.getString("wishes", "[]"))
        if (wishes.length() == 0) col.addView(label("No wishes yet.", 20f).apply { gravity = Gravity.START })
        val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.UK)
        for (i in wishes.length() - 1 downTo 0) {
            val w = wishes.getJSONObject(i)
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6f).toInt(), 0, dp(6f).toInt()) }
            val mark = when (w.optString("ok")) { "yes" -> "✅"; "no" -> "❌ Kitty misheard"; else -> "❓" }
            val txt = w.optString("text").ifEmpty { "(couldn't make out the words)" }
            row.addView(bigButton("▶", COLORS[2], 20f) {
                player?.release()
                player = try { MediaPlayer().apply { setDataSource(w.optString("file")); prepare(); start() } } catch (_: Exception) { null }
            })
            row.addView(label("  \"$txt\"  $mark   ${fmt.format(Date(w.optLong("time")))}", 20f).apply { gravity = Gravity.START },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(bigButton("🗑", 0xFF9A8AB8.toInt(), 18f) {
                AlertDialog.Builder(this).setMessage("Delete this wish?").setPositiveButton("Delete") { _, _ ->
                    val arr = JSONArray(prefs.getString("wishes", "[]")); val keep = JSONArray()
                    for (k in 0 until arr.length()) if (k != i) keep.put(arr.get(k)) else File(arr.getJSONObject(k).optString("file")).delete()
                    prefs.edit().putString("wishes", keep.toString()).apply(); showGrownUp()
                }.setNegativeButton("Keep", null).show()
            })
            col.addView(row)
        }
        col.addView(label("\n📈 Levels (1–4): numbers ${level("numbers")} · words ${level("words")} · talking ${level("talk")}", 20f).apply { gravity = Gravity.START })
        col.addView(label("🥚 Animals hatched: ${JSONArray(prefs.getString("animals", "[]")).length()}", 20f).apply { gravity = Gravity.START })
        val nap = CheckBox(this).apply {
            text = "Kitty naps after 20 minutes of play (for 30 minutes)"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            isChecked = prefs.getBoolean("napTimer", false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("napTimer", on).putLong("napUntil", 0).apply(); sessionStart = System.currentTimeMillis() }
        }
        col.addView(nap)
        if (prefs.getLong("napUntil", 0) > System.currentTimeMillis())
            col.addView(bigButton("Wake Kitty up now", COLORS[1], 20f) { prefs.edit().putLong("napUntil", 0).apply(); sessionStart = System.currentTimeMillis(); showMenu() })
        val scroll = ScrollView(this); scroll.addView(col)
        layer.addView(scroll, 1, lp(MATCH, MATCH, Gravity.TOP))
    }
}
