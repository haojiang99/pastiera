# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Pastiera is an Android Input Method Editor (IME) for physical keyboards, optimized for devices like the Unihertz Titan 2. It's written in Kotlin using Jetpack Compose and provides advanced features like long-press key combinations, modifier key management, and navigation mode.

## Build Commands

### Java Environment
Before building, set the JAVA_HOME environment variable:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

### Building the app
```bash
./gradlew build
```

### Building release APK
```bash
./gradlew assembleRelease
```
The release APK will be signed automatically using the keystore at `pastiera-release-key.jks`.

### Installing to device
```bash
./gradlew installDebug
```

### Running tests
```bash
./gradlew test                    # Unit tests
./gradlew connectedAndroidTest    # Instrumented tests
```

### Build number management
The build number is automatically incremented before each build via the `incrementBuildNumber` task defined in `app/build.gradle.kts`. Build metadata is stored in `app/build.properties`.

## Architecture Overview

### Core Architecture Pattern

The codebase follows a controller-based architecture where `PhysicalKeyboardInputMethodService` (the main IME service at `app/src/main/java/it/palsoftware/pastiera/inputmethod/PhysicalKeyboardInputMethodService.kt`) delegates responsibilities to specialized controllers:

1. **ModifierStateController** (`core/ModifierStateController.kt`): Manages all modifier key states (Shift/Ctrl/Alt) including one-shot, latch, and caps lock modes
2. **TextInputController** (`core/TextInputController.kt`): Handles text-level features like double-space-to-period and auto-capitalization after punctuation
3. **SymLayoutController** (`core/SymLayoutController.kt`): Manages SYM key layout state for emoji/symbol input
4. **NavModeController** (`core/NavModeController.kt`): Handles navigation mode when Ctrl is double-tapped outside text fields
5. **InputContextState** (`core/InputContextState.kt`): Tracks current input field state (password field detection, etc.)
6. **AutoCorrectionManager** (`core/AutoCorrectionManager.kt`): Manages auto-correction dictionaries and language-specific corrections
7. **PinyinInputController** (`core/PinyinInputController.kt`): Manages Chinese Pinyin input, buffer state, and candidate generation

### Key Input Flow

1. Physical key events enter through `PhysicalKeyboardInputMethodService.onKeyDown()`/`onKeyUp()`
2. `InputEventRouter` (`inputmethod/InputEventRouter.kt`) routes events based on current state
3. Controllers process the event based on their domain:
   - Modifier keys → `ModifierKeyHandler` and `ModifierStateController`
   - Long-press detection → `KeyboardEventTracker`
   - **Pinyin input** → `PinyinInputController` (when Pinyin mode is active)
   - Text input → `TextInputController` and `AutoCorrector`
   - Navigation mode → `NavModeHandler` and `NavModeController`
   - Launcher shortcuts → `LauncherShortcutController`
4. Status bar updates via `StatusBarController` and `CandidatesBarController`
5. Text committed through Android's `InputConnection`

### Data Layer

- **Layout mappings** (`data/layout/`): JSON-based keyboard layouts loaded via `LayoutMappingRepository`
  - Located in `app/src/main/assets/common/layouts/*.json`
  - Supports QWERTY, AZERTY, QWERTZ, Arabic, Greek, Russian, Bulgarian
- **Key mappings** (`data/mappings/`): Ctrl/Alt/SYM key mappings loaded from JSON
  - Located in `app/src/main/assets/common/ctrl/*.json`, `devices/*/alt_key_mappings.json`, `common/sym/*.json`
- **Variations** (`data/variation/`): Character accent variations (à, é, ñ, etc.)
  - Loaded from `app/src/main/assets/common/variations/variations.json`
- **Auto-corrections** (`app/src/main/assets/common/autocorrect/`): Language-specific auto-correction dictionaries in JSON format
- **Pinyin dictionary** (`data/pinyin/`): Pinyin-to-Hanzi mappings for Chinese input
  - Located in `app/src/main/assets/common/pinyin/pinyin_dict.json`
  - Contains ~400 common syllables with character candidates

### Settings Management

`SettingsManager` (`SettingsManager.kt`) is a singleton that centralizes all app settings using SharedPreferences. All settings are stored with typed getters/setters. When adding new settings:
1. Add a constant key and default value in `SettingsManager`
2. Add getter/setter methods
3. Update relevant settings screens in the UI

### UI Layer

- Settings screens are Compose-based screens (e.g., `AdvancedSettingsScreen.kt`, `CustomizationSettingsScreen.kt`)
- IME UI components are in `inputmethod/ui/` (status bar, variation bar)
- Main app UI theme in `ui/theme/`

## Important Implementation Notes

### JSON Configuration Files

Most keyboard behavior is configurable via JSON files in `app/src/main/assets/`. When modifying key mappings or adding layouts:
- Layouts follow a standardized JSON format with keycode mappings
- Auto-correction dictionaries use simple "wrong" → "correct" mappings
- Changes to assets require app reinstall to take effect

### Modifier Key State Machine

The modifier key system (Shift/Ctrl/Alt) supports three modes:
- **One-shot**: Tap once, applies to next key only
- **Latch**: Double-tap to keep active until tapped again
- **Physical press**: Holding the physical key down

All three modes are tracked independently and can be combined. The state machine in `ModifierStateController` ensures these modes stay synchronized.

### Long-Press Behavior

Long-press can simulate either Alt+key or Shift+key (configurable). The detection happens in `KeyboardEventTracker` which times key-down events. When long-press threshold is exceeded, it triggers the configured modifier combination.

### Chinese Pinyin Input

The IME supports Chinese Pinyin input for typing Chinese characters using Latin letters:

**Architecture:**
- `PinyinInputController` (`core/PinyinInputController.kt`): Manages the input buffer and generates candidates
- `PinyinDictionary` (`data/pinyin/PinyinDictionary.kt`): Loads and provides lookups from the pinyin dictionary

**Usage:**
- Press `Shift+Enter` to toggle Pinyin mode on/off
- When active, type pinyin letters (e.g., "ni", "hao")
- Candidates appear in the status bar (up to 9)
- Press `Space` to select the first candidate
- Press number keys `1-9` to select specific candidates
- Press `Backspace` to delete letters from the buffer
- Press `ESC` or `Ctrl+Q` to exit Pinyin mode

**Implementation Details:**
- Dictionary uses longest-match algorithm for syllable parsing (e.g., "nian" not "ni"+"an")
- Buffer state is tracked independently from normal text input
- Candidates are displayed in the variation bar (reusing existing UI)
- Status bar shows "拼" indicator and current buffer content
- Pinyin handling takes priority in the key event pipeline (before normal text input)

**Dictionary Format:**
```json
{
  "ni": ["你", "尼", "泥", "呢"],
  "hao": ["好", "号", "毫"],
  ...
}
```

**Settings:**
- `KEY_PINYIN_ENABLED`: Enable/disable Pinyin input (default: true)
- `KEY_PINYIN_CHARACTER_SET`: "simplified" or "traditional" (default: "simplified", currently only simplified is implemented)

### Build Configuration

- **Min SDK**: 29 (Android 10)
- **Target SDK**: 36
- **Java Version**: 11
- ProGuard minification enabled for release builds
- Signing configured in `app/build.gradle.kts` with keystore path `../pastiera-release-key.jks`

### Testing on Device

After building, the keyboard must be enabled:
1. Install the APK
2. Go to Settings → System → Languages & input → Virtual keyboard → Manage keyboards
3. Enable "Pastiera Physical Keyboard"
4. Switch to Pastiera when typing
