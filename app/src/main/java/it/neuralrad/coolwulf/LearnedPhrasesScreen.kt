package it.neuralrad.coolwulf

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.neuralrad.coolwulf.data.pinyin.AutoPhraseMemory
import it.neuralrad.coolwulf.data.shuangpin.ShuangpinPhraseMemory
import it.neuralrad.coolwulf.data.wubi.WubiPhraseMemory

/**
 * Settings screen for viewing and managing learned phrases from AutoPhraseMemory and WubiPhraseMemory.
 * Uses tabs to separate Pinyin and Wubi learned phrases.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnedPhrasesScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val autoPhraseMemory = remember { AutoPhraseMemory.getInstance(context) }
    val wubiPhraseMemory = remember { WubiPhraseMemory.getInstance(context) }
    val shuangpinPhraseMemory = remember { ShuangpinPhraseMemory.getInstance(context) }

    // Tab state
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.learned_phrases_tab_pinyin),
        stringResource(R.string.learned_phrases_tab_shuangpin),
        stringResource(R.string.learned_phrases_tab_wubi)
    )

    // Get learned phrases for each type
    var pinyinPhrases by remember { mutableStateOf(autoPhraseMemory.getAllLearnedPhrases()) }
    var shuangpinPhrases by remember { mutableStateOf(shuangpinPhraseMemory.getAllLearnedPhrases()) }
    var wubiPhrases by remember { mutableStateOf(wubiPhraseMemory.getAllLearnedPhrases()) }

    // Dialog state for confirm delete all
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    // Dialog state for confirm delete single (Pinyin)
    var pinyinPhraseToDelete by remember { mutableStateOf<AutoPhraseMemory.LearnedPhrase?>(null) }

    // Dialog state for confirm delete single (Shuangpin)
    var shuangpinPhraseToDelete by remember { mutableStateOf<ShuangpinPhraseMemory.LearnedPhrase?>(null) }

    // Dialog state for confirm delete single (Wubi)
    var wubiPhraseToDelete by remember { mutableStateOf<WubiPhraseMemory.LearnedPhrase?>(null) }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.learned_phrases_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Delete all button - based on current tab
                    val hasItems = when (selectedTabIndex) {
                        0 -> pinyinPhrases.isNotEmpty()
                        1 -> shuangpinPhrases.isNotEmpty()
                        else -> wubiPhrases.isNotEmpty()
                    }
                    if (hasItems) {
                        IconButton(onClick = { showDeleteAllDialog = true }) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = "Delete all",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab row
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            // Content based on selected tab
            when (selectedTabIndex) {
                0 -> PinyinPhrasesTab(
                    autoPhraseMemory = autoPhraseMemory,
                    phrases = pinyinPhrases,
                    onDeletePhrase = { pinyinPhraseToDelete = it }
                )
                1 -> ShuangpinPhrasesTab(
                    shuangpinPhraseMemory = shuangpinPhraseMemory,
                    phrases = shuangpinPhrases,
                    onDeletePhrase = { shuangpinPhraseToDelete = it }
                )
                2 -> WubiPhrasesTab(
                    wubiPhraseMemory = wubiPhraseMemory,
                    phrases = wubiPhrases,
                    onDeletePhrase = { wubiPhraseToDelete = it }
                )
            }
        }
    }

    // Delete single Pinyin phrase confirmation dialog
    pinyinPhraseToDelete?.let { phrase ->
        AlertDialog(
            onDismissRequest = { pinyinPhraseToDelete = null },
            title = { Text(stringResource(R.string.learned_phrases_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.learned_phrases_delete_message))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${phrase.phrase} (${phrase.pinyin})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        autoPhraseMemory.deleteLearnedPhrase(phrase.pinyin, phrase.phrase)
                        pinyinPhrases = autoPhraseMemory.getAllLearnedPhrases()
                        pinyinPhraseToDelete = null
                        Toast.makeText(context, R.string.learned_phrases_deleted, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pinyinPhraseToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Delete single Shuangpin phrase confirmation dialog
    shuangpinPhraseToDelete?.let { phrase ->
        AlertDialog(
            onDismissRequest = { shuangpinPhraseToDelete = null },
            title = { Text(stringResource(R.string.learned_phrases_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.learned_phrases_delete_message))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${phrase.phrase} (${phrase.shuangpinCode})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        shuangpinPhraseMemory.deleteLearnedPhrase(phrase.shuangpinCode, phrase.phrase)
                        shuangpinPhrases = shuangpinPhraseMemory.getAllLearnedPhrases()
                        shuangpinPhraseToDelete = null
                        Toast.makeText(context, R.string.learned_phrases_deleted, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { shuangpinPhraseToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Delete single Wubi phrase confirmation dialog
    wubiPhraseToDelete?.let { phrase ->
        AlertDialog(
            onDismissRequest = { wubiPhraseToDelete = null },
            title = { Text(stringResource(R.string.learned_phrases_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.learned_phrases_delete_message))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${phrase.phrase} (${phrase.wubiCode})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        wubiPhraseMemory.deleteLearnedPhrase(phrase.wubiCode, phrase.phrase)
                        wubiPhrases = wubiPhraseMemory.getAllLearnedPhrases()
                        wubiPhraseToDelete = null
                        Toast.makeText(context, R.string.learned_phrases_deleted, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { wubiPhraseToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Delete all confirmation dialog
    if (showDeleteAllDialog) {
        val count = when (selectedTabIndex) {
            0 -> pinyinPhrases.size
            1 -> shuangpinPhrases.size
            else -> wubiPhrases.size
        }
        val typeLabel = when (selectedTabIndex) {
            0 -> stringResource(R.string.learned_phrases_tab_pinyin)
            1 -> stringResource(R.string.learned_phrases_tab_shuangpin)
            else -> stringResource(R.string.learned_phrases_tab_wubi)
        }

        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.learned_phrases_delete_all_title)) },
            text = {
                Text(stringResource(R.string.learned_phrases_delete_all_message, count, typeLabel))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (selectedTabIndex) {
                            0 -> {
                                autoPhraseMemory.clearAll()
                                pinyinPhrases = autoPhraseMemory.getAllLearnedPhrases()
                            }
                            1 -> {
                                shuangpinPhraseMemory.clearAll()
                                shuangpinPhrases = shuangpinPhraseMemory.getAllLearnedPhrases()
                            }
                            else -> {
                                wubiPhraseMemory.clearAll()
                                wubiPhrases = wubiPhraseMemory.getAllLearnedPhrases()
                            }
                        }
                        showDeleteAllDialog = false
                        Toast.makeText(context, R.string.learned_phrases_all_deleted, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.delete_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun PinyinPhrasesTab(
    autoPhraseMemory: AutoPhraseMemory,
    phrases: List<AutoPhraseMemory.LearnedPhrase>,
    onDeletePhrase: (AutoPhraseMemory.LearnedPhrase) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Stats header
        val stats = autoPhraseMemory.getStats()
        StatsHeader(
            learnedCount = stats.learnedPhraseCount,
            totalUses = stats.totalSelections,
            pendingCount = stats.pendingPhraseCount
        )

        HorizontalDivider()

        // Phrases list
        if (phrases.isEmpty()) {
            EmptyPhrasesMessage()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(phrases, key = { "${it.pinyin}_${it.phrase}" }) { phrase ->
                    PinyinPhraseItem(
                        phrase = phrase,
                        onDelete = { onDeletePhrase(phrase) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ShuangpinPhrasesTab(
    shuangpinPhraseMemory: ShuangpinPhraseMemory,
    phrases: List<ShuangpinPhraseMemory.LearnedPhrase>,
    onDeletePhrase: (ShuangpinPhraseMemory.LearnedPhrase) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Stats header
        val stats = shuangpinPhraseMemory.getStats()
        StatsHeader(
            learnedCount = stats.learnedPhraseCount,
            totalUses = stats.totalSelections,
            pendingCount = stats.pendingPhraseCount
        )

        HorizontalDivider()

        // Phrases list
        if (phrases.isEmpty()) {
            EmptyPhrasesMessage()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(phrases, key = { "${it.shuangpinCode}_${it.phrase}" }) { phrase ->
                    ShuangpinPhraseItem(
                        phrase = phrase,
                        onDelete = { onDeletePhrase(phrase) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WubiPhrasesTab(
    wubiPhraseMemory: WubiPhraseMemory,
    phrases: List<WubiPhraseMemory.LearnedPhrase>,
    onDeletePhrase: (WubiPhraseMemory.LearnedPhrase) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Stats header
        val stats = wubiPhraseMemory.getStats()
        StatsHeader(
            learnedCount = stats.learnedPhraseCount,
            totalUses = stats.totalSelections,
            pendingCount = stats.pendingPhraseCount
        )

        HorizontalDivider()

        // Phrases list
        if (phrases.isEmpty()) {
            EmptyPhrasesMessage()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(phrases, key = { "${it.wubiCode}_${it.phrase}" }) { phrase ->
                    WubiPhraseItem(
                        phrase = phrase,
                        onDelete = { onDeletePhrase(phrase) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsHeader(
    learnedCount: Int,
    totalUses: Int,
    pendingCount: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$learnedCount",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.learned_phrases_stats_learned),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$totalUses",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = stringResource(R.string.learned_phrases_stats_uses),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$pendingCount",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    text = stringResource(R.string.learned_phrases_stats_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyPhrasesMessage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.learned_phrases_empty),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.learned_phrases_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PinyinPhraseItem(
    phrase: AutoPhraseMemory.LearnedPhrase,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Phrase and pinyin
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = phrase.phrase,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = phrase.pinyin,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Frequency badge
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "${phrase.frequency}x",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun ShuangpinPhraseItem(
    phrase: ShuangpinPhraseMemory.LearnedPhrase,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Phrase and Shuangpin code
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = phrase.phrase,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = phrase.shuangpinCode,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Frequency badge
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "${phrase.frequency}x",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun WubiPhraseItem(
    phrase: WubiPhraseMemory.LearnedPhrase,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Phrase and Wubi code
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = phrase.phrase,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = phrase.wubiCode,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Frequency badge
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "${phrase.frequency}x",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}
