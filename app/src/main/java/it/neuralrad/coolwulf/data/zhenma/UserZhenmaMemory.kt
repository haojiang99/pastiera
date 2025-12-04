package it.neuralrad.coolwulf.data.zhenma

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Manages user's Zhenma input history to learn preferences and prioritize
 * frequently selected characters/phrases for each Zhenma code.
 *
 * Also supports abbreviation learning: when user types multi-character phrases,
 * the code is recorded as an abbreviation for future suggestions.
 */
class UserZhenmaMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache: zhenmaCode -> (character/phrase -> frequency count)
    private val memoryCache = mutableMapOf<String, MutableMap<String, Int>>()

    // Abbreviation cache: abbreviation -> (phrase -> frequency count)
    private val abbreviationCache = mutableMapOf<String, MutableMap<String, Int>>()

    init {
        loadFromPreferences()
    }

    /**
     * Records that the user selected a specific character/phrase for a given Zhenma code.
     * Also records abbreviation for multi-character phrases.
     */
    fun recordSelection(zhenmaCode: String, character: String) {
        val normalizedCode = zhenmaCode.lowercase().trim()
        if (normalizedCode.isEmpty() || character.isEmpty()) return

        // Update in-memory cache
        val charMap = memoryCache.getOrPut(normalizedCode) { mutableMapOf() }
        charMap[character] = (charMap[character] ?: 0) + 1

        Log.d(TAG, "Recorded: '$normalizedCode' -> '$character' (count: ${charMap[character]})")

        // Record abbreviation for multi-character phrases
        if (character.length >= 2) {
            recordAbbreviation(normalizedCode, character)
        }

        // Persist to SharedPreferences (async to avoid blocking)
        saveToPreferencesAsync()
    }

    /**
     * Records an abbreviation for a phrase based on the Zhenma code used.
     */
    private fun recordAbbreviation(zhenmaCode: String, phrase: String) {
        if (zhenmaCode.length >= 2 && zhenmaCode.length <= phrase.length) {
            val abbrevMap = abbreviationCache.getOrPut(zhenmaCode) { mutableMapOf() }
            abbrevMap[phrase] = (abbrevMap[phrase] ?: 0) + 1
            Log.d(TAG, "Recorded abbreviation: '$zhenmaCode' -> '$phrase' (count: ${abbrevMap[phrase]})")
        }
    }

    /**
     * Gets candidates for an abbreviation.
     * Returns candidates sorted by frequency (most used first).
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
     * Gets the frequency count for a specific character under a given Zhenma code.
     */
    fun getFrequency(zhenmaCode: String, character: String): Int {
        val normalizedCode = zhenmaCode.lowercase().trim()
        return memoryCache[normalizedCode]?.get(character) ?: 0
    }

    /**
     * Sorts a list of character candidates by user preference frequency (descending).
     */
    fun sortByFrequency(zhenmaCode: String, candidates: List<String>): List<String> {
        val normalizedCode = zhenmaCode.lowercase().trim()
        val charMap = memoryCache[normalizedCode] ?: return candidates

        // Sort by frequency (descending), maintaining original order for equal frequencies
        // Phrases (multi-character candidates) get a +2 frequency boost ONLY when unselected (frequency 0)
        // This means a single character needs to be selected 3 times to surpass an unselected phrase
        // Once either is selected, they compete on actual frequency
        return candidates.sortedByDescending { candidate ->
            val baseFreq = charMap[candidate] ?: 0
            val phraseBoost = if (candidate.length > 1 && baseFreq == 0) 2 else 0
            baseFreq + phraseBoost
        }
    }

    /**
     * Clears all user memory data.
     */
    fun clearAll() {
        memoryCache.clear()
        abbreviationCache.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "All Zhenma user memory cleared")
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

                Log.d(TAG, "Loaded Zhenma user memory: ${memoryCache.size} code entries")
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

                Log.d(TAG, "Loaded Zhenma abbreviation memory: ${abbreviationCache.size} abbreviation entries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Zhenma user memory from preferences", e)
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
                memoryCache.entries
                    .sortedByDescending { entry -> entry.value.values.sum() }
                    .take(MAX_CACHE_SIZE)
                    .associate { it.key to it.value }
            } else {
                memoryCache
            }

            for ((code, charMap) in limitedCache) {
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
            Log.e(TAG, "Error saving Zhenma user memory to preferences", e)
        }
    }

    companion object {
        private const val TAG = "UserZhenmaMemory"
        private const val PREFS_NAME = "zhenma_user_memory"
        private const val KEY_MEMORY_DATA = "memory_data"
        private const val KEY_ABBREVIATION_DATA = "abbreviation_data"
        private const val MAX_CACHE_SIZE = 2000
        private const val MAX_CHARS_PER_CODE = 30
        private const val MAX_ABBREV_CACHE_SIZE = 1000
        private const val MAX_PHRASES_PER_ABBREV = 20

        @Volatile
        private var instance: UserZhenmaMemory? = null

        fun getInstance(context: Context): UserZhenmaMemory {
            return instance ?: synchronized(this) {
                instance ?: UserZhenmaMemory(context.applicationContext).also { instance = it }
            }
        }
    }
}
