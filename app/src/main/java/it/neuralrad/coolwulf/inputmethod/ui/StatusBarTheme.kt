package it.neuralrad.coolwulf.inputmethod.ui

import android.content.Context
import android.graphics.Color
import it.neuralrad.coolwulf.SettingsManager

/**
 * Defines the color scheme for the status bar and suggestion/candidate bar.
 */
data class StatusBarTheme(
    val id: String,
    val nameResId: Int,
    val backgroundColor: Int,
    val textColor: Int,
    val textColorSecondary: Int,
    val accentColor: Int,
    val buttonBackgroundColor: Int,
    val buttonPressedColor: Int,
    val candidateBackgroundColor: Int,
    val candidateBestBackgroundColor: Int,
    val candidateTextColor: Int,
    val candidateBestTextColor: Int,
    val iconColor: Int,
    val iconInactiveColor: Int,
    val ledActiveColor: Int,
    val ledLockedColor: Int,
    val ledInactiveColor: Int
) {
    companion object {
        // Classic Dark (Default) - The original black theme
        val CLASSIC_DARK = StatusBarTheme(
            id = "classic_dark",
            nameResId = 0, // Will be set via string resource
            backgroundColor = Color.parseColor("#000000"),
            textColor = Color.WHITE,
            textColorSecondary = Color.argb(180, 255, 255, 255),
            accentColor = Color.rgb(100, 200, 255),
            buttonBackgroundColor = Color.argb(40, 255, 255, 255),
            buttonPressedColor = Color.argb(80, 255, 255, 255),
            candidateBackgroundColor = Color.rgb(17, 17, 17),
            candidateBestBackgroundColor = Color.rgb(50, 45, 10),
            candidateTextColor = Color.WHITE,
            candidateBestTextColor = Color.rgb(255, 215, 0),
            iconColor = Color.WHITE,
            iconInactiveColor = Color.rgb(100, 100, 100),
            ledActiveColor = Color.rgb(100, 150, 255),
            ledLockedColor = Color.rgb(247, 99, 0),
            ledInactiveColor = Color.argb(26, 255, 255, 255)
        )

        // Ocean Blue - Modern blue gradient feel
        val OCEAN_BLUE = StatusBarTheme(
            id = "ocean_blue",
            nameResId = 0,
            backgroundColor = Color.parseColor("#0D1B2A"),
            textColor = Color.WHITE,
            textColorSecondary = Color.argb(200, 200, 220, 255),
            accentColor = Color.rgb(72, 202, 228),
            buttonBackgroundColor = Color.argb(50, 72, 202, 228),
            buttonPressedColor = Color.argb(100, 72, 202, 228),
            candidateBackgroundColor = Color.parseColor("#1B263B"),
            candidateBestBackgroundColor = Color.parseColor("#1E3A5F"),
            candidateTextColor = Color.rgb(200, 220, 255),
            candidateBestTextColor = Color.rgb(72, 202, 228),
            iconColor = Color.rgb(200, 220, 255),
            iconInactiveColor = Color.rgb(80, 100, 130),
            ledActiveColor = Color.rgb(72, 202, 228),
            ledLockedColor = Color.rgb(255, 107, 107),
            ledInactiveColor = Color.argb(30, 72, 202, 228)
        )

        // Midnight Purple - Elegant purple theme
        val MIDNIGHT_PURPLE = StatusBarTheme(
            id = "midnight_purple",
            nameResId = 0,
            backgroundColor = Color.parseColor("#1A1A2E"),
            textColor = Color.WHITE,
            textColorSecondary = Color.argb(200, 220, 200, 255),
            accentColor = Color.rgb(187, 134, 252),
            buttonBackgroundColor = Color.argb(50, 187, 134, 252),
            buttonPressedColor = Color.argb(100, 187, 134, 252),
            candidateBackgroundColor = Color.parseColor("#16213E"),
            candidateBestBackgroundColor = Color.parseColor("#2D1B4E"),
            candidateTextColor = Color.rgb(220, 200, 255),
            candidateBestTextColor = Color.rgb(187, 134, 252),
            iconColor = Color.rgb(220, 200, 255),
            iconInactiveColor = Color.rgb(100, 80, 130),
            ledActiveColor = Color.rgb(187, 134, 252),
            ledLockedColor = Color.rgb(255, 82, 82),
            ledInactiveColor = Color.argb(30, 187, 134, 252)
        )

        // Forest Green - Natural green theme
        val FOREST_GREEN = StatusBarTheme(
            id = "forest_green",
            nameResId = 0,
            backgroundColor = Color.parseColor("#0D1F0D"),
            textColor = Color.WHITE,
            textColorSecondary = Color.argb(200, 200, 255, 200),
            accentColor = Color.rgb(102, 204, 102),
            buttonBackgroundColor = Color.argb(50, 102, 204, 102),
            buttonPressedColor = Color.argb(100, 102, 204, 102),
            candidateBackgroundColor = Color.parseColor("#1A2E1A"),
            candidateBestBackgroundColor = Color.parseColor("#1E3D1E"),
            candidateTextColor = Color.rgb(200, 255, 200),
            candidateBestTextColor = Color.rgb(102, 204, 102),
            iconColor = Color.rgb(200, 255, 200),
            iconInactiveColor = Color.rgb(80, 130, 80),
            ledActiveColor = Color.rgb(102, 204, 102),
            ledLockedColor = Color.rgb(255, 152, 0),
            ledInactiveColor = Color.argb(30, 102, 204, 102)
        )

        // Sunset Orange - Warm orange/coral theme
        val SUNSET_ORANGE = StatusBarTheme(
            id = "sunset_orange",
            nameResId = 0,
            backgroundColor = Color.parseColor("#1F1410"),
            textColor = Color.WHITE,
            textColorSecondary = Color.argb(200, 255, 220, 200),
            accentColor = Color.rgb(255, 138, 101),
            buttonBackgroundColor = Color.argb(50, 255, 138, 101),
            buttonPressedColor = Color.argb(100, 255, 138, 101),
            candidateBackgroundColor = Color.parseColor("#2E1A14"),
            candidateBestBackgroundColor = Color.parseColor("#3D2418"),
            candidateTextColor = Color.rgb(255, 220, 200),
            candidateBestTextColor = Color.rgb(255, 138, 101),
            iconColor = Color.rgb(255, 220, 200),
            iconInactiveColor = Color.rgb(130, 90, 70),
            ledActiveColor = Color.rgb(255, 138, 101),
            ledLockedColor = Color.rgb(255, 82, 82),
            ledInactiveColor = Color.argb(30, 255, 138, 101)
        )

        // Rose Gold - Elegant pink/rose theme
        val ROSE_GOLD = StatusBarTheme(
            id = "rose_gold",
            nameResId = 0,
            backgroundColor = Color.parseColor("#1A1418"),
            textColor = Color.WHITE,
            textColorSecondary = Color.argb(200, 255, 210, 220),
            accentColor = Color.rgb(255, 145, 164),
            buttonBackgroundColor = Color.argb(50, 255, 145, 164),
            buttonPressedColor = Color.argb(100, 255, 145, 164),
            candidateBackgroundColor = Color.parseColor("#241A1E"),
            candidateBestBackgroundColor = Color.parseColor("#3D2430"),
            candidateTextColor = Color.rgb(255, 210, 220),
            candidateBestTextColor = Color.rgb(255, 145, 164),
            iconColor = Color.rgb(255, 210, 220),
            iconInactiveColor = Color.rgb(130, 90, 100),
            ledActiveColor = Color.rgb(255, 145, 164),
            ledLockedColor = Color.rgb(255, 82, 82),
            ledInactiveColor = Color.argb(30, 255, 145, 164)
        )

        // Charcoal Gray - Sophisticated dark gray
        val CHARCOAL_GRAY = StatusBarTheme(
            id = "charcoal_gray",
            nameResId = 0,
            backgroundColor = Color.parseColor("#1C1C1E"),
            textColor = Color.WHITE,
            textColorSecondary = Color.argb(200, 200, 200, 200),
            accentColor = Color.rgb(142, 142, 147),
            buttonBackgroundColor = Color.argb(50, 142, 142, 147),
            buttonPressedColor = Color.argb(100, 142, 142, 147),
            candidateBackgroundColor = Color.parseColor("#2C2C2E"),
            candidateBestBackgroundColor = Color.parseColor("#3A3A3C"),
            candidateTextColor = Color.rgb(230, 230, 230),
            candidateBestTextColor = Color.WHITE,
            iconColor = Color.rgb(200, 200, 200),
            iconInactiveColor = Color.rgb(100, 100, 100),
            ledActiveColor = Color.rgb(10, 132, 255),
            ledLockedColor = Color.rgb(255, 149, 0),
            ledInactiveColor = Color.argb(30, 142, 142, 147)
        )

        // Cyber Neon - Vibrant cyberpunk style
        val CYBER_NEON = StatusBarTheme(
            id = "cyber_neon",
            nameResId = 0,
            backgroundColor = Color.parseColor("#0A0A0F"),
            textColor = Color.rgb(0, 255, 255),
            textColorSecondary = Color.argb(200, 0, 200, 200),
            accentColor = Color.rgb(255, 0, 255),
            buttonBackgroundColor = Color.argb(50, 255, 0, 255),
            buttonPressedColor = Color.argb(100, 255, 0, 255),
            candidateBackgroundColor = Color.parseColor("#12121A"),
            candidateBestBackgroundColor = Color.parseColor("#1A0A2E"),
            candidateTextColor = Color.rgb(0, 255, 255),
            candidateBestTextColor = Color.rgb(255, 0, 255),
            iconColor = Color.rgb(0, 255, 255),
            iconInactiveColor = Color.rgb(0, 100, 100),
            ledActiveColor = Color.rgb(0, 255, 255),
            ledLockedColor = Color.rgb(255, 0, 255),
            ledInactiveColor = Color.argb(30, 0, 255, 255)
        )

        val ALL_THEMES = listOf(
            CLASSIC_DARK,
            OCEAN_BLUE,
            MIDNIGHT_PURPLE,
            FOREST_GREEN,
            SUNSET_ORANGE,
            ROSE_GOLD,
            CHARCOAL_GRAY,
            CYBER_NEON
        )

        const val CUSTOM_THEME_ID = "custom"

        fun getThemeById(id: String): StatusBarTheme {
            return ALL_THEMES.find { it.id == id } ?: CLASSIC_DARK
        }

        /**
         * Gets a theme by ID, with context for custom theme support.
         */
        fun getThemeById(id: String, context: Context): StatusBarTheme {
            if (id == CUSTOM_THEME_ID) {
                return getCustomTheme(context)
            }
            return ALL_THEMES.find { it.id == id } ?: CLASSIC_DARK
        }

        /**
         * Creates a custom theme from user settings.
         */
        fun getCustomTheme(context: Context): StatusBarTheme {
            val colors = SettingsManager.getCustomThemeColors(context)
            return StatusBarTheme(
                id = CUSTOM_THEME_ID,
                nameResId = 0,
                backgroundColor = colors["backgroundColor"] ?: CLASSIC_DARK.backgroundColor,
                textColor = colors["textColor"] ?: CLASSIC_DARK.textColor,
                textColorSecondary = colors["textColorSecondary"] ?: CLASSIC_DARK.textColorSecondary,
                accentColor = colors["accentColor"] ?: CLASSIC_DARK.accentColor,
                buttonBackgroundColor = colors["buttonBackgroundColor"] ?: CLASSIC_DARK.buttonBackgroundColor,
                buttonPressedColor = colors["buttonPressedColor"] ?: CLASSIC_DARK.buttonPressedColor,
                candidateBackgroundColor = colors["candidateBackgroundColor"] ?: CLASSIC_DARK.candidateBackgroundColor,
                candidateBestBackgroundColor = colors["candidateBestBackgroundColor"] ?: CLASSIC_DARK.candidateBestBackgroundColor,
                candidateTextColor = colors["candidateTextColor"] ?: CLASSIC_DARK.candidateTextColor,
                candidateBestTextColor = colors["candidateBestTextColor"] ?: CLASSIC_DARK.candidateBestTextColor,
                iconColor = colors["iconColor"] ?: CLASSIC_DARK.iconColor,
                iconInactiveColor = colors["iconInactiveColor"] ?: CLASSIC_DARK.iconInactiveColor,
                ledActiveColor = colors["ledActiveColor"] ?: CLASSIC_DARK.ledActiveColor,
                ledLockedColor = colors["ledLockedColor"] ?: CLASSIC_DARK.ledLockedColor,
                ledInactiveColor = colors["ledInactiveColor"] ?: CLASSIC_DARK.ledInactiveColor
            )
        }
    }
}
