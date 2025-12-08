package it.neuralrad.coolwulf.core

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import it.neuralrad.coolwulf.data.wubi.WubiDictionary
import it.neuralrad.coolwulf.data.wubi.UserWubiMemory
import it.neuralrad.coolwulf.data.wubi.WubiPhraseMemory
import it.neuralrad.coolwulf.data.pinyin.PinyinDictionary
import it.neuralrad.coolwulf.data.NextWordPredictor
import it.neuralrad.coolwulf.data.UserCustomDictionary
import it.neuralrad.coolwulf.SettingsManager

/**
 * Manages Wubi 86 input state and generates Chinese character candidates.
 * Handles the input buffer and provides methods for candidate selection.
 * Also provides next-word predictions after a character is committed.
 *
 * Wubi is a shape-based Chinese input method where characters are
 * encoded using 1-4 letter codes based on their structural components.
 */
class WubiInputController(
    private val context: Context
) {
    companion object {
        private const val TAG = "WubiInputController"
        private const val MAX_WUBI_CODE_LENGTH = 4 // Wubi codes are max 4 characters
        private const val MAX_BUFFER_LENGTH = 30 // Allow longer input for English words (commit with Enter)
        private const val DEFAULT_PAGE_SIZE = 9  // Number of candidates per page (normal mode)
        private const val JUYING_PAGE_SIZE = 5   // Number of candidates per page (Juying mode)
    }

    // Dynamic page size (changes based on Juying mode)
    private var pageSize: Int = DEFAULT_PAGE_SIZE

    /**
     * Sets the page size for candidates.
     * @param juyingMode Whether Juying mode is enabled (uses 5 candidates per page)
     * @param maxCandidatesNonJuying Maximum number of candidates per page in non-Juying mode
     */
    fun setJuyingMode(juyingMode: Boolean, maxCandidatesNonJuying: Int = DEFAULT_PAGE_SIZE) {
        val newPageSize = if (juyingMode) JUYING_PAGE_SIZE else maxCandidatesNonJuying
        if (newPageSize != pageSize) {
            pageSize = newPageSize
            currentPage = 0 // Reset to first page when page size changes
        }
    }

    // Current wubi input buffer (e.g., "gggg" for "王")
    private var buffer = StringBuilder()

    // All candidates for the buffer (full list from dictionary)
    private var allCandidates: List<String> = emptyList()

    // Whether Wubi mode is currently active
    private var isWubiModeActive = false

    // Current page (0-indexed)
    private var currentPage: Int = 0

    // Whether we're showing next-word predictions
    private var isShowingNextWordPredictions: Boolean = false

    // Whether next word prediction feature is enabled
    private var nextWordPredictionEnabled: Boolean = true

    // Next-word predictor for suggesting words after commit
    private val nextWordPredictor: NextWordPredictor = NextWordPredictor.getInstance(context)

    // User memory for learning preferences (prioritize frequently selected characters)
    private val userMemory: UserWubiMemory = UserWubiMemory.getInstance(context)

    // Auto-phrase memory for learning new phrases from user input
    private val wubiPhraseMemory: WubiPhraseMemory = WubiPhraseMemory.getInstance(context)

    // Session tracking for auto-phrase learning
    // Tracks (wubiCode, character) pairs selected in the current input session
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
    private fun isAutoPhraseLearningEnabled(): Boolean {
        return isMemoryEnabled() && SettingsManager.isAutoPhrasMemoryEnabled(context)
    }

    // User custom dictionary for user-defined shortcuts
    private val customDictionary: UserCustomDictionary = UserCustomDictionary.getInstance(context)

    // Store the Wubi code used for the current selection (for memory recording)
    private var lastUsedWubiCode: String = ""

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
        val isNextWordPrediction: Boolean = false  // True when showing next-word predictions
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
                // Finalize session before exiting Wubi mode
                finalizeSession()
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

        // Finalize any pending session when user starts new input
        // This captures phrases like "王五李四" when user starts typing next phrase
        if (sessionSelections.size >= 2) {
            finalizeSession()
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
     * Checks if overflow auto-commit should happen before adding a new letter.
     * Overflow auto-commit occurs when:
     * - The overflow setting is enabled
     * - Buffer already has 4 characters (max Wubi code length)
     * - There is at least 1 candidate
     *
     * This is called BEFORE adding a new letter to handle 5th letter scenario.
     *
     * @return The candidate to auto-commit, or null if overflow should not happen
     */
    fun checkOverflowCommit(): String? {
        if (!SettingsManager.isWubiAutoCommitOverflow(context)) {
            return null
        }

        if (buffer.length != MAX_WUBI_CODE_LENGTH) {
            return null
        }

        if (allCandidates.isEmpty()) {
            return null
        }

        val candidate = allCandidates[0]
        Log.d(TAG, "Overflow auto-commit triggered: '$candidate' (buffer was '$buffer')")

        // Record in user memory if enabled
        if (isMemoryEnabled() && lastUsedWubiCode.isNotEmpty()) {
            userMemory.recordSelection(lastUsedWubiCode, candidate)
        }

        // Track selection for auto-phrase learning
        if (isAutoPhraseLearningEnabled() && lastUsedWubiCode.isNotEmpty() && candidate.length == 1) {
            sessionSelections.add(lastUsedWubiCode to candidate)
        }

        // Record for next-word prediction
        nextWordPredictor.recordCommittedWord(candidate)

        // Clear the buffer (new letter will be added by caller)
        buffer.clear()
        allCandidates = emptyList()
        currentPage = 0
        lastUsedWubiCode = ""

        return candidate
    }

    /**
     * Checks if auto-commit should happen for the current buffer.
     * Auto-commit occurs when:
     * - The setting is enabled
     * - Buffer has exactly 4 characters (max Wubi code length)
     * - There is exactly 1 candidate
     *
     * @return The candidate to auto-commit, or null if auto-commit should not happen
     */
    fun checkAutoCommit(): String? {
        if (!SettingsManager.isWubiAutoCommitSingle(context)) {
            return null
        }

        if (buffer.length != MAX_WUBI_CODE_LENGTH) {
            return null
        }

        if (allCandidates.size != 1) {
            return null
        }

        val candidate = allCandidates[0]
        Log.d(TAG, "Auto-commit triggered for single candidate: '$candidate'")

        // Record in user memory if enabled
        if (isMemoryEnabled() && lastUsedWubiCode.isNotEmpty()) {
            userMemory.recordSelection(lastUsedWubiCode, candidate)
        }

        // Track selection for auto-phrase learning
        if (isAutoPhraseLearningEnabled() && lastUsedWubiCode.isNotEmpty() && candidate.length == 1) {
            sessionSelections.add(lastUsedWubiCode to candidate)
        }

        // Record for next-word prediction
        nextWordPredictor.recordCommittedWord(candidate)

        // Clear the buffer
        buffer.clear()
        lastUsedWubiCode = ""

        return candidate
    }

    /**
     * Handles backspace in Wubi mode.
     * Removes the last character from the buffer, or clears next-word predictions if buffer is empty.
     * @return true if handled, false otherwise
     */
    fun handleBackspace(): Boolean {
        if (!isWubiModeActive) {
            return false
        }

        // If showing next-word predictions and buffer is empty, clear predictions
        if (buffer.isEmpty() && isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            allCandidates = emptyList()
            currentPage = 0
            nextWordPredictor.clearState()
            Log.d(TAG, "Backspace cleared next-word predictions")
            return true
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
        if (!isWubiModeActive || index < 0 || index >= currentPageCandidates.size) {
            return null
        }

        val selected = currentPageCandidates[index]
        Log.d(TAG, "Selected candidate $index: '$selected'")

        // Record the selection in user memory for learning (only for Wubi code-based selections)
        if (isMemoryEnabled() && !isShowingNextWordPredictions && lastUsedWubiCode.isNotEmpty()) {
            userMemory.recordSelection(lastUsedWubiCode, selected)
        }

        // Track selection for auto-phrase learning (only for single characters from Wubi code)
        // Only track single characters - phrases are already in the dictionary
        if (isAutoPhraseLearningEnabled() && !isShowingNextWordPredictions &&
            lastUsedWubiCode.isNotEmpty() && selected.length == 1) {
            sessionSelections.add(lastUsedWubiCode to selected)
        }

        // Record the committed word for next-word prediction learning
        nextWordPredictor.recordCommittedWord(selected)

        // Clear the buffer after selection (Wubi consumes entire code)
        buffer.clear()
        val savedWubiCode = lastUsedWubiCode
        lastUsedWubiCode = ""

        // Finalize the session now that the phrase input is complete
        // This records phrases like "王五" when user finishes picking all characters
        if (sessionSelections.size >= 2) {
            finalizeSession()
        }

        // Show next-word predictions if available
        showNextWordPredictions()

        return selected
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
     * Finalizes the current input session for auto-phrase learning.
     * Combines all single-character selections into a phrase and records it.
     *
     * Uses abbreviated Wubi codes: first 2 letters of each character's code.
     * Example: '王五' with codes 'gggg' + 'gghg' → abbreviated code 'gggg' (gg + gg)
     */
    private fun finalizeSession() {
        if (!isAutoPhraseLearningEnabled() || sessionSelections.size < 2) {
            sessionSelections.clear()
            return
        }

        // Create abbreviated Wubi code: first 2 letters of each character's code
        val abbreviatedWubiCode = sessionSelections.joinToString("") {
            it.first.take(2)  // Take only first 2 letters of each character's Wubi code
        }
        val combinedPhrase = sessionSelections.joinToString("") { it.second }

        // Check if this phrase already exists in dictionary with abbreviated code - if so, skip
        val existingPhrases = WubiDictionary.getCandidatesForPrefix(abbreviatedWubiCode, limit = 50)
        if (combinedPhrase !in existingPhrases) {
            // Record the new phrase for auto-learning with abbreviated code
            wubiPhraseMemory.recordPhrase(abbreviatedWubiCode, combinedPhrase)
            Log.d(TAG, "Wubi session finalized: '$abbreviatedWubiCode' → '$combinedPhrase' (abbreviated from ${sessionSelections.map { it.first }})")
        }

        sessionSelections.clear()
    }

    /**
     * Calculates total number of pages.
     */
    private fun getTotalPages(): Int {
        return if (allCandidates.isEmpty()) 1 else ((allCandidates.size + pageSize - 1) / pageSize)
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
        // Clear session without finalizing (user cancelled input)
        sessionSelections.clear()
        Log.d(TAG, "Buffer cleared")
    }

    /**
     * Checks if Wubi with Pinyin mode is enabled.
     */
    private fun isWubiWithPinyinEnabled(): Boolean {
        return SettingsManager.isWubiWithPinyinEnabled(context)
    }

    /**
     * Checks if Wubi phrases should be displayed first (before single characters).
     */
    private fun isPhrasesFirst(): Boolean {
        return SettingsManager.isWubiPhrasesFirst(context)
    }

    /**
     * Updates candidate list based on current buffer.
     * Uses prefix matching to show all possible completions.
     * Abbreviation matches appear first, then custom dictionary phrases, then sorted by user frequency.
     * If Wubi with Pinyin is enabled, Pinyin candidates are added after Wubi candidates.
     */
    private fun updateCandidates() {
        currentPage = 0  // Reset to first page when candidates change

        if (buffer.isEmpty()) {
            allCandidates = emptyList()
            lastUsedWubiCode = ""
            return
        }

        val bufferStr = buffer.toString()
        lastUsedWubiCode = bufferStr

        val resultCandidates = mutableListOf<String>()

        // Note: Abbreviation learning is disabled for Wubi mode
        // (multi-first-letter word memory is not used in Wubi)

        // Get custom dictionary phrases first (highest priority)
        val customPhrases = customDictionary.getWubiPhrases(bufferStr)
        if (customPhrases.isNotEmpty()) {
            for (phrase in customPhrases) {
                if (phrase !in resultCandidates) {
                    resultCandidates.add(phrase)
                }
            }
            Log.d(TAG, "Custom dictionary phrases for '$bufferStr': $customPhrases")
        }

        // Get candidates for the current code (both exact and prefix matches)
        val rawCandidates = WubiDictionary.getCandidatesForPrefix(bufferStr, limit = 50)

        // Separate single-character and multi-character dictionary candidates
        // Use raw dictionary order (no frequency sorting here - sorting is done later based on phrasesFirst setting)
        val singleCharDictCandidates = rawCandidates.filter { it.length == 1 }
        val multiCharDictCandidates = rawCandidates.filter { it.length > 1 }

        // Get auto-learned phrases
        val autoLearnedPhrases = if (isAutoPhraseLearningEnabled()) wubiPhraseMemory.getLearnedPhrases(bufferStr) else emptyList()
        if (autoLearnedPhrases.isNotEmpty()) {
            Log.d(TAG, "Auto-learned Wubi phrases for '$bufferStr': $autoLearnedPhrases")
        }

        // Get partial/prefix matching learned phrases (e.g., "ggtt" matches "ggttk" → "王五")
        val partialMatchPhrases = if (isAutoPhraseLearningEnabled()) wubiPhraseMemory.getLearnedPhrasesWithPrefix(bufferStr) else emptyList()
        if (partialMatchPhrases.isNotEmpty()) {
            Log.d(TAG, "Partial match Wubi phrases for '$bufferStr': $partialMatchPhrases")
        }

        // Check if "Phrases First" mode is enabled
        val phrasesFirst = isPhrasesFirst()

        if (phrasesFirst) {
            // Phrases First mode: use dictionary order as baseline, then boost by frequency
            // Candidates with usage history are boosted to the front (sorted by frequency)
            // Candidates without usage history maintain their dictionary order
            val allDictCandidates = mutableListOf<String>()

            // Collect all candidates in dictionary order first
            for (candidate in rawCandidates) {
                if (candidate !in resultCandidates && candidate !in allDictCandidates) {
                    allDictCandidates.add(candidate)
                }
            }
            for (phrase in autoLearnedPhrases) {
                if (phrase !in resultCandidates && phrase !in allDictCandidates) {
                    allDictCandidates.add(phrase)
                }
            }
            for (phrase in partialMatchPhrases) {
                if (phrase !in resultCandidates && phrase !in allDictCandidates) {
                    allDictCandidates.add(phrase)
                }
            }

            // Separate candidates with usage history from those without
            val usedCandidates = allDictCandidates.filter { userMemory.getFrequency(bufferStr, it) > 0 }
            val unusedCandidates = allDictCandidates.filter { userMemory.getFrequency(bufferStr, it) == 0 }

            // Sort used candidates by frequency (highest first)
            // When frequencies are equal, longer (phrases) come first
            val sortedUsedCandidates = usedCandidates.sortedWith(
                compareByDescending<String> { userMemory.getFrequency(bufferStr, it) }
                    .thenByDescending { it.length }
            )

            // Add used candidates first (sorted by frequency), then unused in dictionary order
            resultCandidates.addAll(sortedUsedCandidates)
            resultCandidates.addAll(unusedCandidates)
        } else {
            // Default mode: preserve exact dictionary order (no separation of single chars vs phrases)
            // The dictionary already has the correct order (e.g., "thnn" → ["自己", "臫"])

            // Add all dictionary candidates in their original order
            for (candidate in rawCandidates) {
                if (candidate !in resultCandidates) {
                    resultCandidates.add(candidate)
                }
            }

            // Add auto-learned phrases after dictionary candidates
            for (phrase in autoLearnedPhrases) {
                if (phrase !in resultCandidates) {
                    resultCandidates.add(phrase)
                }
            }

            // Add partial match phrases
            for (phrase in partialMatchPhrases) {
                if (phrase !in resultCandidates) {
                    resultCandidates.add(phrase)
                }
            }

            // No frequency sorting - keep dictionary order as-is
        }

        val wubiCandidateCount = resultCandidates.size

        // If Wubi with Pinyin is enabled, add Pinyin candidates after Wubi candidates
        if (isWubiWithPinyinEnabled()) {
            // Ensure Pinyin dictionary is loaded
            if (!PinyinDictionary.isLoaded()) {
                PinyinDictionary.load(context)
            }

            // Get Pinyin candidates for the same input
            // First try exact syllable match, then prefix match
            val pinyinCandidates = mutableListOf<String>()

            // Get phrase candidates (multi-syllable matches)
            val phraseCandidates = PinyinDictionary.getPhraseCandidates(bufferStr)
            pinyinCandidates.addAll(phraseCandidates)

            // Get single-character candidates (for prefix matching)
            val prefixCandidates = PinyinDictionary.getCandidatesForPrefix(bufferStr)
            for (candidate in prefixCandidates) {
                if (candidate !in pinyinCandidates) {
                    pinyinCandidates.add(candidate)
                }
            }

            // Add Pinyin candidates (excluding duplicates from Wubi)
            var pinyinAddedCount = 0
            for (candidate in pinyinCandidates) {
                if (candidate !in resultCandidates) {
                    resultCandidates.add(candidate)
                    pinyinAddedCount++
                }
            }

            if (pinyinAddedCount > 0) {
                Log.d(TAG, "Added $pinyinAddedCount Pinyin candidates after $wubiCandidateCount Wubi candidates for '$bufferStr'")
            }
        }

        allCandidates = resultCandidates

        Log.d(TAG, "Updated candidates for '$bufferStr': ${allCandidates.size} (${customPhrases.size} custom, sorted by frequency)")
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
     * Used for saving 5th suggestion when Alt is pressed in Juying mode.
     */
    fun getCurrentPageCandidates(): List<String> {
        val startIndex = currentPage * pageSize
        val endIndex = minOf(startIndex + pageSize, allCandidates.size)
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
     * Resets quote states to opening (called when exiting Wubi mode or starting fresh).
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
}
