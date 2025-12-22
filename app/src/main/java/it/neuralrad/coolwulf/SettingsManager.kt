package it.neuralrad.coolwulf

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.KeyEvent
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages the app settings.
 * Centralizes access to SharedPreferences for Pastiera settings.
 */
object SettingsManager {
    private const val TAG = "SettingsManager"
    private const val PREFS_NAME = "pastiera_prefs"
    
    // Settings keys
    private const val KEY_LONG_PRESS_THRESHOLD = "long_press_threshold"
    private const val KEY_AUTO_CAPITALIZE_FIRST_LETTER = "auto_capitalize_first_letter"
    private const val KEY_DOUBLE_SPACE_TO_PERIOD = "double_space_to_period"
    private const val KEY_SWIPE_TO_DELETE = "swipe_to_delete"
    private const val KEY_AUTO_SHOW_KEYBOARD = "auto_show_keyboard"
    private const val KEY_CLEAR_ALT_ON_SPACE = "clear_alt_on_space"
    private const val KEY_ALT_CTRL_SPEECH_SHORTCUT = "alt_ctrl_speech_shortcut"
    private const val KEY_SYM_MAPPINGS_CUSTOM = "sym_mappings_custom"
    private const val KEY_SYM_MAPPINGS_PAGE2_CUSTOM = "sym_mappings_page2_custom"
    private const val KEY_SYM_MAPPINGS_PAGE3_CUSTOM = "sym_mappings_page3_custom"
    private const val KEY_AUTO_CORRECT_ENABLED = "auto_correct_enabled"
    private const val KEY_AUTO_CORRECT_ENABLED_LANGUAGES = "auto_correct_enabled_languages"
    private const val KEY_AUTO_CAPITALIZE_AFTER_PERIOD = "auto_capitalize_after_period"
    private const val KEY_LONG_PRESS_MODIFIER = "long_press_modifier" // "alt" or "shift"
    private const val KEY_KEYBOARD_LAYOUT = "keyboard_layout" // "qwerty", "azerty", etc.
    private const val KEY_RESTORE_SYM_PAGE = "restore_sym_page" // SYM page to restore when returning from settings
    private const val KEY_PENDING_RESTORE_SYM_PAGE = "pending_restore_sym_page" // Temporary SYM page state saved when opening settings
    private const val KEY_SYM_PAGES_CONFIG = "sym_pages_config" // Order/enabled pages for SYM
    private const val KEY_SYM_AUTO_CLOSE = "sym_auto_close" // Auto-close SYM layout after key press
    private const val KEY_DISMISSED_RELEASES = "dismissed_releases" // Set of release tag_names that were dismissed
    private const val KEY_PINYIN_ENABLED = "pinyin_enabled" // Enable Pinyin input
    private const val KEY_T9_PINYIN_ENABLED = "t9_pinyin_enabled" // Enable T9 (九宫格) Pinyin input
    private const val KEY_WUBI_ENABLED = "wubi_enabled" // Enable Wubi input
    private const val KEY_SHUANGPIN_ENABLED = "shuangpin_enabled" // Enable Shuangpin (双拼) input
    private const val KEY_ZHENMA_ENABLED = "zhenma_enabled" // Enable Zhenma (真码) input
    private const val KEY_ZIRANMA_ENABLED = "ziranma_enabled" // Enable Ziranma (自然码) input
    private const val KEY_PINYIN_CHARACTER_SET = "pinyin_character_set" // "simplified" or "traditional"
    private const val KEY_PINYIN_FUZZY_ENABLED = "pinyin_fuzzy_enabled" // Enable fuzzy pinyin (模糊音)
    private const val KEY_CHINESE_INPUT_METHOD = "chinese_input_method" // "pinyin", "wubi", or "shuangpin" (legacy, used for last active mode)
    private const val KEY_DEFAULT_INPUT_MODE = "default_input_mode" // "english", "pinyin", "shuangpin", or "wubi"
    private const val KEY_LAST_INPUT_MODE = "last_input_mode" // Remember last used mode before app restart
    private const val KEY_CHINESE_NEXT_WORD_PREDICTION = "chinese_next_word_prediction" // Enable next word prediction for Chinese input
    private const val KEY_COMPACT_MODE = "compact_mode" // Compact mode - auto-hide status bar when no suggestions
    private const val KEY_POWER_SHORTCUTS_ENABLED = "power_shortcuts_enabled" // Enable power shortcuts
    private const val KEY_SWIPE_INCREMENTAL_THRESHOLD = "swipe_incremental_threshold" // Swipe incremental threshold
    private const val KEY_STATIC_VARIATION_BAR_MODE = "static_variation_bar_mode" // Static variation bar mode
    private const val KEY_TUTORIAL_COMPLETED = "tutorial_completed" // Tutorial completion status
    private const val KEY_DEVICE_TYPE = "device_type" // Device type: "titan2" or "blackberry"
    private const val KEY_APP_LANGUAGE = "app_language" // App interface language: "system", "en", "zh"
    private const val KEY_SHOW_VOICE_INPUT_BUTTON = "show_voice_input_button" // Show voice input button in status bar
    private const val KEY_CLIPBOARD_HISTORY_ENABLED = "clipboard_history_enabled" // Enable clipboard history
    private const val KEY_SHOW_CLIPBOARD_BUTTON = "show_clipboard_button" // Show clipboard button in status bar
    private const val KEY_JUYING_MODE_ENABLED = "juying_mode_enabled" // Enable Juying (巨硬) mode - 5 keys for candidate selection
    private const val KEY_JUYING_FIXED_POSITIONS = "juying_fixed_positions" // Fixed suggestion positions with reserved arrow spaces
    private const val KEY_JUYING_SOUND_ENABLED = "juying_sound_enabled" // Enable sound when Juying keys select candidates
    private const val KEY_JUYING_DYNAMIC_CANDIDATE_COUNT = "juying_dynamic_candidate_count" // Dynamically adjust candidate count based on phrase length
    private const val KEY_TOUCHPAD_PAGE_ENABLED = "touchpad_page_enabled" // Enable touchpad swipe up/down for candidate page navigation
    private const val KEY_ALT_DOUBLE_CLICK_DELAY = "alt_double_click_delay" // Delay in ms for Alt double-click next page detection
    private const val KEY_JUYING_KEY_1 = "juying_key_1" // First Juying key (default: Shift)
    private const val KEY_JUYING_KEY_2 = "juying_key_2" // Second Juying key (default: Sym)
    private const val KEY_JUYING_KEY_3 = "juying_key_3" // Third Juying key (default: Space)
    private const val KEY_JUYING_KEY_4 = "juying_key_4" // Fourth Juying key (default: Fn)
    private const val KEY_JUYING_KEY_5 = "juying_key_5" // Fifth Juying key (default: Alt)
    private const val KEY_MEMORY_FUNCTION_ENABLED = "memory_function_enabled" // Enable memory function to reorder candidates by selection frequency
    private const val KEY_SHIFT_ALT_SWAPPED = "shift_alt_swapped" // Titan 2: Shift and Alt buttons are swapped (Alt is 1st, Shift is 5th)
    private const val KEY_MAX_CANDIDATES_NON_JUYING = "max_candidates_non_juying" // Maximum number of candidates to display in non-Juying mode
    private const val KEY_SHIFT_ENTER_TOGGLE_INPUT = "shift_enter_toggle_input" // Enable Shift+Enter to toggle between Chinese input methods
    private const val KEY_VIRTUAL_KEYBOARD_ENABLED = "virtual_keyboard_enabled" // Enable virtual (on-screen) keyboard
    private const val KEY_AUTO_PHRASE_MEMORY_ENABLED = "auto_phrase_memory_enabled" // Enable auto-learning of new phrases
    private const val KEY_WUBI_WITH_PINYIN_ENABLED = "wubi_with_pinyin_enabled" // Enable Pinyin fallback in Wubi mode
    private const val KEY_WUBI_PHRASES_FIRST = "wubi_phrases_first" // Wubi: show phrases before single characters
    private const val KEY_WUBI_AUTO_COMMIT_SINGLE = "wubi_auto_commit_single" // Wubi: auto-commit when 4-char code has only 1 candidate
    private const val KEY_WUBI_AUTO_COMMIT_OVERFLOW = "wubi_auto_commit_overflow" // Wubi: auto-commit on 5th letter and start new word
    private const val KEY_WUBI_Z_KEY_MODE = "wubi_z_key_mode" // Wubi: Z key function mode: "disabled", "wildcard", "symbol"
    private const val KEY_CANDIDATE_FONT_SIZE = "candidate_font_size" // Font size for candidates/suggestions
    private const val KEY_SUGGESTION_MIN_FONT_SIZE = "suggestion_min_font_size" // Minimum font size for suggestions (for long text wrapping)
    private const val KEY_AUTO_ADJUST_STATUS_BAR_HEIGHT = "auto_adjust_status_bar_height" // Auto-adjust status bar height for long suggestions
    private const val KEY_PARTIAL_PINYIN_MATCHING = "partial_pinyin_matching" // Enable partial pinyin matching for phrases
    private const val KEY_ABBREVIATION_INPUT_ENABLED = "abbreviation_input_enabled" // Enable 首字母 (first letter abbreviation) input
    private const val KEY_SHOW_VIRTUAL_KEYBOARD_BUTTON = "show_virtual_keyboard_button" // Show virtual keyboard toggle button in status bar
    private const val KEY_SEMI_TRANSPARENT_STATUS_BAR = "semi_transparent_status_bar" // Make status bar semi-transparent
    private const val KEY_3D_EFFECT_ENABLED = "3d_effect_enabled" // Enable 3D shadow effect for status bar buttons
    private const val KEY_OFFLINE_VOICE_INPUT = "offline_voice_input" // Use Sherpa-ONNX for offline Mandarin Chinese speech recognition
    private const val KEY_SHERPA_MODEL_PATH = "sherpa_model_path" // Path to Sherpa-ONNX model zip file
    private const val KEY_NEURAL_PINYIN_MODEL_PATH = "neural_pinyin_model_path" // Path to Neural Pinyin ONNX model zip file
    private const val KEY_NEURAL_PINYIN_ENABLED = "neural_pinyin_enabled" // Enable neural network for long pinyin sentences
    private const val KEY_NEURAL_PINYIN_MIN_LETTERS = "neural_pinyin_min_letters" // Minimum letters to trigger neural pinyin (default 6)
    private const val KEY_NEURAL_PINYIN_PRIORITY = "neural_pinyin_priority" // Put neural prediction as top suggestion (default false)
    private const val KEY_NEURAL_PINYIN_COUNT = "neural_pinyin_count" // Number of neural predictions to show (1 or 3, default 1)
    private const val KEY_HMM_MODEL_SIZE = "hmm_model_size" // HMM model size: "small" (1.9MB), "standard" (2.3MB), "large" (3.2MB)
    private const val KEY_VOICE_AUTO_INSERT = "voice_auto_insert" // Auto-insert recognized text after silence
    private const val KEY_VOICE_CHINESE_PUNCTUATION = "voice_chinese_punctuation" // Use Chinese punctuation (。,) for voice input
    private const val KEY_VOICE_ADD_PUNCTUATION = "voice_add_punctuation" // Add punctuation to voice input text
    private const val KEY_HOLD_SPACE_FOR_VOICE = "hold_space_for_voice" // Hold space key to trigger voice input
    private const val KEY_HOLD_SPACE_DURATION = "hold_space_duration" // Duration to hold space key for voice input (ms)
    private const val KEY_STATUS_BAR_HEIGHT = "status_bar_height" // Height of status bar / suggestion bar in DIP
    private const val KEY_SUGGESTION_HEIGHT_PERCENT = "suggestion_height_percent" // Height of suggestion word background as percentage of status bar (50-100)
    private const val KEY_VIRTUAL_KEYBOARD_HEIGHT = "virtual_keyboard_height" // Height of virtual keyboard keys in DIP
    private const val KEY_KEYBOARD_SOUND = "virtual_keyboard_sound" // Enable sound effect for keyboard typing (both physical and virtual)
    private const val KEY_KEYBOARD_SOUND_TYPE = "keyboard_sound_type" // Sound type: "mechanical", "bucklespring", "video", "mario", "piano", "custom"
    private const val KEY_KEYBOARD_SOUND_VOLUME = "keyboard_sound_volume" // Sound volume: 0-100
    private const val KEY_CUSTOM_SOUND_PATH = "custom_sound_path" // Path to user's custom sound file
    private const val KEY_VIRTUAL_KEYBOARD_VIBRATION = "virtual_keyboard_vibration" // Enable vibration for virtual keyboard typing
    private const val KEY_SHOW_LED_STATUS = "show_led_status" // Show virtual LED status indicator strip
    private const val KEY_TRADITIONAL_CHINESE_TOGGLE_ENABLED = "traditional_chinese_toggle_enabled" // Show 简/繁 toggle button in status bar
    private const val KEY_SHOW_SYM_BUTTON = "show_sym_button" // Show SYM button in status bar
    private const val KEY_SHOW_SOUND_TOGGLE_BUTTON = "show_sound_toggle_button" // Show sound toggle button in status bar
    private const val KEY_JUYING_PUNCTUATION_BUTTONS = "juying_punctuation_buttons" // Show comma/period buttons on sides in Juying mode
    private const val KEY_JUYING_PUNCTUATION_LEFT = "juying_punctuation_left" // Left punctuation (Shift position)
    private const val KEY_JUYING_PUNCTUATION_RIGHT = "juying_punctuation_right" // Right punctuation (Alt position)
    private const val KEY_JUYING_PUNCTUATION_LEFT_SHIFTED = "juying_punctuation_left_shifted" // Left punctuation when Shift active
    private const val KEY_JUYING_PUNCTUATION_RIGHT_SHIFTED = "juying_punctuation_right_shifted" // Right punctuation when Shift active
    private const val KEY_STATUS_BAR_THEME = "status_bar_theme" // Status bar theme ID
    private const val KEY_DAY_NIGHT_THEME_ENABLED = "day_night_theme_enabled" // Enable automatic day/night theme switching
    private const val KEY_DAY_THEME = "day_theme" // Theme ID to use during daytime
    private const val KEY_NIGHT_THEME = "night_theme" // Theme ID to use during nighttime
    private const val KEY_DAY_START_HOUR = "day_start_hour" // Hour when daytime starts (default: 6)
    private const val KEY_NIGHT_START_HOUR = "night_start_hour" // Hour when nighttime starts (default: 18)

    // Custom theme color keys (slot 1 - default/legacy)
    private const val KEY_CUSTOM_THEME_BACKGROUND = "custom_theme_background"
    private const val KEY_CUSTOM_THEME_TEXT = "custom_theme_text"
    private const val KEY_CUSTOM_THEME_TEXT_SECONDARY = "custom_theme_text_secondary"
    private const val KEY_CUSTOM_THEME_ACCENT = "custom_theme_accent"
    private const val KEY_CUSTOM_THEME_BUTTON_BG = "custom_theme_button_bg"
    private const val KEY_CUSTOM_THEME_BUTTON_PRESSED = "custom_theme_button_pressed"
    private const val KEY_CUSTOM_THEME_CANDIDATE_BG = "custom_theme_candidate_bg"
    private const val KEY_CUSTOM_THEME_CANDIDATE_BEST_BG = "custom_theme_candidate_best_bg"
    private const val KEY_CUSTOM_THEME_CANDIDATE_TEXT = "custom_theme_candidate_text"
    private const val KEY_CUSTOM_THEME_CANDIDATE_BEST_TEXT = "custom_theme_candidate_best_text"
    private const val KEY_CUSTOM_THEME_ICON = "custom_theme_icon"
    private const val KEY_CUSTOM_THEME_ICON_INACTIVE = "custom_theme_icon_inactive"
    private const val KEY_CUSTOM_THEME_LED_ACTIVE = "custom_theme_led_active"
    private const val KEY_CUSTOM_THEME_LED_LOCKED = "custom_theme_led_locked"
    private const val KEY_CUSTOM_THEME_LED_INACTIVE = "custom_theme_led_inactive"

    // Custom theme slot names
    private const val KEY_CUSTOM_THEME_NAME_1 = "custom_theme_name_1"
    private const val KEY_CUSTOM_THEME_NAME_2 = "custom_theme_name_2"
    private const val KEY_CUSTOM_THEME_NAME_3 = "custom_theme_name_3"

    // Custom theme slot types (light/dark)
    private const val KEY_CUSTOM_THEME_TYPE_1 = "custom_theme_type_1"
    private const val KEY_CUSTOM_THEME_TYPE_2 = "custom_theme_type_2"
    private const val KEY_CUSTOM_THEME_TYPE_3 = "custom_theme_type_3"

