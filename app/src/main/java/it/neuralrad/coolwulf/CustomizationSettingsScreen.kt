package it.neuralrad.coolwulf

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import it.neuralrad.coolwulf.inputmethod.ui.StatusBarTheme
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import it.neuralrad.coolwulf.R

/**
 * Customization settings screen.
 */
@Composable
fun CustomizationSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var navigationDirection by remember { mutableStateOf(CustomizationNavigationDirection.Push) }
    val navigationStack = remember {
        mutableStateListOf<CustomizationDestination>(CustomizationDestination.Main)
    }
    val currentDestination by remember {
        derivedStateOf { navigationStack.last() }
    }
    
    fun navigateTo(destination: CustomizationDestination) {
        navigationDirection = CustomizationNavigationDirection.Push
        navigationStack.add(destination)
    }
    
    fun navigateBack() {
        if (navigationStack.size > 1) {
            navigationDirection = CustomizationNavigationDirection.Pop
            navigationStack.removeAt(navigationStack.lastIndex)
        } else {
            onBack()
        }
    }
    
    BackHandler { navigateBack() }
    
    AnimatedContent(
        targetState = currentDestination,
        transitionSpec = {
            if (navigationDirection == CustomizationNavigationDirection.Push) {
                // Forward navigation: new screen enters from right, old screen exits to left
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(250)
                ) togetherWith slideOutHorizontally(
                    targetOffsetX = { fullWidth -> -fullWidth },
                    animationSpec = tween(250)
                )
            } else {
                // Back navigation: current screen exits to right, previous screen enters from left
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> -fullWidth },
                    animationSpec = tween(250)
                ) togetherWith slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(250)
                )
            }
        },
        label = "customization_navigation",
        contentKey = { it::class }
    ) { destination ->
        when (destination) {
            CustomizationDestination.Main -> {
                Scaffold(
                    topBar = {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.statusBars),
                            tonalElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { navigateBack() }) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.settings_back_content_description)
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.settings_category_customization),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                ) { paddingValues ->
                    Column(
                        modifier = modifier
                            .fillMaxWidth()
                            .padding(paddingValues)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // SYM Customization
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clickable {
                                    val intent = Intent(context, SymCustomizationActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    }
                                    context.startActivity(intent)
                                    (context as? Activity)?.overridePendingTransition(
                                        R.anim.slide_in_from_right,
                                        0
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Keyboard,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.sym_customization_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    
                        // Nav Mode Settings
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clickable { navigateTo(CustomizationDestination.NavMode) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Keyboard,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.nav_mode_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_nav_mode_configure),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Custom Dictionary
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clickable { navigateTo(CustomizationDestination.CustomDictionary) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.TextFields,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.custom_dictionary_settings_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.custom_dictionary_settings_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Day/Night Theme Toggle
                        var dayNightEnabled by remember {
                            mutableStateOf(SettingsManager.isDayNightThemeEnabled(context))
                        }
                        var dayTheme by remember {
                            mutableStateOf(SettingsManager.getDayTheme(context))
                        }
                        var nightTheme by remember {
                            mutableStateOf(SettingsManager.getNightTheme(context))
                        }
                        var showDayThemeDialog by remember { mutableStateOf(false) }
                        var showNightThemeDialog by remember { mutableStateOf(false) }

                        // Status Bar Theme Selector
                        var statusBarTheme by remember {
                            mutableStateOf(SettingsManager.getStatusBarTheme(context))
                        }
                        var showThemeDialog by remember { mutableStateOf(false) }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clickable { showThemeDialog = true }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Palette,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.status_bar_theme_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = when (statusBarTheme) {
                                            "classic_dark" -> stringResource(R.string.theme_classic_dark)
                                            "ocean_blue" -> stringResource(R.string.theme_ocean_blue)
                                            "midnight_purple" -> stringResource(R.string.theme_midnight_purple)
                                            "forest_green" -> stringResource(R.string.theme_forest_green)
                                            "sunset_orange" -> stringResource(R.string.theme_sunset_orange)
                                            "rose_gold" -> stringResource(R.string.theme_rose_gold)
                                            "charcoal_gray" -> stringResource(R.string.theme_charcoal_gray)
                                            "cyber_neon" -> stringResource(R.string.theme_cyber_neon)
                                            StatusBarTheme.CUSTOM_THEME_ID -> stringResource(R.string.theme_custom)
                                            StatusBarTheme.CUSTOM_THEME_1_ID -> {
                                                val name = SettingsManager.getCustomThemeSlotName(context, 1)
                                                if (name.isNotEmpty()) name else stringResource(R.string.theme_custom_slot_1)
                                            }
                                            StatusBarTheme.CUSTOM_THEME_2_ID -> {
                                                val name = SettingsManager.getCustomThemeSlotName(context, 2)
                                                if (name.isNotEmpty()) name else stringResource(R.string.theme_custom_slot_2)
                                            }
                                            StatusBarTheme.CUSTOM_THEME_3_ID -> {
                                                val name = SettingsManager.getCustomThemeSlotName(context, 3)
                                                if (name.isNotEmpty()) name else stringResource(R.string.theme_custom_slot_3)
                                            }
                                            else -> stringResource(R.string.theme_classic_dark)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Theme Selection Dialog
                        if (showThemeDialog) {
                            AlertDialog(
                                onDismissRequest = { showThemeDialog = false },
                                title = { Text(stringResource(R.string.status_bar_theme_title)) },
                                text = {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 400.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        // Built-in themes
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
                                                        statusBarTheme = theme.id
                                                        SettingsManager.setStatusBarTheme(context, theme.id)
                                                        // Broadcast theme change to refresh IME UI
                                                        context.sendBroadcast(
                                                            android.content.Intent(it.neuralrad.coolwulf.inputmethod.PhysicalKeyboardInputMethodService.ACTION_THEME_CHANGED).apply {
                                                                setPackage(context.packageName)
                                                            }
                                                        )
                                                        showThemeDialog = false
                                                    }
                                                    .padding(vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Color preview circle
                                                Surface(
                                                    modifier = Modifier.size(24.dp),
                                                    shape = MaterialTheme.shapes.small,
                                                    color = androidx.compose.ui.graphics.Color(theme.backgroundColor)
                                                ) {
                                                    // Show accent color as a smaller circle inside
                                                    Surface(
                                                        modifier = Modifier
                                                            .padding(6.dp)
                                                            .size(12.dp),
                                                        shape = MaterialTheme.shapes.extraSmall,
                                                        color = androidx.compose.ui.graphics.Color(theme.accentColor)
                                                    ) {}
                                                }
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Text(
                                                    text = themeName,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (statusBarTheme == theme.id) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }

                                        // Divider before custom theme slots
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                        // Custom Themes label
                                        Text(
                                            text = stringResource(R.string.theme_custom_slots_title),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )

                                        // Custom theme slots (1, 2, 3)
                                        for (slot in 1..3) {
                                            val slotThemeId = when (slot) {
                                                1 -> StatusBarTheme.CUSTOM_THEME_1_ID
                                                2 -> StatusBarTheme.CUSTOM_THEME_2_ID
                                                3 -> StatusBarTheme.CUSTOM_THEME_3_ID
                                                else -> StatusBarTheme.CUSTOM_THEME_1_ID
                                            }
                                            val customSlotTheme = StatusBarTheme.getCustomThemeForSlot(context, slot)
                                            val slotName = SettingsManager.getCustomThemeSlotName(context, slot)
                                            val displayName = if (slotName.isNotEmpty()) slotName else when (slot) {
                                                1 -> stringResource(R.string.theme_custom_slot_1)
                                                2 -> stringResource(R.string.theme_custom_slot_2)
                                                3 -> stringResource(R.string.theme_custom_slot_3)
                                                else -> "Custom $slot"
                                            }

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        statusBarTheme = slotThemeId
                                                        SettingsManager.setStatusBarTheme(context, slotThemeId)
                                                        context.sendBroadcast(
                                                            android.content.Intent(it.neuralrad.coolwulf.inputmethod.PhysicalKeyboardInputMethodService.ACTION_THEME_CHANGED).apply {
                                                                setPackage(context.packageName)
                                                            }
                                                        )
                                                        showThemeDialog = false
                                                    }
                                                    .padding(vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Color preview circle for custom theme slot
                                                Surface(
                                                    modifier = Modifier.size(24.dp),
                                                    shape = MaterialTheme.shapes.small,
                                                    color = androidx.compose.ui.graphics.Color(customSlotTheme.backgroundColor)
                                                ) {
                                                    Surface(
                                                        modifier = Modifier
                                                            .padding(6.dp)
                                                            .size(12.dp),
                                                        shape = MaterialTheme.shapes.extraSmall,
                                                        color = androidx.compose.ui.graphics.Color(customSlotTheme.accentColor)
                                                    ) {}
                                                }
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Text(
                                                    text = displayName,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                // Edit button
                                                IconButton(
                                                    onClick = {
                                                        showThemeDialog = false
                                                        navigateTo(CustomizationDestination.CustomThemeEditor(slot))
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Edit,
                                                        contentDescription = stringResource(R.string.theme_custom_edit),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                                if (statusBarTheme == slotThemeId) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { showThemeDialog = false }) {
                                        Text(stringResource(android.R.string.cancel))
                                    }
                                }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // Day/Night Theme Auto-Switch Toggle
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .clickable {
                                    dayNightEnabled = !dayNightEnabled
                                    SettingsManager.setDayNightThemeEnabled(context, dayNightEnabled)
                                    // Broadcast theme change to refresh IME UI
                                    context.sendBroadcast(
                                        android.content.Intent(it.neuralrad.coolwulf.inputmethod.PhysicalKeyboardInputMethodService.ACTION_THEME_CHANGED).apply {
                                            setPackage(context.packageName)
                                        }
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Palette,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.day_night_theme_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.day_night_theme_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                                Switch(
                                    checked = dayNightEnabled,
                                    onCheckedChange = { checked ->
                                        dayNightEnabled = checked
                                        SettingsManager.setDayNightThemeEnabled(context, checked)
                                        context.sendBroadcast(
                                            android.content.Intent(it.neuralrad.coolwulf.inputmethod.PhysicalKeyboardInputMethodService.ACTION_THEME_CHANGED).apply {
                                                setPackage(context.packageName)
                                            }
                                        )
                                    }
                                )
                            }
                        }

                        // Day Theme Selector (only show when day/night is enabled)
                        if (dayNightEnabled) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .clickable { showDayThemeDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Spacer(modifier = Modifier.width(36.dp)) // Indent
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.day_theme_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = getThemeDisplayName(context, dayTheme),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Filled.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Night Theme Selector
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .clickable { showNightThemeDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Spacer(modifier = Modifier.width(36.dp)) // Indent
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.night_theme_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = getThemeDisplayName(context, nightTheme),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Filled.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Day Theme Selection Dialog
                        if (showDayThemeDialog) {
                            ThemeSelectionDialog(
                                title = stringResource(R.string.day_theme_title),
                                currentThemeId = dayTheme,
                                onThemeSelected = { themeId ->
                                    dayTheme = themeId
                                    SettingsManager.setDayTheme(context, themeId)
                                    context.sendBroadcast(
                                        android.content.Intent(it.neuralrad.coolwulf.inputmethod.PhysicalKeyboardInputMethodService.ACTION_THEME_CHANGED).apply {
                                            setPackage(context.packageName)
                                        }
                                    )
                                    showDayThemeDialog = false
                                },
                                onDismiss = { showDayThemeDialog = false }
                            )
                        }

                        // Night Theme Selection Dialog
                        if (showNightThemeDialog) {
                            ThemeSelectionDialog(
                                title = stringResource(R.string.night_theme_title),
                                currentThemeId = nightTheme,
                                onThemeSelected = { themeId ->
                                    nightTheme = themeId
                                    SettingsManager.setNightTheme(context, themeId)
                                    context.sendBroadcast(
                                        android.content.Intent(it.neuralrad.coolwulf.inputmethod.PhysicalKeyboardInputMethodService.ACTION_THEME_CHANGED).apply {
                                            setPackage(context.packageName)
                                        }
                                    )
                                    showNightThemeDialog = false
                                },
                                onDismiss = { showNightThemeDialog = false }
                            )
                        }
                    }
                }
            }

            CustomizationDestination.NavMode -> {
                NavModeSettingsScreen(
                    modifier = modifier,
                    onBack = { navigateBack() }
                )
            }

            CustomizationDestination.CustomDictionary -> {
                CustomDictionarySettingsScreen(
                    modifier = modifier,
                    onBack = { navigateBack() }
                )
            }

            is CustomizationDestination.CustomThemeEditor -> {
                CustomThemeEditorScreen(
                    slot = destination.slot,
                    modifier = modifier,
                    onBack = { navigateBack() }
                )
            }
        }
    }
}

private sealed class CustomizationDestination {
    object Main : CustomizationDestination()
    object NavMode : CustomizationDestination()
    object CustomDictionary : CustomizationDestination()
    data class CustomThemeEditor(val slot: Int = 1) : CustomizationDestination()
}

private enum class CustomizationNavigationDirection {
    Push,
    Pop
}

/**
 * Gets the display name for a theme ID.
 */
@Composable
private fun getThemeDisplayName(context: android.content.Context, themeId: String): String {
    return when (themeId) {
        "classic_dark" -> stringResource(R.string.theme_classic_dark)
        "ocean_blue" -> stringResource(R.string.theme_ocean_blue)
        "midnight_purple" -> stringResource(R.string.theme_midnight_purple)
        "forest_green" -> stringResource(R.string.theme_forest_green)
        "sunset_orange" -> stringResource(R.string.theme_sunset_orange)
        "rose_gold" -> stringResource(R.string.theme_rose_gold)
        "charcoal_gray" -> stringResource(R.string.theme_charcoal_gray)
        "cyber_neon" -> stringResource(R.string.theme_cyber_neon)
        "silver_light" -> stringResource(R.string.theme_silver_light)
        "warm_cream" -> stringResource(R.string.theme_warm_cream)
        StatusBarTheme.CUSTOM_THEME_1_ID -> {
            val name = SettingsManager.getCustomThemeSlotName(context, 1)
            if (name.isNotEmpty()) name else stringResource(R.string.theme_custom_slot_1)
        }
        StatusBarTheme.CUSTOM_THEME_2_ID -> {
            val name = SettingsManager.getCustomThemeSlotName(context, 2)
            if (name.isNotEmpty()) name else stringResource(R.string.theme_custom_slot_2)
        }
        StatusBarTheme.CUSTOM_THEME_3_ID -> {
            val name = SettingsManager.getCustomThemeSlotName(context, 3)
            if (name.isNotEmpty()) name else stringResource(R.string.theme_custom_slot_3)
        }
        else -> themeId
    }
}

/**
 * Theme selection dialog for day/night themes.
 */
@Composable
private fun ThemeSelectionDialog(
    title: String,
    currentThemeId: String,
    onThemeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Built-in themes grouped by type
                Text(
                    text = stringResource(R.string.theme_type_dark),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                StatusBarTheme.getDarkThemes().forEach { theme ->
                    ThemeRowItem(
                        theme = theme,
                        displayName = getThemeDisplayName(context, theme.id),
                        isSelected = currentThemeId == theme.id,
                        onClick = { onThemeSelected(theme.id) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = stringResource(R.string.theme_type_light),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                StatusBarTheme.getLightThemes().forEach { theme ->
                    ThemeRowItem(
                        theme = theme,
                        displayName = getThemeDisplayName(context, theme.id),
                        isSelected = currentThemeId == theme.id,
                        onClick = { onThemeSelected(theme.id) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Custom themes
                Text(
                    text = stringResource(R.string.theme_custom_slots_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                for (slot in 1..3) {
                    val slotThemeId = when (slot) {
                        1 -> StatusBarTheme.CUSTOM_THEME_1_ID
                        2 -> StatusBarTheme.CUSTOM_THEME_2_ID
                        3 -> StatusBarTheme.CUSTOM_THEME_3_ID
                        else -> StatusBarTheme.CUSTOM_THEME_1_ID
                    }
                    val customTheme = StatusBarTheme.getCustomThemeForSlot(context, slot)
                    val slotName = SettingsManager.getCustomThemeSlotName(context, slot)
                    val themeType = SettingsManager.getCustomThemeSlotType(context, slot)
                    val typeLabel = if (themeType == "light")
                        stringResource(R.string.theme_type_light)
                    else
                        stringResource(R.string.theme_type_dark)
                    val displayName = if (slotName.isNotEmpty())
                        "$slotName ($typeLabel)"
                    else when (slot) {
                        1 -> "${stringResource(R.string.theme_custom_slot_1)} ($typeLabel)"
                        2 -> "${stringResource(R.string.theme_custom_slot_2)} ($typeLabel)"
                        3 -> "${stringResource(R.string.theme_custom_slot_3)} ($typeLabel)"
                        else -> "Custom $slot ($typeLabel)"
                    }

                    ThemeRowItem(
                        theme = customTheme,
                        displayName = displayName,
                        isSelected = currentThemeId == slotThemeId,
                        onClick = { onThemeSelected(slotThemeId) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/**
 * A single row item in the theme selection list.
 */
@Composable
private fun ThemeRowItem(
    theme: StatusBarTheme,
    displayName: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Color preview circle
        Surface(
            modifier = Modifier.size(24.dp),
            shape = MaterialTheme.shapes.small,
            color = androidx.compose.ui.graphics.Color(theme.backgroundColor)
        ) {
            Surface(
                modifier = Modifier
                    .padding(6.dp)
                    .size(12.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = androidx.compose.ui.graphics.Color(theme.accentColor)
            ) {}
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
