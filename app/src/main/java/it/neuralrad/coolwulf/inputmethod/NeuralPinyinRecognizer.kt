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
import java.util.zip.ZipInputStream

/**
 * Neural network-based Pinyin to Chinese character conversion using ONNX Runtime.
 * Uses a Transformer encoder-decoder model for improved accuracy on long sentences.
 *
 * The model is loaded from a user-selected zip file containing:
 * - encoder.onnx (encoder model)
 * - decoder.onnx (decoder model)
 * - vocab_pinyin.txt (pinyin vocabulary)
 * - vocab_hanzi.txt (Chinese character vocabulary)
 * - config.json (optional model configuration)
 *
 * This is an optional enhancement for pinyin input when typing long sentences (>4 letters).
 *
 * Model source: Duyu/Pinyin2Hanzi-Transformer from HuggingFace
 * Converted using tools/convert_pinyin2hanzi_onnx.py
 */
class NeuralPinyinRecognizer(private val context: Context) {
    companion object {
        private const val TAG = "NeuralPinyinRecognizer"
        private const val EXTRACTED_MODEL_DIR = "neural-pinyin-model"

        // Model files
        private const val ENCODER_FILE = "encoder.onnx"
        private const val DECODER_FILE = "decoder.onnx"
        private const val VOCAB_PINYIN_FILE = "vocab_pinyin.txt"
        private const val VOCAB_HANZI_FILE = "vocab_hanzi.txt"
        private const val CONFIG_FILE = "config.json"

        // Maximum sequence lengths
        private const val MAX_INPUT_LENGTH = 30   // Max pinyin tokens
        private const val MAX_OUTPUT_LENGTH = 20  // Max hanzi output

        // Special token IDs (standard for this model)
        private const val PAD_ID = 0
        private const val UNK_ID = 1
        private const val SOS_ID = 2  // <sos> start of sequence
        private const val EOS_ID = 3  // <eos> end of sequence

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
                // Try to load the OrtEnvironment class
                Class.forName("ai.onnxruntime.OrtEnvironment")
                onnxRuntimeAvailable = true
                Log.d(TAG, "ONNX Runtime library available")
                true
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "ONNX Runtime library not available")
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
    private var hanziToId: Map<String, Int> = emptyMap()

    // ONNX Runtime sessions (encoder and decoder)
    private var ortEnv: Any? = null           // ai.onnxruntime.OrtEnvironment
    private var encoderSession: Any? = null   // ai.onnxruntime.OrtSession
    private var decoderSession: Any? = null   // ai.onnxruntime.OrtSession

