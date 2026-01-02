package it.neuralrad.coolwulf.data.pinyin

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Manages the Pinyin-to-Hanzi dictionary for Chinese input.
 * Loads mappings from JSON and provides lookup functionality.
 */
object PinyinDictionary {
    private const val TAG = "PinyinDictionary"
    private const val DICT_FILE = "common/pinyin/pinyin_dict.json"
    private const val PHRASES_FILE = "common/pinyin/pinyin_phrases.json"
    private const val FREQUENCY_FILE = "common/pinyin/word_frequency.json"

    // Map of pinyin syllable -> list of Chinese characters
    private val dictionary = mutableMapOf<String, List<String>>()

    // Map of multi-syllable pinyin -> list of Chinese phrases/words
    private val phrases = mutableMapOf<String, List<String>>()

    // Map of pinyin -> list of candidates sorted by frequency (most common first)
    private val frequencyOrder = mutableMapOf<String, List<String>>()

    // Track if dictionaries have been loaded
    private var isLoaded = false

    // ===== Pre-computed index for fast fuzzy matching =====
    // Map of first initial -> list of (fullPinyin, syllables) pairs
    // This allows O(1) lookup by first initial instead of scanning all phrases
    private val initialIndex = mutableMapOf<String, MutableList<IndexedPhrase>>()

    // Pre-computed syllable splits for all phrase pinyins
    private val phraseSyllables = mutableMapOf<String, List<String>>()

    /**
     * Data class for indexed phrase lookup
     */
    private data class IndexedPhrase(
        val pinyin: String,           // Full pinyin (e.g., "nihao")
        val syllables: List<String>,  // Pre-split syllables (e.g., ["ni", "hao"])
        val firstChars: String        // First char of each syllable for quick match (e.g., "nh")
    )

