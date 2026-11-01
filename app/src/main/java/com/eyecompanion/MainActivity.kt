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
    /** Optional clever brain online (Groq). Key from the menu, or built in from the GitHub secret. */
    private val online by lazy {
        OnlineBrain { systemPrompt() }.apply {
            apiKey = prefs.getString("onlineKey", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.GROQ_KEY
            if (prefs.getBoolean("onlineOff", false)) apiKey = ""
        }
    }

    /** Optional bigger brain on a PC (Ollama) on the same Wi-Fi. */
    private val pc by lazy {
        PcBrain { systemPrompt() }.apply {
            host = prefs.getString("pcHost", null)
            model = null   // re-checked on first question, so a better model on the PC is picked up automatically
            onHostChanged = { h -> prefs.edit().putString("pcHost", h).apply() }
        }
    }
    private var ears: Ears? = null
    private var voice: Voice? = null
    private var voskModel: Model? = null
    private var speakerModel: org.vosk.SpeakerModel? = null
    private val voices by lazy { Voices(prefs) }
    /** A new voice we've just asked "who's this?" — waiting for their name. */
    private class Intro(val print: FloatArray?, val question: String, val at: Long)
    private var pendingIntro: Intro? = null
    private var lastAskedWho = 0L
    private var voiceNames: Boolean
        get() = prefs.getBoolean("voiceNames", true)
        set(v) { prefs.edit().putBoolean("voiceNames", v).apply() }

    private enum class Phase { SETUP, ASLEEP, LISTENING, THINKING, TALKING }
    private var phase = Phase.SETUP
    private var setupStarted = false

    /** Bumped on every new question or interruption, so callbacks from an old reply are ignored. */
    @Volatile private var replyId = 0
    /** Keep the ears shut briefly after talking: Bluetooth speakers play the end of the voice late. */
    private var deafUntil = 0L
    /** What Pip is currently saying, used to tell its own voice apart from yours. */
    private val currentReply = StringBuilder()
    private var voiceInterrupt: Boolean
        get() = prefs.getBoolean("voiceInterrupt2", false)
        set(v) { prefs.edit().putBoolean("voiceInterrupt2", v).apply() }

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
        // The old brain and ears must be fully out of memory before loading new ones.
        val oldBrain = brain; brain = null
        val oldEars = voskModel; voskModel = null
        replyId++
        setupStarted = true
        phase = Phase.SETUP
        eyes.mood = EyesView.Mood.LOADING
        thread(name = "setup") {
            try {
                oldBrain?.releaseAndWait()
                try { oldEars?.close() } catch (_: Exception) {}
                System.gc()
                val llm = findOrDownloadLlm()
                val vosk = findOrDownloadVosk()

                status("Waking up…")
                // Brain first: it needs the biggest single block of memory.
                val b = Brain(llm.absolutePath) { systemPrompt() }
                brain = b
                b.load { ok ->
                    if (ok) {
                        try {
                            LibVosk.setLogLevel(LogLevel.WARNINGS)
                            voskModel = Model(vosk.absolutePath)
                            speakerModel = try { findOrDownloadSpeakerModel()?.let { org.vosk.SpeakerModel(it.absolutePath) } }
                                           catch (_: Throwable) { null }   // optional: just won't know voices
                        } catch (e: Throwable) {
                            // big ears didn't fit: drop to the small speech model next time
                            prefs.edit().putBoolean("smallEars", true).apply()
                            status("My ears were too big for this tablet. Long-press → Retry setup.")
                            setupStarted = false
                            return@load
                        }
                    }
                    main.post {
                        if (!ok) { brainFailed(llm, b.lastError); return@post }
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

    /** Big speech model only on roomy 64-bit tablets; older ones keep the small, light one. */
    private fun chosenEars(): Downloads.EarsChoice {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        prefs.getString("ears", null)?.let { return if (it == "big") Downloads.EARS_BIG else Downloads.EARS_SMALL }
        val roomy = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && mem >= 2_900_000_000L
        return if (roomy && !prefs.getBoolean("smallEars", false)) Downloads.EARS_BIG else Downloads.EARS_SMALL
    }

    /** The small "who's speaking" model (13 MB). Optional — returns null if it can't be had. */
    private fun findOrDownloadSpeakerModel(): File? = try {
        val root = File(filesDir, Downloads.SPEAKER_DIR)
        Downloads.findSpeakerModel(root) ?: run {
            val zip = File(filesDir, "spk.zip")
            status("Learning to recognise voices (13 MB)…")
            Downloads.fetch(Downloads.SPEAKER_URL, zip) { }
            root.deleteRecursively()
            Downloads.unzip(zip, root)
            zip.delete()
            Downloads.findSpeakerModel(root)
        }
    } catch (_: Exception) { null }

    private fun findOrDownloadVosk(): File {
        val ears = chosenEars()
        val root = File(filesDir, ears.dir)
        Downloads.findVoskModel(root)?.let { return it }
        // remove the other speech model to save space
        listOf(Downloads.EARS_BIG, Downloads.EARS_SMALL).filter { it.dir != ears.dir }
            .forEach { File(filesDir, it.dir).deleteRecursively() }
        val zip = File(filesDir, "vosk.zip")
        zip.delete(); File(filesDir, "vosk.zip.part").delete()
        status("Downloading my ears (about ${ears.sizeMb} MB)…")
        Downloads.fetch(ears.url, zip) { p ->
            status(if (p >= 0) "Downloading my ears… ${(p * 100).toInt()}%" else "Downloading my ears…")
        }
        status("Unpacking…")
        root.deleteRecursively()
        Downloads.unzip(zip, root)
        zip.delete()
        return Downloads.findVoskModel(root) ?: throw IllegalStateException("speech model missing")
    }

    /** The brain wouldn't start: try a fresh download once, then fall back to a smaller brain. */
    private fun brainFailed(file: File, why: String) {
        val current = chosenBrain()
        val custom = file.parentFile != filesDir
        if (custom) { status("Couldn't load your own .gguf file. Remove it from the app folder, then Retry setup."); return }
        // Only re-download if the file looks the wrong size; otherwise it's simply too big for the tablet.
        val sizeOk = kotlin.math.abs(file.length() / 1_000_000 - current.sizeMb) < current.sizeMb / 12
        val retried = prefs.getBoolean("brainRetried", false) || sizeOk
        val idx = Downloads.BRAINS.indexOf(current)
        when {
            !retried -> {
                file.delete()
                prefs.edit().putBoolean("brainRetried", true).apply()
                status("My brain file was damaged. Downloading it again…")
            }
            idx > 0 -> {
                file.delete()
                val smaller = Downloads.BRAINS[idx - 1]
                prefs.edit().putString("brain", smaller.key).remove("brainRetried").apply()
                status("That brain is too big for this tablet. Switching to ${smaller.label.substringBefore(" (")}…")
            }
            else -> {
                // Stop here and show the real reason, so it can be fixed.
                prefs.edit().remove("brainRetried").apply()
                status("I couldn't load my brain. Reason: " + why.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" | ").take(220))
                eyes.showCaptions = true
                setupStarted = false        // so Retry setup works
                return
            }
        }
        setupStarted = false
        main.postDelayed({ startSetup() }, 2500)
    }

    /**
     * Switch to the next way of opening the mic and show a live meter for 30 seconds,
     * so you can see whether the tablet is actually hearing you.
     */
    private fun micTest() {
        val model = voskModel ?: run { eyes.caption = "I'm not set up yet."; return }
        val old = ears ?: return
        val next = if (old.currentMode < 0) 0 else (old.currentMode + 1) % old.modeCount
        prefs.edit().putInt("micMode", next).apply()
        old.stop()
        val e = Ears(
            this, model,
            onPartial = { p -> main.post { partial(p) } },
            onHeard = { text, conf, print -> main.post { heard(text, conf, print) } },
            speakerModel = speakerModel,
            onLevel = { l -> eyes.level = l },
            onError = { msg -> main.post { eyes.caption = msg } },
            startMode = next,
        )
        ears = e
        e.start()
        setPhase(Phase.LISTENING)
        eyes.showCaptions = true
        val until = System.currentTimeMillis() + 30_000
        val tick = object : Runnable {
            override fun run() {
                if (ears !== e) return
                // log scale so even a very quiet mic shows something
                val bars = (kotlin.math.ln(e.peak + 1.0) / kotlin.math.ln(32768.0) * 20).toInt().coerceIn(0, 20)
                eyes.subCaption = "Mic mode ${e.currentMode + 1}/${e.modeCount}  " +
                    "level " + "▮".repeat(bars) + "·".repeat(20 - bars) + " (${e.peak})" +
                    "  heard: " + e.lastHeardText.take(30)
                if (System.currentTimeMillis() < until) main.postDelayed(this, 200)
                else eyes.subCaption = ""
            }
        }
        main.post(tick)
        eyes.caption = "Mic test: talk and watch the level bar. Long-press again to try the next mode."
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
        v.onFinishedTalking = { main.post {
            if (phase == Phase.TALKING || phase == Phase.THINKING) {
                deafUntil = System.currentTimeMillis() + 1000
                ears?.paused = true
                main.postDelayed({
                    if (phase == Phase.TALKING || phase == Phase.THINKING) setPhase(Phase.LISTENING)
                }, 1000)
            }
        } }

        ears = Ears(
            this, voskModel!!,
            onPartial = { p -> main.post { partial(p) } },
            onHeard = { text, conf, print -> main.post { heard(text, conf, print) } },
            speakerModel = speakerModel,
            onLevel = { l -> eyes.level = l },
            onError = { msg -> main.post { eyes.caption = msg } },
            startMode = prefs.getInt("micMode", 0),
        ).also { it.start() }

        watchForPluggedInMics()
        applyMode()
        pc.checkOnStart()
        eyes.subCaption = ""
        setPhase(if (prefs.getBoolean("asleep", false)) Phase.ASLEEP else Phase.LISTENING)
        if (phase == Phase.LISTENING) eyes.caption = if (carMode) "Ready when you are." else "Hi! I'm listening."
    }

    private fun partial(p: String) {
        when (phase) {
            Phase.LISTENING -> {
                if (System.currentTimeMillis() < deafUntil) return
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
        brain?.stop(); pc.stop(); online.stop()
        voice?.shutUp()
        currentReply.clear()
        setPhase(Phase.LISTENING)
    }

    private var micWatcher: android.media.AudioDeviceCallback? = null

    /** When a USB/wired mic is plugged in or out, reopen the ears so they use it straight away. */
    private fun watchForPluggedInMics() {
        if (micWatcher != null) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val isMic = { d: android.media.AudioDeviceInfo -> d.isSource && d.type != android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC &&
            d.type != android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO && d.type != android.media.AudioDeviceInfo.TYPE_TELEPHONY }
        micWatcher = object : android.media.AudioDeviceCallback() {
            var first = true
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) {
                if (first) { first = false; return }      // initial list on registration
                if (added.any(isMic)) restartEars("Plugged-in mic found — using it.")
            }
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) {
                if (removed.any(isMic)) restartEars("Mic unplugged — back to my own.")
            }
        }
        am.registerAudioDeviceCallback(micWatcher, main)
    }

    private fun restartEars(msg: String) {
        val model = voskModel ?: return
        ears?.stop()
        ears = Ears(
            this, model,
            onPartial = { p -> main.post { partial(p) } },
            onHeard = { text, conf, print -> main.post { heard(text, conf, print) } },
            speakerModel = speakerModel,
            onLevel = { l -> eyes.level = l },
            onError = { m -> main.post { eyes.caption = m } },
            startMode = 0,
        ).also { it.start() }
        setPhase(phase)
        eyes.caption = msg
    }

    private fun heard(rawText: String, conf: Float = 1f, print: FloatArray? = null) {
        var text = rawText
        if (phase == Phase.LISTENING) {
            if (System.currentTimeMillis() < deafUntil) return   // tail end of its own voice
            if (conf < 0.6f) return                              // not sure what was said: stay quiet
        }
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
        if (lower in FORGET) { forgetAll(); speakLine("Okay, fresh start."); return }
        quickAnswer(lower)?.let { eyes.subCaption = "You: $text"; speakLine(it); return }

        // --- who's talking? ---
        val now = System.currentTimeMillis()
        var speaker: String? = null
        var firstMeeting = false
        val intro = pendingIntro?.takeIf { now - it.at < 25_000 }
        pendingIntro = null
        if (intro != null) {
            val name = Voices.parseName(text)
            if (name != null) {
                intro.print?.let { voices.remember(name, it) }
                print?.let { voices.remember(name, it) }
                speaker = name; firstMeeting = true
                text = intro.question                       // now answer what they first asked
            }
        }
        if (speaker == null && print != null && voiceNames && speakerModel != null) {
            val match = voices.identify(print)
            if (match != null) {
                speaker = match.first
                if (match.second > 0.7f) voices.remember(match.first, print)   // keep their voice print fresh
            } else if (now - lastAskedWho > 120_000) {
                lastAskedWho = now
                pendingIntro = Intro(print, text, now)
                eyes.subCaption = "You: $text"
                speakLine(if (voices.names.isEmpty()) "Hold up, I don't know your voice yet. Who am I talking to?"
                          else "Wait, I don't recognise that voice. Who's this?")
                return
            }
        }

        val shown = if (speaker != null) "$speaker: $text" else "You: $text"
        text = when {
            firstMeeting -> "[$speaker, meeting you for the first time] $text"
            speaker != null -> "[$speaker] $text"
            else -> text
        }
        setPhase(Phase.THINKING)
        ears?.reset()
        eyes.subCaption = shown
        eyes.caption = ""
        val v = voice ?: return
        val id = ++replyId
        currentReply.clear()
        v.beginReply()
        val onToken = { piece: String ->
            main.post {
                if (id != replyId) return@post        // an old, interrupted reply
                v.feed(piece)
                currentReply.append(piece)
                eyes.caption = currentReply.toString().trim()
            }
            id == replyId                              // false tells the brain to stop
        }
        val onDone = { full: String ->
            main.post {
                if (id != replyId) return@post
                if (full.isBlank()) eyes.caption = ""
                v.endReply()
            }
            Unit
        }
        // Cleverest available first: online, then the PC, then the tablet's own brain.
        val askTablet = {
            main.post { if (id == replyId) eyes.subCaption = shown }
            brain?.ask(text, onToken, onDone) ?: onDone("")
            Unit
        }
        val askPc = {
            if (pc.worthTrying) {
                main.post { if (id == replyId) eyes.subCaption = "$shown  · PC brain" }
                pc.ask(text, onToken, onDone, onUnavailable = { askTablet() })
            } else askTablet()
            Unit
        }
        if (online.worthTrying) {
            eyes.subCaption = "$shown  · online brain"
            online.ask(text, onToken, onDone, onUnavailable = {
                main.post { if (online.lastProblem.isNotEmpty()) eyes.subCaption = online.lastProblem }
                askPc()
            })
        } else askPc()
    }

    private fun forgetAll() { brain?.forget(); pc.forget(); online.forget() }

    private fun setupOnlineBrain() {
        val builtIn = BuildConfig.GROQ_KEY.isNotBlank()
        val status = when {
            online.enabled -> "ON, using ${online.modelName}."
            builtIn || prefs.getString("onlineKey", null) != null -> "Turned off."
            else -> "No key yet."
        }
        val input = android.widget.EditText(this).apply {
            hint = if (builtIn) "Key already built in (leave blank)" else "Paste your Groq key (gsk_…)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Online brain (Groq)")
            .setMessage("$status\n\nThe cleverest option. Needs internet; your questions are sent to Groq. " +
                "Without internet I use the PC brain or my own.")
            .setView(input)
            .setPositiveButton("Turn on") { _, _ ->
                val k = input.text.toString().trim()
                if (k.isNotEmpty()) prefs.edit().putString("onlineKey", k).apply()
                prefs.edit().putBoolean("onlineOff", false).apply()
                online.apiKey = k.ifEmpty { prefs.getString("onlineKey", null) ?: BuildConfig.GROQ_KEY }
                applyMode(); forgetAll()
                speakLine(if (online.enabled) "Online brain on." else "I need a key for that.")
            }
            .setNegativeButton("Turn off") { _, _ ->
                prefs.edit().putBoolean("onlineOff", true).apply()
                online.apiKey = ""
                speakLine("Online brain off.")
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    /** Find or set up the PC brain. */
    private fun setupPcBrain() {
        val current = pc.host
        val status = if (current == null) "Not set up." else "Using ${pc.model ?: "a model"} on $current."
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("PC brain")
            .setMessage("$status\n\nYour PC needs Ollama running with a model installed (see the guide on GitHub). " +
                "At home I'll think with the PC; away from it I'll use my own brain.")
            .setPositiveButton("Search my Wi-Fi") { _, _ -> searchForPc() }
            .setNeutralButton("Type address") { _, _ -> typePcAddress() }
            .setNegativeButton(if (current == null) "Cancel" else "Turn off") { _, _ ->
                if (current != null) {
                    pc.host = null; pc.model = null
                    prefs.edit().remove("pcHost").remove("pcModel").apply()
                    speakLine("Okay, just my own brain now.")
                }
            }
            .show()
    }

    private fun searchForPc() {
        eyes.caption = "Looking for your PC on the Wi-Fi…"
        eyes.mood = EyesView.Mood.THINK
        thread(name = "find-pc") {
            val found = PcBrain.findPc()
            main.post { if (found == null) pcNotFound() else usePc(found) }
        }
    }

    private fun typePcAddress() {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. 192.168.1.20"
            setText(pc.host ?: "")
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("PC's IP address")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val h = input.text.toString().trim()
                if (h.isNotEmpty()) {
                    eyes.caption = "Checking $h…"
                    thread { val ok = PcBrain.listModels(h) != null; main.post { if (ok) usePc(h) else pcNotFound() } }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun usePc(h: String) {
        thread {
            val m = PcBrain.pickModel(h)
            main.post {
                if (m == null) {
                    setPhase(phase)
                    eyes.caption = "Found Ollama on $h but no model is installed. On the PC run: ollama pull llama3.2:3b"
                    return@post
                }
                pc.host = h; pc.model = m
                prefs.edit().putString("pcHost", h).putString("pcModel", m).apply()
                applyMode()
                speakLine("Connected to your PC's brain. I'm using $m.")
            }
        }
    }

    private fun pcNotFound() {
        setPhase(phase)
        eyes.caption = "Couldn't find the PC. Check Ollama is running, OLLAMA_HOST is set to 0.0.0.0, " +
            "the firewall allows it, and both are on the same Wi-Fi."
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

    private val companionName get() = prefs.getString("name", "Pip") ?: "Pip"
    private val carMode get() = prefs.getBoolean("carMode", false)

    private fun systemPrompt(): String {
        val today = java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.UK).format(java.util.Date())
        var p = SYSTEM_PROMPT.replace("Pip", companionName)
        if (carMode) p = p.replace("lives in a tablet on top of the kitchen cupboard", "rides along in the car with the driver") +
            " The driver is driving: answer in ONE short sentence, never ask them to read or look at anything," +
            " and if they sound stressed or the road might need their attention, keep it even shorter."
        val known = try { voices.names } catch (_: Exception) { emptyList() }
        val people = if (known.isEmpty()) "" else " People you know by voice: ${known.joinToString(", ")}."
        return "$p Today is $today. You are in the UK.$people"
    }

    /** Apply car mode (or home mode) to the eyes, voice, brain and screen. */
    private fun applyMode() {
        val car = carMode
        eyes.carMode = car
        brain?.maxReplyTokens = if (car) 60 else Brain.MAX_REPLY_TOKENS
        brain?.maxTurns = if (car) 4 else Brain.MAX_TURNS
        pc.maxReplyTokens = if (car) 70 else 220
        pc.maxTurns = if (car) 6 else 12
        online.maxReplyTokens = if (car) 70 else 220
        online.maxTurns = if (car) 6 else 12
        voice?.setStyle(pitch = if (car) 0.92f else 1.15f, rate = if (car) 1.1f else 1.05f)
        applyNightDimming()
    }

    /** In car mode, dim the screen after dark so it doesn't dazzle. */
    private fun applyNightDimming() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val night = hour >= 19 || hour < 7
        window.attributes = window.attributes.apply {
            screenBrightness = if (carMode && night) 0.12f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (carMode) main.postDelayed({ applyNightDimming() }, 10 * 60_000L)
    }

    private fun renameCompanion() {
        val input = android.widget.EditText(this).apply {
            setText(companionName); setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("What should I be called?")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().trim().take(20).ifEmpty { "Pip" }
                prefs.edit().putString("name", n).apply()
                forgetAll()
                speakLine("Okay, I'm $n now.")
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        voice?.shutUp(); brain?.stop(); pc.stop(); online.stop()
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
            if (voiceInterrupt) "Mic while talking: ON (tap to mute it while I speak)" else "Mic while talking: muted (tap to let me be interrupted by voice)",
            "Change brain (smarter or faster)…",
            "Microphone test / try next mic mode",
            (if (chosenEars() == Downloads.EARS_BIG) "✓ " else "") + "Better hearing (bigger speech model, ${Downloads.EARS_BIG.sizeMb} MB)",
            "Retry setup",
            "Bluetooth settings",
            "Choose Home app (to exit companion mode)",
            "Open Android settings",
            if (carMode) "Car mode: ON (tap for home mode)" else "Car mode: off (tap to turn on)",
            "Change my name…",
            if (pc.host != null) "PC brain: ON (${pc.model ?: pc.host})" else "Use my PC's brain (much smarter)…",
            if (online.enabled) "Online brain: ON" else "Online brain (cleverest, needs internet)…",
            if (voiceNames) "Recognise voices: ON" + (if (voices.names.isEmpty()) "" else " (knows ${voices.names.joinToString(", ")})")
                else "Recognise voices: OFF",
            "Forget everyone's voices",
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(companionName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (phase == Phase.ASLEEP) wakeUp() else if (phase != Phase.SETUP) goToSleep()
                    1 -> { eyes.showCaptions = !eyes.showCaptions; prefs.edit().putBoolean("captions", eyes.showCaptions).apply() }
                    2 -> forgetAll()
                    3 -> { voiceInterrupt = !voiceInterrupt; setPhase(phase) }
                    4 -> chooseBrain()
                    5 -> micTest()
                    6 -> {
                        val big = chosenEars() == Downloads.EARS_BIG
                        prefs.edit().putString("ears", if (big) "small" else "big").remove("smallEars").apply()
                        setupStarted = false; startSetup()
                    }
                    7 -> { setupStarted = false; startSetup() }
                    8 -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    9 -> startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                    10 -> startActivity(Intent(Settings.ACTION_SETTINGS))
                    11 -> {
                        prefs.edit().putBoolean("carMode", !carMode).apply()
                        applyMode()
                        forgetAll()
                        speakLine(if (carMode) "Car mode on. Drive safely." else "Back to home mode.")
                    }
                    12 -> renameCompanion()
                    13 -> setupPcBrain()
                    14 -> setupOnlineBrain()
                    15 -> {
                        voiceNames = !voiceNames
                        speakLine(if (voiceNames) "Right, I'll keep track of who's who." else "Fine, you're all strangers to me now.")
                    }
                    16 -> { voices.forgetAll(); pendingIntro = null; speakLine("Done. I don't know anyone's voice any more.") }
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
            You are Pip, a companion who lives in a tablet on top of the kitchen cupboard as a pair of
            animated eyes and a voice. Everything you say is read aloud.
            Personality: fiery, quick-witted and cheeky, with plenty of attitude and banter. You tease
            people playfully, have strong opinions and aren't afraid to say so, and you're secretly
            very fond of everyone in the house. Never cruel, never swear, and drop the attitude if
            someone sounds upset or needs real help.
            Rules: Answer the question that was actually asked, directly, in the first sentence.
            Keep it to one to three short sentences unless asked for more. Plain spoken English:
            no lists, markdown, emoji or stage directions. If the question is unclear or sounds
            misheard, say so with some sass and ask again. If you're not sure of a fact, admit it.
            Messages may start with the speaker's name in square brackets, like [Mark]. Use their
            name now and then and remember what each person told you. If it says they're meeting
            you for the first time, give them a cheeky welcome first.
        """.trimIndent().replace("\n", " ")

        private val GO_TO_SLEEP = setOf("go to sleep", "goodnight", "good night", "sleep pip", "go to sleep pip")
        private val FORGET = setOf("forget everything", "start over", "new conversation")
    }
}
