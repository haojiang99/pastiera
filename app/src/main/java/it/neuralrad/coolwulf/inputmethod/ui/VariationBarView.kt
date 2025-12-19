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
import it.neuralrad.coolwulf.inputmethod.SherpaSpeechActivity
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

    // Get current theme (considers day/night mode if enabled)
    private fun getCurrentTheme(): StatusBarTheme {
        val themeId = SettingsManager.getEffectiveTheme(context)
        return StatusBarTheme.getThemeById(themeId, context)
    }

    var onVariationSelectedListener: VariationButtonHandler.OnVariationSelectedListener? = null
    var onPinyinCandidateSelectedListener: VariationButtonHandler.OnPinyinCandidateSelectedListener? = null
    var onWubiCandidateSelectedListener: VariationButtonHandler.OnWubiCandidateSelectedListener? = null
    var onShuangpinCandidateSelectedListener: VariationButtonHandler.OnShuangpinCandidateSelectedListener? = null
    var onZiranmaCandidateSelectedListener: VariationButtonHandler.OnZiranmaCandidateSelectedListener? = null
    var onZhenmaCandidateSelectedListener: VariationButtonHandler.OnZhenmaCandidateSelectedListener? = null
    var onCursorMovedListener: (() -> Unit)? = null
    var onNextPageListener: (() -> Unit)? = null
    var onPrevPageListener: (() -> Unit)? = null
    var onLanguageToggleListener: (() -> Unit)? = null
    var onSymButtonListener: (() -> Unit)? = null
    var onPunctuationToggleListener: (() -> Unit)? = null
    var onTraditionalChineseToggleListener: (() -> Unit)? = null
    var onSoundToggleListener: (() -> Unit)? = null
    var onPunctuationButtonListener: ((String) -> Unit)? = null  // Called when comma/period button is pressed

    private var wrapper: FrameLayout? = null
    private var symButtonView: TextView? = null
    private var languageToggleButtonView: TextView? = null
    private var punctuationToggleButtonView: TextView? = null
    private var traditionalChineseToggleButtonView: TextView? = null
    private var isPinyinModeActive: Boolean = false
    private var isT9PinyinModeActive: Boolean = false
    private var isShuangpinModeActive: Boolean = false
    private var isZiranmaModeActive: Boolean = false
    private var isWubiModeActive: Boolean = false
    private var isZhenmaModeActive: Boolean = false
    private var isChinesePunctuationMode: Boolean = true
    private var isTraditionalChineseMode: Boolean = false
    private var isTraditionalChineseToggleEnabled: Boolean = false
    private var isVirtualShiftActive: Boolean = false
    private var prevArrowButton: ImageView? = null
    private var nextArrowButton: ImageView? = null
    private var container: LinearLayout? = null
    private var overlay: View? = null
    private var currentVariationsRow: LinearLayout? = null
    private var variationButtons: MutableList<TextView> = mutableListOf()
    private var microphoneButtonView: ImageView? = null
    private var settingsButtonView: ImageView? = null
    private var clipboardButtonView: ImageView? = null
    private var keyboardToggleButtonView: ImageView? = null
    private var soundToggleButtonView: ImageView? = null
    private var clipboardHistoryPopup: ClipboardHistoryPopup? = null
    private var onVirtualKeyboardToggleListener: (() -> Unit)? = null
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
        val statusBarHeightDip = SettingsManager.getStatusBarHeight(context).toFloat()
        val variationsContainerHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            statusBarHeightDip,
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
            clipChildren = false
            clipToPadding = false
        }

        // Wrapper FrameLayout to hold both container and overlay
        wrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                variationsContainerHeight
            )
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
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

    /**
     * Refreshes the status bar height from settings.
     * Call this when the height setting changes.
     */
    fun refreshHeight() {
        val statusBarHeightDip = SettingsManager.getStatusBarHeight(context).toFloat()
        val newHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            statusBarHeightDip,
            context.resources.displayMetrics
        ).toInt()

        wrapper?.let { w ->
            val lp = w.layoutParams as? LinearLayout.LayoutParams
            if (lp != null && lp.height != newHeight) {
                lp.height = newHeight
                w.layoutParams = lp
                w.requestLayout()
            }
        }
    }

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

    fun setT9PinyinModeActive(active: Boolean) {
        if (isT9PinyinModeActive != active) {
            isT9PinyinModeActive = active
            updateLanguageToggleButton()
        }
    }

    fun setShuangpinModeActive(active: Boolean) {
        if (isShuangpinModeActive != active) {
            isShuangpinModeActive = active
            updateLanguageToggleButton()
        }
    }

    fun setZiranmaModeActive(active: Boolean) {
        if (isZiranmaModeActive != active) {
            isZiranmaModeActive = active
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

    fun setTraditionalChineseMode(active: Boolean) {
        if (isTraditionalChineseMode != active) {
            isTraditionalChineseMode = active
            updateTraditionalChineseToggleButton()
        }
    }

    fun setTraditionalChineseToggleEnabled(enabled: Boolean) {
        if (isTraditionalChineseToggleEnabled != enabled) {
            isTraditionalChineseToggleEnabled = enabled
            // Toggle button visibility will be handled in ensureView
        }
    }

    /**
     * Sets the virtual keyboard shift state. When shift is active, punctuation buttons
     * show the shifted variants (e.g., ? and ! instead of , and .).
     */
    fun setVirtualShiftState(isShifted: Boolean) {
        if (isVirtualShiftActive != isShifted) {
            isVirtualShiftActive = isShifted
            // The punctuation buttons will be updated on the next updateVariations call
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
        removeKeyboardToggleImmediate()
        removeSoundToggleImmediate()
        removeSymButtonImmediate()
        removeLanguageToggleImmediate()
        removeArrowsImmediate()
        hideSwipeIndicator(immediate = true)
        clipboardHistoryPopup?.dismiss()
        container?.visibility = View.GONE
        wrapper?.visibility = View.GONE
        overlay?.visibility = View.GONE
    }

    /**
     * Shows only the SYM button, hiding everything else.
     * Used when SYM mode is active so user can click SYM again to close it.
     */
    fun showSymButtonOnly() {
        val containerView = container ?: return
        val wrapperView = wrapper ?: return

        // Hide everything except SYM button
        currentVariationsRow?.let { row ->
            (row.parent as? ViewGroup)?.removeView(row)
        }
        currentVariationsRow = null
        variationButtons.clear()
        removeMicrophoneImmediate()
        removeSettingsImmediate()
        removeClipboardImmediate()
        removeKeyboardToggleImmediate()
        removeSoundToggleImmediate()
        removeLanguageToggleImmediate()
        removePunctuationToggleImmediate()
        removeTraditionalChineseToggleImmediate()
        removeArrowsImmediate()
        hideSwipeIndicator(immediate = true)
        clipboardHistoryPopup?.dismiss()
        overlay?.visibility = View.GONE

        // Keep container and wrapper visible
        wrapperView.visibility = View.VISIBLE
        containerView.visibility = View.VISIBLE

        // Show SYM button
        val buttonWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            36f,
            context.resources.displayMetrics
        ).toInt()
        val symButton = symButtonView ?: createSymButton(buttonWidth).also {
            symButtonView = it
        }
        if (symButton.parent == null) {
            val symParams = LinearLayout.LayoutParams(buttonWidth, buttonWidth)
            containerView.addView(symButton, symParams)
        }
        symButton.setOnClickListener { onSymButtonListener?.invoke() }
        symButton.alpha = 1f
        symButton.visibility = View.VISIBLE
    }

    private fun removePunctuationToggleImmediate() {
        punctuationToggleButtonView?.let { btn ->
            (btn.parent as? ViewGroup)?.removeView(btn)
            btn.visibility = View.GONE
        }
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
        removeKeyboardToggleImmediate()
        removeSoundToggleImmediate()
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

        // Note: Don't call refreshHeight() here - it causes jumping during typing
        // Height is managed by the stable height logic below based on content

        currentInputConnection = inputConnection
        wrapperView.visibility = View.VISIBLE
        containerView.visibility = View.VISIBLE
        // Show overlay for swipe functionality (hidden during SYM mode)
        if (!isSymModeActive) {
            overlay?.visibility = View.VISIBLE
        }

        // In Juying mode with suggestions, remove container padding to allow full-width buttons
        val isJuyingWithSuggestions = snapshot.isJuyingMode && snapshot.variations.isNotEmpty()
        // Calculate vertical padding based on suggestion height percentage
        // At 100%, no padding (full height). At 50%, use 8.8dp padding (original behavior)
        val heightPercent = SettingsManager.getSuggestionHeightPercent(context)
        val maxVerticalPaddingDp = 8.8f
        // Scale padding inversely with height percentage: 100% -> 0 padding, 50% -> full padding
        val scaledPaddingDp = maxVerticalPaddingDp * (100 - heightPercent) / 50f
        val verticalPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            scaledPaddingDp.coerceAtLeast(0f),
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
        val showNumberedButtons = (snapshot.pinyinModeActive || snapshot.t9PinyinModeActive || snapshot.shuangpinModeActive || snapshot.wubiModeActive || snapshot.zhenmaModeActive) && !snapshot.isJuyingMode
        val userFontSize = SettingsManager.getCandidateFontSize(context).toFloat()
        val textSizeSp = if (showNumberedButtons || snapshot.wordPredictionActive) userFontSize else (userFontSize - 0.4f)
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
        val isEnglishWordPrediction = snapshot.wordPredictionActive && !snapshot.pinyinModeActive && !snapshot.t9PinyinModeActive && !snapshot.wubiModeActive && !snapshot.zhenmaModeActive

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

            // Check if fixed positions mode is enabled
            val fixedPositions = SettingsManager.getJuyingFixedPositions(context)

            if (fixedPositions) {
                // Fixed positions mode: always reserve space for both arrows and 5 slots
                // Best suggestion (1st in suggestionLayouts) goes to position 2 (center, Space key)
                // Other suggestions fill positions 0,1,3,4 in order (2nd,3rd,4th,5th suggestions)
                val arrowsSpace = 2 * (arrowButtonSize + arrowMargin)  // Always reserve both arrows
                val numSlots = 5  // Always 5 slots
                val totalSpacing = (numSlots - 1) * spacingBetweenButtons
                val adjustedWidth = fullScreenWidth - arrowsSpace
                val widthPerButton = (adjustedWidth - totalSpacing) / numSlots

                // Create array with 5 slots, all empty initially
                // Slots: [0]=Shift, [1]=Sym, [2]=Space, [3]=Ctrl, [4]=Alt
                val fixedSlots = arrayOf("", "", "", "", "")
                val numSuggestions = suggestionLayouts.size

                // Check if punctuation buttons should be shown in next word prediction mode
                val showPunctuationButtons = snapshot.isNextWordPrediction &&
                    SettingsManager.isJuyingPunctuationButtons(context) &&
                    suggestionLayouts.isNotEmpty()

                // Get the custom punctuation from settings - use shifted variants when shift is active
                val leftPunctuationEnglish = if (isVirtualShiftActive)
                    SettingsManager.getJuyingPunctuationLeftShifted(context)
                else
                    SettingsManager.getJuyingPunctuationLeft(context)
                val rightPunctuationEnglish = if (isVirtualShiftActive)
                    SettingsManager.getJuyingPunctuationRightShifted(context)
                else
                    SettingsManager.getJuyingPunctuationRight(context)
                // Convert to Chinese if in Chinese punctuation mode
                val leftPunctuation = if (isChinesePunctuationMode)
                    SettingsManager.getChinesePunctuation(leftPunctuationEnglish)
                else
                    leftPunctuationEnglish
                val rightPunctuation = if (isChinesePunctuationMode)
                    SettingsManager.getChinesePunctuation(rightPunctuationEnglish)
                else
                    rightPunctuationEnglish

                if (showPunctuationButtons) {
                    // In next word prediction mode with punctuation enabled:
                    // Position 0 (Shift) = left punctuation, Position 4 (Alt) = right punctuation
                    // Suggestions fill 1, 2, 3 (Sym, Space, Ctrl)
                    fixedSlots[0] = leftPunctuation   // Shift = left punctuation
                    fixedSlots[4] = rightPunctuation  // Alt = right punctuation

                    // Fill middle slots with suggestions
                    when (numSuggestions) {
                        1 -> {
                            fixedSlots[2] = suggestionLayouts[0].text  // Space = best
                        }
                        2 -> {
                            fixedSlots[2] = suggestionLayouts[0].text  // Space = best
                            fixedSlots[1] = suggestionLayouts[1].text  // Sym = 2nd
                        }
                        else -> {
                            // 3 or more candidates - fill Sym, Space, Ctrl
                            fixedSlots[2] = suggestionLayouts[0].text  // Space = best
                            fixedSlots[1] = suggestionLayouts[1].text  // Sym = 2nd
                            fixedSlots[3] = if (numSuggestions > 2) suggestionLayouts[2].text else ""  // Ctrl = 3rd
                        }
                    }
                } else {
                    // Normal fixed placement rules:
                    // 1 candidate:  Space(best)
                    // 2 candidates: Space(best), Sym
                    // 3 candidates: Space(best), Sym, Ctrl
                    // 4 candidates: Space(best), Shift, Sym, Ctrl
                    // 5 candidates: Space(best), Shift, Sym, Ctrl, Alt
                    when (numSuggestions) {
                        1 -> {
                            fixedSlots[2] = suggestionLayouts[0].text  // Space = best
                        }
                        2 -> {
                            fixedSlots[2] = suggestionLayouts[0].text  // Space = best
                            fixedSlots[1] = suggestionLayouts[1].text  // Sym = 2nd
                        }
                        3 -> {
                            fixedSlots[2] = suggestionLayouts[0].text  // Space = best
                            fixedSlots[1] = suggestionLayouts[1].text  // Sym = 2nd
                            fixedSlots[3] = suggestionLayouts[2].text  // Ctrl = 3rd
                        }
                        4 -> {
                            fixedSlots[2] = suggestionLayouts[0].text  // Space = best
                            fixedSlots[0] = suggestionLayouts[1].text  // Shift = 2nd
                            fixedSlots[1] = suggestionLayouts[2].text  // Sym = 3rd
                            fixedSlots[3] = suggestionLayouts[3].text  // Ctrl = 4th
                        }
                        else -> {
                            // 5 or more candidates
                            fixedSlots[2] = suggestionLayouts[0].text  // Space = best
                            fixedSlots[0] = suggestionLayouts[1].text  // Shift = 2nd
                            fixedSlots[1] = suggestionLayouts[2].text  // Sym = 3rd
                            fixedSlots[3] = suggestionLayouts[3].text  // Ctrl = 4th
                            fixedSlots[4] = suggestionLayouts[4].text  // Alt = 5th
                        }
                    }
                }

                // Add slots to limitedVariations
                for (slot in fixedSlots) {
                    limitedVariations.add(slot)
                    buttonWidths.add(widthPerButton)
                }
                totalWidth = adjustedWidth
            } else {
                // Original dynamic layout
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
            }
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

        // Calculate button height based on suggestion height percentage setting
        val statusBarHeightDip = SettingsManager.getStatusBarHeight(context).toFloat()
        val statusBarHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            statusBarHeightDip,
            context.resources.displayMetrics
        ).toInt()
        val suggestionHeightPercent = SettingsManager.getSuggestionHeightPercent(context)
        val maxButtonHeight = (statusBarHeightPx * suggestionHeightPercent / 100)

        val baseButtonHeight = if (isEnglishWordPrediction) {
            // For English word predictions, use percentage-based height
            maxButtonHeight
        } else {
            // For Chinese candidates, use smaller of button width or percentage-based height
            minOf(buttonWidth, maxButtonHeight)
        }

        // For Juying mode with long text, calculate if we need extra height for multi-line display
        // Minimum font size is configurable in settings (default 11sp)
        val minReadableFontSizeSp = SettingsManager.getSuggestionMinFontSize(context).toFloat()
        val minFontSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            minReadableFontSizeSp,
            context.resources.displayMetrics
        )
        val dp6ForCalc = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            6f,
            context.resources.displayMetrics
        ).toInt()
        val dp4ForCalc = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            4f,
            context.resources.displayMetrics
        ).toInt()

        // Check if Chinese input mode is active
        val isChineseInputActive = snapshot.pinyinModeActive || snapshot.t9PinyinModeActive ||
            snapshot.shuangpinModeActive || snapshot.ziranmaModeActive ||
            snapshot.wubiModeActive || snapshot.zhenmaModeActive

        // Check if auto-adjust status bar height is enabled
        val autoAdjustEnabled = SettingsManager.isAutoAdjustStatusBarHeight(context)

        // For Chinese input modes, use fixed base height unless auto-adjust is enabled
        // For non-Chinese modes (English word prediction, accent variations), calculate dynamic height
        val stableButtonHeight = if (isChineseInputActive && !autoAdjustEnabled) {
            // Fixed height for Chinese input - no dynamic adjustment (prevents jumping)
            baseButtonHeight
        } else if (isJuyingModeWithSuggestions) {
            // Calculate dynamic height when auto-adjust is enabled or for non-Chinese modes
            var maxNeededHeight = baseButtonHeight
            val testPaint = android.graphics.Paint().apply {
                textSize = minFontSizePx
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            for ((index, variation) in limitedVariations.withIndex()) {
                if (variation.isEmpty()) continue

                val btnWidth = buttonWidths.getOrElse(index) { buttonWidth }
                val availableTextWidth = btnWidth - dp6ForCalc * 2
                val textWidthAtMinFont = testPaint.measureText(variation)

                if (textWidthAtMinFont > availableTextWidth) {
                    val numLinesNeeded = kotlin.math.ceil(textWidthAtMinFont / availableTextWidth.toFloat()).toInt().coerceIn(1, 10)
                    val lineHeightPx = minFontSizePx * 1.3f
                    val neededHeight = (lineHeightPx * numLinesNeeded + dp4ForCalc * 2).toInt()
                    maxNeededHeight = maxOf(maxNeededHeight, neededHeight)
                }
            }
            maxNeededHeight
        } else {
            baseButtonHeight
        }

        // Update wrapper height based on stable button height
        val finalWrapperHeight = maxOf(statusBarHeightPx, stableButtonHeight + dp4ForCalc * 2)

        wrapper?.let { w ->
            val currentParams = w.layoutParams as? LinearLayout.LayoutParams
            if (currentParams != null && currentParams.height != finalWrapperHeight) {
                currentParams.height = finalWrapperHeight
                w.layoutParams = currentParams
                w.requestLayout()
            }
        }

        // Reuse existing row if available, otherwise create new one
        val variationsRow = if (reuseExistingRow && currentVariationsRow != null) {
            currentVariationsRow!!.apply {
                // Update layout params if size changed - use WRAP_CONTENT for height to allow multi-line
                val lp = layoutParams as? LinearLayout.LayoutParams
                if (lp != null && lp.width != variationsRowWidth) {
                    lp.width = variationsRowWidth
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    layoutParams = lp
                }
            }
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(variationsRowWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                clipChildren = false
                clipToPadding = false
            }.also {
                currentVariationsRow = it
                containerView.addView(it, 0)
            }
        }

        // Measure and layout the variationsRow - use AT_MOST for height to allow expansion
        val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(variationsRowWidth, View.MeasureSpec.EXACTLY)
        val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(stableButtonHeight, View.MeasureSpec.AT_MOST)
        variationsRow.measure(widthMeasureSpec, heightMeasureSpec)
        variationsRow.layout(0, 0, variationsRowWidth, variationsRow.measuredHeight)

        lastDisplayedVariations = limitedVariations.toList()

        val wordPredictionPrefixLength = if (snapshot.wordPredictionActive) snapshot.wordPredictionPrefix.length else 0
        var buttonX = 0
        var maxActualButtonHeight = 0  // Track max button height for wrapper adjustment
        // Count actual (non-empty) suggestions for best candidate calculation
        val actualSuggestionsCount = limitedVariations.count { it.isNotEmpty() }
        for ((index, variation) in limitedVariations.withIndex()) {
            val individualButtonWidth = buttonWidths[index]

            // Skip creating clickable button for empty placeholders (fixed position mode)
            if (variation.isEmpty()) {
                // Create invisible placeholder to maintain spacing
                val placeholder = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(individualButtonWidth, stableButtonHeight)
                }
                variationsRow.addView(placeholder)
                placeholder.layout(buttonX, 0, buttonX + individualButtonWidth, stableButtonHeight)
                buttonX += individualButtonWidth + spacingBetweenButtons
                continue
            }

            // Check if fixed positions mode is enabled
            val fixedPositionsMode = SettingsManager.getJuyingFixedPositions(context)

            // In Juying mode, determine best candidate based on mode:
            // - Fixed positions mode: best is always at position 2 (center, Space key)
            // - Chinese input (non-fixed): best candidate position depends on number of candidates
            //   - 1 candidate: best is at position 0 (only one)
            //   - 2 candidates: [2nd, 1st] -> best is at position 1
            //   - 3 candidates: [2nd, 1st, 3rd] -> best is at position 1 (middle)
            //   - 4+ candidates: [2nd, 3rd, 1st, ...] -> best is at position 2
            // - English word prediction: [1st best, typed word, 2nd best]
            //   - Best candidate is always at position 0 (left, Sym key)
            val isChineseMode = snapshot.pinyinModeActive || snapshot.t9PinyinModeActive || snapshot.shuangpinModeActive ||
                                snapshot.wubiModeActive || snapshot.zhenmaModeActive
            val bestCandidatePosition = when {
                fixedPositionsMode && isChineseMode -> 2  // Fixed positions: best always at center (Space)
                isChineseMode -> when (actualSuggestionsCount) {
                    1 -> 0  // 1 candidate: best at position 0
                    2 -> 1  // 2 candidates: best at position 1 (right)
                    3 -> 1  // 3 candidates: best at position 1 (middle)
                    else -> 2  // 4+ candidates: best at position 2
                }
                snapshot.wordPredictionActive -> 0  // English: best is always at position 0 (left)
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
                variation, inputConnection, individualButtonWidth, stableButtonHeight, showNumberedButtons, index + 1,
                snapshot.wordPredictionActive, wordPredictionPrefixLength, snapshot.pinyinModeActive,
                snapshot.shuangpinModeActive, snapshot.ziranmaModeActive, snapshot.wubiModeActive, snapshot.zhenmaModeActive, candidateIndex = index,
                isJuyingMode = snapshot.isJuyingMode, isBestCandidate = isBestCandidate
            )
            variationButtons.add(button)
            variationsRow.addView(button)

            // Force measure and layout the button - use UNSPECIFIED for height to allow multi-line wrapping
            val buttonWidthSpec = View.MeasureSpec.makeMeasureSpec(individualButtonWidth, View.MeasureSpec.EXACTLY)
            val buttonHeightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            button.measure(buttonWidthSpec, buttonHeightSpec)
            val actualButtonHeight = button.measuredHeight
            maxActualButtonHeight = maxOf(maxActualButtonHeight, actualButtonHeight)
            button.layout(buttonX, 0, buttonX + individualButtonWidth, actualButtonHeight)
            buttonX += individualButtonWidth + spacingBetweenButtons
        }

        // Update wrapper height to fit the tallest button with padding
        if (maxActualButtonHeight > 0) {
            val neededWrapperHeight = maxActualButtonHeight + dp4ForCalc * 2
            val adjustedWrapperHeight = maxOf(statusBarHeightPx, neededWrapperHeight)
            wrapper?.let { w ->
                val currentParams = w.layoutParams as? LinearLayout.LayoutParams
                if (currentParams != null && currentParams.height != adjustedWrapperHeight) {
                    currentParams.height = adjustedWrapperHeight
                    w.layoutParams = currentParams
                    w.requestLayout()
                }
            }
        }

        // Force a layout pass
        containerView.requestLayout()

        // Add navigation arrows when in Pinyin, Shuangpin, Ziranma, Wubi, Zhenma, or word prediction mode with suggestions
        val showPagination = (snapshot.pinyinModeActive || snapshot.t9PinyinModeActive || snapshot.shuangpinModeActive || snapshot.ziranmaModeActive || snapshot.wubiModeActive || snapshot.zhenmaModeActive || snapshot.wordPredictionActive) &&
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

        // Check if fixed positions mode is enabled for Juying
        val fixedPositions = SettingsManager.getJuyingFixedPositions(context)
        val isJuyingFixedMode = snapshot.isJuyingMode && fixedPositions && snapshot.variations.isNotEmpty()

        if (showPagination || isJuyingFixedMode) {
            // Previous page arrow (left) - show if there's a previous page OR in fixed position mode
            if (snapshot.hasPrevPage || isJuyingFixedMode) {
                val prevArrow = prevArrowButton ?: createArrowButton(arrowButtonSize, isNext = false).also {
                    prevArrowButton = it
                }
                // Use maxActualButtonHeight to match suggestion button heights (or stableButtonHeight as fallback)
                val arrowHeight = if (maxActualButtonHeight > 0) maxActualButtonHeight else stableButtonHeight
                val prevParams = LinearLayout.LayoutParams(arrowButtonSize, arrowHeight).apply {
                    marginStart = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        2f,
                        context.resources.displayMetrics
                    ).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                }
                containerView.addView(prevArrow, 0, prevParams)
                // In fixed mode with no prev page, show invisible placeholder
                if (isJuyingFixedMode && !snapshot.hasPrevPage) {
                    prevArrow.alpha = 0f
                    prevArrow.isClickable = false
                } else {
                    prevArrow.alpha = 1f
                    prevArrow.isClickable = true
                    prevArrow.setOnClickListener { onPrevPageListener?.invoke() }
                }
                prevArrow.visibility = View.VISIBLE
            }

            // Next page arrow (right) - show if there's a next page OR in fixed position mode
            if (snapshot.hasNextPage || isJuyingFixedMode) {
                val nextArrow = nextArrowButton ?: createArrowButton(arrowButtonSize, isNext = true).also {
                    nextArrowButton = it
                }
                // Use maxActualButtonHeight to match suggestion button heights (or stableButtonHeight as fallback)
                val arrowHeight = if (maxActualButtonHeight > 0) maxActualButtonHeight else stableButtonHeight
                val nextParams = LinearLayout.LayoutParams(arrowButtonSize, arrowHeight).apply {
                    marginStart = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        4f,
                        context.resources.displayMetrics
                    ).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                }
                // Insert right after variationsRow (index 0) and prevArrow (if exists)
                val insertIndex = if (snapshot.hasPrevPage || isJuyingFixedMode) 2 else 1
                containerView.addView(nextArrow, insertIndex, nextParams)
                // In fixed mode with no next page, show invisible placeholder
                if (isJuyingFixedMode && !snapshot.hasNextPage) {
                    nextArrow.alpha = 0f
                    nextArrow.isClickable = false
                } else {
                    nextArrow.alpha = 1f
                    nextArrow.isClickable = true
                    nextArrow.setOnClickListener { onNextPageListener?.invoke() }
                }
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
        val baseButtonCount = if (isPinyinModeActive || isT9PinyinModeActive || isShuangpinModeActive || isWubiModeActive) 5 else 4
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

        // Keyboard toggle button - reuse if already attached
        val showKeyboardToggleButton = SettingsManager.getShowVirtualKeyboardButton(context) && !hideStatusBarIconsForJuying
        val keyboardToggleButton = keyboardToggleButtonView ?: createKeyboardToggleButton(buttonWidth).also {
            keyboardToggleButtonView = it
        }
        val keyboardToggleMargin = buttonMargin
        if (showKeyboardToggleButton) {
            if (keyboardToggleButton.parent == null) {
                val keyboardParams = if (stretchButtons) {
                    LinearLayout.LayoutParams(0, buttonWidth, 1f).apply {
                        marginStart = keyboardToggleMargin
                    }
                } else {
                    LinearLayout.LayoutParams(buttonWidth, buttonWidth).apply {
                        marginStart = keyboardToggleMargin
                    }
                }
                containerView.addView(keyboardToggleButton, keyboardParams)
            } else if (stretchButtons) {
                (keyboardToggleButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = 0
                    weight = 1f
                    marginStart = keyboardToggleMargin
                }
            } else {
                (keyboardToggleButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = buttonWidth
                    weight = 0f
                    marginStart = keyboardToggleMargin
                }
            }
            keyboardToggleButton.setOnClickListener { onVirtualKeyboardToggleListener?.invoke() }
            keyboardToggleButton.alpha = 1f
            keyboardToggleButton.visibility = View.VISIBLE
            // Update color based on current state
            val isEnabled = SettingsManager.isVirtualKeyboardEnabled(context)
            keyboardToggleButton.setColorFilter(if (isEnabled) Color.rgb(100, 200, 255) else Color.rgb(100, 100, 100))
        } else {
            // Hide/remove keyboard toggle button when disabled or in Juying mode with suggestions
            if (keyboardToggleButton.parent != null) {
                (keyboardToggleButton.parent as? ViewGroup)?.removeView(keyboardToggleButton)
            }
            keyboardToggleButton.visibility = View.GONE
        }

        // Sound toggle button - reuse if already attached
        val showSoundToggleButton = SettingsManager.isShowSoundToggleButton(context) && !hideStatusBarIconsForJuying
        val soundToggleButton = soundToggleButtonView ?: createSoundToggleButton(buttonWidth).also {
            soundToggleButtonView = it
        }
        val soundToggleMargin = buttonMargin
        if (showSoundToggleButton) {
            if (soundToggleButton.parent == null) {
                val soundParams = if (stretchButtons) {
                    LinearLayout.LayoutParams(0, buttonWidth, 1f).apply {
                        marginStart = soundToggleMargin
                    }
                } else {
                    LinearLayout.LayoutParams(buttonWidth, buttonWidth).apply {
                        marginStart = soundToggleMargin
                    }
                }
                containerView.addView(soundToggleButton, soundParams)
            } else if (stretchButtons) {
                (soundToggleButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = 0
                    weight = 1f
                    marginStart = soundToggleMargin
                }
            } else {
                (soundToggleButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = buttonWidth
                    weight = 0f
                    marginStart = soundToggleMargin
                }
            }
            soundToggleButton.setOnClickListener {
                // Toggle sound and update icon
                val newState = !SettingsManager.isKeyboardSoundEnabled(context)
                SettingsManager.setKeyboardSoundEnabled(context, newState)
                updateSoundToggleButtonIcon(soundToggleButton, newState)
                onSoundToggleListener?.invoke()
            }
            soundToggleButton.alpha = 1f
            soundToggleButton.visibility = View.VISIBLE
            // Update icon based on current state
            updateSoundToggleButtonIcon(soundToggleButton, SettingsManager.isKeyboardSoundEnabled(context))
        } else {
            // Hide/remove sound toggle button when disabled or in Juying mode with suggestions
            if (soundToggleButton.parent != null) {
                (soundToggleButton.parent as? ViewGroup)?.removeView(soundToggleButton)
            }
            soundToggleButton.visibility = View.GONE
        }

        // SYM button - reuse if already attached
        val symButton = symButtonView ?: createSymButton(buttonWidth).also {
            symButtonView = it
        }
        val showSymButtonSetting = SettingsManager.isShowSymButton(context)
        if (hideStatusBarIconsForJuying || !showSymButtonSetting) {
            // Hide SYM button in Juying mode with suggestions or when disabled in settings
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
        } else if (isPinyinModeActive || isT9PinyinModeActive || isShuangpinModeActive || isZiranmaModeActive || isWubiModeActive || isZhenmaModeActive) {
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

        // Traditional Chinese toggle button (简/繁) - show in Chinese mode when enabled in settings
        val isInChineseMode = isPinyinModeActive || isT9PinyinModeActive || isShuangpinModeActive || isZiranmaModeActive || isWubiModeActive || isZhenmaModeActive
        val showTraditionalToggle = isInChineseMode && isTraditionalChineseToggleEnabled && !hideStatusBarIconsForJuying
        if (showTraditionalToggle) {
            val traditionalToggleButton = traditionalChineseToggleButtonView ?: createTraditionalChineseToggleButton(buttonWidth).also {
                traditionalChineseToggleButtonView = it
            }

            // Position after punctuation toggle button (or after language toggle if punctuation is hidden)
            val punctIndex = containerView.indexOfChild(punctuationToggleButtonView)
            val langIndex = containerView.indexOfChild(languageToggleButton)
            val tradIndex = containerView.indexOfChild(traditionalToggleButton)
            val expectedTradIndex = if (punctIndex >= 0) punctIndex + 1 else if (langIndex >= 0) langIndex + 1 else -1

            // Remove and re-add if position is wrong or not attached
            if (traditionalToggleButton.parent != null && tradIndex != expectedTradIndex) {
                (traditionalToggleButton.parent as? ViewGroup)?.removeView(traditionalToggleButton)
            }

            if (traditionalToggleButton.parent == null) {
                val tradParams = if (stretchButtons) {
                    LinearLayout.LayoutParams(0, buttonWidth, 1f).apply {
                        marginStart = buttonMargin
                    }
                } else {
                    LinearLayout.LayoutParams(buttonWidth, buttonWidth).apply {
                        marginStart = buttonMargin
                    }
                }
                // Insert after punctuation toggle (or after language toggle)
                if (expectedTradIndex >= 0) {
                    val insertIndex = minOf(expectedTradIndex, containerView.childCount)
                    containerView.addView(traditionalToggleButton, insertIndex, tradParams)
                } else {
                    containerView.addView(traditionalToggleButton, tradParams)
                }
            } else if (stretchButtons) {
                (traditionalToggleButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = 0
                    weight = 1f
                }
            } else {
                (traditionalToggleButton.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = buttonWidth
                    weight = 0f
                }
            }
            traditionalToggleButton.setOnClickListener {
                onTraditionalChineseToggleListener?.invoke()
            }
            traditionalToggleButton.alpha = 1f
            traditionalToggleButton.visibility = View.VISIBLE
        } else {
            // Hide traditional Chinese toggle when not in Chinese mode or when disabled
            traditionalChineseToggleButtonView?.let { btn ->
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
            // Choose between offline (Sherpa-ONNX) and online (Google) voice recognition
            val useOffline = SettingsManager.isOfflineVoiceInput(context)

            val intent = if (useOffline) {
                // Use Sherpa-ONNX for offline voice recognition
                Intent(context, SherpaSpeechActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
            } else {
                Intent(context, SpeechRecognitionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_HISTORY or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
            }
            context.startActivity(intent)
            Log.d(TAG, "Speech recognition started (offline=$useOffline)")
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

    private fun removeKeyboardToggleImmediate() {
        keyboardToggleButtonView?.let { toggle ->
            (toggle.parent as? ViewGroup)?.removeView(toggle)
            toggle.visibility = View.GONE
            toggle.alpha = 1f
        }
    }

    private fun removeSoundToggleImmediate() {
        soundToggleButtonView?.let { toggle ->
            (toggle.parent as? ViewGroup)?.removeView(toggle)
            toggle.visibility = View.GONE
            toggle.alpha = 1f
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
        buttonHeight: Int,
        showNumbered: Boolean = false,
        candidateNumber: Int = 0,
        isWordPrediction: Boolean = false,
        wordPredictionPrefixLength: Int = 0,
        isPinyinMode: Boolean = false,
        isShuangpinMode: Boolean = false,
        isZiranmaMode: Boolean = false,
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

        // Check if semi-transparent mode is enabled
        val isSemiTransparent = SettingsManager.isSemiTransparentStatusBar(context)
        val theme = getCurrentTheme()

        // Use highlighted background for best candidate in Juying mode (golden/yellow tint)
        val normalColor = if (isSemiTransparent) {
            // Transparent background when semi-transparent mode is on
            if (isBestCandidate) {
                Color.argb(100, Color.red(theme.candidateBestBackgroundColor), Color.green(theme.candidateBestBackgroundColor), Color.blue(theme.candidateBestBackgroundColor))
            } else {
                Color.argb(100, Color.red(theme.candidateBackgroundColor), Color.green(theme.candidateBackgroundColor), Color.blue(theme.candidateBackgroundColor))
            }
        } else {
            if (isBestCandidate) {
                theme.candidateBestBackgroundColor
            } else {
                theme.candidateBackgroundColor
            }
        }
        val drawable = GradientDrawable().apply {
            setColor(normalColor)
            cornerRadius = 0f
        }
        val pressedColor = if (isSemiTransparent) Color.argb(100, Color.red(theme.buttonPressedColor), Color.green(theme.buttonPressedColor), Color.blue(theme.buttonPressedColor)) else theme.buttonPressedColor
        val pressedDrawable = GradientDrawable().apply {
            setColor(pressedColor)
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
        // Use user's font size setting as base
        val baseFontSize = SettingsManager.getCandidateFontSize(context).toFloat()
        val textSizeSp = when {
            !showNumbered -> baseFontSize - 0.4f  // Accent variations - single chars
            displayText.length <= 3 -> baseFontSize  // Short (e.g., "1我" or "1go")
            displayText.length <= 5 -> baseFontSize - 2f  // Medium words
            displayText.length <= 7 -> baseFontSize - 4f  // Longer words
            displayText.length <= 9 -> baseFontSize - 6f  // Even longer words
            else -> baseFontSize - 8f  // Very long words
        }.coerceAtLeast(10f)  // Minimum 10sp

        // Check if this is a punctuation button (comma or period in next word prediction mode)
        val isPunctuationButton = variation in listOf(",", ".", "，", "。")

        // Choose the appropriate click listener based on mode
        val clickListener = when {
            isPunctuationButton -> {
                // Punctuation button - just commit the punctuation directly
                View.OnClickListener {
                    inputConnection?.commitText(variation, 1)
                    onPunctuationButtonListener?.invoke(variation)
                }
            }
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
            isZiranmaMode -> {
                // Ziranma candidate - just commit (replaces composing text automatically)
                VariationButtonHandler.createZiranmaCandidateClickListener(
                    variation,
                    candidateIndex,
                    inputConnection,
                    onZiranmaCandidateSelectedListener,
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
        val shouldVibrate = isPunctuationButton || isWordPrediction || isPinyinMode || isShuangpinMode || isZiranmaMode || isWubiMode || isZhenmaMode

        // Use golden/yellow text for best candidate in Juying mode
        // Apply transparency when semi-transparent mode is enabled
        val textColor = if (isSemiTransparent) {
            if (isBestCandidate) {
                Color.argb(180, Color.red(theme.candidateBestTextColor), Color.green(theme.candidateBestTextColor), Color.blue(theme.candidateBestTextColor))
            } else {
                Color.argb(180, Color.red(theme.candidateTextColor), Color.green(theme.candidateTextColor), Color.blue(theme.candidateTextColor))
            }
        } else {
            if (isBestCandidate) {
                theme.candidateBestTextColor
            } else {
                theme.candidateTextColor
            }
        }

        // Check if 3D effect is enabled
        val is3DEffect = SettingsManager.is3DEffectEnabled(context)

        return TextView(context).apply {
            text = displayText
            setTextColor(textColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            // Use equal vertical padding and disable font padding for better vertical centering
            includeFontPadding = false
            setPadding(dp6, dp4, dp6, dp4)

            // Apply 3D effect with shadow and layered background
            if (is3DEffect) {
                // Create 3D layered background drawable
                val dp2 = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    2f,
                    context.resources.displayMetrics
                ).toInt()

                // Shadow layer (darker, offset down-right)
                val shadowColor = Color.argb(
                    120,
                    (Color.red(normalColor) * 0.4f).toInt(),
                    (Color.green(normalColor) * 0.4f).toInt(),
                    (Color.blue(normalColor) * 0.4f).toInt()
                )
                val shadowDrawable = GradientDrawable().apply {
                    setColor(shadowColor)
                    cornerRadius = dp3.toFloat()
                }

                // Highlight layer (lighter, for top edge)
                val highlightColor = Color.argb(
                    80,
                    minOf(Color.red(normalColor) + 60, 255),
                    minOf(Color.green(normalColor) + 60, 255),
                    minOf(Color.blue(normalColor) + 60, 255)
                )

                // Main button with gradient for 3D effect
                val topColor = Color.argb(
                    Color.alpha(normalColor),
                    minOf(Color.red(normalColor) + 30, 255),
                    minOf(Color.green(normalColor) + 30, 255),
                    minOf(Color.blue(normalColor) + 30, 255)
                )
                val bottomColor = Color.argb(
                    Color.alpha(normalColor),
                    maxOf(Color.red(normalColor) - 20, 0),
                    maxOf(Color.green(normalColor) - 20, 0),
                    maxOf(Color.blue(normalColor) - 20, 0)
                )
                val mainDrawable = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(topColor, normalColor, bottomColor)
                ).apply {
                    cornerRadius = dp3.toFloat()
                }

                // Layer drawable: shadow behind, main on top
                val layers = android.graphics.drawable.LayerDrawable(arrayOf(shadowDrawable, mainDrawable))
                layers.setLayerInset(0, dp2, dp2, 0, 0)  // Shadow offset
                layers.setLayerInset(1, 0, 0, dp2, dp2)  // Main button offset

                // Pressed state - flatter look
                val pressedGradient = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(bottomColor, normalColor, topColor)
                ).apply {
                    cornerRadius = dp3.toFloat()
                }
                val pressedLayers = android.graphics.drawable.LayerDrawable(arrayOf(shadowDrawable, pressedGradient))
                pressedLayers.setLayerInset(0, dp2, dp2, 0, 0)
                pressedLayers.setLayerInset(1, dp2/2, dp2/2, dp2/2, dp2/2)  // Smaller offset when pressed

                val stateList3D = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), pressedLayers)
                    addState(intArrayOf(), layers)
                }
                background = stateList3D

                // Add text shadow for 3D text effect
                setShadowLayer(2f, 1f, 1f, Color.argb(100, 0, 0, 0))
            } else {
                background = stateListDrawable
            }

            // For suggestion phrases, auto-adjust font size to fit button with multi-line wrapping
            // Use font size settings from configuration
            val minReadableFontSizeSp = SettingsManager.getSuggestionMinFontSize(context).toFloat()
            val maxFontSizeSp = SettingsManager.getCandidateFontSize(context).toFloat()

            // Calculate available space
            val availableTextWidth = buttonWidth - dp6 * 2
            val availableTextHeight = buttonHeight - dp4 * 2

            val testPaint = android.graphics.Paint().apply {
                textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    maxFontSizeSp,
                    context.resources.displayMetrics
                )
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            // Use WRAP_CONTENT for height to allow multi-line text display
            layoutParams = LinearLayout.LayoutParams(buttonWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp3
                gravity = Gravity.CENTER_VERTICAL
            }

            // Calculate how many lines can fit at max font size
            val maxFontMetrics = testPaint.fontMetrics
            val lineHeightAtMaxFont = maxFontMetrics.bottom - maxFontMetrics.top
            val maxLinesAvailable = (availableTextHeight / lineHeightAtMaxFont).toInt().coerceAtLeast(1)

            // Find the best font size that fits all text using available lines
            var bestFontSizeSp = minReadableFontSizeSp
            var bestLines = 1

            // Try each font size from max to min, prefer larger fonts with more lines
            for (testSizeSp in maxFontSizeSp.toInt() downTo minReadableFontSizeSp.toInt()) {
                val testSizePx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    testSizeSp.toFloat(),
                    context.resources.displayMetrics
                )
                testPaint.textSize = testSizePx
                val testWidth = testPaint.measureText(displayText)
                val testMetrics = testPaint.fontMetrics
                val testLineHeight = testMetrics.bottom - testMetrics.top

                // Calculate how many lines we can fit at this font size
                val linesAvailableAtThisSize = (availableTextHeight / testLineHeight).toInt().coerceAtLeast(1)

                // Calculate how many lines we need at this font size
                val linesNeeded = kotlin.math.ceil(testWidth / availableTextWidth.toDouble()).toInt()

                // Check if text fits with available lines
                if (linesNeeded <= linesAvailableAtThisSize) {
                    bestFontSizeSp = testSizeSp.toFloat()
                    bestLines = linesNeeded.coerceAtLeast(1)
                    break
                }
            }

            // Apply the settings for multi-line wrapping
            isSingleLine = false
            setSingleLine(false)
            maxLines = 10  // Allow up to 10 lines
            ellipsize = null  // No ellipsize - show all text
            textSize = bestFontSizeSp
            // Use SIMPLE break strategy - breaks at any character (works for both Chinese and English)
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE

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
        val theme = getCurrentTheme()
        val drawable = GradientDrawable().apply {
            setColor(theme.backgroundColor)
            cornerRadius = 0f
        }
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_mic_24)
            setColorFilter(theme.iconColor)
            background = drawable
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun createStatusBarSettingsButton(buttonSize: Int): ImageView {
        val theme = getCurrentTheme()
        val dp3 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(theme.backgroundColor)
            cornerRadius = 0f
        }
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_settings_24)
            setColorFilter(theme.iconInactiveColor)
            background = drawable
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            setPadding(dp3, dp3, dp3, dp3)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun createClipboardButton(buttonSize: Int): ImageView {
        val theme = getCurrentTheme()
        val dp3 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(theme.backgroundColor)
            cornerRadius = 0f
        }
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_clipboard_24)
            setColorFilter(theme.iconInactiveColor)
            background = drawable
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            setPadding(dp3, dp3, dp3, dp3)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun createSoundToggleButton(buttonSize: Int): ImageView {
        val theme = getCurrentTheme()
        val dp3 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context.resources.displayMetrics
        ).toInt()
        val isSoundEnabled = SettingsManager.isKeyboardSoundEnabled(context)
        return ImageView(context).apply {
            setImageResource(if (isSoundEnabled) R.drawable.ic_volume_up_24 else R.drawable.ic_volume_off_24)
            setColorFilter(if (isSoundEnabled) theme.accentColor else theme.iconInactiveColor)
            // No background - transparent to work with any theme
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            setPadding(dp3, dp3, dp3, dp3)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun updateSoundToggleButtonIcon(button: ImageView, isEnabled: Boolean) {
        val theme = getCurrentTheme()
        button.setImageResource(if (isEnabled) R.drawable.ic_volume_up_24 else R.drawable.ic_volume_off_24)
        button.setColorFilter(if (isEnabled) theme.accentColor else theme.iconInactiveColor)
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
        val theme = getCurrentTheme()
        val dp2 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(theme.backgroundColor)
            cornerRadius = 0f
        }
        return TextView(context).apply {
            text = "SYM"
            textSize = 10f
            setTextColor(theme.textColor)
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
        val theme = getCurrentTheme()
        val dp2 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(theme.backgroundColor)
            cornerRadius = 0f
        }
        return TextView(context).apply {
            text = when {
                isPinyinModeActive -> "拼"
                isT9PinyinModeActive -> "T9"
                isShuangpinModeActive -> "双"
                isZiranmaModeActive -> "自"
                isWubiModeActive -> "五"
                isZhenmaModeActive -> "真"
                else -> "EN"
            }
            textSize = 12f
            setTextColor(when {
                isPinyinModeActive || isT9PinyinModeActive || isShuangpinModeActive || isZiranmaModeActive || isWubiModeActive || isZhenmaModeActive -> theme.accentColor
                else -> theme.textColor
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
        val theme = getCurrentTheme()
        languageToggleButtonView?.apply {
            text = when {
                isPinyinModeActive -> "拼"
                isT9PinyinModeActive -> "T9"
                isShuangpinModeActive -> "双"
                isZiranmaModeActive -> "自"
                isWubiModeActive -> "五"
                isZhenmaModeActive -> "真"
                else -> "EN"
            }
            setTextColor(when {
                isPinyinModeActive || isT9PinyinModeActive || isShuangpinModeActive || isZiranmaModeActive || isWubiModeActive || isZhenmaModeActive -> theme.accentColor
                else -> theme.textColor
            })
        }
    }

    private fun createPunctuationToggleButton(buttonSize: Int): TextView {
        val theme = getCurrentTheme()
        val dp2 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(theme.backgroundColor)
            cornerRadius = 0f
        }
        return TextView(context).apply {
            text = if (isChinesePunctuationMode) "。" else "."
            textSize = 14f
            setTextColor(if (isChinesePunctuationMode) theme.accentColor else theme.textColor)
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
        val theme = getCurrentTheme()
        punctuationToggleButtonView?.apply {
            text = if (isChinesePunctuationMode) "。" else "."
            setTextColor(if (isChinesePunctuationMode) theme.accentColor else theme.textColor)
        }
    }

    private fun createTraditionalChineseToggleButton(buttonSize: Int): TextView {
        val theme = getCurrentTheme()
        val dp2 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(theme.backgroundColor)
            cornerRadius = 0f
        }
        return TextView(context).apply {
            text = if (isTraditionalChineseMode) "繁" else "简"
            textSize = 14f
            setTextColor(if (isTraditionalChineseMode) theme.ledLockedColor else theme.accentColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = drawable
            isClickable = true
            isFocusable = true
            setPadding(dp2, dp2, dp2, dp2)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun updateTraditionalChineseToggleButton() {
        val theme = getCurrentTheme()
        traditionalChineseToggleButtonView?.apply {
            text = if (isTraditionalChineseMode) "繁" else "简"
            setTextColor(if (isTraditionalChineseMode) theme.ledLockedColor else theme.accentColor)
        }
    }

    private fun removeTraditionalChineseToggleImmediate() {
        traditionalChineseToggleButtonView?.let { toggle ->
            (toggle.parent as? ViewGroup)?.removeView(toggle)
            toggle.visibility = View.GONE
            toggle.alpha = 1f
        }
    }

    private fun createArrowButton(buttonSize: Int, isNext: Boolean): ImageView {
        val theme = getCurrentTheme()
        val drawable = GradientDrawable().apply {
            setColor(theme.candidateBackgroundColor)
            cornerRadius = 0f
        }
        return ImageView(context).apply {
            // Use chevron icons - right arrow for next, left arrow for prev
            setImageResource(
                if (isNext) R.drawable.ic_chevron_right_24
                else R.drawable.ic_chevron_left_24
            )
            setColorFilter(theme.iconColor)
            background = drawable
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            minimumWidth = buttonSize
            minimumHeight = buttonSize
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun createKeyboardToggleButton(buttonSize: Int): ImageView {
        val theme = getCurrentTheme()
        val dp3 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context.resources.displayMetrics
        ).toInt()
        val drawable = GradientDrawable().apply {
            setColor(theme.backgroundColor)
            cornerRadius = 0f
        }
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_keyboard_24)
            // Color depends on virtual keyboard state
            val isEnabled = SettingsManager.isVirtualKeyboardEnabled(context)
            setColorFilter(if (isEnabled) theme.iconColor else theme.iconInactiveColor)
            background = drawable
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            setPadding(dp3, dp3, dp3, dp3)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    fun setOnVirtualKeyboardToggleListener(listener: (() -> Unit)?) {
        onVirtualKeyboardToggleListener = listener
    }

    fun updateKeyboardToggleButtonState() {
        keyboardToggleButtonView?.let { btn ->
            val theme = getCurrentTheme()
            val isEnabled = SettingsManager.isVirtualKeyboardEnabled(context)
            btn.setColorFilter(if (isEnabled) theme.iconColor else theme.iconInactiveColor)
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

    /**
     * Refreshes theme colors on all child views.
     */
    fun refreshTheme() {
        val theme = getCurrentTheme()

        // Refresh wrapper background (transparent)
        wrapper?.setBackgroundColor(Color.TRANSPARENT)

        // Refresh container background (transparent)
        container?.setBackgroundColor(Color.TRANSPARENT)

        // Refresh all variation buttons
        variationButtons.forEach { button ->
            button.setTextColor(theme.candidateTextColor)
            (button.background as? GradientDrawable)?.setColor(theme.candidateBackgroundColor)
            button.invalidate()
        }

        // Refresh SYM button
        symButtonView?.let { btn ->
            btn.setTextColor(if (isSymModeActive) theme.accentColor else theme.textColor)
            (btn.background as? GradientDrawable)?.setColor(theme.backgroundColor)
            btn.invalidate()
        }

        // Refresh language toggle button
        languageToggleButtonView?.let { btn ->
            val isChineseMode = isPinyinModeActive || isT9PinyinModeActive || isShuangpinModeActive || isZiranmaModeActive || isWubiModeActive || isZhenmaModeActive
            btn.setTextColor(if (isChineseMode) theme.accentColor else theme.textColor)
            (btn.background as? GradientDrawable)?.setColor(theme.backgroundColor)
            btn.invalidate()
        }

        // Refresh punctuation toggle button
        punctuationToggleButtonView?.let { btn ->
            btn.setTextColor(if (isChinesePunctuationMode) theme.accentColor else theme.textColor)
            (btn.background as? GradientDrawable)?.setColor(theme.backgroundColor)
            btn.invalidate()
        }

        // Refresh traditional Chinese toggle button
        traditionalChineseToggleButtonView?.let { btn ->
            btn.setTextColor(if (isTraditionalChineseMode) theme.ledLockedColor else theme.accentColor)
            (btn.background as? GradientDrawable)?.setColor(theme.backgroundColor)
            btn.invalidate()
        }

        // Refresh arrow buttons
        prevArrowButton?.let { btn ->
            btn.setColorFilter(theme.iconColor)
            (btn.background as? GradientDrawable)?.setColor(theme.candidateBackgroundColor)
            btn.invalidate()
        }
        nextArrowButton?.let { btn ->
            btn.setColorFilter(theme.iconColor)
            (btn.background as? GradientDrawable)?.setColor(theme.candidateBackgroundColor)
            btn.invalidate()
        }

        // Refresh microphone button - create new drawable to ensure update
        microphoneButtonView?.let { btn ->
            btn.setColorFilter(theme.iconColor)
            val newDrawable = GradientDrawable().apply {
                setColor(theme.backgroundColor)
                cornerRadius = 0f
            }
            btn.background = newDrawable
            btn.invalidate()
        }

        // Refresh settings button - create new drawable to ensure update
        settingsButtonView?.let { btn ->
            btn.setColorFilter(theme.iconInactiveColor)
            val newDrawable = GradientDrawable().apply {
                setColor(theme.backgroundColor)
                cornerRadius = 0f
            }
            btn.background = newDrawable
            btn.invalidate()
        }

        // Refresh clipboard button - create new drawable to ensure update
        clipboardButtonView?.let { btn ->
            btn.setColorFilter(theme.iconInactiveColor)
            val newDrawable = GradientDrawable().apply {
                setColor(theme.backgroundColor)
                cornerRadius = 0f
            }
            btn.background = newDrawable
            btn.invalidate()
        }

        // Refresh keyboard toggle button
        keyboardToggleButtonView?.let { btn ->
            val isEnabled = SettingsManager.isVirtualKeyboardEnabled(context)
            btn.setColorFilter(if (isEnabled) theme.iconColor else theme.iconInactiveColor)
            val newDrawable = GradientDrawable().apply {
                setColor(theme.backgroundColor)
                cornerRadius = 0f
            }
            btn.background = newDrawable
            btn.invalidate()
        }

        // Force the entire view hierarchy to redraw
        wrapper?.invalidate()
        container?.invalidate()
    }
}

