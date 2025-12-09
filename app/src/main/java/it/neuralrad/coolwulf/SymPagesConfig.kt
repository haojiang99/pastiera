package it.neuralrad.coolwulf

/**
 * Configuration for SYM pages order and visibility.
 */
data class SymPagesConfig(
    val emojiEnabled: Boolean = true,
    val symbolsEnabled: Boolean = true,
    val symbols2Enabled: Boolean = false,
    val emojiFirst: Boolean = true
)
