package it.neuralrad.coolwulf.core

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputConnection
import it.neuralrad.coolwulf.data.english.EnglishWordDictionary

/**
 * Manages English word prediction state and generates word suggestions.
 * Tracks the current word being typed and provides completion candidates.
 */
class EnglishWordPredictionController(
    private val context: Context
) {
    companion object {
        private const val TAG = "EnglishWordPrediction"
        private const val MIN_PREFIX_LENGTH = 2  // Minimum characters before showing suggestions
        private const val PAGE_SIZE = 5  // Number of suggestions per page
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

    data class Snapshot(
        val prefix: String,
        val suggestions: List<String>,
        val hasSuggestions: Boolean,
        val currentPage: Int = 0,
        val totalPages: Int = 1,
        val hasNextPage: Boolean = false,
        val hasPrevPage: Boolean = false
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

        if (lowercaseWord.length < MIN_PREFIX_LENGTH) {
            clearSuggestions()
            return
        }

        // Only update if prefix changed
        if (lowercaseWord != currentPrefix) {
            currentPrefix = lowercaseWord
            originalPrefix = originalWord
            currentPage = 0  // Reset to first page when prefix changes
            allSuggestions = EnglishWordDictionary.getSuggestions(lowercaseWord, MAX_SUGGESTIONS)
            Log.d(TAG, "Prefix: '$originalWord' (lowercase: '$lowercaseWord'), total suggestions: ${allSuggestions.size}")
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
        val prefixLength = originalPrefix.length

        Log.d(TAG, "Selected suggestion $index: '$casedWord' (original: '$selectedWord'), prefix: '$originalPrefix'")

        // Return both the word and how many chars to delete
        return SelectionResult(
            word = casedWord,
            prefixLength = prefixLength
        )
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
        val prefixLength: Int       // How many characters of the prefix to delete
    )

    /**
     * Clears current suggestions.
     */
    fun clearSuggestions() {
        currentPrefix = ""
        originalPrefix = ""
        allSuggestions = emptyList()
        currentPage = 0
    }

    /**
     * Gets current state snapshot for UI updates.
     */
    fun getSnapshot(): Snapshot {
        val currentPageSuggestions = getCurrentPageSuggestions()
        // Apply case pattern to all suggestions for display
        val casedSuggestions = currentPageSuggestions.map { applyCasePattern(it) }
        val totalPages = getTotalPages()
        return Snapshot(
            prefix = originalPrefix,
            suggestions = casedSuggestions,
            hasSuggestions = allSuggestions.isNotEmpty(),
            currentPage = currentPage,
            totalPages = totalPages,
            hasNextPage = currentPage < totalPages - 1,
            hasPrevPage = currentPage > 0
        )
    }

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
