package it.neuralrad.coolwulf

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.neuralrad.coolwulf.data.UserCustomDictionary
import it.neuralrad.coolwulf.data.pinyin.UserPinyinMemory
import it.neuralrad.coolwulf.data.wubi.UserWubiMemory
import it.neuralrad.coolwulf.data.zhenma.UserZhenmaMemory
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val pinyinMemory = remember { UserPinyinMemory.getInstance(context) }
    val wubiMemory = remember { UserWubiMemory.getInstance(context) }
    val zhenmaMemory = remember { UserZhenmaMemory.getInstance(context) }

    // 0 = Pinyin, 1 = Shuangpin, 2 = Ziranma, 3 = Wubi, 4 = Zhenma
    var selectedMode by remember { mutableIntStateOf(0) }

    // Mappings list
    var pinyinMappings by remember { mutableStateOf(customDictionary.getAllPinyinMappings()) }
    var shuangpinMappings by remember { mutableStateOf(customDictionary.getAllShuangpinMappings()) }
    var ziranmaMappings by remember { mutableStateOf(customDictionary.getAllZiranmaMappings()) }
    var wubiMappings by remember { mutableStateOf(customDictionary.getAllWubiMappings()) }
    var zhenmaMappings by remember { mutableStateOf(customDictionary.getAllZhenmaMappings()) }

    // Dialog states
    var showAddDialog by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf("") }
    var phraseInput by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showImportModeDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var importType by remember { mutableStateOf("") }  // "dictionary" or "memory"

    // Search state
    var searchQuery by remember { mutableStateOf("") }

    // Export launcher for custom dictionary
    val exportDictionaryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                val jsonContent = customDictionary.exportToJson()
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonContent.toByteArray())
                }
                val count = customDictionary.getTotalMappingCount()
                Toast.makeText(
                    context,
                    context.getString(R.string.custom_dictionary_export_success, count),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.custom_dictionary_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Export launcher for user memory
    val exportMemoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                // Combine all memory exports into one JSON
                val combinedJson = JSONObject()
                combinedJson.put("pinyin", JSONObject(pinyinMemory.exportToJson()))
                combinedJson.put("wubi", JSONObject(wubiMemory.exportToJson()))
                combinedJson.put("zhenma", JSONObject(zhenmaMemory.exportToJson()))

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(combinedJson.toString(2).toByteArray())
                }
                Toast.makeText(context, R.string.user_memory_export_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.user_memory_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Import launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            pendingImportUri = uri
            showImportModeDialog = true
        }
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
                        text = stringResource(R.string.custom_dictionary_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f)
                    )
                    // Menu button for import/export
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.custom_dictionary_export)) },
                                onClick = {
                                    showMenu = false
                                    val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                    val timestamp = dateFormat.format(Date())
                                    exportDictionaryLauncher.launch("coolwulf_dictionary_$timestamp.json")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.custom_dictionary_import)) },
                                onClick = {
                                    showMenu = false
                                    importType = "dictionary"
                                    importLauncher.launch(arrayOf("application/json", "*/*"))
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.user_memory_export)) },
                                onClick = {
                                    showMenu = false
                                    val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                    val timestamp = dateFormat.format(Date())
                                    exportMemoryLauncher.launch("coolwulf_memory_$timestamp.json")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.user_memory_import)) },
                                onClick = {
                                    showMenu = false
                                    importType = "memory"
                                    importLauncher.launch(arrayOf("application/json", "*/*"))
                                }
                            )
                        }
                    }
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
            ScrollableTabRow(
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
                    text = { Text(stringResource(R.string.custom_dictionary_ziranma_mode)) }
                )
                Tab(
                    selected = selectedMode == 3,
                    onClick = { selectedMode = 3 },
                    text = { Text(stringResource(R.string.custom_dictionary_wubi_mode)) }
                )
                Tab(
                    selected = selectedMode == 4,
                    onClick = { selectedMode = 4 },
                    text = { Text(stringResource(R.string.custom_dictionary_zhenma_mode)) }
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
                2 -> ziranmaMappings
                3 -> wubiMappings
                else -> zhenmaMappings
            }

            // Filter mappings based on search query
            val filteredMappings = remember(currentMappings, searchQuery) {
                if (searchQuery.isBlank()) {
                    currentMappings
                } else {
                    val query = searchQuery.lowercase()
                    currentMappings.filter { (code, phrase) ->
                        code.lowercase().contains(query) || phrase.contains(query)
                    }
                }
            }

            // Search bar (only show if there are mappings)
            if (currentMappings.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.custom_dictionary_search_placeholder)) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.custom_dictionary_search_description)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.custom_dictionary_clear_search)
                                )
                            }
                        }
                    },
                    singleLine = true
                )
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
            } else if (filteredMappings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.custom_dictionary_no_results),
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
                    items(filteredMappings) { (code, phrase) ->
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
                                    2 -> {
                                        customDictionary.removeZiranmaMapping(code, phrase)
                                        ziranmaMappings = customDictionary.getAllZiranmaMappings()
                                    }
                                    3 -> {
                                        customDictionary.removeWubiMapping(code, phrase)
                                        wubiMappings = customDictionary.getAllWubiMappings()
                                    }
                                    else -> {
                                        customDictionary.removeZhenmaMapping(code, phrase)
                                        zhenmaMappings = customDictionary.getAllZhenmaMappings()
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
                            2 -> R.string.custom_dictionary_add_ziranma
                            3 -> R.string.custom_dictionary_add_wubi
                            else -> R.string.custom_dictionary_add_zhenma
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
                                        2 -> R.string.custom_dictionary_code_hint_ziranma
                                        3 -> R.string.custom_dictionary_code_hint_wubi
                                        else -> R.string.custom_dictionary_code_hint_zhenma
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
                                2 -> {
                                    customDictionary.addZiranmaMapping(codeInput, phraseInput)
                                    ziranmaMappings = customDictionary.getAllZiranmaMappings()
                                }
                                3 -> {
                                    customDictionary.addWubiMapping(codeInput, phraseInput)
                                    wubiMappings = customDictionary.getAllWubiMappings()
                                }
                                else -> {
                                    customDictionary.addZhenmaMapping(codeInput, phraseInput)
                                    zhenmaMappings = customDictionary.getAllZhenmaMappings()
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

    // Import mode dialog
    if (showImportModeDialog && pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = {
                showImportModeDialog = false
                pendingImportUri = null
            },
            title = { Text(stringResource(R.string.import_mode_title)) },
            text = {
                Column {
                    Text(
                        if (importType == "dictionary")
                            stringResource(R.string.custom_dictionary_import_description)
                        else
                            stringResource(R.string.user_memory_description)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingImportUri!!
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val jsonContent = inputStream?.bufferedReader()?.use { it.readText() } ?: ""

                            if (importType == "dictionary") {
                                val count = customDictionary.importFromJson(jsonContent, mergeMode = true)
                                if (count >= 0) {
                                    // Refresh mappings
                                    pinyinMappings = customDictionary.getAllPinyinMappings()
                                    shuangpinMappings = customDictionary.getAllShuangpinMappings()
                                    ziranmaMappings = customDictionary.getAllZiranmaMappings()
                                    wubiMappings = customDictionary.getAllWubiMappings()
                                    zhenmaMappings = customDictionary.getAllZhenmaMappings()
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.custom_dictionary_import_success, count),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(context, R.string.custom_dictionary_import_invalid, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                // Import user memory
                                val jsonObject = JSONObject(jsonContent)
                                var totalCount = 0

                                if (jsonObject.has("pinyin")) {
                                    val count = pinyinMemory.importFromJson(jsonObject.getJSONObject("pinyin").toString(), mergeMode = true)
                                    if (count > 0) totalCount += count
                                }
                                if (jsonObject.has("wubi")) {
                                    val count = wubiMemory.importFromJson(jsonObject.getJSONObject("wubi").toString(), mergeMode = true)
                                    if (count > 0) totalCount += count
                                }
                                if (jsonObject.has("zhenma")) {
                                    val count = zhenmaMemory.importFromJson(jsonObject.getJSONObject("zhenma").toString(), mergeMode = true)
                                    if (count > 0) totalCount += count
                                }

                                Toast.makeText(context, R.string.user_memory_import_success, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                if (importType == "dictionary") R.string.custom_dictionary_import_failed else R.string.user_memory_import_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        showImportModeDialog = false
                        pendingImportUri = null
                    }
                ) {
                    Text(stringResource(R.string.import_mode_merge))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val uri = pendingImportUri!!
                            try {
                                val inputStream = context.contentResolver.openInputStream(uri)
                                val jsonContent = inputStream?.bufferedReader()?.use { it.readText() } ?: ""

                                if (importType == "dictionary") {
                                    val count = customDictionary.importFromJson(jsonContent, mergeMode = false)
                                    if (count >= 0) {
                                        // Refresh mappings
                                        pinyinMappings = customDictionary.getAllPinyinMappings()
                                        shuangpinMappings = customDictionary.getAllShuangpinMappings()
                                        ziranmaMappings = customDictionary.getAllZiranmaMappings()
                                        wubiMappings = customDictionary.getAllWubiMappings()
                                        zhenmaMappings = customDictionary.getAllZhenmaMappings()
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.custom_dictionary_import_success, count),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(context, R.string.custom_dictionary_import_invalid, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    // Import user memory with replace mode
                                    val jsonObject = JSONObject(jsonContent)

                                    if (jsonObject.has("pinyin")) {
                                        pinyinMemory.importFromJson(jsonObject.getJSONObject("pinyin").toString(), mergeMode = false)
                                    }
                                    if (jsonObject.has("wubi")) {
                                        wubiMemory.importFromJson(jsonObject.getJSONObject("wubi").toString(), mergeMode = false)
                                    }
                                    if (jsonObject.has("zhenma")) {
                                        zhenmaMemory.importFromJson(jsonObject.getJSONObject("zhenma").toString(), mergeMode = false)
                                    }

                                    Toast.makeText(context, R.string.user_memory_import_success, Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    if (importType == "dictionary") R.string.custom_dictionary_import_failed else R.string.user_memory_import_failed,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            showImportModeDialog = false
                            pendingImportUri = null
                        }
                    ) {
                        Text(stringResource(R.string.import_mode_replace))
                    }
                    TextButton(
                        onClick = {
                            showImportModeDialog = false
                            pendingImportUri = null
                        }
                    ) {
                        Text(stringResource(R.string.custom_dictionary_cancel))
                    }
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
