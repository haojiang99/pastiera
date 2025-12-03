package it.neuralrad.coolwulf.data.ziranma

/**
 * Converts Ziranma (自然码) input to standard Pinyin.
 * 自然码 is a Shuangpin scheme where each Chinese syllable is typed with exactly 2 keys.
 *
 * In Ziranma:
 * - First key: Initial consonant (声母)
 * - Second key: Final vowel (韵母)
 *
 * Special initials:
 * - zh → v
 * - ch → i
 * - sh → u
 *
 * Zero initial (零声母) rules:
 * - Single vowel finals: first letter + final key (e.g., a=aa, o=oo, e=ee)
 * - Double letter finals: first letter + second letter (e.g., ai=ai, ei=ei, ou=ou, er=er)
 * - Triple letter finals: first letter + final key (e.g., ang=ah)
 */
object ZiranmaConverter {
    private const val TAG = "ZiranmaConverter"

    // Map of second key to final vowels (韵母) for 自然码
    // Key differences from 小鹤: different final mappings
    private val keyToFinals = mapOf(
        'q' to listOf("iu"),
        'w' to listOf("ia", "ua"),  // ia after j/q/x/y, ua after others
        'e' to listOf("e"),
        'r' to listOf("uan", "er"),
        't' to listOf("ue", "ve"),  // üe for j/q/x/y, ue for others
        'y' to listOf("ing", "uai"),  // uai for specific initials, ing for others
        'o' to listOf("uo", "o"),
        'p' to listOf("un"),
        'a' to listOf("a"),
        's' to listOf("ong", "iong"),  // iong only after j/q/x/y
        'd' to listOf("iang", "uang"),  // iang after j/q/x/y, uang after others
        'f' to listOf("en"),
        'g' to listOf("eng"),
        'h' to listOf("ang"),
        'j' to listOf("an"),
        'k' to listOf("ao"),
        'l' to listOf("ai"),
        'z' to listOf("ei"),
        'x' to listOf("ie"),
        'c' to listOf("iao"),
        'v' to listOf("ui", "v"),  // v (ü) for n/l
        'b' to listOf("ou"),
        'n' to listOf("in"),
        'm' to listOf("ian"),
        'i' to listOf("i"),
        'u' to listOf("u")
    )

    // Initials that take special finals
    private val jqxyInitials = setOf("j", "q", "x", "y")
    private val nlInitials = setOf("n", "l")

    // Map of first key to initial consonants (声母)
    // Same as 小鹤: zh/ch/sh mapped to v/i/u
    private val keyToInitial = mapOf(
        'v' to "zh",
        'i' to "ch",
        'u' to "sh"
        // All other letters map to themselves as initials
    )

    // Valid pinyin syllables
    private val validInitials = setOf(
        "b", "p", "m", "f",
        "d", "t", "n", "l",
        "g", "k", "h",
        "j", "q", "x",
        "zh", "ch", "sh", "r",
        "z", "c", "s",
        "y", "w"
    )

    // Zero-initial vowels (韵母 that can stand alone)
    private val zeroInitialVowels = setOf(
        "a", "o", "e", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng", "er",
        "i", "u", "v"  // ü represented as v
    )

    /**
     * Converts a two-character Ziranma code to standard Pinyin.
     * @param ziranma The two-character Ziranma input
     * @return The corresponding Pinyin syllable, or null if invalid
     */
    fun toPinyin(ziranma: String): String? {
        if (ziranma.length != 2) {
            return null
        }

        val firstKey = ziranma[0].lowercaseChar()
        val secondKey = ziranma[1].lowercaseChar()

        // Determine initial
        val initial = getInitial(firstKey)

        // Handle zero-initial case (vowel-only syllables)
        if (initial == null || isZeroInitialKey(firstKey, secondKey)) {
            return getZeroInitialPinyin(firstKey, secondKey)
        }

        // Get the final based on the second key and initial
        val final = getFinal(secondKey, initial) ?: return null

        // Combine initial and final
        val pinyin = combinePinyin(initial, final)

        return pinyin
    }

