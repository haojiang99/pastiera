package it.neuralrad.coolwulf.inputmethod.ui

import android.content.Context
import android.graphics.Color
import it.neuralrad.coolwulf.SettingsManager

/**
 * Defines the color scheme for the status bar and suggestion/candidate bar.
 */
/**
 * Theme type classification for day/night auto-switching.
 */
enum class ThemeType {
    LIGHT,  // For daytime use
    DARK    // For nighttime use
}

data class StatusBarTheme(
    val id: String,
    val nameResId: Int,
    val themeType: ThemeType = ThemeType.DARK,  // Default to dark
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
    val ledInactiveColor: Int,
    // Virtual keyboard colors
    val virtualKeyboardBackgroundColor: Int = Color.argb(255, 30, 30, 35),
    val virtualKeyBackgroundColor: Int = Color.argb(255, 60, 60, 65),
    val virtualKeyPressedColor: Int = Color.argb(255, 100, 100, 110),
    val virtualKeySpecialColor: Int = Color.argb(255, 45, 45, 50),
    val virtualKeyTextColor: Int = Color.WHITE,
    val virtualKeyPopupBackgroundColor: Int = Color.argb(255, 80, 80, 90),
    val virtualKeyPopupTextColor: Int = Color.WHITE
) {
    companion object {
        // Classic Dark (Default) - The original black theme
        val CLASSIC_DARK = StatusBarTheme(
            id = "classic_dark",
            nameResId = 0, // Will be set via string resource
            themeType = ThemeType.DARK,
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
            ledInactiveColor = Color.argb(26, 255, 255, 255),
            virtualKeyboardBackgroundColor = Color.argb(255, 30, 30, 35),
            virtualKeyBackgroundColor = Color.argb(255, 60, 60, 65),
            virtualKeyPressedColor = Color.argb(255, 100, 100, 110),
            virtualKeySpecialColor = Color.argb(255, 45, 45, 50),
            virtualKeyTextColor = Color.WHITE,
            virtualKeyPopupBackgroundColor = Color.argb(255, 80, 80, 90),
            virtualKeyPopupTextColor = Color.WHITE
        )

        // Ocean Blue - Modern blue gradient feel
        val OCEAN_BLUE = StatusBarTheme(
            id = "ocean_blue",
            nameResId = 0,
            themeType = ThemeType.DARK,
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
            ledInactiveColor = Color.argb(30, 72, 202, 228),
            virtualKeyboardBackgroundColor = Color.parseColor("#0D1B2A"),
            virtualKeyBackgroundColor = Color.parseColor("#1B263B"),
            virtualKeyPressedColor = Color.parseColor("#2E4A6B"),
            virtualKeySpecialColor = Color.parseColor("#152238"),
            virtualKeyTextColor = Color.rgb(200, 220, 255),
            virtualKeyPopupBackgroundColor = Color.parseColor("#1E3A5F"),
            virtualKeyPopupTextColor = Color.WHITE
        )

        // Midnight Purple - Elegant purple theme
        val MIDNIGHT_PURPLE = StatusBarTheme(
            id = "midnight_purple",
            nameResId = 0,
            themeType = ThemeType.DARK,
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
            ledInactiveColor = Color.argb(30, 187, 134, 252),
            virtualKeyboardBackgroundColor = Color.parseColor("#1A1A2E"),
            virtualKeyBackgroundColor = Color.parseColor("#2D2D48"),
            virtualKeyPressedColor = Color.parseColor("#4A3A6E"),
            virtualKeySpecialColor = Color.parseColor("#232338"),
            virtualKeyTextColor = Color.rgb(220, 200, 255),
            virtualKeyPopupBackgroundColor = Color.parseColor("#2D1B4E"),
            virtualKeyPopupTextColor = Color.WHITE
        )

        // Forest Green - Natural green theme
        val FOREST_GREEN = StatusBarTheme(
            id = "forest_green",
            nameResId = 0,
            themeType = ThemeType.DARK,
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
            ledInactiveColor = Color.argb(30, 102, 204, 102),
            virtualKeyboardBackgroundColor = Color.parseColor("#0D1F0D"),
            virtualKeyBackgroundColor = Color.parseColor("#1A2E1A"),
            virtualKeyPressedColor = Color.parseColor("#2E4A2E"),
            virtualKeySpecialColor = Color.parseColor("#142614"),
            virtualKeyTextColor = Color.rgb(200, 255, 200),
            virtualKeyPopupBackgroundColor = Color.parseColor("#1E3D1E"),
            virtualKeyPopupTextColor = Color.WHITE
        )

        // Sunset Orange - Warm orange/coral theme
        val SUNSET_ORANGE = StatusBarTheme(
            id = "sunset_orange",
            nameResId = 0,
            themeType = ThemeType.DARK,
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
            ledInactiveColor = Color.argb(30, 255, 138, 101),
            virtualKeyboardBackgroundColor = Color.parseColor("#1F1410"),
            virtualKeyBackgroundColor = Color.parseColor("#2E1A14"),
            virtualKeyPressedColor = Color.parseColor("#4A2E20"),
            virtualKeySpecialColor = Color.parseColor("#261812"),
            virtualKeyTextColor = Color.rgb(255, 220, 200),
            virtualKeyPopupBackgroundColor = Color.parseColor("#3D2418"),
            virtualKeyPopupTextColor = Color.WHITE
        )

        // Rose Gold - Elegant pink/rose theme
        val ROSE_GOLD = StatusBarTheme(
            id = "rose_gold",
            nameResId = 0,
            themeType = ThemeType.DARK,
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
            ledInactiveColor = Color.argb(30, 255, 145, 164),
            virtualKeyboardBackgroundColor = Color.parseColor("#1A1418"),
            virtualKeyBackgroundColor = Color.parseColor("#2E2428"),
            virtualKeyPressedColor = Color.parseColor("#4A3A40"),
            virtualKeySpecialColor = Color.parseColor("#221C20"),
            virtualKeyTextColor = Color.rgb(255, 210, 220),
            virtualKeyPopupBackgroundColor = Color.parseColor("#3D2430"),
            virtualKeyPopupTextColor = Color.WHITE
        )

        // Charcoal Gray - Sophisticated dark gray
        val CHARCOAL_GRAY = StatusBarTheme(
            id = "charcoal_gray",
            nameResId = 0,
            themeType = ThemeType.DARK,
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
            ledInactiveColor = Color.argb(30, 142, 142, 147),
            virtualKeyboardBackgroundColor = Color.parseColor("#1C1C1E"),
            virtualKeyBackgroundColor = Color.parseColor("#2C2C2E"),
            virtualKeyPressedColor = Color.parseColor("#4A4A4E"),
            virtualKeySpecialColor = Color.parseColor("#242426"),
            virtualKeyTextColor = Color.rgb(230, 230, 230),
            virtualKeyPopupBackgroundColor = Color.parseColor("#3A3A3C"),
            virtualKeyPopupTextColor = Color.WHITE
        )

        // Cyber Neon - Vibrant cyberpunk style
        val CYBER_NEON = StatusBarTheme(
            id = "cyber_neon",
            nameResId = 0,
            themeType = ThemeType.DARK,
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
            ledInactiveColor = Color.argb(30, 0, 255, 255),
            virtualKeyboardBackgroundColor = Color.parseColor("#0A0A0F"),
            virtualKeyBackgroundColor = Color.parseColor("#1A1A24"),
            virtualKeyPressedColor = Color.parseColor("#2E1A3E"),
            virtualKeySpecialColor = Color.parseColor("#12121A"),
            virtualKeyTextColor = Color.rgb(0, 255, 255),
            virtualKeyPopupBackgroundColor = Color.parseColor("#1A0A2E"),
            virtualKeyPopupTextColor = Color.rgb(0, 255, 255)
        )

        // Silver Light - Clean light silver theme for daytime
        val SILVER_LIGHT = StatusBarTheme(
            id = "silver_light",
            nameResId = 0,
            themeType = ThemeType.LIGHT,
            backgroundColor = Color.parseColor("#E8E8ED"),
            textColor = Color.parseColor("#1C1C1E"),
            textColorSecondary = Color.argb(180, 60, 60, 67),
            accentColor = Color.parseColor("#007AFF"),
            buttonBackgroundColor = Color.argb(40, 0, 0, 0),
            buttonPressedColor = Color.argb(80, 0, 0, 0),
            candidateBackgroundColor = Color.parseColor("#F2F2F7"),
            candidateBestBackgroundColor = Color.parseColor("#D1D1D6"),
            candidateTextColor = Color.parseColor("#1C1C1E"),
            candidateBestTextColor = Color.parseColor("#007AFF"),
            iconColor = Color.parseColor("#3C3C43"),
            iconInactiveColor = Color.parseColor("#AEAEB2"),
            ledActiveColor = Color.parseColor("#34C759"),
            ledLockedColor = Color.parseColor("#FF9500"),
            ledInactiveColor = Color.argb(40, 60, 60, 67),
            virtualKeyboardBackgroundColor = Color.parseColor("#D1D1D6"),
            virtualKeyBackgroundColor = Color.parseColor("#FFFFFF"),
            virtualKeyPressedColor = Color.parseColor("#BFBFC4"),
            virtualKeySpecialColor = Color.parseColor("#AEAEB2"),
            virtualKeyTextColor = Color.parseColor("#1C1C1E"),
            virtualKeyPopupBackgroundColor = Color.parseColor("#FFFFFF"),
            virtualKeyPopupTextColor = Color.parseColor("#1C1C1E")
        )

        // Warm Cream - Soft warm light theme
        val WARM_CREAM = StatusBarTheme(
            id = "warm_cream",
            nameResId = 0,
            themeType = ThemeType.LIGHT,
            backgroundColor = Color.parseColor("#FAF8F5"),
            textColor = Color.parseColor("#2C2417"),
            textColorSecondary = Color.argb(180, 80, 70, 50),
            accentColor = Color.parseColor("#B8860B"),
            buttonBackgroundColor = Color.argb(35, 80, 60, 30),
            buttonPressedColor = Color.argb(70, 80, 60, 30),
            candidateBackgroundColor = Color.parseColor("#F5F0E8"),
            candidateBestBackgroundColor = Color.parseColor("#E8DFD0"),
            candidateTextColor = Color.parseColor("#2C2417"),
            candidateBestTextColor = Color.parseColor("#8B6914"),
            iconColor = Color.parseColor("#5D4E37"),
            iconInactiveColor = Color.parseColor("#B8A88A"),
            ledActiveColor = Color.parseColor("#6B8E23"),
            ledLockedColor = Color.parseColor("#CD853F"),
            ledInactiveColor = Color.argb(40, 80, 70, 50),
            virtualKeyboardBackgroundColor = Color.parseColor("#E8DFD0"),
            virtualKeyBackgroundColor = Color.parseColor("#FAF8F5"),
            virtualKeyPressedColor = Color.parseColor("#D5CFC2"),
            virtualKeySpecialColor = Color.parseColor("#D8D0C0"),
            virtualKeyTextColor = Color.parseColor("#2C2417"),
            virtualKeyPopupBackgroundColor = Color.parseColor("#FAF8F5"),
            virtualKeyPopupTextColor = Color.parseColor("#2C2417")
        )

        // Sky Blue Light - Fresh blue light theme
        val SKY_BLUE_LIGHT = StatusBarTheme(
            id = "sky_blue_light",
            nameResId = 0,
            themeType = ThemeType.LIGHT,
            backgroundColor = Color.parseColor("#E3F2FD"),
            textColor = Color.parseColor("#0D47A1"),
            textColorSecondary = Color.argb(180, 30, 80, 150),
            accentColor = Color.parseColor("#1976D2"),
            buttonBackgroundColor = Color.argb(40, 25, 118, 210),
            buttonPressedColor = Color.argb(80, 25, 118, 210),
            candidateBackgroundColor = Color.parseColor("#BBDEFB"),
            candidateBestBackgroundColor = Color.parseColor("#90CAF9"),
            candidateTextColor = Color.parseColor("#0D47A1"),
            candidateBestTextColor = Color.parseColor("#1565C0"),
            iconColor = Color.parseColor("#1565C0"),
            iconInactiveColor = Color.parseColor("#90CAF9"),
            ledActiveColor = Color.parseColor("#2196F3"),
            ledLockedColor = Color.parseColor("#FF5722"),
            ledInactiveColor = Color.argb(40, 25, 118, 210),
            virtualKeyboardBackgroundColor = Color.parseColor("#BBDEFB"),
            virtualKeyBackgroundColor = Color.parseColor("#E3F2FD"),
            virtualKeyPressedColor = Color.parseColor("#90CAF9"),
            virtualKeySpecialColor = Color.parseColor("#BBDEFB"),
            virtualKeyTextColor = Color.parseColor("#0D47A1"),
            virtualKeyPopupBackgroundColor = Color.parseColor("#E3F2FD"),
            virtualKeyPopupTextColor = Color.parseColor("#0D47A1")
        )

        // Mint Fresh - Cool mint green light theme
        val MINT_FRESH = StatusBarTheme(
            id = "mint_fresh",
            nameResId = 0,
            themeType = ThemeType.LIGHT,
            backgroundColor = Color.parseColor("#E8F5E9"),
            textColor = Color.parseColor("#1B5E20"),
            textColorSecondary = Color.argb(180, 40, 100, 50),
            accentColor = Color.parseColor("#43A047"),
            buttonBackgroundColor = Color.argb(40, 67, 160, 71),
            buttonPressedColor = Color.argb(80, 67, 160, 71),
            candidateBackgroundColor = Color.parseColor("#C8E6C9"),
            candidateBestBackgroundColor = Color.parseColor("#A5D6A7"),
            candidateTextColor = Color.parseColor("#1B5E20"),
            candidateBestTextColor = Color.parseColor("#2E7D32"),
            iconColor = Color.parseColor("#2E7D32"),
            iconInactiveColor = Color.parseColor("#A5D6A7"),
            ledActiveColor = Color.parseColor("#4CAF50"),
            ledLockedColor = Color.parseColor("#FF9800"),
            ledInactiveColor = Color.argb(40, 67, 160, 71),
            virtualKeyboardBackgroundColor = Color.parseColor("#C8E6C9"),
            virtualKeyBackgroundColor = Color.parseColor("#E8F5E9"),
            virtualKeyPressedColor = Color.parseColor("#A5D6A7"),
            virtualKeySpecialColor = Color.parseColor("#C8E6C9"),
            virtualKeyTextColor = Color.parseColor("#1B5E20"),
            virtualKeyPopupBackgroundColor = Color.parseColor("#E8F5E9"),
            virtualKeyPopupTextColor = Color.parseColor("#1B5E20")
        )

        // Lavender Mist - Soft purple light theme
        val LAVENDER_MIST = StatusBarTheme(
            id = "lavender_mist",
            nameResId = 0,
            themeType = ThemeType.LIGHT,
            backgroundColor = Color.parseColor("#F3E5F5"),
            textColor = Color.parseColor("#4A148C"),
            textColorSecondary = Color.argb(180, 90, 40, 140),
            accentColor = Color.parseColor("#7B1FA2"),
            buttonBackgroundColor = Color.argb(40, 123, 31, 162),
            buttonPressedColor = Color.argb(80, 123, 31, 162),
            candidateBackgroundColor = Color.parseColor("#E1BEE7"),
            candidateBestBackgroundColor = Color.parseColor("#CE93D8"),
            candidateTextColor = Color.parseColor("#4A148C"),
            candidateBestTextColor = Color.parseColor("#6A1B9A"),
            iconColor = Color.parseColor("#6A1B9A"),
            iconInactiveColor = Color.parseColor("#CE93D8"),
            ledActiveColor = Color.parseColor("#9C27B0"),
            ledLockedColor = Color.parseColor("#E91E63"),
            ledInactiveColor = Color.argb(40, 123, 31, 162),
            virtualKeyboardBackgroundColor = Color.parseColor("#E1BEE7"),
            virtualKeyBackgroundColor = Color.parseColor("#F3E5F5"),
            virtualKeyPressedColor = Color.parseColor("#CE93D8"),
            virtualKeySpecialColor = Color.parseColor("#E1BEE7"),
            virtualKeyTextColor = Color.parseColor("#4A148C"),
            virtualKeyPopupBackgroundColor = Color.parseColor("#F3E5F5"),
            virtualKeyPopupTextColor = Color.parseColor("#4A148C")
        )

        val ALL_THEMES = listOf(
            CLASSIC_DARK,
            OCEAN_BLUE,
            MIDNIGHT_PURPLE,
            FOREST_GREEN,
            SUNSET_ORANGE,
            ROSE_GOLD,
            CHARCOAL_GRAY,
            CYBER_NEON,
            SILVER_LIGHT,
            WARM_CREAM,
            SKY_BLUE_LIGHT,
            MINT_FRESH,
            LAVENDER_MIST
        )

        const val CUSTOM_THEME_ID = "custom"
        const val CUSTOM_THEME_1_ID = "custom_1"
        const val CUSTOM_THEME_2_ID = "custom_2"
        const val CUSTOM_THEME_3_ID = "custom_3"

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
            // Custom theme slots
            if (id == CUSTOM_THEME_1_ID) {
                return getCustomThemeForSlot(context, 1)
            }
            if (id == CUSTOM_THEME_2_ID) {
                return getCustomThemeForSlot(context, 2)
            }
            if (id == CUSTOM_THEME_3_ID) {
                return getCustomThemeForSlot(context, 3)
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
                themeType = ThemeType.DARK,
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
                ledInactiveColor = colors["ledInactiveColor"] ?: CLASSIC_DARK.ledInactiveColor,
                virtualKeyboardBackgroundColor = colors["virtualKeyboardBackgroundColor"] ?: CLASSIC_DARK.virtualKeyboardBackgroundColor,
                virtualKeyBackgroundColor = colors["virtualKeyBackgroundColor"] ?: CLASSIC_DARK.virtualKeyBackgroundColor,
                virtualKeyPressedColor = colors["virtualKeyPressedColor"] ?: CLASSIC_DARK.virtualKeyPressedColor,
                virtualKeySpecialColor = colors["virtualKeySpecialColor"] ?: CLASSIC_DARK.virtualKeySpecialColor,
                virtualKeyTextColor = colors["virtualKeyTextColor"] ?: CLASSIC_DARK.virtualKeyTextColor,
                virtualKeyPopupBackgroundColor = colors["virtualKeyPopupBackgroundColor"] ?: CLASSIC_DARK.virtualKeyPopupBackgroundColor,
                virtualKeyPopupTextColor = colors["virtualKeyPopupTextColor"] ?: CLASSIC_DARK.virtualKeyPopupTextColor
            )
        }

        /**
         * Creates a custom theme for a specific slot (1, 2, or 3).
         */
        fun getCustomThemeForSlot(context: Context, slot: Int): StatusBarTheme {
            val colors = SettingsManager.getCustomThemeColorsForSlot(context, slot)
            val slotId = when (slot) {
                1 -> CUSTOM_THEME_1_ID
                2 -> CUSTOM_THEME_2_ID
                3 -> CUSTOM_THEME_3_ID
                else -> CUSTOM_THEME_1_ID
            }
            val themeTypeStr = SettingsManager.getCustomThemeSlotType(context, slot)
            val themeType = if (themeTypeStr == "light") ThemeType.LIGHT else ThemeType.DARK
            return StatusBarTheme(
                id = slotId,
                nameResId = 0,
                themeType = themeType,
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
                ledInactiveColor = colors["ledInactiveColor"] ?: CLASSIC_DARK.ledInactiveColor,
                virtualKeyboardBackgroundColor = colors["virtualKeyboardBackgroundColor"] ?: CLASSIC_DARK.virtualKeyboardBackgroundColor,
                virtualKeyBackgroundColor = colors["virtualKeyBackgroundColor"] ?: CLASSIC_DARK.virtualKeyBackgroundColor,
                virtualKeyPressedColor = colors["virtualKeyPressedColor"] ?: CLASSIC_DARK.virtualKeyPressedColor,
                virtualKeySpecialColor = colors["virtualKeySpecialColor"] ?: CLASSIC_DARK.virtualKeySpecialColor,
                virtualKeyTextColor = colors["virtualKeyTextColor"] ?: CLASSIC_DARK.virtualKeyTextColor,
                virtualKeyPopupBackgroundColor = colors["virtualKeyPopupBackgroundColor"] ?: CLASSIC_DARK.virtualKeyPopupBackgroundColor,
                virtualKeyPopupTextColor = colors["virtualKeyPopupTextColor"] ?: CLASSIC_DARK.virtualKeyPopupTextColor
            )
        }

        /**
         * Gets all themes of a specific type (light or dark).
         */
        fun getThemesByType(type: ThemeType): List<StatusBarTheme> {
            return ALL_THEMES.filter { it.themeType == type }
        }

        /**
         * Gets all light themes.
         */
        fun getLightThemes(): List<StatusBarTheme> = getThemesByType(ThemeType.LIGHT)

        /**
         * Gets all dark themes.
         */
        fun getDarkThemes(): List<StatusBarTheme> = getThemesByType(ThemeType.DARK)

        /**
         * Checks if a theme ID is a custom theme slot.
         */
        fun isCustomThemeSlot(id: String): Boolean {
            return id == CUSTOM_THEME_1_ID || id == CUSTOM_THEME_2_ID || id == CUSTOM_THEME_3_ID
        }

        /**
         * Gets the slot number from a custom theme ID (1, 2, or 3).
         */
        fun getSlotFromThemeId(id: String): Int {
            return when (id) {
                CUSTOM_THEME_1_ID -> 1
                CUSTOM_THEME_2_ID -> 2
                CUSTOM_THEME_3_ID -> 3
                else -> 0
            }
        }
    }
}
