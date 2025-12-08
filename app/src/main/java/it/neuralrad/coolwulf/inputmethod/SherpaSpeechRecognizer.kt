package it.neuralrad.coolwulf.inputmethod

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
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
 * Offline speech recognition using Sherpa-ONNX.
 * Supports Paraformer models for better Chinese/Mandarin recognition.
 * The model is loaded from a user-selected zip file.
 */
class SherpaSpeechRecognizer(private val context: Context) {
    companion object {
        private const val TAG = "SherpaSpeechRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val EXTRACTED_MODEL_DIR = "sherpa-model"

        @Volatile
        private var instance: SherpaSpeechRecognizer? = null

        @Volatile
        private var libraryAvailable: Boolean? = null

        fun getInstance(context: Context): SherpaSpeechRecognizer {
            return instance ?: synchronized(this) {
                instance ?: SherpaSpeechRecognizer(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Check if Sherpa-ONNX library is available on this device.
         */
        fun isLibraryAvailable(): Boolean {
            if (libraryAvailable != null) return libraryAvailable!!

            return try {
                System.loadLibrary("sherpa-onnx-jni")
                libraryAvailable = true
                Log.d(TAG, "Sherpa-ONNX library loaded successfully")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Sherpa-ONNX library not available: ${e.message}")
                libraryAvailable = false
                false
            } catch (e: Exception) {
                Log.w(TAG, "Error checking Sherpa-ONNX library: ${e.message}")
                libraryAvailable = false
                false
            }
        }
    }

    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isModelLoading = false
    private var isModelReady = false
    private var isExtracting = false
    private var isListening = false

    // Audio buffer for recording
    private val audioSamples = mutableListOf<Float>()

    // Callbacks
    private var onResultListener: ((String) -> Unit)? = null
    private var onPartialResultListener: ((String) -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null

    /**
     * Gets the extracted model directory in app's internal storage.
     */
    private val extractedModelDir: File
        get() = File(context.filesDir, EXTRACTED_MODEL_DIR)

    /**
     * Gets the user-configured model zip file URI.
     */
    fun getModelZipUri(): String? {
        return SettingsManager.getSherpaModelPath(context)
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
        // Check for common Sherpa-ONNX model files
        val hasModel = File(extractedModelDir, "model.onnx").exists() ||
                File(extractedModelDir, "model.int8.onnx").exists()
        val hasTokens = File(extractedModelDir, "tokens.txt").exists()
        return hasModel && hasTokens
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
     * Extracts the model archive (supports .zip and .tar.bz2) to internal storage.
     */
    fun extractZipModel(
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val uriStr = getModelZipUri()
        if (uriStr == null) {
            onComplete?.invoke(false, "No file configured")
            return
        }

        if (isExtracting) {
            onComplete?.invoke(false, "Extraction already in progress")
            return
        }

        isExtracting = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(0, "Preparing to extract...")
                }

                // Delete existing extracted model
                if (extractedModelDir.exists()) {
                    extractedModelDir.deleteRecursively()
                }
                extractedModelDir.mkdirs()

                val uri = Uri.parse(uriStr)
                val fileName = getModelZipName()?.lowercase() ?: ""

                // Detect file type and extract accordingly
                if (fileName.endsWith(".tar.bz2") || fileName.endsWith(".tbz2") || fileName.endsWith(".bz2")) {
                    extractTarBz2(uri, onProgress)
                } else {
                    extractZip(uri, onProgress)
                }

                withContext(Dispatchers.Main) {
                    onProgress?.invoke(100, "Extraction complete!")
                    isExtracting = false
                    onComplete?.invoke(true, null)
                }
                Log.d(TAG, "Model extracted successfully to ${extractedModelDir.absolutePath}")

            } catch (e: Exception) {
                Log.e(TAG, "Error extracting model", e)
                withContext(Dispatchers.Main) {
                    isExtracting = false
                    onComplete?.invoke(false, e.message ?: "Extraction failed")
                }
            }
        }
    }

    /**
     * Extracts a .zip file.
     */
    private suspend fun extractZip(uri: Uri, onProgress: ((Int, String) -> Unit)?) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open zip file")

        ZipInputStream(inputStream).use { zipIn ->
            var entry = zipIn.nextEntry
            var fileCount = 0
            val totalEstimate = 20

            while (entry != null) {
                var entryName = entry.name
                if (entryName.contains('/')) {
                    val parts = entryName.split('/', limit = 2)
                    if (parts.size > 1 && parts[1].isNotEmpty()) {
                        entryName = parts[1]
                    } else {
                        entry = zipIn.nextEntry
                        continue
                    }
                }

                val destFile = File(extractedModelDir, entryName)

                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (zipIn.read(buffer).also { len = it } > 0) {
                            output.write(buffer, 0, len)
                        }
                    }
                    fileCount++
                    val progress = (fileCount * 100 / totalEstimate).coerceIn(0, 99)
                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(progress, "Extracting files ($fileCount)...")
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }

    /**
     * Extracts a .tar.bz2 file.
     */
    private suspend fun extractTarBz2(uri: Uri, onProgress: ((Int, String) -> Unit)?) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open tar.bz2 file")

        val bufferedIn = BufferedInputStream(inputStream)
        val bzIn = BZip2CompressorInputStream(bufferedIn)
        val tarIn = TarArchiveInputStream(bzIn)

        tarIn.use { tar ->
            var entry = tar.nextEntry
            var fileCount = 0
            val totalEstimate = 20

            while (entry != null) {
                var entryName = entry.name
                // Handle nested folder structure (remove top-level directory)
                if (entryName.contains('/')) {
                    val parts = entryName.split('/', limit = 2)
                    if (parts.size > 1 && parts[1].isNotEmpty()) {
                        entryName = parts[1]
                    } else {
                        entry = tar.nextEntry
                        continue
                    }
                }

                val destFile = File(extractedModelDir, entryName)

                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (tar.read(buffer).also { len = it } > 0) {
                            output.write(buffer, 0, len)
                        }
                    }
                    fileCount++
                    val progress = (fileCount * 100 / totalEstimate).coerceIn(0, 99)
                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(progress, "Extracting files ($fileCount)...")
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    /**
     * Deletes the extracted model to free up space.
     */
    fun deleteExtractedModel(): Boolean {
        return try {
            release()
            if (extractedModelDir.exists()) {
                extractedModelDir.deleteRecursively()
            }
            Log.d(TAG, "Extracted model deleted")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting extracted model", e)
            false
        }
    }

    /**
     * Gets the size of the extracted model in bytes.
     */
    fun getExtractedModelSize(): Long {
        if (!extractedModelDir.exists()) return 0
        return extractedModelDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    /**
     * Initializes the Sherpa-ONNX model.
     */
    fun initModel(onReady: ((Boolean, String?) -> Unit)? = null) {
        if (!isLibraryAvailable()) {
            onReady?.invoke(false, "Sherpa-ONNX library not installed")
            return
        }

        if (isModelReady && recognizer != null) {
            onReady?.invoke(true, null)
            return
        }

        if (!isModelExtracted()) {
            onReady?.invoke(false, "Model not extracted. Please extract the model first.")
            return
        }

        isModelLoading = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Loading Sherpa-ONNX model from: ${extractedModelDir.absolutePath}")

                // Find model files
                val modelPath = extractedModelDir.absolutePath
                val modelFile = when {
                    File(modelPath, "model.int8.onnx").exists() -> "$modelPath/model.int8.onnx"
                    File(modelPath, "model.onnx").exists() -> "$modelPath/model.onnx"
                    else -> throw IOException("Model file not found")
                }
                val tokensFile = "$modelPath/tokens.txt"

                Log.d(TAG, "Model file: $modelFile")
                Log.d(TAG, "Tokens file: $tokensFile")

                // Create Paraformer config
                val paraformerConfig = OfflineParaformerModelConfig(
                    model = modelFile
                )

                val modelConfig = OfflineModelConfig(
                    paraformer = paraformerConfig,
                    tokens = tokensFile,
                    numThreads = 2,
                    debug = false
                )

                val config = OfflineRecognizerConfig(
                    featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig
                )

                // Pass null for assetManager to use file-based loading
                recognizer = OfflineRecognizer(assetManager = null, config = config)
                isModelReady = true
                isModelLoading = false

                withContext(Dispatchers.Main) {
                    onReady?.invoke(true, null)
                }
                Log.d(TAG, "Sherpa-ONNX model loaded successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                isModelLoading = false
                withContext(Dispatchers.Main) {
                    onReady?.invoke(false, e.message ?: "Failed to load model")
                }
            }
        }
    }

    /**
     * Starts speech recognition with audio recording.
     */
    fun startListening(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (!isLibraryAvailable()) {
            onError?.invoke("Sherpa-ONNX library not installed")
            return
        }

        if (!isModelReady || recognizer == null) {
            onError?.invoke("Model not initialized")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            onError?.invoke("Microphone permission not granted")
            return
        }

        if (isListening) {
            stopListening()
        }

        onResultListener = onResult
        onPartialResultListener = onPartialResult
        onErrorListener = onError

        isListening = true
        audioSamples.clear()

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IOException("Failed to initialize AudioRecord")
            }

            audioRecord?.startRecording()
            Log.d(TAG, "Started recording audio")

            // Start recording in background
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ShortArray(bufferSize / 2)
                var lastPartialTime = System.currentTimeMillis()
                val partialInterval = 1500L // Process partial results every 1.5 seconds

                while (isListening && isActive) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readCount > 0) {
                        // Convert short samples to float samples
                        synchronized(audioSamples) {
                            for (i in 0 until readCount) {
                                audioSamples.add(buffer[i] / 32768.0f)
                            }
                        }

                        // Periodically decode for partial results
                        val now = System.currentTimeMillis()
                        if (now - lastPartialTime >= partialInterval && audioSamples.size > SAMPLE_RATE) {
                            lastPartialTime = now
                            val samples: FloatArray
                            synchronized(audioSamples) {
                                samples = audioSamples.toFloatArray()
                            }

                            // Decode current audio for partial result
                            try {
                                val rec = recognizer
                                if (rec != null && samples.isNotEmpty()) {
                                    val stream = rec.createStream()
                                    stream.acceptWaveform(samples, SAMPLE_RATE)
                                    rec.decode(stream)
                                    val result = rec.getResult(stream)
                                    val partialText = result.text.trim()
                                    stream.release()

                                    if (partialText.isNotEmpty()) {
                                        withContext(Dispatchers.Main) {
                                            onPartialResultListener?.invoke(partialText)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error getting partial result", e)
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            isListening = false
            onError?.invoke(e.message ?: "Failed to start recording")
        }
    }

    /**
     * Stops speech recognition and processes the recorded audio.
     * @param onComplete Callback with the final recognition result
     */
    fun stopListening(onComplete: ((String) -> Unit)? = null) {
        if (!isListening) {
            onComplete?.invoke("")
            return
        }

        isListening = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }
        audioRecord = null

        // Process recorded audio
        val samples: FloatArray
        synchronized(audioSamples) {
            samples = audioSamples.toFloatArray()
            audioSamples.clear()
        }

        if (samples.isEmpty()) {
            Log.d(TAG, "No audio samples recorded")
            onComplete?.invoke("")
            return
        }

        Log.d(TAG, "Processing ${samples.size} audio samples")

        // Run recognition in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rec = recognizer ?: run {
                    withContext(Dispatchers.Main) { onComplete?.invoke("") }
                    return@launch
                }

                val stream = rec.createStream()
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)

                val result = rec.getResult(stream)
                val text = result.text.trim()

                Log.d(TAG, "Recognition result: $text")

                withContext(Dispatchers.Main) {
                    if (text.isNotEmpty()) {
                        onResultListener?.invoke(text)
                    }
                    onComplete?.invoke(text)
                }

                stream.release()

            } catch (e: Exception) {
                Log.e(TAG, "Error during recognition", e)
                withContext(Dispatchers.Main) {
                    onErrorListener?.invoke(e.message ?: "Recognition failed")
                    onComplete?.invoke("")
                }
            }
        }
    }

    /**
     * Releases all resources.
     */
    fun release() {
        stopListening()
        recognizer?.release()
        recognizer = null
        isModelReady = false
    }

    /**
     * Clears the configured zip file and optionally deletes extracted model.
     */
    fun clearZipConfig(deleteExtracted: Boolean = true) {
        release()
        SettingsManager.setSherpaModelPath(context, null)
        if (deleteExtracted) {
            deleteExtractedModel()
        }
        Log.d(TAG, "Zip config cleared")
    }

    enum class ModelStatus {
        NOT_CONFIGURED,
        ZIP_CONFIGURED,
        EXTRACTING,
        AVAILABLE,
        LOADING,
        READY
    }
}
