package it.neuralrad.coolwulf.inputmethod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import it.neuralrad.coolwulf.SettingsManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import it.neuralrad.coolwulf.inputmethod.KeyboardEventTracker
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import android.view.MotionEvent
import android.view.View
import it.neuralrad.coolwulf.core.AutoCorrectionManager
import it.neuralrad.coolwulf.core.InputContextState
import it.neuralrad.coolwulf.core.ModifierStateController
import it.neuralrad.coolwulf.core.NavModeController
import it.neuralrad.coolwulf.core.PinyinInputController
import it.neuralrad.coolwulf.core.T9PinyinInputController
import it.neuralrad.coolwulf.core.ShuangpinInputController
import it.neuralrad.coolwulf.core.WubiInputController
import it.neuralrad.coolwulf.core.ZhenmaInputController
import it.neuralrad.coolwulf.core.ZiranmaInputController
import it.neuralrad.coolwulf.core.SymLayoutController
import it.neuralrad.coolwulf.core.TextInputController
import it.neuralrad.coolwulf.data.layout.LayoutMappingRepository
import it.neuralrad.coolwulf.data.mappings.KeyMappingLoader
import it.neuralrad.coolwulf.data.variation.VariationRepository
import it.neuralrad.coolwulf.inputmethod.SpeechRecognitionActivity
import it.neuralrad.coolwulf.inputmethod.SherpaSpeechActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import it.neuralrad.coolwulf.core.adb.AdbPairingService
import it.neuralrad.coolwulf.core.adb.EmbeddedADB
import it.neuralrad.coolwulf.core.adb.AdbPortDiscovery
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Input method service specialized for physical keyboards.
 * Handles advanced features such as long press that simulates Alt+key.
 */
class PhysicalKeyboardInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "PastieraInputMethod"
        const val ACTION_THEME_CHANGED = "it.neuralrad.coolwulf.THEME_CHANGED"
    }

    // SharedPreferences for settings
    private lateinit var prefs: SharedPreferences
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private lateinit var altSymManager: AltSymManager
    
    // Broadcast receiver for speech recognition
    private var speechResultReceiver: BroadcastReceiver? = null
    // Broadcast receiver for theme changes
    private var themeChangeReceiver: BroadcastReceiver? = null
    // Broadcast receiver for ADB pairing result
    private var adbPairingReceiver: BroadcastReceiver? = null
    private lateinit var candidatesBarController: CandidatesBarController

    // Keycode for the SYM key (device-specific, initialized in onCreate)
    private var KEYCODE_SYM = 63
    
    // Mapping Ctrl+key -> action or keycode (loaded from JSON)
    private val ctrlKeyMap = mutableMapOf<Int, KeyMappingLoader.CtrlMapping>()
    
    // Accessor properties for backwards compatibility with existing code
    private var capsLockEnabled: Boolean
        get() = modifierStateController.capsLockEnabled
        set(value) { modifierStateController.capsLockEnabled = value }
    
    private var shiftPressed: Boolean
        get() = modifierStateController.shiftPressed
        set(value) { modifierStateController.shiftPressed = value }
    
    private var ctrlLatchActive: Boolean
        get() = modifierStateController.ctrlLatchActive
        set(value) { modifierStateController.ctrlLatchActive = value }
    
    private var altLatchActive: Boolean
        get() = modifierStateController.altLatchActive
        set(value) { modifierStateController.altLatchActive = value }
    
    private var ctrlPressed: Boolean
        get() = modifierStateController.ctrlPressed
        set(value) { modifierStateController.ctrlPressed = value }
    
    private var altPressed: Boolean
        get() = modifierStateController.altPressed
        set(value) { modifierStateController.altPressed = value }
    
    private var shiftPhysicallyPressed: Boolean
        get() = modifierStateController.shiftPhysicallyPressed
        set(value) { modifierStateController.shiftPhysicallyPressed = value }
    
    private var ctrlPhysicallyPressed: Boolean
        get() = modifierStateController.ctrlPhysicallyPressed
        set(value) { modifierStateController.ctrlPhysicallyPressed = value }
    
    private var altPhysicallyPressed: Boolean
        get() = modifierStateController.altPhysicallyPressed
        set(value) { modifierStateController.altPhysicallyPressed = value }
    
    private var shiftOneShot: Boolean
        get() = modifierStateController.shiftOneShot
        set(value) { modifierStateController.shiftOneShot = value }

    private var ctrlOneShot: Boolean
        get() = modifierStateController.ctrlOneShot
        set(value) { modifierStateController.ctrlOneShot = value }
    
    private var altOneShot: Boolean
        get() = modifierStateController.altOneShot
        set(value) { modifierStateController.altOneShot = value }
    
    private var ctrlLatchFromNavMode: Boolean
        get() = modifierStateController.ctrlLatchFromNavMode
        set(value) { modifierStateController.ctrlLatchFromNavMode = value }
    
    // Flag to track whether we are in a valid input context
    private var isInputViewActive = false

    // Track compact mode state for hiding IME bar
    private var isCompactModeHidden = false

    // Track virtual keyboard state
    private var isVirtualKeyboardEnabled = false
    private var lastVirtualKeyboardHeight = -1

    // Snapshot of the current input context (numeric/password/restricted fields, etc.)
    private var inputContextState: InputContextState = InputContextState.EMPTY
    
    private val isNumericField: Boolean
        get() = inputContextState.isNumericField
    
    private val shouldDisableSmartFeatures: Boolean
        get() = inputContextState.shouldDisableSmartFeatures
    
    // Current package name
    private var currentPackageName: String? = null
    
    // Modifier/nav/SYM controllers
    private lateinit var modifierStateController: ModifierStateController
    private lateinit var navModeController: NavModeController
    private lateinit var symLayoutController: SymLayoutController
    private lateinit var textInputController: TextInputController
    private lateinit var autoCorrectionManager: AutoCorrectionManager
    private lateinit var variationStateController: VariationStateController
    private lateinit var inputEventRouter: InputEventRouter
    private lateinit var keyboardVisibilityController: KeyboardVisibilityController
    private lateinit var launcherShortcutController: LauncherShortcutController
    private lateinit var pinyinInputController: PinyinInputController
    private lateinit var t9PinyinInputController: T9PinyinInputController
    private lateinit var shuangpinInputController: ShuangpinInputController
    private lateinit var wubiInputController: WubiInputController
    private lateinit var zhenmaInputController: ZhenmaInputController
    private lateinit var ziranmaInputController: ZiranmaInputController
    private lateinit var englishWordPredictionController: it.neuralrad.coolwulf.core.EnglishWordPredictionController
    private var clearAltOnSpaceEnabled: Boolean = false

    private val motionEventController = MotionEventController(logTag = TAG)
    private lateinit var multiTapController: MultiTapController

    // Pagination double press tracking
    private var altLastPressTime = 0L
    private var shiftLastPressTime = 0L

    // Touchpad page swipe debounce - prevent multiple pages from single swipe
    private var lastTouchpadPageTime = 0L
    private val TOUCHPAD_PAGE_DEBOUNCE_MS = 300L  // Ignore swipes within 300ms of last page change

    // Constants
    private val DOUBLE_TAP_THRESHOLD = 500L
    private val CURSOR_UPDATE_DELAY = 50L
    private val MULTI_TAP_TIMEOUT_MS = 800L
    // Alt double-click delay is now configurable via settings
    private val altDoubleClickDelay: Long
        get() = SettingsManager.getAltDoubleClickDelay(this).toLong()

    // Pending Alt selection for delayed single-click handling
    private var pendingAltSelectionRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Track if Alt was used for symbol input during the current press (for Juying mode)
    private var altUsedForSymbolInput: Boolean = false

    // Track if Alt was used for pagination (skip normal Alt UP handling to prevent latch)
    private var altUsedForPagination: Boolean = false

    // Track if Alt latch was just disabled (ignore event meta state until next Alt press)
    private var altLatchJustDisabled: Boolean = false

    // Track if Alt was just used for candidate selection (ignore Alt until key released)
    private var altUsedForCandidateSelection: Boolean = false

    // Trackpad gesture detection (embedded ADB-based)
    private var geteventJob: Job? = null
    private val trackpadScope = CoroutineScope(Dispatchers.IO)
    private var touchDown = false
    private var startX = 0
    private var startY = 0
    private var currentX = 0
    private var currentY = 0
    private var startPosSet = false
    private val trackpadMaxX = 1440  // Trackpad width matches screen width (Titan 2)
    private val trackpadSwipeThreshold: Int
        get() = SettingsManager.getTrackpadSwipeThreshold(this)

    // Track key presses during touch gesture to suppress swipe gestures during typing
    private var keyPressedDuringTouch = false  // True if any key was pressed while touch is active

    // Track if we just cleared next-word predictions due to Shift+letter or DEL (prevent re-triggering)
    // Uses a counter: 0 = allow updates, >0 = skip this many update cycles
    private var skipNextWordPredictionUpdates: Int = 0

    // Saved state when Alt is pressed in Juying mode (instant selection with undo on double-click)
    // On Alt DOWN: immediately commit suggestion, save state for undo
    // On double-click: undo the committed character and go to next page
    // On Alt+key (symbol): undo the committed character and insert symbol
    private var savedAltSuggestion: String? = null  // The suggestion that was committed
    private var savedAltCandidatesForNextPage: List<String> = emptyList()
    private var savedAltCurrentPage: Int = 0
    private var savedAltChineseMode: String? = null  // "pinyin", "shuangpin", "ziranma", "wubi", "zhenma"
    private var savedAltBuffer: String = ""  // Save buffer for double-click restore
    private var savedAltFirstSyllable: String = ""  // Save syllable parsing state for Alt selection
    private var savedAltMatchedPinyin: String = ""
    private var savedAltPhraseCandidateCount: Int = 0
    private var savedAltPhraseCandidateSet: Set<String> = emptySet()  // Save phrase set for proper single char detection
    private var altCommittedCharacter: String? = null  // Character committed on Alt DOWN (for undo)
    private var altRemainingBuffer: String = ""  // Remaining buffer after selection (for undo)

    // Alt candidate index: When shiftAltSwapped is ON, Alt selects index 1 (leftmost suggestion in Juying mode)
    // Otherwise: 4 (5th suggestion) for Titan2, 3 (4th suggestion) for BlackBerry
    private val altCandidateIndex: Int
        get() = if (SettingsManager.getShiftAltSwapped(this)) 1
                else if (SettingsManager.isBlackBerryDevice(this)) 3 else 4

    private val symPage: Int
        get() = if (::symLayoutController.isInitialized) symLayoutController.currentSymPage() else 0

    // Hold space for voice input tracking
    private var spaceHoldHandler: Handler? = null
    private var spaceHoldRunnable: Runnable? = null
    private var spaceHoldTriggeredVoice: Boolean = false
    private var voiceTriggeredByHoldSpace: Boolean = false  // Persists until voice result is received

    // Keyboard sound for physical key presses
    private var soundPool: android.media.SoundPool? = null
    private var keyClickSoundId: Int = 0
    private var soundLoaded = false
    private var loadedSoundType: String = ""
    private val audioManager: android.media.AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    // Multi-sound IDs (24 different key sounds for variety)
    private var multiSoundIds: IntArray = IntArray(24)
    private var multiSoundsLoaded = false
    private val soundRandom = java.util.Random()
    private val SOUND_COUNT = 24

    // Custom sound support
    private var customSoundId: Int = 0
    private var customSoundLoaded = false
    private var loadedCustomSoundPath: String? = null

    // BlackBerry-specific keycodes
    private val BLACKBERRY_KEYCODE_ALT = 57
    private val BLACKBERRY_KEYCODE_SYM = 58
    private val BLACKBERRY_KEYCODE_SHIFT = 59
    private val BLACKBERRY_KEYCODE_CTRL = 68

    /**
     * Translates device-specific keycodes to standard Android keycodes.
     * For BlackBerry keyboards, the modifier keys have different codes.
     */
    private fun translateKeyCode(keyCode: Int): Int {
        if (!SettingsManager.isBlackBerryDevice(this)) {
            return keyCode
        }
        return when (keyCode) {
            BLACKBERRY_KEYCODE_ALT -> KeyEvent.KEYCODE_ALT_LEFT
            BLACKBERRY_KEYCODE_SHIFT -> KeyEvent.KEYCODE_SHIFT_LEFT
            BLACKBERRY_KEYCODE_CTRL -> KeyEvent.KEYCODE_CTRL_LEFT
            BLACKBERRY_KEYCODE_SYM -> KeyEvent.KEYCODE_SYM  // Translate to standard Android KEYCODE_SYM (63)
            else -> keyCode
        }
    }

    /**
     * Initializes the SoundPool for keyboard click sound if not already initialized.
     * Also handles reloading when sound type changes.
     */
    private fun ensureSoundPoolInitialized() {
        val soundType = SettingsManager.getKeyboardSoundType(this)
        val customSoundPath = SettingsManager.getCustomSoundPath(this)

        // If sound type changed, reload the sound
        if (soundPool != null && loadedSoundType != soundType) {
            soundLoaded = false
            multiSoundsLoaded = false
            customSoundLoaded = false
            keyClickSoundId = 0
            customSoundId = 0
            multiSoundIds = IntArray(24)
        }

        // If custom sound path changed, reload custom sound
        if (soundType == "custom" && loadedCustomSoundPath != customSoundPath) {
            customSoundLoaded = false
            customSoundId = 0
        }

        if (soundPool == null) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = android.media.SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(audioAttributes)
                .build()
        }

        // Handle custom sound type
        if (soundType == "custom") {
            if (!customSoundLoaded && customSoundId == 0 && customSoundPath != null) {
                try {
                    customSoundId = soundPool!!.load(customSoundPath, 1)
                    loadedSoundType = soundType
                    loadedCustomSoundPath = customSoundPath
                    soundPool!!.setOnLoadCompleteListener { _, _, status ->
                        if (status == 0) {
                            customSoundLoaded = true
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PhysicalKeyboardIME", "Failed to load custom sound", e)
                }
            }
            return
        }

        // Get multi-sound resources based on sound type
        val multiSoundResources = when (soundType) {
            "bucklespring" -> intArrayOf(
                it.neuralrad.coolwulf.R.raw.buckle_10, it.neuralrad.coolwulf.R.raw.buckle_11,
                it.neuralrad.coolwulf.R.raw.buckle_12, it.neuralrad.coolwulf.R.raw.buckle_13,
                it.neuralrad.coolwulf.R.raw.buckle_14, it.neuralrad.coolwulf.R.raw.buckle_15,
                it.neuralrad.coolwulf.R.raw.buckle_16, it.neuralrad.coolwulf.R.raw.buckle_17,
                it.neuralrad.coolwulf.R.raw.buckle_18, it.neuralrad.coolwulf.R.raw.buckle_19
            )
            "video" -> intArrayOf(
                it.neuralrad.coolwulf.R.raw.video_1, it.neuralrad.coolwulf.R.raw.video_2,
                it.neuralrad.coolwulf.R.raw.video_3, it.neuralrad.coolwulf.R.raw.video_4,
                it.neuralrad.coolwulf.R.raw.video_5, it.neuralrad.coolwulf.R.raw.video_6,
                it.neuralrad.coolwulf.R.raw.video_7, it.neuralrad.coolwulf.R.raw.video_8,
                it.neuralrad.coolwulf.R.raw.video_9, it.neuralrad.coolwulf.R.raw.video_10,
                it.neuralrad.coolwulf.R.raw.video_11, it.neuralrad.coolwulf.R.raw.video_12,
                it.neuralrad.coolwulf.R.raw.video_13, it.neuralrad.coolwulf.R.raw.video_14,
                it.neuralrad.coolwulf.R.raw.video_15, it.neuralrad.coolwulf.R.raw.video_16,
                it.neuralrad.coolwulf.R.raw.video_17, it.neuralrad.coolwulf.R.raw.video_18,
                it.neuralrad.coolwulf.R.raw.video_19, it.neuralrad.coolwulf.R.raw.video_20,
                it.neuralrad.coolwulf.R.raw.video_21, it.neuralrad.coolwulf.R.raw.video_22,
                it.neuralrad.coolwulf.R.raw.video_23, it.neuralrad.coolwulf.R.raw.video_24
            )
            "mario" -> intArrayOf(
                it.neuralrad.coolwulf.R.raw.mario_1, it.neuralrad.coolwulf.R.raw.mario_2,
                it.neuralrad.coolwulf.R.raw.mario_3, it.neuralrad.coolwulf.R.raw.mario_4,
                it.neuralrad.coolwulf.R.raw.mario_5, it.neuralrad.coolwulf.R.raw.mario_6,
                it.neuralrad.coolwulf.R.raw.mario_7, it.neuralrad.coolwulf.R.raw.mario_8,
                it.neuralrad.coolwulf.R.raw.mario_9, it.neuralrad.coolwulf.R.raw.mario_10,
                it.neuralrad.coolwulf.R.raw.mario_11, it.neuralrad.coolwulf.R.raw.mario_12,
                it.neuralrad.coolwulf.R.raw.mario_13, it.neuralrad.coolwulf.R.raw.mario_14,
                it.neuralrad.coolwulf.R.raw.mario_15, it.neuralrad.coolwulf.R.raw.mario_16,
                it.neuralrad.coolwulf.R.raw.mario_17, it.neuralrad.coolwulf.R.raw.mario_18,
                it.neuralrad.coolwulf.R.raw.mario_19, it.neuralrad.coolwulf.R.raw.mario_20,
                it.neuralrad.coolwulf.R.raw.mario_21, it.neuralrad.coolwulf.R.raw.mario_22,
                it.neuralrad.coolwulf.R.raw.mario_23, it.neuralrad.coolwulf.R.raw.mario_24
            )
            "piano" -> intArrayOf(
                it.neuralrad.coolwulf.R.raw.piano_1, it.neuralrad.coolwulf.R.raw.piano_2,
                it.neuralrad.coolwulf.R.raw.piano_3, it.neuralrad.coolwulf.R.raw.piano_4,
                it.neuralrad.coolwulf.R.raw.piano_5, it.neuralrad.coolwulf.R.raw.piano_6,
                it.neuralrad.coolwulf.R.raw.piano_7, it.neuralrad.coolwulf.R.raw.piano_8,
                it.neuralrad.coolwulf.R.raw.piano_9, it.neuralrad.coolwulf.R.raw.piano_10,
                it.neuralrad.coolwulf.R.raw.piano_11, it.neuralrad.coolwulf.R.raw.piano_12,
                it.neuralrad.coolwulf.R.raw.piano_13, it.neuralrad.coolwulf.R.raw.piano_14,
                it.neuralrad.coolwulf.R.raw.piano_15, it.neuralrad.coolwulf.R.raw.piano_16,
                it.neuralrad.coolwulf.R.raw.piano_17, it.neuralrad.coolwulf.R.raw.piano_18,
                it.neuralrad.coolwulf.R.raw.piano_19, it.neuralrad.coolwulf.R.raw.piano_20,
                it.neuralrad.coolwulf.R.raw.piano_21, it.neuralrad.coolwulf.R.raw.piano_22,
                it.neuralrad.coolwulf.R.raw.piano_23, it.neuralrad.coolwulf.R.raw.piano_24
            )
            else -> null  // mechanical uses single sound
        }

        if (multiSoundResources != null) {
            // Multi-sound types (bucklespring, video, mario, piano)
            if (!multiSoundsLoaded && multiSoundIds[0] == 0) {
                var loadedCount = 0
                val expectedCount = multiSoundResources.size
                multiSoundResources.forEachIndexed { index, res ->
                    multiSoundIds[index] = soundPool!!.load(this, res, 1)
                }
                loadedSoundType = soundType
                soundPool!!.setOnLoadCompleteListener { _, _, status ->
                    if (status == 0) {
                        loadedCount++
                        if (loadedCount >= expectedCount) {
                            multiSoundsLoaded = true
                        }
                    }
                }
            }
        } else {
            // Single sound type (mechanical)
            if (!soundLoaded && keyClickSoundId == 0) {
                keyClickSoundId = soundPool!!.load(this, it.neuralrad.coolwulf.R.raw.key_click, 1)
                loadedSoundType = soundType
                soundPool!!.setOnLoadCompleteListener { _, _, status ->
                    if (status == 0) {
                        soundLoaded = true
                    }
                }
            }
        }
    }

    /**
     * Gets the keyboard sound volume combining user setting and system volume.
     */
    private fun getKeyboardSoundVolume(): Float {
        // Get user-configured volume (0-100) and convert to 0.0-1.0
        val userVolume = SettingsManager.getKeyboardSoundVolume(this) / 100f
        // Apply system media volume on top
        val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
        val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
        val systemVolume = if (maxVolume > 0) currentVolume / maxVolume else 0.5f
        return userVolume * systemVolume
    }

    /**
     * Plays keyboard click sound for physical key presses.
     */
    private fun playKeyClickSound() {
        if (!SettingsManager.isKeyboardSoundEnabled(this)) return
        ensureSoundPoolInitialized()
        val soundType = SettingsManager.getKeyboardSoundType(this)
        val volume = getKeyboardSoundVolume()

        // Handle custom sound
        if (soundType == "custom" && customSoundLoaded && customSoundId != 0) {
            soundPool?.play(customSoundId, volume, volume, 1, 0, 1.0f)
            return
        }

        if (soundType in listOf("bucklespring", "video", "mario", "piano") && multiSoundsLoaded) {
            // Pick a random sound for variety - bucklespring has 10 sounds, others have 24
            val count = if (soundType == "bucklespring") 10 else SOUND_COUNT
            val soundId = multiSoundIds[soundRandom.nextInt(count)]
            if (soundId != 0) {
                soundPool?.play(soundId, volume, volume, 1, 0, 1.0f)
            }
        } else if (soundLoaded && keyClickSoundId != 0) {
            soundPool?.play(keyClickSoundId, volume, volume, 1, 0, 1.0f)
        }
    }

    /**
     * Plays keyboard sound when Juying key selects a candidate (if enabled).
     * Requires both main keyboard sound AND Juying selection sound to be enabled.
     * Uses the same sound configuration as keyboard sounds.
     *
     * @param keyCode The key code that triggered the selection. Space and Sym are excluded
     *                since they already produce sound from normal key handling.
     */
    private fun playJuyingSelectionSound(keyCode: Int) {
        // Require main keyboard sound to be enabled first
        if (!SettingsManager.isKeyboardSoundEnabled(this)) return
        // Then check Juying-specific sound setting
        if (!SettingsManager.getJuyingSoundEnabled(this)) return
        // Skip Space and Sym keys - they already produce sound from normal key handling
        if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_SYM || keyCode == KEYCODE_SYM) return

        // Reuse the same sound playing logic as keyboard clicks
        ensureSoundPoolInitialized()
        val soundType = SettingsManager.getKeyboardSoundType(this)
        val volume = getKeyboardSoundVolume()

        // Handle custom sound
        if (soundType == "custom" && customSoundLoaded && customSoundId != 0) {
            soundPool?.play(customSoundId, volume, volume, 1, 0, 1.0f)
            return
        }

        if (soundType in listOf("bucklespring", "video", "mario", "piano") && multiSoundsLoaded) {
            val count = if (soundType == "bucklespring") 10 else SOUND_COUNT
            val soundId = multiSoundIds[soundRandom.nextInt(count)]
            if (soundId != 0) {
                soundPool?.play(soundId, volume, volume, 1, 0, 1.0f)
            }
        } else if (soundLoaded && keyClickSoundId != 0) {
            soundPool?.play(keyClickSoundId, volume, volume, 1, 0, 1.0f)
        }
    }

    /**
     * Checks if the given keycode should produce a keyboard click sound.
     * Letter keys, number keys, space, enter, delete, SYM, function keys should produce sound.
     * Navigation keys (Back, Recent/App Switch, Home) and volume keys should be silent.
     */
    private fun shouldPlaySoundForKey(keyCode: Int): Boolean {
        return when (keyCode) {
            // Silent keys - navigation buttons and volume keys
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> false

            // All other keys should produce sound (letters, numbers, symbols, function keys, etc.)
            else -> true
        }
    }

    /**
     * Checks if the given keycode is a modifier key (Shift/Ctrl/Alt) for the current device.
     */
    private fun isDeviceModifierKey(keyCode: Int): Boolean {
        if (SettingsManager.isBlackBerryDevice(this)) {
            return keyCode == BLACKBERRY_KEYCODE_ALT ||
                   keyCode == BLACKBERRY_KEYCODE_SHIFT ||
                   keyCode == BLACKBERRY_KEYCODE_CTRL
        }
        return keyCode == KeyEvent.KEYCODE_SHIFT_LEFT ||
               keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT ||
               keyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
               keyCode == KeyEvent.KEYCODE_CTRL_RIGHT ||
               keyCode == KeyEvent.KEYCODE_ALT_LEFT ||
               keyCode == KeyEvent.KEYCODE_ALT_RIGHT
    }

    /**
     * Checks if the given keycode is a Shift key for the current device.
     */
    private fun isDeviceShiftKey(keyCode: Int): Boolean {
        if (SettingsManager.isBlackBerryDevice(this)) {
            return keyCode == BLACKBERRY_KEYCODE_SHIFT
        }
        return keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
    }

    /**
     * Checks if the given keycode is an Alt key for the current device.
     */
    private fun isDeviceAltKey(keyCode: Int): Boolean {
        // Check for both ALT_LEFT (57) and ALT_RIGHT (58) on all devices
        // BlackBerry uses 57, Titan 2 uses 58, but both are standard Android Alt keycodes
        return keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT
    }

    /**
     * Checks if the given keycode is a Ctrl key for the current device.
     */
    private fun isDeviceCtrlKey(keyCode: Int): Boolean {
        if (SettingsManager.isBlackBerryDevice(this)) {
            return keyCode == BLACKBERRY_KEYCODE_CTRL
        }
        return keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT
    }

    private fun updateInputContextState(info: EditorInfo?) {
        inputContextState = InputContextState.fromEditorInfo(info)
    }

    private fun refreshStatusBar() {
        updateStatusBarText()
    }
    
    private fun startSpeechRecognition() {
        try {
            // Choose between offline (Sherpa-ONNX) and online (Google) voice recognition
            val useOffline = SettingsManager.isOfflineVoiceInput(this)

            val intent = if (useOffline) {
                // Use Sherpa-ONNX for offline voice recognition
                Intent(this, SherpaSpeechActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
            } else {
                Intent(this, SpeechRecognitionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_HISTORY or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
            }
            startActivity(intent)
            Log.d(TAG, "Speech recognition started (offline=$useOffline)")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to launch speech recognition", e)
        }
    }
    
    

    /**
     * Initializes the input context for a field.
     * This method contains all common initialization logic that must run
     * regardless of whether input view or candidates view is shown.
     */
    private fun initializeInputContext(restarting: Boolean) {
        if (restarting) {
            return
        }
        
        val state = inputContextState
        val isEditable = state.isEditable
        val isReallyEditable = state.isReallyEditable
        val canCheckAutoCapitalize = isEditable && !state.shouldDisableSmartFeatures
        
        if (!isReallyEditable) {
            isInputViewActive = false
            
            if (canCheckAutoCapitalize) {
                AutoCapitalizeHelper.checkAndEnableAutoCapitalize(
                    this,
                    currentInputConnection,
                    shouldDisableSmartFeatures,
                    enableShift = { modifierStateController.requestShiftOneShotFromAutoCap() },
                    onUpdateStatusBar = { updateStatusBarText() }
                )
            }
            return
        }
        
        isInputViewActive = true
        
        enforceSmartFeatureDisabledState()
        
        if (ctrlLatchFromNavMode && ctrlLatchActive) {
            val inputConnection = currentInputConnection
            if (inputConnection != null) {
                navModeController.exitNavMode()
            }
        }
        
        AutoCapitalizeHelper.checkAndEnableAutoCapitalize(
            this,
            currentInputConnection,
            shouldDisableSmartFeatures,
            enableShift = { modifierStateController.requestShiftOneShotFromAutoCap() },
            onUpdateStatusBar = { updateStatusBarText() }
        )
        
        symLayoutController.restoreSymPageIfNeeded { updateStatusBarText() }

        altSymManager.reloadLongPressThreshold()
        altSymManager.resetTransientState()

        // Apply the startup input mode (respects special text fields and remembers last mode)
        applyStartupInputMode()
    }
    
    private fun enforceSmartFeatureDisabledState() {
        if (!shouldDisableSmartFeatures) {
            return
        }
        setCandidatesViewShown(false)
        deactivateVariations()
    }
    
    /**
     * Reloads nav mode key mappings from the file.
     */
    private fun loadKeyboardLayout() {
        val layoutName = SettingsManager.getKeyboardLayout(this)
        val layout = LayoutMappingRepository.loadLayout(assets, layoutName, this)
        Log.d(TAG, "Keyboard layout loaded: $layoutName")
    }
    
    /**
     * Gets the character from the selected keyboard layout for a given keyCode and shift state.
     * If the keyCode is mapped in the layout, returns that character.
     * Otherwise, returns the character from the event (if available).
     * This ensures that keyboard layouts work correctly regardless of Android's system layout settings.
     */
    private fun getCharacterFromLayout(keyCode: Int, event: KeyEvent?, isShift: Boolean): Char? {
        // First, try to get the character from the selected layout
        val layoutChar = LayoutMappingRepository.getCharacter(keyCode, isShift)
        if (layoutChar != null) {
            return layoutChar
        }
        // If not mapped in layout, fall back to event's unicode character
        if (event != null && event.unicodeChar != 0) {
            return event.unicodeChar.toChar()
        }
        return null
    }
    
    /**
     * Gets the character string from the selected keyboard layout.
     * Returns the original event character if not mapped in layout.
     */
    private fun getCharacterStringFromLayout(keyCode: Int, event: KeyEvent?, isShift: Boolean): String {
        val char = getCharacterFromLayout(keyCode, event, isShift)
        return char?.toString() ?: ""
    }
    
    private fun reloadNavModeMappings() {
        try {
            ctrlKeyMap.clear()
            val assets = assets
            ctrlKeyMap.putAll(KeyMappingLoader.loadCtrlKeyMappings(assets, this))
            Log.d(TAG, "Nav mode mappings reloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error reloading nav mode mappings", e)
        }
    }
    
    /**
     * Checks if a keycode corresponds to an alphabetic key (A-Z).
     * Returns true only for alphabetic keys, false for all others (modifiers, volume, etc.).
     */
    private fun isAlphabeticKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_B,
            KeyEvent.KEYCODE_C,
            KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_E,
            KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_G,
            KeyEvent.KEYCODE_H,
            KeyEvent.KEYCODE_I,
            KeyEvent.KEYCODE_J,
            KeyEvent.KEYCODE_K,
            KeyEvent.KEYCODE_L,
            KeyEvent.KEYCODE_M,
            KeyEvent.KEYCODE_N,
            KeyEvent.KEYCODE_O,
            KeyEvent.KEYCODE_P,
            KeyEvent.KEYCODE_Q,
            KeyEvent.KEYCODE_R,
            KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_T,
            KeyEvent.KEYCODE_U,
            KeyEvent.KEYCODE_V,
            KeyEvent.KEYCODE_W,
            KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_Y,
            KeyEvent.KEYCODE_Z -> true
            else -> false
        }
    }

    /**
     * Handles Enter key based on EditorInfo.imeOptions.
     * Returns true if an action was performed (Send, Search, Go, Done, Next),
     * false if Enter should insert a newline (default behavior).
     */
    private fun handleEnterAction(ic: InputConnection): Boolean {
        val info = currentInputEditorInfo ?: return false

        // Check if IME_FLAG_NO_ENTER_ACTION is set - if so, always insert newline
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
            return false
        }

        // Get the action from imeOptions
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION

        // Perform the action if it's a specific action type
        return when (action) {
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS -> {
                ic.performEditorAction(action)
                true
            }
            // IME_ACTION_NONE, IME_ACTION_UNSPECIFIED, or unknown - insert newline
            else -> false
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("pastiera_prefs", Context.MODE_PRIVATE)
        clearAltOnSpaceEnabled = SettingsManager.getClearAltOnSpace(this)

        // Initialize device-specific keycodes
        KEYCODE_SYM = SettingsManager.getSymKeyCode(this)

        NotificationHelper.createNotificationChannel(this)
        
        modifierStateController = ModifierStateController(DOUBLE_TAP_THRESHOLD)
        navModeController = NavModeController(this, modifierStateController)
        inputEventRouter = InputEventRouter(this, navModeController)
        textInputController = TextInputController(
            context = this,
            modifierStateController = modifierStateController,
            doubleTapThreshold = DOUBLE_TAP_THRESHOLD
        )
        autoCorrectionManager = AutoCorrectionManager(this)
        pinyinInputController = PinyinInputController(this)
        t9PinyinInputController = T9PinyinInputController(this)
        shuangpinInputController = ShuangpinInputController(this)
        wubiInputController = WubiInputController(this)
        zhenmaInputController = ZhenmaInputController(this)
        ziranmaInputController = ZiranmaInputController(this)

        // Apply Chinese next word prediction setting
        val chineseNextWordPredictionEnabled = SettingsManager.getChineseNextWordPredictionEnabled(this)
        pinyinInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)
        shuangpinInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)
        wubiInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)
        zhenmaInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)
        ziranmaInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)

        // Apply fuzzy pinyin setting (模糊音)
        val fuzzyPinyinEnabled = SettingsManager.getPinyinFuzzyEnabled(this)
        pinyinInputController.setFuzzyPinyinEnabled(fuzzyPinyinEnabled)

        // Apply neural pinyin settings
        val neuralPinyinEnabled = SettingsManager.isNeuralPinyinEnabled(this)
        val neuralPinyinMinLetters = SettingsManager.getNeuralPinyinMinLetters(this)
        val neuralPinyinPriority = SettingsManager.isNeuralPinyinPriority(this)
        val neuralPinyinCount = SettingsManager.getNeuralPinyinCount(this)
        pinyinInputController.setNeuralPinyinEnabled(neuralPinyinEnabled)
        pinyinInputController.setNeuralPinyinMinLetters(neuralPinyinMinLetters)
        pinyinInputController.setNeuralPinyinPriority(neuralPinyinPriority)
        pinyinInputController.setNeuralPinyinCount(neuralPinyinCount)
        // Also apply neural pinyin settings to Shuangpin (converts to pinyin internally)
        shuangpinInputController.setNeuralPinyinEnabled(neuralPinyinEnabled)
        shuangpinInputController.setNeuralPinyinMinLetters(neuralPinyinMinLetters)
        shuangpinInputController.setNeuralPinyinPriority(neuralPinyinPriority)
        shuangpinInputController.setNeuralPinyinCount(neuralPinyinCount)

        // Start clipboard history listener if enabled
        if (SettingsManager.getClipboardHistoryEnabled(this)) {
            it.neuralrad.coolwulf.core.ClipboardHistoryManager.startListening(this)
        }

        englishWordPredictionController = it.neuralrad.coolwulf.core.EnglishWordPredictionController(this)
        // Apply next word prediction setting to English controller
        englishWordPredictionController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)

        multiTapController = MultiTapController(
            handler = Handler(Looper.getMainLooper()),
            timeoutMs = MULTI_TAP_TIMEOUT_MS
        )

        candidatesBarController = CandidatesBarController(this)

        // Register listener for variation selection (both controllers)
        val variationListener = object : VariationButtonHandler.OnVariationSelectedListener {
            override fun onVariationSelected(variation: String) {
                // Clear suggestion lists after selection
                if (pinyinInputController.isPinyinMode()) {
                    // For Pinyin, the buffer is managed by selectCandidate() for keyboard input
                    // For touch input, the click listener handles it directly
                    // Just update the UI here
                } else {
                    // Clear English word prediction suggestions
                    englishWordPredictionController.clearSuggestions()
                }
                // Clear Alt state after touch selection (e.g., after double-tap Alt to navigate pages)
                modifierStateController.clearAltState(resetPressedState = true)
                // Update variations after one has been selected (refresh view if needed)
                updateStatusBarText()
            }
        }
        candidatesBarController.onVariationSelectedListener = variationListener

        // Helper function to reverse map display index to original index based on candidate count
        // Display reordering (non-fixed mode):
        // 1 candidate: [1st] -> display 0 = original 0
        // 2 candidates: [2nd, 1st] -> display 0=orig 1, display 1=orig 0
        // 3 candidates: [2nd, 1st, 3rd] -> display 0=orig 1, display 1=orig 0, display 2=orig 2
        // 4+ candidates: [2nd, 3rd, 1st, 4th, 5th] -> display 0=orig 1, display 1=orig 2, display 2=orig 0, etc.
        fun reverseJuyingDisplayIndex(displayIndex: Int, candidateCount: Int): Int {
            return when (candidateCount) {
                1 -> 0  // Only one candidate, display 0 = original 0
                2 -> when (displayIndex) {
                    0 -> 1  // Display 0 -> Original 1 (2nd)
                    1 -> 0  // Display 1 -> Original 0 (1st, best)
                    else -> displayIndex
                }
                3 -> when (displayIndex) {
                    0 -> 1  // Display 0 -> Original 1 (2nd)
                    1 -> 0  // Display 1 -> Original 0 (1st, best)
                    2 -> 2  // Display 2 -> Original 2 (3rd)
                    else -> displayIndex
                }
                else -> when (displayIndex) {
                    0 -> 1  // Display 0 -> Original 1 (2nd)
                    1 -> 2  // Display 1 -> Original 2 (3rd)
                    2 -> 0  // Display 2 -> Original 0 (1st, best)
                    else -> displayIndex  // 3, 4 stay the same
                }
            }
        }

        // Helper function to map slot index to original candidate index in fixed position mode
        // Fixed position slots: [Shift=0, Sym=1, Space=2, Ctrl=3, Alt=4]
        // Placement rules:
        // 1 candidate:  Space(best=0)
        // 2 candidates: Space(best=0), Sym(2nd=1)
        // 3 candidates: Space(best=0), Sym(2nd=1), Ctrl(3rd=2)
        // 4 candidates: Space(best=0), Shift(2nd=1), Sym(3rd=2), Ctrl(4th=3)
        // 5 candidates: Space(best=0), Shift(2nd=1), Sym(3rd=2), Ctrl(4th=3), Alt(5th=4)
        fun reverseFixedPositionSlotIndex(slotIndex: Int, candidateCount: Int): Int {
            return when (candidateCount) {
                1 -> when (slotIndex) {
                    2 -> 0  // Space -> best (original 0)
                    else -> -1
                }
                2 -> when (slotIndex) {
                    2 -> 0  // Space -> best (original 0)
                    1 -> 1  // Sym -> 2nd (original 1)
                    else -> -1
                }
                3 -> when (slotIndex) {
                    2 -> 0  // Space -> best (original 0)
                    1 -> 1  // Sym -> 2nd (original 1)
                    3 -> 2  // Ctrl -> 3rd (original 2)
                    else -> -1
                }
                4 -> when (slotIndex) {
                    2 -> 0  // Space -> best (original 0)
                    0 -> 1  // Shift -> 2nd (original 1)
                    1 -> 2  // Sym -> 3rd (original 2)
                    3 -> 3  // Ctrl -> 4th (original 3)
                    else -> -1
                }
                else -> when (slotIndex) {
                    2 -> 0  // Space -> best (original 0)
                    0 -> 1  // Shift -> 2nd (original 1)
                    1 -> 2  // Sym -> 3rd (original 2)
                    3 -> 3  // Ctrl -> 4th (original 3)
                    4 -> 4  // Alt -> 5th (original 4)
                    else -> -1
                }
            }
        }

        // Register listener for Pinyin candidate selection (with index)
        val pinyinListener = object : VariationButtonHandler.OnPinyinCandidateSelectedListener {
            override fun onPinyinCandidateSelected(candidate: String, candidateIndex: Int) {
                val ic = currentInputConnection ?: return
                val isJuyingMode = SettingsManager.getJuyingModeEnabled(this@PhysicalKeyboardInputMethodService)
                val isFixedPositionMode = SettingsManager.getJuyingFixedPositions(this@PhysicalKeyboardInputMethodService)
                val candidateCount = pinyinInputController.getCurrentPageCandidates().size
                // In fixed position mode, candidateIndex is the slot index (0-4), need to map to original index
                // In non-fixed mode, candidateIndex is the display order, need to reverse the reordering
                val originalIndex = if (isJuyingMode && isFixedPositionMode) {
                    reverseFixedPositionSlotIndex(candidateIndex, candidateCount)
                } else if (isJuyingMode) {
                    reverseJuyingDisplayIndex(candidateIndex, candidateCount)
                } else {
                    candidateIndex
                }
                // VariationButtonHandler already committed the text, we just update buffer state
                pinyinInputController.selectCandidate(originalIndex)
                val remainingBuffer = pinyinInputController.getBuffer()
                // Set remaining buffer as composing text (e.g., 'wode' -> '我' + 'de' underlined)
                if (remainingBuffer.isNotEmpty()) {
                    ic.setComposingText(remainingBuffer, 1)
                }
                // Clear Alt state after touch selection (e.g., after double-tap Alt to navigate pages)
                modifierStateController.clearAltState(resetPressedState = true)
                updateStatusBarText()
            }
        }
        candidatesBarController.onPinyinCandidateSelectedListener = pinyinListener

        // Register listener for Wubi candidate selection (with index)
        val wubiListener = object : VariationButtonHandler.OnWubiCandidateSelectedListener {
            override fun onWubiCandidateSelected(candidate: String, candidateIndex: Int) {
                val ic = currentInputConnection ?: return
                val isJuyingMode = SettingsManager.getJuyingModeEnabled(this@PhysicalKeyboardInputMethodService)
                val isFixedPositionMode = SettingsManager.getJuyingFixedPositions(this@PhysicalKeyboardInputMethodService)
                val candidateCount = wubiInputController.getCurrentPageCandidates().size
                // In fixed position mode, candidateIndex is the slot index (0-4), need to map to original index
                // In non-fixed mode, candidateIndex is the display order, need to reverse the reordering
                val originalIndex = if (isJuyingMode && isFixedPositionMode) {
                    reverseFixedPositionSlotIndex(candidateIndex, candidateCount)
                } else if (isJuyingMode) {
                    reverseJuyingDisplayIndex(candidateIndex, candidateCount)
                } else candidateIndex
                // VariationButtonHandler already committed the text, we just update buffer state
                wubiInputController.selectCandidate(originalIndex)
                val remainingBuffer = wubiInputController.getBuffer()
                if (remainingBuffer.isNotEmpty()) {
                    ic.setComposingText(remainingBuffer, 1)
                }
                // Clear Alt state after touch selection
                modifierStateController.clearAltState(resetPressedState = true)
                updateStatusBarText()
            }
        }
        candidatesBarController.onWubiCandidateSelectedListener = wubiListener

        // Register listener for Shuangpin candidate selection (with index)
        val shuangpinListener = object : VariationButtonHandler.OnShuangpinCandidateSelectedListener {
            override fun onShuangpinCandidateSelected(candidate: String, candidateIndex: Int) {
                val ic = currentInputConnection ?: return
                val isJuyingMode = SettingsManager.getJuyingModeEnabled(this@PhysicalKeyboardInputMethodService)
                val isFixedPositionMode = SettingsManager.getJuyingFixedPositions(this@PhysicalKeyboardInputMethodService)
                val candidateCount = shuangpinInputController.getCurrentPageCandidates().size
                // In fixed position mode, candidateIndex is the slot index (0-4), need to map to original index
                // In non-fixed mode, candidateIndex is the display order, need to reverse the reordering
                val originalIndex = if (isJuyingMode && isFixedPositionMode) {
                    reverseFixedPositionSlotIndex(candidateIndex, candidateCount)
                } else if (isJuyingMode) {
                    reverseJuyingDisplayIndex(candidateIndex, candidateCount)
                } else candidateIndex
                // VariationButtonHandler already committed the text, we just update buffer state
                shuangpinInputController.selectCandidate(originalIndex)
                val remainingBuffer = shuangpinInputController.getBuffer()
                if (remainingBuffer.isNotEmpty()) {
                    ic.setComposingText(remainingBuffer, 1)
                }
                // Clear Alt state after touch selection
                modifierStateController.clearAltState(resetPressedState = true)
                updateStatusBarText()
            }
        }
        candidatesBarController.onShuangpinCandidateSelectedListener = shuangpinListener

        // Register listener for Zhenma candidate selection (with index)
        val zhenmaListener = object : VariationButtonHandler.OnZhenmaCandidateSelectedListener {
            override fun onZhenmaCandidateSelected(candidate: String, candidateIndex: Int) {
                val ic = currentInputConnection ?: return
                val isJuyingMode = SettingsManager.getJuyingModeEnabled(this@PhysicalKeyboardInputMethodService)
                val isFixedPositionMode = SettingsManager.getJuyingFixedPositions(this@PhysicalKeyboardInputMethodService)
                val candidateCount = zhenmaInputController.getCurrentPageCandidates().size
                // In fixed position mode, candidateIndex is the slot index (0-4), need to map to original index
                // In non-fixed mode, candidateIndex is the display order, need to reverse the reordering
                val originalIndex = if (isJuyingMode && isFixedPositionMode) {
                    reverseFixedPositionSlotIndex(candidateIndex, candidateCount)
                } else if (isJuyingMode) {
                    reverseJuyingDisplayIndex(candidateIndex, candidateCount)
                } else candidateIndex
                // VariationButtonHandler already committed the text, we just update buffer state
                zhenmaInputController.selectCandidate(originalIndex)
                val remainingBuffer = zhenmaInputController.getBuffer()
                if (remainingBuffer.isNotEmpty()) {
                    ic.setComposingText(remainingBuffer, 1)
                }
                // Clear Alt state after touch selection
                modifierStateController.clearAltState(resetPressedState = true)
                updateStatusBarText()
            }
        }
        candidatesBarController.onZhenmaCandidateSelectedListener = zhenmaListener

        // Register listener for Ziranma candidate selection (with index)
        val ziranmaListener = object : VariationButtonHandler.OnZiranmaCandidateSelectedListener {
            override fun onZiranmaCandidateSelected(candidate: String, candidateIndex: Int) {
                val ic = currentInputConnection ?: return
                val isJuyingMode = SettingsManager.getJuyingModeEnabled(this@PhysicalKeyboardInputMethodService)
                val isFixedPositionMode = SettingsManager.getJuyingFixedPositions(this@PhysicalKeyboardInputMethodService)
                val candidateCount = ziranmaInputController.getCurrentPageCandidates().size
                // In fixed position mode, candidateIndex is the slot index (0-4), need to map to original index
                // In non-fixed mode, candidateIndex is the display order, need to reverse the reordering
                val originalIndex = if (isJuyingMode && isFixedPositionMode) {
                    reverseFixedPositionSlotIndex(candidateIndex, candidateCount)
                } else if (isJuyingMode) {
                    reverseJuyingDisplayIndex(candidateIndex, candidateCount)
                } else candidateIndex
                // VariationButtonHandler already committed the text, we just update buffer state
                ziranmaInputController.selectCandidate(originalIndex)
                val remainingBuffer = ziranmaInputController.getBuffer()
                if (remainingBuffer.isNotEmpty()) {
                    ic.setComposingText(remainingBuffer, 1)
                }
                // Clear Alt state after touch selection
                modifierStateController.clearAltState(resetPressedState = true)
                updateStatusBarText()
            }
        }
        candidatesBarController.onZiranmaCandidateSelectedListener = ziranmaListener

        // Register listener for cursor movement (both controllers)
        val cursorListener = {
            updateStatusBarText()
        }
        candidatesBarController.onCursorMovedListener = cursorListener

        // Register listeners for page navigation
        candidatesBarController.onNextPageListener = {
            // Navigate to next page (Pinyin, Shuangpin, Ziranma, Wubi, Zhenma, or word prediction)
            if (pinyinInputController.isPinyinMode()) {
                pinyinInputController.nextPage()
            } else if (shuangpinInputController.isShuangpinMode()) {
                shuangpinInputController.nextPage()
            } else if (ziranmaInputController.isZiranmaMode()) {
                ziranmaInputController.nextPage()
            } else if (wubiInputController.isWubiMode()) {
                wubiInputController.nextPage()
            } else if (zhenmaInputController.isZhenmaMode()) {
                zhenmaInputController.nextPage()
            } else {
                englishWordPredictionController.nextPage()
            }
            updateStatusBarText()
        }
        candidatesBarController.onPrevPageListener = {
            // Navigate to previous page (Pinyin, Shuangpin, Ziranma, Wubi, Zhenma, or word prediction)
            if (pinyinInputController.isPinyinMode()) {
                pinyinInputController.prevPage()
            } else if (shuangpinInputController.isShuangpinMode()) {
                shuangpinInputController.prevPage()
            } else if (ziranmaInputController.isZiranmaMode()) {
                ziranmaInputController.prevPage()
            } else if (wubiInputController.isWubiMode()) {
                wubiInputController.prevPage()
            } else if (zhenmaInputController.isZhenmaMode()) {
                zhenmaInputController.prevPage()
            } else {
                englishWordPredictionController.prevPage()
            }
            updateStatusBarText()
        }

        // Register listener for language toggle (EN/CN switch)
        candidatesBarController.onLanguageToggleListener = {
            toggleChineseInputMode()
        }

        // Register listener for SYM button press
        candidatesBarController.onSymButtonListener = {
            symLayoutController.toggleSymPage()
            updateStatusBarText()
        }

        // Register listener for punctuation toggle (Chinese/English punctuation)
        candidatesBarController.onPunctuationToggleListener = {
            togglePunctuationMode()
        }

        // Register listener for traditional Chinese toggle (简/繁)
        candidatesBarController.onTraditionalChineseToggleListener = {
            toggleTraditionalChineseMode()
        }

        // Register listener for virtual keyboard toggle button
        candidatesBarController.onVirtualKeyboardToggleListener = {
            toggleVirtualKeyboard()
        }

        // Register listeners for virtual keyboard
        candidatesBarController.onVirtualKeyPressListener = { keyCode, isShifted ->
            handleVirtualKeyPress(keyCode, isShifted)
        }
        candidatesBarController.onVirtualCharacterInputListener = { char ->
            handleVirtualCharacterInput(char)
        }
        candidatesBarController.onVirtualVoiceInputRequestListener = {
            startSpeechRecognition()
        }
        candidatesBarController.onVirtualShiftStateChangedListener = { isShifted, isCapsLock ->
            // Sync virtual keyboard shift state to physical keyboard modifier state
            modifierStateController.capsLockEnabled = isCapsLock
            if (isShifted && !isCapsLock) {
                modifierStateController.requestShiftOneShotFromAutoCap()
            } else if (!isShifted && !isCapsLock) {
                modifierStateController.clearShiftState(resetPressedState = false)
            }
            updateStatusBarText()
        }
        candidatesBarController.onVirtualCtrlKeyPressListener = { keyCode ->
            handleVirtualCtrlKeyPress(keyCode)
        }

        // Initialize virtual keyboard based on settings
        isVirtualKeyboardEnabled = SettingsManager.isVirtualKeyboardEnabled(this)
        candidatesBarController.setVirtualKeyboardEnabled(isVirtualKeyboardEnabled)

        altSymManager = AltSymManager(assets, prefs, this)
        altSymManager.reloadSymMappings() // Load custom mappings for page 1 if present
        altSymManager.reloadSymMappings2() // Load custom mappings for page 2 if present
        altSymManager.reloadSymMappings3() // Load custom mappings for page 3 (Characters2) if present
        // Register callback to be notified when an Alt character is inserted after long press.
        // Variations are updated automatically by updateStatusBarText().
        altSymManager.onAltCharInserted = { char ->
            updateStatusBarText()
        }
        symLayoutController = SymLayoutController(this, prefs, altSymManager)
        keyboardVisibilityController = KeyboardVisibilityController(
            candidatesBarController = candidatesBarController,
            symLayoutController = symLayoutController,
            isInputViewActive = { isInputViewActive },
            isNavModeLatched = { ctrlLatchFromNavMode },
            currentInputConnection = { currentInputConnection },
            isInputViewShown = { isInputViewShown },
            attachInputView = { view -> setInputView(view) },
            setCandidatesViewShown = { shown -> setCandidatesViewShown(shown) },
            requestShowInputView = { requestShowSelf(0) },
            refreshStatusBar = { refreshStatusBar() }
        )
        launcherShortcutController = LauncherShortcutController(this)
        
        // Initialize keyboard layout
        loadKeyboardLayout()
        
        // Initialize nav mode mappings file if needed
        it.neuralrad.coolwulf.SettingsManager.initializeNavModeMappingsFile(this)
        ctrlKeyMap.putAll(KeyMappingLoader.loadCtrlKeyMappings(assets, this))
        variationStateController = VariationStateController(VariationRepository.loadVariations(assets))
        
        // Load auto-correction rules
        AutoCorrector.loadCorrections(assets, this)
        
        // Register listener for SharedPreferences changes
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
            if (key == "sym_mappings_custom") {
                Log.d(TAG, "SYM mappings page 1 changed, reloading...")
                // Reload SYM mappings for page 1
                altSymManager.reloadSymMappings()
                // Update status bar to reflect new mappings
                Handler(Looper.getMainLooper()).post {
                    updateStatusBarText()
                }
            } else if (key == "sym_mappings_page2_custom") {
                Log.d(TAG, "SYM mappings page 2 changed, reloading...")
                // Reload SYM mappings for page 2
                altSymManager.reloadSymMappings2()
                // Update status bar to reflect new mappings
                Handler(Looper.getMainLooper()).post {
                    updateStatusBarText()
                }
            } else if (key == "sym_mappings_page3_custom") {
                Log.d(TAG, "SYM mappings page 3 (Characters2) changed, reloading...")
                // Reload SYM mappings for page 3
                altSymManager.reloadSymMappings3()
                // Update status bar to reflect new mappings
                Handler(Looper.getMainLooper()).post {
                    updateStatusBarText()
                }
            } else if (key == "sym_pages_config") {
                Log.d(TAG, "SYM pages configuration changed, refreshing status bar...")
                Handler(Looper.getMainLooper()).post {
                    updateStatusBarText()
                }
            } else if (key == "clear_alt_on_space") {
                clearAltOnSpaceEnabled = SettingsManager.getClearAltOnSpace(this)
            } else if (key != null && (key.startsWith("auto_correct_custom_") || key == "auto_correct_enabled_languages")) {
                Log.d(TAG, "Auto-correction rules changed, reloading...")
                // Reload auto-corrections (including new custom languages)
                AutoCorrector.loadCorrections(assets, this)
            } else if (key == "nav_mode_mappings_updated") {
                Log.d(TAG, "Nav mode mappings changed, reloading...")
                // Reload nav mode key mappings
                reloadNavModeMappings()
            } else if (key == "keyboard_layout") {
                Log.d(TAG, "Keyboard layout changed, reloading...")
                // Reload keyboard layout
                loadKeyboardLayout()
            } else if (key == "trackpad_gestures_enabled") {
                val enabled = SettingsManager.getTrackpadGesturesEnabled(this)
                if (enabled) {
                    startTrackpadGestureDetection()
                } else {
                    stopTrackpadGestureDetection()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        
        // Register broadcast receiver for speech recognition (both online and offline)
        speechResultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "Broadcast receiver called - action: ${intent?.action}")
                // Handle Google (online) and Sherpa (offline) speech results
                val isSpeechResult = intent?.action == SpeechRecognitionActivity.ACTION_SPEECH_RESULT ||
                        intent?.action == SherpaSpeechActivity.ACTION_SHERPA_SPEECH_RESULT
                if (isSpeechResult) {
                    val text = intent?.getStringExtra(SpeechRecognitionActivity.EXTRA_TEXT)
                        ?: intent?.getStringExtra(SherpaSpeechActivity.EXTRA_TEXT)
                    Log.d(TAG, "Broadcast received with text: $text")
                    if (text != null && text.isNotEmpty()) {
                        Log.d(TAG, "Received speech recognition result: $text")

                        // Convert to traditional Chinese if setting is enabled
                        val finalText = if (SettingsManager.isTraditionalChineseMode(this@PhysicalKeyboardInputMethodService)) {
                            it.neuralrad.coolwulf.data.pinyin.ChineseCharacterConverter.toTraditional(text)
                        } else {
                            text
                        }

                        // Delay text insertion to give the system time to restore InputConnection
                        // after the speech recognition activity has closed.
                        Handler(Looper.getMainLooper()).postDelayed({
                            // Try multiple times if InputConnection is not immediately available
                            var attempts = 0
                            val maxAttempts = 10

                            fun tryInsertText() {
                                val inputConnection = currentInputConnection
                                if (inputConnection != null) {
                                    // Delete trailing space if voice was triggered by holding space
                                    if (voiceTriggeredByHoldSpace) {
                                        inputConnection.deleteSurroundingText(1, 0)
                                        voiceTriggeredByHoldSpace = false
                                    }
                                    inputConnection.commitText(finalText, 1)
                                    Log.d(TAG, "Speech text inserted successfully: $finalText")
                                } else {
                                    attempts++
                                    if (attempts < maxAttempts) {
                                        Log.d(TAG, "InputConnection not available, attempt $attempts/$maxAttempts, retrying in 100ms...")
                                        Handler(Looper.getMainLooper()).postDelayed({ tryInsertText() }, 100)
                                    } else {
                                        Log.w(TAG, "InputConnection not available after $maxAttempts attempts, text not inserted: $text")
                                    }
                                }
                            }

                            tryInsertText()
                        }, 300) // Wait 300ms before trying to insert text
                    }
                }
            }
        }

        // Register for online (Google) and offline (Sherpa) speech results
        val filter = IntentFilter().apply {
            addAction(SpeechRecognitionActivity.ACTION_SPEECH_RESULT)
            addAction(SherpaSpeechActivity.ACTION_SHERPA_SPEECH_RESULT)
        }

        // On Android 13+ (API 33+) we must specify whether the receiver is exported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(speechResultReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(speechResultReceiver, filter)
        }

        Log.d(TAG, "Broadcast receiver registered for speech results")

        // Register theme change receiver
        themeChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_THEME_CHANGED) {
                    Log.d(TAG, "Theme change broadcast received, refreshing UI")
                    // Refresh the status bar UI with the new theme
                    keyboardVisibilityController.refreshTheme()
                    candidatesBarController.refreshTheme()
                }
            }
        }

        val themeFilter = IntentFilter(ACTION_THEME_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(themeChangeReceiver, themeFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(themeChangeReceiver, themeFilter)
        }
        Log.d(TAG, "Broadcast receiver registered for theme changes")

        // Register ADB pairing result receiver to restart trackpad detection after pairing
        adbPairingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AdbPairingService.ACTION_PAIRING_RESULT) {
                    val success = intent.getBooleanExtra(AdbPairingService.EXTRA_RESULT_SUCCESS, false)
                    if (success && SettingsManager.getTrackpadGesturesEnabled(this@PhysicalKeyboardInputMethodService)) {
                        startTrackpadGestureDetection()
                    }
                }
            }
        }

        val adbPairingFilter = IntentFilter(AdbPairingService.ACTION_PAIRING_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(adbPairingReceiver, adbPairingFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(adbPairingReceiver, adbPairingFilter)
        }
        Log.d(TAG, "Broadcast receiver registered for ADB pairing results")

        // Start trackpad gesture detection if enabled
        if (SettingsManager.getTrackpadGesturesEnabled(this)) {
            startTrackpadGestureDetection()
        }
    }

    /**
     * Start the trackpad gesture detection using embedded ADB.
     * Uses getevent to monitor /dev/input/event7 for the Unihertz Titan 2 trackpad.
     * Requires wireless debugging to be enabled and paired.
     */
    private fun startTrackpadGestureDetection() {
        // Check if already running
        if (geteventJob?.isActive == true) {
            return
        }

        startTrackpadGestureDetectionEmbeddedADB()
    }

    /**
     * Start trackpad gesture detection using embedded ADB.
     *
     * Two modes of operation:
     * 1. Daemon mode (preferred): If a gesture daemon is running on localhost, connect to it.
     *    The daemon runs with shell privileges and survives WiFi disconnection.
     * 2. Direct ADB mode (fallback): Use ADB shell to run getevent directly.
     *    This requires WiFi connectivity but is used for initial setup or if daemon isn't running.
     *
     * After initial ADB pairing, we start the daemon so gestures work without WiFi.
     */
    private fun startTrackpadGestureDetectionEmbeddedADB() {
        val embeddedAdb = EmbeddedADB.getInstance(this)

        if (!embeddedAdb.isAdbAvailable()) {
            Log.w(TAG, "Embedded ADB binary not available")
            return
        }

        geteventJob = trackpadScope.launch {
            val baseDelayMs = 2000L
            val maxDelayMs = 30000L
            var retryCount = 0

            while (isActive) {
                try {
                    // First, try to connect directly to the local daemon (no WiFi needed)
                    // Don't check isGestureDaemonRunning() first as that would consume the connection
                    Log.d(TAG, "Attempting to connect to gesture daemon via localhost")
                    val connected = connectToGestureDaemon(embeddedAdb)
                    if (connected) {
                        retryCount = 0
                        // connectToGestureDaemon blocks until disconnection
                        // When it returns, we'll retry
                        Log.d(TAG, "Daemon connection ended, will retry")
                        delay(baseDelayMs)
                        continue
                    }

                    // Daemon connection failed - try to start it via ADB (requires WiFi)
                    Log.d(TAG, "Daemon not running, attempting ADB connection")

                    if (!embeddedAdb.isWirelessDebuggingEnabled()) {
                        Log.w(TAG, "Wireless debugging not enabled, waiting...")
                        delay(maxDelayMs)
                        continue
                    }

                    // Try to connect via ADB
                    if (!embeddedAdb.isConnected()) {
                        val portDiscovery = AdbPortDiscovery(this@PhysicalKeyboardInputMethodService)
                        val discoveredPort = portDiscovery.discoverPort()

                        val connected = if (discoveredPort == null) {
                            val lastPort = SettingsManager.getEmbeddedAdbPort(this@PhysicalKeyboardInputMethodService)
                            if (lastPort > 0) embeddedAdb.connect(lastPort) else false
                        } else {
                            SettingsManager.setEmbeddedAdbPort(this@PhysicalKeyboardInputMethodService, discoveredPort)
                            embeddedAdb.connect(discoveredPort)
                        }

                        if (!connected) {
                            val delayMs = minOf(baseDelayMs * (retryCount + 1), maxDelayMs)
                            Log.w(TAG, "ADB connection failed, retrying in ${delayMs}ms")
                            delay(delayMs)
                            retryCount++
                            continue
                        }
                    }

                    // ADB connected - start the daemon for future WiFi-independent operation
                    Log.d(TAG, "ADB connected, starting gesture daemon")
                    val daemonStarted = embeddedAdb.startGestureDaemon()

                    if (daemonStarted) {
                        Log.d(TAG, "Gesture daemon started successfully")
                        // Give daemon time to start listening
                        delay(1000)
                        // Now connect to the daemon
                        continue  // Loop back to connect to daemon
                    }

                    // Daemon failed to start, fall back to direct ADB mode
                    Log.w(TAG, "Daemon failed to start, using direct ADB mode")
                    retryCount = 0
                    runDirectAdbGetevent(embeddedAdb)

                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Trackpad detection coroutine cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Trackpad detection error", e)
                    embeddedAdb.markDisconnected()
                    val delayMs = minOf(baseDelayMs * (retryCount + 1), maxDelayMs)
                    delay(delayMs)
                    retryCount++
                }
            }
        }
    }

    /**
     * Connect to the gesture daemon via localhost socket and read events.
     * This method blocks until the connection is lost.
     *
     * @return true if we successfully connected and received events, false otherwise
     */
    private suspend fun connectToGestureDaemon(embeddedAdb: EmbeddedADB): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val socket = embeddedAdb.connectToDaemon()
            if (socket == null) {
                Log.w(TAG, "Failed to connect to gesture daemon")
                return@withContext false
            }

            Log.d(TAG, "Connected to gesture daemon, reading events")
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            try {
                while (true) {
                    val line = reader.readLine()
                    if (line == null) {
                        Log.w(TAG, "Daemon connection closed")
                        break
                    }
                    parseTrackpadEvent(line)
                }
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error reading from daemon: ${e.message}")
            false
        }
    }

    /**
     * Run getevent directly via ADB shell.
     * This is the fallback mode when daemon isn't available.
     * Requires WiFi/ADB connectivity.
     */
    private suspend fun runDirectAdbGetevent(embeddedAdb: EmbeddedADB) {
        val process = embeddedAdb.startShellCommand("getevent -l /dev/input/event7")
        if (process == null) {
            Log.w(TAG, "Failed to start getevent command")
            embeddedAdb.markDisconnected()
            return
        }

        Log.d(TAG, "Direct ADB getevent started")

        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine()
                    if (line == null) {
                        Log.w(TAG, "Getevent stream ended")
                        break
                    }
                    parseTrackpadEvent(line)
                }
            }
        } finally {
            process.destroyForcibly()
            embeddedAdb.markDisconnected()
        }
    }

    /**
     * Stop the trackpad gesture detection.
     */
    private fun stopTrackpadGestureDetection() {
        geteventJob?.cancel()
        geteventJob = null
        touchDown = false
        startPosSet = false
    }

    /**
     * Parse a line of getevent output and detect swipe gestures.
     * Format: /dev/input/event7: EV_KEY BTN_TOUCH DOWN
     * Format: /dev/input/event7: EV_ABS ABS_MT_POSITION_X 00000123
     */
    private fun parseTrackpadEvent(line: String) {
        try {
            when {
                line.contains("BTN_TOUCH") && line.contains("DOWN") -> {
                    touchDown = true
                    startPosSet = false
                    keyPressedDuringTouch = false  // Reset flag on new touch
                }
                line.contains("BTN_TOUCH") && line.contains("UP") -> {
                    if (touchDown) {
                        checkForSwipeGesture()
                    }
                    touchDown = false
                    startPosSet = false
                }
                line.contains("ABS_MT_POSITION_X") -> {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val hexValue = parts.last()
                        val newX = hexValue.toIntOrNull(16)
                        if (newX != null) {
                            currentX = newX
                            if (touchDown && !startPosSet) {
                                startX = newX
                            }
                        }
                    }
                }
                line.contains("ABS_MT_POSITION_Y") -> {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val hexValue = parts.last()
                        val newY = hexValue.toIntOrNull(16)
                        if (newY != null) {
                            currentY = newY
                            if (touchDown && !startPosSet) {
                                startY = newY
                                startPosSet = true
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Silently ignore parsing errors
        }
    }

    /**
     * Check if the current touch gesture is a valid swipe and trigger the appropriate action.
     * Swipe up = candidate selection (zone-based)
     * Swipe down = next page
     */
    private fun checkForSwipeGesture() {
        // Only process swipe gestures when in IME mode (actively editing text)
        if (currentInputConnection == null) {
            return
        }

        // Suppress swipe gestures if any key was pressed during this touch gesture
        // This prevents false triggers when typing vertically aligned keys like "ni"
        if (keyPressedDuringTouch) {
            return
        }

        // deltaY positive = swipe up, negative = swipe down
        val deltaY = startY - currentY
        val deltaX = currentX - startX
        val absDeltaX = kotlin.math.abs(deltaX)
        val absDeltaY = kotlin.math.abs(deltaY)

        // Require primarily vertical swipe: vertical movement must be at least 3x larger than horizontal drift
        if (absDeltaY > trackpadSwipeThreshold && absDeltaX < absDeltaY / 3) {
            if (deltaY > 0) {
                // Swipe UP - candidate selection
                val inChineseMode = isChineseInputModeActive()
                val isMaxThreeSuggestions = SettingsManager.getJuyingMaxThreeSuggestions(this)

                // Check if punctuation buttons are active
                val punctuationButtonsEnabled = SettingsManager.isJuyingPunctuationButtons(this)
                val fixedPositionsEnabled = SettingsManager.getJuyingFixedPositions(this)
                val isNextWordPrediction = when {
                    pinyinInputController.isPinyinMode() -> pinyinInputController.isShowingNextWordPredictions()
                    shuangpinInputController.isShuangpinMode() -> shuangpinInputController.isShowingNextWordPredictions()
                    wubiInputController.isWubiMode() -> wubiInputController.isShowingNextWordPredictions()
                    zhenmaInputController.isZhenmaMode() -> zhenmaInputController.isShowingNextWordPredictions()
                    else -> englishWordPredictionController.getSnapshot().isNextWordPrediction
                }
                val punctuationButtonsActive = punctuationButtonsEnabled && fixedPositionsEnabled && isNextWordPrediction

                Handler(Looper.getMainLooper()).post {
                    // Helper function to calculate zone with even spacing
                    fun calculateZone(x: Int, maxX: Int, numZones: Int): Int {
                        val zoneWidth = maxX.toFloat() / numZones
                        val zone = (x.toFloat() / zoneWidth).toInt()
                        return zone.coerceIn(0, numZones - 1)
                    }

                    if (punctuationButtonsActive) {
                        // Punctuation buttons mode (Chinese or English next word prediction)
                        // Get actual suggestion count to adapt layout
                        val suggestionCount = if (inChineseMode) {
                            when {
                                pinyinInputController.isPinyinMode() -> pinyinInputController.getCurrentPageCandidates().size
                                shuangpinInputController.isShuangpinMode() -> shuangpinInputController.getCurrentPageCandidates().size
                                wubiInputController.isWubiMode() -> wubiInputController.getCurrentPageCandidates().size
                                zhenmaInputController.isZhenmaMode() -> zhenmaInputController.getCurrentPageCandidates().size
                                ziranmaInputController.isZiranmaMode() -> ziranmaInputController.getCurrentPageCandidates().size
                                t9PinyinInputController.isT9Mode() -> t9PinyinInputController.getCurrentPageCandidates().size
                                else -> 3
                            }
                        } else {
                            englishWordPredictionController.getSnapshot().suggestions.size
                        }

                        // Layout adapts based on suggestion count:
                        // 0 suggestions: [leftPunct] [rightPunct] -> 2 zones
                        // 1 suggestion:  [leftPunct] [1st(best)] [rightPunct] -> 3 zones
                        // 2 suggestions: [leftPunct] [2nd] [1st(best)] [rightPunct] -> 4 zones
                        // 3+ suggestions: [leftPunct] [2nd] [1st(best)] [3rd] [rightPunct] -> 5 zones
                        when (minOf(3, suggestionCount)) {
                            0 -> {
                                // No suggestions: 2 zones for punctuation only
                                val zone = calculateZone(startX, trackpadMaxX, 2)
                                candidatesBarController.animateSwipeSelection(zone)
                                candidatesBarController.playSwipeSelectionSound()
                                acceptPunctuationBySwipe(isLeft = zone == 0)
                            }
                            1 -> {
                                // 1 suggestion: 3 zones [leftPunct] [1st] [rightPunct]
                                val zone = calculateZone(startX, trackpadMaxX, 3)
                                when (zone) {
                                    0 -> {
                                        candidatesBarController.animateSwipeSelection(zone)
                                        candidatesBarController.playSwipeSelectionSound()
                                        acceptPunctuationBySwipe(isLeft = true)
                                    }
                                    2 -> {
                                        candidatesBarController.animateSwipeSelection(zone)
                                        candidatesBarController.playSwipeSelectionSound()
                                        acceptPunctuationBySwipe(isLeft = false)
                                    }
                                    else -> {
                                        candidatesBarController.animateSwipeSelection(zone)
                                        candidatesBarController.playSwipeSelectionSound()
                                        if (inChineseMode) acceptChineseCandidateBySwipe(0) else acceptEnglishSuggestionBySwipe(0)
                                    }
                                }
                            }
                            2 -> {
                                // 2 suggestions: 4 zones [leftPunct] [2nd] [1st(best)] [rightPunct]
                                val zone = calculateZone(startX, trackpadMaxX, 4)
                                when (zone) {
                                    0 -> {
                                        candidatesBarController.animateSwipeSelection(zone)
                                        candidatesBarController.playSwipeSelectionSound()
                                        acceptPunctuationBySwipe(isLeft = true)
                                    }
                                    3 -> {
                                        candidatesBarController.animateSwipeSelection(zone)
                                        candidatesBarController.playSwipeSelectionSound()
                                        acceptPunctuationBySwipe(isLeft = false)
                                    }
                                    1 -> {
                                        // 2nd suggestion (index 1)
                                        candidatesBarController.animateSwipeSelection(zone)
                                        candidatesBarController.playSwipeSelectionSound()
                                        if (inChineseMode) acceptChineseCandidateBySwipe(1) else acceptEnglishSuggestionBySwipe(1)
                                    }
                                    else -> {
                                        // 1st/best suggestion (index 0)
                                        candidatesBarController.animateSwipeSelection(zone)
                                        candidatesBarController.playSwipeSelectionSound()
                                        if (inChineseMode) acceptChineseCandidateBySwipe(0) else acceptEnglishSuggestionBySwipe(0)
                                    }
                                }
                            }
                            else -> {
                                // 3+ suggestions: 5 zones [leftPunct] [2nd] [1st(best)] [3rd] [rightPunct]
                                val zone = calculateZone(startX, trackpadMaxX, 5)
                                when (zone) {
                                    0 -> {
                                        candidatesBarController.animateSwipeSelection(zone)
                                        candidatesBarController.playSwipeSelectionSound()
                                        acceptPunctuationBySwipe(isLeft = true)
                                    }
                                    4 -> {
                                        candidatesBarController.animateSwipeSelection(zone)
                                        candidatesBarController.playSwipeSelectionSound()
                                        acceptPunctuationBySwipe(isLeft = false)
                                    }
                                    else -> {
                                        val suggestionIndex = when (zone) {
                                            1 -> 1  // 2nd suggestion
                                            2 -> 0  // 1st (best) suggestion
                                            3 -> 2  // 3rd suggestion
                                            else -> 0
                                        }
                                        candidatesBarController.animateSwipeSelection(zone)
                                        candidatesBarController.playSwipeSelectionSound()
                                        if (inChineseMode) acceptChineseCandidateBySwipe(suggestionIndex) else acceptEnglishSuggestionBySwipe(suggestionIndex)
                                    }
                                }
                            }
                        }
                    } else if (inChineseMode) {
                        // Get actual candidate count to determine zone layout
                        val candidateCount = when {
                            pinyinInputController.isPinyinMode() -> pinyinInputController.getCurrentPageCandidates().size
                            shuangpinInputController.isShuangpinMode() -> shuangpinInputController.getCurrentPageCandidates().size
                            wubiInputController.isWubiMode() -> wubiInputController.getCurrentPageCandidates().size
                            zhenmaInputController.isZhenmaMode() -> zhenmaInputController.getCurrentPageCandidates().size
                            ziranmaInputController.isZiranmaMode() -> ziranmaInputController.getCurrentPageCandidates().size
                            t9PinyinInputController.isT9Mode() -> t9PinyinInputController.getCurrentPageCandidates().size
                            else -> 5
                        }

                        // Determine max zones based on settings and actual candidate count
                        val maxZones = if (isMaxThreeSuggestions) minOf(3, candidateCount) else minOf(5, candidateCount)

                        when (maxZones) {
                            0 -> {
                                // No candidates, do nothing
                            }
                            1 -> {
                                // Only 1 candidate: full width selects it
                                candidatesBarController.animateSwipeSelection(0)
                                candidatesBarController.playSwipeSelectionSound()
                                acceptChineseCandidateBySwipe(0)
                            }
                            2 -> {
                                // 2 candidates: layout [2nd, 1st] -> 2 zones
                                val zone = calculateZone(startX, trackpadMaxX, 2)
                                val candidateIndex = when (zone) {
                                    0 -> 1  // Left half -> 2nd candidate
                                    1 -> 0  // Right half -> 1st (best) candidate
                                    else -> 0
                                }
                                candidatesBarController.animateSwipeSelection(zone)
                                candidatesBarController.playSwipeSelectionSound()
                                acceptChineseCandidateBySwipe(candidateIndex)
                            }
                            3 -> {
                                // 3 candidates: layout [2nd, 1st, 3rd] -> 3 zones
                                val zone = calculateZone(startX, trackpadMaxX, 3)
                                val candidateIndex = when (zone) {
                                    0 -> 1  // Left third -> 2nd candidate
                                    1 -> 0  // Center -> 1st (best) candidate
                                    2 -> 2  // Right third -> 3rd candidate
                                    else -> 0
                                }
                                candidatesBarController.animateSwipeSelection(zone)
                                candidatesBarController.playSwipeSelectionSound()
                                acceptChineseCandidateBySwipe(candidateIndex)
                            }
                            4 -> {
                                // 4 candidates: layout [2nd, 3rd, 1st, 4th] -> 4 zones
                                val zone = calculateZone(startX, trackpadMaxX, 4)
                                val candidateIndex = when (zone) {
                                    0 -> 1  // 1st quarter -> 2nd candidate
                                    1 -> 2  // 2nd quarter -> 3rd candidate
                                    2 -> 0  // 3rd quarter -> 1st (best) candidate
                                    3 -> 3  // 4th quarter -> 4th candidate
                                    else -> 0
                                }
                                candidatesBarController.animateSwipeSelection(zone)
                                candidatesBarController.playSwipeSelectionSound()
                                acceptChineseCandidateBySwipe(candidateIndex)
                            }
                            else -> {
                                // 5+ candidates: layout [2nd, 3rd, 1st, 4th, 5th] -> 5 zones
                                val zone = calculateZone(startX, trackpadMaxX, 5)
                                val candidateIndex = when (zone) {
                                    0 -> 1  // Left fifth -> 2nd candidate
                                    1 -> 2  // Second fifth -> 3rd candidate
                                    2 -> 0  // Center -> 1st (best) candidate
                                    3 -> 3  // Fourth fifth -> 4th candidate
                                    4 -> 4  // Right fifth -> 5th candidate
                                    else -> 0
                                }
                                candidatesBarController.animateSwipeSelection(zone)
                                candidatesBarController.playSwipeSelectionSound()
                                acceptChineseCandidateBySwipe(candidateIndex)
                            }
                        }
                    } else {
                        // English Juying mode without punctuation buttons
                        // Display layout: [1st best (left)] [current typed word (center)] [2nd best (right)]
                        val suggestionCount = englishWordPredictionController.getSnapshot().suggestions.size
                        val maxZones = minOf(3, suggestionCount)

                        when (maxZones) {
                            0 -> {
                                // No suggestions, do nothing
                            }
                            1 -> {
                                // Only 1 suggestion: full width selects it
                                candidatesBarController.animateSwipeSelection(0)
                                candidatesBarController.playSwipeSelectionSound()
                                acceptEnglishSuggestionBySwipe(0)
                            }
                            2 -> {
                                // 2 suggestions: [1st best (left), 2nd (right)] -> 2 zones
                                val zone = calculateZone(startX, trackpadMaxX, 2)
                                candidatesBarController.animateSwipeSelection(zone)
                                candidatesBarController.playSwipeSelectionSound()
                                acceptEnglishSuggestionBySwipe(zone)
                            }
                            else -> {
                                // 3 suggestions: [1st best (left)] [typed word (center)] [2nd best (right)] -> 3 zones
                                val zone = calculateZone(startX, trackpadMaxX, 3)
                                candidatesBarController.animateSwipeSelection(zone)
                                candidatesBarController.playSwipeSelectionSound()
                                acceptEnglishSuggestionBySwipe(zone)
                            }
                        }
                    }
                }
            } else {
                // Swipe DOWN - page navigation
                Handler(Looper.getMainLooper()).post {
                    val splitSwipeDownEnabled = SettingsManager.getSplitSwipeDownEnabled(this@PhysicalKeyboardInputMethodService)
                    if (splitSwipeDownEnabled) {
                        // Split mode: left half = prev page, right half = next page
                        val isLeftHalf = startX < trackpadMaxX / 2
                        if (isLeftHalf) {
                            handleSwipeDownPrevPage()
                        } else {
                            handleSwipeDownNextPage()
                        }
                    } else {
                        // Default: swipe down anywhere = next page
                        handleSwipeDownNextPage()
                    }
                }
            }
        }
    }

    /**
     * Accept a punctuation character by swipe gesture.
     */
    private fun acceptPunctuationBySwipe(isLeft: Boolean) {
        val ic = currentInputConnection ?: return

        // Get the custom punctuation from settings
        val punctuationEnglish = if (isLeft) {
            SettingsManager.getJuyingPunctuationLeft(this)
        } else {
            SettingsManager.getJuyingPunctuationRight(this)
        }

        // Convert to Chinese punctuation if in Chinese punctuation mode
        val punctuation = if (isChinesePunctuationModeActive()) {
            SettingsManager.getChinesePunctuation(punctuationEnglish)
        } else {
            punctuationEnglish
        }

        // Commit the punctuation
        ic.commitText(punctuation, 1)

        // Clear next word predictions after punctuation
        when {
            pinyinInputController.isPinyinMode() -> pinyinInputController.clearNextWordPredictions()
            shuangpinInputController.isShuangpinMode() -> shuangpinInputController.clearNextWordPredictions()
            wubiInputController.isWubiMode() -> wubiInputController.clearNextWordPredictions()
            zhenmaInputController.isZhenmaMode() -> zhenmaInputController.clearNextWordPredictions()
            else -> {
                englishWordPredictionController.clearNextWordPredictions()
                englishWordPredictionController.clearSuggestions()
            }
        }
        updateStatusBarText()
    }

    /**
     * Handle swipe down gesture for next page navigation.
     */
    private fun handleSwipeDownNextPage() {
        val isPinyinMode = pinyinInputController.isPinyinMode()
        val isShuangpinMode = shuangpinInputController.isShuangpinMode()
        val isWubiMode = wubiInputController.isWubiMode()
        val isZhenmaMode = zhenmaInputController.isZhenmaMode()
        val isT9PinyinMode = t9PinyinInputController.isT9Mode()
        val isWordPredictionActive = englishWordPredictionController.hasActivePrediction()

        val hasPinyinCandidates = isPinyinMode && pinyinInputController.hasCandidates()
        val hasShuangpinCandidates = isShuangpinMode && shuangpinInputController.hasCandidates()
        val hasWubiCandidates = isWubiMode && wubiInputController.hasCandidates()
        val hasZhenmaCandidates = isZhenmaMode && zhenmaInputController.hasCandidates()
        val hasT9PinyinCandidates = isT9PinyinMode && t9PinyinInputController.hasCandidates()
        val hasWordPredictions = isWordPredictionActive && englishWordPredictionController.hasSuggestions()

        when {
            hasPinyinCandidates -> {
                pinyinInputController.nextPage()
                updateStatusBarText()
            }
            hasShuangpinCandidates -> {
                shuangpinInputController.nextPage()
                updateStatusBarText()
            }
            hasWubiCandidates -> {
                wubiInputController.nextPage()
                updateStatusBarText()
            }
            hasZhenmaCandidates -> {
                zhenmaInputController.nextPage()
                updateStatusBarText()
            }
            hasT9PinyinCandidates -> {
                t9PinyinInputController.nextPage()
                updateStatusBarText()
            }
            hasWordPredictions -> {
                englishWordPredictionController.nextPage()
                updateStatusBarText()
            }
        }
    }

    /**
     * Handle swipe down gesture for previous page navigation (left half of trackpad).
     */
    private fun handleSwipeDownPrevPage() {
        val isPinyinMode = pinyinInputController.isPinyinMode()
        val isShuangpinMode = shuangpinInputController.isShuangpinMode()
        val isWubiMode = wubiInputController.isWubiMode()
        val isZhenmaMode = zhenmaInputController.isZhenmaMode()
        val isT9PinyinMode = t9PinyinInputController.isT9Mode()
        val isWordPredictionActive = englishWordPredictionController.hasActivePrediction()

        val hasPinyinCandidates = isPinyinMode && pinyinInputController.hasCandidates()
        val hasShuangpinCandidates = isShuangpinMode && shuangpinInputController.hasCandidates()
        val hasWubiCandidates = isWubiMode && wubiInputController.hasCandidates()
        val hasZhenmaCandidates = isZhenmaMode && zhenmaInputController.hasCandidates()
        val hasT9PinyinCandidates = isT9PinyinMode && t9PinyinInputController.hasCandidates()
        val hasWordPredictions = isWordPredictionActive && englishWordPredictionController.hasSuggestions()

        when {
            hasPinyinCandidates -> {
                pinyinInputController.prevPage()
                updateStatusBarText()
            }
            hasShuangpinCandidates -> {
                shuangpinInputController.prevPage()
                updateStatusBarText()
            }
            hasWubiCandidates -> {
                wubiInputController.prevPage()
                updateStatusBarText()
            }
            hasZhenmaCandidates -> {
                zhenmaInputController.prevPage()
                updateStatusBarText()
            }
            hasT9PinyinCandidates -> {
                t9PinyinInputController.prevPage()
                updateStatusBarText()
            }
            hasWordPredictions -> {
                englishWordPredictionController.prevPage()
                updateStatusBarText()
            }
        }
    }

    /**
     * Accept a Chinese candidate by swipe gesture.
     * Works with Pinyin, Shuangpin, Wubi, Zhenma, Ziranma, and T9 input modes.
     * Commits the selected character and handles the remaining buffer.
     */
    private fun acceptChineseCandidateBySwipe(index: Int) {
        val ic = currentInputConnection ?: return

        when {
            pinyinInputController.isPinyinMode() && pinyinInputController.hasCandidates() -> {
                val candidates = pinyinInputController.getCandidates()
                if (index < candidates.size) {
                    val selected = pinyinInputController.selectCandidate(index)
                    if (selected != null) {
                        ic.commitText(selected, 1)
                        val remainingBuffer = pinyinInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }
                    }
                    updateStatusBarText()
                }
            }
            shuangpinInputController.isShuangpinMode() && shuangpinInputController.hasCandidates() -> {
                val candidates = shuangpinInputController.getCandidates()
                if (index < candidates.size) {
                    val selected = shuangpinInputController.selectCandidate(index)
                    if (selected != null) {
                        ic.commitText(selected, 1)
                        val remainingBuffer = shuangpinInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }
                    }
                    updateStatusBarText()
                }
            }
            wubiInputController.isWubiMode() && wubiInputController.hasCandidates() -> {
                val candidates = wubiInputController.getCandidates()
                if (index < candidates.size) {
                    val selected = wubiInputController.selectCandidate(index)
                    if (selected != null) {
                        ic.commitText(selected, 1)
                        val remainingBuffer = wubiInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }
                    }
                    updateStatusBarText()
                }
            }
            zhenmaInputController.isZhenmaMode() && zhenmaInputController.hasCandidates() -> {
                val candidates = zhenmaInputController.getCandidates()
                if (index < candidates.size) {
                    val selected = zhenmaInputController.selectCandidate(index)
                    if (selected != null) {
                        ic.commitText(selected, 1)
                        val remainingBuffer = zhenmaInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }
                    }
                    updateStatusBarText()
                }
            }
            ziranmaInputController.isZiranmaMode() && ziranmaInputController.hasCandidates() -> {
                val candidates = ziranmaInputController.getCandidates()
                if (index < candidates.size) {
                    val selected = ziranmaInputController.selectCandidate(index)
                    if (selected != null) {
                        ic.commitText(selected, 1)
                        val remainingBuffer = ziranmaInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }
                    }
                    updateStatusBarText()
                }
            }
            t9PinyinInputController.isT9Mode() && t9PinyinInputController.hasCandidates() -> {
                val candidates = t9PinyinInputController.getCurrentPageCandidates()
                if (index < candidates.size) {
                    val selected = t9PinyinInputController.selectCandidate(index)
                    if (selected != null) {
                        ic.commitText(selected, 1)
                        val remainingBuffer = t9PinyinInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }
                    }
                    updateStatusBarText()
                }
            }
        }
    }

    /**
     * Accept an English suggestion by swipe gesture.
     */
    private fun acceptEnglishSuggestionBySwipe(index: Int) {
        val ic = currentInputConnection ?: return

        if (!englishWordPredictionController.hasSuggestions()) {
            return
        }

        val result = englishWordPredictionController.selectSuggestion(index)
        if (result != null) {
            ic.deleteSurroundingText(result.prefixLength, 0)
            ic.commitText(result.word + " ", 1)
            englishWordPredictionController.updateFromCursor(ic)
            updateStatusBarText()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove listener when service is destroyed
        prefsListener?.let {
            prefs.unregisterOnSharedPreferenceChangeListener(it)
        }
        
        // Unregister broadcast receiver
        speechResultReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error while unregistering broadcast receiver", e)
            }
        }
        speechResultReceiver = null

        // Unregister theme change receiver
        themeChangeReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error while unregistering theme change receiver", e)
            }
        }
        themeChangeReceiver = null

        // Unregister ADB pairing receiver
        adbPairingReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error while unregistering ADB pairing receiver", e)
            }
        }
        adbPairingReceiver = null

        // Stop clipboard history listener
        it.neuralrad.coolwulf.core.ClipboardHistoryManager.stopListening(this)

        // Release SoundPool resources
        soundPool?.release()
        soundPool = null
        soundLoaded = false

        // Stop trackpad gesture detection
        stopTrackpadGestureDetection()
        trackpadScope.cancel()
    }

    // Track the last known UI mode to detect theme changes
    private var lastUiMode: Int = 0

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        // Check if the UI mode (dark/light theme) has changed
        val currentUiMode = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (lastUiMode != 0 && currentUiMode != lastUiMode) {
            Log.d(TAG, "System theme changed, refreshing UI (was: $lastUiMode, now: $currentUiMode)")
            // Post to main handler to ensure configuration is fully applied before refreshing theme
            android.os.Handler(mainLooper).post {
                // Refresh the status bar UI with the new theme
                keyboardVisibilityController.refreshTheme()
                candidatesBarController.refreshTheme()
            }
        }
        lastUiMode = currentUiMode
    }

    override fun onCreateInputView(): View? = keyboardVisibilityController.onCreateInputView()

    /**
     * Creates the candidates view shown when the soft keyboard is disabled.
     * Uses a separate StatusBarController instance to provide identical functionality.
     */
    override fun onCreateCandidatesView(): View? = keyboardVisibilityController.onCreateCandidatesView()

    /**
     * Determines whether the input view (soft keyboard) should be shown.
     * Respects the system flag (e.g. "Mostra tastiera virtuale" off for tastiere fisiche):
     * when the system asks for candidate-only mode we hide the main status UI and
     * expose the slim candidates view (LED strip + SYM layout on demand).
     */
    override fun onEvaluateInputViewShown(): Boolean {
        val shouldShowInputView = super.onEvaluateInputViewShown()
        return keyboardVisibilityController.onEvaluateInputViewShown(shouldShowInputView)
    }

    /**
     * Computes the insets for the IME window.
     * This increases the "content" area to include the candidate view area,
     * allowing the application to shift upwards properly without the candidates view
     * covering system UI.
     */
    override fun onComputeInsets(outInsets: InputMethodService.Insets?) {
        super.onComputeInsets(outInsets)

        if (outInsets != null) {
            // In compact mode with no suggestions, report zero height to hide system IME bar
            // But keep normal height when virtual keyboard is enabled
            if (isCompactModeHidden && !isVirtualKeyboardEnabled) {
                outInsets.contentTopInsets = outInsets.visibleTopInsets
                outInsets.visibleTopInsets = outInsets.visibleTopInsets
                // Set touchable region to empty so input goes through to the app
                outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_CONTENT
                return
            }

            if (!isFullscreenMode()) {
                outInsets.contentTopInsets = outInsets.visibleTopInsets
            }
        }
    }

    /**
     * Resets all modifier key states.
     * Called when leaving a field or closing/reopening the keyboard.
     * @param preserveNavMode If true, keeps Ctrl latch active when nav mode is enabled.
     */
    private fun resetModifierStates(preserveNavMode: Boolean = false) {
        modifierStateController.resetModifiers(
            preserveNavMode = preserveNavMode,
            onNavModeCancelled = { navModeController.cancelNotification() }
        )
        
        symLayoutController.reset()
        altSymManager.resetTransientState()
        deactivateVariations()
        refreshStatusBar()
    }
    
    /**
     * Forces creation and display of the input view.
     * Called when the first physical key is pressed.
     * Shows the keyboard if there is an active text field.
     * IMPORTANT: UI is never shown in nav mode.
     */
    private fun ensureInputViewCreated() {
        keyboardVisibilityController.ensureInputViewCreated()
    }

    /**
     * Toggles Chinese input mode on/off.
     * When multiple methods are enabled, cycles through them: EN -> Pinyin -> Shuangpin -> Ziranma -> Wubi -> Zhenma -> EN
     * When only one is enabled, toggles that mode on/off.
     * Called from EN/CN toggle button and Shift+Enter shortcut.
     */
    private fun toggleChineseInputMode() {
        val enabledMethods = SettingsManager.getEnabledChineseInputMethods(this)

        if (enabledMethods.isEmpty()) {
            // No Chinese input methods enabled, do nothing
            updateStatusBarText()
            return
        }

        val isPinyinActive = pinyinInputController.isPinyinMode()
        val isT9PinyinActive = t9PinyinInputController.isT9Mode()
        val isShuangpinActive = shuangpinInputController.isShuangpinMode()
        val isZiranmaActive = ziranmaInputController.isZiranmaMode()
        val isWubiActive = wubiInputController.isWubiMode()
        val isZhenmaActive = zhenmaInputController.isZhenmaMode()

        if (enabledMethods.size == 1) {
            // Only one method enabled - simple toggle
            val method = enabledMethods[0]
            when (method) {
                "pinyin" -> {
                    pinyinInputController.togglePinyinMode()
                    if (!pinyinInputController.isPinyinMode()) {
                        currentInputConnection?.finishComposingText()
                    }
                }
                "t9pinyin" -> {
                    t9PinyinInputController.toggleT9Mode()
                    if (!t9PinyinInputController.isT9Mode()) {
                        currentInputConnection?.finishComposingText()
                    }
                }
                "shuangpin" -> {
                    shuangpinInputController.toggleShuangpinMode()
                    if (!shuangpinInputController.isShuangpinMode()) {
                        currentInputConnection?.finishComposingText()
                    }
                }
                "ziranma" -> {
                    ziranmaInputController.toggleZiranmaMode()
                    if (!ziranmaInputController.isZiranmaMode()) {
                        currentInputConnection?.finishComposingText()
                    }
                }
                "wubi" -> {
                    wubiInputController.toggleWubiMode()
                    if (!wubiInputController.isWubiMode()) {
                        currentInputConnection?.finishComposingText()
                    }
                }
                "zhenma" -> {
                    zhenmaInputController.toggleZhenmaMode()
                    if (!zhenmaInputController.isZhenmaMode()) {
                        currentInputConnection?.finishComposingText()
                    }
                }
            }
        } else {
            // Multiple methods enabled - cycle through them in order: EN -> first -> second -> ... -> EN
            // Order is based on enabledMethods list: pinyin, t9pinyin, shuangpin, ziranma, wubi, zhenma
            val currentMethod = when {
                isPinyinActive -> "pinyin"
                isT9PinyinActive -> "t9pinyin"
                isShuangpinActive -> "shuangpin"
                isZiranmaActive -> "ziranma"
                isWubiActive -> "wubi"
                isZhenmaActive -> "zhenma"
                else -> null  // EN mode
            }

            val currentIndex = currentMethod?.let { enabledMethods.indexOf(it) } ?: -1
            val nextIndex = (currentIndex + 1) % (enabledMethods.size + 1)  // +1 for EN mode

            // Deactivate current mode
            when (currentMethod) {
                "pinyin" -> {
                    pinyinInputController.setPinyinMode(false)
                    currentInputConnection?.finishComposingText()
                }
                "t9pinyin" -> {
                    t9PinyinInputController.setT9Mode(false)
                    currentInputConnection?.finishComposingText()
                }
                "shuangpin" -> {
                    shuangpinInputController.setShuangpinMode(false)
                    currentInputConnection?.finishComposingText()
                }
                "ziranma" -> {
                    ziranmaInputController.setZiranmaMode(false)
                    currentInputConnection?.finishComposingText()
                }
                "wubi" -> {
                    wubiInputController.setWubiMode(false)
                    currentInputConnection?.finishComposingText()
                }
                "zhenma" -> {
                    zhenmaInputController.setZhenmaMode(false)
                    currentInputConnection?.finishComposingText()
                }
            }

            // Activate next mode (if not cycling to EN)
            if (nextIndex < enabledMethods.size) {
                when (enabledMethods[nextIndex]) {
                    "pinyin" -> pinyinInputController.setPinyinMode(true)
                    "t9pinyin" -> t9PinyinInputController.setT9Mode(true)
                    "shuangpin" -> shuangpinInputController.setShuangpinMode(true)
                    "ziranma" -> ziranmaInputController.setZiranmaMode(true)
                    "wubi" -> wubiInputController.setWubiMode(true)
                    "zhenma" -> zhenmaInputController.setZhenmaMode(true)
                }
            }
            // else: cycle to EN mode, all modes are already deactivated
        }

        // Save the current mode to remember across restarts
        SettingsManager.setLastInputMode(this, getCurrentInputMode())

        updateStatusBarText()
    }

    /**
     * Checks if any Chinese input mode is active (Pinyin, T9 Pinyin, Shuangpin, Ziranma, Wubi, or Zhenma).
     */
    private fun isChineseInputModeActive(): Boolean {
        return pinyinInputController.isPinyinMode() || t9PinyinInputController.isT9Mode() || shuangpinInputController.isShuangpinMode() || ziranmaInputController.isZiranmaMode() || wubiInputController.isWubiMode() || zhenmaInputController.isZhenmaMode()
    }

    /**
     * Gets the current input mode as a string ("english", "pinyin", "t9pinyin", "shuangpin", "ziranma", "wubi", or "zhenma").
     */
    private fun getCurrentInputMode(): String {
        return when {
            pinyinInputController.isPinyinMode() -> "pinyin"
            t9PinyinInputController.isT9Mode() -> "t9pinyin"
            shuangpinInputController.isShuangpinMode() -> "shuangpin"
            ziranmaInputController.isZiranmaMode() -> "ziranma"
            wubiInputController.isWubiMode() -> "wubi"
            zhenmaInputController.isZhenmaMode() -> "zhenma"
            else -> "english"
        }
    }

    /**
     * Sets the input mode directly without cycling.
     * @param mode One of "english", "pinyin", "shuangpin", "ziranma", "wubi", or "zhenma"
     * @param saveToSettings If true, saves this mode as the last used mode
     */
    private fun setInputMode(mode: String, saveToSettings: Boolean = true) {
        // Deactivate all modes first
        if (pinyinInputController.isPinyinMode()) {
            pinyinInputController.setPinyinMode(false)
            currentInputConnection?.finishComposingText()
        }
        if (t9PinyinInputController.isT9Mode()) {
            t9PinyinInputController.setT9Mode(false)
            currentInputConnection?.finishComposingText()
        }
        if (shuangpinInputController.isShuangpinMode()) {
            shuangpinInputController.setShuangpinMode(false)
            currentInputConnection?.finishComposingText()
        }
        if (ziranmaInputController.isZiranmaMode()) {
            ziranmaInputController.setZiranmaMode(false)
            currentInputConnection?.finishComposingText()
        }
        if (wubiInputController.isWubiMode()) {
            wubiInputController.setWubiMode(false)
            currentInputConnection?.finishComposingText()
        }
        if (zhenmaInputController.isZhenmaMode()) {
            zhenmaInputController.setZhenmaMode(false)
            currentInputConnection?.finishComposingText()
        }

        // Activate the requested mode (if enabled)
        when (mode) {
            "pinyin" -> {
                if (SettingsManager.getPinyinEnabled(this)) {
                    pinyinInputController.setPinyinMode(true)
                }
            }
            "t9pinyin" -> {
                if (SettingsManager.getT9PinyinEnabled(this)) {
                    t9PinyinInputController.setT9Mode(true)
                }
            }
            "shuangpin" -> {
                if (SettingsManager.getShuangpinEnabled(this)) {
                    shuangpinInputController.setShuangpinMode(true)
                }
            }
            "ziranma" -> {
                if (SettingsManager.getZiranmaEnabled(this)) {
                    ziranmaInputController.setZiranmaMode(true)
                }
            }
            "wubi" -> {
                if (SettingsManager.getWubiEnabled(this)) {
                    wubiInputController.setWubiMode(true)
                }
            }
            "zhenma" -> {
                if (SettingsManager.getZhenmaEnabled(this)) {
                    zhenmaInputController.setZhenmaMode(true)
                }
            }
            // "english" or unknown - all modes already deactivated
        }

        // Save the current mode if requested
        if (saveToSettings) {
            SettingsManager.setLastInputMode(this, getCurrentInputMode())
        }

        updateStatusBarText()
    }

    /**
     * Applies the startup input mode based on settings.
     * For special text fields (URL, number, password, etc.), English is forced.
     * Otherwise, uses the last saved input mode.
     */
    private fun applyStartupInputMode() {
        val state = inputContextState

        // Force English for special text fields
        if (state.shouldForceEnglishInput) {
            setInputMode("english", saveToSettings = false)
            return
        }

        // Apply the effective startup mode (last used, validated against enabled methods)
        val startupMode = SettingsManager.getEffectiveStartupInputMode(this)
        setInputMode(startupMode, saveToSettings = false)
    }

    /**
     * Toggles between Chinese and English punctuation modes.
     * All Chinese input controllers share the same state for consistency.
     */
    private fun togglePunctuationMode() {
        val isPinyinMode = pinyinInputController.isPinyinMode()
        val isShuangpinMode = shuangpinInputController.isShuangpinMode()
        val isZiranmaMode = ziranmaInputController.isZiranmaMode()
        val isWubiMode = wubiInputController.isWubiMode()
        val isZhenmaMode = zhenmaInputController.isZhenmaMode()

        if (isPinyinMode) {
            pinyinInputController.togglePunctuationMode()
            // Sync other controllers to match
            val newMode = pinyinInputController.isChinesePunctuationMode()
            shuangpinInputController.setChinesePunctuationMode(newMode)
            ziranmaInputController.setChinesePunctuationMode(newMode)
            wubiInputController.setChinesePunctuationMode(newMode)
            zhenmaInputController.setChinesePunctuationMode(newMode)
        } else if (isShuangpinMode) {
            shuangpinInputController.togglePunctuationMode()
            // Sync other controllers to match
            val newMode = shuangpinInputController.isChinesePunctuationMode()
            pinyinInputController.setChinesePunctuationMode(newMode)
            ziranmaInputController.setChinesePunctuationMode(newMode)
            wubiInputController.setChinesePunctuationMode(newMode)
            zhenmaInputController.setChinesePunctuationMode(newMode)
        } else if (isZiranmaMode) {
            ziranmaInputController.togglePunctuationMode()
            // Sync other controllers to match
            val newMode = ziranmaInputController.isChinesePunctuationMode()
            pinyinInputController.setChinesePunctuationMode(newMode)
            shuangpinInputController.setChinesePunctuationMode(newMode)
            wubiInputController.setChinesePunctuationMode(newMode)
            zhenmaInputController.setChinesePunctuationMode(newMode)
        } else if (isWubiMode) {
            wubiInputController.togglePunctuationMode()
            // Sync other controllers to match
            val newMode = wubiInputController.isChinesePunctuationMode()
            pinyinInputController.setChinesePunctuationMode(newMode)
            shuangpinInputController.setChinesePunctuationMode(newMode)
            ziranmaInputController.setChinesePunctuationMode(newMode)
            zhenmaInputController.setChinesePunctuationMode(newMode)
        } else if (isZhenmaMode) {
            zhenmaInputController.togglePunctuationMode()
            // Sync other controllers to match
            val newMode = zhenmaInputController.isChinesePunctuationMode()
            pinyinInputController.setChinesePunctuationMode(newMode)
            shuangpinInputController.setChinesePunctuationMode(newMode)
            ziranmaInputController.setChinesePunctuationMode(newMode)
            wubiInputController.setChinesePunctuationMode(newMode)
        }
        updateStatusBarText()
    }

    /**
     * Toggles between simplified and traditional Chinese mode.
     */
    private fun toggleTraditionalChineseMode() {
        SettingsManager.toggleChineseCharacterSet(this)
        // Update the status bar to reflect the change
        updateStatusBarText()
    }

    /**
     * Returns whether Chinese punctuation mode is currently active.
     */
    private fun isChinesePunctuationModeActive(): Boolean {
        return if (pinyinInputController.isPinyinMode()) {
            pinyinInputController.isChinesePunctuationMode()
        } else if (t9PinyinInputController.isT9Mode()) {
            // T9 mode uses Pinyin's Chinese punctuation setting
            pinyinInputController.isChinesePunctuationMode()
        } else if (shuangpinInputController.isShuangpinMode()) {
            shuangpinInputController.isChinesePunctuationMode()
        } else if (ziranmaInputController.isZiranmaMode()) {
            ziranmaInputController.isChinesePunctuationMode()
        } else if (wubiInputController.isWubiMode()) {
            wubiInputController.isChinesePunctuationMode()
        } else if (zhenmaInputController.isZhenmaMode()) {
            zhenmaInputController.isChinesePunctuationMode()
        } else {
            false // English mode - use English punctuation
        }
    }

    /**
     * Builds a map of candidate -> actual Wubi code for Z key wildcard learning.
     * Only returns non-empty map when using wildcard mode.
     */
    private fun buildWubiCandidateCodesMap(): Map<String, String> {
        if (!wubiInputController.isWubiMode() || !wubiInputController.isUsingWildcard()) {
            return emptyMap()
        }
        val result = mutableMapOf<String, String>()
        for (candidate in wubiInputController.getCandidates()) {
            val code = wubiInputController.getActualCodeForCandidate(candidate)
            if (code.isNotEmpty()) {
                result[candidate] = code
            }
        }
        return result
    }

    /**
     * Toggles the virtual keyboard on/off.
     */
    private fun toggleVirtualKeyboard() {
        isVirtualKeyboardEnabled = !isVirtualKeyboardEnabled
        SettingsManager.setVirtualKeyboardEnabled(this, isVirtualKeyboardEnabled)
        candidatesBarController.setVirtualKeyboardEnabled(isVirtualKeyboardEnabled)
        updateStatusBarText()
    }

    /**
     * Handles key press from the virtual keyboard.
     * @param keyCode The key code (e.g., KEYCODE_SPACE, KEYCODE_DEL, KEYCODE_ENTER)
     * @param isShifted Whether shift is active on the virtual keyboard
     */
    private fun handleVirtualKeyPress(keyCode: Int, isShifted: Boolean) {
        val ic = currentInputConnection ?: return

        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                // Handle space - select first candidate if in Chinese mode, otherwise insert space
                if (pinyinInputController.hasCandidates()) {
                    val selected = pinyinInputController.selectFirstCandidate()
                    if (selected != null) {
                        ic.setComposingText("", 1)
                        ic.commitText(selected, 1)
                        val remainingBuffer = pinyinInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }
                    }
                } else if (shuangpinInputController.hasCandidates()) {
                    val selected = shuangpinInputController.selectFirstCandidate()
                    if (selected != null) {
                        ic.setComposingText("", 1)
                        ic.commitText(selected, 1)
                        val remainingBuffer = shuangpinInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }
                    }
                } else if (ziranmaInputController.hasCandidates()) {
                    val selected = ziranmaInputController.selectFirstCandidate()
                    if (selected != null) {
                        ic.setComposingText("", 1)
                        ic.commitText(selected, 1)
                        val remainingBuffer = ziranmaInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }
                    }
                } else if (wubiInputController.hasCandidates()) {
                    val selected = wubiInputController.selectFirstCandidate()
                    if (selected != null) {
                        ic.setComposingText("", 1)
                        ic.commitText(selected, 1)
                        val remainingBuffer = wubiInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }
                    }
                } else if (zhenmaInputController.hasCandidates()) {
                    val selected = zhenmaInputController.selectFirstCandidate()
                    if (selected != null) {
                        ic.setComposingText("", 1)
                        ic.commitText(selected, 1)
                        val remainingBuffer = zhenmaInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }
                    }
                } else {
                    ic.commitText(" ", 1)
                }
            }
            KeyEvent.KEYCODE_DEL -> {
                // First check if next-word predictions are showing (no buffer but suggestions visible)
                // If so, clear them without deleting text
                val hasNextWordPredictions = when {
                    pinyinInputController.isPinyinMode() && pinyinInputController.getBuffer().isEmpty() &&
                        pinyinInputController.isShowingNextWordPredictions() -> true
                    shuangpinInputController.isShuangpinMode() && shuangpinInputController.getBuffer().isEmpty() &&
                        shuangpinInputController.isShowingNextWordPredictions() -> true
                    wubiInputController.isWubiMode() && wubiInputController.getBuffer().isEmpty() &&
                        wubiInputController.isShowingNextWordPredictions() -> true
                    zhenmaInputController.isZhenmaMode() && zhenmaInputController.getBuffer().isEmpty() &&
                        zhenmaInputController.isShowingNextWordPredictions() -> true
                    ziranmaInputController.isZiranmaMode() && ziranmaInputController.getBuffer().isEmpty() &&
                        ziranmaInputController.isShowingNextWordPredictions() -> true
                    else -> {
                        // Only check English predictions if NOT in any Chinese input mode
                        val inChineseMode = pinyinInputController.isPinyinMode() ||
                            shuangpinInputController.isShuangpinMode() ||
                            wubiInputController.isWubiMode() ||
                            zhenmaInputController.isZhenmaMode() ||
                            ziranmaInputController.isZiranmaMode()
                        if (!inChineseMode) {
                            val snapshot = englishWordPredictionController.getSnapshot()
                            snapshot.hasSuggestions && snapshot.isNextWordPrediction
                        } else {
                            false
                        }
                    }
                }

                if (hasNextWordPredictions) {
                    // Clear next-word predictions without deleting text
                    when {
                        pinyinInputController.isPinyinMode() -> pinyinInputController.clearNextWordPredictions()
                        shuangpinInputController.isShuangpinMode() -> shuangpinInputController.clearNextWordPredictions()
                        wubiInputController.isWubiMode() -> wubiInputController.clearNextWordPredictions()
                        zhenmaInputController.isZhenmaMode() -> zhenmaInputController.clearNextWordPredictions()
                        ziranmaInputController.isZiranmaMode() -> ziranmaInputController.clearNextWordPredictions()
                        else -> {
                            englishWordPredictionController.onBackspaceInput()
                            englishWordPredictionController.clearNextWordPredictions()
                            englishWordPredictionController.clearSuggestions()
                        }
                    }
                    // Skip next update cycle to prevent predictions from being repopulated
                    skipNextWordPredictionUpdates = 1
                    updateStatusBarText()
                    return  // Don't delete text, just cleared predictions
                }

                // Handle backspace with cursor-aware deletion
                if (pinyinInputController.getBuffer().isNotEmpty()) {
                    // Get cursor position within composing text
                    val bufferLength = pinyinInputController.getBufferLength()
                    val currentBuffer = pinyinInputController.getBuffer()
                    var cursorPositionInBuffer = bufferLength
                    if (bufferLength > 0) {
                        val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                        if (extractedText != null) {
                            // Find the actual composing region by locating the buffer content in the text
                            val textStr = extractedText.text.toString()
                            val composingStart = textStr.indexOf(currentBuffer)
                            if (composingStart >= 0) {
                                val selectionInText = extractedText.startOffset + extractedText.selectionStart
                                if (selectionInText >= composingStart && selectionInText <= composingStart + bufferLength) {
                                    cursorPositionInBuffer = selectionInText - composingStart
                                }
                            }
                        }
                    }
                    pinyinInputController.handleBackspaceAtPosition(cursorPositionInBuffer)
                    val buffer = pinyinInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        ic.setComposingText(buffer, 1)
                        // Restore cursor position after deletion
                        val newCursorPos = if (cursorPositionInBuffer > 0 && cursorPositionInBuffer <= bufferLength) {
                            cursorPositionInBuffer - 1
                        } else {
                            buffer.length
                        }
                        if (newCursorPos < buffer.length) {
                            val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                            if (extractedText != null) {
                                // Find actual composing position
                                val textStr = extractedText.text.toString()
                                val composingStart = textStr.indexOf(buffer)
                                if (composingStart >= 0) {
                                    ic.setSelection(composingStart + newCursorPos, composingStart + newCursorPos)
                                }
                            }
                        }
                    } else {
                        ic.setComposingText("", 1)
                    }
                } else if (shuangpinInputController.getBuffer().isNotEmpty()) {
                    // Get cursor position within composing text
                    val bufferLength = shuangpinInputController.getBufferLength()
                    val currentBuffer = shuangpinInputController.getBuffer()
                    var cursorPositionInBuffer = bufferLength
                    if (bufferLength > 0) {
                        val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                        if (extractedText != null) {
                            // Find the actual composing region by locating the buffer content in the text
                            val textStr = extractedText.text.toString()
                            val composingStart = textStr.indexOf(currentBuffer)
                            if (composingStart >= 0) {
                                val selectionInText = extractedText.startOffset + extractedText.selectionStart
                                if (selectionInText >= composingStart && selectionInText <= composingStart + bufferLength) {
                                    cursorPositionInBuffer = selectionInText - composingStart
                                }
                            }
                        }
                    }
                    shuangpinInputController.handleBackspaceAtPosition(cursorPositionInBuffer)
                    val buffer = shuangpinInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        ic.setComposingText(buffer, 1)
                        // Restore cursor position after deletion
                        val newCursorPos = if (cursorPositionInBuffer > 0 && cursorPositionInBuffer <= bufferLength) {
                            cursorPositionInBuffer - 1
                        } else {
                            buffer.length
                        }
                        if (newCursorPos < buffer.length) {
                            val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                            if (extractedText != null) {
                                // Find actual composing position
                                val textStr = extractedText.text.toString()
                                val composingStart = textStr.indexOf(buffer)
                                if (composingStart >= 0) {
                                    ic.setSelection(composingStart + newCursorPos, composingStart + newCursorPos)
                                }
                            }
                        }
                    } else {
                        ic.setComposingText("", 1)
                    }
                } else if (ziranmaInputController.getBuffer().isNotEmpty()) {
                    ziranmaInputController.handleBackspace()
                    val buffer = ziranmaInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        ic.setComposingText(buffer, 1)
                    } else {
                        ic.setComposingText("", 1)
                    }
                } else if (wubiInputController.getBuffer().isNotEmpty()) {
                    wubiInputController.handleBackspace()
                    val buffer = wubiInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        ic.setComposingText(buffer, 1)
                    } else {
                        ic.setComposingText("", 1)
                    }
                } else if (zhenmaInputController.getBuffer().isNotEmpty()) {
                    zhenmaInputController.handleBackspace()
                    val buffer = zhenmaInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        ic.setComposingText(buffer, 1)
                    } else {
                        ic.setComposingText("", 1)
                    }
                } else {
                    // Delete character from text - send key event directly to InputConnection
                    // for proper newline handling (sendDownUpKeyEvents may not work in all apps)
                    val eventTime = android.os.SystemClock.uptimeMillis()
                    ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
                    ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
                }
            }
            KeyEvent.KEYCODE_ENTER -> {
                // Handle Enter like physical keyboard - commit buffer as English in Chinese mode
                if (pinyinInputController.isPinyinMode()) {
                    val buffer = pinyinInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = pinyinInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                            updateStatusBarText()
                            return
                        }
                    }
                } else if (shuangpinInputController.isShuangpinMode()) {
                    val buffer = shuangpinInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = shuangpinInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                            updateStatusBarText()
                            return
                        }
                    }
                } else if (ziranmaInputController.isZiranmaMode()) {
                    val buffer = ziranmaInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = ziranmaInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                            updateStatusBarText()
                            return
                        }
                    }
                } else if (wubiInputController.isWubiMode()) {
                    val buffer = wubiInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = wubiInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                            updateStatusBarText()
                            return
                        }
                    }
                } else if (zhenmaInputController.isZhenmaMode()) {
                    val buffer = zhenmaInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = zhenmaInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                            updateStatusBarText()
                            return
                        }
                    }
                }
                // No buffer to commit or not in Chinese mode
                // Try to perform editor action (Send, Search, Go, Done, etc.) first
                if (!handleEnterAction(ic)) {
                    // No action performed - send enter key event for newline
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
            }
        }
        updateStatusBarText()
    }

    /**
     * Handles Ctrl+key combinations from the virtual keyboard.
     * @param keyCode The key code to combine with Ctrl (e.g., KEYCODE_A, KEYCODE_C, KEYCODE_V)
     */
    private fun handleVirtualCtrlKeyPress(keyCode: Int) {
        val ic = currentInputConnection ?: return

        // Send Ctrl+key event by simulating key down and up with CTRL meta state
        val eventTime = android.os.SystemClock.uptimeMillis()
        val metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

        val downEvent = KeyEvent(
            eventTime, eventTime,
            KeyEvent.ACTION_DOWN, keyCode, 0, metaState
        )
        val upEvent = KeyEvent(
            eventTime, eventTime,
            KeyEvent.ACTION_UP, keyCode, 0, metaState
        )

        ic.sendKeyEvent(downEvent)
        ic.sendKeyEvent(upEvent)
    }

    /**
     * Handles character input from the virtual keyboard.
     * @param char The character to input
     */
    private fun handleVirtualCharacterInput(char: Char) {
        val ic = currentInputConnection ?: return

        // Check if we're in a Chinese input mode that handles this character
        if (pinyinInputController.isPinyinMode() && char.isLetter()) {
            // Get cursor position within composing text for cursor-aware insertion
            val bufferLength = pinyinInputController.getBufferLength()
            val currentBuffer = pinyinInputController.getBuffer()
            var cursorPositionInBuffer = bufferLength
            if (bufferLength > 0) {
                val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                if (extractedText != null) {
                    // Find the actual composing region by locating the buffer content in the text
                    val textStr = extractedText.text.toString()
                    val composingStart = textStr.indexOf(currentBuffer)
                    if (composingStart >= 0) {
                        val selectionInText = extractedText.startOffset + extractedText.selectionStart
                        if (selectionInText >= composingStart && selectionInText <= composingStart + bufferLength) {
                            cursorPositionInBuffer = selectionInText - composingStart
                        }
                    }
                }
            }
            pinyinInputController.handleLetterKeyAtPosition(char.lowercaseChar(), cursorPositionInBuffer)
            val buffer = pinyinInputController.getBuffer()
            ic.setComposingText(buffer, 1)
            // Restore cursor position after insertion (one position forward)
            val newCursorPos = cursorPositionInBuffer + 1
            if (newCursorPos < buffer.length) {
                val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                if (extractedText != null) {
                    // Find actual composing position
                    val textStr = extractedText.text.toString()
                    val composingStart = textStr.indexOf(buffer)
                    if (composingStart >= 0) {
                        ic.setSelection(composingStart + newCursorPos, composingStart + newCursorPos)
                    }
                }
            }
        } else if (shuangpinInputController.isShuangpinMode() && char.isLetter()) {
            // Get cursor position within composing text for cursor-aware insertion
            val bufferLength = shuangpinInputController.getBufferLength()
            val currentBuffer = shuangpinInputController.getBuffer()
            var cursorPositionInBuffer = bufferLength
            if (bufferLength > 0) {
                val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                if (extractedText != null) {
                    // Find the actual composing region by locating the buffer content in the text
                    val textStr = extractedText.text.toString()
                    val composingStart = textStr.indexOf(currentBuffer)
                    if (composingStart >= 0) {
                        val selectionInText = extractedText.startOffset + extractedText.selectionStart
                        if (selectionInText >= composingStart && selectionInText <= composingStart + bufferLength) {
                            cursorPositionInBuffer = selectionInText - composingStart
                        }
                    }
                }
            }
            shuangpinInputController.handleLetterKeyAtPosition(char.lowercaseChar(), cursorPositionInBuffer)
            val buffer = shuangpinInputController.getBuffer()
            ic.setComposingText(buffer, 1)
            // Restore cursor position after insertion (one position forward)
            val newCursorPos = cursorPositionInBuffer + 1
            if (newCursorPos < buffer.length) {
                val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                if (extractedText != null) {
                    // Find actual composing position
                    val textStr = extractedText.text.toString()
                    val composingStart = textStr.indexOf(buffer)
                    if (composingStart >= 0) {
                        ic.setSelection(composingStart + newCursorPos, composingStart + newCursorPos)
                    }
                }
            }
        } else if (ziranmaInputController.isZiranmaMode() && char.isLetter()) {
            ziranmaInputController.handleLetterKey(char.lowercaseChar())
            val buffer = ziranmaInputController.getBuffer()
            ic.setComposingText(buffer, 1)
        } else if (wubiInputController.isWubiMode() && char.isLetter()) {
            // Check for overflow commit (5th letter scenario) BEFORE adding the letter
            val overflowCommit = wubiInputController.checkOverflowCommit()
            if (overflowCommit != null) {
                ic.commitText(overflowCommit, 1)
            }
            // Handle letter with Z key symbol mode support
            when (val result = wubiInputController.handleLetterKeyWithResult(char.lowercaseChar())) {
                is WubiInputController.LetterKeyResult.SymbolOutput -> {
                    // Z key symbol mode - output the symbol directly
                    ic.commitText(result.symbol, 1)
                }
                is WubiInputController.LetterKeyResult.Handled -> {
                    // Check for auto-commit (4-char code with single candidate)
                    val autoCommit = wubiInputController.checkAutoCommit()
                    if (autoCommit != null) {
                        ic.commitText(autoCommit, 1)
                    } else {
                        val buffer = wubiInputController.getBuffer()
                        ic.setComposingText(buffer, 1)
                    }
                }
                is WubiInputController.LetterKeyResult.NotHandled -> {
                    // Treat as regular character
                    ic.commitText(char.toString(), 1)
                }
            }
        } else if (zhenmaInputController.isZhenmaMode() && char.isLetter()) {
            zhenmaInputController.handleLetterKey(char.lowercaseChar())
            val buffer = zhenmaInputController.getBuffer()
            ic.setComposingText(buffer, 1)
        } else {
            // Regular character input
            ic.commitText(char.toString(), 1)
        }
        updateStatusBarText()
    }

    /**
     * Aggiorna la status bar delegando al controller dedicato.
     */
    private fun updateStatusBarText() {
        // Check if Pinyin, T9 Pinyin, Shuangpin, Ziranma, Wubi, or Zhenma mode is active and use their candidates
        val pinyinSnapshot = pinyinInputController.getSnapshot()
        val t9PinyinSnapshot = t9PinyinInputController.getSnapshot()
        val shuangpinSnapshot = shuangpinInputController.getSnapshot()
        val ziranmaSnapshot = ziranmaInputController.getSnapshot()
        val wubiSnapshot = wubiInputController.getSnapshot()
        val zhenmaSnapshot = zhenmaInputController.getSnapshot()

        // Update English word prediction from cursor position
        // Skip if we just cleared next-word predictions (e.g., from DEL key)
        if (skipNextWordPredictionUpdates > 0) {
            skipNextWordPredictionUpdates--
        } else {
            englishWordPredictionController.updateFromCursor(currentInputConnection)
        }
        val wordPredictionSnapshot = englishWordPredictionController.getSnapshot()

        // Determine which variations to show:
        // 1. Pinyin candidates (when Pinyin mode active)
        // 2. Shuangpin candidates (when Shuangpin mode active)
        // 3. Ziranma candidates (when Ziranma mode active)
        // 4. Wubi candidates (when Wubi mode active)
        // 5. Zhenma candidates (when Zhenma mode active)
        // 6. Accent variations (when lastInsertedChar has variations)
        // 7. English word predictions (when typing and no accent variations)
        val variationSnapshot: VariationStateController.Snapshot
        var wordPredictionActive = false
        var wordPredictionPrefix = ""
        var currentPage = 0
        var totalPages = 1
        var hasNextPage = false
        var hasPrevPage = false

        // In Juying mode, limit candidates based on suggestion length
        val isJuyingMode = SettingsManager.getJuyingModeEnabled(this)
        val isDynamicCandidateCount = SettingsManager.getJuyingDynamicCandidateCount(this)
        val isMaxThreeSuggestions = SettingsManager.getJuyingMaxThreeSuggestions(this)

        // Helper to calculate dynamic candidate limit based on max candidate length in a list
        fun calculateLimitFromCandidates(candidates: List<String>): Int {
            // If max 3 suggestions is enabled, always return 3
            if (isMaxThreeSuggestions) return 3
            // If dynamic candidate count is disabled, return 0 to signal fixed mode
            if (!isDynamicCandidateCount) return 0
            if (candidates.isEmpty()) return 5
            val maxLen = candidates.maxOfOrNull { it.length } ?: 1
            return when {
                maxLen >= 10 -> 1  // Very long phrases (10+ chars): show only 1
                maxLen >= 6 -> 3   // Long phrases (6-9 chars): show 3
                else -> 5          // Short candidates (1-5 chars): show 5
            }
        }

        // Helper to calculate dynamic candidate limit for a specific page
        // This iterates through pages with variable page sizes to find the correct start index
        fun calculateJuyingCandidateLimit(allCandidates: List<String>, targetPage: Int): Int {
            // If max 3 suggestions is enabled, always return 3
            if (isMaxThreeSuggestions) return 3
            // If dynamic candidate count is disabled, return 0 to signal fixed mode
            // This prevents the controller from using dynamic page sizes
            if (!isDynamicCandidateCount) return 0
            if (allCandidates.isEmpty()) return 5

            var startIndex = 0
            for (page in 0..targetPage) {
                // Get candidates for this page
                val pageEndIndex = minOf(startIndex + 5, allCandidates.size)  // Max 5 candidates to consider
                if (startIndex >= allCandidates.size) return 5

                val pageCandidates = allCandidates.subList(startIndex, pageEndIndex)
                val pageLimit = calculateLimitFromCandidates(pageCandidates)

                if (page == targetPage) {
                    return pageLimit
                }

                // Move to next page - advance by the ACTUAL page limit (not 5)
                startIndex += pageLimit
            }
            return 5
        }

        // Default candidate limit: 3 if max three setting enabled, otherwise 5 for Juying, 9 for non-Juying
        val defaultCandidateLimit = if (isMaxThreeSuggestions) 3 else if (isJuyingMode) 5 else 9

        // Helper to reorder candidates for Juying+Chinese mode display:
        // 1 candidate: [1st] - Space picks it
        // 2 candidates: [2nd, 1st] - best on right, Sym=left, Space=right(best)
        // 3 candidates: [2nd, 1st, 3rd] - best in middle, Sym=left, Space=middle(best), Ctrl=right
        // 4+ candidates: [2nd, 3rd, 1st, 4th, 5th] - best at position 2 (Space)
        // In fixed position mode, don't reorder - VariationBarView handles fixed placement
        fun reorderForJuyingDisplay(candidates: List<String>): List<String> {
            if (!isJuyingMode || candidates.size < 2) return candidates
            // In fixed position mode, keep original order - VariationBarView will place them at fixed slots
            if (SettingsManager.getJuyingFixedPositions(this@PhysicalKeyboardInputMethodService)) {
                return candidates
            }
            return when (candidates.size) {
                2 -> listOf(candidates[1], candidates[0]) // [2nd, 1st] - best at position 1
                3 -> listOf(candidates[1], candidates[0], candidates[2]) // [2nd, 1st, 3rd] - best at position 1 (middle)
                4 -> listOf(candidates[1], candidates[2], candidates[0], candidates[3]) // [2nd, 3rd, 1st, 4th]
                else -> listOf(candidates[1], candidates[2], candidates[0], candidates[3], candidates[4]) // [2nd, 3rd, 1st, 4th, 5th]
            }
        }

        if (pinyinSnapshot.isActive) {
            // Pinyin mode takes priority
            // Calculate dynamic limit based on candidate lengths and set on controller for proper pagination
            // Use getAllCandidates() and current page to calculate limit based on what WOULD be shown with max page size
            val allCandidates = pinyinInputController.getAllCandidates()
            val candidateLimit = if (isJuyingMode) calculateJuyingCandidateLimit(allCandidates, pinyinSnapshot.currentPage) else defaultCandidateLimit
            if (isJuyingMode) {
                pinyinInputController.setDynamicDisplayLimit(candidateLimit)
            }
            // Get fresh snapshot with updated pagination
            val updatedSnapshot = pinyinInputController.getSnapshot()
            variationSnapshot = VariationStateController.Snapshot(
                isActive = true,
                lastInsertedChar = if (updatedSnapshot.buffer.isNotEmpty()) updatedSnapshot.buffer.last() else null,
                variations = reorderForJuyingDisplay(updatedSnapshot.candidates)
            )
            // Pagination info from Pinyin
            currentPage = updatedSnapshot.currentPage
            totalPages = updatedSnapshot.totalPages
            hasNextPage = updatedSnapshot.hasNextPage
            hasPrevPage = updatedSnapshot.hasPrevPage
        } else if (t9PinyinSnapshot.isActive) {
            // T9 Pinyin mode
            // Calculate dynamic limit based on candidate lengths and set on controller for proper pagination
            val allCandidates = t9PinyinInputController.getAllCandidates()
            val candidateLimit = if (isJuyingMode) calculateJuyingCandidateLimit(allCandidates, t9PinyinSnapshot.currentPage) else defaultCandidateLimit
            if (isJuyingMode) {
                t9PinyinInputController.setJuyingModeEnabled(true)
                t9PinyinInputController.setDynamicDisplayLimit(candidateLimit)
            }
            // Get fresh snapshot with updated pagination
            val updatedSnapshot = t9PinyinInputController.getSnapshot()
            variationSnapshot = VariationStateController.Snapshot(
                isActive = true,
                lastInsertedChar = if (updatedSnapshot.buffer.isNotEmpty()) updatedSnapshot.buffer.last() else null,
                variations = reorderForJuyingDisplay(updatedSnapshot.candidates)
            )
            // Pagination info from T9 Pinyin
            currentPage = updatedSnapshot.currentPage
            totalPages = updatedSnapshot.totalPages
            hasNextPage = updatedSnapshot.hasNextPage
            hasPrevPage = updatedSnapshot.hasPrevPage
        } else if (shuangpinSnapshot.isActive) {
            // Shuangpin mode
            // Calculate dynamic limit based on candidate lengths and set on controller for proper pagination
            val allCandidates = shuangpinInputController.getAllCandidates()
            val candidateLimit = if (isJuyingMode) calculateJuyingCandidateLimit(allCandidates, shuangpinSnapshot.currentPage) else defaultCandidateLimit
            if (isJuyingMode) {
                shuangpinInputController.setDynamicDisplayLimit(candidateLimit)
            }
            // Get fresh snapshot with updated pagination
            val updatedSnapshot = shuangpinInputController.getSnapshot()
            variationSnapshot = VariationStateController.Snapshot(
                isActive = true,
                lastInsertedChar = if (updatedSnapshot.buffer.isNotEmpty()) updatedSnapshot.buffer.last() else null,
                variations = reorderForJuyingDisplay(updatedSnapshot.candidates)
            )
            // Pagination info from Shuangpin
            currentPage = updatedSnapshot.currentPage
            totalPages = updatedSnapshot.totalPages
            hasNextPage = updatedSnapshot.hasNextPage
            hasPrevPage = updatedSnapshot.hasPrevPage
        } else if (ziranmaSnapshot.isActive) {
            // Ziranma mode
            // Calculate dynamic limit based on candidate lengths and set on controller for proper pagination
            val allCandidates = ziranmaInputController.getAllCandidates()
            val candidateLimit = if (isJuyingMode) calculateJuyingCandidateLimit(allCandidates, ziranmaSnapshot.currentPage) else defaultCandidateLimit
            if (isJuyingMode) {
                ziranmaInputController.setDynamicDisplayLimit(candidateLimit)
            }
            // Get fresh snapshot with updated pagination
            val updatedSnapshot = ziranmaInputController.getSnapshot()
            variationSnapshot = VariationStateController.Snapshot(
                isActive = true,
                lastInsertedChar = if (updatedSnapshot.buffer.isNotEmpty()) updatedSnapshot.buffer.last() else null,
                variations = reorderForJuyingDisplay(updatedSnapshot.candidates)
            )
            // Pagination info from Ziranma
            currentPage = updatedSnapshot.currentPage
            totalPages = updatedSnapshot.totalPages
            hasNextPage = updatedSnapshot.hasNextPage
            hasPrevPage = updatedSnapshot.hasPrevPage
        } else if (wubiSnapshot.isActive) {
            // Wubi mode
            // Calculate dynamic limit based on candidate lengths and set on controller for proper pagination
            val allCandidates = wubiInputController.getAllCandidates()
            val candidateLimit = if (isJuyingMode) calculateJuyingCandidateLimit(allCandidates, wubiSnapshot.currentPage) else defaultCandidateLimit
            if (isJuyingMode) {
                wubiInputController.setDynamicDisplayLimit(candidateLimit)
            }
            // Get fresh snapshot with updated pagination
            val updatedSnapshot = wubiInputController.getSnapshot()
            variationSnapshot = VariationStateController.Snapshot(
                isActive = true,
                lastInsertedChar = if (updatedSnapshot.buffer.isNotEmpty()) updatedSnapshot.buffer.last() else null,
                variations = reorderForJuyingDisplay(updatedSnapshot.candidates)
            )
            // Pagination info from Wubi
            currentPage = updatedSnapshot.currentPage
            totalPages = updatedSnapshot.totalPages
            hasNextPage = updatedSnapshot.hasNextPage
            hasPrevPage = updatedSnapshot.hasPrevPage
        } else if (zhenmaSnapshot.isActive) {
            // Zhenma mode
            // Calculate dynamic limit based on candidate lengths and set on controller for proper pagination
            val allCandidates = zhenmaInputController.getAllCandidates()
            val candidateLimit = if (isJuyingMode) calculateJuyingCandidateLimit(allCandidates, zhenmaSnapshot.currentPage) else defaultCandidateLimit
            if (isJuyingMode) {
                zhenmaInputController.setDynamicDisplayLimit(candidateLimit)
            }
            // Get fresh snapshot with updated pagination
            val updatedSnapshot = zhenmaInputController.getSnapshot()
            variationSnapshot = VariationStateController.Snapshot(
                isActive = true,
                lastInsertedChar = if (updatedSnapshot.buffer.isNotEmpty()) updatedSnapshot.buffer.last() else null,
                variations = reorderForJuyingDisplay(updatedSnapshot.candidates)
            )
            // Pagination info from Zhenma
            currentPage = updatedSnapshot.currentPage
            totalPages = updatedSnapshot.totalPages
            hasNextPage = updatedSnapshot.hasNextPage
            hasPrevPage = updatedSnapshot.hasPrevPage
        } else if (wordPredictionSnapshot.hasSuggestions && !shouldDisableSmartFeatures) {
            // Show English word predictions
            // In Juying mode: [1st best (Sym), current typed word (Space), 2nd best (Ctrl)]
            val rawSuggestions = wordPredictionSnapshot.suggestions.take(3)
            val prefix = wordPredictionSnapshot.prefix
            val displaySuggestions = if (isJuyingMode) {
                // For prefix-based predictions: show [1st best, typed prefix, 2nd best]
                // For next-word predictions (no prefix): show [1st best, "", 2nd best]
                val typedWord = if (prefix.isNotEmpty()) prefix else ""
                when {
                    rawSuggestions.isEmpty() -> listOf(typedWord)
                    rawSuggestions.size == 1 -> listOf(rawSuggestions[0], typedWord, "")
                    else -> listOf(rawSuggestions[0], typedWord, rawSuggestions[1])
                }
            } else {
                rawSuggestions
            }
            variationSnapshot = VariationStateController.Snapshot(
                isActive = true,
                lastInsertedChar = null,
                variations = displaySuggestions
            )
            wordPredictionActive = true
            wordPredictionPrefix = prefix
            // Pagination info from word prediction
            currentPage = wordPredictionSnapshot.currentPage
            totalPages = wordPredictionSnapshot.totalPages
            hasNextPage = wordPredictionSnapshot.hasNextPage
            hasPrevPage = wordPredictionSnapshot.hasPrevPage
        } else {
            // No variations to show
            variationSnapshot = VariationStateController.Snapshot(
                isActive = false,
                lastInsertedChar = null,
                variations = emptyList()
            )
        }

        val modifierSnapshot = modifierStateController.snapshot()
        val snapshot = StatusBarController.StatusSnapshot(
            capsLockEnabled = modifierSnapshot.capsLockEnabled,
            shiftPhysicallyPressed = modifierSnapshot.shiftPhysicallyPressed,
            shiftOneShot = modifierSnapshot.shiftOneShot,
            ctrlLatchActive = modifierSnapshot.ctrlLatchActive,
            ctrlPhysicallyPressed = modifierSnapshot.ctrlPhysicallyPressed,
            ctrlOneShot = modifierSnapshot.ctrlOneShot,
            ctrlLatchFromNavMode = modifierSnapshot.ctrlLatchFromNavMode,
            altLatchActive = modifierSnapshot.altLatchActive,
            altPhysicallyPressed = modifierSnapshot.altPhysicallyPressed,
            altOneShot = modifierSnapshot.altOneShot,
            symPage = symPage,
            variations = variationSnapshot.variations,
            lastInsertedChar = variationSnapshot.lastInsertedChar,
            shouldDisableSmartFeatures = shouldDisableSmartFeatures,
            pinyinModeActive = pinyinSnapshot.isActive,
            pinyinBuffer = pinyinSnapshot.buffer,
            t9PinyinModeActive = t9PinyinSnapshot.isActive,
            t9PinyinBuffer = t9PinyinSnapshot.buffer,
            shuangpinModeActive = shuangpinSnapshot.isActive,
            shuangpinBuffer = shuangpinSnapshot.buffer,
            ziranmaModeActive = ziranmaSnapshot.isActive,
            ziranmaBuffer = ziranmaSnapshot.buffer,
            wubiModeActive = wubiSnapshot.isActive,
            wubiBuffer = wubiSnapshot.buffer,
            wubiWildcardMode = wubiInputController.isUsingWildcard(),
            wubiCandidateCodes = buildWubiCandidateCodesMap(),
            zhenmaModeActive = zhenmaSnapshot.isActive,
            zhenmaBuffer = zhenmaSnapshot.buffer,
            wordPredictionActive = wordPredictionActive,
            wordPredictionPrefix = wordPredictionPrefix,
            currentPage = currentPage,
            totalPages = totalPages,
            hasNextPage = hasNextPage,
            hasPrevPage = hasPrevPage,
            chinesePunctuationMode = isChinesePunctuationModeActive(),
            isJuyingMode = isJuyingMode,
            isNextWordPrediction = pinyinSnapshot.isNextWordPrediction ||
                                   shuangpinSnapshot.isNextWordPrediction ||
                                   wubiSnapshot.isNextWordPrediction ||
                                   zhenmaSnapshot.isNextWordPrediction ||
                                   ziranmaSnapshot.isNextWordPrediction ||
                                   wordPredictionSnapshot.isNextWordPrediction
        )
        val emojiMapText = ""
        // Passa le mappature SYM per la griglia emoji/caratteri
        val symMappings = symLayoutController.currentSymMappings()
        // Passa l'inputConnection per rendere i pulsanti clickabili
        val inputConnection = currentInputConnection

        // Compact mode: hide entire status bar when no suggestions
        val compactModeEnabled = SettingsManager.getCompactModeEnabled(this)
        val hasSuggestions = variationSnapshot.variations.isNotEmpty() ||
                            pinyinSnapshot.hasCandidates ||
                            t9PinyinSnapshot.hasCandidates ||
                            shuangpinSnapshot.hasCandidates ||
                            ziranmaSnapshot.hasCandidates ||
                            wubiSnapshot.hasCandidates ||
                            zhenmaSnapshot.hasCandidates ||
                            symLayoutController.isSymActive()
        if (compactModeEnabled) {
            val shouldHide = !hasSuggestions
            candidatesBarController.setCompactModeHidden(shouldHide)
            isCompactModeHidden = shouldHide
        } else {
            isCompactModeHidden = false
            candidatesBarController.setCompactModeHidden(false)
        }

        candidatesBarController.updateStatusBars(snapshot, emojiMapText, inputConnection, symMappings)

        // Update virtual keyboard shift state to show uppercase/lowercase letters
        val isShifted = modifierSnapshot.shiftOneShot || modifierSnapshot.shiftPhysicallyPressed
        val isCapsLock = modifierSnapshot.capsLockEnabled
        candidatesBarController.updateVirtualKeyboardShiftState(isShifted, isCapsLock)
    }

    /**
     * Disattiva le variazioni.
     */
    private fun deactivateVariations() {
        if (::variationStateController.isInitialized) {
            variationStateController.clear()
        }
    }
    

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        
        currentPackageName = info?.packageName
        
        updateInputContextState(info)
        val state = inputContextState
        val isEditable = state.isEditable
        val isReallyEditable = state.isReallyEditable
        isInputViewActive = isEditable
        
        if (restarting) {
            enforceSmartFeatureDisabledState()
        }
        
        if (info != null && isEditable) {
            info.inputType = info.inputType or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        
        if (isEditable && !restarting) {
            val autoShowKeyboardEnabled = SettingsManager.getAutoShowKeyboard(this)
            if (autoShowKeyboardEnabled && isReallyEditable) {
                if (!isInputViewShown && isInputViewActive) {
                    ensureInputViewCreated()
                }
            }
        }
        
        if (!restarting) {
            if (ctrlLatchFromNavMode && ctrlLatchActive) {
                val inputConnection = currentInputConnection
                val hasValidInputConnection = inputConnection != null
                
                if (isReallyEditable && hasValidInputConnection) {
                    navModeController.exitNavMode()
                    resetModifierStates(preserveNavMode = false)
                }
            } else if (isEditable || !ctrlLatchFromNavMode) {
                resetModifierStates(preserveNavMode = false)
            }
        }
        
        initializeInputContext(restarting)
        
        if (restarting && isEditable && !shouldDisableSmartFeatures) {
            AutoCapitalizeHelper.checkAutoCapitalizeOnRestart(
                this,
                currentInputConnection,
                shouldDisableSmartFeatures,
                enableShift = { modifierStateController.requestShiftOneShotFromAutoCap() },
                onUpdateStatusBar = { updateStatusBarText() }
            )
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        updateInputContextState(info)
        initializeInputContext(restarting)

        // Refresh Chinese next word prediction setting (may have changed in settings)
        val chineseNextWordPredictionEnabled = SettingsManager.getChineseNextWordPredictionEnabled(this)
        pinyinInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)
        shuangpinInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)
        ziranmaInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)
        wubiInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)
        zhenmaInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)
        // Also apply to English word prediction controller
        englishWordPredictionController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)

        // Refresh fuzzy pinyin setting (may have changed in settings)
        val fuzzyPinyinEnabled = SettingsManager.getPinyinFuzzyEnabled(this)
        pinyinInputController.setFuzzyPinyinEnabled(fuzzyPinyinEnabled)

        // Refresh neural pinyin settings (may have changed in settings)
        val neuralPinyinEnabled = SettingsManager.isNeuralPinyinEnabled(this)
        val neuralPinyinMinLetters = SettingsManager.getNeuralPinyinMinLetters(this)
        val neuralPinyinPriority = SettingsManager.isNeuralPinyinPriority(this)
        val neuralPinyinCount = SettingsManager.getNeuralPinyinCount(this)
        pinyinInputController.setNeuralPinyinEnabled(neuralPinyinEnabled)
        pinyinInputController.setNeuralPinyinMinLetters(neuralPinyinMinLetters)
        pinyinInputController.setNeuralPinyinPriority(neuralPinyinPriority)
        pinyinInputController.setNeuralPinyinCount(neuralPinyinCount)
        // Also apply to Shuangpin controller
        shuangpinInputController.setNeuralPinyinEnabled(neuralPinyinEnabled)
        shuangpinInputController.setNeuralPinyinMinLetters(neuralPinyinMinLetters)
        shuangpinInputController.setNeuralPinyinPriority(neuralPinyinPriority)
        shuangpinInputController.setNeuralPinyinCount(neuralPinyinCount)

        // Refresh virtual keyboard setting (may have changed in settings)
        val newVirtualKeyboardEnabled = SettingsManager.isVirtualKeyboardEnabled(this)
        if (newVirtualKeyboardEnabled != isVirtualKeyboardEnabled) {
            isVirtualKeyboardEnabled = newVirtualKeyboardEnabled
            candidatesBarController.setVirtualKeyboardEnabled(isVirtualKeyboardEnabled)
        }

        // Check if virtual keyboard height changed and recreate if needed
        val newVirtualKeyboardHeight = SettingsManager.getVirtualKeyboardHeight(this)
        if (lastVirtualKeyboardHeight != -1 && newVirtualKeyboardHeight != lastVirtualKeyboardHeight) {
            candidatesBarController.recreateVirtualKeyboard()
        }
        lastVirtualKeyboardHeight = newVirtualKeyboardHeight

        val isEditable = inputContextState.isEditable

        if (restarting && isEditable && !shouldDisableSmartFeatures) {
            AutoCapitalizeHelper.checkAutoCapitalizeOnRestart(
                this,
                currentInputConnection,
                shouldDisableSmartFeatures,
                enableShift = { modifierStateController.requestShiftOneShotFromAutoCap() },
                onUpdateStatusBar = { updateStatusBarText() }
            )
        }
    }
    
    override fun onFinishInput() {
        super.onFinishInput()
        isInputViewActive = false
        inputContextState = InputContextState.EMPTY
        resetModifierStates(preserveNavMode = true)
        // Cancel any pending Alt selection
        pendingAltSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingAltSelectionRunnable = null
    }
    
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        isInputViewActive = false
        if (finishingInput) {
            resetModifierStates(preserveNavMode = true)
        }
    }
    
    override fun onWindowShown() {
        super.onWindowShown()
        // Refresh fuzzy pinyin setting (may have changed in settings)
        val fuzzyPinyinEnabled = SettingsManager.getPinyinFuzzyEnabled(this)
        pinyinInputController.setFuzzyPinyinEnabled(fuzzyPinyinEnabled)

        // Refresh neural pinyin settings (may have changed in settings)
        val neuralPinyinEnabled = SettingsManager.isNeuralPinyinEnabled(this)
        val neuralPinyinMinLetters = SettingsManager.getNeuralPinyinMinLetters(this)
        val neuralPinyinPriority = SettingsManager.isNeuralPinyinPriority(this)
        val neuralPinyinCount = SettingsManager.getNeuralPinyinCount(this)
        pinyinInputController.setNeuralPinyinEnabled(neuralPinyinEnabled)
        pinyinInputController.setNeuralPinyinMinLetters(neuralPinyinMinLetters)
        pinyinInputController.setNeuralPinyinPriority(neuralPinyinPriority)
        pinyinInputController.setNeuralPinyinCount(neuralPinyinCount)
        // Also apply to Shuangpin controller
        shuangpinInputController.setNeuralPinyinEnabled(neuralPinyinEnabled)
        shuangpinInputController.setNeuralPinyinMinLetters(neuralPinyinMinLetters)
        shuangpinInputController.setNeuralPinyinPriority(neuralPinyinPriority)
        shuangpinInputController.setNeuralPinyinCount(neuralPinyinCount)

        // Refresh Juying mode page size for all Chinese input controllers
        val juyingModeEnabled = SettingsManager.getJuyingModeEnabled(this)
        val maxCandidatesNonJuying = SettingsManager.getMaxCandidatesNonJuying(this)
        pinyinInputController.setJuyingMode(juyingModeEnabled, maxCandidatesNonJuying)
        shuangpinInputController.setJuyingMode(juyingModeEnabled, maxCandidatesNonJuying)
        ziranmaInputController.setJuyingMode(juyingModeEnabled, maxCandidatesNonJuying)
        wubiInputController.setJuyingMode(juyingModeEnabled, maxCandidatesNonJuying)
        zhenmaInputController.setJuyingMode(juyingModeEnabled, maxCandidatesNonJuying)

        updateStatusBarText()
    }
    
    override fun onWindowHidden() {
        super.onWindowHidden()
        resetModifierStates(preserveNavMode = true)
        // Cancel any pending Alt selection
        pendingAltSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingAltSelectionRunnable = null
    }
    
    /**
     * Called when the cursor position or selection changes in the text field.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        
        if (!shouldDisableSmartFeatures) {
            val cursorPositionChanged = (oldSelStart != newSelStart) || (oldSelEnd != newSelEnd)
            if (cursorPositionChanged && newSelStart == newSelEnd) {
                Handler(Looper.getMainLooper()).postDelayed({
                    updateStatusBarText()
                }, CURSOR_UPDATE_DELAY)
            }
        }
        
        AutoCapitalizeHelper.checkAutoCapitalizeOnSelectionChange(
            this,
            currentInputConnection,
            shouldDisableSmartFeatures,
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            enableShift = { modifierStateController.requestShiftOneShotFromAutoCap() },
            disableShift = { modifierStateController.consumeShiftOneShot() },
            onUpdateStatusBar = { updateStatusBarText() }
        )
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle long press even when the keyboard is hidden but we still have a valid InputConnection.
        val inputConnection = currentInputConnection
        if (inputConnection == null) {
            return super.onKeyLongPress(keyCode, event)
        }

        // If the keyboard is hidden but we have an InputConnection, reactivate it
        if (!isInputViewActive) {
            isInputViewActive = true
            if (!isInputViewShown) {
                ensureInputViewCreated()
            }
        }

        // Intercept long presses BEFORE Android handles them
        if (altSymManager.hasAltMapping(keyCode)) {
            // Consumiamo l'evento per evitare il popup di Android
            return true
        }

        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Translate device-specific keycodes to standard Android keycodes
        val translatedKeyCode = translateKeyCode(keyCode)

        // Mark that a key was pressed during touch (to suppress swipe gestures when typing)
        if (touchDown) {
            keyPressedDuringTouch = true
        }

        // Check if we have an editable field at the very start
        val info = currentInputEditorInfo
        val initialInputConnection = currentInputConnection
        val inputType = info?.inputType ?: EditorInfo.TYPE_NULL
        val hasEditableField = initialInputConnection != null && inputType != EditorInfo.TYPE_NULL
        val wasInputViewActive = isInputViewActive
        if (hasEditableField && !isInputViewActive) {
            isInputViewActive = true
            // Update input context state immediately so Chinese input handlers can work
            updateInputContextState(info)
        }

        // Check if this is a modifier key (using device-specific checks)
        val isModifierKey = isDeviceModifierKey(keyCode)
        if (!isModifierKey) {
            modifierStateController.registerNonModifierKey()
            // Play keyboard click sound for non-modifier key presses (only on first press, not repeats)
            // Skip sound for navigation keys (Back, Recent, Home)
            if (event?.repeatCount == 0 && shouldPlaySoundForKey(translatedKeyCode)) {
                playKeyClickSound()
            }
        }

        // Handle hold space for voice input - only when we have an active, real text input field
        val canTriggerVoiceInput = isInputViewActive &&
                                   inputContextState.isReallyEditable &&
                                   currentInputConnection != null
        if (translatedKeyCode == KeyEvent.KEYCODE_SPACE && SettingsManager.isHoldSpaceForVoice(this) && canTriggerVoiceInput) {
            if (event?.repeatCount == 0) {
                // First press - start hold timer for voice input
                spaceHoldTriggeredVoice = false
                spaceHoldHandler = Handler(Looper.getMainLooper())
                spaceHoldRunnable = Runnable {
                    // Double-check we still have an active, real text input field before triggering
                    val stillCanTrigger = isInputViewActive &&
                                          inputContextState.isReallyEditable &&
                                          currentInputConnection != null
                    if (!spaceHoldTriggeredVoice && stillCanTrigger) {
                        spaceHoldTriggeredVoice = true
                        voiceTriggeredByHoldSpace = true  // Track that voice was triggered by holding space
                        startSpeechRecognition()
                    }
                }
                spaceHoldHandler?.postDelayed(spaceHoldRunnable!!, SettingsManager.getHoldSpaceDuration(this))
            } else {
                // Key repeat - consume to prevent multiple spaces while holding
                return true
            }
        }

        // Track Alt/Shift double press for pagination
        // Only use pagination behavior when there are candidates to paginate
        // Otherwise, allow normal Alt/Shift locking behavior
        val isPinyinMode = pinyinInputController.isPinyinMode()
        val isShuangpinMode = shuangpinInputController.isShuangpinMode()
        val isWubiMode = wubiInputController.isWubiMode()
        val isZhenmaMode = zhenmaInputController.isZhenmaMode()
        val isT9PinyinMode = t9PinyinInputController.isT9Mode()
        val isWordPredictionActive = englishWordPredictionController.hasActivePrediction()

        // Check if we have candidates to paginate (buffer not empty or has candidates)
        val hasPinyinCandidates = isPinyinMode && pinyinInputController.hasCandidates()
        val hasShuangpinCandidates = isShuangpinMode && shuangpinInputController.hasCandidates()
        val hasWubiCandidates = isWubiMode && wubiInputController.hasCandidates()
        val hasZhenmaCandidates = isZhenmaMode && zhenmaInputController.hasCandidates()
        val hasT9PinyinCandidates = isT9PinyinMode && t9PinyinInputController.hasCandidates()
        val hasWordPredictions = isWordPredictionActive && englishWordPredictionController.hasSuggestions()
        val hasCandidatesToPaginate = hasPinyinCandidates || hasShuangpinCandidates || hasWubiCandidates || hasZhenmaCandidates || hasT9PinyinCandidates || hasWordPredictions

        // Handle touchpad DPAD_DOWN/DPAD_UP for Chinese input candidate pagination
        // Use debounce to ensure one swipe = one page change (regardless of swipe distance)
        // When trackpad gestures are enabled:
        // - DPAD_DOWN (swipe down) always triggers next page (regardless of touchpadPageEnabled setting)
        // - DPAD_UP (swipe up) is skipped - handled by raw trackpad gesture for candidate selection
        // When trackpad gestures are disabled:
        // - Both DPAD_DOWN and DPAD_UP work for pagination only if touchpadPageEnabled is true
        val touchpadPageEnabled = SettingsManager.getTouchpadPageEnabled(this)
        val trackpadGesturesEnabled = SettingsManager.getTrackpadGesturesEnabled(this)
        val currentTime = System.currentTimeMillis()
        val timeSinceLastTouchpadPage = currentTime - lastTouchpadPageTime

        // Swipe down for next page: enabled when trackpadGesturesEnabled OR touchpadPageEnabled
        val swipeDownEnabled = trackpadGesturesEnabled || touchpadPageEnabled
        // Swipe up for prev page: only when touchpadPageEnabled AND trackpadGesturesEnabled is false
        val swipeUpEnabled = touchpadPageEnabled && !trackpadGesturesEnabled

        if (hasCandidatesToPaginate && timeSinceLastTouchpadPage > TOUCHPAD_PAGE_DEBOUNCE_MS) {
            when (translatedKeyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (swipeDownEnabled) {
                        // Touchpad down = next page
                        if (hasPinyinCandidates) {
                            pinyinInputController.nextPage()
                        } else if (hasShuangpinCandidates) {
                            shuangpinInputController.nextPage()
                        } else if (hasWubiCandidates) {
                            wubiInputController.nextPage()
                        } else if (hasZhenmaCandidates) {
                            zhenmaInputController.nextPage()
                        } else if (hasT9PinyinCandidates) {
                            t9PinyinInputController.nextPage()
                        } else if (hasWordPredictions) {
                            englishWordPredictionController.nextPage()
                        }
                        lastTouchpadPageTime = currentTime
                        updateStatusBarText()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (swipeUpEnabled) {
                        // Touchpad up = previous page (only when trackpad gestures are disabled)
                        if (hasPinyinCandidates) {
                            pinyinInputController.prevPage()
                        } else if (hasShuangpinCandidates) {
                            shuangpinInputController.prevPage()
                        } else if (hasWubiCandidates) {
                            wubiInputController.prevPage()
                        } else if (hasZhenmaCandidates) {
                            zhenmaInputController.prevPage()
                        } else if (hasT9PinyinCandidates) {
                            t9PinyinInputController.prevPage()
                        } else if (hasWordPredictions) {
                            englishWordPredictionController.prevPage()
                        }
                        lastTouchpadPageTime = currentTime
                        updateStatusBarText()
                        return true
                    }
                }
            }
        } else if (hasCandidatesToPaginate && swipeDownEnabled && translatedKeyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            // Within debounce period for DPAD_DOWN - consume the event but don't change page
            return true
        } else if (hasCandidatesToPaginate && swipeUpEnabled && translatedKeyCode == KeyEvent.KEYCODE_DPAD_UP) {
            // Within debounce period for DPAD_UP - consume the event but don't change page
            return true
        }

        // Check Juying mode settings upfront
        val juyingModeEnabled = SettingsManager.getJuyingModeEnabled(this)
        val isChineseInputActive = isPinyinMode || isShuangpinMode || isWubiMode || isZhenmaMode || isT9PinyinMode
        val hasChineseCandidates = hasPinyinCandidates || hasShuangpinCandidates || hasWubiCandidates || hasZhenmaCandidates || hasT9PinyinCandidates
        // Juying mode works with both Chinese input and English word predictions
        val hasAnyCandidates = hasChineseCandidates || hasWordPredictions

        // Handle double-click Alt when there's a pending selection (first Alt already cleared candidates)
        // This must be checked BEFORE the main Juying block since hasAnyCandidates is false after first Alt DOWN
        if (juyingModeEnabled && isDeviceAltKey(keyCode) && pendingAltSelectionRunnable != null && event?.repeatCount == 0) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastPress = currentTime - altLastPressTime

            // Double-click detected - cancel pending selection and go to next page
            if (timeSinceLastPress <= altDoubleClickDelay && savedAltCandidatesForNextPage.isNotEmpty()) {
                // Cancel the pending runnable
                pendingAltSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingAltSelectionRunnable = null

                // UNDO: Delete the character that was committed on Alt DOWN
                val ic = currentInputConnection
                if (altCommittedCharacter != null && ic != null) {
                    // First clear any composing text WITHOUT committing it
                    ic.setComposingText("", 1)
                    // Delete the committed character
                    ic.deleteSurroundingText(altCommittedCharacter!!.length, 0)
                    altCommittedCharacter = null
                    altRemainingBuffer = ""
                }

                // Restore saved candidates and buffer, then go to next page
                if (savedAltChineseMode != null) {
                    // Helper to calculate dynamic candidate limit based on max candidate length for a specific page
                    val isDynamicCountEnabled = SettingsManager.getJuyingDynamicCandidateCount(this)
                    fun calculateDynamicLimit(allCandidates: List<String>, page: Int): Int {
                        // If dynamic candidate count is disabled, always return 5
                        if (!isDynamicCountEnabled) return 5
                        if (allCandidates.isEmpty()) return 5
                        // Calculate which candidates would be on this page with max page size (5)
                        val startIndex = page * 5
                        val pageCandidates = allCandidates.drop(startIndex).take(5)
                        if (pageCandidates.isEmpty()) return 5
                        val maxLen = pageCandidates.maxOfOrNull { it.length } ?: 1
                        return when {
                            maxLen >= 10 -> 1
                            maxLen >= 6 -> 3
                            else -> 5
                        }
                    }

                    when (savedAltChineseMode) {
                        "pinyin" -> {
                            // Restore buffer and syllable parsing state for proper candidate selection
                            if (savedAltBuffer.isNotEmpty()) {
                                pinyinInputController.restoreBuffer(savedAltBuffer)
                                ic?.setComposingText(savedAltBuffer, 1)
                            }
                            // Restore full state including syllable parsing for next Alt press
                            pinyinInputController.restoreForAltSelection(
                                savedAltCandidatesForNextPage,
                                savedAltCurrentPage,
                                savedAltFirstSyllable,
                                savedAltMatchedPinyin,
                                savedAltPhraseCandidateCount,
                                savedAltPhraseCandidateSet
                            )
                            // Calculate limit for the NEXT page (savedAltCurrentPage + 1)
                            val nextPage = savedAltCurrentPage + 1
                            val dynamicLimit = calculateDynamicLimit(savedAltCandidatesForNextPage, nextPage)
                            pinyinInputController.setDynamicDisplayLimit(dynamicLimit)
                            if (pinyinInputController.hasNextPage()) {
                                pinyinInputController.nextPage()
                            }
                        }
                        "shuangpin" -> {
                            shuangpinInputController.restoreCandidatesForNextPage(savedAltCandidatesForNextPage, savedAltCurrentPage)
                            if (savedAltBuffer.isNotEmpty()) {
                                shuangpinInputController.restoreBuffer(savedAltBuffer)
                                ic?.setComposingText(savedAltBuffer, 1)
                            }
                            // Calculate limit for the NEXT page (savedAltCurrentPage + 1)
                            val nextPage = savedAltCurrentPage + 1
                            val dynamicLimit = calculateDynamicLimit(savedAltCandidatesForNextPage, nextPage)
                            shuangpinInputController.setDynamicDisplayLimit(dynamicLimit)
                            if (shuangpinInputController.hasNextPage()) {
                                shuangpinInputController.nextPage()
                            }
                        }
                        "wubi" -> {
                            wubiInputController.restoreCandidatesForNextPage(savedAltCandidatesForNextPage, savedAltCurrentPage)
                            if (savedAltBuffer.isNotEmpty()) {
                                wubiInputController.restoreBuffer(savedAltBuffer)
                                ic?.setComposingText(savedAltBuffer, 1)
                            }
                            // Calculate limit for the NEXT page (savedAltCurrentPage + 1)
                            val nextPage = savedAltCurrentPage + 1
                            val dynamicLimit = calculateDynamicLimit(savedAltCandidatesForNextPage, nextPage)
                            wubiInputController.setDynamicDisplayLimit(dynamicLimit)
                            if (wubiInputController.hasNextPage()) {
                                wubiInputController.nextPage()
                            }
                        }
                        "zhenma" -> {
                            zhenmaInputController.restoreCandidatesForNextPage(savedAltCandidatesForNextPage, savedAltCurrentPage)
                            if (savedAltBuffer.isNotEmpty()) {
                                zhenmaInputController.restoreBuffer(savedAltBuffer)
                                ic?.setComposingText(savedAltBuffer, 1)
                            }
                            // Calculate limit for the NEXT page (savedAltCurrentPage + 1)
                            val nextPage = savedAltCurrentPage + 1
                            val dynamicLimit = calculateDynamicLimit(savedAltCandidatesForNextPage, nextPage)
                            zhenmaInputController.setDynamicDisplayLimit(dynamicLimit)
                            if (zhenmaInputController.hasNextPage()) {
                                zhenmaInputController.nextPage()
                            }
                        }
                    }
                    // Clear saved state after restoring
                    savedAltSuggestion = null
                    savedAltCandidatesForNextPage = emptyList()
                    savedAltCurrentPage = 0
                    savedAltChineseMode = null
                    savedAltBuffer = ""
                    savedAltPhraseCandidateSet = emptySet()
                }
                updateStatusBarText()
                altLastPressTime = 0L
                // Clear full Alt state to prevent latch from triggering on rapid double-clicks
                modifierStateController.clearAltState(resetPressedState = true)
                // Mark that Alt was used for pagination - skip normal Alt UP handling
                altUsedForPagination = true
                return true
            }
        }

        // Handle Alt+key for symbol input when character was committed on Alt DOWN (candidates already cleared)
        // This must be checked BEFORE the main Juying block since hasAnyCandidates is false after Alt DOWN
        if (juyingModeEnabled && altCommittedCharacter != null && !isDeviceAltKey(keyCode) && event?.repeatCount == 0) {
            // Use Alt LED status as the source of truth: if LED is off, Alt is not active
            val altLedIsOffCommit = !altLatchActive && !altOneShot && !altPressed
            val altHeldFromEvent = event != null && ((event.metaState and KeyEvent.META_ALT_ON) != 0)
            val altFromJuyingTrackingCommit = !altLedIsOffCommit && altLastPressTime > 0
            val altIsActive = altFromJuyingTrackingCommit || altPressed || altHeldFromEvent
            if (altIsActive) {
                // Alt+key for symbol input - undo the committed character
                altUsedForSymbolInput = true
                savedAltSuggestion = null
                savedAltCandidatesForNextPage = emptyList()
                savedAltCurrentPage = 0
                savedAltChineseMode = null

                // Cancel any pending Alt selection
                pendingAltSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingAltSelectionRunnable = null

                // UNDO: Delete the character that was committed on Alt DOWN
                val ic = currentInputConnection
                if (ic != null) {
                    // First clear any composing text WITHOUT committing it
                    ic.setComposingText("", 1)
                    // Delete the committed character
                    ic.deleteSurroundingText(altCommittedCharacter!!.length, 0)
                    altCommittedCharacter = null
                    altRemainingBuffer = ""
                }

                updateStatusBarText()
                // Reset altLastPressTime so we don't keep clearing
                altLastPressTime = 0L
                // Continue to normal handling (don't return) - the Alt+key will be processed below
            }
        }

        // Handle SYM key to toggle SYM page - but only when NOT in Juying mode with candidates
        // In Juying mode with candidates, SYM is used for candidate selection (key 2)
        // When no candidates, SYM should toggle the SYM page (e.g., after inserting a custom symbol)
        // IMPORTANT: Skip this when no editable field and power shortcuts are enabled (SYM triggers power shortcut mode)
        if (translatedKeyCode == KeyEvent.KEYCODE_SYM) {
            // If no editable field and power shortcuts enabled, let the power shortcuts handler deal with SYM
            val powerShortcutsEnabled = SettingsManager.getPowerShortcutsEnabled(this)
            if (!hasEditableField && powerShortcutsEnabled) {
                // Fall through to handleKeyDownWithNoEditableField which handles power shortcuts
            } else {
                // Check if SYM is configured as a Juying key and we have candidates
                // For BlackBerry, Juying keys are stored as raw keycodes (58), not translated (63)
                // So check BOTH raw keyCode and translated keyCode
                val symIsJuyingKey = SettingsManager.getJuyingCandidateIndex(this, keyCode) >= 0 ||
                                     SettingsManager.getJuyingCandidateIndex(this, translatedKeyCode) >= 0
                val shouldUseForCandidateSelection = juyingModeEnabled && hasAnyCandidates && symIsJuyingKey

                if (!shouldUseForCandidateSelection) {
                    // No candidates or SYM not a Juying key - toggle SYM page
                    symLayoutController.toggleSymPage()
                    updateStatusBarText()
                    return true
                }
                // Otherwise, fall through to Juying candidate selection below
            }
        }

        // Handle Juying mode candidate selection for all configured keys
        // Juying: single-click selects candidate (Chinese: 5, English: 3)
        // Double-click Shift for prev page, double-click Alt for next page (Chinese only)
        // Also handle DEL to clear English next-word predictions
        if (juyingModeEnabled && hasAnyCandidates && event?.repeatCount == 0) {
            // Handle DEL to clear English next-word predictions AND perform backspace
            if (translatedKeyCode == KeyEvent.KEYCODE_DEL && hasWordPredictions && currentInputConnection != null) {
                val snapshot = englishWordPredictionController.getSnapshot()
                if (snapshot.hasSuggestions && snapshot.isNextWordPrediction) {
                    englishWordPredictionController.onBackspaceInput()
                    englishWordPredictionController.clearNextWordPredictions()
                    englishWordPredictionController.clearSuggestions()
                    // Skip next 2 update cycles: one for updateStatusBarText below, one for onUpdateSelection callback
                    skipNextWordPredictionUpdates = 2
                    updateStatusBarText()
                    // Don't consume the key - let backspace happen too
                    // Fall through to normal DEL/backspace handling
                }
            }

            // IMPORTANT: If Alt is held and user presses a NON-Alt key,
            // this is Alt+key for symbol input, NOT candidate selection.
            // Clear predictions and cancel pending runnable immediately, then let normal handling proceed.
            // Check multiple ways Alt might be active:
            // 1. altLastPressTime > 0 (Alt was pressed in Juying mode) - but only if Alt LED is still on
            // 2. altPressed is true (Alt physical state)
            // 3. Event meta state has META_ALT_ON (system reports Alt is held)
            // Use Alt LED status as the source of truth: if LED is off, Alt is not active
            val altLedIsOffJuying = !altLatchActive && !altOneShot && !altPressed
            val altHeldFromEvent = event != null && ((event.metaState and KeyEvent.META_ALT_ON) != 0)
            val altFromJuyingTracking = !altLedIsOffJuying && altLastPressTime > 0
            val altIsActive = altFromJuyingTracking || altPressed || altHeldFromEvent
            if (altIsActive && !isDeviceAltKey(keyCode)) {
                // Alt+key for symbol input - clear saved Alt state (used for long hold)
                // This marks that Alt was used for symbol input, not for candidate selection
                altUsedForSymbolInput = true
                savedAltSuggestion = null
                savedAltCandidatesForNextPage = emptyList()
                savedAltCurrentPage = 0
                savedAltChineseMode = null

                // Cancel any pending Alt selection
                pendingAltSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingAltSelectionRunnable = null

                // UNDO: Delete the character that was committed on Alt DOWN
                val ic = currentInputConnection
                if (altCommittedCharacter != null && ic != null) {
                    // First clear any composing text WITHOUT committing it
                    ic.setComposingText("", 1)
                    // Delete the committed character
                    ic.deleteSurroundingText(altCommittedCharacter!!.length, 0)
                    altCommittedCharacter = null
                    altRemainingBuffer = ""
                }

                // Clear predictions for all Chinese modes (may already be cleared on Alt DOWN)
                if (isPinyinMode) {
                    pinyinInputController.clearBuffer()
                    pinyinInputController.clearNextWordPredictions()
                } else if (isT9PinyinMode) {
                    t9PinyinInputController.clearBuffer()
                } else if (isShuangpinMode) {
                    shuangpinInputController.clearBuffer()
                    shuangpinInputController.clearNextWordPredictions()
                } else if (isWubiMode) {
                    wubiInputController.clearBuffer()
                    wubiInputController.clearNextWordPredictions()
                } else if (isZhenmaMode) {
                    zhenmaInputController.clearBuffer()
                    zhenmaInputController.clearNextWordPredictions()
                }
                // Finish any composing text and update UI
                ic?.finishComposingText()
                updateStatusBarText()
                // Reset altLastPressTime so we don't keep clearing
                altLastPressTime = 0L
                // Continue to normal handling (don't return) - the Alt+key will be processed below
            }

            // Try both raw and translated keycode for consistent key matching across devices
            // This handles cases where BlackBerry keycodes may or may not be translated
            var juyingCandidateIndex = SettingsManager.getJuyingCandidateIndex(this, translatedKeyCode)
            if (juyingCandidateIndex < 0 && keyCode != translatedKeyCode) {
                juyingCandidateIndex = SettingsManager.getJuyingCandidateIndex(this, keyCode)
            }

            // Apply swap logic for Shift/Alt keycodes when swap mode is ON
            // When swapped: Shift keycode -> index 4, Alt keycode -> index 1
            val shiftAltSwapped = SettingsManager.getShiftAltSwapped(this)
            if (shiftAltSwapped && juyingCandidateIndex >= 0) {
                if (isDeviceShiftKey(keyCode)) {
                    juyingCandidateIndex = 4  // Shift keycode picks rightmost (index 4)
                } else if (isDeviceAltKey(keyCode)) {
                    juyingCandidateIndex = 1  // Alt keycode picks leftmost (index 1)
                }
            }

            // Fallback: Check modifier keys using device-specific detection
            // This handles cases where RIGHT variants (e.g., KEYCODE_ALT_RIGHT) are pressed
            // but the Juying keys list only contains LEFT variants
            if (juyingCandidateIndex < 0) {
                when {
                    isDeviceShiftKey(keyCode) -> juyingCandidateIndex = if (shiftAltSwapped) 4 else 0  // Shift keycode: swapped->4, normal->0
                    isDeviceCtrlKey(keyCode) -> juyingCandidateIndex = 3   // Ctrl is 4th key (index 3)
                    isDeviceAltKey(keyCode) -> juyingCandidateIndex = if (shiftAltSwapped) 1 else 4    // Alt keycode: swapped->1 (leftmost), normal->4
                }
            }

            // For English word predictions, skip Space key (index 2 in 5-key layout) - let it type space normally
            // Also skip Alt key (index 4) in English mode - it acts normally
            // Also skip Shift when showing NEXT-WORD predictions (not prefix-based) - user should hold Shift to type capital letter
            // English uses only Shift/Sym/Ctrl for 3 suggestions, remapped to indices 0-2
            val isEnglishOnlyMode = hasWordPredictions && !isChineseInputActive
            val isEnglishNextWordMode = isEnglishOnlyMode && englishWordPredictionController.getSnapshot().isNextWordPrediction
            // Check if punctuation buttons should be active (affects Shift/Alt handling)
            val punctuationButtonsSetting = SettingsManager.isJuyingPunctuationButtons(this)
            val fixedPositionsSetting = SettingsManager.getJuyingFixedPositions(this)
            val isNextWordPredictionForPunctuation = when {
                isPinyinMode -> pinyinInputController.isShowingNextWordPredictions()
                isShuangpinMode -> shuangpinInputController.isShowingNextWordPredictions()
                isWubiMode -> wubiInputController.isShowingNextWordPredictions()
                isZhenmaMode -> zhenmaInputController.isShowingNextWordPredictions()
                else -> englishWordPredictionController.getSnapshot().isNextWordPrediction
            }
            val punctuationButtonsActive = punctuationButtonsSetting && fixedPositionsSetting && isNextWordPredictionForPunctuation

            // For Chinese mode with NO candidates AND NO word predictions, skip ALL keys - let them work normally
            // But if there ARE word predictions (next-word suggestions), Juying keys should still work
            val isChineseNoCandidate = isChineseInputActive && !hasChineseCandidates && !hasWordPredictions
            if (isChineseNoCandidate) {
                // No Chinese candidates and no word predictions - don't use any keys for Juying selection, let them work normally
                juyingCandidateIndex = -1
            } else if (isEnglishOnlyMode && (juyingCandidateIndex == 0 || juyingCandidateIndex == 4) && !punctuationButtonsActive) {
                // English mode: Skip Shift(0), Alt(4) for Juying selection UNLESS punctuation buttons are active
                // Shift is reserved for typing capital letters
                // Alt is reserved for other functions
                juyingCandidateIndex = -1
            } else if (isEnglishOnlyMode && juyingCandidateIndex >= 0 && !punctuationButtonsActive) {
                // Remap for English: Sym(1)→0, Space(2)→1, Ctrl(3)→2
                // Display: [1st best (left), current typed word (middle), 2nd best (right)]
                // Sym picks left (1st best), Space picks middle (current word), Ctrl picks right (2nd best)
                juyingCandidateIndex = when (juyingCandidateIndex) {
                    1 -> 0  // Sym -> left (1st best)
                    2 -> 1  // Space -> middle (current typed word)
                    3 -> 2  // Ctrl -> right (2nd best)
                    else -> -1
                }
            }

            if (juyingCandidateIndex >= 0) {
                val currentTime = System.currentTimeMillis()

                // Check for double-click on Shift for prev page (Chinese input only)
                val isDoubleClickShift = isDeviceShiftKey(keyCode) &&
                    (currentTime - shiftLastPressTime) <= altDoubleClickDelay

                // Check for double-click on Alt for next page (Chinese input only)
                // But skip this check if punctuation buttons are active - Alt should type period directly
                val isDoubleClickAlt = isDeviceAltKey(keyCode) &&
                    (currentTime - altLastPressTime) <= altDoubleClickDelay &&
                    !punctuationButtonsActive

                // Check if there's a pending Alt selection (first click waiting)
                // But skip if punctuation buttons are active
                val hasPendingAltSelection = pendingAltSelectionRunnable != null && !punctuationButtonsActive

                if (isDoubleClickShift && isChineseInputActive) {
                    // Double press Shift - go to previous page (Chinese only)
                    when {
                        isPinyinMode && pinyinInputController.hasPrevPage() -> {
                            pinyinInputController.prevPage()
                            updateStatusBarText()
                        }
                        isT9PinyinMode && t9PinyinInputController.getSnapshot().hasPrevPage -> {
                            t9PinyinInputController.prevPage()
                            updateStatusBarText()
                        }
                        isShuangpinMode && shuangpinInputController.hasPrevPage() -> {
                            shuangpinInputController.prevPage()
                            updateStatusBarText()
                        }
                        isWubiMode && wubiInputController.hasPrevPage() -> {
                            wubiInputController.prevPage()
                            updateStatusBarText()
                        }
                        isZhenmaMode && zhenmaInputController.hasPrevPage() -> {
                            zhenmaInputController.prevPage()
                            updateStatusBarText()
                        }
                    }
                    shiftLastPressTime = 0L
                    return true
                } else if ((isDoubleClickAlt || hasPendingAltSelection) && isDeviceAltKey(keyCode) && isChineseInputActive) {
                    // Alt key pressed while pending selection exists OR detected as double-click
                    // Cancel pending selection and restore candidates for next page
                    pendingAltSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingAltSelectionRunnable = null

                    // UNDO: Delete the character that was committed on Alt DOWN
                    val ic = currentInputConnection
                    if (altCommittedCharacter != null && ic != null) {
                        // First clear any composing text WITHOUT committing it
                        ic.setComposingText("", 1)
                        // Delete the committed character
                        ic.deleteSurroundingText(altCommittedCharacter!!.length, 0)
                        altCommittedCharacter = null
                        altRemainingBuffer = ""
                    }

                    // Restore saved candidates and buffer, then go to next page
                    if (savedAltCandidatesForNextPage.isNotEmpty() && savedAltChineseMode != null) {
                        // Helper to calculate dynamic candidate limit based on max candidate length for a specific page
                        val isDynamicCountEnabled = SettingsManager.getJuyingDynamicCandidateCount(this)
                        fun calculateDynamicLimit(allCandidates: List<String>, page: Int): Int {
                            // If dynamic candidate count is disabled, always return 5
                            if (!isDynamicCountEnabled) return 5
                            if (allCandidates.isEmpty()) return 5
                            // Calculate which candidates would be on this page with max page size (5)
                            val startIndex = page * 5
                            val pageCandidates = allCandidates.drop(startIndex).take(5)
                            if (pageCandidates.isEmpty()) return 5
                            val maxLen = pageCandidates.maxOfOrNull { it.length } ?: 1
                            return when {
                                maxLen >= 10 -> 1
                                maxLen >= 6 -> 3
                                else -> 5
                            }
                        }

                        when (savedAltChineseMode) {
                            "pinyin" -> {
                                // Restore buffer and syllable parsing state for proper candidate selection
                                if (savedAltBuffer.isNotEmpty()) {
                                    pinyinInputController.restoreBuffer(savedAltBuffer)
                                    ic?.setComposingText(savedAltBuffer, 1)
                                }
                                // Restore full state including syllable parsing for next Alt press
                                pinyinInputController.restoreForAltSelection(
                                    savedAltCandidatesForNextPage,
                                    savedAltCurrentPage,
                                    savedAltFirstSyllable,
                                    savedAltMatchedPinyin,
                                    savedAltPhraseCandidateCount,
                                    savedAltPhraseCandidateSet
                                )
                                // Calculate limit for the NEXT page (savedAltCurrentPage + 1)
                                val nextPage = savedAltCurrentPage + 1
                                val dynamicLimit = calculateDynamicLimit(savedAltCandidatesForNextPage, nextPage)
                                pinyinInputController.setDynamicDisplayLimit(dynamicLimit)
                                if (pinyinInputController.hasNextPage()) {
                                    pinyinInputController.nextPage()
                                }
                            }
                            "t9pinyin" -> {
                                t9PinyinInputController.restoreCandidatesForNextPage(savedAltCandidatesForNextPage, savedAltCurrentPage)
                                if (savedAltBuffer.isNotEmpty()) {
                                    t9PinyinInputController.restoreBuffer(savedAltBuffer)
                                    ic?.setComposingText(t9PinyinInputController.getDisplayBuffer(), 1)
                                }
                                // Calculate limit for the NEXT page (savedAltCurrentPage + 1)
                                val nextPage = savedAltCurrentPage + 1
                                val dynamicLimit = calculateDynamicLimit(savedAltCandidatesForNextPage, nextPage)
                                t9PinyinInputController.setJuyingModeEnabled(true)
                                t9PinyinInputController.setDynamicDisplayLimit(dynamicLimit)
                                if (t9PinyinInputController.getSnapshot().hasNextPage) {
                                    t9PinyinInputController.nextPage()
                                }
                            }
                            "shuangpin" -> {
                                shuangpinInputController.restoreCandidatesForNextPage(savedAltCandidatesForNextPage, savedAltCurrentPage)
                                if (savedAltBuffer.isNotEmpty()) {
                                    shuangpinInputController.restoreBuffer(savedAltBuffer)
                                    ic?.setComposingText(savedAltBuffer, 1)
                                }
                                // Calculate limit for the NEXT page (savedAltCurrentPage + 1)
                                val nextPage = savedAltCurrentPage + 1
                                val dynamicLimit = calculateDynamicLimit(savedAltCandidatesForNextPage, nextPage)
                                shuangpinInputController.setDynamicDisplayLimit(dynamicLimit)
                                if (shuangpinInputController.hasNextPage()) {
                                    shuangpinInputController.nextPage()
                                }
                            }
                            "wubi" -> {
                                wubiInputController.restoreCandidatesForNextPage(savedAltCandidatesForNextPage, savedAltCurrentPage)
                                if (savedAltBuffer.isNotEmpty()) {
                                    wubiInputController.restoreBuffer(savedAltBuffer)
                                    ic?.setComposingText(savedAltBuffer, 1)
                                }
                                // Calculate limit for the NEXT page (savedAltCurrentPage + 1)
                                val nextPage = savedAltCurrentPage + 1
                                val dynamicLimit = calculateDynamicLimit(savedAltCandidatesForNextPage, nextPage)
                                wubiInputController.setDynamicDisplayLimit(dynamicLimit)
                                if (wubiInputController.hasNextPage()) {
                                    wubiInputController.nextPage()
                                }
                            }
                            "zhenma" -> {
                                zhenmaInputController.restoreCandidatesForNextPage(savedAltCandidatesForNextPage, savedAltCurrentPage)
                                if (savedAltBuffer.isNotEmpty()) {
                                    zhenmaInputController.restoreBuffer(savedAltBuffer)
                                    ic?.setComposingText(savedAltBuffer, 1)
                                }
                                // Calculate limit for the NEXT page (savedAltCurrentPage + 1)
                                val nextPage = savedAltCurrentPage + 1
                                val dynamicLimit = calculateDynamicLimit(savedAltCandidatesForNextPage, nextPage)
                                zhenmaInputController.setDynamicDisplayLimit(dynamicLimit)
                                if (zhenmaInputController.hasNextPage()) {
                                    zhenmaInputController.nextPage()
                                }
                            }
                        }
                        // Clear saved state after restoring
                        savedAltSuggestion = null
                        savedAltCandidatesForNextPage = emptyList()
                        savedAltCurrentPage = 0
                        savedAltChineseMode = null
                        savedAltBuffer = ""
                        savedAltPhraseCandidateSet = emptySet()
                    }
                    updateStatusBarText()
                    altLastPressTime = 0L
                    // Clear Alt latch/one-shot to prevent latch from triggering on rapid double-clicks
                    // BUT keep pressed state so Alt+key for symbol input still works if user holds Alt after pagination
                    modifierStateController.clearAltState(resetPressedState = false)
                    // Manually ensure pressed state is set since Alt is still physically held
                    altPressed = true
                    altPhysicallyPressed = true
                    // Mark that Alt was used for pagination - skip normal Alt UP handling
                    altUsedForPagination = true
                    return true
                } else if (isDeviceAltKey(keyCode) && isChineseInputActive && hasChineseCandidates && !punctuationButtonsActive) {
                    // Alt pressed in Chinese mode with candidates (but NOT when punctuation buttons are active)
                    // INSTANT BEHAVIOR: Immediately commit the suggestion on Alt DOWN
                    // If double-click detected: undo and go to next page
                    // If Alt+key for symbol: undo and insert symbol
                    // When punctuation buttons are active, skip this and let the punctuation handler below deal with Alt

                    altUsedForSymbolInput = false  // Reset flag for new press
                    val ic = currentInputConnection

                    // Save state for undo and immediately commit
                    when {
                        isPinyinMode -> {
                            val candidates = pinyinInputController.getCurrentPageCandidates()
                            val allCandidates = pinyinInputController.getAllCandidates()
                            val suggestion = if (candidates.size > altCandidateIndex) candidates[altCandidateIndex] else null
                            savedAltSuggestion = suggestion
                            savedAltCandidatesForNextPage = allCandidates
                            savedAltCurrentPage = pinyinInputController.getCurrentPage()
                            savedAltBuffer = pinyinInputController.getBuffer()
                            savedAltFirstSyllable = pinyinInputController.getFirstSyllable()
                            savedAltMatchedPinyin = pinyinInputController.getMatchedPinyin()
                            savedAltPhraseCandidateCount = pinyinInputController.getPhraseCandidateCount()
                            savedAltPhraseCandidateSet = pinyinInputController.getPhraseCandidateSet()
                            savedAltChineseMode = "pinyin"

                            // Immediately commit the suggestion
                            if (suggestion != null && ic != null) {
                                val selected = pinyinInputController.selectCandidate(altCandidateIndex)
                                if (selected != null) {
                                    altCommittedCharacter = selected
                                    altRemainingBuffer = pinyinInputController.getBuffer()
                                    ic.commitText(selected, 1)
                                    playJuyingSelectionSound(keyCode)
                                    if (altRemainingBuffer.isNotEmpty()) {
                                        ic.setComposingText(altRemainingBuffer, 1)
                                        // Keep buffer and regenerate candidates for remaining pinyin
                                        // Don't clear - the buffer already has the remaining text
                                    } else {
                                        pinyinInputController.clearBuffer()
                                        pinyinInputController.clearNextWordPredictions()
                                    }
                                }
                            } else if (allCandidates.size > candidates.size) {
                                // No candidate at altCandidateIndex, but there are more candidates available
                                // Keep state for double-click Alt to navigate to next page
                                // Don't clear buffer - just wait for potential double-click
                            } else {
                                pinyinInputController.clearBuffer()
                                pinyinInputController.clearNextWordPredictions()
                            }
                        }
                        isT9PinyinMode -> {
                            val candidates = t9PinyinInputController.getCurrentPageCandidates()
                            val allCandidates = t9PinyinInputController.getAllCandidates()
                            val suggestion = if (candidates.size > altCandidateIndex) candidates[altCandidateIndex] else null
                            savedAltSuggestion = suggestion
                            savedAltCandidatesForNextPage = allCandidates
                            savedAltCurrentPage = t9PinyinInputController.getCurrentPage()
                            savedAltBuffer = t9PinyinInputController.getBuffer()
                            savedAltChineseMode = "t9pinyin"

                            if (suggestion != null && ic != null) {
                                val selected = t9PinyinInputController.selectCandidate(altCandidateIndex)
                                if (selected != null) {
                                    altCommittedCharacter = selected
                                    altRemainingBuffer = ""
                                    ic.commitText(selected, 1)
                                    playJuyingSelectionSound(keyCode)
                                    // T9 always clears buffer after selection
                                }
                            } else if (allCandidates.size > candidates.size) {
                                // No candidate at altCandidateIndex, but there are more candidates available
                                // Keep state for double-click Alt to navigate to next page
                            } else {
                                t9PinyinInputController.clearBuffer()
                            }
                        }
                        isShuangpinMode -> {
                            val candidates = shuangpinInputController.getCurrentPageCandidates()
                            val allCandidates = shuangpinInputController.getAllCandidates()
                            val suggestion = if (candidates.size > altCandidateIndex) candidates[altCandidateIndex] else null
                            savedAltSuggestion = suggestion
                            savedAltCandidatesForNextPage = allCandidates
                            savedAltCurrentPage = shuangpinInputController.getCurrentPage()
                            savedAltBuffer = shuangpinInputController.getBuffer()
                            savedAltChineseMode = "shuangpin"

                            if (suggestion != null && ic != null) {
                                val selected = shuangpinInputController.selectCandidate(altCandidateIndex)
                                if (selected != null) {
                                    altCommittedCharacter = selected
                                    altRemainingBuffer = shuangpinInputController.getBuffer()
                                    ic.commitText(selected, 1)
                                    playJuyingSelectionSound(keyCode)
                                    if (altRemainingBuffer.isNotEmpty()) {
                                        ic.setComposingText(altRemainingBuffer, 1)
                                        // Keep buffer and regenerate candidates for remaining pinyin
                                    } else {
                                        shuangpinInputController.clearBuffer()
                                        shuangpinInputController.clearNextWordPredictions()
                                    }
                                }
                            } else if (allCandidates.size > candidates.size) {
                                // No candidate at altCandidateIndex, but there are more candidates available
                                // Keep state for double-click Alt to navigate to next page
                            } else {
                                shuangpinInputController.clearBuffer()
                                shuangpinInputController.clearNextWordPredictions()
                            }
                        }
                        isWubiMode -> {
                            val candidates = wubiInputController.getCurrentPageCandidates()
                            val allCandidates = wubiInputController.getAllCandidates()
                            val suggestion = if (candidates.size > altCandidateIndex) candidates[altCandidateIndex] else null
                            savedAltSuggestion = suggestion
                            savedAltCandidatesForNextPage = allCandidates
                            savedAltCurrentPage = wubiInputController.getCurrentPage()
                            savedAltBuffer = wubiInputController.getBuffer()
                            savedAltChineseMode = "wubi"

                            if (suggestion != null && ic != null) {
                                val selected = wubiInputController.selectCandidate(altCandidateIndex)
                                if (selected != null) {
                                    altCommittedCharacter = selected
                                    altRemainingBuffer = wubiInputController.getBuffer()
                                    ic.commitText(selected, 1)
                                    playJuyingSelectionSound(keyCode)
                                    if (altRemainingBuffer.isNotEmpty()) {
                                        ic.setComposingText(altRemainingBuffer, 1)
                                        // Keep buffer and regenerate candidates for remaining input
                                    } else {
                                        wubiInputController.clearBuffer()
                                        wubiInputController.clearNextWordPredictions()
                                    }
                                }
                            } else if (allCandidates.size > candidates.size) {
                                // No candidate at altCandidateIndex, but there are more candidates available
                                // Keep state for double-click Alt to navigate to next page
                            } else {
                                wubiInputController.clearBuffer()
                                wubiInputController.clearNextWordPredictions()
                            }
                        }
                        isZhenmaMode -> {
                            val candidates = zhenmaInputController.getCurrentPageCandidates()
                            val allCandidates = zhenmaInputController.getAllCandidates()
                            val suggestion = if (candidates.size > altCandidateIndex) candidates[altCandidateIndex] else null
                            savedAltSuggestion = suggestion
                            savedAltCandidatesForNextPage = allCandidates
                            savedAltCurrentPage = zhenmaInputController.getCurrentPage()
                            savedAltBuffer = zhenmaInputController.getBuffer()
                            savedAltChineseMode = "zhenma"

                            if (suggestion != null && ic != null) {
                                val selected = zhenmaInputController.selectCandidate(altCandidateIndex)
                                if (selected != null) {
                                    altCommittedCharacter = selected
                                    altRemainingBuffer = zhenmaInputController.getBuffer()
                                    ic.commitText(selected, 1)
                                    playJuyingSelectionSound(keyCode)
                                    if (altRemainingBuffer.isNotEmpty()) {
                                        ic.setComposingText(altRemainingBuffer, 1)
                                        // Keep buffer and regenerate candidates for remaining input
                                    } else {
                                        zhenmaInputController.clearBuffer()
                                        zhenmaInputController.clearNextWordPredictions()
                                    }
                                }
                            } else if (allCandidates.size > candidates.size) {
                                // No candidate at altCandidateIndex, but there are more candidates available
                                // Keep state for double-click Alt to navigate to next page
                            } else {
                                zhenmaInputController.clearBuffer()
                                zhenmaInputController.clearNextWordPredictions()
                            }
                        }
                    }

                    // Set timing and state
                    altLastPressTime = currentTime
                    altPressed = true
                    altPhysicallyPressed = true  // Turn on Alt LED
                    updateStatusBarText()

                    return true
                } else if (isDeviceAltKey(keyCode) && isChineseInputActive && !hasChineseCandidates) {
                    // Alt pressed in Chinese mode without candidates
                    // Reset symbol input flag for new Alt press
                    altUsedForSymbolInput = false
                    // Do NOT consume the event - let it fall through to normal Alt handling
                    // (InputEventRouter will set up altOneShot for symbol input)
                } else {
                    // Non-Alt keys or non-Chinese mode - select candidate immediately
                    val ic = currentInputConnection
                    if (ic != null) {
                        // Check if we're in Chinese mode with word predictions (next-word suggestions)
                        // In this case, use word prediction display logic instead of Chinese candidate logic
                        val chineseModeWithWordPredictions = isChineseInputActive && !hasChineseCandidates && hasWordPredictions

                        // Get current candidate count - use DISPLAYED count (after dynamic limit) not raw page count
                        // This is critical for correct key mapping when candidates are reduced from 5 to 3
                        val rawCandidates = if (chineseModeWithWordPredictions) {
                            englishWordPredictionController.getSnapshot().suggestions
                        } else {
                            when {
                                isPinyinMode -> pinyinInputController.getCurrentPageCandidates()
                                isT9PinyinMode -> t9PinyinInputController.getCurrentPageCandidates()
                                isShuangpinMode -> shuangpinInputController.getCurrentPageCandidates()
                                isWubiMode -> wubiInputController.getCurrentPageCandidates()
                                isZhenmaMode -> zhenmaInputController.getCurrentPageCandidates()
                                else -> emptyList()
                            }
                        }
                        // Apply the same dynamic limit used in display (based on candidate length)
                        // When candidates are long (multi-character phrases), reduce count to avoid cramming
                        // Only apply dynamic limit if the setting is enabled; otherwise always use 5 keys
                        val isDynamicCountEnabled = SettingsManager.getJuyingDynamicCandidateCount(this)
                        val displayedCandidateLimit = if (juyingModeEnabled && hasChineseCandidates && rawCandidates.isNotEmpty()) {
                            if (isDynamicCountEnabled) {
                                val maxLen = rawCandidates.take(5).maxOfOrNull { it.length } ?: 1
                                when {
                                    maxLen >= 10 -> 1  // Very long phrases (10+ chars): show only 1
                                    maxLen >= 6 -> 3   // Long phrases (6-9 chars): show 3
                                    else -> 5          // Short candidates (1-5 chars): show 5
                                }
                            } else {
                                5  // Dynamic mode OFF: always map all 5 keys
                            }
                        } else {
                            rawCandidates.size
                        }
                        val candidateCount = minOf(rawCandidates.size, displayedCandidateLimit)

                        // Check if punctuation buttons are active (need this before candidate mapping)
                        val punctuationButtonsEnabled = SettingsManager.isJuyingPunctuationButtons(this@PhysicalKeyboardInputMethodService)
                        val isFixedPositionModeEnabled = SettingsManager.getJuyingFixedPositions(this@PhysicalKeyboardInputMethodService)
                        val isNextWordPredictionForMapping = when {
                            isPinyinMode -> pinyinInputController.isShowingNextWordPredictions()
                            isShuangpinMode -> shuangpinInputController.isShowingNextWordPredictions()
                            isWubiMode -> wubiInputController.isShowingNextWordPredictions()
                            isZhenmaMode -> zhenmaInputController.isShowingNextWordPredictions()
                            else -> englishWordPredictionController.getSnapshot().isNextWordPrediction
                        }
                        val punctuationModeActive = punctuationButtonsEnabled && isNextWordPredictionForMapping && isFixedPositionModeEnabled

                        // For Chinese input in Juying mode (with actual Chinese candidates), map key index to original candidate index
                        // Mapping depends on number of candidates:
                        // 1 candidate: Space(key 2) -> original 0
                        // 2 candidates: [2nd, 1st] - Sym(key 1)->orig 1, Space(key 2)->orig 0
                        // 3 candidates: [2nd, 1st, 3rd] - Sym(key 1)->orig 1, Space(key 2)->orig 0, Ctrl(key 3)->orig 2
                        // 4+ candidates: [2nd, 3rd, 1st, 4th, 5th] - original mapping
                        // For Chinese mode with word predictions (no Chinese candidates), use direct index mapping like English mode
                        // When punctuation buttons are active: positions 0,4 are punctuation, positions 1,2,3 map to candidates
                        val originalCandidateIndex = if (punctuationModeActive) {
                            // Punctuation mode: Shift=comma, Alt=period, others map to candidates
                            // Position 2 (Space) = best (index 0)
                            // Position 1 (Sym) = 2nd (index 1)
                            // Position 3 (Ctrl) = 3rd (index 2)
                            // Shift (0) and Alt (4) return special marker -100 to indicate punctuation
                            when (juyingCandidateIndex) {
                                0 -> -100  // Shift -> punctuation (comma)
                                1 -> 1     // Sym -> 2nd candidate
                                2 -> 0     // Space -> best candidate
                                3 -> 2     // Ctrl -> 3rd candidate
                                4 -> -100  // Alt -> punctuation (period)
                                else -> -1
                            }
                        } else if (isChineseInputActive && hasChineseCandidates) {
                            when (candidateCount) {
                                1 -> if (juyingCandidateIndex == 2) 0 else -1  // Only Space selects
                                2 -> when (juyingCandidateIndex) {
                                    1 -> 1  // Sym selects 2nd (left)
                                    2 -> 0  // Space selects 1st (right, best)
                                    else -> -1
                                }
                                3 -> when (juyingCandidateIndex) {
                                    1 -> 1  // Sym selects 2nd (left)
                                    2 -> 0  // Space selects 1st (middle, best)
                                    3 -> 2  // Ctrl selects 3rd (right)
                                    else -> -1
                                }
                                else -> when (juyingCandidateIndex) {
                                    0 -> 1  // Shift selects 2nd
                                    1 -> 2  // Sym selects 3rd
                                    2 -> 0  // Space selects 1st (best)
                                    3 -> 3  // Ctrl selects 4th
                                    4 -> 4  // Alt selects 5th
                                    else -> juyingCandidateIndex
                                }
                            }
                        } else if (chineseModeWithWordPredictions) {
                            // Chinese mode with word predictions: use 5-key mapping similar to Chinese candidates
                            // Display reordering: [2nd, 3rd, 1st, 4th, 5th]
                            when (candidateCount) {
                                0 -> -1  // No candidates
                                1 -> if (juyingCandidateIndex == 2) 0 else -1  // Only Space selects
                                2 -> when (juyingCandidateIndex) {
                                    1 -> 1  // Sym selects 2nd (left)
                                    2 -> 0  // Space selects 1st (right, best)
                                    else -> -1
                                }
                                3 -> when (juyingCandidateIndex) {
                                    1 -> 1  // Sym selects 2nd (left)
                                    2 -> 0  // Space selects 1st (middle, best)
                                    3 -> 2  // Ctrl selects 3rd (right)
                                    else -> -1
                                }
                                else -> when (juyingCandidateIndex) {
                                    0 -> 1  // Shift selects 2nd
                                    1 -> 2  // Sym selects 3rd
                                    2 -> 0  // Space selects 1st (best)
                                    3 -> 3  // Ctrl selects 4th
                                    4 -> 4  // Alt selects 5th
                                    else -> juyingCandidateIndex
                                }
                            }
                        } else {
                            juyingCandidateIndex // English mode - no reordering
                        }

                        // Skip if key doesn't map to a valid candidate (but -100 is special for punctuation)
                        if (originalCandidateIndex < 0 && originalCandidateIndex != -100) {
                            return true
                        }

                        // Handle punctuation buttons if active (use juyingCandidateIndex for position check)
                        // This must be checked BEFORE candidate selection since -100 indicates punctuation
                        if (punctuationModeActive && originalCandidateIndex == -100) {
                            // Get the custom punctuation from settings and convert based on Chinese/English mode
                            val leftPunctuationEnglish = SettingsManager.getJuyingPunctuationLeft(this@PhysicalKeyboardInputMethodService)
                            val rightPunctuationEnglish = SettingsManager.getJuyingPunctuationRight(this@PhysicalKeyboardInputMethodService)
                            val leftPunctuation = if (isChinesePunctuationModeActive())
                                SettingsManager.getChinesePunctuation(leftPunctuationEnglish)
                            else
                                leftPunctuationEnglish
                            val rightPunctuation = if (isChinesePunctuationModeActive())
                                SettingsManager.getChinesePunctuation(rightPunctuationEnglish)
                            else
                                rightPunctuationEnglish

                            when (juyingCandidateIndex) {
                                0 -> {
                                    // Shift position - commit left punctuation
                                    ic.commitText(leftPunctuation, 1)
                                    playJuyingSelectionSound(keyCode)
                                    // Clear next word predictions after punctuation
                                    when {
                                        isPinyinMode -> pinyinInputController.clearNextWordPredictions()
                                        isShuangpinMode -> shuangpinInputController.clearNextWordPredictions()
                                        isWubiMode -> wubiInputController.clearNextWordPredictions()
                                        isZhenmaMode -> zhenmaInputController.clearNextWordPredictions()
                                        else -> {
                                            englishWordPredictionController.clearNextWordPredictions()
                                            englishWordPredictionController.clearSuggestions()
                                        }
                                    }
                                    updateStatusBarText()
                                    return true
                                }
                                4 -> {
                                    // Alt position - commit right punctuation
                                    ic.commitText(rightPunctuation, 1)
                                    playJuyingSelectionSound(keyCode)
                                    // Clear next word predictions after punctuation
                                    when {
                                        isPinyinMode -> pinyinInputController.clearNextWordPredictions()
                                        isShuangpinMode -> shuangpinInputController.clearNextWordPredictions()
                                        isWubiMode -> wubiInputController.clearNextWordPredictions()
                                        isZhenmaMode -> zhenmaInputController.clearNextWordPredictions()
                                        else -> {
                                            englishWordPredictionController.clearNextWordPredictions()
                                            englishWordPredictionController.clearSuggestions()
                                        }
                                    }
                                    updateStatusBarText()
                                    return true
                                }
                            }
                        }

                        when {
                            isPinyinMode -> {
                                // Save buffer length BEFORE selection (for deletion)
                                val bufferLengthBeforeSelect = pinyinInputController.getBuffer().length
                                val wasShowingPredictions = pinyinInputController.isShowingNextWordPredictions()
                                val selected = pinyinInputController.selectCandidate(originalCandidateIndex)
                                if (selected != null) {
                                    var remainingBuffer = pinyinInputController.getBuffer()
                                    // For memory/prediction candidates, buffer may not be consumed normally
                                    // If buffer unchanged, it's a memory candidate - clear the buffer entirely
                                    // But don't clear if showing predictions (to preserve chained predictions)
                                    if (remainingBuffer.length == bufferLengthBeforeSelect && !wasShowingPredictions) {
                                        pinyinInputController.clearBuffer()
                                        remainingBuffer = ""
                                    }
                                    // Clear composing text first, then commit - this avoids race conditions
                                    // where finishComposingText commits the pinyin and deleteSurroundingText
                                    // may not work properly in some apps
                                    ic.setComposingText("", 1)
                                    ic.commitText(selected, 1)
                                    playJuyingSelectionSound(keyCode)
                                    // Set remaining buffer as composing text (e.g., 'wode' -> '我' + 'de' underlined)
                                    if (remainingBuffer.isNotEmpty()) {
                                        ic.setComposingText(remainingBuffer, 1)
                                    }
                                    updateStatusBarText()
                                    if (isDeviceShiftKey(keyCode)) shiftLastPressTime = currentTime
                                    return true
                                }
                            }
                            isT9PinyinMode -> {
                                val selected = t9PinyinInputController.selectCandidate(originalCandidateIndex)
                                if (selected != null) {
                                    // T9 always clears buffer after selection (no partial matching)
                                    ic.setComposingText("", 1)
                                    ic.commitText(selected, 1)
                                    playJuyingSelectionSound(keyCode)
                                    updateStatusBarText()
                                    if (isDeviceShiftKey(keyCode)) shiftLastPressTime = currentTime
                                    return true
                                }
                            }
                            isShuangpinMode -> {
                                val bufferLengthBeforeSelect = shuangpinInputController.getBuffer().length
                                val wasShowingPredictions = shuangpinInputController.isShowingNextWordPredictions()
                                val selected = shuangpinInputController.selectCandidate(originalCandidateIndex)
                                if (selected != null) {
                                    var remainingBuffer = shuangpinInputController.getBuffer()
                                    // Only clear buffer if not showing predictions (to preserve chained predictions)
                                    if (remainingBuffer.length == bufferLengthBeforeSelect && !wasShowingPredictions) {
                                        shuangpinInputController.clearBuffer()
                                        remainingBuffer = ""
                                    }
                                    // Clear composing text first, then commit
                                    ic.setComposingText("", 1)
                                    ic.commitText(selected, 1)
                                    playJuyingSelectionSound(keyCode)
                                    if (remainingBuffer.isNotEmpty()) {
                                        ic.setComposingText(remainingBuffer, 1)
                                    }
                                    updateStatusBarText()
                                    if (isDeviceShiftKey(keyCode)) shiftLastPressTime = currentTime
                                    return true
                                }
                            }
                            isWubiMode -> {
                                val bufferLengthBeforeSelect = wubiInputController.getBuffer().length
                                val wasShowingPredictions = wubiInputController.isShowingNextWordPredictions()
                                val selected = wubiInputController.selectCandidate(originalCandidateIndex)
                                if (selected != null) {
                                    var remainingBuffer = wubiInputController.getBuffer()
                                    // Only clear buffer if not showing predictions (to preserve chained predictions)
                                    if (remainingBuffer.length == bufferLengthBeforeSelect && !wasShowingPredictions) {
                                        wubiInputController.clearBuffer()
                                        remainingBuffer = ""
                                    }
                                    // Clear composing text first, then commit
                                    ic.setComposingText("", 1)
                                    ic.commitText(selected, 1)
                                    playJuyingSelectionSound(keyCode)
                                    if (remainingBuffer.isNotEmpty()) {
                                        ic.setComposingText(remainingBuffer, 1)
                                    }
                                    updateStatusBarText()
                                    if (isDeviceShiftKey(keyCode)) shiftLastPressTime = currentTime
                                    return true
                                }
                            }
                            isZhenmaMode -> {
                                val bufferLengthBeforeSelect = zhenmaInputController.getBuffer().length
                                val wasShowingPredictions = zhenmaInputController.isShowingNextWordPredictions()
                                val selected = zhenmaInputController.selectCandidate(originalCandidateIndex)
                                if (selected != null) {
                                    var remainingBuffer = zhenmaInputController.getBuffer()
                                    // Only clear buffer if not showing predictions (to preserve chained predictions)
                                    if (remainingBuffer.length == bufferLengthBeforeSelect && !wasShowingPredictions) {
                                        zhenmaInputController.clearBuffer()
                                        remainingBuffer = ""
                                    }
                                    // Clear composing text first, then commit
                                    ic.setComposingText("", 1)
                                    ic.commitText(selected, 1)
                                    playJuyingSelectionSound(keyCode)
                                    if (remainingBuffer.isNotEmpty()) {
                                        ic.setComposingText(remainingBuffer, 1)
                                    }
                                    updateStatusBarText()
                                    if (isDeviceShiftKey(keyCode)) shiftLastPressTime = currentTime
                                    return true
                                }
                            }
                            hasWordPredictions -> {
                                // Word predictions mode (English or Chinese with next-word predictions)
                                // For Chinese mode with word predictions, use originalCandidateIndex (5-key mapping)
                                // For pure English mode, use juyingCandidateIndex directly (3-key mapping)
                                val wordPredictionIndex = if (chineseModeWithWordPredictions) {
                                    originalCandidateIndex
                                } else {
                                    juyingCandidateIndex
                                }
                                // Check prefix BEFORE calling selectSuggestion (which may clear it)
                                val prefixWasEmpty = englishWordPredictionController.getCurrentPrefix().isEmpty()
                                val result = englishWordPredictionController.selectSuggestion(wordPredictionIndex)
                                if (result != null) {
                                    ic.deleteSurroundingText(result.prefixLength, 0)
                                    ic.commitText(result.word + " ", 1)
                                    playJuyingSelectionSound(keyCode)
                                    englishWordPredictionController.updateFromCursor(ic)
                                    updateStatusBarText()
                                    // Consumed - always return after successful selection
                                    if (isDeviceShiftKey(keyCode)) shiftLastPressTime = currentTime
                                    return true
                                } else if (juyingCandidateIndex == 2 && prefixWasEmpty) {
                                    // Space pressed (key index 2) but no prefix to commit - let it type a space normally
                                    // Don't consume the key event, fall through to normal handling
                                } else {
                                    // Selection failed but key should still be consumed to prevent SYM toggle
                                    if (isDeviceShiftKey(keyCode)) shiftLastPressTime = currentTime
                                    return true
                                }
                            }
                        }
                        // If we reach here, either no word predictions or Space with no prefix
                        // For non-Space Juying keys, still consume the key
                        // Space is index 2 in 5-key mode (Chinese/word predictions) and index 1 in 3-key English mode
                        val spaceIndex = if (chineseModeWithWordPredictions || isChineseInputActive) 2 else 1
                        if (!(hasWordPredictions && juyingCandidateIndex == spaceIndex)) {
                            if (isDeviceShiftKey(keyCode)) shiftLastPressTime = currentTime
                            return true
                        }
                    } else {
                        // Update press time for double-click detection
                        if (isDeviceShiftKey(keyCode)) shiftLastPressTime = currentTime
                        return true
                    }
                }
            }
        }

        // Handle Alt/Shift double press for pagination (non-Juying mode or non-Juying keys)
        if (hasCandidatesToPaginate && event?.repeatCount == 0) {
            val currentTime = System.currentTimeMillis()

            when {
                isDeviceAltKey(keyCode) -> {
                    val timeSinceLastPress = currentTime - altLastPressTime
                    if (timeSinceLastPress <= altDoubleClickDelay) {
                        // Double press detected - go to next page
                        when {
                            isPinyinMode && pinyinInputController.hasNextPage() -> {
                                pinyinInputController.nextPage()
                                updateStatusBarText()
                            }
                            isShuangpinMode && shuangpinInputController.hasNextPage() -> {
                                shuangpinInputController.nextPage()
                                updateStatusBarText()
                            }
                            isWubiMode && wubiInputController.hasNextPage() -> {
                                wubiInputController.nextPage()
                                updateStatusBarText()
                            }
                            isZhenmaMode && zhenmaInputController.hasNextPage() -> {
                                zhenmaInputController.nextPage()
                                updateStatusBarText()
                            }
                            isT9PinyinMode && t9PinyinInputController.getSnapshot().hasNextPage -> {
                                t9PinyinInputController.nextPage()
                                updateStatusBarText()
                            }
                            englishWordPredictionController.hasNextPage() -> {
                                englishWordPredictionController.nextPage()
                                updateStatusBarText()
                            }
                        }
                        altLastPressTime = 0L
                        // Clear full Alt state to prevent latch from triggering on rapid double-clicks
                        modifierStateController.clearAltState(resetPressedState = true)
                        // Mark that Alt was used for pagination - skip normal Alt UP handling
                        altUsedForPagination = true
                        return true
                    }
                    altLastPressTime = currentTime
                }
                isDeviceShiftKey(keyCode) -> {
                    val timeSinceLastPress = currentTime - shiftLastPressTime
                    if (timeSinceLastPress <= altDoubleClickDelay) {
                        // Double press detected - go to previous page
                        when {
                            isPinyinMode && pinyinInputController.hasPrevPage() -> {
                                pinyinInputController.prevPage()
                                updateStatusBarText()
                            }
                            isShuangpinMode && shuangpinInputController.hasPrevPage() -> {
                                shuangpinInputController.prevPage()
                                updateStatusBarText()
                            }
                            isWubiMode && wubiInputController.hasPrevPage() -> {
                                wubiInputController.prevPage()
                                updateStatusBarText()
                            }
                            isZhenmaMode && zhenmaInputController.hasPrevPage() -> {
                                zhenmaInputController.prevPage()
                                updateStatusBarText()
                            }
                            isT9PinyinMode && t9PinyinInputController.getSnapshot().hasPrevPage -> {
                                t9PinyinInputController.prevPage()
                                updateStatusBarText()
                            }
                            englishWordPredictionController.hasPrevPage() -> {
                                englishWordPredictionController.prevPage()
                                updateStatusBarText()
                            }
                        }
                        shiftLastPressTime = 0L
                        // Clear full Shift state to prevent latch from triggering on rapid double-clicks
                        modifierStateController.clearShiftState(resetPressedState = true)
                        return true
                    }
                    shiftLastPressTime = currentTime
                }
            }
        }

        // If NO editable field is active, handle ONLY nav mode
        if (!hasEditableField) {
            return inputEventRouter.handleKeyDownWithNoEditableField(
                keyCode = translatedKeyCode,
                event = event,
                ctrlKeyMap = ctrlKeyMap,
                callbacks = InputEventRouter.NoEditableFieldCallbacks(
                    isAlphabeticKey = { code -> isAlphabeticKey(code) },
                    isLauncherPackage = { pkg -> launcherShortcutController.isLauncher(pkg) },
                    handleLauncherShortcut = { key -> launcherShortcutController.handleLauncherShortcut(key) },
                    handlePowerShortcut = { key -> launcherShortcutController.handlePowerShortcut(key) },
                    togglePowerShortcutMode = { message, isNavActive ->
                        launcherShortcutController.togglePowerShortcutMode(
                            showToast = { showToast(it) },
                            isNavModeActive = isNavActive
                        )
                    },
                    callSuper = { super.onKeyDown(keyCode, event) },
                    currentInputConnection = { currentInputConnection }
                ),
                ctrlLatchActive = ctrlLatchActive,
                editorInfo = info,
                currentPackageName = currentPackageName,
                powerShortcutsEnabled = SettingsManager.getPowerShortcutsEnabled(this)
            )
        }
        
        val routingResult = inputEventRouter.handleEditableFieldKeyDownPrelude(
            keyCode = translatedKeyCode,
            params = InputEventRouter.EditableFieldKeyDownParams(
                ctrlLatchFromNavMode = ctrlLatchFromNavMode,
                ctrlLatchActive = ctrlLatchActive,
                isInputViewActive = isInputViewActive,
                isInputViewShown = isInputViewShown,
                hasInputConnection = initialInputConnection != null
            ),
            callbacks = InputEventRouter.EditableFieldKeyDownCallbacks(
                exitNavMode = { navModeController.exitNavMode() },
                ensureInputViewCreated = { keyboardVisibilityController.ensureInputViewCreated() },
                callSuper = { super.onKeyDown(keyCode, event) }
            )
        )
        when (routingResult) {
            InputEventRouter.EditableFieldRoutingResult.Consume -> return true
            InputEventRouter.EditableFieldRoutingResult.CallSuper -> return super.onKeyDown(keyCode, event)
            InputEventRouter.EditableFieldRoutingResult.Continue -> {}
        }
        
        val ic = currentInputConnection

        // Continue with normal IME logic
        KeyboardEventTracker.notifyKeyEvent(keyCode, event, "KEY_DOWN")
        if (!isInputViewShown && isInputViewActive) {
            ensureInputViewCreated()
        }

        // Handle Enter key in Pinyin mode
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            // Toggle Chinese input mode (Pinyin or Wubi) with Shift+Enter (if enabled)
            if (shiftPressed && !ctrlPressed && !altPressed && SettingsManager.isShiftEnterToggleInputEnabled(this)) {
                toggleChineseInputMode()
                return true
            }

            // Commit Pinyin buffer as-is (without conversion) with plain Enter
            // Don't add space - user is typing English in Chinese mode
            if (pinyinInputController.isPinyinMode() && !shiftPressed && !ctrlPressed && !altPressed && ic != null) {
                val buffer = pinyinInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    val committed = pinyinInputController.commitBufferAsIs()
                    if (committed != null) {
                        ic.commitText(committed, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Commit Shuangpin buffer as-is (without conversion) with plain Enter
            // Don't add space - user is typing English in Chinese mode
            if (shuangpinInputController.isShuangpinMode() && !shiftPressed && !ctrlPressed && !altPressed && ic != null) {
                val buffer = shuangpinInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    val committed = shuangpinInputController.commitBufferAsIs()
                    if (committed != null) {
                        ic.commitText(committed, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Commit Ziranma buffer as-is (without conversion) with plain Enter
            // Don't add space - user is typing English in Chinese mode
            if (ziranmaInputController.isZiranmaMode() && !shiftPressed && !ctrlPressed && !altPressed && ic != null) {
                val buffer = ziranmaInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    val committed = ziranmaInputController.commitBufferAsIs()
                    if (committed != null) {
                        ic.commitText(committed, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Commit Wubi buffer as-is (without conversion) with plain Enter
            // Don't add space - user is typing English in Chinese mode
            if (wubiInputController.isWubiMode() && !shiftPressed && !ctrlPressed && !altPressed && ic != null) {
                val buffer = wubiInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    val committed = wubiInputController.commitBufferAsIs()
                    if (committed != null) {
                        ic.commitText(committed, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Commit Zhenma buffer as-is (without conversion) with plain Enter
            // Don't add space - user is typing English in Chinese mode
            if (zhenmaInputController.isZhenmaMode() && !shiftPressed && !ctrlPressed && !altPressed && ic != null) {
                val buffer = zhenmaInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    val committed = zhenmaInputController.commitBufferAsIs()
                    if (committed != null) {
                        ic.commitText(committed, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // After handling Chinese input buffers, check if Enter should perform an action
            // (Send, Search, Go, Done, Next) instead of inserting a newline
            if (!shiftPressed && !ctrlPressed && !altPressed && ic != null) {
                if (handleEnterAction(ic)) {
                    return true
                }
            }
        }

        // Handle DEL when English next-word predictions are showing
        // Clear predictions and let the DEL key delete the space (don't consume the key)
        // Check this BEFORE Chinese input mode handling since Juying mode can show English predictions
        if (translatedKeyCode == KeyEvent.KEYCODE_DEL && ic != null && !isChineseInputModeActive()) {
            val snapshot = englishWordPredictionController.getSnapshot()
            if (snapshot.hasSuggestions && snapshot.isNextWordPrediction) {
                englishWordPredictionController.onBackspaceInput()
                englishWordPredictionController.clearNextWordPredictions()
                englishWordPredictionController.clearSuggestions()
                // Skip next 2 update cycles: one for updateStatusBarText below, one for onUpdateSelection callback
                skipNextWordPredictionUpdates = 2
                updateStatusBarText()
                // Don't return here - let the DEL key be processed normally to delete the space
            }
        }

        // Handle English word prediction (when NOT in Chinese input mode)
        if (!isChineseInputModeActive() && ic != null) {
            // Update suggestions from current cursor position
            // Skip if we just cleared predictions due to Shift+letter or DEL (let them stay cleared)
            if (skipNextWordPredictionUpdates > 0) {
                skipNextWordPredictionUpdates--
            } else {
                englishWordPredictionController.updateFromCursor(ic)
            }

            if (englishWordPredictionController.hasSuggestions()) {
                // Alt+letter keys select suggestion - mapping depends on device type
                // (determined by alt_key_mappings.json for each device)
                // Skip in Juying mode - Alt+W/E/R should input numbers, not select suggestions
                val juyingModeEnabled = SettingsManager.getJuyingModeEnabled(this)
                val number = if (altPressed && !ctrlPressed && !shiftPressed && !juyingModeEnabled) {
                    altSymManager.getAltKeyNumber(keyCode)
                } else {
                    0
                }
                if (number in 1..9) {
                    val result = englishWordPredictionController.selectSuggestion(number - 1)
                    if (result != null) {
                        ic.deleteSurroundingText(result.prefixLength, 0)
                        ic.commitText(result.word + " ", 1)
                        // Update from cursor to trigger next-word predictions
                        englishWordPredictionController.updateFromCursor(ic)
                        // Clear Alt modifier so next key doesn't produce alternate character
                        modifierStateController.clearAltState(resetPressedState = true)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Handle Shift+letter when English predictions are showing (both next-word and prefix-based):
            // - Only type the capital letter
            // - Clear the predictions completely
            // - Don't commit any prediction word
            if (shiftPressed && !ctrlPressed && !altPressed && ic != null && event != null) {
                val snapshot = englishWordPredictionController.getSnapshot()
                // Apply to ALL English predictions (next-word and prefix-based)
                if (snapshot.hasSuggestions && event.unicodeChar != 0) {
                    val char = event.unicodeChar.toChar()
                    if (char.isLetter()) {
                        // Clear the predictions without committing anything
                        englishWordPredictionController.clearSuggestions()
                        englishWordPredictionController.clearNextWordPredictions()  // Also clear predictor state
                        skipNextWordPredictionUpdates = 1  // Skip next update cycle to prevent re-triggering
                        updateStatusBarText()
                        // Don't return - let normal letter handling with Shift occur (will type capital letter)
                    }
                }
            }
        }

        // Handle Pinyin input mode
        if (pinyinInputController.isPinyinMode() && ic != null) {
            // Handle SYM mode - when SYM is active, allow symbol input just like in English mode
            if (symLayoutController.isSymActive()) {
                val symResult = symLayoutController.handleKeyWhenActive(
                    keyCode,
                    event,
                    ic,
                    ctrlLatchActive = ctrlLatchActive,
                    altLatchActive = altLatchActive,
                    updateStatusBar = { updateStatusBarText() },
                    onSymbolInserted = {
                        // Clear next word predictions after inserting symbol
                        pinyinInputController.clearNextWordPredictions()
                    }
                )
                when (symResult) {
                    SymLayoutController.SymKeyResult.CONSUME -> return true
                    SymLayoutController.SymKeyResult.CALL_SUPER -> return super.onKeyDown(keyCode, event)
                    SymLayoutController.SymKeyResult.NOT_HANDLED -> { /* Continue to Pinyin handling */ }
                }
            }

            // FIRST: Handle Alt modifier - when Alt is active (latched, one-shot, or pressed), input alternate characters
            // When a non-Alt key is pressed while Alt is held, always handle it as symbol input
            // (Alt key alone for candidate selection is handled earlier in Juying key handling)
            val currentTime = System.currentTimeMillis()
            val longPressThreshold = SettingsManager.getLongPressThreshold(this)
            val altHoldDuration = if (altLastPressTime > 0) currentTime - altLastPressTime else 0L
            val isAltLongPress = altHoldDuration >= longPressThreshold

            // Use Alt LED status as the source of truth: if LED is off (no latch, no one-shot, not pressed),
            // then Alt is definitively not active regardless of event meta state or tracking
            val altLedIsOff = !altLatchActive && !altOneShot && !altPressed

            // Check multiple ways Alt might be active:
            // 1. Controller knows (altLatchActive, altOneShot, altPressed)
            // 2. Event meta state has ALT_ON (ignored if latch was just disabled or LED is off)
            // 3. altLastPressTime > 0 (Alt key was pressed in Juying mode and we're tracking it)
            val altFromEvent = !altLatchJustDisabled && !altUsedForCandidateSelection && !altLedIsOff && event != null && ((event.metaState and KeyEvent.META_ALT_ON) != 0)
            val altFromJuyingTracking = !altLedIsOff && altLastPressTime > 0
            // Skip Alt handling if latch was just disabled or Alt was used for candidate selection
            val shouldSkipAltHandling = (altLatchJustDisabled && !altLatchActive && !altOneShot) || altUsedForCandidateSelection || altLedIsOff
            if (event != null && !shouldSkipAltHandling && (altLatchActive || altOneShot || altPressed || altFromEvent || altFromJuyingTracking)) {
                // Get the character with Alt modifier applied
                // IMPORTANT: Use Pastiera's altSymManager mapping (device-specific) instead of
                // system's getUnicodeChar(META_ALT_ON) which may not have the correct mappings
                val altMappedChar = altSymManager.getAltMappings()[keyCode]
                val altChar = altMappedChar?.firstOrNull()?.code ?: event.getUnicodeChar(KeyEvent.META_ALT_ON)
                // Get the base character without any modifiers
                val baseChar = event.getUnicodeChar(0)

                // In non-Juying mode, if Alt produces a digit (1-9) and there are candidates,
                // handle candidate selection right here using the Alt-mapped digit
                val isDigitForSelection = !juyingModeEnabled &&
                                          pinyinInputController.hasCandidates() &&
                                          altChar.toChar().isDigit() &&
                                          altChar.toChar() in '1'..'9'

                // Handle candidate selection when Alt+key produces a digit
                if (isDigitForSelection) {
                    val number = altChar.toChar().digitToInt()
                    val index = number - 1
                    val selected = pinyinInputController.selectCandidate(index)
                    if (selected != null) {
                        ic.commitText(selected, 1)

                        // Set remaining buffer as new composing text
                        val remainingBuffer = pinyinInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }

                        // Clear Alt modifier (including latch state from double-tap)
                        modifierStateController.clearAltState(resetPressedState = true)
                        altUsedForPagination = false
                        altLastPressTime = 0L

                        // Set flag to ignore Alt until physical key is released
                        // This prevents Alt from staying active after selection when Alt key is still held
                        altLatchJustDisabled = true
                        altUsedForCandidateSelection = true

                        updateStatusBarText()
                        return true
                    }
                }

                // Only proceed if we get a valid alternate character that's different from the base one
                // AND it's not a digit for candidate selection
                if (altChar != 0 && altChar != baseChar && !isDigitForSelection) {
                    // Clear buffer/predictions when Alt symbol is about to be committed
                    // This prevents pinyin from becoming English text in the input
                    if (juyingModeEnabled) {
                        pinyinInputController.clearBuffer()
                        pinyinInputController.clearNextWordPredictions()
                        ic.finishComposingText()
                        altUsedForSymbolInput = true  // Mark that Alt was used for symbol input
                        // Clear saved Alt state (long hold for symbol cancels single-press insertion)
                        savedAltSuggestion = null
                        savedAltCandidatesForNextPage = emptyList()
                        savedAltCurrentPage = 0
                        savedAltChineseMode = null
                        // Cancel any pending Alt selection
                        pendingAltSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingAltSelectionRunnable = null
                    } else {
                        // Non-Juying mode: Clear buffer instead of committing as English
                        // When user presses Alt+key for symbol, they want to cancel pinyin input
                        val buffer = pinyinInputController.getBuffer()
                        if (buffer.isNotEmpty()) {
                            pinyinInputController.clearBuffer()
                            pinyinInputController.clearNextWordPredictions()
                            ic.finishComposingText()  // Remove composing text without committing
                        }
                    }

                    // Convert punctuation to Chinese if applicable (only when Chinese punctuation mode is enabled)
                    // Use altMappedChar directly if available, otherwise use altChar
                    val char = altMappedChar?.firstOrNull() ?: altChar.toChar()
                    val chinesePunctuation: String? = if (pinyinInputController.isChinesePunctuationMode()) {
                        when (char) {
                            ',' -> "，"
                            '.' -> "。"
                            '!' -> "！"
                            '?' -> "？"
                            ':' -> "："
                            ';' -> "；"
                            '(' -> "（"
                            ')' -> "）"
                            '[' -> "【"
                            ']' -> "】"
                            '<' -> "《"
                            '>' -> "》"
                            '~' -> "～"
                            '\\' -> "、"
                            '^' -> "……"
                            '_' -> "——"
                            '"' -> {
                                val result = if (pinyinInputController.isNextDoubleQuoteOpening()) "\u201C" else "\u201D"
                                pinyinInputController.toggleDoubleQuoteState()
                                result
                            }
                            '\'' -> {
                                val result = if (pinyinInputController.isNextSingleQuoteOpening()) "\u2018" else "\u2019"
                                pinyinInputController.toggleSingleQuoteState()
                                result
                            }
                            else -> null
                        }
                    } else null

                    // Input Chinese punctuation or the original character
                    val textToCommit = chinesePunctuation ?: char.toString()
                    ic.commitText(textToCommit, 1)

                    // In Juying mode, ALWAYS clear predictions when Alt symbol is committed
                    // This should happen regardless of how Alt was activated (long-press, one-shot, or latch)
                    if (juyingModeEnabled) {
                        pinyinInputController.clearBuffer()
                        pinyinInputController.clearNextWordPredictions()
                    }

                    // Handle Alt state clearing based on mode
                    // In Juying mode with long-press, OR in non-Juying mode with suggestions visible,
                    // clear all Alt state (including latch) - this prevents Alt getting stuck
                    // EXCEPTION: For digits and period, don't clear Alt state - allow continuous number input (e.g., "10.5")
                    val isDigitOrPeriod = char.isDigit() || char == '.'
                    val shouldClearAllAltState = (isAltLongPress && juyingModeEnabled && !isDigitOrPeriod) ||
                        (!juyingModeEnabled && hasCandidatesToPaginate && altLatchActive)
                    if (shouldClearAllAltState) {
                        modifierStateController.clearAltState(resetPressedState = true)  // Clear all Alt state
                        altLastPressTime = 0L  // Reset timing state
                        altLatchJustDisabled = true
                    } else if (isDigitOrPeriod) {
                        // For continuous Alt input: ALWAYS update altLastPressTime for digits and period
                        // This ensures altFromJuyingTracking remains true for subsequent digit/period keys
                        altLastPressTime = currentTime
                        if (altOneShot && !altLatchActive) {
                            modifierStateController.clearAltState(resetPressedState = false)
                        }
                    } else if (altOneShot && !altLatchActive) {
                        // Non-digit character with one-shot: clear Alt state and disable continuous input
                        modifierStateController.clearAltState(resetPressedState = false)
                        altLatchJustDisabled = true
                    }

                    // If Chinese punctuation was committed while Alt is held (not latch),
                    // set flag to prevent stale event meta state from keeping Alt active
                    if (chinesePunctuation != null && !altLatchActive && !shouldClearAllAltState) {
                        altLatchJustDisabled = true
                    }

                    // IMPORTANT: Clear altUsedForSymbolInput now that symbol input is complete
                    // This prevents the next Alt UP from incorrectly clearing one-shot mode
                    altUsedForSymbolInput = false

                    updateStatusBarText()
                    return true
                }
            }

            // Handle number keys 1-9 for candidate selection FIRST (before other handlers)
            // Skip this in Juying mode - use physical keys (Shift/Sym/Space/Ctrl/Alt) for selection instead
            if (pinyinInputController.hasCandidates() && !juyingModeEnabled) {
                // Check if it's a number key by keyCode
                if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                    val selected = pinyinInputController.handleNumberKey(keyCode)
                    if (selected != null) {
                        ic.commitText(selected, 1) // commitText replaces composing text automatically

                        // Set remaining buffer as new composing text
                        val remainingBuffer = pinyinInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }

                        // Clear Alt modifier (including latch state from double-tap)
                        modifierStateController.clearAltState(resetPressedState = true)
                        altUsedForPagination = false
                        altLastPressTime = 0L

                        // Set flag to ignore Alt until physical key is released
                        // This prevents Alt from staying active after selection when Alt key is still held
                        altLatchJustDisabled = true
                        altUsedForCandidateSelection = true

                        updateStatusBarText()
                        return true
                    }
                }
                // Also check if the unicode char is a digit (for Alt+Key layouts)
                if (event != null && event.unicodeChar != 0) {
                    val char = event.unicodeChar.toChar()
                    if (char.isDigit() && char in '1'..'9') {
                        val number = char.digitToInt()
                        val index = number - 1
                        val selected = pinyinInputController.selectCandidate(index)
                        if (selected != null) {
                            ic.commitText(selected, 1)

                            // Set remaining buffer as new composing text
                            val remainingBuffer = pinyinInputController.getBuffer()
                            if (remainingBuffer.isNotEmpty()) {
                                ic.setComposingText(remainingBuffer, 1)
                            }

                            // Clear Alt modifier (including latch state from double-tap)
                            modifierStateController.clearAltState(resetPressedState = true)
                            altUsedForPagination = false
                            altLastPressTime = 0L

                            // Set flag to ignore Alt until physical key is released
                            // This prevents Alt from staying active after selection when Alt key is still held
                            altLatchJustDisabled = true
                            altUsedForCandidateSelection = true

                            updateStatusBarText()
                            return true
                        }
                    }
                }
            }

            // Handle Shift+Del to clear entire Pinyin buffer and remove composing text
            if (keyCode == KeyEvent.KEYCODE_DEL && shiftPressed) {
                val buffer = pinyinInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    pinyinInputController.clearBuffer()
                    // Clear the composing text (the displayed pinyin like "women")
                    ic.setComposingText("", 1)
                    ic.finishComposingText()
                    updateStatusBarText()
                    return true
                }
            }

            // Handle backspace in Pinyin mode
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                val hadBuffer = pinyinInputController.getBuffer().isNotEmpty()
                val hadPredictions = pinyinInputController.isShowingNextWordPredictions()

                // Get cursor position within composing text for cursor-aware deletion
                val bufferLength = pinyinInputController.getBufferLength()
                val currentBuffer = pinyinInputController.getBuffer()
                var cursorPositionInBuffer = bufferLength // Default: cursor at end
                if (bufferLength > 0) {
                    val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                    if (extractedText != null) {
                        // Find the actual composing region by locating the buffer content in the text
                        val textStr = extractedText.text.toString()
                        val composingStart = textStr.indexOf(currentBuffer)
                        if (composingStart >= 0) {
                            val selectionInText = extractedText.startOffset + extractedText.selectionStart
                            if (selectionInText >= composingStart && selectionInText <= composingStart + bufferLength) {
                                cursorPositionInBuffer = selectionInText - composingStart
                            }
                        }
                    }
                }

                if (pinyinInputController.handleBackspaceAtPosition(cursorPositionInBuffer)) {
                    val buffer = pinyinInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        // Restore cursor position after deletion (one position back if we deleted before cursor)
                        val newCursorPos = if (cursorPositionInBuffer > 0 && cursorPositionInBuffer <= bufferLength) {
                            cursorPositionInBuffer - 1
                        } else {
                            buffer.length
                        }
                        // setComposingText with cursor position: 1 means cursor at end,
                        // but we need to use setComposingRegion for precise cursor control
                        ic.setComposingText(buffer, 1)
                        // Adjust cursor if not at end
                        if (newCursorPos < buffer.length) {
                            val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                            if (extractedText != null) {
                                // Find actual composing position
                                val textStr = extractedText.text.toString()
                                val composingStart = textStr.indexOf(buffer)
                                if (composingStart >= 0) {
                                    ic.setSelection(composingStart + newCursorPos, composingStart + newCursorPos)
                                }
                            }
                        }
                    } else if (hadBuffer) {
                        // Buffer was cleared, finish composing text
                        ic.finishComposingText()
                        // Delete the last character from committed text
                        ic.deleteSurroundingText(1, 0)
                    }
                    // Clear Alt and Ctrl states when DEL clears suggestions
                    modifierStateController.clearAltState(resetPressedState = true)
                    modifierStateController.clearCtrlState(resetPressedState = true)
                    // If we just cleared predictions (hadPredictions && !hadBuffer),
                    // don't call finishComposingText - just update status bar
                    updateStatusBarText()
                    return true
                } else {
                    // Buffer was empty and no predictions to clear
                    ic.finishComposingText()
                    // Check if there's selected text - if so, delete the selection
                    val selectedText = ic.getSelectedText(0)
                    if (selectedText != null && selectedText.isNotEmpty()) {
                        ic.commitText("", 1)  // Replace selection with empty string
                    } else {
                        ic.deleteSurroundingText(1, 0)  // Delete one character
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key - select first candidate
            if (keyCode == KeyEvent.KEYCODE_SPACE && pinyinInputController.hasCandidates()) {
                // Save buffer length BEFORE selection (for deletion)
                val bufferLengthBeforeSelect = pinyinInputController.getBuffer().length
                val selected = pinyinInputController.selectFirstCandidate()
                if (selected != null) {
                    var remainingBuffer = pinyinInputController.getBuffer()
                    // For memory/prediction candidates, buffer may not be consumed normally
                    // If buffer unchanged, it's a memory candidate - clear the buffer entirely
                    if (remainingBuffer.length == bufferLengthBeforeSelect) {
                        pinyinInputController.clearBuffer()
                        remainingBuffer = ""
                    }
                    // Clear composing text first, then commit - avoids race conditions in some apps
                    ic.setComposingText("", 1)
                    ic.commitText(selected, 1)
                    if (remainingBuffer.isNotEmpty()) {
                        ic.setComposingText(remainingBuffer, 1)
                    }

                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key when no candidates and empty buffer
            if (keyCode == KeyEvent.KEYCODE_SPACE && !pinyinInputController.hasCandidates() && pinyinInputController.getBuffer().isEmpty()) {
                // Try double-space-to-period first (supports Chinese punctuation mode)
                val useChinesePunctuation = pinyinInputController.isChinesePunctuationMode()
                if (textInputController.handleDoubleSpaceToPeriod(
                        keyCode = keyCode,
                        inputConnection = ic,
                        shouldDisableSmartFeatures = shouldDisableSmartFeatures,
                        onStatusBarUpdate = { updateStatusBarText() },
                        useChinesePunctuation = useChinesePunctuation
                    )) {
                    return true
                }
                // If not double-space, just insert a space
                ic.commitText(" ", 1)
                return true
            }

            // Handle letter and symbol keys
            if (event != null && event.unicodeChar != 0) {
                val char = event.unicodeChar.toChar()

                if (char.isLetter()) {
                    // If Shift is pressed, commit buffer and input capital letter directly
                    if (shiftPressed) {
                        // Commit any existing buffer first
                        val buffer = pinyinInputController.getBuffer()
                        if (buffer.isNotEmpty()) {
                            val committed = pinyinInputController.commitBufferAsIs()
                            if (committed != null) {
                                ic.commitText(committed, 1)
                            }
                        }
                        // Input the capital letter directly
                        ic.commitText(char.toString(), 1)
                        updateStatusBarText()
                        return true
                    }

                    // Otherwise, add to pinyin buffer (lowercase) at cursor position
                    // Get cursor position within composing text for cursor-aware insertion
                    val bufferLength = pinyinInputController.getBufferLength()
                    val currentBuffer = pinyinInputController.getBuffer()
                    var cursorPositionInBuffer = bufferLength // Default: cursor at end
                    if (bufferLength > 0) {
                        val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                        if (extractedText != null) {
                            // Find the actual composing region by locating the buffer content in the text
                            // Don't assume it's at the end - it could be in the middle when cursor is between characters
                            val textStr = extractedText.text.toString()
                            val composingStart = textStr.indexOf(currentBuffer)
                            if (composingStart >= 0) {
                                val selectionInText = extractedText.startOffset + extractedText.selectionStart
                                if (selectionInText >= composingStart && selectionInText <= composingStart + bufferLength) {
                                    cursorPositionInBuffer = selectionInText - composingStart
                                }
                            }
                        }
                    }

                    if (pinyinInputController.handleLetterKeyAtPosition(char, cursorPositionInBuffer)) {
                        val buffer = pinyinInputController.getBuffer()
                        ic.setComposingText(buffer, 1)
                        // Restore cursor position after insertion (one position forward)
                        val newCursorPos = cursorPositionInBuffer + 1
                        if (newCursorPos < buffer.length) {
                            val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                            if (extractedText != null) {
                                // Find actual composing position
                                val textStr = extractedText.text.toString()
                                val composingStart = textStr.indexOf(buffer)
                                if (composingStart >= 0) {
                                    ic.setSelection(composingStart + newCursorPos, composingStart + newCursorPos)
                                }
                            }
                        }
                        updateStatusBarText()
                        return true
                    }
                }

                // Handle apostrophe (') as syllable separator for disambiguation
                // e.g., "he'ni" means 和你 (he + ni), not 很 (hen) + something
                if (char == '\'') {
                    if (pinyinInputController.handleSeparatorKey()) {
                        val buffer = pinyinInputController.getBuffer()
                        ic.setComposingText(buffer, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Handle punctuation - use Chinese punctuation in Pinyin mode (only when Chinese punctuation mode is enabled)
            if (event != null && event.unicodeChar != 0 && pinyinInputController.isChinesePunctuationMode()) {
                val char = event.unicodeChar.toChar()
                // Check if this is a punctuation that should be converted to Chinese
                val chinesePunctuation: String? = when (char) {
                    ',' -> "，"  // 逗号
                    '.' -> "。"  // 句号
                    '!' -> "！"  // 感叹号
                    '?' -> "？"  // 问号
                    ':' -> "："  // 冒号
                    ';' -> "；"  // 分号
                    '(' -> "（"  // 左括号
                    ')' -> "）"  // 右括号
                    '[' -> "【"  // 左方括号
                    ']' -> "】"  // 右方括号
                    '<' -> "《"  // 左书名号
                    '>' -> "》"  // 右书名号
                    '~' -> "～"  // 波浪号
                    '\\' -> "、" // 顿号
                    '^' -> "……" // 省略号
                    '_' -> "——" // 破折号
                    '"' -> {
                        // Alternate between opening and closing Chinese double quotes
                        val result = if (pinyinInputController.isNextDoubleQuoteOpening()) {
                            "\u201C" // " opening double quote
                        } else {
                            "\u201D" // " closing double quote
                        }
                        pinyinInputController.toggleDoubleQuoteState()
                        result
                    }
                    '\'' -> {
                        // Alternate between opening and closing Chinese single quotes
                        val result = if (pinyinInputController.isNextSingleQuoteOpening()) {
                            "\u2018" // ' opening single quote
                        } else {
                            "\u2019" // ' closing single quote
                        }
                        pinyinInputController.toggleSingleQuoteState()
                        result
                    }
                    else -> null
                }

                if (chinesePunctuation != null) {
                    // Commit any existing pinyin buffer first
                    val buffer = pinyinInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = pinyinInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                        }
                    }
                    ic.commitText(chinesePunctuation, 1)
                    // Clear next-word predictions since punctuation ends the phrase context
                    pinyinInputController.onPunctuationInput()
                    // Clear Alt one-shot after typing Chinese punctuation
                    if (altOneShot) {
                        altOneShot = false
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // ESC key or Ctrl+Q to exit Pinyin mode
            if (keyCode == KeyEvent.KEYCODE_ESCAPE ||
                (keyCode == KeyEvent.KEYCODE_Q && ctrlPressed)) {
                pinyinInputController.setPinyinMode(false)
                ic.finishComposingText()
                updateStatusBarText()
                return true
            }
        }

        // Handle T9 Pinyin input mode (九宫格)
        if (t9PinyinInputController.isT9Mode() && ic != null) {
            // Handle SYM mode - when SYM is active, allow symbol input
            if (symLayoutController.isSymActive()) {
                val symResult = symLayoutController.handleKeyWhenActive(
                    keyCode,
                    event,
                    ic,
                    ctrlLatchActive = ctrlLatchActive,
                    altLatchActive = altLatchActive,
                    updateStatusBar = { updateStatusBarText() },
                    onSymbolInserted = { }
                )
                when (symResult) {
                    SymLayoutController.SymKeyResult.CONSUME -> return true
                    SymLayoutController.SymKeyResult.CALL_SUPER -> return super.onKeyDown(keyCode, event)
                    SymLayoutController.SymKeyResult.NOT_HANDLED -> { /* Continue to T9 handling */ }
                }
            }

            // Handle number keys 1-9 for candidate selection when Alt/Shift is not pressed
            // and when we have candidates
            if (t9PinyinInputController.hasCandidates() && !altPressed && !altLatchActive && !altOneShot) {
                // Check for number key candidate selection
                if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                    val index = keyCode - KeyEvent.KEYCODE_1
                    val selected = t9PinyinInputController.selectCandidate(index)
                    if (selected != null) {
                        ic.finishComposingText()
                        ic.commitText(selected, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Handle backspace in T9 mode
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                if (t9PinyinInputController.handleBackspace()) {
                    val displayBuffer = t9PinyinInputController.getDisplayBuffer()
                    if (displayBuffer.isNotEmpty()) {
                        ic.setComposingText(displayBuffer, 1)
                    } else {
                        ic.finishComposingText()
                    }
                    updateStatusBarText()
                    return true
                } else {
                    // Buffer was empty, delete character normally
                    ic.finishComposingText()
                    ic.deleteSurroundingText(1, 0)
                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key - select first candidate
            if (keyCode == KeyEvent.KEYCODE_SPACE && t9PinyinInputController.hasCandidates()) {
                val selected = t9PinyinInputController.selectFirstCandidate()
                if (selected != null) {
                    ic.finishComposingText()
                    ic.commitText(selected, 1)
                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key when no candidates - input space
            if (keyCode == KeyEvent.KEYCODE_SPACE && !t9PinyinInputController.hasCandidates()) {
                ic.commitText(" ", 1)
                return true
            }

            // Handle T9 input keys (2-9, 1 for separator, or W/E/R/S/D/F/X/C/V)
            if (event != null) {
                val unicodeChar = event.unicodeChar
                val char = if (unicodeChar != 0) unicodeChar.toChar() else null

                // Check if this is a T9 input key
                val isT9Key = when {
                    keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> true
                    char != null && char.lowercaseChar() in listOf('w', 'e', 'r', 's', 'd', 'f', 'x', 'c', 'v') -> true
                    else -> false
                }

                if (isT9Key && t9PinyinInputController.handleKeyPress(keyCode, char)) {
                    val displayBuffer = t9PinyinInputController.getDisplayBuffer()
                    if (displayBuffer.isNotEmpty()) {
                        ic.setComposingText(displayBuffer, 1)
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // Handle Enter key - commit buffer as-is or insert newline
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                val buffer = t9PinyinInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    ic.finishComposingText()
                    t9PinyinInputController.clearBuffer()
                }
                // Let Enter pass through for normal handling
            }

            // ESC key or Ctrl+Q to exit T9 mode
            if (keyCode == KeyEvent.KEYCODE_ESCAPE ||
                (keyCode == KeyEvent.KEYCODE_Q && ctrlPressed)) {
                t9PinyinInputController.setT9Mode(false)
                ic.finishComposingText()
                updateStatusBarText()
                return true
            }
        }

        // Handle Shuangpin input mode
        if (shuangpinInputController.isShuangpinMode() && ic != null) {
            // Handle SYM mode - when SYM is active, allow symbol input just like in English mode
            if (symLayoutController.isSymActive()) {
                val symResult = symLayoutController.handleKeyWhenActive(
                    keyCode,
                    event,
                    ic,
                    ctrlLatchActive = ctrlLatchActive,
                    altLatchActive = altLatchActive,
                    updateStatusBar = { updateStatusBarText() },
                    onSymbolInserted = {
                        // Clear next word predictions after inserting symbol
                        shuangpinInputController.clearNextWordPredictions()
                    }
                )
                when (symResult) {
                    SymLayoutController.SymKeyResult.CONSUME -> return true
                    SymLayoutController.SymKeyResult.CALL_SUPER -> return super.onKeyDown(keyCode, event)
                    SymLayoutController.SymKeyResult.NOT_HANDLED -> { /* Continue to Shuangpin handling */ }
                }
            }

            // Handle Alt modifier - when Alt is active (latched, one-shot, or pressed), input alternate characters
            // When a non-Alt key is pressed while Alt is held, always handle it as symbol input
            // (Alt key alone for candidate selection is handled earlier in Juying key handling)
            val currentTimeShuangpin = System.currentTimeMillis()
            val longPressThresholdShuangpin = SettingsManager.getLongPressThreshold(this)
            val altHoldDurationShuangpin = if (altLastPressTime > 0) currentTimeShuangpin - altLastPressTime else 0L
            val isAltLongPressShuangpin = altHoldDurationShuangpin >= longPressThresholdShuangpin

            // Use Alt LED status as the source of truth: if LED is off, Alt is not active
            val altLedIsOffShuangpin = !altLatchActive && !altOneShot && !altPressed

            // Check multiple ways Alt might be active
            val altFromEventShuangpin = !altLatchJustDisabled && !altUsedForCandidateSelection && !altLedIsOffShuangpin && event != null && ((event.metaState and KeyEvent.META_ALT_ON) != 0)
            val altFromJuyingTrackingShuangpin = !altLedIsOffShuangpin && altLastPressTime > 0
            // Skip Alt handling if latch was just disabled or Alt was used for candidate selection or LED is off
            val shouldSkipAltHandlingShuangpin = (altLatchJustDisabled && !altLatchActive && !altOneShot) || altUsedForCandidateSelection || altLedIsOffShuangpin
            if (event != null && !shouldSkipAltHandlingShuangpin && (altLatchActive || altOneShot || altPressed || altFromEventShuangpin || altFromJuyingTrackingShuangpin)) {
                // Use Pastiera's altSymManager mapping (device-specific) instead of system's getUnicodeChar
                val altMappedCharShuangpin = altSymManager.getAltMappings()[keyCode]
                val altChar = altMappedCharShuangpin?.firstOrNull()?.code ?: event.getUnicodeChar(KeyEvent.META_ALT_ON)
                // Get the base character without any modifiers (when Alt is held, event.unicodeChar equals altChar)
                val baseCharShuangpin = event.getUnicodeChar(0)

                // In non-Juying mode, if Alt produces a digit (1-9) and there are candidates,
                // handle candidate selection right here using the Alt-mapped digit
                val isDigitForSelectionShuangpin = !juyingModeEnabled &&
                                                   shuangpinInputController.hasCandidates() &&
                                                   altChar.toChar().isDigit() &&
                                                   altChar.toChar() in '1'..'9'

                // Handle candidate selection when Alt+key produces a digit
                if (isDigitForSelectionShuangpin) {
                    val number = altChar.toChar().digitToInt()
                    val index = number - 1
                    val selected = shuangpinInputController.selectCandidate(index)
                    if (selected != null) {
                        ic.commitText(selected, 1)

                        // Set remaining buffer as new composing text
                        val remainingBuffer = shuangpinInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }

                        // Clear Alt modifier (including latch state from double-tap)
                        modifierStateController.clearAltState(resetPressedState = true)
                        altUsedForPagination = false
                        altLastPressTime = 0L

                        // Set flag to ignore Alt until physical key is released
                        altLatchJustDisabled = true
                        altUsedForCandidateSelection = true

                        updateStatusBarText()
                        return true
                    }
                }

                // Only proceed if we get a valid alternate character that's different from the base one
                // AND it's not a digit for candidate selection
                if (altChar != 0 && altChar != baseCharShuangpin && !isDigitForSelectionShuangpin) {
                    // In Juying mode, always clear buffer/predictions and set flags when Alt symbol is about to be committed
                    if (juyingModeEnabled) {
                        shuangpinInputController.clearBuffer()
                        shuangpinInputController.clearNextWordPredictions()
                        ic.finishComposingText()
                        altUsedForSymbolInput = true  // Mark that Alt was used for symbol input
                        // Clear saved Alt state (long hold for symbol cancels single-press insertion)
                        savedAltSuggestion = null
                        savedAltCandidatesForNextPage = emptyList()
                        savedAltCurrentPage = 0
                        savedAltChineseMode = null
                        // Cancel any pending Alt selection
                        pendingAltSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingAltSelectionRunnable = null
                    } else {
                        // Non-Juying mode: Clear buffer instead of committing as English
                        // When user presses Alt+key for symbol, they want to cancel input
                        val buffer = shuangpinInputController.getBuffer()
                        if (buffer.isNotEmpty()) {
                            shuangpinInputController.clearBuffer()
                            shuangpinInputController.clearNextWordPredictions()
                            ic.finishComposingText()  // Remove composing text without committing
                        }
                    }

                    // Convert punctuation to Chinese if applicable
                    // Use altMappedCharShuangpin directly if available, otherwise use altChar
                    val char = altMappedCharShuangpin?.firstOrNull() ?: altChar.toChar()
                    val chinesePunctuation: String? = if (shuangpinInputController.isChinesePunctuationMode()) {
                        when (char) {
                            ',' -> "，"
                            '.' -> "。"
                            '!' -> "！"
                            '?' -> "？"
                            ':' -> "："
                            ';' -> "；"
                            '(' -> "（"
                            ')' -> "）"
                            '[' -> "【"
                            ']' -> "】"
                            '<' -> "《"
                            '>' -> "》"
                            '~' -> "～"
                            '\\' -> "、"
                            '^' -> "……"
                            '_' -> "——"
                            '"' -> {
                                val result = if (shuangpinInputController.isNextDoubleQuoteOpening()) "\u201C" else "\u201D"
                                shuangpinInputController.toggleDoubleQuoteState()
                                result
                            }
                            '\'' -> {
                                val result = if (shuangpinInputController.isNextSingleQuoteOpening()) "\u2018" else "\u2019"
                                shuangpinInputController.toggleSingleQuoteState()
                                result
                            }
                            else -> null
                        }
                    } else null

                    val textToCommit = chinesePunctuation ?: char.toString()
                    ic.commitText(textToCommit, 1)

                    // In Juying mode, ALWAYS clear predictions when Alt symbol is committed
                    // This should happen regardless of how Alt was activated (long-press, one-shot, or latch)
                    if (juyingModeEnabled) {
                        shuangpinInputController.clearBuffer()
                        shuangpinInputController.clearNextWordPredictions()
                    }

                    // Handle Alt state clearing based on mode
                    // In Juying mode with long-press, OR in non-Juying mode with suggestions visible,
                    // clear all Alt state (including latch) - this prevents Alt getting stuck
                    // EXCEPTION: For digits and period, don't clear Alt state - allow continuous number input (e.g., "10.5")
                    val isDigitOrPeriodShuangpin = char.isDigit() || char == '.'
                    val shouldClearAllAltStateShuangpin = (isAltLongPressShuangpin && juyingModeEnabled && !isDigitOrPeriodShuangpin) ||
                        (!juyingModeEnabled && hasCandidatesToPaginate && altLatchActive)
                    if (shouldClearAllAltStateShuangpin) {
                        modifierStateController.clearAltState(resetPressedState = true)  // Clear all Alt state
                        altLastPressTime = 0L  // Reset timing state
                        altLatchJustDisabled = true
                    } else if (isDigitOrPeriodShuangpin) {
                        // For continuous Alt input: ALWAYS update altLastPressTime for digits and period
                        // This ensures altFromJuyingTracking remains true for subsequent digit/period keys
                        altLastPressTime = currentTimeShuangpin
                        if (altOneShot && !altLatchActive) {
                            modifierStateController.clearAltState(resetPressedState = false)
                        }
                    } else if (altOneShot && !altLatchActive) {
                        // Non-digit character with one-shot: clear Alt state and disable continuous input
                        modifierStateController.clearAltState(resetPressedState = false)
                        altLatchJustDisabled = true
                    }

                    // If Chinese punctuation was committed while Alt is held (not latch),
                    // set flag to prevent stale event meta state from keeping Alt active
                    if (chinesePunctuation != null && !altLatchActive && !shouldClearAllAltStateShuangpin) {
                        altLatchJustDisabled = true
                    }

                    // IMPORTANT: Clear altUsedForSymbolInput now that symbol input is complete
                    // This prevents the next Alt UP from incorrectly clearing one-shot mode
                    altUsedForSymbolInput = false

                    updateStatusBarText()
                    return true
                }
            }

            // Handle number keys 1-9 for candidate selection
            // Skip this in Juying mode - use physical keys (Shift/Sym/Space/Ctrl/Alt) for selection instead
            if (shuangpinInputController.hasCandidates() && !juyingModeEnabled) {
                if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                    val selected = shuangpinInputController.handleNumberKey(keyCode)
                    if (selected != null) {
                        ic.commitText(selected, 1)

                        val remainingBuffer = shuangpinInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }

                        modifierStateController.clearAltState(resetPressedState = true)
                        altUsedForPagination = false
                        altLastPressTime = 0L
                        altLatchJustDisabled = true
                        altUsedForCandidateSelection = true
                        updateStatusBarText()
                        return true
                    }
                }
                // Also check unicode char for number selection
                if (event != null && event.unicodeChar != 0) {
                    val char = event.unicodeChar.toChar()
                    if (char.isDigit() && char in '1'..'9') {
                        val number = char.digitToInt()
                        val index = number - 1
                        val selected = shuangpinInputController.selectCandidate(index)
                        if (selected != null) {
                            ic.commitText(selected, 1)

                            val remainingBuffer = shuangpinInputController.getBuffer()
                            if (remainingBuffer.isNotEmpty()) {
                                ic.setComposingText(remainingBuffer, 1)
                            }

                            modifierStateController.clearAltState(resetPressedState = true)
                            altUsedForPagination = false
                            altLastPressTime = 0L
                            altLatchJustDisabled = true
                            altUsedForCandidateSelection = true
                            updateStatusBarText()
                            return true
                        }
                    }
                }
            }

            // Handle Shift+Del to clear entire Shuangpin buffer
            if (keyCode == KeyEvent.KEYCODE_DEL && shiftPressed) {
                val buffer = shuangpinInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    shuangpinInputController.clearBuffer()
                    ic.setComposingText("", 1)
                    ic.finishComposingText()
                    updateStatusBarText()
                    return true
                }
            }

            // Handle backspace in Shuangpin mode
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                val hadBuffer = shuangpinInputController.getBuffer().isNotEmpty()
                val hadPredictions = shuangpinInputController.isShowingNextWordPredictions()

                // Get cursor position within composing text for cursor-aware deletion
                val bufferLength = shuangpinInputController.getBufferLength()
                val currentBuffer = shuangpinInputController.getBuffer()
                var cursorPositionInBuffer = bufferLength // Default: cursor at end
                if (bufferLength > 0) {
                    val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                    if (extractedText != null) {
                        // Find the actual composing region by locating the buffer content in the text
                        val textStr = extractedText.text.toString()
                        val composingStart = textStr.indexOf(currentBuffer)
                        if (composingStart >= 0) {
                            val selectionInText = extractedText.startOffset + extractedText.selectionStart
                            if (selectionInText >= composingStart && selectionInText <= composingStart + bufferLength) {
                                cursorPositionInBuffer = selectionInText - composingStart
                            }
                        }
                    }
                }

                if (shuangpinInputController.handleBackspaceAtPosition(cursorPositionInBuffer)) {
                    val buffer = shuangpinInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val newCursorPos = if (cursorPositionInBuffer > 0 && cursorPositionInBuffer <= bufferLength) {
                            cursorPositionInBuffer - 1
                        } else {
                            buffer.length
                        }
                        ic.setComposingText(buffer, 1)
                        if (newCursorPos < buffer.length) {
                            val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                            if (extractedText != null) {
                                // Find actual composing position
                                val textStr = extractedText.text.toString()
                                val composingStart = textStr.indexOf(buffer)
                                if (composingStart >= 0) {
                                    ic.setSelection(composingStart + newCursorPos, composingStart + newCursorPos)
                                }
                            }
                        }
                    } else if (hadBuffer) {
                        // Buffer was cleared, finish composing text
                        ic.finishComposingText()
                        // Delete the last character from committed text
                        ic.deleteSurroundingText(1, 0)
                    }
                    // Clear Alt and Ctrl states when DEL clears suggestions
                    modifierStateController.clearAltState(resetPressedState = true)
                    modifierStateController.clearCtrlState(resetPressedState = true)
                    // If we just cleared predictions, don't call finishComposingText
                    updateStatusBarText()
                    return true
                } else {
                    // Buffer was empty and no predictions to clear
                    ic.finishComposingText()
                    // Check if there's selected text - if so, delete the selection
                    val selectedText = ic.getSelectedText(0)
                    if (selectedText != null && selectedText.isNotEmpty()) {
                        ic.commitText("", 1)  // Replace selection with empty string
                    } else {
                        ic.deleteSurroundingText(1, 0)  // Delete one character
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key - select first candidate
            if (keyCode == KeyEvent.KEYCODE_SPACE && shuangpinInputController.hasCandidates()) {
                // Save buffer length BEFORE selection (for deletion)
                val bufferLengthBeforeSelect = shuangpinInputController.getBuffer().length
                val selected = shuangpinInputController.selectFirstCandidate()
                if (selected != null) {
                    var remainingBuffer = shuangpinInputController.getBuffer()
                    // For memory/prediction candidates, buffer may not be consumed normally
                    // If buffer unchanged, it's a memory candidate - clear the buffer entirely
                    if (remainingBuffer.length == bufferLengthBeforeSelect) {
                        shuangpinInputController.clearBuffer()
                        remainingBuffer = ""
                    }
                    // Clear composing text first, then commit - avoids race conditions in some apps
                    ic.setComposingText("", 1)
                    ic.commitText(selected, 1)
                    if (remainingBuffer.isNotEmpty()) {
                        ic.setComposingText(remainingBuffer, 1)
                    }

                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key when no candidates and empty buffer
            if (keyCode == KeyEvent.KEYCODE_SPACE && !shuangpinInputController.hasCandidates() && shuangpinInputController.getBuffer().isEmpty()) {
                // Try double-space-to-period first (supports Chinese punctuation mode)
                val useChinesePunctuation = shuangpinInputController.isChinesePunctuationMode()
                if (textInputController.handleDoubleSpaceToPeriod(
                        keyCode = keyCode,
                        inputConnection = ic,
                        shouldDisableSmartFeatures = shouldDisableSmartFeatures,
                        onStatusBarUpdate = { updateStatusBarText() },
                        useChinesePunctuation = useChinesePunctuation
                    )) {
                    return true
                }
                // If not double-space, just insert a space
                ic.commitText(" ", 1)
                return true
            }

            // Handle letter keys for Shuangpin input
            if (event != null && event.unicodeChar != 0) {
                val char = event.unicodeChar.toChar()

                if (char.isLetter()) {
                    // If Shift is pressed, commit buffer and input capital letter directly
                    if (shiftPressed) {
                        val buffer = shuangpinInputController.getBuffer()
                        if (buffer.isNotEmpty()) {
                            val committed = shuangpinInputController.commitBufferAsIs()
                            if (committed != null) {
                                ic.commitText(committed, 1)
                            }
                        }
                        ic.commitText(char.toString(), 1)
                        updateStatusBarText()
                        return true
                    }

                    // Add to Shuangpin buffer (lowercase) at cursor position
                    // Get cursor position within composing text for cursor-aware insertion
                    val bufferLength = shuangpinInputController.getBufferLength()
                    val currentBuffer = shuangpinInputController.getBuffer()
                    var cursorPositionInBuffer = bufferLength // Default: cursor at end
                    if (bufferLength > 0) {
                        val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                        if (extractedText != null) {
                            // Find the actual composing region by locating the buffer content in the text
                            val textStr = extractedText.text.toString()
                            val composingStart = textStr.indexOf(currentBuffer)
                            if (composingStart >= 0) {
                                val selectionInText = extractedText.startOffset + extractedText.selectionStart
                                if (selectionInText >= composingStart && selectionInText <= composingStart + bufferLength) {
                                    cursorPositionInBuffer = selectionInText - composingStart
                                }
                            }
                        }
                    }

                    if (shuangpinInputController.handleLetterKeyAtPosition(char, cursorPositionInBuffer)) {
                        val buffer = shuangpinInputController.getBuffer()
                        ic.setComposingText(buffer, 1)
                        // Restore cursor position after insertion (one position forward)
                        val newCursorPos = cursorPositionInBuffer + 1
                        if (newCursorPos < buffer.length) {
                            val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                            if (extractedText != null) {
                                // Find actual composing position
                                val textStr = extractedText.text.toString()
                                val composingStart = textStr.indexOf(buffer)
                                if (composingStart >= 0) {
                                    ic.setSelection(composingStart + newCursorPos, composingStart + newCursorPos)
                                }
                            }
                        }
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Handle punctuation - use Chinese punctuation in Shuangpin mode
            if (event != null && event.unicodeChar != 0 && shuangpinInputController.isChinesePunctuationMode()) {
                val char = event.unicodeChar.toChar()
                val chinesePunctuation: String? = when (char) {
                    ',' -> "，"
                    '.' -> "。"
                    '!' -> "！"
                    '?' -> "？"
                    ':' -> "："
                    ';' -> "；"
                    '(' -> "（"
                    ')' -> "）"
                    '[' -> "【"
                    ']' -> "】"
                    '<' -> "《"
                    '>' -> "》"
                    '~' -> "～"
                    '\\' -> "、"
                    '^' -> "……"
                    '_' -> "——"
                    '"' -> {
                        val result = if (shuangpinInputController.isNextDoubleQuoteOpening()) {
                            "\u201C"
                        } else {
                            "\u201D"
                        }
                        shuangpinInputController.toggleDoubleQuoteState()
                        result
                    }
                    '\'' -> {
                        val result = if (shuangpinInputController.isNextSingleQuoteOpening()) {
                            "\u2018"
                        } else {
                            "\u2019"
                        }
                        shuangpinInputController.toggleSingleQuoteState()
                        result
                    }
                    else -> null
                }

                if (chinesePunctuation != null) {
                    val buffer = shuangpinInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = shuangpinInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                        }
                    }
                    ic.commitText(chinesePunctuation, 1)
                    shuangpinInputController.onPunctuationInput()
                    if (altOneShot) {
                        altOneShot = false
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // ESC key or Ctrl+Q to exit Shuangpin mode
            if (keyCode == KeyEvent.KEYCODE_ESCAPE ||
                (keyCode == KeyEvent.KEYCODE_Q && ctrlPressed)) {
                shuangpinInputController.setShuangpinMode(false)
                ic.finishComposingText()
                updateStatusBarText()
                return true
            }
        }

        // Handle Ziranma input mode
        if (ziranmaInputController.isZiranmaMode() && ic != null) {
            // Handle SYM mode - when SYM is active, allow symbol input just like in English mode
            if (symLayoutController.isSymActive()) {
                val symResult = symLayoutController.handleKeyWhenActive(
                    keyCode,
                    event,
                    ic,
                    ctrlLatchActive = ctrlLatchActive,
                    altLatchActive = altLatchActive,
                    updateStatusBar = { updateStatusBarText() },
                    onSymbolInserted = {
                        // Clear next word predictions after inserting symbol
                        ziranmaInputController.clearNextWordPredictions()
                    }
                )
                when (symResult) {
                    SymLayoutController.SymKeyResult.CONSUME -> return true
                    SymLayoutController.SymKeyResult.CALL_SUPER -> return super.onKeyDown(keyCode, event)
                    SymLayoutController.SymKeyResult.NOT_HANDLED -> { /* Continue to Ziranma handling */ }
                }
            }

            // Handle number keys 1-9 for candidate selection (not in Juying mode)
            if (!juyingModeEnabled && keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) {
                val index = keyCode - KeyEvent.KEYCODE_1
                val bufferLengthBeforeSelect = ziranmaInputController.getBuffer().length
                val selected = ziranmaInputController.selectCandidate(index)
                if (selected != null) {
                    var remainingBuffer = ziranmaInputController.getBuffer()
                    if (remainingBuffer.length == bufferLengthBeforeSelect) {
                        ziranmaInputController.clearBuffer()
                        remainingBuffer = ""
                    }
                    ic.finishComposingText()
                    if (bufferLengthBeforeSelect > 0) {
                        ic.deleteSurroundingText(bufferLengthBeforeSelect, 0)
                    }
                    ic.commitText(selected, 1)
                    if (remainingBuffer.isNotEmpty()) {
                        ic.setComposingText(remainingBuffer, 1)
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // Handle Shift+Del to clear entire Ziranma buffer
            if (keyCode == KeyEvent.KEYCODE_DEL && shiftPressed) {
                val buffer = ziranmaInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    ziranmaInputController.clearBuffer()
                    ic.setComposingText("", 1)
                    ic.finishComposingText()
                    updateStatusBarText()
                    return true
                }
            }

            // Handle backspace in Ziranma mode
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                val hadBuffer = ziranmaInputController.getBuffer().isNotEmpty()
                if (ziranmaInputController.handleBackspace()) {
                    val buffer = ziranmaInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        ic.setComposingText(buffer, 1)
                    } else if (hadBuffer) {
                        ic.finishComposingText()
                        ic.deleteSurroundingText(1, 0)
                    }
                    modifierStateController.clearAltState(resetPressedState = true)
                    modifierStateController.clearCtrlState(resetPressedState = true)
                    updateStatusBarText()
                    return true
                } else {
                    ic.finishComposingText()
                    val selectedText = ic.getSelectedText(0)
                    if (selectedText != null && selectedText.isNotEmpty()) {
                        ic.commitText("", 1)
                    } else {
                        ic.deleteSurroundingText(1, 0)
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key - select first candidate
            if (keyCode == KeyEvent.KEYCODE_SPACE && ziranmaInputController.hasCandidates()) {
                val bufferLengthBeforeSelect = ziranmaInputController.getBuffer().length
                val selected = ziranmaInputController.selectFirstCandidate()
                if (selected != null) {
                    var remainingBuffer = ziranmaInputController.getBuffer()
                    if (remainingBuffer.length == bufferLengthBeforeSelect) {
                        ziranmaInputController.clearBuffer()
                        remainingBuffer = ""
                    }
                    ic.finishComposingText()
                    if (bufferLengthBeforeSelect > 0) {
                        ic.deleteSurroundingText(bufferLengthBeforeSelect, 0)
                    }
                    ic.commitText(selected, 1)
                    if (remainingBuffer.isNotEmpty()) {
                        ic.setComposingText(remainingBuffer, 1)
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key when no candidates and empty buffer
            if (keyCode == KeyEvent.KEYCODE_SPACE && !ziranmaInputController.hasCandidates() && ziranmaInputController.getBuffer().isEmpty()) {
                // Try double-space-to-period first (supports Chinese punctuation mode)
                val useChinesePunctuation = ziranmaInputController.isChinesePunctuationMode()
                if (textInputController.handleDoubleSpaceToPeriod(
                        keyCode = keyCode,
                        inputConnection = ic,
                        shouldDisableSmartFeatures = shouldDisableSmartFeatures,
                        onStatusBarUpdate = { updateStatusBarText() },
                        useChinesePunctuation = useChinesePunctuation
                    )) {
                    return true
                }
                // If not double-space, just insert a space
                ic.commitText(" ", 1)
                return true
            }

            // Handle letter keys for Ziranma input
            if (event != null && event.unicodeChar != 0) {
                val char = event.unicodeChar.toChar()

                if (char.isLetter()) {
                    // If Shift is pressed, commit buffer and input capital letter directly
                    if (shiftPressed) {
                        val buffer = ziranmaInputController.getBuffer()
                        if (buffer.isNotEmpty()) {
                            val committed = ziranmaInputController.commitBufferAsIs()
                            if (committed != null) {
                                ic.commitText(committed, 1)
                            }
                        }
                        ic.commitText(char.toString(), 1)
                        updateStatusBarText()
                        return true
                    }

                    // Add to Ziranma buffer (lowercase)
                    if (ziranmaInputController.handleLetterKey(char)) {
                        val buffer = ziranmaInputController.getBuffer()
                        ic.setComposingText(buffer, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Handle punctuation - use Chinese punctuation in Ziranma mode
            if (event != null && event.unicodeChar != 0 && ziranmaInputController.isChinesePunctuationMode()) {
                val char = event.unicodeChar.toChar()
                val chinesePunctuation: String? = when (char) {
                    ',' -> "，"
                    '.' -> "。"
                    '!' -> "！"
                    '?' -> "？"
                    ':' -> "："
                    ';' -> "；"
                    '(' -> "（"
                    ')' -> "）"
                    '[' -> "【"
                    ']' -> "】"
                    '<' -> "《"
                    '>' -> "》"
                    '~' -> "～"
                    '\\' -> "、"
                    '^' -> "……"
                    '_' -> "——"
                    '"' -> {
                        val result = if (ziranmaInputController.isNextDoubleQuoteOpening()) {
                            "\u201C"
                        } else {
                            "\u201D"
                        }
                        ziranmaInputController.toggleDoubleQuoteState()
                        result
                    }
                    '\'' -> {
                        val result = if (ziranmaInputController.isNextSingleQuoteOpening()) {
                            "\u2018"
                        } else {
                            "\u2019"
                        }
                        ziranmaInputController.toggleSingleQuoteState()
                        result
                    }
                    else -> null
                }

                if (chinesePunctuation != null) {
                    val buffer = ziranmaInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = ziranmaInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                        }
                    }
                    ic.commitText(chinesePunctuation, 1)
                    ziranmaInputController.onPunctuationInput()
                    if (altOneShot) {
                        altOneShot = false
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // ESC key or Ctrl+Q to exit Ziranma mode
            if (keyCode == KeyEvent.KEYCODE_ESCAPE ||
                (keyCode == KeyEvent.KEYCODE_Q && ctrlPressed)) {
                ziranmaInputController.setZiranmaMode(false)
                ic.finishComposingText()
                updateStatusBarText()
                return true
            }
        }

        // Handle Wubi input mode
        if (wubiInputController.isWubiMode() && ic != null) {
            // Handle SYM mode - when SYM is active, allow symbol input just like in English mode
            if (symLayoutController.isSymActive()) {
                val symResult = symLayoutController.handleKeyWhenActive(
                    keyCode,
                    event,
                    ic,
                    ctrlLatchActive = ctrlLatchActive,
                    altLatchActive = altLatchActive,
                    updateStatusBar = { updateStatusBarText() },
                    onSymbolInserted = {
                        // Clear next word predictions after inserting symbol
                        wubiInputController.clearNextWordPredictions()
                    }
                )
                when (symResult) {
                    SymLayoutController.SymKeyResult.CONSUME -> return true
                    SymLayoutController.SymKeyResult.CALL_SUPER -> return super.onKeyDown(keyCode, event)
                    SymLayoutController.SymKeyResult.NOT_HANDLED -> { /* Continue to Wubi handling */ }
                }
            }

            // Handle Alt modifier - when Alt is active (latched, one-shot, or pressed), input alternate characters
            // When a non-Alt key is pressed while Alt is held, always handle it as symbol input
            // (Alt key alone for candidate selection is handled earlier in Juying key handling)
            val currentTimeWubi = System.currentTimeMillis()
            val longPressThresholdWubi = SettingsManager.getLongPressThreshold(this)
            val altHoldDurationWubi = if (altLastPressTime > 0) currentTimeWubi - altLastPressTime else 0L
            val isAltLongPressWubi = altHoldDurationWubi >= longPressThresholdWubi

            // Use Alt LED status as the source of truth: if LED is off, Alt is not active
            val altLedIsOffWubi = !altLatchActive && !altOneShot && !altPressed

            // Check multiple ways Alt might be active
            val altFromEventWubi = !altLatchJustDisabled && !altUsedForCandidateSelection && !altLedIsOffWubi && event != null && ((event.metaState and KeyEvent.META_ALT_ON) != 0)
            val altFromJuyingTrackingWubi = !altLedIsOffWubi && altLastPressTime > 0
            // Skip Alt handling if latch was just disabled or Alt was used for candidate selection or LED is off
            val shouldSkipAltHandlingWubi = (altLatchJustDisabled && !altLatchActive && !altOneShot) || altUsedForCandidateSelection || altLedIsOffWubi
            if (event != null && !shouldSkipAltHandlingWubi && (altLatchActive || altOneShot || altPressed || altFromEventWubi || altFromJuyingTrackingWubi)) {
                // Use Pastiera's altSymManager mapping (device-specific) instead of system's getUnicodeChar
                val altMappedCharWubi = altSymManager.getAltMappings()[keyCode]
                val altChar = altMappedCharWubi?.firstOrNull()?.code ?: event.getUnicodeChar(KeyEvent.META_ALT_ON)
                // Get the base character without any modifiers (when Alt is held, event.unicodeChar equals altChar)
                val baseCharWubi = event.getUnicodeChar(0)

                // In non-Juying mode, if Alt produces a digit (1-9) and there are candidates,
                // handle candidate selection right here using the Alt-mapped digit
                val isDigitForSelectionWubi = !juyingModeEnabled &&
                                              wubiInputController.hasCandidates() &&
                                              altChar.toChar().isDigit() &&
                                              altChar.toChar() in '1'..'9'

                // Handle candidate selection when Alt+key produces a digit
                if (isDigitForSelectionWubi) {
                    val number = altChar.toChar().digitToInt()
                    val index = number - 1
                    val selected = wubiInputController.selectCandidate(index)
                    if (selected != null) {
                        ic.commitText(selected, 1)

                        // Set remaining buffer as new composing text
                        val remainingBuffer = wubiInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }

                        // Clear Alt modifier (including latch state from double-tap)
                        modifierStateController.clearAltState(resetPressedState = true)
                        altUsedForPagination = false
                        altLastPressTime = 0L

                        // Set flag to ignore Alt until physical key is released
                        altLatchJustDisabled = true
                        altUsedForCandidateSelection = true

                        updateStatusBarText()
                        return true
                    }
                }

                // Only proceed if we get a valid alternate character that's different from the base one
                // AND it's not a digit for candidate selection
                if (altChar != 0 && altChar != baseCharWubi && !isDigitForSelectionWubi) {
                    // In Juying mode, always clear buffer/predictions and set flags when Alt symbol is about to be committed
                    if (juyingModeEnabled) {
                        wubiInputController.clearBuffer()
                        wubiInputController.clearNextWordPredictions()
                        ic.finishComposingText()
                        altUsedForSymbolInput = true  // Mark that Alt was used for symbol input
                        // Clear saved Alt state (long hold for symbol cancels single-press insertion)
                        savedAltSuggestion = null
                        savedAltCandidatesForNextPage = emptyList()
                        savedAltCurrentPage = 0
                        savedAltChineseMode = null
                        // Cancel any pending Alt selection
                        pendingAltSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingAltSelectionRunnable = null
                    } else {
                        // Non-Juying mode: Clear buffer instead of committing as English
                        // When user presses Alt+key for symbol, they want to cancel input
                        val buffer = wubiInputController.getBuffer()
                        if (buffer.isNotEmpty()) {
                            wubiInputController.clearBuffer()
                            wubiInputController.clearNextWordPredictions()
                            ic.finishComposingText()  // Remove composing text without committing
                        }
                    }

                    // Convert punctuation to Chinese if applicable (only when Chinese punctuation mode is enabled)
                    // Use altMappedCharWubi directly if available, otherwise use altChar
                    val char = altMappedCharWubi?.firstOrNull() ?: altChar.toChar()
                    val chinesePunctuation: String? = if (wubiInputController.isChinesePunctuationMode()) {
                        when (char) {
                            ',' -> "，"
                            '.' -> "。"
                            '!' -> "！"
                            '?' -> "？"
                            ':' -> "："
                            ';' -> "；"
                            '(' -> "（"
                            ')' -> "）"
                            '[' -> "【"
                            ']' -> "】"
                            '<' -> "《"
                            '>' -> "》"
                            '~' -> "～"
                            '\\' -> "、"
                            '^' -> "……"
                            '_' -> "——"
                            '"' -> {
                                val result = if (wubiInputController.isNextDoubleQuoteOpening()) "\u201C" else "\u201D"
                                wubiInputController.toggleDoubleQuoteState()
                                result
                            }
                            '\'' -> {
                                val result = if (wubiInputController.isNextSingleQuoteOpening()) "\u2018" else "\u2019"
                                wubiInputController.toggleSingleQuoteState()
                                result
                            }
                            else -> null
                        }
                    } else null

                    // Input Chinese punctuation or the original character
                    val textToCommit = chinesePunctuation ?: char.toString()
                    ic.commitText(textToCommit, 1)

                    // In Juying mode, ALWAYS clear predictions when Alt symbol is committed
                    // This should happen regardless of how Alt was activated (long-press, one-shot, or latch)
                    if (juyingModeEnabled) {
                        wubiInputController.clearBuffer()
                        wubiInputController.clearNextWordPredictions()
                    }

                    // Handle Alt state clearing based on mode
                    // In Juying mode with long-press, OR in non-Juying mode with suggestions visible,
                    // clear all Alt state (including latch) - this prevents Alt getting stuck
                    // EXCEPTION: For digits and period, don't clear Alt state - allow continuous number input (e.g., "10.5")
                    val isDigitOrPeriodWubi = char.isDigit() || char == '.'
                    val shouldClearAllAltStateWubi = (isAltLongPressWubi && juyingModeEnabled && !isDigitOrPeriodWubi) ||
                        (!juyingModeEnabled && hasCandidatesToPaginate && altLatchActive)
                    if (shouldClearAllAltStateWubi) {
                        modifierStateController.clearAltState(resetPressedState = true)  // Clear all Alt state
                        altLastPressTime = 0L  // Reset timing state
                        altLatchJustDisabled = true
                    } else if (isDigitOrPeriodWubi) {
                        // For continuous Alt input: ALWAYS update altLastPressTime for digits and period
                        // This ensures altFromJuyingTracking remains true for subsequent digit/period keys
                        altLastPressTime = currentTimeWubi
                        if (altOneShot && !altLatchActive) {
                            modifierStateController.clearAltState(resetPressedState = false)
                        }
                    } else if (altOneShot && !altLatchActive) {
                        // Non-digit character with one-shot: clear Alt state and disable continuous input
                        modifierStateController.clearAltState(resetPressedState = false)
                        altLatchJustDisabled = true
                    }

                    // If Chinese punctuation was committed while Alt is held (not latch),
                    // set flag to prevent stale event meta state from keeping Alt active
                    if (chinesePunctuation != null && !altLatchActive && !shouldClearAllAltStateWubi) {
                        altLatchJustDisabled = true
                    }

                    // IMPORTANT: Clear altUsedForSymbolInput now that symbol input is complete
                    // This prevents the next Alt UP from incorrectly clearing one-shot mode
                    altUsedForSymbolInput = false

                    updateStatusBarText()
                    return true
                }
            }

            // Handle number keys 1-9 for candidate selection
            // Skip this in Juying mode - use physical keys (Shift/Sym/Space/Ctrl/Alt) for selection instead
            if (wubiInputController.hasCandidates() && !juyingModeEnabled) {
                if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                    val selected = wubiInputController.handleNumberKey(keyCode)
                    if (selected != null) {
                        ic.commitText(selected, 1)
                        // Clear Alt modifier (including latch state from double-tap)
                        modifierStateController.clearAltState(resetPressedState = true)
                        altUsedForPagination = false
                        altLastPressTime = 0L
                        altLatchJustDisabled = true
                        altUsedForCandidateSelection = true
                        updateStatusBarText()
                        return true
                    }
                }
                // Also check unicode char for number selection
                if (event != null && event.unicodeChar != 0) {
                    val char = event.unicodeChar.toChar()
                    if (char.isDigit() && char in '1'..'9') {
                        val number = char.digitToInt()
                        val index = number - 1
                        val selected = wubiInputController.selectCandidate(index)
                        if (selected != null) {
                            ic.commitText(selected, 1)
                            modifierStateController.clearAltState(resetPressedState = true)
                            altUsedForPagination = false
                            altLastPressTime = 0L
                            altLatchJustDisabled = true
                            altUsedForCandidateSelection = true
                            updateStatusBarText()
                            return true
                        }
                    }
                }
            }

            // Handle Shift+Del to clear entire Wubi buffer and remove composing text
            if (keyCode == KeyEvent.KEYCODE_DEL && shiftPressed) {
                val buffer = wubiInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    wubiInputController.clearBuffer()
                    // Clear the composing text (the displayed wubi code)
                    ic.setComposingText("", 1)
                    ic.finishComposingText()
                    updateStatusBarText()
                    return true
                }
            }

            // Handle backspace in Wubi mode
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                val hadBuffer = wubiInputController.getBuffer().isNotEmpty()
                val hadPredictions = wubiInputController.isShowingNextWordPredictions()
                if (wubiInputController.handleBackspace()) {
                    val buffer = wubiInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        ic.setComposingText(buffer, 1)
                    } else if (hadBuffer) {
                        // Buffer was cleared, finish composing text
                        ic.finishComposingText()
                        // Delete the last character from committed text
                        ic.deleteSurroundingText(1, 0)
                    }
                    // Clear Alt and Ctrl states when DEL clears suggestions
                    modifierStateController.clearAltState(resetPressedState = true)
                    modifierStateController.clearCtrlState(resetPressedState = true)
                    // If we just cleared predictions, don't call finishComposingText
                    updateStatusBarText()
                    return true
                } else {
                    // Buffer was empty and no predictions to clear
                    ic.finishComposingText()
                    // Check if there's selected text - if so, delete the selection
                    val selectedText = ic.getSelectedText(0)
                    if (selectedText != null && selectedText.isNotEmpty()) {
                        ic.commitText("", 1)  // Replace selection with empty string
                    } else {
                        ic.deleteSurroundingText(1, 0)  // Delete one character
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key - select first candidate
            if (keyCode == KeyEvent.KEYCODE_SPACE && wubiInputController.hasCandidates()) {
                // Save buffer length BEFORE selection (for deletion)
                val bufferLengthBeforeSelect = wubiInputController.getBuffer().length
                val selected = wubiInputController.selectFirstCandidate()
                if (selected != null) {
                    var remainingBuffer = wubiInputController.getBuffer()
                    // For memory/prediction candidates, buffer may not be consumed normally
                    // If buffer unchanged, it's a memory candidate - clear the buffer entirely
                    if (remainingBuffer.length == bufferLengthBeforeSelect) {
                        wubiInputController.clearBuffer()
                        remainingBuffer = ""
                    }
                    // Clear composing text first, then commit - avoids race conditions in some apps
                    ic.setComposingText("", 1)
                    ic.commitText(selected, 1)
                    if (remainingBuffer.isNotEmpty()) {
                        ic.setComposingText(remainingBuffer, 1)
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key when no candidates and empty buffer
            if (keyCode == KeyEvent.KEYCODE_SPACE && !wubiInputController.hasCandidates() && wubiInputController.getBuffer().isEmpty()) {
                // Finalize phrase learning session - space indicates end of phrase
                wubiInputController.finalizeSessionOnSpace()

                // Try double-space-to-period first (supports Chinese punctuation mode)
                val useChinesePunctuation = wubiInputController.isChinesePunctuationMode()
                if (textInputController.handleDoubleSpaceToPeriod(
                        keyCode = keyCode,
                        inputConnection = ic,
                        shouldDisableSmartFeatures = shouldDisableSmartFeatures,
                        onStatusBarUpdate = { updateStatusBarText() },
                        useChinesePunctuation = useChinesePunctuation
                    )) {
                    return true
                }
                // If not double-space, just insert a space
                ic.commitText(" ", 1)
                return true
            }

            // Handle letter keys for Wubi code input
            if (event != null && event.unicodeChar != 0) {
                val char = event.unicodeChar.toChar()

                if (char.isLetter()) {
                    // In Wubi mode, capital letters (Shift+key) exit and input directly
                    if (shiftPressed) {
                        val buffer = wubiInputController.getBuffer()
                        if (buffer.isNotEmpty()) {
                            val committed = wubiInputController.commitBufferAsIs()
                            if (committed != null) {
                                ic.commitText(committed, 1)
                            }
                        }
                        ic.commitText(char.toString(), 1)
                        updateStatusBarText()
                        return true
                    }

                    // Check for overflow commit (5th letter scenario) BEFORE adding the letter
                    val overflowCommit = wubiInputController.checkOverflowCommit()
                    if (overflowCommit != null) {
                        ic.commitText(overflowCommit, 1)
                    }

                    // Add letter to Wubi buffer (with Z key symbol mode support)
                    when (val result = wubiInputController.handleLetterKeyWithResult(char)) {
                        is WubiInputController.LetterKeyResult.SymbolOutput -> {
                            // Z key symbol mode - output the symbol directly
                            ic.commitText(result.symbol, 1)
                            updateStatusBarText()
                            return true
                        }
                        is WubiInputController.LetterKeyResult.Handled -> {
                            // Check for auto-commit (4-char code with single candidate)
                            val autoCommit = wubiInputController.checkAutoCommit()
                            if (autoCommit != null) {
                                ic.commitText(autoCommit, 1)
                            } else {
                                val buffer = wubiInputController.getBuffer()
                                ic.setComposingText(buffer, 1)
                            }
                            updateStatusBarText()
                            return true
                        }
                        is WubiInputController.LetterKeyResult.NotHandled -> {
                            // Fall through to next handler
                        }
                    }
                }
            }

            // Handle punctuation - use Chinese punctuation in Wubi mode (only when Chinese punctuation mode is enabled)
            if (event != null && event.unicodeChar != 0 && wubiInputController.isChinesePunctuationMode()) {
                val char = event.unicodeChar.toChar()
                // Check if this is a punctuation that should be converted to Chinese
                val chinesePunctuation: String? = when (char) {
                    ',' -> "，"  // 逗号
                    '.' -> "。"  // 句号
                    '!' -> "！"  // 感叹号
                    '?' -> "？"  // 问号
                    ':' -> "："  // 冒号
                    ';' -> "；"  // 分号
                    '(' -> "（"  // 左括号
                    ')' -> "）"  // 右括号
                    '[' -> "【"  // 左方括号
                    ']' -> "】"  // 右方括号
                    '<' -> "《"  // 左书名号
                    '>' -> "》"  // 右书名号
                    '~' -> "～"  // 波浪号
                    '\\' -> "、" // 顿号
                    '^' -> "……" // 省略号
                    '_' -> "——" // 破折号
                    '"' -> {
                        // Alternate between opening and closing Chinese double quotes
                        val result = if (wubiInputController.isNextDoubleQuoteOpening()) {
                            "\u201C" // " opening double quote
                        } else {
                            "\u201D" // " closing double quote
                        }
                        wubiInputController.toggleDoubleQuoteState()
                        result
                    }
                    '\'' -> {
                        // Alternate between opening and closing Chinese single quotes
                        val result = if (wubiInputController.isNextSingleQuoteOpening()) {
                            "\u2018" // ' opening single quote
                        } else {
                            "\u2019" // ' closing single quote
                        }
                        wubiInputController.toggleSingleQuoteState()
                        result
                    }
                    else -> null
                }

                if (chinesePunctuation != null) {
                    val buffer = wubiInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = wubiInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                        }
                    }
                    ic.commitText(chinesePunctuation, 1)
                    // Clear next-word predictions since punctuation ends the phrase context
                    wubiInputController.onPunctuationInput()
                    // Clear Alt one-shot after typing Chinese punctuation
                    if (altOneShot) {
                        altOneShot = false
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // ESC key or Ctrl+Q to exit Wubi mode
            if (keyCode == KeyEvent.KEYCODE_ESCAPE ||
                (keyCode == KeyEvent.KEYCODE_Q && ctrlPressed)) {
                wubiInputController.setWubiMode(false)
                ic.finishComposingText()
                updateStatusBarText()
                return true
            }
        }

        // Handle Zhenma input mode
        if (zhenmaInputController.isZhenmaMode() && ic != null) {
            // Handle SYM mode - when SYM is active, allow symbol input just like in English mode
            if (symLayoutController.isSymActive()) {
                val symResult = symLayoutController.handleKeyWhenActive(
                    keyCode,
                    event,
                    ic,
                    ctrlLatchActive = ctrlLatchActive,
                    altLatchActive = altLatchActive,
                    updateStatusBar = { updateStatusBarText() },
                    onSymbolInserted = {
                        // Clear next word predictions after inserting symbol
                        zhenmaInputController.clearNextWordPredictions()
                    }
                )
                when (symResult) {
                    SymLayoutController.SymKeyResult.CONSUME -> return true
                    SymLayoutController.SymKeyResult.CALL_SUPER -> return super.onKeyDown(keyCode, event)
                    SymLayoutController.SymKeyResult.NOT_HANDLED -> { /* Continue to Zhenma handling */ }
                }
            }

            // Handle Alt modifier - when Alt is active (latched, one-shot, or pressed), input alternate characters
            // When a non-Alt key is pressed while Alt is held, always handle it as symbol input
            // (Alt key alone for candidate selection is handled earlier in Juying key handling)
            val currentTimeZhenma = System.currentTimeMillis()
            val longPressThresholdZhenma = SettingsManager.getLongPressThreshold(this)
            val altHoldDurationZhenma = if (altLastPressTime > 0) currentTimeZhenma - altLastPressTime else 0L
            val isAltLongPressZhenma = altHoldDurationZhenma >= longPressThresholdZhenma

            // Use Alt LED status as the source of truth: if LED is off, Alt is not active
            val altLedIsOffZhenma = !altLatchActive && !altOneShot && !altPressed

            // Check multiple ways Alt might be active
            val altFromEventZhenma = !altLatchJustDisabled && !altUsedForCandidateSelection && !altLedIsOffZhenma && event != null && ((event.metaState and KeyEvent.META_ALT_ON) != 0)
            val altFromJuyingTrackingZhenma = !altLedIsOffZhenma && altLastPressTime > 0
            // Skip Alt handling if latch was just disabled or Alt was used for candidate selection or LED is off
            val shouldSkipAltHandlingZhenma = (altLatchJustDisabled && !altLatchActive && !altOneShot) || altUsedForCandidateSelection || altLedIsOffZhenma
            if (event != null && !shouldSkipAltHandlingZhenma && (altLatchActive || altOneShot || altPressed || altFromEventZhenma || altFromJuyingTrackingZhenma)) {
                // Use Pastiera's altSymManager mapping (device-specific) instead of system's getUnicodeChar
                val altMappedCharZhenma = altSymManager.getAltMappings()[keyCode]
                val altChar = altMappedCharZhenma?.firstOrNull()?.code ?: event.getUnicodeChar(KeyEvent.META_ALT_ON)
                // Get the base character without any modifiers (when Alt is held, event.unicodeChar equals altChar)
                val baseCharZhenma = event.getUnicodeChar(0)

                // In non-Juying mode, if Alt produces a digit (1-9) and there are candidates,
                // handle candidate selection right here using the Alt-mapped digit
                val isDigitForSelectionZhenma = !juyingModeEnabled &&
                                                zhenmaInputController.hasCandidates() &&
                                                altChar.toChar().isDigit() &&
                                                altChar.toChar() in '1'..'9'

                // Handle candidate selection when Alt+key produces a digit
                if (isDigitForSelectionZhenma) {
                    val number = altChar.toChar().digitToInt()
                    val index = number - 1
                    val selected = zhenmaInputController.selectCandidate(index)
                    if (selected != null) {
                        ic.commitText(selected, 1)

                        // Set remaining buffer as new composing text
                        val remainingBuffer = zhenmaInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }

                        // Clear Alt modifier (including latch state from double-tap)
                        modifierStateController.clearAltState(resetPressedState = true)
                        altUsedForPagination = false
                        altLastPressTime = 0L

                        // Set flag to ignore Alt until physical key is released
                        altLatchJustDisabled = true
                        altUsedForCandidateSelection = true

                        updateStatusBarText()
                        return true
                    }
                }

                // Only proceed if we get a valid alternate character that's different from the base one
                // AND it's not a digit for candidate selection
                if (altChar != 0 && altChar != baseCharZhenma && !isDigitForSelectionZhenma) {
                    // In Juying mode, always clear buffer/predictions and set flags when Alt symbol is about to be committed
                    if (juyingModeEnabled) {
                        zhenmaInputController.clearBuffer()
                        zhenmaInputController.clearNextWordPredictions()
                        ic.finishComposingText()
                        altUsedForSymbolInput = true  // Mark that Alt was used for symbol input
                        // Clear saved Alt state (long hold for symbol cancels single-press insertion)
                        savedAltSuggestion = null
                        savedAltCandidatesForNextPage = emptyList()
                        savedAltCurrentPage = 0
                        savedAltChineseMode = null
                        // Cancel any pending Alt selection
                        pendingAltSelectionRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingAltSelectionRunnable = null
                    } else {
                        // Non-Juying mode: Clear buffer instead of committing as English
                        // When user presses Alt+key for symbol, they want to cancel input
                        val buffer = zhenmaInputController.getBuffer()
                        if (buffer.isNotEmpty()) {
                            zhenmaInputController.clearBuffer()
                            zhenmaInputController.clearNextWordPredictions()
                            ic.finishComposingText()  // Remove composing text without committing
                        }
                    }

                    // Convert punctuation to Chinese if applicable (only when Chinese punctuation mode is enabled)
                    // Use altMappedCharZhenma directly if available, otherwise use altChar
                    val char = altMappedCharZhenma?.firstOrNull() ?: altChar.toChar()
                    val chinesePunctuation: String? = if (zhenmaInputController.isChinesePunctuationMode()) {
                        when (char) {
                            ',' -> "，"
                            '.' -> "。"
                            '!' -> "！"
                            '?' -> "？"
                            ':' -> "："
                            ';' -> "；"
                            '(' -> "（"
                            ')' -> "）"
                            '[' -> "【"
                            ']' -> "】"
                            '<' -> "《"
                            '>' -> "》"
                            '~' -> "～"
                            '\\' -> "、"
                            '^' -> "……"
                            '_' -> "——"
                            '"' -> {
                                val result = if (zhenmaInputController.isNextDoubleQuoteOpening()) "\u201C" else "\u201D"
                                zhenmaInputController.toggleDoubleQuoteState()
                                result
                            }
                            '\'' -> {
                                val result = if (zhenmaInputController.isNextSingleQuoteOpening()) "\u2018" else "\u2019"
                                zhenmaInputController.toggleSingleQuoteState()
                                result
                            }
                            else -> null
                        }
                    } else null

                    // Input Chinese punctuation or the original character
                    val textToCommit = chinesePunctuation ?: char.toString()
                    ic.commitText(textToCommit, 1)

                    // In Juying mode, ALWAYS clear predictions when Alt symbol is committed
                    // This should happen regardless of how Alt was activated (long-press, one-shot, or latch)
                    if (juyingModeEnabled) {
                        zhenmaInputController.clearBuffer()
                        zhenmaInputController.clearNextWordPredictions()
                    }

                    // Handle Alt state clearing based on mode
                    // In Juying mode with long-press, OR in non-Juying mode with suggestions visible,
                    // clear all Alt state (including latch) - this prevents Alt getting stuck
                    // EXCEPTION: For digits and period, don't clear Alt state - allow continuous number input (e.g., "10.5")
                    val isDigitOrPeriodZhenma = char.isDigit() || char == '.'
                    val shouldClearAllAltStateZhenma = (isAltLongPressZhenma && juyingModeEnabled && !isDigitOrPeriodZhenma) ||
                        (!juyingModeEnabled && hasCandidatesToPaginate && altLatchActive)
                    if (shouldClearAllAltStateZhenma) {
                        modifierStateController.clearAltState(resetPressedState = true)  // Clear all Alt state
                        altLastPressTime = 0L  // Reset timing state
                        altLatchJustDisabled = true
                    } else if (isDigitOrPeriodZhenma) {
                        // For continuous Alt input: ALWAYS update altLastPressTime for digits and period
                        // This ensures altFromJuyingTracking remains true for subsequent digit/period keys
                        altLastPressTime = currentTimeZhenma
                        if (altOneShot && !altLatchActive) {
                            modifierStateController.clearAltState(resetPressedState = false)
                        }
                    } else if (altOneShot && !altLatchActive) {
                        // Non-digit character with one-shot: clear Alt state and disable continuous input
                        modifierStateController.clearAltState(resetPressedState = false)
                        altLatchJustDisabled = true
                    }

                    // If Chinese punctuation was committed while Alt is held (not latch),
                    // set flag to prevent stale event meta state from keeping Alt active
                    if (chinesePunctuation != null && !altLatchActive && !shouldClearAllAltStateZhenma) {
                        altLatchJustDisabled = true
                    }

                    // IMPORTANT: Clear altUsedForSymbolInput now that symbol input is complete
                    // This prevents the next Alt UP from incorrectly clearing one-shot mode
                    altUsedForSymbolInput = false

                    updateStatusBarText()
                    return true
                }
            }

            // Handle number keys 1-9 for candidate selection
            // Skip this in Juying mode - use physical keys (Shift/Sym/Space/Ctrl/Alt) for selection instead
            if (zhenmaInputController.hasCandidates() && !juyingModeEnabled) {
                if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                    val selected = zhenmaInputController.handleNumberKey(keyCode)
                    if (selected != null) {
                        ic.commitText(selected, 1)
                        // Clear Alt modifier (including latch state from double-tap)
                        modifierStateController.clearAltState(resetPressedState = true)
                        altUsedForPagination = false
                        altLastPressTime = 0L
                        altLatchJustDisabled = true
                        altUsedForCandidateSelection = true
                        updateStatusBarText()
                        return true
                    }
                }
                // Also check unicode char for number selection
                if (event != null && event.unicodeChar != 0) {
                    val char = event.unicodeChar.toChar()
                    if (char.isDigit() && char in '1'..'9') {
                        val number = char.digitToInt()
                        val index = number - 1
                        val selected = zhenmaInputController.selectCandidate(index)
                        if (selected != null) {
                            ic.commitText(selected, 1)
                            modifierStateController.clearAltState(resetPressedState = true)
                            altUsedForPagination = false
                            altLastPressTime = 0L
                            altLatchJustDisabled = true
                            altUsedForCandidateSelection = true
                            updateStatusBarText()
                            return true
                        }
                    }
                }
            }

            // Handle Shift+Del to clear entire Zhenma buffer and remove composing text
            if (keyCode == KeyEvent.KEYCODE_DEL && shiftPressed) {
                val buffer = zhenmaInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    zhenmaInputController.clearBuffer()
                    // Clear the composing text (the displayed zhenma code)
                    ic.setComposingText("", 1)
                    ic.finishComposingText()
                    updateStatusBarText()
                    return true
                }
            }

            // Handle backspace in Zhenma mode
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                val hadBuffer = zhenmaInputController.getBuffer().isNotEmpty()
                val hadPredictions = zhenmaInputController.isShowingNextWordPredictions()
                if (zhenmaInputController.handleBackspace()) {
                    val buffer = zhenmaInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        ic.setComposingText(buffer, 1)
                    } else if (hadBuffer) {
                        // Buffer was cleared, finish composing text
                        ic.finishComposingText()
                        // Delete the last character from committed text
                        ic.deleteSurroundingText(1, 0)
                    }
                    // Clear Alt and Ctrl states when DEL clears suggestions
                    modifierStateController.clearAltState(resetPressedState = true)
                    modifierStateController.clearCtrlState(resetPressedState = true)
                    // If we just cleared predictions, don't call finishComposingText
                    updateStatusBarText()
                    return true
                } else {
                    // Buffer was empty and no predictions to clear
                    ic.finishComposingText()
                    // Check if there's selected text - if so, delete the selection
                    val selectedText = ic.getSelectedText(0)
                    if (selectedText != null && selectedText.isNotEmpty()) {
                        ic.commitText("", 1)  // Replace selection with empty string
                    } else {
                        ic.deleteSurroundingText(1, 0)  // Delete one character
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key - select first candidate
            if (keyCode == KeyEvent.KEYCODE_SPACE && zhenmaInputController.hasCandidates()) {
                // Save buffer length BEFORE selection (for deletion)
                val bufferLengthBeforeSelect = zhenmaInputController.getBuffer().length
                val selected = zhenmaInputController.selectFirstCandidate()
                if (selected != null) {
                    var remainingBuffer = zhenmaInputController.getBuffer()
                    // For memory/prediction candidates, buffer may not be consumed normally
                    // If buffer unchanged, it's a memory candidate - clear the buffer entirely
                    if (remainingBuffer.length == bufferLengthBeforeSelect) {
                        zhenmaInputController.clearBuffer()
                        remainingBuffer = ""
                    }
                    // Clear composing text first, then commit - avoids race conditions in some apps
                    ic.setComposingText("", 1)
                    ic.commitText(selected, 1)
                    if (remainingBuffer.isNotEmpty()) {
                        ic.setComposingText(remainingBuffer, 1)
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key when no candidates and empty buffer
            if (keyCode == KeyEvent.KEYCODE_SPACE && !zhenmaInputController.hasCandidates() && zhenmaInputController.getBuffer().isEmpty()) {
                // Try double-space-to-period first (supports Chinese punctuation mode)
                val useChinesePunctuation = zhenmaInputController.isChinesePunctuationMode()
                if (textInputController.handleDoubleSpaceToPeriod(
                        keyCode = keyCode,
                        inputConnection = ic,
                        shouldDisableSmartFeatures = shouldDisableSmartFeatures,
                        onStatusBarUpdate = { updateStatusBarText() },
                        useChinesePunctuation = useChinesePunctuation
                    )) {
                    return true
                }
                // If not double-space, just insert a space
                ic.commitText(" ", 1)
                return true
            }

            // Handle letter keys for Zhenma code input
            if (event != null && event.unicodeChar != 0) {
                val char = event.unicodeChar.toChar()

                if (char.isLetter()) {
                    // In Zhenma mode, capital letters (Shift+key) exit and input directly
                    if (shiftPressed) {
                        val buffer = zhenmaInputController.getBuffer()
                        if (buffer.isNotEmpty()) {
                            val committed = zhenmaInputController.commitBufferAsIs()
                            if (committed != null) {
                                ic.commitText(committed, 1)
                            }
                        }
                        ic.commitText(char.toString(), 1)
                        updateStatusBarText()
                        return true
                    }

                    // Add letter to Zhenma buffer
                    if (zhenmaInputController.handleLetterKey(char)) {
                        val buffer = zhenmaInputController.getBuffer()
                        ic.setComposingText(buffer, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Handle punctuation - use Chinese punctuation in Zhenma mode (only when Chinese punctuation mode is enabled)
            if (event != null && event.unicodeChar != 0 && zhenmaInputController.isChinesePunctuationMode()) {
                val char = event.unicodeChar.toChar()
                // Check if this is a punctuation that should be converted to Chinese
                val chinesePunctuation: String? = when (char) {
                    ',' -> "，"  // 逗号
                    '.' -> "。"  // 句号
                    '!' -> "！"  // 感叹号
                    '?' -> "？"  // 问号
                    ':' -> "："  // 冒号
                    ';' -> "；"  // 分号
                    '(' -> "（"  // 左括号
                    ')' -> "）"  // 右括号
                    '[' -> "【"  // 左方括号
                    ']' -> "】"  // 右方括号
                    '<' -> "《"  // 左书名号
                    '>' -> "》"  // 右书名号
                    '~' -> "～"  // 波浪号
                    '\\' -> "、" // 顿号
                    '^' -> "……" // 省略号
                    '_' -> "——" // 破折号
                    '"' -> {
                        // Alternate between opening and closing Chinese double quotes
                        val result = if (zhenmaInputController.isNextDoubleQuoteOpening()) {
                            "\u201C" // " opening double quote
                        } else {
                            "\u201D" // " closing double quote
                        }
                        zhenmaInputController.toggleDoubleQuoteState()
                        result
                    }
                    '\'' -> {
                        // Alternate between opening and closing Chinese single quotes
                        val result = if (zhenmaInputController.isNextSingleQuoteOpening()) {
                            "\u2018" // ' opening single quote
                        } else {
                            "\u2019" // ' closing single quote
                        }
                        zhenmaInputController.toggleSingleQuoteState()
                        result
                    }
                    else -> null
                }

                if (chinesePunctuation != null) {
                    val buffer = zhenmaInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = zhenmaInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                        }
                    }
                    ic.commitText(chinesePunctuation, 1)
                    // Clear next-word predictions since punctuation ends the phrase context
                    zhenmaInputController.onPunctuationInput()
                    // Clear Alt one-shot after typing Chinese punctuation
                    if (altOneShot) {
                        altOneShot = false
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // ESC key or Ctrl+Q to exit Zhenma mode
            if (keyCode == KeyEvent.KEYCODE_ESCAPE ||
                (keyCode == KeyEvent.KEYCODE_Q && ctrlPressed)) {
                zhenmaInputController.setZhenmaMode(false)
                ic.finishComposingText()
                updateStatusBarText()
                return true
            }
        }

        val isAutoCorrectEnabled = SettingsManager.getAutoCorrectEnabled(this) && !shouldDisableSmartFeatures
        if (
            inputEventRouter.handleTextInputPipeline(
                keyCode = keyCode,
                event = event,
                inputConnection = ic,
                shouldDisableSmartFeatures = shouldDisableSmartFeatures,
                isAutoCorrectEnabled = isAutoCorrectEnabled,
                textInputController = textInputController,
                autoCorrectionManager = autoCorrectionManager
            ) { updateStatusBarText() }
        ) {
            return true
        }
        
        // Handle period/comma: remove space before it if present
        // This improves punctuation spacing in English text
        if (!isChineseInputModeActive() && ic != null && event != null && event.action == KeyEvent.ACTION_DOWN) {
            val char = event.unicodeChar.toChar()
            if (char in ".," && !shiftPressed && !ctrlPressed && !altPressed) {
                // Get text before cursor to check for trailing space
                val textBefore = ic.getTextBeforeCursor(1, 0)
                if (textBefore != null && textBefore == " ") {
                    // Delete the space before the punctuation
                    ic.deleteSurroundingText(1, 0)
                    Log.d("PunctuationSpacing", "Removed space before '$char'")
                }
            }
        }

        // Check if Juying mode should intercept Alt key (for Chinese candidate selection)
        // Alt is the 5th Juying key - only intercept when there are Chinese candidates
        val juyingModeShouldInterceptAlt = juyingModeEnabled && hasChineseCandidates

        val routingDecision = inputEventRouter.routeEditableFieldKeyDown(
            keyCode = translatedKeyCode,
            event = event,
            params = InputEventRouter.EditableFieldKeyDownHandlingParams(
                inputConnection = ic,
                isNumericField = isNumericField,
                isInputViewActive = isInputViewActive,
                shiftPressed = shiftPressed,
                ctrlPressed = ctrlPressed,
                altPressed = altPressed,
                ctrlLatchActive = ctrlLatchActive,
                altLatchActive = altLatchActive,
                ctrlLatchFromNavMode = ctrlLatchFromNavMode,
                ctrlKeyMap = ctrlKeyMap,
                ctrlOneShot = ctrlOneShot,
                altOneShot = altOneShot,
                clearAltOnSpaceEnabled = clearAltOnSpaceEnabled,
                shiftOneShot = shiftOneShot,
                capsLockEnabled = capsLockEnabled,
                cursorUpdateDelayMs = CURSOR_UPDATE_DELAY,
                juyingModeShouldInterceptAlt = juyingModeShouldInterceptAlt,
                altLatchJustDisabled = altLatchJustDisabled,
                hasSuggestionsVisible = hasCandidatesToPaginate,
                juyingModeEnabled = juyingModeEnabled,
                isChineseInputActive = isChineseInputActive
            ),
            controllers = InputEventRouter.EditableFieldKeyDownControllers(
                modifierStateController = modifierStateController,
                symLayoutController = symLayoutController,
                altSymManager = altSymManager,
                variationStateController = variationStateController
            ),
            callbacks = InputEventRouter.EditableFieldKeyDownHandlingCallbacks(
                updateStatusBar = { updateStatusBarText() },
                refreshStatusBar = { refreshStatusBar() },
                disableShiftOneShot = {
                    modifierStateController.consumeShiftOneShot()
                },
                clearAltOneShot = { altOneShot = false },
                clearCtrlOneShot = { ctrlOneShot = false },
                getCharacterFromLayout = { code, keyEvent, isShiftPressed ->
                    getCharacterFromLayout(code, keyEvent, isShiftPressed)
                },
                isAlphabeticKey = { code -> isAlphabeticKey(code) },
                callSuper = { super.onKeyDown(keyCode, event) },
                callSuperWithKey = { defaultKeyCode, defaultEvent ->
                    super.onKeyDown(defaultKeyCode, defaultEvent)
                },
                startSpeechRecognition = { startSpeechRecognition() },
                getMapping = { code -> LayoutMappingRepository.getMapping(code) },
                handleMultiTapCommit = { code, mapping, uppercase, ic, hasLongPressSupport ->
                    if (ic != null) {
                        multiTapController.handleTap(code, mapping, uppercase, ic)
                    } else {
                        false
                    }
                },
                isLongPressSuppressed = { code -> multiTapController.isLongPressSuppressed(code) },
                onAltLatchDisabled = { altLatchJustDisabled = true },
                clearAltLatchJustDisabled = { altLatchJustDisabled = false },
                onSymbolInserted = {
                    // Clear English word predictions after inserting symbol from SYM keyboard
                    englishWordPredictionController.clearNextWordPredictions()
                    englishWordPredictionController.clearSuggestions()
                }
            )
        )

        return when (routingDecision) {
            InputEventRouter.EditableFieldRoutingResult.Consume -> true
            InputEventRouter.EditableFieldRoutingResult.CallSuper -> super.onKeyDown(keyCode, event)
            InputEventRouter.EditableFieldRoutingResult.Continue -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Clear gesture state on key up to prevent false swipe triggers when typing
        touchDown = false
        startPosSet = false
        keyPressedDuringTouch = false

        // Check if we have an editable field at the start (same logic as onKeyDown)
        val info = currentInputEditorInfo
        val ic = currentInputConnection
        val inputType = info?.inputType ?: EditorInfo.TYPE_NULL
        val hasEditableField = ic != null && inputType != EditorInfo.TYPE_NULL

        // If NO editable field is active, handle ONLY nav mode Ctrl release
        if (!hasEditableField) {
            return inputEventRouter.handleKeyUpWithNoEditableField(
                keyCode = keyCode,
                event = event,
                ctrlKeyMap = ctrlKeyMap,
                callbacks = InputEventRouter.NoEditableFieldCallbacks(
                    isAlphabeticKey = { code -> isAlphabeticKey(code) },
                    isLauncherPackage = { pkg -> launcherShortcutController.isLauncher(pkg) },
                    handleLauncherShortcut = { key -> launcherShortcutController.handleLauncherShortcut(key) },
                    handlePowerShortcut = { key -> launcherShortcutController.handlePowerShortcut(key) },
                    togglePowerShortcutMode = { message, isNavActive ->
                        launcherShortcutController.togglePowerShortcutMode(
                            showToast = { showToast(it) },
                            isNavModeActive = isNavActive
                        )
                    },
                    callSuper = { super.onKeyUp(keyCode, event) },
                    currentInputConnection = { currentInputConnection }
                )
            )
        }
        
        // Continue with normal IME logic for text fields
        val inputConnection = currentInputConnection ?: return super.onKeyUp(keyCode, event)

        // Always notify the tracker (even when the event is consumed)
        KeyboardEventTracker.notifyKeyEvent(keyCode, event, "KEY_UP")

        // Translate device-specific keycodes to standard Android keycodes for modifier handling
        val translatedKeyCode = translateKeyCode(keyCode)

        // Handle hold space for voice input - on key up
        if (translatedKeyCode == KeyEvent.KEYCODE_SPACE && SettingsManager.isHoldSpaceForVoice(this)) {
            // Cancel the pending voice trigger
            spaceHoldRunnable?.let { spaceHoldHandler?.removeCallbacks(it) }
            spaceHoldRunnable = null
            spaceHoldHandler = null

            // If voice was triggered, consume the key up event
            if (spaceHoldTriggeredVoice) {
                spaceHoldTriggeredVoice = false
                return true
            }
        }

        // Check if Juying mode should intercept modifier key releases
        // When Juying mode is active with Chinese input and candidates, prevent normal modifier behavior
        val juyingModeEnabled = SettingsManager.getJuyingModeEnabled(this)
        val isPinyinMode = pinyinInputController.isPinyinMode()
        val isShuangpinMode = shuangpinInputController.isShuangpinMode()
        val isWubiMode = wubiInputController.isWubiMode()
        val isZhenmaMode = zhenmaInputController.isZhenmaMode()
        val isChineseInputActive = isPinyinMode || isShuangpinMode || isWubiMode || isZhenmaMode
        val hasChineseCandidates = (isPinyinMode && pinyinInputController.hasCandidates()) ||
                           (isShuangpinMode && shuangpinInputController.hasCandidates()) ||
                           (isWubiMode && wubiInputController.hasCandidates()) ||
                           (isZhenmaMode && zhenmaInputController.hasCandidates())
        val hasWordPredictions = englishWordPredictionController.hasActivePrediction() &&
                                 englishWordPredictionController.hasSuggestions()
        val hasAnyCandidates = hasChineseCandidates || hasWordPredictions

        // Handle Alt key release in Juying mode with saved state
        // NEW BEHAVIOR: On Alt DOWN, we save the 5th suggestion and clear predictions
        // On Alt UP: schedule delayed insertion of 5th suggestion
        // If second Alt DOWN comes quickly (double-click), cancel and go to next page instead
        // Use savedAltChineseMode != null as the indicator that Alt was pressed with candidates
        if (juyingModeEnabled && isDeviceAltKey(keyCode) && savedAltChineseMode != null) {
            // Clear Alt modifier state
            if (altPressed) {
                val result = modifierStateController.handleAltKeyUp(translatedKeyCode)
            }

            // Check if there are more candidates available for next page navigation
            val hasMoreCandidatesForNextPage = savedAltCandidatesForNextPage.isNotEmpty() &&
                savedAltSuggestion == null && !altUsedForSymbolInput

            if (!altUsedForSymbolInput && (savedAltSuggestion != null || hasMoreCandidatesForNextPage)) {
                // INSTANT BEHAVIOR: Character was already committed on Alt DOWN (if suggestion exists)
                // OR: No suggestion at position but more candidates available for next page
                // This runnable just cleans up state after the double-click window expires
                // If user presses Alt again within THRESHOLD (double-click),
                // the second Alt DOWN will cancel this runnable and undo the committed character / go to next page
                pendingAltSelectionRunnable = Runnable {
                    // Character was already committed on Alt DOWN - just clean up state
                    // Clear saved state after double-click window expires
                    savedAltSuggestion = null
                    savedAltCandidatesForNextPage = emptyList()
                    savedAltCurrentPage = 0
                    savedAltChineseMode = null
                    savedAltBuffer = ""
                    savedAltPhraseCandidateSet = emptySet()
                    pendingAltSelectionRunnable = null
                    // Mark the instant selection as final (no more undo possible)
                    altCommittedCharacter = null
                    altRemainingBuffer = ""
                    // Clear Alt state
                    altLastPressTime = 0L
                    modifierStateController.clearAltState(resetPressedState = true)
                    updateStatusBarText()
                }
                mainHandler.postDelayed(pendingAltSelectionRunnable!!, altDoubleClickDelay)
            } else {
                // Alt was used for symbol input or no candidates for next page - clear state immediately
                savedAltSuggestion = null
                savedAltCandidatesForNextPage = emptyList()
                savedAltCurrentPage = 0
                savedAltChineseMode = null
                savedAltBuffer = ""
                savedAltPhraseCandidateSet = emptySet()
                // Clear altLastPressTime immediately since Alt was used for symbol input
                // This prevents the next key press from being treated as Alt+key
                altLastPressTime = 0L
                // IMPORTANT: Reset altUsedForSymbolInput here since we return early
                // and never reach the normal Alt UP handling at line 5090
                altUsedForSymbolInput = false
                modifierStateController.clearAltState(resetPressedState = true)
                updateStatusBarText()
            }

            // Don't clear altLastPressTime for pending selection branch - it's needed for double-click detection
            return true
        }

        if (juyingModeEnabled && hasAnyCandidates) {
            // Try both raw and translated keycode for consistent key matching across devices
            var juyingCandidateIndex = SettingsManager.getJuyingCandidateIndex(this, translatedKeyCode)
            if (juyingCandidateIndex < 0 && keyCode != translatedKeyCode) {
                juyingCandidateIndex = SettingsManager.getJuyingCandidateIndex(this, keyCode)
            }

            // Apply swap logic for Shift/Alt keycodes when swap mode is ON
            val shiftAltSwapped = SettingsManager.getShiftAltSwapped(this)
            if (shiftAltSwapped && juyingCandidateIndex >= 0) {
                if (isDeviceShiftKey(keyCode)) {
                    juyingCandidateIndex = 4  // Shift keycode picks rightmost (index 4)
                } else if (isDeviceAltKey(keyCode)) {
                    juyingCandidateIndex = 1  // Alt keycode picks leftmost (index 1)
                }
            }

            // Fallback: Check modifier keys using device-specific detection
            // This handles cases where RIGHT variants (e.g., KEYCODE_ALT_RIGHT) are pressed
            if (juyingCandidateIndex < 0) {
                when {
                    isDeviceShiftKey(keyCode) -> juyingCandidateIndex = if (shiftAltSwapped) 4 else 0  // Shift keycode: swapped->4, normal->0
                    isDeviceCtrlKey(keyCode) -> juyingCandidateIndex = 3   // Ctrl is 4th key (index 3)
                    isDeviceAltKey(keyCode) -> juyingCandidateIndex = if (shiftAltSwapped) 1 else 4    // Alt keycode: swapped->1 (leftmost), normal->4
                }
            }

            // For English word predictions, skip Space key (index 0) - let it work normally
            val isEnglishOnlyMode = hasWordPredictions && !isChineseInputActive
            if (isEnglishOnlyMode && juyingCandidateIndex == 0) {
                juyingCandidateIndex = -1
            }

            if (juyingCandidateIndex >= 0) {
                // This is a Juying key - but we still need to handle modifier key releases
                // to clear the pressed/latch state when the physical key is released
                if (isDeviceShiftKey(keyCode) && shiftPressed) {
                    val result = modifierStateController.handleShiftKeyUp(translatedKeyCode)
                    if (result.shouldUpdateStatusBar) {
                        updateStatusBarText()
                    }
                } else if (isDeviceCtrlKey(keyCode) && ctrlPressed) {
                    val result = modifierStateController.handleCtrlKeyUp(translatedKeyCode)
                    if (result.shouldUpdateStatusBar) {
                        updateStatusBarText()
                    }
                } else if (isDeviceAltKey(keyCode) && altPressed) {
                    // Alt key released - handled above with saved state logic
                    val result = modifierStateController.handleAltKeyUp(translatedKeyCode)
                    if (result.shouldUpdateStatusBar) {
                        updateStatusBarText()
                    }
                    // If Alt was used for symbol input, clear altLastPressTime to prevent
                    // next key from being treated as Alt+key
                    if (altUsedForSymbolInput) {
                        altLastPressTime = 0L
                        altUsedForSymbolInput = false
                        modifierStateController.clearAltState(resetPressedState = true)
                        updateStatusBarText()
                    }
                }
                // Consume the key up event after handling modifier release
                return true
            }
        }

        // Handle Shift release for double-tap
        if (isDeviceShiftKey(keyCode)) {
            if (shiftPressed) {
                val result = modifierStateController.handleShiftKeyUp(translatedKeyCode)
                if (result.shouldUpdateStatusBar) {
                    updateStatusBarText()
                }
            }
            return super.onKeyUp(keyCode, event)
        }

        // Handle Ctrl release for double-tap
        if (isDeviceCtrlKey(keyCode)) {
            if (ctrlPressed) {
                val result = modifierStateController.handleCtrlKeyUp(translatedKeyCode)
                if (result.shouldUpdateStatusBar) {
                    updateStatusBarText()
                }
            }
            return super.onKeyUp(keyCode, event)
        }

        // Handle Alt release for double-tap
        if (isDeviceAltKey(keyCode)) {
            // If Alt was used for pagination, skip normal handling to prevent latch
            if (altUsedForPagination) {
                altUsedForPagination = false
                altPressed = false
                altUsedForCandidateSelection = false
                return super.onKeyUp(keyCode, event)
            }
            // Clear candidate selection flag when Alt is released
            // Also clear any lingering Alt states to prevent stuck Alt
            if (altUsedForCandidateSelection) {
                altUsedForCandidateSelection = false
                modifierStateController.clearAltState(resetPressedState = true)
                altLatchJustDisabled = false
                altPressed = false
                updateStatusBarText()
                return super.onKeyUp(keyCode, event)
            }
            if (altPressed) {
                val result = modifierStateController.handleAltKeyUp(translatedKeyCode)
                if (result.shouldUpdateStatusBar) {
                    updateStatusBarText()
                }
            }
            // If Alt was used for symbol input, clear altLastPressTime to prevent
            // next key from being treated as Alt+key
            if (altUsedForSymbolInput) {
                altLastPressTime = 0L
                altUsedForSymbolInput = false
                modifierStateController.clearAltState(resetPressedState = true)
                updateStatusBarText()
            }
            return super.onKeyUp(keyCode, event)
        }

        // Handle SYM key release (nothing to do; it is a toggle)
        // Use translatedKeyCode for BlackBerry compatibility (raw keyCode 58 -> translated 63)
        if (translatedKeyCode == KeyEvent.KEYCODE_SYM || keyCode == KEYCODE_SYM) {
            // Consume the event - SYM is a toggle handled on key down
            return true
        }
        
        if (symLayoutController.handleKeyUp(keyCode, shiftPressed)) {
            return true
        }
        
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Aggiunge una nuova mappatura Alt+tasto -> carattere.
     */
    fun addAltKeyMapping(keyCode: Int, character: String) {
        altSymManager.addAltKeyMapping(keyCode, character)
    }

    /**
     * Rimuove una mappatura Alt+tasto esistente.
     */
    fun removeAltKeyMapping(keyCode: Int) {
        altSymManager.removeAltKeyMapping(keyCode)
    }

    /**
     * Shows a toast message to the user.
     */
    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Intercepts trackpad/touch-sensitive keyboard motion events.
     * The Unihertz Titan 2 keyboard can act as a trackpad, sending MotionEvents
     * for scrolling, cursor movement, and gestures.
     */
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        val handled = motionEventController.handle(event)
        if (handled != null) {
            return handled
        }

        return super.onGenericMotionEvent(event)
    }
}
