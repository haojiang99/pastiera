package it.neuralrad.coolwulf.inputmethod

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.*

/**
 * Offline Mandarin Chinese speech recognition using Vosk.
 * The Chinese model is bundled in assets and copied to filesystem on first use.
 */
class VoskSpeechRecognizer(private val context: Context) {
    companion object {
        private const val TAG = "VoskSpeechRecognizer"
        private const val MODEL_NAME = "vosk-model-small-cn-0.22"
        private const val ASSETS_MODEL_PATH = "vosk-models/$MODEL_NAME"
        private const val SAMPLE_RATE = 16000.0f

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

    // Callbacks
    private var onResultListener: ((String) -> Unit)? = null
    private var onPartialResultListener: ((String) -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var onModelLoadProgressListener: ((Int, String) -> Unit)? = null

    private val modelDir: File
        get() = File(context.filesDir, "vosk-models/$MODEL_NAME")

    /**
     * Checks if the Chinese model is downloaded and ready.
     * Checks for common model files that indicate a valid Vosk model.
     */
    fun isModelAvailable(): Boolean {
        if (!modelDir.exists()) return false
        // Check for common files in Vosk models (different models may have different structures)
        val possibleFiles = listOf(
            File(modelDir, "am/final.mdl"),      // Standard Kaldi model
            File(modelDir, "graph/Gr.fst"),       // Graph file
            File(modelDir, "conf/model.conf"),    // Config file
            File(modelDir, "ivector/final.ie")    // iVector extractor
        )
        // Model is available if at least one key file exists
        return possibleFiles.any { it.exists() }
    }

    /**
     * Gets the model status.
     */
    fun getModelStatus(): ModelStatus {
        return when {
            isModelReady -> ModelStatus.READY
            isModelLoading -> ModelStatus.EXTRACTING
            isModelAvailable() -> ModelStatus.AVAILABLE
            else -> ModelStatus.NOT_EXTRACTED
        }
    }

    /**
     * Checks if the model is bundled in assets.
     */
    private fun isModelBundled(): Boolean {
        return try {
            val files = context.assets.list(ASSETS_MODEL_PATH)
            files != null && files.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extracts the bundled model from assets to filesystem.
     * Vosk requires a filesystem path, so we need to copy from assets.
     */
    fun extractBundledModel(
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        if (isModelAvailable()) {
            onComplete?.invoke(true, null)
            return
        }

        if (isModelLoading) {
            onComplete?.invoke(false, "Extraction already in progress")
            return
        }

        if (!isModelBundled()) {
            onComplete?.invoke(false, "Model not bundled in app")
            return
        }

        isModelLoading = true
        onModelLoadProgressListener = onProgress

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(0, "Extracting model...")
                }

                // Create model directory
                modelDir.mkdirs()

                // Copy all files from assets to filesystem
                copyAssetsFolder(ASSETS_MODEL_PATH, modelDir) { progress ->
                    CoroutineScope(Dispatchers.Main).launch {
                        onProgress?.invoke(progress, "Extracting model ($progress%)...")
                    }
                }

                withContext(Dispatchers.Main) {
                    onProgress?.invoke(100, "Model ready!")
                    isModelLoading = false
                    onComplete?.invoke(true, null)
                }

                Log.d(TAG, "Model extracted from assets successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Error extracting model from assets", e)
                withContext(Dispatchers.Main) {
                    isModelLoading = false
                    onComplete?.invoke(false, e.message ?: "Extraction failed")
                }
            }
        }
    }

    /**
     * Recursively copies a folder from assets to the filesystem.
     */
    private fun copyAssetsFolder(assetsPath: String, destDir: File, onProgress: (Int) -> Unit) {
        val assetManager = context.assets

        // First, count total files for progress
        val allFiles = mutableListOf<String>()
        collectAssetFiles(assetsPath, allFiles)
        val totalFiles = allFiles.size
        var copiedFiles = 0

        // Copy each file
        for (filePath in allFiles) {
            val relativePath = filePath.removePrefix("$assetsPath/")
            val destFile = File(destDir, relativePath)

            destFile.parentFile?.mkdirs()

            assetManager.open(filePath).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            copiedFiles++
            val progress = (copiedFiles * 100 / totalFiles).coerceIn(0, 99)
            onProgress(progress)
        }
    }

    /**
     * Recursively collects all file paths in an assets folder.
     */
    private fun collectAssetFiles(path: String, fileList: MutableList<String>) {
        val assetManager = context.assets
        val files = assetManager.list(path) ?: return

        for (file in files) {
            val fullPath = "$path/$file"
            val subFiles = assetManager.list(fullPath)

            if (subFiles.isNullOrEmpty()) {
                // It's a file
                fileList.add(fullPath)
            } else {
                // It's a directory, recurse
                collectAssetFiles(fullPath, fileList)
            }
        }
    }

    /**
     * Initializes the Vosk model. Must be called before starting recognition.
     */
    fun initModel(onReady: ((Boolean, String?) -> Unit)? = null) {
        if (isModelReady && model != null) {
            onReady?.invoke(true, null)
            return
        }

        if (!isModelAvailable()) {
            onReady?.invoke(false, "Model not downloaded")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Loading Vosk model from: ${modelDir.absolutePath}")
                model = Model(modelDir.absolutePath)
                isModelReady = true

                withContext(Dispatchers.Main) {
                    onReady?.invoke(true, null)
                }
                Log.d(TAG, "Vosk model loaded successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
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
     * Deletes the extracted model to free up space.
     */
    fun deleteModel(): Boolean {
        return try {
            release()
            modelDir.deleteRecursively()
            Log.d(TAG, "Model deleted")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting model", e)
            false
        }
    }

    enum class ModelStatus {
        NOT_EXTRACTED,
        EXTRACTING,
        AVAILABLE,
        READY
    }
}
