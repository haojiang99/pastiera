package it.palsoftware.pastiera.data.pinyin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Manages user's Pinyin input history to learn preferences and prioritize
 * frequently selected characters/phrases for each pinyin input.
 */
class UserPinyinMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache: pinyin -> (candidate -> frequency count)
    private val memoryCache = mutableMapOf<String, MutableMap<String, Int>>()

    init {
        loadFromPreferences()
    }

    /**
     * Records that the user selected a specific candidate for a given pinyin.
     * @param pinyin The pinyin input (e.g., "ni", "nihao")
     * @param candidate The selected character or phrase (e.g., "你", "你好")
     */
    fun recordSelection(pinyin: String, candidate: String) {
        val normalizedPinyin = pinyin.lowercase().trim()

        // Update in-memory cache
        val candidateMap = memoryCache.getOrPut(normalizedPinyin) { mutableMapOf() }
        candidateMap[candidate] = (candidateMap[candidate] ?: 0) + 1

        Log.d(TAG, "Recorded: '$normalizedPinyin' -> '$candidate' (count: ${candidateMap[candidate]})")

        // Persist to SharedPreferences (async to avoid blocking)
        saveToPreferences()
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
     * Sorts a list of candidates by user preference frequency (descending).
     * Candidates with higher frequency appear first.
     * Candidates with same frequency maintain their original order (stable sort).
     * @param pinyin The pinyin input
     * @param candidates The list of candidates to sort
     * @return Sorted list with most frequently selected candidates first
     */
    fun sortByFrequency(pinyin: String, candidates: List<String>): List<String> {
        val normalizedPinyin = pinyin.lowercase().trim()
        val candidateMap = memoryCache[normalizedPinyin] ?: return candidates

        // Sort by frequency (descending), maintaining original order for equal frequencies
        return candidates.sortedByDescending { candidateMap[it] ?: 0 }
    }

    /**
     * Clears all user memory data.
     */
    fun clearAll() {
        memoryCache.clear()
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
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user memory from preferences", e)
        }
    }

    /**
     * Saves the current in-memory cache to SharedPreferences.
     */
    private fun saveToPreferences() {
        try {
            val jsonObject = JSONObject()

            for ((pinyin, candidateMap) in memoryCache) {
                val candidatesJson = JSONObject()
                for ((candidate, frequency) in candidateMap) {
                    candidatesJson.put(candidate, frequency)
                }
                jsonObject.put(pinyin, candidatesJson)
            }

            prefs.edit()
                .putString(KEY_MEMORY_DATA, jsonObject.toString())
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

        return MemoryStats(
            pinyinCount = totalPinyin,
            candidateCount = totalCandidates,
            totalSelections = totalSelections
        )
    }

    data class MemoryStats(
        val pinyinCount: Int,
        val candidateCount: Int,
        val totalSelections: Int
    )

    companion object {
        private const val TAG = "UserPinyinMemory"
        private const val PREFS_NAME = "pinyin_user_memory"
        private const val KEY_MEMORY_DATA = "memory_data"

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