    // Model dimension (from config or default)
    private var dModel: Int = 512

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
        // Check for required model files (encoder + decoder format)
        val hasEncoder = File(extractedModelDir, ENCODER_FILE).exists()
        val hasDecoder = File(extractedModelDir, DECODER_FILE).exists()
        val hasPinyinVocab = File(extractedModelDir, VOCAB_PINYIN_FILE).exists()
        val hasHanziVocab = File(extractedModelDir, VOCAB_HANZI_FILE).exists()
        return hasEncoder && hasDecoder && hasPinyinVocab && hasHanziVocab
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
     */
    fun extractModel(
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        Log.d(TAG, "extractModel called")
        val uriStr = getModelZipUri()
        if (uriStr == null) {
            Log.e(TAG, "No model zip file configured")
            onComplete?.invoke(false, "No model zip file configured")
            return
        }

        if (isExtracting) {
            Log.w(TAG, "Extraction already in progress")
            onComplete?.invoke(false, "Extraction already in progress")
            return
        }

        isExtracting = true
        Log.d(TAG, "Starting extraction coroutine for: $uriStr")

        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            var errorMessage: String? = null

            try {
                val uri = Uri.parse(uriStr)
                val fileName = getModelZipName() ?: "model"
                Log.d(TAG, "Parsed URI: $uri, fileName: $fileName")

                // Delete existing extracted model
                deleteExtractedModel()
                Log.d(TAG, "Deleted existing extracted model")

                // Create extraction directory
                extractedModelDir.mkdirs()
                Log.d(TAG, "Created extraction directory: ${extractedModelDir.absolutePath}")

                withContext(Dispatchers.Main) {
                    onProgress?.invoke(0, "Starting extraction...")
                }

                when {
                    fileName.endsWith(".tar.bz2") || fileName.endsWith(".tbz2") -> {
                        Log.d(TAG, "Extracting tar.bz2 file")
                        extractTarBz2(uri, onProgress)
                    }
                    else -> {
                        Log.d(TAG, "Extracting zip file")
                        extractZip(uri, onProgress)
                    }
                }

                withContext(Dispatchers.Main) {
                    onProgress?.invoke(100, "Extraction complete")
                }

                // Verify extraction
                val extracted = isModelExtracted()
                Log.d(TAG, "Verification - isModelExtracted: $extracted")

                // List extracted files for debugging
                if (extractedModelDir.exists()) {
                    val files = extractedModelDir.listFiles()
                    Log.d(TAG, "Files in extraction directory: ${files?.map { it.name }}")
                }

                if (extracted) {
                    success = true
                } else {
                    errorMessage = "Model files not found after extraction. Required: encoder.onnx, decoder.onnx, vocab_pinyin.txt, vocab_hanzi.txt"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting model: ${e.message}", e)
                errorMessage = e.message ?: "Unknown error"
            } finally {
                isExtracting = false
                Log.d(TAG, "Extraction finished - success: $success, error: $errorMessage")

                // Always call onComplete on main thread
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(success, errorMessage)
                }
            }
        }
    }

    /**
     * Extracts a .zip archive.
     */
    private suspend fun extractZip(uri: Uri, onProgress: ((Int, String) -> Unit)?) {
        Log.d(TAG, "Starting zip extraction from: $uri")

        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open zip file")

        ZipInputStream(inputStream).use { zipIn ->
            var entry = zipIn.nextEntry
            var fileCount = 0
            val extractedFiles = mutableListOf<String>()

            while (entry != null) {
                // Handle both flat and nested directory structures
                var entryName = entry.name
                Log.d(TAG, "Processing zip entry: $entryName (isDirectory: ${entry.isDirectory})")

                // Strip common prefixes
                if (entryName.contains('/')) {
                    entryName = entryName.substringAfterLast('/')
                }

                if (entryName.isNotEmpty() && !entry.isDirectory) {
                    val outFile = File(extractedModelDir, entryName)
                    outFile.parentFile?.mkdirs()

                    Log.d(TAG, "Extracting: $entryName to ${outFile.absolutePath}")

                    FileOutputStream(outFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = 0L
                        while (zipIn.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                        }
                        Log.d(TAG, "Extracted $entryName: $totalBytes bytes")
                    }

                    extractedFiles.add(entryName)
                    fileCount++

                    // Calculate progress based on expected files (5 files total)
                    val progress = minOf((fileCount * 100) / 5, 95)
                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(progress, "Extracted: $entryName")
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }

            Log.d(TAG, "Extraction complete. Files extracted: $extractedFiles")
        }
    }

    /**
     * Extracts a .tar.bz2 archive.
     */
    private suspend fun extractTarBz2(uri: Uri, onProgress: ((Int, String) -> Unit)?) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open tar.bz2 file")

        val bufferedIn = BufferedInputStream(inputStream)
        val bzIn = BZip2CompressorInputStream(bufferedIn)
        val tarIn = TarArchiveInputStream(bzIn)

        var entry = tarIn.nextEntry
        var fileCount = 0

        while (entry != null) {
            var entryName = entry.name
            if (entryName.contains('/')) {
                entryName = entryName.substringAfterLast('/')
            }

            if (entryName.isNotEmpty() && !entry.isDirectory && tarIn.canReadEntryData(entry)) {
                val outFile = File(extractedModelDir, entryName)
                outFile.parentFile?.mkdirs()

                FileOutputStream(outFile).use { fos ->
                    tarIn.copyTo(fos)
                }

                fileCount++
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(
                        minOf(fileCount * 20, 90),
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
     * Clears the configuration and optionally deletes extracted model.
     */
    fun clearConfig(deleteExtracted: Boolean = false) {
        SettingsManager.setNeuralPinyinModelPath(context, null)
        if (deleteExtracted) {
            deleteExtractedModel()
        }
    }

    /**
     * Initializes the ONNX model for inference.
     */
    fun initModel(onReady: ((Boolean, String?) -> Unit)? = null) {
        Log.d(TAG, "initModel called - isModelReady=$isModelReady, isModelLoading=$isModelLoading")

        if (isModelReady) {
            Log.d(TAG, "Model already ready, returning")
            onReady?.invoke(true, null)
            return
        }

        if (isModelLoading) {
            Log.d(TAG, "Model already loading, returning")
            onReady?.invoke(false, "Model is already loading")
            return
        }

        val extracted = isModelExtracted()
        Log.d(TAG, "isModelExtracted=$extracted")
        if (!extracted) {
            Log.e(TAG, "Model not extracted!")
            onReady?.invoke(false, "Model not extracted")
            return
        }

        val onnxAvailable = isOnnxRuntimeAvailable()
        Log.d(TAG, "isOnnxRuntimeAvailable=$onnxAvailable")
        if (!onnxAvailable) {
            Log.e(TAG, "ONNX Runtime not available!")
            onReady?.invoke(false, "ONNX Runtime not available. Please ensure the ONNX Runtime AAR is included.")
            return
        }

        isModelLoading = true
        Log.d(TAG, "Starting model initialization in background...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Loading vocabularies...")
                loadVocabularies()
                Log.d(TAG, "Vocabularies loaded successfully")

                Log.d(TAG, "Loading config...")
                loadConfig()
                Log.d(TAG, "Config loaded")

                Log.d(TAG, "Initializing ONNX Runtime sessions...")
                initOrtSessions()
                Log.d(TAG, "ONNX sessions initialized")

                isModelReady = true
                Log.i(TAG, "Neural pinyin model initialized successfully! isModelReady=$isModelReady")

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

        // Load hanzi vocabulary (id -> token and token -> id)
        val hanziVocabFile = File(extractedModelDir, VOCAB_HANZI_FILE)
        val hanziIdToToken = mutableMapOf<Int, String>()
        val hanziTokenToId = mutableMapOf<String, Int>()
        hanziVocabFile.readLines().forEachIndexed { index, line ->
            val token = line.trim()
            if (token.isNotEmpty()) {
                hanziIdToToken[index] = token
                hanziTokenToId[token] = index
            }
        }
        hanziVocab = hanziIdToToken
        hanziToId = hanziTokenToId

        Log.d(TAG, "Loaded vocabularies: ${pinyinVocab.size} pinyin, ${hanziVocab.size} hanzi")
    }

    /**
     * Loads model configuration from config.json if available.
     */
    private fun loadConfig() {
        val configFile = File(extractedModelDir, CONFIG_FILE)
        if (configFile.exists()) {
            try {
                val configText = configFile.readText()
                // Simple JSON parsing for d_model
                val dModelMatch = """"d_model"\s*:\s*(\d+)""".toRegex().find(configText)
                dModelMatch?.groupValues?.get(1)?.toIntOrNull()?.let {
                    dModel = it
                    Log.d(TAG, "Loaded d_model from config: $dModel")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load config: ${e.message}")
            }
        }
    }

    /**
     * Initializes ONNX Runtime sessions for encoder and decoder.
     */
    private fun initOrtSessions() {
        try {
            // Get OrtEnvironment
            val ortEnvClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val getEnvironmentMethod = ortEnvClass.getMethod("getEnvironment")
            ortEnv = getEnvironmentMethod.invoke(null)

            // Create session options
            val sessionOptionsClass = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
            val sessionOptions = sessionOptionsClass.getDeclaredConstructor().newInstance()

            // Set number of threads for efficiency
            val setIntraOpNumThreadsMethod = sessionOptionsClass.getMethod("setIntraOpNumThreads", Int::class.java)
            setIntraOpNumThreadsMethod.invoke(sessionOptions, 2)

            // Create session method
            val createSessionMethod = ortEnvClass.getMethod(
                "createSession",
                String::class.java,
                sessionOptionsClass
            )

            // Load encoder
            val encoderPath = File(extractedModelDir, ENCODER_FILE).absolutePath
            encoderSession = createSessionMethod.invoke(ortEnv, encoderPath, sessionOptions)
            Log.d(TAG, "Encoder session created")

            // Load decoder
            val decoderPath = File(extractedModelDir, DECODER_FILE).absolutePath
            decoderSession = createSessionMethod.invoke(ortEnv, decoderPath, sessionOptions)
            Log.d(TAG, "Decoder session created")

        } catch (e: ClassNotFoundException) {
            throw RuntimeException("ONNX Runtime classes not found. Make sure the library is included.", e)
        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize ONNX Runtime: ${e.message}", e)
        }
    }

    /**
     * Converts pinyin string to Chinese characters using the neural model.
     * Uses autoregressive decoding with greedy search.
     *
     * @param pinyin The pinyin input (space-separated syllables, e.g., "ni hao")
     * @return List of candidate Chinese strings
     */
    fun convert(pinyin: String): List<String> {
        if (!isModelReady || encoderSession == null || decoderSession == null) {
            Log.w(TAG, "Model not ready for inference")
            return emptyList()
        }

        try {
            // Tokenize pinyin input with <sos> and <eos>
            val pinyinTokens = tokenizePinyin(pinyin)
            if (pinyinTokens.isEmpty()) {
                Log.w(TAG, "Empty pinyin tokens")
                return emptyList()
            }

            // Run encoder to get memory
            val memory = runEncoder(pinyinTokens)
            if (memory == null) {
                Log.w(TAG, "Encoder returned null")
                return emptyList()
            }

            // Create source padding mask
            val srcPaddingMask = BooleanArray(pinyinTokens.size) { i ->
                pinyinTokens[i] == PAD_ID.toLong()
            }

            // Autoregressive decoding
            val outputIds = mutableListOf<Int>()
            outputIds.add(SOS_ID)  // Start with <sos>

            for (step in 0 until MAX_OUTPUT_LENGTH) {
                val nextToken = runDecoderStep(outputIds, memory, srcPaddingMask)
                if (nextToken == EOS_ID) {
                    break
                }
                outputIds.add(nextToken)
            }

            // Decode output to Chinese characters (skip <sos>)
            val result = decodeOutput(outputIds.drop(1))

            return if (result.isNotEmpty()) listOf(result) else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Tokenizes pinyin string into vocabulary IDs with <sos> and <eos>.
     */
    private fun tokenizePinyin(pinyin: String): LongArray {
        val syllables = pinyin.lowercase().trim().split("\\s+".toRegex())
        val tokens = mutableListOf<Long>()

        // Add <sos>
        tokens.add(SOS_ID.toLong())

        for (syllable in syllables) {
            // Try with tone number (e.g., "ni3")
            var id = pinyinVocab[syllable]

            // Try without tone if not found
            if (id == null) {
                val syllableNoTone = syllable.replace(Regex("[0-9]"), "")
                id = pinyinVocab[syllableNoTone]
            }

            if (id != null) {
                tokens.add(id.toLong())
            } else {
                // Use <unk> for unknown syllables
                tokens.add(UNK_ID.toLong())
                Log.d(TAG, "Unknown syllable: $syllable")
            }
        }

        // Add <eos>
        tokens.add(EOS_ID.toLong())

        // Pad to MAX_INPUT_LENGTH
        val paddedTokens = LongArray(MAX_INPUT_LENGTH) { PAD_ID.toLong() }
        tokens.take(MAX_INPUT_LENGTH).forEachIndexed { index, token ->
            paddedTokens[index] = token
        }

        return paddedTokens
    }

    /**
     * Runs the encoder to produce memory.
     * @return Float array of shape [1, seq_len, d_model] flattened
     */
    private fun runEncoder(pinyinTokens: LongArray): FloatArray? {
        try {
            val env = ortEnv ?: return null
            val session = encoderSession ?: return null

            val ortTensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
            val sessionClass = Class.forName("ai.onnxruntime.OrtSession")
            val resultClass = Class.forName("ai.onnxruntime.OrtSession\$Result")
            val ortEnvClass = Class.forName("ai.onnxruntime.OrtEnvironment")

            // Create input tensor [1, seq_len]
            val createTensorLongMethod = ortTensorClass.getMethod(
                "createTensor",
                ortEnvClass,
                LongArray::class.java,
                LongArray::class.java
            )
            val inputShape = longArrayOf(1, pinyinTokens.size.toLong())
            val inputTensor = createTensorLongMethod.invoke(null, env, pinyinTokens, inputShape)

            // Run encoder
            val runMethod = sessionClass.getMethod("run", java.util.Map::class.java)
            val inputMap = mapOf("pinyin" to inputTensor)
            val result = runMethod.invoke(session, inputMap)

            // Get output (memory)
            val getMethod = resultClass.getMethod("get", Int::class.java)
            val outputTensor = getMethod.invoke(result, 0)

            val getValueMethod = ortTensorClass.getMethod("getValue")
            @Suppress("UNCHECKED_CAST")
            val memoryArray = getValueMethod.invoke(outputTensor) as Array<Array<FloatArray>>

            // Flatten to 1D for easier handling
            val seqLen = memoryArray[0].size
            val flatMemory = FloatArray(seqLen * dModel)
            for (i in 0 until seqLen) {
                System.arraycopy(memoryArray[0][i], 0, flatMemory, i * dModel, dModel)
            }

            // Clean up
            val closeMethod = ortTensorClass.getMethod("close")
            closeMethod.invoke(inputTensor)
            val resultCloseMethod = resultClass.getMethod("close")
            resultCloseMethod.invoke(result)

            return flatMemory
        } catch (e: Exception) {
            Log.e(TAG, "Encoder error: ${e.message}", e)
            return null
        }
    }

    /**
     * Runs one step of decoder to get the next token.
     */
    private fun runDecoderStep(currentTokens: List<Int>, memory: FloatArray, srcPaddingMask: BooleanArray): Int {
        try {
            val env = ortEnv ?: return EOS_ID
            val session = decoderSession ?: return EOS_ID

            val ortTensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
            val sessionClass = Class.forName("ai.onnxruntime.OrtSession")
            val resultClass = Class.forName("ai.onnxruntime.OrtSession\$Result")
            val ortEnvClass = Class.forName("ai.onnxruntime.OrtEnvironment")

            // Create hanzi_input tensor [1, tgt_seq_len]
            val hanziInput = LongArray(currentTokens.size) { currentTokens[it].toLong() }
            val createTensorLongMethod = ortTensorClass.getMethod(
                "createTensor",
                ortEnvClass,
                LongArray::class.java,
                LongArray::class.java
            )
            val hanziShape = longArrayOf(1, hanziInput.size.toLong())
            val hanziTensor = createTensorLongMethod.invoke(null, env, hanziInput, hanziShape)

            // Create memory tensor [1, src_seq_len, d_model]
            val srcSeqLen = memory.size / dModel
            val memoryShape = longArrayOf(1, srcSeqLen.toLong(), dModel.toLong())
            val createTensorFloatMethod = ortTensorClass.getMethod(
                "createTensor",
                ortEnvClass,
                FloatArray::class.java,
                LongArray::class.java
            )
            val memoryTensor = createTensorFloatMethod.invoke(null, env, memory, memoryShape)

            // Create src_key_padding_mask tensor [1, src_seq_len]
            val createTensorBoolMethod = ortTensorClass.getMethod(
                "createTensor",
                ortEnvClass,
                BooleanArray::class.java,
                LongArray::class.java
            )
            val maskShape = longArrayOf(1, srcPaddingMask.size.toLong())
            val maskTensor = createTensorBoolMethod.invoke(null, env, srcPaddingMask, maskShape)

            // Run decoder
            val runMethod = sessionClass.getMethod("run", java.util.Map::class.java)
            val inputMap = mapOf(
                "hanzi_input" to hanziTensor,
                "memory" to memoryTensor,
                "src_key_padding_mask" to maskTensor
            )
            val result = runMethod.invoke(session, inputMap)

            // Get output logits [1, tgt_seq_len, vocab_size]
            val getMethod = resultClass.getMethod("get", Int::class.java)
            val outputTensor = getMethod.invoke(result, 0)

            val getValueMethod = ortTensorClass.getMethod("getValue")
            @Suppress("UNCHECKED_CAST")
            val logits = getValueMethod.invoke(outputTensor) as Array<Array<FloatArray>>

            // Get logits for the last position (greedy decoding)
            val lastLogits = logits[0][logits[0].size - 1]

            // Find argmax (greedy selection)
            var maxIdx = 0
            var maxVal = lastLogits[0]
            for (i in 1 until lastLogits.size) {
                if (lastLogits[i] > maxVal) {
                    maxVal = lastLogits[i]
                    maxIdx = i
                }
            }

            // Clean up
            val closeMethod = ortTensorClass.getMethod("close")
            closeMethod.invoke(hanziTensor)
            closeMethod.invoke(memoryTensor)
            closeMethod.invoke(maskTensor)
            val resultCloseMethod = resultClass.getMethod("close")
            resultCloseMethod.invoke(result)

            return maxIdx
        } catch (e: Exception) {
            Log.e(TAG, "Decoder step error: ${e.message}", e)
            return EOS_ID
        }
    }

    /**
     * Decodes output token IDs to Chinese string.
     */
    private fun decodeOutput(outputIds: List<Int>): String {
        val result = StringBuilder()

        for (id in outputIds) {
            if (id == EOS_ID || id == PAD_ID) break
            if (id == SOS_ID || id == UNK_ID) continue

            val token = hanziVocab[id]
            if (token != null && !token.startsWith("<")) {
                result.append(token)
            }
        }

        return result.toString()
    }

    /**
     * Releases the model and frees resources.
     */
    fun releaseModel() {
        try {
            val sessionClass = Class.forName("ai.onnxruntime.OrtSession")
            val closeMethod = sessionClass.getMethod("close")

            encoderSession?.let { closeMethod.invoke(it) }
            decoderSession?.let { closeMethod.invoke(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing sessions: ${e.message}")
        }

        encoderSession = null
        decoderSession = null
        ortEnv = null
        isModelReady = false
        pinyinVocab = emptyMap()
        hanziVocab = emptyMap()
        hanziToId = emptyMap()
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
