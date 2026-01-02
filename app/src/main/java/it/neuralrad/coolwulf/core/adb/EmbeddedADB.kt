package it.neuralrad.coolwulf.core.adb

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/**
 * Embedded ADB client for running shell commands with ADB privileges.
 * Uses the bundled libadb.so binary and connects via Android's Wireless Debugging feature.
 *
 * This eliminates the need for external apps like Shizuku while providing
 * the same capability to read /dev/input devices for trackpad gesture detection.
 */
class EmbeddedADB private constructor(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddedADB"

        /** Port used by the gesture daemon for local TCP communication */
        const val GESTURE_DAEMON_PORT = 5123

        @Volatile
        private var instance: EmbeddedADB? = null

        fun getInstance(context: Context): EmbeddedADB = instance ?: synchronized(this) {
            instance ?: EmbeddedADB(context.applicationContext).also { instance = it }
        }
    }

    private val adbPath = "${context.applicationInfo.nativeLibraryDir}/libadb.so"

    // ADB home directory - where keys are stored
    private val adbHome = File(context.filesDir, "adb_home")
    private val androidDir = File(adbHome, ".android")

    // Connection state
    private var isConnected = false
    private var connectedPort: Int? = null

    // For long-running commands like getevent
    private var shellProcess: Process? = null

    init {
        // Ensure ADB home and .android directories exist
        ensureAdbDirectories()
    }

    /**
     * Ensure the ADB home and .android directories exist
     */
    private fun ensureAdbDirectories() {
        try {
            if (!adbHome.exists()) {
                adbHome.mkdirs()
                Log.d(TAG, "Created ADB home directory: ${adbHome.path}")
            }
            if (!androidDir.exists()) {
                androidDir.mkdirs()
                Log.d(TAG, "Created .android directory: ${androidDir.path}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ADB directories", e)
        }
    }

    /**
     * Check if the embedded ADB binary exists
     */
    fun isAdbAvailable(): Boolean {
        val adbFile = File(adbPath)
        val exists = adbFile.exists() && adbFile.canExecute()
        Log.d(TAG, "ADB binary available: $exists at $adbPath")
        return exists
    }

    /**
     * Check if wireless debugging is enabled in system settings
     */
    fun isWirelessDebuggingEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        } else {
            false
        }
    }

    /**
     * Get the current connection status
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Mark the connection as disconnected.
     * Called when the connection is lost (e.g., WiFi disconnected).
     */
    fun markDisconnected() {
        isConnected = false
        connectedPort = null
        stopShellProcess()
    }

    /**
     * Pair with the device using the pairing code from Wireless Debugging settings.
     * This is a one-time operation that establishes trust between the ADB client and device.
     *
     * @param port The pairing port shown in Wireless Debugging settings
     * @param pairingCode The 6-digit pairing code
     * @return true if pairing succeeded
     */
    suspend fun pair(port: Int, pairingCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Kill any existing server first
            runAdbCommand(listOf("kill-server"), timeoutSeconds = 5)
            delay(500)

            // First, start the ADB server
            runAdbCommand(listOf("start-server"), timeoutSeconds = 10)
            delay(1000)

            // Start the pairing process - send code via stdin like LADB does
            val pairProcess = createAdbProcess(listOf("pair", "localhost:$port"))

            // Use a thread to read output asynchronously
            val outputBuilder = StringBuilder()
            val errorBuilder = StringBuilder()

            val outputThread = Thread {
                try {
                    BufferedReader(InputStreamReader(pairProcess.inputStream)).forEachLine {
                        outputBuilder.append(it).append("\n")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading output", e)
                }
            }

            val errorThread = Thread {
                try {
                    BufferedReader(InputStreamReader(pairProcess.errorStream)).forEachLine {
                        errorBuilder.append(it).append("\n")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading stderr", e)
                }
            }

            outputThread.start()
            errorThread.start()

            // Wait a bit for the process to be ready for input
            delay(2000)

            // Send the pairing code via stdin
            try {
                val writer = PrintStream(pairProcess.outputStream, true)
                writer.println(pairingCode)
                writer.flush()
                pairProcess.outputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write pairing code to stdin", e)
            }

            // Wait for pairing to complete (up to 15 seconds)
            pairProcess.waitFor(15, TimeUnit.SECONDS)

            outputThread.join(1000)
            errorThread.join(1000)

            val output = outputBuilder.toString()
            val error = errorBuilder.toString()

            pairProcess.destroyForcibly()

            // Check for success - look for "Successfully paired" message
            val success = output.contains("Successfully paired", ignoreCase = true) ||
                          error.contains("Successfully paired", ignoreCase = true)

            success
        } catch (e: Exception) {
            Log.e(TAG, "Pairing failed with exception", e)
            false
        }
    }

    /**
     * Connect to the device's ADB daemon via wireless debugging.
     *
     * @param port The connection port (different from pairing port)
     * @return true if connection succeeded
     */
    suspend fun connect(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            // Start ADB server
            runAdbCommand(listOf("start-server"), timeoutSeconds = 30)
            delay(1000)

            // Connect to localhost
            val result = runAdbCommand(listOf("connect", "localhost:$port"), timeoutSeconds = 15)

            val success = result.contains("connected", ignoreCase = true) &&
                          !result.contains("failed", ignoreCase = true) &&
                          !result.contains("cannot connect", ignoreCase = true)

            if (success) {
                isConnected = true
                connectedPort = port
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed with exception", e)
            false
        }
    }

    /**
     * Disconnect from ADB
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            stopShellProcess()
            runAdbCommand(listOf("disconnect"), timeoutSeconds = 5)
            runAdbCommand(listOf("kill-server"), timeoutSeconds = 5)
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed", e)
        } finally {
            isConnected = false
            connectedPort = null
        }
    }

    /**
     * Get list of connected devices
     */
    suspend fun getDevices(): List<String> = withContext(Dispatchers.IO) {
        try {
            val output = runAdbCommand(listOf("devices"), timeoutSeconds = 5)
            output.lines()
                .filter { it.isNotBlank() && !it.contains("List of devices attached") }
                .map { it.split("\t").first().trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get devices", e)
            emptyList()
        }
    }

    /**
     * Start a long-running shell command (like getevent) and return the process.
     * The caller is responsible for reading from the process and destroying it when done.
     *
     * @param command The shell command to run
     * @return The Process object, or null if failed
     */
    fun startShellCommand(command: String): Process? {
        if (!isConnected) {
            Log.w(TAG, "Cannot run shell command: not connected")
            return null
        }

        try {
            stopShellProcess() // Stop any existing process

            shellProcess = createAdbProcess(listOf("shell", command))
            Log.d(TAG, "Started shell command: $command")
            return shellProcess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start shell command: $command", e)
            return null
        }
    }

    /**
     * Stop the current long-running shell process
     */
    fun stopShellProcess() {
        shellProcess?.let { process ->
            try {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping shell process", e)
            }
        }
        shellProcess = null
    }

    /**
     * Run a simple ADB command and return the output
     */
    private fun runAdbCommand(args: List<String>, timeoutSeconds: Long): String {
        val process = createAdbProcess(args)

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
        val error = BufferedReader(InputStreamReader(process.errorStream)).readText()

        if (!completed) {
            process.destroyForcibly()
        }

        Log.d(TAG, "ADB ${args.joinToString(" ")}: $output $error")

        return output + error
    }

    /**
     * Create an ADB process with the given arguments
     */
    private fun createAdbProcess(args: List<String>): Process {
        // Ensure directories exist before each call
        ensureAdbDirectories()

        val commandList = mutableListOf(adbPath).apply {
            addAll(args)
        }

        return ProcessBuilder(commandList)
            .directory(adbHome)
            .redirectErrorStream(false)
            .apply {
                environment().apply {
                    // Set HOME to our custom directory where .android/adbkey will be stored
                    put("HOME", adbHome.path)
                    put("TMPDIR", context.cacheDir.path)
                    // Set Android SDK root to help ADB find its config
                    put("ANDROID_SDK_HOME", adbHome.path)
                    // ADB key locations
                    put("ADB_VENDOR_KEYS", "${androidDir.path}/adbkey")
                    put("ANDROID_ADB_SERVER_PORT", "5037")
                }
            }
            .start()
    }

    /**
     * Check if ADB keys exist (have been generated from previous pairing)
     */
    fun hasAdbKeys(): Boolean {
        val keyFile = File(androidDir, "adbkey")
        val pubKeyFile = File(androidDir, "adbkey.pub")
        val exists = keyFile.exists() && pubKeyFile.exists()
        Log.d(TAG, "ADB keys exist: $exists (keyFile=${keyFile.exists()}, pubKeyFile=${pubKeyFile.exists()})")
        return exists
    }

    /**
     * Clear ADB keys to force re-pairing
     */
    fun clearAdbKeys() {
        try {
            File(androidDir, "adbkey").delete()
            File(androidDir, "adbkey.pub").delete()
            Log.d(TAG, "Cleared ADB keys")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear ADB keys", e)
        }
    }

    /**
     * Start the gesture daemon that runs with shell privileges.
     * The daemon reads /dev/input/event7 and serves events via a local TCP socket on localhost.
     * This allows gesture detection to work without WiFi after initial setup.
     *
     * The daemon listens on 127.0.0.1:GESTURE_DAEMON_PORT and the app can connect
     * to receive events without needing ADB/WiFi connectivity.
     *
     * @return true if daemon was started successfully
     */
    suspend fun startGestureDaemon(): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) {
            Log.w(TAG, "Cannot start daemon: not connected")
            return@withContext false
        }

        try {
            // First, kill any existing daemon
            runAdbCommand(listOf("shell", "pkill -f 'gesture_daemon_loop' || true"), timeoutSeconds = 3)
            delay(500)

            // Start the daemon that:
            // 1. Listens on localhost:$GESTURE_DAEMON_PORT using nc (netcat)
            // 2. Pipes getevent output to connected clients
            // 3. Runs in a loop to accept new connections after client disconnects
            //
            // The while loop with nc -l creates a server that accepts one connection at a time
            // and streams getevent output. When client disconnects, it accepts a new connection.
            // Note: Android's toybox nc uses -s for source address and -p for port
            val daemonCommand = """nohup sh -c 'while true; do getevent -l /dev/input/event7 2>/dev/null | nc -l -s 127.0.0.1 -p $GESTURE_DAEMON_PORT; sleep 0.5; done' >/dev/null 2>&1 & echo gesture_daemon_loop"""

            runAdbCommand(listOf("shell", daemonCommand), timeoutSeconds = 5)

            // Verify the daemon started by checking if port is listening
            delay(1500)
            val checkResult = runAdbCommand(listOf("shell", "netstat -tln 2>/dev/null | grep $GESTURE_DAEMON_PORT || ss -tln 2>/dev/null | grep $GESTURE_DAEMON_PORT || echo 'not listening'"), timeoutSeconds = 3)
            val daemonRunning = !checkResult.contains("not listening") && (checkResult.contains(GESTURE_DAEMON_PORT.toString()) || checkResult.contains("127.0.0.1"))

            daemonRunning
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if the gesture daemon is running by checking if it's listening on the port.
     * This check doesn't require ADB connection - we can directly try to connect to the port.
     */
    fun isGestureDaemonRunning(): Boolean {
        return try {
            // Try to connect to the daemon's port on localhost
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", GESTURE_DAEMON_PORT), 1000)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Stop the gesture daemon.
     */
    suspend fun stopGestureDaemon() = withContext(Dispatchers.IO) {
        if (!isConnected) {
            return@withContext
        }

        try {
            runAdbCommand(listOf("shell", "pkill -f 'gesture_daemon_loop' || true"), timeoutSeconds = 3)
        } catch (e: Exception) {
            // Ignore stop errors
        }
    }

    /**
     * Connect to the gesture daemon and return a socket for reading events.
     * This method connects directly to localhost, no ADB required.
     *
     * @return Socket connected to the daemon, or null if connection failed
     */
    fun connectToDaemon(): java.net.Socket? {
        return try {
            val socket = java.net.Socket()
            // Use a short timeout (1 second) to quickly fail if daemon isn't running
            socket.connect(java.net.InetSocketAddress("127.0.0.1", GESTURE_DAEMON_PORT), 1000)
            socket.soTimeout = 0 // No timeout for reading
            socket
        } catch (e: Exception) {
            null
        }
    }
}