    // Default values
    private const val DEFAULT_STATUS_BAR_THEME = "classic_dark"
    private const val DEFAULT_DAY_NIGHT_THEME_ENABLED = false
    private const val DEFAULT_DAY_THEME = "charcoal_gray" // Light-ish theme for day
    private const val DEFAULT_NIGHT_THEME = "classic_dark" // Dark theme for night
    private const val DEFAULT_DAY_START_HOUR = 6
    private const val DEFAULT_NIGHT_START_HOUR = 18
    private const val DEFAULT_LONG_PRESS_THRESHOLD = 300L
    private const val MIN_LONG_PRESS_THRESHOLD = 50L
    private const val MAX_LONG_PRESS_THRESHOLD = 1000L
    private const val DEFAULT_AUTO_CAPITALIZE_FIRST_LETTER = true
    private const val DEFAULT_DOUBLE_SPACE_TO_PERIOD = true
    private const val DEFAULT_SWIPE_TO_DELETE = false
    private const val DEFAULT_AUTO_SHOW_KEYBOARD = true
    private const val DEFAULT_CLEAR_ALT_ON_SPACE = false
    private const val DEFAULT_ALT_CTRL_SPEECH_SHORTCUT = true
    private const val DEFAULT_AUTO_CORRECT_ENABLED = true
    private const val DEFAULT_AUTO_CAPITALIZE_AFTER_PERIOD = true
    private const val DEFAULT_LONG_PRESS_MODIFIER = "alt"
    private const val DEFAULT_KEYBOARD_LAYOUT = "qwerty"
    private const val DEFAULT_SYM_AUTO_CLOSE = true
    private val DEFAULT_SYM_PAGES_CONFIG = SymPagesConfig()
    private const val DEFAULT_PINYIN_ENABLED = true
    private const val DEFAULT_T9_PINYIN_ENABLED = false  // T9 mode off by default
    private const val DEFAULT_WUBI_ENABLED = false
    private const val DEFAULT_SHUANGPIN_ENABLED = false
    private const val DEFAULT_ZHENMA_ENABLED = false
    private const val DEFAULT_ZIRANMA_ENABLED = false
    private const val DEFAULT_PINYIN_CHARACTER_SET = "simplified"
    private const val DEFAULT_PINYIN_FUZZY_ENABLED = false
    private const val DEFAULT_CHINESE_INPUT_METHOD = "pinyin" // "pinyin", "wubi", or "shuangpin" (legacy)
    private const val DEFAULT_INPUT_MODE = "english" // "english", "pinyin", "shuangpin", or "wubi"
    private const val DEFAULT_CHINESE_NEXT_WORD_PREDICTION = true
    private const val DEFAULT_COMPACT_MODE = false
    private const val DEFAULT_POWER_SHORTCUTS_ENABLED = false
    private const val DEFAULT_SWIPE_INCREMENTAL_THRESHOLD = 14.0f
    private const val MIN_SWIPE_INCREMENTAL_THRESHOLD = 3.0f
    private const val MAX_SWIPE_INCREMENTAL_THRESHOLD = 25.0f
    private const val DEFAULT_STATIC_VARIATION_BAR_MODE = false
    private const val DEFAULT_TUTORIAL_COMPLETED = false
    private const val DEFAULT_DEVICE_TYPE = "titan2"
    private const val DEFAULT_SHOW_VOICE_INPUT_BUTTON = true
    private const val DEFAULT_CLIPBOARD_HISTORY_ENABLED = true
    private const val DEFAULT_SHOW_CLIPBOARD_BUTTON = true
    private const val DEFAULT_JUYING_MODE_ENABLED = false
    private const val DEFAULT_TOUCHPAD_PAGE_ENABLED = true  // Touchpad page navigation enabled by default
    private const val DEFAULT_ALT_DOUBLE_CLICK_DELAY = 250  // Default 250ms for Alt double-click detection
    private const val DEFAULT_MEMORY_FUNCTION_ENABLED = true  // Memory function enabled by default
    private const val DEFAULT_SHIFT_ALT_SWAPPED = false  // Shift and Alt buttons are not swapped by default
    private const val DEFAULT_MAX_CANDIDATES_NON_JUYING = 9  // Default 9 candidates in non-Juying mode
    private const val DEFAULT_SHIFT_ENTER_TOGGLE_INPUT = true  // Shift+Enter toggles input methods by default
    private const val DEFAULT_VIRTUAL_KEYBOARD_ENABLED = true  // Virtual keyboard enabled by default
    private const val DEFAULT_AUTO_PHRASE_MEMORY_ENABLED = true  // Auto-phrase memory enabled by default
    private const val DEFAULT_WUBI_WITH_PINYIN_ENABLED = false  // Wubi with Pinyin disabled by default
    private const val DEFAULT_WUBI_PHRASES_FIRST = false  // Single characters first by default (traditional Wubi behavior)
    private const val DEFAULT_WUBI_AUTO_COMMIT_SINGLE = false  // Don't auto-commit by default
    private const val DEFAULT_WUBI_AUTO_COMMIT_OVERFLOW = false  // Don't auto-commit on 5th letter by default
    private const val DEFAULT_WUBI_Z_KEY_MODE = "wildcard"  // Z key mode: "disabled", "wildcard", "symbol"
    private const val DEFAULT_CANDIDATE_FONT_SIZE = 18  // Default candidate font size in SP
    private const val MIN_CANDIDATE_FONT_SIZE = 12
    private const val MAX_CANDIDATE_FONT_SIZE = 28
    private const val DEFAULT_SUGGESTION_MIN_FONT_SIZE = 11  // Default minimum font size for suggestions in SP
    private const val MIN_SUGGESTION_MIN_FONT_SIZE = 5
    private const val MAX_SUGGESTION_MIN_FONT_SIZE = 28  // Max is same as candidate font size max
    private const val DEFAULT_STATUS_BAR_HEIGHT = 55  // Default status bar height in DIP
    private const val MIN_STATUS_BAR_HEIGHT = 35
    private const val MAX_STATUS_BAR_HEIGHT = 80
    private const val DEFAULT_SUGGESTION_HEIGHT_PERCENT = 100  // Default suggestion background height as percentage of status bar
    private const val MIN_SUGGESTION_HEIGHT_PERCENT = 50
    private const val MAX_SUGGESTION_HEIGHT_PERCENT = 100
    private const val DEFAULT_VIRTUAL_KEYBOARD_HEIGHT = 60  // Default virtual keyboard key height in DIP
    private const val MIN_VIRTUAL_KEYBOARD_HEIGHT = 32
    private const val MAX_VIRTUAL_KEYBOARD_HEIGHT = 80
    private const val DEFAULT_VIRTUAL_KEYBOARD_SOUND = false  // Virtual keyboard sound disabled by default
    private const val DEFAULT_KEYBOARD_SOUND_TYPE = "mechanical"  // Default sound type
    private const val DEFAULT_KEYBOARD_SOUND_VOLUME = 70  // Default sound volume (0-100)
    private const val DEFAULT_VIRTUAL_KEYBOARD_VIBRATION = true  // Virtual keyboard vibration enabled by default
    private const val DEFAULT_PARTIAL_PINYIN_MATCHING = false  // Partial pinyin matching disabled by default
    private const val DEFAULT_ABBREVIATION_INPUT_ENABLED = true  // 首字母 input enabled by default
    private const val DEFAULT_SHOW_VIRTUAL_KEYBOARD_BUTTON = true  // Virtual keyboard button shown by default
    private const val DEFAULT_SEMI_TRANSPARENT_STATUS_BAR = false  // Status bar is opaque by default
    private const val DEFAULT_3D_EFFECT_ENABLED = false  // 3D effect disabled by default
    private const val DEFAULT_OFFLINE_VOICE_INPUT = false  // Online (Google) voice recognition by default
    private const val DEFAULT_VOICE_AUTO_INSERT = true  // Auto-insert voice recognition result
    private const val DEFAULT_VOICE_CHINESE_PUNCTUATION = true  // Use Chinese punctuation for voice input by default
    private const val DEFAULT_VOICE_ADD_PUNCTUATION = true  // Add punctuation to voice input by default
    private const val DEFAULT_HOLD_SPACE_FOR_VOICE = false  // Hold space for voice input disabled by default
    private const val DEFAULT_HOLD_SPACE_DURATION = 500L  // Default hold duration in ms
    private const val MIN_HOLD_SPACE_DURATION = 200L  // Minimum hold duration
    private const val MAX_HOLD_SPACE_DURATION = 1500L  // Maximum hold duration
    private const val DEFAULT_SHOW_LED_STATUS = true  // LED status indicator shown by default
    private const val DEFAULT_TRADITIONAL_CHINESE_TOGGLE_ENABLED = false  // 简/繁 toggle disabled by default
    private const val DEFAULT_SHOW_SYM_BUTTON = true  // SYM button shown by default
    private const val DEFAULT_SHOW_SOUND_TOGGLE_BUTTON = false  // Sound toggle button hidden by default
    private const val DEFAULT_JUYING_PUNCTUATION_BUTTONS = false  // Punctuation buttons hidden by default
    private const val DEFAULT_JUYING_PUNCTUATION_LEFT = ","  // Default left punctuation (comma)
    private const val DEFAULT_JUYING_PUNCTUATION_RIGHT = "."  // Default right punctuation (period)
    private const val DEFAULT_JUYING_PUNCTUATION_LEFT_SHIFTED = "?"  // Default left punctuation when Shift active
    private const val DEFAULT_JUYING_PUNCTUATION_RIGHT_SHIFTED = "!"  // Default right punctuation when Shift active
    // Titan 2 default Juying keys
    private const val DEFAULT_JUYING_KEY_1 = KeyEvent.KEYCODE_SHIFT_LEFT // Shift (Candidate 2)
    private const val DEFAULT_JUYING_KEY_2 = KeyEvent.KEYCODE_SYM // Sym (Candidate 3)
    private const val DEFAULT_JUYING_KEY_3 = KeyEvent.KEYCODE_SPACE // Space (Candidate 1 - Best)
    private const val DEFAULT_JUYING_KEY_4 = KeyEvent.KEYCODE_CTRL_LEFT // Ctrl (Candidate 4)
    private const val DEFAULT_JUYING_KEY_5 = KeyEvent.KEYCODE_ALT_LEFT // Alt (Candidate 5)
    // BlackBerry Juying keys: keycode 59 (Shift), keycode 7 (0 key), keycode 62 (Space), keycode 58 (SYM), keycode 60 (Shift Right)
    private const val BLACKBERRY_JUYING_KEY_1 = 59  // KEYCODE_SHIFT_LEFT
    private const val BLACKBERRY_JUYING_KEY_2 = 7   // KEYCODE_0 (the "0" key)
    private const val BLACKBERRY_JUYING_KEY_3 = 62  // KEYCODE_SPACE
    private const val BLACKBERRY_JUYING_KEY_4 = 58  // SYM key on BlackBerry
    private const val BLACKBERRY_JUYING_KEY_5 = 60  // KEYCODE_SHIFT_RIGHT

    /**
     * Returns the SharedPreferences instance for Pastiera.
     */
    fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Returns the long-press threshold in milliseconds.
     */
    fun getLongPressThreshold(context: Context): Long {
        return getPreferences(context).getLong(KEY_LONG_PRESS_THRESHOLD, DEFAULT_LONG_PRESS_THRESHOLD)
    }
    
    /**
     * Sets the long-press threshold in milliseconds.
     * The value is automatically clamped between MIN and MAX.
     */
    fun setLongPressThreshold(context: Context, threshold: Long) {
        val clampedValue = threshold.coerceIn(MIN_LONG_PRESS_THRESHOLD, MAX_LONG_PRESS_THRESHOLD)
        getPreferences(context).edit()
            .putLong(KEY_LONG_PRESS_THRESHOLD, clampedValue)
            .apply()
    }
    
    /**
     * Returns the minimum allowed value for the long-press threshold.
     */
    fun getMinLongPressThreshold(): Long = MIN_LONG_PRESS_THRESHOLD
    
    /**
     * Returns the maximum allowed value for the long-press threshold.
     */
    fun getMaxLongPressThreshold(): Long = MAX_LONG_PRESS_THRESHOLD
    
    /**
     * Returns the default value for the long-press threshold.
     */
    fun getDefaultLongPressThreshold(): Long = DEFAULT_LONG_PRESS_THRESHOLD
    
