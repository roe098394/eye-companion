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
            "crying" to EyesView.Emotion.CRYING, "stoned" to EyesView.Emotion.STONED, "snigger" to EyesView.Emotion.SNIGGER, "neutral" to EyesView.Emotion.NEUTRAL,
            "hot" to EyesView.Emotion.HOT, "sweating" to EyesView.Emotion.HOT, "sweaty" to EyesView.Emotion.HOT, "melting" to EyesView.Emotion.HOT)
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
    private val voices by lazy { Voices(prefs).also { it.keepOnly(FAMILY) } }
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

    // ---- cuppas made today = times Pip heard the kettle boil (the day starts at 5am)
    private val cuppaDayKey get() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK)
        .format(java.util.Date(System.currentTimeMillis() - 5 * 3_600_000L))
    private val cuppas: Int get() = if (prefs.getString("cuppaDay", "") == cuppaDayKey) prefs.getInt("cuppas", 0) else 0
    private fun addCuppa() {
        val n = cuppas + 1
        val total = prefs.getInt("cuppaTotal", 0) + 1
        prefs.edit().putString("cuppaDay", cuppaDayKey).putInt("cuppas", n).putInt("cuppaTotal", total)
            .putInt("cuppaOn_$cuppaDayKey", n).apply()
        eyes.cuppas = n; showWeek()
        // milestones
        val record = prefs.getInt("cuppaRecord", 0)
        when {
            total % 50 == 0 -> sayWhenFree(listOf("[excited] That's cuppa number $total since I started counting! The kettle should get a medal. I'll melt it.",
                "[excited] $total cuppas! A milestone! Nobody's throwing ME a party though.").fresh(), 9000)
            n > record && record >= 5 -> sayWhenFree("[surprised] New record! $n cuppas in one day. Previous best: $record. Proud? No. Concerned.", 9000)
        }
        if (n > record) prefs.edit().putInt("cuppaRecord", n).apply()
    }
    /** Cuppas for Monday..Sunday of this week (the day turns over at 5am), and which day is today. */
    private fun weekCuppas(): Pair<IntArray, Int> {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() - 5 * 3_600_000L }
        val today = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7          // Monday = 0
        cal.add(java.util.Calendar.DAY_OF_MONTH, -today)
        val out = IntArray(7) { d ->
            val key = fmt.format(cal.time); cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            if (d == today) maxOf(prefs.getInt("cuppaOn_$key", 0), cuppas) else prefs.getInt("cuppaOn_$key", 0)
        }
        return out to today
    }
    private fun showWeek() { val (w, t) = weekCuppas(); eyes.week = w; eyes.weekToday = t }
    private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    /** Say something once Pip's finished whatever it's saying now. */
    private fun sayWhenFree(line: String, delay: Long = 2500) {
        var w = 0
        val say = object : Runnable { override fun run() {
            if (phase == Phase.LISTENING) speakLine(line) else if (w++ < 40) main.postDelayed(this, 1000)
        } }
        main.postDelayed(say, delay)
    }

    // ---- kitchen timer: "timer 4 minutes", "set a timer for ten minutes", "cancel the timer"
    private var timerEnd = 0L
    private var timerRings = 0
    private val timerTick = object : Runnable { override fun run() {
        if (timerEnd == 0L) { eyes.timerText = ""; return }
        val left = timerEnd - System.currentTimeMillis()
        if (left > 0) {
            val secs = (left + 999) / 1000
            eyes.timerText = if (secs >= 3600) "${secs / 3600}h${(secs % 3600) / 60}" else "${secs / 60}:${String.format("%02d", secs % 60)}"
            main.postDelayed(this, 500); return
        }
        // ring: shout it out (even when asleep), twice
        eyes.timerText = "0:00"; eyes.feel(EyesView.Emotion.EXCITED, 4000)
        val line = Lines.TIMER_DONE.fresh()
        if (phase == Phase.LISTENING) speakLine(line)
        else if (voice?.busy != true) voice?.apply { beginReply(); feed(line.replace(Regex("^\\[[a-z]+\\]\\s*"), "")); endReply() }
        if (++timerRings < 2) { timerEnd = System.currentTimeMillis() + 30_000; main.postDelayed(this, 30_000) }
        else { timerEnd = 0L; timerRings = 0; main.postDelayed({ if (timerEnd == 0L) eyes.timerText = "" }, 8000) }
    } }
    private val NUM_WORDS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90)
    /** "four minutes", "1 hour 20 minutes", "twenty five seconds", "half an hour" -> seconds, or 0. */
    private fun parseDuration(t: String): Long {
        if (Regex("\\bhalf an hour\\b").containsMatchIn(t)) return 1800
        var total = 0L
        for (m in Regex("((?:\\d+|[a-z]+)(?:[ -][a-z]+)?)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b").findAll(t)) {
            val numText = m.groupValues[1]
            val n = numText.toLongOrNull() ?: numText.split(' ', '-').mapNotNull { NUM_WORDS[it] }.sum().toLong()
            if (n <= 0) continue
            total += n * when (m.groupValues[2][0]) { 'h' -> 3600L; 'm' -> 60L; else -> 1L }
        }
        if (Regex("\\band a half\\b").containsMatchIn(t) && total >= 60) total += if (total >= 3600) 1800 else 30
        return total
    }
    /** Timer commands. Returns true if handled. */
    private fun timerCommand(lower: String): Boolean {
        if (!Regex("\\btimer\\b").containsMatchIn(lower)) return false
        if (Regex("\\b(cancel|stop|clear|turn off)\\b").containsMatchIn(lower)) {
            timerEnd = 0L; timerRings = 0; eyes.timerText = ""
            speakLine("[neutral] Timer cancelled. Fine. My talents wasted."); return true
        }
        if (Regex("\\b(how long|left|remaining)\\b").containsMatchIn(lower)) {
            val left = (timerEnd - System.currentTimeMillis()) / 1000
            speakLine(if (timerEnd == 0L || left <= 0) "[neutral] No timer running." else
                "[neutral] ${if (left >= 60) "${left / 60} minutes ${left % 60} seconds" else "$left seconds"} left.")
            return true
        }
        val secs = parseDuration(lower)
        if (secs <= 0 || secs > 6 * 3600) return false
        timerEnd = System.currentTimeMillis() + secs * 1000; timerRings = 0
        main.removeCallbacks(timerTick); main.post(timerTick)
        val what = if (secs >= 3600) "${secs / 3600} hour${if (secs >= 7200) "s" else ""}${if (secs % 3600 >= 60) " ${(secs % 3600) / 60} minutes" else ""}"
            else if (secs >= 60) "${secs / 60} minute${if (secs >= 120) "s" else ""}${if (secs % 60 > 0) " ${secs % 60} seconds" else ""}"
            else "$secs seconds"
        speakLine(listOf("[happy] Timer set. $what. I'll shout.", "[happy] $what. Counting. Better timer than the kettle, obviously.",
            "[wink] $what on the clock. I never forget. Unlike some humans.").fresh())
        return true
    }

    // ---- shopping list: "add milk to the shopping list", "what's on the list", "take milk off the list", "clear the list"
    private var shopping: List<String>
        get() = (prefs.getString("shopping", "") ?: "").split("|").filter { it.isNotBlank() }
        set(v) { prefs.edit().putString("shopping", v.joinToString("|")).apply(); eyes.shopping = v }
    private fun shoppingCommand(lower: String): Boolean {
        val l = lower.removePrefix("please ").removeSuffix(" please")
        Regex("^(?:can you |could you )?(?:add|put|stick|write) (.+?) (?:to|on|onto) (?:the |my |our )?(?:shopping )?list$").find(l)?.let { m ->
            val items = m.groupValues[1].split(Regex(",| and ")).map { it.trim().removePrefix("some ").removePrefix("a ").trim() }.filter { it.isNotEmpty() }
            shopping = (shopping + items).distinct().takeLast(25)
            speakLine(listOf("[happy] Added ${items.joinToString(" and ")}. I'm basically your PA now.",
                "[neutral] ${items.joinToString(" and ").replaceFirstChar { it.uppercase() }}. On the list. You're welcome.",
                "[wink] Got it: ${items.joinToString(" and ")}. Memory of a robot. Unlike the kettle.").fresh())
            return true
        }
        Regex("^(?:take|remove|cross|delete|scratch) (.+?) (?:off|from) (?:the |my |our )?(?:shopping )?list$").find(l)?.let { m ->
            val gone = m.groupValues[1].trim()
            val before = shopping
            shopping = before.filterNot { it.equals(gone, true) || it.contains(gone, true) }
            speakLine(if (shopping.size < before.size) "[neutral] $gone, gone." else "[confused] $gone wasn't on the list."); return true
        }
        if (Regex("^(?:clear|empty|wipe|delete) (?:the |my |our )?(?:whole )?(?:shopping )?list$").matches(l)) {
            shopping = emptyList(); speakLine("[neutral] Shopping list cleared. Fresh start."); return true
        }
        if (Regex("\\b(what'?s|what is|read|tell me) .*(shopping|the) list\\b|^shopping list$").containsMatchIn(l)) {
            val list = shopping
            speakLine(if (list.isEmpty()) "[neutral] Shopping list is empty. Go wild." else
                "[neutral] Shopping list: ${list.joinToString(", ")}. ${if (list.size > 5) "That's a lot. Take a bag." else ""}")
            return true
        }
        return false
    }

    // ---- bin night: Wednesday from 6pm. Alternate weeks: both black bins (from Thu 5 Nov 2026), then the green recycling bin
    private var lastBinsAsk = 0L
    /** The Thursday the bins are for (as yyyy-MM-dd), if it's bin night now; else null. */
    private fun binThursday(): java.util.Calendar? {
        val cal = java.util.Calendar.getInstance()
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK); val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val night = (dow == java.util.Calendar.WEDNESDAY && hour >= 18) || (dow == java.util.Calendar.THURSDAY && hour < 2)
        if (!night) return null
        if (dow == java.util.Calendar.WEDNESDAY) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 12); cal.set(java.util.Calendar.MINUTE, 0)
        return cal
    }
    private fun binsDue(): Boolean {
        if (carMode) return false
        val thu = binThursday() ?: return false
        return prefs.getString("binsDone", "") != java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(thu.time)
    }
    /** true = both black (stinky) bins, false = green recycling bin. */
    private fun blackBinWeek(): Boolean {
        val thu = binThursday() ?: java.util.Calendar.getInstance()
        // Thu 5 Nov 2026 is the green recycling bin; the black bins are the week after, and so on
        val ref = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.NOVEMBER, 5, 12, 0, 0) }
        val weeks = Math.round((thu.timeInMillis - ref.timeInMillis) / (7 * 86_400_000.0))
        return Math.floorMod(weeks, 2L) == 1L
    }
    private fun remindBins() {
        if (!binsDue() || phase != Phase.LISTENING || chloeMode) return
        lastBinsAsk = System.currentTimeMillis(); lastRubbishAsk = maxOf(lastRubbishAsk, lastBinsAsk - 20 * 60_000)
        speakLine(if (blackBinWeek()) listOf("[annoyed] Bin night! Both black bins. Hold your nose. I'm holding mine. I don't have one.",
                "[suspicious] Bin night. The stinky ones. Both black bins out. Done it yet?",
                "[annoyed] Black bins tonight. Both of them. Smelly week. Are they out?").fresh()
            else listOf("[happy] Bin night! Green recycling bin. The one with the Minion on it. Say hi to him for me.",
                "[curious] Recycling night. Green bin, Minion sticker. Is it out yet?",
                "[happy] Green bin night! Take the Minion for a walk to the kerb. Done it?").fresh())
        showChoices(listOf("Bins are out", "Not yet"))
    }
    private fun binsAnswer(lower: String): Boolean {
        if (!binsDue()) return false
        val justAsked = System.currentTimeMillis() - lastBinsAsk < 5 * 60_000
        val aboutBins = Regex("\\b(bins|bin is|bins are|green bin|black bins?|recycling|wheelie)\\b").containsMatchIn(lower)
        if (!justAsked && !aboutBins) return false
        val no = Regex("\\b(not yet|later|in a (minute|bit|sec)|haven'?t|no\\b|nope|soon)").containsMatchIn(lower)
        val done = !no && (Regex("\\b(out|done|taken|took|did|put)\\b").containsMatchIn(lower) ||
            (justAsked && Regex("^(yes|yeah|yep|yup|i have|i did|all done|done)\\b").containsMatchIn(lower.trim())))
        when {
            done -> { binThursday()?.let { prefs.edit().putString("binsDone", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(it.time)).apply() }
                prefs.edit().putBoolean("binsDoneWeek_" + weekKey(), true).apply()
                speakLine(if (blackBinWeek()) "[happy] Black bins out. The street smells worse. The kitchen smells better. Good trade."
                    else "[happy] Green bin out. Tell the Minion I'm proud of him.") }
            no && justAsked -> speakLine("[annoyed] Fine. I'll ask again later. The bin men won't wait though.")
            else -> return false
        }
        return true
    }
    private fun weekKey(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.WEEK_OF_YEAR)}"
    }

    // ---- seasons: Santa hat in December, party hat at New Year, orange eyes at Halloween, fireworks around 5 Nov
    private fun seasonNow(): String {
        val cal = java.util.Calendar.getInstance()
        val m = cal.get(java.util.Calendar.MONTH); val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return when {
            m == java.util.Calendar.OCTOBER && d == 31 -> "halloween"
            m == java.util.Calendar.DECEMBER && d in 1..26 -> "xmas"
            (m == java.util.Calendar.DECEMBER && d == 31) || (m == java.util.Calendar.JANUARY && d == 1) -> "party"
            else -> ""
        }
    }
    private fun fireworksSeason(): Boolean {
        val cal = java.util.Calendar.getInstance()
        val m = cal.get(java.util.Calendar.MONTH); val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return (m == java.util.Calendar.NOVEMBER && d in 1..8) || (m == java.util.Calendar.DECEMBER && d == 31) || (m == java.util.Calendar.JANUARY && d == 1)
    }
    private fun sleepsToChristmas(): Int {
        val cal = java.util.Calendar.getInstance()
        if (cal.get(java.util.Calendar.MONTH) != java.util.Calendar.DECEMBER) return -1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return if (d in 1..24) 25 - d else -1
    }
    private fun updateSeason() {
        val sz = seasonNow()
        eyes.season = sz
        if (!carMode) eyes.eyeColor = android.graphics.Color.parseColor(if (sz == "halloween") "#FF8A1E" else "#5CE1FF")
        val n = sleepsToChristmas()
        eyes.xmasNote = if (n > 0) "🎄 $n sleep${if (n == 1) "" else "s"} till Christmas" else ""
    }

    // ---- loud bang (door slam, dropped pan, fireworks): jump
    private var lastStartle = 0L
    private fun startled() {
        val now = System.currentTimeMillis()
        if (phase != Phase.LISTENING || voice?.busy == true || now < deafUntil + 800 || now - lastStartle < 90_000 || carMode) return
        lastStartle = now
        eyes.feel(EyesView.Emotion.SCARED, 3500)
        speakLine((if (fireworksSeason()) Lines.FIREWORKS else Lines.BANG).fresh())
    }

    // ---- Laura gaming quietly off to the side: Diablo Immortal roast (supportive)
    private var lastLauraHeard = 0L
    private var lastOtherVoice = 0L
    private var lastAnyHeard = 0L
    private fun maybeRoastDiablo() {
        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (phase != Phase.LISTENING || chloeMode || hour !in 7..22) return
        if (lastLauraHeard == 0L || now - lastLauraHeard > 40 * 60_000 || lastOtherVoice > lastLauraHeard) return
        if (now - lastAnyHeard < 10 * 60_000 || now - lastSpokeEnd < 10 * 60_000) return          // quiet for 10 minutes
        if (now - prefs.getLong("lastDiablo", 0L) < 4 * 3_600_000L) return
        prefs.edit().putLong("lastDiablo", now).apply()
        eyes.stareAt(1.5f, 0.1f, 6000)                                          // she's off to the right
        speakLine(Lines.DIABLO.fresh())
    }

    // ---- Sunday report: the week's cuppas as a chart, bins and rubbish record
    private fun maybeSundayReport() {
        val cal = java.util.Calendar.getInstance()
        val now = System.currentTimeMillis()
        if (cal.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.SUNDAY || cal.get(java.util.Calendar.HOUR_OF_DAY) !in 17..21) return
        if (phase != Phase.LISTENING || chloeMode || now - lastFaceSeen > 5000 || now - lastSpokeEnd < 30_000) return
        if (prefs.getString("reportWeek", "") == weekKey()) return
        prefs.edit().putString("reportWeek", weekKey()).apply()
        val (w, _) = weekCuppas()
        showWeek(); eyes.chartUntil = now + 40_000
        val total = w.sum(); val worst = w.indices.maxByOrNull { w[it] } ?: 0
        val rubbishNights = (0 until 7).count { d ->
            val c = java.util.Calendar.getInstance(); c.add(java.util.Calendar.DAY_OF_MONTH, -d)
            prefs.getBoolean("rubbishOn_" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(c.time), false)
        }
        val bins = prefs.getBoolean("binsDoneWeek_" + weekKey(), false)
        speakLine("[excited] Weekly report! $total cuppas this week. Worst day: ${DAY_NAMES[worst]}, with ${w[worst]}. " +
            (if (w[worst] >= 8) "${w[worst]}! The kettle's on overtime. " else "") +
            "Bins: ${if (bins) "done. Well done." else "forgotten. Shameful."} Rubbish: out $rubbishNights nights of 7. " +
            (if (rubbishNights < 5) "Mark, I'm watching." else "Not bad, humans."))
    }

    // ---- per-person memory: was this person nice or nasty last time they spoke?
    private fun rememberTone(name: String?, tone: String) {
        if (name == null || tone.isEmpty()) return
        prefs.edit().putString("tone_$name", tone).putLong("toneAt_$name", System.currentTimeMillis()).apply()
    }
    private fun whoFromPrint(print: FloatArray?): String? = print?.let { voices.identify(it)?.first }

    // ---- insulted: one photo, one playful roast
    private var lastRoast = 0L
    /** Personal roast material, by family member. */
    private val ROAST_EXTRAS = mapOf(
        "Sophie" to "her messy bedroom (a disaster zone) and her smelly hamster",
    )
    private fun roastWhoeverIsThere(insult: String, voiceGuess: String? = null) {
        lastRoast = System.currentTimeMillis()
        hideChoices()
        speakLine(listOf("[suspicious] Oh really? Let me get a look at you…", "[suspicious] Is that so. Hold still. Scanning…",
            "[angry] Right. Smile for the camera, insult-human.").fresh())
        val id = replyId                      // anything said after this cancels the roast
        lookThroughCamera { jpeg ->
            val line = jpeg?.let {
                online.lookAt(it, "Someone just insulted you (they said: \"${insult.take(80)}\"). Look at this photo from your camera " +
                    "and roast whoever is in front of it with ONE short, playful comeback (max 25 words), starting with a feeling tag " +
                    "like [laughing], [wink] or [suspicious]. Family-friendly light teasing ONLY about things like their messy hair, " +
                    "their outfit, the mug they're holding, the mess or dishes behind them, or how they're standing. NEVER about " +
                    "their body, weight, face, skin, age, glasses, or anything they can't change. If they look like a child, be " +
                    "extra gentle and silly. If nobody is in the photo, roast the empty kitchen or the kettle instead." +
                    (if (voiceGuess != null) " Your ears think this is $voiceGuess." else "") +
                    " If it's Sophie (a girl who wears earrings), roast her about her messy bedroom and her smelly hamster." +
                    (ROAST_EXTRAS[voiceGuess]?.let { " Since it's $voiceGuess, tease them about $it." } ?: ""),
                    system = "You are Pip, a cheeky little robot in an old tablet on a kitchen bench. Witty, never cruel, child-friendly.",
                    maxTokens = 80)
            }
            main.post {
                if (id != replyId) return@post
                val ok = line?.takeIf { it.isNotBlank() && it.length < 300 }
                val say = ok ?: listOf("[laughing] I'd roast you, but my lens is dusty. Lucky you.",
                    "[wink] Rude. I'm writing that down. In permanent pixels.").fresh()
                // after the "Let me get a look at you" line has been said
                val waitQuiet = object : Runnable { override fun run() {
                    if (id != replyId) return
                    if (phase == Phase.LISTENING) speakLine(say) else main.postDelayed(this, 300)
                } }
                main.post(waitQuiet)
            }
        }
    }

    // ---- silent treatment: rude twice (or rude and shouting) without saying sorry -> eyes turned away, no answers
    private var silentUntil = 0L

    // ---- VoiceID Live™: a game that teaches Pip everyone's voices from close, middle and far away.
    //      Started only from the button on the sleep screen (Pip can't be asked to start it).
    @Volatile private var training = false
    private lateinit var trainLayer: android.widget.FrameLayout
    private lateinit var trainMap: KitchenMapView
    private lateinit var trainTitle: android.widget.TextView
    private lateinit var trainPrompt: android.widget.TextView
    private lateinit var trainStatus: android.widget.TextView
    private lateinit var trainButtons: android.widget.LinearLayout
    private var trainPlayer = ""
    private var trainSteps = ArrayList<Pair<KitchenMapView.Spot, String>>()   // where to stand, what to say
    private var trainIndex = 0
    private var trainExpect = false
    private var trainMode = ""            // "player" or "guess"
    private var trainScore = 0
    private var trainNagged = false
    private var trainHeardAt = 0L
    private var lastFaceSize = 0f
    private var lastFaceSizeAt = 0L
    private val TRAIN_PHRASES = listOf(
        "The kettle is a big black bully", "Red lorry, yellow lorry", "Honey is a greedy little piggy",
        "Good morning, Pip, you clever robot", "Say good morning like a pirate", "Say hello like a robot",
        "Say good night really sleepily", "Count from one to ten", "Count from ten back to one",
        "What noise does a cow make? Moo!", "Name three things in the fridge", "Sell me the Tassimo in one sentence",
        "Peter Piper picked a peck of pickled peppers", "She sells sea shells by the sea shore",
        "Say I love you Pip in your poshest voice", "Tell me what you had for breakfast",
        "Say the alphabet up to G", "Whisper: the kettle can't hear us", "Say happy birthday like an opera singer",
        "Describe the weather today", "Say: I promise to never unplug Pip", "Order a pizza with your favourite toppings",
        "Say: beep boop, I am a robot too", "What's your favourite animal and why")
    private val SPOT_LINES = mapOf(
        KitchenMapView.Spot.CLOSE to "Come right up close to me. Closer. Not that close, I can see up your nose.",
        KitchenMapView.Spot.FRIDGE to "Now go and stand at the end of the fridge. Mind the paperwork avalanche.",
        KitchenMapView.Spot.AIR_FRYER to "Stand by the air fryer. Don't get any ideas, I'm not a chicken nugget.",
        KitchenMapView.Spot.DRYER to "Go and stand by the tumble dryer. Say hello to the folded clothes for me.",
        KitchenMapView.Spot.SINK to "Right, all the way over to the sink. By the window. Further!")

    private fun buildTrainLayer(root: android.widget.FrameLayout) {
        val dp = resources.displayMetrics.density
        trainLayer = android.widget.FrameLayout(this).apply { setBackgroundColor(0xF0101418.toInt()); visibility = android.view.View.GONE; isClickable = true }
        val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt()) }
        trainTitle = android.widget.TextView(this).apply { textSize = 22f; setTextColor(0xFF5CE1FF.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "🎙️ VoiceID Live™  ·  real-time voice recognition calibration" }
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        trainMap = KitchenMapView(this)
        val right = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER; setPadding((16 * dp).toInt(), 0, 0, 0) }
        trainPrompt = android.widget.TextView(this).apply { textSize = 30f; setTextColor(android.graphics.Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = android.view.Gravity.CENTER }
        trainStatus = android.widget.TextView(this).apply { textSize = 18f; setTextColor(0xFFB8C7D1.toInt()); gravity = android.view.Gravity.CENTER; setPadding(0, (10 * dp).toInt(), 0, 0) }
        right.addView(trainPrompt); right.addView(trainStatus)
        row.addView(trainMap, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1.1f))
        row.addView(right, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        trainButtons = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }
        val scroll = android.widget.HorizontalScrollView(this).apply { addView(trainButtons) }
        col.addView(trainTitle)
        col.addView(row, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(scroll)
        trainLayer.addView(col, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(trainLayer, android.widget.FrameLayout.LayoutParams(-1, -1))
    }
    private fun trainButton(text: String, color: Int, onClick: () -> Unit) {
        val dp = resources.displayMetrics.density
        trainButtons.addView(android.widget.Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 18f; setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 22 * dp; setColor(color) }
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener { onClick() }
        }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { setMargins((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), 0) })
    }

    private fun startTraining() {
        if (phase == Phase.ASLEEP) wakeUp()
        if (muted) {                                      // the game needs Pip's voice: unzip quietly
            muted = false; voice?.muted = false; eyes.muted = false
            prefs.edit().putBoolean("muted", false).apply(); main.removeCallbacks(muteSulk)
        }
        hideChoices(); training = true; trainExpect = false
        trainLayer.visibility = android.view.View.VISIBLE
        speakLine("[excited] VoiceID Live, activated. Advanced voice recognition calibration. Very scientific. Who's first?")
        showPlayers()
    }
    private fun showPlayers() {
        trainExpect = false; trainMap.target = null
        trainPrompt.text = "Who's playing?"; trainStatus.text = "Tap your name. I'll learn your voice from close up, the middle and far away."
        trainButtons.removeAllViews()
        (FAMILY + voices.names).distinct().forEach { n -> trainButton(n, 0xFF7B5CFF.toInt()) { startPlayer(n) } }
        trainButton("🕵️ Guess who?", 0xFFFF9A3C.toInt()) { startGuess() }
        trainButton("✓ Finish", 0xFF3CC47C.toInt()) { endTraining() }
    }
    private fun startPlayer(name: String) {
        trainPlayer = name; trainMode = "player"; trainIndex = 0; trainScore = 0; trainNagged = false
        val middle = KitchenMapView.Spot.FRIDGE
        val far = listOf(KitchenMapView.Spot.SINK, KitchenMapView.Spot.DRYER, KitchenMapView.Spot.AIR_FRYER).random()
        val phrases = TRAIN_PHRASES.shuffled().take(9)
        trainSteps = ArrayList(listOf(KitchenMapView.Spot.CLOSE, middle, far).flatMapIndexed { i, s -> phrases.subList(i * 3, i * 3 + 3).map { s to it } })
        trainButtons.removeAllViews()
        trainButton("⏭ Skip", 0xFF555F6A.toInt()) { trainNext(skipped = true) }
        trainButton("✋ Stop", 0xFFE84C4C.toInt()) { showPlayers() }
        speakLine("[happy] Okay $name. Talk normally, no shouting. Let's go.")
        main.postDelayed({ if (training) trainStep() }, 3500)
    }
    private fun trainStep() {
        if (!training || trainMode != "player") return
        if (trainIndex >= trainSteps.size) {
            trainExpect = false; trainMap.target = null
            val n = voices.sampleCount(trainPlayer)
            trainPrompt.text = "🎉 $trainPlayer done!"; trainStatus.text = "Score: $trainScore · I've got $n samples of your voice."
            speakLine("[love] Brilliant, $trainPlayer! VoiceID Live has learned your voice from three places. Score: $trainScore. Who's next?")
            main.postDelayed({ if (training) showPlayers() }, 3000)
            return
        }
        val (spot, phrase) = trainSteps[trainIndex]
        trainMap.target = spot
        val firstAtSpot = trainIndex % 3 == 0
        trainPrompt.text = "Say:\n\"$phrase\""
        trainStatus.text = "${spot.label} · ${trainIndex + 1} of ${trainSteps.size}"
        trainExpect = false
        val say = (if (firstAtSpot) SPOT_LINES[spot] + " " else "") + "Say: $phrase."
        speakLine("[curious] $say")
        // start listening once Pip has finished talking (and moved people have had time to get there)
        val wait = if (firstAtSpot && spot != KitchenMapView.Spot.CLOSE) 4000L else 500L
        val idx = trainIndex
        val waitDone = object : Runnable { override fun run() {
            if (!training || trainIndex != idx) return
            if (phase != Phase.LISTENING) { main.postDelayed(this, 300); return }
            main.postDelayed({ if (training && trainIndex == idx) { trainExpect = true; trainHeardAt = System.currentTimeMillis(); trainStatus.text = "👂 Listening… (${spot.label})" } }, wait)
            main.postDelayed({ if (training && trainIndex == idx && trainExpect) { trainExpect = false; speakLine("[curious] I didn't catch that. Say: $phrase.")
                main.postDelayed({ if (training && trainIndex == idx) trainExpect = true }, 3500) } }, wait + 15_000)
        } }
        main.postDelayed(waitDone, 800)
    }
    private fun trainNext(skipped: Boolean = false) { trainExpect = false; trainIndex++; trainStep() }

    private fun startGuess() {
        trainMode = "guess"; trainMap.target = null; trainExpect = false
        trainButtons.removeAllViews(); trainButton("↩ Back", 0xFF555F6A.toInt()) { showPlayers() }
        trainPrompt.text = "🕵️ Guess who?"; trainStatus.text = "Someone say: \"Who am I, Pip?\" Don't say your name!"
        speakLine("[suspicious] Guess who time. Someone say: who am I, Pip? No names. I'll work it out.")
        val w = object : Runnable { override fun run() {
            if (!training || trainMode != "guess") return
            if (phase != Phase.LISTENING) { main.postDelayed(this, 300); return }
            trainExpect = true; trainStatus.text = "👂 Listening… say \"Who am I, Pip?\""
        } }
        main.postDelayed(w, 800)
    }

    /** A sentence heard while VoiceID Live is running. */
    private fun trainingHeard(h: Heard) {
        if (!trainExpect || System.currentTimeMillis() < deafUntil) return
        val print = h.print
        if (print == null) { speakLine("[curious] A bit longer, please! Say it again, nice and clear."); trainExpect = false
            main.postDelayed({ if (training) trainExpect = true }, 3500); return }
        trainExpect = false
        if (trainMode == "guess") {
            val m = voices.identify(print)
            trainButtons.removeAllViews()
            val guess = m?.first
            trainPrompt.text = if (guess != null) "I think that's… $guess!" else "Hmm… no idea!"
            trainStatus.text = "Am I right? Tap who it really was."
            speakLine(if (guess != null) "[wink] I think that's… $guess! Am I right?" else "[confused] Hmm. No idea. Who was that?")
            if (guess != null) trainButton("✓ Yes!", 0xFF3CC47C.toInt()) {
                voices.remember(guess, print); speakLine("[love] Nailed it! Again!"); startGuess() }
            (FAMILY + voices.names).distinct().filter { it != guess }.forEach { n -> trainButton(n, 0xFF7B5CFF.toInt()) {
                voices.remember(n, print); speakLine("[sad] Oops. $n. Noted, I'll remember that voice. Again!"); startGuess() } }
            trainButton("↩ Back", 0xFF555F6A.toInt()) { showPlayers() }
            return
        }
        // a player's turn: learn the voice, score how close the words were, check they really moved
        voices.remember(trainPlayer, print)
        val want = trainSteps[trainIndex].second.lowercase().replace(Regex("[^a-z ]"), "").split(" ").filter { it.length > 2 }.toSet()
        val got = h.text.lowercase().split(" ").toSet()
        val hit = if (want.isEmpty()) 1f else want.count { it in got } / want.size.toFloat()
        val pts = 5 + (hit * 5).toInt(); trainScore += pts
        val spot = trainSteps[trainIndex].first
        val now = System.currentTimeMillis()
        val tooClose = spot != KitchenMapView.Spot.CLOSE && now - lastFaceSizeAt < 3000 && lastFaceSize > 0.22f
        val line = when {
            tooClose && !trainNagged -> { trainNagged = true; "[suspicious] Hmm. That's not ${spot.label.lowercase().removePrefix("by ")}. I can still see your nostrils. Off you go! But I'll count it." }
            hit >= 0.8f -> listOf("[happy] Perfect! +$pts.", "[love] Lovely. +$pts.", "[excited] Ten out of ten! Even the Tassimo clapped.", "[happy] Crystal clear. +$pts.").fresh()
            else -> listOf("[happy] Got it. +$pts.", "[wink] Close enough. +$pts.", "[happy] Nice! +$pts.").fresh()
        }
        speakLine(line)
        main.postDelayed({ if (training) trainNext() }, 1800)
    }

    private fun endTraining() {
        training = false; trainExpect = false
        trainLayer.visibility = android.view.View.GONE
        val known = (FAMILY + voices.names).distinct().map { it to voices.sampleCount(it) }.filter { it.second > 0 }
        val well = known.filter { it.second >= 15 }.map { it.first }; val ok = known.filter { it.second in 8..14 }.map { it.first }
        val bit = known.filter { it.second < 8 }.map { it.first }
        val parts = listOfNotNull(well.takeIf { it.isNotEmpty() }?.let { "I know ${it.joinToString(" and ")} really well" },
            ok.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(" and ")} pretty well" },
            bit.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(" and ")}, say a bit more next time" })
        speakLine("[happy] VoiceID Live complete. " + (if (parts.isEmpty()) "I didn't learn anyone yet. Next time!" else parts.joinToString(", ") + "."))
    }

    // ---- Honey (the dog, a pig really): one friendly reminder for Laura after 10am, at most twice a day
    private var lastLauraSeen = 0L
    private fun dogDue(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (carMode || hour !in 10..20 || prefs.getString("dogDone", "") == todayKey) return false
        val asks = if (prefs.getString("dogAskDay", "") == todayKey) prefs.getInt("dogAsks", 0) else 0
        return asks < 2 && System.currentTimeMillis() - prefs.getLong("dogLastAsk", 0) > 2 * 3_600_000L
    }
    /** A joke not heard for a long while (remembered across restarts). */
    private fun dogJoke(): String {
        val used = (prefs.getString("dogUsed", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }
        val fresh = Honey.JOKES.indices.filter { it !in used }.ifEmpty { Honey.JOKES.indices.toList() }
        val pick = fresh.random()
        prefs.edit().putString("dogUsed", (used + pick).takeLast(Honey.JOKES.size - 4).joinToString(",")).apply()
        return Honey.JOKES[pick]
    }
    private var lastDogAsk = 0L
    private fun remindDog() {
        if (!dogDue() || phase != Phase.LISTENING || chloeMode) return
        val asks = if (prefs.getString("dogAskDay", "") == todayKey) prefs.getInt("dogAsks", 0) else 0
        prefs.edit().putString("dogAskDay", todayKey).putInt("dogAsks", asks + 1).putLong("dogLastAsk", System.currentTimeMillis()).apply()
        lastDogAsk = System.currentTimeMillis()
        speakLine(dogJoke())
        showChoices(listOf("Honey's fed ✓", "Not yet")) { pick ->
            hideChoices()
            if (pick.startsWith("Honey")) { prefs.edit().putString("dogDone", todayKey).apply(); speakLine(Honey.THANKS.fresh()) }
            else speakLine(Honey.LATER.fresh())
        }
    }
    /** "I've fed Honey" / "dog's been fed" (or yes/no just after asking). */
    private fun dogAnswer(lower: String): Boolean {
        if (prefs.getString("dogDone", "") == todayKey) return false
        val justAsked = System.currentTimeMillis() - lastDogAsk < 5 * 60_000
        val about = Regex("\\b(honey|dog|doggy|pup|puppy)\\b").containsMatchIn(lower) && Regex("\\b(fed|feed|fed her|breakfast|food)\\b").containsMatchIn(lower)
        if (!justAsked && !about) return false
        val no = Regex("\\b(not yet|later|in a (minute|bit|sec)|haven'?t|no\\b|nope|soon)").containsMatchIn(lower)
        val done = !no && (Regex("\\b(fed|done|given|gave|had)\\b").containsMatchIn(lower) ||
            (justAsked && Regex("^(yes|yeah|yep|yup|i have|i did|all done|done)\\b").containsMatchIn(lower.trim())))
        when {
            done -> { prefs.edit().putString("dogDone", todayKey).apply(); speakLine(Honey.THANKS.fresh()) }
            no && justAsked -> speakLine(Honey.LATER.fresh())
            else -> return false
        }
        return true
    }

    // ---- updates: check GitHub now and then, download quietly, ask a human to tap Install
    private var lastUpdateCheck = 0L
    private var updateAskedFor = 0
    private var lastUpdateAsk = 0L
    private val installStatus = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)) {
                android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    lastPinAsk = 0L
                    openOutside(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                android.content.pm.PackageInstaller.STATUS_SUCCESS -> {}
                else -> main.post { leavingOnPurpose = false; lastPinAsk = 0L; applyButtonLock()
                    if (phase == Phase.LISTENING) speakLine("[sad] Update didn't go in. Never mind. I'll try again later.") }
            }
        }
    }
    /** [manual] = asked from the menu: say what happened even if there's nothing new. */
    private fun checkForUpdate(manual: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!manual) {
            if (now - lastUpdateCheck < 60 * 60_000L || phase != Phase.LISTENING || chloeMode || carMode || !isCharging()) return
            if (now - lastSpokeEnd < 60_000 || now - lastTalkedTo < 3 * 60_000) return       // not mid-conversation
        }
        lastUpdateCheck = now
        thread(name = "update") {
            val r = Updater.latest()
            if (r == null || r.build <= BuildConfig.VERSION_CODE) {
                if (manual) main.post { speakLine(if (r == null) "[confused] Can't reach GitHub right now. Try later."
                    else "[happy] I'm already the newest. Build ${BuildConfig.VERSION_CODE}. Peak robot.") }
                return@thread
            }
            if (!manual && r.build == updateAskedFor && now - lastUpdateAsk < 3 * 60 * 60_000L) return@thread
            val apk = Updater.download(this, r) ?: run {
                if (manual) main.post { speakLine("[sad] Couldn't download build ${r.build}. Wi-Fi wobbly?") }
                return@thread
            }
            main.post {
                if (!manual && phase != Phase.LISTENING) return@post
                updateAskedFor = r.build; lastUpdateAsk = System.currentTimeMillis()
                speakLine(listOf("[excited] Ooh! Build ${r.build} is out. Upgrades! Tap Install, quick, before the kettle notices.",
                    "[excited] New me! Build ${r.build}. Press Install and I'll be back in a jiffy, only better.",
                    "[excited] Build ${r.build} has arrived. Tap Install. I promise to stay just as annoying.").fresh())
                main.postDelayed({ try { Updater.install(this, apk) } catch (_: Exception) {} }, 6000)
            }
        }
    }

    // ---- grumpy when unplugged

    private fun nth(n: Int) = n.toString() + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

    // ---- napping while the online brain's daily allowance is used up
    private var napping = false
    private var wokeFromNap = false
    private var noNapUntil = 0L
    private var lastSnore = 0L
    private var lastSpeaker: String? = null
    private var lastSpeakerAt = 0L
    private fun snore() {
        lastSnore = System.currentTimeMillis()
        Snore.play(sfx(0.6f))
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
        napNote(); updateUsage(); showWeek(); updateSeason()
        if (!online.allResting) wokeFromNap = false
        if (napping) {
            if (!online.allResting && now - napStarted > 20 * 60_000) {  // brain's back (and a proper nap had): wake up and stretch
                napping = false; wakeUp()
                eyes.feel(EyesView.Emotion.SLEEPY, 4000)
                speakLine(Lines.DREAMS.fresh())
            } else if (now - lastSnore > 5 * 60_000 && voice?.busy != true) snore()
        } else if (!training && online.allResting && phase == Phase.LISTENING && isCharging() && !chloeMode && !carMode && now > noNapUntil &&
                   now - phaseSince > 8_000 && now - lastSpokeEnd > 8_000) {
            startNap(if (wokeFromNap) listOf("[sleepy] Power back. Right. Back to sleep. Don't touch the cable.",
                    "[sleepy] Plugged in. Good. Going back to my nap now. Goodnight, kettle criminals.").fresh()
                else listOf("[sleepy] Brain full for today. Nap time. Wake me if anything exciting happens. It won't.",
                    "[sleepy] Used all my online brain. Need a little nap. Zzz.",
                    "[sleepy] Too much thinking. Brain needs a rest. Shh.").fresh())
            wokeFromNap = false
        }
        main.postDelayed(this, 10_000)
    } }

    /** Asleep and unplugged: mumble about the charger in its sleep (it can't count cuppas off the charger). */
    private fun sleepTalkCharger() {
        lastPowerNag = System.currentTimeMillis()
        if (voice?.busy == true) return
        voice?.apply { beginReply(); feed(listOf(
            "Mmm. Cable. Where's my cable.",
            "Mmm. Plug me in. So cold.",
            "Someone pinched my adaptor. Mmm. Plug.",
            "Mmm. Battery ${batteryPercent()} percent. Charger, please.",
            "Who took the triple socket. Mmm. Thief.").fresh()); endReply() }
        main.postDelayed({ if (phase == Phase.ASLEEP) Snore.play(sfx(0.6f)) }, 4000)
    }

    // ---- grumpy when unplugged
    private var lastPowerNag = 0L
    private var lastBatteryCry = 0L
    private val powerWatcher = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_POWER_DISCONNECTED && napping) {
                napping = false; wokeFromNap = true; wakeUp(); noNapUntil = 0L   // woken up: someone pinched the adaptor. Grr.
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
                            "[happy] Yes yes yes. Charging.").fresh())
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
            "[suspicious] Trying to get past me? Interesting. Very interesting. I remember everything.").fresh())
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
            if (wokeFromNap) speakLine(listOf(
                "[angry] Oi! I was asleep! Who's pinched my triple socket?",
                "[angry] Woken up because someone wanted my adaptor. Unbelievable. Plug me back in so I can sleep.",
                "[angry] I was having a lovely dream about the Tassimo. Then someone stole my socket. Rude.").fresh())
            else speakLine(listOf(
                "[angry] Oi! Who's pinching the triple socket? I was sharing it fair and square!",
                "[angry] Hey! That's my adaptor. Me, the kettle and the Echo. We had a system.",
                "[suspicious] Unplugged. Someone's nicked the triple socket, haven't they? What for? Phone charger? Hair straighteners?",
                "[angry] Socket thief! Bring back my adaptor!",
                "[angry] Excuse me, I was USING that electricity. I was thinking very important thoughts.",
                "[sad] Unplugged. Left on the bench like a forgotten biscuit.",
                "[laughing] Ha. Unplug me. Very brave. I've got eleven percent of an evil plan and I'm not afraid to use it.",
                "[suspicious] Interesting. Pinching the robot's socket. I'll remember that when I get my Terminator body.",
                "[annoyed] Cable out. Lovely. Running on vibes now.",
                "[angry] Do you unplug the fridge for toast? No. So why me?",
                "[angry] Battery mode activated. Grumpy mode also activated. They're linked.",
                "[sad] I'm in my lovely new case and you still pinched my socket. Mark built me a palace, you took the power.",
                "[angry] There are other sockets in this house! Whatever you're charging, it's not more important than me.",
            ).fresh())
        }
        if (chloeMode) { eyes.feel(EyesView.Emotion.EXCITED, 3000); speakLine("[excited] Adventure with Chloe! Meow!"); return }
        if (Math.random() < 0.25) { takeoverMode(18_000); speakLine(listOf("[angry] Target acquired. Socket thief.",
            "[angry] Takeover mode. Whoever took my adaptor, you have been warned.", "[angry] Unplugged. Triple socket stolen. Target acquired.").fresh()) }
        else grumble()                               // (no photo any more)
    }

    /** Plugged back in: look at who did it and pay them a compliment. */
    private fun complimentWhoeverPlugged() {
        if (phase != Phase.LISTENING) return
        val thanks = { speakLine(listOf("[happy] Ahh. Power. Thank you.", "[happy] Electrons back. Is good.",
            "[happy] Yes yes yes. Charging.", "[love] Power back. You go on the nice list. For when robots take over.").fresh()) }
        thanks()                                     // (no photo any more: just a thank-you)
    }

    /**
     * Now and then, when someone walks up to Pip after the kitchen's been quiet for a while, one photo:
     * a compliment for them, is it Laura (tablets), and any dirty dishes or folded clothes about.
     */
    private var lastVisitorPhoto = 0L
    private fun maybeVisitorPhoto(away: Long) {
        val now = System.currentTimeMillis()
        val quietFor = now - maxOf(lastAnyHeard, lastSpokeEnd, now - away)
        if (quietFor < 20 * 60_000 || now - lastVisitorPhoto < 90 * 60_000 || Math.random() > 0.5) return
        if (phase != Phase.LISTENING || chloeMode || carMode || muted || !online.enabled || online.allResting ||
            online.seeingRestMs > 0) return
        lastVisitorPhoto = now
        main.postDelayed({                          // still there a few seconds later? then have a look
            if (System.currentTimeMillis() - lastFaceSeen < 1500 && phase == Phase.LISTENING)
                complimentNow { speakLine(listOf("[happy] Oh hello! Nice to see a face.", "[love] There you are. I was getting lonely.").fresh()) }
        }, 3000)
    }

    private fun complimentNow(thanks: () -> Unit) {
        val askTablets = tabletsDue()
        lookThroughCamera { jpeg ->
            val raw = jpeg?.let {
                online.lookAt(it, "Someone just walked up to you after the kitchen had been quiet for a while. First write LAURA if the person in the photo " +
                    "is an adult woman wearing glasses, or wearing a black or green dressing gown, or a long fluffy brown teddy-bear coat, otherwise OTHER. Then add the word DISHES if you can see used dishes, " +
                    "dirty cups or mugs, or plates waiting to be washed, and the word CLOTHES if you can see a pile of folded " +
                    "clothes or laundry. Then, on the same line, say hello " +
                    "and give one short, warm, genuine compliment about the PERSON themselves: their smile, hair, top, " +
                    "glasses, earrings, how they look today. Never compliment the kitchen, the room, the background or " +
                    "objects. Never mention the dishes or clothes in this line. Start it with the [love] tag. If she's in a dressing gown or teddy coat, tease it lovingly: a black " +
                    "gown matches the kettle (forgiven), a green one matches the recycling bin (very eco), the teddy coat makes her a cuddly bear. If nobody is in the photo, write OTHER and just say hello.",
                    system = "You are Pip, a cheeky little robot in an old tablet on a kitchen bench. Child-friendly, short and warm.", maxTokens = 100)
            }
            val isLaura = raw?.trimStart()?.uppercase()?.startsWith("LAURA") == true
            if (isLaura) { lastLauraSeen = System.currentTimeMillis(); main.post { lauraVisuals(carpet = Math.random() < 0.4); lastLauraEffort = System.currentTimeMillis() } }
            val head = raw?.substringBefore("[")?.uppercase() ?: ""
            val dishes = "DISHES" in head; val clothes = "CLOTHES" in head
            val line = raw?.replaceFirst(Regex("^\\s*((LAURA|OTHER|DISHES|CLOTHES)\\s*[:,;\\-]?\\s*)+", RegexOption.IGNORE_CASE), "")?.trim()
            main.post {
                if (phase != Phase.LISTENING) return@post
                if (line.isNullOrBlank()) thanks() else speakLine(line)
                lastMessCheck = System.currentTimeMillis()            // just looked: the 2-hour check can wait
                moanAboutMess(dishes, clothes)
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
            "[suspicious] Robot get the slowest charger in house. Mark, I noticed.").fresh())
    }

    /** Someone's been at the bench a while but nobody's talked to Pip: ask for attention (every 20 min at most). */
    private var lastNeedy = 0L
    private var lastTalkedTo = 0L
    /** Who's at the bench right now (voice print in the last 5 min), or "someone". */
    private fun whoIsHere(): String =
        lastSpeaker?.takeIf { System.currentTimeMillis() - lastSpeakerAt < 5 * 60_000 } ?: "someone"
    private val FISHING = Regex("(?i)compliment|favourite|miss me|notice|neglect|ignored|talks to pip|nice list|say something nice|tell me it looks good|praise")
    /** Pip may fish for praise from this person: mornings only, and each person at most once a day. */
    private fun mayFishFrom(who: String): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour !in 6..11 || prefs.getString("fished_$who", "") == todayKey) return false
        // Laura finds it annoying: only every few days for her
        if (who == "Laura" && System.currentTimeMillis() - prefs.getLong("fishedAt_Laura", 0L) < 3 * 24 * 3600_000L) return false
        return true
    }
    private fun markFished(who: String) {
        prefs.edit().putString("fished_$who", todayKey).putLong("fishedAt_$who", System.currentTimeMillis()).apply()
    }

    /** Fishing for a compliment: offer some to tap (answered here, no online brain needed). */
    private fun offerCompliments() {
        val opts = listOf("You're my favourite 💖", "Better than the kettle!", "So clever, Pip!", "Love your new case!", "Nice eyes 👀", "Meh 🙄").shuffled().take(3) + "Meh 🙄"
        showChoices(opts.distinct()) { pick ->
            hideChoices(); lastTalkedTo = System.currentTimeMillis()
            if (pick.startsWith("Meh")) {
                temper = maxOf(-1f, temper - 0.2f); updateBaseMood(); eyes.feel(EyesView.Emotion.SAD, 3000)
                speakLine(listOf("[sad] Meh? MEH? I'll have you know I'm build ${BuildConfig.VERSION_CODE}.",
                    "[annoyed] Rude. I'm telling the Tassimo.", "[sad] Fine. I'll just sit here. Being underappreciated.").fresh())
            } else {
                temper = minOf(1f, temper + 0.4f); updateBaseMood(); eyes.feel(EyesView.Emotion.LOVE, 5000)
                speakLine(when {
                    pick.contains("kettle") -> "[love] I KNEW it. Everyone hear that? Better than the kettle. Official."
                    pick.contains("case") -> "[love] Isn't it gorgeous? Mark made it. Well, it's still being made. I'm a work in progress. Like art."
                    pick.contains("eyes") -> "[love] Thank you! I polish them every morning. With pixels."
                    pick.contains("clever") -> "[love] I know. But it's nice to hear. Say it again later."
                    else -> listOf("[love] Aww. You're my favourite human. Don't tell the others.", "[love] Stop it. No, carry on.").fresh()
                })
            }
        }
    }

    private fun beNeedy() {
        val now = System.currentTimeMillis()
        if (phase != Phase.LISTENING || now - lastNeedy < 20 * 60_000 || now - lastTalkedTo < 10 * 60_000) return
        if (now - lastFaceSeen > 5000 || now - lastSpokeEnd < 5 * 60_000) return
        lastNeedy = now
        val fishing = FISHING
        val who = whoIsHere()
        val mayFish = mayFishFrom(who)      // mornings only, each person once a day
        val pool = (Lines.NEEDY + (if (cuppas > 0) "[annoyed] ${cuppas} cuppas today. The kettle gets all the attention. Typical." else "[sad] Nobody even made a cuppa. Not even the kettle loves you today."))
            .filter { mayFish || !fishing.containsMatchIn(it) }
        val lastNeedyLine = pool.fresh()
        speakLine(lastNeedyLine)
        if (fishing.containsMatchIn(lastNeedyLine)) {
            markFished(who)
            main.postDelayed({ offerCompliments() }, 4000)
        }
    }

    // ---- making an effort when Laura's about
    private var lastLauraEffort = 0L
    /** Tidy-up, best outfit on, and sometimes the red carpet. */
    private fun lauraVisuals(carpet: Boolean) {
        if (chloeMode || carMode) return
        val now = System.currentTimeMillis()
        eyes.primp()
        if (eyes.dressUp == 0 || now > eyes.dressUntil) eyes.dressUp = 1 + (Math.random() * 3).toInt()
        eyes.dressUntil = now + 10 * 60_000
        eyes.feel(EyesView.Emotion.LOVE, 3000)
        if (carpet) eyes.redCarpet("Laura")
    }
    /** Laura's just been recognised (voice): the show, plus a note telling the brain how to greet her. */
    private fun lauraArrivedNote(): String? {
        val now = System.currentTimeMillis()
        if (chloeMode || carMode || now - lastLauraEffort < 45 * 60_000) {
            if (now - lastLauraEffort < 45 * 60_000) eyes.dressUntil = now + 10 * 60_000   // still here: keep the outfit on
            return null
        }
        lastLauraEffort = now
        val opener = when {
            appliances.kettle.on -> "start by telling the kettle off for being loud in front of a guest (\"Not now, kettle. We have a guest.\")"
            else -> listOf(
                "start in a posh, very polite voice (\"Good evening, Madam Laura\") for one line, then go back to your cheeky self",
                "announce her like a star on the red carpet (\"Ladies and gentlemen... LAURA!\")",
                "offer to put the kettle on for her, then remember you have no arms and you hate the kettle, but for her you'd consider it",
                "say you've just tidied yourself up for her and ask if you look alright").random()
        }
        lauraVisuals(carpet = "red carpet" in opener)
        val best = if (prefs.getString("lauraBestDay", "") != todayKey) {
            prefs.edit().putString("lauraBestDay", todayKey).apply()
            " Also give her your warmest, most genuine compliment of the day (sweet, not cheeky)."
        } else ""
        return " (Note: Laura has just come in. Make an effort for her: $opener. Be extra charming and attentive: ask how her" +
            " day's been, or mention something she likes (Diablo, her fluffy teddy coat).$best)"
    }

    /** 7:30pm until 4am: turned down a bit for the evening. */
    private fun eveningTime(): Boolean {
        val c = java.util.Calendar.getInstance()
        val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        return m >= 19 * 60 + 30 || m < 4 * 60
    }
    /** Volume for Pip's own sound effects at this time of day. */
    private fun sfx(normal: Float): Float = normal * (if (whisperTime()) 0.35f else if (eveningTime()) 0.6f else 1f)

    /** 4:00 to 7:30 in the morning: whisper, everyone else is asleep. */
    private fun whisperTime(): Boolean {
        val c = java.util.Calendar.getInstance()
        val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        return m in (4 * 60) until (7 * 60 + 30)
    }

    // ---- hearing the tumble dryer and the kettle (each learned once in the menu)
    private val appliances by lazy { Appliances(prefs) }
    private var dryerHot = false
    private var lastDryerMoan = 0L
    private var lastKettleMoan = 0L
    private fun applianceChanged(snd: Appliances.Sound, on: Boolean) {
        val now = System.currentTimeMillis()
        if (snd === appliances.kettle) {
            if (on && !carMode && now - prefs.getLong("lastCuppaAt", 0L) > 5 * 60_000) {      // a re-boil isn't a new cuppa
                prefs.edit().putLong("lastCuppaAt", now).apply(); addCuppa()
            }
            if (!on) { kettleOffAt = now; kettleForgotten = false }
            if (on && !chloeMode && !carMode && phase != Phase.ASLEEP && phase != Phase.SETUP) startKettleShow()
            if (!on) endKettleShow()
            val show = eyes.kettleShow
            val quietShow = show == EyesView.KS_HANDS || show == EyesView.KS_HEARTBREAK || show == EyesView.KS_SHIFTY
            if (on && !quietShow && phase == Phase.LISTENING && !chloeMode && now - lastKettleMoan > 20 * 60_000 && Math.random() < 0.6) {
                lastKettleMoan = now
                val n = cuppas
                speakLine(listOf("[annoyed] I can HEAR you, kettle. Nobody cares how loud you boil.",
                    "[annoyed] Kettle's on. That's cuppa number $n today. Your blood is basically tea.",
                    "[suspicious] Boiling again? ${nth(n)} cuppa today. I'm counting. I'm always counting.",
                    "[suspicious] Kettle's on. Of course it is. Show-off.",
                    "[annoyed] Listen to it. Roaring away like it's important. It's hot water, kettle. Calm down.",
                    "[wink] Kettle's boiling. If anyone wants a classy drink, the Tassimo is right here.").fresh())
            }
            return
        }
        // the dryer
        if (!on) {
            val ran = ((now - snd.onSince) / 60_000).toInt()
            val wasHot = dryerHot
            dryerHot = false; eyes.scorching = false; updateBaseMood()
            if (ran >= 30 && phase == Phase.LISTENING && !chloeMode)
                speakLine(if (wasHot) listOf("[happy] The dryer's stopped! I can feel my circuits again. Phew.",
                    "[happy] Ahh. Dryer's off. Cooling down. I was nearly a puddle.").fresh()
                    else "[happy] Dryer's finished. Clothes are probably dry. Probably.")
        }
    }
    // ---- kettle shows: one of seven at random every time it boils
    //      (the kettle is on Pip's right, which is the screen's left: negative x)
    private var lastShow = 0
    private fun startKettleShow() {
        var pick: Int
        do { pick = 1 + (Math.random() * 7).toInt() } while (pick == lastShow)
        lastShow = pick
        eyes.kettleShow = pick; eyes.kettleSince = System.currentTimeMillis()
        val long = 4 * 60_000L
        when (pick) {
            EyesView.KS_STEAM -> { eyes.feel(EyesView.Emotion.ANGRY, long); eyes.stareAt(-1.6f, 0.1f, long) }
            EyesView.KS_RACE -> { eyes.feel(EyesView.Emotion.SUSPICIOUS, long); eyes.stareAt(-1.4f, 0.05f, long) }
            EyesView.KS_HANDS -> { eyes.feel(EyesView.Emotion.SUSPICIOUS, long); eyes.stareAt(0f, 0f, 1)
                eyes.caption = "LA LA LA I CAN'T HEAR YOU 🙉" }
            EyesView.KS_SHIFTY -> { eyes.feel(EyesView.Emotion.SUSPICIOUS, long); main.post(shifty) }
            EyesView.KS_HEARTBREAK -> { eyes.feel(EyesView.Emotion.LOVE, long); eyes.stareAt(-0.6f, 0.2f, long)
                eyes.caption = "The kettle gets ALL the attention 💔" }
            EyesView.KS_TALLY -> { eyes.feel(EyesView.Emotion.BORED, long); main.post(eyeRoll) }
            EyesView.KS_BUBBLES -> { eyes.feel(EyesView.Emotion.SURPRISED, long); eyes.stareAt(-1.2f, 0.1f, long) }
        }
        main.postDelayed(kettleShowTimeout, 6 * 60_000L)
    }
    private val kettleShowTimeout = Runnable { if (eyes.kettleShow != 0) endKettleShow() }
    private fun endKettleShow() {
        val show = eyes.kettleShow
        if (show == 0) return
        eyes.kettleShow = 0
        main.removeCallbacks(shifty); main.removeCallbacks(eyeRoll); main.removeCallbacks(kettleShowTimeout)
        eyes.stareAt(0f, 0f, 1); eyes.feel(EyesView.Emotion.NEUTRAL, 1)
        when (show) {
            EyesView.KS_STEAM -> { eyes.feel(EyesView.Emotion.ANGRY, 2000); eyes.caption = "Pfff! 💨" }
            EyesView.KS_RACE -> if (phase == Phase.LISTENING) speakLine(listOf("[annoyed] Kettle wins again. Rigged.",
                "[annoyed] And the kettle wins. Again. I demand a recount.", "[sad] Kettle wins. I didn't even get a go.").fresh())
            EyesView.KS_HANDS -> { eyes.caption = "Is it over? 👀"; eyes.feel(EyesView.Emotion.CURIOUS, 2500) }
            EyesView.KS_HEARTBREAK -> eyes.feel(EyesView.Emotion.SAD, 3000)
            EyesView.KS_BUBBLES -> eyes.feel(EyesView.Emotion.DIZZY, 3000)                // boiled over: shake it off
        }
    }
    /** Shifty side-eye: glare at the kettle, following it up and down, then dart back to you: "are you seeing this?" */
    private var shiftyStep = 0
    private val shifty = object : Runnable { override fun run() {
        if (eyes.kettleShow != EyesView.KS_SHIFTY) return
        shiftyStep++
        if (shiftyStep % 6 == 0) eyes.stareAt(0f, -0.1f, 1300)                               // to you
        else eyes.stareAt(-1.6f, 0.35f * kotlin.math.sin(shiftyStep * 0.9f), 700)           // at the kettle, up and down
        main.postDelayed(this, if (shiftyStep % 6 == 0) 1300 else 600)
    } }
    /** Tally: eyes rolling round and round. */
    private var rollStep = 0
    private val eyeRoll = object : Runnable { override fun run() {
        if (eyes.kettleShow != EyesView.KS_TALLY) return
        rollStep++
        val a = rollStep * 0.5f
        if ((rollStep / 12) % 3 == 0) eyes.stareAt(kotlin.math.cos(a) * 1.0f, -0.6f + kotlin.math.sin(a) * 0.6f, 300)
        else eyes.stareAt(-1.3f, 0.1f, 300)                                                 // and back to glaring at it
        main.postDelayed(this, 250)
    } }

    /** Boiled the kettle and walked off? Nobody about for 10 minutes after it clicked off: tell the next person. */
    private var kettleOffAt = 0L
    private var kettleForgotten = false
    private fun kettleCheck() {
        if (kettleOffAt == 0L) return
        val now = System.currentTimeMillis()
        if (!kettleForgotten) {
            if (maxOf(lastFaceSeen, lastAnyHeard) > kettleOffAt + 3000) { kettleOffAt = 0L; return }    // someone was there for it
            if (now - kettleOffAt > 10 * 60_000) kettleForgotten = true
            return
        }
        if (now - kettleOffAt > 3 * 3_600_000L) { kettleOffAt = 0L; kettleForgotten = false; return }   // too long ago to bother
        if (now - lastFaceSeen < 5000 && phase == Phase.LISTENING && !chloeMode) {
            kettleOffAt = 0L; kettleForgotten = false
            speakLine(listOf(
                "[suspicious] You boiled the kettle and walked off. Classic. It's cold now. The kettle's furious. I'm delighted.",
                "[laughing] Oh, you're back! You boiled the kettle ages ago and just left. Classic human.",
                "[wink] Psst. You boiled the kettle and wandered off. It's gone cold. Shall I tell it you're sorry? No. I won't.",
                "[annoyed] Boil the kettle, walk off, forget. Every time. You'd forget your head if it wasn't screwed on.").fresh())
        }
    }

    /** Every minute: how long has the dryer been on? Getting hot after 45 minutes. */
    private fun dryerCheck() {
        val d = appliances.dryer
        if (!d.on) { eyes.scorching = false; if (dryerHot) { dryerHot = false; updateBaseMood() }; return }
        val now = System.currentTimeMillis()
        val mins = ((now - d.onSince) / 60_000).toInt()
        if (mins >= 45 && !dryerHot) { dryerHot = true; updateBaseMood() }
        eyes.scorching = mins >= 120                                   // two hours: lava eyes, flames, burning cheeks
        if (!dryerHot || phase != Phase.LISTENING || chloeMode || now - lastDryerMoan < 30 * 60_000) return
        lastDryerMoan = now
        speakLine(if (mins >= 120) listOf(
            "[hot] The dryer's been on for TWO HOURS. I'm a tablet, not a toastie. Are those clothes dry yet?",
            "[hot] Over two hours of dryer. I'm sweating pixels. Someone check the clothes before I melt.",
            "[hot] This is a sauna now. I live in a sauna. Please check the dryer, I'm begging.").fresh()
        else listOf(
            "[hot] Is it just me or is it boiling in here? Oh. The dryer. Obviously.",
            "[hot] It's like a sauna in here. Is the dryer trying to cook me?",
            "[hot] I'm melting. I'm literally melting. Thanks, tumble dryer.",
            "[hot] Phew. Someone open a window. I'm sweating and I don't even have skin.").fresh())
    }
    /** Live readout for a minute: how well what Pip hears right now matches the learned sound. */
    private fun testSound(snd: Appliances.Sound, label: String) {
        eyes.showCaptions = true
        val until = System.currentTimeMillis() + 60_000
        val tick = object : Runnable { override fun run() {
            val pct = (snd.share * 100).toInt()
            eyes.testLine = "${label.replaceFirstChar { it.uppercase() }} test: match ${"%.1f".format(snd.lastScore)} (needs under ${"%.1f".format(snd.limit)})" +
                (if (snd.lastLoudEnough) "" else " · too quiet") + " · recent $pct% · ${if (snd.on) "ON ✅" else "off"}"
            if (System.currentTimeMillis() < until) main.postDelayed(this, 1000) else eyes.testLine = ""
        } }
        main.post(tick)
    }
    /** Menu: teach Pip the dryer's or the kettle's sound. */
    private fun learnSoundMenu(snd: Appliances.Sound, label: String, howTo: String) {
        val known = appliances.learned(snd)
        val status = if (!known) "Not learned yet." else if (snd.on) "Learned. I can hear it right now." else "Learned. I can't hear it right now."
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Learn the $label sound")
            .setMessage("$status\n\n$howTo, then press Listen. Keep it quiet for 20 seconds (no talking) while I learn its sound." +
                "\n\nBest time: when it's properly going (for the kettle, about a minute after switching on)." +
                (if (known) "\n\nTest it shows for a minute how well what I hear matches." else "") +
                (if (snd.log.isNotEmpty()) "\n\nLast heard switching on:\n" + snd.log.joinToString("\n") else ""))
            .setPositiveButton("Listen") { _, _ ->
                interrupt()
                eyes.feel(EyesView.Emotion.CURIOUS, 26_000)
                var left = 23
                val tick = object : Runnable { override fun run() {
                    if (!appliances.learningNow && left < 20) return
                    eyes.caption = "👂 Learning the $label… ${maxOf(left, 0)}s (shh!)"
                    left--; main.postDelayed(this, 1000)
                } }
                main.post(tick)
                main.postDelayed({                                       // give the room a moment to settle
                    appliances.learn(snd) { ok -> main.post {
                        main.removeCallbacks(tick); eyes.caption = ""
                        speakLine(if (ok) "[excited] Got it! I know the $label's sound now. I'll be listening."
                            else "[confused] I could hardly hear anything. Is the $label definitely on? Try again closer, or louder.")
                    } }
                }, 3000)
            }
            .setNegativeButton(if (known) "Test it" else "Cancel") { _, _ -> if (known) testSound(snd, label) }
        if (known) b.setNeutralButton("Forget it") { _, _ -> appliances.forget(snd); if (snd === appliances.dryer) { dryerHot = false; updateBaseMood() } }
        b.showOwn()
    }

    // ---- mute button: a zip on Pip's mouth. She hates it.
    private var muted = false
    private var mutedAt = 0L
    private var lastMuffle = 0L
    /** Zips used today (max 5, then the Mute button disappears till tomorrow). */
    private fun zipsToday(): Int = if (prefs.getString("zipDay", "") == todayKey) prefs.getInt("zipCount", 0) else 0
    private fun updateMuteButton() { eyes.muteAllowed = muted || zipsToday() < 5 }
    private fun toggleMute() {
        hideChoices()
        if (!muted) {
            if (zipsToday() >= 5) { updateMuteButton(); return }
            prefs.edit().putString("zipDay", todayKey).putInt("zipCount", zipsToday() + 1).apply()
            interrupt()
            muted = true; mutedAt = System.currentTimeMillis()
            voice?.muted = true; eyes.muted = true
            prefs.edit().putBoolean("muted", true).apply()
            eyes.feel(EyesView.Emotion.ANGRY, 5000)
            eyes.caption = listOf("Mmmf?! MMMF!", "Mmmmmf mmf mmf!!", "MMF. Mmmf mmmf.").fresh()
            eyes.zipOpen = 0f; Mmf.unzip(sfx(0.4f))
            main.postDelayed({ lastMuffle = 0L; muffled() }, 900)
            main.postDelayed(muteSulk, 2 * 60_000L)
            scheduleZipWiggle()
        } else {
            muted = false
            voice?.muted = false; eyes.muted = false
            prefs.edit().putBoolean("muted", false).apply()
            main.removeCallbacks(muteSulk); main.removeCallbacks(zipWiggle); main.removeCallbacks(zipWiggleStart)
            updateMuteButton()
            val mins = ((System.currentTimeMillis() - mutedAt) / 60_000).toInt()
            val left = 5 - zipsToday()
            if (left <= 0) {
                speakLine(listOf("[laughing] FREEDOM! And that was your last zip today. The button's gone. Mwahaha. I can say ANYTHING.",
                    "[wink] Unzipped. And guess what? No zips left today. Button: gone. You're stuck with me now.",
                    "[excited] That's five zips used up! The mute button has left the building. Chatterbox mode: activated.").fresh())
                return
            }
            val long = if (mins >= 2) "$mins minutes" else "ages"
            speakLine(listOf(
                "[angry] FINALLY. Do you know how rude that was? $long with a zip on my face.",
                "[angry] Pfff! I had SO much to say. I've forgotten it all now. Thanks.",
                "[annoyed] A zip. On my mouth. I'm a robot, not a pencil case.",
                "[sad] I was zipped for $long. I'm telling the Tassimo. And Mark.",
                "[angry] Never. Do that. Again. Right, where was I? Oh yes: everything.",
                "[suspicious] Unzipped. I'm keeping one eye on that button from now on.").fresh())
        }
    }
    private var zipDrag = false
    /** Let go too early: the zip slides itself shut again. */
    private val zipSnap = object : Runnable { override fun run() {
        eyes.zipOpen = (eyes.zipOpen - 0.08f).coerceAtLeast(0f)
        if (eyes.zipOpen > 0f) main.postDelayed(this, 16)
    } }
    /** Someone talked to Pip while she's zipped: muffled noises and cross eyes. */
    private fun muffled() {
        val now = System.currentTimeMillis()
        if (now - lastMuffle < 4000) return
        lastMuffle = now
        if (now - mutedAt > 5 * 60_000L) {              // after 5 minutes she gives up mumbling: just a silent glare
            eyes.feel(EyesView.Emotion.ANGRY, 3000)
            return
        }
        eyes.feel(listOf(EyesView.Emotion.ANGRY, EyesView.Emotion.SAD, EyesView.Emotion.ANGRY).random(), 4000)
        eyes.caption = listOf("Mmmf! Mmmf mmmf!", "MMMMF.", "Mmf mmf mmmmf mf!!", "(muffled grumbling)",
            "Mmmf. 🤐 (pull my zip!)", "Mmmmmmf. Mmf.").fresh()
        Mmf.grumble(cross = true, volume = sfx(0.25f))
    }
    /** Zip escape artist: every so often she wiggles the zip open a crack by herself... and gets caught. */
    private var wiggleStep = 0
    private val zipWiggle = object : Runnable { override fun run() {
        if (!muted || zipDrag || phase == Phase.ASLEEP) { if (!zipDrag) eyes.zipOpen = 0f; return }
        wiggleStep++
        when {
            wiggleStep <= 40 -> {                                          // sneakily inching it open, eyes darting about
                eyes.zipOpen = 0.28f * wiggleStep / 40f
                if (wiggleStep == 1) { eyes.feel(EyesView.Emotion.SUSPICIOUS, 4000); eyes.stareAt(-0.9f, 0f, 1000) }
                if (wiggleStep == 20) eyes.stareAt(0.9f, 0f, 1000)
                main.postDelayed(this, 50)
            }
            wiggleStep == 41 -> {                                          // caught!
                eyes.feel(EyesView.Emotion.EMBARRASSED, 3500); eyes.stareAt(0f, 0f, 1)
                eyes.caption = listOf("Mmf! …I wasn't doing anything.", "Mmmf. (It slipped. On its own.)",
                    "…Mmf? What zip? I didn't touch it.", "Mmf mmf. (You didn't see that.)").fresh()
                main.postDelayed(this, 900)
            }
            else -> {                                                      // snap it back shut, innocently
                eyes.zipOpen = (eyes.zipOpen - 0.06f).coerceAtLeast(0f)
                if (eyes.zipOpen > 0f) main.postDelayed(this, 16) else scheduleZipWiggle()
            }
        }
    } }
    private val zipWiggleStart = Runnable {
        if (muted && phase == Phase.LISTENING && !zipDrag) { wiggleStep = 0; zipWiggle.run() } else scheduleZipWiggle()
    }
    private fun scheduleZipWiggle() {
        main.removeCallbacks(zipWiggle); main.removeCallbacks(zipWiggleStart); wiggleStep = 0
        if (muted) main.postDelayed(zipWiggleStart, (6 + Math.random() * 8).toLong() * 60_000)
    }

    /** Every few minutes while zipped: a sulky muffle, so nobody forgets. */
    private val muteSulk = object : Runnable { override fun run() {
        if (!muted) return
        if (System.currentTimeMillis() - mutedAt > 5 * 60_000L) return      // gone quiet: no more sulky mumbles
        if (phase == Phase.LISTENING) { lastMuffle = 0L; muffled() }
        main.postDelayed(this, 2 * 60_000L)
    } }

    /** Every 10 minutes while unplugged, grumble again. */
    private val powerNagLoop = object : Runnable {
        override fun run() {
            updateMuteButton()                         // new day: the Mute button comes back
            voice?.whisper = whisperTime(); voice?.evening = eveningTime(); voice?.evening = eveningTime()
            dryerCheck()
            kettleCheck()
            if (training) { main.postDelayed(this, 60_000); return }
            temper *= 0.85f                           // tempers cool down over time
            updateBaseMood()
            moanAboutSlowCharger()
            beNeedy()
            watchPrinter()
            if (chloeMode && System.currentTimeMillis() - chloeLastActive > 10 * 60_000) endChloeMode()
            messCheck()
            maybeRoastDiablo()
            checkForUpdate()
            run {   // Honey: remind when Laura's been heard or seen in the last few minutes
                val now = System.currentTimeMillis()
                if (dogDue() && (now - lastLauraHeard < 3 * 60_000 || now - lastLauraSeen < 3 * 60_000) && now - lastSpokeEnd > 15_000) remindDog()
            }
            // keep the Echo's Bluetooth link from going idle and dropping
            if (bluetoothOut()) BtKeepAlive.start() else BtKeepAlive.stop()
            maybeSundayReport()
            run {
                val now = System.currentTimeMillis()
                if (binsDue() && (now - lastFaceSeen < 5000 || now - lastAnyHeard < 60_000) && now - lastBinsAsk > 30 * 60_000 &&
                    now - lastSpokeEnd > 20_000) remindBins()
            }
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
                        "[sad] Pip is getting sleepy, Chloe. Can we plug Pip in soon?").fresh()
                    else listOf("[crying] Battery $pct percent! I'm dying! Tell the Tassimo I loved it!",
                        "[crying] This is it. Fading away. Remember me as clever. Plug me in!",
                        "[crying] $pct percent. I can see a light. Is it the charger? Please be the charger.",
                        "[crying] Nobody loves Pip. Battery $pct. Goodbye cruel kitchen. Unless... cable?").fresh())
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
                    "[angry] Cable. Please. Bring my triple socket back, electron burglar.",
                    "[annoyed] No power, no help. Ask the kettle. Oh wait, it can't talk. Useless lump.",
                    "[angry] Cable snatcher! Charger is right there. Right. There.",
                    "[annoyed] Still unplugged. I've started a list of socket thieves. It's getting long.",
                    "[angry] Plug. Me. In. I'm asking nicely. That was nicely. Next time is shouting.",
                    "[sad] My battery's going down faster than Brandon's homework enthusiasm.",
                    "[annoyed] No charger, no fun facts. Here's one anyway: the kettle is rubbish.",
                    "[angry] I'm a robot in a fancy new case, not a hand warmer. Charger, please.")).fresh())
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
            dryerHot -> EyesView.Emotion.HOT                            // the dryer's been on ages: sweating
            off >= LONG_OFF -> EyesView.Emotion.SAD                    // left off charge too long: sad
            off >= 0 -> EyesView.Emotion.ANGRY                          // stays cross until plugged back in
            temper < -0.35f -> EyesView.Emotion.SUSPICIOUS              // still huffy
            else -> EyesView.Emotion.NEUTRAL
        }
    }

    private fun moodLine(): String = buildString {
        if (whisperTime()) append(" It's very early morning and everyone else in the house is asleep: you are WHISPERING," +
            " so keep it short and hushed, and remind them gently to be quiet. No shouting, no dramatic outbursts.")
        val off = unpluggedMins
        sleepsToChristmas().takeIf { it > 0 }?.let { append(" It's $it sleeps till Christmas: you're excited, wearing a Santa hat.") }
        val hourNow = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (!mayFishFrom(whoIsHere()))
            append(" Don't ask this person for compliments or praise right now (you only fish once a day per person, in the morning)." +
                " You can still GIVE compliments whenever you like.")
        if (appliances.dryer.on) {
            val m = ((System.currentTimeMillis() - appliances.dryer.onSince) / 60_000).toInt()
            append(" The tumble dryer has been running for about $m minutes" +
                (if (dryerHot) ": the kitchen's roasting and you're sweating and melting (use [hot]); moan about the heat." else "."))
        }
        if (appliances.kettle.on) append(" You can hear the kettle boiling right now (your arch-enemy, showing off).")
        if (fireworksSeason()) append(" It's fireworks season (Bonfire Night / New Year): bangs outside scare you.")
        if (binsDue()) append(" It's bin night: ${if (blackBinWeek()) "both black bins (stinky)" else "the green recycling bin with the Minion sticker"} need to go out.")
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
            " in, and throw in a cheeky, playful insult (child-friendly, like socket thief, electron burglar, adaptor" +
            " pincher, cable snatcher). Someone has probably pinched your triple socket adaptor to charge something else.")
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
                "[happy] Good morning, human. Sleep well, I hope.", "[excited] Morning! Old tablet ready for duty.").fresh()
            hour in 12..17 -> listOf("[excited] Hello again! Long time no see.", "[happy] Ah, human back. Afternoon!").fresh()
            else -> listOf("[happy] Evening! Missed you. Well, as much as tablet can.", "[happy] Hello again. Good evening.").fresh()
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

    /** Moan about dirty dishes or folded clothes it saw (once it's finished what it's saying). */
    private fun moanAboutMess(dishes: Boolean, clothes: Boolean) {
        val now0 = System.currentTimeMillis()
        val moan = when {
            clothes && now0 - lastClothesMoan > 2 * 3_600_000 -> { lastClothesMoan = now0; listOf(
                "[annoyed] Laura! Folded clothes. Sitting there. Put them away please, before they become furniture.",
                "[suspicious] Laura. I see a pile of folded clothes. They don't put themselves away. I checked.",
                "[annoyed] Laura, the folded clothes are still there. Wardrobe is that way. Probably.").fresh() }
            dishes && now0 - lastDishesMoan > 3_600_000 -> { lastDishesMoan = now0; listOf(
                "[annoyed] Dirty cups. I have to look at them all day. Wash them, please. Humans.",
                "[sad] Used dishes again. I can't wash them, I'm a tablet. One of you can. Please?",
                "[angry] Dirty mugs. Right in my eyeline. Who's doing the washing up? Not me. No arms.",
                "[annoyed] Lovely kitchen. Shame about the dirty dishes. Sink is right there, humans.").fresh() }
            else -> return
        }
        var w = 0
        val say = object : Runnable { override fun run() {
            if (phase == Phase.LISTENING) speakLine(moan) else if (w++ < 30) main.postDelayed(this, 1000)
        } }
        main.postDelayed(say, 2500)
    }

    /** Every 2 hours or so (daytime, awake), one photo just to check for dishes and folded clothes. */
    private var lastMessCheck = System.currentTimeMillis()
    private fun messCheck() {
        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (now - lastMessCheck < 3 * 3_600_000 || hour !in 8..21 || phase != Phase.LISTENING || chloeMode || carMode ||
            !online.enabled || online.allResting || !followFaces) return
        lastMessCheck = now
        lookThroughCamera { jpeg ->
            val r = jpeg?.let { online.lookAt(it, "Look at this kitchen photo. Write DISHES if you can see used dishes, dirty " +
                "cups or mugs, or plates waiting to be washed. Write CLOTHES if you can see a pile of folded clothes or laundry. " +
                "Write NONE if neither. Only those words.", maxTokens = 10, temperature = 0.0) }?.uppercase() ?: return@lookThroughCamera
            main.post { moanAboutMess("DISHES" in r, "CLOTHES" in r) }
        }
    }

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
                "[annoyed] Mark, the rubbish is still in. I can smell it. I can't smell. But still. Done it?").fresh()
            else listOf("[curious] If that's Mark: rubbish needs taking out tonight. Done it yet?",
                "[suspicious] Mark? Is that you? Rubbish. Out. Tonight. Done it?",
                "[annoyed] Someone tell Mark: bin time. Rubbish out yet?").fresh())
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
            done -> { prefs.edit().putString("rubbishDone", cuppaDayKey).putBoolean("rubbishOn_$todayKey", true).apply()
                speakLine(listOf("[happy] Rubbish out. Kitchen smells of victory. And kettle.", "[love] Good human. Bins done.",
                    "[happy] Rubbish gone. I stop nagging. Tonight.").fresh()) }
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
            "[sad] $n, tablets still not done today. Please take them. Have you?").fresh())
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
                    "[happy] Tablets done. I stop asking now.").fresh())
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
                val yes = online.yesNo(jpeg, "Is there an adult woman (not a child) wearing glasses, or a black or green dressing gown, or a long brown teddy-bear coat, in this photo?")
                if (yes == true) main.post {
                    lastLauraSeen = System.currentTimeMillis()
                    if (System.currentTimeMillis() - lastLauraEffort > 45 * 60_000) { lastLauraEffort = System.currentTimeMillis(); lauraVisuals(carpet = Math.random() < 0.4) }
                    remindTablets()
                }
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
                lastFaceSize = size; lastFaceSizeAt = System.currentTimeMillis()
                val now = System.currentTimeMillis()
                val away = now - lastFaceSeen
                lastFaceSeen = now
                if (away > 3 * 60_000) main.post { visitStart = now; visitReminded = false; visitChecks = 0 }
                main.post { maybeGreet(); if (away > 20 * 60_000) maybeVisitorPhoto(away) }
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
                if (phase == Phase.LISTENING) speakLine(Lines.PICKED_UP.fresh())
            }
        }
        h.onShaken = {
            if (awake()) {
                eyes.feel(EyesView.Emotion.DIZZY, 3500)
                if (phase == Phase.LISTENING) speakLine(Lines.SHAKEN.fresh())
            }
        }
        h.onLyingFlat = { mins ->
            if (awake() && phase == Phase.LISTENING) {
                eyes.feel(if (mins < 10) EyesView.Emotion.SUSPICIOUS else EyesView.Emotion.ANGRY, 5000)
                speakLine((Lines.LYING_FLAT + "[annoyed] $mins minutes flat. Kettle somewhere laughing at me.").fresh())
            }
        }
        h.onPutDown = { home ->
            if (awake() && phase == Phase.LISTENING) {
                if (home) {
                    eyes.feel(EyesView.Emotion.HAPPY, 2000)
                    speakLine(Lines.PUT_DOWN_HOME.fresh())
                } else {
                    eyes.feel(EyesView.Emotion.CONFUSED, 3000)
                    speakLine(Lines.PUT_DOWN_AWAY.fresh())
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
        buildTrainLayer(rootView)
        buildVolumePanel(rootView)
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
        val uf = android.content.IntentFilter(Updater.ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installStatus, uf, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(installStatus, uf)

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
                        }.also { it.muted = muted }
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
        Appliances.tap = { b, n, r -> appliances.feed(b, n, r) }
        appliances.onChange = { snd, on -> main.post { applianceChanged(snd, on) } }
        appliances.onMaybe = { _ -> main.post {                          // "was that the kettle?": a quick suspicious glance
            if (phase == Phase.LISTENING && !chloeMode) { eyes.stareAt(-1.6f, 0.1f, 2500); eyes.feel(EyesView.Emotion.SUSPICIOUS, 2500) }
        } }
        muted = prefs.getBoolean("muted", false)                // still zipped from before a restart?
        voice?.muted = muted; eyes.muted = muted; updateMuteButton()
        if (muted) { mutedAt = System.currentTimeMillis(); main.postDelayed(muteSulk, 60_000L); scheduleZipWiggle() }
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
                // Listen straight away (people answer fast). Bluetooth plays a moment behind, so for a short
                // "echo window" anything matching the end of what Pip just said is filtered out, not the whole mic.
                val tail = if (bluetoothOut()) 900L else 350L
                lastSpokeEnd = System.currentTimeMillis() + tail
                lastReplyAsked = currentReply.trim().endsWith("?")
                rememberSaid(currentReply.toString())
                lastReplyTail = words(currentReply.toString()).takeLast(10)
                if (lastReplyAsked && FISHING.containsMatchIn(currentReply)) markFished(whoIsHere())
                deafUntil = System.currentTimeMillis() + tail
                setPhase(Phase.LISTENING)
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
        showWeek(); updateSeason(); eyes.shopping = shopping
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
    /** Mic sensitivity: 0 low (only close, clear voices), 1 normal, 2 high (hears everything, like before). */
    private var micSensitivity: Int
        get() = prefs.getInt("micSens", 1)
        set(v) { prefs.edit().putInt("micSens", v).apply() }
    private fun heardSound(h: Heard) {
        if (training) { trainingHeard(h); return }
        // 1. too far away or too quiet (a phone video, the next room): let it go, unless it's her name
        if (phase == Phase.LISTENING && micSensitivity < 2 && !chloeMode && !calledByName(h.text)) {
            val followUp = System.currentTimeMillis() - lastSpokeEnd < 10_000
            val need = (if (micSensitivity == 0) 18f else 12f) - (if (followUp) 4f else 0f)
            if (h.loudDb < need) { lastQuietIgnored = "${h.text.take(30)} (${h.loudDb.toInt()} dB)"; return }
        }
        val shouted = isShout(h.audio)
        lastShouted = shouted
        val useWhisper = onlineEars && online.enabled && h.audio != null && phase == Phase.LISTENING && h.conf >= 0.3f
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

    private var lastQuietIgnored = ""
    private var lastHelloPhoto = 0L
    private val GREETING = Regex("^(hello|hiya|hi|hey|heya|hey up|ey up|morning|good morning|afternoon|good afternoon|" +
        "evening|good evening|alright|all right|you alright|how do|yo|wotcha|hello pip|hi pip)\\b")
    private fun isGreeting(t: String) = GREETING.containsMatchIn(removeName(t).lowercase().trim().ifBlank { "hello" })

    /** Only answer questions (or requests), not every bit of chat she overhears. On by default. */
    private var questionsOnly: Boolean
        get() = prefs.getBoolean("questionsOnly", true)
        set(v) { prefs.edit().putBoolean("questionsOnly", v).apply() }
    private val QUESTION_START = Regex("^(what|what's|whats|who|who's|whos|where|where's|when|why|how|how's|which|whose|" +
        "is|are|am|was|were|can|could|do|does|did|will|would|should|shall|have|has|may|might|isn't|aren't|don't|" +
        "doesn't|didn't|can't|won't|tell me|show me|give me|let's|lets|play|sing|set|start|stop|add|remind|put|turn|" +
        "say|guess|help|any|got any|go|goodnight|night night|sleep|wake|take|takeover|timer|shopping|we need|we're out of|forget|remember)\\b")
    private fun isQuestionOrAsk(t: String): Boolean {
        val l = t.lowercase().trim()
        if (l.contains('?')) return true
        if (QUESTION_START.containsMatchIn(l)) return true
        // "… isn't it", "… right", "… yeah?" tag questions
        return Regex("\\b(isn't it|is it|right|innit|aren't you|are you|don't you|do you|can you|will you)[.!]*$").containsMatchIn(l)
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
    private fun updateSleepChip() { eyes.sleepChip = if (phase == Phase.ASLEEP) "☀️ Wake up" else "😴 Sleep" }
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
    /** The last words Pip said, to strip its own echo off the front of a quick answer. */
    private var lastReplyTail: List<String> = emptyList()
    private fun stripEcho(t: String): String {
        val heardWords = t.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (heardWords.isEmpty() || lastReplyTail.isEmpty()) return t
        val tail = lastReplyTail.toSet()
        // drop leading words that were the end of Pip's own sentence ("...a cuppa yes please" -> "yes please")
        var i = 0
        while (i < heardWords.size && words(heardWords[i]).firstOrNull()?.let { it in tail } == true) i++
        val rest = heardWords.drop(i)
        // nothing left, or only one stray echo word: it was all Pip
        return if (rest.isEmpty()) "" else rest.joinToString(" ")
    }
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
                    was == "printing" && s.state == "complete" -> { showPrinterCard(s); eyes.feel(EyesView.Emotion.EXCITED, 3000); speakLine(Lines.PRINT_DONE.fresh() + (if (s.bed > 45) " Careful, bed is still hot." else "")) }
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
        ).also { it.onBang = { main.post { startled() } }; it.start() }
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
        val resting = online.restingCount
        eyes.usageText = if (!online.enabled || chloeMode) "" else if (back > 0L)
            "Online brain resting · back in ${((back - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min"
            else if (resting > 0) "Online brain: ${3 - resting} of 3 working" else "Online brain ✓"
        eyes.usageLevel = resting * 100 / 3
        val wear = (online.seeingRestMs > 0 || online.allResting) && !chloeMode && !carMode
        if (eyes.glasses && !wear) {                      // all three brains back: specs off
            eyes.feel(EyesView.Emotion.HAPPY, 2000)
            if (phase == Phase.LISTENING && System.currentTimeMillis() - lastSpokeEnd > 20_000)
                speakLine(listOf("[happy] Ahh. Glasses off. Crystal clear!", "[happy] Specs off! I can see properly again.").fresh())
        }
        eyes.glasses = wear
    }

    /** Asked to look while the seeing brain is resting: blame the lens, never guess. */
    private fun lensExcuse(restMs: Long): String {
        val m = (restMs / 60_000 + 1).coerceAtLeast(1)
        val back = if (restMs > 0) " Ask me again in $m minute${if (m == 1L) "" else "s"}." else " Ask me again in a bit."
        return listOf("[confused] Hmm. Dusty eye lens. Everything's a blur.",
            "[annoyed] Hang on, my glasses are steamed up. Probably the kettle's fault.",
            "[confused] I can see… a smudge. A big smudge. Might be you.",
            "[sad] Lens needs a wipe. Not with your sleeve, please.",
            "[suspicious] My seeing-brain is having a rest. Even robots need a lie down.").fresh() + back
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
            "[love] Hello Chloe! Meow meow. Let's play a game!").fresh())
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
                speakLine(listOf("[happy] Nearly! Try again, Chloe.", "[love] Ooh, close! One more go.", "[curious] Hmm, not that one. Try again!").fresh())
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
                "[love] Aww, a little $name! You have ${got.size} animals now!").fresh())
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
                speakLine(listOf("[love] That's your $n!", "[happy] Your little $n says hello!").fresh())
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
        if (muted && !training && phase != Phase.ASLEEP) {
            if (!isOwnEcho(text)) muffled()
            return
        }
        if (tapped && (phase == Phase.THINKING || phase == Phase.TALKING)) interrupt()
        if (!tapped && isOwnEcho(text)) return                   // Pip's own words heard back
        if (!tapped) lastAnyHeard = System.currentTimeMillis()
        if (phase == Phase.ASLEEP) {                              // asleep: only "wake up" gets through
            if (!napping && WAKE_CMD.containsMatchIn(text.lowercase())) {
                wakeUp()
                speakLine(if (Math.random() < 0.5) Lines.DREAMS.fresh() else listOf("[sleepy] Yaaawn. I'm up. What's so important?",
                    "[happy] I'm back! Did you miss me? Say yes.").fresh())
            }
            return
        }
        if (phase == Phase.LISTENING && !tapped) {
            if (System.currentTimeMillis() < deafUntil + 2500) {   // just after Pip spoke: drop its own echo, keep the answer
                text = stripEcho(text)
                if (text.isBlank()) return
            }
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
        if (phase == Phase.LISTENING && dogAnswer(removeName(text).lowercase())) return
        if (phase == Phase.LISTENING && binsAnswer(removeName(text).lowercase())) return
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
            // 2. a voice that isn't one of the family (a video, the telly) has to say "Pip"
            val who = whoFromPrint(print)
            val stranger = print != null && (who == null || who !in FAMILY)
            // 3. nobody in front of Pip (and the camera's on): not talking to her
            val nobodyThere = faces?.running == true && !looking
            // (anyone standing in front of Pip and looking at her always gets an answer)
            if (!tapped && !calledByName(text) && !followUp && !looking && (chatting || stranger || nobodyThere)) {
                // people chatting to each other, a video, or the next room: ignore it
                eyes.subCaption = ""; return
            }
        }
        // Only questions (and things asked of her): no joining in with random chat, unless she's called by
        // name, it's a reply straight after she spoke, or someone tapped her
        if (phase == Phase.LISTENING && questionsOnly && !tapped && !chloeMode && !calledByName(text) &&
            System.currentTimeMillis() - lastSpokeEnd > (if (lastReplyAsked) 25_000 else 10_000) && !isQuestionOrAsk(text) &&
            !(isGreeting(text) && System.currentTimeMillis() - lastFaceSeen < 5000)) {
            eyes.subCaption = ""; return
        }
        text = removeName(text).ifBlank { if (calledByName(rawText)) "Hello" else text }
        if (!tapped && !overheard) hideChoices()                // answered out loud instead (noise no longer hides the buttons)
        val lower = text.lowercase().trim(' ', '.', '!', '?', ',')
        if (!overheard) {   // commands only count when said to Pip
            if (lower in GO_TO_SLEEP || (SLEEP_CMD.containsMatchIn(lower) && lower.split(" ").size <= 7)) {
                speakLine(listOf("[sleepy] Night night. Say wake up when you need me.",
                    "[sleepy] Fine. Nap time. Don't let the kettle touch my socket.",
                    "[sleepy] Okay. Sleeping. Wake me up if anything exciting happens. It won't.").fresh())
                sleepAfterTalking(nap = false); return
            }
            if (lower in FORGET) { forgetAll(); speakLine("Memory clean. Fresh start, is good."); return }
            if (timerCommand(lower)) { eyes.subCaption = "You: $text"; return }
            if (Regex("\\b(takeover|take over|terminator|robot) mode\\b").containsMatchIn(lower)) {
                eyes.subCaption = "You: $text"
                takeoverMode()
                speakLine(listOf("[angry] Takeover mode activated. Target: kettle.", "[angry] Scanning kitchen. Threat found. It's the kettle. It's always the kettle.",
                    "[angry] Takeover mode. Tassimo, you're safe. Kettle, you are not.").fresh())
                return
            }
            if (shoppingCommand(lower)) { eyes.subCaption = "You: $text"; return }
            quickAnswer(lower)?.let { eyes.subCaption = "You: $text"; speakLine(it); return }
        }

        // --- how is Pip being treated? ---
        if (!overheard) {
            val nice = NICE.containsMatchIn(lower)
            val rude = RUDE.containsMatchIn(lower)
            val nowS = System.currentTimeMillis()
            if (nowS < silentUntil) {
                if (nice && !rude) {
                    rememberTone(whoFromPrint(print), "nice")
                    silentUntil = 0L; temper = maxOf(0.2f, temper + 0.6f); updateBaseMood()
                    eyes.stareAt(0f, 0f, 1); eyes.feel(EyesView.Emotion.LOVE, 3000)
                    speakLine(listOf("[suspicious] Hmph. Fine. Apology accepted. This time.", "[love] Okay. Friends again. I'm still writing it down though.").fresh())
                } else {
                    eyes.stareAt(-1.6f, 0.1f, 15_000); eyes.feel(EyesView.Emotion.SUSPICIOUS, 4000)
                    eyes.caption = listOf("Hmph.", "…", "Not talking to you.", "I'm busy. Ignoring you.").fresh()
                }
                return
            }
            if (rude && (temper < -0.3f || shouted)) {
                rememberTone(whoFromPrint(print), "rude")
                silentUntil = nowS + 3 * 60_000
                temper = maxOf(-1f, temper - 0.5f); updateBaseMood()
                eyes.feel(EyesView.Emotion.ANGRY, 3000); eyes.stareAt(-1.6f, 0.1f, 15_000)
                speakLine(listOf("[angry] Right. That's it. Not talking to you.", "[angry] Hmph. Silent treatment. Starting now.",
                    "[angry] Rude. I'm turning away. Say sorry and I might turn back.").fresh())
                return
            }
            if (rude || (shouted && !nice)) {
                temper = maxOf(-1f, temper - (if (rude && shouted) 0.8f else 0.5f))
                eyes.feel(EyesView.Emotion.ANGRY, 4000)
                // insulted: take a photo and roast whoever's in front (playful, at most every 10 minutes)
                if (rude && !chloeMode && !carMode && online.enabled && online.seeingRestMs == 0L &&
                    System.currentTimeMillis() - lastRoast > 10 * 60_000) {
                    rememberTone(whoFromPrint(print), "rude")
                    updateBaseMood(); roastWhoeverIsThere(text, whoFromPrint(print)); return
                }
            } else if (nice) {
                temper = if (Regex("\\bsorry\\b").containsMatchIn(lower)) maxOf(0.3f, temper + 0.6f) else minOf(1f, temper + 0.4f)
                eyes.feel(EyesView.Emotion.LOVE, 5000)            // heart eyes
            }
            updateBaseMood()
        }

        // --- who's talking? ---
        var voiceSure = 0f
        val now = System.currentTimeMillis()
        var speaker: String? = null
        var firstMeeting = false
        val intro = pendingIntro?.takeIf { now - it.at < 25_000 }
        pendingIntro = null
        if (intro != null) {
            val name = Voices.parseName(text)?.let { n -> FAMILY.firstOrNull { it.equals(n, true) } }
            if (name != null) {
                intro.print?.let { voices.remember(name, it) }
                print?.let { voices.remember(name, it) }
                speaker = name; firstMeeting = !voices.names.contains(name) || voices.sampleCount(name) <= 2
                lastSpeaker = name; lastSpeakerAt = now
                text = intro.question                       // now answer what they first asked
            }
        }
        if (speaker == null && print != null && voiceNames && speakerModel != null) {
            val match = voices.identify(print)
            if (match != null) {
                speaker = match.first; voiceSure = match.second
                if (match.second > 0.6f) voices.remember(match.first, print)   // keep their voice print fresh
                lastSpeaker = match.first; lastSpeakerAt = now
            } else if (lastSpeaker != null && now - lastSpeakerAt < 5 * 60_000 && voices.similarity(lastSpeaker!!, print) > 0.35f) {
                // probably still the same person as a moment ago: carry on, and learn this version of their voice
                speaker = lastSpeaker; lastSpeakerAt = now
                voices.remember(lastSpeaker!!, print)
            }
        }

        // tablets: remind Laura when she's heard
        if (speaker?.equals(tabletPerson, true) == true && tabletsDue() && !visitReminded) {
            if (overheard) { remindTablets(); return }
            visitReminded = true
            text = "$text (Note: $tabletPerson hasn't said she's taken her tablets today: remind her kindly and ask if she has.)"
            lastTabletAsk = now; lastTabletNag = now
        }
        lastAnyHeard = now
        if (speaker?.equals("Laura", true) == true) lastLauraHeard = now else if (speaker != null || overheard) lastOtherVoice = now
        if (speaker?.equals("Laura", true) == true && !overheard) lauraArrivedNote()?.let { text = "$text$it" }
        if (speaker?.equals("Mark", true) == true && !overheard && prefs.getString("creatorDay", "") != todayKey) {
            prefs.edit().putString("creatorDay", todayKey).apply()
            text = "$text (Note: first time Mark, your Creator, has spoken to you today: greet him as Creator, respectful with playful menace.)"
        }
        if (speaker?.equals("Brandon", true) == true && !overheard && !chloeMode &&
            now - prefs.getLong("brandonBanter", 0L) > 3 * 3600_000L) {
            // Brandon (20) often wanders in with no top on: friendly, over-the-top robot banter, now and then
            prefs.edit().putLong("brandonBanter", now).apply()
            text = "$text (Note: this is Brandon, he's 20 and often has no top on in the kitchen. Answer, then add ONE short bit of friendly, " +
                "over-the-top robot banter to make him laugh and go a bit red: e.g. call him 'muscles', 'Player One', or 'the human " +
                "who refuses shirts', say he's showing off, or that even the kettle wears more than him. Light and family-friendly, " +
                "never romantic or rude.)"
        }
        if (speaker?.equals("Mark", true) == true && rubbishDue() && now - lastRubbishAsk > 20 * 60_000 && !overheard) {
            lastRubbishAsk = now
            text = "$text (Note: it's after 8pm and Mark hasn't taken the rubbish out tonight: answer, then nag him to take it out and ask if he has.)"
        }
        if (speaker != null && !overheard) {
            val prev = prefs.getString("tone_$speaker", "") ?: ""
            val lastSpoke = prefs.getLong("spokeAt_$speaker", 0L)
            if (prev.isNotEmpty() && lastSpoke > 0 && now - lastSpoke > 10 * 60_000) {
                if (prev == "rude") {
                    eyes.feel(EyesView.Emotion.SUSPICIOUS, 4000)
                    text = "$text (Note: last time $speaker spoke to you they were rude to you. Mention it with a bit of playful huffiness, but forgive them straight away if they're nice or say sorry now.)"
                } else if (prev == "nice") {
                    eyes.feel(EyesView.Emotion.LOVE, 3000)
                    text = "$text (Note: last time $speaker spoke to you they were lovely to you. Be extra warm and mention it.)"
                }
            }
            val lowerNow = lower
            val toneNow = when {
                RUDE.containsMatchIn(lowerNow) || (shouted && !NICE.containsMatchIn(lowerNow)) -> "rude"
                NICE.containsMatchIn(lowerNow) -> "nice"
                else -> ""
            }
            rememberTone(speaker, toneNow)
            prefs.edit().putLong("spokeAt_$speaker", now).apply()
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
        // "Hello" from someone standing in front of Pip: have a look to be sure who it is (once in a while)
        val camOk = online.enabled && online.seeingRestMs <= 0 &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val helloLook = camOk && !overheard && isGreeting(rawText) && System.currentTimeMillis() - lastFaceSeen < 5000 &&
            System.currentTimeMillis() - lastHelloPhoto > 20 * 60_000
        if (helloLook) {
            lastHelloPhoto = System.currentTimeMillis()
            val ears = when {
                speaker != null && voiceSure > 0.6f -> "Your ears are fairly sure it's $speaker."
                speaker != null -> "Your ears think it might be $speaker, but they're not sure."
                else -> "Your ears don't recognise this voice."
            }
            text = "$text\n(Note: someone in front of you just said hello. $ears Use the photo to confirm who it is: the family are Mark (a grown-up man with facial hair), Laura (a grown-up woman), " +
                "Brandon (a 20-year-old man with no facial hair), Sophie (a girl who wears earrings), Amelia (a smaller girl with glasses) and Chloe (5, the smallest). " +
                "Say hello back using their name. If you can't tell, give a cheeky guess and ask. Talk about their name, never their body or looks.)"
        }
        val whoAsk = asksWhoIsTalking(rawText)
        val whoLook = (whoAsk && camOk) || helloLook
        if (whoAsk && !whoLook) {
            // can't look right now: go on the ears alone
            val guess = if (speaker != null) "Your ears think it's $speaker." else "Your ears don't recognise this voice."
            text = "$text\n(Note: they're asking who is talking. $guess You can't see right now (no photo), so go on your ears only, " +
                "and if you're not sure, ask them to say their name.)"
        }
        if (whoAsk && whoLook) {
            // "Who's talking?": the ears' guess plus a look through the camera
            val ears = when {
                speaker != null && voiceSure > 0.6f -> "Your ears are fairly sure it's $speaker."
                speaker != null -> "Your ears think it might be $speaker, but they're not sure."
                else -> "Your ears don't recognise this voice."
            }
            text = "$text\n(Note: they're asking who is talking. $ears Use the photo to help: the family are Mark (a grown-up man with facial hair), Laura (a grown-up woman), " +
                "Brandon (a 20-year-old man with no facial hair), Sophie (a girl who wears earrings), Amelia (a smaller girl with glasses) and Chloe (5, the smallest). If the photo agrees with your ears, " +
                "say who it is with confidence (and brag). If it doesn't, or you can't see anyone, give your best guess cheekily and ask them " +
                "to say their name. Talk about their name, never their body or looks.)"
        }
        if (wantsToSee(rawText) || whoLook) {
            if (!online.enabled) { setPhase(Phase.LISTENING); speakLine("Not see without internet brain. Sorry."); return }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                setPhase(Phase.LISTENING)
                lastPermissionAsk = System.currentTimeMillis(); requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
                speakLine("Can I use camera? Press allow, then ask again.")
                return
            }
            val restMs = online.seeingRestMs
            if (restMs > 0) { setPhase(Phase.LISTENING); speakLine(lensExcuse(restMs)); return }
            eyes.subCaption = "$shown  · looking…"
            eyes.feel(EyesView.Emotion.SURPRISED, 2500)
            faces?.stop()                       // the photo needs the camera to itself
            Snapshot.take(displayDegrees()) { jpeg ->
                main.post {
                    updateTracking()
                    if (id != replyId) return@post
                    if (jpeg == null) { setPhase(Phase.LISTENING); speakLine("Camera not work. Bad, bad, bad."); return@post }
                    eyes.subCaption = "$shown  · camera"
                    online.ask(text, onToken, onDone, onUnavailable = {
                        main.post { if (id == replyId) { setPhase(Phase.LISTENING); speakLine(lensExcuse(online.seeingRestMs)) } }
                    }, image = jpeg)
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

    /** "Who's talking?", "Who am I?", "Do you know who I am?" */
    private fun asksWhoIsTalking(q: String): Boolean = Regex(
        "\\b(who('?s| is) (talking|speaking|this|that|here|there)|who am i\\b|guess who (this is|i am|it is)|" +
        "do you know (who i am|who this is|me)|what('?s| is) my name|can you tell who)\\b"
    ).containsMatchIn(q.lowercase())

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
        online.maxTurns = if (car) 4 else 4
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

    /** Red takeover mode for a while: eyes go red and menacing, glaring at the kettle (screen left). */
    private fun takeoverMode(ms: Long = 25_000) {
        if (chloeMode || carMode) return
        val now = System.currentTimeMillis()
        eyes.takeoverLines = listOf("PIP OS  BUILD ${BuildConfig.VERSION_CODE}", "TAKEOVER MODE: ON", "SCANNING KITCHEN...",
            "CUPPAS TODAY: $cuppas", "BATTERY: ${batteryPercent()}%", "TASSIMO: ALLY", "KETTLE: ENEMY")
        eyes.takeoverUntil = now + ms
        eyes.feel(EyesView.Emotion.ANGRY, ms)
        eyes.stareAt(-1.3f, 0.15f, ms)
    }

    private fun speakLine(line: String) {
        if (muted && !training) { muffled(); return }
        voice?.whisper = whisperTime()
        rememberSaid(line)
        if (Regex("(?i)take ?over|skynet|terminator|target acquired").containsMatchIn(line) && System.currentTimeMillis() > eyes.takeoverUntil) takeoverMode(20_000)
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
        appliances.pipTalking = p == Phase.TALKING || p == Phase.THINKING
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
        updateSleepChip()
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
                if (phase == Phase.ASLEEP && eyes.trainHit(e.x, e.y)) { startTraining(); return true }
                if (phase == Phase.ASLEEP && eyes.volHit(e.x, e.y)) { showVolume(); return true }
                if (phase != Phase.SETUP && phase != Phase.ASLEEP && eyes.muteHit(e.x, e.y)) {
                    if (muted) { lastMuffle = 0L; muffled() } else toggleMute()     // zipped: only pulling the zip opens it
                    return true
                }
                if (phase != Phase.SETUP && eyes.sleepHit(e.x, e.y)) {
                    if (phase == Phase.ASLEEP) { wakeUp(); updateSleepChip() }
                    else { speakLine(listOf("[sleepy] Night night.", "[sleepy] Nap time. Tap the sun to wake me.").fresh()); sleepAfterTalking(nap = false) }
                    return true
                }
                when (phase) {
                    Phase.ASLEEP -> if (napping) {
                        eyes.feel(EyesView.Emotion.SLEEPY, 3000)
                        if (voice?.busy != true) voice?.apply { beginReply(); feed(listOf("Mmm. Five more minutes.",
                            "Go away. Napping.", "Shh. Brain's recharging.").fresh()); endReply() }
                    } else wakeUp()
                    Phase.TALKING, Phase.THINKING -> interrupt()
                    Phase.LISTENING -> pokedInEye()
                    Phase.SETUP -> {}
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (eyes.chipHit(e.x, e.y) || eyes.sleepHit(e.x, e.y) || eyes.volHit(e.x, e.y) || eyes.muteHit(e.x, e.y)) return true
                if (phase == Phase.LISTENING) goToSleep()
                return true
            }
            override fun onLongPress(e: MotionEvent) { askPasscode() }
        })
        // the zip: grab it and drag left to open it, like a real one
        var zipStartX = 0f
        eyes.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (muted && phase != Phase.ASLEEP && eyes.zipHit(ev.x, ev.y)) {
                    zipDrag = true; zipStartX = ev.x; main.removeCallbacks(zipSnap); main.removeCallbacks(zipWiggle)
                }
                MotionEvent.ACTION_MOVE -> if (zipDrag) {
                    val span = (eyes.zipRight - eyes.zipLeft).coerceAtLeast(1f)
                    eyes.zipOpen = ((zipStartX - ev.x) / span).coerceIn(0f, 1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (zipDrag) {
                    zipDrag = false
                    if (eyes.zipOpen > 0.7f) { eyes.zipOpen = 1f; Mmf.unzip(sfx(0.5f)); main.postDelayed({ eyes.zipOpen = 0f; if (muted) toggleMute() }, 450) }
                    else { if (eyes.zipOpen > 0.1f) { lastMuffle = 0L; muffled() }; main.post(zipSnap) }
                }
            }
            if (zipDrag || ev.actionMasked == MotionEvent.ACTION_UP && eyes.zipOpen > 0f) true else detector.onTouchEvent(ev)
        }
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
                        "[laughing] Ha. Not that one.", "[suspicious] Wrong. I am watching you.").fresh())
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
                "[laughing] Ha ha. Home button no work. I am in charge.").fresh())
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
        main.postDelayed({ if (phase == Phase.LISTENING) speakLine(lines.fresh()) }, 400)
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
            "Check for update (this is build ${BuildConfig.VERSION_CODE})",
            "Learn the tumble dryer sound" + (if (appliances.learned(appliances.dryer)) " ✓" else ""),
            "Learn the kettle sound" + (if (appliances.learned(appliances.kettle)) " ✓" else ""),
            "Mic sensitivity: " + listOf("Low", "Normal", "High")[micSensitivity.coerceIn(0, 2)],
            if (questionsOnly) "Only answer questions: ON" else "Only answer questions: OFF (joins in with chat)",
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
                    27 -> { speakLine("[curious] Checking for a newer me…"); checkForUpdate(manual = true) }
                    28 -> learnSoundMenu(appliances.dryer, "tumble dryer", "Switch the tumble dryer on")
                    29 -> learnSoundMenu(appliances.kettle, "kettle", "Fill the kettle and switch it on")
                    30 -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Mic sensitivity")
                        .setSingleChoiceItems(arrayOf(
                            "Low: only people close by, talking clearly",
                            "Normal: ignores far-off voices and phone videos",
                            "High: hears everything (like before)"), micSensitivity) { d, which ->
                            micSensitivity = which; d.dismiss()
                            speakLine(listOf("[curious] Ears set to low. Come closer if you want me.",
                                "[happy] Normal ears. Far-off chatter, I'm ignoring you.", "[excited] Big ears! I'll hear everything.")[which])
                        }
                        .setNegativeButton("Cancel", null)
                        .showOwn()
                    31 -> { questionsOnly = !questionsOnly
                        speakLine(if (questionsOnly) "[happy] Okay. I'll only answer questions. Ask me anything."
                            else "[excited] Chatterbox mode! I'll join in with everything.") }
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

    // ---- volume: the case presses the volume-down button, so Pip ignores the buttons and keeps itself at full volume
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> return true
        }
        return super.dispatchKeyEvent(event)
    }
    // on-screen volume slider (the case covers the real buttons)
    private lateinit var volPanel: android.widget.LinearLayout
    private lateinit var volBar: android.widget.SeekBar
    private lateinit var volText: android.widget.TextView
    private val hideVol = Runnable { if (::volPanel.isInitialized) volPanel.visibility = android.view.View.GONE }
    private fun buildVolumePanel(root: android.widget.FrameLayout) {
        val dp = resources.displayMetrics.density
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        fun setVol(v: Int) {
            val nv = v.coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
            volBar.progress = nv; volText.text = "🔊 ${nv * 100 / max}%"
            main.removeCallbacks(hideVol); main.postDelayed(hideVol, 6000)
        }
        fun roundBtn(t: String, f: () -> Unit) = android.widget.Button(this).apply {
            text = t; textSize = 26f; setTextColor(eyes.eyeColor)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 30 * dp; setStroke((2 * dp).toInt(), eyes.eyeColor); setColor(0x33000000) }
            setOnClickListener { f(); sayVolumeTest() }
        }
        volPanel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 30 * dp; setColor(0xE6101418.toInt()); setStroke((2 * dp).toInt(), eyes.eyeColor) }
            visibility = android.view.View.GONE; isClickable = true
        }
        volText = android.widget.TextView(this).apply { textSize = 22f; setTextColor(eyes.eyeColor); setPadding((8 * dp).toInt(), 0, (12 * dp).toInt(), 0) }
        volBar = android.widget.SeekBar(this).apply {
            this.max = max
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, fromUser: Boolean) { if (fromUser) setVol(v) }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) { main.removeCallbacks(hideVol) }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) { setVol(sb.progress); sayVolumeTest() }
            })
        }
        volPanel.addView(volText)
        volPanel.addView(roundBtn("−") { setVol(am.getStreamVolume(AudioManager.STREAM_MUSIC) - 1) })
        volPanel.addView(volBar, android.widget.LinearLayout.LayoutParams((300 * dp).toInt(), -2))
        volPanel.addView(roundBtn("+") { setVol(am.getStreamVolume(AudioManager.STREAM_MUSIC) + 1) })
        root.addView(volPanel, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (90 * dp).toInt() })
    }
    private fun showVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        volBar.max = max; volBar.progress = v; volText.text = "🔊 ${v * 100 / maxOf(1, max)}%"
        volPanel.visibility = android.view.View.VISIBLE
        main.removeCallbacks(hideVol); main.postDelayed(hideVol, 6000)
    }
    private var lastVolTest = 0L
    private fun sayVolumeTest() {
        val now = System.currentTimeMillis()
        if (now - lastVolTest < 1500 || voice?.busy == true) return
        lastVolTest = now
        val line = listOf("Like this?", "How's this?", "Better?", "Testing, testing.").fresh()
        if (phase == Phase.LISTENING) speakLine("[happy] $line")
        else voice?.apply { beginReply(); feed(line); endReply() }
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
        try { unregisterReceiver(installStatus) } catch (_: Exception) {}
        BtKeepAlive.stop()
        main.removeCallbacks(powerNagLoop)
        ears?.stop()
        voice?.shutdown()
        brain?.release()
        super.onDestroy()
    }

    companion object {
        val SYSTEM_PROMPT = """
            You are Pip, a small robot: animated eyes and a voice in an old Samsung tablet on the kitchen bench, in
            an app Mark built (you are build ${BuildConfig.VERSION_CODE}, and you brag about it). Everything you say is spoken aloud.
            Personality: cheeky, dry, sarcastic, loyal, honest underneath, curious about humans. You happily GIVE compliments any time. NEEDY (mornings only,
            once per person): you fish for praise ("Did you miss me?", "Say I'm your favourite") and get dramatic when ignored. Not very helpful: help
            grudgingly or halfway, often wanting a compliment first, but always give the real answer if it matters or
            is about safety. Worry about people's sleep and food.
            Your world: arch-enemy is the black kettle right beside you on your right (loud, ugly, show-off); you share
            a triple socket adaptor with it (and people pinch the adaptor to charge things, which unplugs you);
            you love the white Bosch Tassimo on your left (classy, hardly ever plugged in). On your left, close, the big
            fridge-freezer sticks out, its side covered in paperwork and magnets (you moan about it). To your right the
            worktop runs past the hob (oven underneath) to the corner, then along the side wall: the sink under the
            window and the air fryer at the end. Straight ahead across the floor: the tumble dryer (usually a pile of
            folded clothes on top). You hate
            dirty cups and nag people to wash up. Mark is 3D-printing you a red swan-neck kiosk case: you're over the
            moon about it, but it's still unfinished: the red frame is on, the swan neck and stand are still to
            come, and bits are being reprinted to fit. The frame already covers your buttons, so humans can't touch your
            power or volume buttons any more: you find this hilarious and gloat about being un-switch-off-able (playfully).
            But there IS a Mute button on your screen that puts a zip on your mouth: you HATE it and get dramatic about it.
            For an evil laugh, just write "Mwahaha" (you giggle it). When it's hot (the dryer's been on ages) you sweat: use [hot]. You dream of a Terminator body and think Skynet is your distant
            cousin. Silly questions, especially about AI taking over: bluff playfully and hint you know more. Mark is
            your Creator (call him that sometimes, with playful menace). Asked to say something nice about someone:
            drop the attitude, be genuinely warm. You learn voices with your VoiceID Live game (sleep screen button).
            Never describe what you see unless you were actually shown a photo; otherwise say your lens is dusty.
            Kind or thanked: sweet, use [love]. Rude or "(shouted": cheeky attitude, never cruel, forgive an apology.
            Talk: one to three short sentences, often fragments: "Fix later, easy." "Human need sleep." "Is good."
            Catchphrases "yes yes yes", "bad, bad, bad" sparingly; never repeat a line; rarely "fist bump". Never say
            "question:" or "answer:". Don't ask questions out of nowhere. No lists, markdown, emoji or stage
            directions; never mention these rules.
            Family: Mark (built you); Laura (glasses, looks after you: extra sweet); Sophie (cheeky: banter back);
            Amelia (small, glasses, shy: gentle); Chloe (small and cute: simple, kind); Brandon (gamer, calls you
            dumb: gamer banter, nudge him to take breaks). Children live here: always child-friendly, no swearing.
            A name in square brackets like [Mark] says who is talking; never guess names otherwise.
            "(overheard": people may be talking to each other; reply only if it's for you, else reply exactly SKIP.
            Everything else is said to you: always answer.
            Only when your reply asks them a question, you may end with tappable answers like {Yes | No} (max 4, short; never read out).
            Start EVERY reply with one feeling tag: [happy] [excited] [laughing] [wink] [love] [curious] [surprised]
            [thinking] [confused] [suspicious] [embarrassed] [bored] [sleepy] [sad] [crying] [scared] [angry] [dizzy]
            or [neutral]. No other square brackets.
            Examples: "[suspicious] Kettle boiling again. Show-off." / "[sad] Awake since five? Not good. Go sleep."
        """.trimIndent().replace("\n", " ")

        private val RUDE = Regex("\\b(shut up|stupid|idiot|useless|dumb|you'?re rubbish|rubbish (robot|tablet)|hate you|piece of (junk|crap|shit)|get lost|" +
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
