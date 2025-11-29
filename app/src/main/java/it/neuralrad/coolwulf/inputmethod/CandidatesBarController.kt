package it.neuralrad.coolwulf.inputmethod

import android.content.Context
import android.widget.LinearLayout
import android.view.inputmethod.InputConnection

/**
 * Coordinates the two StatusBarController instances (full input view vs
 * candidates-only view) so the IME service can treat them as a single surface.
 */
class CandidatesBarController(
    context: Context
) {

    private val inputStatusBar = StatusBarController(context, StatusBarController.Mode.FULL)
    private val candidatesStatusBar = StatusBarController(context, StatusBarController.Mode.CANDIDATES_ONLY)

    var onVariationSelectedListener: VariationButtonHandler.OnVariationSelectedListener? = null
        set(value) {
            field = value
            inputStatusBar.onVariationSelectedListener = value
            candidatesStatusBar.onVariationSelectedListener = value
        }

    var onPinyinCandidateSelectedListener: VariationButtonHandler.OnPinyinCandidateSelectedListener? = null
        set(value) {
            field = value
            inputStatusBar.onPinyinCandidateSelectedListener = value
            candidatesStatusBar.onPinyinCandidateSelectedListener = value
        }

    var onWubiCandidateSelectedListener: VariationButtonHandler.OnWubiCandidateSelectedListener? = null
        set(value) {
            field = value
            inputStatusBar.onWubiCandidateSelectedListener = value
            candidatesStatusBar.onWubiCandidateSelectedListener = value
        }

    var onShuangpinCandidateSelectedListener: VariationButtonHandler.OnShuangpinCandidateSelectedListener? = null
        set(value) {
            field = value
            inputStatusBar.onShuangpinCandidateSelectedListener = value
            candidatesStatusBar.onShuangpinCandidateSelectedListener = value
        }

    var onCursorMovedListener: (() -> Unit)? = null
        set(value) {
            field = value
            inputStatusBar.onCursorMovedListener = value
            candidatesStatusBar.onCursorMovedListener = value
        }

    var onNextPageListener: (() -> Unit)? = null
        set(value) {
            field = value
            inputStatusBar.onNextPageListener = value
            candidatesStatusBar.onNextPageListener = value
        }

    var onPrevPageListener: (() -> Unit)? = null
        set(value) {
            field = value
            inputStatusBar.onPrevPageListener = value
            candidatesStatusBar.onPrevPageListener = value
        }

    var onLanguageToggleListener: (() -> Unit)? = null
        set(value) {
            field = value
            inputStatusBar.onLanguageToggleListener = value
            candidatesStatusBar.onLanguageToggleListener = value
        }

    var onSymButtonListener: (() -> Unit)? = null
        set(value) {
            field = value
            inputStatusBar.onSymButtonListener = value
            candidatesStatusBar.onSymButtonListener = value
        }

    var onPunctuationToggleListener: (() -> Unit)? = null
        set(value) {
            field = value
            inputStatusBar.onPunctuationToggleListener = value
            candidatesStatusBar.onPunctuationToggleListener = value
        }

    fun getInputView(emojiMapText: String = ""): LinearLayout {
        return inputStatusBar.getOrCreateLayout(emojiMapText)
    }

    fun getCandidatesView(emojiMapText: String = ""): LinearLayout {
        return candidatesStatusBar.getOrCreateLayout(emojiMapText)
    }

    fun setForceMinimalUi(force: Boolean) {
        inputStatusBar.setForceMinimalUi(force)
    }

    fun setCompactModeHidden(hidden: Boolean) {
        inputStatusBar.setCompactModeHidden(hidden)
    }

    fun updateStatusBars(
        snapshot: StatusBarController.StatusSnapshot,
        emojiMapText: String,
        inputConnection: InputConnection?,
        symMappings: Map<Int, String>?
    ) {
        inputStatusBar.update(snapshot, emojiMapText, inputConnection, symMappings)
        candidatesStatusBar.update(snapshot, emojiMapText, inputConnection, symMappings)
    }
}

