package it.neuralrad.coolwulf.core

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import it.neuralrad.coolwulf.data.pinyin.AutoPhraseMemory
import it.neuralrad.coolwulf.data.pinyin.ChineseCharacterConverter
import it.neuralrad.coolwulf.data.pinyin.PinyinDictionary
import it.neuralrad.coolwulf.data.pinyin.UserPinyinMemory
import it.neuralrad.coolwulf.data.UserCustomDictionary
import it.neuralrad.coolwulf.SettingsManager

/**
 * T9 (九宫格) Pinyin input controller for single-handed Chinese input.
 *
 * Uses the standard T9 key mapping:
 * - 1 = Separator (')
 * - 2 (W) = ABC
 * - 3 (E) = DEF
 * - 4 (R) = GHI
 * - 5 (S) = JKL
 * - 6 (D) = MNO
 * - 7 (F) = PQRS
 * - 8 = TUV
 * - 9 = WXYZ
 *
 * Device-specific letter key mappings:
 * - Titan 2: W/E/R/S/D/F/X/C/V -> 1/2/3/4/5/6/7/8/9
 * - BlackBerry: W/E/R/S/D/F/Z/X/C -> 1/2/3/4/5/6/7/8/9
 */
class T9PinyinInputController(
    private val context: Context
) {
    companion object {
        private const val TAG = "T9PinyinInput"
        private const val MAX_BUFFER_LENGTH = 100  // Maximum T9 digit buffer length (increased for long sentences)
        private const val DEFAULT_PAGE_SIZE = 9   // Number of candidates per page
        private const val JUYING_PAGE_SIZE = 5    // Number of candidates per page in Juying mode

        // T9 key to letters mapping
        private val T9_MAPPING = mapOf(
            '2' to "abc",
            '3' to "def",
            '4' to "ghi",
            '5' to "jkl",
            '6' to "mno",
            '7' to "pqrs",
            '8' to "tuv",
            '9' to "wxyz"
        )

        // Letter keys to T9 digit mapping for Titan 2
        // W/E/R/S/D/F/X/C/V -> 1/2/3/4/5/6/7/8/9
        private val LETTER_TO_T9_TITAN2 = mapOf(
            'w' to '1', 'W' to '1',  // Separator key
            'e' to '2', 'E' to '2',
            'r' to '3', 'R' to '3',
            's' to '4', 'S' to '4',
            'd' to '5', 'D' to '5',
            'f' to '6', 'F' to '6',
            'x' to '7', 'X' to '7',
            'c' to '8', 'C' to '8',
            'v' to '9', 'V' to '9'
        )

        // Reverse mapping for display (Titan 2)
        private val T9_TO_LETTER_TITAN2 = mapOf(
            '1' to 'W', '2' to 'E', '3' to 'R',
            '4' to 'S', '5' to 'D', '6' to 'F',
            '7' to 'X', '8' to 'C', '9' to 'V'
        )

        // Letter keys to T9 digit mapping for BlackBerry
        // W/E/R/S/D/F/Z/X/C -> 1/2/3/4/5/6/7/8/9
        private val LETTER_TO_T9_BLACKBERRY = mapOf(
            'w' to '1', 'W' to '1',  // Separator key
            'e' to '2', 'E' to '2',
            'r' to '3', 'R' to '3',
            's' to '4', 'S' to '4',
            'd' to '5', 'D' to '5',
            'f' to '6', 'F' to '6',
            'z' to '7', 'Z' to '7',
            'x' to '8', 'X' to '8',
            'c' to '9', 'C' to '9'
        )

        // Reverse mapping for display (BlackBerry)
        private val T9_TO_LETTER_BLACKBERRY = mapOf(
            '1' to 'W', '2' to 'E', '3' to 'R',
            '4' to 'S', '5' to 'D', '6' to 'F',
            '7' to 'Z', '8' to 'X', '9' to 'C'
        )
    }

    // Device-specific mappings (loaded based on device type)
    private var letterToT9: Map<Char, Char> = LETTER_TO_T9_TITAN2
    private var t9ToLetter: Map<Char, Char> = T9_TO_LETTER_TITAN2

    // T9 digit buffer (e.g., "64426" for "nihao")
    private var digitBuffer = StringBuilder()

    // All candidates for current buffer
    private var allCandidates: List<String> = emptyList()

    // Current possible pinyin interpretations of the digit buffer
    private var possiblePinyins: List<String> = emptyList()

    // Whether T9 mode is active
    private var isT9ModeActive = false

    // Current page (0-indexed)
    private var currentPage = 0

    // Page size
    private var pageSize = DEFAULT_PAGE_SIZE

    // Dynamic display limit for Juying mode (can be 1, 3, or 5 based on candidate length)
    // When set (> 0), this overrides pageSize for pagination
    private var dynamicDisplayLimit: Int = 0

    // Whether Juying mode is enabled
    private var isJuyingModeEnabled: Boolean = false

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
     * Sets Juying mode enabled state.
     */
    fun setJuyingModeEnabled(enabled: Boolean) {
        isJuyingModeEnabled = enabled
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

    // User memory for frequency sorting
    private val userMemory: UserPinyinMemory = UserPinyinMemory.getInstance(context)

    // Custom dictionary for user-defined phrases
    private val customDictionary: UserCustomDictionary = UserCustomDictionary.getInstance(context)

    // Auto phrase memory for learned phrases
    private val autoPhraseMemory: AutoPhraseMemory = AutoPhraseMemory.getInstance(context)

    // Session selections for phrase learning (pinyin -> selected character)
    private val sessionSelections = mutableListOf<Pair<String, String>>()

    /**
     * Checks if auto phrase memory is enabled.
     */
    private fun isAutoPhraseMemoryEnabled(): Boolean {
        return SettingsManager.getMemoryFunctionEnabled(context) &&
               SettingsManager.isAutoPhrasMemoryEnabled(context)
    }

    data class Snapshot(
        val isActive: Boolean,
        val buffer: String,           // Display buffer (e.g., "64426" or "DDRCD")
        val displayBuffer: String,    // Human-readable T9 digits
        val candidates: List<String>,
        val hasCandidates: Boolean,
        val currentPage: Int = 0,
        val totalPages: Int = 1,
        val hasNextPage: Boolean = false,
        val hasPrevPage: Boolean = false,
        val possiblePinyins: List<String> = emptyList()  // Show possible interpretations
    )

    init {
        if (!PinyinDictionary.isLoaded()) {
            PinyinDictionary.load(context)
        }
        // Load device-specific key mappings
        updateDeviceMappings()
    }

    /**
     * Updates the key mappings based on the current device type.
     * Call this when device type changes.
     */
    fun updateDeviceMappings() {
        val isBlackBerry = SettingsManager.isBlackBerryDevice(context)
        letterToT9 = if (isBlackBerry) LETTER_TO_T9_BLACKBERRY else LETTER_TO_T9_TITAN2
        t9ToLetter = if (isBlackBerry) T9_TO_LETTER_BLACKBERRY else T9_TO_LETTER_TITAN2
        Log.d(TAG, "Device mappings updated: ${if (isBlackBerry) "BlackBerry" else "Titan 2"}")
    }

    /**
     * Enables or disables T9 input mode.
     */
    fun setT9Mode(active: Boolean) {
        if (isT9ModeActive != active) {
            isT9ModeActive = active
            if (!active) {
                // Try to finalize any pending phrase learning before clearing
                if (sessionSelections.size >= 2) {
                    finalizeSession()
                }
                clearBuffer()
                clearSession()
            }
            Log.d(TAG, "T9 mode: ${if (active) "ENABLED" else "DISABLED"}")
        }
    }

    /**
     * Toggles T9 input mode.
     */
    fun toggleT9Mode() {
        setT9Mode(!isT9ModeActive)
    }

    /**
     * Returns whether T9 mode is active.
     */
    fun isT9Mode(): Boolean = isT9ModeActive

    /**
     * Handles a key press in T9 mode.
     * Accepts number keys 1-9, or device-specific letter keys.
     * @param keyCode The Android key code
     * @param char The character if available
     * @return true if the key was handled
     */
    fun handleKeyPress(keyCode: Int, char: Char?): Boolean {
        if (!isT9ModeActive) return false

        // Try to map the input to a T9 digit
        val digit = when {
            // Number keys 1-9
            keyCode == KeyEvent.KEYCODE_1 || char == '1' -> '1'  // Separator
            keyCode == KeyEvent.KEYCODE_2 || char == '2' -> '2'
            keyCode == KeyEvent.KEYCODE_3 || char == '3' -> '3'
            keyCode == KeyEvent.KEYCODE_4 || char == '4' -> '4'
            keyCode == KeyEvent.KEYCODE_5 || char == '5' -> '5'
            keyCode == KeyEvent.KEYCODE_6 || char == '6' -> '6'
            keyCode == KeyEvent.KEYCODE_7 || char == '7' -> '7'
            keyCode == KeyEvent.KEYCODE_8 || char == '8' -> '8'
            keyCode == KeyEvent.KEYCODE_9 || char == '9' -> '9'
            // Device-specific letter keys (W/E/R/S/D/F/X/C/V for Titan 2, W/E/R/S/D/F/Z/X/C for BlackBerry)
            char != null && letterToT9.containsKey(char) -> letterToT9[char]!!
            else -> null
        }

        if (digit == null) return false

        // Handle separator key (1)
        if (digit == '1') {
            return handleSeparator()
        }

        // Check buffer limit
        if (digitBuffer.length >= MAX_BUFFER_LENGTH) {
            Log.w(TAG, "Buffer full")
            return true
        }

        digitBuffer.append(digit)
        updateCandidates()
        Log.d(TAG, "T9 input: $digit -> Buffer: $digitBuffer")
        return true
    }

    /**
     * Handles the separator key (for disambiguating syllables).
     */
    private fun handleSeparator(): Boolean {
        if (digitBuffer.isEmpty()) return false

        // Add a separator marker (we'll use '1' internally)
        if (digitBuffer.last() != '1') {
            digitBuffer.append('1')
            updateCandidates()
            Log.d(TAG, "Separator added -> Buffer: $digitBuffer")
        }
        return true
    }

    /**
     * Handles backspace.
     */
    fun handleBackspace(): Boolean {
        if (!isT9ModeActive || digitBuffer.isEmpty()) return false

        digitBuffer.deleteCharAt(digitBuffer.length - 1)
        updateCandidates()
        Log.d(TAG, "Backspace -> Buffer: $digitBuffer")
        return true
    }

    /**
     * Selects a candidate by index.
     * @return The selected Chinese text, or null if invalid
     */
    fun selectCandidate(index: Int): String? {
        val pageCandidates = getCurrentPageCandidates()
        if (!isT9ModeActive || index < 0 || index >= pageCandidates.size) {
            return null
        }

        val selected = pageCandidates[index]
        val currentPinyin = if (possiblePinyins.isNotEmpty()) possiblePinyins.first() else ""

        // Record selection for learning
        if (SettingsManager.getMemoryFunctionEnabled(context) && currentPinyin.isNotEmpty()) {
            // Record for the most likely pinyin
            userMemory.recordSelection(currentPinyin, selected)

            // Track for phrase learning
            if (isAutoPhraseMemoryEnabled()) {
                sessionSelections.add(Pair(currentPinyin, selected))
                // Try to finalize session if we have enough selections
                if (sessionSelections.size >= 2) {
                    finalizeSession()
                }
            }
        }

        // Clear buffer after selection
        clearBuffer()

        // Convert to traditional if needed
        val useTraditional = SettingsManager.isTraditionalChineseMode(context)
        return if (useTraditional) {
            ChineseCharacterConverter.toTraditional(selected)
        } else {
            selected
        }
    }

    /**
     * Finalizes the current session and learns phrases from selections.
     */
    private fun finalizeSession() {
        if (!isAutoPhraseMemoryEnabled() || sessionSelections.size < 2) {
            sessionSelections.clear()
            return
        }

        // Combine consecutive selections into a phrase
        val combinedPinyin = sessionSelections.joinToString("") { it.first }
        val combinedPhrase = sessionSelections.joinToString("") { it.second }

        // Only learn phrases of reasonable length (2-6 characters)
        if (combinedPhrase.length in 2..6) {
            autoPhraseMemory.recordPhrase(combinedPinyin, combinedPhrase)
            Log.d(TAG, "Learned phrase: $combinedPinyin -> $combinedPhrase")
        }

        sessionSelections.clear()
    }

    /**
     * Selects the first candidate (for space key).
     */
    fun selectFirstCandidate(): String? = selectCandidate(0)

    /**
     * Clears the input buffer.
     */
    fun clearBuffer() {
        digitBuffer.clear()
        allCandidates = emptyList()
        possiblePinyins = emptyList()
        currentPage = 0
        // Note: sessionSelections is NOT cleared here to allow phrase learning across multiple inputs
    }

    /**
     * Clears the session selections (for phrase learning).
     * Call this when user switches away from T9 mode or starts a new context.
     */
    fun clearSession() {
        sessionSelections.clear()
    }

    /**
     * Updates candidates based on current T9 buffer.
     */
    private fun updateCandidates() {
        currentPage = 0

        if (digitBuffer.isEmpty()) {
            allCandidates = emptyList()
            possiblePinyins = emptyList()
            return
        }

        // Split by separator (digit 1)
        val segments = digitBuffer.toString().split('1').filter { it.isNotEmpty() }

        if (segments.isEmpty()) {
            allCandidates = emptyList()
            possiblePinyins = emptyList()
            return
        }

        // Generate all possible pinyin combinations for the T9 sequence
        val allPossiblePinyins = generatePossiblePinyins(segments)
        possiblePinyins = allPossiblePinyins.take(5)  // Show top 5 interpretations

        // Collect candidates from all sources
        val candidateSet = mutableSetOf<String>()
        val priorityCandidates = mutableListOf<String>()  // Custom and learned phrases (higher priority)
        val normalCandidates = mutableListOf<String>()    // Dictionary candidates

        for (pinyin in allPossiblePinyins) {
            // 1. Get custom dictionary phrases (highest priority)
            val customPhrases = customDictionary.getPinyinPhrases(pinyin)
            for (phrase in customPhrases) {
                if (phrase !in candidateSet) {
                    candidateSet.add(phrase)
                    priorityCandidates.add(phrase)
                }
            }

            // 2. Get auto-learned phrases
            if (isAutoPhraseMemoryEnabled()) {
                val learnedPhrases = autoPhraseMemory.getLearnedPhrases(pinyin)
                for (phrase in learnedPhrases) {
                    if (phrase !in candidateSet) {
                        candidateSet.add(phrase)
                        priorityCandidates.add(phrase)
                    }
                }

                // Also get partial matches (phrases starting with this pinyin)
                val partialPhrases = autoPhraseMemory.getLearnedPhrasesWithPrefix(pinyin)
                for (phrase in partialPhrases) {
                    if (phrase !in candidateSet) {
                        candidateSet.add(phrase)
                        priorityCandidates.add(phrase)
                    }
                }
            }

            // 3. Get phrase candidates from dictionary
            val phraseCandidates = PinyinDictionary.getPhraseCandidates(pinyin)
            for (p in phraseCandidates) {
                if (p !in candidateSet) {
                    candidateSet.add(p)
                    normalCandidates.add(p)
                }
            }

            // 4. Get single character candidates
            val charCandidates = PinyinDictionary.getCandidates(pinyin)
            for (c in charCandidates) {
                if (c !in candidateSet) {
                    candidateSet.add(c)
                    normalCandidates.add(c)
                }
            }
        }

        // Combine: priority candidates first, then normal candidates
        val candidateList = mutableListOf<String>()
        candidateList.addAll(priorityCandidates)
        candidateList.addAll(normalCandidates)

        // Sort by user frequency if enabled
        allCandidates = if (SettingsManager.getMemoryFunctionEnabled(context) && allPossiblePinyins.isNotEmpty()) {
            userMemory.sortByFrequency(allPossiblePinyins.first(), candidateList)
        } else {
            candidateList
        }

        Log.d(TAG, "T9 candidates: ${allCandidates.size} (${priorityCandidates.size} priority), possible pinyins: $possiblePinyins")
    }

    /**
     * Generates all possible pinyin combinations for T9 digit segments.
     * Uses intelligent parsing to find valid pinyin syllables.
     */
    private fun generatePossiblePinyins(segments: List<String>): List<String> {
        if (segments.isEmpty()) return emptyList()

        // For single segment, generate all valid pinyin interpretations
        if (segments.size == 1) {
            return generatePinyinsForDigits(segments[0])
        }

        // For multiple segments, combine the best interpretation from each
        val segmentPinyins = segments.map { generatePinyinsForDigits(it) }

        // Generate combinations (limited)
        val combinations = mutableListOf<String>()
        generateCombinations(segmentPinyins, 0, "", combinations, maxResults = 20)

        return combinations
    }

    /**
     * Generates valid pinyin interpretations for a T9 digit sequence.
     */
    private fun generatePinyinsForDigits(digits: String): List<String> {
        if (digits.isEmpty()) return emptyList()

        // Generate all letter combinations
        val letterCombos = generateLetterCombinations(digits)

        // Filter to valid pinyins
        val validPinyins = mutableListOf<String>()

        for (combo in letterCombos) {
            // Check if it's a valid single syllable
            if (PinyinDictionary.getCandidates(combo).isNotEmpty()) {
                validPinyins.add(combo)
            }

            // Try to parse as multiple syllables
            val syllables = parseSyllables(combo)
            if (syllables.isNotEmpty() && syllables.joinToString("") == combo) {
                val combined = syllables.joinToString("")
                if (combined !in validPinyins) {
                    validPinyins.add(combined)
                }
            }
        }

        // If no valid full matches, try prefix matching
        if (validPinyins.isEmpty()) {
            for (combo in letterCombos.take(50)) {
                val prefixCandidates = PinyinDictionary.getCandidatesForPrefix(combo)
                if (prefixCandidates.isNotEmpty() && combo !in validPinyins) {
                    validPinyins.add(combo)
                }
            }
        }

        return validPinyins.distinct().take(20)
    }

    /**
     * Generates all possible letter combinations for T9 digits.
     * Limited to prevent explosion for long inputs.
     */
    private fun generateLetterCombinations(digits: String, maxResults: Int = 100): List<String> {
        if (digits.isEmpty()) return listOf("")

        val results = mutableListOf<String>()
        generateLetterCombosRecursive(digits, 0, "", results, maxResults)
        return results
    }

    private fun generateLetterCombosRecursive(
        digits: String,
        index: Int,
        current: String,
        results: MutableList<String>,
        maxResults: Int
    ) {
        if (results.size >= maxResults) return

        if (index == digits.length) {
            results.add(current)
            return
        }

        val digit = digits[index]
        val letters = T9_MAPPING[digit] ?: return

        for (letter in letters) {
            generateLetterCombosRecursive(digits, index + 1, current + letter, results, maxResults)
        }
    }

    /**
     * Parses a string into valid pinyin syllables using longest-match.
     */
    private fun parseSyllables(input: String): List<String> {
        val syllables = mutableListOf<String>()
        var remaining = input

        while (remaining.isNotEmpty()) {
            val syllable = PinyinDictionary.findLongestSyllable(remaining)
            if (syllable != null) {
                syllables.add(syllable)
                remaining = remaining.substring(syllable.length)
            } else {
                // Can't parse further
                break
            }
        }

        return syllables
    }

    /**
     * Generates combinations from multiple lists.
     */
    private fun generateCombinations(
        lists: List<List<String>>,
        index: Int,
        current: String,
        results: MutableList<String>,
        maxResults: Int
    ) {
        if (results.size >= maxResults) return

        if (index == lists.size) {
            if (current.isNotEmpty()) {
                results.add(current)
            }
            return
        }

        val currentList = lists[index]
        if (currentList.isEmpty()) {
            generateCombinations(lists, index + 1, current, results, maxResults)
        } else {
            for (item in currentList.take(3)) {
                generateCombinations(lists, index + 1, current + item, results, maxResults)
            }
        }
    }

    /**
     * Gets the current page's candidates.
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
     * Gets total number of pages.
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
     * Navigates to next page.
     */
    fun nextPage(): Boolean {
        val totalPages = getTotalPages()
        if (currentPage < totalPages - 1) {
            currentPage++
            return true
        }
        return false
    }

    /**
     * Navigates to previous page.
     */
    fun prevPage(): Boolean {
        if (currentPage > 0) {
            currentPage--
            return true
        }
        return false
    }

    /**
     * Gets current state snapshot.
     */
    fun getSnapshot(): Snapshot {
        val currentPageCandidates = getCurrentPageCandidates()
        val totalPages = getTotalPages()

        // Convert to traditional if needed
        val useTraditional = SettingsManager.isTraditionalChineseMode(context)
        val convertedCandidates = ChineseCharacterConverter.convertCandidates(currentPageCandidates, useTraditional)

        // Create display buffer showing T9 digits
        val displayBuffer = digitBuffer.toString().map { digit ->
            if (digit == '1') "'" else digit.toString()
        }.joinToString("")

        return Snapshot(
            isActive = isT9ModeActive,
            buffer = digitBuffer.toString(),
            displayBuffer = displayBuffer,
            candidates = convertedCandidates,
            hasCandidates = allCandidates.isNotEmpty(),
            currentPage = currentPage,
            totalPages = totalPages,
            hasNextPage = currentPage < totalPages - 1,
            hasPrevPage = currentPage > 0,
            possiblePinyins = possiblePinyins
        )
    }

    /**
     * Gets the buffer content (raw T9 digits).
     */
    fun getBuffer(): String = digitBuffer.toString()

    /**
     * Gets the display text for composing - shows the first possible pinyin interpretation.
     * If no valid pinyin found, returns the raw digits.
     */
    fun getDisplayBuffer(): String {
        return if (possiblePinyins.isNotEmpty()) {
            possiblePinyins.first()
        } else {
            digitBuffer.toString()
        }
    }

    /**
     * Checks if there are candidates.
     */
    fun hasCandidates(): Boolean = allCandidates.isNotEmpty()

    /**
     * Gets all candidates.
     */
    fun getAllCandidates(): List<String> = allCandidates

    /**
     * Gets the current page index.
     */
    fun getCurrentPage(): Int = currentPage

    /**
     * Restores the buffer content.
     */
    fun restoreBuffer(buffer: String) {
        digitBuffer.clear()
        digitBuffer.append(buffer)
        updateCandidates()
    }

    /**
     * Restores candidates for Alt double-click next page functionality.
     */
    fun restoreCandidatesForNextPage(candidates: List<String>, page: Int) {
        allCandidates = candidates.toMutableList()
        currentPage = page
    }

    /**
     * Converts a display character to its T9 digit equivalent for display.
     */
    fun getT9DisplayForDigit(digit: Char): String {
        return when (digit) {
            '2' -> "2(ABC)"
            '3' -> "3(DEF)"
            '4' -> "4(GHI)"
            '5' -> "5(JKL)"
            '6' -> "6(MNO)"
            '7' -> "7(PQRS)"
            '8' -> "8(TUV)"
            '9' -> "9(WXYZ)"
            '1' -> "'"
            else -> digit.toString()
        }
    }
}
