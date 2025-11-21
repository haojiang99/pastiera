package it.palsoftware.pastiera.core

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import it.palsoftware.pastiera.data.pinyin.PinyinDictionary

/**
 * Manages Pinyin input state and generates Chinese character candidates.
 * Handles the input buffer and provides methods for candidate selection.
 */
class PinyinInputController(
    private val context: Context
) {
    companion object {
        private const val TAG = "PinyinInputController"
        private const val MAX_BUFFER_LENGTH = 20 // Maximum pinyin buffer length
    }

    // Current pinyin input buffer (e.g., "nihao")
    private var buffer = StringBuilder()

    // Current candidates for the buffer
    private var currentCandidates: List<String> = emptyList()

    // Whether Pinyin mode is currently active
    private var isPinyinModeActive = false

    // Track the matched syllable/phrase for the current candidates
    private var matchedPinyin: String = ""

    data class Snapshot(
        val isActive: Boolean,
        val buffer: String,
        val candidates: List<String>,
        val hasCandidates: Boolean
    )

    init {
        // Load dictionary if not already loaded
        if (!PinyinDictionary.isLoaded()) {
            PinyinDictionary.load(context)
        }
    }

    /**
     * Enables or disables Pinyin input mode.
     */
    fun setPinyinMode(active: Boolean) {
        if (isPinyinModeActive != active) {
            isPinyinModeActive = active
            if (!active) {
                clearBuffer()
            }
            Log.d(TAG, "========== Pinyin mode: ${if (active) "ENABLED" else "DISABLED"} ==========")
        } else {
            Log.d(TAG, "Pinyin mode already ${if (active) "enabled" else "disabled"}")
        }
    }

    /**
     * Toggles Pinyin input mode on/off.
     */
    fun togglePinyinMode() {
        setPinyinMode(!isPinyinModeActive)
    }

    /**
     * Returns whether Pinyin mode is currently active.
     */
    fun isPinyinMode(): Boolean = isPinyinModeActive

    /**
     * Processes a letter key press in Pinyin mode.
     * Adds the letter to the buffer and updates candidates.
     * @param char The character to add (a-z)
     * @return true if the key was handled, false otherwise
     */
    fun handleLetterKey(char: Char): Boolean {
        if (!isPinyinModeActive) {
            Log.d(TAG, "handleLetterKey: Pinyin mode not active")
            return false
        }

        // Only accept lowercase letters
        val lowerChar = char.lowercaseChar()
        if (!lowerChar.isLetter() || lowerChar < 'a' || lowerChar > 'z') {
            Log.d(TAG, "handleLetterKey: Invalid character '$char'")
            return false
        }

        // Check buffer length limit
        if (buffer.length >= MAX_BUFFER_LENGTH) {
            Log.w(TAG, "Buffer full, ignoring input")
            return true
        }

        buffer.append(lowerChar)
        updateCandidates()
        Log.d(TAG, "Letter added: '$lowerChar' → Buffer: '$buffer', Candidates: ${currentCandidates.joinToString(", ")}")
        return true
    }

    /**
     * Handles backspace in Pinyin mode.
     * Removes the last character from the buffer.
     * @return true if handled (buffer was not empty), false otherwise
     */
    fun handleBackspace(): Boolean {
        if (!isPinyinModeActive || buffer.isEmpty()) {
            return false
        }

        buffer.deleteCharAt(buffer.length - 1)
        updateCandidates()
        Log.d(TAG, "Backspace - Buffer: '$buffer', Candidates: ${currentCandidates.size}")
        return true
    }

    /**
     * Selects a candidate by index and returns the selected character.
     * Only consumes the matched pinyin syllable(s), keeping remaining buffer.
     * @param index The candidate index (0-based)
     * @return The selected Chinese character, or null if index invalid
     */
    fun selectCandidate(index: Int): String? {
        if (!isPinyinModeActive || index < 0 || index >= currentCandidates.size) {
            return null
        }

        val selected = currentCandidates[index]
        Log.d(TAG, "Selected candidate $index: '$selected', matched pinyin: '$matchedPinyin'")

        // Remove only the matched pinyin from the buffer
        if (matchedPinyin.isNotEmpty() && buffer.startsWith(matchedPinyin)) {
            buffer.delete(0, matchedPinyin.length)
            Log.d(TAG, "Consumed '$matchedPinyin', remaining buffer: '$buffer'")

            // Update candidates for the remaining buffer
            updateCandidates()
        } else {
            // Fallback: clear entire buffer if something went wrong
            clearBuffer()
        }

        return selected
    }

    /**
     * Selects the first candidate (for space key).
     * @return The selected Chinese character, or null if no candidates
     */
    fun selectFirstCandidate(): String? {
        return selectCandidate(0)
    }

    /**
     * Handles number key press for candidate selection (1-9).
     * @param keyCode The key code (KEYCODE_1 to KEYCODE_9)
     * @return The selected character, or null if not applicable
     */
    fun handleNumberKey(keyCode: Int): String? {
        if (!isPinyinModeActive || currentCandidates.isEmpty()) {
            return null
        }

        // Map KEYCODE_1..9 to candidate indices 0..8
        val number = when (keyCode) {
            KeyEvent.KEYCODE_1 -> 1
            KeyEvent.KEYCODE_2 -> 2
            KeyEvent.KEYCODE_3 -> 3
            KeyEvent.KEYCODE_4 -> 4
            KeyEvent.KEYCODE_5 -> 5
            KeyEvent.KEYCODE_6 -> 6
            KeyEvent.KEYCODE_7 -> 7
            KeyEvent.KEYCODE_8 -> 8
            KeyEvent.KEYCODE_9 -> 9
            else -> return null
        }

        val index = number - 1
        return selectCandidate(index)
    }

    /**
     * Clears the input buffer and candidates.
     */
    fun clearBuffer() {
        buffer.clear()
        currentCandidates = emptyList()
        matchedPinyin = ""
        Log.d(TAG, "Buffer cleared")
    }

    /**
     * Updates candidate list based on current buffer.
     * Uses longest-match strategy and checks for phrase matches.
     */
    private fun updateCandidates() {
        if (buffer.isEmpty()) {
            currentCandidates = emptyList()
            matchedPinyin = ""
            return
        }

        val bufferStr = buffer.toString()

        // Check for exact phrase match first
        val phraseCandidates = PinyinDictionary.getPhraseCandidates(bufferStr)
        if (phraseCandidates.isNotEmpty()) {
            // Exact phrase match - use the entire buffer
            currentCandidates = phraseCandidates
            matchedPinyin = bufferStr
            return
        }

        // Find the longest syllable match
        val longestSyllable = PinyinDictionary.findLongestSyllable(bufferStr)
        if (longestSyllable != null) {
            val charCandidates = PinyinDictionary.getCandidates(longestSyllable)
            currentCandidates = charCandidates
            matchedPinyin = longestSyllable
        } else {
            currentCandidates = emptyList()
            matchedPinyin = ""
        }
    }

    /**
     * Gets current state snapshot for UI updates.
     */
    fun getSnapshot(): Snapshot {
        return Snapshot(
            isActive = isPinyinModeActive,
            buffer = buffer.toString(),
            candidates = currentCandidates,
            hasCandidates = currentCandidates.isNotEmpty()
        )
    }

    /**
     * Gets the current buffer content.
     */
    fun getBuffer(): String = buffer.toString()

    /**
     * Gets the current candidates.
     */
    fun getCandidates(): List<String> = currentCandidates

    /**
     * Checks if there are any candidates available.
     */
    fun hasCandidates(): Boolean = currentCandidates.isNotEmpty()

    /**
     * Commits the current buffer as-is (without selecting a candidate).
     * This is useful if the user wants to type the pinyin letters literally.
     * @return The buffer content, or null if empty
     */
    fun commitBufferAsIs(): String? {
        if (buffer.isEmpty()) {
            return null
        }

        val content = buffer.toString()
        clearBuffer()
        return content
    }
}
