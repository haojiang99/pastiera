package it.neuralrad.coolwulf.inputmethod

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * HMM-based Pinyin to Chinese character conversion.
 * Uses a Hidden Markov Model with Viterbi decoding for toneless pinyin input.
 *
 * Model specifications:
 * - 6,939 Chinese characters
 * - 403 pinyin syllables
 * - 91.1% character accuracy
 * - ~1ms inference time
 *
 * The model is loaded from assets/common/pinyin/hmm_model.dat (gzip-compressed)
 */
class HmmPinyinRecognizer private constructor(private val context: Context) {
    companion object {
        private const val MODEL_FILE = "common/pinyin/hmm_model.dat"

        @Volatile
        private var instance: HmmPinyinRecognizer? = null

        fun getInstance(context: Context): HmmPinyinRecognizer {
            return instance ?: synchronized(this) {
                instance ?: HmmPinyinRecognizer(context.applicationContext).also { instance = it }
            }
        }
    }

    // Model state
    private var isModelLoading = false
    private var isModelReady = false

    // Vocabulary mappings
    private var hanzi2id: Map<String, Int> = emptyMap()
    private var id2hanzi: Map<Int, String> = emptyMap()
    private var pinyin2id: Map<String, Int> = emptyMap()

    // Probability arrays
    private var logInitial: DoubleArray = doubleArrayOf()
    private var defaultTrans: Double = -35.0  // Default (background) transition value

    // Sparse transition matrix: from_id -> (to_id -> log_prob)
    private var sparseTrans: Map<Int, Map<Int, Double>> = emptyMap()

    // Sparse emission: pinyin_id -> (hanzi_id -> log_prob)
    private var sparseEmission: Map<Int, Map<Int, Double>> = emptyMap()

    // Pinyin to candidate hanzi IDs
    private var pinyinToHanzi: Map<Int, List<Int>> = emptyMap()

    /**
     * Checks if the model is ready for inference.
     */
    fun isReady(): Boolean = isModelReady

    /**
     * Checks if the model is currently loading.
     */
    fun isLoading(): Boolean = isModelLoading

