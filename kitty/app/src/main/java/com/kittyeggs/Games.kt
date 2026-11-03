package com.kittyeggs

import kotlin.random.Random

/**
 * Questions for Chloe. Every game has levels 1..4 and moves up when she's doing well, down if it's too hard.
 * No take-aways anywhere.
 */
object Games {

    /** A tap-the-answer question. [picture] is shown big (emoji / sums), [options] are the buttons. */
    class Question(val spoken: String, val picture: String, val options: List<String>, val answer: String,
                   val spell: String? = null, val praise: String? = null)

    // ---------------- numbers ----------------
    private val THINGS = listOf("🐱" to "kittens", "🐟" to "fish", "⭐" to "stars", "🍎" to "apples", "🎈" to "balloons",
        "🐶" to "puppies", "🍓" to "strawberries", "🦋" to "butterflies", "🐣" to "chicks", "🧁" to "cupcakes")
    private val NUM_WORDS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")

    fun numbers(level: Int): Question = when (level) {
        1 -> listOf(::count5, ::findNumber5).random()()
        2 -> listOf({ count(10) }, { add(5) }, { next(10) }).random()()
        3 -> listOf({ add(10) }, { next(20) }, { find(20) }, { count(12) }).random()()
        else -> listOf({ add(20) }, { skip() }, { add(10) }, { bigger() }).random()()
    }

    private fun count5() = count(5)
    private fun findNumber5() = find(5)

    private fun count(max: Int): Question {
        val n = Random.nextInt(1, max + 1); val (pic, name) = THINGS.random()
        val rows = pic.repeat(n).chunked(pic.length * 5).joinToString("\n")
        return Question("How many $name can you count?", rows, options(n, 1..max + 2), n.toString())
    }
    private fun add(maxSum: Int): Question {
        val ans = Random.nextInt(2, maxSum + 1); val a = Random.nextInt(1, ans); val b = ans - a
        val (pic, name) = THINGS.random()
        val shown = if (ans <= 10) "${pic.repeat(a)}  +  ${pic.repeat(b)}\n$a + $b = ?" else "$a + $b = ?"
        return Question("$a $name and $b more $name. How many altogether?", shown, options(ans, 1..maxSum + 2), ans.toString())
    }
    private fun find(max: Int): Question {
        val n = Random.nextInt(1, max + 1)
        return Question("Can you find the number ${NUM_WORDS[n]}?", "🔍", options(n, 1..max, 4), n.toString())
    }
    private fun next(max: Int): Question {
        val s = Random.nextInt(1, max - 2)
        return Question("What number comes next? ${s}, ${s + 1}, ${s + 2}…", "$s, ${s + 1}, ${s + 2}, ?", options(s + 3, 1..max + 1), (s + 3).toString())
    }
    private fun skip(): Question {
        val step = listOf(2, 5, 10).random(); val s = step * Random.nextInt(0, 4)
        val seq = listOf(s, s + step, s + 2 * step)
        return Question("Counting in ${NUM_WORDS[step]}s! What comes next?", seq.joinToString(", ") + ", ?",
            listOf(s + 3 * step, s + 3 * step + 1, s + 3 * step - 1, s + 4 * step).distinct().take(3).shuffled().map { it.toString() }, (s + 3 * step).toString())
    }
    private fun bigger(): Question {
        val a = Random.nextInt(1, 20); var b = Random.nextInt(1, 20); while (b == a) b = Random.nextInt(1, 20)
        return Question("Which number is bigger?", "🐘", listOf(a, b).map { it.toString() }, maxOf(a, b).toString())
    }
    private fun options(ans: Int, range: IntRange, n: Int = 3): List<String> =
        (range.filter { it != ans && it >= 0 }.shuffled().take(n - 1) + ans).shuffled().map { it.toString() }

    // ---------------- words ----------------
    val THREE = listOf("cat" to "🐱", "dog" to "🐶", "pig" to "🐷", "sun" to "☀️", "hat" to "🎩", "bus" to "🚌", "bed" to "🛏️",
        "cup" to "🥤", "fox" to "🦊", "bee" to "🐝", "egg" to "🥚", "cow" to "🐮", "owl" to "🦉", "ant" to "🐜", "bat" to "🦇",
        "hen" to "🐔", "box" to "📦", "car" to "🚗", "pen" to "🖊️", "bug" to "🐛", "red" to "🔴", "map" to "🗺️", "jam" to "🍯", "van" to "🚐")
    val FOUR = listOf("fish" to "🐟", "frog" to "🐸", "duck" to "🦆", "cake" to "🍰", "star" to "⭐", "moon" to "🌙", "bear" to "🐻",
        "lion" to "🦁", "ball" to "⚽", "boat" to "⛵", "tree" to "🌳", "book" to "📖", "milk" to "🥛", "bell" to "🔔", "goat" to "🐐",
        "rain" to "🌧️", "shoe" to "👟", "sock" to "🧦", "door" to "🚪", "nose" to "👃", "hand" to "✋", "kite" to "🪁", "cake" to "🎂")
    private val RHYMES = listOf(
        listOf("cat" to "🐱", "hat" to "🎩", "bat" to "🦇"), listOf("dog" to "🐶", "frog" to "🐸"), listOf("bee" to "🐝", "tree" to "🌳"),
        listOf("car" to "🚗", "star" to "⭐"), listOf("cake" to "🍰", "snake" to "🐍"), listOf("boat" to "⛵", "goat" to "🐐"),
        listOf("bell" to "🔔", "shell" to "🐚"), listOf("fox" to "🦊", "box" to "📦", "socks" to "🧦"), listOf("hen" to "🐔", "pen" to "🖊️"),
        listOf("moon" to "🌙", "spoon" to "🥄"), listOf("bear" to "🐻", "pear" to "🍐", "chair" to "🪑"), listOf("mouse" to "🐭", "house" to "🏠"))

