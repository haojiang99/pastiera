package it.neuralrad.coolwulf.inputmethod

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import android.util.Log
import it.neuralrad.coolwulf.SettingsManager
import kotlinx.coroutines.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Neural network-based Pinyin to Chinese character conversion using ONNX Runtime.
 * Uses a Seq2Seq model with attention for improved accuracy on long sentences.
 * The model is loaded from a user-selected zip file containing:
 * - model.onnx (or model_int8.onnx for quantized version)
 * - vocab_pinyin.txt (pinyin vocabulary)
 * - vocab_hanzi.txt (Chinese character vocabulary)
 *
 * This is an optional enhancement for pinyin input when typing long sentences (>4 letters).
 */
class NeuralPinyinRecognizer(private val context: Context) {
    companion object {
        private const val TAG = "NeuralPinyinRecognizer"
        private const val EXTRACTED_MODEL_DIR = "neural-pinyin-model"
        private const val MODEL_FILE = "model.onnx"
        private const val MODEL_INT8_FILE = "model_int8.onnx"
        private const val MODEL_MOBILE_FP16_FILE = "model_mobile_fp16.onnx"
        private const val VOCAB_PINYIN_FILE = "vocab_pinyin.txt"
        private const val VOCAB_HANZI_FILE = "vocab_hanzi.txt"

        // Maximum sequence length for inference
        private const val MAX_INPUT_LENGTH = 50
        private const val MAX_OUTPUT_LENGTH = 30

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

    // Vocabularies
    private var pinyinVocab: Map<String, Int> = emptyMap()
    private var hanziVocab: Map<Int, String> = emptyMap()
    private var pinyinPadId: Int = 0
    private var hanziStartId: Int = 1
    private var hanziEndId: Int = 2

    // ONNX Runtime session (will be initialized when model is loaded)
    private var ortSession: OrtSession? = null
    private var ortEnv: OrtEnvironment? = null

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
        // Check for required model files (support multiple model naming conventions)
        val hasModel = File(extractedModelDir, MODEL_FILE).exists() ||
                File(extractedModelDir, MODEL_INT8_FILE).exists() ||
                File(extractedModelDir, MODEL_MOBILE_FP16_FILE).exists()
        val hasPinyinVocab = File(extractedModelDir, VOCAB_PINYIN_FILE).exists()
        val hasHanziVocab = File(extractedModelDir, VOCAB_HANZI_FILE).exists()
        return hasModel && hasPinyinVocab && hasHanziVocab
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
        Log.e(TAG, "extractModel: Starting extraction")  // Using Log.e for release builds
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

                // Set isExtracting to false BEFORE calling onComplete to avoid race condition
                // where getModelStatus() is called before isExtracting is cleared
                isExtracting = false
                Log.e(TAG, "extractModel: isExtracting set to false")

                if (extracted) {
                    Log.e(TAG, "extractModel: Success!")
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(true, null)
                    }
                } else {
                    Log.e(TAG, "extractModel: Model files not found after extraction")
                    // Log what files are actually there
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
                // Otherwise use the name as-is
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

        // Log extracted files
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
            // Strip the first directory level
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
     * Initializes the ONNX model for inference.
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
                // Load vocabularies
                loadVocabularies()

                // Find model file (prefer int8 quantized version, then mobile fp16, then full)
                val modelFile = when {
                    File(extractedModelDir, MODEL_INT8_FILE).exists() ->
                        File(extractedModelDir, MODEL_INT8_FILE).absolutePath
                    File(extractedModelDir, MODEL_MOBILE_FP16_FILE).exists() ->
                        File(extractedModelDir, MODEL_MOBILE_FP16_FILE).absolutePath
                    File(extractedModelDir, MODEL_FILE).exists() ->
                        File(extractedModelDir, MODEL_FILE).absolutePath
                    else -> throw IOException("Model file not found")
                }

                // Initialize ONNX Runtime session using reflection
                // This avoids compile-time dependency on ONNX Runtime
                initOrtSession(modelFile)

                isModelReady = true
                Log.i(TAG, "Neural pinyin model initialized successfully")

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
     * Loads the pinyin and hanzi vocabularies from files.
     */
    private fun loadVocabularies() {
        // Load pinyin vocabulary (token -> id)
        val pinyinVocabFile = File(extractedModelDir, VOCAB_PINYIN_FILE)
        val pinyinMap = mutableMapOf<String, Int>()
        pinyinVocabFile.readLines().forEachIndexed { index, line ->
            val token = line.trim()
            if (token.isNotEmpty()) {
                pinyinMap[token] = index
            }
        }
        pinyinVocab = pinyinMap
        pinyinPadId = pinyinMap["<pad>"] ?: 0

        // Load hanzi vocabulary (id -> token)
        val hanziVocabFile = File(extractedModelDir, VOCAB_HANZI_FILE)
        val hanziMap = mutableMapOf<Int, String>()
        hanziVocabFile.readLines().forEachIndexed { index, line ->
            val token = line.trim()
            if (token.isNotEmpty()) {
                hanziMap[index] = token
            }
        }
        hanziVocab = hanziMap
        hanziStartId = hanziMap.entries.find { it.value == "<s>" }?.key ?: 1
        hanziEndId = hanziMap.entries.find { it.value == "</s>" }?.key ?: 2

        Log.d(TAG, "Loaded vocabularies: ${pinyinVocab.size} pinyin, ${hanziVocab.size} hanzi")
    }

    /**
     * Initializes the ONNX Runtime session.
     */
    private fun initOrtSession(modelPath: String) {
        try {
            // Get OrtEnvironment singleton
            ortEnv = OrtEnvironment.getEnvironment()

            // Create session options
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(2)

            // Create session from model file
            ortSession = ortEnv!!.createSession(modelPath, sessionOptions)

            Log.d(TAG, "ONNX Runtime session created successfully")
        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize ONNX Runtime: ${e.message}", e)
        }
    }

    /**
     * Converts pinyin string to Chinese characters using the neural model.
     * @param pinyin The pinyin input (space-separated syllables, e.g., "ni hao")
     * @return List of candidate Chinese strings, sorted by probability
     */
    fun convert(pinyin: String): List<String> {
        if (!isModelReady || ortSession == null) {
            Log.w(TAG, "Model not ready for inference")
            return emptyList()
        }

        try {
            // Tokenize pinyin input
            val pinyinTokens = tokenizePinyin(pinyin)
            if (pinyinTokens.isEmpty()) {
                return emptyList()
            }

            // Run inference
            val outputIds = runInference(pinyinTokens)

            // Decode output to Chinese characters
            val result = decodeOutput(outputIds)

            return if (result.isNotEmpty()) listOf(result) else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Tokenizes pinyin string into vocabulary IDs.
     * The model uses character-level input (e.g., "ni hao" -> ['n','i',' ','h','a','o']).
     */
    private fun tokenizePinyin(pinyin: String): LongArray {
        val cleanedPinyin = pinyin.lowercase().trim()
        val tokens = mutableListOf<Long>()

        // Character-by-character tokenization
        for (char in cleanedPinyin) {
            val charStr = char.toString()
            val id = pinyinVocab[charStr]
            if (id != null) {
                tokens.add(id.toLong())
            } else {
                // Use <unk> token for unknown characters
                val unkId = pinyinVocab["<unk>"] ?: 1
                tokens.add(unkId.toLong())
            }
        }

        // Pad or truncate to MAX_INPUT_LENGTH
        val paddedTokens = LongArray(MAX_INPUT_LENGTH) { pinyinPadId.toLong() }
        tokens.take(MAX_INPUT_LENGTH).forEachIndexed { index, token ->
            paddedTokens[index] = token
        }

        return paddedTokens
    }

    /**
     * Runs model inference using ONNX Runtime.
     * The CBHG model outputs logits [batch, seq_len, vocab_size], we take argmax.
     */
    private fun runInference(inputTokens: LongArray): LongArray {
        try {
            val session = ortSession ?: throw RuntimeException("Session not initialized")
            val env = ortEnv ?: throw RuntimeException("Environment not initialized")

            // Create input tensor with shape [1, seq_len]
            // Reshape 1D array to 2D for batch dimension
            val inputData = arrayOf(inputTokens)
            val inputTensor = OnnxTensor.createTensor(env, inputData)

            // Run inference
            val inputMap = mapOf("pinyin_input" to inputTensor)
            val outputResult = session.run(inputMap)

            // Get output tensor
            val outputTensor = outputResult.get(0) as OnnxTensor
            val outputValue = outputTensor.value

            // Handle different output types
            val outputIds: LongArray = when (outputValue) {
                is Array<*> -> {
                    // Check if it's 3D float array (logits) or 2D long array (direct output)
                    @Suppress("UNCHECKED_CAST")
                    when {
                        outputValue.isArrayOf<FloatArray>() -> {
                            // 2D: [seq_len, vocab_size] - take argmax
                            val logits2d = outputValue as Array<FloatArray>
                            LongArray(logits2d.size) { i ->
                                logits2d[i].indices.maxByOrNull { logits2d[i][it] }?.toLong() ?: 0L
                            }
                        }
                        outputValue.isArrayOf<Array<*>>() -> {
                            // 3D: [batch, seq_len, vocab_size] - take argmax
                            val logits3d = outputValue as Array<Array<FloatArray>>
                            val seqLen = logits3d[0].size
                            LongArray(seqLen) { i ->
                                logits3d[0][i].indices.maxByOrNull { logits3d[0][i][it] }?.toLong() ?: 0L
                            }
                        }
                        outputValue.isArrayOf<LongArray>() -> {
                            // 2D long array - direct indices
                            (outputValue as Array<LongArray>)[0]
                        }
                        else -> {
                            Log.w(TAG, "Unexpected output type: ${outputValue.javaClass}")
                            LongArray(0)
                        }
                    }
                }
                else -> {
                    Log.w(TAG, "Unexpected output value type: ${outputValue?.javaClass}")
                    LongArray(0)
                }
            }

            // Close tensors and result
            inputTensor.close()
            outputResult.close()

            return outputIds
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            return LongArray(0)
        }
    }

    /**
     * Decodes output token IDs to Chinese string.
     * Handles special tokens: <pad>, <unk>, _, <s>, </s>
     */
    private fun decodeOutput(outputIds: LongArray): String {
        val result = StringBuilder()

        for (id in outputIds) {
            val idInt = id.toInt()
            // Stop at end token
            if (idInt == hanziEndId) break

            // Skip special tokens
            if (idInt == hanziStartId || idInt == 0) continue

            val char = hanziVocab[idInt]
            if (char != null) {
                // Skip special tokens and blank alignment tokens
                when (char) {
                    "<pad>", "<unk>", "_", "<s>", "</s>" -> continue
                    else -> result.append(char)
                }
            }
        }

        return result.toString()
    }

    /**
     * Releases the model and frees resources.
     */
    fun releaseModel() {
        try {
            ortSession?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing session: ${e.message}")
        }

        ortSession = null
        isModelReady = false
        pinyinVocab = emptyMap()
        hanziVocab = emptyMap()
    }

    /**
     * Model status enum (shared with SherpaSpeechRecognizer).
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
