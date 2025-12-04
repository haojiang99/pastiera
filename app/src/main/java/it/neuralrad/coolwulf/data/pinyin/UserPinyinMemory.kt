package it.neuralrad.coolwulf.data.pinyin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Manages user's Pinyin input history to learn preferences and prioritize
 * frequently selected characters/phrases for each pinyin input.
 *
 * Also supports abbreviation learning: when user types "nihao" and selects "你好",
 * the abbreviation "nh" is also recorded, so typing "nh" will suggest "你好" directly.
 */
class UserPinyinMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache: pinyin -> (candidate -> frequency count)
    private val memoryCache = mutableMapOf<String, MutableMap<String, Int>>()

    // Abbreviation cache: abbreviation -> (candidate -> frequency count)
    // e.g., "nh" -> ("你好" -> 5)
    private val abbreviationCache = mutableMapOf<String, MutableMap<String, Int>>()

    init {
        loadFromPreferences()
    }

    /**
     * Records that the user selected a specific candidate for a given pinyin.
     * Also records the abbreviation (first letter of each syllable) for smart matching.
     * @param pinyin The pinyin input (e.g., "ni", "nihao")
     * @param candidate The selected character or phrase (e.g., "你", "你好")
     */
    fun recordSelection(pinyin: String, candidate: String) {
        val normalizedPinyin = pinyin.lowercase().trim()

        // Update in-memory cache
        val candidateMap = memoryCache.getOrPut(normalizedPinyin) { mutableMapOf() }
        candidateMap[candidate] = (candidateMap[candidate] ?: 0) + 1

        Log.d(TAG, "Recorded: '$normalizedPinyin' -> '$candidate' (count: ${candidateMap[candidate]})")

        // Also record abbreviation for smart matching
        // Only for multi-character inputs (abbreviations make sense for phrases)
        if (normalizedPinyin.length >= 2 && candidate.length >= 2) {
            val abbreviation = extractAbbreviation(normalizedPinyin)
            if (abbreviation != null && abbreviation.length >= 2 && abbreviation != normalizedPinyin) {
                val abbrevMap = abbreviationCache.getOrPut(abbreviation) { mutableMapOf() }
                abbrevMap[candidate] = (abbrevMap[candidate] ?: 0) + 1
                Log.d(TAG, "Recorded abbreviation: '$abbreviation' -> '$candidate' (count: ${abbrevMap[candidate]})")
            }
        }

        // Persist to SharedPreferences (async to avoid blocking)
        saveToPreferences()
    }

    /**
     * Extracts abbreviation (first letter of each syllable) from pinyin.
     * e.g., "nihao" -> "nh", "woxiangni" -> "wxn"
     * Uses longest-match syllable parsing.
     * @return The abbreviation, or null if extraction fails
     */
    private fun extractAbbreviation(pinyin: String): String? {
        if (pinyin.isEmpty()) return null

        val syllables = PinyinDictionary.splitIntoSyllables(pinyin)
        if (syllables.isEmpty() || syllables.size < 2) {
            // Not a multi-syllable phrase, abbreviation doesn't make sense
            return null
        }

        // Take first letter of each syllable
        return syllables.map { it.first() }.joinToString("")
    }

    /**
     * Gets candidates for an abbreviation (e.g., "nh" -> ["你好", "你好吗", ...]).
     * Returns candidates sorted by frequency (most used first).
     * @param abbreviation The abbreviation to look up (e.g., "nh")
     * @return List of candidates with their frequencies, sorted by frequency descending
     */
    fun getAbbreviationCandidates(abbreviation: String): List<String> {
        val normalized = abbreviation.lowercase().trim()
        val candidateMap = abbreviationCache[normalized] ?: return emptyList()

        // Sort by frequency descending
        return candidateMap.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    /**
     * Checks if an abbreviation has any recorded candidates.
     */
    fun hasAbbreviationMatch(abbreviation: String): Boolean {
        val normalized = abbreviation.lowercase().trim()
        return abbreviationCache[normalized]?.isNotEmpty() == true
    }

    /**
     * Gets the frequency count for a specific candidate under a given pinyin.
     * Higher count means the user selected it more frequently.
     * @return Frequency count, or 0 if never selected
     */
    fun getFrequency(pinyin: String, candidate: String): Int {
        val normalizedPinyin = pinyin.lowercase().trim()
        return memoryCache[normalizedPinyin]?.get(candidate) ?: 0
    }

    /**
     * Sorts a list of candidates by combining base word frequency and user preference.
     * Process:
     * 1. First apply base word frequency sorting from PinyinDictionary
     * 2. Then apply user preference overrides (user selections bubble to top)
     *
     * User selections are given a significant boost, so once a user selects
     * a less common word, it will appear before the default frequent words.
     *
     * @param pinyin The pinyin input
     * @param candidates The list of candidates to sort
     * @return Sorted list with user preferences first, then by base frequency
     */
    fun sortByFrequency(pinyin: String, candidates: List<String>): List<String> {
        val normalizedPinyin = pinyin.lowercase().trim()

        // First, apply base word frequency sorting
        val frequencySorted = PinyinDictionary.sortByFrequency(normalizedPinyin, candidates)

        // Then, apply user preference overrides
        val userFreqMap = memoryCache[normalizedPinyin]

        // If no user data, return base frequency sorted list
        if (userFreqMap == null || userFreqMap.isEmpty()) {
            return frequencySorted
        }

        // Sort: user selections first (by user frequency), then by base frequency order
        // Phrases (multi-character candidates) get a +2 frequency boost ONLY when unselected (frequency 0)
        // This means a single character needs to be selected 3 times to surpass an unselected phrase
        // Once either is selected, they compete on actual frequency
        return frequencySorted.sortedWith(compareBy(
            // User selections get negative scores (appear first), sorted by frequency descending
            // Phrases get +2 boost only when frequency is 0
            { candidate ->
                val baseFreq = userFreqMap[candidate] ?: 0
                val phraseBoost = if (candidate.length > 1 && baseFreq == 0) 2 else 0
                -(baseFreq + phraseBoost)
            },
            // For items with same user frequency (including 0), maintain base frequency order
            { frequencySorted.indexOf(it) }
        ))
    }

    /**
     * Gets the frequency map for a specific pinyin.
     * @param pinyin The pinyin input
     * @return Map of candidate to frequency count, or empty map if no data
     */
    fun getFrequencyMap(pinyin: String): Map<String, Int> {
        val normalizedPinyin = pinyin.lowercase().trim()
        return memoryCache[normalizedPinyin] ?: emptyMap()
    }

    /**
     * Clears all user memory data.
     */
    fun clearAll() {
        memoryCache.clear()
        abbreviationCache.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "All user memory cleared")
    }

    /**
     * Clears memory for a specific pinyin.
     */
    fun clearPinyin(pinyin: String) {
        val normalizedPinyin = pinyin.lowercase().trim()
        memoryCache.remove(normalizedPinyin)
        saveToPreferences()
        Log.d(TAG, "Memory cleared for: $normalizedPinyin")
    }

    /**
     * Loads user memory data from SharedPreferences into the in-memory cache.
     */
    private fun loadFromPreferences() {
        try {
            // Load pinyin memory
            val jsonString = prefs.getString(KEY_MEMORY_DATA, null)
            if (jsonString != null) {
                val jsonObject = JSONObject(jsonString)
                val pinyinKeys = jsonObject.keys()

                while (pinyinKeys.hasNext()) {
                    val pinyin = pinyinKeys.next()
                    val candidatesJson = jsonObject.getJSONObject(pinyin)
                    val candidateKeys = candidatesJson.keys()

                    val candidateMap = mutableMapOf<String, Int>()
                    while (candidateKeys.hasNext()) {
                        val candidate = candidateKeys.next()
                        val frequency = candidatesJson.getInt(candidate)
                        candidateMap[candidate] = frequency
                    }

                    memoryCache[pinyin] = candidateMap
                }

                Log.d(TAG, "Loaded user memory: ${memoryCache.size} pinyin entries")
            }

            // Load abbreviation memory
            val abbrevJsonString = prefs.getString(KEY_ABBREVIATION_DATA, null)
            if (abbrevJsonString != null) {
                val jsonObject = JSONObject(abbrevJsonString)
                val abbrevKeys = jsonObject.keys()

                while (abbrevKeys.hasNext()) {
                    val abbrev = abbrevKeys.next()
                    val candidatesJson = jsonObject.getJSONObject(abbrev)
                    val candidateKeys = candidatesJson.keys()

                    val candidateMap = mutableMapOf<String, Int>()
                    while (candidateKeys.hasNext()) {
                        val candidate = candidateKeys.next()
                        val frequency = candidatesJson.getInt(candidate)
                        candidateMap[candidate] = frequency
                    }

                    abbreviationCache[abbrev] = candidateMap
                }

                Log.d(TAG, "Loaded abbreviation memory: ${abbreviationCache.size} abbreviation entries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user memory from preferences", e)
        }
    }

    /**
     * Saves the current in-memory cache to SharedPreferences.
     */
    private fun saveToPreferences() {
        try {
            // Save pinyin memory
            val jsonObject = JSONObject()
            for ((pinyin, candidateMap) in memoryCache) {
                val candidatesJson = JSONObject()
                for ((candidate, frequency) in candidateMap) {
                    candidatesJson.put(candidate, frequency)
                }
                jsonObject.put(pinyin, candidatesJson)
            }

            // Save abbreviation memory
            val abbrevJsonObject = JSONObject()
            for ((abbrev, candidateMap) in abbreviationCache) {
                val candidatesJson = JSONObject()
                for ((candidate, frequency) in candidateMap) {
                    candidatesJson.put(candidate, frequency)
                }
                abbrevJsonObject.put(abbrev, candidatesJson)
            }

            prefs.edit()
                .putString(KEY_MEMORY_DATA, jsonObject.toString())
                .putString(KEY_ABBREVIATION_DATA, abbrevJsonObject.toString())
                .apply()

            Log.d(TAG, "Saved user memory to preferences")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving user memory to preferences", e)
        }
    }

    /**
     * Gets statistics about the memory data.
     */
    fun getStats(): MemoryStats {
        val totalPinyin = memoryCache.size
        val totalSelections = memoryCache.values.sumOf { it.values.sum() }
        val totalCandidates = memoryCache.values.sumOf { it.size }
        val totalAbbreviations = abbreviationCache.size
        val totalAbbrevCandidates = abbreviationCache.values.sumOf { it.size }

        return MemoryStats(
            pinyinCount = totalPinyin,
            candidateCount = totalCandidates,
            totalSelections = totalSelections,
            abbreviationCount = totalAbbreviations,
            abbreviationCandidates = totalAbbrevCandidates
        )
    }

    data class MemoryStats(
        val pinyinCount: Int,
        val candidateCount: Int,
        val totalSelections: Int,
        val abbreviationCount: Int = 0,
        val abbreviationCandidates: Int = 0
    )

    /**
     * Exports all user memory data to a JSON string.
     * @return JSON string containing all memory data
     */
    fun exportToJson(): String {
        val exportJson = JSONObject()

        // Export memory cache
        val memoryJson = JSONObject()
        for ((pinyin, candidateMap) in memoryCache) {
            val candidatesJson = JSONObject()
            for ((candidate, frequency) in candidateMap) {
                candidatesJson.put(candidate, frequency)
            }
            memoryJson.put(pinyin, candidatesJson)
        }
        exportJson.put("memory", memoryJson)

        // Export abbreviation cache
        val abbreviationJson = JSONObject()
        for ((abbrev, candidateMap) in abbreviationCache) {
            val candidatesJson = JSONObject()
            for ((candidate, frequency) in candidateMap) {
                candidatesJson.put(candidate, frequency)
            }
            abbreviationJson.put(abbrev, candidatesJson)
        }
        exportJson.put("abbreviations", abbreviationJson)

        return exportJson.toString(2)  // Pretty print with 2-space indentation
    }

    /**
     * Imports user memory data from a JSON string.
     * @param jsonString JSON string containing memory data
     * @param mergeMode If true, merges with existing data (keeps higher frequency); if false, replaces all data
     * @return Number of entries imported
     */
    fun importFromJson(jsonString: String, mergeMode: Boolean = true): Int {
        try {
            val importJson = JSONObject(jsonString)
            var importedCount = 0

            if (!mergeMode) {
                // Clear all existing data
                memoryCache.clear()
                abbreviationCache.clear()
            }

            // Import memory data
            if (importJson.has("memory")) {
                val memoryJson = importJson.getJSONObject("memory")
                val keys = memoryJson.keys()
                while (keys.hasNext()) {
                    val pinyin = keys.next()
                    val candidatesJson = memoryJson.getJSONObject(pinyin)
                    val candidateMap = memoryCache.getOrPut(pinyin) { mutableMapOf() }
                    val candidateKeys = candidatesJson.keys()
                    while (candidateKeys.hasNext()) {
                        val candidate = candidateKeys.next()
                        val frequency = candidatesJson.getInt(candidate)
                        val existingFreq = candidateMap[candidate] ?: 0
                        // Keep higher frequency in merge mode
                        if (mergeMode) {
                            candidateMap[candidate] = maxOf(existingFreq, frequency)
                        } else {
                            candidateMap[candidate] = frequency
                        }
                        importedCount++
                    }
                }
            }

            // Import abbreviation data
            if (importJson.has("abbreviations")) {
                val abbreviationJson = importJson.getJSONObject("abbreviations")
                val keys = abbreviationJson.keys()
                while (keys.hasNext()) {
                    val abbrev = keys.next()
                    val candidatesJson = abbreviationJson.getJSONObject(abbrev)
                    val candidateMap = abbreviationCache.getOrPut(abbrev) { mutableMapOf() }
                    val candidateKeys = candidatesJson.keys()
                    while (candidateKeys.hasNext()) {
                        val candidate = candidateKeys.next()
                        val frequency = candidatesJson.getInt(candidate)
                        val existingFreq = candidateMap[candidate] ?: 0
                        // Keep higher frequency in merge mode
                        if (mergeMode) {
                            candidateMap[candidate] = maxOf(existingFreq, frequency)
                        } else {
                            candidateMap[candidate] = frequency
                        }
                        importedCount++
                    }
                }
            }

            saveToPreferences()
            Log.d(TAG, "Imported $importedCount user memory entries")
            return importedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error importing user memory", e)
            return -1
        }
    }

    /**
     * Gets the total count of all memory entries.
     */
    fun getTotalEntryCount(): Int {
        return memoryCache.values.sumOf { it.size } + abbreviationCache.values.sumOf { it.size }
    }

    companion object {
        private const val TAG = "UserPinyinMemory"
        private const val PREFS_NAME = "pinyin_user_memory"
        private const val KEY_MEMORY_DATA = "memory_data"
        private const val KEY_ABBREVIATION_DATA = "abbreviation_data"

        @Volatile
        private var instance: UserPinyinMemory? = null

        /**
         * Gets the singleton instance of UserPinyinMemory.
         */
        fun getInstance(context: Context): UserPinyinMemory {
            return instance ?: synchronized(this) {
                instance ?: UserPinyinMemory(context.applicationContext).also { instance = it }
            }
        }
    }
}
