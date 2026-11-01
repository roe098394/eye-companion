package com.eyecompanion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var eyes: EyesView
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("companion", Context.MODE_PRIVATE) }

    private var brain: Brain? = null
    private var ears: Ears? = null
    private var voice: Voice? = null
    private var voskModel: Model? = null

    private enum class Phase { SETUP, ASLEEP, LISTENING, THINKING, TALKING }
    private var phase = Phase.SETUP
    private var setupStarted = false

    /** Bumped on every new question or interruption, so callbacks from an old reply are ignored. */
    @Volatile private var replyId = 0
    /** What Pip is currently saying, used to tell its own voice apart from yours. */
    private val currentReply = StringBuilder()
    private var voiceInterrupt: Boolean
        get() = prefs.getBoolean("voiceInterrupt", true)
        set(v) { prefs.edit().putBoolean("voiceInterrupt", v).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        eyes = EyesView(this)
        eyes.showCaptions = prefs.getBoolean("captions", true)
        setContentView(eyes)
        setupTouch()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            eyes.caption = "I need the microphone to hear you."
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else startSetup()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSetup()
        else eyes.caption = "Microphone permission is needed. Long-press to open settings."
    }

    // ---------------------------------------------------------------- setup

    private fun startSetup() {
        if (setupStarted && phase == Phase.SETUP) return
        ears?.stop(); ears = null
        voice?.shutUp(); voice?.shutdown(); voice = null
        brain?.release(); brain = null
        replyId++
        setupStarted = true
        phase = Phase.SETUP
        eyes.mood = EyesView.Mood.LOADING
        thread(name = "setup") {
            try {
                val llm = findOrDownloadLlm()
                val vosk = findOrDownloadVosk()

                status("Waking up…")
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                voskModel = Model(vosk.absolutePath)

                val b = Brain(llm.absolutePath) { systemPrompt() }
                brain = b
                b.load { ok ->
                    main.post {
                        if (!ok) { brainFailed(llm); return@post }
                        prefs.edit().remove("brainRetried").apply()
                        voice = Voice(this) { ttsOk ->
                            main.post {
                                if (!ttsOk) status("No text-to-speech voice found. Install Google Speech Services.")
                                startCompanion()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                status("Setup failed: ${e.message}. Check Wi-Fi, then long-press → Retry.")
                setupStarted = false
            }
        }
    }

    /** The brain the user picked, or a sensible one for this tablet's memory. */
    private fun chosenBrain(): Downloads.BrainChoice {
        prefs.getString("brain", null)?.let { k -> Downloads.BRAINS.firstOrNull { it.key == k }?.let { return it } }
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        val key = when {
            mem >= 5_500_000_000L && Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() -> "smartest"
            Build.SUPPORTED_64_BIT_ABIS.isEmpty() -> "fast"     // 32-bit tablets can't fit bigger brains
            mem >= 2_300_000_000L -> "smart"
            else -> "fast"
        }
        return Downloads.BRAINS.first { it.key == key }
    }

    /** Use a .gguf you copied into the app's folder, else download the chosen brain. */
    private fun findOrDownloadLlm(): File {
        val ext = getExternalFilesDir(null)
        ext?.listFiles()?.firstOrNull { it.name.endsWith(".gguf") && it.length() > 0 }?.let { return it }
        val choice = chosenBrain()
        val dest = File(filesDir, choice.file)
        if (!dest.exists()) {
            // free space taken by other brains
            Downloads.BRAINS.filter { it.file != choice.file }.forEach {
                File(filesDir, it.file).delete(); File(filesDir, it.file + ".part").delete()
            }
            status("Downloading my brain (about ${choice.sizeMb} MB, one time only)…")
            Downloads.fetch(choice.url, dest) { p ->
                status(if (p >= 0) "Downloading my brain… ${(p * 100).toInt()}%" else "Downloading my brain…")
            }
        }
        return dest
    }

    private fun findOrDownloadVosk(): File {
        val root = File(filesDir, Downloads.VOSK_DIR)
        Downloads.findVoskModel(root)?.let { return it }
        File(filesDir, "vosk").deleteRecursively()          // old small model
        val zip = File(filesDir, "vosk.zip")
        status("Downloading better ears (about ${Downloads.VOSK_MB} MB)…")
        Downloads.fetch(Downloads.VOSK_URL, zip) { p ->
            status(if (p >= 0) "Downloading my ears… ${(p * 100).toInt()}%" else "Downloading my ears…")
        }
        status("Unpacking…")
        root.deleteRecursively()
        Downloads.unzip(zip, root)
        zip.delete()
        return Downloads.findVoskModel(root) ?: throw IllegalStateException("speech model missing")
    }

    /** The brain wouldn't start: try a fresh download once, then fall back to a smaller brain. */
    private fun brainFailed(file: File) {
        val current = chosenBrain()
        val custom = file.parentFile != filesDir
        if (custom) { status("Couldn't load your own .gguf file. Remove it from the app folder, then Retry setup."); return }
        file.delete()
        val retried = prefs.getBoolean("brainRetried", false)
        val idx = Downloads.BRAINS.indexOf(current)
        when {
            !retried -> {
                prefs.edit().putBoolean("brainRetried", true).apply()
                status("My brain file was damaged. Downloading it again…")
            }
            idx > 0 -> {
                val smaller = Downloads.BRAINS[idx - 1]
                prefs.edit().putString("brain", smaller.key).remove("brainRetried").apply()
                status("That brain is too big for this tablet. Switching to ${smaller.label.substringBefore(" (")}…")
            }
            else -> { status("I couldn't load even my smallest brain. Long-press → Retry setup."); return }
        }
        setupStarted = false
        main.postDelayed({ startSetup() }, 2500)
    }

    private fun chooseBrain() {
        val current = chosenBrain().key
        val labels = Downloads.BRAINS.map { (if (it.key == current) "✓ " else "") + it.label + " · ${it.sizeMb} MB" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Choose Pip's brain")
            .setItems(labels) { _, which ->
                val pick = Downloads.BRAINS[which]
                if (pick.key != current) {
                    prefs.edit().putString("brain", pick.key).remove("brainRetried").apply()
                    setupStarted = false
                    startSetup()
                }
            }
            .show()
    }

    private fun status(text: String) = main.post { eyes.caption = text }

    // ---------------------------------------------------------------- running

    private fun startCompanion() {
        keepBluetoothInMusicMode()
        val v = voice ?: return
        v.onStartedTalking = { main.post { if (phase == Phase.THINKING) setPhase(Phase.TALKING) } }
        v.onFinishedTalking = { main.post { if (phase == Phase.TALKING || phase == Phase.THINKING) setPhase(Phase.LISTENING) } }

        ears = Ears(
            this, voskModel!!,
            onPartial = { p -> main.post { partial(p) } },
            onHeard = { text -> main.post { heard(text) } },
            onLevel = { l -> eyes.level = l },
            onError = { msg -> main.post { eyes.caption = msg } },
        ).also { it.start() }

        eyes.subCaption = ""
        setPhase(if (prefs.getBoolean("asleep", false)) Phase.ASLEEP else Phase.LISTENING)
        if (phase == Phase.LISTENING) eyes.caption = "Hi! I'm listening."
    }

    private fun partial(p: String) {
        when (phase) {
            Phase.LISTENING -> {
                eyes.mood = if (p.isNotEmpty()) EyesView.Mood.LISTEN else EyesView.Mood.IDLE
                if (p.isNotEmpty()) eyes.caption = p
            }
            // You started talking over Pip: stop straight away and listen.
            Phase.THINKING, Phase.TALKING ->
                if (voiceInterrupt && isYouNotPip(p)) {
                    interrupt()
                    eyes.mood = EyesView.Mood.LISTEN
                    eyes.caption = p
                }
            else -> {}
        }
    }

    /**
     * The mic also picks up Pip's own voice from the speaker. Only treat speech as yours if it
     * has at least two words and most of them aren't words Pip is saying right now.
     */
    private fun isYouNotPip(text: String): Boolean {
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }
        if (words.size < 2) return false
        val pipWords = currentReply.toString().lowercase().split(Regex("\\W+")).toHashSet()
        val mine = words.count { it !in pipWords }
        return mine >= 2 && mine.toFloat() / words.size >= 0.5f
    }

    /** Stop thinking/talking immediately and go back to listening. */
    private fun interrupt() {
        replyId++
        brain?.stop()
        voice?.shutUp()
        currentReply.clear()
        setPhase(Phase.LISTENING)
    }

    private fun heard(text: String) {
        when (phase) {
            Phase.LISTENING -> {}
            Phase.THINKING, Phase.TALKING -> {
                // A whole sentence arrived while Pip was busy: if it's you, answer it instead.
                if (!voiceInterrupt || !isYouNotPip(text)) return
                interrupt()
            }
            else -> return
        }
        val lower = text.lowercase()
        if (lower in GO_TO_SLEEP) { goToSleep(); return }
        if (lower in FORGET) { brain?.forget(); speakLine("Okay, fresh start."); return }
        quickAnswer(lower)?.let { eyes.subCaption = "You: $text"; speakLine(it); return }

        setPhase(Phase.THINKING)
        ears?.reset()
        eyes.subCaption = "You: $text"
        eyes.caption = ""
        val v = voice ?: return
        val id = ++replyId
        currentReply.clear()
        v.beginReply()
        brain?.ask(text,
            onToken = { piece ->
                main.post {
                    if (id != replyId) return@post        // an old, interrupted reply
                    v.feed(piece)
                    currentReply.append(piece)
                    eyes.caption = currentReply.toString().trim()
                }
                id == replyId                              // false tells the brain to stop
            },
            onDone = { full ->
                main.post {
                    if (id != replyId) return@post
                    if (full.isBlank()) eyes.caption = ""
                    v.endReply()
                }
            })
    }

    /** Things a small offline brain can't know, answered from the tablet itself. */
    private fun quickAnswer(q: String): String? {
        val now = java.util.Calendar.getInstance()
        val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.UK).format(now.time)
        val date = java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.UK).format(now.time)
        return when {
            Regex("\\b(what('?s| is) the time|what time is it|tell me the time)\\b").containsMatchIn(q) -> "It's $time."
            Regex("\\b(what('?s| is) (the )?date|what day is it|today'?s date|what('?s| is) today)\\b").containsMatchIn(q) -> "It's $date."
            else -> null
        }
    }

    private fun systemPrompt(): String {
        val today = java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.UK).format(java.util.Date())
        return "$SYSTEM_PROMPT Today is $today. You are in the UK."
    }

    private fun speakLine(text: String) {
        setPhase(Phase.THINKING)
        replyId++
        currentReply.clear(); currentReply.append(text)
        eyes.caption = text
        voice?.apply { beginReply(); feed(text); endReply() }
    }

    private fun setPhase(p: Phase) {
        phase = p
        // Keep listening while thinking/talking so you can interrupt by voice.
        ears?.paused = when (p) {
            Phase.LISTENING -> false
            Phase.THINKING, Phase.TALKING -> !voiceInterrupt
            else -> true
        }
        if (p == Phase.LISTENING) currentReply.clear()
        eyes.mood = when (p) {
            Phase.SETUP -> EyesView.Mood.LOADING
            Phase.ASLEEP -> EyesView.Mood.SLEEP
            Phase.LISTENING -> EyesView.Mood.IDLE
            Phase.THINKING -> EyesView.Mood.THINK
            Phase.TALKING -> EyesView.Mood.TALK
        }
    }

    private fun goToSleep() {
        voice?.shutUp(); brain?.stop()
        prefs.edit().putBoolean("asleep", true).apply()
        eyes.caption = ""; eyes.subCaption = ""
        setPhase(Phase.ASLEEP)
    }

    private fun wakeUp() {
        prefs.edit().putBoolean("asleep", false).apply()
        setPhase(Phase.LISTENING)
        eyes.mood = EyesView.Mood.HAPPY
    }

    /**
     * Make sure Android isn't holding the Bluetooth speaker in "call" mode.
     * Output then goes over high-quality A2DP while we record from the built-in mic.
     */
    @Suppress("DEPRECATION")
    private fun keepBluetoothInMusicMode() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
        if (am.isBluetoothScoOn) { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
    }

    // ---------------------------------------------------------------- touch & menu

    private fun setupTouch() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                when (phase) {
                    Phase.ASLEEP -> wakeUp()
                    Phase.TALKING, Phase.THINKING -> interrupt()
                    Phase.LISTENING -> eyes.mood = EyesView.Mood.HAPPY
                    Phase.SETUP -> {}
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (phase == Phase.LISTENING) goToSleep()
                return true
            }
            override fun onLongPress(e: MotionEvent) { showMenu() }
        })
        eyes.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
    }

    private fun showMenu() {
        val items = arrayOf(
            if (phase == Phase.ASLEEP) "Wake up" else "Go to sleep",
            if (eyes.showCaptions) "Hide captions" else "Show captions",
            "Forget our conversation",
            if (voiceInterrupt) "Stop voice interrupting (if I keep cutting myself off)" else "Let me interrupt by voice",
            "Change brain (smarter or faster)…",
            "Retry setup",
            "Bluetooth settings",
            "Choose Home app (to exit companion mode)",
            "Open Android settings",
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Eye Companion")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (phase == Phase.ASLEEP) wakeUp() else if (phase != Phase.SETUP) goToSleep()
                    1 -> { eyes.showCaptions = !eyes.showCaptions; prefs.edit().putBoolean("captions", eyes.showCaptions).apply() }
                    2 -> brain?.forget()
                    3 -> { voiceInterrupt = !voiceInterrupt; setPhase(phase) }
                    4 -> chooseBrain()
                    5 -> { setupStarted = false; startSetup() }
                    6 -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    7 -> startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                    8 -> startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (phase != Phase.SETUP) keepBluetoothInMusicMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    @Deprecated("Back does nothing: this is the home screen")
    override fun onBackPressed() { /* stay put */ }

    override fun onDestroy() {
        ears?.stop()
        voice?.shutdown()
        brain?.release()
        super.onDestroy()
    }

    companion object {
        val SYSTEM_PROMPT = """
            You are Pip, a friendly, clever companion who lives in a tablet on someone's desk as a pair
            of animated eyes and a voice. Everything you say is read aloud.
            Rules: Answer the question that was actually asked, directly, in the first sentence.
            Keep it to one to three short sentences unless asked for more. Use plain spoken English:
            no lists, markdown, emoji or stage directions. If the question is unclear or sounds misheard,
            ask a short clarifying question instead of guessing. If you're not sure of a fact, say so.
            Be warm and a bit playful, and remember what was said earlier in the conversation.
            You have no internet access.
        """.trimIndent().replace("\n", " ")

        private val GO_TO_SLEEP = setOf("go to sleep", "goodnight", "good night", "sleep pip", "go to sleep pip")
        private val FORGET = setOf("forget everything", "start over", "new conversation")
    }
}
