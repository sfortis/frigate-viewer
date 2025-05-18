package com.asksakis.freegate.utils

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Comprehensive utility class to manage network detection and URL selection.
 * Combines functionality from both NetworkUtils and NetworkFixer for simplicity.
 */
class NetworkUtils(private val context: Context) {

    companion object {
        private const val TAG = "NetworkUtils"
        private val FLAG_INCLUDE_LOCATION_INFO = 
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
    }
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiNetworkManager = WifiNetworkManager(context)
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    
    // LiveData for observers
    private val _isHomeNetwork = MutableLiveData<Boolean>()
    val isHomeNetwork: LiveData<Boolean> = _isHomeNetwork
    
    private val _currentSsid = MutableLiveData<String?>()
    val currentSsid: LiveData<String?> = _currentSsid
    
    private val _isConnected = MutableLiveData<Boolean>()
    val isConnected: LiveData<Boolean> = _isConnected
    
    // URL LiveData to allow observing URL changes
    private val _currentUrl = MutableLiveData<String?>()
    val currentUrl: LiveData<String?> = _currentUrl
    
    // Network callback for auto-refreshing on network changes
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        // Initialize with a status check and register for network changes
        checkNetworkStatus()
        registerNetworkCallback()
    }
    
    /**
     * Registers for network callbacks to automatically refresh when network changes
     * Includes logic to detect when we leave a home network
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) {
            return
        }
        
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        
        // Track previous state to detect changes    
        var wasOnWifi = false
        var wasOnHomeNetwork = false
            
        networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // For Android 12+ (API 31+), use FLAG_INCLUDE_LOCATION_INFO
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available")
                    val oldUrl = _currentUrl.value
                    checkNetworkStatus()
                    val newUrl = getAppropriateUrl()
                    _currentUrl.postValue(newUrl)
                    
                    // Log URL change for debugging
                    if (oldUrl != newUrl) {
                        Log.d(TAG, "URL changed from $oldUrl to $newUrl")
                    }
                }

                override fun onLost(network: Network) {
                    _isConnected.postValue(false)
                    _isHomeNetwork.postValue(false)
                    _currentSsid.postValue("")
                    
                    // If we were on a home network before, update URL to external
                    if (wasOnHomeNetwork) {
                        Log.d(TAG, "Lost connection to home network - switching to external URL")
                        _currentUrl.postValue(getExternalUrl())
                    }
                    
                    // Update tracking state
                    wasOnWifi = false
                    wasOnHomeNetwork = false
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    // Check for WiFi transport change
                    val isOnWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    
                    // Only process if WiFi state changed or we haven't checked yet
                    if (isOnWifi != wasOnWifi) {
                        Log.d(TAG, "WiFi state changed: $wasOnWifi -> $isOnWifi")
                        
                        // Get current URL before update
                        val oldUrl = _currentUrl.value
                        
                        // Add a small delay to allow network to configure
                        CoroutineScope(Dispatchers.IO).launch {
                            if (!isOnWifi && wasOnWifi) {
                                // WiFi just disconnected - immediate switch to external
                                Log.d(TAG, "WiFi disconnected - switching to external URL")
                                _currentUrl.postValue(getExternalUrl())
                                _isHomeNetwork.postValue(false)
                                _currentSsid.postValue("")
                                checkNetworkStatus()
                            } else if (isOnWifi && !wasOnWifi) {
                                // WiFi just connected - add delay for SSID detection
                                Log.d(TAG, "WiFi connected - waiting for network to stabilize")
                                delay(2000) // 2 second delay for better SSID detection
                                checkNetworkStatus()
                            } else if (isOnWifi && wasOnWifi) {
                                // WiFi-to-WiFi transition - add shorter delay
                                Log.d(TAG, "WiFi network changed - checking new network")
                                delay(1000) // 1 second delay
                                checkNetworkStatus()
                            }
                        }
                        
                        // Check if it's a home network after status update
                        if (isOnWifi) {
                            // WiFi connected, check if it's a home network
                            val isNowOnHomeNetwork = isHomeNetwork()
                            
                            // If home network state changed, update URL
                            if (isNowOnHomeNetwork != wasOnHomeNetwork) {
                                Log.d(TAG, "Home network state changed: $wasOnHomeNetwork -> $isNowOnHomeNetwork")
                                val newUrl = getAppropriateUrl()
                                _currentUrl.postValue(newUrl)
                                
                                // Log URL change
                                if (oldUrl != newUrl) {
                                    Log.d(TAG, "URL changed from $oldUrl to $newUrl due to network change")
                                }
                            }
                            wasOnHomeNetwork = isNowOnHomeNetwork
                        }
                        
                        // Update tracking state
                        wasOnWifi = isOnWifi
                    }
                }
            }
        } else {
            // For older versions
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available")
                    val oldUrl = _currentUrl.value
                    checkNetworkStatus()
                    val newUrl = getAppropriateUrl()
                    _currentUrl.postValue(newUrl)
                    
                    // Log URL change for debugging
                    if (oldUrl != newUrl) {
                        Log.d(TAG, "URL changed from $oldUrl to $newUrl")
                    }
                }

                override fun onLost(network: Network) {
                    _isConnected.postValue(false)
                    _isHomeNetwork.postValue(false)
                    _currentSsid.postValue("")
                    
                    // If we were on a home network before, update URL to external
                    if (wasOnHomeNetwork) {
                        Log.d(TAG, "Lost connection to home network - switching to external URL")
                        _currentUrl.postValue(getExternalUrl())
                    }
                    
                    // Update tracking state
                    wasOnWifi = false
                    wasOnHomeNetwork = false
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    // Check for WiFi transport change
                    val isOnWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    
                    // Only process if WiFi state changed or we haven't checked yet
                    if (isOnWifi != wasOnWifi) {
                        Log.d(TAG, "WiFi state changed: $wasOnWifi -> $isOnWifi")
                        
                        // Get current URL before update
                        val oldUrl = _currentUrl.value
                        
                        // Add a small delay to allow network to configure
                        CoroutineScope(Dispatchers.IO).launch {
                            if (!isOnWifi && wasOnWifi) {
                                // WiFi just disconnected - immediate switch to external
                                Log.d(TAG, "WiFi disconnected - switching to external URL")
                                _currentUrl.postValue(getExternalUrl())
                                _isHomeNetwork.postValue(false)
                                _currentSsid.postValue("")
                                checkNetworkStatus()
                            } else if (isOnWifi && !wasOnWifi) {
                                // WiFi just connected - add delay for SSID detection
                                Log.d(TAG, "WiFi connected - waiting for network to stabilize")
                                delay(2000) // 2 second delay for better SSID detection
                                checkNetworkStatus()
                            } else if (isOnWifi && wasOnWifi) {
                                // WiFi-to-WiFi transition - add shorter delay
                                Log.d(TAG, "WiFi network changed - checking new network")
                                delay(1000) // 1 second delay
                                checkNetworkStatus()
                            }
                        }
                        
                        // Check if it's a home network after status update
                        if (isOnWifi) {
                            // WiFi connected, check if it's a home network
                            val isNowOnHomeNetwork = isHomeNetwork()
                            
                            // If home network state changed, update URL
                            if (isNowOnHomeNetwork != wasOnHomeNetwork) {
                                Log.d(TAG, "Home network state changed: $wasOnHomeNetwork -> $isNowOnHomeNetwork")
                                val newUrl = getAppropriateUrl()
                                _currentUrl.postValue(newUrl)
                                
                                // Log URL change
                                if (oldUrl != newUrl) {
                                    Log.d(TAG, "URL changed from $oldUrl to $newUrl due to network change")
                                }
                            }
                            wasOnHomeNetwork = isNowOnHomeNetwork
                        }
                        
                        // Update tracking state
                        wasOnWifi = isOnWifi
                    }
                }
            }
        }
        
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        Log.d(TAG, "Registered network callback for connectivity changes")
    }
    
    fun unregisterNetworkCallback() {
        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
            networkCallback = null
        }
    }
    
    suspend fun getWifiSsidAsync(): String? {
        return wifiNetworkManager.getWifiSsidAsync()
    }
    
    /**
     * Modern approach using coroutines to get WiFi SSID
     * This function attempts to force WiFi binding for better SSID access
     */
    fun forceWifiConnection(): String? {
        try {
            android.util.Log.d("NetworkUtils", "========== FORCE WIFI CONNECTION START ==========")
            
            // First check if the device is even connected to WiFi
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val isWifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            
            if (!isWifiConnected) {
                android.util.Log.d("NetworkUtils", "Not connected to WiFi at all")
                return null
            }
            
            android.util.Log.d("NetworkUtils", "Device is connected to WiFi")
            
            // METHOD 1: Try to get WiFi info via guaranteed system settings on some devices
            try {
                // Works reliably on many Samsung, Pixel, and AOSP devices
                val wifiSsidFromManager = getWifiSsidFromLocalManager()
                if (wifiSsidFromManager != null) {
                    android.util.Log.d("NetworkUtils", "Got SSID from local manager: $wifiSsidFromManager")
                    return wifiSsidFromManager
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkUtils", "Error getting SSID from local manager: ${e.message}")
            }
            
            // METHOD 2: Try to get SSID from system advanced settings (works on many devices regardless of permissions)
            try {
                val settingsSSID = getWifiSsidFromSettings()
                if (settingsSSID != null) {
                    android.util.Log.d("NetworkUtils", "Got SSID from system settings: $settingsSSID")
                    return settingsSSID
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkUtils", "Error getting SSID from settings: ${e.message}")
            }
            
            // METHOD 3: Use the standard API (requires permissions)
            try {
                // Check if we have required permission for Android 13+
                val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) == 
                        PackageManager.PERMISSION_GRANTED
                } else {
                    context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == 
                        PackageManager.PERMISSION_GRANTED
                }
                
                android.util.Log.d("NetworkUtils", "Has required permission: $hasPermission")
                
                if (hasPermission) {
                    // Try to get WiFi info via the active network
                    // We know activeNetwork and capabilities are non-null due to earlier checks
                    // We already verified that we're connected to WiFi with hasTransport above
                    
                    // For Android 12+, try to use TransportInfo first
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val transportInfo = capabilities.transportInfo
                        android.util.Log.d("NetworkUtils", "Got transportInfo: ${transportInfo != null}")
                        if (transportInfo is WifiInfo) {
                            @Suppress("DEPRECATION")
                            val ssid = transportInfo.ssid?.removeSurrounding("\"")
                            android.util.Log.d("NetworkUtils", "Raw SSID from TransportInfo: ${transportInfo.ssid}")
                            
                            if (ssid != null && ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                                android.util.Log.d("NetworkUtils", "Got clean SSID from TransportInfo: $ssid")
                                return ssid
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkUtils", "Error getting SSID with permission methods: ${e.message}")
            }
            
            // METHOD 4: Try with WifiManager directly (deprecated but may work on some devices)
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                
                @Suppress("DEPRECATION")
                val wifiInfo = wifiManager.connectionInfo
                
                android.util.Log.d("NetworkUtils", "WifiInfo from manager: $wifiInfo")
                
                if (wifiInfo != null) {
                    // Check if we're actually connected to a network
                    if (wifiInfo.networkId != -1) {
                        @Suppress("DEPRECATION")
                        val ssid = wifiInfo.ssid?.removeSurrounding("\"")
                        
                        android.util.Log.d("NetworkUtils", "SSID from WifiManager: ${wifiInfo.ssid}")
                        
                        if (ssid != null && ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                            android.util.Log.d("NetworkUtils", "Got clean SSID from WifiManager: $ssid")
                            return ssid
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkUtils", "Error getting SSID from WifiManager: ${e.message}")
            }
            
            // METHOD 5: Try using the manual override from preferences
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val manualOverride = prefs.getString("manual_home_network", "")
                if (!manualOverride.isNullOrEmpty()) {
                    android.util.Log.d("NetworkUtils", "Using manual override SSID: $manualOverride")
                    return manualOverride
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkUtils", "Error getting manual override: ${e.message}")
            }
            
            android.util.Log.d("NetworkUtils", "========== FORCE WIFI CONNECTION END: NO SSID FOUND ==========")
            return null
        } catch (e: Exception) {
            android.util.Log.e("NetworkUtils", "Error in forceWifiConnection: ${e.message}")
            return null
        }
    }
    
    /**
     * Attempt to get WiFi SSID from system settings (works on many devices)
     */
    private fun getWifiSsidFromSettings(): String? {
        try {
            // Different methods work on different devices
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            
            // Check if WiFi is enabled
            if (!wifiManager.isWifiEnabled) {
                android.util.Log.d("NetworkUtils", "WiFi is disabled")
                return null
            }
            
            // Try to read from system settings
            // This can work even when permissions are missing on many Android devices
            val settingValue = Settings.System.getString(context.contentResolver, "wifi_ap_ssid")
            val settingValue2 = Settings.Secure.getString(context.contentResolver, "wifi_ssid")
            
            android.util.Log.d("NetworkUtils", "Settings System wifi_ap_ssid: $settingValue")
            android.util.Log.d("NetworkUtils", "Settings Secure wifi_ssid: $settingValue2")
            
            // Return the first valid value
            if (!settingValue.isNullOrEmpty() && settingValue != "<unknown ssid>") {
                return settingValue.removeSurrounding("\"")
            }
            
            if (!settingValue2.isNullOrEmpty() && settingValue2 != "<unknown ssid>") {
                return settingValue2.removeSurrounding("\"")
            }
            
            return null
        } catch (e: Exception) {
            android.util.Log.e("NetworkUtils", "Error getting SSID from settings: ${e.message}")
            return null
        }
    }
    
    /**
     * Attempt to get WiFi SSID using local manager (works on many devices)
     */
    private fun getWifiSsidFromLocalManager(): String? {
        try {
            // Alternative way to get SSID on some devices
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            
            if (!wifiManager.isWifiEnabled) {
                return null
            }
            
            // Force get SSID using alternative method
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            
            if (wifiInfo?.networkId == -1) {
                return null
            }
            
            // This method sometimes works on devices when others fail
            @Suppress("DEPRECATION")
            val ssid = wifiInfo?.ssid?.removeSurrounding("\"")
            
            return if (ssid == null || ssid == "<unknown ssid>" || ssid.isEmpty()) {
                null
            } else {
                ssid
            }
        } catch (e: Exception) {
            android.util.Log.e("NetworkUtils", "Error getting SSID from local manager: ${e.message}")
            return null
        }
    }
    
    /**
     * Checks current network status and updates LiveData
     * Now also handles automatic URL selection
     */
    fun checkNetworkStatus() {
        val activeNetwork = connectivityManager.activeNetwork ?: run {
            _isConnected.postValue(false)
            _isHomeNetwork.postValue(false)
            _currentSsid.postValue("")
            return
        }
        
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: run {
            _isConnected.postValue(false)
            _isHomeNetwork.postValue(false)
            _currentSsid.postValue("")
            return
        }
        
        val isInternetAvailable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        _isConnected.postValue(isInternetAvailable)
        
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            // Try multiple detection methods in order of reliability
            // 1. Try our force connection method first (most reliable on newer Android)
            var ssid = getSsidWithBestMethod()
            Log.d(TAG, "Best method SSID result: $ssid")
            
            // 2. Fallback to WifiNetworkManager if method fails
            if (ssid == null || ssid.isEmpty() || ssid == "<unknown ssid>") {
                // Force refresh to avoid stale data
                wifiNetworkManager.refreshSsid()
                ssid = wifiNetworkManager.getCurrentSsid()
                Log.d(TAG, "WifiNetworkManager SSID result: $ssid")
            }
            
            // 3. Try force connection as another backup
            // The condition is always true here because of the previous check and assignment
            // which means ssid can only be null, empty or "<unknown ssid>" at this point
            ssid = forceWifiConnection()
            Log.d(TAG, "Force connection SSID result: $ssid")
            
            if (ssid != null && ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                _currentSsid.postValue(ssid)
                
                // Clean up SSID (Android sometimes adds quotes) for reliable matching
                val cleanSsid = ssid.trim().removeSurrounding("\"")
                val isHomeNetworkResult = isNetworkInHomeList(cleanSsid)
                _isHomeNetwork.postValue(isHomeNetworkResult)
                
                Log.d(TAG, "Connected to WiFi: $ssid (clean: $cleanSsid, Home: $isHomeNetworkResult)")
                
                // Update URL based on network type
                updateUrlForCurrentNetwork(isHomeNetworkResult)
            } else {
                // We're on WiFi but couldn't detect SSID
                _currentSsid.postValue("")
                
                // CRITICAL: For better UX, assume home network when on WiFi but can't detect SSID
                _isHomeNetwork.postValue(true) 
                Log.d(TAG, "WiFi connected but couldn't get SSID - defaulting to home network")
                
                // Update URL to internal since we're defaulting to home network
                updateUrlForCurrentNetwork(true)
            }
        } else {
            _currentSsid.postValue("")
            _isHomeNetwork.postValue(false)
            Log.d(TAG, "Not connected to WiFi")
            
            // Update URL to external since we're not on WiFi
            updateUrlForCurrentNetwork(false)
        }
    }
    
    /**
     * Updates the URL LiveData based on network connection mode and home network status
     */
    private fun updateUrlForCurrentNetwork(isOnHomeNetwork: Boolean) {
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
        
        val url = when (connectionMode) {
            "internal" -> getInternalUrl()
            "external" -> getExternalUrl()
            else -> if (isOnHomeNetwork) getInternalUrl() else getExternalUrl()
        }
        
        _currentUrl.postValue(url)
        Log.d(TAG, "Set URL to $url (connection mode: $connectionMode, on home network: $isOnHomeNetwork)")
    }
    
    /**
     * Main function to get the appropriate URL based on the current situation
     * Considers connection mode, WiFi state, and home network status
     */
    fun getAppropriateUrl(): String {
        try {
            // First check connection mode - this is the most reliable approach
            val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
            
            if (connectionMode == "internal") {
                Log.d(TAG, "Using internal URL (forced by settings)")
                return getInternalUrl()
            } else if (connectionMode == "external") {
                Log.d(TAG, "Using external URL (forced by settings)")
                return getExternalUrl()
            }
            
            // If auto mode, first check if WiFi is connected at all
            if (!isWifiConnected()) {
                Log.d(TAG, "Not connected to WiFi, using external URL")
                return getExternalUrl()
            }
            
            // Check if manual override is set
            val manualOverride = prefs.getString("manual_home_network", "")
            if (!manualOverride.isNullOrEmpty()) {
                Log.d(TAG, "Using manual override: $manualOverride")
                
                // Check if override is in home networks
                val homeNetworks = getHomeNetworks()
                if (homeNetworks.any { it.equals(manualOverride, ignoreCase = true) }) {
                    Log.d(TAG, "Manual override matches home network, using internal URL")
                    return getInternalUrl()
                }
            }
            
            // Try to get actual SSID
            val ssid = getSsidWithBestMethod()
            Log.d(TAG, "Got SSID: $ssid")
            
            if (ssid == null || ssid.isEmpty() || ssid == "<unknown ssid>" || ssid == "Current WiFi") {
                // CRITICAL FIX: If we can't get SSID but we are on WiFi, default to internal URL for better UX
                Log.d(TAG, "Could not determine SSID - DEFAULTING TO INTERNAL URL for better UX")
                return getInternalUrl()
            }
            
            // Clean up SSID (Android sometimes adds quotes) for reliable matching
            val cleanSsid = ssid.trim().removeSurrounding("\"")
            Log.d(TAG, "Cleaned SSID: $cleanSsid")
            
            // Check against home networks list
            val isInHomeList = isNetworkInHomeList(cleanSsid)
            if (isInHomeList) {
                Log.d(TAG, "SSID matches home network, using internal URL")
                return getInternalUrl()
            }
            
            // No match found, use external URL
            Log.d(TAG, "SSID not in home networks, using external URL")
            return getExternalUrl()
        } catch (e: Exception) {
            Log.e(TAG, "Error in getAppropriateUrl: ${e.message}")
            // When in doubt, use internal URL for better UX
            return getInternalUrl() 
        }
    }
    
    /**
     * Helper function to check if device is connected to WiFi
     */
    fun isWifiConnected(): Boolean {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
                
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking WiFi connection: ${e.message}")
            return false
        }
    }
    
    /**
     * Check if a network SSID is in the home networks list
     */
    private fun isNetworkInHomeList(ssid: String): Boolean {
        val homeNetworks = getHomeNetworks()
        Log.d(TAG, "Checking SSID '$ssid' against home networks: ${homeNetworks.joinToString(", ") { "'$it'" }}")
        
        val result = homeNetworks.any { homeNetwork -> 
            val cleanHomeNetwork = homeNetwork.trim().removeSurrounding("\"")
            val cleanSsid = ssid.trim().removeSurrounding("\"")
            val matches = cleanHomeNetwork.equals(cleanSsid, ignoreCase = true)
            Log.d(TAG, "Comparing '$cleanHomeNetwork' with '$cleanSsid': $matches")
            matches
        }
        
        Log.d(TAG, "SSID '$ssid' is in home networks: $result")
        return result
    }
    
    /**
     * Determines if current WiFi network is a home network
     * Considers connection mode, manual override, and SSID detection
     */
    fun isHomeNetwork(): Boolean {
        // First check connection mode
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
        
        if (connectionMode == "internal") {
            return true
        } else if (connectionMode == "external") {
            return false
        }
        
        // Check if connected to WiFi at all
        if (!isWifiConnected()) {
            return false
        }
        
        // Get current SSID
        val ssid = getSsidWithBestMethod()
        
        // Handle detection failure cases
        if (ssid == null || ssid.isEmpty() || ssid == "<unknown ssid>" || ssid == "Current WiFi") {
            // Check if manual override is set
            val manualOverride = prefs.getString("manual_home_network", "")
            if (!manualOverride.isNullOrEmpty()) {
                // Check if override is in home networks
                val homeNetworks = getHomeNetworks()
                return homeNetworks.any { it.equals(manualOverride, ignoreCase = true) }
            }
            
            // CRITICAL FIX: If we can't detect SSID but are on WiFi, assume home network for better UX
            return true
        }
        
        // Clean up SSID (Android sometimes adds quotes) for reliable matching
        val cleanSsid = ssid.trim().removeSurrounding("\"")
        
        // Check against home networks
        return isNetworkInHomeList(cleanSsid)
    }
    
    /**
     * Gets the list of configured home networks
     */
    fun getHomeNetworks(): Set<String> {
        return prefs.getStringSet("home_wifi_networks", emptySet()) ?: emptySet()
    }
    
    /**
     * Gets the internal URL from preferences
     */
    fun getInternalUrl(): String {
        return prefs.getString("internal_url", "http://frigate.local") ?: "http://frigate.local"
    }
    
    /**
     * Gets the external URL from preferences
     */
    fun getExternalUrl(): String {
        return prefs.getString("external_url", "https://example.com/frigate") ?: "https://example.com/frigate"
    }
    
    /**
     * Gets SSID using the most reliable method for the device
     * Updated for Android 16 compatibility
     */
    fun getSsidWithBestMethod(): String? {
        try {
            // First check if WiFi is even connected
            if (!isWifiConnected()) {
                Log.d(TAG, "Not connected to WiFi")
                return null
            }
            
            // Before trying any method, check permissions for Android 16+
            val hasNearbyDevicesPermission = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "Permissions - NEARBY_WIFI_DEVICES: $hasNearbyDevicesPermission, LOCATION: $hasLocationPermission")
            
            // METHOD 1: Modern API approach for Android 12+ and Android 16+ 
            // This is the recommended approach for newer Android versions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    // For Android 16+, we need NEARBY_WIFI_DEVICES permission
                    // For older versions, location permission might be sufficient
                    if (hasNearbyDevicesPermission || hasLocationPermission) {
                        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val activeNetwork = cm.activeNetwork ?: return null
                        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return null
                        
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            val transportInfo = capabilities.transportInfo
                            if (transportInfo is WifiInfo) {
                                var ssid = transportInfo.ssid
                                
                                // Clean up SSID
                                if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                                    ssid = ssid.substring(1, ssid.length - 1)
                                }
                                
                                Log.d(TAG, "Got SSID from transportInfo: $ssid")
                                
                                if (ssid != null && ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                                    return ssid
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error with NetworkCapabilities approach: ${e.message}")
                }
            }
            
            // METHOD 2: Direct WifiManager approach (works on many devices when proper permissions are granted)
            try {
                // Check if we have either NEARBY_WIFI_DEVICES or location permission
                if (hasNearbyDevicesPermission || hasLocationPermission) {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val wifiInfo = wifiManager.connectionInfo
                    
                    if (wifiInfo != null && wifiInfo.networkId != -1) {
                        @Suppress("DEPRECATION")
                        var ssid = wifiInfo.ssid
                        
                        // Clean up SSID
                        if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                            ssid = ssid.substring(1, ssid.length - 1)
                        }
                        
                        Log.d(TAG, "WifiManager direct SSID: $ssid")
                        
                        if (ssid != null && ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                            return ssid
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error with direct WifiManager approach: ${e.message}")
            }
            
            // METHOD 3: Try to get SSID from system settings as backup
            try {
                val settingsSSID = Settings.System.getString(context.contentResolver, "wifi_ap_ssid")
                Log.d(TAG, "SSID from settings: $settingsSSID")
                
                if (!settingsSSID.isNullOrEmpty() && settingsSSID != "<unknown ssid>") {
                    val cleanSsid = settingsSSID.removeSurrounding("\"")
                    if (cleanSsid.isNotEmpty()) {
                        return cleanSsid
                    }
                }
                
                // Try Secure settings as well
                val secureSSID = Settings.Secure.getString(context.contentResolver, "wifi_ssid")
                Log.d(TAG, "SSID from secure settings: $secureSSID")
                
                if (!secureSSID.isNullOrEmpty() && secureSSID != "<unknown ssid>") {
                    val cleanSsid = secureSSID.removeSurrounding("\"")
                    if (cleanSsid.isNotEmpty()) {
                        return cleanSsid
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting SSID from settings: ${e.message}")
            }
            
            // METHOD 4: Check for manual override as last resort
            val manualOverride = prefs.getString("manual_home_network", "")
            if (!manualOverride.isNullOrEmpty()) {
                Log.d(TAG, "Using manual home network override: $manualOverride")
                return manualOverride
            }
            
            Log.d(TAG, "Could not determine SSID with any method")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting SSID: ${e.message}")
            return null
        }
    }
}