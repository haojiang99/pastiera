package it.neuralrad.coolwulf.inputmethod.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import it.neuralrad.coolwulf.R
import it.neuralrad.coolwulf.SettingsActivity
import it.neuralrad.coolwulf.SettingsManager
import it.neuralrad.coolwulf.inputmethod.NotificationHelper
import it.neuralrad.coolwulf.inputmethod.StatusBarController
import it.neuralrad.coolwulf.inputmethod.TextSelectionHelper
import it.neuralrad.coolwulf.inputmethod.VariationButtonHandler
import it.neuralrad.coolwulf.inputmethod.SpeechRecognitionActivity
import kotlin.math.abs
import kotlin.math.max

/**
 * Handles the variations row (suggestions + microphone/settings) rendered above the LED strip.
 */
class VariationBarView(
    private val context: Context
) {
    companion object {
        private const val TAG = "VariationBarView"
    }

    var onVariationSelectedListener: VariationButtonHandler.OnVariationSelectedListener? = null
    var onPinyinCandidateSelectedListener: VariationButtonHandler.OnPinyinCandidateSelectedListener? = null
    var onWubiCandidateSelectedListener: VariationButtonHandler.OnWubiCandidateSelectedListener? = null
    var onShuangpinCandidateSelectedListener: VariationButtonHandler.OnShuangpinCandidateSelectedListener? = null
    var onZhenmaCandidateSelectedListener: VariationButtonHandler.OnZhenmaCandidateSelectedListener? = null
    var onCursorMovedListener: (() -> Unit)? = null
    var onNextPageListener: (() -> Unit)? = null
    var onPrevPageListener: (() -> Unit)? = null
    var onLanguageToggleListener: (() -> Unit)? = null
    var onSymButtonListener: (() -> Unit)? = null
    var onPunctuationToggleListener: (() -> Unit)? = null

    private var wrapper: FrameLayout? = null
    private var symButtonView: TextView? = null
    private var languageToggleButtonView: TextView? = null
    private var punctuationToggleButtonView: TextView? = null
    private var isPinyinModeActive: Boolean = false
    private var isShuangpinModeActive: Boolean = false
    private var isWubiModeActive: Boolean = false
    private var isZhenmaModeActive: Boolean = false
    private var isChinesePunctuationMode: Boolean = true
    private var prevArrowButton: ImageView? = null
    private var nextArrowButton: ImageView? = null
    private var container: LinearLayout? = null
    private var overlay: View? = null
    private var currentVariationsRow: LinearLayout? = null
    private var variationButtons: MutableList<TextView> = mutableListOf()
    private var microphoneButtonView: ImageView? = null
    private var settingsButtonView: ImageView? = null
    private var clipboardButtonView: ImageView? = null
    private var clipboardHistoryPopup: ClipboardHistoryPopup? = null
    private var lastDisplayedVariations: List<String> = emptyList()
    private var isSymModeActive = false
    private var isSwipeInProgress = false
    private var swipeDirection: Int? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastCursorMoveX = 0f
    private var currentInputConnection: android.view.inputmethod.InputConnection? = null
    private var swipeIndicator: View? = null

    fun ensureView(): View {
        if (wrapper != null) {
            return wrapper!!
        }

        val leftPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            64f,
            context.resources.displayMetrics
        ).toInt()
        val rightPadding = (leftPadding * 0.31f).toInt()
        val variationsVerticalPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            8.8f,
            context.resources.displayMetrics
        ).toInt()
        val variationsContainerHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            55f,
            context.resources.displayMetrics
        ).toInt()

        // Container for suggestion buttons and other controls
        container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(leftPadding, variationsVerticalPadding, rightPadding, variationsVerticalPadding)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.VISIBLE
        }

        // Wrapper FrameLayout to hold both container and overlay
        wrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                variationsContainerHeight
            )
            visibility = View.GONE
            addView(container)
        }

        // Transparent overlay for swipe-to-move-cursor functionality
        overlay = FrameLayout(context).apply {
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.VISIBLE
        }.also { overlayView ->
            // Create swipe indicator (yellow gradient bar that follows finger)
            val indicator = createSwipeIndicator()
            swipeIndicator = indicator
            overlayView.addView(indicator)

            wrapper?.addView(overlayView)
            installOverlayTouchListener(overlayView)
        }

        return wrapper!!
    }

    fun getWrapper(): FrameLayout? = wrapper

    fun setSymModeActive(active: Boolean) {
        isSymModeActive = active
        if (active) {
            overlay?.visibility = View.GONE
        }
    }

    fun setPinyinModeActive(active: Boolean) {
        if (isPinyinModeActive != active) {
            isPinyinModeActive = active
            updateLanguageToggleButton()
        }
    }

    fun setShuangpinModeActive(active: Boolean) {
        if (isShuangpinModeActive != active) {
            isShuangpinModeActive = active
            updateLanguageToggleButton()
        }
    }

    fun setWubiModeActive(active: Boolean) {
        if (isWubiModeActive != active) {
            isWubiModeActive = active
            updateLanguageToggleButton()
        }
    }

    fun setZhenmaModeActive(active: Boolean) {
        if (isZhenmaModeActive != active) {
            isZhenmaModeActive = active
            updateLanguageToggleButton()
        }
    }

    fun setChinesePunctuationMode(active: Boolean) {
        if (isChinesePunctuationMode != active) {
            isChinesePunctuationMode = active
            updatePunctuationToggleButton()
        }
    }

    fun updateInputConnection(inputConnection: android.view.inputmethod.InputConnection?) {
        currentInputConnection = inputConnection
    }

    fun resetVariationsState() {
        lastDisplayedVariations = emptyList()
    }

    fun hideImmediate() {
        currentVariationsRow?.let { row ->
            (row.parent as? ViewGroup)?.removeView(row)
        }
        currentVariationsRow = null
        variationButtons.clear()
        removeMicrophoneImmediate()
        removeSettingsImmediate()
        removeClipboardImmediate()
        removeSymButtonImmediate()
        removeLanguageToggleImmediate()
        removeArrowsImmediate()
        hideSwipeIndicator(immediate = true)
        clipboardHistoryPopup?.dismiss()
        container?.visibility = View.GONE
        wrapper?.visibility = View.GONE
        overlay?.visibility = View.GONE
    }

    private fun removeSymButtonImmediate() {
        symButtonView?.let { sym ->
            (sym.parent as? ViewGroup)?.removeView(sym)
            sym.visibility = View.GONE
            sym.alpha = 1f
        }
    }

    private fun removeArrowsImmediate() {
        prevArrowButton?.let { arrow ->
            (arrow.parent as? ViewGroup)?.removeView(arrow)
            arrow.visibility = View.GONE
        }
        nextArrowButton?.let { arrow ->
            (arrow.parent as? ViewGroup)?.removeView(arrow)
            arrow.visibility = View.GONE
        }
    }

    fun hideForSym(onHidden: () -> Unit) {
        val containerView = container ?: run {
            onHidden()
            return
        }
        val row = currentVariationsRow
        val overlayView = overlay

        removeMicrophoneImmediate()
        removeSettingsImmediate()
        removeClipboardImmediate()
        removeSymButtonImmediate()
        removeLanguageToggleImmediate()
        removeArrowsImmediate()
        hideSwipeIndicator(immediate = true)
        clipboardHistoryPopup?.dismiss()

        if (row != null && row.parent == containerView && row.visibility == View.VISIBLE) {
            animateVariationsOut(row) {
                (row.parent as? ViewGroup)?.removeView(row)
                if (currentVariationsRow == row) {
                    currentVariationsRow = null
                }
                containerView.visibility = View.GONE
                wrapper?.visibility = View.GONE
                overlayView?.visibility = View.GONE
                onHidden()
            }
        } else {
            currentVariationsRow = null
            containerView.visibility = View.GONE
            wrapper?.visibility = View.GONE
            overlayView?.visibility = View.GONE
            onHidden()
        }
    }

    fun showVariations(snapshot: StatusBarController.StatusSnapshot, inputConnection: android.view.inputmethod.InputConnection?) {
        val containerView = container ?: return
        val wrapperView = wrapper ?: return

        currentInputConnection = inputConnection
        wrapperView.visibility = View.VISIBLE
        containerView.visibility = View.VISIBLE
        // Show overlay for swipe functionality (hidden during SYM mode)
        if (!isSymModeActive) {
            overlay?.visibility = View.VISIBLE
        }

        // In Juying mode with suggestions, remove container padding to allow full-width buttons
        val isJuyingWithSuggestions = snapshot.isJuyingMode && snapshot.variations.isNotEmpty()
        val verticalPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            8.8f,
            context.resources.displayMetrics
        ).toInt()
        if (isJuyingWithSuggestions) {
            // Remove horizontal padding in Juying mode for full-width buttons
            containerView.setPadding(0, verticalPadding, 0, verticalPadding)
        } else {
            // Restore normal padding when not in Juying mode or no suggestions
            val normalLeftPadding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                64f,
                context.resources.displayMetrics
            ).toInt()
            val normalRightPadding = (normalLeftPadding * 0.31f).toInt()
            containerView.setPadding(normalLeftPadding, verticalPadding, normalRightPadding, verticalPadding)
        }

        // Show variations if we have any:
        // - For accents: need lastInsertedChar
        // - For Pinyin: always show if pinyinModeActive
        // - For word prediction: always show if wordPredictionActive
        val variationsChanged = snapshot.variations != lastDisplayedVariations
        val hasExistingRow = currentVariationsRow != null &&
            currentVariationsRow?.parent == containerView &&
            currentVariationsRow?.visibility == View.VISIBLE

        if (!variationsChanged && hasExistingRow) {
            return
        }

        // Optimization: Don't remove/recreate views if only updating - reuse existing row
        val reuseExistingRow = hasExistingRow && currentVariationsRow != null

        if (!reuseExistingRow) {
            variationButtons.clear()
            currentVariationsRow?.let {
                (it.parent as? ViewGroup)?.removeView(it)
            }
            currentVariationsRow = null
        } else {
            // Clear existing buttons from row but keep row
            currentVariationsRow?.removeAllViews()
            variationButtons.clear()
        }

        val screenWidth = context.resources.displayMetrics.widthPixels
        val leftPadding = containerView.paddingLeft
        val rightPadding = containerView.paddingRight
        val availableWidth = screenWidth - leftPadding - rightPadding

        val spacingBetweenButtons = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context.resources.displayMetrics
        ).toInt()

        // Calculate button widths dynamically based on text content
        // Show numbered buttons for Pinyin, Shuangpin, Wubi, and Zhenma only (not for English word prediction)
        // In Juying mode, hide numbers - users select with physical keys (Shift/Sym/Space/Ctrl)
        val showNumberedButtons = (snapshot.pinyinModeActive || snapshot.shuangpinModeActive || snapshot.wubiModeActive || snapshot.zhenmaModeActive) && !snapshot.isJuyingMode
        val textSizeSp = if (showNumberedButtons || snapshot.wordPredictionActive) 18f else 17.6f
        val textPaint = android.graphics.Paint().apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                textSizeSp,
                context.resources.displayMetrics
            )
            typeface = android.graphics.Typeface.DEFAULT
        }

        // Calculate minimum width needed for each suggestion
        val minButtonWidthDp = 40f // Minimum button width
        val minButtonWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            minButtonWidthDp,
            context.resources.displayMetrics
        ).toInt()

        val buttonPaddingDp = 8f
        val buttonPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            buttonPaddingDp * 2, // left + right
            context.resources.displayMetrics
        ).toInt()

        // Limit suggestions on pages 2+ to leave room for arrows
        // English word prediction: always 3 per page (BlackBerry-style: left/middle/right)
        // Pinyin: 9 on page 1, 8 on pages 2+
        val maxSuggestionsToShow = if (snapshot.wordPredictionActive) {
            3  // Always show max 3 for English word prediction
        } else if (snapshot.currentPage > 0) {
            8
        } else {
            snapshot.variations.size
        }
        val variationsToProcess = snapshot.variations.take(maxSuggestionsToShow)

        // For English word predictions, stretch buttons to fill screen width evenly
        val isEnglishWordPrediction = snapshot.wordPredictionActive && !snapshot.pinyinModeActive && !snapshot.wubiModeActive && !snapshot.zhenmaModeActive

        // In Juying mode with suggestions, use full screen width for better key alignment
        val isJuyingModeWithSuggestions = snapshot.isJuyingMode && variationsToProcess.isNotEmpty()

        // Calculate required widths for each suggestion
        data class SuggestionLayout(val text: String, val width: Int)
        val suggestionLayouts = mutableListOf<SuggestionLayout>()

        for ((index, variation) in variationsToProcess.withIndex()) {
            val displayText = if (showNumberedButtons) "${index + 1} $variation" else variation
            val textWidth = textPaint.measureText(displayText).toInt()
            val requiredWidth = max(minButtonWidth, textWidth + buttonPadding)
            suggestionLayouts.add(SuggestionLayout(variation, requiredWidth))
        }

        // Determine how many suggestions fit on screen
        var totalWidth = 0
        var limitedVariations = mutableListOf<String>()
        var buttonWidths = mutableListOf<Int>()

        // In Juying mode, use full screen width and divide evenly among candidates
        // This makes button positions align better with physical keys
        val fullScreenWidth = screenWidth  // Use full screen width, no padding

        if (isJuyingModeWithSuggestions && suggestionLayouts.isNotEmpty()) {
            // Juying mode: stretch buttons to fill full screen width
            // Reserve space for arrow buttons if pagination is needed
            val arrowButtonSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                32f,
                context.resources.displayMetrics
            ).toInt()
            val arrowMargin = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                4f,
                context.resources.displayMetrics
            ).toInt()

            // Calculate space needed for arrows
            val prevArrowSpace = if (snapshot.hasPrevPage) arrowButtonSize + arrowMargin else 0
            val nextArrowSpace = if (snapshot.hasNextPage) arrowButtonSize + arrowMargin else 0
            val arrowsSpace = prevArrowSpace + nextArrowSpace

            val numSuggestions = suggestionLayouts.size
            val totalSpacing = (numSuggestions - 1) * spacingBetweenButtons
            // Use full screen width minus arrows for suggestion buttons
            val adjustedWidth = fullScreenWidth - arrowsSpace
            val widthPerButton = (adjustedWidth - totalSpacing) / numSuggestions

            for (i in 0 until numSuggestions) {
                limitedVariations.add(suggestionLayouts[i].text)
                buttonWidths.add(widthPerButton)
            }
            totalWidth = adjustedWidth
        } else if (isEnglishWordPrediction && suggestionLayouts.isNotEmpty()) {
            // For English word predictions, divide screen width evenly among suggestions
            // Reserve space for arrow buttons if pagination is needed
            val arrowButtonSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                32f,
                context.resources.displayMetrics
            ).toInt()
            val arrowMargin = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                4f,
                context.resources.displayMetrics
            ).toInt()

            // Calculate space needed for arrows
            val prevArrowSpace = if (snapshot.hasPrevPage) arrowButtonSize + arrowMargin else 0
            val nextArrowSpace = if (snapshot.hasNextPage) arrowButtonSize + arrowMargin else 0
            val arrowsSpace = prevArrowSpace + nextArrowSpace

            val numSuggestions = minOf(suggestionLayouts.size, 3)
            val totalSpacing = (numSuggestions - 1) * spacingBetweenButtons
            // Subtract arrow space from available width for suggestion buttons
            val adjustedWidth = availableWidth - arrowsSpace
            val widthPerButton = (adjustedWidth - totalSpacing) / numSuggestions

            for (i in 0 until numSuggestions) {
                limitedVariations.add(suggestionLayouts[i].text)
                buttonWidths.add(widthPerButton)
            }
            totalWidth = adjustedWidth
        } else {
            // Original logic for Pinyin/Wubi/character variations
            for (layout in suggestionLayouts) {
                val widthWithSpacing = if (limitedVariations.isEmpty()) layout.width
                                       else layout.width + spacingBetweenButtons
                if (totalWidth + widthWithSpacing <= availableWidth) {
                    limitedVariations.add(layout.text)
                    buttonWidths.add(layout.width)
                    totalWidth += widthWithSpacing
                } else {
                    break
                }
            }

            // If no variations fit, use one with reduced width
            if (limitedVariations.isEmpty() && suggestionLayouts.isNotEmpty()) {
                limitedVariations.add(suggestionLayouts[0].text)
                buttonWidths.add(availableWidth)
            }
        }

        val buttonWidth = if (buttonWidths.isNotEmpty()) buttonWidths[0] else minButtonWidth

        // Calculate explicit width for variationsRow (total width used)
        val variationsRowWidth = totalWidth
        val buttonHeight = if (isEnglishWordPrediction) {
            // For English word predictions, use a fixed height (similar to minButtonWidth)
            minButtonWidth
        } else {
            buttonWidth // Use first button's width as height for square buttons
        }

        // Reuse existing row if available, otherwise create new one
        val variationsRow = if (reuseExistingRow && currentVariationsRow != null) {
            currentVariationsRow!!.apply {
                // Update layout params if size changed
                val lp = layoutParams as? LinearLayout.LayoutParams
                if (lp != null && (lp.width != variationsRowWidth || lp.height != buttonHeight)) {
                    lp.width = variationsRowWidth
                    lp.height = buttonHeight
                    layoutParams = lp
                }
            }
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(variationsRowWidth, buttonHeight)
            }.also {
                currentVariationsRow = it
                containerView.addView(it, 0)
            }
        }

        // Measure and layout the variationsRow
        val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(variationsRowWidth, View.MeasureSpec.EXACTLY)
        val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(buttonHeight, View.MeasureSpec.EXACTLY)
        variationsRow.measure(widthMeasureSpec, heightMeasureSpec)
        variationsRow.layout(0, 0, variationsRowWidth, buttonHeight)

        lastDisplayedVariations = limitedVariations.toList()

        val wordPredictionPrefixLength = if (snapshot.wordPredictionActive) snapshot.wordPredictionPrefix.length else 0
        var buttonX = 0
        for ((index, variation) in limitedVariations.withIndex()) {
            val individualButtonWidth = buttonWidths[index]
            // In Juying mode, determine best candidate based on mode:
            // - Chinese input: best candidate position depends on number of candidates
            //   - 1 candidate: best is at position 0 (only one)
            //   - 2 candidates: [2nd, 1st] -> best is at position 1
            //   - 3 candidates: [2nd, 1st, 3rd] -> best is at position 1 (middle)
            //   - 4+ candidates: [2nd, 3rd, 1st, ...] -> best is at position 2
            // - English word prediction: best candidate is in middle (Sym key selects 1st/best)
            //   - 1 candidate: best is at position 0
            //   - 2 candidates: [2nd, 1st] -> best is at position 1
            //   - 3 candidates: [2nd, 1st, 3rd] -> best is at position 1 (middle)
            val isChineseMode = snapshot.pinyinModeActive || snapshot.shuangpinModeActive ||
                                snapshot.wubiModeActive || snapshot.zhenmaModeActive
            val bestCandidatePosition = when {
                isChineseMode -> when (limitedVariations.size) {
                    1 -> 0  // 1 candidate: best at position 0
                    2 -> 1  // 2 candidates: best at position 1 (right)
                    3 -> 1  // 3 candidates: best at position 1 (middle)
                    else -> 2  // 4+ candidates: best at position 2
                }
                snapshot.wordPredictionActive -> when (limitedVariations.size) {
                    1 -> 0  // 1 candidate: best at position 0
                    else -> 1  // 2+ candidates: best at position 1 (middle)
                }
                else -> -1
            }
            // Highlight best candidate: in Juying mode it's at bestCandidatePosition (reordered),
            // in non-Juying mode it's always at position 0 (first suggestion is best)
            val isBestCandidate = if (snapshot.isJuyingMode) {
                index == bestCandidatePosition
            } else {
                index == 0  // First candidate is always the best in non-Juying mode
            }
            val button = createVariationButton(
                variation, inputConnection, individualButtonWidth, showNumberedButtons, index + 1,
                snapshot.wordPredictionActive, wordPredictionPrefixLength, snapshot.pinyinModeActive,
                snapshot.shuangpinModeActive, snapshot.wubiModeActive, snapshot.zhenmaModeActive, candidateIndex = index,
                isJuyingMode = snapshot.isJuyingMode, isBestCandidate = isBestCandidate
            )
            variationButtons.add(button)
            variationsRow.addView(button)

            // Force measure and layout the button
            val buttonWidthSpec = View.MeasureSpec.makeMeasureSpec(individualButtonWidth, View.MeasureSpec.EXACTLY)
            val buttonHeightSpec = View.MeasureSpec.makeMeasureSpec(buttonHeight, View.MeasureSpec.EXACTLY)
            button.measure(buttonWidthSpec, buttonHeightSpec)
            button.layout(buttonX, 0, buttonX + individualButtonWidth, buttonHeight)
            buttonX += individualButtonWidth + spacingBetweenButtons
        }

        // Force a layout pass
        containerView.requestLayout()

        // Add navigation arrows when in Pinyin, Shuangpin, Wubi, Zhenma, or word prediction mode with suggestions
        val showPagination = (snapshot.pinyinModeActive || snapshot.shuangpinModeActive || snapshot.wubiModeActive || snapshot.zhenmaModeActive || snapshot.wordPredictionActive) &&
                             snapshot.variations.isNotEmpty()

        // Smaller arrow button size
        val arrowButtonSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            32f,
            context.resources.displayMetrics
        ).toInt()

        // Always remove existing arrow buttons first to ensure clean state
        prevArrowButton?.let { arrow ->
            (arrow.parent as? ViewGroup)?.removeView(arrow)
        }
        nextArrowButton?.let { arrow ->
            (arrow.parent as? ViewGroup)?.removeView(arrow)
        }

        if (showPagination) {
            // Previous page arrow (left) - only show if there's a previous page
            if (snapshot.hasPrevPage) {
                val prevArrow = prevArrowButton ?: createArrowButton(arrowButtonSize, isNext = false).also {
                    prevArrowButton = it
                }
                val prevParams = LinearLayout.LayoutParams(arrowButtonSize, arrowButtonSize).apply {
                    marginStart = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        2f,
                        context.resources.displayMetrics
                    ).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                }
                containerView.addView(prevArrow, 0, prevParams)
                prevArrow.alpha = 1f
                prevArrow.isClickable = true
                prevArrow.setOnClickListener { onPrevPageListener?.invoke() }
                prevArrow.visibility = View.VISIBLE
            }

            // Next page arrow (right) - show if there's a next page
            if (snapshot.hasNextPage) {
                val nextArrow = nextArrowButton ?: createArrowButton(arrowButtonSize, isNext = true).also {
                    nextArrowButton = it
                }
                val nextParams = LinearLayout.LayoutParams(arrowButtonSize, arrowButtonSize).apply {
                    marginStart = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        4f,
                        context.resources.displayMetrics
                    ).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                }
                // Insert right after variationsRow (index 0) and prevArrow (if exists)
                val insertIndex = if (snapshot.hasPrevPage) 2 else 1
                containerView.addView(nextArrow, insertIndex, nextParams)
                nextArrow.alpha = 1f
                nextArrow.isClickable = true
                nextArrow.setOnClickListener { onNextPageListener?.invoke() }
                nextArrow.visibility = View.VISIBLE
                // Force layout update
                containerView.requestLayout()
            }
        }

        // Determine if we should stretch buttons (when no suggestions)
        val noSuggestions = snapshot.variations.isEmpty()
        val stretchButtons = noSuggestions

        // In Juying mode with suggestions, hide all status bar icons to give more space to candidates
        val hideStatusBarIconsForJuying = snapshot.isJuyingMode && !noSuggestions

        // Check if voice input button should be shown
        val showVoiceInputButton = SettingsManager.getShowVoiceInputButton(context) && !hideStatusBarIconsForJuying

        // Count visible buttons for weight calculation
        val baseButtonCount = if (isPinyinModeActive || isShuangpinModeActive || isWubiModeActive) 5 else 4
        val visibleButtonCount = if (showVoiceInputButton) baseButtonCount else baseButtonCount - 1

        // Common margin for button spacing
        val buttonMargin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            4f,
            context.resources.displayMetrics
        ).toInt()

        // Microphone button - reuse if already attached
        val microphoneButton = microphoneButtonView ?: createMicrophoneButton(buttonWidth).also {
            microphoneButtonView = it
        }

        if (showVoiceInputButton) {
            if (microphoneButton.parent == null) {
                val micParams = if (stretchButtons) {
                    LinearLayout.LayoutParams(0, buttonWidth, 1f)
                } else {
                    LinearLayout.LayoutParams(buttonWidth, buttonWidth)
                }
                containerView.addView(microphoneButton, micParams)
            } else if (stretchButtons) {
                // Update existing params to use weight
                (microphoneButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = 0
                    weight = 1f
                }
            } else {
                // Restore fixed width
                (microphoneButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = buttonWidth
                    weight = 0f
                }
            }
            microphoneButton.setOnClickListener { startSpeechRecognition(inputConnection) }
            microphoneButton.alpha = 1f
            microphoneButton.visibility = View.VISIBLE
        } else {
            // Hide/remove microphone button when disabled
            if (microphoneButton.parent != null) {
                (microphoneButton.parent as? ViewGroup)?.removeView(microphoneButton)
            }
            microphoneButton.visibility = View.GONE
        }

        // Settings button - reuse if already attached
        val settingsButton = settingsButtonView ?: createStatusBarSettingsButton(buttonWidth).also {
            settingsButtonView = it
        }
        // Settings button has margin only if microphone button is shown (not the first button)
        val settingsMargin = if (showVoiceInputButton) buttonMargin else 0
        if (hideStatusBarIconsForJuying) {
            // Hide settings button in Juying mode with suggestions
            if (settingsButton.parent != null) {
                (settingsButton.parent as? ViewGroup)?.removeView(settingsButton)
            }
            settingsButton.visibility = View.GONE
        } else if (settingsButton.parent == null) {
            val settingsParams = if (stretchButtons) {
                LinearLayout.LayoutParams(0, buttonWidth, 1f).apply {
                    marginStart = settingsMargin
                }
            } else {
                LinearLayout.LayoutParams(buttonWidth, buttonWidth).apply {
                    marginStart = settingsMargin
                }
            }
            containerView.addView(settingsButton, settingsParams)
            settingsButton.setOnClickListener { openSettings() }
            settingsButton.alpha = 1f
            settingsButton.visibility = View.VISIBLE
        } else if (stretchButtons) {
            (settingsButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = 0
                weight = 1f
                marginStart = settingsMargin
            }
            settingsButton.setOnClickListener { openSettings() }
            settingsButton.alpha = 1f
            settingsButton.visibility = View.VISIBLE
        } else {
            (settingsButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = buttonWidth
                weight = 0f
                marginStart = settingsMargin
            }
            settingsButton.setOnClickListener { openSettings() }
            settingsButton.alpha = 1f
            settingsButton.visibility = View.VISIBLE
        }

        // Clipboard button - reuse if already attached
        val showClipboardButton = SettingsManager.getShowClipboardButton(context) && !hideStatusBarIconsForJuying
        val clipboardButton = clipboardButtonView ?: createClipboardButton(buttonWidth).also {
            clipboardButtonView = it
        }
        // Clipboard button has margin if previous buttons are shown
        val clipboardMargin = buttonMargin
        if (showClipboardButton) {
            if (clipboardButton.parent == null) {
                val clipboardParams = if (stretchButtons) {
                    LinearLayout.LayoutParams(0, buttonWidth, 1f).apply {
                        marginStart = clipboardMargin
                    }
                } else {
                    LinearLayout.LayoutParams(buttonWidth, buttonWidth).apply {
                        marginStart = clipboardMargin
                    }
                }
                containerView.addView(clipboardButton, clipboardParams)
            } else if (stretchButtons) {
                (clipboardButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = 0
                    weight = 1f
                    marginStart = clipboardMargin
                }
            } else {
                (clipboardButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = buttonWidth
                    weight = 0f
                    marginStart = clipboardMargin
                }
            }
            clipboardButton.setOnClickListener { showClipboardHistory(it, inputConnection) }
            clipboardButton.alpha = 1f
            clipboardButton.visibility = View.VISIBLE
        } else {
            // Hide/remove clipboard button when disabled or in Juying mode with suggestions
            if (clipboardButton.parent != null) {
                (clipboardButton.parent as? ViewGroup)?.removeView(clipboardButton)
            }
            clipboardButton.visibility = View.GONE
        }

        // SYM button - reuse if already attached
        val symButton = symButtonView ?: createSymButton(buttonWidth).also {
            symButtonView = it
        }
        if (hideStatusBarIconsForJuying) {
            // Hide SYM button in Juying mode with suggestions
            if (symButton.parent != null) {
                (symButton.parent as? ViewGroup)?.removeView(symButton)
            }
            symButton.visibility = View.GONE
        } else if (symButton.parent == null) {
            val symParams = if (stretchButtons) {
                LinearLayout.LayoutParams(0, buttonWidth, 1f).apply {
                    marginStart = buttonMargin
                }
            } else {
                LinearLayout.LayoutParams(buttonWidth, buttonWidth).apply {
                    marginStart = buttonMargin
                }
            }
            containerView.addView(symButton, symParams)
            symButton.setOnClickListener { onSymButtonListener?.invoke() }
            symButton.alpha = 1f
            symButton.visibility = View.VISIBLE
        } else if (stretchButtons) {
            (symButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = 0
                weight = 1f
            }
            symButton.setOnClickListener { onSymButtonListener?.invoke() }
            symButton.alpha = 1f
            symButton.visibility = View.VISIBLE
        } else {
            (symButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = buttonWidth
                weight = 0f
            }
            symButton.setOnClickListener { onSymButtonListener?.invoke() }
            symButton.alpha = 1f
            symButton.visibility = View.VISIBLE
        }

        // Language toggle button (EN/CN) - reuse if already attached
        val languageToggleButton = languageToggleButtonView ?: createLanguageToggleButton(buttonWidth).also {
            languageToggleButtonView = it
        }
        if (hideStatusBarIconsForJuying) {
            // Hide language toggle button in Juying mode with suggestions
            if (languageToggleButton.parent != null) {
                (languageToggleButton.parent as? ViewGroup)?.removeView(languageToggleButton)
            }
            languageToggleButton.visibility = View.GONE
        } else if (languageToggleButton.parent == null) {
            val langParams = if (stretchButtons) {
                LinearLayout.LayoutParams(0, buttonWidth, 1f).apply {
                    marginStart = buttonMargin
                }
            } else {
                LinearLayout.LayoutParams(buttonWidth, buttonWidth).apply {
                    marginStart = buttonMargin
                }
            }
            containerView.addView(languageToggleButton, langParams)
            languageToggleButton.setOnClickListener { onLanguageToggleListener?.invoke() }
            languageToggleButton.alpha = 1f
            languageToggleButton.visibility = View.VISIBLE
        } else if (stretchButtons) {
            (languageToggleButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = 0
                weight = 1f
            }
            languageToggleButton.setOnClickListener { onLanguageToggleListener?.invoke() }
            languageToggleButton.alpha = 1f
            languageToggleButton.visibility = View.VISIBLE
        } else {
            (languageToggleButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = buttonWidth
                weight = 0f
            }
            languageToggleButton.setOnClickListener { onLanguageToggleListener?.invoke() }
            languageToggleButton.alpha = 1f
            languageToggleButton.visibility = View.VISIBLE
        }

        // Punctuation toggle button (中/英 for punctuation) - show right after language toggle in Chinese mode
        // Hide in Juying mode with suggestions
        if (hideStatusBarIconsForJuying) {
            // Hide punctuation toggle in Juying mode with suggestions
            punctuationToggleButtonView?.let { btn ->
                (btn.parent as? ViewGroup)?.removeView(btn)
                btn.visibility = View.GONE
            }
        } else if (isPinyinModeActive || isShuangpinModeActive || isWubiModeActive || isZhenmaModeActive) {
            val punctuationToggleButton = punctuationToggleButtonView ?: createPunctuationToggleButton(buttonWidth).also {
                punctuationToggleButtonView = it
            }

            // Always ensure punctuation button is right after language button
            val langIndex = containerView.indexOfChild(languageToggleButton)
            val punctIndex = containerView.indexOfChild(punctuationToggleButton)
            val expectedPunctIndex = langIndex + 1

            // Remove and re-add if position is wrong or not attached
            if (punctuationToggleButton.parent != null && punctIndex != expectedPunctIndex) {
                (punctuationToggleButton.parent as? ViewGroup)?.removeView(punctuationToggleButton)
            }

            if (punctuationToggleButton.parent == null) {
                val punctParams = if (stretchButtons) {
                    LinearLayout.LayoutParams(0, buttonWidth, 1f).apply {
                        marginStart = buttonMargin
                    }
                } else {
                    LinearLayout.LayoutParams(buttonWidth, buttonWidth).apply {
                        marginStart = buttonMargin
                    }
                }
                // Insert right after language toggle button (clamp to valid range)
                if (langIndex >= 0) {
                    val insertIndex = minOf(langIndex + 1, containerView.childCount)
                    containerView.addView(punctuationToggleButton, insertIndex, punctParams)
                } else {
                    containerView.addView(punctuationToggleButton, punctParams)
                }
            } else if (stretchButtons) {
                (punctuationToggleButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = 0
                    weight = 1f
                }
            } else {
                (punctuationToggleButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = buttonWidth
                    weight = 0f
                }
            }
            punctuationToggleButton.setOnClickListener {
                onPunctuationToggleListener?.invoke()
            }
            punctuationToggleButton.alpha = 1f
            punctuationToggleButton.visibility = View.VISIBLE
        } else {
            // Hide punctuation toggle when not in Chinese mode
            punctuationToggleButtonView?.let { btn ->
                (btn.parent as? ViewGroup)?.removeView(btn)
                btn.visibility = View.GONE
            }
        }

        // Skip animation for smoother updates - just set alpha directly
        variationsRow.alpha = 1f
        // Hide variationsRow when no suggestions (buttons will fill the space)
        variationsRow.visibility = if (noSuggestions) View.GONE else View.VISIBLE

        // Request layout update when stretching buttons
        if (stretchButtons) {
            containerView.requestLayout()
        }
    }

    private fun installOverlayTouchListener(overlayView: View) {
        val swipeThreshold = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            6f,
            context.resources.displayMetrics
        )
        val incrementalThreshold = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            9.6f,
            context.resources.displayMetrics
        )

        overlayView.setOnTouchListener { _, motionEvent ->
            if (isSymModeActive) {
                return@setOnTouchListener false
            }

            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    isSwipeInProgress = false
                    swipeDirection = null
                    touchStartX = motionEvent.x
                    touchStartY = motionEvent.y
                    lastCursorMoveX = motionEvent.x
                    Log.d(TAG, "Touch down on overlay at ($touchStartX, $touchStartY)")
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = motionEvent.x - touchStartX
                    val deltaY = abs(motionEvent.y - touchStartY)
                    val incrementalDeltaX = motionEvent.x - lastCursorMoveX

                    // Update swipe indicator position during move
                    updateSwipeIndicatorPosition(overlayView, motionEvent.x)

                    if (isSwipeInProgress || (abs(deltaX) > swipeThreshold && abs(deltaX) > deltaY)) {
                        if (!isSwipeInProgress) {
                            isSwipeInProgress = true
                            swipeDirection = if (deltaX > 0) 1 else -1
                            // Show swipe indicator when swipe starts
                            revealSwipeIndicator(overlayView, motionEvent.x)
                            Log.d(TAG, "Swipe started: ${if (swipeDirection == 1) "RIGHT" else "LEFT"}")
                        } else {
                            val currentDirection = if (incrementalDeltaX > 0) 1 else -1
                            if (currentDirection != swipeDirection && abs(incrementalDeltaX) > swipeThreshold) {
                                swipeDirection = currentDirection
                                Log.d(TAG, "Swipe direction changed: ${if (swipeDirection == 1) "RIGHT" else "LEFT"}")
                            }
                        }

                        if (isSwipeInProgress && swipeDirection != null) {
                            val inputConnection = currentInputConnection
                            if (inputConnection != null) {
                                val movementInDirection = if (swipeDirection == 1) incrementalDeltaX else -incrementalDeltaX
                                if (movementInDirection > incrementalThreshold) {
                                    val moved = if (swipeDirection == 1) {
                                        TextSelectionHelper.moveCursorRight(inputConnection)
                                    } else {
                                        TextSelectionHelper.moveCursorLeft(inputConnection)
                                    }

                                    if (moved) {
                                        lastCursorMoveX = motionEvent.x
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            onCursorMovedListener?.invoke()
                                        }, 50)
                                    }
                                }
                            }
                        }
                        true
                    } else {
                        true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // Hide swipe indicator
                    hideSwipeIndicator()
                    if (isSwipeInProgress) {
                        isSwipeInProgress = false
                        swipeDirection = null
                        Log.d(TAG, "Swipe ended on overlay")
                        true
                    } else {
                        val x = motionEvent.x
                        val y = motionEvent.y
                        val clickedView = container?.let { findClickableViewAt(it, x, y) }
                        if (clickedView != null) {
                            clickedView.performClick()
                        }
                        true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideSwipeIndicator()
                    isSwipeInProgress = false
                    swipeDirection = null
                    true
                }
                else -> true
            }
        }
    }

    /**
     * Creates the swipe indicator - a yellow gradient bar that follows the finger.
     */
    private fun createSwipeIndicator(): View {
        val barWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            32f,
            context.resources.displayMetrics
        ).toInt().coerceAtLeast(12)

        val drawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.argb(50, 255, 204, 0),   // Semi-transparent yellow
                Color.argb(170, 255, 221, 0),  // More opaque yellow center
                Color.argb(50, 255, 204, 0)    // Semi-transparent yellow
            )
        )

        return View(context).apply {
            background = drawable
            alpha = 0f
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(barWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        }
    }

    /**
     * Shows the swipe indicator with a fade-in animation.
     */
    private fun revealSwipeIndicator(overlayView: View, x: Float) {
        val indicator = swipeIndicator ?: return
        updateSwipeIndicatorPosition(overlayView, x)
        indicator.animate().cancel()
        indicator.alpha = 0f
        indicator.visibility = View.VISIBLE
        indicator.animate()
            .alpha(1f)
            .setDuration(60)
            .setListener(null)
            .start()
    }

    /**
     * Hides the swipe indicator with a fade-out animation.
     */
    private fun hideSwipeIndicator(immediate: Boolean = false) {
        val indicator = swipeIndicator ?: return
        indicator.animate().cancel()
        if (immediate) {
            indicator.alpha = 0f
            indicator.visibility = View.GONE
            return
        }
        indicator.animate()
            .alpha(0f)
            .setDuration(140)
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    indicator.visibility = View.GONE
                    indicator.alpha = 0f
                }
            })
            .start()
    }

    /**
     * Updates the swipe indicator position to follow the finger.
     */
    private fun updateSwipeIndicatorPosition(overlayView: View, x: Float) {
        val indicator = swipeIndicator ?: return
        val indicatorWidth = if (indicator.width > 0) indicator.width else (indicator.layoutParams?.width ?: 0)
        if (indicatorWidth <= 0 || overlayView.width <= 0) {
            return
        }
        val clampedX = x.coerceIn(0f, overlayView.width.toFloat())
        indicator.translationX = clampedX - (indicatorWidth / 2f)
        indicator.translationY = 0f
    }

    private fun startSpeechRecognition(inputConnection: android.view.inputmethod.InputConnection?) {
        try {
            val intent = Intent(context, SpeechRecognitionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            context.startActivity(intent)
            Log.d(TAG, "Speech recognition started")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to launch speech recognition", e)
        }
    }

    private fun openSettings() {
        try {
            val intent = Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Settings", e)
        }
    }

    private fun removeMicrophoneImmediate() {
        microphoneButtonView?.let { microphone ->
            (microphone.parent as? ViewGroup)?.removeView(microphone)
            microphone.visibility = View.GONE
            microphone.alpha = 1f
        }
    }

    private fun removeSettingsImmediate() {
        settingsButtonView?.let { settings ->
            (settings.parent as? ViewGroup)?.removeView(settings)
            settings.visibility = View.GONE
            settings.alpha = 1f
        }
    }

    private fun removeClipboardImmediate() {
        clipboardButtonView?.let { clipboard ->
            (clipboard.parent as? ViewGroup)?.removeView(clipboard)
            clipboard.visibility = View.GONE
            clipboard.alpha = 1f
        }
    }

    private fun removeLanguageToggleImmediate() {
        languageToggleButtonView?.let { toggle ->
            (toggle.parent as? ViewGroup)?.removeView(toggle)
            toggle.visibility = View.GONE
            toggle.alpha = 1f
        }
    }

    private fun createVariationButton(
        variation: String,
        inputConnection: android.view.inputmethod.InputConnection?,
        buttonWidth: Int,
        showNumbered: Boolean = false,
        candidateNumber: Int = 0,
        isWordPrediction: Boolean = false,
        wordPredictionPrefixLength: Int = 0,
        isPinyinMode: Boolean = false,
        isShuangpinMode: Boolean = false,
        isWubiMode: Boolean = false,
        isZhenmaMode: Boolean = false,
        candidateIndex: Int = 0,
        isJuyingMode: Boolean = false,
        isBestCandidate: Boolean = false
    ): TextView {
        val dp4 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            4f,
            context.resources.displayMetrics
        ).toInt()
        val dp6 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            6f,
            context.resources.displayMetrics
        ).toInt()
        val dp3 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context.resources.displayMetrics
        ).toInt()

        val buttonHeight = buttonWidth

        // Use highlighted background for best candidate in Juying mode (golden/yellow tint)
        val normalColor = if (isBestCandidate) {
            Color.rgb(50, 45, 10)  // Dark golden/yellow tint for best candidate
        } else {
            Color.rgb(17, 17, 17)  // Default dark gray
        }
        val drawable = GradientDrawable().apply {
            setColor(normalColor)
            cornerRadius = 0f
        }
        val pressedDrawable = GradientDrawable().apply {
            setColor(Color.rgb(38, 0, 255))
            cornerRadius = 0f
        }
        val stateListDrawable = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
            addState(intArrayOf(), drawable)
        }

        // Show numbered candidates for Pinyin and word prediction modes
        val displayText = if (showNumbered && candidateNumber in 1..9) {
            "$candidateNumber$variation"
        } else {
            variation
        }

        // Calculate text size based on content length (for word predictions which can be long)
        val textSizeSp = when {
            !showNumbered -> 17.6f  // Accent variations - single chars
            displayText.length <= 3 -> 18f  // Short (e.g., "1我" or "1go")
            displayText.length <= 5 -> 16f  // Medium words
            displayText.length <= 7 -> 14f  // Longer words
            displayText.length <= 9 -> 12f  // Even longer words
            else -> 10f  // Very long words
        }

        // Choose the appropriate click listener based on mode
        val clickListener = when {
            isWordPrediction -> {
                // English word prediction - delete prefix and insert word + space
                VariationButtonHandler.createWordPredictionClickListener(
                    variation,
                    wordPredictionPrefixLength,
                    inputConnection,
                    onVariationSelectedListener,
                    context
                )
            }
            isPinyinMode -> {
                // Pinyin candidate - just commit (replaces composing text automatically)
                VariationButtonHandler.createPinyinCandidateClickListener(
                    variation,
                    candidateIndex,
                    inputConnection,
                    onPinyinCandidateSelectedListener,
                    onVariationSelectedListener,
                    context
                )
            }
            isShuangpinMode -> {
                // Shuangpin candidate - just commit (replaces composing text automatically)
                VariationButtonHandler.createShuangpinCandidateClickListener(
                    variation,
                    candidateIndex,
                    inputConnection,
                    onShuangpinCandidateSelectedListener,
                    onVariationSelectedListener,
                    context
                )
            }
            isWubiMode -> {
                // Wubi candidate - just commit (replaces composing text automatically)
                VariationButtonHandler.createWubiCandidateClickListener(
                    variation,
                    candidateIndex,
                    inputConnection,
                    onWubiCandidateSelectedListener,
                    onVariationSelectedListener,
                    context
                )
            }
            isZhenmaMode -> {
                // Zhenma candidate - just commit (replaces composing text automatically)
                VariationButtonHandler.createZhenmaCandidateClickListener(
                    variation,
                    candidateIndex,
                    inputConnection,
                    onZhenmaCandidateSelectedListener,
                    onVariationSelectedListener,
                    context
                )
            }
            else -> {
                // Accent variation - delete 1 char and insert variation
                VariationButtonHandler.createVariationClickListener(
                    variation,
                    inputConnection,
                    onVariationSelectedListener
                )
            }
        }

        // Capture flags for closure
        val shouldVibrate = isWordPrediction || isPinyinMode || isShuangpinMode || isWubiMode || isZhenmaMode

        // Use golden/yellow text for best candidate in Juying mode
        val textColor = if (isBestCandidate) {
            Color.rgb(255, 215, 0)  // Gold color for best candidate
        } else {
            Color.WHITE
        }

        return TextView(context).apply {
            text = displayText
            setTextColor(textColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp6, dp4, dp6, dp4)
            background = stateListDrawable
            layoutParams = LinearLayout.LayoutParams(buttonWidth, buttonHeight).apply {
                marginEnd = dp3
            }
            maxLines = 1

            // In Juying mode, use auto-sizing to ensure long text fits in buttons
            if (isJuyingMode && displayText.length > 2) {
                // Enable auto-size text with min 8sp, max based on content, granularity 1sp
                val maxSize = when {
                    displayText.length <= 3 -> 18
                    displayText.length <= 5 -> 16
                    else -> 14
                }
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    8,  // min text size in sp
                    maxSize,  // max text size in sp
                    1,  // granularity in sp
                    TypedValue.COMPLEX_UNIT_SP
                )
            } else {
                // Use fixed text size for short text or non-Juying mode
                textSize = textSizeSp
            }

            isClickable = true
            isFocusable = true
            isHapticFeedbackEnabled = true
            setOnClickListener { view ->
                // Vibrate for word prediction and pinyin candidate selection
                if (shouldVibrate) {
                    // Use View's performHapticFeedback - most reliable for IME
                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    )
                }
                clickListener.onClick(view)
            }
        }
    }

    private fun createPlaceholderButton(buttonWidth: Int): View {
        val dp3 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 0f
        }
        return View(context).apply {
            background = drawable
            layoutParams = LinearLayout.LayoutParams(buttonWidth, buttonWidth).apply {
                marginEnd = dp3
            }
            isClickable = false
            isFocusable = false
        }
    }

    private fun createMicrophoneButton(buttonSize: Int): ImageView {
        val drawable = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 0f
        }
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_mic_24)
            setColorFilter(Color.WHITE)
            background = drawable
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun createStatusBarSettingsButton(buttonSize: Int): ImageView {
        val dp3 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 0f
        }
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_settings_24)
            setColorFilter(Color.rgb(100, 100, 100))
            background = drawable
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            setPadding(dp3, dp3, dp3, dp3)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun createClipboardButton(buttonSize: Int): ImageView {
        val dp3 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 0f
        }
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_clipboard_24)
            setColorFilter(Color.rgb(100, 100, 100))
            background = drawable
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            setPadding(dp3, dp3, dp3, dp3)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun showClipboardHistory(anchorView: View, inputConnection: android.view.inputmethod.InputConnection?) {
        if (clipboardHistoryPopup?.isShowing() == true) {
            clipboardHistoryPopup?.dismiss()
            return
        }

        clipboardHistoryPopup = ClipboardHistoryPopup(context).apply {
            onItemSelected = { text ->
                Log.d(TAG, "Clipboard item selected: ${text.take(30)}...")
            }
            onDismiss = {
                Log.d(TAG, "Clipboard popup dismissed")
            }
        }
        clipboardHistoryPopup?.show(anchorView, inputConnection)
    }

    private fun createSymButton(buttonSize: Int): TextView {
        val dp2 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 0f
        }
        return TextView(context).apply {
            text = "SYM"
            textSize = 10f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = drawable
            isClickable = true
            isFocusable = true
            setPadding(dp2, dp2, dp2, dp2)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun createLanguageToggleButton(buttonSize: Int): TextView {
        val dp2 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 0f
        }
        return TextView(context).apply {
            text = when {
                isPinyinModeActive -> "拼"
                isWubiModeActive -> "五"
                else -> "EN"
            }
            textSize = 12f
            setTextColor(when {
                isPinyinModeActive || isWubiModeActive -> Color.rgb(100, 200, 255)
                else -> Color.WHITE
            })
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = drawable
            isClickable = true
            isFocusable = true
            setPadding(dp2, dp2, dp2, dp2)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun updateLanguageToggleButton() {
        languageToggleButtonView?.apply {
            text = when {
                isPinyinModeActive -> "拼"
                isShuangpinModeActive -> "双"
                isWubiModeActive -> "五"
                isZhenmaModeActive -> "真"
                else -> "EN"
            }
            setTextColor(when {
                isPinyinModeActive || isShuangpinModeActive || isWubiModeActive || isZhenmaModeActive -> Color.rgb(100, 200, 255)
                else -> Color.WHITE
            })
        }
    }

    private fun createPunctuationToggleButton(buttonSize: Int): TextView {
        val dp2 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 0f
        }
        return TextView(context).apply {
            text = if (isChinesePunctuationMode) "。" else "."
            textSize = 14f
            setTextColor(if (isChinesePunctuationMode) Color.rgb(100, 200, 255) else Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = drawable
            isClickable = true
            isFocusable = true
            setPadding(dp2, dp2, dp2, dp2)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun updatePunctuationToggleButton() {
        punctuationToggleButtonView?.apply {
            text = if (isChinesePunctuationMode) "。" else "."
            setTextColor(if (isChinesePunctuationMode) Color.rgb(100, 200, 255) else Color.WHITE)
        }
    }

    private fun createArrowButton(buttonSize: Int, isNext: Boolean): ImageView {
        val drawable = GradientDrawable().apply {
            setColor(Color.rgb(17, 17, 17))
            cornerRadius = 0f
        }
        return ImageView(context).apply {
            // Use chevron icons - right arrow for next, left arrow for prev
            setImageResource(
                if (isNext) R.drawable.ic_chevron_right_24
                else R.drawable.ic_chevron_left_24
            )
            setColorFilter(Color.WHITE)
            background = drawable
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            minimumWidth = buttonSize
            minimumHeight = buttonSize
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun animateVariationsIn(view: View) {
        view.alpha = 0f
        view.visibility = View.VISIBLE
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 75
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                view.alpha = progress
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.alpha = 1f
                }
            })
        }.start()
    }

    private fun animateVariationsOut(view: View, onAnimationEnd: (() -> Unit)? = null) {
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 50
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                view.alpha = progress
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                    view.alpha = 1f
                    onAnimationEnd?.invoke()
                }
            })
        }.start()
    }

    private fun findClickableViewAt(parent: View, x: Float, y: Float): View? {
        if (parent !is ViewGroup) {
            return if (x >= 0 && x < parent.width &&
                y >= 0 && y < parent.height &&
                parent.isClickable) {
                parent
            } else {
                null
            }
        }

        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i)
            if (child.visibility == View.VISIBLE) {
                val childLeft = child.left.toFloat()
                val childTop = child.top.toFloat()
                val childRight = child.right.toFloat()
                val childBottom = child.bottom.toFloat()

                if (x >= childLeft && x < childRight &&
                    y >= childTop && y < childBottom) {
                    val childX = x - childLeft
                    val childY = y - childTop
                    val result = findClickableViewAt(child, childX, childY)
                    if (result != null) {
                        return result
                    }
                }
            }
        }

        return if (parent.isClickable) parent else null
    }
}

