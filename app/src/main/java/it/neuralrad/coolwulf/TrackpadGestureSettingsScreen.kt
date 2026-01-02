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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    var swipeSelectionAnimationType by remember {
        mutableStateOf(SettingsManager.getSwipeSelectionAnimationType(context))
    }
    var overlayPermissionGranted by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var splitSwipeDownEnabled by remember {
        mutableStateOf(SettingsManager.getSplitSwipeDownEnabled(context))
    }
    var pageFlipModeEnabled by remember {
        mutableStateOf(SettingsManager.getPageFlipModeEnabled(context))
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

    // Refresh overlay permission status when returning from system settings
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                overlayPermissionGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Listen for pairing result from notification service
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == AdbPairingService.ACTION_PAIRING_RESULT) {
                    val success = intent.getBooleanExtra(AdbPairingService.EXTRA_RESULT_SUCCESS, false)
                    val message = intent.getStringExtra(AdbPairingService.EXTRA_RESULT_MESSAGE)
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
                // Ignore unregister errors
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
                                // Ignore if developer settings can't be opened
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
                                // Ignore if developer settings can't be opened
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

        // Animation type selector (only shown when animation is enabled)
        if (swipeSelectionAnimationEnabled) {
            Spacer(modifier = Modifier.height(8.dp))

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
                    Text(
                        text = stringResource(R.string.animation_type_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Flying animation option
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    swipeSelectionAnimationType = "flying"
                                    SettingsManager.setSwipeSelectionAnimationType(context, "flying")
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (swipeSelectionAnimationType == "flying") {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (swipeSelectionAnimationType == "flying") 2.dp else 1.dp,
                                color = if (swipeSelectionAnimationType == "flying") {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "✈️",
                                    fontSize = 24.sp
                                )
                                Text(
                                    text = stringResource(R.string.animation_type_flying),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (swipeSelectionAnimationType == "flying") FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        // Fireworks animation option
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    swipeSelectionAnimationType = "fireworks"
                                    SettingsManager.setSwipeSelectionAnimationType(context, "fireworks")
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (swipeSelectionAnimationType == "fireworks") {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (swipeSelectionAnimationType == "fireworks") 2.dp else 1.dp,
                                color = if (swipeSelectionAnimationType == "fireworks") {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "🎆",
                                    fontSize = 24.sp
                                )
                                Text(
                                    text = stringResource(R.string.animation_type_fireworks),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (swipeSelectionAnimationType == "fireworks") FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // Animation speed slider
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.animation_speed_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    var animationSpeed by remember {
                        mutableStateOf(SettingsManager.getSwipeSelectionAnimationSpeed(context))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.animation_speed_slow),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = animationSpeed,
                            onValueChange = { newValue ->
                                animationSpeed = newValue
                                SettingsManager.setSwipeSelectionAnimationSpeed(context, newValue)
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 5,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.animation_speed_fast),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "%.1fx".format(animationSpeed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )

                    // Animation color selector (only for flying animation)
                    if (swipeSelectionAnimationType == "flying") {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.animation_color_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        var animationColor by remember {
                            mutableStateOf(SettingsManager.getSwipeSelectionAnimationColor(context))
                        }

                        // Color options
                        val colorOptions = listOf(
                            "auto" to null,  // null means auto (follows dark/light mode)
                            "white" to androidx.compose.ui.graphics.Color.White,
                            "black" to androidx.compose.ui.graphics.Color.Black,
                            "red" to androidx.compose.ui.graphics.Color.Red,
                            "orange" to androidx.compose.ui.graphics.Color(0xFFFF9800),
                            "yellow" to androidx.compose.ui.graphics.Color.Yellow,
                            "green" to androidx.compose.ui.graphics.Color(0xFF4CAF50),
                            "blue" to androidx.compose.ui.graphics.Color(0xFF2196F3),
                            "purple" to androidx.compose.ui.graphics.Color(0xFF9C27B0),
                            "pink" to androidx.compose.ui.graphics.Color(0xFFE91E63)
                        )

                        // First row: auto, white, black, red, orange
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            colorOptions.take(5).forEach { (colorName, color) ->
                                ColorOptionCircle(
                                    colorName = colorName,
                                    color = color,
                                    isSelected = animationColor == colorName,
                                    onClick = {
                                        animationColor = colorName
                                        SettingsManager.setSwipeSelectionAnimationColor(context, colorName)
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Second row: yellow, green, blue, purple, pink
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            colorOptions.drop(5).forEach { (colorName, color) ->
                                ColorOptionCircle(
                                    colorName = colorName,
                                    color = color,
                                    isSelected = animationColor == colorName,
                                    onClick = {
                                        animationColor = colorName
                                        SettingsManager.setSwipeSelectionAnimationColor(context, colorName)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Overlay permission for full-screen animation (only shown when animation is enabled)
        if (swipeSelectionAnimationEnabled) {
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                            text = stringResource(R.string.overlay_permission_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (overlayPermissionGranted) {
                                stringResource(R.string.overlay_permission_granted)
                            } else {
                                stringResource(R.string.overlay_permission_description)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overlayPermissionGranted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (overlayPermissionGranted) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Button(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(stringResource(R.string.grant_permission))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Page Flip Mode Toggle
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
                            text = stringResource(R.string.page_flip_mode_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.page_flip_mode_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = pageFlipModeEnabled,
                        onCheckedChange = { enabled ->
                            pageFlipModeEnabled = enabled
                            SettingsManager.setPageFlipModeEnabled(context, enabled)
                            // When page flip mode is enabled, disable split swipe down as it's not relevant
                            if (enabled) {
                                splitSwipeDownEnabled = false
                                SettingsManager.setSplitSwipeDownEnabled(context, false)
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Split Swipe Down Toggle (only shown when page flip mode is disabled)
        if (!pageFlipModeEnabled) {
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

/**
 * Composable for a color option circle in the animation color picker.
 */
@Composable
private fun ColorOptionCircle(
    colorName: String,
    color: androidx.compose.ui.graphics.Color?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    if (color != null) {
                        color
                    } else {
                        // Auto: show a gradient-like appearance
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(
                                androidx.compose.ui.graphics.Color.White,
                                androidx.compose.ui.graphics.Color.Black
                            )
                        ).let { brush ->
                            androidx.compose.ui.graphics.Color.Gray
                        }
                    }
                )
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = androidx.compose.foundation.shape.CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // For "auto", show a special indicator
            if (color == null) {
                Text(
                    text = "A",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(
                when (colorName) {
                    "auto" -> R.string.animation_color_auto
                    "white" -> R.string.animation_color_white
                    "black" -> R.string.animation_color_black
                    "red" -> R.string.animation_color_red
                    "orange" -> R.string.animation_color_orange
                    "yellow" -> R.string.animation_color_yellow
                    "green" -> R.string.animation_color_green
                    "blue" -> R.string.animation_color_blue
                    "purple" -> R.string.animation_color_purple
                    "pink" -> R.string.animation_color_pink
                    else -> R.string.animation_color_auto
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
