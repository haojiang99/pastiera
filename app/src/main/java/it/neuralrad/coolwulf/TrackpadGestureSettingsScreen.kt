package it.neuralrad.coolwulf

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

/**
 * Settings screen for trackpad gesture suggestions.
 * Allows users to enable/disable Shizuku-based trackpad swipe gestures
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
    var showTutorialDialog by remember { mutableStateOf(false) }

    // Check Shizuku status and permission
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuPermissionGranted by remember { mutableStateOf(false) }
    var permissionCheckTrigger by remember { mutableStateOf(0) }

    // Permission result listener
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                shizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
                Log.d("TrackpadGestures", "Shizuku permission result: $shizukuPermissionGranted")
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    LaunchedEffect(permissionCheckTrigger) {
        try {
            shizukuRunning = Shizuku.pingBinder()
            if (shizukuRunning) {
                shizukuPermissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                Log.d("TrackpadGestures", "Shizuku running: $shizukuRunning, permission: $shizukuPermissionGranted")
            }
        } catch (e: Exception) {
            shizukuRunning = false
            shizukuPermissionGranted = false
            Log.e("TrackpadGestures", "Error checking Shizuku: ${e.message}")
        }
    }

    // Function to request Shizuku permission
    fun requestShizukuPermission() {
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.e("TrackpadGestures", "Error requesting Shizuku permission: ${e.message}")
        }
    }

    BackHandler { onBack() }

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

        // Shizuku Status Card - shows both running and permission status
        val shizukuReady = shizukuRunning && shizukuPermissionGranted
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = when {
                shizukuReady -> MaterialTheme.colorScheme.primaryContainer
                shizukuRunning && !shizukuPermissionGranted -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.errorContainer
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
                        imageVector = if (shizukuReady) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = null,
                        tint = when {
                            shizukuReady -> MaterialTheme.colorScheme.onPrimaryContainer
                            shizukuRunning -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = when {
                            shizukuReady -> stringResource(R.string.trackpad_gestures_shizuku_connected)
                            shizukuRunning && !shizukuPermissionGranted -> stringResource(R.string.trackpad_gestures_shizuku_no_permission)
                            else -> stringResource(R.string.trackpad_gestures_shizuku_not_running)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            shizukuReady -> MaterialTheme.colorScheme.onPrimaryContainer
                            shizukuRunning -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Show "Grant Permission" button if Shizuku is running but permission not granted
                if (shizukuRunning && !shizukuPermissionGranted) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            requestShizukuPermission()
                            // Trigger a permission check after a short delay
                            permissionCheckTrigger++
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.trackpad_gestures_grant_permission))
                    }
                }
            }
        }

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
                    enabled = shizukuReady
                )
            }
        }

        // Swipe Threshold Slider (only show if enabled)
        if (trackpadGesturesEnabled && shizukuReady) {
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
                        steps = 24
                    )
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

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

                // 3-zone visualization - Juying layout: 2-1-3 (center is best)
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
                        Text("2", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
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
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("3", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }

                Text(
                    text = "English: 3 suggestions",
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

                // 5-zone visualization - Juying layout: 2-3-1-4-5 (center is best)
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

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // Install Shizuku Button
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable {
                    val url = context.getString(R.string.trackpad_gestures_shizuku_url)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
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
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.trackpad_gestures_install_shizuku),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Text(
                        text = stringResource(R.string.trackpad_gestures_install_shizuku_description),
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

        // Shizuku Setup Instructions
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.trackpad_gestures_shizuku_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.trackpad_gestures_shizuku_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
