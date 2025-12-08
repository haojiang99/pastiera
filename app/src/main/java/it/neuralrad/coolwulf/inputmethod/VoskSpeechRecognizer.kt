package it.neuralrad.coolwulf.inputmethod

import android.content.Context
import android.net.Uri
import android.util.Log
import it.neuralrad.coolwulf.SettingsManager
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*

/**
 * Offline speech recognition using Vosk.
 * The model is loaded from a user-selected zip file.
 * Users can download Vosk model zip files and select them in settings.
 */
class VoskSpeechRecognizer(private val context: Context) {
    companion object {
        private const val TAG = "VoskSpeechRecognizer"
        private const val SAMPLE_RATE = 16000.0f
        private const val EXTRACTED_MODEL_DIR = "vosk-model"

        @Volatile
        private var instance: VoskSpeechRecognizer? = null

        @Volatile
        private var isVoskAvailable: Boolean? = null

        fun getInstance(context: Context): VoskSpeechRecognizer {
            return instance ?: synchronized(this) {
                instance ?: VoskSpeechRecognizer(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Check if Vosk library is available on this device.
         */
        fun isLibraryAvailable(): Boolean {
            if (isVoskAvailable != null) return isVoskAvailable!!

            return try {
                // Try to load the Vosk class to check if native library is available
                Class.forName("org.vosk.Model")
                isVoskAvailable = true
                true
            } catch (e: Exception) {
                Log.e(TAG, "Vosk library not available", e)
                isVoskAvailable = false
                false
            }
        }
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var isModelLoading = false
    private var isModelReady = false
    private var isExtracting = false

    // Callbacks
    private var onResultListener: ((String) -> Unit)? = null
    private var onPartialResultListener: ((String) -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var onModelLoadProgressListener: ((Int, String) -> Unit)? = null

    /**
     * Gets the extracted model directory in app's internal storage.
     */
    private val extractedModelDir: File
        get() = File(context.filesDir, EXTRACTED_MODEL_DIR)

    /**
     * Gets the user-configured model zip file URI.
     * Returns null if not set.
     */
    fun getModelZipUri(): String? {
        return SettingsManager.getVoskModelPath(context)
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
        // Check for common files in Vosk models
        val possibleFiles = listOf(
            File(extractedModelDir, "am/final.mdl"),
            File(extractedModelDir, "graph/Gr.fst"),
            File(extractedModelDir, "conf/model.conf"),
            File(extractedModelDir, "ivector/final.ie")
        )
        return possibleFiles.any { it.exists() }
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
     * Extracts the zip file to internal storage.
     */
    fun extractZipModel(
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val uriStr = getModelZipUri()
        if (uriStr == null) {
            onComplete?.invoke(false, "No zip file configured")
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
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open zip file")

                ZipInputStream(inputStream).use { zipIn ->
                    var entry = zipIn.nextEntry
                    var fileCount = 0
                    val totalEstimate = 50 // Estimate for progress

                    while (entry != null) {
                        // Handle nested folder structure (e.g., vosk-model-small-cn-0.22/...)
                        var entryName = entry.name
                        // Remove the first directory level if it exists
                        if (entryName.contains('/')) {
                            val parts = entryName.split('/', limit = 2)
                            if (parts.size > 1 && parts[1].isNotEmpty()) {
                                entryName = parts[1]
                            } else {
                                // Skip the top-level directory entry itself
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

                withContext(Dispatchers.Main) {
                    onProgress?.invoke(100, "Extraction complete!")
                    isExtracting = false
                    onComplete?.invoke(true, null)
                }
                Log.d(TAG, "Model extracted successfully to ${extractedModelDir.absolutePath}")

            } catch (e: Exception) {
                Log.e(TAG, "Error extracting zip model", e)
                withContext(Dispatchers.Main) {
                    isExtracting = false
                    onComplete?.invoke(false, e.message ?: "Extraction failed")
                }
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
     * Initializes the Vosk model. Must be called before starting recognition.
     * If model is not extracted yet, it will be extracted first.
     */
    fun initModel(onReady: ((Boolean, String?) -> Unit)? = null) {
        if (isModelReady && model != null) {
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
                Log.d(TAG, "Loading Vosk model from: ${extractedModelDir.absolutePath}")
                model = Model(extractedModelDir.absolutePath)
                isModelReady = true
                isModelLoading = false

                withContext(Dispatchers.Main) {
                    onReady?.invoke(true, null)
                }
                Log.d(TAG, "Vosk model loaded successfully")

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
     * Starts speech recognition.
     */
    fun startListening(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (model == null) {
            onError?.invoke("Model not initialized")
            return
        }

        if (speechService != null) {
            stopListening()
        }

        onResultListener = onResult
        onPartialResultListener = onPartialResult
        onErrorListener = onError

        try {
            val recognizer = Recognizer(model, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(recognitionListener)
            Log.d(TAG, "Started listening")
        } catch (e: IOException) {
            Log.e(TAG, "Error starting recognition", e)
            onError?.invoke(e.message ?: "Failed to start recognition")
        }
    }

    /**
     * Stops speech recognition.
     */
    fun stopListening() {
        speechService?.stop()
        speechService = null
        Log.d(TAG, "Stopped listening")
    }

    /**
     * Releases all resources.
     */
    fun release() {
        stopListening()
        model?.close()
        model = null
        isModelReady = false
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            hypothesis?.let {
                try {
                    val json = JSONObject(it)
                    val partial = json.optString("partial", "")
                    if (partial.isNotEmpty()) {
                        onPartialResultListener?.invoke(partial)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing partial result", e)
                }
            }
        }

        override fun onResult(hypothesis: String?) {
            hypothesis?.let {
                try {
                    val json = JSONObject(it)
                    val text = json.optString("text", "")
                    if (text.isNotEmpty()) {
                        onResultListener?.invoke(text)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing result", e)
                }
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            hypothesis?.let {
                try {
                    val json = JSONObject(it)
                    val text = json.optString("text", "")
                    if (text.isNotEmpty()) {
                        onResultListener?.invoke(text)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing final result", e)
                }
            }
        }

        override fun onError(exception: Exception?) {
            Log.e(TAG, "Recognition error", exception)
            onErrorListener?.invoke(exception?.message ?: "Recognition error")
        }

        override fun onTimeout() {
            Log.d(TAG, "Recognition timeout")
            stopListening()
        }
    }

    /**
     * Clears the configured zip file and optionally deletes extracted model.
     */
    fun clearZipConfig(deleteExtracted: Boolean = true) {
        release()
        SettingsManager.setVoskModelPath(context, null)
        if (deleteExtracted) {
            deleteExtractedModel()
        }
        Log.d(TAG, "Zip config cleared")
    }

    /**
     * Reloads the model (useful after changing the model).
     */
    fun reloadModel(onReady: ((Boolean, String?) -> Unit)? = null) {
        release()
        initModel(onReady)
    }

    enum class ModelStatus {
        NOT_CONFIGURED,  // No zip file configured
        ZIP_CONFIGURED,  // Zip file set but not extracted yet
        EXTRACTING,      // Zip is being extracted
        AVAILABLE,       // Model extracted and ready to load
        LOADING,         // Model is being loaded into memory
        READY            // Model loaded and ready for use
    }
}
