package it.neuralrad.coolwulf.inputmethod

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
 * Activity for offline Mandarin Chinese speech recognition using Vosk.
 * Extracts bundled model on first use, then provides continuous recognition.
 */
class VoskSpeechActivity : Activity() {
    companion object {
        private const val TAG = "VoskSpeechActivity"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 1
        const val ACTION_VOSK_SPEECH_RESULT = "it.neuralrad.coolwulf.VOSK_SPEECH_RESULT"
        const val EXTRA_TEXT = "text"
    }

    private lateinit var voskRecognizer: VoskSpeechRecognizer

    private lateinit var statusText: TextView
    private lateinit var recognizedText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var cancelButton: Button
    private lateinit var listeningIndicator: View

    private var isListening = false
    private var currentText = StringBuilder()

    // Punctuation tracking
    private var lastResultTime = 0L
    private val PAUSE_THRESHOLD_MS = 800L  // Pause longer than this inserts comma

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // Check if Vosk library is available first
            if (!VoskSpeechRecognizer.isLibraryAvailable()) {
                Log.e(TAG, "Vosk library not available on this device")
                android.widget.Toast.makeText(
                    this,
                    "Offline voice not available on this device",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                finish()
                return
            }

            voskRecognizer = VoskSpeechRecognizer.getInstance(this)

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
            text = getString(R.string.vosk_initializing)
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
            text = getString(R.string.vosk_start_listening)
            visibility = View.GONE
            setOnClickListener { startListening() }
        }
        buttonLayout.addView(startButton)

        // Stop button
        stopButton = Button(this).apply {
            text = getString(R.string.vosk_stop_send)
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
            setupVosk()
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
                setupVosk()
            } else {
                statusText.text = getString(R.string.vosk_permission_denied)
            }
        }
    }

    private fun setupVosk() {
        when (voskRecognizer.getModelStatus()) {
            VoskSpeechRecognizer.ModelStatus.NOT_CONFIGURED -> {
                // No model configured - tell user to set up in settings
                statusText.text = getString(R.string.vosk_model_not_configured)
                startButton.isEnabled = false
            }
            VoskSpeechRecognizer.ModelStatus.ZIP_CONFIGURED -> {
                // Zip file is configured but not extracted - auto extract
                extractModel()
            }
            VoskSpeechRecognizer.ModelStatus.EXTRACTING -> {
                statusText.text = getString(R.string.vosk_extracting)
                progressBar.visibility = View.VISIBLE
                startButton.isEnabled = false
            }
            VoskSpeechRecognizer.ModelStatus.LOADING -> {
                statusText.text = getString(R.string.vosk_loading_model)
                progressBar.visibility = View.VISIBLE
                startButton.isEnabled = false
            }
            VoskSpeechRecognizer.ModelStatus.AVAILABLE -> {
                initializeModel()
            }
            VoskSpeechRecognizer.ModelStatus.READY -> {
                showReadyState()
            }
        }
    }

    private fun extractModel() {
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        statusText.text = getString(R.string.vosk_extracting)
        startButton.isEnabled = false

        voskRecognizer.extractZipModel(
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
                        statusText.text = getString(R.string.vosk_extract_failed, error ?: "Unknown error")
                        startButton.isEnabled = false
                    }
                }
            }
        )
    }

    private fun initializeModel() {
        statusText.text = getString(R.string.vosk_loading_model)

        voskRecognizer.initModel { success, error ->
            runOnUiThread {
                if (success) {
                    showReadyState()
                } else {
                    statusText.text = getString(R.string.vosk_model_load_failed, error ?: "Unknown error")
                }
            }
        }
    }

    private fun showReadyState() {
        statusText.text = getString(R.string.vosk_ready)
        startButton.visibility = View.VISIBLE
        // Auto-start listening
        startListening()
    }

    private fun startListening() {
        if (isListening) return

        try {
            isListening = true
            currentText.clear()
            lastResultTime = 0L
            recognizedText.text = ""
            statusText.text = getString(R.string.vosk_listening)
            startButton.visibility = View.GONE
            stopButton.visibility = View.VISIBLE
            listeningIndicator.visibility = View.VISIBLE

            // Animate listening indicator
            animateListeningIndicator()

            val addPunctuation = SettingsManager.isVoiceAddPunctuation(this)
            val useChinesePunctuation = SettingsManager.isVoiceChinesePunctuation(this)
            val comma = if (useChinesePunctuation) "，" else ", "

            voskRecognizer.startListening(
            onResult = { text ->
                runOnUiThread {
                    Log.d(TAG, "Result: $text")
                    if (text.isNotEmpty()) {
                        val currentTime = System.currentTimeMillis()

                        // Check for pause and insert comma if needed
                        if (addPunctuation && currentText.isNotEmpty() && lastResultTime > 0) {
                            val pauseDuration = currentTime - lastResultTime
                            if (pauseDuration > PAUSE_THRESHOLD_MS) {
                                // Insert comma for significant pause
                                currentText.append(comma)
                                Log.d(TAG, "Inserted comma due to pause of ${pauseDuration}ms")
                            }
                        }

                        currentText.append(text)
                        lastResultTime = currentTime
                        recognizedText.text = currentText.toString()
                    }
                }
            },
            onPartialResult = { partial ->
                runOnUiThread {
                    Log.d(TAG, "Partial: $partial")
                    val displayText = if (currentText.isEmpty()) {
                        partial
                    } else {
                        "${currentText}$partial"
                    }
                    recognizedText.text = displayText
                }
            },
            onError = { error ->
                runOnUiThread {
                    statusText.text = getString(R.string.vosk_error, error)
                    stopListeningUI()
                }
            }
        )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting listening", e)
            runOnUiThread {
                statusText.text = getString(R.string.vosk_error, e.message ?: "Unknown error")
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
        voskRecognizer.stopListening()
        stopListeningUI()

        // Remove all spaces from Chinese text (Chinese doesn't use spaces between words)
        var resultText = currentText.toString().replace(" ", "").trim()

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
            val broadcastIntent = Intent(ACTION_VOSK_SPEECH_RESULT).apply {
                putExtra(EXTRA_TEXT, resultText)
                setPackage(packageName)
            }
            sendBroadcast(broadcastIntent)
            Log.d(TAG, "Broadcast sent with text: $resultText")
        }

        finish()
    }

    private fun stopListeningUI() {
        isListening = false
        startButton.visibility = View.VISIBLE
        stopButton.visibility = View.GONE
        listeningIndicator.visibility = View.GONE
        listeningIndicator.clearAnimation()
        statusText.text = getString(R.string.vosk_ready)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isListening) {
            voskRecognizer.stopListening()
        }
    }

    override fun onBackPressed() {
        if (isListening) {
            voskRecognizer.stopListening()
        }
        super.onBackPressed()
    }
}
