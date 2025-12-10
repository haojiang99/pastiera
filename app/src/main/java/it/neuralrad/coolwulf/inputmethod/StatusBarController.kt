package it.neuralrad.coolwulf.inputmethod

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.content.ContextCompat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import android.util.TypedValue
import it.neuralrad.coolwulf.R
import it.neuralrad.coolwulf.MainActivity
import it.neuralrad.coolwulf.SymCustomizationActivity
import it.neuralrad.coolwulf.SettingsManager
import kotlin.math.max
import android.view.MotionEvent
import android.view.KeyEvent
import kotlin.math.abs
import it.neuralrad.coolwulf.inputmethod.ui.LedStatusView
import it.neuralrad.coolwulf.inputmethod.ui.VariationBarView
import it.neuralrad.coolwulf.inputmethod.ui.VirtualKeyboardView

/**
 * Manages the status bar shown by the IME, handling view creation
 * and updating text/style based on modifier states.
 */
class StatusBarController(
    private val context: Context,
    private val mode: Mode = Mode.FULL
) {
    enum class Mode {
        FULL,
        CANDIDATES_ONLY
    }

    // Listener for variation selection
    var onVariationSelectedListener: VariationButtonHandler.OnVariationSelectedListener? = null
        set(value) {
            field = value
            variationBarView?.onVariationSelectedListener = value
        }

    // Listener for Pinyin candidate selection (with index)
    var onPinyinCandidateSelectedListener: VariationButtonHandler.OnPinyinCandidateSelectedListener? = null
        set(value) {
            field = value
            variationBarView?.onPinyinCandidateSelectedListener = value
        }

    // Listener for Wubi candidate selection (with index)
    var onWubiCandidateSelectedListener: VariationButtonHandler.OnWubiCandidateSelectedListener? = null
        set(value) {
            field = value
            variationBarView?.onWubiCandidateSelectedListener = value
        }

    // Listener for Shuangpin candidate selection (with index)
    var onShuangpinCandidateSelectedListener: VariationButtonHandler.OnShuangpinCandidateSelectedListener? = null
        set(value) {
            field = value
            variationBarView?.onShuangpinCandidateSelectedListener = value
        }

    // Listener for Ziranma candidate selection (with index)
    var onZiranmaCandidateSelectedListener: VariationButtonHandler.OnZiranmaCandidateSelectedListener? = null
        set(value) {
            field = value
            variationBarView?.onZiranmaCandidateSelectedListener = value
        }

    // Listener for Zhenma candidate selection (with index)
    var onZhenmaCandidateSelectedListener: VariationButtonHandler.OnZhenmaCandidateSelectedListener? = null
        set(value) {
            field = value
            variationBarView?.onZhenmaCandidateSelectedListener = value
        }

    // Listener for cursor movement (to update variations)
    var onCursorMovedListener: (() -> Unit)? = null
        set(value) {
            field = value
            variationBarView?.onCursorMovedListener = value
        }

    // Listener for navigating to next page of suggestions
    var onNextPageListener: (() -> Unit)? = null
        set(value) {
            field = value
            variationBarView?.onNextPageListener = value
        }

    // Listener for navigating to previous page of suggestions
    var onPrevPageListener: (() -> Unit)? = null
        set(value) {
            field = value
            variationBarView?.onPrevPageListener = value
        }

    // Listener for language toggle (EN/CN switch)
    var onLanguageToggleListener: (() -> Unit)? = null
        set(value) {
            field = value
            variationBarView?.onLanguageToggleListener = value
        }

    // Listener for SYM button press
    var onSymButtonListener: (() -> Unit)? = null
        set(value) {
            field = value
            variationBarView?.onSymButtonListener = value
        }

    // Listener for punctuation toggle (Chinese/English punctuation)
    var onPunctuationToggleListener: (() -> Unit)? = null
        set(value) {
            field = value
            variationBarView?.onPunctuationToggleListener = value
        }

    // Listener for traditional Chinese toggle (简/繁)
    var onTraditionalChineseToggleListener: (() -> Unit)? = null
        set(value) {
            field = value
            variationBarView?.onTraditionalChineseToggleListener = value
        }

    // Listener for virtual keyboard toggle button
    var onVirtualKeyboardToggleListener: (() -> Unit)? = null
        set(value) {
            field = value
            variationBarView?.setOnVirtualKeyboardToggleListener(value)
        }

    companion object {
        private const val TAG = "StatusBarController"
        private const val NAV_MODE_LABEL = "NAV MODE"
        private val DEFAULT_BACKGROUND = Color.parseColor("#000000")
        private val NAV_MODE_BACKGROUND = Color.argb(100, 0, 0, 0)
        private const val SEMI_TRANSPARENT_ALPHA = 0.4f  // 40% opacity for entire UI
        
        // LED colors
        private val LED_COLOR_GRAY_OFF = Color.argb(26, 255, 255, 255) // Gray when LED is off
        private val LED_COLOR_RED_LOCKED = Color.rgb(247, 99, 0) // Orange/red when locked
        private val LED_COLOR_BLUE_ACTIVE = Color.rgb(100, 150, 255) // Blue when active
    }

    data class StatusSnapshot(
        val capsLockEnabled: Boolean,
        val shiftPhysicallyPressed: Boolean,
        val shiftOneShot: Boolean,
        val ctrlLatchActive: Boolean,
        val ctrlPhysicallyPressed: Boolean,
        val ctrlOneShot: Boolean,
        val ctrlLatchFromNavMode: Boolean,
        val altLatchActive: Boolean,
        val altPhysicallyPressed: Boolean,
        val altOneShot: Boolean,
        val symPage: Int, // 0=disattivato, 1=pagina1 emoji, 2=pagina2 caratteri
        val variations: List<String> = emptyList(),
        val lastInsertedChar: Char? = null,
        val shouldDisableSmartFeatures: Boolean = false,
        val pinyinModeActive: Boolean = false,
        val pinyinBuffer: String = "",
        val shuangpinModeActive: Boolean = false,
        val shuangpinBuffer: String = "",
        val ziranmaModeActive: Boolean = false,
        val ziranmaBuffer: String = "",
        val wubiModeActive: Boolean = false,
        val wubiBuffer: String = "",
        val wubiWildcardMode: Boolean = false,  // True when 'Z' key is used as wildcard
        val wubiCandidateCodes: Map<String, String> = emptyMap(),  // Candidate -> actual Wubi code (for Z key learning)
        val zhenmaModeActive: Boolean = false,
        val zhenmaBuffer: String = "",
        val wordPredictionActive: Boolean = false,
        val wordPredictionPrefix: String = "",
        // Pagination fields
        val currentPage: Int = 0,
        val totalPages: Int = 1,
        val hasNextPage: Boolean = false,
        val hasPrevPage: Boolean = false,
        // Chinese punctuation mode
        val chinesePunctuationMode: Boolean = true,
        // Juying mode - hide numbers, candidates already reordered
        val isJuyingMode: Boolean = false
    ) {
        val navModeActive: Boolean
            get() = ctrlLatchActive && ctrlLatchFromNavMode
    }

    private var statusBarLayout: LinearLayout? = null
    private var modifiersContainer: LinearLayout? = null
    private var emojiMapTextView: TextView? = null
    private var emojiKeyboardContainer: LinearLayout? = null
    private var emojiKeyButtons: MutableList<View> = mutableListOf()
    private var lastSymPageRendered: Int = 0
    private var lastSymMappingsRendered: Map<Int, String>? = null
    private var wasSymActive: Boolean = false
    private var symShown: Boolean = false
    private val ledStatusView = LedStatusView(context)
    // Create variation bar for both FULL and CANDIDATES_ONLY modes (needed for Pinyin candidates)
    private val variationBarView: VariationBarView? = VariationBarView(context)
    // variationsWrapper is now a View (could be LinearLayout directly) instead of FrameLayout
    private var variationsWrapper: View? = null
    private var forceMinimalUi: Boolean = false
    private var compactModeHidden: Boolean = false

    // Virtual keyboard support
    private var virtualKeyboardContainer: LinearLayout? = null
    private var virtualKeyboardView: VirtualKeyboardView? = null
    private var virtualKeyboardEnabled: Boolean = false

    // Listener for virtual keyboard key presses
    var onVirtualKeyPressListener: ((keyCode: Int, isShifted: Boolean) -> Unit)? = null

    // Listener for virtual keyboard character input
    var onVirtualCharacterInputListener: ((char: Char) -> Unit)? = null

    /**
     * Enables or disables the virtual keyboard.
     */
    fun setVirtualKeyboardEnabled(enabled: Boolean) {
        virtualKeyboardEnabled = enabled
        updateVirtualKeyboardVisibility()
    }

    /**
     * Updates the visibility of the virtual keyboard based on the enabled state.
     */
    private fun updateVirtualKeyboardVisibility() {
        virtualKeyboardContainer?.visibility = if (virtualKeyboardEnabled) View.VISIBLE else View.GONE
    }

    /**
     * Returns whether the virtual keyboard is currently enabled.
     */
    fun isVirtualKeyboardEnabled(): Boolean = virtualKeyboardEnabled

    /**
     * Updates the shift state of the virtual keyboard.
     */
    fun updateVirtualKeyboardShiftState(shifted: Boolean, capsLock: Boolean) {
        virtualKeyboardView?.setShiftState(shifted, capsLock)
    }

    fun setForceMinimalUi(force: Boolean) {
        if (mode != Mode.FULL) {
            return
        }
        if (forceMinimalUi == force) {
            return
        }
        forceMinimalUi = force
        if (force) {
            variationBarView?.hideImmediate()
        }
    }

    fun setCompactModeHidden(hidden: Boolean) {
        if (mode != Mode.FULL) {
            return
        }
        if (compactModeHidden == hidden) {
            return
        }
        compactModeHidden = hidden
        if (hidden) {
            // If virtual keyboard is enabled, keep the layout visible but hide other components
            if (virtualKeyboardEnabled) {
                statusBarLayout?.visibility = View.VISIBLE
                // Hide other components but keep virtual keyboard visible
                modifiersContainer?.visibility = View.GONE
                emojiMapTextView?.visibility = View.GONE
                emojiKeyboardContainer?.visibility = View.GONE
                variationsWrapper?.visibility = View.GONE
                ledStatusView.ensureView().visibility = View.GONE
                variationBarView?.hideImmediate()
            } else {
                // Hide the entire status bar layout in compact mode when no suggestions
                statusBarLayout?.apply {
                    visibility = View.GONE
                    // Set height to 0 to ensure no space is taken
                    layoutParams = layoutParams?.apply {
                        height = 0
                    }
                }
                variationBarView?.hideImmediate()
            }
        } else {
            // Show the status bar layout when there are suggestions
            statusBarLayout?.apply {
                visibility = View.VISIBLE
                // Restore wrap_content height
                layoutParams = layoutParams?.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
            // Restore LED visibility based on setting
            ledStatusView.ensureView().visibility = if (SettingsManager.isShowLedStatus(context)) View.VISIBLE else View.GONE
            // Restore virtual keyboard visibility if enabled
            if (virtualKeyboardEnabled) {
                virtualKeyboardContainer?.visibility = View.VISIBLE
            }
        }
    }

    fun getLayout(): LinearLayout? = statusBarLayout

    fun getOrCreateLayout(emojiMapText: String = ""): LinearLayout {
        val isSemiTransparent = SettingsManager.isSemiTransparentStatusBar(context)
        if (statusBarLayout == null) {
            statusBarLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(DEFAULT_BACKGROUND)
            }

            // Container for modifier indicators (horizontal, left-aligned).
            // Add left padding to avoid the IME collapse button.
            val leftPadding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 
                64f, 
                context.resources.displayMetrics
            ).toInt()
            val horizontalPadding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 
                16f, 
                context.resources.displayMetrics
            ).toInt()
            val verticalPadding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 
                8f, 
                context.resources.displayMetrics
            ).toInt()
            
            modifiersContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(leftPadding, verticalPadding, horizontalPadding, verticalPadding)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                visibility = View.GONE
            }

            // Container for emoji grid (when SYM is active) - placed at the bottom
            val emojiKeyboardHorizontalPadding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                8f,
                context.resources.displayMetrics
            ).toInt()
            val emojiKeyboardBottomPadding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                12f, // Padding in basso per evitare i controlli IME
                context.resources.displayMetrics
            ).toInt()
            
            emojiKeyboardContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // No top padding, only horizontal and bottom
                setPadding(emojiKeyboardHorizontalPadding, 0, emojiKeyboardHorizontalPadding, emojiKeyboardBottomPadding)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                visibility = View.GONE
            }
            
            // TextView for Pinyin buffer display
            emojiMapTextView = TextView(context).apply {
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                visibility = View.GONE
            }

            variationsWrapper = variationBarView?.ensureView()
            val ledStrip = ledStatusView.ensureView()
            // Set initial LED visibility based on setting
            ledStrip.visibility = if (SettingsManager.isShowLedStatus(context)) View.VISIBLE else View.GONE

            // Create virtual keyboard container (hidden by default)
            virtualKeyboardContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                visibility = View.GONE
            }

            // Create virtual keyboard view
            virtualKeyboardView = VirtualKeyboardView(
                context = context,
                onKeyPress = { keyCode, isShifted ->
                    onVirtualKeyPressListener?.invoke(keyCode, isShifted)
                },
                onCharacterInput = { char ->
                    onVirtualCharacterInputListener?.invoke(char)
                }
            )
            virtualKeyboardContainer?.addView(virtualKeyboardView?.ensureView())

            statusBarLayout?.apply {
                addView(modifiersContainer)
                addView(emojiMapTextView) // Pinyin buffer text
                variationsWrapper?.let { addView(it) }
                addView(emojiKeyboardContainer) // Griglia emoji prima dei LED
                addView(virtualKeyboardContainer) // Virtual keyboard
                addView(ledStrip) // LED sempre in fondo
            }

            // Check if virtual keyboard should be enabled
            updateVirtualKeyboardVisibility()
        } else if (emojiMapText.isNotEmpty()) {
            emojiMapTextView?.text = emojiMapText
        }

        // Always apply transparency setting (check every time in case setting changed)
        statusBarLayout?.alpha = if (isSemiTransparent) SEMI_TRANSPARENT_ALPHA else 1.0f

        return statusBarLayout!!
    }
    
    /**
     * Ensures the layout is created before updating.
     * This is important for candidates view which may not have been created yet.
     */
    private fun ensureLayoutCreated(emojiMapText: String = ""): LinearLayout? {
        return statusBarLayout ?: getOrCreateLayout(emojiMapText)
    }
    
    /**
     * Recursively finds a clickable view at the given coordinates in the view hierarchy.
     * Coordinates are relative to the parent view.
     */
    private fun findClickableViewAt(parent: View, x: Float, y: Float): View? {
        if (parent !is ViewGroup) {
            // Single view: check if it's clickable and contains the point
            if (x >= 0 && x < parent.width &&
                y >= 0 && y < parent.height &&
                parent.isClickable) {
                return parent
            }
            return null
        }
        
        // For ViewGroup, check children first (they are on top)
        // Iterate in reverse to check topmost views first
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i)
            if (child.visibility == View.VISIBLE) {
                val childLeft = child.left.toFloat()
                val childTop = child.top.toFloat()
                val childRight = child.right.toFloat()
                val childBottom = child.bottom.toFloat()
                
                if (x >= childLeft && x < childRight &&
                    y >= childTop && y < childBottom) {
                    // Point is inside this child, recurse with relative coordinates
                    val childX = x - childLeft
                    val childY = y - childTop
                    val found = findClickableViewAt(child, childX, childY)
                    if (found != null) {
                        return found
                    }
                    
                    // If child itself is clickable, return it
                    if (child.isClickable) {
                        return child
                    }
                }
            }
        }
        
        // If no child was found and parent is clickable, return parent
        if (parent.isClickable) {
            return parent
        }
        
        return null
    }
    
    /**
     * Crea un indicatore per un modificatore (deprecato, mantenuto per compatibilità).
     */
    private fun createModifierIndicator(text: String, isActive: Boolean): TextView {
        val dp8 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 
            8f, 
            context.resources.displayMetrics
        ).toInt()
        val dp6 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 
            6f, 
            context.resources.displayMetrics
        ).toInt()
        
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(if (isActive) Color.WHITE else Color.argb(180, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(dp6, dp8, dp6, dp8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp8 // Margine a destra tra gli indicatori
            }
        }
    }
    
    /**
     * Aggiorna la griglia emoji/caratteri con le mappature SYM.
     * @param symMappings Le mappature da visualizzare
     * @param page La pagina attiva (1=emoji, 2=caratteri)
     * @param inputConnection L'input connection per inserire caratteri quando si clicca sui pulsanti
     */
    private fun updateEmojiKeyboard(symMappings: Map<Int, String>, page: Int, inputConnection: android.view.inputmethod.InputConnection? = null) {
        val container = emojiKeyboardContainer ?: return
        if (lastSymPageRendered == page && lastSymMappingsRendered == symMappings) {
            return
        }
        
        // Rimuovi tutti i tasti esistenti
        container.removeAllViews()
        emojiKeyButtons.clear()
        
        // Definizione delle righe della tastiera
        val keyboardRows = listOf(
            listOf(android.view.KeyEvent.KEYCODE_Q, android.view.KeyEvent.KEYCODE_W, android.view.KeyEvent.KEYCODE_E, 
                   android.view.KeyEvent.KEYCODE_R, android.view.KeyEvent.KEYCODE_T, android.view.KeyEvent.KEYCODE_Y, 
                   android.view.KeyEvent.KEYCODE_U, android.view.KeyEvent.KEYCODE_I, android.view.KeyEvent.KEYCODE_O, 
                   android.view.KeyEvent.KEYCODE_P),
            listOf(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.KEYCODE_S, android.view.KeyEvent.KEYCODE_D, 
                   android.view.KeyEvent.KEYCODE_F, android.view.KeyEvent.KEYCODE_G, android.view.KeyEvent.KEYCODE_H, 
                   android.view.KeyEvent.KEYCODE_J, android.view.KeyEvent.KEYCODE_K, android.view.KeyEvent.KEYCODE_L),
            listOf(android.view.KeyEvent.KEYCODE_Z, android.view.KeyEvent.KEYCODE_X, android.view.KeyEvent.KEYCODE_C, 
                   android.view.KeyEvent.KEYCODE_V, android.view.KeyEvent.KEYCODE_B, android.view.KeyEvent.KEYCODE_N, 
                   android.view.KeyEvent.KEYCODE_M)
        )
        
        val keyLabels = mapOf(
            android.view.KeyEvent.KEYCODE_Q to "Q", android.view.KeyEvent.KEYCODE_W to "W", android.view.KeyEvent.KEYCODE_E to "E",
            android.view.KeyEvent.KEYCODE_R to "R", android.view.KeyEvent.KEYCODE_T to "T", android.view.KeyEvent.KEYCODE_Y to "Y",
            android.view.KeyEvent.KEYCODE_U to "U", android.view.KeyEvent.KEYCODE_I to "I", android.view.KeyEvent.KEYCODE_O to "O",
            android.view.KeyEvent.KEYCODE_P to "P", android.view.KeyEvent.KEYCODE_A to "A", android.view.KeyEvent.KEYCODE_S to "S",
            android.view.KeyEvent.KEYCODE_D to "D", android.view.KeyEvent.KEYCODE_F to "F", android.view.KeyEvent.KEYCODE_G to "G",
            android.view.KeyEvent.KEYCODE_H to "H", android.view.KeyEvent.KEYCODE_J to "J", android.view.KeyEvent.KEYCODE_K to "K",
            android.view.KeyEvent.KEYCODE_L to "L", android.view.KeyEvent.KEYCODE_Z to "Z", android.view.KeyEvent.KEYCODE_X to "X",
            android.view.KeyEvent.KEYCODE_C to "C", android.view.KeyEvent.KEYCODE_V to "V", android.view.KeyEvent.KEYCODE_B to "B",
            android.view.KeyEvent.KEYCODE_N to "N", android.view.KeyEvent.KEYCODE_M to "M"
        )
        
        val keySpacing = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            4f,
            context.resources.displayMetrics
        ).toInt()
        
        // Calcola la larghezza fissa dei tasti basata sulla prima riga (10 caselle)
        val maxKeysInRow = 10 // Prima riga ha 10 caselle
        val screenWidth = context.resources.displayMetrics.widthPixels
        val horizontalPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            8f * 2, // padding sinistro + destro
            context.resources.displayMetrics
        ).toInt()
        val availableWidth = screenWidth - horizontalPadding
        val totalSpacing = keySpacing * (maxKeysInRow - 1)
        val fixedKeyWidth = (availableWidth - totalSpacing) / maxKeysInRow
        
        val keyHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            56f,
            context.resources.displayMetrics
        ).toInt()
        
        // Crea ogni riga della tastiera
        for ((rowIndex, row) in keyboardRows.withIndex()) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL // Centra le righe più corte
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    // Aggiungi margine solo tra le righe, non dopo l'ultima
                    if (rowIndex < keyboardRows.size - 1) {
                        bottomMargin = keySpacing
                    }
                }
            }
            
            // Per la terza riga, aggiungi placeholder trasparente a sinistra
            if (rowIndex == 2) {
                val leftPlaceholder = createPlaceholderButton(keyHeight)
                rowLayout.addView(leftPlaceholder, LinearLayout.LayoutParams(fixedKeyWidth, keyHeight).apply {
                    marginEnd = keySpacing
                })
            }
            
            for ((index, keyCode) in row.withIndex()) {
                val label = keyLabels[keyCode] ?: ""
                val content = symMappings[keyCode] ?: ""
                
                val keyButton = createEmojiKeyButton(label, content, keyHeight, page)
                emojiKeyButtons.add(keyButton)
                
                // Aggiungi click listener per rendere il pulsante touchabile
                if (content.isNotEmpty() && inputConnection != null) {
                    keyButton.isClickable = true
                    keyButton.isFocusable = true
                    keyButton.setOnClickListener {
                        // Inserisci il carattere/emoji quando si clicca
                        inputConnection.commitText(content, 1)
                        Log.d(TAG, "Clicked SYM button for keyCode $keyCode: $content")
                    }
                    
                    // Aggiungi feedback visivo quando il pulsante viene premuto
                    val originalBackground = keyButton.background
                    keyButton.setOnTouchListener { view, motionEvent ->
                        when (motionEvent.action) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                // Dimmer lo sfondo quando premuto
                                if (originalBackground is GradientDrawable) {
                                    val pressedColor = Color.argb(80, 255, 255, 255) // Più opaco
                                    originalBackground.setColor(pressedColor)
                                }
                                view.invalidate()
                            }
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                // Ripristina lo sfondo originale
                                if (originalBackground is GradientDrawable) {
                                    val normalColor = Color.argb(40, 255, 255, 255) // Sfondo normale
                                    originalBackground.setColor(normalColor)
                                }
                                view.invalidate()
                            }
                        }
                        false // Non consumare l'evento, lascia che il click listener funzioni
                    }
                }
                
                // Usa larghezza fissa invece di weight
                rowLayout.addView(keyButton, LinearLayout.LayoutParams(fixedKeyWidth, keyHeight).apply {
                    // Aggiungi margine solo se non è l'ultimo tasto della riga
                    if (index < row.size - 1) {
                        marginEnd = keySpacing
                    }
                })
            }
            
            // Per la terza riga, aggiungi placeholder con icona matita a destra
            if (rowIndex == 2) {
                val rightPlaceholder = createPlaceholderWithPencilButton(keyHeight)
                rowLayout.addView(rightPlaceholder, LinearLayout.LayoutParams(fixedKeyWidth, keyHeight).apply {
                    marginStart = keySpacing
                })
            }
            
            container.addView(rowLayout)
        }

        // Cache what was rendered to avoid rebuilding on each status refresh
        lastSymPageRendered = page
        lastSymMappingsRendered = HashMap(symMappings)
    }
    
    /**
     * Crea un placeholder trasparente per allineare le righe.
     */
    private fun createPlaceholderButton(height: Int): View {
        return FrameLayout(context).apply {
            background = null // Trasparente
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
            )
            isClickable = false
            isFocusable = false
        }
    }
    
    /**
     * Crea un placeholder con icona matita per aprire la schermata di personalizzazione SYM.
     */
    private fun createPlaceholderWithPencilButton(height: Int): View {
        val placeholder = FrameLayout(context).apply {
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
            )
        }
        
        // Background trasparente
        placeholder.background = null
        
        // Dimensione icona più grande
        val iconSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            20f, // Aumentata ulteriormente per maggiore visibilità
            context.resources.displayMetrics
        ).toInt()
        
        val button = ImageView(context).apply {
            background = null
            setImageResource(R.drawable.ic_edit_24)
            setColorFilter(Color.WHITE) // Bianco
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            maxWidth = iconSize
            maxHeight = iconSize
            layoutParams = FrameLayout.LayoutParams(
                iconSize,
                iconSize
            ).apply {
                gravity = Gravity.CENTER
            }
            isClickable = true
            isFocusable = true
        }
        
        button.setOnClickListener {
            // Save current SYM page state temporarily (will be confirmed only if user presses back)
            val prefs = context.getSharedPreferences("pastiera_prefs", Context.MODE_PRIVATE)
            val currentSymPage = prefs.getInt("current_sym_page", 0)
            if (currentSymPage > 0) {
                // Save as pending - will be converted to restore only if user presses back
                SettingsManager.setPendingRestoreSymPage(context, currentSymPage)
            }
            
            // Apri SymCustomizationActivity direttamente
            val intent = Intent(context, SymCustomizationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Errore nell'apertura della schermata di personalizzazione SYM", e)
            }
        }
        
        placeholder.addView(button)
        return placeholder
    }
    
    /**
     * Crea un tasto della griglia emoji/caratteri.
     * @param label La lettera del tasto
     * @param content L'emoji o carattere da mostrare
     * @param height L'altezza del tasto
     * @param page La pagina attiva (1=emoji, 2=caratteri)
     */
    private fun createEmojiKeyButton(label: String, content: String, height: Int, page: Int): View {
        val keyLayout = FrameLayout(context).apply {
            setPadding(0, 0, 0, 0) // Nessun padding per permettere all'emoji di occupare tutto lo spazio
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
            )
        }
        
        // Background del tasto con angoli leggermente arrotondati
        val cornerRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            6f, // Angoli leggermente arrotondati
            context.resources.displayMetrics
        )
        val drawable = GradientDrawable().apply {
            setColor(Color.argb(40, 255, 255, 255)) // Bianco semi-trasparente
            setCornerRadius(cornerRadius)
            // Nessun bordo
        }
        keyLayout.background = drawable
        
        // Emoji/carattere deve occupare tutto il tasto, centrata
        // Calcola textSize in base all'altezza disponibile (convertendo da pixel a sp)
        val heightInDp = height / context.resources.displayMetrics.density
        val contentTextSize = if (page == 2) {
            // Per caratteri unicode, usa una dimensione più piccola
            (heightInDp * 0.5f)
        } else {
            // Per emoji, usa la dimensione normale
            (heightInDp * 0.75f)
        }
        
        val contentText = TextView(context).apply {
            text = content
            textSize = contentTextSize // textSize è in sp
            gravity = Gravity.CENTER
            // Per pagina 2 (caratteri), rendi bianco e in grassetto
            if (page == 2) {
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            // Larghezza e altezza per occupare tutto lo spazio disponibile
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        
        // Label (lettera) - posizionato in basso a destra, davanti all'emoji
        val labelPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f, // Pochissimo margine
            context.resources.displayMetrics
        ).toInt()
        
        val labelText = TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE) // Bianco 100% opaco
            gravity = Gravity.END or Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = labelPadding
                bottomMargin = labelPadding
            }
        }
        
        // Aggiungi prima il contenuto (dietro) poi il testo (davanti)
        keyLayout.addView(contentText)
        keyLayout.addView(labelText)
        
        return keyLayout
    }
    
    /**
     * Crea una griglia emoji personalizzabile (per la schermata di personalizzazione).
     * Restituisce una View che può essere incorporata in Compose tramite AndroidView.
     * 
     * @param symMappings Le mappature emoji da visualizzare
     * @param onKeyClick Callback chiamato quando un tasto viene cliccato (keyCode, emoji)
     */
    fun createCustomizableEmojiKeyboard(
        symMappings: Map<Int, String>,
        onKeyClick: (Int, String) -> Unit,
        page: Int = 1 // Default a pagina 1 (emoji)
    ): View {
        val isSemiTransparent = SettingsManager.isSemiTransparentStatusBar(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bottomPadding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                12f,
                context.resources.displayMetrics
            ).toInt()
            setPadding(0, 0, 0, bottomPadding) // Nessun padding orizzontale, solo in basso
            // Aggiungi sfondo nero per migliorare la visibilità dei caratteri con tema chiaro
            setBackgroundColor(DEFAULT_BACKGROUND)
            // Apply transparency to entire UI when setting is enabled, otherwise full opacity
            alpha = if (isSemiTransparent) SEMI_TRANSPARENT_ALPHA else 1.0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Definizione delle righe della tastiera (stessa struttura della tastiera reale)
        val keyboardRows = listOf(
            listOf(android.view.KeyEvent.KEYCODE_Q, android.view.KeyEvent.KEYCODE_W, android.view.KeyEvent.KEYCODE_E, 
                   android.view.KeyEvent.KEYCODE_R, android.view.KeyEvent.KEYCODE_T, android.view.KeyEvent.KEYCODE_Y, 
                   android.view.KeyEvent.KEYCODE_U, android.view.KeyEvent.KEYCODE_I, android.view.KeyEvent.KEYCODE_O, 
                   android.view.KeyEvent.KEYCODE_P),
            listOf(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.KEYCODE_S, android.view.KeyEvent.KEYCODE_D, 
                   android.view.KeyEvent.KEYCODE_F, android.view.KeyEvent.KEYCODE_G, android.view.KeyEvent.KEYCODE_H, 
                   android.view.KeyEvent.KEYCODE_J, android.view.KeyEvent.KEYCODE_K, android.view.KeyEvent.KEYCODE_L),
            listOf(android.view.KeyEvent.KEYCODE_Z, android.view.KeyEvent.KEYCODE_X, android.view.KeyEvent.KEYCODE_C, 
                   android.view.KeyEvent.KEYCODE_V, android.view.KeyEvent.KEYCODE_B, android.view.KeyEvent.KEYCODE_N, 
                   android.view.KeyEvent.KEYCODE_M)
        )
        
        val keyLabels = mapOf(
            android.view.KeyEvent.KEYCODE_Q to "Q", android.view.KeyEvent.KEYCODE_W to "W", android.view.KeyEvent.KEYCODE_E to "E",
            android.view.KeyEvent.KEYCODE_R to "R", android.view.KeyEvent.KEYCODE_T to "T", android.view.KeyEvent.KEYCODE_Y to "Y",
            android.view.KeyEvent.KEYCODE_U to "U", android.view.KeyEvent.KEYCODE_I to "I", android.view.KeyEvent.KEYCODE_O to "O",
            android.view.KeyEvent.KEYCODE_P to "P", android.view.KeyEvent.KEYCODE_A to "A", android.view.KeyEvent.KEYCODE_S to "S",
            android.view.KeyEvent.KEYCODE_D to "D", android.view.KeyEvent.KEYCODE_F to "F", android.view.KeyEvent.KEYCODE_G to "G",
            android.view.KeyEvent.KEYCODE_H to "H", android.view.KeyEvent.KEYCODE_J to "J", android.view.KeyEvent.KEYCODE_K to "K",
            android.view.KeyEvent.KEYCODE_L to "L", android.view.KeyEvent.KEYCODE_Z to "Z", android.view.KeyEvent.KEYCODE_X to "X",
            android.view.KeyEvent.KEYCODE_C to "C", android.view.KeyEvent.KEYCODE_V to "V", android.view.KeyEvent.KEYCODE_B to "B",
            android.view.KeyEvent.KEYCODE_N to "N", android.view.KeyEvent.KEYCODE_M to "M"
        )
        
        val keySpacing = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            4f,
            context.resources.displayMetrics
        ).toInt()
        
        // Calcola la larghezza fissa dei tasti basata sulla prima riga (10 caselle)
        // Usa ViewTreeObserver per ottenere la larghezza effettiva del container dopo il layout
        val maxKeysInRow = 10 // Prima riga ha 10 caselle
        
        // Inizializza con una larghezza temporanea, verrà aggiornata dopo il layout
        var fixedKeyWidth = 0
        
        container.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val containerWidth = container.width
                if (containerWidth > 0) {
                    val totalSpacing = keySpacing * (maxKeysInRow - 1)
                    fixedKeyWidth = (containerWidth - totalSpacing) / maxKeysInRow
                    
                    // Aggiorna tutti i tasti con la larghezza corretta
                    for (i in 0 until container.childCount) {
                        val rowLayout = container.getChildAt(i) as? LinearLayout
                        rowLayout?.let { row ->
                            for (j in 0 until row.childCount) {
                                val keyButton = row.getChildAt(j)
                                val layoutParams = keyButton.layoutParams as? LinearLayout.LayoutParams
                                layoutParams?.let {
                                    it.width = fixedKeyWidth
                                    keyButton.layoutParams = it
                                }
                            }
                        }
                    }
                    
                    // Rimuovi il listener dopo il primo layout
                    container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
        
        // Valore iniziale basato sulla larghezza dello schermo (verrà aggiornato dal listener)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val totalSpacing = keySpacing * (maxKeysInRow - 1)
        fixedKeyWidth = (screenWidth - totalSpacing) / maxKeysInRow
        
        val keyHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            56f,
            context.resources.displayMetrics
        ).toInt()
        
        // Crea ogni riga della tastiera (stessa struttura della tastiera reale)
        for ((rowIndex, row) in keyboardRows.withIndex()) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL // Centra le righe più corte
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIndex < keyboardRows.size - 1) {
                        bottomMargin = keySpacing
                    }
                }
            }
            
            // Per la terza riga, aggiungi placeholder trasparente a sinistra
            if (rowIndex == 2) {
                val leftPlaceholder = createPlaceholderButton(keyHeight)
                rowLayout.addView(leftPlaceholder, LinearLayout.LayoutParams(fixedKeyWidth, keyHeight).apply {
                    marginEnd = keySpacing
                })
            }
            
            for ((index, keyCode) in row.withIndex()) {
                val label = keyLabels[keyCode] ?: ""
                val emoji = symMappings[keyCode] ?: ""
                
                // Usa la stessa funzione createEmojiKeyButton della tastiera reale
                val keyButton = createEmojiKeyButton(label, emoji, keyHeight, page)
                
                // Aggiungi click listener
                keyButton.setOnClickListener {
                    onKeyClick(keyCode, emoji)
                }
                
                // Usa larghezza fissa invece di weight (stesso layout della tastiera reale)
                rowLayout.addView(keyButton, LinearLayout.LayoutParams(fixedKeyWidth, keyHeight).apply {
                    if (index < row.size - 1) {
                        marginEnd = keySpacing
                    }
                })
            }
            
            // Per la terza riga nella schermata di personalizzazione, aggiungi placeholder trasparente a destra
            // per mantenere l'allineamento (senza matita e senza click listener)
            if (rowIndex == 2) {
                val rightPlaceholder = createPlaceholderButton(keyHeight)
                rowLayout.addView(rightPlaceholder, LinearLayout.LayoutParams(fixedKeyWidth, keyHeight).apply {
                    marginStart = keySpacing
                })
            }
            
            container.addView(rowLayout)
        }
        
        return container
    }
    
    /**
     * Anima l'apparizione della griglia emoji solo con slide up (nessun fade).
     * @param backgroundView Il view dello sfondo da impostare a opaco immediatamente
     */
    private fun animateEmojiKeyboardIn(view: View, backgroundView: View? = null) {
        val height = view.height
        if (height == 0) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }
        val measuredHeight = view.measuredHeight

        view.alpha = 1f
        view.translationY = measuredHeight.toFloat()
        view.visibility = View.VISIBLE

        // Set background to opaque immediately without animation
        backgroundView?.let { bgView ->
            if (bgView.background !is ColorDrawable) {
                bgView.background = ColorDrawable(DEFAULT_BACKGROUND)
            }
            (bgView.background as? ColorDrawable)?.alpha = 255
        }

        val animator = ValueAnimator.ofFloat(measuredHeight.toFloat(), 0f).apply {
            duration = 125
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                view.translationY = value
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.translationY = 0f
                    view.alpha = 1f
                }
            })
        }
        animator.start()
    }
    
    /**
     * Anima la scomparsa della griglia emoji (slide down + fade out).
     * @param backgroundView Il view dello sfondo (non animato, rimane opaco)
     * @param onAnimationEnd Callback chiamato quando l'animazione è completata
     */
    private fun animateEmojiKeyboardOut(view: View, backgroundView: View? = null, onAnimationEnd: (() -> Unit)? = null) {
        val height = view.height
        if (height == 0) {
            view.visibility = View.GONE
            onAnimationEnd?.invoke()
            return
        }

        // Background remains opaque, no animation

        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 100
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                view.alpha = progress
                view.translationY = height * (1f - progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                    view.translationY = 0f
                    view.alpha = 1f
                    onAnimationEnd?.invoke()
                }
            })
        }
        animator.start()
    }

    
    

    fun update(snapshot: StatusSnapshot, emojiMapText: String = "", inputConnection: android.view.inputmethod.InputConnection? = null, symMappings: Map<Int, String>? = null) {
        variationBarView?.onVariationSelectedListener = onVariationSelectedListener
        variationBarView?.onPinyinCandidateSelectedListener = onPinyinCandidateSelectedListener
        variationBarView?.onWubiCandidateSelectedListener = onWubiCandidateSelectedListener
        variationBarView?.onShuangpinCandidateSelectedListener = onShuangpinCandidateSelectedListener
        variationBarView?.onZiranmaCandidateSelectedListener = onZiranmaCandidateSelectedListener
        variationBarView?.onZhenmaCandidateSelectedListener = onZhenmaCandidateSelectedListener
        variationBarView?.onCursorMovedListener = onCursorMovedListener
        variationBarView?.onLanguageToggleListener = onLanguageToggleListener
        variationBarView?.onSymButtonListener = onSymButtonListener
        variationBarView?.updateInputConnection(inputConnection)
        variationBarView?.setSymModeActive(snapshot.symPage > 0)
        variationBarView?.setPinyinModeActive(snapshot.pinyinModeActive)
        variationBarView?.setShuangpinModeActive(snapshot.shuangpinModeActive)
        variationBarView?.setZiranmaModeActive(snapshot.ziranmaModeActive)
        variationBarView?.setWubiModeActive(snapshot.wubiModeActive)
        variationBarView?.setZhenmaModeActive(snapshot.zhenmaModeActive)
        variationBarView?.setChinesePunctuationMode(snapshot.chinesePunctuationMode)
        variationBarView?.setTraditionalChineseMode(SettingsManager.isTraditionalChineseMode(context))
        variationBarView?.setTraditionalChineseToggleEnabled(SettingsManager.isTraditionalChineseToggleEnabled(context))
        variationBarView?.onTraditionalChineseToggleListener = onTraditionalChineseToggleListener

        // Update virtual keyboard Chinese punctuation mode
        virtualKeyboardView?.setChinesePunctuationMode(snapshot.chinesePunctuationMode)

        // Always call showVariations() early, before any potential early returns
        // This ensures the variation bar is updated even if the rest of update() returns early
        if (snapshot.symPage == 0) { // Only if not in SYM mode
            variationBarView?.showVariations(snapshot, inputConnection)
        }

        val layout = ensureLayoutCreated(emojiMapText) ?: return
        val modifiersContainerView = modifiersContainer ?: return
        val emojiView = emojiMapTextView ?: return
        val emojiKeyboardView = emojiKeyboardContainer ?: return

        // Show emoji map text if provided
        if (emojiMapText.isNotEmpty()) {
            emojiView.text = emojiMapText
            emojiView.visibility = View.VISIBLE
        } else {
            emojiView.visibility = View.GONE
        }
        
        if (snapshot.navModeActive) {
            layout.visibility = View.GONE
            // Keep virtual keyboard visible even in nav mode if enabled
            if (virtualKeyboardEnabled) {
                virtualKeyboardContainer?.visibility = View.VISIBLE
            }
            return
        }

        // In compact mode with no suggestions, keep layout hidden unless virtual keyboard is enabled
        if (compactModeHidden && !virtualKeyboardEnabled) {
            layout.visibility = View.GONE
            return
        }

        // Always ensure virtual keyboard is visible if enabled
        if (virtualKeyboardEnabled) {
            virtualKeyboardContainer?.visibility = View.VISIBLE
        }
        layout.visibility = View.VISIBLE
        
        if (layout.background !is ColorDrawable) {
            layout.background = ColorDrawable(DEFAULT_BACKGROUND)
        } else if (snapshot.symPage == 0) {
            (layout.background as ColorDrawable).alpha = 255
        }
        
        modifiersContainerView.visibility = View.GONE
        ledStatusView.update(snapshot)
        // Respect LED visibility setting
        ledStatusView.ensureView().visibility = if (SettingsManager.isShowLedStatus(context)) View.VISIBLE else View.GONE
        val variationsBar = if (!forceMinimalUi) variationBarView else null

        if (snapshot.symPage > 0 && symMappings != null) {
            // Clear cache to force rebuild of emoji keyboard
            lastSymPageRendered = 0
            lastSymMappingsRendered = null
            updateEmojiKeyboard(symMappings, snapshot.symPage, inputConnection)
            variationsBar?.resetVariationsState()

            // Pin background to opaque IME color and hide variations so SYM animates on a solid canvas.
            if (layout.background !is ColorDrawable) {
                layout.background = ColorDrawable(DEFAULT_BACKGROUND)
            }
            (layout.background as? ColorDrawable)?.alpha = 255

            // When virtual keyboard is enabled, keep status bar visible with SYM button
            // When virtual keyboard is disabled, use original logic (hide status bar)
            if (virtualKeyboardEnabled) {
                variationsWrapper?.apply {
                    visibility = View.VISIBLE
                    isEnabled = true
                    isClickable = true
                }
                variationsBar?.showSymButtonOnly()
            } else {
                // Original logic: hide status bar when showing emoji keyboard
                variationsWrapper?.visibility = View.GONE
            }

            // Set up emoji keyboard visibility BEFORE setting height
            val isSemiTransparent = SettingsManager.isSemiTransparentStatusBar(context)
            emojiKeyboardView.setBackgroundColor(DEFAULT_BACKGROUND)
            emojiKeyboardView.translationY = 0f
            // Apply transparency to entire UI when setting is enabled
            emojiKeyboardView.alpha = if (isSemiTransparent) SEMI_TRANSPARENT_ALPHA else 1f

            // First set to WRAP_CONTENT to allow proper measurement
            emojiKeyboardView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            emojiKeyboardView.visibility = View.VISIBLE

            // Force the parent layout to remeasure immediately
            layout.requestLayout()

            // Post to ensure measurement happens after content is added
            emojiKeyboardView.post {
                // Calculate exact height for 3 rows of emoji keys
                val keyHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56f, context.resources.displayMetrics).toInt()
                val keySpacing = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, context.resources.displayMetrics).toInt()
                val bottomPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, context.resources.displayMetrics).toInt()
                val calculatedHeight = (keyHeight * 3) + (keySpacing * 2) + bottomPadding

                // Use measured height if available, otherwise use calculated
                val measuredHeight = emojiKeyboardView.measuredHeight
                val finalHeight = if (measuredHeight > calculatedHeight / 2) measuredHeight else calculatedHeight

                emojiKeyboardView.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    finalHeight
                )
                emojiKeyboardView.requestLayout()

                // Force another layout pass on the parent
                layout.requestLayout()
                layout.invalidate()
            }

            symShown = true
            wasSymActive = true

            // Hide virtual keyboard when SYM is shown to make room for emoji keyboard
            // The SYM button remains visible so user can close the symbol layout
            virtualKeyboardContainer?.visibility = View.GONE
            return
        }
        
        if (emojiKeyboardView.visibility == View.VISIBLE) {
            animateEmojiKeyboardOut(emojiKeyboardView, layout) {
                variationsWrapper?.apply {
                    visibility = View.VISIBLE
                    isEnabled = true
                    isClickable = true
                }
                variationsBar?.showVariations(snapshot, inputConnection)
            }
            symShown = false
            wasSymActive = false
        } else {
            emojiKeyboardView.visibility = View.GONE
            variationsWrapper?.apply {
                visibility = View.VISIBLE
                isEnabled = true
                isClickable = true
            }
            variationsBar?.showVariations(snapshot, inputConnection)
            symShown = false
            wasSymActive = false
        }

        // Final check: ensure virtual keyboard stays visible if enabled
        if (virtualKeyboardEnabled) {
            virtualKeyboardContainer?.visibility = View.VISIBLE
        }
    }

    private fun ensureEmojiKeyboardMeasuredHeight(view: View, parent: View): Int {
        // Force measure the view to get accurate height
        val width = if (parent.width > 0) parent.width else context.resources.displayMetrics.widthPixels
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        return view.measuredHeight
    }
}


