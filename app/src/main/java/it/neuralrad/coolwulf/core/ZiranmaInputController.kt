package it.neuralrad.coolwulf.core

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import it.neuralrad.coolwulf.data.pinyin.ChineseCharacterConverter
import it.neuralrad.coolwulf.data.pinyin.PinyinDictionary
import it.neuralrad.coolwulf.data.pinyin.UserPinyinMemory
import it.neuralrad.coolwulf.data.ziranma.ZiranmaConverter
import it.neuralrad.coolwulf.data.ziranma.ZiranmaPhraseMemory
import it.neuralrad.coolwulf.data.NextWordPredictor
import it.neuralrad.coolwulf.data.UserCustomDictionary
import it.neuralrad.coolwulf.SettingsManager

/**
 * Manages Ziranma (自然码) input state and generates Chinese character candidates.
 * Uses the 自然码双拼 scheme where each syllable is typed with exactly 2 keys.
 *
 * The input flow:
 * 1. User types 2 keys (e.g., "nl" for "ni")
 * 2. ZiranmaConverter converts to pinyin (e.g., "ni")
 * 3. PinyinDictionary provides character candidates
 * 4. User selects a candidate
 */
class ZiranmaInputController(
    private val context: Context
) {
    companion object {
        private const val TAG = "ZiranmaInputController"
        private const val MAX_BUFFER_LENGTH = 50 // Maximum Ziranma buffer length
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

    // Current Ziranma input buffer
    private var buffer = StringBuilder()

    // All candidates for the current buffer
    private var allCandidates: List<String> = emptyList()

    // Whether Ziranma mode is currently active
    private var isZiranmaModeActive = false

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
        val pinyinPreview: String = ""
    )

    // User memory for learning preferences
    private val userMemory: UserPinyinMemory = UserPinyinMemory.getInstance(context)

    // Next-word predictor for suggesting words after commit
    private val nextWordPredictor: NextWordPredictor = NextWordPredictor.getInstance(context)

    // User custom dictionary for user-defined shortcuts
    private val customDictionary: UserCustomDictionary = UserCustomDictionary.getInstance(context)

    // Phrase memory for learning combined phrases
    private val ziranmaPhraseMemory: ZiranmaPhraseMemory = ZiranmaPhraseMemory.getInstance(context)

    init {
        // Load dictionary if not already loaded
        if (!PinyinDictionary.isLoaded()) {
            PinyinDictionary.load(context)
        }
    }

    /**
     * Enables or disables Ziranma input mode.
     */
    fun setZiranmaMode(active: Boolean) {
        if (isZiranmaModeActive != active) {
            isZiranmaModeActive = active
            if (!active) {
                clearBuffer()
            }
            Log.d(TAG, "========== Ziranma mode: ${if (active) "ENABLED" else "DISABLED"} ==========")
        }
    }

    /**
     * Toggles Ziranma input mode on/off.
     */
    fun toggleZiranmaMode() {
        setZiranmaMode(!isZiranmaModeActive)
    }

    /**
     * Returns whether Ziranma mode is currently active.
     */
    fun isZiranmaMode(): Boolean = isZiranmaModeActive

    /**
     * Processes a letter key press in Ziranma mode.
     */
    fun handleLetterKey(char: Char): Boolean {
        if (!isZiranmaModeActive) {
            return false
        }

        val lowerChar = char.lowercaseChar()
        if (!lowerChar.isLetter() || lowerChar < 'a' || lowerChar > 'z') {
            return false
        }

        if (buffer.length >= MAX_BUFFER_LENGTH) {
            Log.w(TAG, "Buffer full, ignoring input")
            return true
        }

        if (isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            nextWordPredictor.onUserStartedTyping()
        }

        buffer.append(lowerChar)
        updateCandidates()
        Log.d(TAG, "Letter added: '$lowerChar' -> Buffer: '$buffer', Candidates: ${allCandidates.size}")
        return true
    }

    /**
     * Handles backspace in Ziranma mode.
     */
    fun handleBackspace(): Boolean {
        if (!isZiranmaModeActive) {
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
     * Selects a candidate by index on current page.
     */
    fun selectCandidate(index: Int): String? {
        val currentPageCandidates = getCurrentPageCandidates()
        if (!isZiranmaModeActive || index < 0 || index >= currentPageCandidates.size) {
            return null
        }

        val selected = currentPageCandidates[index]
        val actualIndex = currentPage * pageSize + index

        // Determine if this is a phrase (multi-character) or single character
        // Use selected.length > 1 as the primary check since frequency sorting can mix candidates
        val isPhrase = selected.length > 1

        val charsToConsume: Int
        val pinyinToRecord: String

        if (isPhrase) {
            charsToConsume = buffer.length
            pinyinToRecord = matchedPinyin
        } else {
            charsToConsume = minOf(2, buffer.length)
            val consumed = buffer.substring(0, charsToConsume)
            pinyinToRecord = ZiranmaConverter.toPinyin(consumed) ?: consumed
        }

        if (pinyinToRecord.isNotEmpty()) {
            recordSelectionIfEnabled(pinyinToRecord, selected)
        }

        // Record combined phrases for learning
        val ziranmaCodeToRecord = buffer.substring(0, charsToConsume)
        if (isMemoryEnabled() && ziranmaCodeToRecord.isNotEmpty() && selected.length >= 2) {
            // Combined phrase selected - record it directly to phrase memory
            ziranmaPhraseMemory.recordPhrase(ziranmaCodeToRecord, selected)
            Log.d(TAG, "Recorded combined phrase: '$ziranmaCodeToRecord' → '$selected'")
        }

        Log.d(TAG, "Selected candidate $index: '$selected', consuming $charsToConsume chars, pinyin: '$pinyinToRecord'")

        if (charsToConsume > 0 && charsToConsume <= buffer.length) {
            buffer.delete(0, charsToConsume)
        }

        nextWordPredictor.recordCommittedWord(selected)

        if (buffer.isEmpty()) {
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
    }

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
     * Uses effective page size (dynamic display limit in Juying mode) for pagination.
     */
    private fun getTotalPages(): Int {
        val effectivePageSize = getEffectivePageSize()
        return if (allCandidates.isEmpty()) 1 else ((allCandidates.size + effectivePageSize - 1) / effectivePageSize)
    }

    fun nextPage(): Boolean {
        val totalPages = getTotalPages()
        if (currentPage < totalPages - 1) {
            currentPage++
            Log.d(TAG, "Moved to page ${currentPage + 1}/$totalPages")
            return true
        }
        return false
    }

    fun prevPage(): Boolean {
        if (currentPage > 0) {
            currentPage--
            Log.d(TAG, "Moved to page ${currentPage + 1}/${getTotalPages()}")
            return true
        }
        return false
    }

    fun resetPage() {
        currentPage = 0
    }

    fun selectFirstCandidate(): String? {
        return selectCandidate(0)
    }

    fun handleNumberKey(keyCode: Int): String? {
        if (!isZiranmaModeActive || allCandidates.isEmpty()) {
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
     * Converts Ziranma to Pinyin and looks up candidates.
     * Also supports abbreviation matching and combined phrase generation.
     *
     * For multi-syllable input (e.g., "woxlvifj"), generates combined phrase
     * candidates (e.g., "我想吃饭") similar to Pinyin input.
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

        // Check custom dictionary first
        val customPhrases = customDictionary.getShuangpinPhrases(bufferStr)
        if (customPhrases.isNotEmpty()) {
            resultCandidates.addAll(customPhrases)
            Log.d(TAG, "Custom dictionary phrases for '$bufferStr': $customPhrases")
        }

        // Check for abbreviation matches
        val abbreviationCandidates = getAbbreviationCandidates(bufferStr)
        if (abbreviationCandidates.isNotEmpty()) {
            for (candidate in abbreviationCandidates) {
                if (candidate !in resultCandidates) {
                    resultCandidates.add(candidate)
                }
            }
            Log.d(TAG, "Abbreviation matches for '$bufferStr': $abbreviationCandidates")
        }

        // Convert Ziranma to Pinyin syllables
        val syllables = ZiranmaConverter.toPinyinSyllables(bufferStr)
        val pinyinString = ZiranmaConverter.toPinyinString(bufferStr)

        if (pinyinString != null && syllables.isNotEmpty()) {
            matchedPinyin = pinyinString

            // Try to get phrase candidates for the full pinyin from dictionary
            val phraseCandidates = PinyinDictionary.getPhraseCandidates(pinyinString)
            if (phraseCandidates.isNotEmpty()) {
                val sortedPhrases = sortByFrequencyIfEnabled(pinyinString, phraseCandidates)
                for (phrase in sortedPhrases) {
                    if (phrase !in resultCandidates) {
                        resultCandidates.add(phrase)
                    }
                }
            }

            // Generate combined candidates from multiple syllables
            // This allows typing full Ziranma code to get combined phrase suggestions
            if (syllables.size >= 2) {
                val combinedPhrases = generateCombinedCandidates(syllables)
                for (phrase in combinedPhrases) {
                    if (phrase !in resultCandidates) {
                        resultCandidates.add(phrase)
                    }
                }
                Log.d(TAG, "Combined candidates for ${syllables.size} syllables: ${combinedPhrases.take(3)}")
            }

            phraseCandidateCount = resultCandidates.size

            // Also get single character candidates for the first syllable
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

            Log.d(TAG, "Ziranma '$bufferStr' -> Pinyin '$pinyinString', syllables: $syllables, candidates: ${resultCandidates.size}")
        } else {
            if (bufferStr.length >= 2) {
                val directAbbrevCandidates = getAbbreviationCandidatesIfEnabled(bufferStr)
                for (candidate in directAbbrevCandidates) {
                    if (candidate !in resultCandidates) {
                        resultCandidates.add(candidate)
                    }
                }
            }

            if (bufferStr.length == 1) {
                val singleKeyCandidates = getSingleKeyCandidates(bufferStr[0])
                val prefixes = ZiranmaConverter.getPossiblePinyinPrefixes(bufferStr[0])
                matchedPinyin = prefixes.firstOrNull() ?: bufferStr

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
                // Also generate combined candidates for complete syllables
                val completePairs = bufferStr.length / 2
                if (completePairs >= 2) {
                    val completeShuangpin = bufferStr.substring(0, completePairs * 2)
                    val completeSyllables = ZiranmaConverter.toPinyinSyllables(completeShuangpin)
                    if (completeSyllables.size >= 2) {
                        val combinedPhrases = generateCombinedCandidates(completeSyllables)
                        for (phrase in combinedPhrases) {
                            if (phrase !in resultCandidates) {
                                resultCandidates.add(phrase)
                            }
                        }
                        Log.d(TAG, "Combined candidates for partial input ($completePairs complete pairs): ${combinedPhrases.take(3)}")
                    }
                }

                val partialPinyin = getPartialPinyin(bufferStr)
                if (partialPinyin.isNotEmpty()) {
                    matchedPinyin = partialPinyin

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

                Log.d(TAG, "Partial Ziranma '$bufferStr' -> candidates: ${resultCandidates.size}")
            }
        }

        allCandidates = resultCandidates
    }

    /**
     * Generates combined phrase candidates from multiple pinyin syllables.
     * Takes the top candidates from each syllable and combines them.
     * Sorts results by learned phrase frequency for better suggestions.
     */
    private fun generateCombinedCandidates(
        syllables: List<String>,
        maxPerSyllable: Int = 3,
        maxTotal: Int = 9
    ): List<String> {
        if (syllables.isEmpty()) return emptyList()
        if (syllables.size == 1) {
            return sortByFrequencyIfEnabled(syllables[0], PinyinDictionary.getCandidates(syllables[0])).take(maxTotal)
        }

        val candidateLists = syllables.map { syllable ->
            val candidates = PinyinDictionary.getCandidates(syllable)
            sortByFrequencyIfEnabled(syllable, candidates).take(maxPerSyllable)
        }

        if (candidateLists.any { it.isEmpty() }) {
            Log.d(TAG, "Some syllables have no candidates: ${syllables.zip(candidateLists).filter { it.second.isEmpty() }.map { it.first }}")
            return emptyList()
        }

        // Generate more combined phrases than needed so we can sort by frequency
        val combined = mutableListOf<String>()
        generateCartesianProduct(candidateLists, 0, "", combined, maxTotal * 3)

        // Sort combined phrases by learned frequency
        val sortedCombined = sortCombinedByFrequency(combined)

        return sortedCombined.take(maxTotal)
    }

    /**
     * Sorts combined phrases by their learned frequency from ZiranmaPhraseMemory.
     * Phrases with higher frequency appear first.
     */
    private fun sortCombinedByFrequency(phrases: List<String>): List<String> {
        if (!isMemoryEnabled()) {
            return phrases
        }

        val bufferStr = buffer.toString()

        // Get frequency for each phrase and sort
        val phrasesWithFreq = phrases.mapIndexed { index, phrase ->
            val freq = ziranmaPhraseMemory.getFrequency(bufferStr, phrase)
            // Give unlearned phrases a small base score based on original position
            val effectiveFreq = if (freq > 0) freq * 100 else (phrases.size - index)
            phrase to effectiveFreq
        }

        return phrasesWithFreq
            .sortedByDescending { it.second }
            .map { it.first }
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
            generateCartesianProduct(lists, index + 1, current, result, maxResults)
        } else {
            for (candidate in currentList) {
                if (result.size >= maxResults) break
                generateCartesianProduct(lists, index + 1, current + candidate, result, maxResults)
            }
        }
    }

    private fun getAbbreviationCandidates(input: String): List<String> {
        val candidates = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        val directAbbrev = getAbbreviationCandidatesIfEnabled(input)
        for (candidate in directAbbrev) {
            if (candidate !in seen) {
                seen.add(candidate)
                candidates.add(candidate)
            }
        }

        val pinyinAbbrev = StringBuilder()
        for (char in input) {
            val prefixes = ZiranmaConverter.getPossiblePinyinPrefixes(char)
            val initial = prefixes.firstOrNull() ?: char.toString()
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

    private fun getPartialPinyin(ziranma: String): String {
        if (ziranma.isEmpty()) return ""

        val completePairs = ziranma.length / 2
        val hasPartial = ziranma.length % 2 == 1

        val syllables = mutableListOf<String>()

        for (i in 0 until completePairs) {
            val pair = ziranma.substring(i * 2, i * 2 + 2)
            val pinyin = ZiranmaConverter.toPinyin(pair)
            if (pinyin != null) {
                syllables.add(pinyin)
            }
        }

        if (hasPartial) {
            val lastChar = ziranma.last()
            val prefixes = ZiranmaConverter.getPossiblePinyinPrefixes(lastChar)
            if (prefixes.isNotEmpty()) {
                syllables.add(prefixes[0])
            }
        }

        return syllables.joinToString("")
    }

    private fun getSingleKeyCandidates(key: Char): List<String> {
        val candidates = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        val prefixes = ZiranmaConverter.getPossiblePinyinPrefixes(key)

        for (prefix in prefixes) {
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

    fun getSnapshot(): Snapshot {
        val currentPageCandidates = getCurrentPageCandidates()
        val totalPages = getTotalPages()

        val pinyinPreview = if (buffer.isNotEmpty()) {
            ZiranmaConverter.toPinyinString(buffer.toString()) ?: getPartialPinyin(buffer.toString())
        } else {
            ""
        }

        // Convert candidates to traditional Chinese if setting is enabled
        val useTraditional = SettingsManager.isTraditionalChineseMode(context)
        val convertedCandidates = ChineseCharacterConverter.convertCandidates(currentPageCandidates, useTraditional)

        return Snapshot(
            isActive = isZiranmaModeActive,
            buffer = buffer.toString(),
            candidates = convertedCandidates,
            hasCandidates = allCandidates.isNotEmpty(),
            currentPage = currentPage,
            totalPages = totalPages,
            hasNextPage = currentPage < totalPages - 1,
            hasPrevPage = currentPage > 0,
            isNextWordPrediction = isShowingNextWordPredictions,
            pinyinPreview = pinyinPreview
        )
    }

    fun isShowingNextWordPredictions(): Boolean = isShowingNextWordPredictions

    fun clearNextWordPredictions() {
        nextWordPredictor.clearState()
        isShowingNextWordPredictions = false
        allCandidates = emptyList()
        currentPage = 0
    }

    fun onPunctuationInput() {
        if (isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            allCandidates = emptyList()
            currentPage = 0
        }
        nextWordPredictor.clearState()
    }

    fun getBuffer(): String = buffer.toString()

    fun getCandidates(): List<String> = getCurrentPageCandidates()

    fun hasCandidates(): Boolean = allCandidates.isNotEmpty()

    fun commitBufferAsIs(): String? {
        if (buffer.isEmpty()) {
            return null
        }
        val content = buffer.toString()
        clearBuffer()
        return content
    }

    fun hasNextPage(): Boolean = currentPage < getTotalPages() - 1

    fun getAllCandidates(): List<String> = allCandidates.toList()

    fun getCurrentPage(): Int = currentPage

    /**
     * Gets candidates for the current page.
     * Uses effective page size (dynamic display limit in Juying mode) so hidden
     * candidates are pushed to the next page.
     */
    fun getCurrentPageCandidates(): List<String> {
        val effectivePageSize = getEffectivePageSize()
        val startIndex = currentPage * effectivePageSize
        val endIndex = minOf(startIndex + effectivePageSize, allCandidates.size)
        return if (startIndex < allCandidates.size) {
            allCandidates.subList(startIndex, endIndex)
        } else {
            emptyList()
        }
    }

    fun restoreCandidatesForNextPage(candidates: List<String>, page: Int) {
        allCandidates = candidates
        currentPage = page
        isShowingNextWordPredictions = true
    }

    fun restoreBuffer(content: String) {
        buffer.clear()
        buffer.append(content)
    }

    fun hasPrevPage(): Boolean = currentPage > 0

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

    private fun isMemoryEnabled(): Boolean {
        return SettingsManager.getMemoryFunctionEnabled(context)
    }

    private fun recordSelectionIfEnabled(pinyin: String, selected: String) {
        if (isMemoryEnabled()) {
            userMemory.recordSelection(pinyin, selected)
        }
    }

    private fun sortByFrequencyIfEnabled(pinyin: String, candidates: List<String>): List<String> {
        return if (isMemoryEnabled()) {
            userMemory.sortByFrequency(pinyin, candidates)
        } else {
            candidates
        }
    }

    /**
     * Checks if abbreviation input (首字母) is enabled.
     */
    private fun isAbbreviationInputEnabled(): Boolean {
        return SettingsManager.isAbbreviationInputEnabled(context)
    }

    private fun getAbbreviationCandidatesIfEnabled(input: String): List<String> {
        return if (isMemoryEnabled() && isAbbreviationInputEnabled()) {
            userMemory.getAbbreviationCandidates(input)
        } else {
            emptyList()
        }
    }
}
