package it.neuralrad.coolwulf.inputmethod

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import it.neuralrad.coolwulf.R
import it.neuralrad.coolwulf.SettingsManager

/**
 * Activity for offline Mandarin Chinese speech recognition using Sherpa-ONNX.
 * Uses Paraformer models for better accuracy.
 */
class SherpaSpeechActivity : Activity() {
    companion object {
        private const val TAG = "SherpaSpeechActivity"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 1
        const val ACTION_SHERPA_SPEECH_RESULT = "it.neuralrad.coolwulf.SHERPA_SPEECH_RESULT"
        const val EXTRA_TEXT = "text"
    }

    private lateinit var sherpaRecognizer: SherpaSpeechRecognizer

    private lateinit var statusText: TextView
    private lateinit var recognizedText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var cancelButton: Button
    private lateinit var listeningIndicator: View

    private var isListening = false
    private var currentText = StringBuilder()

    // Punctuation tracking - detect pauses using partial result timing
    private val handler = Handler(Looper.getMainLooper())
    private var pauseDetected = false
    private var hasReceivedPartial = false
    private val PAUSE_THRESHOLD_MS = 500L  // 0.5 second pause threshold
    private var pauseRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            sherpaRecognizer = SherpaSpeechRecognizer.getInstance(this)

            createUI()

            // Set window layout AFTER setContentView
            window?.let { win ->
                win.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                win.setGravity(Gravity.BOTTOM)
            }

            checkPermissionAndSetup()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            android.widget.Toast.makeText(
                this,
                "Error: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun createUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFF1A1A1A.toInt())
        }

