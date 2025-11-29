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
        } catch (e: Exception) {
            Log.e(TAG, "Error loading pinyin dictionary", e)
        }
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
}
