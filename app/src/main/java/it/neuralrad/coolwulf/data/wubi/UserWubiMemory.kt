package it.neuralrad.coolwulf.data.wubi

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Manages user's Wubi input history to learn preferences and prioritize
 * frequently selected characters/phrases for each Wubi code.
 *
 * Also supports abbreviation learning: when user types multi-character phrases,
 * the first letter of each character's Wubi code is recorded as an abbreviation.
 * This allows typing abbreviated codes to suggest previously typed phrases.
 */
class UserWubiMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache: wubiCode -> (character/phrase -> frequency count)
    private val memoryCache = mutableMapOf<String, MutableMap<String, Int>>()

    // Abbreviation cache: abbreviation -> (phrase -> frequency count)
    // e.g., for Wubi: "wq" -> ("我去" -> 5) where w is first letter of 我's code, q is first letter of 去's code
    private val abbreviationCache = mutableMapOf<String, MutableMap<String, Int>>()

    init {
        loadFromPreferences()
    }

    /**
     * Records that the user selected a specific character/phrase for a given Wubi code.
     * Note: Abbreviation learning is disabled for Wubi mode.
     * @param wubiCode The Wubi code input (e.g., "gggg", "wq")
     * @param character The selected character/phrase (e.g., "王", "我")
     */
    fun recordSelection(wubiCode: String, character: String) {
        val normalizedCode = wubiCode.lowercase().trim()
        if (normalizedCode.isEmpty() || character.isEmpty()) return

        // Update in-memory cache
        val charMap = memoryCache.getOrPut(normalizedCode) { mutableMapOf() }
        charMap[character] = (charMap[character] ?: 0) + 1

        Log.d(TAG, "Recorded: '$normalizedCode' -> '$character' (count: ${charMap[character]})")

        // Note: Abbreviation learning is disabled for Wubi mode
        // (multi-first-letter word memory is not used in Wubi)

        // Persist to SharedPreferences (async to avoid blocking)
        saveToPreferencesAsync()
    }

    /**
     * Records an abbreviation for a phrase based on the Wubi code used.
     * For Wubi, we use the input code itself as the abbreviation since it already
     * represents first letters of components.
     */
    private fun recordAbbreviation(wubiCode: String, phrase: String) {
        // In Wubi, when user types a short code for a phrase, that code itself
        // becomes the abbreviation. For example, typing "wq" for "我去" means
        // "wq" should suggest "我去" next time.
        if (wubiCode.length >= 2 && wubiCode.length <= phrase.length) {
            val abbrevMap = abbreviationCache.getOrPut(wubiCode) { mutableMapOf() }
            abbrevMap[phrase] = (abbrevMap[phrase] ?: 0) + 1
            Log.d(TAG, "Recorded abbreviation: '$wubiCode' -> '$phrase' (count: ${abbrevMap[phrase]})")
        }
    }

    /**
     * Gets candidates for an abbreviation.
     * Returns candidates sorted by frequency (most used first).
     * @param abbreviation The abbreviation to look up
     * @return List of phrase candidates sorted by frequency descending
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
     * Gets the frequency count for a specific character under a given Wubi code.
     * Higher count means the user selected it more frequently.
     * @return Frequency count, or 0 if never selected
     */
    fun getFrequency(wubiCode: String, character: String): Int {
        val normalizedCode = wubiCode.lowercase().trim()
        return memoryCache[normalizedCode]?.get(character) ?: 0
    }

    /**
     * Sorts a list of character candidates by user preference frequency (descending).
     * Uses SEPARATE frequency tracking for single characters (words) and phrases (multi-character).
     * This means phrases and single characters are sorted independently within their own groups,
     * with single characters appearing first, then phrases.
     * @param wubiCode The Wubi code input
     * @param candidates The list of characters to sort
     * @return Sorted list with single characters first (sorted by frequency), then phrases (sorted by frequency)
     */
    fun sortByFrequency(wubiCode: String, candidates: List<String>): List<String> {
        val normalizedCode = wubiCode.lowercase().trim()
        val charMap = memoryCache[normalizedCode] ?: return candidates

        // Separate phrases (multi-character) and single characters (words)
        val phrases = candidates.filter { it.length > 1 }
        val singleChars = candidates.filter { it.length == 1 }

        // Sort single characters by frequency (descending)
        val sortedSingleChars = singleChars.sortedByDescending { charMap[it] ?: 0 }

        // Sort phrases by frequency (descending)
        val sortedPhrases = phrases.sortedByDescending { charMap[it] ?: 0 }

        // Single characters first, then phrases
        return sortedSingleChars + sortedPhrases
    }

    /**
     * Clears all user memory data.
     */
    fun clearAll() {
        memoryCache.clear()
        abbreviationCache.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "All Wubi user memory cleared")
    }

    /**
     * Clears memory for a specific Wubi code.
     */
    fun clearCode(wubiCode: String) {
        val normalizedCode = wubiCode.lowercase().trim()
        memoryCache.remove(normalizedCode)
        saveToPreferencesAsync()
        Log.d(TAG, "Memory cleared for: $normalizedCode")
    }

    /**
     * Loads user memory data from SharedPreferences into the in-memory cache.
     */
    private fun loadFromPreferences() {
        try {
            // Load main memory cache
            val jsonString = prefs.getString(KEY_MEMORY_DATA, null)
            if (jsonString != null) {
                val jsonObject = JSONObject(jsonString)
                val codeKeys = jsonObject.keys()

                while (codeKeys.hasNext()) {
                    val code = codeKeys.next()
                    val charsJson = jsonObject.getJSONObject(code)
                    val charKeys = charsJson.keys()

                    val charMap = mutableMapOf<String, Int>()
                    while (charKeys.hasNext()) {
                        val char = charKeys.next()
                        val frequency = charsJson.getInt(char)
                        charMap[char] = frequency
                    }

                    memoryCache[code] = charMap
                }

                Log.d(TAG, "Loaded Wubi user memory: ${memoryCache.size} code entries")
            }

            // Load abbreviation cache
            val abbrevJsonString = prefs.getString(KEY_ABBREVIATION_DATA, null)
            if (abbrevJsonString != null) {
                val jsonObject = JSONObject(abbrevJsonString)
                val abbrevKeys = jsonObject.keys()

                while (abbrevKeys.hasNext()) {
                    val abbrev = abbrevKeys.next()
                    val phrasesJson = jsonObject.getJSONObject(abbrev)
                    val phraseKeys = phrasesJson.keys()

                    val phraseMap = mutableMapOf<String, Int>()
                    while (phraseKeys.hasNext()) {
                        val phrase = phraseKeys.next()
                        val frequency = phrasesJson.getInt(phrase)
                        phraseMap[phrase] = frequency
                    }

                    abbreviationCache[abbrev] = phraseMap
                }

                Log.d(TAG, "Loaded Wubi abbreviation memory: ${abbreviationCache.size} abbreviation entries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Wubi user memory from preferences", e)
        }
    }

    /**
     * Saves the current in-memory cache to SharedPreferences asynchronously.
     */
    private fun saveToPreferencesAsync() {
        try {
            // Save main memory cache
            val jsonObject = JSONObject()

            // Limit the cache size to prevent unbounded growth
            val limitedCache = if (memoryCache.size > MAX_CACHE_SIZE) {
                // Keep only the most frequently used entries
                memoryCache.entries
                    .sortedByDescending { entry -> entry.value.values.sum() }
                    .take(MAX_CACHE_SIZE)
                    .associate { it.key to it.value }
            } else {
                memoryCache
            }

            for ((code, charMap) in limitedCache) {
                // Limit characters per code entry
                val limitedChars = if (charMap.size > MAX_CHARS_PER_CODE) {
                    charMap.entries
                        .sortedByDescending { it.value }
                        .take(MAX_CHARS_PER_CODE)
                        .associate { it.key to it.value }
                } else {
                    charMap
                }

                val charsJson = JSONObject()
                for ((char, frequency) in limitedChars) {
                    charsJson.put(char, frequency)
                }
                jsonObject.put(code, charsJson)
            }

            // Save abbreviation cache
            val abbrevJsonObject = JSONObject()

            // Limit abbreviation cache size
            val limitedAbbrevCache = if (abbreviationCache.size > MAX_ABBREV_CACHE_SIZE) {
                abbreviationCache.entries
                    .sortedByDescending { entry -> entry.value.values.sum() }
                    .take(MAX_ABBREV_CACHE_SIZE)
                    .associate { it.key to it.value }
            } else {
                abbreviationCache
            }

            for ((abbrev, phraseMap) in limitedAbbrevCache) {
                val limitedPhrases = if (phraseMap.size > MAX_PHRASES_PER_ABBREV) {
                    phraseMap.entries
                        .sortedByDescending { it.value }
                        .take(MAX_PHRASES_PER_ABBREV)
                        .associate { it.key to it.value }
                } else {
                    phraseMap
                }

                val phrasesJson = JSONObject()
                for ((phrase, frequency) in limitedPhrases) {
                    phrasesJson.put(phrase, frequency)
                }
                abbrevJsonObject.put(abbrev, phrasesJson)
            }

            prefs.edit()
                .putString(KEY_MEMORY_DATA, jsonObject.toString())
                .putString(KEY_ABBREVIATION_DATA, abbrevJsonObject.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving Wubi user memory to preferences", e)
        }
    }

    /**
     * Gets statistics about the memory data.
     */
    fun getStats(): MemoryStats {
        val totalCodes = memoryCache.size
        val totalSelections = memoryCache.values.sumOf { it.values.sum() }
        val totalCharacters = memoryCache.values.sumOf { it.size }
        val totalAbbreviations = abbreviationCache.size
        val totalAbbrevPhrases = abbreviationCache.values.sumOf { it.size }

        return MemoryStats(
            codeCount = totalCodes,
            characterCount = totalCharacters,
            totalSelections = totalSelections,
            abbreviationCount = totalAbbreviations,
            abbreviationPhrases = totalAbbrevPhrases
        )
    }

    data class MemoryStats(
        val codeCount: Int,
        val characterCount: Int,
        val totalSelections: Int,
        val abbreviationCount: Int = 0,
        val abbreviationPhrases: Int = 0
    )

    /**
     * Exports all user memory data to a JSON string.
     * @return JSON string containing all memory data
     */
    fun exportToJson(): String {
        val exportJson = JSONObject()

        // Export memory cache
        val memoryJson = JSONObject()
        for ((code, charMap) in memoryCache) {
            val charsJson = JSONObject()
            for ((char, frequency) in charMap) {
                charsJson.put(char, frequency)
            }
            memoryJson.put(code, charsJson)
        }
        exportJson.put("memory", memoryJson)

        // Export abbreviation cache
        val abbreviationJson = JSONObject()
        for ((abbrev, phraseMap) in abbreviationCache) {
            val phrasesJson = JSONObject()
            for ((phrase, frequency) in phraseMap) {
                phrasesJson.put(phrase, frequency)
            }
            abbreviationJson.put(abbrev, phrasesJson)
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
                    val code = keys.next()
                    val charsJson = memoryJson.getJSONObject(code)
                    val charMap = memoryCache.getOrPut(code) { mutableMapOf() }
                    val charKeys = charsJson.keys()
                    while (charKeys.hasNext()) {
                        val char = charKeys.next()
                        val frequency = charsJson.getInt(char)
                        val existingFreq = charMap[char] ?: 0
                        if (mergeMode) {
                            charMap[char] = maxOf(existingFreq, frequency)
                        } else {
                            charMap[char] = frequency
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
                    val phrasesJson = abbreviationJson.getJSONObject(abbrev)
                    val phraseMap = abbreviationCache.getOrPut(abbrev) { mutableMapOf() }
                    val phraseKeys = phrasesJson.keys()
                    while (phraseKeys.hasNext()) {
                        val phrase = phraseKeys.next()
                        val frequency = phrasesJson.getInt(phrase)
                        val existingFreq = phraseMap[phrase] ?: 0
                        if (mergeMode) {
                            phraseMap[phrase] = maxOf(existingFreq, frequency)
                        } else {
                            phraseMap[phrase] = frequency
                        }
                        importedCount++
                    }
                }
            }

            saveToPreferencesAsync()
            Log.d(TAG, "Imported $importedCount Wubi user memory entries")
            return importedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error importing Wubi user memory", e)
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
        private const val TAG = "UserWubiMemory"
        private const val PREFS_NAME = "wubi_user_memory"
        private const val KEY_MEMORY_DATA = "memory_data"
        private const val KEY_ABBREVIATION_DATA = "abbreviation_data"
        private const val MAX_CACHE_SIZE = 2000  // Maximum unique Wubi codes to store
        private const val MAX_CHARS_PER_CODE = 30  // Maximum characters per Wubi code
        private const val MAX_ABBREV_CACHE_SIZE = 1000  // Maximum unique abbreviations to store
        private const val MAX_PHRASES_PER_ABBREV = 20  // Maximum phrases per abbreviation

        @Volatile
        private var instance: UserWubiMemory? = null

        /**
         * Gets the singleton instance of UserWubiMemory.
         */
        fun getInstance(context: Context): UserWubiMemory {
            return instance ?: synchronized(this) {
                instance ?: UserWubiMemory(context.applicationContext).also { instance = it }
            }
        }
    }
}
