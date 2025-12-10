package it.neuralrad.coolwulf.data.ziranma

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Manages auto-learning of new phrases from Ziranma input.
 *
 * Similar to ShuangpinPhraseMemory, but uses Ziranma codes instead.
 * When user types a phrase character by character using Ziranma codes,
 * this class tracks the sequence and learns it as a phrase when typed twice.
 *
 * Flow:
 * 1. First time: phrase is recorded as "pending"
 * 2. Second time: phrase is promoted to "learned" and becomes available as a candidate
 * 3. Subsequent times: frequency is incremented for priority sorting
 */
class ZiranmaPhraseMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Pending phrases: typed once, awaiting confirmation
    // ziranmaCode → (phrase → timestamp)
    private val pendingPhrases = mutableMapOf<String, MutableMap<String, Long>>()

    // Learned phrases: typed twice or more, confirmed
    // ziranmaCode → (phrase → frequency)
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
     * @param ziranmaCode The combined Ziranma code
     * @param phrase The combined characters
     */
    fun recordPhrase(ziranmaCode: String, phrase: String) {
        val normalizedCode = ziranmaCode.lowercase().trim()

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
     * Gets learned phrases for a given Ziranma code, sorted by frequency.
     *
     * @param ziranmaCode The Ziranma code to look up
     * @return List of learned phrases, most frequent first
     */
    fun getLearnedPhrases(ziranmaCode: String): List<String> {
        val normalizedCode = ziranmaCode.lowercase().trim()
        val phraseMap = learnedPhrases[normalizedCode] ?: return emptyList()

        return phraseMap.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    /**
     * Gets learned phrases whose Ziranma code starts with the given prefix.
     * This enables partial matching.
     *
     * @param prefix The Ziranma code prefix to match
     * @return List of learned phrases sorted by frequency, most frequent first
     */
    fun getLearnedPhrasesWithPrefix(prefix: String): List<String> {
        val normalizedPrefix = prefix.lowercase().trim()
        if (normalizedPrefix.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<String, Int>>()  // phrase to frequency

        for ((ziranmaCode, phraseMap) in learnedPhrases) {
            // Check if this code starts with the prefix (but is longer - partial match)
            if (ziranmaCode.startsWith(normalizedPrefix) && ziranmaCode.length > normalizedPrefix.length) {
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
     * Checks if a specific phrase is learned for a given Ziranma code.
     */
    fun isLearnedPhrase(ziranmaCode: String, phrase: String): Boolean {
        val normalizedCode = ziranmaCode.lowercase().trim()
        return learnedPhrases[normalizedCode]?.containsKey(phrase) == true
    }

    /**
     * Gets the frequency of a learned phrase.
     *
     * @return Frequency count, or 0 if not learned
     */
    fun getFrequency(ziranmaCode: String, phrase: String): Int {
        val normalizedCode = ziranmaCode.lowercase().trim()
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

        for ((ziranmaCode, phraseMap) in pendingPhrases) {
            val phrasesToRemove = phraseMap.entries
                .filter { it.value < cutoffTime }
                .map { it.key }

            for (phrase in phrasesToRemove) {
                phraseMap.remove(phrase)
                removedCount++
            }

            if (phraseMap.isEmpty()) {
                codesToRemove.add(ziranmaCode)
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
     * Clears all Ziranma phrase memory data.
     */
    fun clearAll() {
        pendingPhrases.clear()
        learnedPhrases.clear()
        prefs.edit().clear().apply()
    }

    /**
     * Gets all learned phrases as a list of LearnedPhrase objects.
     */
    fun getAllLearnedPhrases(): List<LearnedPhrase> {
        val result = mutableListOf<LearnedPhrase>()
        for ((ziranmaCode, phraseMap) in learnedPhrases) {
            for ((phrase, frequency) in phraseMap) {
                result.add(LearnedPhrase(ziranmaCode, phrase, frequency))
            }
        }
        return result.sortedWith(compareByDescending<LearnedPhrase> { it.frequency }.thenBy { it.phrase })
    }

    /**
     * Deletes a specific learned phrase.
     */
    fun deleteLearnedPhrase(ziranmaCode: String, phrase: String): Boolean {
        val normalizedCode = ziranmaCode.lowercase().trim()
        val phraseMap = learnedPhrases[normalizedCode] ?: return false

        if (phraseMap.remove(phrase) != null) {
            if (phraseMap.isEmpty()) {
                learnedPhrases.remove(normalizedCode)
            }
            saveToPreferences()
            return true
        }
        return false
    }

    data class LearnedPhrase(
        val ziranmaCode: String,
        val phrase: String,
        val frequency: Int
    )

    data class MemoryStats(
        val pendingPhraseCount: Int,
        val learnedPhraseCount: Int,
        val totalSelections: Int
    )

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

    private fun loadFromPreferences() {
        try {
            val pendingJson = prefs.getString(KEY_PENDING, null)
            if (pendingJson != null) {
                val jsonObject = JSONObject(pendingJson)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val ziranmaCode = keys.next()
                    val phrasesJson = jsonObject.getJSONObject(ziranmaCode)
                    val phraseMap = mutableMapOf<String, Long>()
                    val phraseKeys = phrasesJson.keys()
                    while (phraseKeys.hasNext()) {
                        val phrase = phraseKeys.next()
                        phraseMap[phrase] = phrasesJson.getLong(phrase)
                    }
                    pendingPhrases[ziranmaCode] = phraseMap
                }
            }

            val learnedJson = prefs.getString(KEY_LEARNED, null)
            if (learnedJson != null) {
                val jsonObject = JSONObject(learnedJson)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val ziranmaCode = keys.next()
                    val phrasesJson = jsonObject.getJSONObject(ziranmaCode)
                    val phraseMap = mutableMapOf<String, Int>()
                    val phraseKeys = phrasesJson.keys()
                    while (phraseKeys.hasNext()) {
                        val phrase = phraseKeys.next()
                        phraseMap[phrase] = phrasesJson.getInt(phrase)
                    }
                    learnedPhrases[ziranmaCode] = phraseMap
                }
            }
        } catch (e: Exception) {
            // Error loading - start fresh
        }
    }

    private fun saveToPreferences() {
        try {
            val pendingJson = JSONObject()
            for ((ziranmaCode, phraseMap) in pendingPhrases) {
                val phrasesJson = JSONObject()
                for ((phrase, timestamp) in phraseMap) {
                    phrasesJson.put(phrase, timestamp)
                }
                pendingJson.put(ziranmaCode, phrasesJson)
            }

            val learnedJson = JSONObject()
            for ((ziranmaCode, phraseMap) in learnedPhrases) {
                val phrasesJson = JSONObject()
                for ((phrase, frequency) in phraseMap) {
                    phrasesJson.put(phrase, frequency)
                }
                learnedJson.put(ziranmaCode, phrasesJson)
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
        private const val PREFS_NAME = "ziranma_phrase_memory"
        private const val KEY_PENDING = "pending_phrases"
        private const val KEY_LEARNED = "learned_phrases"
        private const val MAX_PENDING_AGE_DAYS = 30

        @Volatile
        private var instance: ZiranmaPhraseMemory? = null

        fun getInstance(context: Context): ZiranmaPhraseMemory {
            return instance ?: synchronized(this) {
                instance ?: ZiranmaPhraseMemory(context.applicationContext).also { instance = it }
            }
        }
    }
}
