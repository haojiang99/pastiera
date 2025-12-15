package it.neuralrad.coolwulf.data.wubi

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Manages auto-learning of new phrases from Wubi input.
 *
 * Similar to AutoPhraseMemory for Pinyin, but uses Wubi codes instead.
 * When user types a phrase character by character using Wubi codes,
 * this class tracks the sequence and learns it as a phrase when typed twice.
 *
 * Flow:
 * 1. First time: phrase is recorded as "pending"
 * 2. Second time: phrase is promoted to "learned" and becomes available as a candidate
 * 3. Subsequent times: frequency is incremented for priority sorting
 */
class WubiPhraseMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Pending phrases: typed once, awaiting confirmation
    // wubiCode → (phrase → timestamp)
    private val pendingPhrases = mutableMapOf<String, MutableMap<String, Long>>()

    // Learned phrases: typed twice or more, confirmed
    // wubiCode → (phrase → frequency)
    private val learnedPhrases = mutableMapOf<String, MutableMap<String, Int>>()

    init {
        loadFromPreferences()
        // Clean up old pending phrases on startup
        clearOldPending(MAX_PENDING_AGE_DAYS)
    }

    /**
     * Records a phrase typed by the user.
     *
     * Logic:
     * 1. If phrase already learned → increment frequency
     * 2. If phrase is pending → promote to learned
     * 3. Otherwise → add to pending
     *
     * @param wubiCode The combined Wubi code (e.g., "ggttk")
     * @param phrase The combined characters (e.g., "王五")
     */
    fun recordPhrase(wubiCode: String, phrase: String) {
        val normalizedCode = wubiCode.lowercase().trim()

        // Skip single characters - not a phrase
        if (phrase.length < 2) {
            return
        }

        // Check if already learned
        val learnedMap = learnedPhrases[normalizedCode]
        if (learnedMap != null && learnedMap.containsKey(phrase)) {
            // Already learned - increment frequency
            learnedMap[phrase] = (learnedMap[phrase] ?: 1) + 1
            saveToPreferences()
            return
        }

        // Check if pending
        val pendingMap = pendingPhrases[normalizedCode]
        if (pendingMap != null && pendingMap.containsKey(phrase)) {
            // Pending → promote to learned
            pendingMap.remove(phrase)
            if (pendingMap.isEmpty()) {
                pendingPhrases.remove(normalizedCode)
            }

            val learned = learnedPhrases.getOrPut(normalizedCode) { mutableMapOf() }
            learned[phrase] = 1
            saveToPreferences()
            return
        }

        // New phrase - add to pending
        val pending = pendingPhrases.getOrPut(normalizedCode) { mutableMapOf() }
        pending[phrase] = System.currentTimeMillis()
        saveToPreferences()
    }

    /**
     * Gets learned phrases for a given Wubi code, sorted by frequency.
     *
     * @param wubiCode The Wubi code to look up
     * @return List of learned phrases, most frequent first
     */
    fun getLearnedPhrases(wubiCode: String): List<String> {
        val normalizedCode = wubiCode.lowercase().trim()
        val phraseMap = learnedPhrases[normalizedCode] ?: return emptyList()

        return phraseMap.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    /**
     * Gets learned phrases whose Wubi code starts with the given prefix.
     * This enables partial matching.
     *
     * @param prefix The Wubi code prefix to match
     * @return List of learned phrases sorted by frequency, most frequent first
     */
    fun getLearnedPhrasesWithPrefix(prefix: String): List<String> {
        val normalizedPrefix = prefix.lowercase().trim()
        if (normalizedPrefix.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<String, Int>>()  // phrase to frequency

        for ((wubiCode, phraseMap) in learnedPhrases) {
            // Check if this code starts with the prefix (but is longer - partial match)
            if (wubiCode.startsWith(normalizedPrefix) && wubiCode.length > normalizedPrefix.length) {
                for ((phrase, frequency) in phraseMap) {
                    results.add(phrase to frequency)
                }
            }
        }

        // Sort by frequency descending and return phrases
        return results
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(9)  // Limit to avoid too many results
    }

    /**
     * Checks if a specific phrase is learned for a given Wubi code.
     */
    fun isLearnedPhrase(wubiCode: String, phrase: String): Boolean {
        val normalizedCode = wubiCode.lowercase().trim()
        return learnedPhrases[normalizedCode]?.containsKey(phrase) == true
    }

    /**
     * Gets the frequency of a learned phrase.
     *
     * @return Frequency count, or 0 if not learned
     */
    fun getFrequency(wubiCode: String, phrase: String): Int {
        val normalizedCode = wubiCode.lowercase().trim()
        return learnedPhrases[normalizedCode]?.get(phrase) ?: 0
    }

    /**
     * Clears pending phrases older than the specified age.
     *
     * @param maxAgeDays Maximum age in days for pending phrases
     */
    fun clearOldPending(maxAgeDays: Int) {
        val maxAgeMillis = maxAgeDays * 24L * 60L * 60L * 1000L
        val cutoffTime = System.currentTimeMillis() - maxAgeMillis
        var removedCount = 0

        val codesToRemove = mutableListOf<String>()

        for ((wubiCode, phraseMap) in pendingPhrases) {
            val phrasesToRemove = phraseMap.entries
                .filter { it.value < cutoffTime }
                .map { it.key }

            for (phrase in phrasesToRemove) {
                phraseMap.remove(phrase)
                removedCount++
            }

            if (phraseMap.isEmpty()) {
                codesToRemove.add(wubiCode)
            }
        }

        for (code in codesToRemove) {
            pendingPhrases.remove(code)
        }

        if (removedCount > 0) {
            saveToPreferences()
        }
    }

    /**
     * Clears all Wubi phrase memory data.
     */
    fun clearAll() {
        pendingPhrases.clear()
        learnedPhrases.clear()
        prefs.edit().clear().apply()
    }

    /**
     * Exports all learned phrases to a JSON string for backup/transfer.
     * Format: { "wubiCode": { "phrase": frequency, ... }, ... }
     */
    fun exportToJson(): String {
        val json = JSONObject()
        for ((wubiCode, phraseMap) in learnedPhrases) {
            val phrasesJson = JSONObject()
            for ((phrase, frequency) in phraseMap) {
                phrasesJson.put(phrase, frequency)
            }
            json.put(wubiCode, phrasesJson)
        }
        return json.toString(2)  // Pretty print with 2-space indent
    }

    /**
     * Imports learned phrases from a JSON string.
     * @param jsonString The JSON string to import
     * @param merge If true, merges with existing phrases (higher frequency wins). If false, replaces all.
     * @return Number of phrases imported
     */
    fun importFromJson(jsonString: String, merge: Boolean = true): Int {
        try {
            val json = JSONObject(jsonString)
            var importedCount = 0

            if (!merge) {
                learnedPhrases.clear()
            }

            val keys = json.keys()
            while (keys.hasNext()) {
                val wubiCode = keys.next()
                val phrasesJson = json.getJSONObject(wubiCode)
                val phraseMap = learnedPhrases.getOrPut(wubiCode) { mutableMapOf() }

                val phraseKeys = phrasesJson.keys()
                while (phraseKeys.hasNext()) {
                    val phrase = phraseKeys.next()
                    val frequency = phrasesJson.getInt(phrase)

                    if (merge) {
                        // Keep higher frequency
                        val existingFreq = phraseMap[phrase] ?: 0
                        if (frequency > existingFreq) {
                            phraseMap[phrase] = frequency
                            importedCount++
                        }
                    } else {
                        phraseMap[phrase] = frequency
                        importedCount++
                    }
                }
            }

            saveToPreferences()
            return importedCount
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * Gets all learned phrases as a list of LearnedPhrase objects.
     * Useful for displaying in settings UI.
     */
    fun getAllLearnedPhrases(): List<LearnedPhrase> {
        val result = mutableListOf<LearnedPhrase>()
        for ((wubiCode, phraseMap) in learnedPhrases) {
            for ((phrase, frequency) in phraseMap) {
                result.add(LearnedPhrase(wubiCode, phrase, frequency))
            }
        }
        // Sort by frequency descending, then by phrase
        return result.sortedWith(compareByDescending<LearnedPhrase> { it.frequency }.thenBy { it.phrase })
    }

    /**
     * Deletes a specific learned phrase.
     * @param wubiCode The Wubi code of the phrase
     * @param phrase The phrase to delete
     * @return true if deleted, false if not found
     */
    fun deleteLearnedPhrase(wubiCode: String, phrase: String): Boolean {
        val normalizedCode = wubiCode.lowercase().trim()
        val phraseMap = learnedPhrases[normalizedCode] ?: return false

        if (phraseMap.remove(phrase) != null) {
            // Remove the code entry if no more phrases
            if (phraseMap.isEmpty()) {
                learnedPhrases.remove(normalizedCode)
            }
            saveToPreferences()
            return true
        }
        return false
    }

    data class LearnedPhrase(
        val wubiCode: String,
        val phrase: String,
        val frequency: Int
    )

    data class PendingPhrase(
        val wubiCode: String,
        val phrase: String,
        val timestamp: Long
    )

    /**
     * Gets all pending phrases as a list of PendingPhrase objects.
     * Returns latest 100 pending phrases in reverse time order (newest first).
     */
    fun getAllPendingPhrases(): List<PendingPhrase> {
        val result = mutableListOf<PendingPhrase>()
        for ((code, phraseMap) in pendingPhrases) {
            for ((phrase, timestamp) in phraseMap) {
                result.add(PendingPhrase(code, phrase, timestamp))
            }
        }
        // Sort by timestamp descending (newest first) and limit to 100
        return result.sortedByDescending { it.timestamp }.take(100)
    }

    /**
     * Gets statistics about the memory data.
     */
    fun getStats(): MemoryStats {
        val pendingCount = pendingPhrases.values.sumOf { it.size }
        val learnedCount = learnedPhrases.values.sumOf { it.size }
        val totalSelections = learnedPhrases.values.sumOf { it.values.sum() }

        return MemoryStats(
            pendingPhraseCount = pendingCount,
            learnedPhraseCount = learnedCount,
            totalSelections = totalSelections
        )
    }

    data class MemoryStats(
        val pendingPhraseCount: Int,
        val learnedPhraseCount: Int,
        val totalSelections: Int
    )

    /**
     * Loads data from SharedPreferences.
     */
    private fun loadFromPreferences() {
        try {
            // Load pending phrases
            val pendingJson = prefs.getString(KEY_PENDING, null)
            if (pendingJson != null) {
                val jsonObject = JSONObject(pendingJson)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val wubiCode = keys.next()
                    val phrasesJson = jsonObject.getJSONObject(wubiCode)
                    val phraseMap = mutableMapOf<String, Long>()
                    val phraseKeys = phrasesJson.keys()
                    while (phraseKeys.hasNext()) {
                        val phrase = phraseKeys.next()
                        phraseMap[phrase] = phrasesJson.getLong(phrase)
                    }
                    pendingPhrases[wubiCode] = phraseMap
                }
            }

            // Load learned phrases
            val learnedJson = prefs.getString(KEY_LEARNED, null)
            if (learnedJson != null) {
                val jsonObject = JSONObject(learnedJson)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val wubiCode = keys.next()
                    val phrasesJson = jsonObject.getJSONObject(wubiCode)
                    val phraseMap = mutableMapOf<String, Int>()
                    val phraseKeys = phrasesJson.keys()
                    while (phraseKeys.hasNext()) {
                        val phrase = phraseKeys.next()
                        phraseMap[phrase] = phrasesJson.getInt(phrase)
                    }
                    learnedPhrases[wubiCode] = phraseMap
                }
            }
        } catch (e: Exception) {
            // Error loading - start fresh
        }
    }

    /**
     * Saves data to SharedPreferences.
     */
    private fun saveToPreferences() {
        try {
            // Save pending phrases
            val pendingJson = JSONObject()
            for ((wubiCode, phraseMap) in pendingPhrases) {
                val phrasesJson = JSONObject()
                for ((phrase, timestamp) in phraseMap) {
                    phrasesJson.put(phrase, timestamp)
                }
                pendingJson.put(wubiCode, phrasesJson)
            }

            // Save learned phrases
            val learnedJson = JSONObject()
            for ((wubiCode, phraseMap) in learnedPhrases) {
                val phrasesJson = JSONObject()
                for ((phrase, frequency) in phraseMap) {
                    phrasesJson.put(phrase, frequency)
                }
                learnedJson.put(wubiCode, phrasesJson)
            }

            prefs.edit()
                .putString(KEY_PENDING, pendingJson.toString())
                .putString(KEY_LEARNED, learnedJson.toString())
                .apply()
        } catch (e: Exception) {
            // Error saving
        }
    }

    companion object {
        private const val PREFS_NAME = "wubi_phrase_memory"
        private const val KEY_PENDING = "pending_phrases"
        private const val KEY_LEARNED = "learned_phrases"
        private const val MAX_PENDING_AGE_DAYS = 30

        @Volatile
        private var instance: WubiPhraseMemory? = null

        /**
         * Gets the singleton instance of WubiPhraseMemory.
         */
        fun getInstance(context: Context): WubiPhraseMemory {
            return instance ?: synchronized(this) {
                instance ?: WubiPhraseMemory(context.applicationContext).also { instance = it }
            }
        }
    }
}
