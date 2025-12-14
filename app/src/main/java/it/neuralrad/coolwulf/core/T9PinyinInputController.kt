package it.neuralrad.coolwulf.core

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import it.neuralrad.coolwulf.data.pinyin.ChineseCharacterConverter
import it.neuralrad.coolwulf.data.pinyin.PinyinDictionary
import it.neuralrad.coolwulf.data.pinyin.UserPinyinMemory
import it.neuralrad.coolwulf.SettingsManager

/**
 * T9 (九宫格) Pinyin input controller for single-handed Chinese input.
 *
 * Uses the standard T9 key mapping:
 * - 2 (W) = ABC
 * - 3 (E) = DEF
 * - 4 (R) = GHI
 * - 5 (S) = JKL
 * - 6 (D) = MNO
 * - 7 (F) = PQRS
 * - 8 (X) = TUV
 * - 9 (C) = WXYZ
 *
 * Also supports letter keys W/E/R/S/D/F/X/C/V mapping to 2-9.
 * Key 1 (V) is used for syllable separator (').
 */
class T9PinyinInputController(
    private val context: Context
) {
    companion object {
        private const val TAG = "T9PinyinInput"
        private const val MAX_BUFFER_LENGTH = 20  // Maximum T9 digit buffer length
        private const val DEFAULT_PAGE_SIZE = 9   // Number of candidates per page

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

        // Letter keys to T9 digit mapping (for physical keyboard)
        // W/E/R/S/D/F/X/C/V -> 2/3/4/5/6/7/8/9/1
        private val LETTER_TO_T9 = mapOf(
            'w' to '2', 'W' to '2',
            'e' to '3', 'E' to '3',
            'r' to '4', 'R' to '4',
            's' to '5', 'S' to '5',
            'd' to '6', 'D' to '6',
            'f' to '7', 'F' to '7',
            'x' to '8', 'X' to '8',
            'c' to '9', 'C' to '9',
            'v' to '1', 'V' to '1'  // Separator key
        )

        // Reverse mapping for display
        private val T9_TO_LETTER = mapOf(
            '2' to 'W', '3' to 'E', '4' to 'R',
            '5' to 'S', '6' to 'D', '7' to 'F',
            '8' to 'X', '9' to 'C', '1' to 'V'
        )
    }

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

    // User memory for frequency sorting
    private val userMemory: UserPinyinMemory = UserPinyinMemory.getInstance(context)

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
    }

    /**
     * Enables or disables T9 input mode.
     */
    fun setT9Mode(active: Boolean) {
        if (isT9ModeActive != active) {
            isT9ModeActive = active
            if (!active) {
                clearBuffer()
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
     * Accepts number keys 2-9, or letter keys W/E/R/S/D/F/X/C/V.
     * @param keyCode The Android key code
     * @param char The character if available
     * @return true if the key was handled
     */
    fun handleKeyPress(keyCode: Int, char: Char?): Boolean {
        if (!isT9ModeActive) return false

        // Try to map the input to a T9 digit
        val digit = when {
            // Number keys 2-9
            keyCode == KeyEvent.KEYCODE_2 || char == '2' -> '2'
            keyCode == KeyEvent.KEYCODE_3 || char == '3' -> '3'
            keyCode == KeyEvent.KEYCODE_4 || char == '4' -> '4'
            keyCode == KeyEvent.KEYCODE_5 || char == '5' -> '5'
            keyCode == KeyEvent.KEYCODE_6 || char == '6' -> '6'
            keyCode == KeyEvent.KEYCODE_7 || char == '7' -> '7'
            keyCode == KeyEvent.KEYCODE_8 || char == '8' -> '8'
            keyCode == KeyEvent.KEYCODE_9 || char == '9' -> '9'
            keyCode == KeyEvent.KEYCODE_1 || char == '1' -> '1'  // Separator
            // Letter keys W/E/R/S/D/F/X/C/V
            char != null && LETTER_TO_T9.containsKey(char) -> LETTER_TO_T9[char]!!
            else -> null
        }

        if (digit == null) return false

        // Handle separator key (1 or V)
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

        // Record selection for learning
        if (SettingsManager.getMemoryFunctionEnabled(context) && possiblePinyins.isNotEmpty()) {
            // Record for the most likely pinyin
            userMemory.recordSelection(possiblePinyins.first(), selected)
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

        // Get candidates for all valid pinyins
        val candidateSet = mutableSetOf<String>()
        val candidateList = mutableListOf<String>()

        for (pinyin in allPossiblePinyins) {
            // Get single character candidates
            val charCandidates = PinyinDictionary.getCandidates(pinyin)
            for (c in charCandidates) {
                if (c !in candidateSet) {
                    candidateSet.add(c)
                    candidateList.add(c)
                }
            }

            // Get phrase candidates
            val phraseCandidates = PinyinDictionary.getPhraseCandidates(pinyin)
            for (p in phraseCandidates) {
                if (p !in candidateSet) {
                    candidateSet.add(p)
                    candidateList.add(p)
                }
            }
        }

        // Sort by user frequency if enabled
        allCandidates = if (SettingsManager.getMemoryFunctionEnabled(context) && allPossiblePinyins.isNotEmpty()) {
            userMemory.sortByFrequency(allPossiblePinyins.first(), candidateList)
        } else {
            candidateList
        }

        Log.d(TAG, "T9 candidates: ${allCandidates.size}, possible pinyins: $possiblePinyins")
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
     * Gets total number of pages.
     */
    private fun getTotalPages(): Int {
        return if (allCandidates.isEmpty()) 1 else ((allCandidates.size + pageSize - 1) / pageSize)
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
