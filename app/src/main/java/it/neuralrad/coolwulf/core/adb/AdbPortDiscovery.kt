package it.neuralrad.coolwulf.core.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Discovers the ADB wireless debugging ports using mDNS/DNS-SD.
 *
 * Android broadcasts two service types:
 * - "_adb-tls-connect._tcp": The connection port (always available when wireless debugging is on)
 * - "_adb-tls-pairing._tcp": The pairing port (only available when "Pair device with pairing code" dialog is open)
 */
class AdbPortDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "AdbPortDiscovery"
        private const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp"
        private const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp"
        private const val DISCOVERY_TIMEOUT_MS = 8000L
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val discoveredPort = AtomicInteger(0)
    private val isDiscovering = AtomicBoolean(false)
    private val pendingServices = CopyOnWriteArrayList<NsdServiceInfo>()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Get the device's local IP address on the current Wi-Fi network.
     */
    fun getLocalIpAddress(): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val inetAddress = addresses.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }

        return null
    }

    /**
     * Discover the ADB connection port using mDNS.
     * This suspends until a port is found or timeout occurs.
     *
     * @return The discovered port, or null if discovery failed
     */
    suspend fun discoverPort(): Int? = suspendCoroutine { continuation ->
        if (isDiscovering.getAndSet(true)) {
            Log.w(TAG, "Discovery already in progress")
            continuation.resume(null)
            return@suspendCoroutine
        }

        discoveredPort.set(0)
        pendingServices.clear()

        val localIp = getLocalIpAddress()
        Log.d(TAG, "Local IP: $localIp")

        var hasResumed = false
        val resumeOnce: (Int?) -> Unit = { port ->
            if (!hasResumed) {
                hasResumed = true
                stopDiscovery()
                continuation.resume(port)
            }
        }

        // Timeout handler
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!hasResumed) {
                Log.d(TAG, "Discovery timeout, returning best port: ${discoveredPort.get()}")
                val port = discoveredPort.get().takeIf { it > 0 }
                resumeOnce(port)
            }
        }, DISCOVERY_TIMEOUT_MS)

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                pendingServices.add(service)
                resolveService(service, localIp) { port ->
                    if (port != null && port > 0) {
                        discoveredPort.set(port)
                        Log.d(TAG, "Found valid ADB port: $port")
                        // Don't resume immediately - wait for timeout to get best port
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                pendingServices.removeIf { it.serviceName == service.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped: $serviceType")
                isDiscovering.set(false)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                isDiscovering.set(false)
                resumeOnce(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
                isDiscovering.set(false)
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE_CONNECT, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            isDiscovering.set(false)
            resumeOnce(null)
        }
    }

    /**
     * Discover the ADB pairing port using mDNS.
     * The pairing port is only broadcast when the "Pair device with pairing code" dialog is open.
     *
     * @return The discovered pairing port, or null if not found
     */
    suspend fun discoverPairingPort(): Int? = suspendCoroutine { continuation ->
        if (isDiscovering.getAndSet(true)) {
            Log.w(TAG, "Discovery already in progress")
            continuation.resume(null)
            return@suspendCoroutine
        }

        discoveredPort.set(0)
        pendingServices.clear()

        val localIp = getLocalIpAddress()
        Log.d(TAG, "Discovering pairing port, local IP: $localIp")

        var hasResumed = false
        val resumeOnce: (Int?) -> Unit = { port ->
            if (!hasResumed) {
                hasResumed = true
                stopDiscovery()
                continuation.resume(port)
            }
        }

        // Timeout handler - pairing discovery should be quick
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!hasResumed) {
                Log.d(TAG, "Pairing port discovery timeout, returning: ${discoveredPort.get()}")
                val port = discoveredPort.get().takeIf { it > 0 }
                resumeOnce(port)
            }
        }, DISCOVERY_TIMEOUT_MS)

        val pairingDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Pairing port discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Pairing service found: ${service.serviceName}")
                pendingServices.add(service)
                resolveService(service, localIp) { port ->
                    if (port != null && port > 0) {
                        discoveredPort.set(port)
                        Log.d(TAG, "Found valid ADB pairing port: $port")
                        // Resume immediately for pairing port since we want it ASAP
                        resumeOnce(port)
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Pairing service lost: ${service.serviceName}")
                pendingServices.removeIf { it.serviceName == service.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Pairing port discovery stopped: $serviceType")
                isDiscovering.set(false)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Pairing port discovery start failed: $errorCode")
                isDiscovering.set(false)
                resumeOnce(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Pairing port discovery stop failed: $errorCode")
                isDiscovering.set(false)
            }
        }

        // Store as our discovery listener so stopDiscovery() works
        discoveryListener = pairingDiscoveryListener

        try {
            nsdManager.discoverServices(SERVICE_TYPE_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingDiscoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pairing port discovery", e)
            isDiscovering.set(false)
            resumeOnce(null)
        }
    }

    /**
     * Resolve a discovered service to get its port
     */
    private fun resolveService(service: NsdServiceInfo, localIp: String?, callback: (Int?) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                pendingServices.removeIf { it.serviceName == serviceInfo.serviceName }

                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    // Retry after a short delay
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        resolveService(serviceInfo, localIp, callback)
                    }, 500)
                } else {
                    callback(null)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Resolved: ${serviceInfo.serviceName} -> ${serviceInfo.host}:${serviceInfo.port}")
                pendingServices.removeIf { it.serviceName == serviceInfo.serviceName }

                // Check if this is our device by comparing IP
                val serviceIp = serviceInfo.host?.hostAddress
                if (localIp != null && serviceIp != null && serviceIp != localIp) {
                    Log.d(TAG, "Service IP $serviceIp doesn't match device IP $localIp, skipping")
                    callback(null)
                    return
                }

                if (serviceInfo.port > 0) {
                    callback(serviceInfo.port)
                } else {
                    callback(null)
                }
            }
        }

        try {
            nsdManager.resolveService(service, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve service", e)
            callback(null)
        }
    }

    /**
     * Stop any ongoing discovery
     */
    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
        discoveryListener = null
        isDiscovering.set(false)
    }
}