    /**
     * Returns the state of auto-capitalization for the first letter.
     */
    fun getAutoCapitalizeFirstLetter(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTO_CAPITALIZE_FIRST_LETTER, DEFAULT_AUTO_CAPITALIZE_FIRST_LETTER)
    }
    
    /**
     * Sets the state of auto-capitalization for the first letter.
     */
    fun setAutoCapitalizeFirstLetter(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_AUTO_CAPITALIZE_FIRST_LETTER, enabled)
            .apply()
    }

    /**
     * Returns the state of auto-capitalization after period.
     */
    fun getAutoCapitalizeAfterPeriod(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTO_CAPITALIZE_AFTER_PERIOD, DEFAULT_AUTO_CAPITALIZE_AFTER_PERIOD)
    }

    /**
     * Sets the state of auto-capitalization after period.
     */
    fun setAutoCapitalizeAfterPeriod(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_AUTO_CAPITALIZE_AFTER_PERIOD, enabled)
            .apply()
    }

    /**
     * Returns the state of the double-space-to-period feature.
     */
    fun getDoubleSpaceToPeriod(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_DOUBLE_SPACE_TO_PERIOD, DEFAULT_DOUBLE_SPACE_TO_PERIOD)
    }
    
    /**
     * Sets the state of the double-space-to-period feature.
     */
    fun setDoubleSpaceToPeriod(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_DOUBLE_SPACE_TO_PERIOD, enabled)
            .apply()
    }
    
    /**
     * Returns the state of swipe-to-delete (keycode 322).
     */
    fun getSwipeToDelete(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SWIPE_TO_DELETE, DEFAULT_SWIPE_TO_DELETE)
    }
    
    /**
     * Sets the state of swipe-to-delete (keycode 322).
     */
    fun setSwipeToDelete(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SWIPE_TO_DELETE, enabled)
            .apply()
    }
    
    /**
     * Returns the state of automatically showing the keyboard when a field gains focus.
     */
    fun getAutoShowKeyboard(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTO_SHOW_KEYBOARD, DEFAULT_AUTO_SHOW_KEYBOARD)
    }
    
    /**
     * Sets the state of automatically showing the keyboard when a field gains focus.
     */
    fun setAutoShowKeyboard(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_AUTO_SHOW_KEYBOARD, enabled)
            .apply()
    }

    /**
     * Returns whether Alt+Ctrl shortcut for speech recognition is enabled.
     */
    fun getAltCtrlSpeechShortcutEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_ALT_CTRL_SPEECH_SHORTCUT, DEFAULT_ALT_CTRL_SPEECH_SHORTCUT)
    }

    /**
     * Sets whether Alt+Ctrl shortcut for speech recognition is enabled.
     */
    fun setAltCtrlSpeechShortcutEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_ALT_CTRL_SPEECH_SHORTCUT, enabled)
            .apply()
    }

    /**
     * Returns whether Alt/Alt-Lock should be cleared when pressing Space.
     */
    fun getClearAltOnSpace(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_CLEAR_ALT_ON_SPACE, DEFAULT_CLEAR_ALT_ON_SPACE)
    }

    /**
     * Sets whether Alt/Alt-Lock should be cleared when pressing Space.
     */
    fun setClearAltOnSpace(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_CLEAR_ALT_ON_SPACE, enabled)
            .apply()
    }
    
    /**
     * Returns custom SYM mappings.
     * Returns an empty map if there are no custom mappings.
     */
    fun getSymMappings(context: Context): Map<Int, String> {
        val prefs = getPreferences(context)
        val jsonString = prefs.getString(KEY_SYM_MAPPINGS_CUSTOM, null) ?: return emptyMap()
        
        return try {
            val jsonObject = JSONObject(jsonString)
            val mappingsObject = jsonObject.getJSONObject("mappings")
            val keyCodeMap = mapOf(
                "KEYCODE_Q" to KeyEvent.KEYCODE_Q, "KEYCODE_W" to KeyEvent.KEYCODE_W,
                "KEYCODE_E" to KeyEvent.KEYCODE_E, "KEYCODE_R" to KeyEvent.KEYCODE_R,
                "KEYCODE_T" to KeyEvent.KEYCODE_T, "KEYCODE_Y" to KeyEvent.KEYCODE_Y,
                "KEYCODE_U" to KeyEvent.KEYCODE_U, "KEYCODE_I" to KeyEvent.KEYCODE_I,
                "KEYCODE_O" to KeyEvent.KEYCODE_O, "KEYCODE_P" to KeyEvent.KEYCODE_P,
                "KEYCODE_A" to KeyEvent.KEYCODE_A, "KEYCODE_S" to KeyEvent.KEYCODE_S,
                "KEYCODE_D" to KeyEvent.KEYCODE_D, "KEYCODE_F" to KeyEvent.KEYCODE_F,
                "KEYCODE_G" to KeyEvent.KEYCODE_G, "KEYCODE_H" to KeyEvent.KEYCODE_H,
                "KEYCODE_J" to KeyEvent.KEYCODE_J, "KEYCODE_K" to KeyEvent.KEYCODE_K,
                "KEYCODE_L" to KeyEvent.KEYCODE_L, "KEYCODE_Z" to KeyEvent.KEYCODE_Z,
                "KEYCODE_X" to KeyEvent.KEYCODE_X, "KEYCODE_C" to KeyEvent.KEYCODE_C,
                "KEYCODE_V" to KeyEvent.KEYCODE_V, "KEYCODE_B" to KeyEvent.KEYCODE_B,
                "KEYCODE_N" to KeyEvent.KEYCODE_N, "KEYCODE_M" to KeyEvent.KEYCODE_M
            )
            
            val result = mutableMapOf<Int, String>()
            val keys = mappingsObject.keys()
            while (keys.hasNext()) {
                val keyName = keys.next()
                val keyCode = keyCodeMap[keyName]
                val emoji = mappingsObject.getString(keyName)
                if (keyCode != null) {
                    result[keyCode] = emoji
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom SYM mappings", e)
            emptyMap()
        }
    }
    
    /**
     * Saves custom SYM mappings.
     */
    fun saveSymMappings(context: Context, mappings: Map<Int, String>) {
        try {
            val keyCodeToName = mapOf(
                KeyEvent.KEYCODE_Q to "KEYCODE_Q", KeyEvent.KEYCODE_W to "KEYCODE_W",
                KeyEvent.KEYCODE_E to "KEYCODE_E", KeyEvent.KEYCODE_R to "KEYCODE_R",
                KeyEvent.KEYCODE_T to "KEYCODE_T", KeyEvent.KEYCODE_Y to "KEYCODE_Y",
                KeyEvent.KEYCODE_U to "KEYCODE_U", KeyEvent.KEYCODE_I to "KEYCODE_I",
                KeyEvent.KEYCODE_O to "KEYCODE_O", KeyEvent.KEYCODE_P to "KEYCODE_P",
                KeyEvent.KEYCODE_A to "KEYCODE_A", KeyEvent.KEYCODE_S to "KEYCODE_S",
                KeyEvent.KEYCODE_D to "KEYCODE_D", KeyEvent.KEYCODE_F to "KEYCODE_F",
                KeyEvent.KEYCODE_G to "KEYCODE_G", KeyEvent.KEYCODE_H to "KEYCODE_H",
                KeyEvent.KEYCODE_J to "KEYCODE_J", KeyEvent.KEYCODE_K to "KEYCODE_K",
                KeyEvent.KEYCODE_L to "KEYCODE_L", KeyEvent.KEYCODE_Z to "KEYCODE_Z",
                KeyEvent.KEYCODE_X to "KEYCODE_X", KeyEvent.KEYCODE_C to "KEYCODE_C",
                KeyEvent.KEYCODE_V to "KEYCODE_V", KeyEvent.KEYCODE_B to "KEYCODE_B",
                KeyEvent.KEYCODE_N to "KEYCODE_N", KeyEvent.KEYCODE_M to "KEYCODE_M"
            )
            
            val mappingsObject = JSONObject()
            for ((keyCode, emoji) in mappings) {
                val keyName = keyCodeToName[keyCode]
                if (keyName != null) {
                    mappingsObject.put(keyName, emoji)
                }
            }
            
            val jsonObject = JSONObject()
            jsonObject.put("mappings", mappingsObject)
            
            getPreferences(context).edit()
                .putString(KEY_SYM_MAPPINGS_CUSTOM, jsonObject.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom SYM mappings", e)
        }
    }
    
    /**
     * Resets custom SYM mappings back to defaults.
     */
    fun resetSymMappings(context: Context) {
        getPreferences(context).edit()
            .remove(KEY_SYM_MAPPINGS_CUSTOM)
            .apply()
    }
    
    /**
     * Returns true if custom SYM mappings exist.
     */
    fun hasCustomSymMappings(context: Context): Boolean {
        val prefs = getPreferences(context)
        return prefs.contains(KEY_SYM_MAPPINGS_CUSTOM)
    }
    
    /**
     * Returns custom SYM mappings for page 2.
     * Returns an empty map if there are no custom mappings.
     */
    fun getSymMappingsPage2(context: Context): Map<Int, String> {
        val prefs = getPreferences(context)
        val jsonString = prefs.getString(KEY_SYM_MAPPINGS_PAGE2_CUSTOM, null) ?: return emptyMap()
        
        return try {
            val jsonObject = JSONObject(jsonString)
            val mappingsObject = jsonObject.getJSONObject("mappings")
            val keyCodeMap = mapOf(
                "KEYCODE_Q" to KeyEvent.KEYCODE_Q, "KEYCODE_W" to KeyEvent.KEYCODE_W,
                "KEYCODE_E" to KeyEvent.KEYCODE_E, "KEYCODE_R" to KeyEvent.KEYCODE_R,
                "KEYCODE_T" to KeyEvent.KEYCODE_T, "KEYCODE_Y" to KeyEvent.KEYCODE_Y,
                "KEYCODE_U" to KeyEvent.KEYCODE_U, "KEYCODE_I" to KeyEvent.KEYCODE_I,
                "KEYCODE_O" to KeyEvent.KEYCODE_O, "KEYCODE_P" to KeyEvent.KEYCODE_P,
                "KEYCODE_A" to KeyEvent.KEYCODE_A, "KEYCODE_S" to KeyEvent.KEYCODE_S,
                "KEYCODE_D" to KeyEvent.KEYCODE_D, "KEYCODE_F" to KeyEvent.KEYCODE_F,
                "KEYCODE_G" to KeyEvent.KEYCODE_G, "KEYCODE_H" to KeyEvent.KEYCODE_H,
                "KEYCODE_J" to KeyEvent.KEYCODE_J, "KEYCODE_K" to KeyEvent.KEYCODE_K,
                "KEYCODE_L" to KeyEvent.KEYCODE_L, "KEYCODE_Z" to KeyEvent.KEYCODE_Z,
                "KEYCODE_X" to KeyEvent.KEYCODE_X, "KEYCODE_C" to KeyEvent.KEYCODE_C,
                "KEYCODE_V" to KeyEvent.KEYCODE_V, "KEYCODE_B" to KeyEvent.KEYCODE_B,
                "KEYCODE_N" to KeyEvent.KEYCODE_N, "KEYCODE_M" to KeyEvent.KEYCODE_M
            )
            
            val result = mutableMapOf<Int, String>()
            val keys = mappingsObject.keys()
            while (keys.hasNext()) {
                val keyName = keys.next()
                val keyCode = keyCodeMap[keyName]
                val character = mappingsObject.getString(keyName)
                if (keyCode != null) {
                    result[keyCode] = character
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom SYM page 2 mappings", e)
            emptyMap()
        }
    }
    
    /**
     * Saves custom SYM mappings for page 2.
     */
    fun saveSymMappingsPage2(context: Context, mappings: Map<Int, String>) {
        try {
            val keyCodeToName = mapOf(
                KeyEvent.KEYCODE_Q to "KEYCODE_Q", KeyEvent.KEYCODE_W to "KEYCODE_W",
                KeyEvent.KEYCODE_E to "KEYCODE_E", KeyEvent.KEYCODE_R to "KEYCODE_R",
                KeyEvent.KEYCODE_T to "KEYCODE_T", KeyEvent.KEYCODE_Y to "KEYCODE_Y",
                KeyEvent.KEYCODE_U to "KEYCODE_U", KeyEvent.KEYCODE_I to "KEYCODE_I",
                KeyEvent.KEYCODE_O to "KEYCODE_O", KeyEvent.KEYCODE_P to "KEYCODE_P",
                KeyEvent.KEYCODE_A to "KEYCODE_A", KeyEvent.KEYCODE_S to "KEYCODE_S",
                KeyEvent.KEYCODE_D to "KEYCODE_D", KeyEvent.KEYCODE_F to "KEYCODE_F",
                KeyEvent.KEYCODE_G to "KEYCODE_G", KeyEvent.KEYCODE_H to "KEYCODE_H",
                KeyEvent.KEYCODE_J to "KEYCODE_J", KeyEvent.KEYCODE_K to "KEYCODE_K",
                KeyEvent.KEYCODE_L to "KEYCODE_L", KeyEvent.KEYCODE_Z to "KEYCODE_Z",
                KeyEvent.KEYCODE_X to "KEYCODE_X", KeyEvent.KEYCODE_C to "KEYCODE_C",
                KeyEvent.KEYCODE_V to "KEYCODE_V", KeyEvent.KEYCODE_B to "KEYCODE_B",
                KeyEvent.KEYCODE_N to "KEYCODE_N", KeyEvent.KEYCODE_M to "KEYCODE_M"
            )
            
            val mappingsObject = JSONObject()
            for ((keyCode, character) in mappings) {
                val keyName = keyCodeToName[keyCode]
                if (keyName != null) {
                    mappingsObject.put(keyName, character)
                }
            }
            
            val jsonObject = JSONObject()
            jsonObject.put("mappings", mappingsObject)
            
            getPreferences(context).edit()
                .putString(KEY_SYM_MAPPINGS_PAGE2_CUSTOM, jsonObject.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom SYM page 2 mappings", e)
        }
    }
    
    /**
     * Resets custom SYM mappings for page 2 back to defaults.
     */
    fun resetSymMappingsPage2(context: Context) {
        getPreferences(context).edit()
            .remove(KEY_SYM_MAPPINGS_PAGE2_CUSTOM)
            .apply()
    }
    
    /**
     * Returns true if custom SYM page 2 mappings exist.
     */
    fun hasCustomSymMappingsPage2(context: Context): Boolean {
        val prefs = getPreferences(context)
        return prefs.contains(KEY_SYM_MAPPINGS_PAGE2_CUSTOM)
    }

    /**
     * Returns custom SYM mappings for page 3 (Characters2).
     * Returns an empty map if there are no custom mappings.
     */
    fun getSymMappingsPage3(context: Context): Map<Int, String> {
        val prefs = getPreferences(context)
        val jsonString = prefs.getString(KEY_SYM_MAPPINGS_PAGE3_CUSTOM, null) ?: return emptyMap()

        return try {
            val jsonObject = JSONObject(jsonString)
            val mappingsObject = jsonObject.getJSONObject("mappings")
            val keyCodeMap = mapOf(
                "KEYCODE_Q" to KeyEvent.KEYCODE_Q, "KEYCODE_W" to KeyEvent.KEYCODE_W,
                "KEYCODE_E" to KeyEvent.KEYCODE_E, "KEYCODE_R" to KeyEvent.KEYCODE_R,
                "KEYCODE_T" to KeyEvent.KEYCODE_T, "KEYCODE_Y" to KeyEvent.KEYCODE_Y,
                "KEYCODE_U" to KeyEvent.KEYCODE_U, "KEYCODE_I" to KeyEvent.KEYCODE_I,
                "KEYCODE_O" to KeyEvent.KEYCODE_O, "KEYCODE_P" to KeyEvent.KEYCODE_P,
                "KEYCODE_A" to KeyEvent.KEYCODE_A, "KEYCODE_S" to KeyEvent.KEYCODE_S,
                "KEYCODE_D" to KeyEvent.KEYCODE_D, "KEYCODE_F" to KeyEvent.KEYCODE_F,
                "KEYCODE_G" to KeyEvent.KEYCODE_G, "KEYCODE_H" to KeyEvent.KEYCODE_H,
                "KEYCODE_J" to KeyEvent.KEYCODE_J, "KEYCODE_K" to KeyEvent.KEYCODE_K,
                "KEYCODE_L" to KeyEvent.KEYCODE_L, "KEYCODE_Z" to KeyEvent.KEYCODE_Z,
                "KEYCODE_X" to KeyEvent.KEYCODE_X, "KEYCODE_C" to KeyEvent.KEYCODE_C,
                "KEYCODE_V" to KeyEvent.KEYCODE_V, "KEYCODE_B" to KeyEvent.KEYCODE_B,
                "KEYCODE_N" to KeyEvent.KEYCODE_N, "KEYCODE_M" to KeyEvent.KEYCODE_M
            )
            val result = mutableMapOf<Int, String>()
            val keys = mappingsObject.keys()
            while (keys.hasNext()) {
                val keyName = keys.next()
                val keyCode = keyCodeMap[keyName]
                val character = mappingsObject.getString(keyName)
                if (keyCode != null) {
                    result[keyCode] = character
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom SYM page 3 mappings", e)
            emptyMap()
        }
    }

    /**
     * Saves custom SYM mappings for page 3 (Characters2).
     */
    fun saveSymMappingsPage3(context: Context, mappings: Map<Int, String>) {
        try {
            val keyCodeToName = mapOf(
                KeyEvent.KEYCODE_Q to "KEYCODE_Q", KeyEvent.KEYCODE_W to "KEYCODE_W",
                KeyEvent.KEYCODE_E to "KEYCODE_E", KeyEvent.KEYCODE_R to "KEYCODE_R",
                KeyEvent.KEYCODE_T to "KEYCODE_T", KeyEvent.KEYCODE_Y to "KEYCODE_Y",
                KeyEvent.KEYCODE_U to "KEYCODE_U", KeyEvent.KEYCODE_I to "KEYCODE_I",
                KeyEvent.KEYCODE_O to "KEYCODE_O", KeyEvent.KEYCODE_P to "KEYCODE_P",
                KeyEvent.KEYCODE_A to "KEYCODE_A", KeyEvent.KEYCODE_S to "KEYCODE_S",
                KeyEvent.KEYCODE_D to "KEYCODE_D", KeyEvent.KEYCODE_F to "KEYCODE_F",
                KeyEvent.KEYCODE_G to "KEYCODE_G", KeyEvent.KEYCODE_H to "KEYCODE_H",
                KeyEvent.KEYCODE_J to "KEYCODE_J", KeyEvent.KEYCODE_K to "KEYCODE_K",
                KeyEvent.KEYCODE_L to "KEYCODE_L", KeyEvent.KEYCODE_Z to "KEYCODE_Z",
                KeyEvent.KEYCODE_X to "KEYCODE_X", KeyEvent.KEYCODE_C to "KEYCODE_C",
                KeyEvent.KEYCODE_V to "KEYCODE_V", KeyEvent.KEYCODE_B to "KEYCODE_B",
                KeyEvent.KEYCODE_N to "KEYCODE_N", KeyEvent.KEYCODE_M to "KEYCODE_M"
            )

            val mappingsObject = JSONObject()
            for ((keyCode, character) in mappings) {
                val keyName = keyCodeToName[keyCode]
                if (keyName != null) {
                    mappingsObject.put(keyName, character)
                }
            }

            val jsonObject = JSONObject()
            jsonObject.put("mappings", mappingsObject)

            getPreferences(context).edit()
                .putString(KEY_SYM_MAPPINGS_PAGE3_CUSTOM, jsonObject.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom SYM page 3 mappings", e)
        }
    }

    /**
     * Resets custom SYM mappings for page 3 back to defaults.
     */
    fun resetSymMappingsPage3(context: Context) {
        getPreferences(context).edit()
            .remove(KEY_SYM_MAPPINGS_PAGE3_CUSTOM)
            .apply()
    }

    /**
     * Returns true if custom SYM page 3 mappings exist.
     */
    fun hasCustomSymMappingsPage3(context: Context): Boolean {
        val prefs = getPreferences(context)
        return prefs.contains(KEY_SYM_MAPPINGS_PAGE3_CUSTOM)
    }

    /**
     * Returns whether auto-correction is enabled.
     */
    fun getAutoCorrectEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTO_CORRECT_ENABLED, DEFAULT_AUTO_CORRECT_ENABLED)
    }
    
    /**
     * Sets whether auto-correction is enabled.
     */
    fun setAutoCorrectEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_AUTO_CORRECT_ENABLED, enabled)
            .apply()
    }
    
    /**
     * Returns the list of languages enabled for auto-correction.
     * @return Set of language codes (e.g. "it", "en")
     */
    fun getAutoCorrectEnabledLanguages(context: Context): Set<String> {
        val prefs = getPreferences(context)
        val languagesString = prefs.getString(KEY_AUTO_CORRECT_ENABLED_LANGUAGES, null)
        
        // If languages are explicitly set, return them (ensuring x-pastiera is always included)
        if (languagesString != null && languagesString.isNotEmpty()) {
            val languages = languagesString.split(",").toMutableSet()
            languages.add("x-pastiera") // Always include x-pastiera
            return languages
        }
        
        // Default: system language + x-pastiera, with fallback to English
        val systemLanguage = context.resources.configuration.locales[0].language.lowercase()
        val supportedLanguages = setOf("it", "en", "es", "fr", "de", "pl")
        
        val defaultLanguage = if (systemLanguage in supportedLanguages) {
            systemLanguage
        } else {
            "en" // Fallback to English
        }
        
        return setOf(defaultLanguage, "x-pastiera")
    }
    
    /**
     * Sets the list of languages enabled for auto-correction.
     * @param languages Set of language codes (e.g. "it", "en")
     * Note: x-pastiera is always included automatically in getAutoCorrectEnabledLanguages()
     */
    fun setAutoCorrectEnabledLanguages(context: Context, languages: Set<String>) {
        // Filter out x-pastiera from the saved list (it's always included automatically)
        val languagesToSave = languages.filter { it != "x-pastiera" }
        val languagesString = languagesToSave.joinToString(",")
        getPreferences(context).edit()
            .putString(KEY_AUTO_CORRECT_ENABLED_LANGUAGES, languagesString)
            .apply()
    }
    
    /**
     * Returns true if a language is enabled for auto-correction.
     */
    fun isAutoCorrectLanguageEnabled(context: Context, language: String): Boolean {
        val enabledLanguages = getAutoCorrectEnabledLanguages(context)
        // If the list is empty, all languages are enabled (default behavior)
        return enabledLanguages.isEmpty() || enabledLanguages.contains(language)
    }
    
    /**
     * Special JSON field for the language name.
     */
    private const val LANGUAGE_NAME_KEY = "__name"
    
    /**
     * Returns custom corrections for a language.
     */
    fun getCustomAutoCorrections(context: Context, languageCode: String): Map<String, String> {
        val prefs = getPreferences(context)
        val key = "auto_correct_custom_$languageCode"
        val jsonString = prefs.getString(key, null) ?: return emptyMap()
        
        return try {
            val jsonObject = JSONObject(jsonString)
            val corrections = mutableMapOf<String, String>()
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val correctionKey = keys.next()
                // Skip the special name field
                if (correctionKey != LANGUAGE_NAME_KEY) {
                    val value = jsonObject.getString(correctionKey)
                    corrections[correctionKey] = value
                }
            }
            corrections
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom corrections for $languageCode", e)
            emptyMap()
        }
    }
    
    /**
     * Returns the display name of a custom language from JSON.
     */
    fun getCustomLanguageName(context: Context, languageCode: String): String? {
        val prefs = getPreferences(context)
        val key = "auto_correct_custom_$languageCode"
        val jsonString = prefs.getString(key, null) ?: return null
        
        return try {
            val jsonObject = JSONObject(jsonString)
            if (jsonObject.has(LANGUAGE_NAME_KEY)) {
                jsonObject.getString(LANGUAGE_NAME_KEY)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading language name for $languageCode", e)
            null
        }
    }
    
    /**
     * Saves custom corrections for a language.
     * @param languageName The display name of the language (optional, if null it is not saved/updated)
     */
    fun saveCustomAutoCorrections(
        context: Context, 
        languageCode: String, 
        corrections: Map<String, String>,
        languageName: String? = null
    ) {
        try {
            val jsonObject = JSONObject()
            
            // Save the language name if provided
            if (languageName != null) {
                jsonObject.put(LANGUAGE_NAME_KEY, languageName)
            } else {
                // If not provided, try to keep the existing name
                val existingName = getCustomLanguageName(context, languageCode)
                if (existingName != null) {
                    jsonObject.put(LANGUAGE_NAME_KEY, existingName)
                }
            }
            
            // Save corrections
            corrections.forEach { (key, value) ->
                // Skip the special field if present in the corrections
                if (key != LANGUAGE_NAME_KEY) {
                    jsonObject.put(key, value)
                }
            }
            
            val key = "auto_correct_custom_$languageCode"
            getPreferences(context).edit()
                .putString(key, jsonObject.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom corrections for $languageCode", e)
        }
    }
    
    /**
     * Updates only the display name of a custom language.
     */
    fun updateCustomLanguageName(context: Context, languageCode: String, languageName: String) {
        try {
            val prefs = getPreferences(context)
            val key = "auto_correct_custom_$languageCode"
            val jsonString = prefs.getString(key, null)
            
            val jsonObject = if (jsonString != null) {
                JSONObject(jsonString)
            } else {
                JSONObject()
            }
            
            jsonObject.put(LANGUAGE_NAME_KEY, languageName)
            
            prefs.edit()
                .putString(key, jsonObject.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating language name for $languageCode", e)
        }
    }
    
    /**
     * Returns the long-press modifier type ("alt" or "shift").
     */
    fun getLongPressModifier(context: Context): String {
        return getPreferences(context).getString(KEY_LONG_PRESS_MODIFIER, DEFAULT_LONG_PRESS_MODIFIER) ?: DEFAULT_LONG_PRESS_MODIFIER
    }
    
    /**
     * Sets the long-press modifier type ("alt" or "shift").
     */
    fun setLongPressModifier(context: Context, modifier: String) {
        val validModifier = if (modifier == "shift") "shift" else "alt"
        getPreferences(context).edit()
            .putString(KEY_LONG_PRESS_MODIFIER, validModifier)
            .apply()
    }
    
    /**
     * Returns true if long press uses Shift, false if it uses Alt.
     */
    fun isLongPressShift(context: Context): Boolean {
        return getLongPressModifier(context) == "shift"
    }
    
    /**
     * Data class per rappresentare una scorciatoia del launcher.
     * Estendibile per supportare diversi tipi di azioni in futuro (app, shortcut, ecc.)
     */
    data class LauncherShortcut(
        val type: String = TYPE_APP, // Tipo di azione: "app", "shortcut", ecc.
        val packageName: String? = null, // Per tipo "app"
        val appName: String? = null, // Per tipo "app"
        val action: String? = null, // Per tipo "shortcut" o altri tipi futuri
        val data: String? = null // Dati aggiuntivi per tipi futuri
    ) {
        companion object {
            const val TYPE_APP = "app"
            const val TYPE_SHORTCUT = "shortcut"
            // Aggiungi altri tipi in futuro qui
        }
    }
    
    private const val KEY_LAUNCHER_SHORTCUTS = "launcher_shortcuts"
    private const val KEY_LAUNCHER_SHORTCUTS_ENABLED = "launcher_shortcuts_enabled"
    private const val DEFAULT_LAUNCHER_SHORTCUTS_ENABLED = false
    
    // Nav mode settings
    private const val KEY_NAV_MODE_ENABLED = "nav_mode_enabled"
    private const val DEFAULT_NAV_MODE_ENABLED = true
    private const val NAV_MODE_MAPPINGS_FILE_NAME = "ctrl_key_mappings.json"
    private const val KEY_NAV_MODE_MAPPINGS_UPDATED = "nav_mode_mappings_updated"
    
    /**
     * Imposta una scorciatoia del launcher per un tasto (tipo app).
     */
    fun setLauncherShortcut(context: Context, keyCode: Int, packageName: String, appName: String) {
        setLauncherAction(context, keyCode, LauncherShortcut(
            type = LauncherShortcut.TYPE_APP,
            packageName = packageName,
            appName = appName
        ))
    }
    
    /**
     * Imposta un'azione del launcher per un tasto (generico, estendibile).
     */
    fun setLauncherAction(context: Context, keyCode: Int, action: LauncherShortcut) {
        val prefs = getPreferences(context)
        val shortcutsJson = prefs.getString(KEY_LAUNCHER_SHORTCUTS, "{}") ?: "{}"
        
        try {
            val shortcuts = JSONObject(shortcutsJson)
            shortcuts.put(keyCode.toString(), JSONObject().apply {
                put("type", action.type)
                if (action.packageName != null) put("packageName", action.packageName)
                if (action.appName != null) put("appName", action.appName)
                if (action.action != null) put("action", action.action)
                if (action.data != null) put("data", action.data)
            })
            prefs.edit().putString(KEY_LAUNCHER_SHORTCUTS, shortcuts.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Errore nel salvataggio dell'azione per tasto $keyCode", e)
        }
    }
    
    /**
     * Rimuove una scorciatoia del launcher per un tasto.
     */
    fun removeLauncherShortcut(context: Context, keyCode: Int) {
        val prefs = getPreferences(context)
        val shortcutsJson = prefs.getString(KEY_LAUNCHER_SHORTCUTS, "{}") ?: "{}"
        
        try {
            val shortcuts = JSONObject(shortcutsJson)
            shortcuts.remove(keyCode.toString())
            prefs.edit().putString(KEY_LAUNCHER_SHORTCUTS, shortcuts.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Errore nella rimozione della scorciatoia per tasto $keyCode", e)
        }
    }
    
    /**
     * Ottiene tutte le scorciatoie del launcher salvate.
     */
    fun getLauncherShortcuts(context: Context): Map<Int, LauncherShortcut> {
        val prefs = getPreferences(context)
        val shortcutsJson = prefs.getString(KEY_LAUNCHER_SHORTCUTS, "{}") ?: "{}"
        val shortcuts = mutableMapOf<Int, LauncherShortcut>()
        
        try {
            val json = JSONObject(shortcutsJson)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val keyCode = key.toIntOrNull()
                if (keyCode != null) {
                    val shortcutObj = json.getJSONObject(key)
                    val type = shortcutObj.optString("type", LauncherShortcut.TYPE_APP)
                    
                    shortcuts[keyCode] = LauncherShortcut(
                        type = type,
                        packageName = shortcutObj.optString("packageName").takeIf { it.isNotEmpty() },
                        appName = shortcutObj.optString("appName").takeIf { it.isNotEmpty() },
                        action = shortcutObj.optString("action").takeIf { it.isNotEmpty() },
                        data = shortcutObj.optString("data").takeIf { it.isNotEmpty() }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore nel caricamento delle scorciatoie", e)
        }
        
        return shortcuts
    }
    
    /**
     * Ottiene una scorciatoia del launcher per un tasto specifico.
     */
    fun getLauncherShortcut(context: Context, keyCode: Int): LauncherShortcut? {
        return getLauncherShortcuts(context)[keyCode]
    }
    
    /**
     * Restituisce se le scorciatoie del launcher sono abilitate.
     */
    fun getLauncherShortcutsEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_LAUNCHER_SHORTCUTS_ENABLED, DEFAULT_LAUNCHER_SHORTCUTS_ENABLED)
    }
    
    /**
     * Imposta se le scorciatoie del launcher sono abilitate.
     */
    fun setLauncherShortcutsEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_LAUNCHER_SHORTCUTS_ENABLED, enabled)
            .apply()
    }
    
    /**
     * Returns whether nav mode is enabled.
     */
    fun getNavModeEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_NAV_MODE_ENABLED, DEFAULT_NAV_MODE_ENABLED)
    }
    
    /**
     * Sets whether nav mode is enabled.
     */
    fun setNavModeEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_NAV_MODE_ENABLED, enabled)
            .apply()
    }
    
    /**
     * Returns the File for nav mode mappings in filesDir.
     */
    fun getNavModeMappingsFile(context: Context): File {
        return File(context.filesDir, NAV_MODE_MAPPINGS_FILE_NAME)
    }
    
    /**
     * Initializes the nav mode mappings file by copying from assets if it doesn't exist.
     */
    fun initializeNavModeMappingsFile(context: Context) {
        val mappingsFile = getNavModeMappingsFile(context)
        if (mappingsFile.exists()) {
            return // File already exists, don't overwrite
        }
        
        try {
            val inputStream: InputStream = context.assets.open("common/ctrl/$NAV_MODE_MAPPINGS_FILE_NAME")
            val outputStream = FileOutputStream(mappingsFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            Log.d(TAG, "Nav mode mappings file initialized from assets")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing nav mode mappings file", e)
        }
    }
    
    /**
     * Saves nav mode key mappings to the JSON file in filesDir.
     */
    fun saveNavModeKeyMappings(context: Context, mappings: Map<Int, it.neuralrad.coolwulf.data.mappings.KeyMappingLoader.CtrlMapping>) {
        try {
            val keyCodeToName = mapOf(
                KeyEvent.KEYCODE_Q to "KEYCODE_Q", KeyEvent.KEYCODE_W to "KEYCODE_W",
                KeyEvent.KEYCODE_E to "KEYCODE_E", KeyEvent.KEYCODE_R to "KEYCODE_R",
                KeyEvent.KEYCODE_T to "KEYCODE_T", KeyEvent.KEYCODE_Y to "KEYCODE_Y",
                KeyEvent.KEYCODE_U to "KEYCODE_U", KeyEvent.KEYCODE_I to "KEYCODE_I",
                KeyEvent.KEYCODE_O to "KEYCODE_O", KeyEvent.KEYCODE_P to "KEYCODE_P",
                KeyEvent.KEYCODE_A to "KEYCODE_A", KeyEvent.KEYCODE_S to "KEYCODE_S",
                KeyEvent.KEYCODE_D to "KEYCODE_D", KeyEvent.KEYCODE_F to "KEYCODE_F",
                KeyEvent.KEYCODE_G to "KEYCODE_G", KeyEvent.KEYCODE_H to "KEYCODE_H",
                KeyEvent.KEYCODE_J to "KEYCODE_J", KeyEvent.KEYCODE_K to "KEYCODE_K",
                KeyEvent.KEYCODE_L to "KEYCODE_L", KeyEvent.KEYCODE_Z to "KEYCODE_Z",
                KeyEvent.KEYCODE_X to "KEYCODE_X", KeyEvent.KEYCODE_C to "KEYCODE_C",
                KeyEvent.KEYCODE_V to "KEYCODE_V", KeyEvent.KEYCODE_B to "KEYCODE_B",
                KeyEvent.KEYCODE_N to "KEYCODE_N", KeyEvent.KEYCODE_M to "KEYCODE_M"
            )
            
            val mappingsObject = JSONObject()
            for ((keyCode, mapping) in mappings) {
                val keyName = keyCodeToName[keyCode]
                if (keyName != null) {
                    val mappingObject = JSONObject()
                    mappingObject.put("type", mapping.type)
                    when (mapping.type) {
                        "action" -> mappingObject.put("action", mapping.value)
                        "keycode" -> mappingObject.put("keycode", mapping.value)
                        "none" -> { /* type is already set */ }
                    }
                    mappingsObject.put(keyName, mappingObject)
                }
            }
            
            // Also include all alphabetic keys that might not be in the mappings map
            // but should be saved as "none" if they're not explicitly set
            val allAlphabeticKeys = listOf(
                KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R,
                KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I,
                KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S,
                KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H,
                KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_Z,
                KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_B,
                KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M
            )
            
            // Ensure all alphabetic keys are in the JSON (even if "none")
            allAlphabeticKeys.forEach { keyCode ->
                val keyName = keyCodeToName[keyCode]
                if (keyName != null && !mappingsObject.has(keyName)) {
                    val mappingObject = JSONObject()
                    mappingObject.put("type", "none")
                    mappingsObject.put(keyName, mappingObject)
                }
            }
            
            val jsonObject = JSONObject()
            jsonObject.put("mappings", mappingsObject)
            
            val mappingsFile = getNavModeMappingsFile(context)
            mappingsFile.writeText(jsonObject.toString())
            
            // Update timestamp in SharedPreferences to notify the service
            getPreferences(context).edit()
                .putLong(KEY_NAV_MODE_MAPPINGS_UPDATED, System.currentTimeMillis())
                .apply()
            
            Log.d(TAG, "Nav mode key mappings saved")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving nav mode key mappings", e)
        }
    }
    
    /**
     * Resets nav mode key mappings to default by deleting the custom file.
     */
    fun resetNavModeKeyMappings(context: Context) {
        try {
            val mappingsFile = getNavModeMappingsFile(context)
            if (mappingsFile.exists()) {
                mappingsFile.delete()
                Log.d(TAG, "Nav mode key mappings reset to default")
            }
            // Re-initialize from assets
            initializeNavModeMappingsFile(context)
            
            // Update timestamp in SharedPreferences to notify the service
            getPreferences(context).edit()
                .putLong(KEY_NAV_MODE_MAPPINGS_UPDATED, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting nav mode key mappings", e)
        }
    }
    
    /**
     * Returns true if custom nav mode mappings exist.
     */
    fun hasCustomNavModeMappings(context: Context): Boolean {
        val mappingsFile = getNavModeMappingsFile(context)
        return mappingsFile.exists()
    }
    
    /**
     * Returns the selected keyboard layout name.
     */
    fun getKeyboardLayout(context: Context): String {
        return getPreferences(context).getString(KEY_KEYBOARD_LAYOUT, DEFAULT_KEYBOARD_LAYOUT) ?: DEFAULT_KEYBOARD_LAYOUT
    }
    
    /**
     * Sets the keyboard layout name.
     */
    fun setKeyboardLayout(context: Context, layoutName: String) {
        getPreferences(context).edit()
            .putString(KEY_KEYBOARD_LAYOUT, layoutName)
            .apply()
    }

    /**
     * Returns whether Pinyin input is enabled.
     */
    fun getPinyinEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_PINYIN_ENABLED, DEFAULT_PINYIN_ENABLED)
    }

    /**
     * Sets whether Pinyin input is enabled.
     */
    fun setPinyinEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_PINYIN_ENABLED, enabled)
            .apply()
    }

    /**
     * Returns whether T9 (九宫格) Pinyin input is enabled.
     */
    fun getT9PinyinEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_T9_PINYIN_ENABLED, DEFAULT_T9_PINYIN_ENABLED)
    }

    /**
     * Sets whether T9 (九宫格) Pinyin input is enabled.
     */
    fun setT9PinyinEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_T9_PINYIN_ENABLED, enabled)
            .apply()
    }

    /**
     * Returns the Pinyin character set ("simplified" or "traditional").
     */
    fun getPinyinCharacterSet(context: Context): String {
        return getPreferences(context).getString(KEY_PINYIN_CHARACTER_SET, DEFAULT_PINYIN_CHARACTER_SET) ?: DEFAULT_PINYIN_CHARACTER_SET
    }

    /**
     * Sets the Pinyin character set ("simplified" or "traditional").
     */
    fun setPinyinCharacterSet(context: Context, characterSet: String) {
        getPreferences(context).edit()
            .putString(KEY_PINYIN_CHARACTER_SET, characterSet)
            .apply()
    }

    /**
     * Returns whether the 简/繁 (simplified/traditional) toggle button is enabled.
     */
    fun isTraditionalChineseToggleEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_TRADITIONAL_CHINESE_TOGGLE_ENABLED, DEFAULT_TRADITIONAL_CHINESE_TOGGLE_ENABLED)
    }

    /**
     * Sets whether the 简/繁 (simplified/traditional) toggle button is enabled.
     */
    fun setTraditionalChineseToggleEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_TRADITIONAL_CHINESE_TOGGLE_ENABLED, enabled)
            .apply()
    }

    /**
     * Returns true if currently in traditional Chinese mode.
     */
    fun isTraditionalChineseMode(context: Context): Boolean {
        return getPinyinCharacterSet(context) == "traditional"
    }

    /**
     * Toggles between simplified and traditional Chinese mode.
     */
    fun toggleChineseCharacterSet(context: Context) {
        val current = getPinyinCharacterSet(context)
        val newMode = if (current == "simplified") "traditional" else "simplified"
        setPinyinCharacterSet(context, newMode)
    }

    /**
     * Returns whether fuzzy pinyin (模糊音) is enabled.
     * Fuzzy pinyin allows substitutions like z=zh, c=ch, s=sh, l=n, en=eng, in=ing.
     */
    fun getPinyinFuzzyEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_PINYIN_FUZZY_ENABLED, DEFAULT_PINYIN_FUZZY_ENABLED)
    }

    /**
     * Sets whether fuzzy pinyin (模糊音) is enabled.
     */
    fun setPinyinFuzzyEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_PINYIN_FUZZY_ENABLED, enabled)
            .apply()
    }

    /**
     * Gets the Chinese input method ("pinyin" or "wubi").
     */
    fun getChineseInputMethod(context: Context): String {
        return getPreferences(context).getString(KEY_CHINESE_INPUT_METHOD, DEFAULT_CHINESE_INPUT_METHOD) ?: DEFAULT_CHINESE_INPUT_METHOD
    }

    /**
     * Sets the Chinese input method ("pinyin" or "wubi").
     */
    fun setChineseInputMethod(context: Context, method: String) {
        getPreferences(context).edit()
            .putString(KEY_CHINESE_INPUT_METHOD, method)
            .apply()
    }

    /**
     * Checks if Wubi input method is selected (legacy - for backwards compatibility).
     */
    fun isWubiInputMethod(context: Context): Boolean {
        return getChineseInputMethod(context) == "wubi"
    }

    /**
     * Returns whether Wubi input is enabled.
     */
    fun getWubiEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_WUBI_ENABLED, DEFAULT_WUBI_ENABLED)
    }

    /**
     * Sets whether Wubi input is enabled.
     */
    fun setWubiEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_WUBI_ENABLED, enabled)
            .apply()
    }

    /**
     * Returns whether Shuangpin (双拼) input is enabled.
     */
    fun getShuangpinEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SHUANGPIN_ENABLED, DEFAULT_SHUANGPIN_ENABLED)
    }

    /**
     * Sets whether Shuangpin (双拼) input is enabled.
     */
    fun setShuangpinEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SHUANGPIN_ENABLED, enabled)
            .apply()
    }

    /**
     * Returns whether Zhenma (真码) input is enabled.
     */
    fun getZhenmaEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_ZHENMA_ENABLED, DEFAULT_ZHENMA_ENABLED)
    }

    /**
     * Sets whether Zhenma (真码) input is enabled.
     */
    fun setZhenmaEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_ZHENMA_ENABLED, enabled)
            .apply()
    }

    /**
     * Returns whether Ziranma (自然码) input is enabled.
     */
    fun getZiranmaEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_ZIRANMA_ENABLED, DEFAULT_ZIRANMA_ENABLED)
    }

    /**
     * Sets whether Ziranma (自然码) input is enabled.
     */
    fun setZiranmaEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_ZIRANMA_ENABLED, enabled)
            .apply()
    }

    /**
     * Returns whether multiple Chinese input methods are enabled (cycling mode).
     */
    fun isMultipleChineseInputMethodsEnabled(context: Context): Boolean {
        var count = 0
        if (getPinyinEnabled(context)) count++
        if (getT9PinyinEnabled(context)) count++
        if (getWubiEnabled(context)) count++
        if (getShuangpinEnabled(context)) count++
        if (getZhenmaEnabled(context)) count++
        if (getZiranmaEnabled(context)) count++
        return count > 1
    }

    /**
     * Returns whether both Pinyin and Wubi are enabled (cycling mode).
     * @deprecated Use isMultipleChineseInputMethodsEnabled instead
     */
    fun isBothChineseInputMethodsEnabled(context: Context): Boolean {
        return isMultipleChineseInputMethodsEnabled(context)
    }

    /**
     * Returns the list of enabled Chinese input methods in cycle order.
     * Returns empty list if none is enabled.
     */
    fun getEnabledChineseInputMethods(context: Context): List<String> {
        val methods = mutableListOf<String>()
        if (getPinyinEnabled(context)) methods.add("pinyin")
        if (getT9PinyinEnabled(context)) methods.add("t9pinyin")
        if (getShuangpinEnabled(context)) methods.add("shuangpin")
        if (getZiranmaEnabled(context)) methods.add("ziranma")
        if (getWubiEnabled(context)) methods.add("wubi")
        if (getZhenmaEnabled(context)) methods.add("zhenma")
        return methods
    }

    /**
     * Gets the default input mode ("english", "pinyin", "shuangpin", or "wubi").
     * This is the mode the keyboard starts in when opening a new input field.
     */
    fun getDefaultInputMode(context: Context): String {
        return getPreferences(context).getString(KEY_DEFAULT_INPUT_MODE, DEFAULT_INPUT_MODE) ?: DEFAULT_INPUT_MODE
    }

    /**
     * Sets the default input mode ("english", "pinyin", "shuangpin", or "wubi").
     */
    fun setDefaultInputMode(context: Context, mode: String) {
        getPreferences(context).edit()
            .putString(KEY_DEFAULT_INPUT_MODE, mode)
            .apply()
    }

    /**
     * Gets the last used input mode (remembered across app restarts).
     * Returns the default input mode if not set.
     */
    fun getLastInputMode(context: Context): String {
        return getPreferences(context).getString(KEY_LAST_INPUT_MODE, getDefaultInputMode(context)) ?: getDefaultInputMode(context)
    }

    /**
     * Sets the last used input mode (to remember across app restarts).
     */
    fun setLastInputMode(context: Context, mode: String) {
        getPreferences(context).edit()
            .putString(KEY_LAST_INPUT_MODE, mode)
            .apply()
    }

    /**
     * Gets the effective input mode to use when starting.
     * Returns the last used mode, validated against enabled methods.
     * Falls back to "english" if the saved mode is not available.
     */
    fun getEffectiveStartupInputMode(context: Context): String {
        val lastMode = getLastInputMode(context)

        // Validate that the mode is available
        return when (lastMode) {
            "english" -> "english"
            "pinyin" -> if (getPinyinEnabled(context)) "pinyin" else "english"
            "t9pinyin" -> if (getT9PinyinEnabled(context)) "t9pinyin" else "english"
            "shuangpin" -> if (getShuangpinEnabled(context)) "shuangpin" else "english"
            "ziranma" -> if (getZiranmaEnabled(context)) "ziranma" else "english"
            "wubi" -> if (getWubiEnabled(context)) "wubi" else "english"
            "zhenma" -> if (getZhenmaEnabled(context)) "zhenma" else "english"
            else -> "english"
        }
    }

    /**
     * Gets whether next word prediction is enabled for Chinese input.
     */
    fun getChineseNextWordPredictionEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_CHINESE_NEXT_WORD_PREDICTION, DEFAULT_CHINESE_NEXT_WORD_PREDICTION)
    }

    /**
     * Sets whether next word prediction is enabled for Chinese input.
     */
    fun setChineseNextWordPredictionEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_CHINESE_NEXT_WORD_PREDICTION, enabled)
            .apply()
    }

    /**
     * Gets whether compact mode is enabled.
     * When enabled, the status bar is hidden when there are no suggestions/candidates.
     */
    fun getCompactModeEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_COMPACT_MODE, DEFAULT_COMPACT_MODE)
    }

    /**
     * Sets whether compact mode is enabled.
     */
    fun setCompactModeEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_COMPACT_MODE, enabled)
            .apply()
    }

    /**
     * Gets whether the voice input button is shown in the status bar.
     */
    fun getShowVoiceInputButton(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SHOW_VOICE_INPUT_BUTTON, DEFAULT_SHOW_VOICE_INPUT_BUTTON)
    }

    /**
     * Sets whether the voice input button is shown in the status bar.
     */
    fun setShowVoiceInputButton(context: Context, show: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SHOW_VOICE_INPUT_BUTTON, show)
            .apply()
    }

    /**
     * Gets whether clipboard history is enabled.
     */
    fun getClipboardHistoryEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_CLIPBOARD_HISTORY_ENABLED, DEFAULT_CLIPBOARD_HISTORY_ENABLED)
    }

    /**
     * Sets whether clipboard history is enabled.
     */
    fun setClipboardHistoryEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_CLIPBOARD_HISTORY_ENABLED, enabled)
            .apply()
    }

    /**
     * Gets whether the clipboard button is shown in the status bar.
     */
    fun getShowClipboardButton(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SHOW_CLIPBOARD_BUTTON, DEFAULT_SHOW_CLIPBOARD_BUTTON)
    }

    /**
     * Sets whether the clipboard button is shown in the status bar.
     */
    fun setShowClipboardButton(context: Context, show: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SHOW_CLIPBOARD_BUTTON, show)
            .apply()
    }

    /**
     * Gets whether Juying (巨硬) mode is enabled.
     * When enabled, 5 physical keys are used to select Chinese candidates directly.
     * Single-click selects candidate, double-click still works for pagination.
     */
    fun getJuyingModeEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_JUYING_MODE_ENABLED, DEFAULT_JUYING_MODE_ENABLED)
    }

    /**
     * Sets whether Juying (巨硬) mode is enabled.
     */
    fun setJuyingModeEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_JUYING_MODE_ENABLED, enabled)
            .apply()
    }

    /**
     * Gets whether Juying fixed positions mode is enabled.
     * When enabled, 5 suggestion words are at fixed locations with reserved arrow spaces.
     */
    fun getJuyingFixedPositions(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_JUYING_FIXED_POSITIONS, false)
    }

    /**
     * Sets whether Juying fixed positions mode is enabled.
     */
    fun setJuyingFixedPositions(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_JUYING_FIXED_POSITIONS, enabled)
            .apply()
    }

    /**
     * Gets whether Juying sound is enabled.
     * When enabled, pressing Juying keys (Shift/Sym/Space/Ctrl/Alt) to select candidates produces keyboard sound.
     */
    fun getJuyingSoundEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_JUYING_SOUND_ENABLED, false)
    }

    /**
     * Sets whether Juying sound is enabled.
     */
    fun setJuyingSoundEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_JUYING_SOUND_ENABLED, enabled)
            .apply()
    }

    /**
     * Gets whether Juying dynamic candidate count is enabled.
     * When enabled, the number of candidates shown per page adjusts based on phrase length
     * (fewer candidates for longer phrases to avoid cramped display).
     */
    fun getJuyingDynamicCandidateCount(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_JUYING_DYNAMIC_CANDIDATE_COUNT, false)
    }

    /**
     * Sets whether Juying dynamic candidate count is enabled.
     */
    fun setJuyingDynamicCandidateCount(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_JUYING_DYNAMIC_CANDIDATE_COUNT, enabled)
            .apply()
    }

    /**
     * Gets whether touchpad page navigation is enabled.
     * When enabled, swiping up/down on the touchpad navigates candidate pages.
     */
    fun getTouchpadPageEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_TOUCHPAD_PAGE_ENABLED, DEFAULT_TOUCHPAD_PAGE_ENABLED)
    }

    /**
     * Sets whether touchpad page navigation is enabled.
     */
    fun setTouchpadPageEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_TOUCHPAD_PAGE_ENABLED, enabled)
            .apply()
    }

    /**
     * Gets the Alt double-click delay for next page navigation in milliseconds.
     * This is the maximum time between two Alt key presses to trigger next page.
     */
    fun getAltDoubleClickDelay(context: Context): Int {
        return getPreferences(context).getInt(KEY_ALT_DOUBLE_CLICK_DELAY, DEFAULT_ALT_DOUBLE_CLICK_DELAY)
    }

    /**
     * Sets the Alt double-click delay for next page navigation in milliseconds.
     */
    fun setAltDoubleClickDelay(context: Context, delayMs: Int) {
        getPreferences(context).edit()
            .putInt(KEY_ALT_DOUBLE_CLICK_DELAY, delayMs)
            .apply()
    }

    /**
     * Gets the app interface language setting.
     * @return "en" (English) or "zh" (Chinese)
     */
    fun getAppLanguage(context: Context): String {
        val lang = getPreferences(context).getString(KEY_APP_LANGUAGE, "en") ?: "en"
        // Migrate old "system" setting to "en"
        return if (lang == "system") "en" else lang
    }

    /**
     * Sets the app interface language.
     * @param language "en" or "zh"
     */
    fun setAppLanguage(context: Context, language: String) {
        getPreferences(context).edit()
            .putString(KEY_APP_LANGUAGE, language)
            .apply()
    }

    /**
     * Gets whether the memory function is enabled.
     * When enabled, candidate words are reordered based on user selection frequency.
     */
    fun getMemoryFunctionEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_MEMORY_FUNCTION_ENABLED, DEFAULT_MEMORY_FUNCTION_ENABLED)
    }

    /**
     * Sets whether the memory function is enabled.
     */
    fun setMemoryFunctionEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_MEMORY_FUNCTION_ENABLED, enabled)
            .apply()
    }

    /**
     * Gets whether Shift and Alt buttons are swapped (for Titan 2).
     * When enabled, the physical Alt button (1st position) sends Shift keycode,
     * and the physical Shift button (5th position) sends Alt keycode.
     * This affects Juying mode candidate selection: Alt selects 1st candidate, Shift selects 5th.
     */
    fun getShiftAltSwapped(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SHIFT_ALT_SWAPPED, DEFAULT_SHIFT_ALT_SWAPPED)
    }

    /**
     * Sets whether Shift and Alt buttons are swapped.
     */
    fun setShiftAltSwapped(context: Context, swapped: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SHIFT_ALT_SWAPPED, swapped)
            .apply()
    }

    /**
     * Gets the maximum number of candidates to display in non-Juying mode.
     * Default is 9 candidates.
     */
    fun getMaxCandidatesNonJuying(context: Context): Int {
        return getPreferences(context).getInt(KEY_MAX_CANDIDATES_NON_JUYING, DEFAULT_MAX_CANDIDATES_NON_JUYING)
    }

    /**
     * Sets the maximum number of candidates to display in non-Juying mode.
     */
    fun setMaxCandidatesNonJuying(context: Context, maxCandidates: Int) {
        getPreferences(context).edit()
            .putInt(KEY_MAX_CANDIDATES_NON_JUYING, maxCandidates)
            .apply()
    }

    /**
     * Gets whether Shift+Enter should toggle between Chinese input methods.
     */
    fun isShiftEnterToggleInputEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SHIFT_ENTER_TOGGLE_INPUT, DEFAULT_SHIFT_ENTER_TOGGLE_INPUT)
    }

    /**
     * Sets whether Shift+Enter should toggle between Chinese input methods.
     */
    fun setShiftEnterToggleInputEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SHIFT_ENTER_TOGGLE_INPUT, enabled)
            .apply()
    }

    /**
     * Returns whether the virtual (on-screen) keyboard is enabled.
     * When enabled, a soft keyboard is shown for devices without physical keyboards.
     */
    fun isVirtualKeyboardEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_VIRTUAL_KEYBOARD_ENABLED, DEFAULT_VIRTUAL_KEYBOARD_ENABLED)
    }

    /**
     * Sets whether the virtual (on-screen) keyboard is enabled.
     */
    fun setVirtualKeyboardEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_VIRTUAL_KEYBOARD_ENABLED, enabled)
            .apply()
    }

    /**
     * Gets whether auto-phrase memory is enabled.
     * When enabled, new phrases typed character-by-character are automatically learned
     * after being typed twice.
     */
    fun isAutoPhrasMemoryEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTO_PHRASE_MEMORY_ENABLED, DEFAULT_AUTO_PHRASE_MEMORY_ENABLED)
    }

    /**
     * Sets whether auto-phrase memory is enabled.
     */
    fun setAutoPhraseMemoryEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_AUTO_PHRASE_MEMORY_ENABLED, enabled)
            .apply()
    }

    /**
     * Gets whether Wubi with Pinyin mode is enabled.
     * When enabled, Pinyin candidates are shown after Wubi candidates in Wubi mode.
     */
    fun isWubiWithPinyinEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_WUBI_WITH_PINYIN_ENABLED, DEFAULT_WUBI_WITH_PINYIN_ENABLED)
    }

    /**
     * Sets whether Wubi with Pinyin mode is enabled.
     */
    fun setWubiWithPinyinEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_WUBI_WITH_PINYIN_ENABLED, enabled)
            .apply()
    }

    /**
     * Gets whether Wubi phrases should be displayed before single characters.
     * When true, multi-character phrases appear first; when false, single characters appear first.
     */
    fun isWubiPhrasesFirst(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_WUBI_PHRASES_FIRST, DEFAULT_WUBI_PHRASES_FIRST)
    }

    /**
     * Sets whether Wubi phrases should be displayed before single characters.
     */
    fun setWubiPhrasesFirst(context: Context, phrasesFirst: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_WUBI_PHRASES_FIRST, phrasesFirst)
            .apply()
    }

    /**
     * Gets whether Wubi auto-commits when a 4-char code has only one candidate.
     */
    fun isWubiAutoCommitSingle(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_WUBI_AUTO_COMMIT_SINGLE, DEFAULT_WUBI_AUTO_COMMIT_SINGLE)
    }

    /**
     * Sets whether Wubi auto-commits when a 4-char code has only one candidate.
     */
    fun setWubiAutoCommitSingle(context: Context, autoCommit: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_WUBI_AUTO_COMMIT_SINGLE, autoCommit)
            .apply()
    }

    /**
     * Gets whether Wubi auto-commits on 5th letter (overflow) and starts a new word.
     */
    fun isWubiAutoCommitOverflow(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_WUBI_AUTO_COMMIT_OVERFLOW, DEFAULT_WUBI_AUTO_COMMIT_OVERFLOW)
    }

    /**
     * Sets whether Wubi auto-commits on 5th letter (overflow) and starts a new word.
     */
    fun setWubiAutoCommitOverflow(context: Context, autoCommit: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_WUBI_AUTO_COMMIT_OVERFLOW, autoCommit)
            .apply()
    }

    /**
     * Gets the Wubi Z key mode.
     * @return "disabled", "wildcard", or "symbol"
     */
    fun getWubiZKeyMode(context: Context): String {
        return getPreferences(context).getString(KEY_WUBI_Z_KEY_MODE, DEFAULT_WUBI_Z_KEY_MODE) ?: DEFAULT_WUBI_Z_KEY_MODE
    }

    /**
     * Sets the Wubi Z key mode.
     * @param mode "disabled", "wildcard", or "symbol"
     */
    fun setWubiZKeyMode(context: Context, mode: String) {
        getPreferences(context).edit()
            .putString(KEY_WUBI_Z_KEY_MODE, mode)
            .apply()
    }

    /**
     * Returns whether Wubi Z key wildcard mode is enabled.
     */
    fun isWubiZKeyWildcardEnabled(context: Context): Boolean {
        return getWubiZKeyMode(context) == "wildcard"
    }

    /**
     * Returns whether Wubi Z key symbol mode is enabled.
     */
    fun isWubiZKeySymbolEnabled(context: Context): Boolean {
        return getWubiZKeyMode(context) == "symbol"
    }

    /**
     * Gets the candidate/suggestion font size in SP.
     */
    fun getCandidateFontSize(context: Context): Int {
        return getPreferences(context).getInt(KEY_CANDIDATE_FONT_SIZE, DEFAULT_CANDIDATE_FONT_SIZE)
    }

    /**
     * Sets the candidate/suggestion font size in SP.
     */
    fun setCandidateFontSize(context: Context, size: Int) {
        val clampedSize = size.coerceIn(MIN_CANDIDATE_FONT_SIZE, MAX_CANDIDATE_FONT_SIZE)
        getPreferences(context).edit()
            .putInt(KEY_CANDIDATE_FONT_SIZE, clampedSize)
            .apply()
    }

    /**
     * Gets the minimum candidate font size.
     */
    fun getMinCandidateFontSize(): Int = MIN_CANDIDATE_FONT_SIZE

    /**
     * Gets the maximum candidate font size.
     */
    fun getMaxCandidateFontSize(): Int = MAX_CANDIDATE_FONT_SIZE

    /**
     * Gets the default candidate font size.
     */
    fun getDefaultCandidateFontSize(): Int = DEFAULT_CANDIDATE_FONT_SIZE

    /**
     * Gets the minimum font size for suggestions (used when text is long and needs wrapping).
     */
    fun getSuggestionMinFontSize(context: Context): Int {
        val maxAllowed = getCandidateFontSize(context)  // Can't exceed current font size
        val stored = getPreferences(context).getInt(KEY_SUGGESTION_MIN_FONT_SIZE, DEFAULT_SUGGESTION_MIN_FONT_SIZE)
        return stored.coerceIn(MIN_SUGGESTION_MIN_FONT_SIZE, maxAllowed)
    }

    /**
     * Sets the minimum font size for suggestions.
     */
    fun setSuggestionMinFontSize(context: Context, size: Int) {
        val maxAllowed = getCandidateFontSize(context)
        val clampedSize = size.coerceIn(MIN_SUGGESTION_MIN_FONT_SIZE, maxAllowed)
        getPreferences(context).edit()
            .putInt(KEY_SUGGESTION_MIN_FONT_SIZE, clampedSize)
            .apply()
    }

    /**
     * Gets the minimum allowed value for suggestion min font size.
     */
    fun getMinSuggestionMinFontSize(): Int = MIN_SUGGESTION_MIN_FONT_SIZE

    /**
     * Gets the maximum allowed value for suggestion min font size (same as current font size).
     */
    fun getMaxSuggestionMinFontSize(context: Context): Int = getCandidateFontSize(context)

    /**
     * Gets the default suggestion min font size.
     */
    fun getDefaultSuggestionMinFontSize(): Int = DEFAULT_SUGGESTION_MIN_FONT_SIZE

    /**
     * Returns whether auto-adjust status bar height is enabled.
     * When enabled, status bar height will expand to fit long suggestions.
     */
    fun isAutoAdjustStatusBarHeight(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTO_ADJUST_STATUS_BAR_HEIGHT, false)
    }

    /**
     * Sets whether auto-adjust status bar height is enabled.
     */
    fun setAutoAdjustStatusBarHeight(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_AUTO_ADJUST_STATUS_BAR_HEIGHT, enabled)
            .apply()
    }

    /**
     * Gets the status bar height in DIP.
     */
    fun getStatusBarHeight(context: Context): Int {
        return getPreferences(context).getInt(KEY_STATUS_BAR_HEIGHT, DEFAULT_STATUS_BAR_HEIGHT)
    }

    /**
     * Sets the status bar height in DIP.
     */
    fun setStatusBarHeight(context: Context, height: Int) {
        val clampedHeight = height.coerceIn(MIN_STATUS_BAR_HEIGHT, MAX_STATUS_BAR_HEIGHT)
        getPreferences(context).edit()
            .putInt(KEY_STATUS_BAR_HEIGHT, clampedHeight)
            .apply()
    }

    /**
     * Gets the minimum status bar height.
     */
    fun getMinStatusBarHeight(): Int = MIN_STATUS_BAR_HEIGHT

    /**
     * Gets the maximum status bar height.
     */
    fun getMaxStatusBarHeight(): Int = MAX_STATUS_BAR_HEIGHT

    /**
     * Gets the default status bar height.
     */
    fun getDefaultStatusBarHeight(): Int = DEFAULT_STATUS_BAR_HEIGHT

    /**
     * Gets the suggestion background height as percentage of status bar (50-100).
     */
    fun getSuggestionHeightPercent(context: Context): Int {
        return getPreferences(context).getInt(KEY_SUGGESTION_HEIGHT_PERCENT, DEFAULT_SUGGESTION_HEIGHT_PERCENT)
    }

    /**
     * Sets the suggestion background height as percentage of status bar (50-100).
     */
    fun setSuggestionHeightPercent(context: Context, percent: Int) {
        val clampedPercent = percent.coerceIn(MIN_SUGGESTION_HEIGHT_PERCENT, MAX_SUGGESTION_HEIGHT_PERCENT)
        getPreferences(context).edit()
            .putInt(KEY_SUGGESTION_HEIGHT_PERCENT, clampedPercent)
            .apply()
    }

    /**
     * Gets the minimum suggestion height percentage.
     */
    fun getMinSuggestionHeightPercent(): Int = MIN_SUGGESTION_HEIGHT_PERCENT

    /**
     * Gets the maximum suggestion height percentage.
     */
    fun getMaxSuggestionHeightPercent(): Int = MAX_SUGGESTION_HEIGHT_PERCENT

    /**
     * Gets the default suggestion height percentage.
     */
    fun getDefaultSuggestionHeightPercent(): Int = DEFAULT_SUGGESTION_HEIGHT_PERCENT

    /**
     * Gets the virtual keyboard key height in DIP.
     */
    fun getVirtualKeyboardHeight(context: Context): Int {
        return getPreferences(context).getInt(KEY_VIRTUAL_KEYBOARD_HEIGHT, DEFAULT_VIRTUAL_KEYBOARD_HEIGHT)
    }

    /**
     * Sets the virtual keyboard key height in DIP.
     */
    fun setVirtualKeyboardHeight(context: Context, height: Int) {
        val clampedHeight = height.coerceIn(MIN_VIRTUAL_KEYBOARD_HEIGHT, MAX_VIRTUAL_KEYBOARD_HEIGHT)
        getPreferences(context).edit()
            .putInt(KEY_VIRTUAL_KEYBOARD_HEIGHT, clampedHeight)
            .apply()
    }

    /**
     * Gets the minimum virtual keyboard height.
     */
    fun getMinVirtualKeyboardHeight(): Int = MIN_VIRTUAL_KEYBOARD_HEIGHT

    /**
     * Gets the maximum virtual keyboard height.
     */
    fun getMaxVirtualKeyboardHeight(): Int = MAX_VIRTUAL_KEYBOARD_HEIGHT

    /**
     * Gets the default virtual keyboard height.
     */
    fun getDefaultVirtualKeyboardHeight(): Int = DEFAULT_VIRTUAL_KEYBOARD_HEIGHT

    /**
     * Gets whether keyboard sound effect is enabled (for both physical and virtual keyboards).
     */
    fun isKeyboardSoundEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_KEYBOARD_SOUND, DEFAULT_VIRTUAL_KEYBOARD_SOUND)
    }

    /**
     * Sets whether keyboard sound effect is enabled (for both physical and virtual keyboards).
     */
    fun setKeyboardSoundEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_KEYBOARD_SOUND, enabled)
            .apply()
    }

    /**
     * Gets the keyboard sound type: "mechanical", "soft", or "typewriter".
     */
    fun getKeyboardSoundType(context: Context): String {
        return getPreferences(context).getString(KEY_KEYBOARD_SOUND_TYPE, DEFAULT_KEYBOARD_SOUND_TYPE) ?: DEFAULT_KEYBOARD_SOUND_TYPE
    }

    /**
     * Sets the keyboard sound type: "mechanical", "soft", or "typewriter".
     */
    fun setKeyboardSoundType(context: Context, type: String) {
        getPreferences(context).edit()
            .putString(KEY_KEYBOARD_SOUND_TYPE, type)
            .apply()
    }

    /**
     * Gets the keyboard sound volume (0-100).
     */
    fun getKeyboardSoundVolume(context: Context): Int {
        return getPreferences(context).getInt(KEY_KEYBOARD_SOUND_VOLUME, DEFAULT_KEYBOARD_SOUND_VOLUME)
    }

    /**
     * Sets the keyboard sound volume (0-100).
     */
    fun setKeyboardSoundVolume(context: Context, volume: Int) {
        getPreferences(context).edit()
            .putInt(KEY_KEYBOARD_SOUND_VOLUME, volume.coerceIn(0, 100))
            .apply()
    }

    /**
     * Gets the custom sound file path (internal storage path).
     * Returns null if no custom sound is set.
     */
    fun getCustomSoundPath(context: Context): String? {
        return getPreferences(context).getString(KEY_CUSTOM_SOUND_PATH, null)
    }

    /**
     * Sets the custom sound file path.
     */
    fun setCustomSoundPath(context: Context, path: String?) {
        getPreferences(context).edit()
            .putString(KEY_CUSTOM_SOUND_PATH, path)
            .apply()
    }

    /**
     * Copies a sound file from the given URI to internal storage.
     * Returns the internal storage path, or null if copy failed.
     */
    fun copyCustomSoundFile(context: Context, inputStream: InputStream): String? {
        return try {
            val soundDir = File(context.filesDir, "custom_sounds")
            if (!soundDir.exists()) {
                soundDir.mkdirs()
            }
            val destFile = File(soundDir, "custom_keyboard_sound.mp3")
            FileOutputStream(destFile).use { output ->
                inputStream.copyTo(output)
            }
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy custom sound file", e)
            null
        }
    }

    /**
     * Deletes the custom sound file if it exists.
     */
    fun deleteCustomSoundFile(context: Context) {
        val path = getCustomSoundPath(context)
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
        setCustomSoundPath(context, null)
    }

    /**
     * Gets whether virtual keyboard vibration is enabled.
     */
    fun isVirtualKeyboardVibrationEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_VIRTUAL_KEYBOARD_VIBRATION, DEFAULT_VIRTUAL_KEYBOARD_VIBRATION)
    }

    /**
     * Sets whether virtual keyboard vibration is enabled.
     */
    fun setVirtualKeyboardVibrationEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_VIRTUAL_KEYBOARD_VIBRATION, enabled)
            .apply()
    }

    /**
     * Gets whether partial pinyin matching is enabled.
     */
    fun isPartialPinyinMatchingEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_PARTIAL_PINYIN_MATCHING, DEFAULT_PARTIAL_PINYIN_MATCHING)
    }

    /**
     * Sets whether partial pinyin matching is enabled.
     */
    fun setPartialPinyinMatchingEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_PARTIAL_PINYIN_MATCHING, enabled)
            .apply()
    }

    /**
     * Gets whether abbreviation input (首字母) is enabled.
     * When enabled, users can type first letters of pinyin syllables to get phrase suggestions.
     */
    fun isAbbreviationInputEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_ABBREVIATION_INPUT_ENABLED, DEFAULT_ABBREVIATION_INPUT_ENABLED)
    }

    /**
     * Sets whether abbreviation input (首字母) is enabled.
     */
    fun setAbbreviationInputEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_ABBREVIATION_INPUT_ENABLED, enabled)
            .apply()
    }

    /**
     * Gets whether virtual keyboard toggle button is shown in status bar.
     */
    fun getShowVirtualKeyboardButton(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SHOW_VIRTUAL_KEYBOARD_BUTTON, DEFAULT_SHOW_VIRTUAL_KEYBOARD_BUTTON)
    }

    /**
     * Sets whether virtual keyboard toggle button is shown in status bar.
     */
    fun setShowVirtualKeyboardButton(context: Context, show: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SHOW_VIRTUAL_KEYBOARD_BUTTON, show)
            .apply()
    }

    /**
     * Gets whether status bar should be semi-transparent.
     */
    fun isSemiTransparentStatusBar(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SEMI_TRANSPARENT_STATUS_BAR, DEFAULT_SEMI_TRANSPARENT_STATUS_BAR)
    }

    /**
     * Sets whether status bar should be semi-transparent.
     */
    fun setSemiTransparentStatusBar(context: Context, transparent: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SEMI_TRANSPARENT_STATUS_BAR, transparent)
            .apply()
    }

    /**
     * Gets whether 3D effect is enabled for status bar buttons.
     */
    fun is3DEffectEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_3D_EFFECT_ENABLED, DEFAULT_3D_EFFECT_ENABLED)
    }

    /**
     * Sets whether 3D effect is enabled for status bar buttons.
     */
    fun set3DEffectEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_3D_EFFECT_ENABLED, enabled)
            .apply()
    }

    /**
     * Returns whether offline voice input (Sherpa-ONNX) is enabled.
     * When enabled, uses Sherpa-ONNX for offline Mandarin Chinese speech recognition.
     * When disabled, uses online Google voice recognition.
     */
    fun isOfflineVoiceInput(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_OFFLINE_VOICE_INPUT, DEFAULT_OFFLINE_VOICE_INPUT)
    }

    /**
     * Sets whether to use offline voice input (Sherpa-ONNX).
     */
    fun setOfflineVoiceInput(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_OFFLINE_VOICE_INPUT, enabled)
            .apply()
    }

    /**
     * Gets the path to the Sherpa-ONNX model zip file.
     * Returns null if not set.
     */
    fun getSherpaModelPath(context: Context): String? {
        return getPreferences(context).getString(KEY_SHERPA_MODEL_PATH, null)
    }

    /**
     * Sets the path to the Sherpa-ONNX model zip file.
     * Pass null to clear the path.
     */
    fun setSherpaModelPath(context: Context, path: String?) {
        getPreferences(context).edit()
            .putString(KEY_SHERPA_MODEL_PATH, path)
            .apply()
    }

    /**
     * Gets the path to the Neural Pinyin ONNX model zip file.
     * Returns null if not set.
     */
    fun getNeuralPinyinModelPath(context: Context): String? {
        return getPreferences(context).getString(KEY_NEURAL_PINYIN_MODEL_PATH, null)
    }

    /**
     * Sets the path to the Neural Pinyin ONNX model zip file.
     * Pass null to clear the path.
     */
    fun setNeuralPinyinModelPath(context: Context, path: String?) {
        getPreferences(context).edit()
            .putString(KEY_NEURAL_PINYIN_MODEL_PATH, path)
            .apply()
    }

    /**
     * Returns whether neural pinyin (deep learning based) is enabled.
     * When enabled and model is loaded, long pinyin sentences use neural network for conversion.
     */
    fun isNeuralPinyinEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_NEURAL_PINYIN_ENABLED, false)
    }

    /**
     * Sets whether neural pinyin is enabled.
     */
    fun setNeuralPinyinEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_NEURAL_PINYIN_ENABLED, enabled)
            .apply()
    }

    /**
     * Gets the minimum number of letters required to trigger neural pinyin.
     * Default is 8 letters.
     */
    fun getNeuralPinyinMinLetters(context: Context): Int {
        return getPreferences(context).getInt(KEY_NEURAL_PINYIN_MIN_LETTERS, 8)
    }

    /**
     * Sets the minimum number of letters required to trigger neural pinyin.
     */
    fun setNeuralPinyinMinLetters(context: Context, minLetters: Int) {
        getPreferences(context).edit()
            .putInt(KEY_NEURAL_PINYIN_MIN_LETTERS, minLetters)
            .apply()
    }

    /**
     * Gets whether neural pinyin prediction should be prioritized as top suggestion.
     * @return true if neural prediction should appear first, false to use frequency-based sorting
     */
    fun isNeuralPinyinPriority(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_NEURAL_PINYIN_PRIORITY, false)
    }

    /**
     * Sets whether neural pinyin prediction should be prioritized as top suggestion.
     */
    fun setNeuralPinyinPriority(context: Context, priority: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_NEURAL_PINYIN_PRIORITY, priority)
            .apply()
    }

    /**
     * Gets the number of neural pinyin predictions to show.
     * @return 1 (default) or 3
     */
    fun getNeuralPinyinCount(context: Context): Int {
        return getPreferences(context).getInt(KEY_NEURAL_PINYIN_COUNT, 1)
    }

    /**
     * Sets the number of neural pinyin predictions to show.
     * @param count 1 or 3
     */
    fun setNeuralPinyinCount(context: Context, count: Int) {
        getPreferences(context).edit()
            .putInt(KEY_NEURAL_PINYIN_COUNT, count)
            .apply()
    }

    /**
     * Gets the HMM model size setting.
     * @return "small" (1.9MB, fastest), "standard" (2.3MB, default), or "large" (3.2MB, most accurate)
     */
    fun getHmmModelSize(context: Context): String {
        return getPreferences(context).getString(KEY_HMM_MODEL_SIZE, "standard") ?: "standard"
    }

    /**
     * Sets the HMM model size.
     * @param size "small", "standard", or "large"
     */
    fun setHmmModelSize(context: Context, size: String) {
        getPreferences(context).edit()
            .putString(KEY_HMM_MODEL_SIZE, size)
            .apply()
    }

    /**
     * Gets the HMM model file name based on the current setting.
     */
    fun getHmmModelFileName(context: Context): String {
        return when (getHmmModelSize(context)) {
            "small" -> "common/pinyin/hmm_model_small.dat"
            "large" -> "common/pinyin/hmm_model_large.dat"
            else -> "common/pinyin/hmm_model_standard.dat"
        }
    }

    /**
     * Returns whether voice auto-insert is enabled.
     * When enabled, recognized text is automatically inserted after detecting silence.
     */
    fun isVoiceAutoInsert(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_VOICE_AUTO_INSERT, DEFAULT_VOICE_AUTO_INSERT)
    }

    /**
     * Sets whether to auto-insert voice recognition result.
     */
    fun setVoiceAutoInsert(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_VOICE_AUTO_INSERT, enabled)
            .apply()
    }

    /**
     * Returns whether Chinese punctuation should be used for voice input.
     * When true, uses 。, when false, uses .
     */
    fun isVoiceChinesePunctuation(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_VOICE_CHINESE_PUNCTUATION, DEFAULT_VOICE_CHINESE_PUNCTUATION)
    }

    /**
     * Sets whether to use Chinese punctuation for voice input.
     */
    fun setVoiceChinesePunctuation(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_VOICE_CHINESE_PUNCTUATION, enabled)
            .apply()
    }

    /**
     * Returns whether punctuation should be added to voice input text.
     * When enabled, periods are added at end and commas on pauses.
     */
    fun isVoiceAddPunctuation(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_VOICE_ADD_PUNCTUATION, DEFAULT_VOICE_ADD_PUNCTUATION)
    }

    /**
     * Sets whether to add punctuation to voice input text.
     */
    fun setVoiceAddPunctuation(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_VOICE_ADD_PUNCTUATION, enabled)
            .apply()
    }

    /**
     * Returns whether holding space key triggers voice input.
     */
    fun isHoldSpaceForVoice(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_HOLD_SPACE_FOR_VOICE, DEFAULT_HOLD_SPACE_FOR_VOICE)
    }

    /**
     * Sets whether holding space key triggers voice input.
     */
    fun setHoldSpaceForVoice(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_HOLD_SPACE_FOR_VOICE, enabled)
            .apply()
    }

    /**
     * Returns the duration to hold space key for voice input in milliseconds.
     */
    fun getHoldSpaceDuration(context: Context): Long {
        return getPreferences(context).getLong(KEY_HOLD_SPACE_DURATION, DEFAULT_HOLD_SPACE_DURATION)
    }

    /**
     * Sets the duration to hold space key for voice input in milliseconds.
     */
    fun setHoldSpaceDuration(context: Context, duration: Long) {
        val clampedValue = duration.coerceIn(MIN_HOLD_SPACE_DURATION, MAX_HOLD_SPACE_DURATION)
        getPreferences(context).edit()
            .putLong(KEY_HOLD_SPACE_DURATION, clampedValue)
            .apply()
    }

    /**
     * Returns the minimum allowed value for hold space duration.
     */
    fun getMinHoldSpaceDuration(): Long = MIN_HOLD_SPACE_DURATION

    /**
     * Returns the maximum allowed value for hold space duration.
     */
    fun getMaxHoldSpaceDuration(): Long = MAX_HOLD_SPACE_DURATION

    /**
     * Returns whether the LED status indicator strip is shown.
     */
    fun isShowLedStatus(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SHOW_LED_STATUS, DEFAULT_SHOW_LED_STATUS)
    }

    /**
     * Sets whether to show the LED status indicator strip.
     */
    fun setShowLedStatus(context: Context, show: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SHOW_LED_STATUS, show)
            .apply()
    }

    /**
     * Returns whether the SYM button is shown in the status bar.
     */
    fun isShowSymButton(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SHOW_SYM_BUTTON, DEFAULT_SHOW_SYM_BUTTON)
    }

    /**
     * Sets whether to show the SYM button in the status bar.
     */
    fun setShowSymButton(context: Context, show: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SHOW_SYM_BUTTON, show)
            .apply()
    }

    /**
     * Returns whether the sound toggle button is shown in the status bar.
     */
    fun isShowSoundToggleButton(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SHOW_SOUND_TOGGLE_BUTTON, DEFAULT_SHOW_SOUND_TOGGLE_BUTTON)
    }

    /**
     * Sets whether to show the sound toggle button in the status bar.
     */
    fun setShowSoundToggleButton(context: Context, show: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SHOW_SOUND_TOGGLE_BUTTON, show)
            .apply()
    }

    /**
     * Returns whether punctuation buttons (comma/period) are shown on sides in Juying mode.
     */
    fun isJuyingPunctuationButtons(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_JUYING_PUNCTUATION_BUTTONS, DEFAULT_JUYING_PUNCTUATION_BUTTONS)
    }

    /**
     * Sets whether to show punctuation buttons (comma/period) on sides in Juying mode.
     */
    fun setJuyingPunctuationButtons(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_JUYING_PUNCTUATION_BUTTONS, enabled)
            .apply()
    }

    /**
     * Gets the left punctuation character (Shift position) for quick punctuation input.
     */
    fun getJuyingPunctuationLeft(context: Context): String {
        return getPreferences(context).getString(KEY_JUYING_PUNCTUATION_LEFT, DEFAULT_JUYING_PUNCTUATION_LEFT)
            ?: DEFAULT_JUYING_PUNCTUATION_LEFT
    }

    /**
     * Sets the left punctuation character (Shift position) for quick punctuation input.
     */
    fun setJuyingPunctuationLeft(context: Context, punctuation: String) {
        getPreferences(context).edit()
            .putString(KEY_JUYING_PUNCTUATION_LEFT, punctuation)
            .apply()
    }

    /**
     * Gets the right punctuation character (Alt position) for quick punctuation input.
     */
    fun getJuyingPunctuationRight(context: Context): String {
        return getPreferences(context).getString(KEY_JUYING_PUNCTUATION_RIGHT, DEFAULT_JUYING_PUNCTUATION_RIGHT)
            ?: DEFAULT_JUYING_PUNCTUATION_RIGHT
    }

    /**
     * Sets the right punctuation character (Alt position) for quick punctuation input.
     */
    fun setJuyingPunctuationRight(context: Context, punctuation: String) {
        getPreferences(context).edit()
            .putString(KEY_JUYING_PUNCTUATION_RIGHT, punctuation)
            .apply()
    }

    /**
     * Gets the left punctuation character when Shift is active.
     */
    fun getJuyingPunctuationLeftShifted(context: Context): String {
        return getPreferences(context).getString(KEY_JUYING_PUNCTUATION_LEFT_SHIFTED, DEFAULT_JUYING_PUNCTUATION_LEFT_SHIFTED)
            ?: DEFAULT_JUYING_PUNCTUATION_LEFT_SHIFTED
    }

    /**
     * Sets the left punctuation character when Shift is active.
     */
    fun setJuyingPunctuationLeftShifted(context: Context, punctuation: String) {
        getPreferences(context).edit()
            .putString(KEY_JUYING_PUNCTUATION_LEFT_SHIFTED, punctuation)
            .apply()
    }

    /**
     * Gets the right punctuation character when Shift is active.
     */
    fun getJuyingPunctuationRightShifted(context: Context): String {
        return getPreferences(context).getString(KEY_JUYING_PUNCTUATION_RIGHT_SHIFTED, DEFAULT_JUYING_PUNCTUATION_RIGHT_SHIFTED)
            ?: DEFAULT_JUYING_PUNCTUATION_RIGHT_SHIFTED
    }

    /**
     * Sets the right punctuation character when Shift is active.
     */
    fun setJuyingPunctuationRightShifted(context: Context, punctuation: String) {
        getPreferences(context).edit()
            .putString(KEY_JUYING_PUNCTUATION_RIGHT_SHIFTED, punctuation)
            .apply()
    }

    /**
     * Converts English punctuation to Chinese equivalent.
     */
    fun getChinesePunctuation(englishPunctuation: String): String {
        return when (englishPunctuation) {
            "," -> "，"
            "." -> "。"
            "?" -> "？"
            "!" -> "！"
            ":" -> "："
            ";" -> "；"
            "'" -> "'"
            "\"" -> "\""
            "(" -> "（"
            ")" -> "）"
            "[" -> "【"
            "]" -> "】"
            "<" -> "《"
            ">" -> "》"
            "/" -> "、"
            "-" -> "—"
            else -> englishPunctuation
        }
    }

    /**
     * Gets the status bar theme ID.
     */
    fun getStatusBarTheme(context: Context): String {
        return getPreferences(context).getString(KEY_STATUS_BAR_THEME, DEFAULT_STATUS_BAR_THEME) ?: DEFAULT_STATUS_BAR_THEME
    }

    /**
     * Sets the status bar theme ID.
     */
    fun setStatusBarTheme(context: Context, themeId: String) {
        getPreferences(context).edit()
            .putString(KEY_STATUS_BAR_THEME, themeId)
            .apply()
    }

    // ==================== Day/Night Theme Settings ====================

    /**
     * Gets whether day/night theme switching is enabled.
     */
    fun isDayNightThemeEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_DAY_NIGHT_THEME_ENABLED, DEFAULT_DAY_NIGHT_THEME_ENABLED)
    }

    /**
     * Sets whether day/night theme switching is enabled.
     */
    fun setDayNightThemeEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_DAY_NIGHT_THEME_ENABLED, enabled)
            .apply()
    }

    /**
     * Gets the theme ID for daytime.
     */
    fun getDayTheme(context: Context): String {
        return getPreferences(context).getString(KEY_DAY_THEME, DEFAULT_DAY_THEME) ?: DEFAULT_DAY_THEME
    }

    /**
     * Sets the theme ID for daytime.
     */
    fun setDayTheme(context: Context, themeId: String) {
        getPreferences(context).edit()
            .putString(KEY_DAY_THEME, themeId)
            .apply()
    }

    /**
     * Gets the theme ID for nighttime.
     */
    fun getNightTheme(context: Context): String {
        return getPreferences(context).getString(KEY_NIGHT_THEME, DEFAULT_NIGHT_THEME) ?: DEFAULT_NIGHT_THEME
    }

    /**
     * Sets the theme ID for nighttime.
     */
    fun setNightTheme(context: Context, themeId: String) {
        getPreferences(context).edit()
            .putString(KEY_NIGHT_THEME, themeId)
            .apply()
    }

    /**
     * Gets the hour when daytime starts (0-23).
     */
    fun getDayStartHour(context: Context): Int {
        return getPreferences(context).getInt(KEY_DAY_START_HOUR, DEFAULT_DAY_START_HOUR)
    }

    /**
     * Sets the hour when daytime starts (0-23).
     */
    fun setDayStartHour(context: Context, hour: Int) {
        getPreferences(context).edit()
            .putInt(KEY_DAY_START_HOUR, hour.coerceIn(0, 23))
            .apply()
    }

    /**
     * Gets the hour when nighttime starts (0-23).
     */
    fun getNightStartHour(context: Context): Int {
        return getPreferences(context).getInt(KEY_NIGHT_START_HOUR, DEFAULT_NIGHT_START_HOUR)
    }

    /**
     * Sets the hour when nighttime starts (0-23).
     */
    fun setNightStartHour(context: Context, hour: Int) {
        getPreferences(context).edit()
            .putInt(KEY_NIGHT_START_HOUR, hour.coerceIn(0, 23))
            .apply()
    }

    /**
     * Checks if the system is currently in light mode (not dark mode).
     * Uses Android's UI_MODE_NIGHT configuration from the application context
     * to ensure we get the most up-to-date configuration after system theme changes.
     */
    fun isSystemLightMode(context: Context): Boolean {
        // Use applicationContext to get the latest system configuration
        val appContext = context.applicationContext
        val nightModeFlags = appContext.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Gets the appropriate theme ID based on day/night setting and system theme.
     * If day/night mode is disabled, returns the regular status bar theme.
     * If day/night mode is enabled, follows the system dark/light theme:
     * - System light mode → uses day theme (light)
     * - System dark mode → uses night theme (dark)
     */
    fun getEffectiveTheme(context: Context): String {
        return if (isDayNightThemeEnabled(context)) {
            if (isSystemLightMode(context)) getDayTheme(context) else getNightTheme(context)
        } else {
            getStatusBarTheme(context)
        }
    }

    /**
     * Gets the theme type (light/dark) for a given theme ID.
     * Returns "dark" for unknown themes.
     */
    fun getThemeType(context: Context, themeId: String): String {
        // Check custom theme slots
        if (themeId == it.neuralrad.coolwulf.inputmethod.ui.StatusBarTheme.CUSTOM_THEME_1_ID) {
            return getCustomThemeSlotType(context, 1)
        }
        if (themeId == it.neuralrad.coolwulf.inputmethod.ui.StatusBarTheme.CUSTOM_THEME_2_ID) {
            return getCustomThemeSlotType(context, 2)
        }
        if (themeId == it.neuralrad.coolwulf.inputmethod.ui.StatusBarTheme.CUSTOM_THEME_3_ID) {
            return getCustomThemeSlotType(context, 3)
        }
        // Check built-in themes
        val theme = it.neuralrad.coolwulf.inputmethod.ui.StatusBarTheme.ALL_THEMES.find { it.id == themeId }
        return if (theme?.themeType == it.neuralrad.coolwulf.inputmethod.ui.ThemeType.LIGHT) "light" else "dark"
    }

    /**
     * Gets all custom theme colors as a map.
     * Returns default values based on Classic Dark theme if not set.
     */
    fun getCustomThemeColors(context: Context): Map<String, Int> {
        val prefs = getPreferences(context)
        // Default colors based on Classic Dark theme
        return mapOf(
            "backgroundColor" to prefs.getInt(KEY_CUSTOM_THEME_BACKGROUND, android.graphics.Color.parseColor("#000000")),
            "textColor" to prefs.getInt(KEY_CUSTOM_THEME_TEXT, android.graphics.Color.WHITE),
            "textColorSecondary" to prefs.getInt(KEY_CUSTOM_THEME_TEXT_SECONDARY, android.graphics.Color.argb(180, 255, 255, 255)),
            "accentColor" to prefs.getInt(KEY_CUSTOM_THEME_ACCENT, android.graphics.Color.rgb(100, 200, 255)),
            "buttonBackgroundColor" to prefs.getInt(KEY_CUSTOM_THEME_BUTTON_BG, android.graphics.Color.argb(40, 255, 255, 255)),
            "buttonPressedColor" to prefs.getInt(KEY_CUSTOM_THEME_BUTTON_PRESSED, android.graphics.Color.argb(80, 255, 255, 255)),
            "candidateBackgroundColor" to prefs.getInt(KEY_CUSTOM_THEME_CANDIDATE_BG, android.graphics.Color.rgb(17, 17, 17)),
            "candidateBestBackgroundColor" to prefs.getInt(KEY_CUSTOM_THEME_CANDIDATE_BEST_BG, android.graphics.Color.rgb(50, 45, 10)),
            "candidateTextColor" to prefs.getInt(KEY_CUSTOM_THEME_CANDIDATE_TEXT, android.graphics.Color.WHITE),
            "candidateBestTextColor" to prefs.getInt(KEY_CUSTOM_THEME_CANDIDATE_BEST_TEXT, android.graphics.Color.rgb(255, 215, 0)),
            "iconColor" to prefs.getInt(KEY_CUSTOM_THEME_ICON, android.graphics.Color.WHITE),
            "iconInactiveColor" to prefs.getInt(KEY_CUSTOM_THEME_ICON_INACTIVE, android.graphics.Color.rgb(100, 100, 100)),
            "ledActiveColor" to prefs.getInt(KEY_CUSTOM_THEME_LED_ACTIVE, android.graphics.Color.rgb(100, 150, 255)),
            "ledLockedColor" to prefs.getInt(KEY_CUSTOM_THEME_LED_LOCKED, android.graphics.Color.rgb(247, 99, 0)),
            "ledInactiveColor" to prefs.getInt(KEY_CUSTOM_THEME_LED_INACTIVE, android.graphics.Color.argb(26, 255, 255, 255))
        )
    }

    /**
     * Sets a custom theme color.
     */
    fun setCustomThemeColor(context: Context, colorKey: String, colorValue: Int) {
        val prefKey = when (colorKey) {
            "backgroundColor" -> KEY_CUSTOM_THEME_BACKGROUND
            "textColor" -> KEY_CUSTOM_THEME_TEXT
            "textColorSecondary" -> KEY_CUSTOM_THEME_TEXT_SECONDARY
            "accentColor" -> KEY_CUSTOM_THEME_ACCENT
            "buttonBackgroundColor" -> KEY_CUSTOM_THEME_BUTTON_BG
            "buttonPressedColor" -> KEY_CUSTOM_THEME_BUTTON_PRESSED
            "candidateBackgroundColor" -> KEY_CUSTOM_THEME_CANDIDATE_BG
            "candidateBestBackgroundColor" -> KEY_CUSTOM_THEME_CANDIDATE_BEST_BG
            "candidateTextColor" -> KEY_CUSTOM_THEME_CANDIDATE_TEXT
            "candidateBestTextColor" -> KEY_CUSTOM_THEME_CANDIDATE_BEST_TEXT
            "iconColor" -> KEY_CUSTOM_THEME_ICON
            "iconInactiveColor" -> KEY_CUSTOM_THEME_ICON_INACTIVE
            "ledActiveColor" -> KEY_CUSTOM_THEME_LED_ACTIVE
            "ledLockedColor" -> KEY_CUSTOM_THEME_LED_LOCKED
            "ledInactiveColor" -> KEY_CUSTOM_THEME_LED_INACTIVE
            else -> return
        }
        getPreferences(context).edit()
            .putInt(prefKey, colorValue)
            .apply()
    }

    /**
     * Copies colors from an existing theme to custom theme settings.
     */
    fun copyThemeToCustom(context: Context, theme: it.neuralrad.coolwulf.inputmethod.ui.StatusBarTheme) {
        getPreferences(context).edit()
            .putInt(KEY_CUSTOM_THEME_BACKGROUND, theme.backgroundColor)
            .putInt(KEY_CUSTOM_THEME_TEXT, theme.textColor)
            .putInt(KEY_CUSTOM_THEME_TEXT_SECONDARY, theme.textColorSecondary)
            .putInt(KEY_CUSTOM_THEME_ACCENT, theme.accentColor)
            .putInt(KEY_CUSTOM_THEME_BUTTON_BG, theme.buttonBackgroundColor)
            .putInt(KEY_CUSTOM_THEME_BUTTON_PRESSED, theme.buttonPressedColor)
            .putInt(KEY_CUSTOM_THEME_CANDIDATE_BG, theme.candidateBackgroundColor)
            .putInt(KEY_CUSTOM_THEME_CANDIDATE_BEST_BG, theme.candidateBestBackgroundColor)
            .putInt(KEY_CUSTOM_THEME_CANDIDATE_TEXT, theme.candidateTextColor)
            .putInt(KEY_CUSTOM_THEME_CANDIDATE_BEST_TEXT, theme.candidateBestTextColor)
            .putInt(KEY_CUSTOM_THEME_ICON, theme.iconColor)
            .putInt(KEY_CUSTOM_THEME_ICON_INACTIVE, theme.iconInactiveColor)
            .putInt(KEY_CUSTOM_THEME_LED_ACTIVE, theme.ledActiveColor)
            .putInt(KEY_CUSTOM_THEME_LED_LOCKED, theme.ledLockedColor)
            .putInt(KEY_CUSTOM_THEME_LED_INACTIVE, theme.ledInactiveColor)
            .apply()
    }

    // ==================== Custom Theme Slots (1, 2, 3) ====================

    /**
     * Gets the prefix for a custom theme slot's keys.
     */
    private fun getCustomThemeSlotPrefix(slot: Int): String {
        return "custom_theme_${slot}_"
    }

    /**
     * Gets custom theme colors for a specific slot (1, 2, or 3).
     */
    fun getCustomThemeColorsForSlot(context: Context, slot: Int): Map<String, Int> {
        val prefs = getPreferences(context)
        val prefix = getCustomThemeSlotPrefix(slot)
        // Default colors based on Classic Dark theme
        return mapOf(
            "backgroundColor" to prefs.getInt("${prefix}background", android.graphics.Color.parseColor("#000000")),
            "textColor" to prefs.getInt("${prefix}text", android.graphics.Color.WHITE),
            "textColorSecondary" to prefs.getInt("${prefix}text_secondary", android.graphics.Color.argb(180, 255, 255, 255)),
            "accentColor" to prefs.getInt("${prefix}accent", android.graphics.Color.rgb(100, 200, 255)),
            "buttonBackgroundColor" to prefs.getInt("${prefix}button_bg", android.graphics.Color.argb(40, 255, 255, 255)),
            "buttonPressedColor" to prefs.getInt("${prefix}button_pressed", android.graphics.Color.argb(80, 255, 255, 255)),
            "candidateBackgroundColor" to prefs.getInt("${prefix}candidate_bg", android.graphics.Color.rgb(17, 17, 17)),
            "candidateBestBackgroundColor" to prefs.getInt("${prefix}candidate_best_bg", android.graphics.Color.rgb(50, 45, 10)),
            "candidateTextColor" to prefs.getInt("${prefix}candidate_text", android.graphics.Color.WHITE),
            "candidateBestTextColor" to prefs.getInt("${prefix}candidate_best_text", android.graphics.Color.rgb(255, 215, 0)),
            "iconColor" to prefs.getInt("${prefix}icon", android.graphics.Color.WHITE),
            "iconInactiveColor" to prefs.getInt("${prefix}icon_inactive", android.graphics.Color.rgb(100, 100, 100)),
            "ledActiveColor" to prefs.getInt("${prefix}led_active", android.graphics.Color.rgb(100, 150, 255)),
            "ledLockedColor" to prefs.getInt("${prefix}led_locked", android.graphics.Color.rgb(247, 99, 0)),
            "ledInactiveColor" to prefs.getInt("${prefix}led_inactive", android.graphics.Color.argb(26, 255, 255, 255))
        )
    }

    /**
     * Sets a custom theme color for a specific slot.
     */
    fun setCustomThemeColorForSlot(context: Context, slot: Int, colorKey: String, colorValue: Int) {
        val prefix = getCustomThemeSlotPrefix(slot)
        val prefKey = when (colorKey) {
            "backgroundColor" -> "${prefix}background"
            "textColor" -> "${prefix}text"
            "textColorSecondary" -> "${prefix}text_secondary"
            "accentColor" -> "${prefix}accent"
            "buttonBackgroundColor" -> "${prefix}button_bg"
            "buttonPressedColor" -> "${prefix}button_pressed"
            "candidateBackgroundColor" -> "${prefix}candidate_bg"
            "candidateBestBackgroundColor" -> "${prefix}candidate_best_bg"
            "candidateTextColor" -> "${prefix}candidate_text"
            "candidateBestTextColor" -> "${prefix}candidate_best_text"
            "iconColor" -> "${prefix}icon"
            "iconInactiveColor" -> "${prefix}icon_inactive"
            "ledActiveColor" -> "${prefix}led_active"
            "ledLockedColor" -> "${prefix}led_locked"
            "ledInactiveColor" -> "${prefix}led_inactive"
            else -> return
        }
        getPreferences(context).edit()
            .putInt(prefKey, colorValue)
            .apply()
    }

    /**
     * Copies colors from an existing theme to a custom theme slot.
     */
    fun copyThemeToCustomSlot(context: Context, theme: it.neuralrad.coolwulf.inputmethod.ui.StatusBarTheme, slot: Int) {
        val prefix = getCustomThemeSlotPrefix(slot)
        getPreferences(context).edit()
            .putInt("${prefix}background", theme.backgroundColor)
            .putInt("${prefix}text", theme.textColor)
            .putInt("${prefix}text_secondary", theme.textColorSecondary)
            .putInt("${prefix}accent", theme.accentColor)
            .putInt("${prefix}button_bg", theme.buttonBackgroundColor)
            .putInt("${prefix}button_pressed", theme.buttonPressedColor)
            .putInt("${prefix}candidate_bg", theme.candidateBackgroundColor)
            .putInt("${prefix}candidate_best_bg", theme.candidateBestBackgroundColor)
            .putInt("${prefix}candidate_text", theme.candidateTextColor)
            .putInt("${prefix}candidate_best_text", theme.candidateBestTextColor)
            .putInt("${prefix}icon", theme.iconColor)
            .putInt("${prefix}icon_inactive", theme.iconInactiveColor)
            .putInt("${prefix}led_active", theme.ledActiveColor)
            .putInt("${prefix}led_locked", theme.ledLockedColor)
            .putInt("${prefix}led_inactive", theme.ledInactiveColor)
            .apply()
    }

    /**
     * Gets the custom name for a theme slot.
     */
    fun getCustomThemeSlotName(context: Context, slot: Int): String {
        val prefs = getPreferences(context)
        val key = when (slot) {
            1 -> KEY_CUSTOM_THEME_NAME_1
            2 -> KEY_CUSTOM_THEME_NAME_2
            3 -> KEY_CUSTOM_THEME_NAME_3
            else -> return "Custom $slot"
        }
        return prefs.getString(key, "") ?: ""
    }

    /**
     * Sets the custom name for a theme slot.
     */
    fun setCustomThemeSlotName(context: Context, slot: Int, name: String) {
        val key = when (slot) {
            1 -> KEY_CUSTOM_THEME_NAME_1
            2 -> KEY_CUSTOM_THEME_NAME_2
            3 -> KEY_CUSTOM_THEME_NAME_3
            else -> return
        }
        getPreferences(context).edit()
            .putString(key, name)
            .apply()
    }

    /**
     * Checks if a custom theme slot has been configured (has custom colors saved).
     */
    fun isCustomThemeSlotConfigured(context: Context, slot: Int): Boolean {
        val prefs = getPreferences(context)
        val prefix = getCustomThemeSlotPrefix(slot)
        // Check if at least the background color has been set
        return prefs.contains("${prefix}background")
    }

    /**
     * Gets the theme type (light/dark) for a custom theme slot.
     * Returns "dark" by default.
     */
    fun getCustomThemeSlotType(context: Context, slot: Int): String {
        val prefs = getPreferences(context)
        val key = when (slot) {
            1 -> KEY_CUSTOM_THEME_TYPE_1
            2 -> KEY_CUSTOM_THEME_TYPE_2
            3 -> KEY_CUSTOM_THEME_TYPE_3
            else -> return "dark"
        }
        return prefs.getString(key, "dark") ?: "dark"
    }

    /**
     * Sets the theme type (light/dark) for a custom theme slot.
     */
    fun setCustomThemeSlotType(context: Context, slot: Int, type: String) {
        val key = when (slot) {
            1 -> KEY_CUSTOM_THEME_TYPE_1
            2 -> KEY_CUSTOM_THEME_TYPE_2
            3 -> KEY_CUSTOM_THEME_TYPE_3
            else -> return
        }
        getPreferences(context).edit()
            .putString(key, type)
            .apply()
    }

    /**
     * Gets the key code for a Juying key (1-5).
     * @param keyIndex The key index (1-5)
     * @return The key code for this Juying key
     */
    fun getJuyingKey(context: Context, keyIndex: Int): Int {
        val prefs = getPreferences(context)
        return when (keyIndex) {
            1 -> prefs.getInt(KEY_JUYING_KEY_1, DEFAULT_JUYING_KEY_1)
            2 -> prefs.getInt(KEY_JUYING_KEY_2, DEFAULT_JUYING_KEY_2)
            3 -> prefs.getInt(KEY_JUYING_KEY_3, DEFAULT_JUYING_KEY_3)
            4 -> prefs.getInt(KEY_JUYING_KEY_4, DEFAULT_JUYING_KEY_4)
            5 -> prefs.getInt(KEY_JUYING_KEY_5, DEFAULT_JUYING_KEY_5)
            else -> 0
        }
    }

    /**
     * Sets the key code for a Juying key (1-5).
     * @param keyIndex The key index (1-5)
     * @param keyCode The key code to assign
     */
    fun setJuyingKey(context: Context, keyIndex: Int, keyCode: Int) {
        val key = when (keyIndex) {
            1 -> KEY_JUYING_KEY_1
            2 -> KEY_JUYING_KEY_2
            3 -> KEY_JUYING_KEY_3
            4 -> KEY_JUYING_KEY_4
            5 -> KEY_JUYING_KEY_5
            else -> return
        }
        getPreferences(context).edit()
            .putInt(key, keyCode)
            .apply()
    }

    /**
     * Gets all 5 Juying key codes as a list.
     * @return List of 5 key codes for Juying mode
     */
    fun getJuyingKeys(context: Context): List<Int> {
        return listOf(
            getJuyingKey(context, 1),
            getJuyingKey(context, 2),
            getJuyingKey(context, 3),
            getJuyingKey(context, 4),
            getJuyingKey(context, 5)
        )
    }

    /**
     * Resets all Juying keys to their default values based on the current device type.
     * Titan 2: Shift/Sym/Space/Ctrl/Alt
     * BlackBerry: Shift/0/Space/SYM/ShiftRight
     */
    fun resetJuyingKeys(context: Context) {
        if (isBlackBerryDevice(context)) {
            setJuyingKeysForBlackBerry(context)
        } else {
            // Reset to Titan 2 defaults
            getPreferences(context).edit()
                .putInt(KEY_JUYING_KEY_1, DEFAULT_JUYING_KEY_1)
                .putInt(KEY_JUYING_KEY_2, DEFAULT_JUYING_KEY_2)
                .putInt(KEY_JUYING_KEY_3, DEFAULT_JUYING_KEY_3)
                .putInt(KEY_JUYING_KEY_4, DEFAULT_JUYING_KEY_4)
                .putInt(KEY_JUYING_KEY_5, DEFAULT_JUYING_KEY_5)
                .apply()
        }
    }

    /**
     * Gets the candidate index (0-4) for a given key code in Juying mode.
     * @return The candidate index (0-4), or -1 if not a Juying key
     */
    fun getJuyingCandidateIndex(context: Context, keyCode: Int): Int {
        val keys = getJuyingKeys(context)
        return keys.indexOf(keyCode)
    }

    /**
     * Sets the SYM page to restore when returning from settings.
     * @param context The context
     * @param page The SYM page to restore (0=disabled, 1=page1 emoji, 2=page2 characters)
     */
    fun setRestoreSymPage(context: Context, page: Int) {
        getPreferences(context).edit()
            .putInt(KEY_RESTORE_SYM_PAGE, page)
            .apply()
    }
    
    /**
     * Gets the SYM page to restore when returning from settings.
     * @param context The context
     * @return The SYM page to restore (0=disabled, 1=page1 emoji, 2=page2 characters), or 0 if not set
     */
    fun getRestoreSymPage(context: Context): Int {
        return getPreferences(context).getInt(KEY_RESTORE_SYM_PAGE, 0)
    }
    
    /**
     * Clears the SYM page restore state.
     * @param context The context
     */
    fun clearRestoreSymPage(context: Context) {
        getPreferences(context).edit()
            .remove(KEY_RESTORE_SYM_PAGE)
            .apply()
    }
    
    /**
     * Sets a pending SYM page state when opening SymCustomizationActivity.
     * This will be converted to restore_sym_page only if user presses back.
     * @param context The context
     * @param page The SYM page that was active (0=disabled, 1=page1 emoji, 2=page2 characters)
     */
    fun setPendingRestoreSymPage(context: Context, page: Int) {
        getPreferences(context).edit()
            .putInt(KEY_PENDING_RESTORE_SYM_PAGE, page)
            .apply()
    }
    
    /**
     * Gets the pending SYM page state.
     * @param context The context
     * @return The pending SYM page, or 0 if not set
     */
    fun getPendingRestoreSymPage(context: Context): Int {
        return getPreferences(context).getInt(KEY_PENDING_RESTORE_SYM_PAGE, 0)
    }
    
    /**
     * Clears the pending SYM page state.
     * @param context The context
     */
    fun clearPendingRestoreSymPage(context: Context) {
        getPreferences(context).edit()
            .remove(KEY_PENDING_RESTORE_SYM_PAGE)
            .apply()
    }
    
    /**
     * Confirms the pending restore by moving it to restore_sym_page.
     * Called when user presses back from SymCustomizationActivity.
     * @param context The context
     */
    fun confirmPendingRestoreSymPage(context: Context) {
        val pendingPage = getPendingRestoreSymPage(context)
        if (pendingPage > 0) {
            setRestoreSymPage(context, pendingPage)
            clearPendingRestoreSymPage(context)
        }
    }

    /**
     * Reads the SYM pages configuration (enabled pages and order).
     */
    fun getSymPagesConfig(context: Context): SymPagesConfig {
        val prefs = getPreferences(context)
        val jsonString = prefs.getString(KEY_SYM_PAGES_CONFIG, null) ?: return DEFAULT_SYM_PAGES_CONFIG

        return try {
            val jsonObject = JSONObject(jsonString)
            SymPagesConfig(
                emojiEnabled = jsonObject.optBoolean("emojiEnabled", true),
                symbolsEnabled = jsonObject.optBoolean("symbolsEnabled", true),
                symbols2Enabled = jsonObject.optBoolean("symbols2Enabled", false),
                emojiFirst = jsonObject.optBoolean("emojiFirst", true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading SYM pages config", e)
            DEFAULT_SYM_PAGES_CONFIG
        }
    }

    /**
     * Persists the SYM pages configuration (enabled pages and order).
     */
    fun setSymPagesConfig(context: Context, config: SymPagesConfig) {
        try {
            val jsonObject = JSONObject().apply {
                put("emojiEnabled", config.emojiEnabled)
                put("symbolsEnabled", config.symbolsEnabled)
                put("symbols2Enabled", config.symbols2Enabled)
                put("emojiFirst", config.emojiFirst)
            }

            getPreferences(context).edit()
                .putString(KEY_SYM_PAGES_CONFIG, jsonObject.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving SYM pages config", e)
        }
    }
    
    /**
     * Gets whether SYM layout should auto-close after key press.
     * @param context The context
     * @return true if SYM should auto-close, false otherwise
     */
    fun getSymAutoClose(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SYM_AUTO_CLOSE, DEFAULT_SYM_AUTO_CLOSE)
    }
    
    /**
     * Sets whether SYM layout should auto-close after key press.
     * @param context The context
     * @param enabled true to enable auto-close, false to disable
     */
    fun setSymAutoClose(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_SYM_AUTO_CLOSE, enabled)
            .apply()
    }
    
    /**
     * Returns the set of dismissed release tag names.
     * @param context The context
     * @return Set of release tag names that were dismissed by the user
     */
    fun getDismissedReleases(context: Context): Set<String> {
        val prefs = getPreferences(context)
        val dismissedString = prefs.getString(KEY_DISMISSED_RELEASES, null) ?: return emptySet()
        return if (dismissedString.isBlank()) {
            emptySet()
        } else {
            dismissedString.split(",").toSet()
        }
    }
    
    /**
     * Adds a release tag name to the dismissed releases set.
     * @param context The context
     * @param tagName The release tag name to dismiss
     */
    fun addDismissedRelease(context: Context, tagName: String) {
        val dismissed = getDismissedReleases(context).toMutableSet()
        dismissed.add(tagName)
        val dismissedString = dismissed.joinToString(",")
        getPreferences(context).edit()
            .putString(KEY_DISMISSED_RELEASES, dismissedString)
            .apply()
    }
    
    /**
     * Checks if a release tag name has been dismissed.
     * @param context The context
     * @param tagName The release tag name to check
     * @return true if the release was dismissed, false otherwise
     */
    fun isReleaseDismissed(context: Context, tagName: String): Boolean {
        return getDismissedReleases(context).contains(tagName)
    }

    /**
     * Returns whether power shortcuts are enabled.
     */
    fun getPowerShortcutsEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_POWER_SHORTCUTS_ENABLED, DEFAULT_POWER_SHORTCUTS_ENABLED)
    }

    /**
     * Sets whether power shortcuts are enabled.
     */
    fun setPowerShortcutsEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_POWER_SHORTCUTS_ENABLED, enabled)
            .apply()
    }

    /**
     * Returns the swipe incremental threshold.
     */
    fun getSwipeIncrementalThreshold(context: Context): Float {
        return getPreferences(context).getFloat(KEY_SWIPE_INCREMENTAL_THRESHOLD, DEFAULT_SWIPE_INCREMENTAL_THRESHOLD)
    }

    /**
     * Sets the swipe incremental threshold.
     */
    fun setSwipeIncrementalThreshold(context: Context, threshold: Float) {
        val clampedValue = threshold.coerceIn(MIN_SWIPE_INCREMENTAL_THRESHOLD, MAX_SWIPE_INCREMENTAL_THRESHOLD)
        getPreferences(context).edit()
            .putFloat(KEY_SWIPE_INCREMENTAL_THRESHOLD, clampedValue)
            .apply()
    }

    /**
     * Returns the minimum swipe incremental threshold.
     */
    fun getMinSwipeIncrementalThreshold(): Float = MIN_SWIPE_INCREMENTAL_THRESHOLD

    /**
     * Returns the maximum swipe incremental threshold.
     */
    fun getMaxSwipeIncrementalThreshold(): Float = MAX_SWIPE_INCREMENTAL_THRESHOLD

    /**
     * Returns whether static variation bar mode is enabled.
     */
    fun isStaticVariationBarModeEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_STATIC_VARIATION_BAR_MODE, DEFAULT_STATIC_VARIATION_BAR_MODE)
    }

    /**
     * Sets whether static variation bar mode is enabled.
     */
    fun setStaticVariationBarModeEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(KEY_STATIC_VARIATION_BAR_MODE, enabled)
            .apply()
    }

    /**
     * Returns whether the tutorial has been completed.
     */
    fun isTutorialCompleted(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_TUTORIAL_COMPLETED, DEFAULT_TUTORIAL_COMPLETED)
    }

    /**
     * Sets whether the tutorial has been completed.
     */
    fun setTutorialCompleted(context: Context, completed: Boolean = true) {
        getPreferences(context).edit()
            .putBoolean(KEY_TUTORIAL_COMPLETED, completed)
            .apply()
    }

    /**
     * Resets the tutorial completion status.
     */
    fun resetTutorialCompleted(context: Context) {
        setTutorialCompleted(context, false)
    }

    private const val KEY_KEYBOARD_LAYOUT_LIST = "keyboard_layout_list"

    /**
     * Returns the list of keyboard layouts.
     */
    fun getKeyboardLayoutList(context: Context): List<String> {
        val prefs = getPreferences(context)
        val listString = prefs.getString(KEY_KEYBOARD_LAYOUT_LIST, null) ?: return emptyList()
        return if (listString.isBlank()) {
            emptyList()
        } else {
            listString.split(",")
        }
    }

    /**
     * Sets the list of keyboard layouts.
     */
    fun setKeyboardLayoutList(context: Context, layouts: List<String>) {
        val listString = layouts.joinToString(",")
        getPreferences(context).edit()
            .putString(KEY_KEYBOARD_LAYOUT_LIST, listString)
            .apply()
    }

    /**
     * Swaps launcher shortcuts for two keys.
     */
    fun swapLauncherShortcuts(context: Context, keyCode1: Int, keyCode2: Int) {
        val shortcuts = getLauncherShortcuts(context).toMutableMap()
        val shortcut1 = shortcuts[keyCode1]
        val shortcut2 = shortcuts[keyCode2]

        if (shortcut1 != null && shortcut2 != null) {
            shortcuts[keyCode1] = shortcut2
            shortcuts[keyCode2] = shortcut1
        } else if (shortcut1 != null) {
            shortcuts.remove(keyCode1)
            shortcuts[keyCode2] = shortcut1
        } else if (shortcut2 != null) {
            shortcuts.remove(keyCode2)
            shortcuts[keyCode1] = shortcut2
        }

        // Save all shortcuts
        shortcuts.forEach { (key, shortcut) ->
            setLauncherAction(context, key, shortcut)
        }
    }

    /**
     * Returns the device type ("titan2" or "blackberry").
     */
    fun getDeviceType(context: Context): String {
        return getPreferences(context).getString(KEY_DEVICE_TYPE, DEFAULT_DEVICE_TYPE) ?: DEFAULT_DEVICE_TYPE
    }

    /**
     * Sets the device type ("titan2" or "blackberry").
     * Also updates the Juying mode keys to match the device's physical key layout.
     */
    fun setDeviceType(context: Context, deviceType: String) {
        val validType = if (deviceType == "blackberry") "blackberry" else "titan2"
        getPreferences(context).edit()
            .putString(KEY_DEVICE_TYPE, validType)
            .apply()

        // Update Juying keys to match the device type
        if (validType == "blackberry") {
            setJuyingKeysForBlackBerry(context)
        } else {
            resetJuyingKeys(context) // Reset to Titan 2 defaults
        }
    }

    /**
     * Sets Juying keys to BlackBerry layout.
     * BlackBerry keys: keycode 59 (Shift), keycode 7 (0 key), keycode 62 (Space), keycode 58 (SYM), keycode 60 (Shift Right)
     */
    fun setJuyingKeysForBlackBerry(context: Context) {
        getPreferences(context).edit()
            .putInt(KEY_JUYING_KEY_1, BLACKBERRY_JUYING_KEY_1)
            .putInt(KEY_JUYING_KEY_2, BLACKBERRY_JUYING_KEY_2)
            .putInt(KEY_JUYING_KEY_3, BLACKBERRY_JUYING_KEY_3)
            .putInt(KEY_JUYING_KEY_4, BLACKBERRY_JUYING_KEY_4)
            .putInt(KEY_JUYING_KEY_5, BLACKBERRY_JUYING_KEY_5)
            .apply()
    }

    /**
     * Returns true if BlackBerry device is selected.
     */
    fun isBlackBerryDevice(context: Context): Boolean {
        return getDeviceType(context) == "blackberry"
    }

    /**
     * Returns the list of available device types.
     */
    fun getAvailableDeviceTypes(): List<Pair<String, String>> {
        return listOf(
            "titan2" to "Unihertz Titan 2",
            "blackberry" to "BlackBerry"
        )
    }

    /**
     * Returns the SYM key code for the current device.
     * Titan 2: KEYCODE_SYM (63)
     * BlackBerry: 58
     */
    fun getSymKeyCode(context: Context): Int {
        return if (isBlackBerryDevice(context)) 58 else 63
    }

    /**
     * Returns the ALT key codes for the current device.
     * Titan 2: KEYCODE_ALT_LEFT (57), KEYCODE_ALT_RIGHT (58)
     * BlackBerry: 57 for ALT
     */
    fun getAltKeyCode(context: Context): Int {
        return 57 // Same for both devices
    }

    /**
     * Returns the SHIFT key codes for the current device.
     * Titan 2: KEYCODE_SHIFT_LEFT (59), KEYCODE_SHIFT_RIGHT (60)
     * BlackBerry: 59 for SHIFT
     */
    fun getShiftKeyCode(context: Context): Int {
        return 59 // Same for both devices
    }

    /**
     * Returns the CTRL key code for the current device.
     * Titan 2: KEYCODE_CTRL_LEFT (113), KEYCODE_CTRL_RIGHT (114)
     * BlackBerry: 68
     */
    fun getCtrlKeyCode(context: Context): Int {
        return if (isBlackBerryDevice(context)) 68 else 113
    }

    /**
     * Checks if a keycode is an ALT key for the current device.
     */
    fun isAltKey(context: Context, keyCode: Int): Boolean {
        return if (isBlackBerryDevice(context)) {
            keyCode == 57
        } else {
            keyCode == android.view.KeyEvent.KEYCODE_ALT_LEFT || keyCode == android.view.KeyEvent.KEYCODE_ALT_RIGHT
        }
    }

    /**
     * Checks if a keycode is a SHIFT key for the current device.
     */
    fun isShiftKey(context: Context, keyCode: Int): Boolean {
        return if (isBlackBerryDevice(context)) {
            keyCode == 59
        } else {
            keyCode == android.view.KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == android.view.KeyEvent.KEYCODE_SHIFT_RIGHT
        }
    }

    /**
     * Checks if a keycode is a CTRL key for the current device.
     */
    fun isCtrlKey(context: Context, keyCode: Int): Boolean {
        return if (isBlackBerryDevice(context)) {
            keyCode == 68
        } else {
            keyCode == android.view.KeyEvent.KEYCODE_CTRL_LEFT || keyCode == android.view.KeyEvent.KEYCODE_CTRL_RIGHT
        }
    }

    /**
     * Checks if a keycode is a SYM key for the current device.
     */
    fun isSymKey(context: Context, keyCode: Int): Boolean {
        return keyCode == getSymKeyCode(context)
    }
}

