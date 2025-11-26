package it.neuralrad.coolwulf.inputmethod

import android.util.Log
import android.view.MotionEvent

/**
 * Handles motion events from trackpad/touch-sensitive keyboards.
 */
class MotionEventController(private val logTag: String) {

    /**
     * Handles a motion event.
     * @param event The motion event to handle
     * @return true if the event was handled, false otherwise, or null to delegate to default handling
     */
    fun handle(event: MotionEvent?): Boolean? {
        if (event == null) {
            return null
        }

        // For now, just log the event and return null to use default handling
        Log.d(logTag, "Motion event received: action=${event.action}, x=${event.x}, y=${event.y}")

        // Return null to delegate to default handling
        return null
    }
}
