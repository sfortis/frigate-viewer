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
import com.asksakis.freegate.utils.NetworkUtils

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    
    // Use NetworkUtils singleton to avoid duplicate callbacks
    private val networkUtils = NetworkUtils.getInstance(application)
    
    val isHomeNetwork: LiveData<Boolean> = networkUtils.isHomeNetwork
    
    private val _currentNetwork = MediatorLiveData<String?>()
    val currentNetwork: LiveData<String?> = _currentNetwork
    
    val isConnected: LiveData<Boolean> = networkUtils.isConnected
    
    private val _currentUrl = MediatorLiveData<String?>()
    val currentUrl: LiveData<String?> = _currentUrl
    
    private fun updateCurrentUrl(isHome: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
        
        val url = when (connectionMode) {
            "internal" -> prefs.getString("internal_url", "http://frigate.local")
            "external" -> prefs.getString("external_url", "https://example.com/frigate")
            else -> if (isHome) {
                prefs.getString("internal_url", "http://frigate.local")
            } else {
                prefs.getString("external_url", "https://example.com/frigate")
            }
        }
        
        _currentUrl.postValue(url)
        Log.d("HomeViewModel", "URL updated to: $url (isHome: $isHome)")
        
        val homeNetworks = getHomeNetworks()
        Log.d("HomeViewModel", "Home networks: ${homeNetworks.joinToString(", ")}")
        
        val currentSsid = _currentNetwork.value ?: "unknown"
        Log.d("HomeViewModel", "Current SSID: $currentSsid, isHome: $isHome")
    }
    
    fun getHomeNetworks(): Set<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        return prefs.getStringSet("home_wifi_networks", emptySet()) ?: emptySet()
    }
    
    init {
        // Combine both SSID and home network status to avoid duplicate updates
        val combinedNetwork = MediatorLiveData<String>()
        
        val updateNetwork = {
            val ssid = networkUtils.currentSsid.value
            val isHome = networkUtils.isHomeNetwork.value ?: false
            
            val newStatus = when {
                ssid.isNullOrEmpty() -> "Not Connected"
                isHome -> "Home: $ssid"
                else -> "External: $ssid"
            }
            
            if (combinedNetwork.value != newStatus) {
                Log.d("HomeViewModel", "Network status changed: $newStatus")
                combinedNetwork.postValue(newStatus)
            }
        }
        
        combinedNetwork.addSource(networkUtils.currentSsid) { updateNetwork() }
        combinedNetwork.addSource(networkUtils.isHomeNetwork) { updateNetwork() }
        
        _currentNetwork.addSource(combinedNetwork) { status ->
            _currentNetwork.postValue(status)
        }
        
        _currentUrl.addSource(networkUtils.currentUrl) { url ->
            if (_currentUrl.value != url) {
                Log.d("HomeViewModel", "URL from NetworkUtils: $url")
                _currentUrl.postValue(url)
            }
        }
        
        determineUrlOnStartup()
    }
    
    /**
     * One-time startup determination of which URL to use based on current WiFi network.
     */
    private fun determineUrlOnStartup() {
        val isHome = networkUtils.isHome()
        
        val url = networkUtils.getUrl()
        _currentUrl.postValue(url)
        
        Log.d("HomeViewModel", "Startup URL determination - isHome: $isHome, url: $url")
    }
    
    /**
     * Manual refresh method to update network status
     */
    fun refreshStatus() {
        Log.d("HomeViewModel", "Manual refresh triggered")
        
        networkUtils.checkStatus()
        
        val url = networkUtils.getUrl()
        Log.d("HomeViewModel", "Refreshed URL: $url")
        _currentUrl.postValue(url)
    }
}