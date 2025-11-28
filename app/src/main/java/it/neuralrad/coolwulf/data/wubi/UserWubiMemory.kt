package it.neuralrad.coolwulf.data.wubi

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Manages user's Wubi input history to learn preferences and prioritize
 * frequently selected characters/phrases for each Wubi code.
 */
class UserWubiMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache: wubiCode -> (character/phrase -> frequency count)
    private val memoryCache = mutableMapOf<String, MutableMap<String, Int>>()

    init {
        loadFromPreferences()
    }

    /**
     * Records that the user selected a specific character/phrase for a given Wubi code.
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

        // Persist to SharedPreferences (async to avoid blocking)
        saveToPreferencesAsync()
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
     * Characters with higher frequency appear first.
     * Characters with same frequency maintain their original order (stable sort).
     * @param wubiCode The Wubi code input
     * @param candidates The list of characters to sort
     * @return Sorted list with most frequently selected characters first
     */
    fun sortByFrequency(wubiCode: String, candidates: List<String>): List<String> {
        val normalizedCode = wubiCode.lowercase().trim()
        val charMap = memoryCache[normalizedCode] ?: return candidates

        // Sort by frequency (descending), maintaining original order for equal frequencies
        return candidates.sortedByDescending { charMap[it] ?: 0 }
    }

    /**
     * Clears all user memory data.
     */
    fun clearAll() {
        memoryCache.clear()
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
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Wubi user memory from preferences", e)
        }
    }

    /**
     * Saves the current in-memory cache to SharedPreferences asynchronously.
     */
    private fun saveToPreferencesAsync() {
        try {
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

            prefs.edit()
                .putString(KEY_MEMORY_DATA, jsonObject.toString())
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

        return MemoryStats(
            codeCount = totalCodes,
            characterCount = totalCharacters,
            totalSelections = totalSelections
        )
    }

    data class MemoryStats(
        val codeCount: Int,
        val characterCount: Int,
        val totalSelections: Int
    )

    companion object {
        private const val TAG = "UserWubiMemory"
        private const val PREFS_NAME = "wubi_user_memory"
        private const val KEY_MEMORY_DATA = "memory_data"
        private const val MAX_CACHE_SIZE = 2000  // Maximum unique Wubi codes to store
        private const val MAX_CHARS_PER_CODE = 30  // Maximum characters per Wubi code

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
