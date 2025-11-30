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
import it.neuralrad.coolwulf.core.ShuangpinInputController
import it.neuralrad.coolwulf.core.WubiInputController
import it.neuralrad.coolwulf.core.ZhenmaInputController
import it.neuralrad.coolwulf.core.SymLayoutController
import it.neuralrad.coolwulf.core.TextInputController
import it.neuralrad.coolwulf.data.layout.LayoutMappingRepository
import it.neuralrad.coolwulf.data.mappings.KeyMappingLoader
import it.neuralrad.coolwulf.data.variation.VariationRepository
import it.neuralrad.coolwulf.inputmethod.SpeechRecognitionActivity

/**
 * Input method service specialized for physical keyboards.
 * Handles advanced features such as long press that simulates Alt+key.
 */
class PhysicalKeyboardInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "PastieraInputMethod"
    }

    // SharedPreferences for settings
    private lateinit var prefs: SharedPreferences
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private lateinit var altSymManager: AltSymManager
    
    // Broadcast receiver for speech recognition
    private var speechResultReceiver: BroadcastReceiver? = null
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
    private lateinit var shuangpinInputController: ShuangpinInputController
    private lateinit var wubiInputController: WubiInputController
    private lateinit var zhenmaInputController: ZhenmaInputController
    private lateinit var englishWordPredictionController: it.neuralrad.coolwulf.core.EnglishWordPredictionController
    private var clearAltOnSpaceEnabled: Boolean = false

    private val motionEventController = MotionEventController(logTag = TAG)
    private lateinit var multiTapController: MultiTapController

    // Pagination double press tracking
    private var altLastPressTime = 0L
    private var shiftLastPressTime = 0L

    // Constants
    private val DOUBLE_TAP_THRESHOLD = 500L
    private val CURSOR_UPDATE_DELAY = 50L
    private val MULTI_TAP_TIMEOUT_MS = 800L
    private val PAGINATION_DOUBLE_PRESS_THRESHOLD = 400L

    private val symPage: Int
        get() = if (::symLayoutController.isInitialized) symLayoutController.currentSymPage() else 0

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
        if (SettingsManager.isBlackBerryDevice(this)) {
            return keyCode == BLACKBERRY_KEYCODE_ALT
        }
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
            val intent = Intent(this, SpeechRecognitionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            startActivity(intent)
            Log.d(TAG, "Speech recognition started via Alt+Ctrl shortcut")
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
        shuangpinInputController = ShuangpinInputController(this)
        wubiInputController = WubiInputController(this)
        zhenmaInputController = ZhenmaInputController(this)

        // Apply Chinese next word prediction setting
        val chineseNextWordPredictionEnabled = SettingsManager.getChineseNextWordPredictionEnabled(this)
        pinyinInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)
        shuangpinInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)
        wubiInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)
        zhenmaInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)

        // Start clipboard history listener if enabled
        if (SettingsManager.getClipboardHistoryEnabled(this)) {
            it.neuralrad.coolwulf.core.ClipboardHistoryManager.startListening(this)
        }

        englishWordPredictionController = it.neuralrad.coolwulf.core.EnglishWordPredictionController(this)
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

        // Register listener for Pinyin candidate selection (with index)
        val pinyinListener = object : VariationButtonHandler.OnPinyinCandidateSelectedListener {
            override fun onPinyinCandidateSelected(candidate: String, candidateIndex: Int) {
                // Let PinyinInputController handle buffer management based on candidate type
                pinyinInputController.selectCandidate(candidateIndex)
                // Clear Alt state after touch selection (e.g., after double-tap Alt to navigate pages)
                modifierStateController.clearAltState(resetPressedState = true)
                updateStatusBarText()
            }
        }
        candidatesBarController.onPinyinCandidateSelectedListener = pinyinListener

        // Register listener for Wubi candidate selection (with index)
        val wubiListener = object : VariationButtonHandler.OnWubiCandidateSelectedListener {
            override fun onWubiCandidateSelected(candidate: String, candidateIndex: Int) {
                // Let WubiInputController handle buffer management
                wubiInputController.selectCandidate(candidateIndex)
                // Clear Alt state after touch selection
                modifierStateController.clearAltState(resetPressedState = true)
                updateStatusBarText()
            }
        }
        candidatesBarController.onWubiCandidateSelectedListener = wubiListener

        // Register listener for Shuangpin candidate selection (with index)
        val shuangpinListener = object : VariationButtonHandler.OnShuangpinCandidateSelectedListener {
            override fun onShuangpinCandidateSelected(candidate: String, candidateIndex: Int) {
                // Let ShuangpinInputController handle buffer management
                shuangpinInputController.selectCandidate(candidateIndex)
                // Clear Alt state after touch selection
                modifierStateController.clearAltState(resetPressedState = true)
                updateStatusBarText()
            }
        }
        candidatesBarController.onShuangpinCandidateSelectedListener = shuangpinListener

        // Register listener for Zhenma candidate selection (with index)
        val zhenmaListener = object : VariationButtonHandler.OnZhenmaCandidateSelectedListener {
            override fun onZhenmaCandidateSelected(candidate: String, candidateIndex: Int) {
                // Let ZhenmaInputController handle buffer management
                zhenmaInputController.selectCandidate(candidateIndex)
                // Clear Alt state after touch selection
                modifierStateController.clearAltState(resetPressedState = true)
                updateStatusBarText()
            }
        }
        candidatesBarController.onZhenmaCandidateSelectedListener = zhenmaListener

        // Register listener for cursor movement (both controllers)
        val cursorListener = {
            updateStatusBarText()
        }
        candidatesBarController.onCursorMovedListener = cursorListener

        // Register listeners for page navigation
        candidatesBarController.onNextPageListener = {
            // Navigate to next page (Pinyin, Shuangpin, Wubi, Zhenma, or word prediction)
            if (pinyinInputController.isPinyinMode()) {
                pinyinInputController.nextPage()
            } else if (shuangpinInputController.isShuangpinMode()) {
                shuangpinInputController.nextPage()
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
            // Navigate to previous page (Pinyin, Shuangpin, Wubi, Zhenma, or word prediction)
            if (pinyinInputController.isPinyinMode()) {
                pinyinInputController.prevPage()
            } else if (shuangpinInputController.isShuangpinMode()) {
                shuangpinInputController.prevPage()
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

        altSymManager = AltSymManager(assets, prefs, this)
        altSymManager.reloadSymMappings() // Load custom mappings for page 1 if present
        altSymManager.reloadSymMappings2() // Load custom mappings for page 2 if present
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
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        
        // Register broadcast receiver for speech recognition
        speechResultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "Broadcast receiver called - action: ${intent?.action}")
                if (intent?.action == SpeechRecognitionActivity.ACTION_SPEECH_RESULT) {
                    val text = intent.getStringExtra(SpeechRecognitionActivity.EXTRA_TEXT)
                    Log.d(TAG, "Broadcast received with text: $text")
                    if (text != null && text.isNotEmpty()) {
                        Log.d(TAG, "Received speech recognition result: $text")
                        
                        // Delay text insertion to give the system time to restore InputConnection
                        // after the speech recognition activity has closed.
                        Handler(Looper.getMainLooper()).postDelayed({
                            // Try multiple times if InputConnection is not immediately available
                            var attempts = 0
                            val maxAttempts = 10
                            
                            fun tryInsertText() {
                                val inputConnection = currentInputConnection
                                if (inputConnection != null) {
                                    inputConnection.commitText(text, 1)
                                    Log.d(TAG, "Speech text inserted successfully: $text")
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
        
        val filter = IntentFilter(SpeechRecognitionActivity.ACTION_SPEECH_RESULT)
        
        // On Android 13+ (API 33+) we must specify whether the receiver is exported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(speechResultReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(speechResultReceiver, filter)
        }
        
        Log.d(TAG, "Broadcast receiver registered for: ${SpeechRecognitionActivity.ACTION_SPEECH_RESULT}")
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

        // Stop clipboard history listener
        it.neuralrad.coolwulf.core.ClipboardHistoryManager.stopListening(this)
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
            if (isCompactModeHidden) {
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
     * When multiple methods are enabled, cycles through them: EN -> Pinyin -> Shuangpin -> Wubi -> EN
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
        val isShuangpinActive = shuangpinInputController.isShuangpinMode()
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
                "shuangpin" -> {
                    shuangpinInputController.toggleShuangpinMode()
                    if (!shuangpinInputController.isShuangpinMode()) {
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
            // Order is based on enabledMethods list: pinyin, shuangpin, wubi, zhenma
            val currentMethod = when {
                isPinyinActive -> "pinyin"
                isShuangpinActive -> "shuangpin"
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
                "shuangpin" -> {
                    shuangpinInputController.setShuangpinMode(false)
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
                    "shuangpin" -> shuangpinInputController.setShuangpinMode(true)
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
     * Checks if any Chinese input mode is active (Pinyin, Shuangpin, Wubi, or Zhenma).
     */
    private fun isChineseInputModeActive(): Boolean {
        return pinyinInputController.isPinyinMode() || shuangpinInputController.isShuangpinMode() || wubiInputController.isWubiMode() || zhenmaInputController.isZhenmaMode()
    }

    /**
     * Gets the current input mode as a string ("english", "pinyin", "shuangpin", "wubi", or "zhenma").
     */
    private fun getCurrentInputMode(): String {
        return when {
            pinyinInputController.isPinyinMode() -> "pinyin"
            shuangpinInputController.isShuangpinMode() -> "shuangpin"
            wubiInputController.isWubiMode() -> "wubi"
            zhenmaInputController.isZhenmaMode() -> "zhenma"
            else -> "english"
        }
    }

    /**
     * Sets the input mode directly without cycling.
     * @param mode One of "english", "pinyin", "shuangpin", "wubi", or "zhenma"
     * @param saveToSettings If true, saves this mode as the last used mode
     */
    private fun setInputMode(mode: String, saveToSettings: Boolean = true) {
        // Deactivate all modes first
        if (pinyinInputController.isPinyinMode()) {
            pinyinInputController.setPinyinMode(false)
            currentInputConnection?.finishComposingText()
        }
        if (shuangpinInputController.isShuangpinMode()) {
            shuangpinInputController.setShuangpinMode(false)
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
            "shuangpin" -> {
                if (SettingsManager.getShuangpinEnabled(this)) {
                    shuangpinInputController.setShuangpinMode(true)
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
        val isWubiMode = wubiInputController.isWubiMode()
        val isZhenmaMode = zhenmaInputController.isZhenmaMode()

        if (isPinyinMode) {
            pinyinInputController.togglePunctuationMode()
            // Sync other controllers to match
            val newMode = pinyinInputController.isChinesePunctuationMode()
            shuangpinInputController.setChinesePunctuationMode(newMode)
            wubiInputController.setChinesePunctuationMode(newMode)
            zhenmaInputController.setChinesePunctuationMode(newMode)
        } else if (isShuangpinMode) {
            shuangpinInputController.togglePunctuationMode()
            // Sync other controllers to match
            val newMode = shuangpinInputController.isChinesePunctuationMode()
            pinyinInputController.setChinesePunctuationMode(newMode)
            wubiInputController.setChinesePunctuationMode(newMode)
            zhenmaInputController.setChinesePunctuationMode(newMode)
        } else if (isWubiMode) {
            wubiInputController.togglePunctuationMode()
            // Sync other controllers to match
            val newMode = wubiInputController.isChinesePunctuationMode()
            pinyinInputController.setChinesePunctuationMode(newMode)
            shuangpinInputController.setChinesePunctuationMode(newMode)
            zhenmaInputController.setChinesePunctuationMode(newMode)
        } else if (isZhenmaMode) {
            zhenmaInputController.togglePunctuationMode()
            // Sync other controllers to match
            val newMode = zhenmaInputController.isChinesePunctuationMode()
            pinyinInputController.setChinesePunctuationMode(newMode)
            shuangpinInputController.setChinesePunctuationMode(newMode)
            wubiInputController.setChinesePunctuationMode(newMode)
        }
        updateStatusBarText()
    }

    /**
     * Returns whether Chinese punctuation mode is currently active.
     */
    private fun isChinesePunctuationModeActive(): Boolean {
        return if (pinyinInputController.isPinyinMode()) {
            pinyinInputController.isChinesePunctuationMode()
        } else if (shuangpinInputController.isShuangpinMode()) {
            shuangpinInputController.isChinesePunctuationMode()
        } else if (wubiInputController.isWubiMode()) {
            wubiInputController.isChinesePunctuationMode()
        } else if (zhenmaInputController.isZhenmaMode()) {
            zhenmaInputController.isChinesePunctuationMode()
        } else {
            true // Default to Chinese punctuation
        }
    }

    /**
     * Aggiorna la status bar delegando al controller dedicato.
     */
    private fun updateStatusBarText() {
        // Check if Pinyin, Shuangpin, Wubi, or Zhenma mode is active and use their candidates
        val pinyinSnapshot = pinyinInputController.getSnapshot()
        val shuangpinSnapshot = shuangpinInputController.getSnapshot()
        val wubiSnapshot = wubiInputController.getSnapshot()
        val zhenmaSnapshot = zhenmaInputController.getSnapshot()

        // Update English word prediction from cursor position
        englishWordPredictionController.updateFromCursor(currentInputConnection)
        val wordPredictionSnapshot = englishWordPredictionController.getSnapshot()

        // Determine which variations to show:
        // 1. Pinyin candidates (when Pinyin mode active)
        // 2. Shuangpin candidates (when Shuangpin mode active)
        // 3. Wubi candidates (when Wubi mode active)
        // 4. Zhenma candidates (when Zhenma mode active)
        // 5. Accent variations (when lastInsertedChar has variations)
        // 6. English word predictions (when typing and no accent variations)
        val variationSnapshot: VariationStateController.Snapshot
        var wordPredictionActive = false
        var wordPredictionPrefix = ""
        var currentPage = 0
        var totalPages = 1
        var hasNextPage = false
        var hasPrevPage = false

        if (pinyinSnapshot.isActive) {
            // Pinyin mode takes priority
            variationSnapshot = VariationStateController.Snapshot(
                isActive = true,
                lastInsertedChar = if (pinyinSnapshot.buffer.isNotEmpty()) pinyinSnapshot.buffer.last() else null,
                variations = pinyinSnapshot.candidates.take(9)
            )
            // Pagination info from Pinyin
            currentPage = pinyinSnapshot.currentPage
            totalPages = pinyinSnapshot.totalPages
            hasNextPage = pinyinSnapshot.hasNextPage
            hasPrevPage = pinyinSnapshot.hasPrevPage
        } else if (shuangpinSnapshot.isActive) {
            // Shuangpin mode
            variationSnapshot = VariationStateController.Snapshot(
                isActive = true,
                lastInsertedChar = if (shuangpinSnapshot.buffer.isNotEmpty()) shuangpinSnapshot.buffer.last() else null,
                variations = shuangpinSnapshot.candidates.take(9)
            )
            // Pagination info from Shuangpin
            currentPage = shuangpinSnapshot.currentPage
            totalPages = shuangpinSnapshot.totalPages
            hasNextPage = shuangpinSnapshot.hasNextPage
            hasPrevPage = shuangpinSnapshot.hasPrevPage
        } else if (wubiSnapshot.isActive) {
            // Wubi mode
            variationSnapshot = VariationStateController.Snapshot(
                isActive = true,
                lastInsertedChar = if (wubiSnapshot.buffer.isNotEmpty()) wubiSnapshot.buffer.last() else null,
                variations = wubiSnapshot.candidates.take(9)
            )
            // Pagination info from Wubi
            currentPage = wubiSnapshot.currentPage
            totalPages = wubiSnapshot.totalPages
            hasNextPage = wubiSnapshot.hasNextPage
            hasPrevPage = wubiSnapshot.hasPrevPage
        } else if (zhenmaSnapshot.isActive) {
            // Zhenma mode
            variationSnapshot = VariationStateController.Snapshot(
                isActive = true,
                lastInsertedChar = if (zhenmaSnapshot.buffer.isNotEmpty()) zhenmaSnapshot.buffer.last() else null,
                variations = zhenmaSnapshot.candidates.take(9)
            )
            // Pagination info from Zhenma
            currentPage = zhenmaSnapshot.currentPage
            totalPages = zhenmaSnapshot.totalPages
            hasNextPage = zhenmaSnapshot.hasNextPage
            hasPrevPage = zhenmaSnapshot.hasPrevPage
        } else if (wordPredictionSnapshot.hasSuggestions && !shouldDisableSmartFeatures) {
            // Show English word predictions (BlackBerry-style: 3 per page)
            variationSnapshot = VariationStateController.Snapshot(
                isActive = true,
                lastInsertedChar = null,
                variations = wordPredictionSnapshot.suggestions.take(3)
            )
            wordPredictionActive = true
            wordPredictionPrefix = wordPredictionSnapshot.prefix
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
            shuangpinModeActive = shuangpinSnapshot.isActive,
            shuangpinBuffer = shuangpinSnapshot.buffer,
            wubiModeActive = wubiSnapshot.isActive,
            wubiBuffer = wubiSnapshot.buffer,
            zhenmaModeActive = zhenmaSnapshot.isActive,
            zhenmaBuffer = zhenmaSnapshot.buffer,
            wordPredictionActive = wordPredictionActive,
            wordPredictionPrefix = wordPredictionPrefix,
            currentPage = currentPage,
            totalPages = totalPages,
            hasNextPage = hasNextPage,
            hasPrevPage = hasPrevPage,
            chinesePunctuationMode = isChinesePunctuationModeActive()
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
                            shuangpinSnapshot.hasCandidates ||
                            wubiSnapshot.hasCandidates ||
                            zhenmaSnapshot.hasCandidates
        if (compactModeEnabled) {
            val shouldHide = !hasSuggestions
            candidatesBarController.setCompactModeHidden(shouldHide)
            isCompactModeHidden = shouldHide
        } else {
            isCompactModeHidden = false
            candidatesBarController.setCompactModeHidden(false)
        }

        candidatesBarController.updateStatusBars(snapshot, emojiMapText, inputConnection, symMappings)
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
        wubiInputController.setNextWordPredictionEnabled(chineseNextWordPredictionEnabled)

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
        updateStatusBarText()
    }
    
    override fun onWindowHidden() {
        super.onWindowHidden()
        resetModifierStates(preserveNavMode = true)
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

        // Check if we have an editable field at the very start
        val info = currentInputEditorInfo
        val initialInputConnection = currentInputConnection
        val inputType = info?.inputType ?: EditorInfo.TYPE_NULL
        val hasEditableField = initialInputConnection != null && inputType != EditorInfo.TYPE_NULL
        if (hasEditableField && !isInputViewActive) {
            isInputViewActive = true
        }

        // Check if this is a modifier key (using device-specific checks)
        val isModifierKey = isDeviceModifierKey(keyCode)
        if (!isModifierKey) {
            modifierStateController.registerNonModifierKey()
        }

        // Track Alt/Shift double press for pagination
        // Only use pagination behavior when there are candidates to paginate
        // Otherwise, allow normal Alt/Shift locking behavior
        val isPinyinMode = pinyinInputController.isPinyinMode()
        val isShuangpinMode = shuangpinInputController.isShuangpinMode()
        val isWubiMode = wubiInputController.isWubiMode()
        val isZhenmaMode = zhenmaInputController.isZhenmaMode()
        val isWordPredictionActive = englishWordPredictionController.hasActivePrediction()

        // Check if we have candidates to paginate (buffer not empty or has candidates)
        val hasPinyinCandidates = isPinyinMode && pinyinInputController.hasCandidates()
        val hasShuangpinCandidates = isShuangpinMode && shuangpinInputController.hasCandidates()
        val hasWubiCandidates = isWubiMode && wubiInputController.hasCandidates()
        val hasZhenmaCandidates = isZhenmaMode && zhenmaInputController.hasCandidates()
        val hasWordPredictions = isWordPredictionActive && englishWordPredictionController.hasSuggestions()
        val hasCandidatesToPaginate = hasPinyinCandidates || hasShuangpinCandidates || hasWubiCandidates || hasZhenmaCandidates || hasWordPredictions

        if (hasCandidatesToPaginate) {
            when {
                isDeviceAltKey(keyCode) -> {
                    if (event?.repeatCount == 0) {
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastPress = currentTime - altLastPressTime

                        if (timeSinceLastPress <= PAGINATION_DOUBLE_PRESS_THRESHOLD) {
                            // Double press detected - go to next page
                            when {
                                isPinyinMode -> {
                                    if (pinyinInputController.hasNextPage()) {
                                        pinyinInputController.nextPage()
                                        updateStatusBarText()
                                    }
                                }
                                isShuangpinMode -> {
                                    if (shuangpinInputController.hasNextPage()) {
                                        shuangpinInputController.nextPage()
                                        updateStatusBarText()
                                    }
                                }
                                isWubiMode -> {
                                    if (wubiInputController.hasNextPage()) {
                                        wubiInputController.nextPage()
                                        updateStatusBarText()
                                    }
                                }
                                isZhenmaMode -> {
                                    if (zhenmaInputController.hasNextPage()) {
                                        zhenmaInputController.nextPage()
                                        updateStatusBarText()
                                    }
                                }
                                else -> {
                                    if (englishWordPredictionController.hasNextPage()) {
                                        englishWordPredictionController.nextPage()
                                        updateStatusBarText()
                                    }
                                }
                            }
                            altLastPressTime = 0L // Reset to prevent triple press
                        } else {
                            altLastPressTime = currentTime
                        }
                    }
                }
                isDeviceShiftKey(keyCode) -> {
                    if (event?.repeatCount == 0) {
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastPress = currentTime - shiftLastPressTime

                        if (timeSinceLastPress <= PAGINATION_DOUBLE_PRESS_THRESHOLD) {
                            // Double press detected - go to previous page
                            when {
                                isPinyinMode -> {
                                    if (pinyinInputController.hasPrevPage()) {
                                        pinyinInputController.prevPage()
                                        updateStatusBarText()
                                    }
                                }
                                isShuangpinMode -> {
                                    if (shuangpinInputController.hasPrevPage()) {
                                        shuangpinInputController.prevPage()
                                        updateStatusBarText()
                                    }
                                }
                                isWubiMode -> {
                                    if (wubiInputController.hasPrevPage()) {
                                        wubiInputController.prevPage()
                                        updateStatusBarText()
                                    }
                                }
                                isZhenmaMode -> {
                                    if (zhenmaInputController.hasPrevPage()) {
                                        zhenmaInputController.prevPage()
                                        updateStatusBarText()
                                    }
                                }
                                else -> {
                                    if (englishWordPredictionController.hasPrevPage()) {
                                        englishWordPredictionController.prevPage()
                                        updateStatusBarText()
                                    }
                                }
                            }
                            shiftLastPressTime = 0L // Reset to prevent triple press
                        } else {
                            shiftLastPressTime = currentTime
                        }
                    }
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
            // Toggle Chinese input mode (Pinyin or Wubi) with Shift+Enter
            if (shiftPressed && !ctrlPressed && !altPressed) {
                toggleChineseInputMode()
                return true
            }

            // Commit Pinyin buffer as-is (without conversion) with plain Enter
            // Don't add space - user is typing English in Chinese mode
            if (pinyinInputController.isPinyinMode() && !shiftPressed && !ctrlPressed && !altPressed) {
                val buffer = pinyinInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    val committed = pinyinInputController.commitBufferAsIs()
                    if (committed != null && ic != null) {
                        ic.commitText(committed, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Commit Shuangpin buffer as-is (without conversion) with plain Enter
            // Don't add space - user is typing English in Chinese mode
            if (shuangpinInputController.isShuangpinMode() && !shiftPressed && !ctrlPressed && !altPressed) {
                val buffer = shuangpinInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    val committed = shuangpinInputController.commitBufferAsIs()
                    if (committed != null && ic != null) {
                        ic.commitText(committed, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Commit Wubi buffer as-is (without conversion) with plain Enter
            // Don't add space - user is typing English in Chinese mode
            if (wubiInputController.isWubiMode() && !shiftPressed && !ctrlPressed && !altPressed) {
                val buffer = wubiInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    val committed = wubiInputController.commitBufferAsIs()
                    if (committed != null && ic != null) {
                        ic.commitText(committed, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }

            // Commit Zhenma buffer as-is (without conversion) with plain Enter
            // Don't add space - user is typing English in Chinese mode
            if (zhenmaInputController.isZhenmaMode() && !shiftPressed && !ctrlPressed && !altPressed) {
                val buffer = zhenmaInputController.getBuffer()
                if (buffer.isNotEmpty()) {
                    val committed = zhenmaInputController.commitBufferAsIs()
                    if (committed != null && ic != null) {
                        ic.commitText(committed, 1)
                        updateStatusBarText()
                        return true
                    }
                }
            }
        }

        // Handle English word prediction (when NOT in Chinese input mode)
        if (!isChineseInputModeActive() && ic != null) {
            // Update suggestions from current cursor position
            englishWordPredictionController.updateFromCursor(ic)

            // Handle backspace to clear next-word predictions
            if (keyCode == KeyEvent.KEYCODE_DEL && englishWordPredictionController.isShowingNextWordPredictions()) {
                englishWordPredictionController.onBackspaceInput()
                updateStatusBarText()
                // Don't return true - let the backspace delete character as normal
            }

            if (englishWordPredictionController.hasSuggestions()) {
                // Alt+letter keys select suggestion - mapping depends on device type
                // (determined by alt_key_mappings.json for each device)
                val number = if (altPressed && !ctrlPressed && !shiftPressed) {
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
                    updateStatusBar = { updateStatusBarText() }
                )
                when (symResult) {
                    SymLayoutController.SymKeyResult.CONSUME -> return true
                    SymLayoutController.SymKeyResult.CALL_SUPER -> return super.onKeyDown(keyCode, event)
                    SymLayoutController.SymKeyResult.NOT_HANDLED -> { /* Continue to Pinyin handling */ }
                }
            }

            // FIRST: Handle Alt modifier - when Alt is active (latched, one-shot, or pressed), input alternate characters
            if (event != null && (altLatchActive || altOneShot || altPressed)) {
                // Get the character with Alt modifier applied
                val altChar = event.getUnicodeChar(KeyEvent.META_ALT_ON)
                // Only proceed if we get a valid alternate character that's different from the normal one
                if (altChar != 0 && altChar != event.unicodeChar) {
                    // Commit any existing buffer first
                    val buffer = pinyinInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = pinyinInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                        }
                    }

                    // Convert punctuation to Chinese if applicable (only when Chinese punctuation mode is enabled)
                    val char = altChar.toChar()
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

                    // Only clear Alt state if it's one-shot mode, keep it if latched (double-click locked)
                    if (altOneShot && !altLatchActive) {
                        modifierStateController.clearAltState(resetPressedState = false)
                    }

                    updateStatusBarText()
                    return true
                }
            }

            // Handle number keys 1-9 for candidate selection FIRST (before other handlers)
            // This allows number key selection even when Alt is required to type numbers
            if (pinyinInputController.hasCandidates()) {
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
                if (pinyinInputController.handleBackspace()) {
                    val buffer = pinyinInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        ic.setComposingText(buffer, 1)
                    } else {
                        // Buffer is now empty, delete the composing text entirely
                        ic.finishComposingText()
                        if (hadBuffer) {
                            // If we had a buffer before, delete the last character
                            ic.deleteSurroundingText(1, 0)
                        }
                    }
                    updateStatusBarText()
                    return true
                }
                // If buffer was empty, fall through to normal backspace handling
            }

            // Handle space key - select first candidate
            if (keyCode == KeyEvent.KEYCODE_SPACE && pinyinInputController.hasCandidates()) {
                val selected = pinyinInputController.selectFirstCandidate()
                if (selected != null) {
                    ic.commitText(selected, 1) // commitText replaces composing text automatically

                    // Set remaining buffer as new composing text
                    val remainingBuffer = pinyinInputController.getBuffer()
                    if (remainingBuffer.isNotEmpty()) {
                        ic.setComposingText(remainingBuffer, 1)
                    }

                    updateStatusBarText()
                    return true
                }
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

                    // Otherwise, add to pinyin buffer (lowercase)
                    if (pinyinInputController.handleLetterKey(char)) {
                        val buffer = pinyinInputController.getBuffer()
                        ic.setComposingText(buffer, 1)
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
                    updateStatusBar = { updateStatusBarText() }
                )
                when (symResult) {
                    SymLayoutController.SymKeyResult.CONSUME -> return true
                    SymLayoutController.SymKeyResult.CALL_SUPER -> return super.onKeyDown(keyCode, event)
                    SymLayoutController.SymKeyResult.NOT_HANDLED -> { /* Continue to Shuangpin handling */ }
                }
            }

            // Handle Alt modifier - when Alt is active (latched, one-shot, or pressed), input alternate characters
            if (event != null && (altLatchActive || altOneShot || altPressed)) {
                val altChar = event.getUnicodeChar(KeyEvent.META_ALT_ON)
                if (altChar != 0 && altChar != event.unicodeChar) {
                    // Commit any existing buffer first
                    val buffer = shuangpinInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = shuangpinInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                        }
                    }

                    // Convert punctuation to Chinese if applicable
                    val char = altChar.toChar()
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

                    if (altOneShot && !altLatchActive) {
                        modifierStateController.clearAltState(resetPressedState = false)
                    }

                    updateStatusBarText()
                    return true
                }
            }

            // Handle number keys 1-9 for candidate selection
            if (shuangpinInputController.hasCandidates()) {
                if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                    val selected = shuangpinInputController.handleNumberKey(keyCode)
                    if (selected != null) {
                        ic.commitText(selected, 1)

                        val remainingBuffer = shuangpinInputController.getBuffer()
                        if (remainingBuffer.isNotEmpty()) {
                            ic.setComposingText(remainingBuffer, 1)
                        }

                        modifierStateController.clearAltState(resetPressedState = true)
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
                if (shuangpinInputController.handleBackspace()) {
                    val buffer = shuangpinInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        ic.setComposingText(buffer, 1)
                    } else {
                        ic.finishComposingText()
                        if (hadBuffer) {
                            ic.deleteSurroundingText(1, 0)
                        }
                    }
                    updateStatusBarText()
                    return true
                }
            }

            // Handle space key - select first candidate
            if (keyCode == KeyEvent.KEYCODE_SPACE && shuangpinInputController.hasCandidates()) {
                val selected = shuangpinInputController.selectFirstCandidate()
                if (selected != null) {
                    ic.commitText(selected, 1)

                    val remainingBuffer = shuangpinInputController.getBuffer()
                    if (remainingBuffer.isNotEmpty()) {
                        ic.setComposingText(remainingBuffer, 1)
                    }

                    updateStatusBarText()
                    return true
                }
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

                    // Add to Shuangpin buffer (lowercase)
                    if (shuangpinInputController.handleLetterKey(char)) {
                        val buffer = shuangpinInputController.getBuffer()
                        ic.setComposingText(buffer, 1)
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
                    updateStatusBar = { updateStatusBarText() }
                )
                when (symResult) {
                    SymLayoutController.SymKeyResult.CONSUME -> return true
                    SymLayoutController.SymKeyResult.CALL_SUPER -> return super.onKeyDown(keyCode, event)
                    SymLayoutController.SymKeyResult.NOT_HANDLED -> { /* Continue to Wubi handling */ }
                }
            }

            // Handle Alt modifier - when Alt is active (latched, one-shot, or pressed), input alternate characters
            if (event != null && (altLatchActive || altOneShot || altPressed)) {
                // Get the character with Alt modifier applied
                val altChar = event.getUnicodeChar(KeyEvent.META_ALT_ON)
                // Only proceed if we get a valid alternate character that's different from the normal one
                if (altChar != 0 && altChar != event.unicodeChar) {
                    // Commit any existing buffer first
                    val buffer = wubiInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = wubiInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                        }
                    }

                    // Convert punctuation to Chinese if applicable (only when Chinese punctuation mode is enabled)
                    val char = altChar.toChar()
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

                    // Only clear Alt state if it's one-shot mode, keep it if latched (double-click locked)
                    if (altOneShot && !altLatchActive) {
                        modifierStateController.clearAltState(resetPressedState = false)
                    }

                    updateStatusBarText()
                    return true
                }
            }

            // Handle number keys 1-9 for candidate selection
            if (wubiInputController.hasCandidates()) {
                if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                    val selected = wubiInputController.handleNumberKey(keyCode)
                    if (selected != null) {
                        ic.commitText(selected, 1)
                        // Clear Alt modifier (including latch state from double-tap)
                        modifierStateController.clearAltState(resetPressedState = true)
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
                if (wubiInputController.handleBackspace()) {
                    val buffer = wubiInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        ic.setComposingText(buffer, 1)
                    } else {
                        // Buffer is now empty, delete the composing text entirely
                        ic.finishComposingText()
                        if (hadBuffer) {
                            // If we had a buffer before, delete the last character
                            ic.deleteSurroundingText(1, 0)
                        }
                    }
                    updateStatusBarText()
                    return true
                }
                // If buffer was empty, fall through to normal backspace handling
            }

            // Handle space key - select first candidate
            if (keyCode == KeyEvent.KEYCODE_SPACE && wubiInputController.hasCandidates()) {
                val selected = wubiInputController.selectFirstCandidate()
                if (selected != null) {
                    ic.commitText(selected, 1)
                    updateStatusBarText()
                    return true
                }
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

                    // Add letter to Wubi buffer
                    if (wubiInputController.handleLetterKey(char)) {
                        val buffer = wubiInputController.getBuffer()
                        ic.setComposingText(buffer, 1)
                        updateStatusBarText()
                        return true
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
                    updateStatusBar = { updateStatusBarText() }
                )
                when (symResult) {
                    SymLayoutController.SymKeyResult.CONSUME -> return true
                    SymLayoutController.SymKeyResult.CALL_SUPER -> return super.onKeyDown(keyCode, event)
                    SymLayoutController.SymKeyResult.NOT_HANDLED -> { /* Continue to Zhenma handling */ }
                }
            }

            // Handle Alt modifier - when Alt is active (latched, one-shot, or pressed), input alternate characters
            if (event != null && (altLatchActive || altOneShot || altPressed)) {
                // Get the character with Alt modifier applied
                val altChar = event.getUnicodeChar(KeyEvent.META_ALT_ON)
                // Only proceed if we get a valid alternate character that's different from the normal one
                if (altChar != 0 && altChar != event.unicodeChar) {
                    // Commit any existing buffer first
                    val buffer = zhenmaInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        val committed = zhenmaInputController.commitBufferAsIs()
                        if (committed != null) {
                            ic.commitText(committed, 1)
                        }
                    }

                    // Convert punctuation to Chinese if applicable (only when Chinese punctuation mode is enabled)
                    val char = altChar.toChar()
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

                    // Only clear Alt state if it's one-shot mode, keep it if latched (double-click locked)
                    if (altOneShot && !altLatchActive) {
                        modifierStateController.clearAltState(resetPressedState = false)
                    }

                    updateStatusBarText()
                    return true
                }
            }

            // Handle number keys 1-9 for candidate selection
            if (zhenmaInputController.hasCandidates()) {
                if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                    val selected = zhenmaInputController.handleNumberKey(keyCode)
                    if (selected != null) {
                        ic.commitText(selected, 1)
                        // Clear Alt modifier (including latch state from double-tap)
                        modifierStateController.clearAltState(resetPressedState = true)
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
                if (zhenmaInputController.handleBackspace()) {
                    val buffer = zhenmaInputController.getBuffer()
                    if (buffer.isNotEmpty()) {
                        ic.setComposingText(buffer, 1)
                    } else {
                        // Buffer is now empty, delete the composing text entirely
                        ic.finishComposingText()
                        if (hadBuffer) {
                            // If we had a buffer before, delete the last character
                            ic.deleteSurroundingText(1, 0)
                        }
                    }
                    updateStatusBarText()
                    return true
                }
                // If buffer was empty, fall through to normal backspace handling
            }

            // Handle space key - select first candidate
            if (keyCode == KeyEvent.KEYCODE_SPACE && zhenmaInputController.hasCandidates()) {
                val selected = zhenmaInputController.selectFirstCandidate()
                if (selected != null) {
                    ic.commitText(selected, 1)
                    updateStatusBarText()
                    return true
                }
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
                cursorUpdateDelayMs = CURSOR_UPDATE_DELAY
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
                isLongPressSuppressed = { code -> multiTapController.isLongPressSuppressed(code) }
            )
        )

        return when (routingDecision) {
            InputEventRouter.EditableFieldRoutingResult.Consume -> true
            InputEventRouter.EditableFieldRoutingResult.CallSuper -> super.onKeyDown(keyCode, event)
            InputEventRouter.EditableFieldRoutingResult.Continue -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
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
            if (altPressed) {
                val result = modifierStateController.handleAltKeyUp(translatedKeyCode)
                if (result.shouldUpdateStatusBar) {
                    updateStatusBarText()
                }
            }
            return super.onKeyUp(keyCode, event)
        }
        
        // Handle SYM key release (nothing to do; it is a toggle)
        if (keyCode == KEYCODE_SYM) {
            // Consumiamo l'evento
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
