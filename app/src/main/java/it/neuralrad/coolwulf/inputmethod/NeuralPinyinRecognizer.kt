package it.neuralrad.coolwulf.inputmethod

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import java.util.EnumSet
import android.net.Uri
import android.util.Log
import it.neuralrad.coolwulf.SettingsManager
import kotlinx.coroutines.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Neural network-based Pinyin to Chinese character conversion using ONNX Runtime.
 * Uses a BiLSTM+Attention encoder-decoder model for improved accuracy.
 * The model is loaded from a user-selected zip file containing:
 * - encoder_int8.onnx (encoder model)
 * - decoder_int8.onnx (decoder model)
 * - vocab_pinyin.json (pinyin vocabulary with token2idx/idx2token)
 * - vocab_hanzi.json (Chinese character vocabulary with token2idx/idx2token)
 * - inference_config.json (model configuration)
 *
 * This is the primary method for pinyin-to-hanzi conversion when the model is loaded.
 */
class NeuralPinyinRecognizer(private val context: Context) {
    companion object {
        private const val TAG = "NeuralPinyinRecognizer"
        private const val EXTRACTED_MODEL_DIR = "neural-pinyin-model"

        // Encoder-decoder model files
        private const val ENCODER_FILE = "encoder_int8.onnx"
        private const val DECODER_FILE = "decoder_int8.onnx"
        private const val VOCAB_PINYIN_FILE = "vocab_pinyin.json"
        private const val VOCAB_HANZI_FILE = "vocab_hanzi.json"
        private const val CONFIG_FILE = "inference_config.json"

        @Volatile
        private var instance: NeuralPinyinRecognizer? = null

        @Volatile
        private var onnxRuntimeAvailable: Boolean? = null

        fun getInstance(context: Context): NeuralPinyinRecognizer {
            return instance ?: synchronized(this) {
                instance ?: NeuralPinyinRecognizer(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Check if ONNX Runtime library is available on this device.
         */
        fun isOnnxRuntimeAvailable(): Boolean {
            if (onnxRuntimeAvailable != null) return onnxRuntimeAvailable!!

            return try {
                // Try to load ONNX Runtime native library
                System.loadLibrary("onnxruntime")
                onnxRuntimeAvailable = true
                Log.d(TAG, "ONNX Runtime library loaded successfully")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "ONNX Runtime library not available: ${e.message}")
                onnxRuntimeAvailable = false
                false
            } catch (e: Exception) {
                Log.w(TAG, "Error checking ONNX Runtime library: ${e.message}")
                onnxRuntimeAvailable = false
                false
            }
        }
    }

    // Model state
    private var isModelLoading = false
    private var isModelReady = false
    private var isExtracting = false

    // Vocabularies (token -> index)
    private var pinyinToken2Idx: Map<String, Int> = emptyMap()
    private var hanziToken2Idx: Map<String, Int> = emptyMap()
    private var hanziIdx2Token: Map<Int, String> = emptyMap()

    // Special token IDs
    private var padId: Int = 0
    private var sosId: Int = 1
    private var eosId: Int = 2
    private var unkId: Int = 3

    // Model configuration
    private var maxOutputLen: Int = 64
    private var hiddenDim: Int = 512
    private var numLayers: Int = 3

    // ONNX Runtime sessions
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var ortEnv: OrtEnvironment? = null

    // Pre-allocated buffer for decoder input to reduce tensor allocation overhead
    // Reused across decoder steps in autoregressive decoding
    private val decoderInputBuffer = LongArray(1)

    /**
     * Gets the extracted model directory in app's internal storage.
     */
    private val extractedModelDir: File
        get() = File(context.filesDir, EXTRACTED_MODEL_DIR)

    /**
     * Gets the user-configured model zip file URI.
     */
    fun getModelZipUri(): String? {
        return SettingsManager.getNeuralPinyinModelPath(context)
    }

    /**
     * Gets the model zip file name for display.
     */
    fun getModelZipName(): String? {
        val uriStr = getModelZipUri() ?: return null
        return try {
            val uri = Uri.parse(uriStr)
            uri.lastPathSegment?.substringAfterLast('/') ?: uriStr.substringAfterLast('/')
        } catch (e: Exception) {
            uriStr.substringAfterLast('/')
        }
    }

    /**
     * Checks if the model has been extracted and is available.
     */
    fun isModelExtracted(): Boolean {
        if (!extractedModelDir.exists()) return false
        val hasEncoder = File(extractedModelDir, ENCODER_FILE).exists()
        val hasDecoder = File(extractedModelDir, DECODER_FILE).exists()
        val hasPinyinVocab = File(extractedModelDir, VOCAB_PINYIN_FILE).exists()
        val hasHanziVocab = File(extractedModelDir, VOCAB_HANZI_FILE).exists()
        val hasConfig = File(extractedModelDir, CONFIG_FILE).exists()
        return hasEncoder && hasDecoder && hasPinyinVocab && hasHanziVocab && hasConfig
    }

    /**
     * Checks if a zip file is configured.
     */
    fun isZipConfigured(): Boolean {
        return getModelZipUri() != null
    }

    /**
     * Gets the model status.
     */
    fun getModelStatus(): ModelStatus {
        val status = when {
            isModelReady -> ModelStatus.READY
            isModelLoading -> ModelStatus.LOADING
            isExtracting -> ModelStatus.EXTRACTING
            isModelExtracted() -> ModelStatus.AVAILABLE
            isZipConfigured() -> ModelStatus.ZIP_CONFIGURED
            else -> ModelStatus.NOT_CONFIGURED
        }
        Log.d(TAG, "getModelStatus: $status (isReady=$isModelReady, isLoading=$isModelLoading, isExtracting=$isExtracting, extracted=${isModelExtracted()}, configured=${isZipConfigured()})")
        return status
    }

    /**
     * Checks if the model is ready for inference.
     */
    fun isReady(): Boolean = isModelReady

    /**
     * Extracts the model archive (supports .zip and .tar.bz2) to internal storage.
     * @param onProgress Callback with (progress percentage, status message)
     * @param onComplete Callback when extraction completes (success, error message)
     */
    fun extractModel(
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        Log.e(TAG, "extractModel: Starting extraction")
        val uriStr = getModelZipUri()
        if (uriStr == null) {
            Log.e(TAG, "extractModel: No model zip file configured")
            onComplete?.invoke(false, "No model zip file configured")
            return
        }

        if (isExtracting) {
            Log.e(TAG, "extractModel: Extraction already in progress")
            onComplete?.invoke(false, "Extraction already in progress")
            return
        }

        isExtracting = true
        Log.e(TAG, "extractModel: isExtracting set to true")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uri = Uri.parse(uriStr)
                val fileName = getModelZipName() ?: "model"
                Log.e(TAG, "extractModel: Processing file: $fileName from URI: $uri")

                // Delete existing extracted model
                deleteExtractedModel()
                Log.e(TAG, "extractModel: Deleted existing model")

                // Create extraction directory
                extractedModelDir.mkdirs()
                Log.e(TAG, "extractModel: Created extraction directory: ${extractedModelDir.absolutePath}")

                withContext(Dispatchers.Main) {
                    onProgress?.invoke(0, "Starting extraction...")
                }

                when {
                    fileName.endsWith(".tar.bz2") || fileName.endsWith(".tbz2") -> {
                        Log.e(TAG, "extractModel: Extracting as tar.bz2")
                        extractTarBz2(uri, onProgress)
                    }
                    else -> {
                        Log.e(TAG, "extractModel: Extracting as zip")
                        extractZip(uri, onProgress)
                    }
                }

                Log.e(TAG, "extractModel: Extraction finished, verifying...")
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(100, "Extraction complete")
                }

                // Verify extraction
                val extracted = isModelExtracted()
                Log.e(TAG, "extractModel: isModelExtracted = $extracted")

                isExtracting = false
                Log.e(TAG, "extractModel: isExtracting set to false")

                if (extracted) {
                    Log.e(TAG, "extractModel: Success!")
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(true, null)
                    }
                } else {
                    Log.e(TAG, "extractModel: Model files not found after extraction")
                    val files = extractedModelDir.listFiles()
                    Log.e(TAG, "extractModel: Files in dir: ${files?.map { "${it.name} (${it.length()} bytes)" }}")
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(false, "Model files not found after extraction")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "extractModel: Error - ${e.message}", e)
                isExtracting = false
                Log.e(TAG, "extractModel: isExtracting set to false (error case)")
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Extracts a .zip archive.
     */
    private fun extractZip(uri: Uri, onProgress: ((Int, String) -> Unit)?) {
        Log.e(TAG, "extractZip: Starting extraction from $uri")
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open zip file")

        ZipInputStream(inputStream).use { zipIn ->
            var entry = zipIn.nextEntry
            var fileCount = 0

            while (entry != null) {
                Log.e(TAG, "extractZip: Found entry: ${entry.name}, isDirectory=${entry.isDirectory}")

                // Get the entry name - if it contains a /, strip the first directory level
                val entryName = if (entry.name.contains('/')) {
                    entry.name.substringAfter('/')
                } else {
                    entry.name
                }

                Log.e(TAG, "extractZip: Processed entry name: $entryName")

                if (entryName.isNotEmpty() && !entry.isDirectory) {
                    val outFile = File(extractedModelDir, entryName)
                    outFile.parentFile?.mkdirs()

                    Log.e(TAG, "extractZip: Extracting to ${outFile.absolutePath}")
                    FileOutputStream(outFile).use { fos ->
                        val bytesWritten = zipIn.copyTo(fos)
                        Log.e(TAG, "extractZip: Wrote $bytesWritten bytes to $entryName")
                    }

                    fileCount++
                    CoroutineScope(Dispatchers.Main).launch {
                        onProgress?.invoke(
                            minOf(fileCount * 10, 90),
                            "Extracting: $entryName"
                        )
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
            Log.e(TAG, "extractZip: Extraction complete, extracted $fileCount files")
        }

        Log.e(TAG, "extractZip: Files in extracted dir: ${extractedModelDir.listFiles()?.map { it.name }}")
    }

    /**
     * Extracts a .tar.bz2 archive.
     */
    private fun extractTarBz2(uri: Uri, onProgress: ((Int, String) -> Unit)?) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open tar.bz2 file")

        val bufferedIn = BufferedInputStream(inputStream)
        val bzIn = BZip2CompressorInputStream(bufferedIn)
        val tarIn = TarArchiveInputStream(bzIn)

        var entry = tarIn.nextEntry
        var fileCount = 0

        while (entry != null) {
            val entryName = entry.name.substringAfter('/', entry.name)

            if (entryName.isNotEmpty() && !entry.isDirectory && tarIn.canReadEntryData(entry)) {
                val outFile = File(extractedModelDir, entryName)
                outFile.parentFile?.mkdirs()

                FileOutputStream(outFile).use { fos ->
                    tarIn.copyTo(fos)
                }

                fileCount++
                CoroutineScope(Dispatchers.Main).launch {
                    onProgress?.invoke(
                        minOf(fileCount * 10, 90),
                        "Extracting: $entryName"
                    )
                }
            }
            entry = tarIn.nextEntry
        }

        tarIn.close()
    }

    /**
     * Deletes the extracted model files.
     */
    fun deleteExtractedModel() {
        releaseModel()
        if (extractedModelDir.exists()) {
            extractedModelDir.deleteRecursively()
        }
    }

    /**
     * Clears the model configuration (zip path).
     */
    fun clearModelConfig() {
        SettingsManager.setNeuralPinyinModelPath(context, null)
        deleteExtractedModel()
    }

    /**
     * Initializes the ONNX encoder-decoder models for inference.
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

        if (!isModelExtracted()) {
            onReady?.invoke(false, "Model not extracted")
            return
        }

        if (!isOnnxRuntimeAvailable()) {
            onReady?.invoke(false, "ONNX Runtime not available")
            return
        }

        isModelLoading = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Load configuration
                loadConfig()

                // Load vocabularies (JSON format)
                loadVocabularies()

                // Initialize ONNX Runtime sessions for encoder and decoder
                initOrtSessions()

                isModelReady = true
                Log.i(TAG, "Neural pinyin encoder-decoder model initialized successfully")

                withContext(Dispatchers.Main) {
                    onReady?.invoke(true, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing model: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onReady?.invoke(false, e.message ?: "Unknown error")
                }
            } finally {
                isModelLoading = false
            }
        }
    }

    /**
     * Loads the model configuration from inference_config.json.
     */
    private fun loadConfig() {
        val configFile = File(extractedModelDir, CONFIG_FILE)
        val jsonStr = configFile.readText()
        val json = JSONObject(jsonStr)

        maxOutputLen = json.optInt("max_output_len", 64)
        hiddenDim = json.optInt("hidden_dim", 512)
        numLayers = json.optInt("num_layers", 3)

        // Load special token IDs
        val specialTokens = json.optJSONObject("special_tokens")
        if (specialTokens != null) {
            padId = specialTokens.optInt("PAD", 0)
            sosId = specialTokens.optInt("SOS", 1)
            eosId = specialTokens.optInt("EOS", 2)
            unkId = specialTokens.optInt("UNK", 3)
        }

        Log.d(TAG, "Loaded config: maxOutputLen=$maxOutputLen, hiddenDim=$hiddenDim, numLayers=$numLayers")
        Log.d(TAG, "Special tokens: PAD=$padId, SOS=$sosId, EOS=$eosId, UNK=$unkId")
    }

    /**
     * Loads the pinyin and hanzi vocabularies from JSON files.
     */
    private fun loadVocabularies() {
        // Load pinyin vocabulary (token -> index)
        val pinyinVocabFile = File(extractedModelDir, VOCAB_PINYIN_FILE)
        val pinyinJsonStr = pinyinVocabFile.readText()
        val pinyinJson = JSONObject(pinyinJsonStr)

        val pinyinToken2IdxJson = pinyinJson.getJSONObject("token2idx")
        val pinyinMap = mutableMapOf<String, Int>()
        pinyinToken2IdxJson.keys().forEach { key ->
            pinyinMap[key] = pinyinToken2IdxJson.getInt(key)
        }
        pinyinToken2Idx = pinyinMap

        // Load hanzi vocabulary (token -> index and index -> token)
        val hanziVocabFile = File(extractedModelDir, VOCAB_HANZI_FILE)
        val hanziJsonStr = hanziVocabFile.readText()
        val hanziJson = JSONObject(hanziJsonStr)

        val hanziToken2IdxJson = hanziJson.getJSONObject("token2idx")
        val hanziTokenMap = mutableMapOf<String, Int>()
        hanziToken2IdxJson.keys().forEach { key ->
            hanziTokenMap[key] = hanziToken2IdxJson.getInt(key)
        }
        hanziToken2Idx = hanziTokenMap

        val hanziIdx2TokenJson = hanziJson.getJSONObject("idx2token")
        val hanziIdxMap = mutableMapOf<Int, String>()
        hanziIdx2TokenJson.keys().forEach { key ->
            hanziIdxMap[key.toInt()] = hanziIdx2TokenJson.getString(key)
        }
        hanziIdx2Token = hanziIdxMap

        Log.d(TAG, "Loaded vocabularies: ${pinyinToken2Idx.size} pinyin tokens, ${hanziToken2Idx.size} hanzi tokens")
    }

    /**
     * Initializes the ONNX Runtime sessions for encoder and decoder.
     * Applies performance optimizations:
     * - Dynamic thread count based on device CPU cores
     * - Graph optimization level ALL_OPT for operator fusion
     * - Memory pattern optimization for predictable shapes
     * - NNAPI execution provider for hardware acceleration (with fallback)
     */
    private fun initOrtSessions() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions()

            // 1. Optimize thread count based on device CPU cores
            // Use half the cores to leave headroom for UI thread, minimum 2
            val numCores = Runtime.getRuntime().availableProcessors()
            val optimalThreads = maxOf(2, numCores / 2)
            sessionOptions.setIntraOpNumThreads(optimalThreads)
            Log.d(TAG, "Using $optimalThreads intra-op threads (device has $numCores cores)")

            // 2. Enable all graph optimizations (operator fusion, constant folding, etc.)
            sessionOptions.setOptimizationLevel(OptLevel.ALL_OPT)
            Log.d(TAG, "Graph optimization level set to ALL_OPT")

            // 3. Enable memory pattern optimization for reduced allocation overhead
            // Effective when tensor shapes are predictable (as in our encoder-decoder model)
            sessionOptions.setMemoryPatternOptimization(true)
            Log.d(TAG, "Memory pattern optimization enabled")

            // 4. Try NNAPI execution provider for hardware acceleration (GPU/NPU)
            // Falls back gracefully to CPU if NNAPI is unavailable or unsupported
            var nnapiEnabled = false
            try {
                // USE_FP16: Enable FP16 relaxation for speed (slight precision trade-off)
                // CPU_DISABLED: Disable NNAPI's CPU fallback, use ORT's optimized CPU kernels instead
                sessionOptions.addNnapi(EnumSet.of(
                    NNAPIFlags.USE_FP16,
                    NNAPIFlags.CPU_DISABLED
                ))
                nnapiEnabled = true
                Log.d(TAG, "NNAPI execution provider enabled with FP16 relaxation")
            } catch (e: Exception) {
                // NNAPI not available (older Android, missing library, etc.) - continue with CPU
                Log.d(TAG, "NNAPI not available, using CPU execution: ${e.message}")
            }

            // Load encoder
            val encoderPath = File(extractedModelDir, ENCODER_FILE).absolutePath
            encoderSession = ortEnv!!.createSession(encoderPath, sessionOptions)
            Log.d(TAG, "Encoder session created (NNAPI: $nnapiEnabled)")

            // Load decoder
            val decoderPath = File(extractedModelDir, DECODER_FILE).absolutePath
            decoderSession = ortEnv!!.createSession(decoderPath, sessionOptions)
            Log.d(TAG, "Decoder session created (NNAPI: $nnapiEnabled)")

        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize ONNX Runtime sessions: ${e.message}", e)
        }
    }

    /**
     * Converts pinyin string to Chinese characters using the neural encoder-decoder model.
     * @param pinyin The pinyin input (continuous letters without spaces, e.g., "nihao")
     * @param numResults Number of candidate results to return (1 or 3, default 1)
     * @return List of candidate Chinese strings, sorted by probability
     */
    fun convert(pinyin: String, numResults: Int = 1): List<String> {
        if (!isModelReady || encoderSession == null || decoderSession == null) {
            Log.w(TAG, "Model not ready for inference")
            return emptyList()
        }

        try {
            // Tokenize pinyin input (character-level)
            val pinyinTokens = tokenizePinyin(pinyin)
            if (pinyinTokens.isEmpty()) {
                return emptyList()
            }

            // If only 1 result needed, use greedy decoding
            if (numResults <= 1) {
                val outputIds = runEncoderDecoderInference(pinyinTokens)
                val result = decodeOutput(outputIds)
                return if (result.isNotEmpty()) listOf(result) else emptyList()
            }

            // Use beam search for multiple results
            val beamResults = runBeamSearchInference(pinyinTokens, numResults)
            return beamResults.mapNotNull { ids ->
                val result = decodeOutput(ids)
                if (result.isNotEmpty()) result else null
            }.distinct().take(numResults)
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Tokenizes pinyin string into vocabulary IDs.
     * Uses character-level tokenization (e.g., "nihao" -> [n, i, h, a, o]).
     * Note: SOS/EOS tokens are not added to encoder input - they are only used by the decoder.
     */
    private fun tokenizePinyin(pinyin: String): LongArray {
        val cleanedPinyin = pinyin.lowercase().trim()
        val tokens = mutableListOf<Long>()

        // Character-by-character tokenization (no SOS/EOS for encoder input)
        for (char in cleanedPinyin) {
            val charStr = char.toString()
            val id = pinyinToken2Idx[charStr]
            if (id != null) {
                tokens.add(id.toLong())
            } else {
                // Use UNK token for unknown characters
                tokens.add(unkId.toLong())
            }
        }

        return tokens.toLongArray()
    }

    /**
     * Runs encoder-decoder inference with autoregressive decoding.
     * The model uses LSTM hidden/cell states that need to be passed between steps.
     */
    private fun runEncoderDecoderInference(inputTokens: LongArray): List<Int> {
        val encoder = encoderSession ?: throw RuntimeException("Encoder session not initialized")
        val decoder = decoderSession ?: throw RuntimeException("Decoder session not initialized")
        val env = ortEnv ?: throw RuntimeException("Environment not initialized")

        try {
            // Prepare encoder input [1, seq_len]
            val encoderInputData = arrayOf(inputTokens)
            val encoderInputTensor = OnnxTensor.createTensor(env, encoderInputData)

            // Run encoder to get hidden states
            // Encoder inputs: pinyin_ids [batch, seq_len]
            // Encoder outputs: encoder_outputs [batch, seq_len, hidden*2], hidden [num_layers, batch, hidden*2], cell [num_layers, batch, hidden*2]
            val encoderInputMap = mapOf("pinyin_ids" to encoderInputTensor)
            val encoderResult = encoder.run(encoderInputMap)

            // Get encoder outputs (3 tensors: encoder_outputs, hidden, cell)
            val encoderOutputsTensor = encoderResult.get("encoder_outputs").get() as OnnxTensor
            var hiddenTensor = encoderResult.get("hidden").get() as OnnxTensor
            var cellTensor = encoderResult.get("cell").get() as OnnxTensor

            // Calculate reasonable max output length based on input pinyin length
            // Each Chinese character corresponds to ~2-6 pinyin letters (average ~3)
            // So max output should be roughly inputLength/2 + some buffer
            val inputLength = inputTokens.size
            val maxAllowedLen = minOf(maxOutputLen, inputLength / 2 + 5)

            // Autoregressive decoding
            val outputIds = mutableListOf<Int>()
            var currentToken = sosId
            var consecutiveRepeatCount = 0
            var lastToken = -1

            for (step in 0 until maxAllowedLen) {
                // Prepare decoder input: input_token [1] containing current token
                // Use pre-allocated buffer to reduce allocation overhead
                decoderInputBuffer[0] = currentToken.toLong()
                val decoderInputTensor = OnnxTensor.createTensor(env, decoderInputBuffer)

                // Run decoder with encoder outputs and LSTM states
                // Decoder inputs: input_token [1], encoder_outputs, hidden, cell
                // Decoder outputs: output [1, vocab], new_hidden, new_cell
                val decoderInputMap = mapOf(
                    "input_token" to decoderInputTensor,
                    "encoder_outputs" to encoderOutputsTensor,
                    "hidden" to hiddenTensor,
                    "cell" to cellTensor
                )
                val decoderResult = decoder.run(decoderInputMap)

                // Get logits [1, vocab_size] and find the most likely next token
                val logitsTensor = decoderResult.get("output").get() as OnnxTensor
                val logitsValue = logitsTensor.value

                val nextToken = when (logitsValue) {
                    is Array<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        when {
                            // 2D: [1, vocab] - take first (only) batch
                            logitsValue.isArrayOf<FloatArray>() -> {
                                val logits2d = logitsValue as Array<FloatArray>
                                val logits = logits2d[0]
                                logits.indices.maxByOrNull { logits[it] } ?: eosId
                            }
                            else -> eosId
                        }
                    }
                    else -> eosId
                }

                // Get new hidden/cell states for next step
                val newHiddenTensor = decoderResult.get("new_hidden").get() as OnnxTensor
                val newCellTensor = decoderResult.get("new_cell").get() as OnnxTensor

                // Close old states and update (except for encoderOutputsTensor which is reused)
                decoderInputTensor.close()
                if (step > 0) {
                    // Only close if not the original from encoder
                    hiddenTensor.close()
                    cellTensor.close()
                }
                hiddenTensor = newHiddenTensor
                cellTensor = newCellTensor

                // Stop if we hit EOS
                if (nextToken == eosId) {
                    decoderResult.close()
                    break
                }

                // Detect repetition - stop if same token generated 3+ times consecutively
                if (nextToken == lastToken) {
                    consecutiveRepeatCount++
                    if (consecutiveRepeatCount >= 2) {
                        // Stop decoding - model is stuck in a loop
                        decoderResult.close()
                        break
                    }
                } else {
                    consecutiveRepeatCount = 0
                }
                lastToken = nextToken

                // Skip PAD tokens
                if (nextToken != padId) {
                    outputIds.add(nextToken)
                }

                currentToken = nextToken
            }

            // Close remaining tensors
            encoderInputTensor.close()
            encoderResult.close()
            hiddenTensor.close()
            cellTensor.close()

            return outputIds
        } catch (e: Exception) {
            Log.e(TAG, "Encoder-decoder inference error: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Beam state for beam search decoding.
     */
    private data class BeamState(
        val tokens: List<Int>,
        val logProb: Float,
        val lastToken: Int,
        val hiddenState: FloatArray,
        val cellState: FloatArray
    )

    /**
     * Runs beam search inference to get multiple candidate outputs.
     * Uses beam search with top-k expansion at each step.
     */
    private fun runBeamSearchInference(inputTokens: LongArray, beamWidth: Int): List<List<Int>> {
        val encoder = encoderSession ?: throw RuntimeException("Encoder session not initialized")
        val decoder = decoderSession ?: throw RuntimeException("Decoder session not initialized")
        val env = ortEnv ?: throw RuntimeException("Environment not initialized")

        try {
            // Prepare encoder input [1, seq_len]
            val encoderInputData = arrayOf(inputTokens)
            val encoderInputTensor = OnnxTensor.createTensor(env, encoderInputData)

            // Run encoder
            val encoderInputMap = mapOf("pinyin_ids" to encoderInputTensor)
            val encoderResult = encoder.run(encoderInputMap)

            val encoderOutputsTensor = encoderResult.get("encoder_outputs").get() as OnnxTensor
            val initialHiddenTensor = encoderResult.get("hidden").get() as OnnxTensor
            val initialCellTensor = encoderResult.get("cell").get() as OnnxTensor

            // Extract hidden and cell states as arrays for beam state
            val initialHidden = extractFloatArray(initialHiddenTensor)
            val initialCell = extractFloatArray(initialCellTensor)

            // Calculate reasonable max output length based on input pinyin length
            val inputLength = inputTokens.size
            val maxAllowedLen = minOf(maxOutputLen, inputLength / 2 + 5)

            // Initialize beams with SOS token
            var beams = listOf(
                BeamState(
                    tokens = emptyList(),
                    logProb = 0f,
                    lastToken = sosId,
                    hiddenState = initialHidden,
                    cellState = initialCell
                )
            )

            val completedBeams = mutableListOf<BeamState>()

            for (step in 0 until maxAllowedLen) {
                if (beams.isEmpty()) break

                val newBeams = mutableListOf<BeamState>()

                for (beam in beams) {
                    // Create tensors for this beam
                    // Use pre-allocated buffer to reduce allocation overhead
                    decoderInputBuffer[0] = beam.lastToken.toLong()
                    val decoderInputTensor = OnnxTensor.createTensor(env, decoderInputBuffer)

                    // Reshape hidden/cell states back to tensor format [numLayers, 1, hiddenDim*2]
                    val hiddenTensor = createHiddenTensor(env, beam.hiddenState)
                    val cellTensor = createHiddenTensor(env, beam.cellState)

                    val decoderInputMap = mapOf(
                        "input_token" to decoderInputTensor,
                        "encoder_outputs" to encoderOutputsTensor,
                        "hidden" to hiddenTensor,
                        "cell" to cellTensor
                    )
                    val decoderResult = decoder.run(decoderInputMap)

                    // Get logits and compute log probabilities
                    val logitsTensor = decoderResult.get("output").get() as OnnxTensor
                    val logits = extractLogits(logitsTensor)

                    // Get top-k tokens
                    val topK = getTopKTokens(logits, beamWidth * 2)

                    // Get new hidden/cell states
                    val newHiddenTensor = decoderResult.get("new_hidden").get() as OnnxTensor
                    val newCellTensor = decoderResult.get("new_cell").get() as OnnxTensor
                    val newHidden = extractFloatArray(newHiddenTensor)
                    val newCell = extractFloatArray(newCellTensor)

                    for ((token, logProb) in topK) {
                        val newLogProb = beam.logProb + logProb

                        if (token == eosId) {
                            completedBeams.add(BeamState(
                                tokens = beam.tokens,
                                logProb = newLogProb,
                                lastToken = eosId,
                                hiddenState = newHidden,
                                cellState = newCell
                            ))
                        } else if (token != padId) {
                            // Check for repetition - skip if this token would create 3+ consecutive repeats
                            val newTokens = beam.tokens + token
                            val hasRepetition = newTokens.size >= 3 &&
                                newTokens[newTokens.size - 1] == newTokens[newTokens.size - 2] &&
                                newTokens[newTokens.size - 2] == newTokens[newTokens.size - 3]

                            if (!hasRepetition) {
                                newBeams.add(BeamState(
                                    tokens = newTokens,
                                    logProb = newLogProb,
                                    lastToken = token,
                                    hiddenState = newHidden,
                                    cellState = newCell
                                ))
                            }
                        }
                    }

                    // Clean up tensors
                    decoderInputTensor.close()
                    hiddenTensor.close()
                    cellTensor.close()
                }

                // Keep top beams
                beams = newBeams.sortedByDescending { it.logProb }.take(beamWidth)

                // Early stop if we have enough completed beams
                if (completedBeams.size >= beamWidth) break
            }

            // Add any remaining beams to completed
            completedBeams.addAll(beams)

            // Clean up
            encoderInputTensor.close()
            encoderResult.close()

            // Return sorted results
            return completedBeams
                .sortedByDescending { it.logProb }
                .take(beamWidth)
                .map { it.tokens }
        } catch (e: Exception) {
            Log.e(TAG, "Beam search inference error: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Extracts float array from ONNX tensor.
     */
    private fun extractFloatArray(tensor: OnnxTensor): FloatArray {
        val value = tensor.value
        return when (value) {
            is Array<*> -> {
                // 3D: [numLayers, 1, hidden*2] -> flatten
                @Suppress("UNCHECKED_CAST")
                val arr3d = value as Array<Array<FloatArray>>
                arr3d.flatMap { layer -> layer.flatMap { it.toList() } }.toFloatArray()
            }
            is FloatArray -> value
            else -> FloatArray(0)
        }
    }

    /**
     * Creates hidden state tensor from flat array.
     */
    private fun createHiddenTensor(env: OrtEnvironment, flatArray: FloatArray): OnnxTensor {
        // Reshape to [numLayers, 1, hiddenDim*2]
        val hiddenSize = hiddenDim * 2
        val reshaped = Array(numLayers) { layer ->
            Array(1) { FloatArray(hiddenSize) { idx ->
                flatArray.getOrElse(layer * hiddenSize + idx) { 0f }
            }}
        }
        return OnnxTensor.createTensor(env, reshaped)
    }

    /**
     * Extracts logits from output tensor.
     */
    private fun extractLogits(tensor: OnnxTensor): FloatArray {
        val value = tensor.value
        return when (value) {
            is Array<*> -> {
                @Suppress("UNCHECKED_CAST")
                when {
                    value.isArrayOf<FloatArray>() -> (value as Array<FloatArray>)[0]
                    else -> FloatArray(0)
                }
            }
            is FloatArray -> value
            else -> FloatArray(0)
        }
    }

    /**
     * Gets top-k tokens with their log probabilities.
     */
    private fun getTopKTokens(logits: FloatArray, k: Int): List<Pair<Int, Float>> {
        if (logits.isEmpty()) return emptyList()

        // Apply softmax and convert to log probs
        val maxLogit = logits.maxOrNull() ?: 0f
        val expLogits = logits.map { kotlin.math.exp(it - maxLogit) }
        val sumExp = expLogits.sum()
        val logProbs = expLogits.map { kotlin.math.ln(it / sumExp).toFloat() }

        // Get top-k indices
        return logProbs.mapIndexed { idx, prob -> idx to prob }
            .sortedByDescending { it.second }
            .take(k)
    }

    /**
     * Decodes output token IDs to Chinese string.
     * Also removes trailing repetitive characters.
     */
    private fun decodeOutput(outputIds: List<Int>): String {
        val result = StringBuilder()

        for (id in outputIds) {
            // Skip special tokens
            if (id == padId || id == sosId || id == eosId || id == unkId) {
                continue
            }

            val char = hanziIdx2Token[id]
            if (char != null && !char.startsWith("<")) {
                result.append(char)
            }
        }

        // Post-processing: trim trailing repetitive characters
        // If the last 2+ characters are the same, keep only one
        var output = result.toString()
        while (output.length >= 2) {
            val lastChar = output.last()
            val secondLastChar = output[output.length - 2]
            if (lastChar == secondLastChar) {
                output = output.dropLast(1)
            } else {
                break
            }
        }

        return output
    }

    /**
     * Releases the model and frees resources.
     */
    fun releaseModel() {
        try {
            encoderSession?.close()
            decoderSession?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing sessions: ${e.message}")
        }

        encoderSession = null
        decoderSession = null
        isModelReady = false
        pinyinToken2Idx = emptyMap()
        hanziToken2Idx = emptyMap()
        hanziIdx2Token = emptyMap()
    }

    /**
     * Model status enum.
     */
    enum class ModelStatus {
        NOT_CONFIGURED,      // No zip file selected
        ZIP_CONFIGURED,      // Zip file selected, not extracted
        EXTRACTING,          // Currently extracting
        AVAILABLE,           // Extracted, ready for initialization
        LOADING,             // Loading model into memory
        READY               // Ready for inference
    }
}
