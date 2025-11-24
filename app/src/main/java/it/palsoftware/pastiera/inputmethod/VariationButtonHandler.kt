package it.palsoftware.pastiera.inputmethod

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
<<<<<<< HEAD

    /**
     * Creates a listener for a word prediction button.
     * When clicked, deletes the prefix being typed and inserts the complete word + space.
     */
    fun createWordPredictionClickListener(
        word: String,
        prefixLength: Int,
        inputConnection: InputConnection?,
        listener: OnVariationSelectedListener? = null
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
=======
>>>>>>> 68a62b5a557c9498db1e930a7c17d753b744580a

    /**
     * Creates a listener for a static variation button.
     * When clicked, inserts the variation without deleting the character before the cursor.
     */
    fun createStaticVariationClickListener(
        variation: String,
        inputConnection: InputConnection?,
        listener: OnVariationSelectedListener? = null
    ): View.OnClickListener {
        return View.OnClickListener {
            Log.d(TAG, "Click on static variation button: $variation")

            if (inputConnection == null) {
                Log.w(TAG, "No inputConnection available to insert static variation")
                return@OnClickListener
            }

            // Insert variation without deleting previous character
            inputConnection.commitText(variation, 1)
            Log.d(TAG, "Static variation '$variation' inserted")

            // Notify listener if present
            listener?.onVariationSelected(variation)
        }
    }
}
