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
 * Manages network detection and URL selection
 */
class NetworkUtils private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "NetworkUtils"
        private val FLAG_INCLUDE_LOCATION_INFO = 
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        
        @Volatile
        private var INSTANCE: NetworkUtils? = null
        
        fun getInstance(context: Context): NetworkUtils {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkUtils(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
    }
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiNetworkManager = WifiNetworkManager(context)
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    
    private val _isHomeNetwork = MutableLiveData<Boolean>()
    val isHomeNetwork: LiveData<Boolean> = _isHomeNetwork
    
    private val _currentSsid = MutableLiveData<String?>()
    val currentSsid: LiveData<String?> = _currentSsid
    
    private val _isConnected = MutableLiveData<Boolean>()
    val isConnected: LiveData<Boolean> = _isConnected
    
    private val _currentUrl = MutableLiveData<String?>()
    val currentUrl: LiveData<String?> = _currentUrl
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        checkStatus()
        registerCallback()
    }
    
    /**
     * Registers for network callbacks to detect network changes
     */
    private fun registerCallback() {
        if (networkCallback != null) {
            return
        }
        
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        
        var wasOnWifi = false
        var wasOnHomeNetwork = false
            
        networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available")
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        // Wait a bit for the network to stabilize
                        delay(500)
                        
                        val oldUrl = _currentUrl.value
                        checkStatus()
                        val newUrl = getUrl()
                        _currentUrl.postValue(newUrl)
                        
                        if (oldUrl != newUrl) {
                            Log.d(TAG, "URL changed from $oldUrl to $newUrl")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    _isConnected.postValue(false)
                    _isHomeNetwork.postValue(false)
                    _currentSsid.postValue("")
                    
                    if (wasOnHomeNetwork) {
                        Log.d(TAG, "Lost connection to home network - switching to external URL")
                        _currentUrl.postValue(getExternalUrl())
                    }
                    
                    wasOnWifi = false
                    wasOnHomeNetwork = false
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val isOnWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    
                    if (isOnWifi != wasOnWifi) {
                        Log.d(TAG, "WiFi state changed: $wasOnWifi -> $isOnWifi")
                        
                        val oldUrl = _currentUrl.value
                        
                        CoroutineScope(Dispatchers.IO).launch {
                            if (!isOnWifi && wasOnWifi) {
                                Log.d(TAG, "WiFi disconnected - waiting for mobile data to be available")
                                
                                // Wait for mobile data to establish connection
                                delay(1000)
                                
                                // Check if we have internet connectivity
                                var retries = 0
                                while (retries < 5) {
                                    val activeNetwork = connectivityManager.activeNetwork
                                    val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
                                    val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                                                     capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                                    
                                    if (hasInternet) {
                                        Log.d(TAG, "Mobile data is available - switching to external URL")
                                        break
                                    }
                                    
                                    Log.d(TAG, "Waiting for mobile data... (attempt ${retries + 1}/5)")
                                    delay(1000)
                                    retries++
                                }
                                
                                _currentUrl.postValue(getExternalUrl())
                                _isHomeNetwork.postValue(false)
                                _currentSsid.postValue("")
                                checkStatus()
                            } else if (isOnWifi && !wasOnWifi) {
                                Log.d(TAG, "WiFi connected - waiting for network to stabilize")
                                delay(2000)
                                checkStatus()
                            } else if (isOnWifi && wasOnWifi) {
                                Log.d(TAG, "WiFi network changed - checking new network")
                                delay(1000)
                                checkStatus()
                            }
                        }
                        
                        if (isOnWifi) {
                            val isNowOnHomeNetwork = isHome()
                            
                            if (isNowOnHomeNetwork != wasOnHomeNetwork) {
                                Log.d(TAG, "Home network state changed: $wasOnHomeNetwork -> $isNowOnHomeNetwork")
                                val newUrl = getUrl()
                                _currentUrl.postValue(newUrl)
                                
                                if (oldUrl != newUrl) {
                                    Log.d(TAG, "URL changed from $oldUrl to $newUrl due to network change")
                                }
                            }
                            wasOnHomeNetwork = isNowOnHomeNetwork
                        }
                        
                        wasOnWifi = isOnWifi
                    }
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available")
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        // Wait a bit for the network to stabilize
                        delay(500)
                        
                        val oldUrl = _currentUrl.value
                        checkStatus()
                        val newUrl = getUrl()
                        _currentUrl.postValue(newUrl)
                        
                        if (oldUrl != newUrl) {
                            Log.d(TAG, "URL changed from $oldUrl to $newUrl")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    _isConnected.postValue(false)
                    _isHomeNetwork.postValue(false)
                    _currentSsid.postValue("")
                    
                    if (wasOnHomeNetwork) {
                        Log.d(TAG, "Lost connection to home network - switching to external URL")
                        _currentUrl.postValue(getExternalUrl())
                    }
                    
                    wasOnWifi = false
                    wasOnHomeNetwork = false
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val isOnWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    
                    if (isOnWifi != wasOnWifi) {
                        Log.d(TAG, "WiFi state changed: $wasOnWifi -> $isOnWifi")
                        
                        val oldUrl = _currentUrl.value
                        
                        CoroutineScope(Dispatchers.IO).launch {
                            if (!isOnWifi && wasOnWifi) {
                                Log.d(TAG, "WiFi disconnected - waiting for mobile data to be available")
                                
                                // Wait for mobile data to establish connection
                                delay(1000)
                                
                                // Check if we have internet connectivity
                                var retries = 0
                                while (retries < 5) {
                                    val activeNetwork = connectivityManager.activeNetwork
                                    val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
                                    val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                                                     capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                                    
                                    if (hasInternet) {
                                        Log.d(TAG, "Mobile data is available - switching to external URL")
                                        break
                                    }
                                    
                                    Log.d(TAG, "Waiting for mobile data... (attempt ${retries + 1}/5)")
                                    delay(1000)
                                    retries++
                                }
                                
                                _currentUrl.postValue(getExternalUrl())
                                _isHomeNetwork.postValue(false)
                                _currentSsid.postValue("")
                                checkStatus()
                            } else if (isOnWifi && !wasOnWifi) {
                                Log.d(TAG, "WiFi connected - waiting for network to stabilize")
                                delay(2000)
                                checkStatus()
                            } else if (isOnWifi && wasOnWifi) {
                                Log.d(TAG, "WiFi network changed - checking new network")
                                delay(1000)
                                checkStatus()
                            }
                        }
                        
                        if (isOnWifi) {
                            val isNowOnHomeNetwork = isHome()
                            
                            if (isNowOnHomeNetwork != wasOnHomeNetwork) {
                                Log.d(TAG, "Home network state changed: $wasOnHomeNetwork -> $isNowOnHomeNetwork")
                                val newUrl = getUrl()
                                _currentUrl.postValue(newUrl)
                                
                                if (oldUrl != newUrl) {
                                    Log.d(TAG, "URL changed from $oldUrl to $newUrl due to network change")
                                }
                            }
                            wasOnHomeNetwork = isNowOnHomeNetwork
                        }
                        
                        wasOnWifi = isOnWifi
                    }
                }
            }
        }
        
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        Log.d(TAG, "Registered network callback for connectivity changes")
    }
    
    fun unregisterCallback() {
        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
            networkCallback = null
        }
    }
    
    suspend fun getSsidAsync(): String? {
        return getSsid()
    }
    
    /**
     * Checks current network status and updates LiveData
     */
    fun checkStatus() {
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
            var ssid = getSsid()
            Log.d(TAG, "SSID result: $ssid")
            
            // If we couldn't get SSID, try through WifiNetworkManager
            if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") {
                wifiNetworkManager.refreshSsid()
                ssid = wifiNetworkManager.getCurrentSsid()
                Log.d(TAG, "WifiNetworkManager SSID result: $ssid")
            }
            
            // Check if we have a valid SSID
            val hasValidSsid = !ssid.isNullOrEmpty() && ssid != "<unknown ssid>"
            
            if (hasValidSsid) {
                _currentSsid.postValue(ssid!!)
                
                val cleanSsid = ssid.trim().removeSurrounding("\"")
                val isHomeNetworkResult = isNetworkInHomeList(cleanSsid)
                _isHomeNetwork.postValue(isHomeNetworkResult)
                
                Log.d(TAG, "Connected to WiFi: $ssid (clean: $cleanSsid, Home: $isHomeNetworkResult)")
                
                updateUrlForCurrentNetwork(isHomeNetworkResult)
            } else {
                _currentSsid.postValue("")
                
                _isHomeNetwork.postValue(true) 
                Log.d(TAG, "WiFi connected but couldn't get SSID - defaulting to home network")
                
                updateUrlForCurrentNetwork(true)
            }
        } else {
            _currentSsid.postValue("")
            _isHomeNetwork.postValue(false)
            Log.d(TAG, "Not connected to WiFi")
            
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
     * Gets the appropriate URL based on the current situation
     */
    fun getUrl(): String {
        try {
            val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
            
            if (connectionMode == "internal") {
                Log.d(TAG, "Using internal URL (forced by settings)")
                return getInternalUrl()
            } else if (connectionMode == "external") {
                Log.d(TAG, "Using external URL (forced by settings)")
                return getExternalUrl()
            }
            
            if (!isWifiConnected()) {
                Log.d(TAG, "Not connected to WiFi, using external URL")
                return getExternalUrl()
            }
            
            val manualOverride = prefs.getString("manual_home_network", "")
            if (!manualOverride.isNullOrEmpty()) {
                Log.d(TAG, "Using manual override: $manualOverride")
                
                val homeNetworks = getHomeNetworks()
                if (homeNetworks.any { it.equals(manualOverride, ignoreCase = true) }) {
                    Log.d(TAG, "Manual override matches home network, using internal URL")
                    return getInternalUrl()
                }
            }
            
            val ssid = getSsid()
            Log.d(TAG, "Got SSID: $ssid")
            
            if (ssid == null || ssid.isEmpty() || ssid == "<unknown ssid>" || ssid == "Current WiFi") {
                Log.d(TAG, "Could not determine SSID - DEFAULTING TO INTERNAL URL for better UX")
                return getInternalUrl()
            }
            
            val cleanSsid = ssid.trim().removeSurrounding("\"")
            Log.d(TAG, "Cleaned SSID: $cleanSsid")
            
            val isInHomeList = isNetworkInHomeList(cleanSsid)
            if (isInHomeList) {
                Log.d(TAG, "SSID matches home network, using internal URL")
                return getInternalUrl()
            }
            
            Log.d(TAG, "SSID not in home networks, using external URL")
            return getExternalUrl()
        } catch (e: Exception) {
            Log.e(TAG, "Error in getUrl: ${e.message}")
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
     */
    fun isHome(): Boolean {
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
        
        if (connectionMode == "internal") {
            return true
        } else if (connectionMode == "external") {
            return false
        }
        
        if (!isWifiConnected()) {
            return false
        }
        
        val ssid = getSsid()
        
        if (ssid == null || ssid.isEmpty() || ssid == "<unknown ssid>" || ssid == "Current WiFi") {
            val manualOverride = prefs.getString("manual_home_network", "")
            if (!manualOverride.isNullOrEmpty()) {
                val homeNetworks = getHomeNetworks()
                return homeNetworks.any { it.equals(manualOverride, ignoreCase = true) }
            }
            
            return true
        }
        
        val cleanSsid = ssid.trim().removeSurrounding("\"")
        
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
     */
    fun getSsid(): String? {
        try {
            if (!isWifiConnected()) {
                Log.d(TAG, "Not connected to WiFi")
                return null
            }
            
            val hasNearbyDevicesPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
            
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "Permissions - NEARBY_WIFI_DEVICES: $hasNearbyDevicesPermission, LOCATION: $hasLocationPermission")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (hasNearbyDevicesPermission || hasLocationPermission)) {
                val ssid = getSsidFromTransportInfo()
                if (ssid != null) {
                    Log.d(TAG, "Got SSID from transportInfo: $ssid")
                    return ssid
                }
            }
            
            if (hasNearbyDevicesPermission || hasLocationPermission) {
                val ssid = getSsidFromWifiManager()
                if (ssid != null) {
                    Log.d(TAG, "Got SSID from WifiManager: $ssid")
                    return ssid
                }
            }
            
            val settingsSsid = getSsidFromSettings()
            if (settingsSsid != null) {
                Log.d(TAG, "Got SSID from settings: $settingsSsid")
                return settingsSsid
            }
            
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
    
    /**
     * Get SSID from TransportInfo (Android 12+)
     */
    private fun getSsidFromTransportInfo(): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return null
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return null
            
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val transportInfo = capabilities.transportInfo
                if (transportInfo is WifiInfo) {
                    @Suppress("DEPRECATION")
                    var ssid = transportInfo.ssid
                    
                    if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid = ssid.substring(1, ssid.length - 1)
                    }
                    
                    if (ssid != null && ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                        return ssid
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error with TransportInfo approach: ${e.message}")
        }
        return null
    }
    
    /**
     * Get SSID from WifiManager directly
     */
    private fun getSsidFromWifiManager(): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            
            if (wifiInfo != null && wifiInfo.networkId != -1) {
                @Suppress("DEPRECATION")
                var ssid = wifiInfo.ssid
                
                if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length - 1)
                }
                
                if (ssid != null && ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                    return ssid
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error with WifiManager approach: ${e.message}")
        }
        return null
    }
    
    /**
     * Get SSID from system settings
     */
    private fun getSsidFromSettings(): String? {
        try {
            val settingValue = Settings.System.getString(context.contentResolver, "wifi_ap_ssid")
            val settingValue2 = Settings.Secure.getString(context.contentResolver, "wifi_ssid")
            
            if (!settingValue.isNullOrEmpty() && settingValue != "<unknown ssid>") {
                return settingValue.removeSurrounding("\"")
            }
            
            if (!settingValue2.isNullOrEmpty() && settingValue2 != "<unknown ssid>") {
                return settingValue2.removeSurrounding("\"")
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting SSID from settings: ${e.message}")
            return null
        }
    }
}