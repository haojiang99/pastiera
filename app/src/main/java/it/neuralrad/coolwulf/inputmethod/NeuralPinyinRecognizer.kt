package it.neuralrad.coolwulf.inputmethod

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
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private var ortSession: Any? = null  // ai.onnxruntime.OrtSession
    private var ortEnv: Any? = null      // ai.onnxruntime.OrtEnvironment

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
        // Check for required model files
        val hasModel = File(extractedModelDir, MODEL_FILE).exists() ||
                File(extractedModelDir, MODEL_INT8_FILE).exists()
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
        return when {
            isModelReady -> ModelStatus.READY
            isModelLoading -> ModelStatus.LOADING
            isExtracting -> ModelStatus.EXTRACTING
            isModelExtracted() -> ModelStatus.AVAILABLE
            isZipConfigured() -> ModelStatus.ZIP_CONFIGURED
            else -> ModelStatus.NOT_CONFIGURED
        }
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
        val uriStr = getModelZipUri()
        if (uriStr == null) {
            onComplete?.invoke(false, "No model zip file configured")
            return
        }

        if (isExtracting) {
            onComplete?.invoke(false, "Extraction already in progress")
            return
        }

        isExtracting = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uri = Uri.parse(uriStr)
                val fileName = getModelZipName() ?: "model"

                // Delete existing extracted model
                deleteExtractedModel()

                // Create extraction directory
                extractedModelDir.mkdirs()

                withContext(Dispatchers.Main) {
                    onProgress?.invoke(0, "Starting extraction...")
                }

                when {
                    fileName.endsWith(".tar.bz2") || fileName.endsWith(".tbz2") -> {
                        extractTarBz2(uri, onProgress)
                    }
                    else -> {
                        extractZip(uri, onProgress)
                    }
                }

                withContext(Dispatchers.Main) {
                    onProgress?.invoke(100, "Extraction complete")
                }

                // Verify extraction
                if (isModelExtracted()) {
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(true, null)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(false, "Model files not found after extraction")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting model: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, e.message ?: "Unknown error")
                }
            } finally {
                isExtracting = false
            }
        }
    }

    /**
     * Extracts a .zip archive.
     */
    private fun extractZip(uri: Uri, onProgress: ((Int, String) -> Unit)?) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open zip file")

        ZipInputStream(inputStream).use { zipIn ->
            var entry = zipIn.nextEntry
            var fileCount = 0

            while (entry != null) {
                // Strip the first directory level if present (common in archives)
                val entryName = entry.name.substringAfter('/', entry.name)

                if (entryName.isNotEmpty() && !entry.isDirectory) {
                    val outFile = File(extractedModelDir, entryName)
                    outFile.parentFile?.mkdirs()

                    FileOutputStream(outFile).use { fos ->
                        zipIn.copyTo(fos)
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
        }
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

                // Find model file (prefer int8 quantized version)
                val modelFile = when {
                    File(extractedModelDir, MODEL_INT8_FILE).exists() ->
                        File(extractedModelDir, MODEL_INT8_FILE).absolutePath
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
     * Initializes the ONNX Runtime session using reflection.
     */
    private fun initOrtSession(modelPath: String) {
        try {
            // Get OrtEnvironment class
            val ortEnvClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val getEnvironmentMethod = ortEnvClass.getMethod("getEnvironment")
            ortEnv = getEnvironmentMethod.invoke(null)

            // Get OrtSession.SessionOptions class
            val sessionOptionsClass = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
            val sessionOptions = sessionOptionsClass.getDeclaredConstructor().newInstance()

            // Set number of threads
            val setIntraOpNumThreadsMethod = sessionOptionsClass.getMethod("setIntraOpNumThreads", Int::class.java)
            setIntraOpNumThreadsMethod.invoke(sessionOptions, 2)

            // Create session
            val createSessionMethod = ortEnvClass.getMethod(
                "createSession",
                String::class.java,
                sessionOptionsClass
            )
            ortSession = createSessionMethod.invoke(ortEnv, modelPath, sessionOptions)

            Log.d(TAG, "ONNX Runtime session created successfully")
        } catch (e: ClassNotFoundException) {
            throw RuntimeException("ONNX Runtime classes not found. Make sure the library is included.", e)
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
     */
    private fun tokenizePinyin(pinyin: String): LongArray {
        val syllables = pinyin.lowercase().trim().split("\\s+".toRegex())
        val tokens = mutableListOf<Long>()

        for (syllable in syllables) {
            val id = pinyinVocab[syllable]
            if (id != null) {
                tokens.add(id.toLong())
            } else {
                // Try character-by-character for unknown syllables
                for (char in syllable) {
                    val charId = pinyinVocab[char.toString()]
                    if (charId != null) {
                        tokens.add(charId.toLong())
                    }
                }
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
     * Runs model inference using ONNX Runtime via reflection.
     */
    private fun runInference(inputTokens: LongArray): LongArray {
        try {
            val session = ortSession ?: throw RuntimeException("Session not initialized")
            val env = ortEnv ?: throw RuntimeException("Environment not initialized")

            // Create input tensor
            val ortTensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
            val createTensorMethod = ortTensorClass.getMethod(
                "createTensor",
                Class.forName("ai.onnxruntime.OrtEnvironment"),
                LongArray::class.java,
                LongArray::class.java
            )

            val inputShape = longArrayOf(1, inputTokens.size.toLong())
            val inputTensor = createTensorMethod.invoke(null, env, inputTokens, inputShape)

            // Run inference
            val sessionClass = Class.forName("ai.onnxruntime.OrtSession")
            val runMethod = sessionClass.getMethod("run", java.util.Map::class.java)
            val inputMap = mapOf("input" to inputTensor)
            val outputResult = runMethod.invoke(session, inputMap)

            // Get output tensor
            val resultClass = Class.forName("ai.onnxruntime.OrtSession\$Result")
            val getMethod = resultClass.getMethod("get", Int::class.java)
            val outputTensor = getMethod.invoke(outputResult, 0)

            // Get output values
            val getValueMethod = ortTensorClass.getMethod("getValue")
            @Suppress("UNCHECKED_CAST")
            val outputArray = getValueMethod.invoke(outputTensor) as Array<LongArray>

            // Close tensors
            val closeMethod = ortTensorClass.getMethod("close")
            closeMethod.invoke(inputTensor)

            val resultCloseMethod = resultClass.getMethod("close")
            resultCloseMethod.invoke(outputResult)

            return outputArray[0]
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            return LongArray(0)
        }
    }

    /**
     * Decodes output token IDs to Chinese string.
     */
    private fun decodeOutput(outputIds: LongArray): String {
        val result = StringBuilder()

        for (id in outputIds) {
            val idInt = id.toInt()
            if (idInt == hanziEndId) break
            if (idInt == hanziStartId || idInt == 0) continue

            val char = hanziVocab[idInt]
            if (char != null && char != "<pad>" && char != "<unk>") {
                result.append(char)
            }
        }

        return result.toString()
    }

    /**
     * Releases the model and frees resources.
     */
    fun releaseModel() {
        try {
            ortSession?.let { session ->
                val sessionClass = Class.forName("ai.onnxruntime.OrtSession")
                val closeMethod = sessionClass.getMethod("close")
                closeMethod.invoke(session)
            }
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
