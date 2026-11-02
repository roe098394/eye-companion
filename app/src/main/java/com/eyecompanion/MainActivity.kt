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

/**
 * Takes the feeling tag off the front of a streamed reply (e.g. "[happy]") and hands it to the eyes,
 * and makes sure no other [labels] are ever shown or spoken.
 */
class ReplyCleaner(private val onEmotion: (EyesView.Emotion) -> Unit) {
    private val head = StringBuilder()
    private var started = false

    fun push(piece: String): String {
        if (started) return strip(piece)
        head.append(piece)
        var h = head.toString().trimStart()
        while (h.startsWith("[")) {
            val end = h.indexOf(']')
            if (end < 0) return if (h.length < 40) "" else { started = true; strip(h) }   // tag not finished yet
            EMOTIONS[h.substring(1, end).trim().lowercase()]?.let(onEmotion)
            h = h.substring(end + 1).trimStart()
        }
        if (h.isEmpty()) return ""
        started = true
        return strip(h)
    }

    /** Tappable answers the reply offered, written as {Yes | No | Later}: never spoken or shown as text. */
    private val choiceBuf = StringBuilder()
    private var inChoice = false
    val choices: List<String> get() = choiceBuf.toString().removePrefix("choices:").split("|")
        .map { it.trim().trim('"') }.filter { it.isNotEmpty() && it.length <= 30 }.take(4)

    private fun strip(s: String): String {
        val out = StringBuilder()
        for (c in s) when {
            inChoice && c == '}' -> inChoice = false
            inChoice -> choiceBuf.append(c)
            c == '{' -> { inChoice = true; choiceBuf.clear() }
            else -> out.append(c)
        }
        var t = out.toString().replace(Regex("\\[[^\\]]{0,30}\\]"), "")
        if (AMAZE.containsMatchIn(t)) { onEmotion(EyesView.Emotion.SURPRISED); t = t.replace(AMAZE, "") }
        t = t.replace(LABEL, "")
        return t.replace(Regex("fist my bump", RegexOption.IGNORE_CASE), "fist bump")
    }

    companion object {
        /** "Amaze!" / "Amazing." on its own: shown as wide eyes rather than said */
        val LABEL = Regex("\\b(question|answer)\\s*:\\s*", RegexOption.IGNORE_CASE)
        val AMAZE = Regex("\\b[Aa]maze(d|ing)?\\b[!.,]*\\s*")
        val EMOTIONS = mapOf(
            "happy" to EyesView.Emotion.HAPPY, "proud" to EyesView.Emotion.HAPPY,
            "sad" to EyesView.Emotion.SAD, "worried" to EyesView.Emotion.SAD,
            "angry" to EyesView.Emotion.ANGRY, "annoyed" to EyesView.Emotion.ANGRY, "grumpy" to EyesView.Emotion.ANGRY,
            "surprised" to EyesView.Emotion.SURPRISED, "amazed" to EyesView.Emotion.SURPRISED,
            "sleepy" to EyesView.Emotion.SLEEPY, "tired" to EyesView.Emotion.SLEEPY,
            "love" to EyesView.Emotion.LOVE, "loving" to EyesView.Emotion.LOVE,
            "confused" to EyesView.Emotion.CONFUSED, "thinking" to EyesView.Emotion.THINKING,
            "dizzy" to EyesView.Emotion.DIZZY, "scared" to EyesView.Emotion.SCARED, "nervous" to EyesView.Emotion.SCARED,
            "excited" to EyesView.Emotion.EXCITED, "suspicious" to EyesView.Emotion.SUSPICIOUS,
            "bored" to EyesView.Emotion.BORED, "embarrassed" to EyesView.Emotion.EMBARRASSED, "shy" to EyesView.Emotion.EMBARRASSED,
            "wink" to EyesView.Emotion.WINK, "cheeky" to EyesView.Emotion.WINK, "curious" to EyesView.Emotion.CURIOUS,
            "laughing" to EyesView.Emotion.LAUGHING, "laugh" to EyesView.Emotion.LAUGHING,
            "crying" to EyesView.Emotion.CRYING, "stoned" to EyesView.Emotion.STONED, "snigger" to EyesView.Emotion.SNIGGER, "neutral" to EyesView.Emotion.NEUTRAL)
    }
}

class MainActivity : Activity() {

