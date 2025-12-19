package it.neuralrad.coolwulf.inputmethod.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import it.neuralrad.coolwulf.SettingsManager
import it.neuralrad.coolwulf.R

/**
 * Virtual (on-screen) keyboard for devices without physical keyboards.
 * Provides a standard QWERTY layout with shift, backspace, enter, and space keys.
 */
class VirtualKeyboardView(
    private val context: Context,
    private val onKeyPress: (keyCode: Int, isShifted: Boolean) -> Unit,
    private val onCharacterInput: (char: Char) -> Unit,
    private val onVoiceInputRequest: (() -> Unit)? = null,
    private val onShiftStateChanged: ((isShifted: Boolean, isCapsLock: Boolean) -> Unit)? = null,
    private val onCtrlKeyPress: ((keyCode: Int) -> Unit)? = null
) {
    companion object {
        private val KEY_BG_COLOR = Color.argb(255, 60, 60, 65)
        private val KEY_BG_PRESSED = Color.argb(255, 100, 100, 110)
        private val KEY_BG_SPECIAL = Color.argb(255, 45, 45, 50)
        private val KEY_TEXT_COLOR = Color.WHITE
        private val KEYBOARD_BG_COLOR = Color.argb(255, 30, 30, 35)
        private const val SEMI_TRANSPARENT_ALPHA = 0.4f  // 40% opacity for entire UI
        private const val SOUND_COUNT = 24

        // QWERTY layout rows
        private val ROW_1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        private val ROW_2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        private val ROW_3 = listOf("z", "x", "c", "v", "b", "n", "m")

        // Alt mode symbol mappings - numbers on top row
        private val ALT_ROW_1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        private val ALT_ROW_2 = listOf("@", "#", "$", "%", "&", "*", "(", ")", "_")
        private val ALT_ROW_3 = listOf("-", "+", "=", ":", ";", "/", "?")
    }

    private var container: LinearLayout? = null
    private var isShifted = false
    private var isCapsLock = false
    private var isAltMode = false
    private var isAltLocked = false
    private var isCtrlActive = false
    private var shiftKey: TextView? = null
    private var altKey: TextView? = null
    private var ctrlKey: TextView? = null
    private var useChinesePunctuation = false

    private val keyHeight: Int
        get() = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            SettingsManager.getVirtualKeyboardHeight(context).toFloat(),
            context.resources.displayMetrics
        ).toInt()

    private val keyMargin: Int by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()
    }

    private val cornerRadius: Float by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            5f,
            context.resources.displayMetrics
        )
    }

    private val keyTextSize: Float by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            18f,
            context.resources.displayMetrics
        )
    }

    private val specialKeyTextSize: Float by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            context.resources.displayMetrics
        )
    }

    private val symbolKeyTextSize: Float by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            20f,
            context.resources.displayMetrics
        )
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val soundPool: SoundPool by lazy {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(audioAttributes)
            .build()
    }

    private var keyClickSoundId: Int = 0
    private var soundLoaded = false
    private var loadedSoundType: String = ""

    // Multi-sound IDs (24 different key sounds for variety)
    private var multiSoundIds: IntArray = IntArray(24)
    private var multiSoundsLoaded = false
    private val random = java.util.Random()

    // Custom sound support
    private var customSoundId: Int = 0
    private var customSoundLoaded = false
    private var loadedCustomSoundPath: String? = null

    private fun ensureSoundLoaded() {
        val soundType = SettingsManager.getKeyboardSoundType(context)
        val customSoundPath = SettingsManager.getCustomSoundPath(context)

        // If sound type changed, reload the sound
        if (loadedSoundType != soundType) {
            soundLoaded = false
            multiSoundsLoaded = false
            customSoundLoaded = false
            keyClickSoundId = 0
            customSoundId = 0
            multiSoundIds = IntArray(SOUND_COUNT)
        }

        // If custom sound path changed, reload custom sound
        if (soundType == "custom" && loadedCustomSoundPath != customSoundPath) {
            customSoundLoaded = false
            customSoundId = 0
        }

        // Handle custom sound type
        if (soundType == "custom") {
            if (!customSoundLoaded && customSoundId == 0 && customSoundPath != null) {
                try {
                    customSoundId = soundPool.load(customSoundPath, 1)
                    loadedSoundType = soundType
                    loadedCustomSoundPath = customSoundPath
                    soundPool.setOnLoadCompleteListener { _, _, status ->
                        if (status == 0) {
                            customSoundLoaded = true
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VirtualKeyboardView", "Failed to load custom sound", e)
                }
            }
            return
        }

        // Multi-sound types: bucklespring, video, mario, piano (24 sounds each)
        val multiSoundResources = when (soundType) {
            "bucklespring" -> intArrayOf(
                R.raw.buckle_10, R.raw.buckle_11, R.raw.buckle_12, R.raw.buckle_13,
                R.raw.buckle_14, R.raw.buckle_15, R.raw.buckle_16, R.raw.buckle_17,
                R.raw.buckle_18, R.raw.buckle_19
            )
            "video" -> intArrayOf(
                R.raw.video_1, R.raw.video_2, R.raw.video_3, R.raw.video_4,
                R.raw.video_5, R.raw.video_6, R.raw.video_7, R.raw.video_8,
                R.raw.video_9, R.raw.video_10, R.raw.video_11, R.raw.video_12,
                R.raw.video_13, R.raw.video_14, R.raw.video_15, R.raw.video_16,
                R.raw.video_17, R.raw.video_18, R.raw.video_19, R.raw.video_20,
                R.raw.video_21, R.raw.video_22, R.raw.video_23, R.raw.video_24
            )
            "mario" -> intArrayOf(
                R.raw.mario_1, R.raw.mario_2, R.raw.mario_3, R.raw.mario_4,
                R.raw.mario_5, R.raw.mario_6, R.raw.mario_7, R.raw.mario_8,
                R.raw.mario_9, R.raw.mario_10, R.raw.mario_11, R.raw.mario_12,
                R.raw.mario_13, R.raw.mario_14, R.raw.mario_15, R.raw.mario_16,
                R.raw.mario_17, R.raw.mario_18, R.raw.mario_19, R.raw.mario_20,
                R.raw.mario_21, R.raw.mario_22, R.raw.mario_23, R.raw.mario_24
            )
            "piano" -> intArrayOf(
                R.raw.piano_1, R.raw.piano_2, R.raw.piano_3, R.raw.piano_4,
                R.raw.piano_5, R.raw.piano_6, R.raw.piano_7, R.raw.piano_8,
                R.raw.piano_9, R.raw.piano_10, R.raw.piano_11, R.raw.piano_12,
                R.raw.piano_13, R.raw.piano_14, R.raw.piano_15, R.raw.piano_16,
                R.raw.piano_17, R.raw.piano_18, R.raw.piano_19, R.raw.piano_20,
                R.raw.piano_21, R.raw.piano_22, R.raw.piano_23, R.raw.piano_24
            )
            else -> null
        }

        if (multiSoundResources != null) {
            if (!multiSoundsLoaded && multiSoundIds[0] == 0) {
                var loadedCount = 0
                val expectedCount = multiSoundResources.size
                multiSoundResources.forEachIndexed { index, res ->
                    multiSoundIds[index] = soundPool.load(context, res, 1)
                }
                loadedSoundType = soundType
                soundPool.setOnLoadCompleteListener { _, _, status ->
                    if (status == 0) {
                        loadedCount++
                        if (loadedCount >= expectedCount) {
                            multiSoundsLoaded = true
                        }
                    }
                }
            }
        } else {
            // Single sound: mechanical
            if (!soundLoaded && keyClickSoundId == 0) {
                keyClickSoundId = soundPool.load(context, R.raw.key_click, 1)
                loadedSoundType = soundType
                soundPool.setOnLoadCompleteListener { _, _, status ->
                    if (status == 0) {
                        soundLoaded = true
                    }
                }
            }
        }
    }

    private fun playKeySound() {
        val soundType = SettingsManager.getKeyboardSoundType(context)
        val volume = getKeyboardSoundVolume()

        // Handle custom sound
        if (soundType == "custom" && customSoundLoaded && customSoundId != 0) {
            soundPool.play(customSoundId, volume, volume, 1, 0, 1.0f)
            return
        }

        val isMultiSound = soundType in listOf("bucklespring", "video", "mario", "piano")

        if (isMultiSound && multiSoundsLoaded) {
            // Pick a random sound for variety - bucklespring has 10 sounds, others have 24
            val count = if (soundType == "bucklespring") 10 else SOUND_COUNT
            val soundId = multiSoundIds[random.nextInt(count)]
            if (soundId != 0) {
                soundPool.play(soundId, volume, volume, 1, 0, 1.0f)
            }
        } else if (soundLoaded && keyClickSoundId != 0) {
            soundPool.play(keyClickSoundId, volume, volume, 1, 0, 1.0f)
        }
    }

    private fun getKeyboardSoundVolume(): Float {
        // Get user-configured volume (0-100) and convert to 0.0-1.0
        val userVolume = SettingsManager.getKeyboardSoundVolume(context) / 100f
        // Apply system media volume on top
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        val systemVolume = if (maxVolume > 0) currentVolume / maxVolume else 0.5f
        return userVolume * systemVolume
    }

    /**
     * Plays key press feedback (sound and/or vibration) based on settings.
     */
    @Suppress("DEPRECATION")
    private fun playKeyPressFeedback(view: View) {
        // Play vibration if enabled - use same approach as VariationBarView for suggestion selection
        if (SettingsManager.isVirtualKeyboardVibrationEnabled(context)) {
            view.isHapticFeedbackEnabled = true
            view.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
        // Play sound if enabled
        if (SettingsManager.isKeyboardSoundEnabled(context)) {
            ensureSoundLoaded()
            playKeySound()
        }
    }

    /**
     * Plays long press feedback (sound and/or vibration) based on settings.
     */
    @Suppress("DEPRECATION")
    private fun playLongPressFeedback(view: View) {
        // Play vibration if enabled - use same approach as VariationBarView for suggestion selection
        if (SettingsManager.isVirtualKeyboardVibrationEnabled(context)) {
            view.isHapticFeedbackEnabled = true
            view.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
        // Play sound if enabled
        if (SettingsManager.isKeyboardSoundEnabled(context)) {
            ensureSoundLoaded()
            playKeySound()
        }
    }

    fun ensureView(): LinearLayout {
        val isSemiTransparent = SettingsManager.isSemiTransparentStatusBar(context)

        container?.let {
            // Always apply transparency setting (check every time in case setting changed)
            it.alpha = if (isSemiTransparent) SEMI_TRANSPARENT_ALPHA else 1.0f
            return it
        }

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KEYBOARD_BG_COLOR)
            // Apply transparency to entire UI when setting is enabled, otherwise full opacity
            alpha = if (isSemiTransparent) SEMI_TRANSPARENT_ALPHA else 1.0f
            val padding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                4f,
                context.resources.displayMetrics
            ).toInt()
            setPadding(padding, padding, padding, padding)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Add QWERTY rows (no number row - use 123/Alt for numbers)
        container?.addView(createKeyRow(ROW_1))
        container?.addView(createKeyRow(ROW_2, sidePadding = true))
        container?.addView(createRow3WithShiftAndBackspace())
        container?.addView(createBottomRow())

        return container!!
    }

    fun getView(): LinearLayout? = container

    /**
     * Invalidates the cached view so it will be recreated on next ensureView() call.
     * Call this when settings that affect the keyboard layout change (e.g., key height).
     */
    fun invalidateView() {
        container = null
        shiftKey = null
        altKey = null
        ctrlKey = null
    }

    private fun createKeyRow(keys: List<String>, sidePadding: Boolean = false): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            if (sidePadding) {
                // Add side padding for row 2 (ASDF row)
                val sidePad = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    15f,
                    context.resources.displayMetrics
                ).toInt()
                setPadding(sidePad, 0, sidePad, 0)
            }

            keys.forEach { key ->
                addView(createCharacterKey(key))
            }
        }
    }

    private fun createRow3WithShiftAndBackspace(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Shift key
            shiftKey = createSymbolKey("⇧", 1.3f) {
                if (isCapsLock) {
                    // Disable caps lock
                    isCapsLock = false
                    isShifted = false
                } else if (isShifted) {
                    // Double tap - enable caps lock
                    isCapsLock = true
                } else {
                    // Single tap - enable shift
                    isShifted = true
                }
                updateShiftKeyAppearance()
                updateAllKeyLabels()
                onShiftStateChanged?.invoke(isShifted, isCapsLock)
            }
            addView(shiftKey)

            // Letter keys
            ROW_3.forEach { key ->
                addView(createCharacterKey(key))
            }

            // Backspace key with hold-to-repeat
            addView(createBackspaceKey())
        }
    }

    private fun createBottomRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Alt/Symbols toggle key (123 button)
            altKey = createAltKey()
            addView(altKey)

            // Comma key
            addView(createCharacterKey(",", 0.8f))

            // Space bar
            addView(createSpaceBar())

            // Period key
            addView(createCharacterKey(".", 0.8f))

            // Ctrl key
            ctrlKey = createCtrlKey()
            addView(ctrlKey)

            // Enter key
            addView(createSymbolKey("↵", 1.0f) {
                onKeyPress(KeyEvent.KEYCODE_ENTER, false)
            })
        }
    }

    private fun createAltKey(): TextView {
        return TextView(context).apply {
            text = "123"
            setTextColor(KEY_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, specialKeyTextSize)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createKeyBackground(KEY_BG_SPECIAL)

            layoutParams = LinearLayout.LayoutParams(
                0,
                keyHeight,
                1.2f
            ).apply {
                setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        playKeyPressFeedback(v)
                        background = createKeyBackground(KEY_BG_PRESSED)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isAltLocked) {
                            // Disable alt lock
                            isAltLocked = false
                            isAltMode = false
                        } else if (isAltMode) {
                            // Double tap - enable alt lock
                            isAltLocked = true
                        } else {
                            // Single tap - enable alt mode
                            isAltMode = true
                        }
                        updateAltKeyAppearance()
                        updateAllKeyLabels()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        background = createKeyBackground(
                            when {
                                isAltLocked -> Color.argb(255, 100, 150, 255)  // Blue for alt lock
                                isAltMode -> Color.argb(255, 80, 80, 90)       // Lighter for alt
                                else -> KEY_BG_SPECIAL
                            }
                        )
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun updateAltKeyAppearance() {
        altKey?.apply {
            text = when {
                isAltLocked -> "ABC"  // Show ABC when locked in alt mode
                isAltMode -> "ABC"    // Show ABC when alt active
                else -> "123"         // Normal
            }
            background = createKeyBackground(
                when {
                    isAltLocked -> Color.argb(255, 100, 150, 255)  // Blue for alt lock (same as caps lock)
                    isAltMode -> Color.argb(255, 80, 80, 90)       // Lighter for alt (same as shift)
                    else -> KEY_BG_SPECIAL
                }
            )
        }
    }

    private fun createCtrlKey(): TextView {
        return TextView(context).apply {
            text = "Ctrl"
            setTextColor(KEY_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, specialKeyTextSize)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createKeyBackground(KEY_BG_SPECIAL)

            layoutParams = LinearLayout.LayoutParams(
                0,
                keyHeight,
                1.0f
            ).apply {
                setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        playKeyPressFeedback(v)
                        background = createKeyBackground(KEY_BG_PRESSED)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        // Toggle Ctrl state (one-shot mode)
                        isCtrlActive = !isCtrlActive
                        updateCtrlKeyAppearance()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        background = createKeyBackground(
                            if (isCtrlActive) Color.argb(255, 80, 80, 90) else KEY_BG_SPECIAL
                        )
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun updateCtrlKeyAppearance() {
        ctrlKey?.apply {
            background = createKeyBackground(
                if (isCtrlActive) Color.argb(255, 80, 80, 90) else KEY_BG_SPECIAL
            )
        }
    }

    private fun createCharacterKey(char: String, weight: Float = 1f): TextView {
        return TextView(context).apply {
            val displayText = getDisplayText(char)
            text = displayText
            tag = char // Store original lowercase char
            setTextColor(KEY_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, keyTextSize)
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            background = createKeyBackground(KEY_BG_COLOR)

            layoutParams = LinearLayout.LayoutParams(
                0,
                keyHeight,
                weight
            ).apply {
                setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        playKeyPressFeedback(v)
                        background = createKeyBackground(KEY_BG_PRESSED)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        background = createKeyBackground(KEY_BG_COLOR)
                        val originalChar = (v.tag as String)

                        // Handle Ctrl+key combinations
                        if (isCtrlActive && originalChar.length == 1 && originalChar[0].isLetter()) {
                            val keyCode = getKeyCodeForChar(originalChar[0])
                            if (keyCode != null) {
                                onCtrlKeyPress?.invoke(keyCode)
                            }
                            // Clear Ctrl after use (one-shot)
                            isCtrlActive = false
                            updateCtrlKeyAppearance()
                        } else {
                            val outputText = getOutputText(originalChar)

                            // Output each character (for multi-char symbols like \\)
                            outputText.forEach { c ->
                                onCharacterInput(c)
                            }

                            // Clear shift after typing (unless caps lock)
                            if (isShifted && !isCapsLock) {
                                isShifted = false
                                updateShiftKeyAppearance()
                                updateAllKeyLabels()
                                onShiftStateChanged?.invoke(isShifted, isCapsLock)
                            }

                            // Clear alt mode after typing (unless alt locked)
                            if (isAltMode && !isAltLocked) {
                                isAltMode = false
                                updateAltKeyAppearance()
                                updateAllKeyLabels()
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        background = createKeyBackground(KEY_BG_COLOR)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun getDisplayText(char: String): String {
        if (isAltMode || isAltLocked) {
            val altChar = getAltSymbol(char)
            if (altChar != null) return altChar
        }
        // Show Chinese punctuation when in Chinese punctuation mode
        if (useChinesePunctuation) {
            when (char) {
                "." -> return "。"
                "," -> return "，"
            }
        }
        return if (isShifted || isCapsLock) char.uppercase() else char
    }

    private fun getOutputText(char: String): String {
        if (isAltMode || isAltLocked) {
            val altChar = getAltSymbol(char)
            if (altChar != null) return altChar
        }
        // Convert punctuation to Chinese if Chinese punctuation mode is enabled
        if (useChinesePunctuation) {
            when (char) {
                "." -> return "。"
                "," -> return "，"
            }
        }
        return if (isShifted || isCapsLock) char.uppercase() else char
    }

    private fun getAltSymbol(char: String): String? {
        // Check row 1 (QWERTY row -> numbers in alt mode)
        val row1Index = ROW_1.indexOf(char.lowercase())
        if (row1Index >= 0 && row1Index < ALT_ROW_1.size) {
            return ALT_ROW_1[row1Index]
        }

        // Check row 2 (ASDF row -> symbols in alt mode)
        val row2Index = ROW_2.indexOf(char.lowercase())
        if (row2Index >= 0 && row2Index < ALT_ROW_2.size) {
            return ALT_ROW_2[row2Index]
        }

        // Check row 3 (ZXCV row -> symbols in alt mode)
        val row3Index = ROW_3.indexOf(char.lowercase())
        if (row3Index >= 0 && row3Index < ALT_ROW_3.size) {
            return ALT_ROW_3[row3Index]
        }

        // Bottom row punctuation in alt mode
        if (char == ",") return "!"

        return null
    }

    private fun createSpecialKey(label: String, weight: Float, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(KEY_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, specialKeyTextSize)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createKeyBackground(KEY_BG_SPECIAL)

            layoutParams = LinearLayout.LayoutParams(
                0,
                keyHeight,
                weight
            ).apply {
                setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        playKeyPressFeedback(v)
                        background = createKeyBackground(KEY_BG_PRESSED)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        background = createKeyBackground(KEY_BG_SPECIAL)
                        onClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        background = createKeyBackground(KEY_BG_SPECIAL)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun createSymbolKey(label: String, weight: Float, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(KEY_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, symbolKeyTextSize)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createKeyBackground(KEY_BG_SPECIAL)

            layoutParams = LinearLayout.LayoutParams(
                0,
                keyHeight,
                weight
            ).apply {
                setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        playKeyPressFeedback(v)
                        background = createKeyBackground(KEY_BG_PRESSED)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        background = createKeyBackground(KEY_BG_SPECIAL)
                        onClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        background = createKeyBackground(KEY_BG_SPECIAL)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun createBackspaceKey(): TextView {
        var deleteRepeatHandler: Handler? = null
        var deleteRepeatRunnable: Runnable? = null
        var isHolding = false

        // Initial delay before repeat starts (ms)
        val initialDelay = 400L
        // Interval between repeats (ms)
        val repeatInterval = 50L

        return TextView(context).apply {
            text = "⌫"
            setTextColor(KEY_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, specialKeyTextSize)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createKeyBackground(KEY_BG_SPECIAL)

            layoutParams = LinearLayout.LayoutParams(
                0,
                keyHeight,
                1.3f
            ).apply {
                setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        playKeyPressFeedback(v)
                        background = createKeyBackground(KEY_BG_PRESSED)
                        isHolding = true

                        // Fire initial delete immediately
                        onKeyPress(KeyEvent.KEYCODE_DEL, false)

                        // Set up repeat handler
                        deleteRepeatHandler = Handler(Looper.getMainLooper())
                        deleteRepeatRunnable = object : Runnable {
                            override fun run() {
                                if (isHolding) {
                                    onKeyPress(KeyEvent.KEYCODE_DEL, false)
                                    deleteRepeatHandler?.postDelayed(this, repeatInterval)
                                }
                            }
                        }
                        // Start repeating after initial delay
                        deleteRepeatHandler?.postDelayed(deleteRepeatRunnable!!, initialDelay)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        isHolding = false
                        deleteRepeatHandler?.removeCallbacks(deleteRepeatRunnable ?: return@setOnTouchListener true)
                        deleteRepeatHandler = null
                        deleteRepeatRunnable = null
                        background = createKeyBackground(KEY_BG_SPECIAL)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        isHolding = false
                        deleteRepeatHandler?.removeCallbacks(deleteRepeatRunnable ?: return@setOnTouchListener true)
                        deleteRepeatHandler = null
                        deleteRepeatRunnable = null
                        background = createKeyBackground(KEY_BG_SPECIAL)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun createSpaceBar(): TextView {
        var spaceHoldHandler: Handler? = null
        var spaceHoldRunnable: Runnable? = null
        var spaceHoldTriggeredVoice = false

        return TextView(context).apply {
            text = "space"
            setTextColor(Color.argb(150, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, specialKeyTextSize)
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            background = createKeyBackground(KEY_BG_COLOR)

            layoutParams = LinearLayout.LayoutParams(
                0,
                keyHeight,
                4f
            ).apply {
                setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        playKeyPressFeedback(v)
                        background = createKeyBackground(KEY_BG_PRESSED)

                        // Start hold timer for voice input if enabled
                        if (SettingsManager.isHoldSpaceForVoice(context) && onVoiceInputRequest != null) {
                            spaceHoldTriggeredVoice = false
                            spaceHoldHandler = Handler(Looper.getMainLooper())
                            spaceHoldRunnable = Runnable {
                                if (!spaceHoldTriggeredVoice) {
                                    spaceHoldTriggeredVoice = true
                                    playLongPressFeedback(v)
                                    onVoiceInputRequest.invoke()
                                }
                            }
                            spaceHoldHandler?.postDelayed(spaceHoldRunnable!!, SettingsManager.getHoldSpaceDuration(context))
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        // Cancel hold timer
                        spaceHoldHandler?.removeCallbacks(spaceHoldRunnable ?: return@setOnTouchListener true)
                        spaceHoldHandler = null
                        spaceHoldRunnable = null

                        background = createKeyBackground(KEY_BG_COLOR)

                        // Only send space if voice input was not triggered
                        if (!spaceHoldTriggeredVoice) {
                            onKeyPress(KeyEvent.KEYCODE_SPACE, false)
                        }
                        spaceHoldTriggeredVoice = false
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // Cancel hold timer
                        spaceHoldHandler?.removeCallbacks(spaceHoldRunnable ?: return@setOnTouchListener true)
                        spaceHoldHandler = null
                        spaceHoldRunnable = null
                        spaceHoldTriggeredVoice = false

                        background = createKeyBackground(KEY_BG_COLOR)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun createKeyBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = this@VirtualKeyboardView.cornerRadius
        }
    }

    private fun updateShiftKeyAppearance() {
        shiftKey?.apply {
            text = when {
                isCapsLock -> "⇪"  // Caps lock indicator
                isShifted -> "⇧"   // Shift active
                else -> "⇧"        // Normal
            }
            background = createKeyBackground(
                when {
                    isCapsLock -> Color.argb(255, 100, 150, 255)  // Blue for caps lock
                    isShifted -> Color.argb(255, 80, 80, 90)      // Lighter for shift
                    else -> KEY_BG_SPECIAL
                }
            )
        }
    }

    private fun updateAllKeyLabels() {
        container?.let { cont ->
            updateKeyLabelsRecursive(cont)
        }
    }

    private fun updateKeyLabelsRecursive(viewGroup: LinearLayout) {
        for (i in 0 until viewGroup.childCount) {
            when (val child = viewGroup.getChildAt(i)) {
                is LinearLayout -> updateKeyLabelsRecursive(child)
                is TextView -> {
                    val tag = child.tag as? String
                    if (tag != null && tag.isNotEmpty()) {
                        // Skip special keys (shift, alt, backspace, enter, space)
                        if (child == shiftKey || child == altKey) continue

                        child.text = getDisplayText(tag)
                    }
                }
            }
        }
    }

    /**
     * Called to update keyboard state based on external shift state.
     */
    fun setShiftState(shifted: Boolean, capsLock: Boolean) {
        isShifted = shifted
        isCapsLock = capsLock
        updateShiftKeyAppearance()
        updateAllKeyLabels()
    }

    /**
     * Sets whether Chinese punctuation mode is enabled.
     * When enabled, period (.) outputs 。 and comma (,) outputs ，
     */
    fun setChinesePunctuationMode(enabled: Boolean) {
        if (useChinesePunctuation != enabled) {
            useChinesePunctuation = enabled
            updateAllKeyLabels()  // Update key labels to show Chinese/English punctuation
        }
    }

    /**
     * Refreshes theme colors. Virtual keyboard uses fixed colors, so this is a no-op.
     */
    fun refreshTheme() {
        // Virtual keyboard colors are independent of the status bar theme - nothing to update
    }

    /**
     * Returns the KeyEvent keycode for a given character.
     */
    private fun getKeyCodeForChar(char: Char): Int? {
        return when (char.lowercaseChar()) {
            'a' -> KeyEvent.KEYCODE_A
            'b' -> KeyEvent.KEYCODE_B
            'c' -> KeyEvent.KEYCODE_C
            'd' -> KeyEvent.KEYCODE_D
            'e' -> KeyEvent.KEYCODE_E
            'f' -> KeyEvent.KEYCODE_F
            'g' -> KeyEvent.KEYCODE_G
            'h' -> KeyEvent.KEYCODE_H
            'i' -> KeyEvent.KEYCODE_I
            'j' -> KeyEvent.KEYCODE_J
            'k' -> KeyEvent.KEYCODE_K
            'l' -> KeyEvent.KEYCODE_L
            'm' -> KeyEvent.KEYCODE_M
            'n' -> KeyEvent.KEYCODE_N
            'o' -> KeyEvent.KEYCODE_O
            'p' -> KeyEvent.KEYCODE_P
            'q' -> KeyEvent.KEYCODE_Q
            'r' -> KeyEvent.KEYCODE_R
            's' -> KeyEvent.KEYCODE_S
            't' -> KeyEvent.KEYCODE_T
            'u' -> KeyEvent.KEYCODE_U
            'v' -> KeyEvent.KEYCODE_V
            'w' -> KeyEvent.KEYCODE_W
            'x' -> KeyEvent.KEYCODE_X
            'y' -> KeyEvent.KEYCODE_Y
            'z' -> KeyEvent.KEYCODE_Z
            else -> null
        }
    }
}
