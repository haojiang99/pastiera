package it.neuralrad.coolwulf.core

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import it.neuralrad.coolwulf.data.wubi.WubiDictionary

/**
 * Manages Wubi 86 input state and generates Chinese character candidates.
 * Handles the input buffer and provides methods for candidate selection.
 *
 * Wubi is a shape-based Chinese input method where characters are
 * encoded using 1-4 letter codes based on their structural components.
 */
class WubiInputController(
    private val context: Context
) {
    companion object {
        private const val TAG = "WubiInputController"
        private const val MAX_BUFFER_LENGTH = 4 // Wubi codes are max 4 characters
        private const val PAGE_SIZE = 9  // Number of candidates per page
    }

    // Current wubi input buffer (e.g., "gggg" for "王")
    private var buffer = StringBuilder()

    // All candidates for the buffer (full list from dictionary)
    private var allCandidates: List<String> = emptyList()

    // Whether Wubi mode is currently active
    private var isWubiModeActive = false

    // Current page (0-indexed)
    private var currentPage: Int = 0

    data class Snapshot(
        val isActive: Boolean,
        val buffer: String,
        val candidates: List<String>,
        val hasCandidates: Boolean,
        val currentPage: Int = 0,
        val totalPages: Int = 1,
        val hasNextPage: Boolean = false,
        val hasPrevPage: Boolean = false
    )

    init {
        // Load dictionary if not already loaded
        if (!WubiDictionary.isLoaded()) {
            WubiDictionary.load(context)
        }
    }

    /**
     * Enables or disables Wubi input mode.
     */
    fun setWubiMode(active: Boolean) {
        if (isWubiModeActive != active) {
            isWubiModeActive = active
            if (!active) {
                clearBuffer()
            }
            Log.d(TAG, "========== Wubi mode: ${if (active) "ENABLED" else "DISABLED"} ==========")
        } else {
            Log.d(TAG, "Wubi mode already ${if (active) "enabled" else "disabled"}")
        }
    }

    /**
     * Toggles Wubi input mode on/off.
     */
    fun toggleWubiMode() {
        setWubiMode(!isWubiModeActive)
    }

    /**
     * Returns whether Wubi mode is currently active.
     */
    fun isWubiMode(): Boolean = isWubiModeActive

    /**
     * Processes a letter key press in Wubi mode.
     * Adds the letter to the buffer and updates candidates.
     * @param char The character to add (a-z)
     * @return true if the key was handled, false otherwise
     */
    fun handleLetterKey(char: Char): Boolean {
        if (!isWubiModeActive) {
            Log.d(TAG, "handleLetterKey: Wubi mode not active")
            return false
        }

        // Only accept lowercase letters
        val lowerChar = char.lowercaseChar()
        if (!lowerChar.isLetter() || lowerChar < 'a' || lowerChar > 'z') {
            Log.d(TAG, "handleLetterKey: Invalid character '$char'")
            return false
        }

        // Check buffer length limit - Wubi codes are max 4 characters
        if (buffer.length >= MAX_BUFFER_LENGTH) {
            // In Wubi, if buffer is full but no exact match, we might want to
            // auto-select the first candidate and start fresh with this key
            if (allCandidates.isNotEmpty()) {
                val selected = selectCandidate(0)
                if (selected != null) {
                    // After auto-selecting, add the new key to buffer
                    buffer.append(lowerChar)
                    updateCandidates()
                    Log.d(TAG, "Auto-selected first candidate, new letter: '$lowerChar' → Buffer: '$buffer'")
                    return true
                }
            }
            Log.w(TAG, "Buffer full, ignoring input")
            return true
        }

        buffer.append(lowerChar)
        updateCandidates()
        Log.d(TAG, "Letter added: '$lowerChar' → Buffer: '$buffer', Candidates: ${allCandidates.joinToString(", ")}")

        // In Wubi, if we have exactly 4 characters and only one candidate,
        // we can auto-commit it (traditional Wubi behavior)
        if (buffer.length == MAX_BUFFER_LENGTH && allCandidates.size == 1) {
            Log.d(TAG, "Auto-commit single candidate at max length")
            // Don't auto-commit - let user decide
        }

        return true
    }

    /**
     * Handles backspace in Wubi mode.
     * Removes the last character from the buffer.
     * @return true if handled (buffer was not empty), false otherwise
     */
    fun handleBackspace(): Boolean {
        if (!isWubiModeActive || buffer.isEmpty()) {
            return false
        }

        buffer.deleteCharAt(buffer.length - 1)
        updateCandidates()
        Log.d(TAG, "Backspace - Buffer: '$buffer', Candidates: ${allCandidates.size}")
        return true
    }

    /**
     * Selects a candidate by index on current page and returns the selected character.
     * @param index The candidate index on current page (0-based)
     * @return The selected Chinese character, or null if index invalid
     */
    fun selectCandidate(index: Int): String? {
        val currentPageCandidates = getCurrentPageCandidates()
        if (!isWubiModeActive || index < 0 || index >= currentPageCandidates.size) {
            return null
        }

        val selected = currentPageCandidates[index]
        Log.d(TAG, "Selected candidate $index: '$selected'")

        // Clear the buffer after selection (Wubi consumes entire code)
        buffer.clear()
        updateCandidates()

        return selected
    }

    /**
     * Gets candidates for the current page.
     */
    private fun getCurrentPageCandidates(): List<String> {
        val startIndex = currentPage * PAGE_SIZE
        val endIndex = minOf(startIndex + PAGE_SIZE, allCandidates.size)
        return if (startIndex < allCandidates.size) {
            allCandidates.subList(startIndex, endIndex)
        } else {
            emptyList()
        }
    }

    /**
     * Calculates total number of pages.
     */
    private fun getTotalPages(): Int {
        return if (allCandidates.isEmpty()) 1 else ((allCandidates.size + PAGE_SIZE - 1) / PAGE_SIZE)
    }

    /**
     * Navigates to the next page of candidates.
     * @return true if navigation was successful, false if already on last page
     */
    fun nextPage(): Boolean {
        val totalPages = getTotalPages()
        if (currentPage < totalPages - 1) {
            currentPage++
            Log.d(TAG, "Moved to page ${currentPage + 1}/$totalPages")
            return true
        }
        return false
    }

    /**
     * Navigates to the previous page of candidates.
     * @return true if navigation was successful, false if already on first page
     */
    fun prevPage(): Boolean {
        if (currentPage > 0) {
            currentPage--
            Log.d(TAG, "Moved to page ${currentPage + 1}/${getTotalPages()}")
            return true
        }
        return false
    }

    /**
     * Resets to first page.
     */
    fun resetPage() {
        currentPage = 0
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
        if (!isWubiModeActive || allCandidates.isEmpty()) {
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
        allCandidates = emptyList()
        currentPage = 0
        Log.d(TAG, "Buffer cleared")
    }

    /**
     * Updates candidate list based on current buffer.
     * Uses prefix matching to show all possible completions.
     */
    private fun updateCandidates() {
        currentPage = 0  // Reset to first page when candidates change

        if (buffer.isEmpty()) {
            allCandidates = emptyList()
            return
        }

        val bufferStr = buffer.toString()

        // Get candidates for the current code (both exact and prefix matches)
        allCandidates = WubiDictionary.getCandidatesForPrefix(bufferStr, limit = 50)

        Log.d(TAG, "Updated candidates for '$bufferStr': ${allCandidates.size}")
    }

    /**
     * Gets current state snapshot for UI updates.
     */
    fun getSnapshot(): Snapshot {
        val currentPageCandidates = getCurrentPageCandidates()
        val totalPages = getTotalPages()
        return Snapshot(
            isActive = isWubiModeActive,
            buffer = buffer.toString(),
            candidates = currentPageCandidates,
            hasCandidates = allCandidates.isNotEmpty(),
            currentPage = currentPage,
            totalPages = totalPages,
            hasNextPage = currentPage < totalPages - 1,
            hasPrevPage = currentPage > 0
        )
    }

    /**
     * Gets the current buffer content.
     */
    fun getBuffer(): String = buffer.toString()

    /**
     * Gets the current page's candidates.
     */
    fun getCandidates(): List<String> = getCurrentPageCandidates()

    /**
     * Checks if there are any candidates available.
     */
    fun hasCandidates(): Boolean = allCandidates.isNotEmpty()

    /**
     * Commits the current buffer as-is (without selecting a candidate).
     * This is useful if the user wants to type the letters literally.
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

    /**
     * Checks if there is a next page of candidates.
     */
    fun hasNextPage(): Boolean {
        val totalPages = getTotalPages()
        return currentPage < totalPages - 1
    }

    /**
     * Checks if there is a previous page of candidates.
     */
    fun hasPrevPage(): Boolean = currentPage > 0
}
