package it.neuralrad.coolwulf

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.neuralrad.coolwulf.R
import it.neuralrad.coolwulf.backup.BackupManager
import it.neuralrad.coolwulf.backup.RestoreManager
import androidx.compose.material3.Surface
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Advanced settings screen.
 */
@Composable
fun AdvancedSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    var launcherShortcutsEnabled by remember { 
        mutableStateOf(SettingsManager.getLauncherShortcutsEnabled(context))
    }
    var powerShortcutsEnabled by remember {
        mutableStateOf(SettingsManager.getPowerShortcutsEnabled(context))
    }
    var virtualKeyboardEnabled by remember {
        mutableStateOf(SettingsManager.isVirtualKeyboardEnabled(context))
    }
    var showVirtualKeyboardButton by remember {
        mutableStateOf(SettingsManager.getShowVirtualKeyboardButton(context))
    }
    var keyboardSound by remember {
        mutableStateOf(SettingsManager.isKeyboardSoundEnabled(context))
    }
    var keyboardSoundType by remember {
        mutableStateOf(SettingsManager.getKeyboardSoundType(context))
    }
    var keyboardSoundVolume by remember {
        mutableStateOf(SettingsManager.getKeyboardSoundVolume(context))
    }
    var showSoundToggleButton by remember {
        mutableStateOf(SettingsManager.isShowSoundToggleButton(context))
    }
    var customSoundPath by remember {
        mutableStateOf(SettingsManager.getCustomSoundPath(context))
    }
    // File picker launcher for custom sound
    val customSoundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // Copy the selected file to internal storage
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val path = SettingsManager.copyCustomSoundFile(context, inputStream)
                    if (path != null) {
                        customSoundPath = path
                        SettingsManager.setCustomSoundPath(context, path)
                        // Auto-select custom sound type
                        keyboardSoundType = "custom"
                        SettingsManager.setKeyboardSoundType(context, "custom")
                        scope.launch {
                            snackbarHostState.showSnackbar("Custom sound loaded successfully")
                        }
                    }
                }
            } catch (e: Exception) {
                scope.launch {
                    snackbarHostState.showSnackbar("Failed to load sound file")
                }
            }
        }
    }
    var virtualKeyboardVibration by remember {
        mutableStateOf(SettingsManager.isVirtualKeyboardVibrationEnabled(context))
    }
    var virtualKeyboardHeight by remember {
        mutableStateOf(SettingsManager.getVirtualKeyboardHeight(context))
    }
    // Store the actual value (3 to 25), but display it inverted in the slider (25 to 3)
    var swipeIncrementalThreshold by remember {
        mutableStateOf(SettingsManager.getSwipeIncrementalThreshold(context))
    }
    var deviceType by remember {
        mutableStateOf(SettingsManager.getDeviceType(context))
    }
    val availableDevices = remember { SettingsManager.getAvailableDeviceTypes() }
    var navigationDirection by remember { mutableStateOf(AdvancedNavigationDirection.Push) }
    val navigationStack = remember {
        mutableStateListOf<AdvancedDestination>(AdvancedDestination.Main)
    }
    val currentDestination by remember {
        derivedStateOf { navigationStack.last() }
    }
    
    fun navigateTo(destination: AdvancedDestination) {
        navigationDirection = AdvancedNavigationDirection.Push
        navigationStack.add(destination)
    }
    
    fun navigateBack() {
        if (navigationStack.size > 1) {
            navigationDirection = AdvancedNavigationDirection.Pop
            navigationStack.removeAt(navigationStack.lastIndex)
        } else {
            onBack()
        }
    }
    
    BackHandler { navigateBack() }
    
    fun defaultBackupName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
        return "coolwulf-backup-${formatter.format(Date())}.zip"
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = BackupManager.createBackup(context, uri)
                val message = when (result) {
                    is it.neuralrad.coolwulf.backup.BackupResult.Success ->
                        context.getString(R.string.backup_completed)
                    is it.neuralrad.coolwulf.backup.BackupResult.Failure ->
                        context.getString(R.string.backup_failed, result.reason)
                }
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = RestoreManager.restore(context, uri)
                val message = when (result) {
                    is it.neuralrad.coolwulf.backup.RestoreResult.Success ->
                        context.getString(R.string.restore_completed)
                    is it.neuralrad.coolwulf.backup.RestoreResult.Failure ->
                        context.getString(R.string.restore_failed, result.reason)
                }
                snackbarHostState.showSnackbar(message)
            }
        }
    }
    
    AnimatedContent(
        targetState = currentDestination,
        transitionSpec = {
            if (navigationDirection == AdvancedNavigationDirection.Push) {
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
        label = "advanced_navigation",
        contentKey = { it::class }
    ) { destination ->
        when (destination) {
            AdvancedDestination.Main -> {
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
                                    text = stringResource(R.string.settings_category_advanced),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { paddingValues ->
                    Column(
                        modifier = modifier
                            .fillMaxWidth()
                            .padding(paddingValues)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Device Type Selection
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PhoneAndroid,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.device_type_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = availableDevices.find { it.first == deviceType }?.second ?: deviceType,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    availableDevices.forEach { (type, name) ->
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = if (deviceType == type)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else
                                                MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier
                                                .padding(vertical = 4.dp)
                                                .clickable {
                                                    deviceType = type
                                                    SettingsManager.setDeviceType(context, type)
                                                }
                                        ) {
                                            Text(
                                                text = name,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                color = if (deviceType == type)
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Launcher Shortcuts Enabled Toggle
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
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
                                        text = stringResource(R.string.launcher_shortcuts_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.launcher_shortcuts_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                Switch(
                                    checked = launcherShortcutsEnabled,
                                    onCheckedChange = { enabled ->
                                        launcherShortcutsEnabled = enabled
                                        SettingsManager.setLauncherShortcutsEnabled(context, enabled)
                                    }
                                )
                            }
                        }
                    
                        // Power Shortcuts Toggle
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Bolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.power_shortcuts_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.power_shortcuts_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                                Switch(
                                    checked = powerShortcutsEnabled,
                                    onCheckedChange = { enabled ->
                                        powerShortcutsEnabled = enabled
                                        SettingsManager.setPowerShortcutsEnabled(context, enabled)
                                    }
                                )
                            }
                        }

                        // Keyboard Sound (applies to both physical and virtual keyboards)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VolumeUp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.keyboard_sound_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.keyboard_sound_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                                Switch(
                                    checked = keyboardSound,
                                    onCheckedChange = { enabled ->
                                        keyboardSound = enabled
                                        SettingsManager.setKeyboardSoundEnabled(context, enabled)
                                    }
                                )
                            }
                        }

                        // Sound Type Selector (only visible when keyboard sound is enabled)
                        if (keyboardSound) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .padding(start = 32.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.VolumeUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.keyboard_sound_type_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(
                                            "mechanical" to R.string.keyboard_sound_type_mechanical,
                                            "bucklespring" to R.string.keyboard_sound_type_bucklespring,
                                            "video" to R.string.keyboard_sound_type_video,
                                            "mario" to R.string.keyboard_sound_type_mario,
                                            "piano" to R.string.keyboard_sound_type_piano,
                                            "custom" to R.string.keyboard_sound_type_custom
                                        ).forEach { (type, nameRes) ->
                                            Surface(
                                                shape = MaterialTheme.shapes.small,
                                                color = if (keyboardSoundType == type)
                                                    MaterialTheme.colorScheme.primaryContainer
                                                else
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                modifier = Modifier
                                                    .padding(vertical = 4.dp)
                                                    .clickable {
                                                        if (type == "custom" && customSoundPath == null) {
                                                            // Launch file picker for custom sound
                                                            customSoundPicker.launch("audio/*")
                                                        } else {
                                                            keyboardSoundType = type
                                                            SettingsManager.setKeyboardSoundType(context, type)
                                                        }
                                                    }
                                            ) {
                                                Text(
                                                    text = stringResource(nameRes),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    color = if (keyboardSoundType == type)
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    else
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Custom Sound File Picker (shown when custom type is selected or available)
                            if (keyboardSoundType == "custom" || customSoundPath != null) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 32.dp, top = 8.dp, bottom = 8.dp)
                                        .clickable {
                                            customSoundPicker.launch("audio/*")
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.VolumeUp,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.keyboard_sound_custom_select),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = if (customSoundPath != null) {
                                                    val fileName = customSoundPath!!.substringAfterLast("/")
                                                    stringResource(R.string.keyboard_sound_custom_current, fileName)
                                                } else {
                                                    stringResource(R.string.keyboard_sound_custom_select_file)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            // Sound Volume Slider
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 32.dp, top = 8.dp, bottom = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.VolumeUp,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.keyboard_sound_volume_title),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = stringResource(R.string.keyboard_sound_volume_description, keyboardSoundVolume),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Slider(
                                        value = keyboardSoundVolume.toFloat(),
                                        onValueChange = { newValue ->
                                            keyboardSoundVolume = newValue.toInt()
                                            SettingsManager.setKeyboardSoundVolume(context, newValue.toInt())
                                        },
                                        valueRange = 0f..100f,
                                        steps = 9,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                            // Show Sound Toggle Button in status bar
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .padding(start = 32.dp) // Indent to show it's a sub-setting
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.VolumeUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.show_sound_toggle_button_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = stringResource(R.string.show_sound_toggle_button_description),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }
                                    Switch(
                                        checked = showSoundToggleButton,
                                        onCheckedChange = { enabled ->
                                            showSoundToggleButton = enabled
                                            SettingsManager.setShowSoundToggleButton(context, enabled)
                                        }
                                    )
                                }
                            }
                        }

                        // Virtual Keyboard Toggle
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.TouchApp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.virtual_keyboard_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.virtual_keyboard_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                                Switch(
                                    checked = virtualKeyboardEnabled,
                                    onCheckedChange = { enabled ->
                                        virtualKeyboardEnabled = enabled
                                        SettingsManager.setVirtualKeyboardEnabled(context, enabled)
                                    }
                                )
                            }
                        }

                        // Virtual Keyboard Settings Section (only visible when virtual keyboard is enabled)
                        if (virtualKeyboardEnabled) {
                            // Show Virtual Keyboard Button
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .padding(start = 32.dp) // Indent to show it's a sub-setting
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.TouchApp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.show_virtual_keyboard_button_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = stringResource(R.string.show_virtual_keyboard_button_description),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }
                                    Switch(
                                        checked = showVirtualKeyboardButton,
                                        onCheckedChange = { enabled ->
                                            showVirtualKeyboardButton = enabled
                                            SettingsManager.setShowVirtualKeyboardButton(context, enabled)
                                        }
                                    )
                                }
                            }

                            // Virtual Keyboard Vibration
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .padding(start = 32.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Vibration,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.virtual_keyboard_vibration_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = stringResource(R.string.virtual_keyboard_vibration_description),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }
                                    Switch(
                                        checked = virtualKeyboardVibration,
                                        onCheckedChange = { enabled ->
                                            virtualKeyboardVibration = enabled
                                            SettingsManager.setVirtualKeyboardVibrationEnabled(context, enabled)
                                        }
                                    )
                                }
                            }

                            // Virtual Keyboard Height slider
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 32.dp, top = 8.dp, bottom = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.TouchApp,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.virtual_keyboard_height_title),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = stringResource(R.string.virtual_keyboard_height_description, virtualKeyboardHeight),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Slider(
                                        value = virtualKeyboardHeight.toFloat(),
                                        onValueChange = { newValue ->
                                            virtualKeyboardHeight = newValue.toInt()
                                            SettingsManager.setVirtualKeyboardHeight(context, newValue.toInt())
                                        },
                                        valueRange = SettingsManager.getMinVirtualKeyboardHeight().toFloat()..SettingsManager.getMaxVirtualKeyboardHeight().toFloat(),
                                        steps = SettingsManager.getMaxVirtualKeyboardHeight() - SettingsManager.getMinVirtualKeyboardHeight() - 1,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }

                        // Launcher Shortcuts Settings (always visible)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clickable { navigateTo(AdvancedDestination.LauncherShortcuts) }
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
                                        text = stringResource(R.string.launcher_shortcuts_configure),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.launcher_shortcuts_configure_description),
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
                    
                        // Backup
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clickable {
                                    backupLauncher.launch(defaultBackupName())
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
                                    imageVector = Icons.Filled.Backup,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.backup_now),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.backup_now_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    
                        // Restore
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clickable {
                                    restoreLauncher.launch(arrayOf("application/zip"))
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
                                    imageVector = Icons.Filled.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.restore_from_file),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.restore_from_file_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    
                        // Swipe Incremental Threshold
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.TouchApp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.swipe_incremental_threshold_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${String.format("%.1f", swipeIncrementalThreshold)} ${stringResource(R.string.dip_unit)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                Slider(
                                    value = SettingsManager.getMaxSwipeIncrementalThreshold() + 
                                        SettingsManager.getMinSwipeIncrementalThreshold() - swipeIncrementalThreshold,
                                    onValueChange = { newInvertedValue ->
                                        // Invert the slider value (25 to 3) back to stored value (3 to 25)
                                        val actualValue = SettingsManager.getMaxSwipeIncrementalThreshold() + 
                                            SettingsManager.getMinSwipeIncrementalThreshold() - newInvertedValue
                                        swipeIncrementalThreshold = actualValue
                                        SettingsManager.setSwipeIncrementalThreshold(context, actualValue)
                                    },
                                    valueRange = SettingsManager.getMinSwipeIncrementalThreshold()..SettingsManager.getMaxSwipeIncrementalThreshold(),
                                    steps = 16,
                                    modifier = Modifier
                                        .weight(1.5f)
                                        .height(24.dp)
                                )
                            }
                        }
                    
                        // Show Tutorial
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clickable {
                                    SettingsManager.resetTutorialCompleted(context)
                                    val intent = Intent(context, TutorialActivity::class.java)
                                    context.startActivity(intent)
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
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.tutorial_show),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = stringResource(R.string.tutorial_review_description),
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
                    }
                }
            }
            
            AdvancedDestination.LauncherShortcuts -> {
                LauncherShortcutsScreen(
                    modifier = modifier,
                    onBack = { navigateBack() }
                )
            }
            
        }
    }
}

private sealed class AdvancedDestination {
    object Main : AdvancedDestination()
    object LauncherShortcuts : AdvancedDestination()
}

private enum class AdvancedNavigationDirection {
    Push,
    Pop
}
