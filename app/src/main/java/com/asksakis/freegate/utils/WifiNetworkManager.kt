package com.asksakis.freegate.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Utility class for WiFi network selection UI.
 * This is a reduced version focused only on providing network name suggestions
 * for the UI settings, with core WiFi detection now handled by NetworkUtils.
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
    
    // CoroutineScope for async operations
    private val wifiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Cache for performance
    private var cachedSsid: String? = null
    private var lastCacheTime = 0L
    private val CACHE_DURATION = 3000L // 3 seconds
    
    // LiveData for observers
    private val _ssidData = MutableLiveData<String?>()
    val ssidData: LiveData<String?> = _ssidData
    
    /**
     * Gets the current connected WiFi network name.
     * Simplified to use only the direct approach.
     */
    fun getCurrentSsid(): String {
        // Direct approach using WifiManager
        val directSsid = try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            
            if (wifiInfo != null && wifiInfo.networkId != -1) {
                @Suppress("DEPRECATION")
                val ssid = wifiInfo.ssid?.removeSurroundingQuotes()
                if (ssid != null && ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                    ssid
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting direct SSID: ${e.message}")
            null
        }
        
        // Update cache and LiveData if we got a result
        if (directSsid != null) {
            cachedSsid = directSsid
            lastCacheTime = System.currentTimeMillis()
            _ssidData.postValue(directSsid)
            return directSsid
        }
        
        // Return cached value or empty string
        return cachedSsid ?: ""
    }
    
    /**
     * Force refresh the SSID information
     */
    fun refreshSsid() {
        // Reset cache time to force a fresh lookup
        lastCacheTime = 0L
        getCurrentSsid()
    }
    
    /**
     * Gets a list of network names for WiFi selection in the settings UI.
     * This is a combination of current network and some common network names.
     */
    fun getNetworkSelectionList(): List<String> {
        val networks = mutableSetOf<String>()
        
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
    
    /**
     * Cancel any ongoing operations when no longer needed
     */
    fun cleanup() {
        wifiScope.cancel()
    }
}