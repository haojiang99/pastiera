package it.neuralrad.coolwulf.data.shuangpin

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Manages auto-learning of new phrases from Shuangpin input.
 *
 * Similar to AutoPhraseMemory for Pinyin, but uses Shuangpin codes instead.
 * When user types a phrase character by character using Shuangpin codes,
 * this class tracks the sequence and learns it as a phrase when typed twice.
 *
 * Flow:
 * 1. First time: phrase is recorded as "pending"
 * 2. Second time: phrase is promoted to "learned" and becomes available as a candidate
 * 3. Subsequent times: frequency is incremented for priority sorting
 */
class ShuangpinPhraseMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Pending phrases: typed once, awaiting confirmation
    // shuangpinCode → (phrase → timestamp)
    private val pendingPhrases = mutableMapOf<String, MutableMap<String, Long>>()

    // Learned phrases: typed twice or more, confirmed
    // shuangpinCode → (phrase → frequency)
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
     * @param shuangpinCode The combined Shuangpin code (e.g., "nhmc" for 你好吗)
     * @param phrase The combined characters (e.g., "你好")
     */
    fun recordPhrase(shuangpinCode: String, phrase: String) {
        val normalizedCode = shuangpinCode.lowercase().trim()

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
     * Gets learned phrases for a given Shuangpin code, sorted by frequency.
     *
     * @param shuangpinCode The Shuangpin code to look up
     * @return List of learned phrases, most frequent first
     */
    fun getLearnedPhrases(shuangpinCode: String): List<String> {
        val normalizedCode = shuangpinCode.lowercase().trim()
        val phraseMap = learnedPhrases[normalizedCode] ?: return emptyList()

        return phraseMap.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    /**
     * Gets learned phrases whose Shuangpin code starts with the given prefix.
     * This enables partial matching.
     *
     * @param prefix The Shuangpin code prefix to match
     * @return List of learned phrases sorted by frequency, most frequent first
     */
    fun getLearnedPhrasesWithPrefix(prefix: String): List<String> {
        val normalizedPrefix = prefix.lowercase().trim()
        if (normalizedPrefix.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<String, Int>>()  // phrase to frequency

        for ((shuangpinCode, phraseMap) in learnedPhrases) {
            // Check if this code starts with the prefix (but is longer - partial match)
            if (shuangpinCode.startsWith(normalizedPrefix) && shuangpinCode.length > normalizedPrefix.length) {
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
     * Checks if a specific phrase is learned for a given Shuangpin code.
     */
    fun isLearnedPhrase(shuangpinCode: String, phrase: String): Boolean {
        val normalizedCode = shuangpinCode.lowercase().trim()
        return learnedPhrases[normalizedCode]?.containsKey(phrase) == true
    }

    /**
     * Gets the frequency of a learned phrase.
     *
     * @return Frequency count, or 0 if not learned
     */
    fun getFrequency(shuangpinCode: String, phrase: String): Int {
        val normalizedCode = shuangpinCode.lowercase().trim()
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

        for ((shuangpinCode, phraseMap) in pendingPhrases) {
            val phrasesToRemove = phraseMap.entries
                .filter { it.value < cutoffTime }
                .map { it.key }

            for (phrase in phrasesToRemove) {
                phraseMap.remove(phrase)
                removedCount++
            }

            if (phraseMap.isEmpty()) {
                codesToRemove.add(shuangpinCode)
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
     * Clears all Shuangpin phrase memory data.
     */
    fun clearAll() {
        pendingPhrases.clear()
        learnedPhrases.clear()
        prefs.edit().clear().apply()
    }

    /**
     * Gets all learned phrases as a list of LearnedPhrase objects.
     * Useful for displaying in settings UI.
     */
    fun getAllLearnedPhrases(): List<LearnedPhrase> {
        val result = mutableListOf<LearnedPhrase>()
        for ((shuangpinCode, phraseMap) in learnedPhrases) {
            for ((phrase, frequency) in phraseMap) {
                result.add(LearnedPhrase(shuangpinCode, phrase, frequency))
            }
        }
        // Sort by frequency descending, then by phrase
        return result.sortedWith(compareByDescending<LearnedPhrase> { it.frequency }.thenBy { it.phrase })
    }

    /**
     * Deletes a specific learned phrase.
     * @param shuangpinCode The Shuangpin code of the phrase
     * @param phrase The phrase to delete
     * @return true if deleted, false if not found
     */
    fun deleteLearnedPhrase(shuangpinCode: String, phrase: String): Boolean {
        val normalizedCode = shuangpinCode.lowercase().trim()
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
        val shuangpinCode: String,
        val phrase: String,
        val frequency: Int
    )

    data class PendingPhrase(
        val shuangpinCode: String,
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
                    val shuangpinCode = keys.next()
                    val phrasesJson = jsonObject.getJSONObject(shuangpinCode)
                    val phraseMap = mutableMapOf<String, Long>()
                    val phraseKeys = phrasesJson.keys()
                    while (phraseKeys.hasNext()) {
                        val phrase = phraseKeys.next()
                        phraseMap[phrase] = phrasesJson.getLong(phrase)
                    }
                    pendingPhrases[shuangpinCode] = phraseMap
                }
            }

            // Load learned phrases
            val learnedJson = prefs.getString(KEY_LEARNED, null)
            if (learnedJson != null) {
                val jsonObject = JSONObject(learnedJson)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val shuangpinCode = keys.next()
                    val phrasesJson = jsonObject.getJSONObject(shuangpinCode)
                    val phraseMap = mutableMapOf<String, Int>()
                    val phraseKeys = phrasesJson.keys()
                    while (phraseKeys.hasNext()) {
                        val phrase = phraseKeys.next()
                        phraseMap[phrase] = phrasesJson.getInt(phrase)
                    }
                    learnedPhrases[shuangpinCode] = phraseMap
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
            for ((shuangpinCode, phraseMap) in pendingPhrases) {
                val phrasesJson = JSONObject()
                for ((phrase, timestamp) in phraseMap) {
                    phrasesJson.put(phrase, timestamp)
                }
                pendingJson.put(shuangpinCode, phrasesJson)
            }

            // Save learned phrases
            val learnedJson = JSONObject()
            for ((shuangpinCode, phraseMap) in learnedPhrases) {
                val phrasesJson = JSONObject()
                for ((phrase, frequency) in phraseMap) {
                    phrasesJson.put(phrase, frequency)
                }
                learnedJson.put(shuangpinCode, phrasesJson)
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
        private const val PREFS_NAME = "shuangpin_phrase_memory"
        private const val KEY_PENDING = "pending_phrases"
        private const val KEY_LEARNED = "learned_phrases"
        private const val MAX_PENDING_AGE_DAYS = 30

        @Volatile
        private var instance: ShuangpinPhraseMemory? = null

        /**
         * Gets the singleton instance of ShuangpinPhraseMemory.
         */
        fun getInstance(context: Context): ShuangpinPhraseMemory {
            return instance ?: synchronized(this) {
                instance ?: ShuangpinPhraseMemory(context.applicationContext).also { instance = it }
            }
        }
    }
}