    /**
     * Gets the initial consonant from the first key.
     */
    private fun getInitial(key: Char): String? {
        // Check special mappings first
        keyToInitial[key]?.let { return it }

        // Check if it's a valid initial
        val initial = key.toString()
        return if (initial in validInitials || key.isLetter()) {
            initial
        } else {
            null
        }
    }

    /**
     * Checks if this is a zero-initial (vowel-only) syllable.
     */
    private fun isZeroInitialKey(firstKey: Char, secondKey: Char): Boolean {
        // Vowel keys that can be zero-initial
        val vowelKeys = setOf('a', 'o', 'e')
        return firstKey in vowelKeys
    }

    /**
     * Gets pinyin for zero-initial syllables.
     */
    private fun getZeroInitialPinyin(firstKey: Char, secondKey: Char): String? {
        // Map of zero-initial Ziranma codes to Pinyin
        val zeroInitialMap = mapOf(
            // Single vowel (key + same key)
            "aa" to "a",
            "oo" to "o",
            "ee" to "e",
            // Double vowels (first letter + second letter)
            "ai" to "ai",
            "ei" to "ei",
            "ao" to "ao",
            "ou" to "ou",
            "an" to "an",
            "en" to "en",
            "er" to "er",
            // Triple vowels (first letter + final key)
            "ah" to "ang",
            "eg" to "eng",
            // Additional zero-initial finals using Ziranma mapping
            "al" to "ai",  // l=ai in Ziranma
            "af" to "en",  // f=en in Ziranma
            "aj" to "an",  // j=an in Ziranma
            "ak" to "ao",  // k=ao in Ziranma
            "ab" to "ou",  // b=ou in Ziranma
            "og" to "ong"  // o + g for ong
        )

        val code = "$firstKey$secondKey"
        return zeroInitialMap[code]
    }

    /**
     * Gets the final vowel from the second key, considering the initial.
     */
    private fun getFinal(key: Char, initial: String): String? {
        val finals = keyToFinals[key] ?: return null

        if (finals.isEmpty()) return null
        if (finals.size == 1) return finals[0]

        // Handle special cases based on initial
        return when (key) {
            't' -> {
                // üe (ve) for j/q/x/y, ue for l/n
                if (initial in jqxyInitials) "ue" else if (initial in nlInitials) "ve" else "ue"
            }
            'w' -> {
                // ia for j/q/x/y, ua for others
                if (initial in jqxyInitials) "ia" else "ua"
            }
            's' -> {
                // iong for j/q/x/y, ong for others
                if (initial in jqxyInitials) "iong" else "ong"
            }
            'y' -> {
                // uai for some initials, ing for others
                if (initial in setOf("g", "k", "h", "zh", "ch", "sh", "z", "c", "s")) "uai" else "ing"
            }
            'd' -> {
                // iang for j/q/x/y and n/l (niang, liang are valid; nuang, luang are not)
                // uang for g/k/h/zh/ch/sh/z/c/s/d/w/r (guang, kuang, huang, zhuang, chuang, shuang)
                if (initial in jqxyInitials || initial in nlInitials) "iang" else "uang"
            }
            'v' -> {
                // ü (v) for n/l/j/q/x/y, ui for others
                if (initial in nlInitials || initial in jqxyInitials) "v" else "ui"
            }
            'o' -> {
                // uo for most, o for b/p/m/f/w
                if (initial in setOf("b", "p", "m", "f", "w")) "o" else "uo"
            }
            'r' -> {
                // er standalone, uan for others
                finals[0]  // Default to uan
            }
            else -> finals[0]
        }
    }

    /**
     * Combines initial and final into a valid pinyin syllable.
     * Handles special combinations like j/q/x + ü → ju/qu/xu (not jv/qv/xv)
     */
    private fun combinePinyin(initial: String, final: String): String {
        var result = initial + final

        // Handle ü (v) combinations
        // j/q/x/y + ü → u (e.g., jv → ju)
        if (final == "v" && initial in jqxyInitials) {
            result = initial + "u"
        }
        // j/q/x/y + üe → ue (e.g., jve → jue)
        else if (final == "ve" && initial in jqxyInitials) {
            result = initial + "ue"
        }
        // n/l + ü stays as nü/lü but we represent as nv/lv in pinyin
        else if (final == "v" && initial in nlInitials) {
            result = initial + "v"
        }

        // Handle iong → yong when initial is y
        if (final == "iong" && initial == "y") {
            result = "yong"
        }

        return result
    }

