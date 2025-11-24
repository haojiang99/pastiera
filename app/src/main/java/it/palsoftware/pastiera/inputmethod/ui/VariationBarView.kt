package it.palsoftware.pastiera.inputmethod.ui

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
import it.palsoftware.pastiera.R
import it.palsoftware.pastiera.SettingsActivity
import it.palsoftware.pastiera.inputmethod.StatusBarController
import it.palsoftware.pastiera.inputmethod.TextSelectionHelper
import it.palsoftware.pastiera.inputmethod.VariationButtonHandler
import it.palsoftware.pastiera.inputmethod.SpeechRecognitionActivity
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
    var onCursorMovedListener: (() -> Unit)? = null
    var onNextPageListener: (() -> Unit)? = null
    var onPrevPageListener: (() -> Unit)? = null

    private var wrapper: FrameLayout? = null
    private var prevArrowButton: ImageView? = null
    private var nextArrowButton: ImageView? = null
    private var container: LinearLayout? = null
    private var overlay: View? = null
    private var currentVariationsRow: LinearLayout? = null
    private var variationButtons: MutableList<TextView> = mutableListOf()
    private var microphoneButtonView: ImageView? = null
    private var settingsButtonView: ImageView? = null
    private var lastDisplayedVariations: List<String> = emptyList()
    private var isSymModeActive = false
    private var isSwipeInProgress = false
    private var swipeDirection: Int? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastCursorMoveX = 0f
    private var currentInputConnection: android.view.inputmethod.InputConnection? = null

    fun ensureView(): View {
        if (container != null) {
            return container!!
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

        // DEBUG: Skip the FrameLayout wrapper entirely - just use container directly
        container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(leftPadding, variationsVerticalPadding, rightPadding, variationsVerticalPadding)
            // Use LinearLayout.LayoutParams since parent is now the statusBarLayout (a LinearLayout)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                variationsContainerHeight
            )
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }

        // Use container as wrapper (no FrameLayout)
        wrapper = container as? FrameLayout  // Will be null, that's OK for this test

        overlay = View(context)  // Dummy overlay

        return container!!
    }

    fun getWrapper(): FrameLayout? = wrapper

    fun setSymModeActive(active: Boolean) {
        isSymModeActive = active
        if (active) {
            overlay?.visibility = View.GONE
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
        container?.visibility = View.GONE
        wrapper?.visibility = View.GONE
        overlay?.visibility = View.GONE
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

        currentInputConnection = inputConnection
        containerView.visibility = View.VISIBLE

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

        variationButtons.clear()
        currentVariationsRow?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }
        currentVariationsRow = null

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
        val showNumberedButtons = snapshot.pinyinModeActive || snapshot.wordPredictionActive
        val textSizeSp = if (showNumberedButtons) 18f else 17.6f
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
        // English: 5 on page 1, 4 on pages 2+
        // Pinyin: 9 on page 1, 8 on pages 2+
        val maxSuggestionsToShow = if (snapshot.currentPage > 0) {
            if (snapshot.wordPredictionActive) 4 else 8
        } else {
            snapshot.variations.size
        }
        val variationsToProcess = snapshot.variations.take(maxSuggestionsToShow)

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

        val buttonWidth = if (buttonWidths.isNotEmpty()) buttonWidths[0] else minButtonWidth

        val variationsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        currentVariationsRow = variationsRow

        // Calculate explicit width for variationsRow (total width used)
        val variationsRowWidth = totalWidth
        val buttonHeight = buttonWidth // Use first button's width as height for square buttons
        val rowLayoutParams = LinearLayout.LayoutParams(
            variationsRowWidth,
            buttonHeight
        )
        containerView.addView(variationsRow, 0, rowLayoutParams)

        // Force measure and layout the variationsRow
        val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(variationsRowWidth, View.MeasureSpec.EXACTLY)
        val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(buttonHeight, View.MeasureSpec.EXACTLY)
        variationsRow.measure(widthMeasureSpec, heightMeasureSpec)
        variationsRow.layout(0, 0, variationsRowWidth, buttonHeight)

        lastDisplayedVariations = limitedVariations.toList()

        val wordPredictionPrefixLength = if (snapshot.wordPredictionActive) snapshot.wordPredictionPrefix.length else 0
        var buttonX = 0
        for ((index, variation) in limitedVariations.withIndex()) {
            val individualButtonWidth = buttonWidths[index]
            val button = createVariationButton(
                variation, inputConnection, individualButtonWidth, showNumberedButtons, index + 1,
                snapshot.wordPredictionActive, wordPredictionPrefixLength
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

        // Add navigation arrows when in Pinyin or word prediction mode with suggestions
        val showPagination = (snapshot.pinyinModeActive || snapshot.wordPredictionActive) &&
                             snapshot.variations.isNotEmpty()

        // Smaller arrow button size
        val arrowButtonSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            32f,
            context.resources.displayMetrics
        ).toInt()

        if (showPagination) {
            // Count existing children to determine proper insertion points
            var currentChildCount = containerView.childCount

            // Previous page arrow (left) - only show if there's a previous page
            if (snapshot.hasPrevPage) {
                val prevArrow = prevArrowButton ?: createArrowButton(arrowButtonSize, isNext = false)
                prevArrowButton = prevArrow
                (prevArrow.parent as? ViewGroup)?.removeView(prevArrow)
                val prevParams = LinearLayout.LayoutParams(arrowButtonSize, arrowButtonSize).apply {
                    marginStart = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        2f,
                        context.resources.displayMetrics
                    ).toInt()
                }
                // Insert at the beginning (before variations row)
                containerView.addView(prevArrow, 0, prevParams)
                prevArrow.alpha = 1f
                prevArrow.isClickable = true
                prevArrow.setOnClickListener {
                    onPrevPageListener?.invoke()
                }
                prevArrow.visibility = View.VISIBLE
            } else {
                // Hide left arrow on first page
                prevArrowButton?.let {
                    (it.parent as? ViewGroup)?.removeView(it)
                    it.visibility = View.GONE
                }
            }

            // Next page arrow (right) - show if there's a next page
            if (snapshot.hasNextPage) {
                val nextArrow = nextArrowButton ?: createArrowButton(arrowButtonSize, isNext = true)
                nextArrowButton = nextArrow
                (nextArrow.parent as? ViewGroup)?.removeView(nextArrow)
                val nextParams = LinearLayout.LayoutParams(arrowButtonSize, arrowButtonSize).apply {
                    marginEnd = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        2f,
                        context.resources.displayMetrics
                    ).toInt()
                }
                // Insert after variations row (at index 1 if prevArrow exists, else 0)
                // But before microphone button which will be added later
                val insertIndex = if (snapshot.hasPrevPage) 2 else 1
                containerView.addView(nextArrow, insertIndex, nextParams)
                nextArrow.alpha = 1f
                nextArrow.isClickable = true
                nextArrow.setOnClickListener {
                    onNextPageListener?.invoke()
                }
                nextArrow.visibility = View.VISIBLE
            } else {
                // Hide right arrow on last page
                nextArrowButton?.let {
                    (it.parent as? ViewGroup)?.removeView(it)
                    it.visibility = View.GONE
                }
            }
        } else {
            // Remove arrow buttons if not in pagination mode
            prevArrowButton?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.visibility = View.GONE
            }
            nextArrowButton?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.visibility = View.GONE
            }
        }

        val microphoneButton = microphoneButtonView ?: createMicrophoneButton(buttonWidth)
        microphoneButtonView = microphoneButton
        (microphoneButton.parent as? ViewGroup)?.removeView(microphoneButton)
        val micParams = LinearLayout.LayoutParams(buttonWidth, buttonWidth)
        containerView.addView(microphoneButton, micParams)
        microphoneButton.setOnClickListener {
            startSpeechRecognition(inputConnection)
        }
        microphoneButton.alpha = 1f
        microphoneButton.visibility = View.VISIBLE

        val settingsButton = settingsButtonView ?: createStatusBarSettingsButton(buttonWidth)
        settingsButtonView = settingsButton
        (settingsButton.parent as? ViewGroup)?.removeView(settingsButton)
        val settingsParams = LinearLayout.LayoutParams(buttonWidth, buttonWidth).apply {
            topMargin = (-buttonWidth * 0.1f).toInt()
        }
        containerView.addView(settingsButton, settingsParams)
        settingsButton.setOnClickListener {
            openSettings()
        }
        settingsButton.alpha = 1f
        settingsButton.visibility = View.VISIBLE

        if (variationsChanged) {
            animateVariationsIn(variationsRow)
        } else {
            variationsRow.alpha = 1f
            variationsRow.visibility = View.VISIBLE
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

                    if (isSwipeInProgress || (abs(deltaX) > swipeThreshold && abs(deltaX) > deltaY)) {
                        if (!isSwipeInProgress) {
                            isSwipeInProgress = true
                            swipeDirection = if (deltaX > 0) 1 else -1
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
                    isSwipeInProgress = false
                    swipeDirection = null
                    true
                }
                else -> true
            }
        }
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

    private fun createVariationButton(
        variation: String,
        inputConnection: android.view.inputmethod.InputConnection?,
        buttonWidth: Int,
        showNumbered: Boolean = false,
        candidateNumber: Int = 0,
        isWordPrediction: Boolean = false,
        wordPredictionPrefixLength: Int = 0
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

        val drawable = GradientDrawable().apply {
            setColor(Color.rgb(17, 17, 17))
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
        val clickListener = if (isWordPrediction) {
            VariationButtonHandler.createWordPredictionClickListener(
                variation,
                wordPredictionPrefixLength,
                inputConnection,
                onVariationSelectedListener
            )
        } else {
            VariationButtonHandler.createVariationClickListener(
                variation,
                inputConnection,
                onVariationSelectedListener
            )
        }

        return TextView(context).apply {
            text = displayText
            textSize = textSizeSp
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp6, dp4, dp6, dp4)
            background = stateListDrawable
            layoutParams = LinearLayout.LayoutParams(buttonWidth, buttonHeight).apply {
                marginEnd = dp3
            }
            maxLines = 1
            isClickable = true
            isFocusable = true
            setOnClickListener(clickListener)
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
            setColor(Color.rgb(17, 17, 17))
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
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_settings_24)
            setColorFilter(Color.rgb(100, 100, 100))
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            setPadding(dp3, dp3, dp3, dp3)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
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

