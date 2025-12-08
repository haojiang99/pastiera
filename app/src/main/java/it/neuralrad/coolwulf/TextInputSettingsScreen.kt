package it.neuralrad.coolwulf

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.BackHandler
import it.neuralrad.coolwulf.R

/**
 * Text Input settings screen.
 */
@Composable
fun TextInputSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onNavigateToCustomDictionary: () -> Unit = {},
    onNavigateToLearnedPhrases: () -> Unit = {}
) {
    val context = LocalContext.current
    
    var autoCapitalizeFirstLetter by remember {
        mutableStateOf(SettingsManager.getAutoCapitalizeFirstLetter(context))
    }

    var autoCapitalizeAfterPeriod by remember {
        mutableStateOf(SettingsManager.getAutoCapitalizeAfterPeriod(context))
    }

    var doubleSpaceToPeriod by remember {
        mutableStateOf(SettingsManager.getDoubleSpaceToPeriod(context))
    }

    var clearAltOnSpace by remember {
        mutableStateOf(SettingsManager.getClearAltOnSpace(context))
    }
    
    var swipeToDelete by remember {
        mutableStateOf(SettingsManager.getSwipeToDelete(context))
    }
    
    var autoShowKeyboard by remember {
        mutableStateOf(SettingsManager.getAutoShowKeyboard(context))
    }
    
    var altCtrlSpeechShortcut by remember {
        mutableStateOf(SettingsManager.getAltCtrlSpeechShortcutEnabled(context))
    }

    var showVoiceInputButton by remember {
        mutableStateOf(SettingsManager.getShowVoiceInputButton(context))
    }

    var offlineVoiceInput by remember {
        mutableStateOf(SettingsManager.isOfflineVoiceInput(context))
    }

    var clipboardHistoryEnabled by remember {
        mutableStateOf(SettingsManager.getClipboardHistoryEnabled(context))
    }

    var showClipboardButton by remember {
        mutableStateOf(SettingsManager.getShowClipboardButton(context))
    }

    var showVirtualKeyboardButton by remember {
        mutableStateOf(SettingsManager.getShowVirtualKeyboardButton(context))
    }

    var semiTransparentStatusBar by remember {
        mutableStateOf(SettingsManager.isSemiTransparentStatusBar(context))
    }

    var staticVariationBarMode by remember {
        mutableStateOf(SettingsManager.isStaticVariationBarModeEnabled(context))
    }

    var compactMode by remember {
        mutableStateOf(SettingsManager.getCompactModeEnabled(context))
    }

    var pinyinEnabled by remember {
        mutableStateOf(SettingsManager.getPinyinEnabled(context))
    }

    var wubiEnabled by remember {
        mutableStateOf(SettingsManager.getWubiEnabled(context))
    }

    var wubiWithPinyinEnabled by remember {
        mutableStateOf(SettingsManager.isWubiWithPinyinEnabled(context))
    }

    var wubiPhrasesFirst by remember {
        mutableStateOf(SettingsManager.isWubiPhrasesFirst(context))
    }

    var wubiAutoCommitSingle by remember {
        mutableStateOf(SettingsManager.isWubiAutoCommitSingle(context))
    }

    var wubiAutoCommitOverflow by remember {
        mutableStateOf(SettingsManager.isWubiAutoCommitOverflow(context))
    }

    var shuangpinEnabled by remember {
        mutableStateOf(SettingsManager.getShuangpinEnabled(context))
    }

    var zhenmaEnabled by remember {
        mutableStateOf(SettingsManager.getZhenmaEnabled(context))
    }

    var ziranmaEnabled by remember {
        mutableStateOf(SettingsManager.getZiranmaEnabled(context))
    }

    var juyingModeEnabled by remember {
        mutableStateOf(SettingsManager.getJuyingModeEnabled(context))
    }

    var juyingKeys by remember {
        mutableStateOf(SettingsManager.getJuyingKeys(context))
    }

    var memoryFunctionEnabled by remember {
        mutableStateOf(SettingsManager.getMemoryFunctionEnabled(context))
    }

    var autoPhraseMemoryEnabled by remember {
        mutableStateOf(SettingsManager.isAutoPhrasMemoryEnabled(context))
    }

    var partialPinyinMatchingEnabled by remember {
        mutableStateOf(SettingsManager.isPartialPinyinMatchingEnabled(context))
    }

    var shiftAltSwapped by remember {
        mutableStateOf(SettingsManager.getShiftAltSwapped(context))
    }

    var shiftEnterToggleInput by remember {
        mutableStateOf(SettingsManager.isShiftEnterToggleInputEnabled(context))
    }

    var showJuyingKeyDialog by remember { mutableStateOf(false) }
    var editingJuyingKeyIndex by remember { mutableStateOf(0) }

    var chineseNextWordPrediction by remember {
        mutableStateOf(SettingsManager.getChineseNextWordPredictionEnabled(context))
    }

    var fuzzyPinyinEnabled by remember {
        mutableStateOf(SettingsManager.getPinyinFuzzyEnabled(context))
    }

    var defaultInputMode by remember {
        mutableStateOf(SettingsManager.getDefaultInputMode(context))
    }

    var showDefaultModeDialog by remember { mutableStateOf(false) }

    var maxCandidatesNonJuying by remember {
        mutableStateOf(SettingsManager.getMaxCandidatesNonJuying(context))
    }

    var showMaxCandidatesDialog by remember { mutableStateOf(false) }

    var candidateFontSize by remember {
        mutableStateOf(SettingsManager.getCandidateFontSize(context))
    }

    var statusBarHeight by remember {
        mutableStateOf(SettingsManager.getStatusBarHeight(context))
    }

    // Handle system back button
    BackHandler { onBack() }
    
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
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back_content_description)
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_category_text_input),
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
            // Auto Capitalize
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.auto_capitalize_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = autoCapitalizeFirstLetter,
                        onCheckedChange = { enabled ->
                            autoCapitalizeFirstLetter = enabled
                            SettingsManager.setAutoCapitalizeFirstLetter(context, enabled)
                        }
                    )
                }
            }

            // Auto Capitalize After Period
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.auto_capitalize_after_period_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.auto_capitalize_after_period_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = autoCapitalizeAfterPeriod,
                        onCheckedChange = { enabled ->
                            autoCapitalizeAfterPeriod = enabled
                            SettingsManager.setAutoCapitalizeAfterPeriod(context, enabled)
                        }
                    )
                }
            }

            // Double Space to Period
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.double_space_to_period_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.double_space_to_period_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = doubleSpaceToPeriod,
                        onCheckedChange = { enabled ->
                            doubleSpaceToPeriod = enabled
                            SettingsManager.setDoubleSpaceToPeriod(context, enabled)
                        }
                    )
                }
            }

            // Clear Alt on Space
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.clear_alt_on_space_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.clear_alt_on_space_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = clearAltOnSpace,
                        onCheckedChange = { enabled ->
                            clearAltOnSpace = enabled
                            SettingsManager.setClearAltOnSpace(context, enabled)
                        }
                    )
                }
            }

            // Swipe to Delete
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.swipe_to_delete_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = swipeToDelete,
                        onCheckedChange = { enabled ->
                            swipeToDelete = enabled
                            SettingsManager.setSwipeToDelete(context, enabled)
                        }
                    )
                }
            }
        
            // Auto Show Keyboard
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.auto_show_keyboard_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = autoShowKeyboard,
                        onCheckedChange = { enabled ->
                            autoShowKeyboard = enabled
                            SettingsManager.setAutoShowKeyboard(context, enabled)
                        }
                    )
                }
            }

            // Alt+Ctrl Speech Recognition Shortcut
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.alt_ctrl_speech_shortcut_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.alt_ctrl_speech_shortcut_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = altCtrlSpeechShortcut,
                        onCheckedChange = { enabled ->
                            altCtrlSpeechShortcut = enabled
                            SettingsManager.setAltCtrlSpeechShortcutEnabled(context, enabled)
                        }
                    )
                }
            }

            // Show Voice Input Button
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.show_voice_input_button_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.show_voice_input_button_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = showVoiceInputButton,
                        onCheckedChange = { enabled ->
                            showVoiceInputButton = enabled
                            SettingsManager.setShowVoiceInputButton(context, enabled)
                        }
                    )
                }
            }

            // Offline Voice Input (Vosk)
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
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.offline_voice_input_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.offline_voice_input_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = offlineVoiceInput,
                        onCheckedChange = { enabled ->
                            offlineVoiceInput = enabled
                            SettingsManager.setOfflineVoiceInput(context, enabled)
                        }
                    )
                }
            }

            // Clipboard History Enabled
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.clipboard_history_enabled_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.clipboard_history_enabled_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = clipboardHistoryEnabled,
                        onCheckedChange = { enabled ->
                            clipboardHistoryEnabled = enabled
                            SettingsManager.setClipboardHistoryEnabled(context, enabled)
                        }
                    )
                }
            }

            // Show Clipboard Button
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.show_clipboard_button_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.show_clipboard_button_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = showClipboardButton,
                        onCheckedChange = { enabled ->
                            showClipboardButton = enabled
                            SettingsManager.setShowClipboardButton(context, enabled)
                        }
                    )
                }
            }

            // Show Virtual Keyboard Button
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
                        imageVector = Icons.Filled.TextFields,
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
                            maxLines = 1
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

            // Semi-Transparent Status Bar
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.semi_transparent_status_bar_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.semi_transparent_status_bar_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = semiTransparentStatusBar,
                        onCheckedChange = { enabled ->
                            semiTransparentStatusBar = enabled
                            SettingsManager.setSemiTransparentStatusBar(context, enabled)
                        }
                    )
                }
            }

            // Static Variation Bar Mode
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.static_variation_bar_mode_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.static_variation_bar_mode_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = staticVariationBarMode,
                        onCheckedChange = { enabled ->
                            staticVariationBarMode = enabled
                            SettingsManager.setStaticVariationBarModeEnabled(context, enabled)
                        }
                    )
                }
            }

            // Compact Mode
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
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
                            text = stringResource(R.string.compact_mode_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.compact_mode_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = compactMode,
                        onCheckedChange = { enabled ->
                            compactMode = enabled
                            SettingsManager.setCompactModeEnabled(context, enabled)
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Chinese Input Section Header
            Text(
                text = stringResource(R.string.chinese_input_section_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Pinyin Toggle
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chinese_input_pinyin),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.chinese_input_pinyin_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = pinyinEnabled,
                        onCheckedChange = { enabled ->
                            pinyinEnabled = enabled
                            SettingsManager.setPinyinEnabled(context, enabled)
                        }
                    )
                }
            }

            // Shuangpin Toggle
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chinese_input_shuangpin),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.chinese_input_shuangpin_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = shuangpinEnabled,
                        onCheckedChange = { enabled ->
                            shuangpinEnabled = enabled
                            SettingsManager.setShuangpinEnabled(context, enabled)
                        }
                    )
                }
            }

            // Ziranma Toggle
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chinese_input_ziranma),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.chinese_input_ziranma_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = ziranmaEnabled,
                        onCheckedChange = { enabled ->
                            ziranmaEnabled = enabled
                            SettingsManager.setZiranmaEnabled(context, enabled)
                        }
                    )
                }
            }

            // Wubi Toggle
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
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chinese_input_wubi),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.chinese_input_wubi_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = wubiEnabled,
                        onCheckedChange = { enabled ->
                            wubiEnabled = enabled
                            SettingsManager.setWubiEnabled(context, enabled)
                        }
                    )
                }
            }

            // Wubi with Pinyin Toggle (only visible when Wubi is enabled)
            if (wubiEnabled) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .padding(start = 40.dp) // Indent to show it's a sub-option
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
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Wubi + Pinyin",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = "Show Pinyin candidates after Wubi candidates",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Switch(
                            checked = wubiWithPinyinEnabled,
                            onCheckedChange = { enabled ->
                                wubiWithPinyinEnabled = enabled
                                SettingsManager.setWubiWithPinyinEnabled(context, enabled)
                            }
                        )
                    }
                }

                // Wubi Phrases First Toggle
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .padding(start = 40.dp)  // Indent as sub-option
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Phrases First",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = if (wubiPhrasesFirst) "Phrases shown before single characters" else "Single characters shown first",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Switch(
                            checked = wubiPhrasesFirst,
                            onCheckedChange = { enabled ->
                                wubiPhrasesFirst = enabled
                                SettingsManager.setWubiPhrasesFirst(context, enabled)
                            }
                        )
                    }
                }
            }

            // Wubi auto-commit single candidate toggle (only shown when Wubi is enabled)
            if (wubiEnabled) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TextFields,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.wubi_auto_commit_single_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.wubi_auto_commit_single_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Switch(
                            checked = wubiAutoCommitSingle,
                            onCheckedChange = { enabled ->
                                wubiAutoCommitSingle = enabled
                                SettingsManager.setWubiAutoCommitSingle(context, enabled)
                            }
                        )
                    }
                }
            }

            // Wubi auto-commit overflow (5th letter) toggle
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.wubi_auto_commit_overflow_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.wubi_auto_commit_overflow_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = wubiAutoCommitOverflow,
                        onCheckedChange = { enabled ->
                            wubiAutoCommitOverflow = enabled
                            SettingsManager.setWubiAutoCommitOverflow(context, enabled)
                        }
                    )
                }
            }

            // Zhenma toggle
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chinese_input_zhenma),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.chinese_input_zhenma_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = zhenmaEnabled,
                        onCheckedChange = { enabled ->
                            zhenmaEnabled = enabled
                            SettingsManager.setZhenmaEnabled(context, enabled)
                        }
                    )
                }
            }

            // Info text when multiple are enabled
            val enabledCount = listOf(pinyinEnabled, shuangpinEnabled, ziranmaEnabled, wubiEnabled, zhenmaEnabled).count { it }
            if (enabledCount >= 2) {
                Text(
                    text = stringResource(R.string.chinese_input_both_enabled_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Shift+Enter toggle input methods (show when any Chinese input is enabled)
            if (pinyinEnabled || shuangpinEnabled || ziranmaEnabled || wubiEnabled || zhenmaEnabled) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.shift_enter_toggle_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.shift_enter_toggle_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Switch(
                            checked = shiftEnterToggleInput,
                            onCheckedChange = { enabled ->
                                shiftEnterToggleInput = enabled
                                SettingsManager.setShiftEnterToggleInputEnabled(context, enabled)
                            }
                        )
                    }
                }
            }

            // Juying Mode Section (only show if any Chinese input is enabled)
            if (pinyinEnabled || shuangpinEnabled || ziranmaEnabled || wubiEnabled || zhenmaEnabled) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Section header
                Text(
                    text = stringResource(R.string.juying_mode_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // Juying mode toggle
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TextFields,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.juying_mode_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.juying_mode_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Switch(
                            checked = juyingModeEnabled,
                            onCheckedChange = { enabled ->
                                juyingModeEnabled = enabled
                                SettingsManager.setJuyingModeEnabled(context, enabled)
                            }
                        )
                    }
                }

                // Juying key configuration (only show when Juying mode is enabled)
                if (juyingModeEnabled) {
                    // Shift/Alt swap toggle (Titan 2 specific)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SwapHoriz,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.shift_alt_swap_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    text = stringResource(R.string.shift_alt_swap_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            Switch(
                                checked = shiftAltSwapped,
                                onCheckedChange = { swapped ->
                                    shiftAltSwapped = swapped
                                    SettingsManager.setShiftAltSwapped(context, swapped)
                                }
                            )
                        }
                    }

                    // Key 1-5 configuration rows
                    for (keyIndex in 1..5) {
                        val keyCode = juyingKeys.getOrElse(keyIndex - 1) { 0 }
                        val keyName = getKeyName(keyCode)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clickable {
                                    editingJuyingKeyIndex = keyIndex
                                    showJuyingKeyDialog = true
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = when (keyIndex) {
                                        1 -> stringResource(R.string.juying_key_1)
                                        2 -> stringResource(R.string.juying_key_2)
                                        3 -> stringResource(R.string.juying_key_3)
                                        4 -> stringResource(R.string.juying_key_4)
                                        5 -> stringResource(R.string.juying_key_5)
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = keyName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Reset keys button
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable {
                                SettingsManager.resetJuyingKeys(context)
                                juyingKeys = SettingsManager.getJuyingKeys(context)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.juying_reset_keys),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Max Candidates in Non-Juying Mode (only show when Juying mode is disabled)
                if (!juyingModeEnabled) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .clickable { showMaxCandidatesDialog = true }
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
                                    text = stringResource(R.string.max_candidates_non_juying_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    text = stringResource(R.string.max_candidates_non_juying_description, maxCandidatesNonJuying),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Candidate Font Size
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
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
                                text = stringResource(R.string.candidate_font_size_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.candidate_font_size_description, candidateFontSize),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Slider(
                        value = candidateFontSize.toFloat(),
                        onValueChange = { newValue ->
                            candidateFontSize = newValue.toInt()
                            SettingsManager.setCandidateFontSize(context, newValue.toInt())
                        },
                        valueRange = SettingsManager.getMinCandidateFontSize().toFloat()..SettingsManager.getMaxCandidateFontSize().toFloat(),
                        steps = SettingsManager.getMaxCandidateFontSize() - SettingsManager.getMinCandidateFontSize() - 1,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // Status Bar Height
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
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
                                text = stringResource(R.string.status_bar_height_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.status_bar_height_description, statusBarHeight),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Slider(
                        value = statusBarHeight.toFloat(),
                        onValueChange = { newValue ->
                            statusBarHeight = newValue.toInt()
                            SettingsManager.setStatusBarHeight(context, newValue.toInt())
                        },
                        valueRange = SettingsManager.getMinStatusBarHeight().toFloat()..SettingsManager.getMaxStatusBarHeight().toFloat(),
                        steps = SettingsManager.getMaxStatusBarHeight() - SettingsManager.getMinStatusBarHeight() - 1,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // Default Input Mode Selection
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clickable { showDefaultModeDialog = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.default_input_mode_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = when (defaultInputMode) {
                                "english" -> stringResource(R.string.input_mode_english)
                                "pinyin" -> stringResource(R.string.input_mode_pinyin)
                                "shuangpin" -> stringResource(R.string.input_mode_shuangpin)
                                "ziranma" -> stringResource(R.string.input_mode_ziranma)
                                "wubi" -> stringResource(R.string.input_mode_wubi)
                                "zhenma" -> stringResource(R.string.input_mode_zhenma)
                                else -> stringResource(R.string.input_mode_english)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Next Word Prediction Toggle (only show if Chinese input is enabled)
            if (pinyinEnabled || shuangpinEnabled || ziranmaEnabled || wubiEnabled || zhenmaEnabled) {
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
                            imageVector = Icons.Filled.TextFields,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.chinese_next_word_prediction_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.chinese_next_word_prediction_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Switch(
                            checked = chineseNextWordPrediction,
                            onCheckedChange = { enabled ->
                                chineseNextWordPrediction = enabled
                                SettingsManager.setChineseNextWordPredictionEnabled(context, enabled)
                            }
                        )
                    }
                }
            }

            // Memory Function Toggle (learn from user selections)
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
                        imageVector = Icons.Filled.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.memory_function_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.memory_function_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = memoryFunctionEnabled,
                        onCheckedChange = { enabled ->
                            memoryFunctionEnabled = enabled
                            SettingsManager.setMemoryFunctionEnabled(context, enabled)
                        }
                    )
                }
            }

            // Auto-Phrase Memory Toggle (only show if memory function is enabled)
            if (memoryFunctionEnabled) {
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
                            imageVector = Icons.Filled.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.auto_phrase_memory_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.auto_phrase_memory_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Switch(
                            checked = autoPhraseMemoryEnabled,
                            onCheckedChange = { enabled ->
                                autoPhraseMemoryEnabled = enabled
                                SettingsManager.setAutoPhraseMemoryEnabled(context, enabled)
                            }
                        )
                    }
                }

                // View Learned Phrases button (only if auto-phrase memory is enabled)
                if (autoPhraseMemoryEnabled) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(start = 40.dp),  // Indent as sub-option
                        onClick = onNavigateToLearnedPhrases
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = stringResource(R.string.learned_phrases_title),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Partial Pinyin Matching Toggle (sub-option under auto phrase memory)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(start = 40.dp)  // Indent as sub-option
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.partial_pinyin_matching_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    text = stringResource(R.string.partial_pinyin_matching_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            Switch(
                                checked = partialPinyinMatchingEnabled,
                                onCheckedChange = { enabled ->
                                    partialPinyinMatchingEnabled = enabled
                                    SettingsManager.setPartialPinyinMatchingEnabled(context, enabled)
                                }
                            )
                        }
                    }
                }
            }

            // Fuzzy Pinyin Toggle (only show if Pinyin is enabled)
            if (pinyinEnabled) {
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
                            imageVector = Icons.Filled.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.fuzzy_pinyin_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.fuzzy_pinyin_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Switch(
                            checked = fuzzyPinyinEnabled,
                            onCheckedChange = { enabled ->
                                fuzzyPinyinEnabled = enabled
                                SettingsManager.setPinyinFuzzyEnabled(context, enabled)
                            }
                        )
                    }
                }
            }

            // Custom Dictionary (only show if Chinese input is enabled)
            if (pinyinEnabled || shuangpinEnabled || ziranmaEnabled || wubiEnabled || zhenmaEnabled) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clickable { onNavigateToCustomDictionary() }
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
                    }
                }
            }
        }
    }

    // Default Input Mode Selection Dialog
    if (showDefaultModeDialog) {
        AlertDialog(
            onDismissRequest = { showDefaultModeDialog = false },
            title = { Text(stringResource(R.string.default_input_mode_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.default_input_mode_description),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // English option (always available)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                defaultInputMode = "english"
                                SettingsManager.setDefaultInputMode(context, "english")
                                SettingsManager.setLastInputMode(context, "english")
                                showDefaultModeDialog = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = defaultInputMode == "english",
                            onClick = {
                                defaultInputMode = "english"
                                SettingsManager.setDefaultInputMode(context, "english")
                                SettingsManager.setLastInputMode(context, "english")
                                showDefaultModeDialog = false
                            }
                        )
                        Text(
                            text = stringResource(R.string.input_mode_english),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    // Pinyin option (only if enabled)
                    if (pinyinEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    defaultInputMode = "pinyin"
                                    SettingsManager.setDefaultInputMode(context, "pinyin")
                                    SettingsManager.setLastInputMode(context, "pinyin")
                                    showDefaultModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = defaultInputMode == "pinyin",
                                onClick = {
                                    defaultInputMode = "pinyin"
                                    SettingsManager.setDefaultInputMode(context, "pinyin")
                                    SettingsManager.setLastInputMode(context, "pinyin")
                                    showDefaultModeDialog = false
                                }
                            )
                            Text(
                                text = stringResource(R.string.input_mode_pinyin),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    // Shuangpin option (only if enabled)
                    if (shuangpinEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    defaultInputMode = "shuangpin"
                                    SettingsManager.setDefaultInputMode(context, "shuangpin")
                                    SettingsManager.setLastInputMode(context, "shuangpin")
                                    showDefaultModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = defaultInputMode == "shuangpin",
                                onClick = {
                                    defaultInputMode = "shuangpin"
                                    SettingsManager.setDefaultInputMode(context, "shuangpin")
                                    SettingsManager.setLastInputMode(context, "shuangpin")
                                    showDefaultModeDialog = false
                                }
                            )
                            Text(
                                text = stringResource(R.string.input_mode_shuangpin),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    // Ziranma option (only if enabled)
                    if (ziranmaEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    defaultInputMode = "ziranma"
                                    SettingsManager.setDefaultInputMode(context, "ziranma")
                                    SettingsManager.setLastInputMode(context, "ziranma")
                                    showDefaultModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = defaultInputMode == "ziranma",
                                onClick = {
                                    defaultInputMode = "ziranma"
                                    SettingsManager.setDefaultInputMode(context, "ziranma")
                                    SettingsManager.setLastInputMode(context, "ziranma")
                                    showDefaultModeDialog = false
                                }
                            )
                            Text(
                                text = stringResource(R.string.input_mode_ziranma),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    // Wubi option (only if enabled)
                    if (wubiEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    defaultInputMode = "wubi"
                                    SettingsManager.setDefaultInputMode(context, "wubi")
                                    SettingsManager.setLastInputMode(context, "wubi")
                                    showDefaultModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = defaultInputMode == "wubi",
                                onClick = {
                                    defaultInputMode = "wubi"
                                    SettingsManager.setDefaultInputMode(context, "wubi")
                                    SettingsManager.setLastInputMode(context, "wubi")
                                    showDefaultModeDialog = false
                                }
                            )
                            Text(
                                text = stringResource(R.string.input_mode_wubi),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    // Zhenma option (only if enabled)
                    if (zhenmaEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    defaultInputMode = "zhenma"
                                    SettingsManager.setDefaultInputMode(context, "zhenma")
                                    SettingsManager.setLastInputMode(context, "zhenma")
                                    showDefaultModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = defaultInputMode == "zhenma",
                                onClick = {
                                    defaultInputMode = "zhenma"
                                    SettingsManager.setDefaultInputMode(context, "zhenma")
                                    SettingsManager.setLastInputMode(context, "zhenma")
                                    showDefaultModeDialog = false
                                }
                            )
                            Text(
                                text = stringResource(R.string.input_mode_zhenma),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDefaultModeDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // Juying key configuration dialog
    if (showJuyingKeyDialog) {
        JuyingKeyInputDialog(
            keyIndex = editingJuyingKeyIndex,
            onKeySelected = { keyCode ->
                SettingsManager.setJuyingKey(context, editingJuyingKeyIndex, keyCode)
                juyingKeys = SettingsManager.getJuyingKeys(context)
                showJuyingKeyDialog = false
            },
            onDismiss = { showJuyingKeyDialog = false }
        )
    }

    // Max Candidates in Non-Juying Mode dialog
    if (showMaxCandidatesDialog) {
        AlertDialog(
            onDismissRequest = { showMaxCandidatesDialog = false },
            title = { Text(stringResource(R.string.max_candidates_non_juying_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.max_candidates_non_juying_dialog_description),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Options: 5, 6, 7, 8, 9
                    val options = listOf(5, 6, 7, 8, 9)
                    options.forEach { count ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    maxCandidatesNonJuying = count
                                    SettingsManager.setMaxCandidatesNonJuying(context, count)
                                    showMaxCandidatesDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = maxCandidatesNonJuying == count,
                                onClick = {
                                    maxCandidatesNonJuying = count
                                    SettingsManager.setMaxCandidatesNonJuying(context, count)
                                    showMaxCandidatesDialog = false
                                }
                            )
                            Text(
                                text = "$count ${stringResource(R.string.max_candidates_candidates)}",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMaxCandidatesDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

/**
 * Dialog for capturing a key press to assign to a Juying key slot.
 */
@Composable
fun JuyingKeyInputDialog(
    keyIndex: Int,
    onKeySelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var capturedKeyName by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.juying_key_config_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            val keyCode = event.nativeKeyEvent.keyCode
                            // Accept modifier keys and common keys
                            if (keyCode != android.view.KeyEvent.KEYCODE_UNKNOWN) {
                                capturedKeyName = getKeyName(keyCode)
                                onKeySelected(keyCode)
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.juying_key_config_description),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = capturedKeyName ?: stringResource(R.string.juying_press_key),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (capturedKeyName != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

/**
 * Converts a key code to a human-readable name.
 */
private fun getKeyName(keyCode: Int): String {
    return when (keyCode) {
        android.view.KeyEvent.KEYCODE_SHIFT_LEFT, android.view.KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift"
        android.view.KeyEvent.KEYCODE_ALT_LEFT, android.view.KeyEvent.KEYCODE_ALT_RIGHT -> "Alt"
        android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.KEYCODE_CTRL_RIGHT -> "Ctrl"
        android.view.KeyEvent.KEYCODE_SYM -> "Sym"
        android.view.KeyEvent.KEYCODE_SPACE -> "Space"
        android.view.KeyEvent.KEYCODE_FUNCTION -> "Fn"
        android.view.KeyEvent.KEYCODE_ENTER -> "Enter"
        android.view.KeyEvent.KEYCODE_DEL -> "Backspace"
        android.view.KeyEvent.KEYCODE_TAB -> "Tab"
        android.view.KeyEvent.KEYCODE_ESCAPE -> "Esc"
        android.view.KeyEvent.KEYCODE_CAPS_LOCK -> "Caps Lock"
        android.view.KeyEvent.KEYCODE_META_LEFT, android.view.KeyEvent.KEYCODE_META_RIGHT -> "Meta"
        in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z -> {
            val char = ('A'.code + (keyCode - android.view.KeyEvent.KEYCODE_A)).toChar()
            char.toString()
        }
        in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> {
            val char = ('0'.code + (keyCode - android.view.KeyEvent.KEYCODE_0)).toChar()
            char.toString()
        }
        else -> "Key $keyCode"
    }
}
