package it.neuralrad.coolwulf.inputmethod

import android.content.Context
import android.util.Log
import android.view.View
import android.view.inputmethod.InputConnection

/**
 * Handles clicks on variation buttons.
 */
object VariationButtonHandler {
    private const val TAG = "VariationButtonHandler"
    
    /**
     * Callback called when a variation is selected.
     */
    interface OnVariationSelectedListener {
        /**
         * Called when a variation is selected.
         * @param variation The selected variation character
         */
        fun onVariationSelected(variation: String)
    }
    
    /**
     * Creates a listener for a variation button (accent variations).
     * When clicked, deletes character before cursor and inserts the variation.
     */
    fun createVariationClickListener(
        variation: String,
        inputConnection: InputConnection?,
        listener: OnVariationSelectedListener? = null
    ): View.OnClickListener {
        return View.OnClickListener {
            Log.d(TAG, "Click on variation button: $variation")

            if (inputConnection == null) {
                Log.w(TAG, "No inputConnection available to insert variation")
                return@OnClickListener
            }

            // Delete character before cursor (backspace)
            val deleted = inputConnection.deleteSurroundingText(1, 0)
            if (deleted) {
                Log.d(TAG, "Character before cursor deleted")
            } else {
                Log.w(TAG, "Unable to delete character before cursor")
            }

            // Insert variation
            inputConnection.commitText(variation, 1)
            Log.d(TAG, "Variation '$variation' inserted")

            // Notify listener if present
            listener?.onVariationSelected(variation)
        }
    }

    /**
     * Callback for Pinyin candidate selection with index information.
     */
    interface OnPinyinCandidateSelectedListener {
        /**
         * Called when a Pinyin candidate is selected.
         * @param candidate The selected candidate text
         * @param candidateIndex The index of the candidate (accounting for current page)
         */
        fun onPinyinCandidateSelected(candidate: String, candidateIndex: Int)
    }

    /**
     * Creates a listener for a Pinyin candidate button.
     * When clicked, commits the Chinese character and notifies with the candidate index.
     * Does NOT delete any committed text - the Pinyin buffer is shown as composing text.
     * Note: Vibration is handled by VariationBarView, not here.
     */
    fun createPinyinCandidateClickListener(
        candidate: String,
        candidateIndex: Int,
        inputConnection: InputConnection?,
        pinyinListener: OnPinyinCandidateSelectedListener? = null,
        listener: OnVariationSelectedListener? = null,
        @Suppress("UNUSED_PARAMETER") context: Context? = null
    ): View.OnClickListener {
        return View.OnClickListener {
            Log.d(TAG, "Click on Pinyin candidate $candidateIndex: $candidate")

            if (inputConnection == null) {
                Log.w(TAG, "No inputConnection available to insert candidate")
                return@OnClickListener
            }

            // Commit the Chinese character - this automatically replaces any composing text
            inputConnection.commitText(candidate, 1)
            Log.d(TAG, "Pinyin candidate '$candidate' inserted")

            // Notify Pinyin-specific listener with index
            pinyinListener?.onPinyinCandidateSelected(candidate, candidateIndex)

            // Also notify general listener
            listener?.onVariationSelected(candidate)
        }
    }

    /**
     * Creates a listener for a word prediction button.
     * When clicked, deletes the prefix being typed and inserts the complete word + space.
     * Note: Vibration is handled by VariationBarView, not here.
     */
    fun createWordPredictionClickListener(
        word: String,
        prefixLength: Int,
        inputConnection: InputConnection?,
        listener: OnVariationSelectedListener? = null,
        @Suppress("UNUSED_PARAMETER") context: Context? = null
    ): View.OnClickListener {
        return View.OnClickListener {
            Log.d(TAG, "Click on word prediction: $word (prefix length: $prefixLength)")

            if (inputConnection == null) {
                Log.w(TAG, "No inputConnection available to insert word")
                return@OnClickListener
            }

            // Delete the prefix that was typed
            if (prefixLength > 0) {
                inputConnection.deleteSurroundingText(prefixLength, 0)
                Log.d(TAG, "Deleted $prefixLength characters")
            }

            // Insert the complete word followed by a space
            inputConnection.commitText("$word ", 1)
            Log.d(TAG, "Word '$word' inserted")

            // Notify listener if present
            listener?.onVariationSelected(word)
        }
    }
}

