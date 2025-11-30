package it.neuralrad.coolwulf.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages clipboard history storage and retrieval.
 * Stores clipboard entries in SharedPreferences as JSON.
 */
object ClipboardHistoryManager {
    private const val TAG = "ClipboardHistoryManager"
    private const val PREFS_NAME = "clipboard_history_prefs"
    private const val KEY_HISTORY = "clipboard_history"
    private const val KEY_ENABLED = "clipboard_history_enabled"
    private const val MAX_HISTORY_SIZE = 20
    private const val MAX_ENTRY_LENGTH = 1000 // Maximum characters per entry

    data class ClipboardEntry(
        val text: String,
        val timestamp: Long
    )

    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var isListening = false

    /**
     * Returns whether clipboard history is enabled.
     */
    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    /**
     * Sets whether clipboard history is enabled.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

        if (enabled) {
            startListening(context)
        } else {
            stopListening(context)
        }
    }

    /**
     * Starts listening for clipboard changes.
     */
    fun startListening(context: Context) {
        if (!isEnabled(context) || isListening) return

        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboardManager != null && clipboardListener == null) {
                clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
                    onClipboardChanged(context, clipboardManager)
                }
                clipboardManager.addPrimaryClipChangedListener(clipboardListener)
                isListening = true
                Log.d(TAG, "Started listening for clipboard changes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting clipboard listener", e)
        }
    }

    /**
     * Stops listening for clipboard changes.
     */
    fun stopListening(context: Context) {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboardManager != null && clipboardListener != null) {
                clipboardManager.removePrimaryClipChangedListener(clipboardListener)
                clipboardListener = null
                isListening = false
                Log.d(TAG, "Stopped listening for clipboard changes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping clipboard listener", e)
        }
    }

    /**
     * Called when clipboard content changes.
     */
    private fun onClipboardChanged(context: Context, clipboardManager: ClipboardManager) {
        if (!isEnabled(context)) return

        try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val text = item.text?.toString()
                if (!text.isNullOrBlank()) {
                    addEntry(context, text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading clipboard", e)
        }
    }

    /**
     * Adds a new entry to the clipboard history.
     */
    fun addEntry(context: Context, text: String) {
        if (text.isBlank()) return

        val trimmedText = if (text.length > MAX_ENTRY_LENGTH) {
            text.substring(0, MAX_ENTRY_LENGTH)
        } else {
            text
        }

        val history = getHistory(context).toMutableList()

        // Remove duplicate if exists
        history.removeAll { it.text == trimmedText }

        // Add new entry at the beginning
        history.add(0, ClipboardEntry(trimmedText, System.currentTimeMillis()))

        // Trim to max size
        while (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(history.lastIndex)
        }

        saveHistory(context, history)
        Log.d(TAG, "Added clipboard entry: ${trimmedText.take(50)}...")
    }

    /**
     * Returns the clipboard history.
     */
    fun getHistory(context: Context): List<ClipboardEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_HISTORY, null) ?: return emptyList()

        return try {
            val jsonArray = JSONArray(jsonString)
            val history = mutableListOf<ClipboardEntry>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                history.add(ClipboardEntry(
                    text = obj.getString("text"),
                    timestamp = obj.getLong("timestamp")
                ))
            }
            history
        } catch (e: Exception) {
            Log.e(TAG, "Error loading clipboard history", e)
            emptyList()
        }
    }

    /**
     * Saves the clipboard history.
     */
    private fun saveHistory(context: Context, history: List<ClipboardEntry>) {
        try {
            val jsonArray = JSONArray()
            for (entry in history) {
                val obj = JSONObject()
                obj.put("text", entry.text)
                obj.put("timestamp", entry.timestamp)
                jsonArray.put(obj)
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving clipboard history", e)
        }
    }

    /**
     * Clears the clipboard history.
     */
    fun clearHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_HISTORY).apply()
        Log.d(TAG, "Clipboard history cleared")
    }

    /**
     * Removes a specific entry from the clipboard history.
     */
    fun removeEntry(context: Context, text: String) {
        val history = getHistory(context).toMutableList()
        history.removeAll { it.text == text }
        saveHistory(context, history)
    }

    /**
     * Copies text to clipboard and adds to history.
     */
    fun copyToClipboard(context: Context, text: String) {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Pastiera", text)
            clipboardManager?.setPrimaryClip(clip)
            // Entry will be added by the listener
        } catch (e: Exception) {
            Log.e(TAG, "Error copying to clipboard", e)
        }
    }
}
