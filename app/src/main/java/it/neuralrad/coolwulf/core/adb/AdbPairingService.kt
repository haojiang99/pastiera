package it.neuralrad.coolwulf.core.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import it.neuralrad.coolwulf.R
import it.neuralrad.coolwulf.SettingsManager
import kotlinx.coroutines.*

/**
 * Foreground service that shows a notification for ADB pairing.
 * The user can enter the pairing code directly from the notification
 * while staying in Developer Settings, avoiding the code expiration issue.
 */
class AdbPairingService : Service() {

    companion object {
        private const val TAG = "AdbPairingService"
        private const val CHANNEL_ID = "adb_pairing_channel"
        private const val NOTIFICATION_ID = 9527

        const val ACTION_START_PAIRING = "it.neuralrad.coolwulf.START_ADB_PAIRING"
        const val ACTION_SUBMIT_CODE = "it.neuralrad.coolwulf.SUBMIT_PAIRING_CODE"
        const val ACTION_CANCEL_PAIRING = "it.neuralrad.coolwulf.CANCEL_PAIRING"
        const val ACTION_PAIRING_RESULT = "it.neuralrad.coolwulf.PAIRING_RESULT"

        const val EXTRA_PAIRING_PORT = "pairing_port"
        const val EXTRA_CONNECTION_PORT = "connection_port"
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val EXTRA_RESULT_SUCCESS = "result_success"
        const val EXTRA_RESULT_MESSAGE = "result_message"

        const val KEY_PAIRING_CODE_INPUT = "key_pairing_code_input"

        fun start(context: Context, pairingPort: Int, connectionPort: Int) {
            val intent = Intent(context, AdbPairingService::class.java).apply {
                action = ACTION_START_PAIRING
                putExtra(EXTRA_PAIRING_PORT, pairingPort)
                putExtra(EXTRA_CONNECTION_PORT, connectionPort)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AdbPairingService::class.java))
        }
    }

    private var pairingPort: Int = 0
    private var connectionPort: Int = 0
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val codeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SUBMIT_CODE) {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val input = remoteInput?.getCharSequence(KEY_PAIRING_CODE_INPUT)?.toString()
                    ?: intent.getStringExtra(EXTRA_PAIRING_CODE)

                // Parse input - format should be "PORT:CODE" e.g. "42021:755022"
                // Or just "CODE" if port was already provided (6 digits only)
                if (!input.isNullOrEmpty()) {
                    val parts = input.split(":")
                    when {
                        // Format: PORT:CODE (e.g., "42021:755022")
                        parts.size == 2 && parts[0].all { it.isDigit() } && parts[1].length == 6 -> {
                            val port = parts[0].toIntOrNull()
                            val code = parts[1]
                            if (port != null && port > 0) {
                                pairingPort = port  // Update the pairing port
                                handlePairingCode(code)
                            } else {
                                updateNotification(getString(R.string.adb_pairing_invalid_format))
                            }
                        }
                        // Format: Just 6-digit code (use existing port)
                        input.length == 6 && input.all { it.isDigit() } -> {
                            if (pairingPort > 0) {
                                handlePairingCode(input)
                            } else {
                                updateNotification(getString(R.string.adb_pairing_need_port))
                            }
                        }
                        else -> {
                            updateNotification(getString(R.string.adb_pairing_invalid_format))
                        }
                    }
                } else {
                    updateNotification(getString(R.string.adb_pairing_invalid_format))
                }
            } else if (intent?.action == ACTION_CANCEL_PAIRING) {
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(ACTION_SUBMIT_CODE)
            addAction(ACTION_CANCEL_PAIRING)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(codeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(codeReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(codeReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PAIRING -> {
                pairingPort = intent.getIntExtra(EXTRA_PAIRING_PORT, 0)
                connectionPort = intent.getIntExtra(EXTRA_CONNECTION_PORT, 0)
                startForeground(NOTIFICATION_ID, createPairingNotification())
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.adb_pairing_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.adb_pairing_notification_channel_desc)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createPairingNotification(): Notification {
        // Remote input for entering the pairing code
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE_INPUT)
            .setLabel(getString(R.string.adb_pairing_enter_code))
            .build()

        // Submit action with remote input
        val submitIntent = Intent(ACTION_SUBMIT_CODE).apply {
            setPackage(packageName)
        }
        val submitPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            submitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val submitAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            getString(R.string.adb_pairing_submit),
            submitPendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()

        // Cancel action
        val cancelIntent = Intent(ACTION_CANCEL_PAIRING).apply {
            setPackage(packageName)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.adb_pairing_cancel),
            cancelPendingIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keyboard_24)
            .setContentTitle(getString(R.string.adb_pairing_notification_title))
            .setContentText(getString(R.string.adb_pairing_notification_text))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(getString(R.string.adb_pairing_notification_instructions)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(submitAction)
            .addAction(cancelAction)
            .build()
    }

    private fun updateNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keyboard_24)
            .setContentTitle(getString(R.string.adb_pairing_notification_title))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun handlePairingCode(code: String) {
        updateNotification(getString(R.string.adb_pairing_in_progress))

        serviceScope.launch {
            try {
                val embeddedAdb = EmbeddedADB.getInstance(this@AdbPairingService)

                val pairSuccess = embeddedAdb.pair(pairingPort, code)

                if (pairSuccess) {
                    updateNotification(getString(R.string.adb_pairing_success_connecting))

                    // Save that we're paired
                    SettingsManager.setEmbeddedAdbPaired(this@AdbPairingService, true)
                    SettingsManager.setEmbeddedAdbPort(this@AdbPairingService, connectionPort)

                    // Try to connect
                    val connectSuccess = embeddedAdb.connect(connectionPort)

                    // Broadcast result
                    val resultIntent = Intent(ACTION_PAIRING_RESULT).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_RESULT_SUCCESS, connectSuccess)
                        putExtra(EXTRA_RESULT_MESSAGE, if (connectSuccess) "Connected!" else "Paired but connection failed")
                    }
                    sendBroadcast(resultIntent)

                    withContext(Dispatchers.Main) {
                        updateNotification(
                            if (connectSuccess) getString(R.string.adb_pairing_complete)
                            else getString(R.string.adb_pairing_success_connect_failed)
                        )
                        delay(2000)
                        stopSelf()
                    }
                } else {
                    val resultIntent = Intent(ACTION_PAIRING_RESULT).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_RESULT_SUCCESS, false)
                        putExtra(EXTRA_RESULT_MESSAGE, "Pairing failed")
                    }
                    sendBroadcast(resultIntent)

                    withContext(Dispatchers.Main) {
                        updateNotification(getString(R.string.adb_pairing_failed_retry))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing error", e)
                withContext(Dispatchers.Main) {
                    updateNotification("Error: ${e.message}")
                }
            }
        }
    }
}
