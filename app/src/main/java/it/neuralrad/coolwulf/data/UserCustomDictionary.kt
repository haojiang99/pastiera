package it.neuralrad.coolwulf.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages user-defined custom dictionary mappings for Pinyin, Shuangpin, Ziranma, Wubi, and Zhenma input methods.
 * Users can define their own letter -> phrase mappings for quick input.
 */
class UserCustomDictionary(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache: code -> list of phrases (Pinyin)
    private val pinyinMappings = mutableMapOf<String, MutableList<String>>()

    // In-memory cache: code -> list of phrases (Shuangpin)
    private val shuangpinMappings = mutableMapOf<String, MutableList<String>>()

    // In-memory cache: code -> list of phrases (Ziranma)
    private val ziranmaMappings = mutableMapOf<String, MutableList<String>>()

    // In-memory cache: code -> list of phrases (Wubi)
    private val wubiMappings = mutableMapOf<String, MutableList<String>>()

    // In-memory cache: code -> list of phrases (Zhenma)
    private val zhenmaMappings = mutableMapOf<String, MutableList<String>>()

    init {
        loadFromPreferences()
    }

    /**
     * Adds a custom mapping for Pinyin.
     * @param code The pinyin code (e.g., "nh" for "你好")
     * @param phrase The phrase to map to
     */
    fun addPinyinMapping(code: String, phrase: String) {
        val normalizedCode = code.lowercase().trim()
        if (normalizedCode.isEmpty() || phrase.isEmpty()) return

        val phrases = pinyinMappings.getOrPut(normalizedCode) { mutableListOf() }
        if (!phrases.contains(phrase)) {
            phrases.add(0, phrase)  // Add to front for priority
            saveToPreferencesAsync()
            Log.d(TAG, "Added Pinyin mapping: '$normalizedCode' -> '$phrase'")
        }
    }

    /**
     * Adds a custom mapping for Shuangpin.
     * @param code The shuangpin code (e.g., "nh" for "你好")
     * @param phrase The phrase to map to
     */
    fun addShuangpinMapping(code: String, phrase: String) {
        val normalizedCode = code.lowercase().trim()
        if (normalizedCode.isEmpty() || phrase.isEmpty()) return

        val phrases = shuangpinMappings.getOrPut(normalizedCode) { mutableListOf() }
        if (!phrases.contains(phrase)) {
            phrases.add(0, phrase)  // Add to front for priority
            saveToPreferencesAsync()
            Log.d(TAG, "Added Shuangpin mapping: '$normalizedCode' -> '$phrase'")
        }
    }

    /**
     * Adds a custom mapping for Ziranma.
     * @param code The ziranma code (e.g., "nh" for "你好")
     * @param phrase The phrase to map to
     */
    fun addZiranmaMapping(code: String, phrase: String) {
        val normalizedCode = code.lowercase().trim()
        if (normalizedCode.isEmpty() || phrase.isEmpty()) return

        val phrases = ziranmaMappings.getOrPut(normalizedCode) { mutableListOf() }
        if (!phrases.contains(phrase)) {
            phrases.add(0, phrase)  // Add to front for priority
            saveToPreferencesAsync()
            Log.d(TAG, "Added Ziranma mapping: '$normalizedCode' -> '$phrase'")
        }
    }

    /**
     * Adds a custom mapping for Wubi.
     * @param code The wubi code (e.g., "wq" for custom phrase)
     * @param phrase The phrase to map to
     */
    fun addWubiMapping(code: String, phrase: String) {
        val normalizedCode = code.lowercase().trim()
        if (normalizedCode.isEmpty() || phrase.isEmpty()) return

        val phrases = wubiMappings.getOrPut(normalizedCode) { mutableListOf() }
        if (!phrases.contains(phrase)) {
            phrases.add(0, phrase)  // Add to front for priority
            saveToPreferencesAsync()
            Log.d(TAG, "Added Wubi mapping: '$normalizedCode' -> '$phrase'")
        }
    }

    /**
     * Adds a custom mapping for Zhenma.
     * @param code The zhenma code (e.g., "ab" for custom phrase)
     * @param phrase The phrase to map to
     */
    fun addZhenmaMapping(code: String, phrase: String) {
        val normalizedCode = code.lowercase().trim()
        if (normalizedCode.isEmpty() || phrase.isEmpty()) return

        val phrases = zhenmaMappings.getOrPut(normalizedCode) { mutableListOf() }
        if (!phrases.contains(phrase)) {
            phrases.add(0, phrase)  // Add to front for priority
            saveToPreferencesAsync()
            Log.d(TAG, "Added Zhenma mapping: '$normalizedCode' -> '$phrase'")
        }
    }

    /**
     * Removes a Pinyin mapping.
     */
    fun removePinyinMapping(code: String, phrase: String) {
        val normalizedCode = code.lowercase().trim()
        pinyinMappings[normalizedCode]?.remove(phrase)
        if (pinyinMappings[normalizedCode]?.isEmpty() == true) {
            pinyinMappings.remove(normalizedCode)
        }
        saveToPreferencesAsync()
        Log.d(TAG, "Removed Pinyin mapping: '$normalizedCode' -> '$phrase'")
    }

    /**
     * Removes a Shuangpin mapping.
     */
    fun removeShuangpinMapping(code: String, phrase: String) {
        val normalizedCode = code.lowercase().trim()
        shuangpinMappings[normalizedCode]?.remove(phrase)
        if (shuangpinMappings[normalizedCode]?.isEmpty() == true) {
            shuangpinMappings.remove(normalizedCode)
        }
        saveToPreferencesAsync()
        Log.d(TAG, "Removed Shuangpin mapping: '$normalizedCode' -> '$phrase'")
    }

    /**
     * Removes a Ziranma mapping.
     */
    fun removeZiranmaMapping(code: String, phrase: String) {
        val normalizedCode = code.lowercase().trim()
        ziranmaMappings[normalizedCode]?.remove(phrase)
        if (ziranmaMappings[normalizedCode]?.isEmpty() == true) {
            ziranmaMappings.remove(normalizedCode)
        }
        saveToPreferencesAsync()
        Log.d(TAG, "Removed Ziranma mapping: '$normalizedCode' -> '$phrase'")
    }

    /**
     * Removes a Wubi mapping.
     */
    fun removeWubiMapping(code: String, phrase: String) {
        val normalizedCode = code.lowercase().trim()
        wubiMappings[normalizedCode]?.remove(phrase)
        if (wubiMappings[normalizedCode]?.isEmpty() == true) {
            wubiMappings.remove(normalizedCode)
        }
        saveToPreferencesAsync()
        Log.d(TAG, "Removed Wubi mapping: '$normalizedCode' -> '$phrase'")
    }

    /**
     * Removes a Zhenma mapping.
     */
    fun removeZhenmaMapping(code: String, phrase: String) {
        val normalizedCode = code.lowercase().trim()
        zhenmaMappings[normalizedCode]?.remove(phrase)
        if (zhenmaMappings[normalizedCode]?.isEmpty() == true) {
            zhenmaMappings.remove(normalizedCode)
        }
        saveToPreferencesAsync()
        Log.d(TAG, "Removed Zhenma mapping: '$normalizedCode' -> '$phrase'")
    }

    /**
     * Gets custom Pinyin phrases for a code.
     * Returns phrases that should be prioritized in candidates.
     */
    fun getPinyinPhrases(code: String): List<String> {
        val normalizedCode = code.lowercase().trim()
        return pinyinMappings[normalizedCode]?.toList() ?: emptyList()
    }

    /**
     * Gets custom Shuangpin phrases for a code.
     * Returns phrases that should be prioritized in candidates.
     */
    fun getShuangpinPhrases(code: String): List<String> {
        val normalizedCode = code.lowercase().trim()
        return shuangpinMappings[normalizedCode]?.toList() ?: emptyList()
    }

    /**
     * Gets custom Ziranma phrases for a code.
     * Returns phrases that should be prioritized in candidates.
     */
    fun getZiranmaPhrases(code: String): List<String> {
        val normalizedCode = code.lowercase().trim()
        return ziranmaMappings[normalizedCode]?.toList() ?: emptyList()
    }

    /**
     * Gets custom Wubi phrases for a code.
     * Returns phrases that should be prioritized in candidates.
     */
    fun getWubiPhrases(code: String): List<String> {
        val normalizedCode = code.lowercase().trim()
        return wubiMappings[normalizedCode]?.toList() ?: emptyList()
    }

    /**
     * Gets custom Zhenma phrases for a code.
     * Returns phrases that should be prioritized in candidates.
     */
    fun getZhenmaPhrases(code: String): List<String> {
        val normalizedCode = code.lowercase().trim()
        return zhenmaMappings[normalizedCode]?.toList() ?: emptyList()
    }

    /**
     * Gets all Pinyin mappings as a list of pairs.
     */
    fun getAllPinyinMappings(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for ((code, phrases) in pinyinMappings) {
            for (phrase in phrases) {
                result.add(Pair(code, phrase))
            }
        }
        return result.sortedBy { it.first }
    }

    /**
     * Gets all Shuangpin mappings as a list of pairs.
     */
    fun getAllShuangpinMappings(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for ((code, phrases) in shuangpinMappings) {
            for (phrase in phrases) {
                result.add(Pair(code, phrase))
            }
        }
        return result.sortedBy { it.first }
    }

    /**
     * Gets all Ziranma mappings as a list of pairs.
     */
    fun getAllZiranmaMappings(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for ((code, phrases) in ziranmaMappings) {
            for (phrase in phrases) {
                result.add(Pair(code, phrase))
            }
        }
        return result.sortedBy { it.first }
    }

    /**
     * Gets all Wubi mappings as a list of pairs.
     */
    fun getAllWubiMappings(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for ((code, phrases) in wubiMappings) {
            for (phrase in phrases) {
                result.add(Pair(code, phrase))
            }
        }
        return result.sortedBy { it.first }
    }

    /**
     * Gets all Zhenma mappings as a list of pairs.
     */
    fun getAllZhenmaMappings(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for ((code, phrases) in zhenmaMappings) {
            for (phrase in phrases) {
                result.add(Pair(code, phrase))
            }
        }
        return result.sortedBy { it.first }
    }

    /**
     * Clears all Pinyin mappings.
     */
    fun clearAllPinyinMappings() {
        pinyinMappings.clear()
        saveToPreferencesAsync()
        Log.d(TAG, "All Pinyin custom mappings cleared")
    }

    /**
     * Clears all Shuangpin mappings.
     */
    fun clearAllShuangpinMappings() {
        shuangpinMappings.clear()
        saveToPreferencesAsync()
        Log.d(TAG, "All Shuangpin custom mappings cleared")
    }

    /**
     * Clears all Ziranma mappings.
     */
    fun clearAllZiranmaMappings() {
        ziranmaMappings.clear()
        saveToPreferencesAsync()
        Log.d(TAG, "All Ziranma custom mappings cleared")
    }

    /**
     * Clears all Wubi mappings.
     */
    fun clearAllWubiMappings() {
        wubiMappings.clear()
        saveToPreferencesAsync()
        Log.d(TAG, "All Wubi custom mappings cleared")
    }

    /**
     * Clears all Zhenma mappings.
     */
    fun clearAllZhenmaMappings() {
        zhenmaMappings.clear()
        saveToPreferencesAsync()
        Log.d(TAG, "All Zhenma custom mappings cleared")
    }

    /**
     * Loads mappings from SharedPreferences.
     */
    private fun loadFromPreferences() {
        try {
            // Load Pinyin mappings
            val pinyinJson = prefs.getString(KEY_PINYIN_MAPPINGS, null)
            if (pinyinJson != null) {
                val jsonObject = JSONObject(pinyinJson)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val code = keys.next()
                    val phrasesArray = jsonObject.getJSONArray(code)
                    val phrases = mutableListOf<String>()
                    for (i in 0 until phrasesArray.length()) {
                        phrases.add(phrasesArray.getString(i))
                    }
                    pinyinMappings[code] = phrases
                }
                Log.d(TAG, "Loaded ${pinyinMappings.size} Pinyin custom mappings")
            }

            // Load Shuangpin mappings
            val shuangpinJson = prefs.getString(KEY_SHUANGPIN_MAPPINGS, null)
            if (shuangpinJson != null) {
                val jsonObject = JSONObject(shuangpinJson)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val code = keys.next()
                    val phrasesArray = jsonObject.getJSONArray(code)
                    val phrases = mutableListOf<String>()
                    for (i in 0 until phrasesArray.length()) {
                        phrases.add(phrasesArray.getString(i))
                    }
                    shuangpinMappings[code] = phrases
                }
                Log.d(TAG, "Loaded ${shuangpinMappings.size} Shuangpin custom mappings")
            }

            // Load Ziranma mappings
            val ziranmaJson = prefs.getString(KEY_ZIRANMA_MAPPINGS, null)
            if (ziranmaJson != null) {
                val jsonObject = JSONObject(ziranmaJson)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val code = keys.next()
                    val phrasesArray = jsonObject.getJSONArray(code)
                    val phrases = mutableListOf<String>()
                    for (i in 0 until phrasesArray.length()) {
                        phrases.add(phrasesArray.getString(i))
                    }
                    ziranmaMappings[code] = phrases
                }
                Log.d(TAG, "Loaded ${ziranmaMappings.size} Ziranma custom mappings")
            }

            // Load Wubi mappings
            val wubiJson = prefs.getString(KEY_WUBI_MAPPINGS, null)
            if (wubiJson != null) {
                val jsonObject = JSONObject(wubiJson)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val code = keys.next()
                    val phrasesArray = jsonObject.getJSONArray(code)
                    val phrases = mutableListOf<String>()
                    for (i in 0 until phrasesArray.length()) {
                        phrases.add(phrasesArray.getString(i))
                    }
                    wubiMappings[code] = phrases
                }
                Log.d(TAG, "Loaded ${wubiMappings.size} Wubi custom mappings")
            }

            // Load Zhenma mappings
            val zhenmaJson = prefs.getString(KEY_ZHENMA_MAPPINGS, null)
            if (zhenmaJson != null) {
                val jsonObject = JSONObject(zhenmaJson)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val code = keys.next()
                    val phrasesArray = jsonObject.getJSONArray(code)
                    val phrases = mutableListOf<String>()
                    for (i in 0 until phrasesArray.length()) {
                        phrases.add(phrasesArray.getString(i))
                    }
                    zhenmaMappings[code] = phrases
                }
                Log.d(TAG, "Loaded ${zhenmaMappings.size} Zhenma custom mappings")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom dictionary from preferences", e)
        }
    }

    /**
     * Saves mappings to SharedPreferences asynchronously.
     */
    private fun saveToPreferencesAsync() {
        try {
            // Save Pinyin mappings
            val pinyinJson = JSONObject()
            for ((code, phrases) in pinyinMappings) {
                val phrasesArray = JSONArray()
                for (phrase in phrases) {
                    phrasesArray.put(phrase)
                }
                pinyinJson.put(code, phrasesArray)
            }

            // Save Shuangpin mappings
            val shuangpinJson = JSONObject()
            for ((code, phrases) in shuangpinMappings) {
                val phrasesArray = JSONArray()
                for (phrase in phrases) {
                    phrasesArray.put(phrase)
                }
                shuangpinJson.put(code, phrasesArray)
            }

            // Save Ziranma mappings
            val ziranmaJson = JSONObject()
            for ((code, phrases) in ziranmaMappings) {
                val phrasesArray = JSONArray()
                for (phrase in phrases) {
                    phrasesArray.put(phrase)
                }
                ziranmaJson.put(code, phrasesArray)
            }

            // Save Wubi mappings
            val wubiJson = JSONObject()
            for ((code, phrases) in wubiMappings) {
                val phrasesArray = JSONArray()
                for (phrase in phrases) {
                    phrasesArray.put(phrase)
                }
                wubiJson.put(code, phrasesArray)
            }

            // Save Zhenma mappings
            val zhenmaJson = JSONObject()
            for ((code, phrases) in zhenmaMappings) {
                val phrasesArray = JSONArray()
                for (phrase in phrases) {
                    phrasesArray.put(phrase)
                }
                zhenmaJson.put(code, phrasesArray)
            }

            prefs.edit()
                .putString(KEY_PINYIN_MAPPINGS, pinyinJson.toString())
                .putString(KEY_SHUANGPIN_MAPPINGS, shuangpinJson.toString())
                .putString(KEY_ZIRANMA_MAPPINGS, ziranmaJson.toString())
                .putString(KEY_WUBI_MAPPINGS, wubiJson.toString())
                .putString(KEY_ZHENMA_MAPPINGS, zhenmaJson.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom dictionary to preferences", e)
        }
    }

    companion object {
        private const val TAG = "UserCustomDictionary"
        private const val PREFS_NAME = "user_custom_dictionary"
        private const val KEY_PINYIN_MAPPINGS = "pinyin_mappings"
        private const val KEY_SHUANGPIN_MAPPINGS = "shuangpin_mappings"
        private const val KEY_ZIRANMA_MAPPINGS = "ziranma_mappings"
        private const val KEY_WUBI_MAPPINGS = "wubi_mappings"
        private const val KEY_ZHENMA_MAPPINGS = "zhenma_mappings"

        @Volatile
        private var instance: UserCustomDictionary? = null

        /**
         * Gets the singleton instance of UserCustomDictionary.
         */
        fun getInstance(context: Context): UserCustomDictionary {
            return instance ?: synchronized(this) {
                instance ?: UserCustomDictionary(context.applicationContext).also { instance = it }
            }
        }
    }
}
