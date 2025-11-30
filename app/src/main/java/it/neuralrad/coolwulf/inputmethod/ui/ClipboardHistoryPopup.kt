package it.neuralrad.coolwulf.inputmethod.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import it.neuralrad.coolwulf.R
import it.neuralrad.coolwulf.core.ClipboardHistoryManager

/**
 * Popup window that displays clipboard history and allows pasting previous entries.
 */
class ClipboardHistoryPopup(
    private val context: Context
) {
    companion object {
        private const val TAG = "ClipboardHistoryPopup"
    }

    private var popupWindow: PopupWindow? = null
    var onItemSelected: ((String) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    /**
     * Shows the clipboard history popup anchored to the given view.
     */
    fun show(anchorView: View, inputConnection: InputConnection?) {
        dismiss()

        val history = ClipboardHistoryManager.getHistory(context)
        if (history.isEmpty()) {
            Log.d(TAG, "No clipboard history to show")
            return
        }

        val contentView = createContentView(history, inputConnection)

        val width = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            280f,
            context.resources.displayMetrics
        ).toInt()

        val maxHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            300f,
            context.resources.displayMetrics
        ).toInt()

        popupWindow = PopupWindow(contentView, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isFocusable = true
            elevation = 8f

            setOnDismissListener {
                onDismiss?.invoke()
            }

            // Set background
            val background = GradientDrawable().apply {
                setColor(Color.rgb(30, 30, 30))
                cornerRadius = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    8f,
                    context.resources.displayMetrics
                )
            }
            setBackgroundDrawable(background)
        }

        // Show popup above the anchor view
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)

        popupWindow?.showAtLocation(
            anchorView,
            Gravity.NO_GRAVITY,
            location[0],
            location[1] - maxHeight
        )

        Log.d(TAG, "Showing clipboard history popup with ${history.size} entries")
    }

    /**
     * Creates the content view for the popup.
     */
    private fun createContentView(
        history: List<ClipboardHistoryManager.ClipboardEntry>,
        inputConnection: InputConnection?
    ): View {
        val dp8 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            8f,
            context.resources.displayMetrics
        ).toInt()

        val dp12 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            12f,
            context.resources.displayMetrics
        ).toInt()

        val dp16 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            16f,
            context.resources.displayMetrics
        ).toInt()

        val dp24 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            24f,
            context.resources.displayMetrics
        ).toInt()

        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp8, dp8, dp8, dp8)
        }

        // Header
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp8, dp8, dp8, dp8)
        }

        val titleText = TextView(context).apply {
            text = context.getString(R.string.clipboard_history_title)
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(titleText)

        // Clear button
        val clearButton = TextView(context).apply {
            text = context.getString(R.string.clipboard_history_clear)
            setTextColor(Color.rgb(255, 100, 100))
            textSize = 12f
            setPadding(dp8, dp8, dp8, dp8)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                ClipboardHistoryManager.clearHistory(context)
                dismiss()
            }
        }
        headerLayout.addView(clearButton)

        mainLayout.addView(headerLayout)

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(Color.rgb(60, 60, 60))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                topMargin = dp8
                bottomMargin = dp8
            }
        }
        mainLayout.addView(divider)

        // Scrollable list of entries
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val maxHeightPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    240f,
                    context.resources.displayMetrics
                ).toInt()
            }
        }

        val listLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        for ((index, entry) in history.withIndex()) {
            val itemView = createEntryView(entry, index, inputConnection)
            listLayout.addView(itemView)
        }

        scrollView.addView(listLayout)
        mainLayout.addView(scrollView)

        return mainLayout
    }

    /**
     * Creates a view for a single clipboard entry.
     */
    private fun createEntryView(
        entry: ClipboardHistoryManager.ClipboardEntry,
        index: Int,
        inputConnection: InputConnection?
    ): View {
        val dp4 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            4f,
            context.resources.displayMetrics
        ).toInt()

        val dp8 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            8f,
            context.resources.displayMetrics
        ).toInt()

        val dp12 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            12f,
            context.resources.displayMetrics
        ).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp8, dp12, dp8, dp12)

            // Background with press state
            val normalDrawable = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp4.toFloat()
            }
            val pressedDrawable = GradientDrawable().apply {
                setColor(Color.rgb(50, 50, 50))
                cornerRadius = dp4.toFloat()
            }
            val stateListDrawable = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
                addState(intArrayOf(), normalDrawable)
            }
            background = stateListDrawable

            isClickable = true
            isFocusable = true

            setOnClickListener {
                // Paste the text
                inputConnection?.commitText(entry.text, 1)
                onItemSelected?.invoke(entry.text)
                dismiss()
                Log.d(TAG, "Pasted clipboard entry: ${entry.text.take(30)}...")
            }
        }

        // Number indicator
        val numberText = TextView(context).apply {
            text = "${index + 1}"
            setTextColor(Color.rgb(150, 150, 150))
            textSize = 12f
            gravity = Gravity.CENTER
            val size = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                24f,
                context.resources.displayMetrics
            ).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp8
            }
        }
        layout.addView(numberText)

        // Text content
        val displayText = if (entry.text.length > 80) {
            entry.text.take(80) + "..."
        } else {
            entry.text
        }

        val textView = TextView(context).apply {
            text = displayText.replace("\n", " ").trim()
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        layout.addView(textView)

        // Delete button
        val deleteButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_close_24)
            setColorFilter(Color.rgb(150, 150, 150))
            val size = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                20f,
                context.resources.displayMetrics
            ).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp8
            }
            isClickable = true
            isFocusable = true
            setPadding(dp4, dp4, dp4, dp4)

            setOnClickListener { view ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                ClipboardHistoryManager.removeEntry(context, entry.text)
                // Refresh the popup
                val parent = layout.parent as? ViewGroup
                parent?.removeView(layout)
                Log.d(TAG, "Removed clipboard entry")
            }
        }
        layout.addView(deleteButton)

        return layout
    }

    /**
     * Dismisses the popup if it's showing.
     */
    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }

    /**
     * Returns whether the popup is currently showing.
     */
    fun isShowing(): Boolean {
        return popupWindow?.isShowing == true
    }
}
