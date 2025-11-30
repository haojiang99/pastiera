package it.neuralrad.coolwulf.data.zhenma

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Manages the Zhenma (真码) dictionary for Chinese input.
 * Loads mappings from JSON and provides lookup functionality.
 *
 * Zhenma is a shape-based Chinese input method similar to Wubi,
 * where characters are encoded based on their structural components.
 * Each character has a code of varying length.
 */
object ZhenmaDictionary {
    private const val TAG = "ZhenmaDictionary"
    private const val DICT_FILE = "common/zhenma/zhenma_dict.json"

    // Map of zhenma code -> list of Chinese characters
    private val dictionary = mutableMapOf<String, List<String>>()

    // Track if dictionary has been loaded
    private var isLoaded = false

    /**
     * Loads the Zhenma dictionary from assets.
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
            Log.d(TAG, "Loaded $loadedCount zhenma codes from dictionary")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading zhenma dictionary", e)
        }
    }

    /**
     * Gets character candidates for a given zhenma code.
     * @param code The zhenma code
     * @return List of Chinese characters, or empty list if not found
     */
    fun getCandidates(code: String): List<String> {
        if (!isLoaded) {
            Log.w(TAG, "Dictionary not loaded yet")
            return emptyList()
        }

        val normalized = code.lowercase().trim()
        return dictionary[normalized] ?: emptyList()
    }

    /**
     * Gets candidates for codes that start with the given prefix.
     * Useful for showing possible completions as user types.
     * @param prefix The prefix to search for
     * @param limit Maximum number of candidates to return
     * @return Combined list of candidates from matching codes
     */
    fun getCandidatesForPrefix(prefix: String, limit: Int = 30): List<String> {
        if (!isLoaded || prefix.isEmpty()) {
            return emptyList()
        }

        val normalized = prefix.lowercase()
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // First, check for exact match
        val exactMatch = dictionary[normalized]
        if (exactMatch != null) {
            for (candidate in exactMatch) {
                if (candidate !in seen) {
                    seen.add(candidate)
                    results.add(candidate)
                    if (results.size >= limit) {
                        return results
                    }
                }
            }
        }

        // Then find all codes starting with this prefix, prioritizing shorter codes
        val matchingCodes = dictionary.keys
            .filter { it.startsWith(normalized) && it != normalized }
            .sortedBy { it.length }

        // Collect candidates from matching codes
        for (code in matchingCodes) {
            val candidates = dictionary[code] ?: continue
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
     * Checks if a zhenma code exists in the dictionary.
     */
    fun contains(code: String): Boolean {
        val normalized = code.lowercase().trim()
        return dictionary.containsKey(normalized)
    }

    /**
     * Gets all valid zhenma codes (for validation).
     */
    fun getAllCodes(): Set<String> {
        return dictionary.keys.toSet()
    }

    /**
     * Checks if the dictionary is loaded.
     */
    fun isLoaded(): Boolean = isLoaded

    /**
     * Gets the total number of codes in the dictionary.
     */
    fun size(): Int = dictionary.size

    /**
     * Finds if there are any codes that could complete the given prefix.
     * Used to determine if user should continue typing or if no match is possible.
     * @param prefix The current input
     * @return true if there are codes starting with this prefix
     */
    fun hasCodesWithPrefix(prefix: String): Boolean {
        if (!isLoaded || prefix.isEmpty()) {
            return false
        }
        val normalized = prefix.lowercase()
        return dictionary.keys.any { it.startsWith(normalized) }
    }
}
