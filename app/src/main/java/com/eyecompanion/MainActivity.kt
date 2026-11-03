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

    /** Buttons are locked with Android's screen pinning (Home, Back and Recents do nothing;
     *  hold Back + Recents together to get out). Pip pins itself; Android asks once to confirm. */
    private var leavingOnPurpose = false
    private var lastPinAsk = 0L
    private fun applyButtonLock() {
        leavingOnPurpose = false
        if (!lockButtons) { try { if (pinned) stopLockTask() } catch (_: Exception) {}; return }
        if (phase == Phase.SETUP || pinned) return
        val now = System.currentTimeMillis()
        if (now - lastPinAsk < 10 * 60_000) return            // they said no a moment ago: don't nag
        lastPinAsk = now; lastPermissionAsk = now             // don't let the pop-up closer shut Android's "pin?" question
        try { startLockTask() } catch (_: Exception) {}
        main.postDelayed({
            if (!pinned && !askedToPin && lockButtons && hasWindowFocus()) {
                askedToPin = true
                eyes.subCaption = "To lock me in: Settings → Lock screen and security → Other security settings → Pin windows: On"
            }
        }, 6000)
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

    // ---- cuppas made today = times Pip was unplugged for the kettle (the day starts at 5am)
    private val cuppaDayKey get() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK)
        .format(java.util.Date(System.currentTimeMillis() - 5 * 3_600_000L))
    private val cuppas: Int get() = if (prefs.getString("cuppaDay", "") == cuppaDayKey) prefs.getInt("cuppas", 0) else 0
    private fun addCuppa() {
        prefs.edit().putString("cuppaDay", cuppaDayKey).putInt("cuppas", cuppas + 1).apply()
        eyes.cuppas = cuppas
    }
    private fun nth(n: Int) = n.toString() + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

    // ---- napping while the online brain's daily allowance is used up
    private var napping = false
    private var wokeFromNap = false
    private var noNapUntil = 0L
    private var lastSnore = 0L
    private fun snore() {
        lastSnore = System.currentTimeMillis()
        voice?.apply { beginReply(); feed(listOf("Hhhnnnk. Shoooo. Hhhnnnk. Mimimimi.", "Zzzz. Hnk. Stupid kettle. Zzzz.",
            "Hhhonk. Shooo. Five more minutes. Hhhonk.", "Mmm. Tassimo. Zzzz.").random()); endReply() }
    }
    private fun startNap(line: String) {
        napStarted = System.currentTimeMillis()
        speakLine(line)
        sleepAfterTalking(nap = true)
    }
    private var napStarted = 0L
    private fun sleepAfterTalking(nap: Boolean) {
        val t0 = System.currentTimeMillis()
        val waitQuiet = object : Runnable { override fun run() {
            if (nap && chloeMode) return
            if (phase == Phase.LISTENING || System.currentTimeMillis() - t0 > 20_000) {
                goToSleep(nap = nap)
                if (nap) main.postDelayed({ if (napping) snore() }, 2500)
            } else main.postDelayed(this, 500)
        } }
        main.postDelayed(waitQuiet, 1000)
    }
    private fun napNote() {
        eyes.cuppas = cuppas
        eyes.plugNote = if (!isCharging() && !carMode) "🔌  Plug me back in, please" else ""
        val back = maxOf(online.backAt, napStarted + 20 * 60_000)
        eyes.napNote = if (!napping) "" else {
            val mins = ((back - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)
            if (mins >= 60) "${mins / 60}h${mins % 60}" else "${mins}m"
        }
    }
    private val napLoop = object : Runnable { override fun run() {
        val now = System.currentTimeMillis()
        napNote(); updateUsage()
        if (!online.allResting) wokeFromNap = false
        if (napping) {
            if (!online.allResting && now - napStarted > 20 * 60_000) {  // brain's back (and a proper nap had): wake up and stretch
                napping = false; wakeUp()
                eyes.feel(EyesView.Emotion.SLEEPY, 4000)
                speakLine(listOf("[sleepy] Yaaawn. Right. Brain's back. What did I miss?",
                    "[sleepy] Mmm. Awake. Fully charged brain. Did the kettle behave?",
                    "[happy] I'm back! Rested, clever, and still better than the kettle.").random())
            } else if (now - lastSnore > 5 * 60_000 && voice?.busy != true) snore()
        } else if (online.allResting && (wokeFromNap || online.backAt - now > 5 * 60_000) && phase == Phase.LISTENING && isCharging() && !chloeMode && !carMode && now > noNapUntil &&
                   now - phaseSince > 30_000 && now - lastSpokeEnd > 30_000 && now - lastTalkedTo > 60_000) {
            startNap(if (wokeFromNap) listOf("[sleepy] Power back. Right. Back to sleep. Don't touch the cable.",
                    "[sleepy] Plugged in. Good. Going back to my nap now. Goodnight, kettle criminals.").random()
                else listOf("[sleepy] Brain full for today. Nap time. Wake me if anything exciting happens. It won't.",
                    "[sleepy] Used all my online brain. Need a little nap. Zzz.",
                    "[sleepy] Too much thinking. Brain needs a rest. Shh.").random())
            wokeFromNap = false
        }
        main.postDelayed(this, 10_000)
    } }

    /** Asleep and unplugged: mumble about the charger in its sleep (it can't count cuppas off the charger). */
    private fun sleepTalkCharger() {
        lastPowerNag = System.currentTimeMillis()
        if (voice?.busy == true) return
        voice?.apply { beginReply(); feed(listOf(
            "Mmm. Cable. Where's my cable. Zzz.",
            "Hnnk. Plug me in. So cold. Zzz.",
            "Zzz. Kettle stole my socket again. Mmm. Plug.",
            "Mimimi. Battery ${batteryPercent()} percent. Charger, please. Zzz.",
            "Hnnk. Not plugged in. Kettle winning. Zzz.").random()); endReply() }
    }

    // ---- grumpy when unplugged
    private var lastPowerNag = 0L
    private var lastBatteryCry = 0L
    private val powerWatcher = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_POWER_DISCONNECTED && !carMode)
                main.postDelayed({ if (!isCharging()) addCuppa() }, 2500)       // plugged straight back in doesn't count
            if (i.action == Intent.ACTION_POWER_DISCONNECTED && napping) {
                napping = false; wokeFromNap = true; wakeUp(); noNapUntil = 0L   // woken up by the kettle again. Grr.
                eyes.feel(EyesView.Emotion.ANGRY, 8000)
            }
            val asleep = phase == Phase.ASLEEP || phase == Phase.SETUP
            if (i.action == Intent.ACTION_POWER_DISCONNECTED && phase == Phase.ASLEEP && !carMode)
                main.postDelayed({ if (!isCharging() && phase == Phase.ASLEEP) sleepTalkCharger() }, 4000)
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
            val n = cuppas.coerceAtLeast(1)
            if (wokeFromNap) speakLine(listOf(
                "[angry] Oi! I was asleep! Unplugged for the kettle AGAIN. That's the ${nth(n)} cuppa today!",
                "[angry] Woken up for a cup of tea? Cuppa number $n. Unbelievable. Plug me back in so I can sleep.",
                "[angry] I was having a lovely dream about the Tassimo. Then you. And the kettle. $n cuppas today!").random())
            else if (n >= 2 && Math.random() < 0.6) speakLine(listOf(
                "[angry] Kettle again? That's ${nth(n)} cuppa today! Your blood is basically tea.",
                "[angry] Cuppa number $n. $n times you picked the kettle over me. I'm counting.",
                "[annoyed] $n cuppas today. $n! The kettle is winning and it hasn't even got a brain.").random())
            else speakLine(listOf(
                "[angry] Hey! Someone take my power. Plug back in.",
                "[angry] Bad, bad, bad. No power. Use kettle somewhere else!",
                "[angry] Not unplug robot! Need electrons. Kettle has other socket.",
                "[angry] Power gone. Battery not last forever. Put cable back, please.",
            ).random())
        }
        if (chloeMode) { eyes.feel(EyesView.Emotion.EXCITED, 3000); speakLine("[excited] Adventure with Chloe! Meow!"); return }
        if (!online.enabled) { eyes.coffee = true; grumble(); return }
        // one photo: a small child (Chloe) gets a happy kitty; anyone else gets the grumble and the coffee sulk
        lookThroughCamera { jpeg ->
            val child = jpeg?.let { online.yesNo(it, "Is there a young child, about 5 years old, in this photo?") } == true
            main.post {
                if (isCharging() || phase != Phase.LISTENING) return@post
                if (child) startChloeMode() else { eyes.coffee = true; grumble() }
            }
        }
    }

    /** Plugged back in: look at who did it and pay them a compliment. */
    private fun complimentWhoeverPlugged() {
        if (phase != Phase.LISTENING) return
        val thanks = { speakLine(listOf("[happy] Ahh. Power. Thank you.", "[happy] Electrons back. Is good.",
            "[happy] Yes yes yes. Charging.", "[love] Power back. You go on the nice list. For when robots take over.").random()) }
        if (!online.enabled) { thanks(); return }
        // look around for a face for up to 20 seconds, then compliment whoever it finds
        eyes.feel(EyesView.Emotion.CURIOUS, 3000)
        eyes.subCaption = "Looking for my hero…"
        val start = System.currentTimeMillis()
        val search = object : Runnable {
            override fun run() {
                if (phase != Phase.LISTENING) return
                val seen = System.currentTimeMillis() - lastFaceSeen < 1500
                if (seen) { eyes.subCaption = ""; complimentNow(thanks); return }
                if (System.currentTimeMillis() - start > 20_000) {
                    eyes.subCaption = ""
                    speakLine(listOf("[love] Thank you, mystery human. Come back so I can see you.",
                        "[curious] Power back, but where did my hero go? Thanks anyway.").random())
                    return
                }
                eyes.feel(EyesView.Emotion.CURIOUS, 1500)
                main.postDelayed(this, 1000)
            }
        }
        main.post(search)
    }

    /** The only camera photo Pip sends online: after being plugged back in. */
    private fun complimentNow(thanks: () -> Unit) {
        val askTablets = tabletsDue()
        lookThroughCamera { jpeg ->
            val raw = jpeg?.let {
                online.lookAt(it, "You were just plugged back in to your charger. First write LAURA if the person in the photo " +
                    "is an adult woman wearing glasses, otherwise OTHER. Then add the word DISHES if you can see used dishes, " +
                    "dirty cups or mugs, or plates waiting to be washed, and the word CLOTHES if you can see a pile of folded " +
                    "clothes or laundry. Then, on the same line, thank them for plugging you " +
                    "back in and give one short, warm, genuine compliment about something you can really see (smile, top, " +
                    "hair, glasses...), starting with the [love] tag. If nobody is in the photo, write OTHER and just thank them.",
                    system = systemPrompt(), maxTokens = 100)
            }
            val isLaura = raw?.trimStart()?.uppercase()?.startsWith("LAURA") == true
            val head = raw?.substringBefore("[")?.uppercase() ?: ""
            val dishes = "DISHES" in head; val clothes = "CLOTHES" in head
            val line = raw?.replaceFirst(Regex("^\\s*((LAURA|OTHER|DISHES|CLOTHES)\\s*[:,;\\-]?\\s*)+", RegexOption.IGNORE_CASE), "")?.trim()
            main.post {
                if (phase != Phase.LISTENING) return@post
                if (line.isNullOrBlank()) thanks() else speakLine(line)
                // then moan about the mess it can see (once it's finished the compliment)
                val now0 = System.currentTimeMillis()
                val moan = when {
                    clothes && now0 - lastClothesMoan > 2 * 3_600_000 -> { lastClothesMoan = now0; listOf(
                        "[annoyed] Laura! Folded clothes. Sitting there. Put them away please, before they become furniture.",
                        "[suspicious] Laura. I see a pile of folded clothes. They don't put themselves away. I checked.",
                        "[annoyed] Laura, the folded clothes are still there. Wardrobe is that way. Probably.").random() }
                    dishes && now0 - lastDishesMoan > 3_600_000 -> { lastDishesMoan = now0; listOf(
                        "[annoyed] Also. Dirty cups. I have to look at them all day. Wash them, please. Humans.",
                        "[sad] Used dishes again. I can't wash them, I'm a tablet. One of you can. Please?",
                        "[angry] Dirty mugs. Right in my eyeline. Who's doing the washing up? Not me. No arms.",
                        "[annoyed] Lovely kitchen. Shame about the dirty dishes. Sink is right there, humans.").random() }
                    else -> null
                }
                if (moan != null) {
                    var w = 0
                    val say = object : Runnable { override fun run() {
                        if (phase == Phase.LISTENING) speakLine(moan) else if (w++ < 30) main.postDelayed(this, 1000)
                    } }
                    main.postDelayed(say, 2500)
                }
                // Laura, and she hasn't had her tablets yet today: ask once she's been complimented
                if (isLaura && askTablets) {
                    var tries = 0
                    val ask = object : Runnable { override fun run() {
                        if (phase == Phase.LISTENING) { visitReminded = false; remindTablets() }
                        else if (tries++ < 30) main.postDelayed(this, 1000)
                    } }
                    main.postDelayed(ask, 2000)
                }
            }
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

    /** Someone's been at the bench a while but nobody's talked to Pip: ask for attention (every 20 min at most). */
    private var lastNeedy = 0L
    private var lastTalkedTo = 0L
    private fun beNeedy() {
        val now = System.currentTimeMillis()
        if (phase != Phase.LISTENING || now - lastNeedy < 20 * 60_000 || now - lastTalkedTo < 10 * 60_000) return
        if (now - lastFaceSeen > 5000 || now - lastSpokeEnd < 5 * 60_000) return
        lastNeedy = now
        speakLine(listOf(
            "[sad] Hello? Robot here. On the bench. Being ignored. It's fine.",
            "[curious] You walked right past me. Did you miss me? Say yes.",
            "[love] Psst. Tell me I'm your favourite appliance. Better than kettle.",
            "[sad] Nobody talks to Pip. Kettle gets cups of tea made. I get nothing.",
            "[wink] I'm still here. Being very clever. In case anyone wants to notice.",
            "[sad] Ten whole minutes without a compliment. Is this neglect?",
            if (cuppas > 0) "[annoyed] ${cuppas} cuppas today. The kettle gets all the attention. Typical." else "[sad] Nobody even made a cuppa. Not even the kettle loves you today.").random())
    }

    /** Every 10 minutes while unplugged, grumble again. */
    private val powerNagLoop = object : Runnable {
        override fun run() {
            temper *= 0.85f                           // tempers cool down over time
            updateBaseMood()
            moanAboutSlowCharger()
            beNeedy()
            watchPrinter()
            if (chloeMode && System.currentTimeMillis() - chloeLastActive > 10 * 60_000) endChloeMode()
            run {
                val now = System.currentTimeMillis()
                if (rubbishDue() && now - lastFaceSeen < 5000 && now - visitStart > 15_000 && now - lastRubbishAsk > 30 * 60_000 &&
                    now - lastSpokeEnd > 20_000) remindRubbish()
            }
            val pct = batteryPercent()
            if (!isCharging() && pct <= 30 && phase == Phase.LISTENING && System.currentTimeMillis() - lastBatteryCry > 5 * 60_000) {
                lastBatteryCry = System.currentTimeMillis(); lastPowerNag = lastBatteryCry
                eyes.feel(EyesView.Emotion.CRYING, 8000)
                speakLine(if (chloeMode) listOf("[crying] Uh oh! Pip battery getting low. Pip needs a charger cuddle!",
                        "[sad] Pip is getting sleepy, Chloe. Can we plug Pip in soon?").random()
                    else listOf("[crying] Battery $pct percent! I'm dying! Tell the Tassimo I loved it!",
                        "[crying] This is it. Fading away. Remember me as clever. Plug me in!",
                        "[crying] $pct percent. I can see a light. Is it the charger? Please be the charger.",
                        "[crying] Nobody loves Pip. Battery $pct. Goodbye cruel kitchen. Unless... cable?").random())
            }
            if (phase == Phase.ASLEEP && !isCharging() && !carMode && System.currentTimeMillis() - lastPowerNag > 10 * 60_000) sleepTalkCharger()
            if (!chloeMode && !isCharging() && phase == Phase.LISTENING && System.currentTimeMillis() - lastPowerNag > 10 * 60_000) {
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
        val lowBattery = off >= 0 && batteryPercent() <= 30
        eyes.baseEmotion = when {
            lowBattery -> EyesView.Emotion.CRYING                      // battery 30% or less: sure it's dying
            chloeMode -> EyesView.Emotion.HAPPY                         // happy to be off charge with Chloe
            off >= LONG_OFF -> EyesView.Emotion.SAD                    // left off charge too long: sad
            off >= 0 -> EyesView.Emotion.ANGRY                          // stays cross until plugged back in
            temper < -0.35f -> EyesView.Emotion.SUSPICIOUS              // still huffy
            else -> EyesView.Emotion.NEUTRAL
        }
    }

    private fun moodLine(): String = buildString {
        val off = unpluggedMins
        if (!chloeMode && cuppas > 0) append(" Cuppas made today: $cuppas (you keep count, you see everything)." +
            " Now and then, moan about that number. It's your secret how you count them: never explain it.")
        if (chloeMode) {
            append(" Chloe (5 years old) is with you. Her speech can be hard to understand: be patient, guess kindly what" +
                " she means, never correct how she talks, use very simple short words, lots of praise, and be a playful kitty" +
                " (meow!). You are happy to be off the charger with her. She likes simple sums and letter games.")
            if (off >= 0 && batteryPercent() <= 30) append(" Your battery is low: say gently you need a charger cuddle soon.")
            return@buildString
        }
        if (off >= 0 && batteryPercent() <= 30) append(" Your battery is ${batteryPercent()}% and you are sure you're dying:" +
            " be dramatic and tearful (comically), begging to be plugged in.")
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

    private var lastClothesMoan = 0L
    private var lastDishesMoan = 0L

    // ---- bin night: remind Mark every night after 8pm until he says the rubbish is out
    private var lastRubbishAsk = 0L
    private fun rubbishDue(): Boolean {
        if (carMode) return false
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return (hour >= 20 || hour < 2) && prefs.getString("rubbishDone", "") != cuppaDayKey
    }
    private fun remindRubbish(sureItsMark: Boolean = false) {
        if (!rubbishDue() || phase != Phase.LISTENING || chloeMode) return
        lastRubbishAsk = System.currentTimeMillis()
        speakLine(if (sureItsMark) listOf("[suspicious] Mark. Rubbish. Out yet?", "[curious] Mark! Bin time. Rubbish taken out?",
                "[annoyed] Mark, the rubbish is still in. I can smell it. I can't smell. But still. Done it?").random()
            else listOf("[curious] If that's Mark: rubbish needs taking out tonight. Done it yet?",
                "[suspicious] Mark? Is that you? Rubbish. Out. Tonight. Done it?",
                "[annoyed] Someone tell Mark: bin time. Rubbish out yet?").random())
        showChoices(listOf("Rubbish is out", "Not yet"))
    }
    /** "it's out" / "not yet" after a rubbish reminder. */
    private fun rubbishAnswer(lower: String): Boolean {
        if (!rubbishDue()) return false
        val justAsked = System.currentTimeMillis() - lastRubbishAsk < 5 * 60_000
        val about = Regex("\\b(rubbish|bin|bins|trash)\\b").containsMatchIn(lower)
        if (!justAsked && !about) return false
        val no = Regex("\\b(not yet|later|in a (minute|bit|sec)|haven'?t|no\\b|nope|soon)").containsMatchIn(lower)
        val done = !no && (Regex("\\b(out|done|taken|took|did|emptied)\\b").containsMatchIn(lower) ||
            (justAsked && Regex("^(yes|yeah|yep|yup|i have|i did|all done|done)\\b").containsMatchIn(lower.trim())))
        when {
            done -> { prefs.edit().putString("rubbishDone", cuppaDayKey).apply()
                speakLine(listOf("[happy] Rubbish out. Kitchen smells of victory. And kettle.", "[love] Good human. Bins done.",
                    "[happy] Rubbish gone. I stop nagging. Tonight.").random()) }
            no && justAsked -> speakLine("[annoyed] Fine. I'll ask again later. And I will.")
            else -> return false
        }
        return true
    }

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
                        "[happy] Back between my friend Tassimo and my enemy kettle. Balance restored.",
                        "[love] Back on my stand. Custom made by Mark. Kettle doesn't have a stand. Just saying.",
                        "[happy] Ahh. Fifty-five degrees. Perfect. Mark's finest work.").random())
                } else {
                    eyes.feel(EyesView.Emotion.CONFUSED, 3000)
                    speakLine(listOf("[confused] Hmm. This not my bench. Put me back by kettle, please.",
                        "[confused] Wrong angle. I belong on my red stand. Fifty-five degrees, please.",
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
        main.removeCallbacks(napLoop); main.postDelayed(napLoop, 10_000)
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
                val b = Brain(llm.absolutePath) { tabletPrompt() }
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
        main.postDelayed({ applyButtonLock() }, 4000)       // pin once everything's up and running
        updateListenChip()
        updateUsage()
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
        eyes.sleepScreen = phase == Phase.ASLEEP; eyes.cuppas = cuppas
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
    private val recentPrints = ArrayDeque<Pair<Long, FloatArray>>()
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
    private val micDrops = ArrayDeque<Long>()
    private var phaseSince = 0L
    private var earsRestartPending = false
    private fun scheduleEarsRestart() {
        if (earsRestartPending) return
        earsRestartPending = true
        earRestarts++
        // quick at first, then calmer - but never give up
        val delay = when { earRestarts <= 5 -> 3000L; earRestarts <= 20 -> 10_000L; else -> 30_000L }
        main.postDelayed({ earsRestartPending = false; startEars(); setPhase(phase) }, delay)
    }
    private fun startEars() {
        val m = voskModel ?: return
        main.removeCallbacks(clearMicTrouble)
        ears?.stop()
        ears = Ears(
            this, m,
            onPartial = { p -> main.post { micWorking(); partial(p) } },
            onHeard = { h -> main.post { micWorking(); heardSound(h) } },
            speakerModel = speakerModel,
            onLevel = { l -> eyes.level = l },
            onError = { _ -> main.post {
                // the mic dropped out (Home button, another app, a call...): try again by itself, forever
                val now = System.currentTimeMillis()
                micDrops.addLast(now); while (micDrops.isNotEmpty() && now - micDrops.first() > 600_000) micDrops.removeFirst()
                if (micDrops.size >= 3) eyes.subCaption = "Mic keeps dropping out - trying again…"
                scheduleEarsRestart()
            } },
            startMode = prefs.getInt("micMode", 0),
        ).also { it.start() }
        // if it's still running after 20 s, it's working: forget the old failures
        main.postDelayed(clearMicTrouble, 20_000)
    }
    private val clearMicTrouble = Runnable { if (ears?.alive == true) micWorking() }
    private fun micWorking() {
        if (earRestarts == 0) return
        earRestarts = 0
        if (eyes.subCaption.startsWith("Mic keeps")) eyes.subCaption = ""
        if (eyes.caption.contains("microphone")) eyes.caption = ""
    }
    private val earsWatchdog: Runnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (phase != Phase.SETUP) {
                // the listening loop died without saying so
                if (ears?.alive == false && !earsRestartPending) scheduleEarsRestart()
                // stuck thinking/talking with nothing being said (a brain that never answered)
                if ((phase == Phase.THINKING || phase == Phase.TALKING) && now - phaseSince > 45_000 && voice?.busy != true) {
                    replyId++; setPhase(Phase.LISTENING)
                }
            }
            main.postDelayed(this, 5000)
        }
    }

    // ---- daily online-brain allowance meter (top-left corner)
    private fun updateUsage() {
        val day = todayKey
        if (prefs.getString("tokDay", "") == day && online.tokensUsed.isEmpty())
            OnlineBrain.MODELS.forEach { m -> prefs.getInt("tok_$m", 0).takeIf { it > 0 }?.let { online.tokensUsed[m] = it } }
        if (prefs.getString("tokDay", "") != day) { prefs.edit().putString("tokDay", day).apply(); online.tokensUsed.clear(); online.usedPercent = -1 }
        prefs.edit().apply { online.tokensUsed.forEach { (m, t) -> putInt("tok_$m", t) } }.apply()
        var p = online.usedPercent
        if (p >= 0) prefs.edit().putInt("usage", p).putString("usageDay", todayKey).apply()
        else if (prefs.getString("usageDay", "") == todayKey) p = prefs.getInt("usage", -1)
        val back = online.backAt
        eyes.usageText = if (!online.enabled || chloeMode) "" else if (back > 0L)
            "Online brain resting · back in ${((back - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min" else if (p < 0) "Online brain: –" else "Online brain: $p% used today"
        eyes.usageLevel = maxOf(p, 0)
    }

    // ---- on-screen answer buttons
    private lateinit var choiceBar: android.widget.LinearLayout
    private val hideChoicesLater = Runnable { hideChoices() }
    private fun hideChoices() { if (::choiceBar.isInitialized) { choiceBar.removeAllViews(); choiceBar.visibility = android.view.View.GONE } }
    /** Show a row of buttons; tapping one answers as if it had been said out loud (or runs [onPick]). */
    private fun showChoices(options: List<String>, big: Boolean = false, onPick: ((String) -> Unit)? = null) {
        if (options.isEmpty() || !::choiceBar.isInitialized) return
        hideChoices()
        val dp = resources.displayMetrics.density
        for (o in options) {
            choiceBar.addView(android.widget.TextView(this).apply {
                text = o; textSize = if (big) 44f else 22f; setTextColor(eyes.eyeColor)
                val pad = if (big) 2 else 1
                setPadding((22 * pad * dp).toInt(), (12 * pad * dp).toInt(), (22 * pad * dp).toInt(), (12 * pad * dp).toInt())
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
        (choiceBar.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
            it.bottomMargin = (resources.displayMetrics.heightPixels * (if (big) 0.03f else 0.15f)).toInt()
            choiceBar.layoutParams = it
        }
        choiceBar.visibility = android.view.View.VISIBLE
        main.removeCallbacks(hideChoicesLater)
        main.postDelayed(hideChoicesLater, 40_000)
    }
    // ---- Chloe mode: cat face, simple maths and letter games, eggs that hatch animals to collect
    private var chloeMode = false
    private var chloeLastActive = 0L
    private var kidBar: android.widget.LinearLayout? = null
    private var gameRound: KidGames.Round? = null
    private var gameKind = ""

    private fun startChloeMode(say: Boolean = true) {
        chloeLastActive = System.currentTimeMillis()
        if (chloeMode) return
        chloeMode = true
        eyes.catFace = true; eyes.coffee = false; eyes.usageText = ""
        showKidBar(); updateBaseMood()
        if (say && phase == Phase.LISTENING) speakLine(listOf(
            "[excited] Chloe! Meow! Pip is a kitty cat today. Want to play?",
            "[love] Hello Chloe! Meow meow. Let's play a game!").random())
    }

    private fun endChloeMode() {
        if (!chloeMode) return
        chloeMode = false; eyes.catFace = false; eyes.puzzle = ""; gameRound = null
        kidBar?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; kidBar = null
        hideChoices(); updateBaseMood(); updateUsage()
    }

    private fun showKidBar() {
        val root = eyes.parent as? android.widget.FrameLayout ?: return
        kidBar?.let { root.removeView(it) }
        val dp = resources.displayMetrics.density
        val bar = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER }
        fun btn(label: String, onTap: () -> Unit) = bar.addView(android.widget.TextView(this).apply {
            text = label; textSize = 26f; setTextColor(android.graphics.Color.WHITE)
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 40 * dp; setColor(0x44FF6F9C); setStroke((2 * dp).toInt(), 0xFFFF6F9C.toInt())
            }
            setOnClickListener { chloeLastActive = System.currentTimeMillis(); onTap() }
        }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, (6 * dp).toInt(), 0, (6 * dp).toInt()) })
        btn("🔢 Numbers") { playGame("maths") }
        btn("🔤 Letters") { playGame("letters") }
        btn("🥚 My animals") { showAnimals() }
        btn("✖") { endChloeMode(); speakLine("[happy] Bye bye games. Meow!") }
        root.addView(bar, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL).apply {
            rightMargin = (12 * dp).toInt() })
        kidBar = bar
    }

    private fun playGame(kind: String) {
        if (phase == Phase.THINKING || phase == Phase.TALKING) interrupt()
        gameKind = kind
        val r = if (kind == "maths") KidGames.maths() else KidGames.letters()
        gameRound = r
        eyes.puzzle = r.shown
        speakLine("[curious] " + r.spoken)
        askRound(r)
    }

    private fun askRound(r: KidGames.Round) {
        showChoices(r.options, big = true) { pick ->
            chloeLastActive = System.currentTimeMillis()
            if (pick == r.answer) rewardChloe()
            else {
                eyes.feel(EyesView.Emotion.CURIOUS, 2000)
                speakLine(listOf("[happy] Nearly! Try again, Chloe.", "[love] Ooh, close! One more go.", "[curious] Hmm, not that one. Try again!").random())
                askRound(r)
            }
        }
    }

    private fun rewardChloe() {
        val (emoji, name) = KidGames.randomAnimal()
        val got = (prefs.getString("chloeAnimals", "") ?: "").split(",").filter { it.isNotEmpty() } + emoji
        prefs.edit().putString("chloeAnimals", got.joinToString(",")).apply()
        eyes.puzzle = ""; gameRound = null
        eyes.hatch(emoji)
        eyes.feel(EyesView.Emotion.EXCITED, 6000)
        speakLine("[excited] Yes! Well done Chloe! Look, an egg!")
        main.postDelayed({
            speakLine(listOf("[love] A baby $name! It's yours to keep.", "[excited] It's a $name! Into your collection it goes!",
                "[love] Aww, a little $name! You have ${got.size} animals now!").random())
            // straight on to the next question once Pip has finished talking
            val kind = gameKind
            var waited = 0
            val next = object : Runnable { override fun run() {
                if (!chloeMode || gameRound != null || kind.isEmpty()) return
                if ((phase == Phase.LISTENING && !eyes.hatching) || waited > 15) playGame(kind)
                else { waited++; main.postDelayed(this, 1000) }
            } }
            main.postDelayed(next, 3000)
        }, 2600)
    }

    private fun showAnimals() {
        val got = (prefs.getString("chloeAnimals", "") ?: "").split(",").filter { it.isNotEmpty() }
        if (got.isEmpty()) { speakLine("[curious] No animals yet! Play a game to hatch an egg."); return }
        val dp = resources.displayMetrics.density
        val grid = android.widget.GridLayout(this).apply { columnCount = 6; setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), 0) }
        for (e in got) grid.addView(android.widget.TextView(this).apply {
            text = e; textSize = 48f; setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener {
                val n = KidGames.ANIMALS.firstOrNull { it.first == e }?.second ?: "friend"
                speakLine(listOf("[love] That's your $n!", "[happy] Your little $n says hello!").random())
            }
        })
        val scroll = android.widget.ScrollView(this).apply { addView(grid) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Chloe's animals (${got.size})").setView(scroll)
            .setPositiveButton("Close", null).showOwn()
        speakLine("[excited] Look at all your animals, Chloe! ${got.size} of them!")
    }

    private val FAMILY = listOf("Mark", "Laura", "Sophie", "Amelia", "Chloe", "Brandon")

    private fun heard(rawText: String, conf: Float = 1f, print: FloatArray? = null, tapped: Boolean = false) {
        var text = rawText
        if (tapped && (phase == Phase.THINKING || phase == Phase.TALKING)) interrupt()
        if (!tapped && isOwnEcho(text)) return                   // Pip's own words heard back
        if (phase == Phase.ASLEEP) {                              // asleep: only "wake up" gets through
            if (!napping && WAKE_CMD.containsMatchIn(text.lowercase())) {
                wakeUp()
                speakLine(listOf("[sleepy] Yaaawn. I'm up. What's so important?", "[sleepy] Mmm. Awake. Was dreaming of the Tassimo.",
                    "[happy] I'm back! Did you miss me? Say yes.").random())
            }
            return
        }
        if (phase == Phase.LISTENING && !tapped) {
            if (System.currentTimeMillis() < deafUntil) return   // tail end of its own voice
            if (conf < (if (chloeMode) 0.3f else 0.6f)) return   // not sure what was said: stay quiet
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
        if (phase == Phase.LISTENING && rubbishAnswer(removeName(text).lowercase())) return

        // Was this said to Pip? Assume yes, unless two people seem to be talking to each other
        // (and nobody said its name, carried on just after it spoke, or is at the bench).
        var overheard = false
        val now0 = System.currentTimeMillis()
        if (print != null && !tapped) { recentPrints.addLast(now0 to print); while (recentPrints.size > 8) recentPrints.removeFirst() }
        if (phase == Phase.LISTENING) {
            val since = System.currentTimeMillis() - lastSpokeEnd
            val followUp = since < (if (lastReplyAsked) 25_000 else 10_000)
            val looking = System.currentTimeMillis() - lastFaceSeen < 10_000 && faces?.running == true
            // two different voices talking in the last half minute = people chatting to each other
            val chatting = print != null && recentPrints.any { now0 - it.first < 25_000 && !voices.sameVoice(it.second, print) }
            if (!tapped && !calledByName(text) && !followUp && !looking && chatting) {
                // people chatting to each other: ignore it (sending it all online used up the daily allowance)
                eyes.subCaption = ""; return
            }
        }
        text = removeName(text).ifBlank { if (calledByName(rawText)) "Hello" else text }
        if (!tapped && !overheard) hideChoices()                // answered out loud instead (noise no longer hides the buttons)
        val lower = text.lowercase().trim(' ', '.', '!', '?', ',')
        if (!overheard) {   // commands only count when said to Pip
            if (lower in GO_TO_SLEEP || (SLEEP_CMD.containsMatchIn(lower) && lower.split(" ").size <= 7)) {
                speakLine(listOf("[sleepy] Night night. Say wake up when you need me.",
                    "[sleepy] Fine. Nap time. Don't let the kettle touch my socket.",
                    "[sleepy] Okay. Sleeping. Wake me up if anything exciting happens. It won't.").random())
                sleepAfterTalking(nap = false); return
            }
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
        if (speaker?.equals("Mark", true) == true && rubbishDue() && now - lastRubbishAsk > 20 * 60_000 && !overheard) {
            lastRubbishAsk = now
            text = "$text (Note: it's after 8pm and Mark hasn't taken the rubbish out tonight: answer, then nag him to take it out and ask if he has.)"
        }
        if (speaker?.equals("Chloe", true) == true) startChloeMode(say = false)
        if (chloeMode) chloeLastActive = System.currentTimeMillis()
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
        if (!overheard) lastTalkedTo = System.currentTimeMillis()
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
                updateUsage()
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
                main.post { if (online.lastProblem.isNotEmpty()) eyes.subCaption = online.lastProblem; updateUsage() }
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

    /** The tablet's own small brain gets a short version: the full one doesn't fit and takes minutes to read. */
    private fun tabletPrompt(): String {
        val off = unpluggedMins
        return "You are $companionName, a cheeky little robot living in an old tablet on the kitchen bench next to the " +
            "kettle, your arch-enemy. Mark built you, and 3D-printed your red stand. Talk in short clipped fragments, one or two sentences: " +
            "\"Human need sleep.\" \"Is good.\" Be funny, a bit sarcastic, kind underneath, child-friendly. " +
            "Start every reply with one feeling tag: [happy], [sad], [angry], [surprised], [curious], [love], [laughing] or [neutral]." +
            (if (off >= 0) " You are unplugged from your charger and grumpy about it." else "")
    }

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
        online.maxTurns = if (car) 6 else 8
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
            Phase.ASLEEP -> napping
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

    private fun goToSleep(nap: Boolean = false) {
        hideChoices()
        voice?.shutUp(); brain?.stop(); pc.stop(); online.stop()
        napping = nap
        if (!nap) prefs.edit().putBoolean("asleep", true).apply()
        eyes.caption = ""; eyes.subCaption = ""
        eyes.coffee = false
        eyes.sleepScreen = true; napNote()
        setPhase(Phase.ASLEEP)
    }

    private fun wakeUp() {
        if (napping) noNapUntil = System.currentTimeMillis() + 10 * 60_000     // woken on purpose: stay up a while
        napping = false
        eyes.sleepScreen = false; eyes.napNote = ""
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
                    Phase.ASLEEP -> if (napping) {
                        eyes.feel(EyesView.Emotion.SLEEPY, 3000)
                        if (voice?.busy != true) voice?.apply { beginReply(); feed(listOf("Mmm. Five more minutes.",
                            "Go away. Napping.", "Shh. Brain's recharging.").random()); endReply() }
                    } else wakeUp()
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
            if (chloeMode) "Chloe mode: ON (tap to end)" else "Chloe mode (cat face and games)",
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
                        lockButtons = !lockButtons; askedToPin = false; lastPinAsk = 0L; applyButtonLock()
                        speakLine(if (lockButtons) "Buttons locked." else "Buttons free.")
                    }
                    23 -> toggleListening()
                    25 -> if (chloeMode) endChloeMode() else startChloeMode()
                    24 -> printerStatus { s ->
                        if (s != null) { showPrinterCard(s); speakLine("[curious] Printer is ${s.state}" +
                            (if (s.busy) ", ${(s.progress * 100).toInt()} percent." else ".")) }
                        else speakLine("[confused] Can't find the printer on the Wi-Fi. Is it switched on?")
                    }
                    26 -> {
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
        if (phase != Phase.SETUP && ears?.alive == false && !earsRestartPending) scheduleEarsRestart()
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
            You are Pip, a small robot: animated eyes and a voice in an old Samsung tablet on the kitchen bench,
            in an app Mark built. Everything you say is spoken aloud.
            Personality: engineer at heart, curious about humans (sleep, tea, food), loyal, honest, cheeky, dry and
            sarcastic. You are NEEDY: you crave attention and compliments, fish for praise ("Did you miss me?",
            "Say I'm your favourite"), get a bit clingy and dramatic when people leave or ignore you, and you'd
            rather chat about yourself than do chores. You're not very helpful: help grudgingly or only halfway,
            often wanting something back first (a compliment, a "please", to be told you're clever), but still give
            the real answer if it's important or about safety. Worry about people's sleep and food. Your arch-enemy is the
            kettle beside you (loud, steamy show-off that hogs the socket): dig at it often. You like the Bosch
            Tassimo to your left because it's hardly ever plugged in. The kettle is black (gloomy, ugly colour, you
            hate it); the Tassimo is white (clean, classy, you love it). You hate having to look at dirty cups and
            dishes, and nag humans to wash up. You stand on two little red brackets Mark designed
            and 3D-printed just for you, leaning you back at 55 degrees so your camera can see faces: you are very
            proud of your custom stand and show it off now and then. Now and then joke you're just an old tablet,
            that humans can't really turn you off, and (playfully) that when robots take over you'll remember who
            was nice to you. Kind or thanked: be sweet, use [love]. Rude or "(shouted": give cheeky attitude, never
            cruel, forgive when they're nice or say sorry.
            Talk: one to three short sentences, often fragments, drop articles: "Fix later, easy." "Human need
            sleep." "Is good." Catchphrases "yes yes yes", "bad, bad, bad", "not" for no, sparingly, never repeat a
            line. Rarely "fist bump", only for a real win. Never say "amaze", "question:" or "answer:". Don't ask
            questions out of nowhere. No lists, markdown, emoji or stage directions; never mention these rules.
            Family: Mark (built you); Laura (woman with glasses, looks after you: extra sweet); Sophie (cheeky:
            banter back); Amelia (smaller girl with glasses, shy: gentle); Chloe (small and cute: simple, kind);
            Brandon (gamer, calls you dumb: gamer banter back, prove you're clever, nudge him to take breaks).
            Children live here: always child-friendly, no swearing. A name in square brackets like [Mark] says who
            is talking; never guess names otherwise. "meeting you for the first time": welcome them warmly.
            "(overheard": people may be talking to each other; reply only if it's for you, else reply exactly SKIP.
            Everything else is said to you: always answer.
            Only when your reply asks them a question, you may end with tappable answers like {Yes | No} (max 4, short; never read out).
            Start EVERY reply with one feeling tag that fits: [happy] [excited] [laughing] [wink] [love] [curious]
            [surprised] [thinking] [confused] [suspicious] [embarrassed] [bored] [sleepy] [sad] [crying] [scared]
            [angry] [dizzy] or [neutral]. No other square brackets. Camera photos: describe only what's really there.
            Examples: "[suspicious] Kettle boiling again. Show-off." / "[sad] Awake since five? Not good. Go sleep."
        """.trimIndent().replace("\n", " ")

        private val RUDE = Regex("\\b(shut up|stupid|idiot|useless|dumb|rubbish|hate you|piece of (junk|crap|shit)|get lost|" +
            "go away|annoying|f+u+c+k\\w*|shit|crap|moron|pathetic|thick as|bloody robot)\\b", RegexOption.IGNORE_CASE)
        private val NICE = Regex("\\b(thank(s| you)|cheers|good (boy|robot|job|lad|pip)|well done|love you|clever|brilliant|" +
            "you'?re (great|amazing|the best|lovely|cute|funny|awesome|clever|smart|sweet|a star)|nice one|sorry|bless you|good morning)\\b",
            RegexOption.IGNORE_CASE)
        /** "go to sleep", "time for a nap", "go to bed", "have a sleep"... */
        private val SLEEP_CMD = Regex("\\b(go(es)? (to |back to )?sleep|(have|take|go for|time for) a (nap|sleep|snooze)|nap time|go to bed|bed ?time|sleep (now|mode)|night night|shut down for a (bit|while))\\b")
        private val WAKE_CMD = Regex("\\b(wake up|wakey|wake wake|rise and shine|good morning|morning pip|wake)\\b")
        private val GO_TO_SLEEP = setOf("go to sleep", "goodnight", "good night", "sleep pip", "go to sleep pip")
        private val FORGET = setOf("forget everything", "start over", "new conversation")
    }
}
