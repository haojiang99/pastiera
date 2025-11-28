package it.neuralrad.coolwulf.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Learns and predicts next words based on previous word context.
 * Tracks word sequences (bigrams) to suggest the most likely next word
 * after the user commits a word.
 */
class NextWordPredictor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache: previousWord -> (nextWord -> frequency count)
    private val bigramCache = mutableMapOf<String, MutableMap<String, Int>>()

    // Last committed word (used to learn sequences)
    private var lastCommittedWord: String? = null

    // Current next-word suggestions (shown before user types)
    private var nextWordSuggestions: List<String> = emptyList()

    // Whether we're showing next-word predictions (before user starts typing)
    private var isShowingNextWordPredictions: Boolean = false

    init {
        loadFromPreferences()
    }

    /**
     * Records that a word was committed after the previous word.
     * This builds the bigram model for next-word prediction.
     * @param word The word that was just committed
     */
    fun recordCommittedWord(word: String) {
        val normalizedWord = normalizeWord(word)
        if (normalizedWord.isEmpty()) return

        // If we have a previous word, record the bigram
        lastCommittedWord?.let { prevWord ->
            val prevNormalized = normalizeWord(prevWord)
            if (prevNormalized.isNotEmpty()) {
                val nextWordMap = bigramCache.getOrPut(prevNormalized) { mutableMapOf() }
                nextWordMap[normalizedWord] = (nextWordMap[normalizedWord] ?: 0) + 1
                Log.d(TAG, "Recorded bigram: '$prevNormalized' -> '$normalizedWord' (count: ${nextWordMap[normalizedWord]})")
                saveToPreferencesAsync()
            }
        }

        // Update last committed word and generate next-word suggestions
        lastCommittedWord = normalizedWord
        nextWordSuggestions = getNextWordSuggestions(normalizedWord)
        isShowingNextWordPredictions = nextWordSuggestions.isNotEmpty()

        Log.d(TAG, "After '$normalizedWord', suggestions: $nextWordSuggestions")
    }

    /**
     * Called when user starts typing a new word.
     * Clears next-word prediction mode so normal suggestions take over.
     */
    fun onUserStartedTyping() {
        isShowingNextWordPredictions = false
        nextWordSuggestions = emptyList()
    }

    /**
     * Clears the prediction state (e.g., when input field changes).
     */
    fun clearState() {
        lastCommittedWord = null
        nextWordSuggestions = emptyList()
        isShowingNextWordPredictions = false
    }

    /**
     * Returns whether we're currently showing next-word predictions.
     */
    fun isShowingPredictions(): Boolean = isShowingNextWordPredictions && nextWordSuggestions.isNotEmpty()

    /**
     * Gets the current next-word suggestions.
     */
    fun getSuggestions(): List<String> = nextWordSuggestions

    /**
     * Gets next-word suggestions based on the previous word.
     * @param previousWord The word that was just typed
     * @return List of likely next words, sorted by frequency
     */
    private fun getNextWordSuggestions(previousWord: String, limit: Int = MAX_SUGGESTIONS): List<String> {
        val normalized = normalizeWord(previousWord)
        val nextWordMap = bigramCache[normalized] ?: return emptyList()

        // Sort by frequency (descending) and take top results
        return nextWordMap.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    /**
     * Gets the last committed word.
     */
    fun getLastCommittedWord(): String? = lastCommittedWord

    /**
     * Normalizes a word for consistent storage and lookup.
     */
    private fun normalizeWord(word: String): String {
        return word.lowercase().trim().filter { it.isLetterOrDigit() || it in "一-龥" }
    }

    /**
     * Loads bigram data from SharedPreferences.
     */
    private fun loadFromPreferences() {
        try {
            val jsonString = prefs.getString(KEY_BIGRAM_DATA, null)
            if (jsonString != null) {
                val jsonObject = JSONObject(jsonString)
                val prevWordKeys = jsonObject.keys()

                while (prevWordKeys.hasNext()) {
                    val prevWord = prevWordKeys.next()
                    val nextWordsJson = jsonObject.getJSONObject(prevWord)
                    val nextWordKeys = nextWordsJson.keys()

                    val nextWordMap = mutableMapOf<String, Int>()
                    while (nextWordKeys.hasNext()) {
                        val nextWord = nextWordKeys.next()
                        val frequency = nextWordsJson.getInt(nextWord)
                        nextWordMap[nextWord] = frequency
                    }

                    bigramCache[prevWord] = nextWordMap
                }

                Log.d(TAG, "Loaded bigram data: ${bigramCache.size} word entries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bigram data from preferences", e)
        }
    }

    /**
     * Saves bigram data to SharedPreferences asynchronously.
     */
    private fun saveToPreferencesAsync() {
        try {
            val jsonObject = JSONObject()

            // Limit the cache size to prevent unbounded growth
            val limitedCache = if (bigramCache.size > MAX_CACHE_SIZE) {
                // Keep only the most frequently used entries
                bigramCache.entries
                    .sortedByDescending { entry -> entry.value.values.sum() }
                    .take(MAX_CACHE_SIZE)
                    .associate { it.key to it.value }
            } else {
                bigramCache
            }

            for ((prevWord, nextWordMap) in limitedCache) {
                // Limit next words per entry
                val limitedNextWords = if (nextWordMap.size > MAX_NEXT_WORDS_PER_ENTRY) {
                    nextWordMap.entries
                        .sortedByDescending { it.value }
                        .take(MAX_NEXT_WORDS_PER_ENTRY)
                        .associate { it.key to it.value }
                } else {
                    nextWordMap
                }

                val nextWordsJson = JSONObject()
                for ((nextWord, frequency) in limitedNextWords) {
                    nextWordsJson.put(nextWord, frequency)
                }
                jsonObject.put(prevWord, nextWordsJson)
            }

            prefs.edit()
                .putString(KEY_BIGRAM_DATA, jsonObject.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bigram data to preferences", e)
        }
    }

    /**
     * Clears all learned data.
     */
    fun clearAll() {
        bigramCache.clear()
        lastCommittedWord = null
        nextWordSuggestions = emptyList()
        isShowingNextWordPredictions = false
        prefs.edit().clear().apply()
        Log.d(TAG, "All bigram data cleared")
    }

    /**
     * Gets statistics about the learned data.
     */
    fun getStats(): Stats {
        val totalPrevWords = bigramCache.size
        val totalBigrams = bigramCache.values.sumOf { it.size }
        val totalFrequency = bigramCache.values.sumOf { it.values.sum() }

        return Stats(
            uniquePreviousWords = totalPrevWords,
            totalBigrams = totalBigrams,
            totalOccurrences = totalFrequency
        )
    }

    data class Stats(
        val uniquePreviousWords: Int,
        val totalBigrams: Int,
        val totalOccurrences: Int
    )

    companion object {
        private const val TAG = "NextWordPredictor"
        private const val PREFS_NAME = "next_word_predictor"
        private const val KEY_BIGRAM_DATA = "bigram_data"
        private const val MAX_SUGGESTIONS = 5
        private const val MAX_CACHE_SIZE = 1000  // Maximum unique previous words to store
        private const val MAX_NEXT_WORDS_PER_ENTRY = 20  // Maximum next words per previous word

        @Volatile
        private var instance: NextWordPredictor? = null

        /**
         * Gets the singleton instance of NextWordPredictor.
         */
        fun getInstance(context: Context): NextWordPredictor {
            return instance ?: synchronized(this) {
                instance ?: NextWordPredictor(context.applicationContext).also { instance = it }
            }
        }
    }
}
