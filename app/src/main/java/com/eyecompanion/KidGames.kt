package com.eyecompanion

import kotlin.random.Random

/** Simple number and letter games for Chloe (5), and the animals she hatches as rewards. */
object KidGames {

    class Round(val spoken: String, val shown: String, val options: List<String>, val answer: String)

    /** Tiny sums: adding up to 10, taking away from 5 or less, or counting things. */
    fun maths(): Round {
        return when (Random.nextInt(3)) {
            0 -> {
                val a = Random.nextInt(1, 6); val b = Random.nextInt(1, 6)
                val ans = a + b
                Round("What is $a and $b more?", "$a + $b = ?", options(ans, 1..10), ans.toString())
            }
            1 -> {
                val a = Random.nextInt(2, 6); val b = Random.nextInt(1, a)
                val ans = a - b
                Round("$a sweeties. Pip eats $b. How many left?", "$a − $b = ?", options(ans, 0..5), ans.toString())
            }
            else -> {
                val n = Random.nextInt(1, 7)
                val thing = listOf("🍎", "⭐", "🐟", "🎈", "🍓").random()
                Round("How many can you count?", thing.repeat(n), options(n, 1..8), n.toString())
            }
        }
    }

    private val WORDS = listOf(
        "cat" to "🐱", "dog" to "🐶", "sun" to "☀️", "apple" to "🍎", "ball" to "⚽", "fish" to "🐟",
        "egg" to "🥚", "moon" to "🌙", "pig" to "🐷", "bee" to "🐝", "tree" to "🌳", "cake" to "🍰",
        "duck" to "🦆", "hat" to "🎩", "frog" to "🐸", "lion" to "🦁", "rabbit" to "🐰", "unicorn" to "🦄",
    )

    /** "What letter does cat start with?" with three big letters to tap. */
    fun letters(): Round {
        val (word, pic) = WORDS.random()
        val ans = word.first().uppercaseChar().toString()
        val others = ('A'..'Z').map { it.toString() }.filter { it != ans }.shuffled().take(2)
        return Round("What letter does $word start with?", "$pic  $word", (others + ans).shuffled(), ans)
    }

    private fun options(ans: Int, range: IntRange): List<String> {
        val pool = range.filter { it != ans }.shuffled().take(2)
        return (pool + ans).shuffled().map { it.toString() }
    }

    /** Animals that hatch from eggs. Rarer ones near the end. */
    val ANIMALS = listOf(
        "🐣" to "chick", "🐱" to "kitten", "🐶" to "puppy", "🐰" to "bunny", "🐹" to "hamster",
        "🐼" to "panda", "🐨" to "koala", "🦊" to "fox", "🐸" to "froggy", "🐧" to "penguin",
        "🐢" to "turtle", "🦔" to "hedgehog", "🐮" to "cow", "🐷" to "piggy", "🦁" to "lion cub",
        "🐯" to "tiger cub", "🐻" to "bear", "🦉" to "owl", "🦋" to "butterfly", "🐬" to "dolphin",
        "🦄" to "unicorn", "🐲" to "baby dragon",
    )

    fun randomAnimal(): Pair<String, String> =
        if (Random.nextFloat() < 0.08f) ANIMALS.takeLast(2).random() else ANIMALS.dropLast(2).random()
}
