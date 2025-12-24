package it.neuralrad.coolwulf.core

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import it.neuralrad.coolwulf.data.zhenma.ZhenmaDictionary
import it.neuralrad.coolwulf.data.zhenma.UserZhenmaMemory
import it.neuralrad.coolwulf.data.pinyin.ChineseCharacterConverter
import it.neuralrad.coolwulf.data.NextWordPredictor
import it.neuralrad.coolwulf.data.UserCustomDictionary
import it.neuralrad.coolwulf.SettingsManager

/**
 * Manages Zhenma (真码) input state and generates Chinese character candidates.
 * Handles the input buffer and provides methods for candidate selection.
 * Also provides next-word predictions after a character is committed.
 *
 * Zhenma is a shape-based Chinese input method similar to Wubi,
 * where characters are encoded based on their structural components.
 */
class ZhenmaInputController(
    private val context: Context
) {
    companion object {
        private const val TAG = "ZhenmaInputController"
        private const val MAX_BUFFER_LENGTH = 100 // Allow longer input for English words and sentences
        private const val DEFAULT_PAGE_SIZE = 9  // Number of candidates per page (normal mode)
        private const val JUYING_PAGE_SIZE = 5   // Number of candidates per page (Juying mode)
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
     * @param maxCandidatesNonJuying Maximum number of candidates per page in non-Juying mode
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
        return dynamicDisplayLimit
    }

    // Current zhenma input buffer
    private var buffer = StringBuilder()

    // All candidates for the buffer (full list from dictionary)
    private var allCandidates: List<String> = emptyList()

    // Whether Zhenma mode is currently active
    private var isZhenmaModeActive = false

    // Current page (0-indexed)
    private var currentPage: Int = 0

    // Whether we're showing next-word predictions
    private var isShowingNextWordPredictions: Boolean = false

    // Whether next word prediction feature is enabled
    private var nextWordPredictionEnabled: Boolean = true

    // Next-word predictor for suggesting words after commit
    private val nextWordPredictor: NextWordPredictor = NextWordPredictor.getInstance(context)

    // User memory for learning preferences (prioritize frequently selected characters)
    private val userMemory: UserZhenmaMemory = UserZhenmaMemory.getInstance(context)

    // User custom dictionary for user-defined shortcuts
    private val customDictionary: UserCustomDictionary = UserCustomDictionary.getInstance(context)

    // Store the Zhenma code used for the current selection (for memory recording)
    private var lastUsedZhenmaCode: String = ""

    // Track quote state for alternating between opening and closing Chinese quotes
    private var nextDoubleQuoteIsOpening: Boolean = true
    private var nextSingleQuoteIsOpening: Boolean = true

    // Whether to use Chinese punctuation (true) or English punctuation (false)
    private var useChinesePunctuation: Boolean = true

    data class Snapshot(
        val isActive: Boolean,
        val buffer: String,
        val candidates: List<String>,
        val hasCandidates: Boolean,
        val currentPage: Int = 0,
        val totalPages: Int = 1,
        val hasNextPage: Boolean = false,
        val hasPrevPage: Boolean = false,
        val isNextWordPrediction: Boolean = false
    )

    init {
        // Load dictionary if not already loaded
        if (!ZhenmaDictionary.isLoaded()) {
            ZhenmaDictionary.load(context)
        }
    }

    /**
     * Enables or disables Zhenma input mode.
     */
    fun setZhenmaMode(active: Boolean) {
        if (isZhenmaModeActive != active) {
            isZhenmaModeActive = active
            if (!active) {
                clearBuffer()
            }
            Log.d(TAG, "========== Zhenma mode: ${if (active) "ENABLED" else "DISABLED"} ==========")
        } else {
            Log.d(TAG, "Zhenma mode already ${if (active) "enabled" else "disabled"}")
        }
    }

    /**
     * Toggles Zhenma input mode on/off.
     */
    fun toggleZhenmaMode() {
        setZhenmaMode(!isZhenmaModeActive)
    }

    /**
     * Returns whether Zhenma mode is currently active.
     */
    fun isZhenmaMode(): Boolean = isZhenmaModeActive

    /**
     * Processes a letter key press in Zhenma mode.
     * Adds the letter to the buffer and updates candidates.
     * @param char The character to add (a-z)
     * @return true if the key was handled, false otherwise
     */
    fun handleLetterKey(char: Char): Boolean {
        if (!isZhenmaModeActive) {
            Log.d(TAG, "handleLetterKey: Zhenma mode not active")
            return false
        }

        // Only accept lowercase letters
        val lowerChar = char.lowercaseChar()
        if (!lowerChar.isLetter() || lowerChar < 'a' || lowerChar > 'z') {
            Log.d(TAG, "handleLetterKey: Invalid character '$char'")
            return false
        }

        // Clear next-word prediction state when user starts typing
        if (isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            nextWordPredictor.onUserStartedTyping()
        }

        // Check buffer length limit for typing English words
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
     * Handles backspace in Zhenma mode.
     * Removes the last character from the buffer, or clears next-word predictions if buffer is empty.
     * @return true if handled, false otherwise
     */
    fun handleBackspace(): Boolean {
        if (!isZhenmaModeActive) {
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
        if (!isZhenmaModeActive || index < 0 || index >= currentPageCandidates.size) {
            return null
        }

        val selected = currentPageCandidates[index]
        Log.d(TAG, "Selected candidate $index: '$selected'")

        // Record the selection in user memory for learning (only for Zhenma code-based selections)
        if (!isShowingNextWordPredictions && lastUsedZhenmaCode.isNotEmpty()) {
            recordSelectionIfEnabled(lastUsedZhenmaCode, selected)
        }

        // Record the committed word for next-word prediction learning
        nextWordPredictor.recordCommittedWord(selected)

        // Clear the buffer after selection
        buffer.clear()
        lastUsedZhenmaCode = ""

        // Show next-word predictions if available
        showNextWordPredictions()

        // Convert to traditional Chinese if setting is enabled
        val useTraditional = SettingsManager.isTraditionalChineseMode(context)
        return if (useTraditional) {
            ChineseCharacterConverter.toTraditional(selected)
        } else {
            selected
        }
    }

    /**
     * Shows next-word predictions if available and enabled.
     */
    private fun showNextWordPredictions() {
        if (nextWordPredictionEnabled && nextWordPredictor.isShowingPredictions()) {
            val nextWordSuggestions = nextWordPredictor.getSuggestions()
            if (nextWordSuggestions.isNotEmpty()) {
                isShowingNextWordPredictions = true
                allCandidates = nextWordSuggestions
                currentPage = 0
                Log.d(TAG, "Showing next-word predictions: $nextWordSuggestions")
                return
            }
        }
        isShowingNextWordPredictions = false
        updateCandidates()
    }

    /**
     * Calculates total number of pages.
     * Uses effective page size (dynamic display limit in Juying mode) for pagination.
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
        if (!isZhenmaModeActive || allCandidates.isEmpty()) {
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
     * Abbreviation matches appear first, then custom dictionary phrases, then sorted by user frequency.
     */
    private fun updateCandidates() {
        currentPage = 0  // Reset to first page when candidates change

        if (buffer.isEmpty()) {
            allCandidates = emptyList()
            lastUsedZhenmaCode = ""
            return
        }

        val bufferStr = buffer.toString()
        lastUsedZhenmaCode = bufferStr

        val resultCandidates = mutableListOf<String>()

        // Get abbreviation matches first (highest priority - learned from user input)
        val abbreviationCandidates = getAbbreviationCandidatesIfEnabled(bufferStr)
        if (abbreviationCandidates.isNotEmpty()) {
            resultCandidates.addAll(abbreviationCandidates)
            Log.d(TAG, "Abbreviation matches for '$bufferStr': $abbreviationCandidates")
        }

        // Get custom dictionary phrases second (second highest priority)
        val customPhrases = customDictionary.getZhenmaPhrases(bufferStr)
        if (customPhrases.isNotEmpty()) {
            for (phrase in customPhrases) {
                if (phrase !in resultCandidates) {
                    resultCandidates.add(phrase)
                }
            }
            Log.d(TAG, "Custom dictionary phrases for '$bufferStr': $customPhrases")
        }

        // Get candidates for the current code (both exact and prefix matches)
        val rawCandidates = ZhenmaDictionary.getCandidatesForPrefix(bufferStr, limit = 50)

        // Sort by user frequency (most frequently selected first)
        val sortedCandidates = sortByFrequencyIfEnabled(bufferStr, rawCandidates)

        // Add dictionary candidates (excluding duplicates)
        for (candidate in sortedCandidates) {
            if (candidate !in resultCandidates) {
                resultCandidates.add(candidate)
            }
        }

        allCandidates = resultCandidates

        Log.d(TAG, "Updated candidates for '$bufferStr': ${allCandidates.size} (${abbreviationCandidates.size} abbrev, ${customPhrases.size} custom, sorted by frequency)")
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
            isActive = isZhenmaModeActive,
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
     * Gets candidates for the current page.
     * Uses effective page size (dynamic display limit in Juying mode) so hidden
     * candidates are pushed to the next page.
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
        isShowingNextWordPredictions = true
    }

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
     * Resets quote states to opening (called when exiting Zhenma mode or starting fresh).
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
     * Returns whether the memory function is enabled.
     */
    private fun isMemoryEnabled(): Boolean {
        return SettingsManager.getMemoryFunctionEnabled(context)
    }

    /**
     * Records a selection in user memory if memory function is enabled.
     */
    private fun recordSelectionIfEnabled(code: String, selected: String) {
        if (isMemoryEnabled()) {
            userMemory.recordSelection(code, selected)
        }
    }

    /**
     * Sorts candidates by frequency if memory function is enabled, otherwise returns unsorted.
     */
    private fun sortByFrequencyIfEnabled(code: String, candidates: List<String>): List<String> {
        return if (isMemoryEnabled()) {
            userMemory.sortByFrequency(code, candidates)
        } else {
            candidates
        }
    }

    /**
     * Gets abbreviation candidates if memory function is enabled, otherwise returns empty list.
     */
    private fun getAbbreviationCandidatesIfEnabled(code: String): List<String> {
        return if (isMemoryEnabled()) {
            userMemory.getAbbreviationCandidates(code)
        } else {
            emptyList()
        }
    }
}
