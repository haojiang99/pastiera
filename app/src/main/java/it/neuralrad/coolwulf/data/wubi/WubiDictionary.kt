package it.neuralrad.coolwulf.data.wubi

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Manages the Wubi 86 dictionary for Chinese input.
 * Loads mappings from JSON and provides lookup functionality.
 *
 * Wubi is a shape-based Chinese input method where characters are
 * encoded based on their structural components (radicals/strokes)
 * rather than pronunciation. Each character has a 1-4 letter code.
 */
object WubiDictionary {
    private const val TAG = "WubiDictionary"
    private const val DICT_FILE = "common/wubi/wubi_dict.json"

    // Map of wubi code -> list of Chinese characters
    private val dictionary = mutableMapOf<String, List<String>>()

    // Track if dictionary has been loaded
    private var isLoaded = false

    /**
     * Loads the Wubi dictionary from assets.
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
            Log.d(TAG, "Loaded $loadedCount wubi codes from dictionary")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading wubi dictionary", e)
        }
    }

    /**
     * Gets character candidates for a given wubi code.
     * @param code The wubi code (e.g., "gggg" for "王", "wo" for "我")
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
     * @param prefix The prefix to search for (e.g., "g")
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
     * Checks if a wubi code exists in the dictionary.
     */
    fun contains(code: String): Boolean {
        val normalized = code.lowercase().trim()
        return dictionary.containsKey(normalized)
    }

    /**
     * Gets all valid wubi codes (for validation).
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

    /**
     * Gets candidates for codes matching a pattern with 'z' as wildcard.
     * In Wubi, 'z' is the "learning key" (万能学习键) that matches any character.
     *
     * Examples:
     * - "rwyz" matches "rwyg", "rwya", etc. (z replaces any single letter)
     * - "qvzp" matches "qvfp", "qvap", etc.
     *
     * @param pattern The pattern with 'z' as wildcard (e.g., "rwyz")
     * @param limit Maximum number of candidates to return
     * @return List of candidates with their actual codes (for learning display)
     */
    fun getCandidatesWithWildcard(pattern: String, limit: Int = 50): List<Pair<String, String>> {
        if (!isLoaded || pattern.isEmpty()) {
            return emptyList()
        }

        val normalized = pattern.lowercase()

        // If no 'z' in pattern, just return normal candidates (but with empty codes)
        if (!normalized.contains('z')) {
            return getCandidatesForPrefix(normalized, limit).map { it to "" }
        }

        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        // Convert pattern to regex: replace 'z' with [a-y] (any letter except z)
        val regexPattern = buildWildcardRegex(normalized)

        // Search all dictionary codes that match the pattern
        val matchingCodes = dictionary.keys
            .filter { code ->
                // Code must be same length as pattern for exact match
                // OR code starts with pattern (for prefix matching when z is not at end)
                if (code.length == normalized.length) {
                    regexPattern.matches(code)
                } else if (code.length > normalized.length && !normalized.endsWith('z')) {
                    // For prefix matching (when pattern doesn't end with z)
                    val prefixRegex = buildWildcardRegex(normalized, isPrefix = true)
                    prefixRegex.containsMatchIn(code)
                } else {
                    false
                }
            }
            .sortedWith(compareBy(
                { it.length },  // Shorter codes first
                { it }          // Then alphabetically
            ))

        // Collect candidates from matching codes
        for (code in matchingCodes) {
            val candidates = dictionary[code] ?: continue
            for (candidate in candidates) {
                if (candidate !in seen) {
                    seen.add(candidate)
                    results.add(candidate to code)  // Include the actual code for learning
                    if (results.size >= limit) {
                        return results
                    }
                }
            }
        }

        return results
    }

    /**
     * Builds a regex pattern from Wubi code with 'z' as wildcard.
     * @param pattern The pattern (e.g., "rwyz")
     * @param isPrefix If true, creates a prefix-matching regex
     * @return Regex that matches codes where 'z' can be any letter a-y
     */
    private fun buildWildcardRegex(pattern: String, isPrefix: Boolean = false): Regex {
        val regexStr = buildString {
            if (isPrefix) append("^")
            for (char in pattern) {
                when (char) {
                    'z' -> append("[a-y]")  // z matches any letter except z itself
                    in 'a'..'y' -> append(char)
                    else -> append(Regex.escape(char.toString()))
                }
            }
            if (!isPrefix) append("$")
        }
        return Regex(regexStr)
    }

    /**
     * Checks if a pattern contains the wildcard character 'z'.
     */
    fun containsWildcard(code: String): Boolean {
        return code.lowercase().contains('z')
    }
}
