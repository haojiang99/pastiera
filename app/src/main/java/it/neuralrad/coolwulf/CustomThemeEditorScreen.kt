package it.neuralrad.coolwulf

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ContentPaste
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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom theme editor screen with color pickers.
 * @param slot The custom theme slot (1, 2, or 3)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomThemeEditorScreen(
    slot: Int = 1,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Load current custom theme colors for the specific slot
    var colors by remember {
        mutableStateOf(SettingsManager.getCustomThemeColorsForSlot(context, slot).mapValues { Color(it.value) })
    }

    // Theme name for this slot
    var themeName by remember {
        mutableStateOf(SettingsManager.getCustomThemeSlotName(context, slot))
    }
    var showRenameDialog by remember { mutableStateOf(false) }

    // Theme type (light/dark) for day/night switching
    var themeType by remember {
        mutableStateOf(SettingsManager.getCustomThemeSlotType(context, slot))
    }

    // Get the theme ID for this slot
    val slotThemeId = when (slot) {
        1 -> StatusBarTheme.CUSTOM_THEME_1_ID
        2 -> StatusBarTheme.CUSTOM_THEME_2_ID
        3 -> StatusBarTheme.CUSTOM_THEME_3_ID
        else -> StatusBarTheme.CUSTOM_THEME_1_ID
    }

    // Currently selected color for editing
    var editingColorKey by remember { mutableStateOf<String?>(null) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showCopyFromDialog by remember { mutableStateOf(false) }
    var showExportImportMenu by remember { mutableStateOf(false) }

    // Export theme to JSON
    fun exportThemeToJson(): String {
        val json = JSONObject()
        json.put("theme_name", if (themeName.isNotEmpty()) themeName else "Custom Theme $slot")
        json.put("theme_type", themeType)
        json.put("slot", slot)
        json.put("version", 1)
        json.put("exported_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))

        val colorsJson = JSONObject()
        colors.forEach { (key, color) ->
            colorsJson.put(key, String.format("#%08X", color.toArgb()))
        }
        json.put("colors", colorsJson)

        return json.toString(2)
    }

    // Import theme from JSON
    fun importThemeFromJson(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            val colorsJson = json.getJSONObject("colors")

            // Import theme name if present
            if (json.has("theme_name")) {
                val importedName = json.getString("theme_name")
                themeName = importedName
                SettingsManager.setCustomThemeSlotName(context, slot, importedName)
            }

            // Import theme type if present
            if (json.has("theme_type")) {
                val importedType = json.getString("theme_type")
                if (importedType == "light" || importedType == "dark") {
                    themeType = importedType
                    SettingsManager.setCustomThemeSlotType(context, slot, importedType)
                }
            }

            val newColors = mutableMapOf<String, Color>()
            colorsJson.keys().forEach { key ->
                val colorStr = colorsJson.getString(key)
                val colorInt = android.graphics.Color.parseColor(colorStr)
                newColors[key] = Color(colorInt)
                SettingsManager.setCustomThemeColorForSlot(context, slot, key, colorInt)
            }

            colors = newColors

            // Broadcast theme change if this slot's theme is currently active
            if (SettingsManager.getStatusBarTheme(context) == slotThemeId) {
                context.sendBroadcast(
                    Intent(it.neuralrad.coolwulf.inputmethod.PhysicalKeyboardInputMethodService.ACTION_THEME_CHANGED).apply {
                        setPackage(context.packageName)
                    }
                )
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // File picker for export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(exportThemeToJson().toByteArray())
                }
                Toast.makeText(context, context.getString(R.string.theme_export_success), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.theme_export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // File picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    val jsonString = inputStream.bufferedReader().readText()
                    if (importThemeFromJson(jsonString)) {
                        Toast.makeText(context, context.getString(R.string.theme_import_success), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.theme_import_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.theme_import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Color picker state
    var pickerHue by remember { mutableFloatStateOf(0f) }
    var pickerSaturation by remember { mutableFloatStateOf(1f) }
    var pickerValue by remember { mutableFloatStateOf(1f) }
    var pickerAlpha by remember { mutableFloatStateOf(1f) }

    fun saveColor(key: String, color: Color) {
        SettingsManager.setCustomThemeColorForSlot(context, slot, key, color.toArgb())
        // Create a new map to trigger recomposition
        colors = HashMap(colors).apply { this[key] = color }
        // Broadcast theme change if this slot's theme is currently active
        if (SettingsManager.getStatusBarTheme(context) == slotThemeId) {
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

    // Default slot name
    val defaultSlotName = when (slot) {
        1 -> stringResource(R.string.theme_custom_slot_1)
        2 -> stringResource(R.string.theme_custom_slot_2)
        3 -> stringResource(R.string.theme_custom_slot_3)
        else -> "Custom $slot"
    }
    val displayTitle = if (themeName.isNotEmpty()) themeName else defaultSlotName

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(displayTitle)
                        Text(
                            text = stringResource(R.string.theme_custom_editor_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Rename button
                    IconButton(onClick = { showRenameDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.theme_rename))
                    }
                    // Import button
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = stringResource(R.string.theme_import))
                    }
                    // Export button
                    IconButton(onClick = {
                        val filename = "coolwulf_theme_${slot}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
                        exportLauncher.launch(filename)
                    }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.theme_export))
                    }
                    // Copy from theme button
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
            // Preview section - Enhanced to show more elements
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
                    // Header row with title and icons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.theme_preview),
                            color = colors["textColor"] ?: Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Active icon
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = null,
                                tint = colors["iconColor"] ?: Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            // Inactive icon
                            Icon(
                                imageVector = Icons.Filled.ContentPaste,
                                contentDescription = null,
                                tint = colors["iconInactiveColor"] ?: Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Secondary text
                    Text(
                        text = "Secondary text example",
                        color = colors["textColorSecondary"] ?: Color.Gray,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Candidate buttons row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Best candidate (highlighted)
                        Box(
                            modifier = Modifier
                                .background(
                                    colors["candidateBestBackgroundColor"] ?: Color.DarkGray,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "1.你好",
                                color = colors["candidateBestTextColor"] ?: Color.Yellow,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // Normal candidate
                        Box(
                            modifier = Modifier
                                .background(
                                    colors["candidateBackgroundColor"] ?: Color.DarkGray,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "2.您好",
                                color = colors["candidateTextColor"] ?: Color.White,
                                fontSize = 13.sp
                            )
                        }
                        // Another normal candidate
                        Box(
                            modifier = Modifier
                                .background(
                                    colors["candidateBackgroundColor"] ?: Color.DarkGray,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "3.妳好",
                                color = colors["candidateTextColor"] ?: Color.White,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Button row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Normal button
                        Box(
                            modifier = Modifier
                                .background(
                                    colors["buttonBackgroundColor"] ?: Color.DarkGray,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "SYM",
                                color = colors["textColor"] ?: Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        // Pressed/active button
                        Box(
                            modifier = Modifier
                                .background(
                                    colors["buttonPressedColor"] ?: Color.Gray,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "拼",
                                color = colors["accentColor"] ?: Color.Cyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // Another button with accent
                        Box(
                            modifier = Modifier
                                .background(
                                    colors["buttonBackgroundColor"] ?: Color.DarkGray,
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    1.dp,
                                    colors["accentColor"] ?: Color.Cyan,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "简",
                                color = colors["accentColor"] ?: Color.Cyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // LED indicators row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LED:",
                            color = colors["textColorSecondary"] ?: Color.Gray,
                            fontSize = 10.sp
                        )
                        // Active LED (Shift active)
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(colors["ledActiveColor"] ?: Color.Blue)
                        )
                        // Locked LED (Caps lock)
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(colors["ledLockedColor"] ?: Color.Red)
                        )
                        // Inactive LEDs
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(colors["ledInactiveColor"] ?: Color.Gray)
                        )
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(colors["ledInactiveColor"] ?: Color.Gray)
                        )
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(colors["ledInactiveColor"] ?: Color.Gray)
                        )
                    }
                }
            }

            // Theme Type Selector (Light/Dark)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.theme_type_selector_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.theme_type_selector_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Dark button
                        FilterChip(
                            selected = themeType == "dark",
                            onClick = {
                                themeType = "dark"
                                SettingsManager.setCustomThemeSlotType(context, slot, "dark")
                            },
                            label = { Text(stringResource(R.string.theme_type_dark)) },
                            modifier = Modifier.weight(1f)
                        )
                        // Light button
                        FilterChip(
                            selected = themeType == "light",
                            onClick = {
                                themeType = "light"
                                SettingsManager.setCustomThemeSlotType(context, slot, "light")
                            },
                            label = { Text(stringResource(R.string.theme_type_light)) },
                            modifier = Modifier.weight(1f)
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
                                    SettingsManager.copyThemeToCustomSlot(context, theme, slot)
                                    colors = SettingsManager.getCustomThemeColorsForSlot(context, slot).mapValues { Color(it.value) }
                                    // Broadcast if this slot's theme is active
                                    if (SettingsManager.getStatusBarTheme(context) == slotThemeId) {
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

    // Rename theme dialog
    if (showRenameDialog) {
        var newName by remember { mutableStateOf(themeName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.theme_rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.theme_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        themeName = newName
                        SettingsManager.setCustomThemeSlotName(context, slot, newName)
                        showRenameDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
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
