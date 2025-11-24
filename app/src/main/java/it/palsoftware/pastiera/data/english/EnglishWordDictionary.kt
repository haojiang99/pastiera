package it.palsoftware.pastiera.data.english

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Manages the English word dictionary for word prediction.
 * Loads words from JSON and provides prefix-based lookup functionality.
 */
object EnglishWordDictionary {
    private const val TAG = "EnglishWordDictionary"
    private const val DICT_FILE = "common/english/english_words.json"

    // List of words sorted by frequency (most common first)
    private val words = mutableListOf<String>()

    // Map of prefix -> list of words starting with that prefix (for fast lookup)
    private val prefixIndex = mutableMapOf<String, MutableList<String>>()

    // Track if dictionary has been loaded
    private var isLoaded = false

    /**
     * Loads the English word dictionary from assets.
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
            val jsonArray = jsonObject.optJSONArray("words")

            if (jsonArray != null) {
                // Use a set to deduplicate while preserving order for first occurrences
                val seenWords = mutableSetOf<String>()

                for (i in 0 until jsonArray.length()) {
                    val word = jsonArray.getString(i).lowercase()
                    if (word.length >= 2 && seenWords.add(word)) {  // Only add words with 2+ chars, skip duplicates
                        words.add(word)
                        // Index by all prefixes for fast lookup
                        indexWord(word)
                    }
                }
            }

            isLoaded = true
            Log.d(TAG, "Loaded ${words.size} English words from dictionary")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading English word dictionary", e)
        }
    }

    /**
     * Indexes a word by all its prefixes for fast lookup.
     */
    private fun indexWord(word: String) {
        // Index by prefixes of length 1, 2, 3 for fast lookup
        for (prefixLen in 1..minOf(3, word.length)) {
            val prefix = word.substring(0, prefixLen)
            prefixIndex.getOrPut(prefix) { mutableListOf() }.add(word)
        }
    }

    /**
     * Gets word suggestions for a given prefix.
     * @param prefix The prefix to search for (e.g., "hel" -> ["help", "hello", "held", ...])
     * @param limit Maximum number of suggestions to return
     * @return List of words starting with the prefix, sorted by frequency
     */
    fun getSuggestions(prefix: String, limit: Int = 9): List<String> {
        if (!isLoaded || prefix.isEmpty()) {
            return emptyList()
        }

        val normalizedPrefix = prefix.lowercase()

        // For short prefixes (1-3 chars), use the index
        if (normalizedPrefix.length <= 3) {
            val indexed = prefixIndex[normalizedPrefix]
            if (indexed != null) {
                // Filter to ensure exact prefix match and exclude the prefix itself if it's a complete word
                return indexed
                    .filter { it.startsWith(normalizedPrefix) && it != normalizedPrefix }
                    .take(limit)
            }
            return emptyList()
        }

        // For longer prefixes, search within the indexed results for the first 3 chars
        val shortPrefix = normalizedPrefix.substring(0, 3)
        val indexed = prefixIndex[shortPrefix]
        if (indexed != null) {
            return indexed
                .filter { it.startsWith(normalizedPrefix) && it != normalizedPrefix }
                .take(limit)
        }

        return emptyList()
    }

    /**
     * Checks if a word exists in the dictionary.
     */
    fun contains(word: String): Boolean {
        val normalized = word.lowercase()
        return words.contains(normalized)
    }

    /**
     * Checks if the dictionary is loaded.
     */
    fun isLoaded(): Boolean = isLoaded

    /**
     * Gets the total number of words in the dictionary.
     */
    fun size(): Int = words.size
}
