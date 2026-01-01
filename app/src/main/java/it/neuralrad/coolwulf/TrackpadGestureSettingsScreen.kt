package it.neuralrad.coolwulf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import it.neuralrad.coolwulf.core.adb.AdbPairingService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import it.neuralrad.coolwulf.core.adb.AdbPortDiscovery
import it.neuralrad.coolwulf.core.adb.EmbeddedADB
import kotlinx.coroutines.launch

/**
 * Settings screen for trackpad gesture suggestions.
 * Allows users to enable/disable trackpad swipe gestures using embedded ADB
 * for inserting suggestions in both English (3 zones) and Chinese Juying mode (5 zones).
 */
@Composable
fun TrackpadGestureSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var trackpadGesturesEnabled by remember {
        mutableStateOf(SettingsManager.getTrackpadGesturesEnabled(context))
    }
    var swipeThreshold by remember {
        mutableStateOf(SettingsManager.getTrackpadSwipeThreshold(context))
    }
    var swipeSelectionSoundEnabled by remember {
        mutableStateOf(SettingsManager.getSwipeSelectionSoundEnabled(context))
    }
    var swipeSelectionSoundVolume by remember {
        mutableStateOf(SettingsManager.getSwipeSelectionSoundVolume(context))
    }
    var swipeSelectionAnimationEnabled by remember {
        mutableStateOf(SettingsManager.getSwipeSelectionAnimationEnabled(context))
    }
    var splitSwipeDownEnabled by remember {
        mutableStateOf(SettingsManager.getSplitSwipeDownEnabled(context))
    }

    // Embedded ADB state
    var embeddedAdbPaired by remember {
        mutableStateOf(SettingsManager.getEmbeddedAdbPaired(context))
    }

    // Embedded ADB pairing dialog state
    var showPairingDialog by remember { mutableStateOf(false) }
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectionPort by remember { mutableStateOf("") }  // The port shown after "IP address & Port"
    var pairingInProgress by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var connectionError by remember { mutableStateOf<String?>(null) }

    // Embedded ADB connection state
    var embeddedAdbConnected by remember { mutableStateOf(false) }
    var embeddedAdbConnecting by remember { mutableStateOf(false) }
    var wirelessDebuggingEnabled by remember { mutableStateOf(false) }

    val embeddedAdb = remember { EmbeddedADB.getInstance(context) }
    val portDiscovery = remember { AdbPortDiscovery(context) }
    val coroutineScope = rememberCoroutineScope()

    // Check wireless debugging and connection status
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wirelessDebuggingEnabled = embeddedAdb.isWirelessDebuggingEnabled()
            embeddedAdbConnected = embeddedAdb.isConnected()
        }
    }

    // Listen for pairing result from notification service
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == AdbPairingService.ACTION_PAIRING_RESULT) {
                    val success = intent.getBooleanExtra(AdbPairingService.EXTRA_RESULT_SUCCESS, false)
                    val message = intent.getStringExtra(AdbPairingService.EXTRA_RESULT_MESSAGE)
                    Log.d("TrackpadGestures", "Pairing result: success=$success, message=$message")

                    if (success) {
                        embeddedAdbPaired = SettingsManager.getEmbeddedAdbPaired(context)
                        embeddedAdbConnected = embeddedAdb.isConnected()
                    } else {
                        connectionError = message ?: context.getString(R.string.embedded_adb_pairing_failed)
                    }
                }
            }
        }

        val filter = IntentFilter(AdbPairingService.ACTION_PAIRING_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.e("TrackpadGestures", "Error unregistering receiver", e)
            }
        }
    }

    // Determine if connection is ready
    val connectionReady = embeddedAdbPaired && embeddedAdbConnected

    BackHandler { onBack() }

    // Pairing Dialog - Only need connection port upfront, pairing port+code entered via notification
    if (showPairingDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!pairingInProgress) {
                    showPairingDialog = false
                    pairingError = null
                }
            },
            title = { Text(stringResource(R.string.embedded_adb_pairing_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.embedded_adb_pairing_instructions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Only need connection port - it's shown under "IP address & Port" and doesn't change
                    OutlinedTextField(
                        value = connectionPort,
                        onValueChange = { connectionPort = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.embedded_adb_connection_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !pairingInProgress,
                        supportingText = {
                            Text(stringResource(R.string.embedded_adb_connection_port_hint))
                        }
                    )

                    pairingError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (pairingInProgress) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.embedded_adb_pairing_in_progress))
                        }
                    }

                    // Notification-based pairing section
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = stringResource(R.string.embedded_adb_start_pairing_notification_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )

                    // Start Notification Pairing button
                    Button(
                        onClick = {
                            val connPort = connectionPort.toIntOrNull()
                            if (connPort == null || connPort <= 0) {
                                pairingError = context.getString(R.string.embedded_adb_invalid_connection_port)
                                return@Button
                            }

                            // Start notification pairing service (pairing port will be entered via notification)
                            AdbPairingService.start(context, 0, connPort)
                            showPairingDialog = false
                            connectionPort = ""

                            // Open developer settings so user can see the pairing code
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Log.e("TrackpadGestures", "Failed to open developer settings", e)
                            }
                        },
                        enabled = !pairingInProgress && connectionPort.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.embedded_adb_start_pairing_notification))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        showPairingDialog = false
                        pairingError = null
                    },
                    enabled = !pairingInProgress
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // Header
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
                    text = stringResource(R.string.trackpad_gestures_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // Connection Status Card
        val embeddedAdbReady = embeddedAdbPaired && embeddedAdbConnected
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = when {
                embeddedAdbReady -> MaterialTheme.colorScheme.primaryContainer
                embeddedAdbPaired && !embeddedAdbConnected -> MaterialTheme.colorScheme.tertiaryContainer
                !wirelessDebuggingEnabled -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = when {
                            embeddedAdbReady -> Icons.Filled.CheckCircle
                            !wirelessDebuggingEnabled -> Icons.Filled.Error
                            else -> Icons.Filled.Wifi
                        },
                        contentDescription = null,
                        tint = when {
                            embeddedAdbReady -> MaterialTheme.colorScheme.onPrimaryContainer
                            embeddedAdbPaired -> MaterialTheme.colorScheme.onTertiaryContainer
                            !wirelessDebuggingEnabled -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = when {
                            embeddedAdbReady -> stringResource(R.string.embedded_adb_connected)
                            embeddedAdbConnecting -> stringResource(R.string.embedded_adb_connecting)
                            embeddedAdbPaired && !embeddedAdbConnected -> stringResource(R.string.embedded_adb_paired_not_connected)
                            !wirelessDebuggingEnabled -> stringResource(R.string.embedded_adb_wireless_debugging_disabled)
                            !embeddedAdbPaired -> stringResource(R.string.embedded_adb_not_paired)
                            else -> stringResource(R.string.embedded_adb_ready)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            embeddedAdbReady -> MaterialTheme.colorScheme.onPrimaryContainer
                            embeddedAdbPaired -> MaterialTheme.colorScheme.onTertiaryContainer
                            !wirelessDebuggingEnabled -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action buttons based on state
                if (!wirelessDebuggingEnabled) {
                    // Open Developer Settings button
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Log.e("TrackpadGestures", "Failed to open developer settings", e)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.embedded_adb_open_dev_settings))
                    }
                } else if (!embeddedAdbPaired) {
                    // Pair button
                    Button(
                        onClick = { showPairingDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.embedded_adb_pair_button))
                    }
                } else if (!embeddedAdbConnected) {
                    // Show connection error if any
                    connectionError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // Connect button
                    Button(
                        onClick = {
                            embeddedAdbConnecting = true
                            connectionError = null
                            coroutineScope.launch {
                                try {
                                    // Use stored port
                                    val portToUse = SettingsManager.getEmbeddedAdbPort(context)

                                    if (portToUse > 0) {
                                        val success = embeddedAdb.connect(portToUse)
                                        if (success) {
                                            embeddedAdbConnected = true
                                        } else {
                                            connectionError = context.getString(R.string.embedded_adb_connection_failed)
                                        }
                                    } else {
                                        connectionError = context.getString(R.string.embedded_adb_no_port_saved)
                                    }
                                } catch (e: Exception) {
                                    Log.e("TrackpadGestures", "Connection failed", e)
                                    connectionError = context.getString(R.string.embedded_adb_connection_failed)
                                } finally {
                                    embeddedAdbConnecting = false
                                }
                            }
                        },
                        enabled = !embeddedAdbConnecting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (embeddedAdbConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.embedded_adb_connect_button))
                    }
                }

                // Show "Re-pair" option if already paired
                if (embeddedAdbPaired) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { showPairingDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.embedded_adb_repair_button))
                    }
                }
            }
        }

        // Setup Instructions
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.embedded_adb_setup_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.embedded_adb_setup_instructions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Enable/Disable Toggle
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
                    imageVector = Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.trackpad_gestures_enabled_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Text(
                        text = stringResource(R.string.trackpad_gestures_enabled_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Switch(
                    checked = trackpadGesturesEnabled,
                    onCheckedChange = { enabled ->
                        trackpadGesturesEnabled = enabled
                        SettingsManager.setTrackpadGesturesEnabled(context, enabled)
                    },
                    enabled = connectionReady
                )
            }
        }

        // Swipe Threshold Slider (only show if enabled)
        if (trackpadGesturesEnabled && connectionReady) {
            Surface(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.trackpad_gestures_swipe_threshold_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.trackpad_gestures_swipe_threshold_description, swipeThreshold),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = swipeThreshold.toFloat(),
                        onValueChange = { value ->
                            swipeThreshold = value.toInt()
                        },
                        onValueChangeFinished = {
                            SettingsManager.setTrackpadSwipeThreshold(context, swipeThreshold)
                        },
                        valueRange = SettingsManager.getMinTrackpadSwipeThreshold().toFloat()..SettingsManager.getMaxTrackpadSwipeThreshold().toFloat(),
                        steps = 19
                    )
                }
            }

            // Swipe Selection Sound Toggle
            Surface(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.swipe_selection_sound_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.swipe_selection_sound_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = swipeSelectionSoundEnabled,
                        onCheckedChange = { enabled ->
                            swipeSelectionSoundEnabled = enabled
                            SettingsManager.setSwipeSelectionSoundEnabled(context, enabled)
                        }
                    )
                }
            }

            // Swipe Selection Sound Volume Slider (only shown when sound is enabled)
            if (swipeSelectionSoundEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.swipe_selection_sound_volume_title),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${(swipeSelectionSoundVolume * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = swipeSelectionSoundVolume,
                        onValueChange = { volume ->
                            swipeSelectionSoundVolume = volume
                            SettingsManager.setSwipeSelectionSoundVolume(context, volume)
                        },
                        valueRange = 0f..1f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Swipe Selection Animation Toggle
            Surface(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.swipe_selection_animation_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.swipe_selection_animation_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = swipeSelectionAnimationEnabled,
                        onCheckedChange = { enabled ->
                            swipeSelectionAnimationEnabled = enabled
                            SettingsManager.setSwipeSelectionAnimationEnabled(context, enabled)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Split Swipe Down Toggle
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.split_swipe_down_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.split_swipe_down_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = splitSwipeDownEnabled,
                        onCheckedChange = { enabled ->
                            splitSwipeDownEnabled = enabled
                            SettingsManager.setSplitSwipeDownEnabled(context, enabled)
                        }
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // How It Works Section
        Surface(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.trackpad_gestures_how_it_works_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // English Mode (3 zones) visualization
                Text(
                    text = stringResource(R.string.trackpad_gestures_how_it_works_english),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 3-zone visualization
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("1", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("abc", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("2", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }

                Text(
                    text = "English: [1st best] [typed word] [2nd best]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Chinese Mode (5 zones) description
                Text(
                    text = stringResource(R.string.trackpad_gestures_how_it_works_chinese),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 5-zone visualization
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("2", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("3", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("1", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("4", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("5", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Text(
                    text = "Chinese Juying: 5 candidates",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
