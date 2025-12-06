package it.neuralrad.coolwulf.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Learns and predicts next words based on previous word context.
 * Uses both bigrams (2-word sequences) and trigrams (3-word sequences) for better accuracy.
 * Filters common stop words and learns from user input.
 */
class NextWordPredictor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache: previousWord -> (nextWord -> frequency count)
    private val bigramCache = mutableMapOf<String, MutableMap<String, Int>>()

    // Trigram cache: (word1 + "|" + word2) -> (nextWord -> frequency count)
    // For better accuracy using two previous words as context
    private val trigramCache = mutableMapOf<String, MutableMap<String, Int>>()

    // Last two committed words (used to learn sequences)
    private var lastCommittedWords: Pair<String?, String?> = Pair(null, null)

    // Current next-word suggestions (shown before user types)
    private var nextWordSuggestions: List<String> = emptyList()

    // Whether we're showing next-word predictions (before user starts typing)
    private var isShowingNextWordPredictions: Boolean = false

    // Common English stop words to filter from predictions
    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "up", "about", "into", "through", "during",
        "before", "after", "above", "below", "between", "under", "again",
        "further", "then", "once", "i", "me", "my", "myself", "we", "our",
        "ours", "ourselves", "you", "your", "yours", "yourself", "yourselves",
        "he", "him", "his", "himself", "she", "her", "hers", "herself", "it",
        "its", "itself", "they", "them", "their", "theirs", "themselves"
    )

    init {
        loadFromPreferences()
    }

    /**
     * Records that a word was committed after the previous word(s).
     * This builds both bigram and trigram models for next-word prediction.
     * @param word The word that was just committed
     */
    fun recordCommittedWord(word: String) {
        val normalizedWord = normalizeWord(word)
        if (normalizedWord.isEmpty()) return

        // Record trigram if we have two previous words
        val (prevWord1, prevWord2) = lastCommittedWords
        if (prevWord1 != null && prevWord2 != null) {
            val trigramKey = "$prevWord1|$prevWord2"
            val nextWordMap = trigramCache.getOrPut(trigramKey) { mutableMapOf() }
            nextWordMap[normalizedWord] = (nextWordMap[normalizedWord] ?: 0) + 1
            Log.d(TAG, "Recorded trigram: '$trigramKey' -> '$normalizedWord' (count: ${nextWordMap[normalizedWord]})")
        }

        // Record bigram if we have one previous word
        prevWord2?.let { prevWord ->
            val nextWordMap = bigramCache.getOrPut(prevWord) { mutableMapOf() }
            nextWordMap[normalizedWord] = (nextWordMap[normalizedWord] ?: 0) + 1
            Log.d(TAG, "Recorded bigram: '$prevWord' -> '$normalizedWord' (count: ${nextWordMap[normalizedWord]})")
        }

        // Update last committed words (shift: word2 -> word1, new word -> word2)
        lastCommittedWords = Pair(prevWord2, normalizedWord)

        // Generate next-word suggestions using both trigrams and bigrams
        nextWordSuggestions = getNextWordSuggestions(lastCommittedWords)
        isShowingNextWordPredictions = nextWordSuggestions.isNotEmpty()

        Log.d(TAG, "After '$normalizedWord', suggestions: $nextWordSuggestions")
        saveToPreferencesAsync()
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
        lastCommittedWords = Pair(null, null)
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
     * Gets next-word suggestions based on the previous word(s).
     * Tries trigrams first (two previous words), falls back to bigrams (one previous word).
     * Filters out common stop words, just-typed word, and sorts by frequency.
     * Returns both English and Chinese words.
     * @param previousWords Pair of last two committed words
     * @return List of likely next words, sorted by frequency and quality
     */
    private fun getNextWordSuggestions(previousWords: Pair<String?, String?>, limit: Int = MAX_SUGGESTIONS): List<String> {
        val (word1, word2) = previousWords

        // Try trigram suggestions first (more specific context)
        if (word1 != null && word2 != null) {
            val trigramKey = "$word1|$word2"
            val trigramMap = trigramCache[trigramKey]
            if (trigramMap != null && trigramMap.isNotEmpty()) {
                val suggestions = trigramMap.entries
                    .sortedByDescending { it.value }
                    .filter {
                        !stopWords.contains(it.key) &&
                        it.key != word2
                    }
                    .take(limit)
                    .map { it.key }
                if (suggestions.isNotEmpty()) {
                    return suggestions
                }
            }
        }

        // Fall back to bigram suggestions (using most recent word)
        if (word2 != null) {
            val bigramMap = bigramCache[word2] ?: return emptyList()
            return bigramMap.entries
                .sortedByDescending { it.value }
                .filter {
                    !stopWords.contains(it.key) &&
                    it.key != word2
                }
                .take(limit)
                .map { it.key }
        }

        return emptyList()
    }

    /**
     * Gets the last committed word.
     */
    fun getLastCommittedWord(): String? = lastCommittedWords.second

    /**
     * Normalizes a word for consistent storage and lookup.
     */
    private fun normalizeWord(word: String): String {
        return word.lowercase().trim().filter { it.isLetterOrDigit() || it in "一-龥" }
    }

    /**
     * Loads bigram and trigram data from SharedPreferences.
     */
    private fun loadFromPreferences() {
        try {
            // Load bigrams
            val bigramJsonString = prefs.getString(KEY_BIGRAM_DATA, null)
            if (bigramJsonString != null) {
                val jsonObject = JSONObject(bigramJsonString)
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

            // Load trigrams
            val trigramJsonString = prefs.getString(KEY_TRIGRAM_DATA, null)
            if (trigramJsonString != null) {
                val jsonObject = JSONObject(trigramJsonString)
                val trigramKeys = jsonObject.keys()

                while (trigramKeys.hasNext()) {
                    val trigramKey = trigramKeys.next()
                    val nextWordsJson = jsonObject.getJSONObject(trigramKey)
                    val nextWordKeys = nextWordsJson.keys()

                    val nextWordMap = mutableMapOf<String, Int>()
                    while (nextWordKeys.hasNext()) {
                        val nextWord = nextWordKeys.next()
                        val frequency = nextWordsJson.getInt(nextWord)
                        nextWordMap[nextWord] = frequency
                    }

                    trigramCache[trigramKey] = nextWordMap
                }

                Log.d(TAG, "Loaded trigram data: ${trigramCache.size} trigram entries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading word prediction data from preferences", e)
        }
    }

    /**
     * Saves bigram and trigram data to SharedPreferences asynchronously.
     */
    private fun saveToPreferencesAsync() {
        try {
            // Save bigrams
            val bigramJsonObject = JSONObject()
            val limitedBigramCache = if (bigramCache.size > MAX_CACHE_SIZE) {
                bigramCache.entries
                    .sortedByDescending { entry -> entry.value.values.sum() }
                    .take(MAX_CACHE_SIZE)
                    .associate { it.key to it.value }
            } else {
                bigramCache
            }

            for ((prevWord, nextWordMap) in limitedBigramCache) {
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
                bigramJsonObject.put(prevWord, nextWordsJson)
            }

            // Save trigrams
            val trigramJsonObject = JSONObject()
            val limitedTrigramCache = if (trigramCache.size > MAX_TRIGRAM_CACHE_SIZE) {
                trigramCache.entries
                    .sortedByDescending { entry -> entry.value.values.sum() }
                    .take(MAX_TRIGRAM_CACHE_SIZE)
                    .associate { it.key to it.value }
            } else {
                trigramCache
            }

            for ((trigramKey, nextWordMap) in limitedTrigramCache) {
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
                trigramJsonObject.put(trigramKey, nextWordsJson)
            }

            prefs.edit()
                .putString(KEY_BIGRAM_DATA, bigramJsonObject.toString())
                .putString(KEY_TRIGRAM_DATA, trigramJsonObject.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving word prediction data to preferences", e)
        }
    }

    /**
     * Clears all learned data.
     */
    fun clearAll() {
        bigramCache.clear()
        trigramCache.clear()
        lastCommittedWords = Pair(null, null)
        nextWordSuggestions = emptyList()
        isShowingNextWordPredictions = false
        prefs.edit().clear().apply()
        Log.d(TAG, "All word prediction data cleared")
    }

    /**
     * Gets statistics about the learned data.
     */
    fun getStats(): Stats {
        val totalBigrams = bigramCache.values.sumOf { it.size }
        val totalBigramFrequency = bigramCache.values.sumOf { it.values.sum() }
        val totalTrigrams = trigramCache.values.sumOf { it.size }
        val totalTrigramFrequency = trigramCache.values.sumOf { it.values.sum() }

        return Stats(
            totalBigrams = totalBigrams,
            bigramOccurrences = totalBigramFrequency,
            totalTrigrams = totalTrigrams,
            trigramOccurrences = totalTrigramFrequency
        )
    }

    data class Stats(
        val totalBigrams: Int,
        val bigramOccurrences: Int,
        val totalTrigrams: Int,
        val trigramOccurrences: Int
    )

    companion object {
        private const val TAG = "NextWordPredictor"
        private const val PREFS_NAME = "next_word_predictor"
        private const val KEY_BIGRAM_DATA = "bigram_data"
        private const val KEY_TRIGRAM_DATA = "trigram_data"
        private const val MAX_SUGGESTIONS = 5
        private const val MAX_CACHE_SIZE = 1000  // Maximum unique previous words to store
        private const val MAX_TRIGRAM_CACHE_SIZE = 500  // Maximum unique trigrams to store
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
