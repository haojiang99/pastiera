package it.neuralrad.coolwulf.data.pinyin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Manages auto-learning of new phrases from user input.
 *
 * When user types a phrase character by character (e.g., "zhang" → "张", "san" → "三"),
 * this class tracks the sequence and learns it as a phrase when typed twice.
 *
 * Flow:
 * 1. First time: phrase is recorded as "pending"
 * 2. Second time: phrase is promoted to "learned" and becomes available as a candidate
 * 3. Subsequent times: frequency is incremented for priority sorting
 */
class AutoPhraseMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Pending phrases: typed once, awaiting confirmation
    // pinyin → (phrase → timestamp)
    private val pendingPhrases = mutableMapOf<String, MutableMap<String, Long>>()

    // Learned phrases: typed twice or more, confirmed
    // pinyin → (phrase → frequency)
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
     * @param pinyin The combined pinyin (e.g., "zhangsan")
     * @param phrase The combined characters (e.g., "张三")
     */
    fun recordPhrase(pinyin: String, phrase: String) {
        val normalizedPinyin = pinyin.lowercase().trim()

        // Skip single characters - not a phrase
        if (phrase.length < 2) {
            return
        }

        // Check if already learned
        val learnedMap = learnedPhrases[normalizedPinyin]
        if (learnedMap != null && learnedMap.containsKey(phrase)) {
            // Already learned - increment frequency
            learnedMap[phrase] = (learnedMap[phrase] ?: 1) + 1
            Log.d(TAG, "Incremented learned phrase: '$normalizedPinyin' → '$phrase' (count: ${learnedMap[phrase]})")
            saveToPreferences()
            return
        }

        // Check if pending
        val pendingMap = pendingPhrases[normalizedPinyin]
        if (pendingMap != null && pendingMap.containsKey(phrase)) {
            // Pending → promote to learned
            pendingMap.remove(phrase)
            if (pendingMap.isEmpty()) {
                pendingPhrases.remove(normalizedPinyin)
            }

            val learned = learnedPhrases.getOrPut(normalizedPinyin) { mutableMapOf() }
            learned[phrase] = 1
            Log.d(TAG, "Promoted to learned: '$normalizedPinyin' → '$phrase'")
            saveToPreferences()
            return
        }

        // New phrase - add to pending
        val pending = pendingPhrases.getOrPut(normalizedPinyin) { mutableMapOf() }
        pending[phrase] = System.currentTimeMillis()
        Log.d(TAG, "Added to pending: '$normalizedPinyin' → '$phrase'")
        saveToPreferences()
    }

    /**
     * Gets learned phrases for a given pinyin, sorted by frequency.
     *
     * @param pinyin The pinyin to look up
     * @return List of learned phrases, most frequent first
     */
    fun getLearnedPhrases(pinyin: String): List<String> {
        val normalizedPinyin = pinyin.lowercase().trim()
        val phraseMap = learnedPhrases[normalizedPinyin] ?: return emptyList()

        return phraseMap.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    /**
     * Gets learned phrases whose pinyin starts with the given prefix.
     * This enables partial matching - e.g., typing "wos" shows "我是" (woshi).
     *
     * @param prefix The pinyin prefix to match
     * @return List of learned phrases sorted by frequency, most frequent first
     */
    fun getLearnedPhrasesWithPrefix(prefix: String): List<String> {
        val normalizedPrefix = prefix.lowercase().trim()
        if (normalizedPrefix.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<String, Int>>()  // phrase to frequency

        for ((pinyin, phraseMap) in learnedPhrases) {
            // Check if this pinyin starts with the prefix (but is longer - partial match)
            if (pinyin.startsWith(normalizedPrefix) && pinyin.length > normalizedPrefix.length) {
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
     * Checks if a specific phrase is learned for a given pinyin.
     */
    fun isLearnedPhrase(pinyin: String, phrase: String): Boolean {
        val normalizedPinyin = pinyin.lowercase().trim()
        return learnedPhrases[normalizedPinyin]?.containsKey(phrase) == true
    }

    /**
     * Gets the frequency of a learned phrase.
     *
     * @return Frequency count, or 0 if not learned
     */
    fun getFrequency(pinyin: String, phrase: String): Int {
        val normalizedPinyin = pinyin.lowercase().trim()
        return learnedPhrases[normalizedPinyin]?.get(phrase) ?: 0
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

        val pinyinToRemove = mutableListOf<String>()

        for ((pinyin, phraseMap) in pendingPhrases) {
            val phrasesToRemove = phraseMap.entries
                .filter { it.value < cutoffTime }
                .map { it.key }

            for (phrase in phrasesToRemove) {
                phraseMap.remove(phrase)
                removedCount++
            }

            if (phraseMap.isEmpty()) {
                pinyinToRemove.add(pinyin)
            }
        }

        for (pinyin in pinyinToRemove) {
            pendingPhrases.remove(pinyin)
        }

        if (removedCount > 0) {
            Log.d(TAG, "Cleaned up $removedCount old pending phrases")
            saveToPreferences()
        }
    }

    /**
     * Clears all auto-phrase memory data.
     */
    fun clearAll() {
        pendingPhrases.clear()
        learnedPhrases.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "All auto-phrase memory cleared")
    }

    /**
     * Gets all learned phrases as a list of LearnedPhrase objects.
     * Useful for displaying in settings UI.
     */
    fun getAllLearnedPhrases(): List<LearnedPhrase> {
        val result = mutableListOf<LearnedPhrase>()
        for ((pinyin, phraseMap) in learnedPhrases) {
            for ((phrase, frequency) in phraseMap) {
                result.add(LearnedPhrase(pinyin, phrase, frequency))
            }
        }
        // Sort by frequency descending, then by phrase
        return result.sortedWith(compareByDescending<LearnedPhrase> { it.frequency }.thenBy { it.phrase })
    }

    /**
     * Deletes a specific learned phrase.
     * @param pinyin The pinyin of the phrase
     * @param phrase The phrase to delete
     * @return true if deleted, false if not found
     */
    fun deleteLearnedPhrase(pinyin: String, phrase: String): Boolean {
        val normalizedPinyin = pinyin.lowercase().trim()
        val phraseMap = learnedPhrases[normalizedPinyin] ?: return false

        if (phraseMap.remove(phrase) != null) {
            // Remove the pinyin entry if no more phrases
            if (phraseMap.isEmpty()) {
                learnedPhrases.remove(normalizedPinyin)
            }
            saveToPreferences()
            Log.d(TAG, "Deleted learned phrase: '$normalizedPinyin' → '$phrase'")
            return true
        }
        return false
    }

    data class LearnedPhrase(
        val pinyin: String,
        val phrase: String,
        val frequency: Int
    )

    data class PendingPhrase(
        val pinyin: String,
        val phrase: String,
        val timestamp: Long
    )

    /**
     * Gets all pending phrases as a list of PendingPhrase objects.
     * Returns latest 100 pending phrases in reverse time order (newest first).
     */
    fun getAllPendingPhrases(): List<PendingPhrase> {
        val result = mutableListOf<PendingPhrase>()
        for ((pinyin, phraseMap) in pendingPhrases) {
            for ((phrase, timestamp) in phraseMap) {
                result.add(PendingPhrase(pinyin, phrase, timestamp))
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
                    val pinyin = keys.next()
                    val phrasesJson = jsonObject.getJSONObject(pinyin)
                    val phraseMap = mutableMapOf<String, Long>()
                    val phraseKeys = phrasesJson.keys()
                    while (phraseKeys.hasNext()) {
                        val phrase = phraseKeys.next()
                        phraseMap[phrase] = phrasesJson.getLong(phrase)
                    }
                    pendingPhrases[pinyin] = phraseMap
                }
            }

            // Load learned phrases
            val learnedJson = prefs.getString(KEY_LEARNED, null)
            if (learnedJson != null) {
                val jsonObject = JSONObject(learnedJson)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val pinyin = keys.next()
                    val phrasesJson = jsonObject.getJSONObject(pinyin)
                    val phraseMap = mutableMapOf<String, Int>()
                    val phraseKeys = phrasesJson.keys()
                    while (phraseKeys.hasNext()) {
                        val phrase = phraseKeys.next()
                        phraseMap[phrase] = phrasesJson.getInt(phrase)
                    }
                    learnedPhrases[pinyin] = phraseMap
                }
            }

            Log.d(TAG, "Loaded auto-phrase memory: ${pendingPhrases.size} pending pinyin, ${learnedPhrases.size} learned pinyin")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading auto-phrase memory", e)
        }
    }

    /**
     * Saves data to SharedPreferences.
     */
    private fun saveToPreferences() {
        try {
            // Save pending phrases
            val pendingJson = JSONObject()
            for ((pinyin, phraseMap) in pendingPhrases) {
                val phrasesJson = JSONObject()
                for ((phrase, timestamp) in phraseMap) {
                    phrasesJson.put(phrase, timestamp)
                }
                pendingJson.put(pinyin, phrasesJson)
            }

            // Save learned phrases
            val learnedJson = JSONObject()
            for ((pinyin, phraseMap) in learnedPhrases) {
                val phrasesJson = JSONObject()
                for ((phrase, frequency) in phraseMap) {
                    phrasesJson.put(phrase, frequency)
                }
                learnedJson.put(pinyin, phrasesJson)
            }

            prefs.edit()
                .putString(KEY_PENDING, pendingJson.toString())
                .putString(KEY_LEARNED, learnedJson.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving auto-phrase memory", e)
        }
    }

    companion object {
        private const val TAG = "AutoPhraseMemory"
        private const val PREFS_NAME = "auto_phrase_memory"
        private const val KEY_PENDING = "pending_phrases"
        private const val KEY_LEARNED = "learned_phrases"
        private const val MAX_PENDING_AGE_DAYS = 30

        @Volatile
        private var instance: AutoPhraseMemory? = null

        /**
         * Gets the singleton instance of AutoPhraseMemory.
         */
        fun getInstance(context: Context): AutoPhraseMemory {
            return instance ?: synchronized(this) {
                instance ?: AutoPhraseMemory(context.applicationContext).also { instance = it }
            }
        }
    }
}
