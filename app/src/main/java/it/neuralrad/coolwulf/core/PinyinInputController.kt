package it.neuralrad.coolwulf.core

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import it.neuralrad.coolwulf.data.pinyin.AutoPhraseMemory
import it.neuralrad.coolwulf.data.pinyin.ChineseCharacterConverter
import it.neuralrad.coolwulf.data.pinyin.PinyinDictionary
import it.neuralrad.coolwulf.data.pinyin.UserPinyinMemory
import it.neuralrad.coolwulf.data.NextWordPredictor
import it.neuralrad.coolwulf.data.UserCustomDictionary
import it.neuralrad.coolwulf.inputmethod.NeuralPinyinRecognizer
import it.neuralrad.coolwulf.SettingsManager

/**
 * Manages Pinyin input state and generates Chinese character candidates.
 * Handles the input buffer and provides methods for candidate selection.
 * Also provides next-word predictions after a character/phrase is committed.
 */
class PinyinInputController(
    private val context: Context
) {
    companion object {
        private const val TAG = "PinyinInputController"
        private const val MAX_BUFFER_LENGTH = 120 // Maximum pinyin buffer length
        private const val DEFAULT_PAGE_SIZE = 9  // Number of candidates per page (normal mode)
        private const val JUYING_PAGE_SIZE = 5   // Number of candidates per page (Juying mode)
        private const val SEPARATOR = '\'' // Apostrophe separator for disambiguating syllables (e.g., he'ni = 和你)
    }

    // Dynamic page size (changes based on Juying mode)
    private var pageSize: Int = DEFAULT_PAGE_SIZE

    // Dynamic display limit for Juying mode (can be 1, 3, or 5 based on candidate length)
    // When set (> 0), this overrides pageSize for pagination
    private var dynamicDisplayLimit: Int = 0

    // Whether Juying mode is enabled
    private var isJuyingModeEnabled: Boolean = false

    /**
     * Sets the page size for candidates.
     * @param juyingMode Whether Juying mode is enabled (uses 5 candidates per page)
     * @param maxCandidatesNonJuying Maximum number of candidates in non-Juying mode (default 9)
     */
    fun setJuyingMode(juyingMode: Boolean, maxCandidatesNonJuying: Int = DEFAULT_PAGE_SIZE) {
        isJuyingModeEnabled = juyingMode
        val newPageSize = if (juyingMode) JUYING_PAGE_SIZE else maxCandidatesNonJuying
        if (newPageSize != pageSize) {
            pageSize = newPageSize
            currentPage = 0 // Reset to first page when page size changes
        }
    }

    /**
     * Sets the dynamic display limit for Juying mode.
     * This is calculated based on the maximum candidate length and determines
     * how many candidates can be shown on screen (1, 3, or 5).
     * When this is set, candidates beyond this limit are pushed to the next page.
     * @param limit The number of candidates to show (1, 3, or 5). Set to 0 to disable.
     */
    fun setDynamicDisplayLimit(limit: Int) {
        if (dynamicDisplayLimit != limit) {
            dynamicDisplayLimit = limit
            // Don't reset page - user may be navigating
        }
    }

    /**
     * Gets the effective page size for pagination.
     * In Juying mode, uses dynamicDisplayLimit if set, otherwise uses pageSize.
     */
    private fun getEffectivePageSize(): Int {
        return if (isJuyingModeEnabled && dynamicDisplayLimit > 0) {
            dynamicDisplayLimit
        } else {
            pageSize
        }
    }

    /**
     * Calculates the dynamic page limit for a specific set of candidates based on max length.
     * Used for variable page sizes in Juying mode.
     */
    private fun calculateDynamicLimitForCandidates(candidates: List<String>): Int {
        if (candidates.isEmpty()) return JUYING_PAGE_SIZE
        val maxLen = candidates.maxOfOrNull { it.length } ?: 1
        return when {
            maxLen >= 10 -> 1  // Very long phrases (10+ chars): show only 1
            maxLen >= 6 -> 3   // Long phrases (6-9 chars): show 3
            else -> 5          // Short candidates (1-5 chars): show 5
        }
    }

    /**
     * Gets the start index for a given page by iterating through all previous pages
     * with variable page sizes (dynamic mode).
     */
    private fun getPageStartIndex(targetPage: Int): Int {
        if (!isJuyingModeEnabled || dynamicDisplayLimit <= 0) {
            // Fixed page size mode
            return targetPage * getEffectivePageSize()
        }

        // Dynamic page size mode - use dynamicDisplayLimit as the page size
        // This respects the "max 3 suggestions" setting
        return targetPage * dynamicDisplayLimit
    }

    /**
     * Gets the page limit for a specific page (how many candidates on that page).
     */
    private fun getPageLimit(targetPage: Int): Int {
        if (!isJuyingModeEnabled || dynamicDisplayLimit <= 0) {
            return getEffectivePageSize()
        }

        // Dynamic page size mode - use the dynamicDisplayLimit as the limit
        // This respects the "max 3 suggestions" setting when dynamicDisplayLimit is set to 3
        // For dynamic candidate count based on phrase length, the service calculates and sets the appropriate limit
        return dynamicDisplayLimit
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

    // Set of candidates that are phrases (for determining syllable consumption in mixed-sorted list)
    private var phraseCandidateSet: Set<String> = emptySet()

    // Track the first syllable for single-character fallback
    private var firstSyllable: String = ""

    // Current page (0-indexed)
    private var currentPage: Int = 0

    // Whether we're showing next-word predictions
    private var isShowingNextWordPredictions: Boolean = false

    // Whether next word prediction feature is enabled
    private var nextWordPredictionEnabled: Boolean = true

    // Track quote state for alternating between opening and closing Chinese quotes
    private var nextDoubleQuoteIsOpening: Boolean = true
    private var nextSingleQuoteIsOpening: Boolean = true

    // Whether to use Chinese punctuation (true) or English punctuation (false)
    private var useChinesePunctuation: Boolean = true

    // Whether fuzzy pinyin (模糊音) is enabled
    private var fuzzyPinyinEnabled: Boolean = false

    // Neural pinyin recognizer for encoder-decoder model (primary method for long sentences)
    private var neuralPinyinRecognizer: NeuralPinyinRecognizer? = null
    private var neuralPinyinEnabled: Boolean = false  // Disabled by default until model is loaded
    private var neuralPinyinMinLetters: Int = 6
    private var neuralPinyinPriority: Boolean = false  // When true, neural prediction shown as top suggestion
    private var neuralPinyinCount: Int = 1  // Number of neural predictions (1 or 3)

    // Fuzzy pinyin substitution rules
    // z↔zh, c↔ch, s↔sh, l↔n, en↔eng, in↔ing
    private val fuzzyInitials = mapOf(
        "z" to "zh", "zh" to "z",
        "c" to "ch", "ch" to "c",
        "s" to "sh", "sh" to "s",
        "l" to "n", "n" to "l"
    )
    private val fuzzyFinals = mapOf(
        "en" to "eng", "eng" to "en",
        "in" to "ing", "ing" to "in"
    )

    data class Snapshot(
        val isActive: Boolean,
        val buffer: String,
        val candidates: List<String>,
        val hasCandidates: Boolean,
        val currentPage: Int = 0,
        val totalPages: Int = 1,
        val hasNextPage: Boolean = false,
        val hasPrevPage: Boolean = false,
        val isNextWordPrediction: Boolean = false  // True when showing next-word predictions
    )

    // User memory for learning preferences
    private val userMemory: UserPinyinMemory = UserPinyinMemory.getInstance(context)

    // Auto-phrase memory for learning new phrases from user input
    private val autoPhraseMemory: AutoPhraseMemory = AutoPhraseMemory.getInstance(context)

    // Session tracking for auto-phrase learning
    // Tracks (pinyin, character) pairs selected in the current input session
    private val sessionSelections = mutableListOf<Pair<String, String>>()

    /**
     * Checks if memory function is enabled.
     */
    private fun isMemoryEnabled(): Boolean {
        return SettingsManager.getMemoryFunctionEnabled(context)
    }

    /**
     * Checks if auto-phrase memory is enabled.
     */
    private fun isAutoPhraseMemoryEnabled(): Boolean {
        return isMemoryEnabled() && SettingsManager.isAutoPhrasMemoryEnabled(context)
    }

    /**
     * Checks if abbreviation input (首字母) is enabled.
     */
    private fun isAbbreviationInputEnabled(): Boolean {
        return SettingsManager.isAbbreviationInputEnabled(context)
    }

    /**
     * Checks if the input looks like an abbreviation (首字母).
     * An abbreviation is a string that doesn't contain any valid pinyin syllable.
     * For example: "nh" (你好), "xwza" (学无止境) are abbreviations.
     * While "nihao", "wo", "ni" are valid pinyin inputs.
     */
    private fun isAbbreviationInput(input: String): Boolean {
        val cleanInput = input.lowercase().replace("'", "")
        if (cleanInput.isEmpty()) return false
        // If we can find any valid pinyin syllable, it's not purely abbreviation
        val longestSyllable = PinyinDictionary.findLongestSyllable(cleanInput)
        return longestSyllable == null || longestSyllable.isEmpty()
    }

    /**
     * Sorts candidates by frequency if memory function is enabled, otherwise returns original list.
     */
    private fun sortByFrequencyIfEnabled(pinyin: String, candidates: List<String>): List<String> {
        return if (isMemoryEnabled()) {
            userMemory.sortByFrequency(pinyin, candidates)
        } else {
            // If memory disabled, still apply base dictionary frequency sorting (from PinyinDictionary)
            PinyinDictionary.sortByFrequency(pinyin, candidates)
        }
    }

    /**
     * Records a selection if memory function is enabled.
     */
    private fun recordSelectionIfEnabled(pinyin: String, selected: String) {
        if (isMemoryEnabled()) {
            userMemory.recordSelection(pinyin, selected)
        }
    }

    // Next-word predictor for suggesting words after commit
    private val nextWordPredictor: NextWordPredictor = NextWordPredictor.getInstance(context)

    // User custom dictionary for user-defined shortcuts
    private val customDictionary: UserCustomDictionary = UserCustomDictionary.getInstance(context)

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
                // Finalize session before exiting pinyin mode
                finalizeSession()
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
        return handleLetterKeyAtPosition(char, -1)
    }

    /**
     * Processes a letter key press in Pinyin mode at a specific cursor position.
     * Inserts the letter at the specified position in the buffer and updates candidates.
     * @param char The character to add (a-z)
     * @param cursorPosition The position to insert at (0 = before first char). If -1 or > buffer.length, appends at end.
     * @return true if the key was handled, false otherwise
     */
    fun handleLetterKeyAtPosition(char: Char, cursorPosition: Int): Boolean {
        if (!isPinyinModeActive) {
            Log.d(TAG, "handleLetterKeyAtPosition: Pinyin mode not active")
            return false
        }

        // Only accept lowercase letters
        val lowerChar = char.lowercaseChar()
        if (!lowerChar.isLetter() || lowerChar < 'a' || lowerChar > 'z') {
            Log.d(TAG, "handleLetterKeyAtPosition: Invalid character '$char'")
            return false
        }

        // Check buffer length limit
        if (buffer.length >= MAX_BUFFER_LENGTH) {
            Log.w(TAG, "Buffer full, ignoring input")
            return true
        }

        // Finalize any pending session when user starts new input
        // This captures phrases like "张四李三" when user starts typing next phrase
        // Check sessionSelections directly since isShowingNextWordPredictions may not be set
        // if next-word predictions are disabled
        if (sessionSelections.size >= 2) {
            finalizeSession()
        }

        // Clear next-word prediction state when user starts typing
        if (isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            nextWordPredictor.onUserStartedTyping()
        }

        // Insert at position or append at end
        val insertPosition = if (cursorPosition < 0 || cursorPosition > buffer.length) {
            buffer.length
        } else {
            cursorPosition
        }
        buffer.insert(insertPosition, lowerChar)
        try {
            updateCandidates()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating candidates after letter input: ${e.message}", e)
            // Fallback: clear candidates to prevent crash loop
            allCandidates = emptyList()
        }
        Log.d(TAG, "Letter added at position $insertPosition: '$lowerChar' → Buffer: '$buffer', Candidates: ${allCandidates.joinToString(", ")}")
        return true
    }

    /**
     * Handles the separator key (backtick) in Pinyin mode.
     * Used to disambiguate syllable boundaries (e.g., "he`ni" means "和你" not "很").
     * @return true if handled, false otherwise
     */
    fun handleSeparatorKey(): Boolean {
        if (!isPinyinModeActive) {
            return false
        }

        // Don't allow separator at start or consecutive separators
        if (buffer.isEmpty() || buffer.last() == SEPARATOR) {
            return false
        }

        // Check buffer length limit
        if (buffer.length >= MAX_BUFFER_LENGTH) {
            Log.w(TAG, "Buffer full, ignoring separator")
            return true
        }

        buffer.append(SEPARATOR)
        updateCandidates()
        Log.d(TAG, "Separator added → Buffer: '$buffer'")
        return true
    }

    /**
     * Handles backspace in Pinyin mode.
     * Removes the last character from the buffer, or clears next-word predictions if buffer is empty.
     * @return true if handled, false otherwise
     */
    fun handleBackspace(): Boolean {
        return handleBackspaceAtPosition(-1)
    }

    /**
     * Handles backspace in Pinyin mode at a specific cursor position within the buffer.
     * @param cursorPosition The cursor position within the buffer (0 = before first char, buffer.length = after last char).
     *                       If -1 or >= buffer.length, deletes the last character.
     * @return true if handled, false otherwise
     */
    fun handleBackspaceAtPosition(cursorPosition: Int): Boolean {
        if (!isPinyinModeActive) {
            return false
        }

        // If showing next-word predictions and buffer is empty, clear predictions
        if (buffer.isEmpty() && isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            allCandidates = emptyList()
            currentPage = 0
            nextWordPredictor.clearState()
            return true  // Consume the key - only clear predictions, don't delete character
        }

        if (buffer.isEmpty()) {
            return false
        }

        // Determine which character to delete
        val deletePosition = if (cursorPosition <= 0 || cursorPosition > buffer.length) {
            // Default: delete last character (cursor at end or invalid position)
            buffer.length - 1
        } else {
            // Delete the character before the cursor
            cursorPosition - 1
        }

        if (deletePosition >= 0 && deletePosition < buffer.length) {
            buffer.deleteCharAt(deletePosition)
            updateCandidates()
            Log.d(TAG, "Backspace at position $cursorPosition - Buffer: '$buffer', Candidates: ${allCandidates.size}")
        }
        return true
    }

    /**
     * Gets the current cursor position hint for the buffer.
     * This is used when the user moves the cursor within composing text.
     */
    fun getBufferLength(): Int = buffer.length

    /**
     * Selects a candidate by index on current page and returns the selected character.
     * For combined phrase candidates, consumes the entire buffer.
     * For single-character candidates, consumes only the first syllable.
     * @param index The candidate index on current page (0-based)
     * @return The selected Chinese character, or null if index invalid
     */
    fun selectCandidate(index: Int): String? {
        try {
            val currentPageCandidates = getCurrentPageCandidates()
            if (!isPinyinModeActive || index < 0 || index >= currentPageCandidates.size) {
                return null
            }

            val selected = currentPageCandidates[index]

        // Calculate the actual index in the full candidate list (accounting for pagination)
        val actualIndex = currentPage * pageSize + index

        // Determine which pinyin to consume based on candidate type
        // Use phraseCandidateSet for accurate phrase detection in mixed-sorted lists
        // ALSO check if the selected string has multiple characters (length > 1) to catch phrases
        // that may have come from user learning/memory but aren't in phraseCandidateSet
        val isPhrase = (selected in phraseCandidateSet) || (selected.length > 1)
        val pinyinToConsume: String

        if (isPhrase) {
            // Combined phrase candidate - consume the entire matched buffer (all segments)
            pinyinToConsume = matchedPinyin
            // Record learning for the combined pinyin
            recordSelectionIfEnabled(pinyinToConsume, selected)
            // Track phrase selection for auto-phrase learning (include phrases in session)
            // This allows learning longer phrases like "这个输入法" when user picks "这", "个", "输入法"
            if (isAutoPhraseMemoryEnabled() && pinyinToConsume.isNotEmpty()) {
                sessionSelections.add(pinyinToConsume to selected)
            }
        } else {
            // Single-character candidate - consume only the first syllable
            pinyinToConsume = firstSyllable
            // Record learning for just the first syllable
            recordSelectionIfEnabled(pinyinToConsume, selected)
            // Track single-character selection for auto-phrase learning
            if (isAutoPhraseMemoryEnabled() && pinyinToConsume.isNotEmpty()) {
                sessionSelections.add(pinyinToConsume to selected)
            }
        }

        Log.d(TAG, "Selected candidate $index (actual: $actualIndex): '$selected', consuming: '$pinyinToConsume' (phrase count: $phraseCandidateCount, isPhrase: $isPhrase)")

        // Remove the consumed pinyin from the buffer
        // Need to handle the case where buffer contains separators
        val bufferStr = buffer.toString()
        val bufferWithoutSep = bufferStr.replace(SEPARATOR.toString(), "")

        if (isPhrase && pinyinToConsume == bufferWithoutSep) {
            // Consuming entire buffer for phrase - clear everything including separators
            buffer.clear()
            Log.d(TAG, "Consumed entire buffer for phrase")
        } else if (pinyinToConsume.isNotEmpty()) {
            // Try to find and consume the pinyin from the start of buffer
            // Handle both with and without separators
            var consumed = false

            // First, try direct match (no separator at start)
            if (bufferStr.startsWith(pinyinToConsume)) {
                buffer.delete(0, pinyinToConsume.length)
                // Also remove any separator that immediately follows
                if (buffer.isNotEmpty() && buffer[0] == SEPARATOR) {
                    buffer.deleteCharAt(0)
                }
                consumed = true
            } else {
                // Try to match considering segments - consume until we've matched the pinyin
                var matchedLen = 0
                var pinyinPos = 0
                for (i in 0 until bufferStr.length) {
                    if (bufferStr[i] == SEPARATOR) {
                        matchedLen++
                        continue
                    }
                    if (pinyinPos < pinyinToConsume.length && bufferStr[i] == pinyinToConsume[pinyinPos]) {
                        matchedLen++
                        pinyinPos++
                        if (pinyinPos >= pinyinToConsume.length) {
                            // Matched all of pinyinToConsume
                            buffer.delete(0, matchedLen)
                            // Remove trailing separator if present
                            if (buffer.isNotEmpty() && buffer[0] == SEPARATOR) {
                                buffer.deleteCharAt(0)
                            }
                            consumed = true
                            break
                        }
                    } else {
                        break
                    }
                }
            }

            if (consumed) {
                Log.d(TAG, "Consumed '$pinyinToConsume', remaining buffer: '$buffer'")
            } else {
                // Fallback: clear entire buffer if something went wrong
                Log.w(TAG, "Failed to consume pinyin '$pinyinToConsume' from buffer '$bufferStr', clearing buffer")
                buffer.clear()
            }
        }

        // Record the committed word for next-word prediction learning
        nextWordPredictor.recordCommittedWord(selected)

        // Update candidates for the remaining buffer
        // If buffer is empty after selection, finalize and show next-word predictions
        if (buffer.isEmpty()) {
            // Finalize the session now that the phrase input is complete
            // This records phrases like "姜浩的家" when user finishes picking all characters
            if (sessionSelections.size >= 2) {
                finalizeSession()
            }
            showNextWordPredictions()
        } else {
            isShowingNextWordPredictions = false
            updateCandidates()
        }

        // Convert to traditional Chinese if setting is enabled
        val useTraditional = SettingsManager.isTraditionalChineseMode(context)
            return if (useTraditional) {
                ChineseCharacterConverter.toTraditional(selected)
            } else {
                selected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in selectCandidate: ${e.message}", e)
            return null
        }
    }

    /**
     * Finalizes the current input session for auto-phrase learning.
     * Combines all single-character selections into a phrase and records it.
     */
    private fun finalizeSession() {
        if (!isAutoPhraseMemoryEnabled() || sessionSelections.size < 2) {
            sessionSelections.clear()
            return
        }

        // Combine all selections into a phrase
        val combinedPinyin = sessionSelections.joinToString("") { it.first }
        val combinedPhrase = sessionSelections.joinToString("") { it.second }

        // Check if this phrase already exists in dictionary - if so, skip
        val existingPhrases = PinyinDictionary.getPhraseCandidates(combinedPinyin)
        if (combinedPhrase !in existingPhrases) {
            // Record the new phrase for auto-learning
            autoPhraseMemory.recordPhrase(combinedPinyin, combinedPhrase)
            Log.d(TAG, "Session finalized: '$combinedPinyin' → '$combinedPhrase'")
        }

        sessionSelections.clear()
    }

    /**
     * Shows next-word predictions if available and enabled.
     */
    private fun showNextWordPredictions() {
        try {
            if (nextWordPredictionEnabled && nextWordPredictor.isShowingPredictions()) {
                val nextWordSuggestions = nextWordPredictor.getSuggestions()
                if (nextWordSuggestions.isNotEmpty()) {
                    isShowingNextWordPredictions = true
                    allCandidates = nextWordSuggestions
                    currentPage = 0
                    phraseCandidateCount = nextWordSuggestions.size  // All are "phrase" type for selection
                    phraseCandidateSet = nextWordSuggestions.toSet()  // All predictions are phrases
                    matchedPinyin = ""
                    firstSyllable = ""
                    Log.d(TAG, "Showing next-word predictions: $nextWordSuggestions")
                    return
                }
            }
            isShowingNextWordPredictions = false
            updateCandidates()
        } catch (e: Exception) {
            Log.e(TAG, "Error in showNextWordPredictions: ${e.message}", e)
            isShowingNextWordPredictions = false
            allCandidates = emptyList()
        }
    }


    /**
     * Calculates total number of pages.
     * In dynamic mode, iterates through pages with variable sizes.
     */
    private fun getTotalPages(): Int {
        if (allCandidates.isEmpty()) return 1

        if (!isJuyingModeEnabled || dynamicDisplayLimit <= 0) {
            // Fixed page size mode
            val effectivePageSize = getEffectivePageSize()
            return (allCandidates.size + effectivePageSize - 1) / effectivePageSize
        }

        // Dynamic page size mode - count pages by iterating
        var pageCount = 0
        var startIndex = 0
        while (startIndex < allCandidates.size) {
            val pageEndIndex = minOf(startIndex + JUYING_PAGE_SIZE, allCandidates.size)
            val pageCandidates = allCandidates.subList(startIndex, pageEndIndex)
            val pageLimit = calculateDynamicLimitForCandidates(pageCandidates)
            startIndex += pageLimit
            pageCount++
        }
        return maxOf(pageCount, 1)
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
        phraseCandidateSet = emptySet()
        firstSyllable = ""
        currentPage = 0
        // Clear session without finalizing (user cancelled input)
        sessionSelections.clear()
        Log.d(TAG, "Buffer cleared")
    }

    /**
     * Represents a parsed segment from user input.
     * Each segment is either delimited by backtick or auto-parsed using longest-match.
     */
    private data class ParsedSegment(
        val pinyin: String,           // The pinyin for this segment (e.g., "wo", "xiang")
        val candidates: List<String>, // Chinese character candidates for this segment
        val isComplete: Boolean       // True if this is a complete syllable/phrase
    )

    // Cached parsed segments for the current buffer
    private var parsedSegments: List<ParsedSegment> = emptyList()

    /**
     * Updates candidate list based on current buffer.
     * Supports multi-segment input with backtick separator for disambiguation.
     *
     * Input patterns:
     * - "woxiang" → Auto-parse as "wo" + "xiang" → "我想" (combined candidates)
     * - "wo`xiang" → Explicit segments "wo" | "xiang" → "我想"
     * - "he`ni" → "he" | "ni" → "和你" (not "hen" + something)
     * - Single segment like "ni" → Normal single-syllable behavior
     */
    private fun updateCandidates() {
        currentPage = 0  // Reset to first page when candidates change

        if (buffer.isEmpty()) {
            allCandidates = emptyList()
            matchedPinyin = ""
            phraseCandidateCount = 0
            firstSyllable = ""
            parsedSegments = emptyList()
            return
        }

        val bufferStr = buffer.toString()

        // Safety limit: if buffer is very long, only process the first portion
        // to prevent stack overflow from deep recursion in parsing
        val maxProcessLength = 100  // ~30 syllables max for practical use
        val processStr = if (bufferStr.length > maxProcessLength) {
            Log.w(TAG, "Buffer too long (${bufferStr.length}), truncating to $maxProcessLength for parsing")
            bufferStr.take(maxProcessLength)
        } else {
            bufferStr
        }

        try {
            // Parse the buffer into segments (using backtick delimiters or auto-parsing)
            parsedSegments = parseBufferIntoSegments(processStr)

            if (parsedSegments.isEmpty()) {
                // Couldn't parse - show prefix matches for partial input
                handleUnparsableInput(processStr)
                return
            }

            // Generate combined candidates from all segments
            generateCombinedCandidates(bufferStr, parsedSegments)
        } catch (e: StackOverflowError) {
            Log.e(TAG, "Stack overflow in updateCandidates, buffer length: ${bufferStr.length}", e)
            // Fallback: just show the raw buffer as unparsable
            allCandidates = emptyList()
            matchedPinyin = ""
            phraseCandidateCount = 0
            firstSyllable = ""
            parsedSegments = emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateCandidates: ${e.message}", e)
            allCandidates = emptyList()
            matchedPinyin = ""
            phraseCandidateCount = 0
            firstSyllable = ""
            parsedSegments = emptyList()
        }
    }

    /**
     * Parses the buffer into segments, respecting backtick separators.
     * For text without backticks, uses auto-parsing with longest-match.
     */
    private fun parseBufferIntoSegments(input: String): List<ParsedSegment> {
        // Remove trailing separator if present
        val cleanInput = input.trimEnd(SEPARATOR)
        if (cleanInput.isEmpty()) return emptyList()

        val segments = mutableListOf<ParsedSegment>()

        // Split by backtick separator
        val parts = cleanInput.split(SEPARATOR)

        for (part in parts) {
            if (part.isEmpty()) continue

            // For each part, try to auto-parse into syllables
            val partSegments = autoParseSegment(part)
            segments.addAll(partSegments)
        }

        return segments
    }

    /**
     * Auto-parses a segment (without separators) into syllables/phrases using smart matching.
     * Prioritizes phrase matches over individual syllables for more accurate results.
     * Returns a list of ParsedSegments for each syllable/phrase found.
     */
    private fun autoParseSegment(segment: String): List<ParsedSegment> {
        if (segment.isEmpty()) return emptyList()

        // Hard limit on input length to prevent crashes
        val maxInputLength = 100
        val limitedSegment = if (segment.length > maxInputLength) {
            Log.w(TAG, "autoParseSegment: input too long (${segment.length}), truncating to $maxInputLength")
            segment.take(maxInputLength)
        } else {
            segment
        }

        val segments = mutableListOf<ParsedSegment>()
        var remaining = limitedSegment.lowercase()
        val maxSegments = 30  // Reduced limit to prevent excessive processing

        try {
            while (remaining.isNotEmpty() && segments.size < maxSegments) {
                // First, check if there's a phrase match for the entire remaining input
                val fullPhraseCandidates = PinyinDictionary.getPhraseCandidates(remaining)
                if (fullPhraseCandidates.isNotEmpty()) {
                    // Found a phrase match for entire remaining - use it as a single segment
                    segments.add(ParsedSegment(
                        pinyin = remaining,
                        candidates = sortByFrequencyIfEnabled(remaining, fullPhraseCandidates),
                        isComplete = true
                    ))
                    break
                }

                // Try to find the longest phrase match starting at current position
                // This is key: check phrases BEFORE falling back to single syllables
                val longestPhrase = findLongestPhraseMatch(remaining)
                if (longestPhrase != null) {
                    // Get phrase candidates - include fuzzy variants if enabled
                    val phraseCandidates = getPhraseCandidatesForInput(longestPhrase)
                    segments.add(ParsedSegment(
                        pinyin = longestPhrase,
                        candidates = sortByFrequencyIfEnabled(longestPhrase, phraseCandidates),
                        isComplete = true
                    ))
                    remaining = remaining.substring(longestPhrase.length)
                    continue
                }

                // No phrase match found, try longest-match syllable (with fuzzy support)
                val syllable = findLongestSyllableWithFuzzy(remaining)
                if (syllable != null) {
                    // Get candidates with fuzzy variants if enabled
                    val candidates = getCandidatesWithFuzzy(syllable)
                    segments.add(ParsedSegment(
                        pinyin = syllable,
                        candidates = sortByFrequencyIfEnabled(syllable, candidates),
                        isComplete = true
                    ))
                    remaining = remaining.substring(syllable.length)
                } else {
                    // Can't parse as complete syllable - might be partial input
                    // Try prefix matching for the remaining text (with fuzzy support)
                    var prefixCandidates = PinyinDictionary.getCandidatesForPrefix(remaining)

                    // If no exact prefix match and fuzzy is enabled, try fuzzy variants
                    if (prefixCandidates.isEmpty() && fuzzyPinyinEnabled) {
                        val variants = getFuzzyVariants(remaining)
                        for (variant in variants) {
                            if (variant != remaining) {
                                prefixCandidates = PinyinDictionary.getCandidatesForPrefix(variant)
                                if (prefixCandidates.isNotEmpty()) {
                                    Log.d(TAG, "Fuzzy prefix match: '$remaining' → '$variant'")
                                    break
                                }
                            }
                        }
                    }

                    if (prefixCandidates.isNotEmpty()) {
                        val firstSyl = PinyinDictionary.getFirstSyllableForPrefix(remaining) ?: remaining
                        segments.add(ParsedSegment(
                            pinyin = remaining,
                            candidates = sortByFrequencyIfEnabled(remaining, prefixCandidates),
                            isComplete = false
                        ))
                    }
                    break
                }
            }
        } catch (e: StackOverflowError) {
            Log.e(TAG, "Stack overflow in autoParseSegment: ${e.message}", e)
            return emptyList()
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory in autoParseSegment: ${e.message}", e)
            return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error in autoParseSegment: ${e.message}", e)
            return emptyList()
        }

        return segments
    }

    /**
     * Finds the longest phrase match starting at the current position.
     * Uses greedy longest-match from the full phrase dictionary.
     * Supports fuzzy pinyin matching when enabled.
     *
     * The philosophy is: match as many phrases as possible to give users choices,
     * then rely on frequency sorting and user memory to learn the right candidates.
     *
     * @return The longest phrase pinyin that has a match, or null if none found
     */
    private fun findLongestPhraseMatch(input: String): String? {
        if (input.length < 2) return null

        try {
            // Limit input length to prevent excessive parsing with long pinyin
            val maxInputForPhrase = 15  // Reduced from 20 to ~4-5 syllables max
            val limitedInput = if (input.length > maxInputForPhrase) input.take(maxInputForPhrase) else input

            // First, split the entire input into syllables (using fuzzy matching if enabled)
            val allSyllables = mutableListOf<String>()
            var remaining = limitedInput
            val maxSyllables = 4  // Reduced from 6 to prevent excessive combinations
            while (remaining.isNotEmpty() && allSyllables.size < maxSyllables) {
                val syllable = findLongestSyllableWithFuzzy(remaining)
                if (syllable != null) {
                    allSyllables.add(syllable)
                    remaining = remaining.substring(syllable.length)
                } else {
                    break
                }
            }

            // Need at least 2 syllables to form a phrase
            if (allSyllables.size < 2) return null

            // Try progressively shorter combinations starting from all syllables
            // Match any phrase in the dictionary - rely on frequency sorting for ranking
            for (numSyllables in allSyllables.size downTo 2) {
                val phrase = allSyllables.take(numSyllables).joinToString("")
                val phraseCandidates = PinyinDictionary.getPhraseCandidates(phrase)
                if (phraseCandidates.isNotEmpty()) {
                    Log.d(TAG, "Found phrase match: '$phrase' -> ${phraseCandidates.take(3)}")
                    return phrase
                }

                // If fuzzy enabled, try phrase variants (only for short phrases to avoid explosion)
                if (fuzzyPinyinEnabled && numSyllables <= 3) {
                    // Generate fuzzy variants for each syllable and try combinations
                    val syllablesToUse = allSyllables.take(numSyllables)
                    val fuzzyPhraseCandidates = getPhraseCandidatesWithFuzzy(syllablesToUse)
                    if (fuzzyPhraseCandidates.isNotEmpty()) {
                        Log.d(TAG, "Found fuzzy phrase match: '$phrase' -> ${fuzzyPhraseCandidates.take(3)}")
                        return phrase
                    }
                }
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error in findLongestPhraseMatch: ${e.message}", e)
            return null
        }
    }

    /**
     * Gets phrase candidates for a given pinyin input, including fuzzy variants if enabled.
     * This ensures phrase candidates are properly found when fuzzy pinyin is active.
     */
    private fun getPhraseCandidatesForInput(pinyin: String): List<String> {
        // First try exact match
        val exactCandidates = PinyinDictionary.getPhraseCandidates(pinyin)
        if (exactCandidates.isNotEmpty()) {
            return exactCandidates
        }

        if (!fuzzyPinyinEnabled) {
            return emptyList()
        }

        // Split pinyin into syllables and get fuzzy phrase candidates
        val syllables = mutableListOf<String>()
        var remaining = pinyin
        val maxSyllables = 6  // Limit to prevent excessive processing
        while (remaining.isNotEmpty() && syllables.size < maxSyllables) {
            val syllable = PinyinDictionary.findLongestSyllable(remaining)
                ?: findLongestSyllableWithFuzzy(remaining)
            if (syllable != null) {
                syllables.add(syllable)
                remaining = remaining.substring(syllable.length)
            } else {
                break
            }
        }

        return if (syllables.size >= 2) {
            getPhraseCandidatesWithFuzzy(syllables)
        } else {
            emptyList()
        }
    }

    /**
     * Gets phrase candidates for syllables including fuzzy variants.
     */
    private fun getPhraseCandidatesWithFuzzy(syllables: List<String>): List<String> {
        if (syllables.isEmpty()) return emptyList()

        try {
            // Limit syllables to prevent exponential explosion with fuzzy matching
            // With 3 syllables and 4 variants each, that's 4^3 = 64 combinations (safe)
            // With 4 syllables it becomes 256 (borderline), 5+ is dangerous
            val maxSyllablesForFuzzy = 3  // Reduced from 4 to be safer
            val limitedSyllables = syllables.take(maxSyllablesForFuzzy)

            // First try exact phrase
            val exactPhrase = syllables.joinToString("")
            val exactCandidates = PinyinDictionary.getPhraseCandidates(exactPhrase)
            if (exactCandidates.isNotEmpty()) {
                return exactCandidates
            }

            if (!fuzzyPinyinEnabled) return emptyList()

            // Skip fuzzy matching for long phrases (too many combinations)
            if (syllables.size > maxSyllablesForFuzzy) {
                return emptyList()
            }

            // Generate fuzzy variants for each syllable
            val variantLists = limitedSyllables.map { getFuzzyVariants(it) }

            // Try combinations iteratively (not recursively) to avoid stack issues
            val seen = mutableSetOf<String>()
            val results = mutableListOf<String>()
            var combinationCount = 0
            val maxCombinations = 100  // Hard limit on combinations to try

            // Iterative cartesian product instead of recursive
            val indices = IntArray(variantLists.size) { 0 }
            while (combinationCount < maxCombinations && results.size < 9) {
                // Build current combination
                val current = StringBuilder()
                for (i in variantLists.indices) {
                    current.append(variantLists[i][indices[i]])
                }
                val phrase = current.toString()
                combinationCount++

                if (phrase != exactPhrase && phrase !in seen) {
                    seen.add(phrase)
                    val candidates = PinyinDictionary.getPhraseCandidates(phrase)
                    for (c in candidates) {
                        if (c !in seen && results.size < 9) {
                            seen.add(c)
                            results.add(c)
                        }
                    }
                }

                // Increment indices (like counting in variable-base number system)
                var carry = true
                for (i in variantLists.indices.reversed()) {
                    if (carry) {
                        indices[i]++
                        if (indices[i] >= variantLists[i].size) {
                            indices[i] = 0
                        } else {
                            carry = false
                        }
                    }
                }
                // If we carried all the way through, we've tried all combinations
                if (carry) break
            }

            return results
        } catch (e: Exception) {
            Log.e(TAG, "Error in getPhraseCandidatesWithFuzzy: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Generates combined candidates from multiple segments.
     * Shows abbreviation matches first, then custom dictionary phrases, then combined phrases, then individual character options.
     */
    private fun generateCombinedCandidates(fullBuffer: String, segments: List<ParsedSegment>) {
        if (segments.isEmpty()) {
            allCandidates = emptyList()
            matchedPinyin = ""
            phraseCandidateCount = 0
            phraseCandidateSet = emptySet()
            firstSyllable = ""
            return
        }

        try {
            generateCombinedCandidatesInternal(fullBuffer, segments)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory in generateCombinedCandidates: ${e.message}", e)
            allCandidates = emptyList()
            matchedPinyin = ""
            phraseCandidateCount = 0
            phraseCandidateSet = emptySet()
            firstSyllable = ""
        } catch (e: Exception) {
            Log.e(TAG, "Error in generateCombinedCandidates: ${e.message}", e)
            allCandidates = emptyList()
            matchedPinyin = ""
            phraseCandidateCount = 0
            phraseCandidateSet = emptySet()
            firstSyllable = ""
        }
    }

    private fun generateCombinedCandidatesInternal(fullBuffer: String, segments: List<ParsedSegment>) {
        // Find the actual first syllable (not phrase) for single-character fallback
        val bufferWithoutSep = fullBuffer.replace(SEPARATOR.toString(), "")

        // Get abbreviation matches from user memory (highest priority)
        // These are phrases the user has typed before using abbreviations like "nh" for "你好"
        val abbreviationCandidates = if (isMemoryEnabled() && isAbbreviationInputEnabled()) userMemory.getAbbreviationCandidates(bufferWithoutSep) else emptyList()

        // Get custom dictionary phrases for this code (second highest priority)
        val customPhrases = customDictionary.getPinyinPhrases(bufferWithoutSep)
        val actualFirstSyllable = PinyinDictionary.findLongestSyllable(bufferWithoutSep) ?: ""

        // Get single-character candidates for the first syllable (always needed as fallback)
        // Use fuzzy matching if enabled to include candidates from fuzzy variants
        val firstSyllableCharCandidates: List<String>
        if (actualFirstSyllable.isNotEmpty()) {
            firstSyllable = actualFirstSyllable
            val rawCandidates = getCandidatesWithFuzzy(actualFirstSyllable)
            firstSyllableCharCandidates = sortByFrequencyIfEnabled(actualFirstSyllable, rawCandidates)
        } else {
            // For partial input (e.g., single letter "w"), use prefix matching
            // and get candidates from the first parsed segment
            val firstSegment = segments.firstOrNull()
            if (firstSegment != null && firstSegment.candidates.isNotEmpty()) {
                firstSyllable = firstSegment.pinyin
                firstSyllableCharCandidates = firstSegment.candidates
            } else {
                // Last resort: try direct prefix matching
                val prefixCandidates = PinyinDictionary.getCandidatesForPrefix(bufferWithoutSep)
                if (prefixCandidates.isNotEmpty()) {
                    firstSyllable = bufferWithoutSep
                    firstSyllableCharCandidates = sortByFrequencyIfEnabled(bufferWithoutSep, prefixCandidates)
                } else {
                    firstSyllable = ""
                    firstSyllableCharCandidates = emptyList()
                }
            }
        }

        // Check if all segments have candidates
        val allHaveCandidates = segments.all { it.candidates.isNotEmpty() }

        val resultCandidates = mutableListOf<String>()
        val customPhraseSet = customPhrases.toSet()  // For quick lookup

        // Skip if input is abbreviation-style and abbreviation input is disabled
        val skipAutoLearnedForAbbrev = isAbbreviationInput(bufferWithoutSep) && !isAbbreviationInputEnabled()
        val autoLearnedPhrases = if (isAutoPhraseMemoryEnabled() && !skipAutoLearnedForAbbrev) autoPhraseMemory.getLearnedPhrases(bufferWithoutSep) else emptyList()

        // Get partial pinyin matches for abbreviated input (e.g., "shrfa" -> "输入法")
        // This allows typing abbreviated pinyin directly without needing prior selections
        // Includes: dictionary phrases, learned phrases, and custom dictionary phrases
        // All sources are merged and sorted by dictionary frequency (highest frequency first)
        val abbreviatedPinyinPhrases = if (SettingsManager.isPartialPinyinMatchingEnabled(context) && bufferWithoutSep.length >= 2) {
            // Get all matches with their frequency scores
            val dictMatches = PinyinDictionary.getPartialPinyinMatches(bufferWithoutSep, maxResults = 20)
            val learnedPhrases = if (isAutoPhraseMemoryEnabled()) autoPhraseMemory.getLearnedPhrasesForAbbreviatedPinyin(bufferWithoutSep) else emptyList()
            val customPhrases = customDictionary.getPinyinPhrasesForAbbreviatedPinyin(bufferWithoutSep)

            // Create a map of phrase to frequency score (lower = higher frequency)
            val phraseScores = mutableMapOf<String, Int>()

            // Add dictionary matches with their frequency scores
            for (match in dictMatches) {
                phraseScores[match.phrase] = match.frequencyScore
            }

            // Custom phrases get highest priority (score 0)
            for (phrase in customPhrases) {
                if (phrase !in phraseScores || phraseScores[phrase]!! > 0) {
                    phraseScores[phrase] = 0
                }
            }

            // Learned phrases get high priority (score 1-10 based on position)
            for ((index, phrase) in learnedPhrases.withIndex()) {
                if (phrase !in phraseScores || phraseScores[phrase]!! > index + 1) {
                    phraseScores[phrase] = index + 1
                }
            }

            // Sort by frequency score (lower = higher priority) and return top 9
            phraseScores.entries
                .sortedBy { it.value }
                .take(9)
                .map { it.key }
        } else {
            emptyList()
        }

        // Check for dictionary exact phrase match
        // Only for single-segment input where dictionary might have the exact phrase
        val dictExactMatch = if (segments.size == 1) {
            val exactPhrases = PinyinDictionary.getPhraseCandidates(bufferWithoutSep)
            // Take the first (highest frequency) exact match from dictionary
            exactPhrases.firstOrNull()
        } else {
            null
        }

        // Get neural pinyin suggestions for long sentences
        val neuralSuggestions = getLongSentenceSuggestions(bufferWithoutSep)

        // NOTE: Custom phrases, auto-learned phrases, dict exact match, and neural suggestion
        // are ALL added to frequency-based sorting below (not in fixed priority order)
        // This ensures the most-used candidate appears first, regardless of source.

        // Add partial/prefix matching phrases (e.g., "wos" matches "woshi" → "我是")
        // Two modes:
        // 1. After character selection: uses session context (e.g., already typed "wo", now typing "shi")
        // 2. Direct abbreviated input: uses the new getPartialMatchPhrases algorithm (e.g., "shrfa" → "输入法")
        // IMPORTANT: Strip already-committed characters from suggestions when in session mode
        val partialMatchPhrases: List<String>
        val dictPartialPhrases: List<String>
        val partialMatchingEnabled = SettingsManager.isPartialPinyinMatchingEnabled(context)
        if (partialMatchingEnabled) {
            if (sessionSelections.isNotEmpty()) {
                // Session mode: build the full pinyin from session + current buffer
                val sessionPinyin = sessionSelections.joinToString("") { it.first }
                val fullPinyin = sessionPinyin + bufferWithoutSep
                // Get already-committed characters to strip from suggestions
                val committedChars = sessionSelections.joinToString("") { it.second }

                // Get partial matches using the full pinyin
                val rawPartialPhrases = if (isAutoPhraseMemoryEnabled()) autoPhraseMemory.getLearnedPhrasesWithPrefix(fullPinyin) else emptyList()
                // Strip already-committed prefix from each phrase
                partialMatchPhrases = rawPartialPhrases.mapNotNull { phrase ->
                    if (phrase.startsWith(committedChars) && phrase.length > committedChars.length) {
                        phrase.substring(committedChars.length)  // Return only the remaining part
                    } else {
                        null  // Skip phrases that don't start with committed chars
                    }
                }

                // Add dictionary partial matches (common phrases sorted by frequency)
                val rawDictPhrases = PinyinDictionary.getPhraseCandidatesWithPrefix(fullPinyin)
                dictPartialPhrases = rawDictPhrases.mapNotNull { phrase ->
                    if (phrase.startsWith(committedChars) && phrase.length > committedChars.length) {
                        phrase.substring(committedChars.length)
                    } else {
                        null
                    }
                }
            } else {
                // Direct mode: no session, use prefix matching on current buffer only
                partialMatchPhrases = if (isAutoPhraseMemoryEnabled() && bufferWithoutSep.length >= 2) {
                    autoPhraseMemory.getLearnedPhrasesWithPrefix(bufferWithoutSep)
                } else {
                    emptyList()
                }
                dictPartialPhrases = if (bufferWithoutSep.length >= 2) {
                    PinyinDictionary.getPhraseCandidatesWithPrefix(bufferWithoutSep)
                } else {
                    emptyList()
                }
            }
        } else {
            partialMatchPhrases = emptyList()
            dictPartialPhrases = emptyList()
        }

        // If no regular candidates but we have abbreviation/custom/auto-learned/neural/abbreviated-pinyin matches, use them
        if (!allHaveCandidates && firstSyllableCharCandidates.isEmpty()) {
            val hasPhraseSourceCandidates = abbreviationCandidates.isNotEmpty() ||
                customPhrases.isNotEmpty() ||
                autoLearnedPhrases.isNotEmpty() ||
                neuralSuggestions.isNotEmpty() ||
                partialMatchPhrases.isNotEmpty() ||
                dictPartialPhrases.isNotEmpty() ||
                abbreviatedPinyinPhrases.isNotEmpty()

            if (hasPhraseSourceCandidates) {
                // Build candidates sorted by frequency for this fallback case
                val userFreqMap = if (isMemoryEnabled()) userMemory.getFrequencyMap(bufferWithoutSep) else emptyMap()
                val fallbackCandidates = mutableListOf<Pair<String, Int>>()
                val addedToFallback = mutableSetOf<String>()

                // Add all phrase sources with appropriate boosts
                for (phrase in customPhrases) {
                    val freq = userFreqMap[phrase] ?: 0
                    fallbackCandidates.add(phrase to (freq + 2))
                    addedToFallback.add(phrase)
                }
                for (phrase in abbreviationCandidates) {
                    if (phrase !in addedToFallback) {
                        val freq = userFreqMap[phrase] ?: 0
                        fallbackCandidates.add(phrase to (freq + if (freq == 0) 2 else 0))
                        addedToFallback.add(phrase)
                    }
                }
                for (phrase in autoLearnedPhrases) {
                    if (phrase !in addedToFallback) {
                        val freq = userFreqMap[phrase] ?: 0
                        fallbackCandidates.add(phrase to (freq + if (freq == 0) 2 else 0))
                        addedToFallback.add(phrase)
                    }
                }
                for (phrase in partialMatchPhrases) {
                    if (phrase !in addedToFallback) {
                        val freq = userFreqMap[phrase] ?: 0
                        fallbackCandidates.add(phrase to (freq + if (freq == 0) 2 else 0))
                        addedToFallback.add(phrase)
                    }
                }
                for (phrase in dictPartialPhrases) {
                    if (phrase !in addedToFallback) {
                        val freq = userFreqMap[phrase] ?: 0
                        fallbackCandidates.add(phrase to (freq + if (freq == 0) 2 else 0))
                        addedToFallback.add(phrase)
                    }
                }
                // Add abbreviated pinyin phrases (e.g., "shrfa" -> "输入法")
                for (phrase in abbreviatedPinyinPhrases) {
                    if (phrase !in addedToFallback) {
                        val freq = userFreqMap[phrase] ?: 0
                        fallbackCandidates.add(phrase to (freq + if (freq == 0) 2 else 0))
                        addedToFallback.add(phrase)
                    }
                }
                for (neuralSuggestion in neuralSuggestions) {
                    if (neuralSuggestion !in addedToFallback) {
                        val freq = userFreqMap[neuralSuggestion] ?: 0
                        fallbackCandidates.add(neuralSuggestion to (freq + if (freq == 0) 1 else 0))
                        addedToFallback.add(neuralSuggestion)
                    }
                }

                // Sort by frequency and set as candidates
                allCandidates = fallbackCandidates.sortedByDescending { it.second }.map { it.first }
                phraseCandidateCount = allCandidates.size
                phraseCandidateSet = allCandidates.toSet()  // All are phrases
                matchedPinyin = bufferWithoutSep
                return
            }
            // No candidates at all - fall back to single segment mode
            handleSingleSegmentFallback(segments)
            return
        }

        // Collect phrase candidates and single-character candidates, then sort together by frequency
        val phraseCandidatesRaw = mutableListOf<String>()

        if (segments.size > 1) {
            // Generate combined phrases by taking top candidates from each segment
            val combinedPhrases = generateCombinedPhrases(segments)
            phraseCandidatesRaw.addAll(combinedPhrases)
            // Calculate the pinyin that will be consumed for phrase candidates
            matchedPinyin = segments.joinToString("") { it.pinyin }
        } else {
            // Single segment - check for dictionary phrase first
            val dictPhraseCandidates = PinyinDictionary.getPhraseCandidates(bufferWithoutSep)
            phraseCandidatesRaw.addAll(dictPhraseCandidates)
            // Include auto-learned phrases and neural suggestions in the check - if any phrase matches, consume full buffer
            matchedPinyin = if (dictPhraseCandidates.isNotEmpty() || customPhrases.isNotEmpty() || autoLearnedPhrases.isNotEmpty() || neuralSuggestions.isNotEmpty()) bufferWithoutSep else actualFirstSyllable
        }

        // Now combine ALL candidate sources (custom, abbreviation, auto-learned, dictionary, partial, neural)
        // and single characters into a single frequency-sorted list.
        // This ensures the most-used candidates appear first, regardless of source.
        val seenInCombined = mutableSetOf<String>()

        // Sort phrases by frequency first (user memory + dictionary)
        val sortedPhrases = sortByFrequencyIfEnabled(bufferWithoutSep, phraseCandidatesRaw)

        // Get user frequency for accurate merging
        val userFreqMap = if (isMemoryEnabled()) userMemory.getFrequencyMap(bufferWithoutSep) else emptyMap()

        // Custom dictionary phrases get +2 frequency boost (need 3 uses of normal phrase to surpass)
        val CUSTOM_PHRASE_FREQUENCY_BOOST = 2
        // Auto-learned phrases get +2 boost when frequency is 0 (same as dictionary phrases)
        val AUTO_LEARNED_PHRASE_BOOST = 2
        // Abbreviation candidates get +2 boost when frequency is 0
        val ABBREVIATION_PHRASE_BOOST = 2
        // Partial match phrases get +2 boost when frequency is 0
        val PARTIAL_MATCH_PHRASE_BOOST = 2
        // Abbreviated pinyin matches get +2 boost when frequency is 0
        val ABBREVIATED_PINYIN_PHRASE_BOOST = 2

        // Merge ALL candidates (abbreviation, custom, auto-learned, dictionary phrases, partial matches, abbreviated pinyin, and single chars)
        // sorted by effective user frequency. This ensures fair competition based on actual usage.
        // Phrases get +2 boost when frequency is 0
        // Custom phrases get +2 boost always (on top of any user frequency)
        val allCandidatesWithFreq = mutableListOf<Pair<String, Int>>()
        val addedPhrases = mutableSetOf<String>()

        // Add custom phrases first (they'll be sorted by frequency with +2 boost always)
        for (phrase in customPhrases) {
            val freq = userFreqMap[phrase] ?: 0
            // Custom phrases get +2 boost always
            allCandidatesWithFreq.add(phrase to (freq + CUSTOM_PHRASE_FREQUENCY_BOOST))
            addedPhrases.add(phrase)
        }

        // Add abbreviation candidates (with +2 boost when freq is 0)
        for (phrase in abbreviationCandidates) {
            if (phrase !in addedPhrases) {
                val freq = userFreqMap[phrase] ?: 0
                val boost = if (freq == 0) ABBREVIATION_PHRASE_BOOST else 0
                allCandidatesWithFreq.add(phrase to (freq + boost))
                addedPhrases.add(phrase)
            }
        }

        // Add auto-learned phrases (with +2 boost when freq is 0)
        for (phrase in autoLearnedPhrases) {
            if (phrase !in addedPhrases) {
                val freq = userFreqMap[phrase] ?: 0
                val boost = if (freq == 0) AUTO_LEARNED_PHRASE_BOOST else 0
                allCandidatesWithFreq.add(phrase to (freq + boost))
                addedPhrases.add(phrase)
            }
        }

        // Add dictionary phrases
        for (phrase in sortedPhrases) {
            if (phrase !in addedPhrases) {  // Skip if already added
                val freq = userFreqMap[phrase] ?: 0
                val boost = if (freq == 0) 2 else 0
                allCandidatesWithFreq.add(phrase to (freq + boost))
                addedPhrases.add(phrase)
            }
        }

        // Add partial match phrases (with +2 boost when freq is 0)
        for (phrase in partialMatchPhrases) {
            if (phrase !in addedPhrases) {
                val freq = userFreqMap[phrase] ?: 0
                val boost = if (freq == 0) PARTIAL_MATCH_PHRASE_BOOST else 0
                allCandidatesWithFreq.add(phrase to (freq + boost))
                addedPhrases.add(phrase)
            }
        }

        // Add dict partial phrases (with +2 boost when freq is 0)
        for (phrase in dictPartialPhrases) {
            if (phrase !in addedPhrases) {
                val freq = userFreqMap[phrase] ?: 0
                val boost = if (freq == 0) PARTIAL_MATCH_PHRASE_BOOST else 0
                allCandidatesWithFreq.add(phrase to (freq + boost))
                addedPhrases.add(phrase)
            }
        }

        // Add abbreviated pinyin phrases (e.g., "shrfa" -> "输入法") with +2 boost when freq is 0
        for (phrase in abbreviatedPinyinPhrases) {
            if (phrase !in addedPhrases) {
                val freq = userFreqMap[phrase] ?: 0
                val boost = if (freq == 0) ABBREVIATED_PINYIN_PHRASE_BOOST else 0
                allCandidatesWithFreq.add(phrase to (freq + boost))
                addedPhrases.add(phrase)
            }
        }

        // Add neural suggestions (with priority boost or +1 boost when freq is 0)
        // Neural suggestions get lower boost because they're model predictions, not user preferences
        // When priority is enabled, use a high boost to put neural prediction first
        for ((index, neuralSuggestion) in neuralSuggestions.withIndex()) {
            val freq = userFreqMap[neuralSuggestion] ?: 0
            // First neural suggestion gets priority boost, others get decreasing priority
            val boost = if (neuralPinyinPriority) (1000 - index) else if (freq == 0) 1 else 0

            if (neuralSuggestion !in addedPhrases) {
                // New suggestion - add with boost
                allCandidatesWithFreq.add(neuralSuggestion to (freq + boost))
                addedPhrases.add(neuralSuggestion)
            } else if (neuralPinyinPriority) {
                // Already added but priority is enabled - update the score to ensure neural gets priority
                // Find and update the existing entry with the higher neural priority boost
                val existingIndex = allCandidatesWithFreq.indexOfFirst { it.first == neuralSuggestion }
                if (existingIndex >= 0) {
                    val existingScore = allCandidatesWithFreq[existingIndex].second
                    val newScore = freq + boost
                    if (newScore > existingScore) {
                        allCandidatesWithFreq[existingIndex] = neuralSuggestion to newScore
                    }
                }
            }
        }

        // Add single-character candidates (no boost - raw frequency)
        for (char in firstSyllableCharCandidates) {
            val freq = userFreqMap[char] ?: 0
            allCandidatesWithFreq.add(char to freq)
        }

        // Sort by effective frequency (descending)
        val mergedSorted = allCandidatesWithFreq.sortedByDescending { it.second }.map { it.first }

        // Add to result
        var phraseCountFromSorted = 0
        var foundFirstSingleChar = false
        for (candidate in mergedSorted) {
            if (candidate !in seenInCombined) {
                resultCandidates.add(candidate)
                seenInCombined.add(candidate)
                if (candidate.length > 1 && !foundFirstSingleChar) {
                    phraseCountFromSorted++
                } else if (candidate.length == 1) {
                    foundFirstSingleChar = true
                }
            }
        }

        // Build the set of phrase candidates for accurate phrase detection in selectCandidate
        val phraseSet = mutableSetOf<String>()
        phraseSet.addAll(phraseCandidatesRaw)
        phraseSet.addAll(abbreviationCandidates)
        phraseSet.addAll(customPhrases)
        phraseSet.addAll(autoLearnedPhrases)
        phraseSet.addAll(partialMatchPhrases)  // Include user-learned partial matches
        phraseSet.addAll(dictPartialPhrases)   // Include dictionary partial matches
        // Include neural pinyin suggestions as phrases (consume entire buffer)
        phraseSet.addAll(neuralSuggestions)
        // Include abbreviated pinyin phrases (e.g., "shrfa" -> "输入法")
        phraseSet.addAll(abbreviatedPinyinPhrases)
        phraseCandidateSet = phraseSet

        // phraseCandidateCount for compatibility (used in some places)
        phraseCandidateCount = phraseSet.size

        allCandidates = resultCandidates
    }

    /**
     * Generates combined Chinese phrases from multiple segments.
     * Takes top candidates from each segment and combines them.
     */
    private fun generateCombinedPhrases(segments: List<ParsedSegment>, maxPerSegment: Int = 3, maxTotal: Int = 9): List<String> {
        if (segments.isEmpty()) return emptyList()
        if (segments.size == 1) return segments[0].candidates.take(maxTotal)

        // Take top candidates from each segment
        val candidateLists = segments.map { it.candidates.take(maxPerSegment) }

        // Generate cartesian product (limited)
        val combined = mutableListOf<String>()
        generateCartesianProduct(candidateLists, 0, "", combined, maxTotal)

        return combined
    }

    /**
     * Recursively generates cartesian product of candidate lists.
     */
    private fun generateCartesianProduct(
        lists: List<List<String>>,
        index: Int,
        current: String,
        result: MutableList<String>,
        maxResults: Int
    ) {
        if (result.size >= maxResults) return

        if (index == lists.size) {
            if (current.isNotEmpty()) {
                result.add(current)
            }
            return
        }

        val currentList = lists[index]
        if (currentList.isEmpty()) {
            // Skip empty segment
            generateCartesianProduct(lists, index + 1, current, result, maxResults)
        } else {
            for (candidate in currentList) {
                if (result.size >= maxResults) break
                generateCartesianProduct(lists, index + 1, current + candidate, result, maxResults)
            }
        }
    }

    /**
     * Handles input that couldn't be fully parsed.
     * Falls back to abbreviation matching, custom dictionary, then prefix matching.
     */
    private fun handleUnparsableInput(bufferStr: String) {
        val cleanBuffer = bufferStr.replace(SEPARATOR.toString(), "")

        val resultCandidates = mutableListOf<String>()

        // Get neural pinyin suggestions (will be added to frequency-based sorting below)
        val neuralSuggestions = getLongSentenceSuggestions(cleanBuffer)

        val abbreviationCandidates = if (isMemoryEnabled() && isAbbreviationInputEnabled()) userMemory.getAbbreviationCandidates(cleanBuffer) else emptyList()

        val customPhrases = customDictionary.getPinyinPhrases(cleanBuffer)
        val customPhraseSet = customPhrases.toSet()  // For quick lookup

        // Skip if input is abbreviation-style and abbreviation input is disabled
        val skipAutoLearnedForAbbrev = isAbbreviationInput(cleanBuffer) && !isAbbreviationInputEnabled()
        val autoLearnedPhrases = if (isAutoPhraseMemoryEnabled() && !skipAutoLearnedForAbbrev) autoPhraseMemory.getLearnedPhrases(cleanBuffer) else emptyList()

        // Get partial pinyin matches for abbreviated input (e.g., "shrfa" -> "输入法")
        // This works even without prior selections - allows typing abbreviated pinyin directly
        // Includes: dictionary phrases, learned phrases, and custom dictionary phrases
        // All sources are merged and sorted by dictionary frequency (highest frequency first)
        val partialPinyinMatchingEnabled = SettingsManager.isPartialPinyinMatchingEnabled(context)
        val abbreviatedPinyinPhrases = if (partialPinyinMatchingEnabled && cleanBuffer.length >= 2) {
            // Get all matches with their frequency scores
            val dictMatches = PinyinDictionary.getPartialPinyinMatches(cleanBuffer, maxResults = 20)
            val learnedAbbrPhrases = if (isAutoPhraseMemoryEnabled()) autoPhraseMemory.getLearnedPhrasesForAbbreviatedPinyin(cleanBuffer) else emptyList()
            val customAbbrPhrases = customDictionary.getPinyinPhrasesForAbbreviatedPinyin(cleanBuffer)

            // Create a map of phrase to frequency score (lower = higher frequency)
            val phraseScores = mutableMapOf<String, Int>()

            // Add dictionary matches with their frequency scores
            for (match in dictMatches) {
                phraseScores[match.phrase] = match.frequencyScore
            }

            // Custom phrases get highest priority (score 0)
            for (phrase in customAbbrPhrases) {
                if (phrase !in phraseScores || phraseScores[phrase]!! > 0) {
                    phraseScores[phrase] = 0
                }
            }

            // Learned phrases get high priority (score 1-10 based on position)
            for ((index, phrase) in learnedAbbrPhrases.withIndex()) {
                if (phrase !in phraseScores || phraseScores[phrase]!! > index + 1) {
                    phraseScores[phrase] = index + 1
                }
            }

            // Sort by frequency score (lower = higher priority) and return top 9
            phraseScores.entries
                .sortedBy { it.value }
                .take(9)
                .map { it.key }
        } else {
            emptyList()
        }

        // Check partial/prefix matching phrases
        // Two modes: with session context or direct abbreviated input
        val partialMatchPhrases: List<String>
        val dictPartialPhrases: List<String>
        val partialMatchingEnabled = SettingsManager.isPartialPinyinMatchingEnabled(context)
        if (partialMatchingEnabled) {
            if (sessionSelections.isNotEmpty()) {
                // Session mode: use session context
                val sessionPinyin = sessionSelections.joinToString("") { it.first }
                val fullPinyin = sessionPinyin + cleanBuffer
                val committedChars = sessionSelections.joinToString("") { it.second }

                val rawPartialPhrases = if (isAutoPhraseMemoryEnabled()) autoPhraseMemory.getLearnedPhrasesWithPrefix(fullPinyin) else emptyList()
                partialMatchPhrases = rawPartialPhrases.mapNotNull { phrase ->
                    if (phrase.startsWith(committedChars) && phrase.length > committedChars.length) {
                        phrase.substring(committedChars.length)
                    } else {
                        null
                    }
                }

                val rawDictPhrases = PinyinDictionary.getPhraseCandidatesWithPrefix(fullPinyin)
                dictPartialPhrases = rawDictPhrases.mapNotNull { phrase ->
                    if (phrase.startsWith(committedChars) && phrase.length > committedChars.length) {
                        phrase.substring(committedChars.length)
                    } else {
                        null
                    }
                }
            } else {
                // Direct mode: no session, use prefix matching on current buffer only
                partialMatchPhrases = if (isAutoPhraseMemoryEnabled() && cleanBuffer.length >= 2) {
                    autoPhraseMemory.getLearnedPhrasesWithPrefix(cleanBuffer)
                } else {
                    emptyList()
                }
                dictPartialPhrases = if (cleanBuffer.length >= 2) {
                    PinyinDictionary.getPhraseCandidatesWithPrefix(cleanBuffer)
                } else {
                    emptyList()
                }
            }
        } else {
            partialMatchPhrases = emptyList()
            dictPartialPhrases = emptyList()
        }

        val prefixCandidates = PinyinDictionary.getCandidatesForPrefix(cleanBuffer)

        if (prefixCandidates.isNotEmpty() || customPhrases.isNotEmpty() || autoLearnedPhrases.isNotEmpty() || abbreviationCandidates.isNotEmpty() || neuralSuggestions.isNotEmpty() || partialMatchPhrases.isNotEmpty() || dictPartialPhrases.isNotEmpty() || abbreviatedPinyinPhrases.isNotEmpty()) {
            // Get user frequency for accurate merging
            val userFreqMap = if (isMemoryEnabled()) userMemory.getFrequencyMap(cleanBuffer) else emptyMap()

            // Custom dictionary phrases get +2 frequency boost (need 3 uses of normal phrase to surpass)
            val CUSTOM_PHRASE_FREQUENCY_BOOST = 2
            // Auto-learned phrases get +2 boost when frequency is 0
            val AUTO_LEARNED_PHRASE_BOOST = 2
            // Abbreviation candidates get +2 boost when frequency is 0
            val ABBREVIATION_PHRASE_BOOST = 2
            // Partial match phrases get +2 boost when frequency is 0
            val PARTIAL_MATCH_PHRASE_BOOST = 2
            // Abbreviated pinyin matches get +2 boost when frequency is 0
            val ABBREVIATED_PINYIN_PHRASE_BOOST = 2

            // Merge ALL candidates sorted by effective user frequency
            val allCandidatesWithFreq = mutableListOf<Pair<String, Int>>()
            val addedToMerge = mutableSetOf<String>()

            // Add custom phrases (they'll be sorted by frequency with +2 boost always)
            for (phrase in customPhrases) {
                val freq = userFreqMap[phrase] ?: 0
                allCandidatesWithFreq.add(phrase to (freq + CUSTOM_PHRASE_FREQUENCY_BOOST))
                addedToMerge.add(phrase)
            }

            // Add abbreviation candidates (with +2 boost when freq is 0)
            for (phrase in abbreviationCandidates) {
                if (phrase !in addedToMerge) {
                    val freq = userFreqMap[phrase] ?: 0
                    val boost = if (freq == 0) ABBREVIATION_PHRASE_BOOST else 0
                    allCandidatesWithFreq.add(phrase to (freq + boost))
                    addedToMerge.add(phrase)
                }
            }

            // Add auto-learned phrases (with +2 boost when freq is 0)
            for (phrase in autoLearnedPhrases) {
                if (phrase !in addedToMerge) {
                    val freq = userFreqMap[phrase] ?: 0
                    val boost = if (freq == 0) AUTO_LEARNED_PHRASE_BOOST else 0
                    allCandidatesWithFreq.add(phrase to (freq + boost))
                    addedToMerge.add(phrase)
                }
            }

            // Add partial match phrases (with +2 boost when freq is 0)
            for (phrase in partialMatchPhrases) {
                if (phrase !in addedToMerge) {
                    val freq = userFreqMap[phrase] ?: 0
                    val boost = if (freq == 0) PARTIAL_MATCH_PHRASE_BOOST else 0
                    allCandidatesWithFreq.add(phrase to (freq + boost))
                    addedToMerge.add(phrase)
                }
            }

            // Add dict partial phrases (with +2 boost when freq is 0)
            for (phrase in dictPartialPhrases) {
                if (phrase !in addedToMerge) {
                    val freq = userFreqMap[phrase] ?: 0
                    val boost = if (freq == 0) PARTIAL_MATCH_PHRASE_BOOST else 0
                    allCandidatesWithFreq.add(phrase to (freq + boost))
                    addedToMerge.add(phrase)
                }
            }

            // Add abbreviated pinyin phrases (e.g., "shrfa" -> "输入法") with +2 boost when freq is 0
            for (phrase in abbreviatedPinyinPhrases) {
                if (phrase !in addedToMerge) {
                    val freq = userFreqMap[phrase] ?: 0
                    val boost = if (freq == 0) ABBREVIATED_PINYIN_PHRASE_BOOST else 0
                    allCandidatesWithFreq.add(phrase to (freq + boost))
                    addedToMerge.add(phrase)
                }
            }

            // Add prefix candidates (single characters from prefix matching - no boost)
            for (candidate in prefixCandidates) {
                if (candidate !in addedToMerge) {
                    val freq = userFreqMap[candidate] ?: 0
                    allCandidatesWithFreq.add(candidate to freq)
                    addedToMerge.add(candidate)
                }
            }

            // Add neural suggestions (with priority boost or +1 boost when freq is 0)
            // When priority is enabled, use a high boost to put neural prediction first
            for ((index, neuralSuggestion) in neuralSuggestions.withIndex()) {
                val freq = userFreqMap[neuralSuggestion] ?: 0
                // First neural suggestion gets priority boost, others get decreasing priority
                val boost = if (neuralPinyinPriority) (1000 - index) else if (freq == 0) 1 else 0

                if (neuralSuggestion !in addedToMerge) {
                    // New suggestion - add with boost
                    allCandidatesWithFreq.add(neuralSuggestion to (freq + boost))
                    addedToMerge.add(neuralSuggestion)
                } else if (neuralPinyinPriority) {
                    // Already added but priority is enabled - update the score to ensure neural gets priority
                    // Find and update the existing entry with the higher neural priority boost
                    val existingIndex = allCandidatesWithFreq.indexOfFirst { it.first == neuralSuggestion }
                    if (existingIndex >= 0) {
                        val existingScore = allCandidatesWithFreq[existingIndex].second
                        val newScore = freq + boost
                        if (newScore > existingScore) {
                            allCandidatesWithFreq[existingIndex] = neuralSuggestion to newScore
                        }
                    }
                }
            }

            // Sort by effective frequency (descending)
            val mergedSorted = allCandidatesWithFreq.sortedByDescending { it.second }.map { it.first }

            // Add to result
            for (candidate in mergedSorted) {
                resultCandidates.add(candidate)
            }

            allCandidates = resultCandidates
            // If we have phrase candidates (abbreviation, custom, auto-learned, neural, abbreviated pinyin), use full buffer for phrase matching
            // Otherwise fall back to first syllable for single-character selection
            val hasPhraseCandidates = abbreviationCandidates.isNotEmpty() || customPhrases.isNotEmpty() || autoLearnedPhrases.isNotEmpty() || neuralSuggestions.isNotEmpty() || abbreviatedPinyinPhrases.isNotEmpty()
            matchedPinyin = if (hasPhraseCandidates) cleanBuffer else (PinyinDictionary.getFirstSyllableForPrefix(cleanBuffer) ?: cleanBuffer)
            firstSyllable = PinyinDictionary.getFirstSyllableForPrefix(cleanBuffer) ?: cleanBuffer
            // Build phrase set for accurate detection
            val phraseSet = mutableSetOf<String>()
            phraseSet.addAll(abbreviationCandidates)
            phraseSet.addAll(customPhrases)
            phraseSet.addAll(autoLearnedPhrases)
            phraseSet.addAll(partialMatchPhrases)
            phraseSet.addAll(dictPartialPhrases)
            // Include neural pinyin suggestions as phrases
            phraseSet.addAll(neuralSuggestions)
            // Include abbreviated pinyin phrases (e.g., "shrfa" -> "输入法")
            phraseSet.addAll(abbreviatedPinyinPhrases)
            phraseCandidateSet = phraseSet
            phraseCandidateCount = phraseSet.size
        } else if (resultCandidates.isNotEmpty()) {
            // Only neural/abbreviation/auto-learned/partial-match phrases available (no custom or prefix)
            allCandidates = resultCandidates
            matchedPinyin = cleanBuffer
            firstSyllable = cleanBuffer
            // Include neural suggestions in phrase set
            val phraseSet = resultCandidates.toMutableSet()
            phraseSet.addAll(neuralSuggestions)
            phraseCandidateSet = phraseSet
            phraseCandidateCount = phraseSet.size
        } else {
            allCandidates = emptyList()
            matchedPinyin = ""
            phraseCandidateSet = emptySet()
            firstSyllable = ""
            phraseCandidateCount = 0
        }
    }

    /**
     * Falls back to showing candidates from the first segment only.
     */
    private fun handleSingleSegmentFallback(segments: List<ParsedSegment>) {
        val firstSegment = segments.firstOrNull()
        if (firstSegment != null && firstSegment.candidates.isNotEmpty()) {
            allCandidates = firstSegment.candidates
            matchedPinyin = firstSegment.pinyin
            firstSyllable = firstSegment.pinyin
            phraseCandidateCount = 0
            phraseCandidateSet = emptySet()  // Single-char only, no phrases
        } else {
            allCandidates = emptyList()
            matchedPinyin = ""
            firstSyllable = ""
            phraseCandidateCount = 0
            phraseCandidateSet = emptySet()
        }
    }

    /**
     * Gets current state snapshot for UI updates.
     */
    fun getSnapshot(): Snapshot {
        val currentPageCandidates = getCurrentPageCandidates()
        val totalPages = getTotalPages()

        // Convert candidates to traditional Chinese if setting is enabled
        val useTraditional = SettingsManager.isTraditionalChineseMode(context)
        val convertedCandidates = ChineseCharacterConverter.convertCandidates(currentPageCandidates, useTraditional)

        return Snapshot(
            isActive = isPinyinModeActive,
            buffer = buffer.toString(),
            candidates = convertedCandidates,
            hasCandidates = allCandidates.isNotEmpty(),
            currentPage = currentPage,
            totalPages = totalPages,
            hasNextPage = currentPage < totalPages - 1,
            hasPrevPage = currentPage > 0,
            isNextWordPrediction = isShowingNextWordPredictions
        )
    }

    /**
     * Returns whether we're currently showing next-word predictions.
     */
    fun isShowingNextWordPredictions(): Boolean = isShowingNextWordPredictions

    /**
     * Clears next-word prediction state and all candidates.
     */
    fun clearNextWordPredictions() {
        nextWordPredictor.clearState()
        isShowingNextWordPredictions = false
        allCandidates = emptyList()
        currentPage = 0
    }

    /**
     * Called when user inputs punctuation (period, comma, etc.).
     * Clears next-word predictions since punctuation ends the phrase context.
     */
    fun onPunctuationInput() {
        // Finalize session before punctuation (phrase boundary)
        finalizeSession()

        if (isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            allCandidates = emptyList()
            currentPage = 0
            Log.d(TAG, "Cleared next-word predictions due to punctuation input")
        }
        // Also clear the predictor state so next sentence starts fresh
        nextWordPredictor.clearState()
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
     * Gets all candidates (not just current page).
     * Used for saving state when Alt is pressed in Juying mode.
     */
    fun getAllCandidates(): List<String> = allCandidates.toList()

    /**
     * Gets the current page number.
     * Used for saving state when Alt is pressed in Juying mode.
     */
    fun getCurrentPage(): Int = currentPage

    /**
     * Gets candidates for the current page (public access).
     * In dynamic mode, uses variable page sizes based on candidate lengths.
     */
    fun getCurrentPageCandidates(): List<String> {
        val startIndex = getPageStartIndex(currentPage)
        val pageLimit = getPageLimit(currentPage)
        val endIndex = minOf(startIndex + pageLimit, allCandidates.size)
        return if (startIndex < allCandidates.size) {
            allCandidates.subList(startIndex, endIndex)
        } else {
            emptyList()
        }
    }

    /**
     * Restores candidates for next page navigation.
     * Used when double-clicking Alt in Juying mode to navigate to next page.
     */
    fun restoreCandidatesForNextPage(candidates: List<String>, page: Int) {
        allCandidates = candidates
        currentPage = page
        isShowingNextWordPredictions = true  // Mark as showing predictions for proper state
    }

    /**
     * Full restoration for Alt selection - restores candidates and syllable parsing state.
     * This ensures selectCandidate() knows which syllables to consume.
     */
    fun restoreForAltSelection(
        candidates: List<String>,
        page: Int,
        savedFirstSyllable: String,
        savedMatchedPinyin: String,
        savedPhraseCandidateCount: Int,
        savedPhraseCandidateSet: Set<String> = emptySet()
    ) {
        allCandidates = candidates
        currentPage = page
        firstSyllable = savedFirstSyllable
        matchedPinyin = savedMatchedPinyin
        phraseCandidateCount = savedPhraseCandidateCount
        phraseCandidateSet = savedPhraseCandidateSet
        isShowingNextWordPredictions = false  // Not showing predictions, showing actual candidates
    }

    /**
     * Gets the phrase candidate set for saving before Alt selection.
     */
    fun getPhraseCandidateSet(): Set<String> = phraseCandidateSet

    /**
     * Gets the first syllable value for saving before Alt selection.
     */
    fun getFirstSyllable(): String = firstSyllable

    /**
     * Gets the matched pinyin value for saving before Alt selection.
     */
    fun getMatchedPinyin(): String = matchedPinyin

    /**
     * Gets the phrase candidate count for saving before Alt selection.
     */
    fun getPhraseCandidateCount(): Int = phraseCandidateCount

    /**
     * Restores the buffer content.
     * Used when double-clicking Alt in Juying mode to restore composing text.
     */
    fun restoreBuffer(content: String) {
        buffer.clear()
        buffer.append(content)
    }

    /**
     * Checks if there is a previous page of candidates.
     */
    fun hasPrevPage(): Boolean = currentPage > 0

    /**
     * Checks if the next double quote should be an opening quote.
     */
    fun isNextDoubleQuoteOpening(): Boolean = nextDoubleQuoteIsOpening

    /**
     * Toggles the double quote state between opening and closing.
     */
    fun toggleDoubleQuoteState() {
        nextDoubleQuoteIsOpening = !nextDoubleQuoteIsOpening
    }

    /**
     * Checks if the next single quote should be an opening quote.
     */
    fun isNextSingleQuoteOpening(): Boolean = nextSingleQuoteIsOpening

    /**
     * Toggles the single quote state between opening and closing.
     */
    fun toggleSingleQuoteState() {
        nextSingleQuoteIsOpening = !nextSingleQuoteIsOpening
    }

    /**
     * Resets quote states to opening (called when exiting Pinyin mode or starting fresh).
     */
    fun resetQuoteStates() {
        nextDoubleQuoteIsOpening = true
        nextSingleQuoteIsOpening = true
    }

    /**
     * Returns whether Chinese punctuation mode is active.
     */
    fun isChinesePunctuationMode(): Boolean = useChinesePunctuation

    /**
     * Sets whether to use Chinese punctuation.
     */
    fun setChinesePunctuationMode(enabled: Boolean) {
        useChinesePunctuation = enabled
    }

    /**
     * Toggles between Chinese and English punctuation modes.
     */
    fun togglePunctuationMode() {
        useChinesePunctuation = !useChinesePunctuation
    }

    /**
     * Sets whether next word prediction is enabled.
     */
    fun setNextWordPredictionEnabled(enabled: Boolean) {
        nextWordPredictionEnabled = enabled
        if (!enabled) {
            // Clear any existing predictions when disabled
            if (isShowingNextWordPredictions) {
                isShowingNextWordPredictions = false
                allCandidates = emptyList()
                currentPage = 0
            }
        }
    }

    /**
     * Returns whether next word prediction is enabled.
     */
    fun isNextWordPredictionEnabled(): Boolean = nextWordPredictionEnabled

    /**
     * Sets whether fuzzy pinyin (模糊音) is enabled.
     */
    fun setFuzzyPinyinEnabled(enabled: Boolean) {
        fuzzyPinyinEnabled = enabled
        Log.d(TAG, "Fuzzy pinyin ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Returns whether fuzzy pinyin is enabled.
     */
    fun isFuzzyPinyinEnabled(): Boolean = fuzzyPinyinEnabled

    /**
     * Generates fuzzy pinyin variants for a given syllable.
     * Applies substitution rules: z↔zh, c↔ch, s↔sh, l↔n, en↔eng, in↔ing
     * @param syllable The original pinyin syllable
     * @return List of variant syllables (including the original)
     */
    private fun getFuzzyVariants(syllable: String): List<String> {
        if (!fuzzyPinyinEnabled || syllable.isEmpty()) {
            return listOf(syllable)
        }

        val variants = mutableSetOf(syllable)

        // Try initial consonant substitutions
        for ((from, to) in fuzzyInitials) {
            if (syllable.startsWith(from)) {
                val variant = to + syllable.substring(from.length)
                variants.add(variant)
            }
        }

        // Try final substitutions (on original and initial-substituted variants)
        val currentVariants = variants.toList()
        for (variant in currentVariants) {
            for ((from, to) in fuzzyFinals) {
                if (variant.endsWith(from)) {
                    val newVariant = variant.substring(0, variant.length - from.length) + to
                    variants.add(newVariant)
                }
            }
        }

        return variants.toList()
    }

    /**
     * Gets candidates for a syllable including fuzzy variants.
     * @param syllable The original pinyin syllable
     * @return Combined list of candidates from all matching variants
     */
    private fun getCandidatesWithFuzzy(syllable: String): List<String> {
        val variants = getFuzzyVariants(syllable)
        val allCandidates = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (variant in variants) {
            val candidates = PinyinDictionary.getCandidates(variant)
            for (candidate in candidates) {
                if (candidate !in seen) {
                    seen.add(candidate)
                    allCandidates.add(candidate)
                }
            }
        }

        return allCandidates
    }

    /**
     * Finds the longest syllable match including fuzzy variants.
     * @param input The input string to match
     * @return The longest matching syllable, or null if none found
     */
    private fun findLongestSyllableWithFuzzy(input: String): String? {
        // When fuzzy is enabled, try fuzzy matching FIRST for longer prefixes
        // This prevents issues like "zang" being split as "zan" + "g" instead of matching "zhang" via fuzzy
        if (fuzzyPinyinEnabled) {
            // Try fuzzy matching for progressively shorter prefixes (longest first)
            for (len in minOf(6, input.length) downTo 1) {
                val prefix = input.substring(0, len)

                // First check if this prefix has an exact match
                if (PinyinDictionary.contains(prefix)) {
                    return prefix
                }

                // Then try fuzzy variants
                val variants = getFuzzyVariants(prefix)
                for (variant in variants) {
                    if (variant != prefix && PinyinDictionary.getCandidates(variant).isNotEmpty()) {
                        Log.d(TAG, "Fuzzy match: '$prefix' → '$variant'")
                        return prefix // Return original prefix, candidates will use variant
                    }
                }
            }
            return null
        }

        // When fuzzy is disabled, just use exact match
        return PinyinDictionary.findLongestSyllable(input)
    }

    /**
     * Sets whether neural pinyin is enabled.
     */
    fun setNeuralPinyinEnabled(enabled: Boolean) {
        neuralPinyinEnabled = enabled
        if (enabled && neuralPinyinRecognizer == null) {
            neuralPinyinRecognizer = NeuralPinyinRecognizer.getInstance(context)
        }
    }

    /**
     * Returns whether neural pinyin is enabled.
     */
    fun isNeuralPinyinEnabled(): Boolean = neuralPinyinEnabled

    /**
     * Sets the minimum number of letters required for neural pinyin.
     */
    fun setNeuralPinyinMinLetters(minLetters: Int) {
        neuralPinyinMinLetters = minLetters
    }

    /**
     * Returns the minimum number of letters for neural pinyin.
     */
    fun getNeuralPinyinMinLetters(): Int = neuralPinyinMinLetters

    /**
     * Sets whether neural pinyin prediction should be shown as the top suggestion.
     */
    fun setNeuralPinyinPriority(priority: Boolean) {
        neuralPinyinPriority = priority
    }

    /**
     * Sets the number of neural pinyin predictions to show.
     */
    fun setNeuralPinyinCount(count: Int) {
        neuralPinyinCount = count
    }

    /**
     * Gets pinyin suggestions using the neural encoder-decoder model.
     * Returns a list of suggestions (1 or 3 based on setting), or empty list if not available.
     */
    private fun getLongSentenceSuggestions(pinyinInput: String): List<String> {
        if (!neuralPinyinEnabled) return emptyList()
        if (pinyinInput.length < neuralPinyinMinLetters) return emptyList()

        // Use neural model for long sentence conversion
        val neuralResults = getNeuralPinyinSuggestions(pinyinInput)
        if (neuralResults.isNotEmpty()) {
            Log.d(TAG, "Neural pinyin suggestions: $neuralResults for '$pinyinInput'")
            return neuralResults
        }

        return emptyList()
    }

    /**
     * Gets neural pinyin suggestions using the encoder-decoder model.
     * Returns a list of suggestions (1 or 3 based on setting), or empty list if not available.
     */
    private fun getNeuralPinyinSuggestions(pinyinInput: String): List<String> {
        try {
            // Check if ONNX Runtime is available
            if (!NeuralPinyinRecognizer.isOnnxRuntimeAvailable()) {
                return emptyList()
            }

            // Get or create recognizer instance
            val recognizer = neuralPinyinRecognizer ?: run {
                neuralPinyinRecognizer = NeuralPinyinRecognizer.getInstance(context)
                neuralPinyinRecognizer
            }

            if (recognizer == null) return emptyList()

            // Check model status
            val status = recognizer.getModelStatus()
            when (status) {
                NeuralPinyinRecognizer.ModelStatus.READY -> {
                    // Model is ready, run inference with count parameter
                    return recognizer.convert(pinyinInput, neuralPinyinCount)
                }
                NeuralPinyinRecognizer.ModelStatus.AVAILABLE -> {
                    // Model extracted but not loaded - initialize it
                    recognizer.initModel()
                    return emptyList()
                }
                NeuralPinyinRecognizer.ModelStatus.ZIP_CONFIGURED -> {
                    // Zip configured but not extracted - extract it
                    recognizer.extractModel(
                        onComplete = { success, _ ->
                            if (success) {
                                recognizer.initModel()
                            }
                        }
                    )
                    return emptyList()
                }
                else -> {
                    // Not configured, extracting, or loading - skip
                    return emptyList()
                }
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory in neural pinyin: ${e.message}", e)
            return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error in neural pinyin: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * Parses concatenated pinyin into space-separated syllables.
     * E.g., "nihao" -> "ni hao", "woaini" -> "wo ai ni"
     */
    private fun parseToSpacedPinyin(input: String): String {
        val syllables = mutableListOf<String>()
        var remaining = input.lowercase()
        val maxSyllables = 20  // Limit to prevent excessive processing

        while (remaining.isNotEmpty() && syllables.size < maxSyllables) {
            val syllable = PinyinDictionary.findLongestSyllable(remaining)
            if (syllable != null) {
                syllables.add(syllable)
                remaining = remaining.substring(syllable.length)
            } else {
                // Unknown syllable - skip one character
                remaining = remaining.substring(1)
            }
        }

        return syllables.joinToString(" ")
    }

}
