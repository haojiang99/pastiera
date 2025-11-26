package it.neuralrad.coolwulf.core

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import it.neuralrad.coolwulf.data.pinyin.PinyinDictionary
import it.neuralrad.coolwulf.data.pinyin.UserPinyinMemory

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
        private const val PAGE_SIZE = 9  // Number of candidates per page
    }

    // Current pinyin input buffer (e.g., "nihao")
    private var buffer = StringBuilder()

    // All candidates for the buffer (full list from dictionary)
    private var allCandidates: List<String> = emptyList()

    // Whether Pinyin mode is currently active
    private var isPinyinModeActive = false

    // Track the matched syllable/phrase for the current candidates
    private var matchedPinyin: String = ""

    // Track how many candidates are phrase candidates (vs single-character candidates)
    private var phraseCandidateCount: Int = 0

    // Track the first syllable for single-character fallback
    private var firstSyllable: String = ""

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

    // User memory for learning preferences
    private val userMemory: UserPinyinMemory = UserPinyinMemory.getInstance(context)

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
        Log.d(TAG, "Letter added: '$lowerChar' → Buffer: '$buffer', Candidates: ${allCandidates.joinToString(", ")}")
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
        Log.d(TAG, "Backspace - Buffer: '$buffer', Candidates: ${allCandidates.size}")
        return true
    }

    /**
     * Selects a candidate by index on current page and returns the selected character.
     * Only consumes the matched pinyin syllable(s), keeping remaining buffer.
     * @param index The candidate index on current page (0-based)
     * @return The selected Chinese character, or null if index invalid
     */
    fun selectCandidate(index: Int): String? {
        val currentPageCandidates = getCurrentPageCandidates()
        if (!isPinyinModeActive || index < 0 || index >= currentPageCandidates.size) {
            return null
        }

        val selected = currentPageCandidates[index]

        // Calculate the actual index in the full candidate list (accounting for pagination)
        val actualIndex = currentPage * PAGE_SIZE + index

        // Determine which pinyin to consume based on candidate type
        val pinyinToConsume = if (actualIndex < phraseCandidateCount) {
            // Phrase candidate - consume the entire matched buffer
            matchedPinyin
        } else {
            // Single-character candidate - consume only the first syllable
            firstSyllable
        }

        Log.d(TAG, "Selected candidate $index (actual: $actualIndex): '$selected', consuming: '$pinyinToConsume' (phrase count: $phraseCandidateCount)")

        // Record the selection in user memory for learning
        userMemory.recordSelection(pinyinToConsume, selected)

        // Remove the consumed pinyin from the buffer
        if (pinyinToConsume.isNotEmpty() && buffer.startsWith(pinyinToConsume)) {
            buffer.delete(0, pinyinToConsume.length)
            Log.d(TAG, "Consumed '$pinyinToConsume', remaining buffer: '$buffer'")

            // Update candidates for the remaining buffer
            updateCandidates()
        } else {
            // Fallback: clear entire buffer if something went wrong
            Log.w(TAG, "Failed to consume pinyin, clearing buffer")
            clearBuffer()
        }

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
        if (!isPinyinModeActive || allCandidates.isEmpty()) {
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
        matchedPinyin = ""
        phraseCandidateCount = 0
        firstSyllable = ""
        currentPage = 0
        Log.d(TAG, "Buffer cleared")
    }

    /**
     * Updates candidate list based on current buffer.
     * Uses longest-match strategy and checks for phrase matches.
     * For multi-syllable inputs, shows both phrase candidates and single-character
     * candidates for the first syllable to allow character-by-character input.
     * For partial/incomplete syllables (like single letters), shows prefix matches.
     */
    private fun updateCandidates() {
        currentPage = 0  // Reset to first page when candidates change

        if (buffer.isEmpty()) {
            allCandidates = emptyList()
            matchedPinyin = ""
            phraseCandidateCount = 0
            firstSyllable = ""
            return
        }

        val bufferStr = buffer.toString()

        // Find the first syllable for fallback options
        val firstSyl = PinyinDictionary.findLongestSyllable(bufferStr) ?: ""
        firstSyllable = firstSyl

        // Check for exact phrase match first
        val phraseCandidatesRaw = PinyinDictionary.getPhraseCandidates(bufferStr)
        if (phraseCandidatesRaw.isNotEmpty()) {
            // Exact phrase match - show phrase candidates
            // Also add single-character candidates for first syllable as fallback
            val singleCharCandidatesRaw = if (firstSyl.isNotEmpty()) {
                PinyinDictionary.getCandidates(firstSyl)
            } else {
                emptyList()
            }

            // Sort both by user frequency
            val phraseCandidates = userMemory.sortByFrequency(bufferStr, phraseCandidatesRaw)
            val singleCharCandidates = userMemory.sortByFrequency(firstSyl, singleCharCandidatesRaw)

            // Combine: phrase candidates first, then single-character candidates
            allCandidates = phraseCandidates + singleCharCandidates
            matchedPinyin = bufferStr  // Default to full buffer for phrases
            phraseCandidateCount = phraseCandidates.size
            Log.d(TAG, "Phrase match: $bufferStr → ${phraseCandidates.size} phrases + ${singleCharCandidates.size} single chars (sorted by frequency)")
            return
        }

        // Find the longest syllable match
        val longestSyllable = PinyinDictionary.findLongestSyllable(bufferStr)
        if (longestSyllable != null) {
            val charCandidatesRaw = PinyinDictionary.getCandidates(longestSyllable)
            // Sort by user frequency
            allCandidates = userMemory.sortByFrequency(longestSyllable, charCandidatesRaw)
            matchedPinyin = longestSyllable
            phraseCandidateCount = 0  // No phrases, all single characters
            Log.d(TAG, "Syllable match: $longestSyllable → ${allCandidates.size} chars (sorted by frequency)")
        } else {
            // No exact syllable match - try prefix matching for incomplete input
            // This handles cases like "w" showing candidates from "wa", "wo", "wu", etc.
            val prefixCandidatesRaw = PinyinDictionary.getCandidatesForPrefix(bufferStr)
            if (prefixCandidatesRaw.isNotEmpty()) {
                // Sort by user frequency using the prefix as key
                allCandidates = userMemory.sortByFrequency(bufferStr, prefixCandidatesRaw)
                // Use the first matching syllable for consumption
                matchedPinyin = PinyinDictionary.getFirstSyllableForPrefix(bufferStr) ?: bufferStr
                firstSyllable = matchedPinyin
                phraseCandidateCount = 0
                Log.d(TAG, "Prefix match: $bufferStr → ${allCandidates.size} chars from syllables starting with '$bufferStr'")
            } else {
                allCandidates = emptyList()
                matchedPinyin = ""
                phraseCandidateCount = 0
                Log.d(TAG, "No match for: $bufferStr")
            }
        }
    }

    /**
     * Gets current state snapshot for UI updates.
     */
    fun getSnapshot(): Snapshot {
        val currentPageCandidates = getCurrentPageCandidates()
        val totalPages = getTotalPages()
        return Snapshot(
            isActive = isPinyinModeActive,
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
