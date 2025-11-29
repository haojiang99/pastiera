package it.neuralrad.coolwulf.data.shuangpin

/**
 * Converts Shuangpin (双拼) input to standard Pinyin.
 * Uses the 小鹤双拼 (Xiaohe Shuangpin) scheme.
 *
 * In Shuangpin, each Chinese syllable is typed with exactly 2 keys:
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
object ShuangpinConverter {
    private const val TAG = "ShuangpinConverter"

    // Map of second key to final vowels (韵母)
    // Some keys map to multiple finals depending on the initial
    private val keyToFinals = mapOf(
        'q' to listOf("iu"),
        'w' to listOf("ei"),
        'e' to listOf("e"),
        'r' to listOf("uan", "er"),
        't' to listOf("ue", "ve"),  // üe for j/q/x/y, ue for others
        'y' to listOf("un"),
        'o' to listOf("uo", "o"),
        'p' to listOf("ie"),
        'a' to listOf("a"),
        's' to listOf("ong", "iong"),  // iong only after j/q/x/y
        'd' to listOf("ai"),
        'f' to listOf("en"),
        'g' to listOf("eng"),
        'h' to listOf("ang"),
        'j' to listOf("an"),
        'k' to listOf("ing", "uai"),  // uai only after specific initials
        'l' to listOf("uang", "iang"),  // iang after j/q/x/y, uang after others
        'z' to listOf("ou"),
        'x' to listOf("ia", "ua"),  // ia after j/q/x/y, ua after others
        'c' to listOf("ao"),
        'v' to listOf("ui", "v"),  // v (ü) for n/l
        'b' to listOf("in"),
        'n' to listOf("iao"),
        'm' to listOf("ian"),
        'i' to listOf("i"),
        'u' to listOf("u")
    )

    // Initials that take special finals
    private val jqxyInitials = setOf("j", "q", "x", "y")
    private val nlInitials = setOf("n", "l")

    // Map of first key to initial consonants (声母)
    // Most are same as pinyin, except zh/ch/sh
    private val keyToInitial = mapOf(
        'v' to "zh",
        'i' to "ch",
        'u' to "sh"
        // All other letters map to themselves as initials
    )

    // Valid pinyin syllables (we'll validate against PinyinDictionary)
    // This is a subset used for quick validation
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
     * Converts a two-character Shuangpin code to standard Pinyin.
     * @param shuangpin The two-character Shuangpin input (e.g., "nh" for "ni hao" initial)
     * @return The corresponding Pinyin syllable, or null if invalid
     */
    fun toPinyin(shuangpin: String): String? {
        if (shuangpin.length != 2) {
            return null
        }

        val firstKey = shuangpin[0].lowercaseChar()
        val secondKey = shuangpin[1].lowercaseChar()

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
     * Zero-initial rules:
     * - Single vowel: key + same key (aa, oo, ee)
     * - Double vowel: first letter + second letter (ai, ei, ou)
     * - Triple vowel ending in ng: first letter + final key (ah for ang)
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
        // Map of zero-initial Shuangpin codes to Pinyin
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
            // Additional zero-initial finals
            "ad" to "ai",
            "af" to "en",  // Not standard but some schemes use it
            "aj" to "an",
            "ac" to "ao",
            "az" to "ou",
            "oh" to "ang",  // o + h for ang when starting with o sound
            "og" to "ong"   // o + g for ong
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
            's' -> {
                // iong for j/q/x/y, ong for others
                if (initial in jqxyInitials) "iong" else "ong"
            }
            'k' -> {
                // uai for some initials, ing for others
                // In 小鹤, k primarily maps to ing; uai is less common
                if (initial in setOf("g", "k", "h", "zh", "ch", "sh", "z", "c", "s")) "uai" else "ing"
            }
            'l' -> {
                // iang for j/q/x/y, uang for others
                if (initial in jqxyInitials) "iang" else "uang"
            }
            'x' -> {
                // ia for j/q/x/y, ua for others
                if (initial in jqxyInitials) "ia" else "ua"
            }
            'v' -> {
                // ü (v) for n/l/j/q/x/y, ui for others
                if (initial in nlInitials || initial in jqxyInitials) "v" else "ui"
            }
            'o' -> {
                // uo for most, o for b/p/m/f
                if (initial in setOf("b", "p", "m", "f")) "o" else "uo"
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
     * Converts a complete Shuangpin input string to Pinyin syllables.
     * Each pair of characters is one syllable.
     * @param input The Shuangpin input string
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
     * Gets the complete Pinyin string from Shuangpin input.
     * @param input The Shuangpin input string
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
     * Checks if a single character is a valid Shuangpin initial key.
     */
    fun isValidInitialKey(key: Char): Boolean {
        return key.lowercaseChar() in 'a'..'z'
    }

    /**
     * Checks if a single character is a valid Shuangpin final key.
     */
    fun isValidFinalKey(key: Char): Boolean {
        return keyToFinals.containsKey(key.lowercaseChar())
    }

    /**
     * Gets the display help for a key (what it represents in Shuangpin).
     * Useful for showing hints in the status bar.
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
}
