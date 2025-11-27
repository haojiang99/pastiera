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
        private const val MAX_BUFFER_LENGTH = 50 // Maximum pinyin buffer length (increased for sentences)
        private const val PAGE_SIZE = 9  // Number of candidates per page
        private const val SEPARATOR = '\'' // Apostrophe separator for disambiguating syllables (e.g., he'ni = 和你)
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
     * For combined phrase candidates, consumes the entire buffer.
     * For single-character candidates, consumes only the first syllable.
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
        val isPhrase = actualIndex < phraseCandidateCount
        val pinyinToConsume: String

        if (isPhrase) {
            // Combined phrase candidate - consume the entire matched buffer (all segments)
            pinyinToConsume = matchedPinyin
            // Record learning for the combined pinyin
            userMemory.recordSelection(pinyinToConsume, selected)
        } else {
            // Single-character candidate - consume only the first syllable
            pinyinToConsume = firstSyllable
            // Record learning for just the first syllable
            userMemory.recordSelection(pinyinToConsume, selected)
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

        // Update candidates for the remaining buffer
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

        // Parse the buffer into segments (using backtick delimiters or auto-parsing)
        parsedSegments = parseBufferIntoSegments(bufferStr)

        if (parsedSegments.isEmpty()) {
            // Couldn't parse - show prefix matches for partial input
            handleUnparsableInput(bufferStr)
            return
        }

        // Generate combined candidates from all segments
        generateCombinedCandidates(bufferStr, parsedSegments)
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
     * Auto-parses a segment (without separators) into syllables using longest-match.
     * Returns a list of ParsedSegments for each syllable found.
     */
    private fun autoParseSegment(segment: String): List<ParsedSegment> {
        if (segment.isEmpty()) return emptyList()

        val segments = mutableListOf<ParsedSegment>()
        var remaining = segment.lowercase()

        while (remaining.isNotEmpty()) {
            // First, check if there's a phrase match for the entire remaining input
            val phraseCandidates = PinyinDictionary.getPhraseCandidates(remaining)
            if (phraseCandidates.isNotEmpty()) {
                // Found a phrase match - use it as a single segment
                segments.add(ParsedSegment(
                    pinyin = remaining,
                    candidates = userMemory.sortByFrequency(remaining, phraseCandidates),
                    isComplete = true
                ))
                break
            }

            // Try longest-match syllable
            val syllable = PinyinDictionary.findLongestSyllable(remaining)
            if (syllable != null) {
                val candidates = PinyinDictionary.getCandidates(syllable)
                segments.add(ParsedSegment(
                    pinyin = syllable,
                    candidates = userMemory.sortByFrequency(syllable, candidates),
                    isComplete = true
                ))
                remaining = remaining.substring(syllable.length)
            } else {
                // Can't parse as complete syllable - might be partial input
                // Try prefix matching for the remaining text
                val prefixCandidates = PinyinDictionary.getCandidatesForPrefix(remaining)
                if (prefixCandidates.isNotEmpty()) {
                    val firstSyl = PinyinDictionary.getFirstSyllableForPrefix(remaining) ?: remaining
                    segments.add(ParsedSegment(
                        pinyin = remaining,
                        candidates = userMemory.sortByFrequency(remaining, prefixCandidates),
                        isComplete = false
                    ))
                }
                break
            }
        }

        return segments
    }

    /**
     * Generates combined candidates from multiple segments.
     * Shows the combined phrase first, then individual character options for first syllable.
     */
    private fun generateCombinedCandidates(fullBuffer: String, segments: List<ParsedSegment>) {
        if (segments.isEmpty()) {
            allCandidates = emptyList()
            matchedPinyin = ""
            phraseCandidateCount = 0
            firstSyllable = ""
            return
        }

        // Find the actual first syllable (not phrase) for single-character fallback
        val bufferWithoutSep = fullBuffer.replace(SEPARATOR.toString(), "")
        val actualFirstSyllable = PinyinDictionary.findLongestSyllable(bufferWithoutSep) ?: ""

        // Get single-character candidates for the first syllable (always needed as fallback)
        val firstSyllableCharCandidates: List<String>
        if (actualFirstSyllable.isNotEmpty()) {
            firstSyllable = actualFirstSyllable
            val rawCandidates = PinyinDictionary.getCandidates(actualFirstSyllable)
            firstSyllableCharCandidates = userMemory.sortByFrequency(actualFirstSyllable, rawCandidates)
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
                    firstSyllableCharCandidates = userMemory.sortByFrequency(bufferWithoutSep, prefixCandidates)
                } else {
                    firstSyllable = ""
                    firstSyllableCharCandidates = emptyList()
                }
            }
        }

        // Check if all segments have candidates
        val allHaveCandidates = segments.all { it.candidates.isNotEmpty() }

        if (!allHaveCandidates && firstSyllableCharCandidates.isEmpty()) {
            // No candidates at all - fall back to single segment mode
            handleSingleSegmentFallback(segments)
            return
        }

        val resultCandidates = mutableListOf<String>()

        // If we have multiple segments, create combined phrase candidates
        if (segments.size > 1) {
            // Generate combined phrases by taking top candidates from each segment
            val combinedPhrases = generateCombinedPhrases(segments)
            resultCandidates.addAll(combinedPhrases)
            phraseCandidateCount = combinedPhrases.size

            // Calculate the pinyin that will be consumed for phrase candidates
            // (all segments combined, without separators)
            matchedPinyin = segments.joinToString("") { it.pinyin }

            Log.d(TAG, "Multi-segment: ${segments.map { it.pinyin }} → ${combinedPhrases.size} combined phrases")
        } else {
            // Single segment - check for dictionary phrase first
            val phraseCandidates = PinyinDictionary.getPhraseCandidates(bufferWithoutSep)
            if (phraseCandidates.isNotEmpty()) {
                val sortedPhrases = userMemory.sortByFrequency(bufferWithoutSep, phraseCandidates)
                resultCandidates.addAll(sortedPhrases)
                phraseCandidateCount = sortedPhrases.size
                matchedPinyin = bufferWithoutSep
            } else {
                phraseCandidateCount = 0
                matchedPinyin = actualFirstSyllable
            }
        }

        // Always add single-character candidates for the first syllable as fallback
        // This ensures user can still type character-by-character even when phrases are shown
        for (candidate in firstSyllableCharCandidates) {
            if (candidate !in resultCandidates) {
                resultCandidates.add(candidate)
            }
        }

        allCandidates = resultCandidates
        Log.d(TAG, "Final candidates: ${allCandidates.size} (${phraseCandidateCount} phrases, ${firstSyllableCharCandidates.size} single chars for '$actualFirstSyllable')")
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
     * Falls back to prefix matching.
     */
    private fun handleUnparsableInput(bufferStr: String) {
        val cleanBuffer = bufferStr.replace(SEPARATOR.toString(), "")
        val prefixCandidates = PinyinDictionary.getCandidatesForPrefix(cleanBuffer)

        if (prefixCandidates.isNotEmpty()) {
            allCandidates = userMemory.sortByFrequency(cleanBuffer, prefixCandidates)
            matchedPinyin = PinyinDictionary.getFirstSyllableForPrefix(cleanBuffer) ?: cleanBuffer
            firstSyllable = matchedPinyin
            phraseCandidateCount = 0
            Log.d(TAG, "Prefix fallback: $cleanBuffer → ${allCandidates.size} candidates")
        } else {
            allCandidates = emptyList()
            matchedPinyin = ""
            firstSyllable = ""
            phraseCandidateCount = 0
            Log.d(TAG, "No match for: $bufferStr")
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
            Log.d(TAG, "Single segment fallback: ${firstSegment.pinyin} → ${allCandidates.size} candidates")
        } else {
            allCandidates = emptyList()
            matchedPinyin = ""
            firstSyllable = ""
            phraseCandidateCount = 0
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
