package it.neuralrad.coolwulf.inputmethod.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Virtual (on-screen) keyboard for devices without physical keyboards.
 * Provides a standard QWERTY layout with shift, backspace, enter, and space keys.
 */
class VirtualKeyboardView(
    private val context: Context,
    private val onKeyPress: (keyCode: Int, isShifted: Boolean) -> Unit,
    private val onCharacterInput: (char: Char) -> Unit
) {
    companion object {
        private val KEY_BG_COLOR = Color.argb(255, 60, 60, 65)
        private val KEY_BG_PRESSED = Color.argb(255, 100, 100, 110)
        private val KEY_BG_SPECIAL = Color.argb(255, 45, 45, 50)
        private val KEY_TEXT_COLOR = Color.WHITE
        private val KEYBOARD_BG_COLOR = Color.argb(255, 30, 30, 35)

        // QWERTY layout rows
        private val ROW_1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        private val ROW_2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        private val ROW_3 = listOf("z", "x", "c", "v", "b", "n", "m")

        // Alt mode symbol mappings - numbers on top row
        private val ALT_ROW_1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        private val ALT_ROW_2 = listOf("@", "#", "$", "%", "&", "*", "(", ")", "_")
        private val ALT_ROW_3 = listOf("-", "+", "=", ":", ";", "/", "?")
    }

    private var container: LinearLayout? = null
    private var isShifted = false
    private var isCapsLock = false
    private var isAltMode = false
    private var isAltLocked = false
    private var shiftKey: TextView? = null
    private var altKey: TextView? = null

    private val keyHeight: Int by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            42f,
            context.resources.displayMetrics
        ).toInt()
    }

    private val keyMargin: Int by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()
    }

    private val cornerRadius: Float by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            5f,
            context.resources.displayMetrics
        )
    }

    private val keyTextSize: Float by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            18f,
            context.resources.displayMetrics
        )
    }

    private val specialKeyTextSize: Float by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            context.resources.displayMetrics
        )
    }

    fun ensureView(): LinearLayout {
        container?.let { return it }

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KEYBOARD_BG_COLOR)
            val padding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                4f,
                context.resources.displayMetrics
            ).toInt()
            setPadding(padding, padding, padding, padding)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Add QWERTY rows (no number row - use 123/Alt for numbers)
        container?.addView(createKeyRow(ROW_1))
        container?.addView(createKeyRow(ROW_2, sidePadding = true))
        container?.addView(createRow3WithShiftAndBackspace())
        container?.addView(createBottomRow())

        return container!!
    }

    fun getView(): LinearLayout? = container

    private fun createKeyRow(keys: List<String>, sidePadding: Boolean = false): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            if (sidePadding) {
                // Add side padding for row 2 (ASDF row)
                val sidePad = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    15f,
                    context.resources.displayMetrics
                ).toInt()
                setPadding(sidePad, 0, sidePad, 0)
            }

            keys.forEach { key ->
                addView(createCharacterKey(key))
            }
        }
    }

    private fun createRow3WithShiftAndBackspace(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Shift key
            shiftKey = createSpecialKey("⇧", 1.3f) {
                if (isCapsLock) {
                    // Disable caps lock
                    isCapsLock = false
                    isShifted = false
                } else if (isShifted) {
                    // Double tap - enable caps lock
                    isCapsLock = true
                } else {
                    // Single tap - enable shift
                    isShifted = true
                }
                updateShiftKeyAppearance()
            }
            addView(shiftKey)

            // Letter keys
            ROW_3.forEach { key ->
                addView(createCharacterKey(key))
            }

            // Backspace key
            addView(createSpecialKey("⌫", 1.3f) {
                onKeyPress(KeyEvent.KEYCODE_DEL, false)
            })
        }
    }

    private fun createBottomRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Alt/Symbols toggle key (123 button)
            altKey = createAltKey()
            addView(altKey)

            // Comma key
            addView(createCharacterKey(",", 0.8f))

            // Space bar
            addView(createSpaceBar())

            // Period key
            addView(createCharacterKey(".", 0.8f))

            // Enter key
            addView(createSpecialKey("↵", 1.2f) {
                onKeyPress(KeyEvent.KEYCODE_ENTER, false)
            })
        }
    }

    private fun createAltKey(): TextView {
        return TextView(context).apply {
            text = "123"
            setTextColor(KEY_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, specialKeyTextSize)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createKeyBackground(KEY_BG_SPECIAL)

            layoutParams = LinearLayout.LayoutParams(
                0,
                keyHeight,
                1.2f
            ).apply {
                setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        background = createKeyBackground(KEY_BG_PRESSED)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isAltLocked) {
                            // Disable alt lock
                            isAltLocked = false
                            isAltMode = false
                        } else if (isAltMode) {
                            // Double tap - enable alt lock
                            isAltLocked = true
                        } else {
                            // Single tap - enable alt mode
                            isAltMode = true
                        }
                        updateAltKeyAppearance()
                        updateAllKeyLabels()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        background = createKeyBackground(
                            when {
                                isAltLocked -> Color.argb(255, 100, 150, 255)  // Blue for alt lock
                                isAltMode -> Color.argb(255, 80, 80, 90)       // Lighter for alt
                                else -> KEY_BG_SPECIAL
                            }
                        )
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun updateAltKeyAppearance() {
        altKey?.apply {
            text = when {
                isAltLocked -> "ABC"  // Show ABC when locked in alt mode
                isAltMode -> "ABC"    // Show ABC when alt active
                else -> "123"         // Normal
            }
            background = createKeyBackground(
                when {
                    isAltLocked -> Color.argb(255, 100, 150, 255)  // Blue for alt lock (same as caps lock)
                    isAltMode -> Color.argb(255, 80, 80, 90)       // Lighter for alt (same as shift)
                    else -> KEY_BG_SPECIAL
                }
            )
        }
    }

    private fun createCharacterKey(char: String, weight: Float = 1f): TextView {
        return TextView(context).apply {
            val displayText = getDisplayText(char)
            text = displayText
            tag = char // Store original lowercase char
            setTextColor(KEY_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, keyTextSize)
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            background = createKeyBackground(KEY_BG_COLOR)

            layoutParams = LinearLayout.LayoutParams(
                0,
                keyHeight,
                weight
            ).apply {
                setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        background = createKeyBackground(KEY_BG_PRESSED)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        background = createKeyBackground(KEY_BG_COLOR)
                        val originalChar = (v.tag as String)
                        val outputText = getOutputText(originalChar)

                        // Output each character (for multi-char symbols like \\)
                        outputText.forEach { c ->
                            onCharacterInput(c)
                        }

                        // Clear shift after typing (unless caps lock)
                        if (isShifted && !isCapsLock) {
                            isShifted = false
                            updateShiftKeyAppearance()
                            updateAllKeyLabels()
                        }

                        // Clear alt mode after typing (unless alt locked)
                        if (isAltMode && !isAltLocked) {
                            isAltMode = false
                            updateAltKeyAppearance()
                            updateAllKeyLabels()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        background = createKeyBackground(KEY_BG_COLOR)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun getDisplayText(char: String): String {
        if (isAltMode || isAltLocked) {
            val altChar = getAltSymbol(char)
            if (altChar != null) return altChar
        }
        return if (isShifted || isCapsLock) char.uppercase() else char
    }

    private fun getOutputText(char: String): String {
        if (isAltMode || isAltLocked) {
            val altChar = getAltSymbol(char)
            if (altChar != null) return altChar
        }
        return if (isShifted || isCapsLock) char.uppercase() else char
    }

    private fun getAltSymbol(char: String): String? {
        // Check row 1 (QWERTY row -> numbers in alt mode)
        val row1Index = ROW_1.indexOf(char.lowercase())
        if (row1Index >= 0 && row1Index < ALT_ROW_1.size) {
            return ALT_ROW_1[row1Index]
        }

        // Check row 2 (ASDF row -> symbols in alt mode)
        val row2Index = ROW_2.indexOf(char.lowercase())
        if (row2Index >= 0 && row2Index < ALT_ROW_2.size) {
            return ALT_ROW_2[row2Index]
        }

        // Check row 3 (ZXCV row -> symbols in alt mode)
        val row3Index = ROW_3.indexOf(char.lowercase())
        if (row3Index >= 0 && row3Index < ALT_ROW_3.size) {
            return ALT_ROW_3[row3Index]
        }

        return null
    }

    private fun createSpecialKey(label: String, weight: Float, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(KEY_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, specialKeyTextSize)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createKeyBackground(KEY_BG_SPECIAL)

            layoutParams = LinearLayout.LayoutParams(
                0,
                keyHeight,
                weight
            ).apply {
                setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        background = createKeyBackground(KEY_BG_PRESSED)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        background = createKeyBackground(KEY_BG_SPECIAL)
                        onClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        background = createKeyBackground(KEY_BG_SPECIAL)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun createSpaceBar(): TextView {
        return TextView(context).apply {
            text = "space"
            setTextColor(Color.argb(150, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, specialKeyTextSize)
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            background = createKeyBackground(KEY_BG_COLOR)

            layoutParams = LinearLayout.LayoutParams(
                0,
                keyHeight,
                4f
            ).apply {
                setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        background = createKeyBackground(KEY_BG_PRESSED)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        background = createKeyBackground(KEY_BG_COLOR)
                        onKeyPress(KeyEvent.KEYCODE_SPACE, false)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        background = createKeyBackground(KEY_BG_COLOR)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun createKeyBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = this@VirtualKeyboardView.cornerRadius
        }
    }

    private fun updateShiftKeyAppearance() {
        shiftKey?.apply {
            text = when {
                isCapsLock -> "⇪"  // Caps lock indicator
                isShifted -> "⇧"   // Shift active
                else -> "⇧"        // Normal
            }
            background = createKeyBackground(
                when {
                    isCapsLock -> Color.argb(255, 100, 150, 255)  // Blue for caps lock
                    isShifted -> Color.argb(255, 80, 80, 90)      // Lighter for shift
                    else -> KEY_BG_SPECIAL
                }
            )
        }
    }

    private fun updateAllKeyLabels() {
        container?.let { cont ->
            updateKeyLabelsRecursive(cont)
        }
    }

    private fun updateKeyLabelsRecursive(viewGroup: LinearLayout) {
        for (i in 0 until viewGroup.childCount) {
            when (val child = viewGroup.getChildAt(i)) {
                is LinearLayout -> updateKeyLabelsRecursive(child)
                is TextView -> {
                    val tag = child.tag as? String
                    if (tag != null && tag.isNotEmpty()) {
                        // Skip special keys (shift, alt, backspace, enter, space)
                        if (child == shiftKey || child == altKey) continue

                        child.text = getDisplayText(tag)
                    }
                }
            }
        }
    }

    /**
     * Called to update keyboard state based on external shift state.
     */
    fun setShiftState(shifted: Boolean, capsLock: Boolean) {
        isShifted = shifted
        isCapsLock = capsLock
        updateShiftKeyAppearance()
        updateAllKeyLabels()
    }
}