    /**
     * Initializes the HMM model from assets.
     * @param onReady Callback when initialization completes (success, error message)
     */
    fun initModel(onReady: ((Boolean, String?) -> Unit)? = null) {
        if (isModelReady) {
            onReady?.invoke(true, null)
            return
        }

        if (isModelLoading) {
            onReady?.invoke(false, "Model is already loading")
            return
        }

        isModelLoading = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                loadModelFromAssets()
                isModelReady = true

                withContext(Dispatchers.Main) {
                    onReady?.invoke(true, null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onReady?.invoke(false, e.message ?: "Unknown error")
                }
            } finally {
                isModelLoading = false
            }
        }
    }

    /**
     * Loads the HMM model from compressed JSON in assets.
     */
    private fun loadModelFromAssets() {
        val inputStream = context.assets.open(MODEL_FILE)
        val gzipStream = GZIPInputStream(inputStream)
        val reader = BufferedReader(InputStreamReader(gzipStream, Charsets.UTF_8))
        val jsonStr = reader.readText()
        reader.close()

        val json = JSONObject(jsonStr)

        // Load vocabularies
        val hanzi2idJson = json.getJSONObject("hanzi2id")
        val hanzi2idMap = mutableMapOf<String, Int>()
        hanzi2idJson.keys().forEach { key ->
            hanzi2idMap[key] = hanzi2idJson.getInt(key)
        }
        hanzi2id = hanzi2idMap

        val id2hanziJson = json.getJSONObject("id2hanzi")
        val id2hanziMap = mutableMapOf<Int, String>()
        id2hanziJson.keys().forEach { key ->
            id2hanziMap[key.toInt()] = id2hanziJson.getString(key)
        }
        id2hanzi = id2hanziMap

        val pinyin2idJson = json.getJSONObject("pinyin2id")
        val pinyin2idMap = mutableMapOf<String, Int>()
        pinyin2idJson.keys().forEach { key ->
            pinyin2idMap[key] = pinyin2idJson.getInt(key)
        }
        pinyin2id = pinyin2idMap

        // Load initial probabilities
        val logInitialJson = json.getJSONArray("log_initial")
        logInitial = DoubleArray(logInitialJson.length()) { i ->
            logInitialJson.getDouble(i)
        }

        // Load default transition value
        defaultTrans = json.getDouble("default_trans")

        // Load sparse transitions
        val sparseTransJson = json.getJSONObject("sparse_trans")
        val sparseTransMap = mutableMapOf<Int, Map<Int, Double>>()
        sparseTransJson.keys().forEach { fromId ->
            val toMap = mutableMapOf<Int, Double>()
            val toJson = sparseTransJson.getJSONObject(fromId)
            toJson.keys().forEach { toId ->
                toMap[toId.toInt()] = toJson.getDouble(toId)
            }
            sparseTransMap[fromId.toInt()] = toMap
        }
        sparseTrans = sparseTransMap

        // Load pinyin to hanzi mapping
        val pinyinToHanziJson = json.getJSONObject("pinyin_to_hanzi")
        val pinyinToHanziMap = mutableMapOf<Int, List<Int>>()
        pinyinToHanziJson.keys().forEach { pinyinId ->
            val hanziList = mutableListOf<Int>()
            val hanziArray = pinyinToHanziJson.getJSONArray(pinyinId)
            for (i in 0 until hanziArray.length()) {
                hanziList.add(hanziArray.getInt(i))
            }
            pinyinToHanziMap[pinyinId.toInt()] = hanziList
        }
        pinyinToHanzi = pinyinToHanziMap

        // Load sparse emission
        val sparseEmissionJson = json.getJSONObject("sparse_emission")
        val sparseEmissionMap = mutableMapOf<Int, Map<Int, Double>>()
        sparseEmissionJson.keys().forEach { pinyinId ->
            val emissionMap = mutableMapOf<Int, Double>()
            val emissionJson = sparseEmissionJson.getJSONObject(pinyinId)
            emissionJson.keys().forEach { hanziId ->
                emissionMap[hanziId.toInt()] = emissionJson.getDouble(hanziId)
            }
            sparseEmissionMap[pinyinId.toInt()] = emissionMap
        }
        sparseEmission = sparseEmissionMap
    }

    /**
     * Converts space-separated pinyin to Chinese characters using Viterbi decoding.
     * @param pinyin The pinyin input (space-separated syllables, e.g., "ni hao")
     * @return The predicted Chinese string, or empty string if inference fails
     */
    fun convert(pinyin: String): String {
        if (!isModelReady) {
            return ""
        }

        val syllables = pinyin.trim().lowercase().split(" ").filter { it.isNotEmpty() }
        if (syllables.isEmpty()) {
            return ""
        }

        return viterbiDecode(syllables)
    }

    /**
     * Viterbi decoding algorithm for HMM.
     * Finds the most likely sequence of characters given the pinyin syllables.
     */
    private fun viterbiDecode(syllables: List<String>): String {
        val T = syllables.size
        if (T == 0) return ""

        // Get candidate hanzi IDs for each position
        val candidates = syllables.map { syllable ->
            val pinyinId = pinyin2id[syllable]
            if (pinyinId != null && pinyinToHanzi.containsKey(pinyinId)) {
                pinyinToHanzi[pinyinId]!!
            } else {
                // Unknown syllable - return empty list
                emptyList()
            }
        }

        // If any position has no candidates, return placeholder
        if (candidates.any { it.isEmpty() }) {
            return syllables.joinToString("") { syllable ->
                val pinyinId = pinyin2id[syllable]
                if (pinyinId != null && pinyinToHanzi.containsKey(pinyinId)) {
                    val hanziIds = pinyinToHanzi[pinyinId]!!
                    if (hanziIds.isNotEmpty()) {
                        id2hanzi[hanziIds[0]] ?: "?"
                    } else "?"
                } else "?"
            }
        }

        // Viterbi with pruning (only consider valid candidates)
        // V[t] = map of state -> (probability, prev_state)
        val V = mutableListOf<MutableMap<Int, Pair<Double, Int?>>>()

        // Initialize first position
        val firstPinyinId = pinyin2id[syllables[0]]!!
        val firstEmissions = sparseEmission[firstPinyinId] ?: emptyMap()
        val v0 = mutableMapOf<Int, Pair<Double, Int?>>()
        for (state in candidates[0]) {
            val initProb = logInitial.getOrElse(state) { -50.0 }
            val emitProb = firstEmissions[state] ?: -50.0
            v0[state] = (initProb + emitProb) to null
        }
        V.add(v0)

        // Forward pass
        for (t in 1 until T) {
            val vt = mutableMapOf<Int, Pair<Double, Int?>>()
            val pinyinId = pinyin2id[syllables[t]]!!
            val emissions = sparseEmission[pinyinId] ?: emptyMap()

            for (currState in candidates[t]) {
                var maxProb = Double.NEGATIVE_INFINITY
                var bestPrev: Int? = null

                for ((prevState, prevData) in V[t - 1]) {
                    val prevProb = prevData.first

                    // Get transition probability (sparse lookup with default)
                    val transMap = sparseTrans[prevState]
                    val transProb = transMap?.get(currState) ?: defaultTrans

                    // Get emission probability
                    val emitProb = emissions[currState] ?: -50.0

                    val prob = prevProb + transProb + emitProb
                    if (prob > maxProb) {
                        maxProb = prob
                        bestPrev = prevState
                    }
                }

                if (bestPrev != null) {
                    vt[currState] = maxProb to bestPrev
                }
            }

            if (vt.isEmpty()) {
                // Fallback: use emission only
                for (currState in candidates[t]) {
                    val emitProb = emissions[currState] ?: -50.0
                    vt[currState] = emitProb to null
                }
            }

            V.add(vt)
        }

        // Backtrack
        if (V.last().isEmpty()) {
            return "?" .repeat(T)
        }

        // Find best final state
        var bestFinal = -1
        var bestProb = Double.NEGATIVE_INFINITY
        for ((state, data) in V.last()) {
            if (data.first > bestProb) {
                bestProb = data.first
                bestFinal = state
            }
        }

        if (bestFinal == -1) {
            return "?".repeat(T)
        }

        // Backtrack path
        val path = mutableListOf<Int>()
        var currentState = bestFinal
        for (t in T - 1 downTo 0) {
            path.add(currentState)
            val prevState = V[t][currentState]?.second
            if (prevState != null) {
                currentState = prevState
            }
        }
        path.reverse()

        // Convert to characters
        return path.mapNotNull { id2hanzi[it] }.joinToString("")
    }

    /**
     * Releases the model and frees resources.
     */
    fun releaseModel() {
        hanzi2id = emptyMap()
        id2hanzi = emptyMap()
        pinyin2id = emptyMap()
        logInitial = doubleArrayOf()
        sparseTrans = emptyMap()
        sparseEmission = emptyMap()
        pinyinToHanzi = emptyMap()
        isModelReady = false
    }
}
