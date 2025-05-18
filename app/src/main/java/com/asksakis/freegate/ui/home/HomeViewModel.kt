package com.asksakis.freegate.ui.home

import android.app.Application
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.preference.PreferenceManager
// NetworkFixer has been consolidated into NetworkUtils
import com.asksakis.freegate.utils.NetworkUtils
import com.asksakis.freegate.utils.WifiNetworkManager

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    
    private val networkUtils = NetworkUtils(application)
    private val wifiNetworkManager = WifiNetworkManager(application)
    // NetworkFixer functionality has been consolidated into NetworkUtils
    
    // Create composite LiveData sources
    val isHomeNetwork: LiveData<Boolean> = networkUtils.isHomeNetwork
    
    // MediatorLiveData to combine SSID sources for better detection
    private val _currentNetwork = MediatorLiveData<String>()
    val currentNetwork: LiveData<String> = _currentNetwork
    
    // Network callback to only respond to actual network changes
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    val isConnected: LiveData<Boolean> = networkUtils.isConnected
    
    // URL MediatorLiveData observes NetworkUtils URL changes
    private val _currentUrl = MediatorLiveData<String>()
    val currentUrl: LiveData<String> = _currentUrl
    
    private fun updateCurrentUrl(isHome: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val url = if (isHome) {
            prefs.getString("internal_url", "http://frigate.local") ?: "http://frigate.local"
        } else {
            prefs.getString("external_url", "https://example.com/frigate") ?: "https://example.com/frigate"
        }
        _currentUrl.postValue(url)
        Log.d("HomeViewModel", "URL updated to: $url (isHome: $isHome)")
        
        // Log the home networks for debugging
        val homeNetworks = getHomeNetworks()
        Log.d("HomeViewModel", "Home networks: ${homeNetworks.joinToString(", ")}")
        
        // Get current SSID for debugging
        val currentSsid = _currentNetwork.value ?: "unknown"
        Log.d("HomeViewModel", "Current SSID: $currentSsid, isHome: $isHome")
    }
    
    // Helper function to get home networks
    fun getHomeNetworks(): Set<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        return prefs.getStringSet("home_wifi_networks", emptySet()) ?: emptySet()
    }
    
    init {
        // Add SSID sources to our combined data for reactive updates
        // From NetworkUtils
        _currentNetwork.addSource(networkUtils.currentSsid) { ssid ->
            Log.d("HomeViewModel", "SSID from NetworkUtils: $ssid")
            if (ssid.isNotEmpty()) {
                _currentNetwork.postValue(ssid)
            }
        }
        
        // From WifiNetworkManager (primary source using coroutines)
        _currentNetwork.addSource(wifiNetworkManager.ssidData) { ssid ->
            Log.d("HomeViewModel", "SSID from WifiNetworkManager: $ssid")
            if (ssid.isNotEmpty()) {
                _currentNetwork.postValue(ssid)
                
                // When SSID changes, we should check if it's a home network
                // but don't automatically refresh URL unless settings allow it
                val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
                val refreshOnNetworkChange = prefs.getBoolean("refresh_on_network_change", true)
                
                if (refreshOnNetworkChange) {
                    checkNetworkStatus()
                }
            }
        }
        
        // Observe URL changes from NetworkUtils
        _currentUrl.addSource(networkUtils.currentUrl) { url ->
            Log.d("HomeViewModel", "URL from NetworkUtils: $url")
            _currentUrl.postValue(url)
        }
        
        // Initial network and URL check on startup only
        determineUrlOnStartup()
        
        // Register for actual network changes instead of constant checking
        registerNetworkCallback()
    }
    
    /**
     * One-time startup determination of which URL to use based on current WiFi network.
     * This will set the URL once and not change it until manually refreshed.
     */
    /**
     * Get current WiFi SSID using the most reliable method
     */
    /**
     * Simplified SSID-only network detection
     */
    private fun isOnHomeNetwork(): Boolean {
        try {
            // Use NetworkUtils for most reliable detection
            return networkUtils.isHomeNetwork()
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Error determining if on home network: ${e.message}")
            return false
        }
    }
    
    /**
     * Get current WiFi SSID using most direct method
     */
    private fun getCurrentSsid(): String? {
        try {
            // Use our NetworkUtils for most reliable detection
            return networkUtils.getSsidWithBestMethod()
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Error getting current SSID: ${e.message}")
            return null
        }
    }
    
    /**
     * Helper method to check if a string is a MAC address
     * Used for filtering out MAC addresses
     */
    private fun isMacAddress(text: String): Boolean {
        return text.matches(Regex("([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})"))
    }
    
    private fun determineUrlOnStartup() {
        Log.d("HomeViewModel", "Starting URL determination process")
        
        // Use NetworkUtils for the most reliable URL detection
        val url = networkUtils.getAppropriateUrl()
        _currentUrl.postValue(url)
        
        // Log for debugging
        Log.d("HomeViewModel", "Selected URL on startup: $url")
        
        // Update UI display based on network status
        val isHome = url == networkUtils.getInternalUrl()
        
        // For UI display, just indicate whether we're on a home network or not
        if (isHome) {
            // Try to get actual SSID for display
            val ssid = networkUtils.getSsidWithBestMethod()
            _currentNetwork.postValue(if (ssid != null && ssid != "Current WiFi" && ssid != "<unknown ssid>") {
                "Home: $ssid"
            } else {
                "Home Network"
            })
        } else {
            // Check if we're connected to WiFi at all
            val isConnectedToWifi = networkUtils.isWifiConnected()
            
            if (isConnectedToWifi) {
                val ssid = networkUtils.getSsidWithBestMethod()
                _currentNetwork.postValue(if (ssid != null && ssid != "Current WiFi" && ssid != "<unknown ssid>") {
                    "External: $ssid"
                } else {
                    "External Network"
                })
            } else {
                _currentNetwork.postValue("Not Connected")
            }
        }
    }
    
    /**
     * Manual refresh for when the user explicitly requests a refresh.
     * This will re-check the network status and update the URL if needed.
     */
    fun checkNetworkStatus() {
        Log.d("HomeViewModel", "Manual refresh requested - rechecking network status")
        
        // Use NetworkUtils for the most reliable URL detection
        val url = networkUtils.getAppropriateUrl()
        _currentUrl.postValue(url)
        
        // Log for debugging
        Log.d("HomeViewModel", "Selected URL after refresh: $url")
        
        // Update UI display based on network status
        val isHome = url == networkUtils.getInternalUrl()
        
        // For UI display, just indicate whether we're on a home network or not
        if (isHome) {
            // Try to get actual SSID for display
            val ssid = networkUtils.getSsidWithBestMethod()
            _currentNetwork.postValue(if (ssid != null && ssid != "Current WiFi" && ssid != "<unknown ssid>") {
                "Home: $ssid"
            } else {
                "Home Network"
            })
        } else {
            // Check if we're connected to WiFi at all
            val isConnectedToWifi = networkUtils.isWifiConnected()
            
            if (isConnectedToWifi) {
                val ssid = networkUtils.getSsidWithBestMethod()
                _currentNetwork.postValue(if (ssid != null && ssid != "Current WiFi" && ssid != "<unknown ssid>") {
                    "External: $ssid"
                } else {
                    "External Network"
                })
            } else {
                _currentNetwork.postValue("Not Connected")
            }
        }
    }
    
    /**
     * Helper method to get the manual network override from preferences
     */
    private fun getManualNetworkOverride(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        return prefs.getString("manual_home_network", "") ?: ""
    }
    
    /**
     * Retrieves WebView settings from SharedPreferences
     */
    fun getWebViewSettings(): Map<String, Boolean> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication<Application>())
        return mapOf(
            "javascript" to prefs.getBoolean("enable_javascript", true),
            "dom_storage" to prefs.getBoolean("enable_dom_storage", true),
            "refresh_on_network_change" to prefs.getBoolean("refresh_on_network_change", true)
        )
    }
    
    /**
     * Register for actual network connectivity changes rather than constantly polling
     */
    private fun registerNetworkCallback() {
        try {
            // Only register if not already registered
            if (networkCallback != null) return
            
            val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            // Create a request for all network types
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
                
            // Create a callback that only triggers on actual network changes
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                // Track previous state to detect actual changes
                private var wasOnWifi = false
                private var wasOnCellular = false
                private var wasConnected = false
                
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d("HomeViewModel", "Network became available")
                    
                    // Only check if we were previously disconnected
                    if (!wasConnected) {
                        wasConnected = true
                        Log.d("HomeViewModel", "Connection state changed: disconnected -> connected")
                        checkNetworkStatus()
                    }
                }
                
                override fun onLost(network: Network) {
                    super.onLost(network)
                    
                    // Check if we just lost our last connection
                    val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val isStillConnected = cm.activeNetwork != null
                    
                    if (wasConnected && !isStillConnected) {
                        wasConnected = false
                        Log.d("HomeViewModel", "Connection state changed: connected -> disconnected")
                        checkNetworkStatus()
                    }
                }
                
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    super.onCapabilitiesChanged(network, capabilities)
                    
                    // Get current transport types
                    val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val hasCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    
                    // Only update when transport changes (WiFi on/off is what we care about)
                    val wifiChanged = hasWifi != wasOnWifi
                    val cellularChanged = hasCellular != wasOnCellular
                    
                    if (wifiChanged || cellularChanged) {
                        Log.d("HomeViewModel", "Network transport changed - WiFi: $wasOnWifi -> $hasWifi, Cellular: $wasOnCellular -> $hasCellular")
                        
                        // Update stored state
                        wasOnWifi = hasWifi
                        wasOnCellular = hasCellular
                        
                        // Only reload when WiFi state changes (on -> off or off -> on)
                        if (wifiChanged) {
                            checkNetworkStatus()
                        }
                    }
                }
            }
            
            // Register the callback
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            Log.d("HomeViewModel", "Registered network callback for connectivity changes")
            
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Error registering network callback: ${e.message}")
        }
    }
    
    /**
     * Unregister network callback to prevent leaks
     */
    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
                networkCallback = null
                Log.d("HomeViewModel", "Unregistered network callback")
            }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Error unregistering network callback: ${e.message}")
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        networkUtils.unregisterNetworkCallback()
        wifiNetworkManager.stopNetworkMonitoring()
        unregisterNetworkCallback()
    }
}