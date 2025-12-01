package it.neuralrad.coolwulf.core

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import it.neuralrad.coolwulf.data.pinyin.PinyinDictionary
import it.neuralrad.coolwulf.data.pinyin.UserPinyinMemory
import it.neuralrad.coolwulf.data.shuangpin.ShuangpinConverter
import it.neuralrad.coolwulf.data.NextWordPredictor
import it.neuralrad.coolwulf.data.UserCustomDictionary
import it.neuralrad.coolwulf.SettingsManager

/**
 * Manages Shuangpin (双拼) input state and generates Chinese character candidates.
 * Uses the 小鹤双拼 (Xiaohe Shuangpin) scheme where each syllable is typed with exactly 2 keys.
 *
 * The input flow:
 * 1. User types 2 keys (e.g., "nh")
 * 2. ShuangpinConverter converts to pinyin (e.g., "ni")
 * 3. PinyinDictionary provides character candidates
 * 4. User selects a candidate
 */
class ShuangpinInputController(
    private val context: Context
) {
    companion object {
        private const val TAG = "ShuangpinInputController"
        private const val MAX_BUFFER_LENGTH = 50 // Maximum Shuangpin buffer length
        private const val DEFAULT_PAGE_SIZE = 9  // Number of candidates per page (normal mode)
        private const val JUYING_PAGE_SIZE = 5   // Number of candidates per page (Juying mode)
    }

    // Dynamic page size (changes based on Juying mode)
    private var pageSize: Int = DEFAULT_PAGE_SIZE

    /**
     * Sets the page size for candidates.
     * @param juyingMode Whether Juying mode is enabled (uses 5 candidates per page)
     */
    fun setJuyingMode(juyingMode: Boolean) {
        val newPageSize = if (juyingMode) JUYING_PAGE_SIZE else DEFAULT_PAGE_SIZE
        if (newPageSize != pageSize) {
            pageSize = newPageSize
            currentPage = 0 // Reset to first page when page size changes
        }
    }

    // Current Shuangpin input buffer (e.g., "nhmc" for 你好吗)
    private var buffer = StringBuilder()

    // All candidates for the current buffer
    private var allCandidates: List<String> = emptyList()

    // Whether Shuangpin mode is currently active
    private var isShuangpinModeActive = false

    // Track the matched pinyin for the current candidates
    private var matchedPinyin: String = ""

    // Track how many candidates are phrase candidates
    private var phraseCandidateCount: Int = 0

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

    data class Snapshot(
        val isActive: Boolean,
        val buffer: String,
        val candidates: List<String>,
        val hasCandidates: Boolean,
        val currentPage: Int = 0,
        val totalPages: Int = 1,
        val hasNextPage: Boolean = false,
        val hasPrevPage: Boolean = false,
        val isNextWordPrediction: Boolean = false,
        val pinyinPreview: String = ""  // Shows the pinyin conversion of current buffer
    )

    // User memory for learning preferences (reuse Pinyin memory since it's the same characters)
    private val userMemory: UserPinyinMemory = UserPinyinMemory.getInstance(context)

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
     * Enables or disables Shuangpin input mode.
     */
    fun setShuangpinMode(active: Boolean) {
        if (isShuangpinModeActive != active) {
            isShuangpinModeActive = active
            if (!active) {
                clearBuffer()
            }
            Log.d(TAG, "========== Shuangpin mode: ${if (active) "ENABLED" else "DISABLED"} ==========")
        }
    }

    /**
     * Toggles Shuangpin input mode on/off.
     */
    fun toggleShuangpinMode() {
        setShuangpinMode(!isShuangpinModeActive)
    }

    /**
     * Returns whether Shuangpin mode is currently active.
     */
    fun isShuangpinMode(): Boolean = isShuangpinModeActive

    /**
     * Processes a letter key press in Shuangpin mode.
     * @param char The character to add (a-z)
     * @return true if the key was handled, false otherwise
     */
    fun handleLetterKey(char: Char): Boolean {
        if (!isShuangpinModeActive) {
            return false
        }

        // Only accept lowercase letters
        val lowerChar = char.lowercaseChar()
        if (!lowerChar.isLetter() || lowerChar < 'a' || lowerChar > 'z') {
            return false
        }

        // Check buffer length limit
        if (buffer.length >= MAX_BUFFER_LENGTH) {
            Log.w(TAG, "Buffer full, ignoring input")
            return true
        }

        // Clear next-word prediction state when user starts typing
        if (isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            nextWordPredictor.onUserStartedTyping()
        }

        buffer.append(lowerChar)
        updateCandidates()
        Log.d(TAG, "Letter added: '$lowerChar' → Buffer: '$buffer', Candidates: ${allCandidates.size}")
        return true
    }

    /**
     * Handles backspace in Shuangpin mode.
     * @return true if handled, false otherwise
     */
    fun handleBackspace(): Boolean {
        if (!isShuangpinModeActive) {
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
        if (!isShuangpinModeActive || index < 0 || index >= currentPageCandidates.size) {
            return null
        }

        val selected = currentPageCandidates[index]
        val actualIndex = currentPage * pageSize + index
        val isPhrase = actualIndex < phraseCandidateCount

        // Determine how much of the buffer to consume
        val charsToConsume: Int
        val pinyinToRecord: String

        if (isPhrase) {
            // Phrase candidate - consume entire buffer
            charsToConsume = buffer.length
            pinyinToRecord = matchedPinyin
        } else {
            // Single character - consume first 2 chars (one Shuangpin syllable)
            charsToConsume = minOf(2, buffer.length)
            // Convert the consumed part to pinyin for recording
            val consumed = buffer.substring(0, charsToConsume)
            pinyinToRecord = ShuangpinConverter.toPinyin(consumed) ?: consumed
        }

        // Record learning
        if (pinyinToRecord.isNotEmpty()) {
            recordSelectionIfEnabled(pinyinToRecord, selected)
        }

        Log.d(TAG, "Selected candidate $index: '$selected', consuming $charsToConsume chars, pinyin: '$pinyinToRecord'")

        // Remove consumed characters from buffer
        if (charsToConsume > 0 && charsToConsume <= buffer.length) {
            buffer.delete(0, charsToConsume)
        }

        // Record for next-word prediction
        nextWordPredictor.recordCommittedWord(selected)

        // Update candidates for remaining buffer
        if (buffer.isEmpty()) {
            showNextWordPredictions()
        } else {
            isShowingNextWordPredictions = false
            updateCandidates()
        }

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
                phraseCandidateCount = nextWordSuggestions.size
                matchedPinyin = ""
                Log.d(TAG, "Showing next-word predictions: $nextWordSuggestions")
                return
            }
        }
        isShowingNextWordPredictions = false
        updateCandidates()
    }

    /**
     * Calculates total number of pages.
     */
    private fun getTotalPages(): Int {
        return if (allCandidates.isEmpty()) 1 else ((allCandidates.size + pageSize - 1) / pageSize)
    }

    /**
     * Navigates to the next page of candidates.
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
     */
    fun selectFirstCandidate(): String? {
        return selectCandidate(0)
    }

    /**
     * Handles number key press for candidate selection (1-9).
     */
    fun handleNumberKey(keyCode: Int): String? {
        if (!isShuangpinModeActive || allCandidates.isEmpty()) {
            return null
        }

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

        return selectCandidate(number - 1)
    }

    /**
     * Clears the input buffer and candidates.
     */
    fun clearBuffer() {
        buffer.clear()
        allCandidates = emptyList()
        matchedPinyin = ""
        phraseCandidateCount = 0
        currentPage = 0
        Log.d(TAG, "Buffer cleared")
    }

    /**
     * Updates candidate list based on current buffer.
     * Converts Shuangpin to Pinyin and looks up candidates.
     * Also supports abbreviation matching (first letter of each syllable).
     */
    private fun updateCandidates() {
        currentPage = 0

        if (buffer.isEmpty()) {
            allCandidates = emptyList()
            matchedPinyin = ""
            phraseCandidateCount = 0
            return
        }

        val bufferStr = buffer.toString()
        val resultCandidates = mutableListOf<String>()

        // Check custom dictionary first (Shuangpin-specific mappings)
        val customPhrases = customDictionary.getShuangpinPhrases(bufferStr)
        if (customPhrases.isNotEmpty()) {
            resultCandidates.addAll(customPhrases)
            Log.d(TAG, "Custom dictionary phrases for '$bufferStr': $customPhrases")
        }

        // Check for abbreviation matches (first letter of each pinyin syllable)
        // This allows typing "nh" to get "你好" if user has typed it before
        val abbreviationCandidates = getAbbreviationCandidates(bufferStr)
        if (abbreviationCandidates.isNotEmpty()) {
            for (candidate in abbreviationCandidates) {
                if (candidate !in resultCandidates) {
                    resultCandidates.add(candidate)
                }
            }
            Log.d(TAG, "Abbreviation matches for '$bufferStr': $abbreviationCandidates")
        }

        // Convert Shuangpin to Pinyin
        val pinyinString = ShuangpinConverter.toPinyinString(bufferStr)

        if (pinyinString != null) {
            matchedPinyin = pinyinString

            // Try to get phrase candidates for the full pinyin
            val phraseCandidates = PinyinDictionary.getPhraseCandidates(pinyinString)
            if (phraseCandidates.isNotEmpty()) {
                val sortedPhrases = sortByFrequencyIfEnabled(pinyinString, phraseCandidates)
                for (phrase in sortedPhrases) {
                    if (phrase !in resultCandidates) {
                        resultCandidates.add(phrase)
                    }
                }
            }

            phraseCandidateCount = resultCandidates.size

            // Also get single character candidates for the first syllable
            val syllables = ShuangpinConverter.toPinyinSyllables(bufferStr)
            if (syllables.isNotEmpty()) {
                val firstSyllable = syllables[0]
                val charCandidates = PinyinDictionary.getCandidates(firstSyllable)
                val sortedChars = sortByFrequencyIfEnabled(firstSyllable, charCandidates)
                for (char in sortedChars) {
                    if (char !in resultCandidates) {
                        resultCandidates.add(char)
                    }
                }
            }

            Log.d(TAG, "Shuangpin '$bufferStr' -> Pinyin '$pinyinString', candidates: ${resultCandidates.size}")
        } else {
            // Partial input (odd number of characters)
            // Show candidates based on possible pinyin initials

            // For odd-length input, try treating each character as an abbreviation key
            // E.g., "nhm" could be abbreviation for "你好吗"
            if (bufferStr.length >= 2) {
                // The buffer itself could be an abbreviation
                val directAbbrevCandidates = getAbbreviationCandidatesIfEnabled(bufferStr)
                for (candidate in directAbbrevCandidates) {
                    if (candidate !in resultCandidates) {
                        resultCandidates.add(candidate)
                    }
                }
            }

            if (bufferStr.length == 1) {
                // Single key input - show all candidates for possible initials
                val singleKeyCandidates = getSingleKeyCandidates(bufferStr[0])
                val prefixes = ShuangpinConverter.getPossiblePinyinPrefixes(bufferStr[0])
                matchedPinyin = prefixes.firstOrNull() ?: bufferStr

                // Sort by user memory using the first prefix as reference
                val sortedCandidates = if (prefixes.isNotEmpty()) {
                    sortByFrequencyIfEnabled(prefixes[0], singleKeyCandidates)
                } else {
                    singleKeyCandidates
                }

                for (candidate in sortedCandidates) {
                    if (candidate !in resultCandidates) {
                        resultCandidates.add(candidate)
                    }
                }
                phraseCandidateCount = resultCandidates.size - sortedCandidates.filter { it !in resultCandidates.take(resultCandidates.size - sortedCandidates.size) }.size

                Log.d(TAG, "Single key '$bufferStr' -> prefixes: $prefixes, candidates: ${resultCandidates.size}")
            } else {
                // Multiple characters with odd length - parse complete pairs and show prefix candidates for the last char
                val partialPinyin = getPartialPinyin(bufferStr)
                if (partialPinyin.isNotEmpty()) {
                    matchedPinyin = partialPinyin

                    // Get candidates for the last (incomplete) key
                    val lastChar = bufferStr.last()
                    val lastKeyCandidates = getSingleKeyCandidates(lastChar)
                    val sortedCandidates = sortByFrequencyIfEnabled(partialPinyin, lastKeyCandidates)
                    for (candidate in sortedCandidates) {
                        if (candidate !in resultCandidates) {
                            resultCandidates.add(candidate)
                        }
                    }
                }
                phraseCandidateCount = resultCandidates.size

                Log.d(TAG, "Partial Shuangpin '$bufferStr' -> candidates: ${resultCandidates.size}")
            }
        }

        allCandidates = resultCandidates
    }

    /**
     * Gets abbreviation candidates for the given input.
     * In Shuangpin, we can use two approaches:
     * 1. Direct abbreviation lookup (e.g., "nh" for "你好")
     * 2. Convert each key to pinyin initial and lookup (e.g., "nh" -> initials n, h)
     */
    private fun getAbbreviationCandidates(input: String): List<String> {
        val candidates = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // First, try direct abbreviation lookup from user memory
        val directAbbrev = getAbbreviationCandidatesIfEnabled(input)
        for (candidate in directAbbrev) {
            if (candidate !in seen) {
                seen.add(candidate)
                candidates.add(candidate)
            }
        }

        // Also try interpreting the Shuangpin keys as pinyin initials
        // Each Shuangpin key maps to a pinyin initial, so "nh" -> "n" + "h" initials
        // But we need to convert special keys: v->zh, i->ch, u->sh
        val pinyinAbbrev = StringBuilder()
        for (char in input) {
            val prefixes = ShuangpinConverter.getPossiblePinyinPrefixes(char)
            // Take the first (primary) initial for abbreviation
            val initial = prefixes.firstOrNull() ?: char.toString()
            // For abbreviation, we only want the first letter of the initial
            pinyinAbbrev.append(initial.first())
        }

        if (pinyinAbbrev.toString() != input) {
            val convertedAbbrev = getAbbreviationCandidatesIfEnabled(pinyinAbbrev.toString())
            for (candidate in convertedAbbrev) {
                if (candidate !in seen) {
                    seen.add(candidate)
                    candidates.add(candidate)
                }
            }
        }

        return candidates
    }

    /**
     * Gets partial pinyin from incomplete Shuangpin buffer.
     * For example, "nih" -> "ni" + partial "h"
     */
    private fun getPartialPinyin(shuangpin: String): String {
        if (shuangpin.isEmpty()) return ""

        val completePairs = shuangpin.length / 2
        val hasPartial = shuangpin.length % 2 == 1

        val syllables = mutableListOf<String>()

        // Convert complete pairs
        for (i in 0 until completePairs) {
            val pair = shuangpin.substring(i * 2, i * 2 + 2)
            val pinyin = ShuangpinConverter.toPinyin(pair)
            if (pinyin != null) {
                syllables.add(pinyin)
            }
        }

        // Handle partial (single character at end)
        if (hasPartial) {
            val lastChar = shuangpin.last()
            // Get possible pinyin prefixes for this single key
            val prefixes = ShuangpinConverter.getPossiblePinyinPrefixes(lastChar)
            if (prefixes.isNotEmpty()) {
                // Add the first (most likely) prefix
                syllables.add(prefixes[0])
            }
        }

        return syllables.joinToString("")
    }

    /**
     * Gets candidates for a single Shuangpin key (before second key is typed).
     * Maps the key to possible pinyin initials and collects candidates from all matching syllables.
     */
    private fun getSingleKeyCandidates(key: Char): List<String> {
        val candidates = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // Get all possible pinyin prefixes for this key
        val prefixes = ShuangpinConverter.getPossiblePinyinPrefixes(key)

        for (prefix in prefixes) {
            // Get candidates for all syllables starting with this prefix
            val prefixCandidates = PinyinDictionary.getCandidatesForPrefix(prefix)
            for (candidate in prefixCandidates) {
                if (candidate !in seen) {
                    seen.add(candidate)
                    candidates.add(candidate)
                }
            }
        }

        return candidates
    }

    /**
     * Gets current state snapshot for UI updates.
     */
    fun getSnapshot(): Snapshot {
        val currentPageCandidates = getCurrentPageCandidates()
        val totalPages = getTotalPages()

        // Generate pinyin preview for the buffer
        val pinyinPreview = if (buffer.isNotEmpty()) {
            ShuangpinConverter.toPinyinString(buffer.toString()) ?: getPartialPinyin(buffer.toString())
        } else {
            ""
        }

        return Snapshot(
            isActive = isShuangpinModeActive,
            buffer = buffer.toString(),
            candidates = currentPageCandidates,
            hasCandidates = allCandidates.isNotEmpty(),
            currentPage = currentPage,
            totalPages = totalPages,
            hasNextPage = currentPage < totalPages - 1,
            hasPrevPage = currentPage > 0,
            isNextWordPrediction = isShowingNextWordPredictions,
            pinyinPreview = pinyinPreview
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
     * Called when user inputs punctuation.
     */
    fun onPunctuationInput() {
        if (isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            allCandidates = emptyList()
            currentPage = 0
        }
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
     * Commits the current buffer as-is.
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
    fun hasNextPage(): Boolean = currentPage < getTotalPages() - 1

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

    // Quote state methods (same as PinyinInputController)
    fun isNextDoubleQuoteOpening(): Boolean = nextDoubleQuoteIsOpening
    fun toggleDoubleQuoteState() { nextDoubleQuoteIsOpening = !nextDoubleQuoteIsOpening }
    fun isNextSingleQuoteOpening(): Boolean = nextSingleQuoteIsOpening
    fun toggleSingleQuoteState() { nextSingleQuoteIsOpening = !nextSingleQuoteIsOpening }
    fun resetQuoteStates() {
        nextDoubleQuoteIsOpening = true
        nextSingleQuoteIsOpening = true
    }

    fun isChinesePunctuationMode(): Boolean = useChinesePunctuation
    fun setChinesePunctuationMode(enabled: Boolean) { useChinesePunctuation = enabled }
    fun togglePunctuationMode() { useChinesePunctuation = !useChinesePunctuation }

    fun setNextWordPredictionEnabled(enabled: Boolean) {
        nextWordPredictionEnabled = enabled
        if (!enabled && isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            allCandidates = emptyList()
            currentPage = 0
        }
    }

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
    private fun recordSelectionIfEnabled(pinyin: String, selected: String) {
        if (isMemoryEnabled()) {
            userMemory.recordSelection(pinyin, selected)
        }
    }

    /**
     * Sorts candidates by frequency if memory function is enabled, otherwise returns unsorted.
     */
    private fun sortByFrequencyIfEnabled(pinyin: String, candidates: List<String>): List<String> {
        return if (isMemoryEnabled()) {
            userMemory.sortByFrequency(pinyin, candidates)
        } else {
            candidates
        }
    }

    /**
     * Gets abbreviation candidates if memory function is enabled, otherwise returns empty list.
     */
    private fun getAbbreviationCandidatesIfEnabled(input: String): List<String> {
        return if (isMemoryEnabled()) {
            userMemory.getAbbreviationCandidates(input)
        } else {
            emptyList()
        }
    }
}
