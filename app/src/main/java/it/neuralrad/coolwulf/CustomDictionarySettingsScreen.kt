package it.neuralrad.coolwulf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.BackHandler
import it.neuralrad.coolwulf.data.UserCustomDictionary

/**
 * Custom Dictionary settings screen for defining letter -> phrase mappings.
 */
@Composable
fun CustomDictionarySettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val customDictionary = remember { UserCustomDictionary.getInstance(context) }

    // 0 = Pinyin, 1 = Shuangpin, 2 = Wubi
    var selectedMode by remember { mutableIntStateOf(0) }

    // Mappings list
    var pinyinMappings by remember { mutableStateOf(customDictionary.getAllPinyinMappings()) }
    var shuangpinMappings by remember { mutableStateOf(customDictionary.getAllShuangpinMappings()) }
    var wubiMappings by remember { mutableStateOf(customDictionary.getAllWubiMappings()) }

    // Dialog states
    var showAddDialog by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf("") }
    var phraseInput by remember { mutableStateOf("") }

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
                        text = stringResource(R.string.custom_dictionary_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    codeInput = ""
                    phraseInput = ""
                    showAddDialog = true
                }
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.custom_dictionary_add))
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Mode selector tabs
            TabRow(
                selectedTabIndex = selectedMode,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedMode == 0,
                    onClick = { selectedMode = 0 },
                    text = { Text(stringResource(R.string.custom_dictionary_pinyin_mode)) }
                )
                Tab(
                    selected = selectedMode == 1,
                    onClick = { selectedMode = 1 },
                    text = { Text(stringResource(R.string.custom_dictionary_shuangpin_mode)) }
                )
                Tab(
                    selected = selectedMode == 2,
                    onClick = { selectedMode = 2 },
                    text = { Text(stringResource(R.string.custom_dictionary_wubi_mode)) }
                )
            }

            // Description
            Text(
                text = stringResource(R.string.custom_dictionary_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )

            // Mappings list
            val currentMappings = when (selectedMode) {
                0 -> pinyinMappings
                1 -> shuangpinMappings
                else -> wubiMappings
            }

            if (currentMappings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.custom_dictionary_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(currentMappings) { (code, phrase) ->
                        MappingItem(
                            code = code,
                            phrase = phrase,
                            onDelete = {
                                when (selectedMode) {
                                    0 -> {
                                        customDictionary.removePinyinMapping(code, phrase)
                                        pinyinMappings = customDictionary.getAllPinyinMappings()
                                    }
                                    1 -> {
                                        customDictionary.removeShuangpinMapping(code, phrase)
                                        shuangpinMappings = customDictionary.getAllShuangpinMappings()
                                    }
                                    else -> {
                                        customDictionary.removeWubiMapping(code, phrase)
                                        wubiMappings = customDictionary.getAllWubiMappings()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Add mapping dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = stringResource(
                        when (selectedMode) {
                            0 -> R.string.custom_dictionary_add_pinyin
                            1 -> R.string.custom_dictionary_add_shuangpin
                            else -> R.string.custom_dictionary_add_wubi
                        }
                    )
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it.lowercase().filter { c -> c.isLetter() } },
                        label = { Text(stringResource(R.string.custom_dictionary_code_label)) },
                        placeholder = {
                            Text(
                                stringResource(
                                    when (selectedMode) {
                                        0 -> R.string.custom_dictionary_code_hint_pinyin
                                        1 -> R.string.custom_dictionary_code_hint_shuangpin
                                        else -> R.string.custom_dictionary_code_hint_wubi
                                    }
                                )
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = phraseInput,
                        onValueChange = { phraseInput = it },
                        label = { Text(stringResource(R.string.custom_dictionary_phrase_label)) },
                        placeholder = { Text(stringResource(R.string.custom_dictionary_phrase_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (codeInput.isNotBlank() && phraseInput.isNotBlank()) {
                            when (selectedMode) {
                                0 -> {
                                    customDictionary.addPinyinMapping(codeInput, phraseInput)
                                    pinyinMappings = customDictionary.getAllPinyinMappings()
                                }
                                1 -> {
                                    customDictionary.addShuangpinMapping(codeInput, phraseInput)
                                    shuangpinMappings = customDictionary.getAllShuangpinMappings()
                                }
                                else -> {
                                    customDictionary.addWubiMapping(codeInput, phraseInput)
                                    wubiMappings = customDictionary.getAllWubiMappings()
                                }
                            }
                            showAddDialog = false
                        }
                    },
                    enabled = codeInput.isNotBlank() && phraseInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.custom_dictionary_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.custom_dictionary_cancel))
                }
            }
        )
    }
}

@Composable
private fun MappingItem(
    code: String,
    phrase: String,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = phrase,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.custom_dictionary_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.custom_dictionary_delete_confirm_title)) },
            text = { Text(stringResource(R.string.custom_dictionary_delete_confirm_message, code, phrase)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.custom_dictionary_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.custom_dictionary_cancel))
                }
            }
        )
    }

    HorizontalDivider()
}