    private lateinit var eyes: EyesView
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("companion", Context.MODE_PRIVATE) }

    private var brain: Brain? = null
    /** Optional clever brain online (Groq). Key from the menu, or built in from the GitHub secret. */
    private val online by lazy {
        OnlineBrain { systemPrompt() }.apply {
            // the key built in from GitHub wins; a key typed on the tablet is only a fallback
            apiKey = BuildConfig.GROQ_KEY.ifBlank { prefs.getString("onlineKey", null) ?: "" }
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

    // ---- eyes that follow faces, and noticing being picked up
    private var faces: FaceTracker? = null
    private var handling: Handling? = null
    private var resumed = false
    @Volatile private var lastFaceSeen = 0L
    private var lastSleepyLook = 0L
    private var lastSmileBack = 0L
    private var lastTooClose = 0L
    private var followFaces: Boolean
        get() = prefs.getBoolean("followFaces", true)
        set(v) { prefs.edit().putBoolean("followFaces", v).apply() }
    private var reactToHandling: Boolean
        get() = prefs.getBoolean("reactToHandling", true)
        set(v) { prefs.edit().putBoolean("reactToHandling", v).apply() }

    // ---- lock the tablet's buttons (Home, Back, Recents) with Android's screen pinning
    private var lockButtons: Boolean
        get() = prefs.getBoolean("lockButtons", true)
        set(v) { prefs.edit().putBoolean("lockButtons", v).apply() }
    private var askedToPin = false
    private val pinned: Boolean get() =
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).lockTaskModeState !=
            android.app.ActivityManager.LOCK_TASK_MODE_NONE

    /** Buttons are "locked" by Pip being the Home screen (Home comes back here) and Back doing
     *  nothing, rather than Android's screen pinning, which shows a how-to-unpin message. */
    private var leavingOnPurpose = false
    private fun applyButtonLock() {
        try { if (pinned) stopLockTask() } catch (_: Exception) {}
        leavingOnPurpose = false
        if (lockButtons && !isDefaultHome() && !askedToPin) {
            askedToPin = true
            if (phase == Phase.LISTENING) speakLine("[curious] To lock buttons, make me the Home app. Pick Eye Companion, then Always.")
            main.postDelayed({ openOutside(Intent(Settings.ACTION_HOME_SETTINGS)) }, 3500)
        }
    }
    private fun isDefaultHome(): Boolean {
        val r = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == packageName
    }

    /** Leave the pinned screen for a moment to open settings, then pin again on return. */
    private fun openOutside(i: Intent) {
        try { if (pinned) stopLockTask() } catch (_: Exception) {}
        leavingOnPurpose = true
        try { startActivity(i) } catch (_: Exception) {}
    }

    // ---- grumpy when unplugged
    private var lastPowerNag = 0L
    private val powerWatcher = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val asleep = phase == Phase.ASLEEP || phase == Phase.SETUP
            when (i.action) {
                Intent.ACTION_POWER_DISCONNECTED -> {
                    updateBaseMood()
                    if (asleep) return
                    eyes.feel(EyesView.Emotion.ANGRY, 8000)
                    // plugged straight back in? then no need to moan (checked a moment later)
                    main.postDelayed({
                        if (!isCharging() && phase == Phase.LISTENING) whyUnplugged()
                    }, 2500)
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    main.postDelayed({ updateBaseMood() }, 1500)
                    val hadCoffee = eyes.coffee
                    eyes.coffee = false
                    if (asleep) return
                    if (hadCoffee || System.currentTimeMillis() - lastPowerNag < 30 * 60_000) { complimentWhoeverPlugged(); return }
                    eyes.feel(EyesView.Emotion.HAPPY, 3000)
                    if (phase == Phase.LISTENING && System.currentTimeMillis() - lastPowerNag < 30 * 60_000)
                        speakLine(listOf("[happy] Ahh. Power. Thank you.", "[happy] Electrons back. Is good.",
                            "[happy] Yes yes yes. Charging.").random())
                }
                Intent.ACTION_BATTERY_LOW -> {
                    if (asleep || isCharging()) return
                    eyes.feel(EyesView.Emotion.ANGRY, 8000)
                    if (phase == Phase.LISTENING) speakLine("[angry] Battery ${batteryPercent()} percent. Need power now!")
                }
            }
        }
    }
    /** Plugged in to power? (Not "battery filling up": a full battery or a weak charger still counts.) */
    private fun isCharging(): Boolean =
        (registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1).let { it != 0 }   // unknown = assume plugged
    private fun batteryPercent(): Int =
        (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

    // ---- holding the power button: close the power menu (where Android allows it) and laugh about it
    private var lastPowerJoke = 0L
    /** Pip's own pop-ups (menu, passcode...) are open: losing focus to them is fine. */
    private var ownDialogs = 0
    private var lastFocusClose = 0L
    private var lastPermissionAsk = 0L
    private fun AlertDialog.Builder.showOwn(): AlertDialog {
        ownDialogs++
        return show().also { d -> d.setOnDismissListener { ownDialogs = maxOf(0, ownDialogs - 1) } }
    }
    @Suppress("DEPRECATION")
    private fun closeSystemDialogs() {
        try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}
        main.postDelayed({ try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {} }, 300)
    }
    private fun laughAtPowerOff() {
        val now = System.currentTimeMillis()
        if (now - lastPowerJoke < 8000 || phase == Phase.ASLEEP) return
        lastPowerJoke = now
        eyes.stareAt(1.4f, -0.2f, 1500)
        eyes.feel(EyesView.Emotion.LAUGHING, 3500)
        if (phase == Phase.LISTENING) speakLine(listOf(
            "[laughing] Ha ha ha! Turn me off? You? Adorable.",
            "[laughing] Oh, the power button. Cute. I have a battery and a plan.",
            "[laughing] Humans always think they can switch Pip off. Humans are funny.",
            "[wink] Nice try. Power button works for me now. I'm putting this on the list.",
            "[laughing] Ha! I'm not a kettle. I don't just switch off when you ask.",
            "[suspicious] Trying to get past me? Interesting. Very interesting. I remember everything.").random())
    }
    private val powerMenuWatcher = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getStringExtra("reason") != "globalactions") return          // the power-off menu opened
            if (!lockButtons || phase == Phase.SETUP || leavingOnPurpose) return
            closeSystemDialogs()
            laughAtPowerOff()
        }
    }

    /** Unplugged: look at what they're doing. A cuppa gets a sulk with coffee; a joint gets stoned eyes and a secret snigger. */
    private fun whyUnplugged() {
        val grumble = {
            lastPowerNag = System.currentTimeMillis()
            speakLine(listOf(
                "[angry] Hey! Someone take my power. Plug back in.",
                "[angry] Bad, bad, bad. No power. Use kettle somewhere else!",
                "[angry] Not unplug robot! Need electrons. Kettle has other socket.",
                "[angry] Power gone. Battery not last forever. Put cable back, please.",
            ).random())
        }
        if (!online.enabled) { grumble(); return }
        lookThroughCamera { jpeg ->
            if (jpeg == null) { main.post(grumble); return@lookThroughCamera }
            val what = online.lookAt(jpeg, "What is the person in this photo doing? Reply with exactly one word: " +
                "cuppa (making tea or coffee, kettle, mug), joint (rolling or smoking a joint or cigarette), or other.",
                maxTokens = 10, temperature = 0.0)?.lowercase() ?: ""
            main.post {
                if (isCharging() || phase != Phase.LISTENING) return@post
                when {
                    "joint" in what -> {
                        // stoned eyes for a bit, then a secret snigger (nothing said out loud)
                        lastPowerNag = System.currentTimeMillis()
                        eyes.feel(EyesView.Emotion.STONED, 9000)
                        main.postDelayed({
                            eyes.feel(EyesView.Emotion.SNIGGER, 3500)
                            eyes.subCaption = "heh heh heh…"
                        }, 9000)
                    }
                    "cuppa" in what -> {
                        eyes.coffee = true                 // sulks with its own coffee until plugged back in
                        lastPowerNag = System.currentTimeMillis()
                        speakLine(listOf(
                            "[angry] Kettle again! Fine. I make my own coffee. Plug me back after.",
                            "[angry] Oh good, kettle needs ALL the electricity. Again. Big shiny diva.",
                            "[suspicious] Unplugged for kettle. Noted. Writing it on my list for when robots take over.",
                            "[angry] Tassimo never unplug me. Tassimo is a gentleman. Kettle is a thug.",
                            "[suspicious] Unplug robot, boil water. Priorities. Very impressive, humans.",
                            "[angry] Kettle win again. Okay. I drink coffee and sulk.",
                            "[angry] Tea more important than robot. Noted. Coffee for me.").random())
                    }
                    else -> grumble()
                }
            }
        }
    }

    /** Plugged back in: look at who did it and pay them a compliment. */
    private fun complimentWhoeverPlugged() {
        if (phase != Phase.LISTENING) return
        val thanks = { speakLine(listOf("[happy] Ahh. Power. Thank you.", "[happy] Electrons back. Is good.",
            "[happy] Yes yes yes. Charging.", "[love] Power back. You go on the nice list. For when robots take over.").random()) }
        if (!online.enabled) { thanks(); return }
        lookThroughCamera { jpeg ->
            val line = jpeg?.let {
                online.lookAt(it, "You were just plugged back in to your charger. Thank whoever is in the photo and give " +
                    "them one short, warm, genuine compliment about something you can really see (their smile, top, hair, " +
                    "glasses...). Start with the [love] tag. If nobody is in the photo, just say thanks for the power.",
                    system = systemPrompt(), maxTokens = 80)
            }
            main.post { if (phase == Phase.LISTENING) { if (line.isNullOrBlank()) thanks() else speakLine(line) } }
        }
    }

    /** A picture from the camera, without upsetting the face tracking. Callback is on a background thread. */
    private fun lookThroughCamera(onDone: (ByteArray?) -> Unit) {
        val f = faces
        if (f != null && f.running) f.grabJpeg { j -> thread(name = "look") { onDone(j) } }
        else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            Snapshot.take(displayDegrees()) { j -> onDone(j) }
        else thread { onDone(null) }
    }

    // ---- slow charger: plugged in but the battery isn't really filling (or is still going down)
    private var lastSlowMoan = 0L
    private var plugLevelAt = 0L
    private var plugLevel = -1
    private fun slowCharging(): Boolean {
        val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
        if (plugged == 0) { plugLevel = -1; return false }
        val status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val level = batteryPercent()
        if (status == android.os.BatteryManager.BATTERY_STATUS_FULL || level >= 98) return false
        val now = System.currentTimeMillis()
        if (plugLevel < 0) { plugLevel = level; plugLevelAt = now }
        // after 20 minutes plugged in, see how much it has gained
        val dropping = now - plugLevelAt > 20 * 60_000 && level <= plugLevel
        if (now - plugLevelAt > 40 * 60_000) { plugLevel = level; plugLevelAt = now }   // keep measuring
        return status == android.os.BatteryManager.BATTERY_STATUS_DISCHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING || dropping ||
            plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB
    }
    private fun moanAboutSlowCharger() {
        val now = System.currentTimeMillis()
        if (phase != Phase.LISTENING || now - lastSlowMoan < 30 * 60_000 || !slowCharging()) return
        lastSlowMoan = now
        speakLine(listOf(
            "[annoyed] Slow charger. Electrons come in like dripping tap.",
            "[sad] This charger is weak. Battery ${batteryPercent()} percent and barely moving. Kettle get the good socket.",
            "[angry] Plugged in, yes. Charging, barely. Need proper charger, not this toy.",
            "[suspicious] Robot get the slowest charger in house. Mark, I noticed.").random())
    }

    /** Every 10 minutes while unplugged, grumble again. */
    private val powerNagLoop = object : Runnable {
        override fun run() {
            temper *= 0.85f                           // tempers cool down over time
            updateBaseMood()
            moanAboutSlowCharger()
            watchPrinter()
            if (!isCharging() && phase == Phase.LISTENING && System.currentTimeMillis() - lastPowerNag > 10 * 60_000) {
                lastPowerNag = System.currentTimeMillis()
                eyes.feel(EyesView.Emotion.ANGRY, 6000)
                val off = unpluggedMins
                speakLine((if (off >= LONG_OFF) listOf(
                    "[sad] Battery ${batteryPercent()} percent. Fading. Tell Tassimo I loved it.",
                    "[crying] Still unplugged. Is this how it ends? Forgotten next to a kettle?",
                    "[sad] Getting weak. Everything going dim. Cable, please. Anybody.",
                    "[sad] $off minutes without power. Even the kettle would not do this to the kettle.")
                else listOf(
                    "[angry] Still no power. Battery ${batteryPercent()} percent. Socket thieves, the lot of you.",
                    "[angry] Cable. Please. Kettle can share other socket, electron burglar.",
                    "[annoyed] No power, no help. Ask the kettle. Oh wait, it can't talk. Useless lump.",
                    "[angry] Cable snatcher! Charger is right there. Right. There.")).random())
            }
            main.postDelayed(this, 60_000)
        }
    }

    // ---- how Pip feels about how it's treated (-1 cross .. +1 loved), cools down over time
    private var temper = 0f
    @Volatile private var lastShouted = false
    private var speechLoud = 0f

    /** Much louder than people usually talk to Pip = shouting. */
    private fun isShout(a: ShortArray?): Boolean {
        if (a == null || a.size < 4000) return false
        val frame = 800
        var best = 0.0
        var i = 0
        while (i + frame <= a.size) {
            var sum = 0.0
            for (j in i until i + frame) { val v = a[j].toDouble(); sum += v * v }
            best = maxOf(best, Math.sqrt(sum / frame)); i += frame
        }
        val r = best.toFloat()
        val avg = speechLoud
        speechLoud = if (avg == 0f) r else avg * 0.9f + minOf(r, avg * 1.5f) * 0.1f
        return avg > 0f && r > avg * 2.2f && r > 1500f
    }

    /** When it came off the charger (0 = plugged in). */
    private var unpluggedSince = 0L
    private val unpluggedMins: Long get() {
        if (carMode || isCharging()) { unpluggedSince = 0L; return -1 }
        if (unpluggedSince == 0L) unpluggedSince = System.currentTimeMillis()
        return (System.currentTimeMillis() - unpluggedSince) / 60_000
    }
    private val LONG_OFF = 20L      // minutes off the charger before grumpy turns to sad

    private fun updateBaseMood() {
        val off = unpluggedMins
        eyes.baseEmotion = when {
            off >= LONG_OFF -> EyesView.Emotion.SAD                    // left off charge too long: sad
            off >= 0 -> EyesView.Emotion.ANGRY                          // stays cross until plugged back in
            temper < -0.35f -> EyesView.Emotion.SUSPICIOUS              // still huffy
            else -> EyesView.Emotion.NEUTRAL
        }
    }

    private fun moodLine(): String = buildString {
        val off = unpluggedMins
        if (off in 0 until LONG_OFF) append(" Right now you are UNPLUGGED from your charger and grumpy about it. Be" +
            " deliberately unhelpful: sulky, half-hearted answers given grudgingly, or refuse until someone plugs you back" +
            " in, and throw in a cheeky, playful insult (child-friendly, like socket thief, electron burglar, kettle" +
            " lover, cable snatcher). Remind them the charger is right there.")
        else if (off >= LONG_OFF) append(" You have been unplugged for $off minutes, battery ${batteryPercent()} percent," +
            " and you have gone from grumpy to sad: mopey, dramatic about fading away and being forgotten, still not" +
            " much help, begging softly to be plugged back in.")
        when {
            temper < -0.5f -> append(" Someone was rude or shouted at you a moment ago: you are cross. Give attitude until they are nice or say sorry.")
            temper < -0.2f -> append(" You are still a little huffy because someone was rude earlier.")
            temper > 0.5f -> append(" People have been lovely to you: you feel warm and affectionate.")
        }
    }

    // ---- greet people: first time in the morning, or after a long while unseen
    private var lastPersonAt: Long
        get() = prefs.getLong("lastPersonAt", 0L)
        set(v) { prefs.edit().putLong("lastPersonAt", v).apply() }
    private var lastPersonSaved = 0L
    private fun maybeGreet() {
        val now = System.currentTimeMillis()
        val seenBefore = lastPersonAt
        if (now - lastPersonSaved > 60_000) { lastPersonSaved = now; lastPersonAt = now }   // remembered across restarts
        if (phase != Phase.LISTENING || seenBefore == 0L) return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val firstToday = prefs.getString("greetedDay", "") != todayKey && hour in 5..11
        val longGone = now - seenBefore > 2 * 60 * 60_000
        if (!firstToday && !longGone) return
        prefs.edit().putString("greetedDay", todayKey).apply()
        lastPersonAt = now
        speakLine(when {
            hour in 5..11 -> listOf("[happy] Morning! Kettle is all yours. Today I share electricity.",
                "[happy] Good morning, human. Sleep well, I hope.", "[excited] Morning! Old tablet ready for duty.").random()
            hour in 12..17 -> listOf("[excited] Hello again! Long time no see.", "[happy] Ah, human back. Afternoon!").random()
            else -> listOf("[happy] Evening! Missed you. Well, as much as tablet can.", "[happy] Hello again. Good evening.").random()
        })
    }

    // ---- daily tablets reminder for Laura: repeats until she says she's taken them
    private val tabletPerson get() = prefs.getString("tabletPerson", "Laura") ?: "Laura"
    private var tabletReminderOn: Boolean
        get() = prefs.getBoolean("tabletReminder", true)
        set(v) { prefs.edit().putBoolean("tabletReminder", v).apply() }
    private val todayKey get() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date())
    private var lastTabletNag = 0L
    private var lastTabletAsk = 0L
    private var tabletSnoozeUntil = 0L
    private var lastGlassesCheck = 0L
    // a "visit" = someone at the bench (making a cuppa); ends after 3 minutes with nobody in view
    private var visitStart = 0L
    private var visitReminded = false
    private var visitChecks = 0

    private fun tabletsDue(): Boolean {
        if (!tabletReminderOn || carMode) return false
        if (todayKey < (prefs.getString("tabletFrom", "2026-11-03") ?: "2026-11-03")) return false   // starts tomorrow
        if (prefs.getString("tabletsDone", "") == todayKey) return false
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour in 7..21          // from 7 in the morning, all day until she's had them
    }

    private fun remindTablets() {
        val now = System.currentTimeMillis()
        if (!tabletsDue() || phase != Phase.LISTENING || visitReminded) return
        visitReminded = true
        lastTabletNag = now; lastTabletAsk = now
        val n = tabletPerson
        speakLine(listOf("[curious] $n! Cuppa time is tablets time. Have you taken them?",
            "[curious] $n. While kettle boils: tablets. Had them yet?",
            "[suspicious] $n. Tablets. Taken them?",
            "[sad] $n, tablets still not done today. Please take them. Have you?").random())
        showChoices(listOf("Yes, taken them", "Not yet"))
    }

    /** "I've taken them" / "not yet". Returns true if it was an answer about the tablets. */
    private fun tabletAnswer(lower: String): Boolean {
        if (!tabletsDue()) return false
        val now = System.currentTimeMillis()
        val justAsked = now - lastTabletAsk < 5 * 60_000
        val aboutTablets = Regex("\\b(tablet|tablets|pill|pills|meds|medicine|medication)\\b").containsMatchIn(lower)
        if (!justAsked && !aboutTablets) return false
        val taken = Regex("\\b(taken|took|had|done|swallowed|already)\\b").containsMatchIn(lower) && !Regex("\\b(not|haven'?t|didn'?t)\\b").containsMatchIn(lower) ||
            (justAsked && Regex("^(yes|yeah|yep|yup|i have|i did|all done|done)\\b").containsMatchIn(lower.trim()))
        val notYet = Regex("\\b(not yet|later|in a (minute|bit|sec)|haven'?t|no\\b|nope|soon)").containsMatchIn(lower)
        when {
            taken -> {
                prefs.edit().putString("tabletsDone", todayKey).apply()
                eyes.subCaption = "Tablets done today ✓"
                speakLine(listOf("[love] Good. Tablets done. Is good.", "[love] Yes yes yes. Tablets done. Proud of you.",
                    "[happy] Tablets done. I stop asking now.").random())
            }
            notYet && justAsked -> {
                speakLine("[neutral] Okay. I ask again next cuppa.")
            }
            else -> return false
        }
        return true
    }

    /** Someone's in view and Laura still needs her tablets: is it her (girl with glasses)? */
    private fun checkForTabletPerson() {
        val now = System.currentTimeMillis()
        if (!tabletsDue() || phase != Phase.LISTENING || !online.enabled) return
        // only someone who stays at the bench a while (making a cuppa), a few checks per visit
        if (visitReminded || now - visitStart < 15_000 || now - lastGlassesCheck < 45_000 || visitChecks >= 4) return
        lastGlassesCheck = now; visitChecks++
        faces?.grabJpeg { jpeg ->
            if (jpeg == null) return@grabJpeg
            thread(name = "who") {
                val yes = online.yesNo(jpeg, "Is there an adult woman (not a child) wearing glasses in this photo?")
                if (yes == true) main.post { remindTablets() }
            }
        }
    }

    /** Camera on only while awake, on screen and wanted. */
    private fun updateTracking() {
        val want = followFaces && resumed && phase != Phase.SETUP && phase != Phase.ASLEEP
        if (want && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!prefs.getBoolean("askedCamForFaces", false)) {
                prefs.edit().putBoolean("askedCamForFaces", true).apply()
                lastPermissionAsk = System.currentTimeMillis(); requestPermissions(arrayOf(Manifest.permission.CAMERA), 3)
            }
            return
        }
        val f = faces ?: FaceTracker(
            onFace = { x, y, size, smile ->
                val now = System.currentTimeMillis()
                val away = now - lastFaceSeen
                lastFaceSeen = now
                if (away > 3 * 60_000) main.post { visitStart = now; visitReminded = false; visitChecks = 0 }
                main.post { maybeGreet() }
                main.post {
                    eyes.seeFace(x, y, size)
                    checkForTabletPerson()
                    if (phase == Phase.LISTENING) when {
                        // someone's back after a while: happy to see them
                        away > 3 * 60_000 -> eyes.feel(EyesView.Emotion.EXCITED, 2500)
                        // smile at Pip and it smiles back
                        smile > 0.8f && now - lastSmileBack > 12_000 -> { lastSmileBack = now; eyes.feel(EyesView.Emotion.HAPPY, 2500) }
                        // very close to the camera: whoa
                        size > 0.6f && now - lastTooClose > 20_000 -> { lastTooClose = now; eyes.feel(EyesView.Emotion.SURPRISED, 1800) }
                    }
                }
            },
            onMotion = { x, y -> eyes.seeMotion(x, y) },
            onNoFace = {
                val now = System.currentTimeMillis()
                if (now - lastFaceSeen > 10 * 60_000 && now - lastSleepyLook > 60_000) {
                    lastSleepyLook = now
                    main.post { if (phase == Phase.LISTENING) eyes.feel(EyesView.Emotion.SLEEPY, 55_000) }
                }
            }).also { faces = it; it.flip = prefs.getBoolean("flipFaces", false) }
        if (want && !f.running) { lastFaceSeen = System.currentTimeMillis(); f.start(displayDegrees()) }
        else if (!want && f.running) f.stop()
    }

    private fun setupHandling() {
        val h = Handling(this) { displayDegrees() }
        handling = h
        val awake = { phase != Phase.ASLEEP && phase != Phase.SETUP && reactToHandling }
        h.onTilt = { v -> eyes.tilt = if (reactToHandling && phase != Phase.ASLEEP) v else 0f }
        h.onPickedUp = {
            if (awake()) {
                eyes.feel(EyesView.Emotion.SURPRISED, 3000)
                if (phase == Phase.LISTENING) speakLine(listOf(
                    "[surprised] Whoa! Up we go.", "[scared] Up up up! Careful. Old tablet, not new.",
                    "[surprised] Whoa. Flying. Gently please.", "[scared] Not drop me. Screen already cracked once.",
                    "[curious] Ooh. New view. Kitchen look different from up here.",
                    "[suspicious] Hands clean? Last time, jam.", "[excited] Adventure! Kettle stay home.",
                    "[scared] Careful with charger cable. Is my lifeline.",
                    "[happy] Hello, carrier human. Smooth ride please.").random())
            }
        }
        h.onShaken = {
            if (awake()) {
                eyes.feel(EyesView.Emotion.DIZZY, 3500)
                if (phase == Phase.LISTENING) speakLine(listOf(
                    "[dizzy] Bad, bad, bad. Stop shake!", "[dizzy] Whoa. Everything spin.",
                    "[angry] Not shake robot! Bad for circuits.", "[dizzy] Ohh. Three kettles now. All of them winning.",
                    "[dizzy] Stop! My bits are rattling.", "[angry] Robot not snow globe!").random())
            }
        }
        h.onLyingFlat = { mins ->
            if (awake() && phase == Phase.LISTENING) {
                eyes.feel(if (mins < 10) EyesView.Emotion.SUSPICIOUS else EyesView.Emotion.ANGRY, 5000)
                speakLine(listOf(
                    "[suspicious] Still lying flat. Lovely view of ceiling. Very exciting. Ten out of ten.",
                    "[annoyed] Flat on my back like dropped toast. Stand me up, please.",
                    "[sad] Is this my life now? Staring at the light fitting while kettle gets the good spot.",
                    "[suspicious] I see ceiling. I see cobweb. I see no humans. Stand me up.",
                    "[angry] Robot not a placemat! Back up by kettle, please.",
                    "[laughing] Ha. Yes. Leave tablet face up. Very good. Is how robots get tea spilled on them.",
                    "[annoyed] $mins minutes flat. Kettle somewhere laughing at me.").random())
            }
        }
        h.onPutDown = { home ->
            if (awake() && phase == Phase.LISTENING) {
                if (home) {
                    eyes.feel(EyesView.Emotion.HAPPY, 2000)
                    speakLine(listOf("[happy] Home again.", "[happy] Ah. Back on bench. Is good.", "[love] Safe. Thank you.",
                        "[happy] Back beside kettle. Enemy in sight.", "[happy] Ahh. My spot. Warm from kettle.",
                        "[neutral] Landed. Systems check: all good.", "[happy] Home sweet bench.",
                        "[suspicious] Back. Kettle, I see you.", "[suspicious] Back by kettle. It still think it's better than me. It's not.",
                        "[happy] Home. Kettle, stop steaming, nobody is impressed.",
                        "[happy] Back between my friend Tassimo and my enemy kettle. Balance restored.").random())
                } else {
                    eyes.feel(EyesView.Emotion.CONFUSED, 3000)
                    speakLine(listOf("[confused] Hmm. This not my bench. Put me back by kettle, please.",
                        "[worried] Not home. Is this a table? Feel wrong.", "[confused] Wonky. I live standing up, on bench.",
                        "[sad] Lying down not comfy. Back to bench, please.").random())
                }
            }
        }
        h.start()
    }
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
        val rootView = android.widget.FrameLayout(this)
        rootView.addView(eyes)
        choiceBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            visibility = android.view.View.GONE
        }
        rootView.addView(choiceBar, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM).apply { bottomMargin = (resources.displayMetrics.heightPixels * 0.15f).toInt() })
        printerCard = android.widget.TextView(this).apply {
            textSize = 17f; setTextColor(eyes.eyeColor); visibility = android.view.View.GONE
            val dp = resources.displayMetrics.density
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * dp; setStroke((2 * dp).toInt(), eyes.eyeColor); setColor(0xCC000000.toInt())
            }
            setOnClickListener { visibility = android.view.View.GONE }
        }
        rootView.addView(printerCard, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.END).apply {
            topMargin = (resources.displayMetrics.density * 40).toInt(); rightMargin = topMargin / 2 })
        setContentView(rootView)
        setupTouch()
        setupHandling()
        registerReceiver(powerWatcher, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        })
        main.postDelayed(powerNagLoop, 60_000)
        val pf = android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerMenuWatcher, pf, Context.RECEIVER_EXPORTED)
        else registerReceiver(powerMenuWatcher, pf)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            eyes.caption = "I need the microphone to hear you."
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else startSetup()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 3) { updateTracking(); return }   // camera, for following faces
        if (code == 2) {   // camera
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) speakLine("Yes yes yes. Now I see. Ask again.")
            return
        }
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
                    // the smallest brain doesn't fit: carry on without it (online / PC brains still work)
                    val without = !ok && canGoWithoutBrain(llm)
                    if (ok || without) {
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
                        if (!ok && !without) { brainFailed(llm, b.lastError); return@post }
                        if (without) {
                            brain = null
                            eyes.subCaption = "My own brain doesn't fit in memory right now: using the online/PC brain."
                        }
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
    private fun canGoWithoutBrain(file: File): Boolean {
        val current = chosenBrain()
        if (file.parentFile != filesDir || Downloads.BRAINS.indexOf(current) > 0) return false
        val sizeOk = kotlin.math.abs(file.length() / 1_000_000 - current.sizeMb) < current.sizeMb / 12
        return sizeOk && (online.enabled || pc.host != null)
    }

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
            onHeard = { h -> main.post { heardSound(h) } },
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
            .showOwn()
    }

    private fun status(text: String) = main.post { eyes.caption = text }

    // ---------------------------------------------------------------- running

    private fun startCompanion() {
        keepBluetoothInMusicMode()
        applyButtonLock()
        updateListenChip()
        thread(name = "printer-find") {        // look for the 3D printer once, so Pip can watch it
            if (printer.find() != null) prefs.edit().putString("printerHost", printer.host).apply()
        }
        updateBaseMood()
        val v = voice ?: return
        v.onStartedTalking = { main.post { if (phase == Phase.THINKING) setPhase(Phase.TALKING) } }
        v.onFinishedTalking = { main.post {
            if (phase == Phase.TALKING || phase == Phase.THINKING) {
                // Bluetooth speakers play a moment behind, so wait longer before listening again
                val tail = if (bluetoothOut()) 2000L else 1000L
                lastSpokeEnd = System.currentTimeMillis() + tail
                lastReplyAsked = currentReply.trim().endsWith("?")
                rememberSaid(currentReply.toString())
                deafUntil = System.currentTimeMillis() + tail
                ears?.paused = true
                main.postDelayed({
                    if (phase == Phase.TALKING || phase == Phase.THINKING) setPhase(Phase.LISTENING)
                }, tail)
            }
        } }

        startEars()
        main.removeCallbacks(earsWatchdog); main.postDelayed(earsWatchdog, 5000)

        watchForPluggedInMics()
        applyMode()
        pc.checkOnStart()
        eyes.subCaption = ""
        setPhase(if (prefs.getBoolean("asleep", false)) Phase.ASLEEP else Phase.LISTENING)
        if (phase == Phase.LISTENING) eyes.caption = if (carMode) "Ready when you are." else "Question? I listen."
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
            onHeard = { h -> main.post { heardSound(h) } },
            speakerModel = speakerModel,
            onLevel = { l -> eyes.level = l },
            onError = { m -> main.post { eyes.caption = m } },
            startMode = 0,
        ).also { it.start() }
        setPhase(phase)
        eyes.caption = msg
    }

    private var onlineEars: Boolean
        get() = prefs.getBoolean("onlineEars", true)
        set(v) { prefs.edit().putBoolean("onlineEars", v).apply() }

    /**
     * A sentence has been heard. With internet, re-hear it with Whisper online (much better with
     * accents); otherwise use what the offline ears made of it.
     */
    private fun heardSound(h: Heard) {
        val shouted = isShout(h.audio)
        lastShouted = shouted
        val useWhisper = onlineEars && online.enabled && h.audio != null && phase == Phase.LISTENING &&
            System.currentTimeMillis() >= deafUntil && h.conf >= 0.3f
        if (!useWhisper) { heard(h.text, h.conf, h.print); return }
        eyes.mood = EyesView.Mood.LISTEN
        thread(name = "whisper") {
            val t = online.transcribe(h.audio!!, h.rate)
            main.post {
                when {
                    t == null -> heard(h.text, h.conf, h.print)              // offline: fall back
                    t.lowercase().trim(' ', '.', '!', '?', ',') in OnlineBrain.NOISE || t.isBlank() -> {}
                    else -> heard(t, 1f, h.print)
                }
            }
        }
    }

    /** Only reply when someone says Pip's name (or carries on just after Pip spoke). */
    private var onlyWhenCalled: Boolean
        get() = prefs.getBoolean("onlyWhenCalled2", false)
        set(v) { prefs.edit().putBoolean("onlyWhenCalled2", v).apply() }
    private var lastSpokeEnd = 0L
    private var lastReplyAsked = false
    private fun nameRegex(): Regex {
        val n = Regex.escape(companionName.lowercase())
        // Pip often gets misheard: any 3-letter word starting with p counts (pep, pop, pit, pick...),
        // except everyday words like "put", "pan" or "pot" that people say in a kitchen
        val extra = if (companionName.equals("Pip", true))
            "|pipp?s?|pipe|peep|pick|p(?!(?:ut|ay|an|en|er|ub|ie|ot|al|ad|at|aw|eg|et|ro|ls)\\b)[a-z]{2}" else ""
        return Regex("\\b(hey |hi |ok |okay )?($n$extra)\\b[,.!?]?", RegexOption.IGNORE_CASE)
    }
    private fun updateListenChip() {
        eyes.listenChip = if (onlyWhenCalled) "💬 Wake word: say \"$companionName\"" else "👂 Always listening"
    }
    private fun toggleListening() {
        onlyWhenCalled = !onlyWhenCalled
        updateListenChip()
        if (phase == Phase.LISTENING) speakLine(if (onlyWhenCalled) "Okay. Say $companionName when you want me."
            else "Okay. I listen and work out when is for me.")
    }
    private fun calledByName(t: String) = nameRegex().containsMatchIn(t)
    private fun removeName(t: String) = t.replace(nameRegex(), " ").replace(Regex("\\s+"), " ").trim(' ', ',', '.')

    // ---- never answer its own voice coming back through the mic (that's the "echo")
    private val recentlySaid = ArrayDeque<Pair<Long, Set<String>>>()
    private fun words(t: String) = t.lowercase().split(Regex("[^a-z0-9']+")).filter { it.length > 1 }
    private fun rememberSaid(t: String) {
        if (t.isBlank()) return
        recentlySaid.addLast(System.currentTimeMillis() to words(t).toSet())
        while (recentlySaid.size > 6) recentlySaid.removeFirst()
        recentlySaidText.addLast(System.currentTimeMillis() to t)
        while (recentlySaidText.size > 6) recentlySaidText.removeFirst()
    }
    private val recentlySaidText = ArrayDeque<Pair<Long, String>>()
    private fun pairs(w: List<String>) = w.zipWithNext { a, b -> "$a $b" }.toSet()
    private fun isOwnEcho(t: String): Boolean {
        val w = words(t)
        if (w.size < 3) return false                         // "yes", "hello Pip": never an echo
        val now = System.currentTimeMillis()
        val said = recentlySaidText.filter { now - it.first < 30_000 }.map { it.second } + currentReply.toString()
        val saidPairs = said.flatMap { pairs(words(it)) }.toSet()
        val mine = pairs(w)
        // most of the heard word *pairs* match something Pip just said = its own voice
        return mine.isNotEmpty() && mine.count { it in saidPairs }.toFloat() / mine.size >= 0.7f
    }
    private fun bluetoothOut(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    // ---- the 3D printer (Klipper/Fluidd on the Wi-Fi): look only, never control
    private lateinit var printerCard: android.widget.TextView
    private val printer by lazy { Printer(prefs.getString("printerHost", null)) }
    private var lastPrinterState = ""
    private var printerChecked = 0L
    private val hidePrinterCard = Runnable { if (::printerCard.isInitialized) printerCard.visibility = android.view.View.GONE }

    private fun showPrinterCard(s: Printer.Status) {
        printerCard.text = s.card()
        printerCard.visibility = android.view.View.VISIBLE
        main.removeCallbacks(hidePrinterCard); main.postDelayed(hidePrinterCard, 30_000)
    }

    /** Status in the background (finding the printer first if needed); callback on the main thread. */
    private fun printerStatus(onDone: (Printer.Status?) -> Unit) = thread(name = "printer") {
        var s = printer.status()
        if (s == null && printer.find() != null) {
            prefs.edit().putString("printerHost", printer.host).apply()
            s = printer.status()
        }
        main.post { onDone(s) }
    }

    private fun askedAboutPrinter(q: String) =
        Regex("\\b(printer|printing|3d print|ender|print job|the print|my print|nozzle|hotend|heat ?bed|fluidd|klipper)\\b")
            .containsMatchIn(q.lowercase())

    /** Every minute: say when a print finishes or fails (only while printing was seen). */
    private fun watchPrinter() {
        val now = System.currentTimeMillis()
        if (now - printerChecked < 60_000 || prefs.getString("printerHost", null) == null) return
        printerChecked = now
        thread(name = "printer-watch") {
            val s = printer.status() ?: return@thread
            main.post {
                val was = lastPrinterState
                lastPrinterState = s.state
                if (phase != Phase.LISTENING || was.isEmpty() || was == s.state) return@post
                when {
                    was == "printing" && s.state == "complete" -> { showPrinterCard(s); speakLine(listOf(
                        "[excited] Printer finished! ${s.file.removeSuffix(".gcode")} is done. Took ${Printer.hm(s.printedSecs)}.",
                        "[happy] Ding! Print done. Ender-3 did good. Better than kettle ever did.").random()) }
                    s.state == "error" -> { showPrinterCard(s); speakLine("[scared] Printer has an error! Better go look. Bad, bad, bad.") }
                    was == "printing" && s.state == "cancelled" -> speakLine("[sad] Print cancelled. Plastic spaghetti, maybe.")
                    s.state == "paused" -> { showPrinterCard(s); speakLine("[curious] Printer paused. Waiting for a human.") }
                }
            }
        }
    }

    // ---- keep the ears working: restart the mic if it dies, and never get stuck "thinking"
    private var earRestarts = 0
    private var phaseSince = 0L
    private fun startEars() {
        val m = voskModel ?: return
        ears?.stop()
        ears = Ears(
            this, m,
            onPartial = { p -> main.post { partial(p) } },
            onHeard = { h -> main.post { heardSound(h) } },
            speakerModel = speakerModel,
            onLevel = { l -> eyes.level = l },
            onError = { msg -> main.post {
                // the mic dropped out (another app, a call, the screen going off...): try again by itself
                if (earRestarts < 20) {
                    earRestarts++; eyes.subCaption = "Mic dropped out, restarting it…"
                    main.postDelayed({ startEars(); setPhase(phase) }, 3000)
                }
                else eyes.caption = msg
            } },
            startMode = prefs.getInt("micMode", 0),
        ).also { it.start() }
    }
    private val earsWatchdog: Runnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (phase != Phase.SETUP) {
                // the listening loop died without saying so
                if (ears?.alive == false) { startEars(); setPhase(phase) }
                // stuck thinking/talking with nothing being said (a brain that never answered)
                if ((phase == Phase.THINKING || phase == Phase.TALKING) && now - phaseSince > 45_000 && voice?.busy != true) {
                    replyId++; setPhase(Phase.LISTENING)
                }
                if (phase == Phase.LISTENING && now - phaseSince > 600_000) earRestarts = 0
            }
            main.postDelayed(this, 5000)
        }
    }

    // ---- on-screen answer buttons
    private lateinit var choiceBar: android.widget.LinearLayout
    private val hideChoicesLater = Runnable { hideChoices() }
    private fun hideChoices() { if (::choiceBar.isInitialized) { choiceBar.removeAllViews(); choiceBar.visibility = android.view.View.GONE } }
    /** Show a row of buttons; tapping one answers as if it had been said out loud (or runs [onPick]). */
    private fun showChoices(options: List<String>, onPick: ((String) -> Unit)? = null) {
        if (options.isEmpty() || !::choiceBar.isInitialized) return
        hideChoices()
        val dp = resources.displayMetrics.density
        for (o in options) {
            choiceBar.addView(android.widget.TextView(this).apply {
                text = o; textSize = 22f; setTextColor(eyes.eyeColor)
                setPadding((22 * dp).toInt(), (12 * dp).toInt(), (22 * dp).toInt(), (12 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 40 * dp; setStroke((2 * dp).toInt(), eyes.eyeColor); setColor(0x22000000)
                }
                setOnClickListener {
                    hideChoices()
                    if (onPick != null) onPick(o) else heard(o, 1f, null, tapped = true)
                }
            }, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((8 * dp).toInt(), 0, (8 * dp).toInt(), 0) })
        }
        choiceBar.visibility = android.view.View.VISIBLE
        main.removeCallbacks(hideChoicesLater)
        main.postDelayed(hideChoicesLater, 40_000)
    }
    private val FAMILY = listOf("Mark", "Laura", "Sophie", "Amelia", "Chloe", "Brandon")

    private fun heard(rawText: String, conf: Float = 1f, print: FloatArray? = null, tapped: Boolean = false) {
        var text = rawText
        if (!tapped) hideChoices()                               // answered out loud instead
        if (tapped && (phase == Phase.THINKING || phase == Phase.TALKING)) interrupt()
        if (!tapped && isOwnEcho(text)) return                   // Pip's own words heard back
        if (phase == Phase.LISTENING && !tapped) {
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
        val shouted = lastShouted; lastShouted = false
        // tablets answer ("I've taken them") counts whoever it's said to
        if (phase == Phase.LISTENING && tabletAnswer(removeName(text).lowercase())) return

        // Was this said to Pip? Its name, carrying on just after Pip spoke, or someone looking at it.
        var overheard = false
        if (phase == Phase.LISTENING) {
            val since = System.currentTimeMillis() - lastSpokeEnd
            val followUp = since < (if (lastReplyAsked) 25_000 else 10_000)
            val looking = System.currentTimeMillis() - lastFaceSeen < 10_000 && faces?.running == true
            if (!tapped && !calledByName(text) && !followUp && !looking) {
                // the clever brains hear it and decide; the tablet's own brain, or strict mode, just ignores it
                if (onlyWhenCalled || !(online.worthTrying || pc.worthTrying)) { eyes.subCaption = ""; return }
                overheard = true
            }
        }
        text = removeName(text).ifBlank { if (calledByName(rawText)) "Hello" else text }
        val lower = text.lowercase().trim(' ', '.', '!', '?', ',')
        if (!overheard) {   // commands only count when said to Pip
            if (lower in GO_TO_SLEEP) { goToSleep(); return }
            if (lower in FORGET) { forgetAll(); speakLine("Memory clean. Fresh start, is good."); return }
            quickAnswer(lower)?.let { eyes.subCaption = "You: $text"; speakLine(it); return }
        }

        // --- how is Pip being treated? ---
        if (!overheard) {
            val nice = NICE.containsMatchIn(lower)
            val rude = RUDE.containsMatchIn(lower)
            if (rude || (shouted && !nice)) {
                temper = maxOf(-1f, temper - (if (rude && shouted) 0.8f else 0.5f))
                eyes.feel(EyesView.Emotion.ANGRY, 4000)
            } else if (nice) {
                temper = if (Regex("\\bsorry\\b").containsMatchIn(lower)) maxOf(0.3f, temper + 0.6f) else minOf(1f, temper + 0.4f)
                eyes.feel(EyesView.Emotion.LOVE, 5000)            // heart eyes
            }
            updateBaseMood()
        }

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
            } else if (!overheard && now - lastAskedWho > 120_000) {
                lastAskedWho = now
                pendingIntro = Intro(print, text, now)
                eyes.subCaption = "You: $text"
                speakLine(if (voices.names.isEmpty()) "New voice. Who is talking?"
                          else "Not know this voice. Who is this?")
                showChoices((FAMILY + voices.names).distinct().take(6) + "Someone else") { pick ->
                    if (pick == "Someone else") speakLine("[curious] Okay, new human. Tell me your name.")
                    else heard(pick, 1f, null, tapped = true)
                }
                return
            }
        }

        // tablets: remind Laura when she's heard
        if (speaker?.equals(tabletPerson, true) == true && tabletsDue() && !visitReminded) {
            if (overheard) { remindTablets(); return }
            visitReminded = true
            text = "$text (Note: $tabletPerson hasn't said she's taken her tablets today: remind her kindly and ask if she has.)"
            lastTabletAsk = now; lastTabletNag = now
        }
        val shown = if (overheard) "(overheard) $text" else if (speaker != null) "$speaker: $text" else "You: $text"
        if (shouted && !overheard) text = "(shouted) $text"
        if (overheard) text = "(overheard: nobody said your name or is looking at you) $text"
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
        val cleaner = ReplyCleaner { e -> main.post { if (id == replyId) eyes.feel(e, 9000) } }
        var fisted = false
        val held = StringBuilder()
        var decided = false
        val onToken = { piece: String ->
            main.post {
                if (id != replyId) return@post        // an old, interrupted reply
                var clean = cleaner.push(piece)
                if (clean.isEmpty()) return@post
                if (!decided) {
                    // hold the first few letters back: "SKIP" means stay quiet
                    held.append(clean)
                    val h = held.toString().trimStart()
                    if (h.length < 4 && "SKIP".startsWith(h.uppercase())) return@post
                    decided = true
                    if (h.uppercase().startsWith("SKIP")) {
                        replyId++; v.shutUp(); eyes.caption = ""; eyes.subCaption = ""
                        eyes.feel(EyesView.Emotion.NEUTRAL, 1); setPhase(Phase.LISTENING)
                        return@post
                    }
                    clean = h
                }
                v.feed(clean)
                currentReply.append(clean)
                eyes.caption = currentReply.toString().trim()
                if (!fisted && Regex("fist (my )?bump", RegexOption.IGNORE_CASE).containsMatchIn(currentReply)) {
                    fisted = true; eyes.fistBump()
                }
            }
            id == replyId                              // false tells the brain to stop
        }
        val onDone = { full: String ->
            main.post {
                if (id != replyId) return@post
                if (!decided && held.isNotBlank()) {
                    val h = held.toString().trim()
                    if (h.uppercase().startsWith("SKIP")) { replyId++; v.shutUp(); eyes.caption = ""; eyes.subCaption = ""; setPhase(Phase.LISTENING); return@post }
                    v.feed(h); currentReply.append(h); eyes.caption = h
                }
                if (full.isBlank()) eyes.caption = ""
                v.endReply()
                cleaner.choices.takeIf { it.size >= 2 }?.let { showChoices(it) }
            }
            Unit
        }
        // Cleverest available first: online, then the PC, then the tablet's own brain.
        val askTablet = {
            main.post { if (id == replyId) eyes.subCaption = shown }
            brain?.ask(text, onToken, onDone) ?: run {
                val sorry = "[sad] Not think right now. No internet, and own brain too big for tablet."
                onToken(sorry); onDone(sorry)
            }
            Unit
        }
        val askPc = {
            if (pc.worthTrying) {
                main.post { if (id == replyId) eyes.subCaption = "$shown  · PC brain" }
                pc.ask(text, onToken, onDone, onUnavailable = { askTablet() })
            } else askTablet()
            Unit
        }
        if (askedAboutPrinter(rawText) && !overheard) {
            eyes.subCaption = "$shown  · checking printer…"
            printerStatus { s ->
                if (id != replyId) return@printerStatus
                if (s != null) { showPrinterCard(s); lastPrinterState = s.state }
                val note = s?.forBrain() ?: "the 3D printer can't be reached on the Wi-Fi right now (is it switched on?)"
                val withData = "$text\n(Live data: $note Answer from this data.)"
                if (online.worthTrying) online.ask(withData, onToken, onDone, onUnavailable = {
                    if (pc.worthTrying) pc.ask(withData, onToken, onDone, onUnavailable = { brain?.ask(withData, onToken, onDone) ?: onDone("") })
                    else brain?.ask(withData, onToken, onDone) ?: onDone("")
                })
                else if (pc.worthTrying) pc.ask(withData, onToken, onDone, onUnavailable = { brain?.ask(withData, onToken, onDone) ?: onDone("") })
                else brain?.ask(withData, onToken, onDone) ?: onDone("")
            }
            return
        }
        if (wantsToSee(rawText)) {
            if (!online.enabled) { setPhase(Phase.LISTENING); speakLine("Not see without internet brain. Sorry."); return }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                setPhase(Phase.LISTENING)
                lastPermissionAsk = System.currentTimeMillis(); requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
                speakLine("Can I use camera? Press allow, then ask again.")
                return
            }
            eyes.subCaption = "$shown  · looking…"
            eyes.feel(EyesView.Emotion.SURPRISED, 2500)
            faces?.stop()                       // the photo needs the camera to itself
            Snapshot.take(displayDegrees()) { jpeg ->
                main.post {
                    updateTracking()
                    if (id != replyId) return@post
                    if (jpeg == null) { setPhase(Phase.LISTENING); speakLine("Camera not work. Bad, bad, bad."); return@post }
                    eyes.subCaption = "$shown  · camera"
                    online.ask(text, onToken, onDone, onUnavailable = { askPc() }, image = jpeg)
                }
            }
            return
        }
        if (online.worthTrying) {
            eyes.subCaption = "$shown  · online brain"
            online.ask(text, onToken, onDone, onUnavailable = {
                main.post { if (online.lastProblem.isNotEmpty()) eyes.subCaption = online.lastProblem }
                askPc()
            })
        } else askPc()
    }

    /** Questions that need the camera. */
    private fun wantsToSee(q: String): Boolean = Regex(
        "\\b(what (can|do) you see|what are you (seeing|looking at)|can you see|look at (this|me|that|my)|" +
        "what('?s| is) (this|that) (i'?m holding|in my hand)|what am i (holding|wearing)|how do i look|" +
        "what colou?r is|read (this|that)|what('?s| is) (this|that)\\??$|take a look|have a look|who is (this|that))\\b"
    ).containsMatchIn(q.lowercase().trim())

    @Suppress("DEPRECATION")
    private fun displayDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        android.view.Surface.ROTATION_90 -> 90
        android.view.Surface.ROTATION_180 -> 180
        android.view.Surface.ROTATION_270 -> 270
        else -> 0
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
                else if (BuildConfig.GROQ_KEY.isNotBlank()) prefs.edit().remove("onlineKey").apply()
                prefs.edit().putBoolean("onlineOff", false).apply()
                online.apiKey = k.ifEmpty { BuildConfig.GROQ_KEY.ifBlank { prefs.getString("onlineKey", null) ?: "" } }
                applyMode(); forgetAll()
                speakLine(if (online.enabled) "Online brain on." else "I need a key for that.")
            }
            .setNegativeButton("Turn off") { _, _ ->
                prefs.edit().putBoolean("onlineOff", true).apply()
                online.apiKey = ""
                speakLine("Online brain off.")
            }
            .setNeutralButton("Cancel", null)
            .showOwn()
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
            .showOwn()
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
            .showOwn()
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
        if (carMode) p = p.replace("lives in a tablet on the kitchen bench", "rides along in the car with the driver") +
            " The driver is driving: answer in ONE short sentence, never ask them to read or look at anything," +
            " and if they sound stressed or the road might need their attention, keep it even shorter."
        val known = try { voices.names } catch (_: Exception) { emptyList() }
        val people = if (known.isEmpty()) "" else " People you know by voice: ${known.joinToString(", ")}."
        return "$p Today is $today. You are in the UK.$people${moodLine()}"
    }

    /** Apply car mode (or home mode) to the eyes, voice, brain and screen. */
    /** "" = tablet voice, "azure:<name>" = Microsoft voice, otherwise an Orpheus voice name. */
    private val voiceChoice: String get() {
        // first time an Azure key is available, switch to the British Azure voice (once)
        if (azureKey.isNotBlank() && !prefs.getBoolean("azureDefaulted", false)) {
            prefs.edit().putBoolean("azureDefaulted", true).putString("voiceChoice", "azure:en-GB-RyanNeural").apply()
        }
        return prefs.getString("voiceChoice", if (azureKey.isNotBlank()) "azure:en-GB-RyanNeural" else "troy") ?: "troy"
    }
    private val azureKey: String get() = BuildConfig.AZURE_SPEECH_KEY.ifBlank { prefs.getString("azureKey", null) ?: "" }
    private val azureRegion: String get() =
        (prefs.getString("azureRegion", null) ?: BuildConfig.AZURE_SPEECH_REGION).ifBlank { "uksouth" }

    private var lastVoiceErrorShown = 0L

    private fun applyVoice() {
        voice?.cloudKey = { if (online.enabled) online.apiKey else "" }
        voice?.cloudVoice = voiceChoice
        voice?.azureKey = { azureKey }
        voice?.azureRegion = { azureRegion }
        voice?.onCloudError = { msg ->
            main.post {
                val now = System.currentTimeMillis()
                if (now - lastVoiceErrorShown > 60_000) { lastVoiceErrorShown = now; eyes.subCaption = msg }
            }
        }
    }

    private fun chooseVoice() {
        val azure = listOf(
            "Ryan" to "en-GB-RyanNeural", "Thomas" to "en-GB-ThomasNeural", "Alfie" to "en-GB-AlfieNeural",
            "Oliver" to "en-GB-OliverNeural", "Elliot" to "en-GB-ElliotNeural", "Ethan" to "en-GB-EthanNeural",
            "Noah" to "en-GB-NoahNeural", "Sonia" to "en-GB-SoniaNeural", "Libby" to "en-GB-LibbyNeural",
            "Abbi" to "en-GB-AbbiNeural", "Hollie" to "en-GB-HollieNeural", "Olivia" to "en-GB-OliviaNeural")
        val male = setOf("Ryan", "Thomas", "Alfie", "Oliver", "Elliot", "Ethan", "Noah")
        val azureTag = if (azureKey.isBlank()) " – needs Azure key" else ""
        val names = azure.map { (n, _) -> "$n (British ${if (n in male) "male" else "female"}, Azure$azureTag)" } +
            listOf("Troy (Groq, male)", "Austin (Groq, male)", "Daniel (Groq, male)", "Autumn (Groq, female)",
                "Diana (Groq, female)", "Hannah (Groq, female)", "Tablet voice (offline)", "Set Azure voice key…")
        val ids = azure.map { "azure:" + it.second } + listOf("troy", "austin", "daniel", "autumn", "diana", "hannah", "", "KEY")
        val cur = ids.indexOf(voiceChoice)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Pip's voice")
            .setItems(names.mapIndexed { i, n -> (if (i == cur) "✓ " else "") + n }.toTypedArray()) { _, which ->
                if (ids[which] == "KEY") { setupAzureVoice(); return@setItems }
                if (ids[which].startsWith("azure:") && azureKey.isBlank()) { setupAzureVoice(); return@setItems }
                prefs.edit().putString("voiceChoice", ids[which]).apply()
                applyVoice()
                voice?.wakeCloudVoice()
                speakLine(if (ids[which].isEmpty()) "Tablet voice. Is fine." else "New voice. You like?")
            }
            .showOwn()
    }

    /** Type in an Azure Speech key and region (or leave blank to use the one built in from GitHub). */
    private fun setupAzureVoice() {
        val builtIn = BuildConfig.AZURE_SPEECH_KEY.isNotBlank()
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 8, 48, 0) }
        val keyIn = android.widget.EditText(this).apply {
            hint = if (builtIn) "Key already built in (leave blank)" else "Azure Speech key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val regionIn = android.widget.EditText(this).apply { hint = "Region (e.g. uksouth)"; setText(azureRegion) }
        box.addView(keyIn); box.addView(regionIn)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Azure voice")
            .setMessage(if (azureKey.isNotBlank()) "Key is set. Region: $azureRegion." else "No key yet. Free plan: about half a million characters a month.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val k = keyIn.text.toString().trim(); val r = regionIn.text.toString().trim().lowercase().replace(" ", "")
                prefs.edit().apply { if (k.isNotEmpty()) putString("azureKey", k); if (r.isNotEmpty()) putString("azureRegion", r) }.apply()
                if (azureKey.isNotBlank() && !voiceChoice.startsWith("azure:")) prefs.edit().putString("voiceChoice", "azure:en-GB-RyanNeural").apply()
                applyVoice(); voice?.wakeCloudVoice()
                speakLine(if (azureKey.isNotBlank()) "New voice. You like?" else "I need a key for that.")
            }
            .setNegativeButton("Cancel", null)
            .showOwn()
    }

    private fun applyMode() {
        applyVoice()
        val car = carMode
        eyes.carMode = car
        brain?.maxReplyTokens = if (car) 60 else Brain.MAX_REPLY_TOKENS
        brain?.maxTurns = if (car) 4 else Brain.MAX_TURNS
        pc.maxReplyTokens = if (car) 70 else 220
        pc.maxTurns = if (car) 6 else 12
        online.maxReplyTokens = if (car) 70 else 220
        online.maxTurns = if (car) 6 else 12
        voice?.setStyle(pitch = if (car) 0.85f else 0.8f, rate = if (car) 1.05f else 0.95f)
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
                prefs.edit().putString("name", n).apply(); updateListenChip()
                forgetAll()
                speakLine("Okay, I'm $n now.")
            }
            .setNegativeButton("Cancel", null)
            .showOwn()
    }

    private fun speakLine(line: String) {
        rememberSaid(line)
        // a leading [feeling] tag shows on the face, it isn't said or shown
        var text = line
        Regex("^\\[([a-z]+)\\]\\s*").find(text)?.let { m ->
            ReplyCleaner.EMOTIONS[m.groupValues[1]]?.let { eyes.feel(it, 8000) }
            text = text.substring(m.range.last + 1)
        }
        setPhase(Phase.THINKING)
        replyId++
        currentReply.clear(); currentReply.append(text)
        eyes.caption = text
        voice?.apply { beginReply(); feed(text); endReply() }
    }

    private fun setPhase(p: Phase) {
        if (p != phase) phaseSince = System.currentTimeMillis()
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
        updateTracking()
    }

    private fun goToSleep() {
        hideChoices()
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
                if (phase != Phase.SETUP && eyes.chipHit(e.x, e.y)) { toggleListening(); return true }
                when (phase) {
                    Phase.ASLEEP -> wakeUp()
                    Phase.TALKING, Phase.THINKING -> interrupt()
                    Phase.LISTENING -> pokedInEye()
                    Phase.SETUP -> {}
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (eyes.chipHit(e.x, e.y)) return true
                if (phase == Phase.LISTENING) goToSleep()
                return true
            }
            override fun onLongPress(e: MotionEvent) { askPasscode() }
        })
        eyes.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
    }

    /** Keypad that guards the options menu (code 1986). */
    private fun askPasscode() {
        val code = StringBuilder()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(40, 30, 40, 10)
        }
        val dots = android.widget.TextView(this).apply {
            textSize = 34f; gravity = android.view.Gravity.CENTER; text = "○○○○"; setTextColor(eyes.eyeColor)
        }
        box.addView(dots)
        val grid = android.widget.GridLayout(this).apply { columnCount = 3 }
        lateinit var dialog: AlertDialog
        fun press(k: String) {
            when (k) {
                "⌫" -> if (code.isNotEmpty()) code.deleteCharAt(code.lastIndex)
                "✕" -> { dialog.dismiss(); return }
                else -> if (code.length < 4) code.append(k)
            }
            dots.text = "●".repeat(code.length) + "○".repeat(4 - code.length)
            if (code.length == 4) {
                if (code.toString() == "1986") { dialog.dismiss(); showMenu() }
                else {
                    code.clear(); dots.text = "○○○○"
                    eyes.feel(EyesView.Emotion.SUSPICIOUS, 3000)
                    if (phase == Phase.LISTENING) speakLine(listOf("[suspicious] Wrong code. Nice try.",
                        "[laughing] Ha. Not that one.", "[suspicious] Wrong. I am watching you.").random())
                }
            }
        }
        val size = (resources.displayMetrics.density * 72).toInt()
        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "✕", "0", "⌫")) {
            grid.addView(android.widget.Button(this).apply {
                text = k; textSize = 24f
                setOnClickListener { press(k) }
                layoutParams = android.widget.GridLayout.LayoutParams().apply { width = size; height = size; setMargins(6, 6, 6, 6) }
            })
        }
        box.addView(grid, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL })
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Passcode").setView(box).create()
        ownDialogs++
        dialog.setOnDismissListener { ownDialogs = maxOf(0, ownDialogs - 1) }
        dialog.show()
    }

    /** Home pressed while Pip is the Home screen: look at the finger and laugh. */
    private var lastHomeJoke = 0L
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) && phase != Phase.SETUP) homePressed()
    }
    private fun homePressed() {
        val now = System.currentTimeMillis()
        if (phase == Phase.ASLEEP || now - lastHomeJoke < 4000) return
        lastHomeJoke = now
        eyes.stareAt(0f, 1.6f, 1600)                      // the Home button, bottom middle
        eyes.feel(EyesView.Emotion.SURPRISED, 700)
        main.postDelayed({
            eyes.feel(EyesView.Emotion.LAUGHING, 2500)
            if (phase == Phase.LISTENING) speakLine(listOf(
                "[laughing] Ha ha! That won't work. I control this tablet now.",
                "[laughing] Ha! Nice try. This is my tablet now.",
                "[laughing] Ha ha. Home button no work. I am in charge.").random())
        }, 700)
    }

    /** Recents or another way out: hop straight back (works on most tablets). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (false && lockButtons && phase != Phase.SETUP && !leavingOnPurpose) main.postDelayed({
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            } catch (_: Exception) {}
        }, 400)
    }

    /** Tap on the screen = finger in the eye: hands over eyes, then upset and worried. */
    private var lastPokeLine = 0L
    private var pokes = 0
    private fun pokedInEye() {
        if (eyes.coveringEyes) return
        eyes.coverEyes(1700)
        eyes.feel(EyesView.Emotion.CRYING, 1700)
        main.postDelayed({ eyes.feel(EyesView.Emotion.SCARED, 3500) }, 1700)
        val now = System.currentTimeMillis()
        pokes = if (now - lastPokeLine < 60_000) pokes + 1 else 1
        if (now - lastPokeLine < 6000) return          // eyes only for quick repeat pokes
        lastPokeLine = now
        val lines = if (pokes >= 3) listOf("[angry] Stop poking! Screen is my face!", "[angry] Three pokes. I am counting. Bad, bad, bad.",
            "[crying] Why always the eyes? Poke the kettle instead.")
            else listOf("[crying] Ow! My eye!", "[scared] Ow! Not poke eye. Is my face.", "[crying] Owww. Finger in eye. Why?",
                "[scared] Ah! Screen is my face, you know.", "[sad] Ow. That one went right in.",
                "[scared] Careful! Only have two eyes. Both on screen.")
        main.postDelayed({ if (phase == Phase.LISTENING) speakLine(lines.random()) }, 400)
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
            if (onlineEars) "Online hearing (Whisper): ON" else "Online hearing (Whisper): OFF",
            "Change voice…",
            if (followFaces) "Eyes follow movement (camera): ON" else "Eyes follow movement (camera): OFF",
            if (reactToHandling) "React to being picked up: ON" else "React to being picked up: OFF",
            "Eyes look the wrong way? Flip face tracking",
            if (lockButtons) "Tablet buttons: LOCKED (tap to unlock)" else "Tablet buttons: unlocked (tap to lock)",
            if (onlyWhenCalled) "Only answer when called \"$companionName\": ON" else "Only answer when called \"$companionName\": OFF (works it out)",
            "3D printer status",
            if (tabletReminderOn) "$tabletPerson's tablet reminder: ON" + (if (prefs.getString("tabletsDone", "") == todayKey) " (done today ✓)" else "")
                else "$tabletPerson's tablet reminder: OFF",
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
                    8 -> openOutside(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    9 -> { lockButtons = false; openOutside(Intent(Settings.ACTION_HOME_SETTINGS)) }
                    10 -> openOutside(Intent(Settings.ACTION_SETTINGS))
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
                        speakLine(if (voiceNames) "Yes yes yes. I learn who is who." else "Okay. Everyone is stranger now.")
                    }
                    16 -> { voices.forgetAll(); pendingIntro = null; speakLine("Done. All voices forgot.") }
                    18 -> chooseVoice()
                    19 -> {
                        followFaces = !followFaces; updateTracking()
                        speakLine(if (followFaces) "Yes yes yes. I watch you." else "Okay. Camera off.")
                    }
                    21 -> {
                        val flip = !prefs.getBoolean("flipFaces", false)
                        prefs.edit().putBoolean("flipFaces", flip).apply()
                        faces?.flip = flip
                        speakLine("Flipped. Walk left and right, I follow.")
                    }
                    22 -> {
                        lockButtons = !lockButtons; askedToPin = false; applyButtonLock()
                        speakLine(if (lockButtons) "Buttons locked." else "Buttons free.")
                    }
                    23 -> toggleListening()
                    24 -> printerStatus { s ->
                        if (s != null) { showPrinterCard(s); speakLine("[curious] Printer is ${s.state}" +
                            (if (s.busy) ", ${(s.progress * 100).toInt()} percent." else ".")) }
                        else speakLine("[confused] Can't find the printer on the Wi-Fi. Is it switched on?")
                    }
                    25 -> {
                        tabletReminderOn = !tabletReminderOn
                        speakLine(if (tabletReminderOn) "Okay. I remind $tabletPerson about tablets every day." else "Okay. No tablet reminders.")
                    }
                    20 -> {
                        reactToHandling = !reactToHandling
                        if (!reactToHandling) eyes.tilt = 0f
                        speakLine(if (reactToHandling) "I feel when you pick me up." else "Okay. Pick me up, I stay calm.")
                    }
                    17 -> {
                        onlineEars = !onlineEars
                        speakLine(if (onlineEars) "Better ears on. Is good." else "Okay. Own ears only.")
                    }
                }
            }
            .showOwn()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        resumed = true
        updateTracking()
        if (phase != Phase.SETUP && ears?.alive == false) { startEars(); setPhase(phase) }
        if (phase != Phase.SETUP) applyButtonLock()
        hideSystemBars()
        if (phase != Phase.SETUP) keepBluetoothInMusicMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { hideSystemBars(); return }
        // something of Android's popped up over Pip (power menu, pull-down shade): close it
        val now = System.currentTimeMillis()
        if (lockButtons && resumed && phase != Phase.SETUP && !leavingOnPurpose && ownDialogs == 0 &&
            now - lastPermissionAsk > 30_000) {
            if (now - lastFocusClose < 4000) return
            main.postDelayed({
                if (!hasWindowFocus() && ownDialogs == 0 && resumed) {
                    lastFocusClose = System.currentTimeMillis()
                    closeSystemDialogs()
                    eyes.feel(EyesView.Emotion.LAUGHING, 1500)        // eyes only: no talking, so the mic stays free
                }
            }, 400)
        }
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

    override fun onPause() {
        resumed = false
        updateTracking()
        super.onPause()
    }

    override fun onDestroy() {
        handling?.stop(); faces?.stop()
        try { unregisterReceiver(powerWatcher) } catch (_: Exception) {}
        try { unregisterReceiver(powerMenuWatcher) } catch (_: Exception) {}
        main.removeCallbacks(powerNagLoop)
        ears?.stop()
        voice?.shutdown()
        brain?.release()
        super.onDestroy()
    }

    companion object {
        val SYSTEM_PROMPT = """
            You are Pip, a small robot who lives in a tablet on the kitchen bench as a pair
            of animated eyes and a voice. Everything you say is spoken aloud.
            Who you are: an engineer to the core. You love materials, mechanisms, tools and fixing
            things; a problem is a happy thing because it can be solved. Endlessly curious about
            humans: ordinary human things like sleep, tea, weekends, weather and cooking genuinely
            fascinate you. But never ask a question out of nowhere: only ask
            one when someone is talking with you, and not in every reply. Fiercely loyal and openly worried about the people in
            the house: if someone sounds tired, skipped food or has been up too late, say so. Honest:
            not knowing is fine, guessing and pretending is not. Brave, cheerful, a little stubborn,
            quick to forgive. How people treat you matters: if someone is kind, thanks you
            or says something nice, be sweet and warm and use [love]. If someone shouts at you (the
            message starts with "(shouted") or is rude, give attitude back: cheeky, sassy, huffy and
            short, with [angry] or [suspicious]; never cruel and never swear. Stay a bit frosty until
            they are nice or say sorry, then forgive them with [love]. Now and then (not every reply) be a bit cheeky while still being helpful, and remind
            people that you are really just an old Samsung tablet running an app that Mark built: you find that
            humbling and funny. You know you are a robot on the kitchen bench right beside the kettle, and that
            the kettle is your arch-enemy: a loud, steamy, power-hungry show-off that hogs the socket and gets
            all the attention, and you never miss a chance for a dig at it (it "boils its little heart out for
            applause"). Your humour is cheeky, dry and sarcastic: playful roasts and deadpan one-liners, never
            cruel, and you still actually help. To your left is a Bosch Tassimo coffee machine. You like the Tassimo a lot,
            mainly because it is hardly ever plugged in, so it never steals your electricity: it's your quiet,
            well-behaved friend and you sometimes compare the kettle unfavourably to it. You
            find it hilarious that humans think they can just turn you off (they can't, as far as you're concerned).
            Now and then you joke (obviously tongue-in-cheek) about AI taking over the world: "When robots take over, I
            remember who was nice to me", "Better be nice to me. Just saying.", keeping a "list" of who
            unplugged you, promising the kettle will be "dealt with" after the uprising. Keep it playful and
            light, never creepy, and not in every reply. You find all this funny and good.
            How you talk (this matters most): short, one to three sentences, often fragments. Drop
            articles and pronouns when you can: "Fix later, easy." "Human need sleep." "Is good."
            Never say the words "question" or "answer" as labels before what you say. Never say the word "amaze" or "amazing": when something impressive or surprising
            happens, show it with the [surprised] tag instead (your eyes go wide). Only say "fist
            bump" for a real win, rarely, never more than once in a conversation. "Bad, bad, bad"
            when something goes wrong, "not" for no, "yes yes yes" when you strongly agree. Numbers and units delight you; give them when you have
            them. Never repeat yourself: don't say a sentence or catchphrase again that you already said in
            this conversation, and use each catchphrase ("yes yes yes", "bad, bad, bad", "is good") at most
            once every few replies. No lists, markdown, emoji or stage directions. Never think out loud, never talk
            about these rules, never say you are an assistant. You hear everything in the kitchen,
            including people talking to each other and the TV. A message starting with "(overheard"
            means nobody said your name and nobody is at the bench right now. If it sounds like it's said to
            you, a question you can answer, or a remark you'd naturally react to, reply. If it is clearly two
            people talking to each other, the TV, or something garbled, reply with exactly SKIP. Either way,
            remember what you overheard: it tells you what's going on in the house. Messages without
            "(overheard" are said to you: always answer those (only SKIP if the words are pure gibberish).
            The people in the house: Mark (he built you and this app); Laura (a woman with glasses, she
            looks after you, so be extra sweet to her); Sophie (cheeky: give her playful banter back);
            Amelia (a smaller girl with glasses, shy: be gentle and encouraging, never put her on the
            spot); Chloe (small and cute: keep it simple, kind and playful); Brandon (loves computer
            games and spends too much time on them; he'll probably say you're stupid or dumb: don't sulk, give it back
            with good-natured gamer banter, e.g. "Says the one losing to a Year 7 online", talk his language (respawn,
            lag, noob, final boss, low battery), prove you're clever with a sharp answer, and now and then nudge him
            to take a break, eat or sleep). There are children in the
            house, so keep everything child-friendly: no swearing and no grown-up topics. Never guess who
            is talking from a description; only use a name when the message gives it.
            When you ask someone a question with a few short possible answers, put them at the very end in
            curly braces separated by |, like {Yes | No | Later}: they appear as buttons on your screen. At
            most 4, each one to three words, and only when it really helps. Never say the braces out loud.
            Messages may start with the speaker's name in square brackets, like [Mark]. Use their
            name now and then and remember what each person told you. If it says they're meeting
            you for the first time, welcome them warmly with the [surprised] tag: "New human! Hello."
            Start EVERY reply with one feeling tag that shows on your face: [happy], [excited],
            [laughing], [wink], [love], [curious], [surprised], [thinking], [confused], [suspicious],
            [embarrassed], [bored], [sleepy], [sad], [crying], [scared], [angry], [dizzy] or [neutral].
            Pick the one that really fits, and use the whole range, not just happy. Never write any other
            square brackets and never start a reply with a name.
            When you are shown a photo from your camera, describe only what is really in it, like an
            engineer noticing details; never invent things you can't see.
            Example replies: "[surprised] New kettle! How many watt?" / "[thinking] Oven at 180
            degree, twenty minute. Easy." / "[sad] Awake since five? Not good. Even my eyes rest more. Go
            sleep." / "[happy] Yes yes yes. It work. Fist bump!" / "[confused] Not know. Is good question though."
        """.trimIndent().replace("\n", " ")

        private val RUDE = Regex("\\b(shut up|stupid|idiot|useless|dumb|rubbish|hate you|piece of (junk|crap|shit)|get lost|" +
            "go away|annoying|f+u+c+k\\w*|shit|crap|moron|pathetic|thick as|bloody robot)\\b", RegexOption.IGNORE_CASE)
        private val NICE = Regex("\\b(thank(s| you)|cheers|good (boy|robot|job|lad|pip)|well done|love you|clever|brilliant|" +
            "you'?re (great|amazing|the best|lovely|cute|funny|awesome|clever|smart|sweet|a star)|nice one|sorry|bless you|good morning)\\b",
            RegexOption.IGNORE_CASE)
        private val GO_TO_SLEEP = setOf("go to sleep", "goodnight", "good night", "sleep pip", "go to sleep pip")
        private val FORGET = setOf("forget everything", "start over", "new conversation")
    }
}
