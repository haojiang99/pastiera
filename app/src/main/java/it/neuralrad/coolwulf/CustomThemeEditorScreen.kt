package it.neuralrad.coolwulf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.neuralrad.coolwulf.inputmethod.ui.StatusBarTheme

/**
 * Custom theme editor screen with color pickers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomThemeEditorScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Load current custom theme colors
    var colors by remember {
        mutableStateOf(SettingsManager.getCustomThemeColors(context).mapValues { Color(it.value) })
    }

    // Currently selected color for editing
    var editingColorKey by remember { mutableStateOf<String?>(null) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showCopyFromDialog by remember { mutableStateOf(false) }

    // Color picker state
    var pickerHue by remember { mutableFloatStateOf(0f) }
    var pickerSaturation by remember { mutableFloatStateOf(1f) }
    var pickerValue by remember { mutableFloatStateOf(1f) }
    var pickerAlpha by remember { mutableFloatStateOf(1f) }

    fun saveColor(key: String, color: Color) {
        SettingsManager.setCustomThemeColor(context, key, color.toArgb())
        // Create a new map to trigger recomposition
        colors = HashMap(colors).apply { this[key] = color }
        // Broadcast theme change if custom theme is currently active
        if (SettingsManager.getStatusBarTheme(context) == StatusBarTheme.CUSTOM_THEME_ID) {
            context.sendBroadcast(
                android.content.Intent(it.neuralrad.coolwulf.inputmethod.PhysicalKeyboardInputMethodService.ACTION_THEME_CHANGED).apply {
                    setPackage(context.packageName)
                }
            )
        }
    }

    fun colorToHsv(color: Color): FloatArray {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        return hsv
    }

    fun hsvToColor(h: Float, s: Float, v: Float, a: Float): Color {
        val argb = android.graphics.Color.HSVToColor((a * 255).toInt(), floatArrayOf(h, s, v))
        return Color(argb)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.theme_custom_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCopyFromDialog = true }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.theme_copy_from))
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Preview section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colors["backgroundColor"] ?: Color.Black
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.theme_preview),
                        color = colors["textColor"] ?: Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Sample candidate
                        Box(
                            modifier = Modifier
                                .background(
                                    colors["candidateBackgroundColor"] ?: Color.DarkGray,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Candidate",
                                color = colors["candidateTextColor"] ?: Color.White,
                                fontSize = 12.sp
                            )
                        }
                        // Best candidate
                        Box(
                            modifier = Modifier
                                .background(
                                    colors["candidateBestBackgroundColor"] ?: Color.DarkGray,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Best",
                                color = colors["candidateBestTextColor"] ?: Color.Yellow,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // LED indicators
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(colors["ledActiveColor"] ?: Color.Blue)
                        )
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(colors["ledLockedColor"] ?: Color.Red)
                        )
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(colors["ledInactiveColor"] ?: Color.Gray)
                        )
                    }
                }
            }

            // Color list
            val colorItems = listOf(
                "backgroundColor" to R.string.theme_color_background,
                "textColor" to R.string.theme_color_text,
                "textColorSecondary" to R.string.theme_color_text_secondary,
                "accentColor" to R.string.theme_color_accent,
                "buttonBackgroundColor" to R.string.theme_color_button_bg,
                "buttonPressedColor" to R.string.theme_color_button_pressed,
                "candidateBackgroundColor" to R.string.theme_color_candidate_bg,
                "candidateBestBackgroundColor" to R.string.theme_color_candidate_best_bg,
                "candidateTextColor" to R.string.theme_color_candidate_text,
                "candidateBestTextColor" to R.string.theme_color_candidate_best_text,
                "iconColor" to R.string.theme_color_icon,
                "iconInactiveColor" to R.string.theme_color_icon_inactive,
                "ledActiveColor" to R.string.theme_color_led_active,
                "ledLockedColor" to R.string.theme_color_led_locked,
                "ledInactiveColor" to R.string.theme_color_led_inactive
            )

            colorItems.forEach { (key, labelResId) ->
                ColorPickerRow(
                    label = stringResource(labelResId),
                    color = colors[key] ?: Color.Black,
                    onClick = {
                        editingColorKey = key
                        val hsv = colorToHsv(colors[key] ?: Color.Black)
                        pickerHue = hsv[0]
                        pickerSaturation = hsv[1]
                        pickerValue = hsv[2]
                        pickerAlpha = (colors[key]?.alpha ?: 1f)
                        showColorPicker = true
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Color picker dialog
    if (showColorPicker && editingColorKey != null) {
        AlertDialog(
            onDismissRequest = { showColorPicker = false },
            title = {
                Text(
                    colorItems.find { it.first == editingColorKey }?.second?.let { stringResource(it) } ?: ""
                )
            },
            text = {
                Column {
                    // Color preview
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(hsvToColor(pickerHue, pickerSaturation, pickerValue, pickerAlpha))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Hue slider
                    Text("Hue", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = pickerHue,
                        onValueChange = { pickerHue = it },
                        valueRange = 0f..360f,
                        colors = SliderDefaults.colors(
                            thumbColor = hsvToColor(pickerHue, 1f, 1f, 1f),
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    // Saturation slider
                    Text("Saturation", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = pickerSaturation,
                        onValueChange = { pickerSaturation = it },
                        valueRange = 0f..1f
                    )

                    // Brightness slider
                    Text("Brightness", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = pickerValue,
                        onValueChange = { pickerValue = it },
                        valueRange = 0f..1f
                    )

                    // Alpha slider
                    Text("Opacity", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = pickerAlpha,
                        onValueChange = { pickerAlpha = it },
                        valueRange = 0f..1f
                    )

                    // Quick color presets
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Quick Colors", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val presets = listOf(
                            Color.White, Color.Black, Color.Red, Color.Green,
                            Color.Blue, Color.Yellow, Color.Cyan, Color.Magenta
                        )
                        presets.forEach { preset ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(preset)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    .clickable {
                                        val hsv = colorToHsv(preset)
                                        pickerHue = hsv[0]
                                        pickerSaturation = hsv[1]
                                        pickerValue = hsv[2]
                                        pickerAlpha = 1f
                                    }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editingColorKey?.let { key ->
                            saveColor(key, hsvToColor(pickerHue, pickerSaturation, pickerValue, pickerAlpha))
                        }
                        showColorPicker = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showColorPicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // Copy from theme dialog
    if (showCopyFromDialog) {
        AlertDialog(
            onDismissRequest = { showCopyFromDialog = false },
            title = { Text(stringResource(R.string.theme_copy_from)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    StatusBarTheme.ALL_THEMES.forEach { theme ->
                        val themeName = when (theme.id) {
                            "classic_dark" -> stringResource(R.string.theme_classic_dark)
                            "ocean_blue" -> stringResource(R.string.theme_ocean_blue)
                            "midnight_purple" -> stringResource(R.string.theme_midnight_purple)
                            "forest_green" -> stringResource(R.string.theme_forest_green)
                            "sunset_orange" -> stringResource(R.string.theme_sunset_orange)
                            "rose_gold" -> stringResource(R.string.theme_rose_gold)
                            "charcoal_gray" -> stringResource(R.string.theme_charcoal_gray)
                            "cyber_neon" -> stringResource(R.string.theme_cyber_neon)
                            else -> theme.id
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    SettingsManager.copyThemeToCustom(context, theme)
                                    colors = SettingsManager.getCustomThemeColors(context).mapValues { Color(it.value) }
                                    // Broadcast if custom theme is active
                                    if (SettingsManager.getStatusBarTheme(context) == StatusBarTheme.CUSTOM_THEME_ID) {
                                        context.sendBroadcast(
                                            android.content.Intent(it.neuralrad.coolwulf.inputmethod.PhysicalKeyboardInputMethodService.ACTION_THEME_CHANGED).apply {
                                                setPackage(context.packageName)
                                            }
                                        )
                                    }
                                    showCopyFromDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Color preview
                            Surface(
                                modifier = Modifier.size(24.dp),
                                shape = MaterialTheme.shapes.small,
                                color = Color(theme.backgroundColor)
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .size(12.dp),
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = Color(theme.accentColor)
                                ) {}
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = themeName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCopyFromDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ColorPickerRow(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            )
        }
    }
    HorizontalDivider()
}

// Helper for colorItems reference in dialog
private val colorItems = listOf(
    "backgroundColor" to R.string.theme_color_background,
    "textColor" to R.string.theme_color_text,
    "textColorSecondary" to R.string.theme_color_text_secondary,
    "accentColor" to R.string.theme_color_accent,
    "buttonBackgroundColor" to R.string.theme_color_button_bg,
    "buttonPressedColor" to R.string.theme_color_button_pressed,
    "candidateBackgroundColor" to R.string.theme_color_candidate_bg,
    "candidateBestBackgroundColor" to R.string.theme_color_candidate_best_bg,
    "candidateTextColor" to R.string.theme_color_candidate_text,
    "candidateBestTextColor" to R.string.theme_color_candidate_best_text,
    "iconColor" to R.string.theme_color_icon,
    "iconInactiveColor" to R.string.theme_color_icon_inactive,
    "ledActiveColor" to R.string.theme_color_led_active,
    "ledLockedColor" to R.string.theme_color_led_locked,
    "ledInactiveColor" to R.string.theme_color_led_inactive
)
