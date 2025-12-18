package it.neuralrad.coolwulf.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max

/**
 * Advanced next-word predictor using interpolated n-gram language model.
 *
 * Features:
 * - Unigram, bigram, and trigram models with interpolated backoff
 * - Pre-trained base language model from corpus data
 * - User learning that adapts to individual typing patterns
 * - Kneser-Ney inspired continuation probability for better smoothing
 * - Filters common stop words for more meaningful predictions
 *
 * The prediction formula uses interpolation:
 * P(word | w1, w2) = λ3 * P_trigram(word | w1, w2)
 *                 + λ2 * P_bigram(word | w2)
 *                 + λ1 * P_unigram(word)
 *                 + λ0 * P_base(word | context)
 */
class NextWordPredictor(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // User-learned n-gram caches
    // Unigram cache: word -> frequency count
    private val unigramCache = mutableMapOf<String, Int>()
    private var totalWordCount = 0

    // Bigram cache: previousWord -> (nextWord -> frequency count)
    private val bigramCache = mutableMapOf<String, MutableMap<String, Int>>()

    // Trigram cache: (word1 + "|" + word2) -> (nextWord -> frequency count)
    private val trigramCache = mutableMapOf<String, MutableMap<String, Int>>()

    // Continuation counts for Kneser-Ney style smoothing
    // How many different contexts does each word appear in?
    private val continuationCounts = mutableMapOf<String, Int>()
    private var totalContinuationContexts = 0

    // Pre-trained base language model - English (loaded from assets)
    private var baseUnigrams: Map<String, Float> = emptyMap()
    private var baseBigrams: Map<String, Map<String, Float>> = emptyMap()
    private var baseTrigrams: Map<String, Map<String, Float>> = emptyMap()
    private var baseModelLoaded = false

    // Pre-trained base language model - Chinese (loaded from assets)
    private var chineseBaseUnigrams: Map<String, Float> = emptyMap()
    private var chineseBaseBigrams: Map<String, Map<String, Float>> = emptyMap()
    private var chineseBaseTrigrams: Map<String, Map<String, Float>> = emptyMap()
    private var chineseBaseModelLoaded = false

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
        loadBaseModel()
        loadChineseBaseModel()
        loadFromPreferences()
    }

    /**
     * Loads the pre-trained base language model from assets.
     * This provides a foundation of common word sequences.
     */
    private fun loadBaseModel() {
        try {
            val inputStream = context.assets.open("common/english/base_language_model.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)

            // Load unigrams
            if (json.has("unigrams")) {
                val unigramsJson = json.getJSONObject("unigrams")
                val unigramMap = mutableMapOf<String, Float>()
                val keys = unigramsJson.keys()
                while (keys.hasNext()) {
                    val word = keys.next()
                    unigramMap[word] = unigramsJson.getDouble(word).toFloat()
                }
                baseUnigrams = unigramMap
            }

            // Load bigrams
            if (json.has("bigrams")) {
                val bigramsJson = json.getJSONObject("bigrams")
                val bigramMap = mutableMapOf<String, Map<String, Float>>()
                val contextKeys = bigramsJson.keys()
                while (contextKeys.hasNext()) {
                    val context = contextKeys.next()
                    val nextWordsJson = bigramsJson.getJSONObject(context)
                    val nextWordMap = mutableMapOf<String, Float>()
                    val wordKeys = nextWordsJson.keys()
                    while (wordKeys.hasNext()) {
                        val word = wordKeys.next()
                        nextWordMap[word] = nextWordsJson.getDouble(word).toFloat()
                    }
                    bigramMap[context] = nextWordMap
                }
                baseBigrams = bigramMap
            }

            // Load trigrams
            if (json.has("trigrams")) {
                val trigramsJson = json.getJSONObject("trigrams")
                val trigramMap = mutableMapOf<String, Map<String, Float>>()
                val contextKeys = trigramsJson.keys()
                while (contextKeys.hasNext()) {
                    val context = contextKeys.next()
                    val nextWordsJson = trigramsJson.getJSONObject(context)
                    val nextWordMap = mutableMapOf<String, Float>()
                    val wordKeys = nextWordsJson.keys()
                    while (wordKeys.hasNext()) {
                        val word = wordKeys.next()
                        nextWordMap[word] = nextWordsJson.getDouble(word).toFloat()
                    }
                    trigramMap[context] = nextWordMap
                }
                baseTrigrams = trigramMap
            }

            baseModelLoaded = true
            Log.d(TAG, "Loaded English base language model: ${baseUnigrams.size} unigrams, ${baseBigrams.size} bigram contexts, ${baseTrigrams.size} trigram contexts")
        } catch (e: Exception) {
            Log.w(TAG, "English base language model not found or failed to load: ${e.message}")
            baseModelLoaded = false
        }
    }

    /**
     * Loads the pre-trained Chinese base language model from assets.
     * This provides a foundation of common Chinese word/phrase sequences.
     */
    private fun loadChineseBaseModel() {
        try {
            val inputStream = context.assets.open("common/pinyin/base_chinese_language_model.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)

            // Load Chinese unigrams
            if (json.has("unigrams")) {
                val unigramsJson = json.getJSONObject("unigrams")
                val unigramMap = mutableMapOf<String, Float>()
                val keys = unigramsJson.keys()
                while (keys.hasNext()) {
                    val word = keys.next()
                    unigramMap[word] = unigramsJson.getDouble(word).toFloat()
                }
                chineseBaseUnigrams = unigramMap
            }

            // Load Chinese bigrams
            if (json.has("bigrams")) {
                val bigramsJson = json.getJSONObject("bigrams")
                val bigramMap = mutableMapOf<String, Map<String, Float>>()
                val contextKeys = bigramsJson.keys()
                while (contextKeys.hasNext()) {
                    val ctx = contextKeys.next()
                    val nextWordsJson = bigramsJson.getJSONObject(ctx)
                    val nextWordMap = mutableMapOf<String, Float>()
                    val wordKeys = nextWordsJson.keys()
                    while (wordKeys.hasNext()) {
                        val word = wordKeys.next()
                        nextWordMap[word] = nextWordsJson.getDouble(word).toFloat()
                    }
                    bigramMap[ctx] = nextWordMap
                }
                chineseBaseBigrams = bigramMap
            }

            // Load Chinese trigrams
            if (json.has("trigrams")) {
                val trigramsJson = json.getJSONObject("trigrams")
                val trigramMap = mutableMapOf<String, Map<String, Float>>()
                val contextKeys = trigramsJson.keys()
                while (contextKeys.hasNext()) {
                    val ctx = contextKeys.next()
                    val nextWordsJson = trigramsJson.getJSONObject(ctx)
                    val nextWordMap = mutableMapOf<String, Float>()
                    val wordKeys = nextWordsJson.keys()
                    while (wordKeys.hasNext()) {
                        val word = wordKeys.next()
                        nextWordMap[word] = nextWordsJson.getDouble(word).toFloat()
                    }
                    trigramMap[ctx] = nextWordMap
                }
                chineseBaseTrigrams = trigramMap
            }

            chineseBaseModelLoaded = true
            Log.d(TAG, "Loaded Chinese base language model: ${chineseBaseUnigrams.size} unigrams, ${chineseBaseBigrams.size} bigram contexts, ${chineseBaseTrigrams.size} trigram contexts")
        } catch (e: Exception) {
            Log.w(TAG, "Chinese base language model not found or failed to load: ${e.message}")
            chineseBaseModelLoaded = false
        }
    }

    /**
     * Records that a word was committed after the previous word(s).
     * This builds unigram, bigram and trigram models for next-word prediction.
     * @param word The word that was just committed
     */
    fun recordCommittedWord(word: String) {
        val normalizedWord = normalizeWord(word)
        if (normalizedWord.isEmpty()) return

        // Record unigram
        unigramCache[normalizedWord] = (unigramCache[normalizedWord] ?: 0) + 1
        totalWordCount++

        val (prevWord1, prevWord2) = lastCommittedWords

        // Record trigram if we have two previous words
        if (prevWord1 != null && prevWord2 != null) {
            val trigramKey = "$prevWord1|$prevWord2"
            val nextWordMap = trigramCache.getOrPut(trigramKey) { mutableMapOf() }
            val wasNew = !nextWordMap.containsKey(normalizedWord)
            nextWordMap[normalizedWord] = (nextWordMap[normalizedWord] ?: 0) + 1

            // Update continuation count (Kneser-Ney style)
            if (wasNew) {
                continuationCounts[normalizedWord] = (continuationCounts[normalizedWord] ?: 0) + 1
                totalContinuationContexts++
            }

            Log.d(TAG, "Recorded trigram: '$trigramKey' -> '$normalizedWord' (count: ${nextWordMap[normalizedWord]})")
        }

        // Record bigram if we have one previous word
        prevWord2?.let { prevWord ->
            val nextWordMap = bigramCache.getOrPut(prevWord) { mutableMapOf() }
            val wasNew = !nextWordMap.containsKey(normalizedWord)
            nextWordMap[normalizedWord] = (nextWordMap[normalizedWord] ?: 0) + 1

            // Update continuation count for bigram context too
            if (wasNew && prevWord1 == null) {
                continuationCounts[normalizedWord] = (continuationCounts[normalizedWord] ?: 0) + 1
                totalContinuationContexts++
            }

            Log.d(TAG, "Recorded bigram: '$prevWord' -> '$normalizedWord' (count: ${nextWordMap[normalizedWord]})")
        }

        // Update last committed words (shift: word2 -> word1, new word -> word2)
        lastCommittedWords = Pair(prevWord2, normalizedWord)

        // Generate next-word suggestions using interpolated model
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
     * Gets next-word suggestions using interpolated n-gram model.
     * Combines trigram, bigram, unigram, and base model probabilities.
     * Supports continuous prediction by always providing fallback candidates.
     *
     * @param previousWords Pair of last two committed words
     * @param limit Maximum number of suggestions to return
     * @return List of likely next words, sorted by interpolated probability
     */
    private fun getNextWordSuggestions(previousWords: Pair<String?, String?>, limit: Int = MAX_SUGGESTIONS): List<String> {
        val (word1, word2) = previousWords
        val candidates = mutableMapOf<String, Float>()

        // Collect all candidate words from all sources
        collectCandidates(candidates, word1, word2)

        // For continuous prediction: if no context-specific candidates found,
        // fall back to top unigrams from base models
        if (candidates.isEmpty() && word2 != null) {
            collectFallbackCandidates(candidates, word2)
        }

        if (candidates.isEmpty()) {
            return emptyList()
        }

        // Calculate interpolated scores for each candidate
        for (candidate in candidates.keys.toList()) {
            candidates[candidate] = calculateInterpolatedScore(candidate, word1, word2)
        }

        // Filter and sort by score
        return candidates.entries
            .filter { entry ->
                !stopWords.contains(entry.key) &&
                entry.key != word2 &&
                entry.key != word1 &&
                entry.value > 0f
            }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    /**
     * Collects fallback candidates when no context-specific candidates are available.
     * This enables continuous prediction by providing high-frequency words.
     */
    private fun collectFallbackCandidates(candidates: MutableMap<String, Float>, lastWord: String) {
        // Detect if last word is Chinese
        val isChinese = lastWord.any { it in '\u4e00'..'\u9fff' }

        if (isChinese) {
            // For Chinese, use top Chinese unigrams as fallback
            chineseBaseUnigrams.entries
                .sortedByDescending { it.value }
                .take(20)
                .forEach { candidates[it.key] = 0f }
        } else {
            // For English, use top English unigrams as fallback
            baseUnigrams.entries
                .sortedByDescending { it.value }
                .take(20)
                .forEach { candidates[it.key] = 0f }
        }

        // Also include user's top learned words
        unigramCache.entries
            .sortedByDescending { it.value }
            .take(20)
            .forEach { candidates[it.key] = 0f }
    }

    /**
     * Collects all candidate words from user n-grams and base models (English + Chinese).
     */
    private fun collectCandidates(candidates: MutableMap<String, Float>, word1: String?, word2: String?) {
        // From user trigrams
        if (word1 != null && word2 != null) {
            trigramCache["$word1|$word2"]?.keys?.forEach { candidates[it] = 0f }
        }

        // From user bigrams
        word2?.let { bigramCache[it]?.keys?.forEach { candidates[it] = 0f } }

        // From user unigrams (top frequent words)
        unigramCache.entries
            .sortedByDescending { it.value }
            .take(50)
            .forEach { candidates[it.key] = 0f }

        // From English base model trigrams
        if (word1 != null && word2 != null) {
            baseTrigrams["$word1|$word2"]?.keys?.forEach { candidates[it] = 0f }
        }

        // From English base model bigrams
        word2?.let { baseBigrams[it]?.keys?.forEach { candidates[it] = 0f } }

        // From English base model unigrams (top words)
        baseUnigrams.entries
            .sortedByDescending { it.value }
            .take(30)
            .forEach { candidates[it.key] = 0f }

        // From Chinese base model trigrams
        if (word1 != null && word2 != null) {
            chineseBaseTrigrams["$word1|$word2"]?.keys?.forEach { candidates[it] = 0f }
        }

        // From Chinese base model bigrams
        word2?.let { chineseBaseBigrams[it]?.keys?.forEach { candidates[it] = 0f } }

        // From Chinese base model unigrams (top words)
        chineseBaseUnigrams.entries
            .sortedByDescending { it.value }
            .take(50)
            .forEach { candidates[it.key] = 0f }
    }

    /**
     * Calculates the interpolated probability score for a candidate word.
     *
     * Formula: P(word | w1, w2) = λ_user * P_user + λ_base_en * P_english + λ_base_zh * P_chinese
     *
     * Uses adaptive interpolation weights based on available data:
     * - More weight to trigrams when they have sufficient data
     * - Falls back gracefully to lower-order models
     * - Supports both English and Chinese base language models
     */
    private fun calculateInterpolatedScore(candidate: String, word1: String?, word2: String?): Float {
        var score = 0f
        var totalWeight = 0f

        // Detect if candidate is Chinese (contains Chinese characters)
        val isChinese = candidate.any { it in '\u4e00'..'\u9fff' }

        // Trigram contribution (highest weight when available)
        if (word1 != null && word2 != null) {
            val trigramKey = "$word1|$word2"

            // User trigram
            val userTrigramCount = trigramCache[trigramKey]?.get(candidate) ?: 0
            val userTrigramTotal = trigramCache[trigramKey]?.values?.sum() ?: 0
            if (userTrigramTotal > 0) {
                val userTrigramProb = userTrigramCount.toFloat() / userTrigramTotal
                // Boost weight based on evidence (more occurrences = more reliable)
                val confidence = ln(userTrigramTotal.toFloat() + 1) / ln(10f)
                val weight = LAMBDA_USER_TRIGRAM * confidence.coerceIn(0.5f, 2f)
                score += weight * userTrigramProb
                totalWeight += weight
            }

            // English base trigram
            val baseTrigramProb = baseTrigrams[trigramKey]?.get(candidate) ?: 0f
            if (baseTrigramProb > 0f && !isChinese) {
                score += LAMBDA_BASE_TRIGRAM * baseTrigramProb
                totalWeight += LAMBDA_BASE_TRIGRAM
            }

            // Chinese base trigram
            val chineseTrigramProb = chineseBaseTrigrams[trigramKey]?.get(candidate) ?: 0f
            if (chineseTrigramProb > 0f) {
                // Give Chinese trigrams higher weight for Chinese candidates
                val zhWeight = if (isChinese) LAMBDA_CHINESE_TRIGRAM * 1.5f else LAMBDA_CHINESE_TRIGRAM
                score += zhWeight * chineseTrigramProb
                totalWeight += zhWeight
            }
        }

        // Bigram contribution
        if (word2 != null) {
            // User bigram
            val userBigramCount = bigramCache[word2]?.get(candidate) ?: 0
            val userBigramTotal = bigramCache[word2]?.values?.sum() ?: 0
            if (userBigramTotal > 0) {
                val userBigramProb = userBigramCount.toFloat() / userBigramTotal
                val confidence = ln(userBigramTotal.toFloat() + 1) / ln(10f)
                val weight = LAMBDA_USER_BIGRAM * confidence.coerceIn(0.5f, 1.5f)
                score += weight * userBigramProb
                totalWeight += weight
            }

            // English base bigram
            val baseBigramProb = baseBigrams[word2]?.get(candidate) ?: 0f
            if (baseBigramProb > 0f && !isChinese) {
                score += LAMBDA_BASE_BIGRAM * baseBigramProb
                totalWeight += LAMBDA_BASE_BIGRAM
            }

            // Chinese base bigram
            val chineseBigramProb = chineseBaseBigrams[word2]?.get(candidate) ?: 0f
            if (chineseBigramProb > 0f) {
                val zhWeight = if (isChinese) LAMBDA_CHINESE_BIGRAM * 1.5f else LAMBDA_CHINESE_BIGRAM
                score += zhWeight * chineseBigramProb
                totalWeight += zhWeight
            }
        }

        // Unigram contribution (with Kneser-Ney style continuation probability)
        val userUnigramScore = calculateUnigramScore(candidate)
        if (userUnigramScore > 0f) {
            score += LAMBDA_USER_UNIGRAM * userUnigramScore
            totalWeight += LAMBDA_USER_UNIGRAM
        }

        // English base unigram
        val baseUnigramProb = baseUnigrams[candidate] ?: 0f
        if (baseUnigramProb > 0f && !isChinese) {
            score += LAMBDA_BASE_UNIGRAM * baseUnigramProb
            totalWeight += LAMBDA_BASE_UNIGRAM
        }

        // Chinese base unigram
        val chineseUnigramProb = chineseBaseUnigrams[candidate] ?: 0f
        if (chineseUnigramProb > 0f) {
            val zhWeight = if (isChinese) LAMBDA_CHINESE_UNIGRAM * 1.5f else LAMBDA_CHINESE_UNIGRAM
            score += zhWeight * chineseUnigramProb
            totalWeight += zhWeight
        }

        // Normalize by total weight (so scores are comparable)
        return if (totalWeight > 0f) score / totalWeight else 0f
    }

    /**
     * Calculates unigram score using Kneser-Ney style continuation probability.
     *
     * Instead of just counting word frequency, we ask:
     * "In how many different contexts does this word appear?"
     *
     * This prevents common words from dominating all predictions.
     */
    private fun calculateUnigramScore(word: String): Float {
        // Standard unigram probability
        val standardProb = if (totalWordCount > 0) {
            (unigramCache[word] ?: 0).toFloat() / totalWordCount
        } else 0f

        // Continuation probability (Kneser-Ney inspired)
        val continuationProb = if (totalContinuationContexts > 0) {
            (continuationCounts[word] ?: 0).toFloat() / totalContinuationContexts
        } else 0f

        // Blend both (continuation helps with diversity)
        return 0.6f * standardProb + 0.4f * continuationProb
    }

    /**
     * Gets the last committed word.
     */
    fun getLastCommittedWord(): String? = lastCommittedWords.second

    /**
     * Normalizes a word for consistent storage and lookup.
     */
    private fun normalizeWord(word: String): String {
        return word.lowercase().trim().filter { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }
    }

    /**
     * Loads user n-gram data from SharedPreferences.
     */
    private fun loadFromPreferences() {
        try {
            // Load unigrams
            val unigramJsonString = prefs.getString(KEY_UNIGRAM_DATA, null)
            if (unigramJsonString != null) {
                val jsonObject = JSONObject(unigramJsonString)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val word = keys.next()
                    unigramCache[word] = jsonObject.getInt(word)
                }
                totalWordCount = unigramCache.values.sum()
                Log.d(TAG, "Loaded unigram data: ${unigramCache.size} words, total count: $totalWordCount")
            }

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

            // Load continuation counts
            val continuationJsonString = prefs.getString(KEY_CONTINUATION_DATA, null)
            if (continuationJsonString != null) {
                val jsonObject = JSONObject(continuationJsonString)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val word = keys.next()
                    continuationCounts[word] = jsonObject.getInt(word)
                }
                totalContinuationContexts = continuationCounts.values.sum()
                Log.d(TAG, "Loaded continuation data: ${continuationCounts.size} words")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading word prediction data from preferences", e)
        }
    }

    /**
     * Saves user n-gram data to SharedPreferences asynchronously.
     */
    private fun saveToPreferencesAsync() {
        try {
            // Save unigrams
            val unigramJsonObject = JSONObject()
            val limitedUnigrams = if (unigramCache.size > MAX_UNIGRAM_CACHE_SIZE) {
                unigramCache.entries
                    .sortedByDescending { it.value }
                    .take(MAX_UNIGRAM_CACHE_SIZE)
                    .associate { it.key to it.value }
            } else {
                unigramCache
            }
            for ((word, count) in limitedUnigrams) {
                unigramJsonObject.put(word, count)
            }

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

            // Save continuation counts
            val continuationJsonObject = JSONObject()
            val limitedContinuation = if (continuationCounts.size > MAX_UNIGRAM_CACHE_SIZE) {
                continuationCounts.entries
                    .sortedByDescending { it.value }
                    .take(MAX_UNIGRAM_CACHE_SIZE)
                    .associate { it.key to it.value }
            } else {
                continuationCounts
            }
            for ((word, count) in limitedContinuation) {
                continuationJsonObject.put(word, count)
            }

            prefs.edit()
                .putString(KEY_UNIGRAM_DATA, unigramJsonObject.toString())
                .putString(KEY_BIGRAM_DATA, bigramJsonObject.toString())
                .putString(KEY_TRIGRAM_DATA, trigramJsonObject.toString())
                .putString(KEY_CONTINUATION_DATA, continuationJsonObject.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving word prediction data to preferences", e)
        }
    }

    /**
     * Clears all learned data.
     */
    fun clearAll() {
        unigramCache.clear()
        bigramCache.clear()
        trigramCache.clear()
        continuationCounts.clear()
        totalWordCount = 0
        totalContinuationContexts = 0
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
            totalUnigrams = unigramCache.size,
            unigramOccurrences = totalWordCount,
            totalBigrams = totalBigrams,
            bigramOccurrences = totalBigramFrequency,
            totalTrigrams = totalTrigrams,
            trigramOccurrences = totalTrigramFrequency,
            baseModelLoaded = baseModelLoaded,
            baseUnigramCount = baseUnigrams.size,
            baseBigramContexts = baseBigrams.size,
            baseTrigramContexts = baseTrigrams.size,
            chineseModelLoaded = chineseBaseModelLoaded,
            chineseUnigramCount = chineseBaseUnigrams.size,
            chineseBigramContexts = chineseBaseBigrams.size,
            chineseTrigramContexts = chineseBaseTrigrams.size
        )
    }

    data class Stats(
        val totalUnigrams: Int,
        val unigramOccurrences: Int,
        val totalBigrams: Int,
        val bigramOccurrences: Int,
        val totalTrigrams: Int,
        val trigramOccurrences: Int,
        val baseModelLoaded: Boolean,
        val baseUnigramCount: Int,
        val baseBigramContexts: Int,
        val baseTrigramContexts: Int,
        val chineseModelLoaded: Boolean,
        val chineseUnigramCount: Int,
        val chineseBigramContexts: Int,
        val chineseTrigramContexts: Int
    )

    companion object {
        private const val TAG = "NextWordPredictor"
        private const val PREFS_NAME = "next_word_predictor"
        private const val KEY_UNIGRAM_DATA = "unigram_data"
        private const val KEY_BIGRAM_DATA = "bigram_data"
        private const val KEY_TRIGRAM_DATA = "trigram_data"
        private const val KEY_CONTINUATION_DATA = "continuation_data"
        private const val MAX_SUGGESTIONS = 5
        private const val MAX_UNIGRAM_CACHE_SIZE = 2000  // Maximum unique words to store
        private const val MAX_CACHE_SIZE = 1000  // Maximum unique previous words to store
        private const val MAX_TRIGRAM_CACHE_SIZE = 500  // Maximum unique trigrams to store
        private const val MAX_NEXT_WORDS_PER_ENTRY = 20  // Maximum next words per previous word

        // Interpolation weights for user-learned model
        private const val LAMBDA_USER_TRIGRAM = 0.35f   // User trigrams (highest specificity)
        private const val LAMBDA_USER_BIGRAM = 0.25f    // User bigrams
        private const val LAMBDA_USER_UNIGRAM = 0.10f   // User unigrams

        // Interpolation weights for English base model
        private const val LAMBDA_BASE_TRIGRAM = 0.15f   // English base trigrams
        private const val LAMBDA_BASE_BIGRAM = 0.10f    // English base bigrams
        private const val LAMBDA_BASE_UNIGRAM = 0.05f   // English base unigrams

        // Interpolation weights for Chinese base model
        private const val LAMBDA_CHINESE_TRIGRAM = 0.20f   // Chinese base trigrams (higher weight for Chinese)
        private const val LAMBDA_CHINESE_BIGRAM = 0.12f    // Chinese base bigrams
        private const val LAMBDA_CHINESE_UNIGRAM = 0.08f   // Chinese base unigrams

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
