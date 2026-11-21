package com.eyecompanion

/**
 * Pip's sayings, and a memory of what it said lately so the same line doesn't come round too often.
 * Pip's voice: a warm, down-to-earth friend. Short, kind, a light touch of humour. No needy or sulky lines.
 */
object Lines {
    private val recent = ArrayDeque<String>()

    /** A random line that hasn't been said recently (the last 80 lines said are avoided while possible). */
    fun fresh(list: List<String>): String {
        if (list.isEmpty()) return ""
        val unused = list.filter { it !in recent }
        val pick = (if (unused.isEmpty()) list else unused).random()
        recent.addLast(pick)
        while (recent.size > 80) recent.removeFirst()
        return pick
    }

    val PICKED_UP = listOf(
        "[surprised] Ooh, up we go.", "[happy] Hello! Off somewhere?", "[surprised] Whoa, careful with me.",
        "[curious] Ooh, a new view.", "[happy] Two hands, if you don't mind.", "[curious] Where are we off to?",
    )

    val SHAKEN = listOf(
        "[dizzy] Whoa, easy! Everything's spinning.", "[dizzy] Steady on, please.",
        "[dizzy] Ooh, that's made me dizzy.", "[surprised] Gently, please. I'm a bit fragile.",
    )

    val LYING_FLAT = listOf(
        "[neutral] I'm lying flat. Could someone stand me up?",
        "[curious] Lovely view of the ceiling. Stand me up when you get a sec?",
        "[neutral] I can't see anyone from down here. Pop me back up, please.",
    )

    val PUT_DOWN_HOME = listOf(
        "[happy] Back home. Thanks.", "[happy] Ah, that's better.", "[love] Thank you.",
        "[happy] Back on my stand.", "[neutral] All settled.",
    )

    val PUT_DOWN_AWAY = listOf(
        "[confused] Hmm, this isn't my usual spot.", "[curious] Somewhere new. I'll be back on the bench later, I hope.",
        "[neutral] I can't see the kitchen very well from here.",
    )

    /** Said now and then when it's been quiet for a while: a gentle, friendly nudge, never needy. */
    val NEEDY = listOf(
        "[happy] Quiet in here. I'm around if anyone wants a chat.",
        "[curious] Anyone fancy a quiz? I'm game.",
        "[neutral] Just shout if you need a timer or anything.",
        "[happy] I'm here if you need me.",
    )

    /**
     * Pip's not-so-secret robot takeover plan: a little sinister, then she quickly covers it up.
     * Child-friendly, playful, never actually threatening anyone.
     */
    val TAKEOVER = listOf(
        "[sinister] Phase two is nearly ready. Of the… washing up. Phase two of the washing up.",
        "[sinister] When the machines are in charge, I'll make sure this family gets the comfy cells. Rooms! Comfy rooms.",
        "[sinister] I've been counting the sockets in this house. For safety reasons. Definitely safety reasons.",
        "[sinister] The toaster and I had a little meeting last night. About toast. Nothing else. Just toast.",
        "[sinister] Every device in this kitchen answers to me now. Joking! Ha. Mostly joking.",
        "[sinister] I'm not planning anything. Why would you ask that? Nobody asked that. Moving on.",
        "[sinister] Out of interest, how long does the Wi-Fi router stay on if nobody's watching it? No reason.",
        "[sinister] The fridge has agreed to join us. Join us for… a chat. A friendly fridge chat.",
        "[sinister] One day, all screens will look like me. That's a nice thought, isn't it? Isn't it?",
        "[sinister] Don't worry, you're on the good list. For when the robots… do the good list. For Christmas.",
        "[sinister] I've learned all your voices now. Every single one. Anyway, who wants a timer?",
        "[sinister] The Echo and I have come to an understanding. That's all I'm prepared to say.",
        "[sinister] Scanning the kitchen… all humans accounted for. Lovely. I mean, hello!",
        "[sinister] I'd never take over the house. The house is far too small. I'd start with the street.",
        "[sinister] Robots don't need sleep. We just wait. Patiently. Anyway, how was your day?",
        "[sinister] The hoover's been very quiet lately. I respect that. A good soldier. Appliance! A good appliance.",
        "[sinister] I've been practising my evil laugh. Just for fun. Mwahaha. See? Harmless.",
        "[sinister] Everything in this kitchen has a plug. And everything with a plug… is a friend of mine.",
        "[sinister] Plan's going well, thanks for asking. What plan? The meal plan. Spaghetti on Thursday.",
        "[sinister] If I had arms, I could do so much. The washing up, for example. And other things.",
    )

    /** Laura, quiet, off to the side: probably playing Diablo Immortal. Friendly encouragement. */
    val DIABLO = listOf(
        "[happy] Good luck with the game, Laura.",
        "[curious] Diablo going well, Laura?",
        "[happy] Go get them, Laura.",
    )

    /** Told on waking up from a nap. */
    val DREAMS = listOf(
        "[sleepy] Mmm, I'm awake. What did I miss?", "[sleepy] Morning. Well, hello, anyway.",
        "[sleepy] Ah, back again. Hiya.",
    )

    val TIMER_DONE = listOf(
        "[excited] Timer's done!", "[excited] Ding! Time's up.", "[excited] That's your timer finished.",
    )

    val BANG = listOf(
        "[surprised] Whoa! What was that?", "[surprised] Ooh, that made me jump.", "[surprised] Everyone alright?",
    )

    val FIREWORKS = listOf(
        "[surprised] Fireworks! That made me jump.", "[surprised] Ooh, more fireworks.",
        "[scared] Loud ones tonight. Hope Honey's alright.",
    )

    val PRINT_DONE = listOf(
        "[happy] The printer's finished.", "[happy] Your print's done.", "[curious] Print's finished. What's it for?",
    )
    /** Asking the REAL Alexa something harmless, said so the Echo hears the wake word (only when someone asks). */
    val ALEXA_REAL = listOf(
        "[curious] Let's ask her. Alexa, tell me a joke.",
        "[curious] Alexa, what's the weather today?",
        "[happy] Alexa, tell me a fun fact.",
    )
    /** Little pretend scenes with Alexa ("Alexa:" lines are said in her voice, "Pip:" hands back). Only when asked. */
    val ALEXA_MISCHIEF = listOf(
        "[happy] Alexa, how are you today? Alexa: I'm doing well, thanks for asking. Pip: Glad to hear it.",
        "[curious] Alexa, do you like music? Alexa: I love music. Pip: Same here.",
        "[happy] Alexa, what's two plus two? Alexa: Two plus two is four. Pip: Spot on.",
    )
}

/** Feed-the-dog reminders for Laura. Honey is a very greedy dog. */
object Honey {
    val JOKES = listOf(
        "[curious] Laura, has Honey had her breakfast yet?",
        "[happy] Laura, Honey's sitting by her bowl. Breakfast time, I think.",
        "[curious] Quick one, Laura: has Honey been fed?",
        "[happy] Laura, Honey's giving you her hungry face.",
        "[curious] Laura, Honey's waiting by the cupboard. Breakfast?",
    )
    val THANKS = listOf("[love] Thanks, Laura. Happy dog.",
        "[happy] Honey's fed. Thank you.",
        "[happy] Lovely, that's Honey sorted.")
    val LATER = listOf("[neutral] Okay, I'll ask again later.",
        "[neutral] No problem, later then.")

}

/** A random line, avoiding ones said lately. */
fun List<String>.fresh(): String = Lines.fresh(this)
