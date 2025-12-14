package it.neuralrad.coolwulf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import it.neuralrad.coolwulf.inputmethod.ui.StatusBarTheme
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
import it.neuralrad.coolwulf.inputmethod.SherpaSpeechRecognizer
import java.io.File

/**
 * Text Input settings screen.
 */
@Composable
fun TextInputSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
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

    var holdSpaceForVoice by remember {
        mutableStateOf(SettingsManager.isHoldSpaceForVoice(context))
    }

    var voiceAddPunctuation by remember {
        mutableStateOf(SettingsManager.isVoiceAddPunctuation(context))
    }

    var offlineVoiceInput by remember {
        mutableStateOf(SettingsManager.isOfflineVoiceInput(context))
    }

    var sherpaModelPath by remember {
        mutableStateOf(SettingsManager.getSherpaModelPath(context))
    }

    var sherpaModelStatus by remember {
        mutableStateOf(SherpaSpeechRecognizer.getInstance(context).getModelStatus())
    }

    var holdSpaceDuration by remember {
        mutableStateOf(SettingsManager.getHoldSpaceDuration(context))
    }

    var clipboardHistoryEnabled by remember {
        mutableStateOf(SettingsManager.getClipboardHistoryEnabled(context))
    }

    var showClipboardButton by remember {
        mutableStateOf(SettingsManager.getShowClipboardButton(context))
    }

    var semiTransparentStatusBar by remember {
        mutableStateOf(SettingsManager.isSemiTransparentStatusBar(context))
    }

    var effect3DEnabled by remember {
        mutableStateOf(SettingsManager.is3DEffectEnabled(context))
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

    var t9PinyinEnabled by remember {
        mutableStateOf(SettingsManager.getT9PinyinEnabled(context))
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

    var wubiZKeyMode by remember {
        mutableStateOf(SettingsManager.getWubiZKeyMode(context))
    }
    var wubiZKeyModeExpanded by remember { mutableStateOf(false) }

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

    var touchpadPageEnabled by remember {
        mutableStateOf(SettingsManager.getTouchpadPageEnabled(context))
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

    var abbreviationInputEnabled by remember {
        mutableStateOf(SettingsManager.isAbbreviationInputEnabled(context))
    }

    var defaultInputMode by remember {
        mutableStateOf(SettingsManager.getDefaultInputMode(context))
    }

    var showDefaultModeDialog by remember { mutableStateOf(false) }

    var maxCandidatesNonJuying by remember {
        mutableStateOf(SettingsManager.getMaxCandidatesNonJuying(context))
    }

    var showMaxCandidatesDialog by remember { mutableStateOf(false) }

    var altDoubleClickDelay by remember {
        mutableStateOf(SettingsManager.getAltDoubleClickDelay(context).toFloat())
    }

    var candidateFontSize by remember {
        mutableStateOf(SettingsManager.getCandidateFontSize(context))
    }

    var statusBarHeight by remember {
        mutableStateOf(SettingsManager.getStatusBarHeight(context))
    }

    var showLedStatus by remember {
        mutableStateOf(SettingsManager.isShowLedStatus(context))
    }

    var showSymButton by remember {
        mutableStateOf(SettingsManager.isShowSymButton(context))
    }

    var traditionalChineseToggleEnabled by remember {
        mutableStateOf(SettingsManager.isTraditionalChineseToggleEnabled(context))
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Voice Input Section Header
            Text(
                text = stringResource(R.string.voice_input_section_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

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

            // Hold Space for Voice Input
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
                            text = stringResource(R.string.hold_space_for_voice_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.hold_space_for_voice_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = holdSpaceForVoice,
                        onCheckedChange = { enabled ->
                            holdSpaceForVoice = enabled
                            SettingsManager.setHoldSpaceForVoice(context, enabled)
                        }
                    )
                }
            }

            // Hold Space Duration (shown when Hold Space for Voice is enabled)
            if (holdSpaceForVoice) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.hold_space_duration_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.hold_space_duration_description, holdSpaceDuration.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Slider(
                            value = holdSpaceDuration.toFloat(),
                            onValueChange = { newValue ->
                                holdSpaceDuration = newValue.toLong()
                            },
                            onValueChangeFinished = {
                                SettingsManager.setHoldSpaceDuration(context, holdSpaceDuration)
                            },
                            valueRange = SettingsManager.getMinHoldSpaceDuration().toFloat()..SettingsManager.getMaxHoldSpaceDuration().toFloat(),
                            steps = 12,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // Voice Auto-Add Punctuation
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
                            text = stringResource(R.string.voice_add_punctuation_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.voice_add_punctuation_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = voiceAddPunctuation,
                        onCheckedChange = { enabled ->
                            voiceAddPunctuation = enabled
                            SettingsManager.setVoiceAddPunctuation(context, enabled)
                        }
                    )
                }
            }

            // Offline Voice Input (Sherpa-ONNX)
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

            // Voice Auto-Insert Toggle (shown when offline voice is enabled)
            if (offlineVoiceInput) {
                var voiceAutoInsert by remember {
                    mutableStateOf(SettingsManager.isVoiceAutoInsert(context))
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.voice_auto_insert_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.voice_auto_insert_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Switch(
                            checked = voiceAutoInsert,
                            onCheckedChange = { enabled ->
                                voiceAutoInsert = enabled
                                SettingsManager.setVoiceAutoInsert(context, enabled)
                            }
                        )
                    }
                }
            }

            // Sherpa-ONNX Model Selector (shown when offline voice is enabled)
            if (offlineVoiceInput) {
                // File picker launcher for Sherpa zip files
                val sherpaZipFileLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let {
                        try {
                            context.contentResolver.takePersistableUriPermission(
                                it,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: Exception) {
                            // Permission may already be granted
                        }
                        SettingsManager.setSherpaModelPath(context, it.toString())
                        sherpaModelPath = it.toString()
                        // Delete old extracted model when new file is selected
                        SherpaSpeechRecognizer.getInstance(context).deleteExtractedModel()
                        sherpaModelStatus = SherpaSpeechRecognizer.getInstance(context).getModelStatus()
                    }
                }

                var sherpaExtractionProgress by remember { mutableStateOf(0) }
                var sherpaExtractionMessage by remember { mutableStateOf("") }
                var isExtractingSherpaModel by remember { mutableStateOf(false) }

                val sherpaStatusText = when (sherpaModelStatus) {
                    SherpaSpeechRecognizer.ModelStatus.NOT_CONFIGURED -> stringResource(R.string.sherpa_model_status_not_configured)
                    SherpaSpeechRecognizer.ModelStatus.ZIP_CONFIGURED -> stringResource(R.string.sherpa_model_status_zip_configured)
                    SherpaSpeechRecognizer.ModelStatus.EXTRACTING -> stringResource(R.string.sherpa_model_status_extracting)
                    SherpaSpeechRecognizer.ModelStatus.AVAILABLE -> stringResource(R.string.sherpa_model_status_available)
                    SherpaSpeechRecognizer.ModelStatus.LOADING -> stringResource(R.string.sherpa_model_status_loading)
                    SherpaSpeechRecognizer.ModelStatus.READY -> stringResource(R.string.sherpa_model_status_ready)
                }

                val sherpaDisplayName = SherpaSpeechRecognizer.getInstance(context).getModelZipName()
                val sherpaDisplayText = sherpaDisplayName ?: stringResource(R.string.sherpa_model_not_selected)

                Surface(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.sherpa_model_path_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    text = sherpaDisplayText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Text(
                                    text = if (isExtractingSherpaModel) sherpaExtractionMessage else sherpaStatusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (sherpaModelStatus) {
                                        SherpaSpeechRecognizer.ModelStatus.READY,
                                        SherpaSpeechRecognizer.ModelStatus.AVAILABLE -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1
                                )
                            }
                        }

                        if (isExtractingSherpaModel) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { sherpaExtractionProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.sherpa_model_instructions),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    sherpaZipFileLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isExtractingSherpaModel
                            ) {
                                Text(stringResource(R.string.sherpa_model_select_zip_button))
                            }

                            if (sherpaModelStatus == SherpaSpeechRecognizer.ModelStatus.ZIP_CONFIGURED) {
                                Button(
                                    onClick = {
                                        isExtractingSherpaModel = true
                                        SherpaSpeechRecognizer.getInstance(context).extractZipModel(
                                            onProgress = { progress, message ->
                                                sherpaExtractionProgress = progress
                                                sherpaExtractionMessage = message
                                            },
                                            onComplete = { success, error ->
                                                isExtractingSherpaModel = false
                                                sherpaModelStatus = SherpaSpeechRecognizer.getInstance(context).getModelStatus()
                                                if (!success) {
                                                    sherpaExtractionMessage = error ?: "Extraction failed"
                                                }
                                            }
                                        )
                                    },
                                    enabled = !isExtractingSherpaModel
                                ) {
                                    Text(stringResource(R.string.sherpa_model_extract_button))
                                }
                            }

                            if (sherpaModelPath != null) {
                                OutlinedButton(
                                    onClick = {
                                        SherpaSpeechRecognizer.getInstance(context).clearZipConfig(true)
                                        sherpaModelStatus = SherpaSpeechRecognizer.getInstance(context).getModelStatus()
                                        sherpaModelPath = null
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    enabled = !isExtractingSherpaModel
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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

            // 3D Effect
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
                            text = stringResource(R.string.effect_3d_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.effect_3d_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = effect3DEnabled,
                        onCheckedChange = { enabled ->
                            effect3DEnabled = enabled
                            SettingsManager.set3DEffectEnabled(context, enabled)
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

            // T9 Pinyin Toggle
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
                        imageVector = Icons.Filled.Dialpad,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chinese_input_t9pinyin),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.chinese_input_t9pinyin_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = t9PinyinEnabled,
                        onCheckedChange = { enabled ->
                            t9PinyinEnabled = enabled
                            SettingsManager.setT9PinyinEnabled(context, enabled)
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

                // Wubi auto-commit single candidate toggle
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
                            imageVector = Icons.Filled.TextFields,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
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

                // Wubi auto-commit overflow (5th letter) toggle
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
                            imageVector = Icons.Filled.TextFields,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
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

                // Wubi Z Key Mode dropdown
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .padding(start = 40.dp)  // Indent as sub-option
                        .clickable { wubiZKeyModeExpanded = true }
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
                                text = stringResource(R.string.wubi_z_key_mode_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            val currentModeLabel = when (wubiZKeyMode) {
                                "disabled" -> stringResource(R.string.wubi_z_key_mode_disabled)
                                "wildcard" -> stringResource(R.string.wubi_z_key_mode_wildcard)
                                "symbol" -> stringResource(R.string.wubi_z_key_mode_symbol)
                                else -> stringResource(R.string.wubi_z_key_mode_wildcard)
                            }
                            Text(
                                text = currentModeLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Box {
                            DropdownMenu(
                                expanded = wubiZKeyModeExpanded,
                                onDismissRequest = { wubiZKeyModeExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(stringResource(R.string.wubi_z_key_mode_disabled))
                                            Text(
                                                stringResource(R.string.wubi_z_key_mode_disabled_desc),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        wubiZKeyMode = "disabled"
                                        SettingsManager.setWubiZKeyMode(context, "disabled")
                                        wubiZKeyModeExpanded = false
                                    },
                                    leadingIcon = {
                                        if (wubiZKeyMode == "disabled") {
                                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(stringResource(R.string.wubi_z_key_mode_wildcard))
                                            Text(
                                                stringResource(R.string.wubi_z_key_mode_wildcard_desc),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        wubiZKeyMode = "wildcard"
                                        SettingsManager.setWubiZKeyMode(context, "wildcard")
                                        wubiZKeyModeExpanded = false
                                    },
                                    leadingIcon = {
                                        if (wubiZKeyMode == "wildcard") {
                                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(stringResource(R.string.wubi_z_key_mode_symbol))
                                            Text(
                                                stringResource(R.string.wubi_z_key_mode_symbol_desc),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        wubiZKeyMode = "symbol"
                                        SettingsManager.setWubiZKeyMode(context, "symbol")
                                        wubiZKeyModeExpanded = false
                                    },
                                    leadingIcon = {
                                        if (wubiZKeyMode == "symbol") {
                                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                            }
                        }
                    }
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

                // Traditional Chinese toggle (简/繁 button)
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
                            imageVector = Icons.Filled.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.traditional_chinese_toggle_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.traditional_chinese_toggle_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Switch(
                            checked = traditionalChineseToggleEnabled,
                            onCheckedChange = { enabled ->
                                traditionalChineseToggleEnabled = enabled
                                SettingsManager.setTraditionalChineseToggleEnabled(context, enabled)
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

                // Touchpad Page Navigation toggle
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
                                text = stringResource(R.string.touchpad_page_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.touchpad_page_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Switch(
                            checked = touchpadPageEnabled,
                            onCheckedChange = { enabled ->
                                touchpadPageEnabled = enabled
                                SettingsManager.setTouchpadPageEnabled(context, enabled)
                            }
                        )
                    }
                }

                // Alt Double-Click Delay setting with slider
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
                                    text = stringResource(R.string.alt_double_click_delay_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    text = stringResource(R.string.alt_double_click_delay_description, altDoubleClickDelay.toInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                        Slider(
                            value = altDoubleClickDelay,
                            onValueChange = { altDoubleClickDelay = it },
                            onValueChangeFinished = {
                                SettingsManager.setAltDoubleClickDelay(context, altDoubleClickDelay.toInt())
                            },
                            valueRange = 250f..1000f,
                            steps = 14, // 250, 300, 350, ..., 1000 (50ms increments = 15 values, 14 steps)
                            modifier = Modifier.padding(top = 8.dp)
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

            // Show LED Status toggle
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
                            text = stringResource(R.string.show_led_status_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.show_led_status_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = showLedStatus,
                        onCheckedChange = { enabled ->
                            showLedStatus = enabled
                            SettingsManager.setShowLedStatus(context, enabled)
                        }
                    )
                }
            }

            // Show SYM Button toggle
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
                            text = stringResource(R.string.show_sym_button_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = stringResource(R.string.show_sym_button_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = showSymButton,
                        onCheckedChange = { enabled ->
                            showSymButton = enabled
                            SettingsManager.setShowSymButton(context, enabled)
                        }
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

            // Abbreviation Input Toggle (only show if Pinyin is enabled)
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
                            imageVector = Icons.Filled.TextFields,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.abbreviation_input_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.abbreviation_input_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Switch(
                            checked = abbreviationInputEnabled,
                            onCheckedChange = { enabled ->
                                abbreviationInputEnabled = enabled
                                SettingsManager.setAbbreviationInputEnabled(context, enabled)
                            }
                        )
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
