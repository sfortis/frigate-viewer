package com.asksakis.freegate.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Utility class to retrieve and manage WiFi networks for Android 13+.
 * Uses coroutines and modern APIs for better performance.
 */
class WifiNetworkManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiNetworkManager"
        
        // String extension to remove quotes from SSID
        fun String.removeSurroundingQuotes(): String {
            return if (startsWith("\"") && endsWith("\"")) {
                substring(1, length - 1)
            } else {
                this
            }
        }
    }
    
    // CoroutineScope to replace GlobalScope (which is a delicate API)
    private val wifiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Cache for performance
    private var cachedSsid: String? = null
    private var lastCacheTime = 0L
    private val CACHE_DURATION = 3000L // 3 seconds
    
    // LiveData for observers
    private val _ssidData = MutableLiveData<String?>()
    val ssidData: LiveData<String?> = _ssidData
    
    // Network callback for active monitoring
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    init {
        // Start monitoring in the init block
        startNetworkMonitoring()
    }
    
    /**
     * Gets the current connected WiFi network SSID using coroutines
     * This is the recommended modern approach for Android 13+
     */
    suspend fun getWifiSsidAsync(): String? = withContext(Dispatchers.IO) {
        try {
            // Check cache first for performance
            val now = System.currentTimeMillis()
            if (cachedSsid != null && (now - lastCacheTime < CACHE_DURATION)) {
                return@withContext cachedSsid
            }
            
            // Use the modern approach with suspendCancellableCoroutine
            val result = getSsidWithCoroutine()
            
            // Update cache if we got a valid result
            if (!result.isNullOrEmpty() && result != "<unknown ssid>") {
                cachedSsid = result
                lastCacheTime = System.currentTimeMillis()
                _ssidData.postValue(result)
            }
            
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Error getting SSID: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Synchronous version that launches a coroutine internally
     * Use this from non-coroutine code
     */
    fun getCurrentSsid(): String {
        // For immediate results, try direct approach first
        val directSsid = getDirectSsid()
        if (!directSsid.isNullOrEmpty() && directSsid != "<unknown ssid>") {
            cachedSsid = directSsid
            lastCacheTime = System.currentTimeMillis()
            _ssidData.postValue(directSsid)
            return directSsid
        }
        
        // Launch coroutine to get SSID in background
        wifiScope.launch {
            try {
                val ssid = getWifiSsidAsync()
                if (!ssid.isNullOrEmpty()) {
                    _ssidData.postValue(ssid)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting SSID in background: ${e.message}")
            }
        }
        
        // Return cached value or empty string
        return cachedSsid ?: ""
    }
    
    /**
     * Uses the most direct approach to get SSID with minimal API dependencies
     */
    private fun getDirectSsid(): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                return null
            }
            
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            
            if (wifiInfo == null || wifiInfo.networkId == -1) {
                return null
            }
            
            @Suppress("DEPRECATION")
            var ssid = wifiInfo.ssid
            
            // Clean up SSID
            ssid = ssid.removeSurroundingQuotes()
            
            Log.d(TAG, "Direct SSID detection result: $ssid")
            return if (ssid == "<unknown ssid>" || ssid.isEmpty()) null else ssid
        } catch (e: Exception) {
            Log.e(TAG, "Error in getDirectSsid: ${e.message}")
            return null
        }
    }
    
    /**
     * Start monitoring network changes to proactively update SSID information
     */
    private fun startNetworkMonitoring() {
        if (networkCallback != null) return
        
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
                
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
                
            val callback = getNetworkCallback()
            
            networkCallback = callback
            
            // Register the callback
            connectivityManager.registerNetworkCallback(request, callback)
            
            // Also trigger an immediate check
            wifiScope.launch {
                refreshSsid()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting network monitoring: ${e.message}")
        }
    }
    
    /**
     * Stop monitoring when no longer needed
     */
    fun stopNetworkMonitoring() {
        try {
            networkCallback?.let {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                connectivityManager?.unregisterNetworkCallback(it)
                networkCallback = null
            }
            wifiScope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping network monitoring: ${e.message}")
        }
    }
    
    /**
     * Create appropriate NetworkCallback based on Android version
     */
    private fun getNetworkCallback(): ConnectivityManager.NetworkCallback {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                // For Android 12+, use FLAG_INCLUDE_LOCATION_INFO
                object : ConnectivityManager.NetworkCallback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
                    else 0
                ) {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            extractSsidFromCapabilities(capabilities)
                        }
                    }
                    
                    override fun onLost(network: Network) {
                        // Clear SSID when WiFi disconnects
                        _ssidData.postValue("")
                    }
                }
            }
            else -> {
                // For older Android versions
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            // For older versions, use WifiManager
                            wifiScope.launch {
                                val ssid = getLegacySsid()
                                if (!ssid.isNullOrEmpty()) {
                                    cachedSsid = ssid
                                    lastCacheTime = System.currentTimeMillis()
                                    _ssidData.postValue(ssid)
                                }
                            }
                        }
                    }
                    
                    override fun onLost(network: Network) {
                        // Clear SSID when WiFi disconnects
                        _ssidData.postValue("")
                    }
                }
            }
        }
    }
    
    /**
     * Extract SSID from NetworkCapabilities and update cache
     */
    private fun extractSsidFromCapabilities(capabilities: NetworkCapabilities) {
        try {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val transportInfo = capabilities.transportInfo
                if (transportInfo is WifiInfo) {
                    @Suppress("DEPRECATION")
                    val ssid = transportInfo.ssid?.replace("\"", "")
                    
                    if (ssid != null && ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                        // Update cache
                        cachedSsid = ssid
                        lastCacheTime = System.currentTimeMillis()
                        _ssidData.postValue(ssid)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting SSID: ${e.message}")
        }
    }
    
    /**
     * Refresh SSID information forcefully
     */
    fun refreshSsid() {
        // Force cache expiration
        lastCacheTime = 0L
        
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
                
            val activeNetwork = connectivityManager.activeNetwork ?: return
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return
            
            // If connected to WiFi, extract SSID
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    extractSsidFromCapabilities(capabilities)
                } else {
                    val ssid = getLegacySsid()
                    if (!ssid.isNullOrEmpty()) {
                        cachedSsid = ssid
                        lastCacheTime = System.currentTimeMillis()
                        _ssidData.postValue(ssid)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing SSID: ${e.message}")
        }
    }
    
    /**
     * Modern approach using coroutines as shown in the example
     * This wraps the callback pattern in a coroutine for better usability
     */
    private suspend fun getSsidWithCoroutine(): String? = suspendCancellableCoroutine { continuation ->
        try {
            // First, check for both location and nearby devices permissions for best compatibility
            val hasNearbyPermission = context.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) == 
                PackageManager.PERMISSION_GRANTED
            val hasLocationPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == 
                PackageManager.PERMISSION_GRANTED
                
            Log.d(TAG, "NEARBY_WIFI_DEVICES permission: $hasNearbyPermission, LOCATION permission: $hasLocationPermission")
            
            if (!hasNearbyPermission && !hasLocationPermission) {
                Log.e(TAG, "Missing both NEARBY_WIFI_DEVICES and LOCATION permission - cannot get SSID")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            // Trick: Try to get direct SSID from WifiManager first (without callbacks)
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val connInfo = wifiManager.connectionInfo
                if (connInfo != null) {
                    @Suppress("DEPRECATION")
                    val directSsid = connInfo.ssid?.removeSurrounding("\"")
                    Log.d(TAG, "Direct WifiManager SSID attempt: $directSsid")
                    
                    if (directSsid != null && directSsid.isNotEmpty() && directSsid != "<unknown ssid>") {
                        Log.d(TAG, "Successfully got direct SSID: $directSsid")
                        continuation.resume(directSsid)
                        return@suspendCancellableCoroutine
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting direct SSID: ${e.message}")
            }
            
            // Next, build a more precise network request
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)  // Include all networks
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Include networks without internet
                .build()
                
            Log.d(TAG, "Starting new WiFi network request with enhanced settings")
            
            // Using different callbacks based on API level
            val networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                object : ConnectivityManager.NetworkCallback(
                    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
                ) {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            try {
                                val wifiInfo = capabilities.transportInfo as? WifiInfo
                                Log.d(TAG, "Got transportInfo: ${wifiInfo != null}")
                                
                                if (wifiInfo == null) {
                                    Log.e(TAG, "transportInfo is not WifiInfo - cannot get SSID")
                                    return
                                }
                                
                                val rawSsid = wifiInfo.ssid
                                Log.d(TAG, "Raw SSID from transportInfo: $rawSsid")
                                
                                val ssid = rawSsid?.removeSurrounding("\"")
                                Log.d(TAG, "Processed SSID: $ssid")
                                
                                if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") {
                                    connectivityManager.unregisterNetworkCallback(this)
                                    if (continuation.isActive) {
                                        Log.d(TAG, "Successfully retrieved SSID: $ssid")
                                        continuation.resume(ssid)
                                    }
                                } else {
                                    Log.e(TAG, "Got null, empty or <unknown ssid> from WifiInfo")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error extracting SSID from capabilities: ${e.message}")
                            }
                        }
                    }
                    
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.d(TAG, "Network available, attempting to get SSID directly")
                        
                        // Try to bind to network to get better SSID access
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                connectivityManager.bindProcessToNetwork(network)
                            }
                            
                            // Get capabilities of this specific network
                            val capabilities = connectivityManager.getNetworkCapabilities(network)
                            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                                val wifiInfo = capabilities.transportInfo as? WifiInfo
                                if (wifiInfo != null) {
                                    val ssid = wifiInfo.ssid?.removeSurrounding("\"")
                                    Log.d(TAG, "onAvailable SSID: $ssid")
                                    
                                    if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") {
                                        Log.d(TAG, "Successfully got SSID in onAvailable: $ssid")
                                        connectivityManager.unregisterNetworkCallback(this)
                                        if (continuation.isActive) {
                                            continuation.resume(ssid)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error getting SSID in onAvailable: ${e.message}")
                        } finally {
                            // Always unbind from network
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                connectivityManager.bindProcessToNetwork(null)
                            }
                        }
                    }
                    
                    override fun onUnavailable() {
                        Log.d(TAG, "Network unavailable in coroutine")
                        connectivityManager.unregisterNetworkCallback(this)
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                    
                    override fun onLost(network: Network) {
                        Log.d(TAG, "Network lost in coroutine")
                        connectivityManager.unregisterNetworkCallback(this)
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            } else {
                // For older Android versions
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            try {
                                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                                @Suppress("DEPRECATION")
                                val wifiInfo = wifiManager.connectionInfo
                                
                                if (wifiInfo != null) {
                                    @Suppress("DEPRECATION")
                                    val ssid = wifiInfo.ssid?.removeSurrounding("\"")
                                    Log.d(TAG, "Got SSID from legacy implementation: $ssid")
                                    
                                    if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") {
                                        connectivityManager.unregisterNetworkCallback(this)
                                        if (continuation.isActive) {
                                            continuation.resume(ssid)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error getting SSID in legacy implementation: ${e.message}")
                            }
                        }
                    }
                    
                    override fun onUnavailable() {
                        Log.d(TAG, "Network unavailable in legacy implementation")
                        connectivityManager.unregisterNetworkCallback(this)
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                    
                    override fun onLost(network: Network) {
                        Log.d(TAG, "Network lost in legacy implementation")
                        connectivityManager.unregisterNetworkCallback(this)
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            }
            
            // Check active connection first (can sometimes work directly)
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    // Try to bind to network
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        connectivityManager.bindProcessToNetwork(activeNetwork)
                    }
                    
                    val wifiInfo = capabilities.transportInfo as? WifiInfo
                    val ssid = wifiInfo?.ssid?.removeSurrounding("\"")
                    
                    Log.d(TAG, "Coroutine immediate SSID: $ssid")
                    
                    if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") {
                        continuation.resume(ssid)
                        return@suspendCancellableCoroutine
                    }
                    
                    // Unbind from network
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        connectivityManager.bindProcessToNetwork(null)
                    }
                }
            }
            
            // Register for network events with timeout
            try {
                connectivityManager.requestNetwork(networkRequest, networkCallback, 5000) // 5 second timeout
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting network with timeout: ${e.message}")
                // Fallback to standard request
                connectivityManager.requestNetwork(networkRequest, networkCallback)
            }
            
            // Make sure we clean up when cancelled
            continuation.invokeOnCancellation {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    connectivityManager.bindProcessToNetwork(null) // Ensure we unbind
                }
            }
            
            // Set a longer timeout as some devices are slow to respond
            wifiScope.launch {
                try {
                    withTimeout(8000) { // 8 second timeout (longer to make sure we get a response)
                        // This just waits - the continuation will be resumed by the callback
                    }
                } catch (e: Exception) {
                    // Timeout occurred
                    connectivityManager.unregisterNetworkCallback(networkCallback)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        connectivityManager.bindProcessToNetwork(null) // Ensure we unbind
                    }
                    if (continuation.isActive) {
                        Log.d(TAG, "Coroutine timeout getting SSID")
                        continuation.resume(null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getSsidWithCoroutine: ${e.message}")
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }
    }
    
    /**
     * Get SSID using legacy methods for older Android versions
     */
    @Suppress("DEPRECATION")
    private fun getLegacySsid(): String? {
        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo?.ssid?.replace("\"", "")
            if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") null else ssid
        } catch (e: Exception) {
            Log.e(TAG, "Error getting legacy SSID: ${e.message}")
            null
        }
    }
    
    /**
     * Gets a list of WiFi networks from the system.
     * On Android 10+, this attempts alternate methods as direct configuration access is restricted.
     */
    fun getSavedNetworks(): List<String> {
        // Combine results from multiple sources
        val networks = mutableSetOf<String>()
        
        try {
            // Try method for Android 9 and below
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return emptyList()
                    
                @Suppress("DEPRECATION")
                val configuredNetworks = wifiManager.configuredNetworks
                    ?: emptyList()
                    
                // Add to results
                networks.addAll(
                    configuredNetworks
                        .mapNotNull { config ->
                            @Suppress("DEPRECATION")
                            config.SSID?.replace("\"", "")
                        }
                        .filter { it.isNotEmpty() }
                )
            }
            
            // For Android 10+, get current connected network if available
            val currentSsid = getCurrentSsid()
            if (currentSsid.isNotEmpty()) {
                networks.add(currentSsid)
            }
            
            // Try to read from system database directly
            // This is a fallback method that may not work on all devices
            readSystemNetworkList().forEach { networks.add(it) }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in getSavedNetworks: ${e.message}")
        }
        
        return networks.toList().sorted()
    }
    
    /**
     * Attempts to read WiFi networks from the system database.
     * This is an experimental method that may not work on all devices.
     */
    private fun readSystemNetworkList(): List<String> {
        val networks = mutableListOf<String>()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10+, we need to use a content provider or settings
                // Attempt to read network suggestion histories from settings
                // This is experimental and may not work on all devices
                
                // Extract WiFi network names directly from Android settings data
                val contentProvider = android.provider.Settings.Global.getString(
                    context.contentResolver, 
                    "wifi_networks_available_notification_on"
                )
                
                if (!contentProvider.isNullOrEmpty()) {
                    Log.d(TAG, "Found settings data: $contentProvider")
                    // This might contain network data on some devices
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading system network list: ${e.message}")
        }
        
        return networks
    }
    
    /**
     * Gets a list of network names for WiFi selection.
     * This is a combination of saved networks, current network, and some common network names.
     */
    fun getNetworkSelectionList(): List<String> {
        val networks = mutableSetOf<String>()
        
        // Add saved networks
        networks.addAll(getSavedNetworks())
        
        // Add current network
        val current = getCurrentSsid()
        if (current.isNotEmpty()) {
            networks.add(current)
        }
        
        // Add common default network names that users might have
        val commonNetworks = listOf(
            "Home", "WiFi", "MyWifi", "HomeNetwork", 
            "Family WiFi", "Default", "Home WiFi"
        )
        
        // Add common networks to the list
        networks.addAll(commonNetworks)
        
        // Return all unique networks sorted alphabetically
        return networks.toList().sorted()
    }
}