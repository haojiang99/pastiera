package it.neuralrad.coolwulf.data.english

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Manages user's English word input history to learn preferences and prioritize
 * frequently selected words for each prefix.
 */
class UserEnglishMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache: prefix -> (word -> frequency count)
    private val memoryCache = mutableMapOf<String, MutableMap<String, Int>>()

    init {
        loadFromPreferences()
    }

    /**
     * Records that the user selected a specific word for a given prefix.
     * @param prefix The prefix input (e.g., "hel", "comp")
     * @param word The selected word (e.g., "hello", "computer")
     */
    fun recordSelection(prefix: String, word: String) {
        val normalizedPrefix = prefix.lowercase().trim()
        val normalizedWord = word.lowercase().trim()

        // Update in-memory cache
        val wordMap = memoryCache.getOrPut(normalizedPrefix) { mutableMapOf() }
        wordMap[normalizedWord] = (wordMap[normalizedWord] ?: 0) + 1

        Log.d(TAG, "Recorded: '$normalizedPrefix' -> '$normalizedWord' (count: ${wordMap[normalizedWord]})")

        // Persist to SharedPreferences (async to avoid blocking)
        saveToPreferences()
    }

    /**
     * Gets the frequency count for a specific word under a given prefix.
     * Higher count means the user selected it more frequently.
     * @return Frequency count, or 0 if never selected
     */
    fun getFrequency(prefix: String, word: String): Int {
        val normalizedPrefix = prefix.lowercase().trim()
        val normalizedWord = word.lowercase().trim()
        return memoryCache[normalizedPrefix]?.get(normalizedWord) ?: 0
    }

    /**
     * Sorts a list of word suggestions by user preference frequency (descending).
     * Words with higher frequency appear first.
     * Words with same frequency maintain their original order (stable sort).
     * @param prefix The prefix input
     * @param words The list of words to sort
     * @return Sorted list with most frequently selected words first
     */
    fun sortByFrequency(prefix: String, words: List<String>): List<String> {
        val normalizedPrefix = prefix.lowercase().trim()
        val wordMap = memoryCache[normalizedPrefix] ?: return words

        // Sort by frequency (descending), maintaining original order for equal frequencies
        return words.sortedByDescending { wordMap[it.lowercase()] ?: 0 }
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
     * Clears memory for a specific prefix.
     */
    fun clearPrefix(prefix: String) {
        val normalizedPrefix = prefix.lowercase().trim()
        memoryCache.remove(normalizedPrefix)
        saveToPreferences()
        Log.d(TAG, "Memory cleared for: $normalizedPrefix")
    }

    /**
     * Loads user memory data from SharedPreferences into the in-memory cache.
     */
    private fun loadFromPreferences() {
        try {
            val jsonString = prefs.getString(KEY_MEMORY_DATA, null)
            if (jsonString != null) {
                val jsonObject = JSONObject(jsonString)
                val prefixKeys = jsonObject.keys()

                while (prefixKeys.hasNext()) {
                    val prefix = prefixKeys.next()
                    val wordsJson = jsonObject.getJSONObject(prefix)
                    val wordKeys = wordsJson.keys()

                    val wordMap = mutableMapOf<String, Int>()
                    while (wordKeys.hasNext()) {
                        val word = wordKeys.next()
                        val frequency = wordsJson.getInt(word)
                        wordMap[word] = frequency
                    }

                    memoryCache[prefix] = wordMap
                }

                Log.d(TAG, "Loaded user memory: ${memoryCache.size} prefix entries")
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

            for ((prefix, wordMap) in memoryCache) {
                val wordsJson = JSONObject()
                for ((word, frequency) in wordMap) {
                    wordsJson.put(word, frequency)
                }
                jsonObject.put(prefix, wordsJson)
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
        val totalPrefixes = memoryCache.size
        val totalSelections = memoryCache.values.sumOf { it.values.sum() }
        val totalWords = memoryCache.values.sumOf { it.size }

        return MemoryStats(
            prefixCount = totalPrefixes,
            wordCount = totalWords,
            totalSelections = totalSelections
        )
    }

    data class MemoryStats(
        val prefixCount: Int,
        val wordCount: Int,
        val totalSelections: Int
    )

    companion object {
        private const val TAG = "UserEnglishMemory"
        private const val PREFS_NAME = "english_user_memory"
        private const val KEY_MEMORY_DATA = "memory_data"

        @Volatile
        private var instance: UserEnglishMemory? = null

        /**
         * Gets the singleton instance of UserEnglishMemory.
         */
        fun getInstance(context: Context): UserEnglishMemory {
            return instance ?: synchronized(this) {
                instance ?: UserEnglishMemory(context.applicationContext).also { instance = it }
            }
        }
    }
}