        // Status text
        statusText = TextView(this).apply {
            text = getString(R.string.sherpa_initializing)
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        layout.addView(statusText)

        // Progress bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 24
            }
        }
        layout.addView(progressBar)

        // Listening indicator (pulsing dot)
        listeningIndicator = View(this).apply {
            setBackgroundColor(0xFFFF4444.toInt())
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(24, 24).apply {
                gravity = Gravity.CENTER
                bottomMargin = 16
            }
        }
        layout.addView(listeningIndicator)

        // Recognized text display
        recognizedText = TextView(this).apply {
            text = ""
            textSize = 20f
            setTextColor(0xFF4CAF50.toInt())
            gravity = Gravity.CENTER
            minHeight = 100
            setPadding(0, 16, 0, 16)
        }
        layout.addView(recognizedText)

        // Button container
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }

        // Start button
        startButton = Button(this).apply {
            text = getString(R.string.sherpa_start_listening)
            visibility = View.GONE
            setOnClickListener { startListening() }
        }
        buttonLayout.addView(startButton)

        // Stop button
        stopButton = Button(this).apply {
            text = getString(R.string.sherpa_stop_send)
            visibility = View.GONE
            setOnClickListener { stopAndSend() }
        }
        buttonLayout.addView(stopButton)

        // Cancel button
        cancelButton = Button(this).apply {
            text = getString(R.string.cancel)
            setOnClickListener { finish() }
        }
        buttonLayout.addView(cancelButton)

        layout.addView(buttonLayout)

        setContentView(layout)
    }

    private fun checkPermissionAndSetup() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_RECORD_AUDIO
            )
        } else {
            setupSherpa()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupSherpa()
            } else {
                statusText.text = getString(R.string.sherpa_permission_denied)
            }
        }
    }

    private fun setupSherpa() {
        when (sherpaRecognizer.getModelStatus()) {
            SherpaSpeechRecognizer.ModelStatus.NOT_CONFIGURED -> {
                // No model configured - tell user to set up in settings
                statusText.text = getString(R.string.sherpa_model_not_configured)
                startButton.isEnabled = false
            }
            SherpaSpeechRecognizer.ModelStatus.ZIP_CONFIGURED -> {
                // Zip file is configured but not extracted - auto extract
                extractModel()
            }
            SherpaSpeechRecognizer.ModelStatus.EXTRACTING -> {
                statusText.text = getString(R.string.sherpa_extracting)
                progressBar.visibility = View.VISIBLE
                startButton.isEnabled = false
            }
            SherpaSpeechRecognizer.ModelStatus.LOADING -> {
                statusText.text = getString(R.string.sherpa_loading_model)
                progressBar.visibility = View.VISIBLE
                startButton.isEnabled = false
            }
            SherpaSpeechRecognizer.ModelStatus.AVAILABLE -> {
                initializeModel()
            }
            SherpaSpeechRecognizer.ModelStatus.READY -> {
                showReadyState()
            }
        }
    }

    private fun extractModel() {
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        statusText.text = getString(R.string.sherpa_extracting)
        startButton.isEnabled = false

        sherpaRecognizer.extractZipModel(
            onProgress = { progress, message ->
                runOnUiThread {
                    progressBar.progress = progress
                    statusText.text = message
                }
            },
            onComplete = { success, error ->
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (success) {
                        initializeModel()
                    } else {
                        statusText.text = getString(R.string.sherpa_extract_failed, error ?: "Unknown error")
                        startButton.isEnabled = false
                    }
                }
            }
        )
    }

    private fun initializeModel() {
        statusText.text = getString(R.string.sherpa_loading_model)

        sherpaRecognizer.initModel { success, error ->
            runOnUiThread {
                if (success) {
                    showReadyState()
                } else {
                    statusText.text = getString(R.string.sherpa_model_load_failed, error ?: "Unknown error")
                }
            }
        }
    }

    private fun showReadyState() {
        // Check if native library is available
        if (!SherpaSpeechRecognizer.isLibraryAvailable()) {
            statusText.text = getString(R.string.sherpa_library_not_available)
            startButton.visibility = View.VISIBLE
            startButton.isEnabled = false
            return
        }

        statusText.text = getString(R.string.sherpa_ready)
        startButton.visibility = View.VISIBLE
        // Auto-start listening
        startListening()
    }

    private fun startListening() {
        if (isListening) return

        // Check if native library is available
        if (!SherpaSpeechRecognizer.isLibraryAvailable()) {
            statusText.text = getString(R.string.sherpa_library_not_available)
            return
        }

        try {
            isListening = true
            currentText.clear()
            pauseDetected = false
            hasReceivedPartial = false
            pauseRunnable?.let { handler.removeCallbacks(it) }
            recognizedText.text = ""
            statusText.text = getString(R.string.sherpa_listening)
            startButton.visibility = View.GONE
            stopButton.visibility = View.VISIBLE
            listeningIndicator.visibility = View.VISIBLE

            // Animate listening indicator
            animateListeningIndicator()

            // Check if auto-insert is enabled
            val autoInsertEnabled = SettingsManager.isVoiceAutoInsert(this)
            val addPunctuation = SettingsManager.isVoiceAddPunctuation(this)
            val useChinesePunctuation = SettingsManager.isVoiceChinesePunctuation(this)
            val comma = if (useChinesePunctuation) "，" else ","

            sherpaRecognizer.startListening(
                onResult = { text ->
                    runOnUiThread {
                        if (text.isNotEmpty()) {
                            // Cancel any pending pause detection
                            pauseRunnable?.let { handler.removeCallbacks(it) }

                            // If we detected a pause and have existing text, insert comma
                            if (addPunctuation && pauseDetected && currentText.isNotEmpty()) {
                                currentText.append(comma)
                            }

                            currentText.append(text)
                            recognizedText.text = currentText.toString()

                            // Reset pause state and start watching for next pause
                            pauseDetected = false
                            hasReceivedPartial = false

                            // Start timer to detect pause after this result
                            if (addPunctuation) {
                                pauseRunnable = Runnable {
                                    if (isListening && currentText.isNotEmpty()) {
                                        pauseDetected = true
                                    }
                                }
                                handler.postDelayed(pauseRunnable!!, PAUSE_THRESHOLD_MS)
                            }
                        }
                    }
                },
                onPartialResult = { partial ->
                    runOnUiThread {
                        // Cancel pending pause detection when we get partial results
                        pauseRunnable?.let { handler.removeCallbacks(it) }

                        // If pause was detected before this partial, mark for comma insertion
                        if (addPunctuation && pauseDetected && currentText.isNotEmpty() && partial.isNotEmpty()) {
                            // Pause was detected, new speech started - insert comma now
                            currentText.append(comma)
                            pauseDetected = false
                            recognizedText.text = "${currentText}$partial"
                        } else {
                            val displayText = if (currentText.isEmpty()) {
                                partial
                            } else {
                                "${currentText}$partial"
                            }
                            recognizedText.text = displayText
                        }

                        hasReceivedPartial = partial.isNotEmpty()

                        // Restart pause detection timer
                        if (addPunctuation && partial.isNotEmpty()) {
                            pauseRunnable = Runnable {
                                if (isListening && (currentText.isNotEmpty() || hasReceivedPartial)) {
                                    pauseDetected = true
                                }
                            }
                            handler.postDelayed(pauseRunnable!!, PAUSE_THRESHOLD_MS)
                        }
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        statusText.text = getString(R.string.sherpa_error, error)
                        stopListeningUI()
                    }
                },
                onSilenceDetected = if (autoInsertEnabled) {
                    {
                        runOnUiThread {
                            Log.d(TAG, "Silence detected - auto-sending")
                            stopAndSend()
                        }
                    }
                } else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting listening", e)
            runOnUiThread {
                statusText.text = getString(R.string.sherpa_error, e.message ?: "Unknown error")
                stopListeningUI()
            }
        }
    }

    private fun animateListeningIndicator() {
        listeningIndicator.animate()
            .alpha(0.3f)
            .setDuration(500)
            .withEndAction {
                if (isListening) {
                    listeningIndicator.animate()
                        .alpha(1f)
                        .setDuration(500)
                        .withEndAction {
                            if (isListening) animateListeningIndicator()
                        }
                }
            }
    }

    private fun stopAndSend() {
        statusText.text = getString(R.string.sherpa_processing)
        stopButton.isEnabled = false

        sherpaRecognizer.stopListening { finalText ->
            stopListeningUI()

            // Combine any partial results with the final recognition
            val combinedText = if (currentText.isNotEmpty() && finalText.isEmpty()) {
                currentText.toString()
            } else {
                finalText
            }

            // Keep the text as-is since user may speak English which needs spaces
            var resultText = combinedText.trim()

            // Add period at the end if punctuation is enabled
            if (resultText.isNotEmpty() && SettingsManager.isVoiceAddPunctuation(this)) {
                val useChinesePunctuation = SettingsManager.isVoiceChinesePunctuation(this)
                val period = if (useChinesePunctuation) "。" else ". "

                // Only add period if text doesn't already end with punctuation
                val lastChar = resultText.lastOrNull()
                val isPunctuation = lastChar in listOf('。', '，', '！', '？', '、', '.', ',', '!', '?', '：', ':')
                if (!isPunctuation) {
                    resultText += period
                }
            }

            if (resultText.isNotEmpty()) {
                // Send result via broadcast
                val broadcastIntent = Intent(ACTION_SHERPA_SPEECH_RESULT).apply {
                    putExtra(EXTRA_TEXT, resultText)
                    setPackage(packageName)
                }
                sendBroadcast(broadcastIntent)
                Log.d(TAG, "Broadcast sent with text: $resultText")
            }

            finish()
        }
    }

    private fun stopListeningUI() {
        isListening = false
        pauseRunnable?.let { handler.removeCallbacks(it) }
        pauseRunnable = null
        startButton.visibility = View.VISIBLE
        stopButton.visibility = View.GONE
        listeningIndicator.visibility = View.GONE
        listeningIndicator.clearAnimation()
        statusText.text = getString(R.string.sherpa_ready)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isListening) {
            sherpaRecognizer.stopListening()
        }
    }

    override fun onBackPressed() {
        if (isListening) {
            sherpaRecognizer.stopListening()
        }
        super.onBackPressed()
    }
}