    /**
     * Converts a complete Ziranma input string to Pinyin syllables.
     * Each pair of characters is one syllable.
     * @param input The Ziranma input string
     * @return List of Pinyin syllables, or empty list if invalid
     */
    fun toPinyinSyllables(input: String): List<String> {
        if (input.isEmpty()) return emptyList()

        val syllables = mutableListOf<String>()
        var i = 0

        while (i < input.length) {
            if (i + 1 < input.length) {
                val pair = input.substring(i, i + 2)
                val pinyin = toPinyin(pair)
                if (pinyin != null) {
                    syllables.add(pinyin)
                } else {
                    // Invalid pair, return what we have so far
                    break
                }
                i += 2
            } else {
                // Single character remaining - incomplete
                break
            }
        }

        return syllables
    }

    /**
     * Gets the complete Pinyin string from Ziranma input.
     * @param input The Ziranma input string
     * @return The combined Pinyin string (without spaces), or null if invalid
     */
    fun toPinyinString(input: String): String? {
        val syllables = toPinyinSyllables(input)
        return if (syllables.isNotEmpty()) {
            syllables.joinToString("")
        } else {
            null
        }
    }

    /**
     * Checks if a single character is a valid Ziranma initial key.
     */
    fun isValidInitialKey(key: Char): Boolean {
        return key.lowercaseChar() in 'a'..'z'
    }

    /**
     * Checks if a single character is a valid Ziranma final key.
     */
    fun isValidFinalKey(key: Char): Boolean {
        return keyToFinals.containsKey(key.lowercaseChar())
    }

    /**
     * Gets the display help for a key (what it represents in Ziranma).
     */
    fun getKeyHelp(key: Char): String {
        val lowerKey = key.lowercaseChar()
        val parts = mutableListOf<String>()

        // Check if it's a special initial
        keyToInitial[lowerKey]?.let { parts.add("声母:$it") }

        // Check finals
        keyToFinals[lowerKey]?.let { finals ->
            parts.add("韵母:${finals.joinToString("/")}")
        }

        return parts.joinToString(" ")
    }

    /**
     * Gets all possible pinyin prefixes for a single Ziranma key.
     */
    fun getPossiblePinyinPrefixes(key: Char): List<String> {
        val lowerKey = key.lowercaseChar()
        val prefixes = mutableListOf<String>()

        // Check special initial mappings first (v→zh, i→ch, u→sh)
        keyToInitial[lowerKey]?.let { specialInitial ->
            prefixes.add(specialInitial)
        }

        // Also add the key itself as a potential initial
        if (lowerKey.isLetter() && lowerKey != 'v') {
            val keyStr = lowerKey.toString()
            if (keyStr !in prefixes) {
                prefixes.add(keyStr)
            }
        }

        // Handle vowel keys that can start zero-initial syllables
        when (lowerKey) {
            'a' -> {
                if ("a" !in prefixes) prefixes.add("a")
            }
            'o' -> {
                if ("o" !in prefixes) prefixes.add("o")
            }
            'e' -> {
                if ("e" !in prefixes) prefixes.add("e")
            }
        }

        return prefixes
    }

    /**
     * Gets all possible complete pinyin syllables that start with the given initial.
     */
    fun getPossibleSyllablesForInitial(initial: String): List<String> {
        val syllables = mutableListOf<String>()

        // Combine initial with all possible finals
        for ((_, finals) in keyToFinals) {
            for (final in finals) {
                val candidate = combinePinyin(initial, final)
                if (candidate !in syllables && isValidPinyinSyllable(candidate)) {
                    syllables.add(candidate)
                }
            }
        }

        return syllables
    }

    /**
     * Basic validation of pinyin syllable.
     */
    private fun isValidPinyinSyllable(syllable: String): Boolean {
        return syllable.isNotEmpty() && syllable.length <= 6
    }
}