    fun words(level: Int): Question = when (level) {
        1 -> listOf({ firstLetter(THREE) }, { firstLetter(FOUR) }).random()()
        2 -> listOf({ missing(THREE) }, { rhyme() }, { firstLetter(FOUR) }).random()()
        3 -> listOf({ spell(THREE) }, { rhyme() }, { read(THREE) }, { missing(FOUR) }).random()()
        else -> listOf({ spell(FOUR) }, { read(FOUR) }, { rhyme() }, { spell(THREE) }).random()()
    }

    private fun letters(except: Set<Char>, n: Int) = ('a'..'z').filter { it !in except && it !in "qxz" }.shuffled().take(n).map { it.toString() }

    private fun firstLetter(bank: List<Pair<String, String>>): Question {
        val (w, pic) = bank.random(); val ans = w.first().toString()
        return Question("What sound does $w start with?", "$pic\n$w", (letters(setOf(w.first()), 2) + ans).shuffled().map { it.uppercase() }, ans.uppercase())
    }
    private fun missing(bank: List<Pair<String, String>>): Question {
        val (w, pic) = bank.random(); val i = Random.nextInt(w.length); val ans = w[i].toString()
        val shown = w.substring(0, i) + "_" + w.substring(i + 1)
        return Question("Which letter is missing from $w?", "$pic\n$shown", (letters(setOf(w[i]), 2) + ans).shuffled(), ans)
    }
    private fun rhyme(): Question {
        val group = RHYMES.random(); val (target, tPic) = group.random(); val (ans, aPic) = group.filter { it.first != target }.random()
        val others = RHYMES.filter { it !== group }.shuffled().take(2).map { it.random() }
        val opts = (others + (ans to aPic)).shuffled()
        return Question("Which one rhymes with $target?", "$tPic\n$target", opts.map { "${it.second}\n${it.first}" }, "$aPic\n$ans")
    }
    private fun read(bank: List<Pair<String, String>>): Question {
        val pick = bank.shuffled().take(3); val (w, pic) = pick[0]
        return Question("Can you read this word? Find the picture!", w, pick.shuffled().map { it.second }, pic)
    }
    /** Tap the letters in order to spell the word. */
    private fun spell(bank: List<Pair<String, String>>): Question {
        val (w, pic) = bank.random()
        val tiles = (w.map { it.toString() } + letters(w.toSet(), 1)).shuffled()
        return Question("Can you spell $w?", pic, tiles, w, spell = w)
    }

    // ---------------- talking ----------------
    /** Short, clear words to say, easiest first. */
    val SAY = listOf(
        listOf("cat" to "🐱", "dog" to "🐶", "cow" to "🐮", "pig" to "🐷", "sun" to "☀️", "bee" to "🐝", "car" to "🚗", "ball" to "⚽", "moon" to "🌙", "fish" to "🐟"),
        listOf("apple" to "🍎", "duck" to "🦆", "frog" to "🐸", "cake" to "🍰", "star" to "⭐", "bear" to "🐻", "boat" to "⛵", "tree" to "🌳", "milk" to "🥛", "book" to "📖"),
        listOf("banana" to "🍌", "rabbit" to "🐰", "tiger" to "🐯", "rainbow" to "🌈", "flower" to "🌸", "penguin" to "🐧", "pizza" to "🍕", "balloon" to "🎈", "teddy" to "🧸", "snowman" to "⛄"),
        listOf("butterfly" to "🦋", "elephant" to "🐘", "dinosaur" to "🦕", "unicorn" to "🦄", "strawberry" to "🍓", "crocodile" to "🐊", "octopus" to "🐙", "Christmas tree" to "🎄", "ice cream" to "🍦", "spaghetti" to "🍝"),
    )

    // ---------------- rewards ----------------
    val ANIMALS = listOf("🐣" to "chick", "🐱" to "kitten", "🐶" to "puppy", "🐰" to "bunny", "🐹" to "hamster", "🐼" to "panda",
        "🐨" to "koala", "🦊" to "fox", "🐸" to "froggy", "🐧" to "penguin", "🐢" to "turtle", "🦔" to "hedgehog", "🐮" to "cow",
        "🐷" to "piggy", "🦁" to "lion cub", "🐯" to "tiger cub", "🐻" to "bear", "🦉" to "owl", "🦋" to "butterfly", "🐬" to "dolphin",
        "🐙" to "octopus", "🦒" to "giraffe", "🐘" to "baby elephant", "🦜" to "parrot", "🦄" to "unicorn", "🐲" to "baby dragon")
    fun randomAnimal(): Pair<String, String> =
        if (Random.nextFloat() < 0.08f) ANIMALS.takeLast(2).random() else ANIMALS.dropLast(2).random()

    val PRAISE = listOf("Well done, Chloe!", "Clever girl!", "Purrfect!", "You're a superstar, Chloe!", "Amazing!",
        "Meow! That's right!", "Wow, Chloe, you're so clever!", "Brilliant!", "Paw-some!", "Yay! You did it!", "Super duper!")
    val NEARLY = listOf("Ooh, nearly! Try again.", "So close! Have another go.", "Hmm, not that one. You can do it!",
        "Good try! Try another one.")
    val TRY_PRAISE = listOf("Great try, Chloe!", "Lovely talking!", "Ooh, good trying!", "I love hearing you, Chloe!")
}