    /**
     * Loads the pinyin dictionary from assets.
     * Should be called once during initialization.
     */
    fun load(context: Context) {
        if (isLoaded) {
            Log.d(TAG, "Dictionary already loaded")
            return
        }

        try {
            val jsonString = context.assets.open(DICT_FILE).bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)

            val keys = jsonObject.keys()
            var loadedCount = 0

            while (keys.hasNext()) {
                val key = keys.next()
                // Skip metadata fields
                if (key.startsWith("__")) {
                    continue
                }

                val jsonArray = jsonObject.optJSONArray(key)
                if (jsonArray != null) {
                    val characters = mutableListOf<String>()
                    for (i in 0 until jsonArray.length()) {
                        characters.add(jsonArray.getString(i))
                    }
                    dictionary[key] = characters
                    loadedCount++
                }
            }

            isLoaded = true
            Log.d(TAG, "Loaded $loadedCount pinyin syllables from dictionary")

            // Load phrases dictionary
            loadPhrases(context)

            // Load frequency data for smart sorting
            loadFrequencyData(context)

            // Build index for fast fuzzy matching
            buildFuzzyMatchIndex()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading pinyin dictionary", e)
        }
    }

    /**
     * Builds the pre-computed index for fast fuzzy pinyin matching.
     * Called once after loading all dictionaries.
     */
    private fun buildFuzzyMatchIndex() {
        initialIndex.clear()
        phraseSyllables.clear()

        val startTime = System.currentTimeMillis()
        var indexedCount = 0

        // Index all phrases
        for (pinyin in phrases.keys) {
            indexPhrasePinyin(pinyin)
            indexedCount++
        }

        // Also index frequency entries that might not be in phrases
        for (pinyin in frequencyOrder.keys) {
            if (pinyin !in phraseSyllables) {
                val syllables = splitIntoSyllables(pinyin)
                if (syllables.size > 1) {  // Only multi-syllable (phrases)
                    indexPhrasePinyin(pinyin)
                    indexedCount++
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Built fuzzy match index: $indexedCount phrases in ${elapsed}ms")
    }

    /**
     * Indexes a single phrase pinyin for fast lookup.
     */
    private fun indexPhrasePinyin(pinyin: String) {
        val syllables = splitIntoSyllables(pinyin)
        if (syllables.isEmpty()) return

        // Cache the syllable split
        phraseSyllables[pinyin] = syllables

        // Build first-chars string for quick matching (e.g., "nihao" -> "nh")
        val firstChars = syllables.map { it.first() }.joinToString("")

        // Get first initial for indexing
        val firstInitial = getFirstInitialKey(syllables[0])

        // Add to index
        val indexEntry = IndexedPhrase(pinyin, syllables, firstChars)
        initialIndex.getOrPut(firstInitial) { mutableListOf() }.add(indexEntry)
    }

    /**
     * Gets the index key for a syllable (first 1-2 chars covering all initials).
     */
    private fun getFirstInitialKey(syllable: String): String {
        if (syllable.length >= 2) {
            val twoChars = syllable.substring(0, 2)
            if (twoChars in twoLetterInitials) return twoChars
        }
        return syllable.first().toString()
    }

    /**
     * Loads the pinyin phrases dictionary from assets.
     */
    private fun loadPhrases(context: Context) {
        try {
            val jsonString = context.assets.open(PHRASES_FILE).bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)

            val keys = jsonObject.keys()
            var loadedCount = 0

            while (keys.hasNext()) {
                val key = keys.next()
                val jsonArray = jsonObject.optJSONArray(key)
                if (jsonArray != null) {
                    val words = mutableListOf<String>()
                    for (i in 0 until jsonArray.length()) {
                        words.add(jsonArray.getString(i))
                    }
                    phrases[key] = words
                    loadedCount++
                }
            }

            Log.d(TAG, "Loaded $loadedCount pinyin phrases from dictionary")
        } catch (e: Exception) {
            Log.w(TAG, "Error loading pinyin phrases dictionary (optional)", e)
        }
    }

    /**
     * Loads word frequency data from assets for smart candidate sorting.
     */
    private fun loadFrequencyData(context: Context) {
        try {
            val jsonString = context.assets.open(FREQUENCY_FILE).bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)

            val keys = jsonObject.keys()
            var loadedCount = 0

            while (keys.hasNext()) {
                val key = keys.next()
                // Skip metadata fields
                if (key.startsWith("__")) {
                    continue
                }

                val jsonArray = jsonObject.optJSONArray(key)
                if (jsonArray != null) {
                    val candidates = mutableListOf<String>()
                    for (i in 0 until jsonArray.length()) {
                        candidates.add(jsonArray.getString(i))
                    }
                    frequencyOrder[key] = candidates
                    loadedCount++
                }
            }

            Log.d(TAG, "Loaded $loadedCount word frequency entries")
        } catch (e: Exception) {
            Log.w(TAG, "Error loading word frequency data (optional)", e)
        }
    }

    /**
     * Sorts candidates by word frequency using the frequency dictionary.
     * Candidates in the frequency order are ranked first (in that order),
     * followed by remaining candidates in their original order.
     * @param pinyin The pinyin input
     * @param candidates The list of candidates to sort
     * @return Sorted list with most frequent candidates first
     */
    fun sortByFrequency(pinyin: String, candidates: List<String>): List<String> {
        val normalized = pinyin.lowercase().trim()
        val freqOrder = frequencyOrder[normalized] ?: return candidates

        // Create a map of candidate -> frequency rank (lower is more frequent)
        val rankMap = freqOrder.withIndex().associate { (index, value) -> value to index }

        // Sort: candidates in frequency order first, then others maintain original order
        return candidates.sortedWith(compareBy(
            { rankMap[it] ?: Int.MAX_VALUE },  // Frequent words first
            { candidates.indexOf(it) }  // Maintain original order for same rank
        ))
    }

    /**
     * Gets the frequency rank for a specific candidate under a given pinyin.
     * @return Frequency rank (0 = most frequent), or -1 if not in frequency list
     */
    fun getFrequencyRank(pinyin: String, candidate: String): Int {
        val normalized = pinyin.lowercase().trim()
        val freqOrder = frequencyOrder[normalized] ?: return -1
        return freqOrder.indexOf(candidate)
    }

    /**
     * Checks if a pinyin exists in the frequency data (i.e., is a "common" word/phrase).
     * This is used to prioritize common phrases over rare/obscure ones.
     * @param pinyin The pinyin to check
     * @return True if the pinyin has frequency data (is a common word/phrase)
     */
    fun hasFrequencyData(pinyin: String): Boolean {
        val normalized = pinyin.lowercase().trim()
        return frequencyOrder.containsKey(normalized)
    }

    /**
     * Gets character candidates for a given pinyin syllable.
     * @param pinyin The pinyin syllable (e.g., "ni", "hao")
     * @return List of Chinese characters, or empty list if not found
     */
    fun getCandidates(pinyin: String): List<String> {
        if (!isLoaded) {
            Log.w(TAG, "Dictionary not loaded yet")
            return emptyList()
        }

        val normalized = pinyin.lowercase().trim()
        return dictionary[normalized] ?: emptyList()
    }

    /**
     * Gets phrase candidates for a given multi-syllable pinyin.
     * @param pinyin The pinyin string (e.g., "wode", "nihao")
     * @return List of Chinese words/phrases, or empty list if not found
     */
    fun getPhraseCandidates(pinyin: String): List<String> {
        val normalized = pinyin.lowercase().trim()
        return phrases[normalized] ?: emptyList()
    }

    /**
     * Gets phrase candidates whose pinyin starts with the given prefix.
     * Results are sorted by frequency (common phrases first).
     * @param prefix The pinyin prefix to match (e.g., "wos" matches "woshi")
     * @param limit Maximum number of candidates to return
     * @return List of phrases sorted by frequency
     */
    fun getPhraseCandidatesWithPrefix(prefix: String, limit: Int = 9): List<String> {
        if (!isLoaded || prefix.length < 2) {
            return emptyList()
        }

        val normalized = prefix.lowercase().trim()
        val results = mutableListOf<Pair<String, Int>>()  // phrase to frequency rank
        val seen = mutableSetOf<String>()

        // Find all phrase pinyins starting with this prefix (but longer)
        for ((pinyin, phraseList) in phrases) {
            if (pinyin.startsWith(normalized) && pinyin.length > normalized.length) {
                // Get frequency order for this pinyin if available
                val freqOrder = frequencyOrder[pinyin]
                for ((index, phrase) in phraseList.withIndex()) {
                    if (phrase !in seen) {
                        seen.add(phrase)
                        // Use frequency rank if available, otherwise use index
                        val rank = freqOrder?.indexOf(phrase)?.takeIf { it >= 0 } ?: (index + 1000)
                        results.add(phrase to rank)
                    }
                }
            }
        }

        // Sort by frequency rank and return
        return results
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Gets all candidates (both phrases and characters) for the given input.
     * Prioritizes phrase matches, then falls back to longest syllable match.
     * @param input The pinyin input string
     * @return List of candidates (phrases first, then characters)
     */
    fun getAllCandidates(input: String): List<String> {
        if (!isLoaded || input.isEmpty()) {
            return emptyList()
        }

        val normalized = input.lowercase()
        val results = mutableListOf<String>()

        // First, check for exact phrase match
        val phraseCandidates = getPhraseCandidates(normalized)
        if (phraseCandidates.isNotEmpty()) {
            results.addAll(phraseCandidates)
        }

        // Then, get character candidates for the longest syllable
        val longestSyllable = findLongestSyllable(normalized)
        if (longestSyllable != null) {
            val charCandidates = getCandidates(longestSyllable)
            results.addAll(charCandidates)
        }

        return results
    }

    /**
     * Checks if a pinyin syllable exists in the dictionary.
     */
    fun contains(pinyin: String): Boolean {
        val normalized = pinyin.lowercase().trim()
        return dictionary.containsKey(normalized)
    }

    /**
     * Gets all valid pinyin syllables (for validation).
     */
    fun getAllSyllables(): Set<String> {
        return dictionary.keys.toSet()
    }

    /**
     * Finds the longest valid pinyin syllable from the start of the input.
     * For example, "nian" returns "nian", not "ni" + "an".
     * @param input The pinyin string to parse
     * @return The longest valid syllable found, or null if none found
     */
    fun findLongestSyllable(input: String): String? {
        if (input.isEmpty()) return null

        val normalized = input.lowercase()

        // Try from longest to shortest
        for (length in normalized.length downTo 1) {
            val candidate = normalized.substring(0, length)
            if (contains(candidate)) {
                return candidate
            }
        }

        return null
    }

    /**
     * Gets candidates for syllables that start with the given prefix.
     * Useful for single-letter input like "w" -> candidates from "wa", "wo", "wu", "wei", etc.
     * @param prefix The prefix to search for (e.g., "w")
     * @param limit Maximum number of candidates to return
     * @return Combined list of candidates from matching syllables
     */
    fun getCandidatesForPrefix(prefix: String, limit: Int = 30): List<String> {
        if (!isLoaded || prefix.isEmpty()) {
            return emptyList()
        }

        val normalized = prefix.lowercase()
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // Find all syllables starting with this prefix
        val matchingSyllables = dictionary.keys
            .filter { it.startsWith(normalized) }
            .sortedBy { it.length }  // Shorter syllables first (more common)

        // Collect candidates from matching syllables
        for (syllable in matchingSyllables) {
            val candidates = dictionary[syllable] ?: continue
            for (candidate in candidates) {
                if (candidate !in seen) {
                    seen.add(candidate)
                    results.add(candidate)
                    if (results.size >= limit) {
                        return results
                    }
                }
            }
        }

        return results
    }

    /**
     * Gets the first matching syllable for a prefix.
     * Used for determining which pinyin to consume when selecting a candidate.
     * @param prefix The prefix to search for
     * @return The shortest syllable starting with the prefix, or null if none found
     */
    fun getFirstSyllableForPrefix(prefix: String): String? {
        if (!isLoaded || prefix.isEmpty()) {
            return null
        }

        val normalized = prefix.lowercase()

        // If the prefix itself is a valid syllable, return it
        if (contains(normalized)) {
            return normalized
        }

        // Find the shortest syllable starting with this prefix
        return dictionary.keys
            .filter { it.startsWith(normalized) }
            .minByOrNull { it.length }
    }

    /**
     * Splits input into valid pinyin syllables using greedy longest-match.
     * For example, "nihao" -> ["ni", "hao"]
     * @param input The complete pinyin string
     * @return List of syllables, or empty list if parsing fails
     */
    fun splitIntoSyllables(input: String): List<String> {
        if (input.isEmpty()) return emptyList()

        val syllables = mutableListOf<String>()
        var remaining = input.lowercase()

        while (remaining.isNotEmpty()) {
            val syllable = findLongestSyllable(remaining)
            if (syllable != null) {
                syllables.add(syllable)
                remaining = remaining.substring(syllable.length)
            } else {
                // Can't parse remaining input as valid pinyin
                Log.d(TAG, "Cannot parse: '$remaining'")
                return emptyList()
            }
        }

        return syllables
    }

    /**
     * Checks if the dictionary is loaded.
     */
    fun isLoaded(): Boolean = isLoaded

    /**
     * Gets the total number of syllables in the dictionary.
     */
    fun size(): Int = dictionary.size

    // ==================== Partial Pinyin Matching ====================

    /**
     * All valid pinyin syllables, cached for faster lookup.
     */
    private val allSyllables: List<String> by lazy {
        dictionary.keys.toList()
    }

    /**
     * All phrase pinyins, cached for faster lookup.
     */
    private val allPhrasePinyins: List<String> by lazy {
        phrases.keys.toList()
    }

    /**
     * Checks if a partial input can match a full syllable.
     * For example: "sh" matches "shu", "shi", "sha", etc.
     * "shr" matches "shru" (not a valid syllable, but conceptually "shu" + partial "r")
     * @param partial The partial input (e.g., "sh", "shr")
     * @param full The full syllable to match against (e.g., "shu", "shi")
     * @return true if partial can expand to match full
     */
    fun matchesPartialSyllable(partial: String, full: String): Boolean {
        if (partial.isEmpty() || full.isEmpty()) return false
        if (partial.length > full.length) return false
        return full.startsWith(partial)
    }

    /**
     * Valid pinyin initials (声母).
     * These are the consonants that can start a pinyin syllable.
     */
    private val pinyinInitials = setOf(
        "b", "p", "m", "f",
        "d", "t", "n", "l",
        "g", "k", "h",
        "j", "q", "x",
        "zh", "ch", "sh", "r",
        "z", "c", "s",
        "y", "w"
    )

    /**
     * Two-letter initials (声母) that must be matched as a unit.
     */
    private val twoLetterInitials = setOf("zh", "ch", "sh")

    /**
     * Gets all syllables that start with the given partial input.
     * Prioritizes common syllables (those with high-frequency phrases) over rare ones.
     * @param partial The partial syllable input
     * @return List of matching syllables sorted by frequency/commonality
     */
    fun getSyllablesStartingWith(partial: String): List<String> {
        if (partial.isEmpty()) return emptyList()
        val normalized = partial.lowercase()

        // Get all matching syllables
        val matching = allSyllables.filter { it.startsWith(normalized) }

        // Sort by: 1) whether it has frequency data (common), 2) length (shorter first)
        // Syllables with frequency data are more commonly used
        return matching.sortedWith(compareBy(
            { if (frequencyOrder.containsKey(it)) 0 else 1 },  // Prioritize syllables with frequency data
            { it.length }  // Then by length
        ))
    }

    /**
     * Extracts the initial (声母) from a pinyin syllable.
     * For example: "shu" -> "sh", "ni" -> "n", "a" -> ""
     * @param syllable The complete pinyin syllable
     * @return The initial (声母), or empty string for syllables starting with vowels
     */
    fun getInitial(syllable: String): String {
        if (syllable.isEmpty()) return ""
        val lower = syllable.lowercase()

        // Check two-letter initials first
        if (lower.length >= 2) {
            val twoChars = lower.substring(0, 2)
            if (twoChars in twoLetterInitials) return twoChars
        }

        // Check single-letter initials
        val firstChar = lower[0].toString()
        if (firstChar in pinyinInitials) return firstChar

        // Vowel start (a, o, e, i, u, ü) - no initial
        return ""
    }

    /**
     * Splits abbreviated pinyin input into initials (声母).
     * For "nh" -> ["n", "h"]
     * For "shrfa" -> ["sh", "r", "f", "a"]
     * For "srf" -> ["s", "r", "f"]
     *
     * @param input The abbreviated pinyin input
     * @return List of initials extracted from input
     */
    fun splitIntoInitials(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        val lower = input.lowercase()
        val initials = mutableListOf<String>()
        var i = 0

        while (i < lower.length) {
            // Check for two-letter initials first
            if (i + 1 < lower.length) {
                val twoChars = lower.substring(i, i + 2)
                if (twoChars in twoLetterInitials) {
                    initials.add(twoChars)
                    i += 2
                    continue
                }
            }

            // Single character
            initials.add(lower[i].toString())
            i++
        }

        return initials
    }

    /**
     * Data class to hold partial match results with frequency score.
     */
    data class PartialMatchResult(
        val phrase: String,           // The Chinese phrase (e.g., "输入法")
        val fullPinyin: String,       // The full pinyin (e.g., "shurufa")
        val frequencyScore: Int       // Lower is more frequent (0 = most frequent)
    )

    /**
     * Finds phrases that match a partial/abbreviated pinyin input using 声母 (initials) matching.
     * For example: "srf" can match "shurufa" (输入法)
     *              "nh" can match "nihao" (你好)
     *
     * OPTIMIZED: Uses pre-computed index for O(n) where n is matching candidates,
     * instead of O(total phrases) in the unoptimized version.
     *
     * @param input The partial pinyin input (e.g., "srf", "nh")
     * @param maxResults Maximum number of results to return
     * @return List of matching phrases sorted by frequency
     */
    fun getPartialPinyinMatches(input: String, maxResults: Int = 20): List<PartialMatchResult> {
        if (!isLoaded || input.length < 2) return emptyList()

        val normalized = input.lowercase()
        val results = mutableListOf<PartialMatchResult>()
        val seen = mutableSetOf<String>()

        // Get the first initial from input for index lookup
        val firstInitial = getFirstInitialKey(normalized)

        // Quick check: also try single-char key if we used two-char key
        val keysToCheck = if (firstInitial.length == 2) {
            listOf(firstInitial, firstInitial[0].toString())
        } else {
            // For single-char initial, also check two-char initials that start with it
            val possibleTwoChar = twoLetterInitials.filter { it.startsWith(firstInitial) }
            listOf(firstInitial) + possibleTwoChar
        }

        // Search only in the relevant index buckets
        for (key in keysToCheck) {
            val indexedPhrases = initialIndex[key] ?: continue

            for (indexed in indexedPhrases) {
                // Fast pre-check: input must be able to match the first-chars pattern
                if (!canQuickMatch(normalized, indexed)) continue

                // Full match check using pre-computed syllables
                if (matchAgainstPrecomputedSyllables(normalized, indexed.syllables)) {
                    addMatchResults(indexed.pinyin, results, seen)
                }
            }
        }

        // Sort by frequency and return top results
        return results
            .sortedBy { it.frequencyScore }
            .take(maxResults)
    }

    /**
     * Quick pre-filter check before doing full matching.
     * Returns false if input definitely can't match this phrase.
     */
    private fun canQuickMatch(input: String, indexed: IndexedPhrase): Boolean {
        // Input can't be longer than full pinyin
        if (input.length > indexed.pinyin.length) return false

        // First char of input must match first char of first syllable
        if (input[0] != indexed.syllables[0][0]) return false

        return true
    }

    /**
     * Match input against pre-computed syllables (no splitIntoSyllables call needed).
     */
    private fun matchAgainstPrecomputedSyllables(input: String, syllables: List<String>): Boolean {
        if (input.isEmpty()) return true
        if (syllables.isEmpty()) return false

        var inputPos = 0
        var syllableIdx = 0

        while (inputPos < input.length && syllableIdx < syllables.size) {
            val currentSyllable = syllables[syllableIdx]
            val remaining = input.length - inputPos

            // Match as much as possible from input against current syllable
            var matchLen = 0
            val maxMatch = minOf(currentSyllable.length, remaining)
            while (matchLen < maxMatch && currentSyllable[matchLen] == input[inputPos + matchLen]) {
                matchLen++
            }

            if (matchLen == 0) return false

            inputPos += matchLen
            syllableIdx++
        }

        return inputPos >= input.length
    }

    /**
     * Add matching phrases to results list.
     */
    private fun addMatchResults(
        phrasePinyin: String,
        results: MutableList<PartialMatchResult>,
        seen: MutableSet<String>
    ) {
        val phraseList = phrases[phrasePinyin]
        val freqOrder = frequencyOrder[phrasePinyin]

        if (phraseList != null) {
            for ((index, phrase) in phraseList.withIndex()) {
                if (phrase !in seen) {
                    seen.add(phrase)
                    val freqRank = freqOrder?.indexOf(phrase)?.takeIf { it >= 0 } ?: (index + 1000)
                    results.add(PartialMatchResult(phrase, phrasePinyin, freqRank))
                }
            }
        }

        // Also add from frequency order if not in phrases
        if (freqOrder != null && phraseList == null) {
            for ((index, phrase) in freqOrder.withIndex()) {
                if (phrase !in seen) {
                    seen.add(phrase)
                    results.add(PartialMatchResult(phrase, phrasePinyin, index))
                }
            }
        }
    }

    /**
     * Generates possible ways to split input into partial syllables.
     * For "shrfa", possible splits include:
     * - ["sh", "r", "fa"]
     * - ["shr", "fa"]
     * - ["sh", "rf", "a"]
     * - etc.
     */
    private fun generatePartialSyllableSplits(input: String, maxSplits: Int = 50): List<List<String>> {
        if (input.isEmpty()) return emptyList()
        if (input.length == 1) return listOf(listOf(input))

        val results = mutableListOf<List<String>>()

        // Use dynamic programming approach with memoization
        fun generateSplits(remaining: String, current: List<String>, depth: Int) {
            if (results.size >= maxSplits) return
            if (depth > 10) return // Limit recursion depth
            if (remaining.isEmpty()) {
                results.add(current)
                return
            }

            // Try different split lengths (1 to min(6, remaining.length))
            val maxLen = minOf(6, remaining.length)
            for (len in 1..maxLen) {
                val part = remaining.substring(0, len)
                val rest = remaining.substring(len)

                // Only continue if this part could be a partial syllable
                if (couldBePartialSyllable(part)) {
                    generateSplits(rest, current + part, depth + 1)
                }
            }
        }

        generateSplits(input, emptyList(), 0)
        return results
    }

    /**
     * Checks if a string could be a partial pinyin syllable.
     * A partial syllable must start with a valid consonant or vowel combination.
     */
    private fun couldBePartialSyllable(s: String): Boolean {
        if (s.isEmpty()) return false

        // Valid initials (consonants that can start a syllable)
        val validInitials = setOf(
            "b", "p", "m", "f",
            "d", "t", "n", "l",
            "g", "k", "h",
            "j", "q", "x",
            "zh", "ch", "sh", "r",
            "z", "c", "s",
            "y", "w"
        )

        // Valid standalone vowels
        val validVowelStarts = setOf("a", "o", "e", "i", "u", "ü")

        val first = s[0].toString()

        // Check if starts with a valid initial or vowel
        if (first in validVowelStarts) return true
        if (first in validInitials) return true

        // Check for two-letter initials
        if (s.length >= 2) {
            val twoLetters = s.substring(0, 2)
            if (twoLetters in validInitials) return true
        }

        // Check if any syllable starts with this string
        return allSyllables.any { it.startsWith(s) }
    }

    /**
     * Expands a list of partial syllables to possible full syllable combinations.
     * For ["sh", "r", "fa"], might return [["shu", "ru", "fa"], ["shi", "ru", "fa"], ...]
     */
    private fun expandPartialSyllables(partials: List<String>, maxExpansions: Int = 10): List<List<String>> {
        if (partials.isEmpty()) return emptyList()

        val results = mutableListOf<List<String>>()

        fun expand(index: Int, current: List<String>) {
            if (results.size >= maxExpansions) return
            if (index >= partials.size) {
                results.add(current)
                return
            }

            val partial = partials[index]

            // Get all syllables starting with this partial
            val matchingSyllables = getSyllablesStartingWith(partial)

            if (matchingSyllables.isEmpty()) {
                // No matching syllables, skip this split
                return
            }

            // Try each matching syllable (limit to top few for performance)
            for (syllable in matchingSyllables.take(5)) {
                expand(index + 1, current + syllable)
            }
        }

        expand(0, emptyList())
        return results
    }

    /**
     * Gets phrase candidates that match partial pinyin with frequency ordering.
     * This is the main entry point for partial pinyin matching.
     * @param input The partial pinyin input
     * @param limit Maximum number of results
     * @return List of Chinese phrases sorted by frequency
     */
    fun getPartialMatchPhrases(input: String, limit: Int = 9): List<String> {
        val matches = getPartialPinyinMatches(input, limit)
        return matches.map { it.phrase }
    }

    /**
     * Gets all phrase pinyin keys that could match the partial input.
     * This is used for finding potential phrase matches.
     * @param partialPinyin The partial input
     * @return List of full phrase pinyins that could match
     */
    fun getMatchingPhrasePinyins(partialPinyin: String): List<String> {
        if (partialPinyin.isEmpty()) return emptyList()
        val normalized = partialPinyin.lowercase()

        return allPhrasePinyins.filter { fullPinyin ->
            couldPartialMatchFull(normalized, fullPinyin)
        }
    }

    /**
     * Checks if a partial pinyin could match a full pinyin using 声母 (initials) matching.
     *
     * Supports two input modes:
     * 1. Pure initials (声母): "nh" matches "nihao", "srf" matches "shurufa"
     * 2. Mixed syllables + initials: "nih" matches "nihao" ("ni" + "h")
     *
     * Algorithm:
     * - Greedily consume input by matching against each syllable in the full pinyin
     * - For each syllable, the input must start with either:
     *   a) The full syllable (e.g., "ni" matches "ni")
     *   b) A prefix of the syllable starting with its initial (e.g., "n" matches "ni")
     *
     * OPTIMIZED: Uses pre-computed syllables cache when available.
     *
     * @param partial The abbreviated input (e.g., "nh", "nih", "srf")
     * @param full The full pinyin to match against (e.g., "nihao", "shurufa")
     * @return true if partial could be an abbreviation of full
     */
    fun couldPartialMatchFull(partial: String, full: String): Boolean {
        if (partial.isEmpty() || full.isEmpty()) return false
        if (partial.length > full.length) return false

        // First check if partial is a prefix of full (simple case)
        if (full.startsWith(partial)) return true

        // Use pre-computed syllables if available (fast path)
        val syllables = phraseSyllables[full] ?: splitIntoSyllables(full)
        if (syllables.isEmpty()) return false

        // Use greedy matching algorithm
        return greedyMatchPartialAgainstSyllables(partial.lowercase(), syllables)
    }

    /**
     * Greedy matching algorithm for partial pinyin against syllables.
     *
     * For each syllable, tries to match the longest prefix of input that:
     * 1. Is the complete syllable (e.g., "ni" matches "ni")
     * 2. OR is a proper prefix of the syllable (e.g., "n" matches "ni")
     *
     * Examples:
     * - "nih" against ["ni", "hao"]: "ni" matches "ni", "h" matches "hao" ✓
     * - "nh" against ["ni", "hao"]: "n" matches "ni", "h" matches "hao" ✓
     * - "srf" against ["shu", "ru", "fa"]: "s" matches "shu", "r" matches "ru", "f" matches "fa" ✓
     * - "shrfa" against ["shu", "ru", "fa"]: "sh" matches "shu", "r" matches "ru", "fa" matches "fa" ✓
     */
    private fun greedyMatchPartialAgainstSyllables(partial: String, syllables: List<String>): Boolean {
        if (partial.isEmpty()) return true
        if (syllables.isEmpty()) return false

        var partialPos = 0
        var syllableIdx = 0

        while (partialPos < partial.length && syllableIdx < syllables.size) {
            val currentSyllable = syllables[syllableIdx]
            val remaining = partial.substring(partialPos)

            // Try to match as much as possible from remaining input against current syllable
            // The remaining input must START the syllable (be a prefix of it)
            var matchLen = 0
            while (matchLen < currentSyllable.length &&
                   matchLen < remaining.length &&
                   currentSyllable[matchLen] == remaining[matchLen]) {
                matchLen++
            }

            if (matchLen == 0) {
                // No match at all - fail
                return false
            }

            // Move past the matched portion
            partialPos += matchLen
            syllableIdx++
        }

        // Success if we consumed all of the partial input
        return partialPos >= partial.length
    }
}
