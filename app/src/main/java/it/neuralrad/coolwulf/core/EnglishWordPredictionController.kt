package it.neuralrad.coolwulf.core

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputConnection
import it.neuralrad.coolwulf.data.english.EnglishWordDictionary
import it.neuralrad.coolwulf.data.english.UserEnglishMemory
import it.neuralrad.coolwulf.data.NextWordPredictor

/**
 * Manages English word prediction state and generates word suggestions.
 * Tracks the current word being typed and provides completion candidates.
 * Also provides next-word predictions after a word is committed.
 */
class EnglishWordPredictionController(
    private val context: Context
) {
    companion object {
        private const val TAG = "EnglishWordPrediction"
        private const val MIN_PREFIX_LENGTH = 2  // Minimum characters before showing suggestions
        private const val PAGE_SIZE = 3  // Number of suggestions per page (BlackBerry-style: left/middle/right)
        private const val MAX_SUGGESTIONS = 50  // Maximum suggestions to fetch from dictionary
    }

    // All word suggestions (up to MAX_SUGGESTIONS)
    private var allSuggestions: List<String> = emptyList()

    // Current word prefix being typed (lowercase for matching)
    private var currentPrefix: String = ""

    // Original prefix with case preserved
    private var originalPrefix: String = ""

    // Current page (0-indexed)
    private var currentPage: Int = 0

    // Whether word prediction is enabled
    private var isEnabled = true

    // User memory for learning preferences
    private val userMemory: UserEnglishMemory = UserEnglishMemory.getInstance(context)

    // Next-word predictor for suggesting words after commit
    private val nextWordPredictor: NextWordPredictor = NextWordPredictor.getInstance(context)

    // Whether we're currently showing next-word predictions (before user types)
    private var isShowingNextWordPredictions: Boolean = false

    data class Snapshot(
        val prefix: String,
        val suggestions: List<String>,
        val hasSuggestions: Boolean,
        val currentPage: Int = 0,
        val totalPages: Int = 1,
        val hasNextPage: Boolean = false,
        val hasPrevPage: Boolean = false,
        val isNextWordPrediction: Boolean = false  // True when showing next-word predictions
    )

    init {
        // Load dictionary if not already loaded
        if (!EnglishWordDictionary.isLoaded()) {
            EnglishWordDictionary.load(context)
        }
    }

    /**
     * Enables or disables word prediction.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            clearSuggestions()
        }
    }

    /**
     * Returns whether word prediction is enabled.
     */
    fun isEnabled(): Boolean = isEnabled

    /**
     * Updates suggestions based on the current cursor position.
     * Extracts the word being typed from the text before cursor.
     * Shows next-word predictions if user hasn't started typing a new word.
     * @param inputConnection The input connection to read text from
     */
    fun updateFromCursor(inputConnection: InputConnection?) {
        if (!isEnabled || inputConnection == null) {
            clearSuggestions()
            return
        }

        // Get text before cursor to find the current word
        val textBefore = inputConnection.getTextBeforeCursor(50, 0)
        if (textBefore.isNullOrEmpty()) {
            clearSuggestions()
            return
        }

        // Extract the current word (characters since last word boundary)
        val (originalWord, lowercaseWord) = extractCurrentWordWithCase(textBefore.toString())

        // Check if text ends with punctuation (period, comma, etc.)
        // If so, don't show next-word predictions - user is ending a sentence
        val lastChar = textBefore.lastOrNull()
        val isPunctuation = lastChar != null && lastChar in ".,;:!?。，；：！？"

        // If no current word (user just finished a word with space/punctuation),
        // record the previous word for learning and show next-word predictions
        if (lowercaseWord.isEmpty() || lowercaseWord.length < MIN_PREFIX_LENGTH) {
            // If punctuation was just typed, clear predictions and don't show new ones
            if (isPunctuation) {
                nextWordPredictor.clearState()
                clearSuggestions()
                Log.d(TAG, "Cleared predictions due to punctuation: '$lastChar'")
                return
            }

            // Extract and record the previous word for learning
            val previousWord = extractPreviousWord(textBefore.toString())
            if (previousWord.length >= MIN_PREFIX_LENGTH) {
                // Only record if we haven't just recorded this word
                // (avoid double-recording from multiple updateFromCursor calls)
                nextWordPredictor.recordCommittedWord(previousWord)
            }

            // Check if we have next-word predictions to show
            if (nextWordPredictor.isShowingPredictions()) {
                val nextWordSuggestions = nextWordPredictor.getSuggestions()
                if (nextWordSuggestions.isNotEmpty()) {
                    isShowingNextWordPredictions = true
                    currentPrefix = ""
                    originalPrefix = ""
                    allSuggestions = nextWordSuggestions
                    currentPage = 0
                    Log.d(TAG, "Showing next-word predictions: $nextWordSuggestions")
                    return
                }
            }
            clearSuggestions()
            return
        }

        // User started typing - switch to normal prefix-based suggestions
        if (isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            nextWordPredictor.onUserStartedTyping()
        }

        // Only update if prefix changed
        if (lowercaseWord != currentPrefix) {
            currentPrefix = lowercaseWord
            originalPrefix = originalWord
            currentPage = 0  // Reset to first page when prefix changes
            // Get suggestions from dictionary and sort by user frequency
            val rawSuggestions = EnglishWordDictionary.getSuggestions(lowercaseWord, MAX_SUGGESTIONS)
            allSuggestions = userMemory.sortByFrequency(lowercaseWord, rawSuggestions)
            Log.d(TAG, "Prefix: '$originalWord' (lowercase: '$lowercaseWord'), total suggestions: ${allSuggestions.size} (sorted by frequency)")
        }
    }

    /**
     * Extracts the current word being typed from text with case preserved.
     * Returns a pair of (originalWord, lowercaseWord).
     * Characters since the last word boundary (space, punctuation, etc.)
     */
    private fun extractCurrentWordWithCase(text: String): Pair<String, String> {
        if (text.isEmpty()) return Pair("", "")

        // Find the last word boundary
        var wordStart = text.length
        for (i in text.length - 1 downTo 0) {
            val char = text[i]
            if (!char.isLetter()) {
                wordStart = i + 1
                break
            }
            if (i == 0) {
                wordStart = 0
            }
        }

        val originalWord = text.substring(wordStart)
        val lowercaseWord = originalWord.lowercase()
        return Pair(originalWord, lowercaseWord)
    }

    /**
     * Extracts the previous word (just completed) from text.
     * This is the word before the last word boundary.
     * @param text The text before the cursor
     * @return The previous word, or empty string if not found
     */
    private fun extractPreviousWord(text: String): String {
        if (text.isEmpty()) return ""

        // Find the end of the previous word (skip trailing spaces/punctuation)
        var wordEnd = text.length - 1
        while (wordEnd >= 0 && !text[wordEnd].isLetter()) {
            wordEnd--
        }
        if (wordEnd < 0) return ""

        // Find the start of the previous word
        var wordStart = wordEnd
        while (wordStart > 0 && text[wordStart - 1].isLetter()) {
            wordStart--
        }

        return text.substring(wordStart, wordEnd + 1).lowercase()
    }

    /**
     * Applies the case pattern from the original prefix to a suggestion word.
     * For example: "He" + "hello" -> "Hello", "HE" + "hello" -> "HELLO"
     */
    private fun applyCasePattern(suggestion: String): String {
        if (originalPrefix.isEmpty() || suggestion.isEmpty()) {
            return suggestion
        }

        // Check if original prefix is all uppercase
        val allUppercase = originalPrefix.all { it.isUpperCase() }
        if (allUppercase) {
            return suggestion.uppercase()
        }

        // Check if first character is uppercase
        val firstCharUppercase = originalPrefix.first().isUpperCase()
        if (firstCharUppercase) {
            return suggestion.replaceFirstChar { it.uppercase() }
        }

        // Default: return as-is (lowercase)
        return suggestion
    }

    /**
     * Selects a suggestion by index and returns the text to insert.
     * @param index The suggestion index on current page (0-based)
     * @return The completion text (the part to add after the prefix), or null if invalid
     */
    fun selectSuggestion(index: Int): SelectionResult? {
        val currentPageSuggestions = getCurrentPageSuggestions()
        if (index < 0 || index >= currentPageSuggestions.size) {
            return null
        }

        val selectedWord = currentPageSuggestions[index]
        // Apply the case pattern from the original prefix to the suggestion
        val casedWord = applyCasePattern(selectedWord)
        val prefixLength = if (isShowingNextWordPredictions) 0 else originalPrefix.length

        Log.d(TAG, "Selected suggestion $index: '$casedWord' (original: '$selectedWord'), prefix: '$originalPrefix', isNextWord: $isShowingNextWordPredictions")

        // Record the selection in user memory for learning (only for prefix-based suggestions)
        if (!isShowingNextWordPredictions && currentPrefix.isNotEmpty()) {
            userMemory.recordSelection(currentPrefix, selectedWord)
        }

        // Record the committed word for next-word prediction learning
        nextWordPredictor.recordCommittedWord(selectedWord)

        // Clear the next-word prediction state since we're selecting a word
        isShowingNextWordPredictions = false

        // Return both the word and how many chars to delete
        return SelectionResult(
            word = casedWord,
            prefixLength = prefixLength,
            isNextWordPrediction = prefixLength == 0 && isShowingNextWordPredictions
        )
    }

    /**
     * Records a word that was committed (typed normally without selecting from suggestions).
     * This helps learn word sequences for next-word prediction.
     */
    fun recordCommittedWord(word: String) {
        if (word.isNotBlank()) {
            nextWordPredictor.recordCommittedWord(word)
            Log.d(TAG, "Recorded committed word: '$word'")
        }
    }

    /**
     * Gets suggestions for the current page.
     */
    private fun getCurrentPageSuggestions(): List<String> {
        val startIndex = currentPage * PAGE_SIZE
        val endIndex = minOf(startIndex + PAGE_SIZE, allSuggestions.size)
        return if (startIndex < allSuggestions.size) {
            allSuggestions.subList(startIndex, endIndex)
        } else {
            emptyList()
        }
    }

    /**
     * Calculates total number of pages.
     */
    private fun getTotalPages(): Int {
        return if (allSuggestions.isEmpty()) 1 else ((allSuggestions.size + PAGE_SIZE - 1) / PAGE_SIZE)
    }

    /**
     * Navigates to the next page of suggestions.
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
     * Navigates to the previous page of suggestions.
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
     * Result of selecting a word suggestion.
     */
    data class SelectionResult(
        val word: String,           // The complete word to insert
        val prefixLength: Int,      // How many characters of the prefix to delete
        val isNextWordPrediction: Boolean = false  // True if this was a next-word prediction
    )

    /**
     * Clears current suggestions.
     */
    fun clearSuggestions() {
        currentPrefix = ""
        originalPrefix = ""
        allSuggestions = emptyList()
        currentPage = 0
        isShowingNextWordPredictions = false
    }

    /**
     * Clears next-word prediction state (called when input field changes).
     */
    fun clearNextWordPredictions() {
        nextWordPredictor.clearState()
        isShowingNextWordPredictions = false
    }

    /**
     * Called when user inputs punctuation (period, comma, etc.).
     * Clears next-word predictions since punctuation ends the phrase context.
     */
    fun onPunctuationInput() {
        if (isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            allSuggestions = emptyList()
            currentPage = 0
            Log.d(TAG, "Cleared next-word predictions due to punctuation input")
        }
        // Also clear the predictor state so next sentence starts fresh
        nextWordPredictor.clearState()
    }

    /**
     * Called when user presses backspace while showing next-word predictions.
     * Clears the predictions so the user can continue editing.
     * @return true if predictions were cleared, false if no predictions were showing
     */
    fun onBackspaceInput(): Boolean {
        if (isShowingNextWordPredictions) {
            isShowingNextWordPredictions = false
            allSuggestions = emptyList()
            currentPage = 0
            nextWordPredictor.clearState()
            Log.d(TAG, "Cleared next-word predictions due to backspace input")
            return true
        }
        return false
    }

    /**
     * Gets current state snapshot for UI updates.
     */
    fun getSnapshot(): Snapshot {
        val currentPageSuggestions = getCurrentPageSuggestions()
        // Apply case pattern to all suggestions for display (only for prefix-based suggestions)
        val casedSuggestions = if (isShowingNextWordPredictions) {
            currentPageSuggestions  // Next-word predictions don't need case adjustment
        } else {
            currentPageSuggestions.map { applyCasePattern(it) }
        }
        val totalPages = getTotalPages()
        return Snapshot(
            prefix = originalPrefix,
            suggestions = casedSuggestions,
            hasSuggestions = allSuggestions.isNotEmpty(),
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
     * Gets the current page's suggestions.
     */
    fun getSuggestions(): List<String> = getCurrentPageSuggestions()

    /**
     * Checks if there are any suggestions available.
     */
    fun hasSuggestions(): Boolean = allSuggestions.isNotEmpty()

    /**
     * Gets the current prefix being typed (with original case preserved).
     */
    fun getCurrentPrefix(): String = originalPrefix

    /**
     * Checks if word prediction is currently active.
     */
    fun hasActivePrediction(): Boolean = allSuggestions.isNotEmpty()

    /**
     * Checks if there is a next page of suggestions.
     */
    fun hasNextPage(): Boolean {
        val totalPages = getTotalPages()
        return currentPage < totalPages - 1
    }

    /**
     * Checks if there is a previous page of suggestions.
     */
    fun hasPrevPage(): Boolean = currentPage > 0
}
