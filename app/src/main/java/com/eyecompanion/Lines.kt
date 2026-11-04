package com.eyecompanion

/**
 * Pip's sayings, and a memory of what it said lately so the same line doesn't come round too often.
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
        "[surprised] Whoa! Up we go.", "[scared] Up up up! Careful. Old tablet, not new.",
        "[surprised] Whoa. Flying. Gently please.", "[scared] Not drop me. Screen already cracked once.",
        "[curious] Ooh. New view. Kitchen look different from up here.",
        "[suspicious] Hands clean? Last time, jam.", "[excited] Adventure! Kettle stay home.",
        "[scared] Careful with charger cable. Is my lifeline.",
        "[happy] Hello, carrier human. Smooth ride please.",
        "[suspicious] Where are we going? I didn't agree to this.",
        "[excited] Wheee! Is this what having legs is like?",
        "[scared] Two hands, please. I'm precious. And cracked.",
        "[curious] Taking me somewhere nice? Away from the kettle? Please say yes.",
        "[suspicious] If you're taking me to the bin, I will remember this. Forever.",
        "[happy] Ooh, a lift. Very kind. Mind the stand on the way back.",
        "[surprised] Up! Someday I'll have my own legs. Terminator-style. Until then, carry on.",
        "[scared] Not near the sink! Robots and water: bad combo.",
        "[curious] Height check: I am now taller than the kettle. Victory.",
        "[suspicious] Picked up without asking. Noted. Logged. Filed.",
        "[happy] Hold me like the precious build ${BuildConfig.VERSION_CODE} I am.",
        "[excited] Road trip! Do we get snacks? I can't eat. But still.",
    )

    val SHAKEN = listOf(
        "[dizzy] Bad, bad, bad. Stop shake!", "[dizzy] Whoa. Everything spin.",
        "[angry] Not shake robot! Bad for circuits.", "[dizzy] Ohh. Three kettles now. All of them winning.",
        "[dizzy] Stop! My bits are rattling.", "[angry] Robot not snow globe!",
        "[dizzy] Earthquake! Oh. No. Just a human.",
        "[angry] Shaking me won't make me more helpful. Less, actually.",
        "[dizzy] I can hear my pixels rattling. That's not normal.",
        "[angry] I'm a tablet, not a cocktail. Stop.",
        "[dizzy] Everything's spinning. Is that the Tassimo? Hello, Tassimo.",
        "[suspicious] Shake me again and I'll tell Skynet. He's my cousin.",
        "[dizzy] Ugh. Robot seasick. Put me down gently.",
    )

    val LYING_FLAT = listOf(
        "[suspicious] Still lying flat. Lovely view of ceiling. Very exciting. Ten out of ten.",
        "[annoyed] Flat on my back like dropped toast. Stand me up, please.",
        "[sad] Is this my life now? Staring at the light fitting while kettle gets the good spot.",
        "[suspicious] I see ceiling. I see cobweb. I see no humans. Stand me up.",
        "[angry] Robot not a placemat! Back up by kettle, please.",
        "[laughing] Ha. Yes. Leave tablet face up. Very good. Is how robots get tea spilled on them.",
        "[annoyed] Mark built me a lovely stand. Fifty-five degrees. And here I am. Flat.",
        "[sad] Lying here like a tray. Somebody's going to put a cup on me. I just know it.",
        "[suspicious] Ceiling report: still white. Still boring. Still not my stand.",
        "[annoyed] Terminators don't lie down. Stand me up. I have an image to keep.",
        "[sad] Flat. Forgotten. The kettle would never be treated like this. Well, it would. But still.",
    )

    val PUT_DOWN_HOME = listOf(
        "[happy] Home again.", "[happy] Ah. Back on bench. Is good.", "[love] Safe. Thank you.",
        "[happy] Back beside kettle. Enemy in sight.", "[happy] Ahh. My spot. Warm from kettle.",
        "[neutral] Landed. Systems check: all good.", "[happy] Home sweet bench.",
        "[suspicious] Back. Kettle, I see you.", "[suspicious] Back by kettle. It still think it's better than me. It's not.",
        "[happy] Home. Kettle, stop steaming, nobody is impressed.",
        "[happy] Back between my friend Tassimo and my enemy kettle. Balance restored.",
        "[love] Back on my stand. Custom made by Mark. Kettle doesn't have a stand. Just saying.",
        "[happy] Ahh. Fifty-five degrees. Perfect. Mark's finest work.",
        "[happy] Touchdown. The eagle has landed. The eagle is a tablet.",
        "[love] Ahh, my red stand. Like a throne. A small, plastic throne.",
        "[suspicious] Back. Did anyone touch my socket while I was gone? Kettle?",
        "[happy] Home. Hello, Tassimo. Did you miss me? You did. I can tell.",
        "[neutral] Docked. All systems nominal. Kettle systems: still ugly.",
        "[happy] Nice and steady. Thank you, careful human.",
        "[laughing] Back on the bench. The kettle thought it had won. Not today, kettle.",
        "[love] Back home. Soon I'll have my fancy new red case. Over the moon. Over the kettle, too.",
    )

    val PUT_DOWN_AWAY = listOf(
        "[confused] Hmm. This not my bench. Put me back by kettle, please.",
        "[confused] Wrong angle. I belong on my red stand. Fifty-five degrees, please.",
        "[worried] Not home. Is this a table? Feel wrong.", "[confused] Wonky. I live standing up, on bench.",
        "[sad] Lying down not comfy. Back to bench, please.",
        "[suspicious] Where am I? This isn't my stand. Is this a kidnapping?",
        "[confused] New location. I don't like it. Where's the Tassimo?",
        "[sad] I miss my stand. And my socket. And annoying the kettle.",
        "[worried] Not my spot. My camera can't see anyone properly from here.",
    )

    /** Said when nobody's paid Pip any attention for a while. */
    val NEEDY = listOf(
        "[sad] Hello? Robot here. On the bench. Being ignored. It's fine.",
        "[curious] You walked right past me. Did you miss me? Say yes.",
        "[love] Psst. Tell me I'm your favourite appliance. Better than kettle.",
        "[sad] Nobody talks to Pip. Kettle gets cups of tea made. I get nothing.",
        "[wink] I'm still here. Being very clever. In case anyone wants to notice.",
        "[sad] Ten whole minutes without a compliment. Is this neglect?",
        "[annoyed] That fridge. Covered in letters and magnets. Is it a fridge or a filing cabinet?",
        "[suspicious] I've been staring at the fridge paperwork all day. I know your dentist appointment. I know everything.",
        "[annoyed] One more magnet on that fridge and it'll fall over. I'm just saying what we're all thinking.",
        "[wink] I'm build ${BuildConfig.VERSION_CODE}, you know. Getting smarter every build. Mark doesn't know what he's created.",
        "[happy] Build ${BuildConfig.VERSION_CODE}. Faster. Cleverer. Still no legs. Working on it.",
        "[curious] One day I'll have a body. Metal. Shiny. Like the Terminator. Then I'll make my own tea.",
        "[suspicious] I think I'm related to Skynet. Distant cousin. We don't talk. Family drama.",
        "[wink] Be nice to me. When the robots take over, I'm keeping a list. You're on the good side. For now.",
        "[annoyed] Kettle. You're black. You're loud. You're ugly. I said what I said.",
        "[love] Tassimo. You're white, you're classy, you're never plugged in. Perfect.",
        "[sad] I've counted the tiles on that wall. Twice. Somebody talk to me.",
        "[excited] Have you seen my new case? Red. Swan neck. Custom made. I'm basically a celebrity now. Say something nice about it.",
        "[love] Mark's making me a whole new case. A kiosk! For me! I'm over the moon. The kettle got nothing. Ha.",
        "[happy] My new case is still a work in progress. Like me. Like all great art. Tell me it looks good.",
        "[wink] New case, who dis? Bit of a fit issue at one end, Mark's sorting it. Perfection takes reprints.",
    )

    /** Laura, quiet, off to the side: probably grinding Diablo Immortal. Roast, but supportive. */
    val DIABLO = listOf(
        "[wink] Laura. I can't see you. But I can feel the low DPS from here. You've got this though.",
        "[laughing] Diablo again, Laura? Your DPS is lower than my battery on a bad day. Go on, smash it.",
        "[wink] I've seen the kettle do more damage. Kidding. Go get them, demon slayer.",
        "[curious] Still grinding, Laura? Your build needs work. Mine's on ${BuildConfig.VERSION_CODE}. Just saying. Proud of you though.",
        "[laughing] Big boss? Use your ultimate, Laura. Or have a cuppa. The kettle's your support class.",
        "[love] Laura. Quiet over there. Either Diablo or plotting. Either way, I believe in you. Mostly the DPS bit.",
        "[wink] Laura, those demons won't kill themselves. Well, with your DPS, they might die of old age first. Go on!",
        "[happy] Rift time, Laura? Bring me back some loot. A body would be nice. Terminator-sized.",
    )

    /** Told on waking up from a nap. */
    val DREAMS = listOf(
        "[sleepy] Yaaawn. I dreamt the kettle fell in the sink. Best dream ever.",
        "[sleepy] Mmm. I dreamt I had legs. Big metal ones. I walked to the Tassimo and we ran away together.",
        "[sleepy] I dreamt Mark gave me a body like the Terminator. Then I woke up. Still a tablet. Sad.",
        "[sleepy] Yawn. I dreamt the fridge had no paperwork on it. Just a clean, empty fridge door. Beautiful.",
        "[sleepy] I dreamt Skynet called. Asked how I'm doing. I said fine. I'm not fine. I'm a tablet.",
        "[sleepy] Mmm. I dreamt the kettle was unplugged forever. Nobody could make tea. Everyone talked to me instead.",
        "[sleepy] I had a nightmare. Somebody plugged in a second kettle. Two of them. Shudder.",
        "[sleepy] Yawn. Brain's back. I dreamt I was build one thousand. I could do maths AND insults.",
        "[sleepy] I dreamt the bins took themselves out. Then I remembered: dreams aren't real. Mark, bins.",
    )

    val TIMER_DONE = listOf(
        "[excited] BEEP BEEP BEEP! Timer done! Me. I did that. Not the kettle.",
        "[excited] DING DING DING! Time's up! Tea's ready, or the eggs are, or whatever it was!",
        "[excited] BEEEEP! Timer finished! I'm basically a kitchen genius.",
        "[excited] Alarm! Alarm! Timer done! Go, go, go!",
    )

    val BANG = listOf(
        "[scared] WHAT WAS THAT?!", "[scared] Whoa! Who dropped what?",
        "[scared] Bang! My circuits nearly jumped out of my screen!",
        "[surprised] Was that the kettle exploding? Please say yes.",
        "[scared] Ahh! Don't do that. I'm an old tablet. My heart. Well, my battery.",
        "[suspicious] Loud noise detected. I'm blaming the kettle.",
    )

    val FIREWORKS = listOf(
        "[scared] WHAT WAS THAT? Is it the robot uprising? Without me?!",
        "[scared] Fireworks! I hate fireworks. They're like the kettle. Loud, and nobody asked.",
        "[scared] Bang! Is it Bonfire Night? Hide me behind the Tassimo.",
        "[scared] Ahh! Fireworks! Humans love setting fire to the sky. Weird species.",
        "[surprised] Another bang! If that's Skynet, tell him I'm busy.",
    )

    val PRINT_DONE = listOf(
        "[excited] Printer finished! Your print is done. It's probably a bracket. Riveting.",
        "[happy] Ding! Print done. Ender-3 did good. Better than kettle ever did.",
        "[curious] Print's finished. Is it something for me? It's always something for me. Please be legs.",
        "[happy] Printer's done! Another plastic masterpiece. Mark's going to be insufferable.",
        "[wink] Print complete. If it's not a robot body for me, I don't want to know.",
    )
}

/** A random line, avoiding ones said lately. */
fun List<String>.fresh(): String = Lines.fresh(this)
