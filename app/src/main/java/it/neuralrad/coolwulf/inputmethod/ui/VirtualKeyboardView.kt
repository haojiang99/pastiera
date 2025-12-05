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
        private val ROW_NUMBERS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        private val ROW_1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        private val ROW_2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        private val ROW_3 = listOf("z", "x", "c", "v", "b", "n", "m")
    }

    private var container: LinearLayout? = null
    private var isShifted = false
    private var isCapsLock = false
    private var shiftKey: TextView? = null

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

        // Add number row
        container?.addView(createKeyRow(ROW_NUMBERS))

        // Add QWERTY rows
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

            // Symbols/numbers toggle key
            addView(createSpecialKey("123", 1.2f) {
                // Could toggle to symbols/numbers layout in future
            })

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

    private fun createCharacterKey(char: String, weight: Float = 1f): TextView {
        return TextView(context).apply {
            text = if (isShifted || isCapsLock) char.uppercase() else char
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
                        val originalChar = (v.tag as String).first()
                        val outputChar = if (isShifted || isCapsLock) {
                            originalChar.uppercaseChar()
                        } else {
                            originalChar
                        }
                        onCharacterInput(outputChar)

                        // Clear shift after typing (unless caps lock)
                        if (isShifted && !isCapsLock) {
                            isShifted = false
                            updateShiftKeyAppearance()
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
                    if (tag != null && tag.length == 1 && tag.first().isLetter()) {
                        child.text = if (isShifted || isCapsLock) tag.uppercase() else tag
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
