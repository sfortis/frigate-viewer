package com.asksakis.freegate.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.util.Log
import androidx.preference.EditTextPreference
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.asksakis.freegate.R
import com.asksakis.freegate.utils.NetworkUtils
import com.asksakis.freegate.utils.WifiNetworkManager
// NetworkFixer has been consolidated into NetworkUtils

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var wifiNetworkManager: WifiNetworkManager
    private lateinit var networkUtils: NetworkUtils
    // NetworkFixer functionality has been consolidated into NetworkUtils
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        
        // Initialize network managers
        wifiNetworkManager = WifiNetworkManager(requireContext())
        networkUtils = NetworkUtils(requireContext())
        
        // Setup connection mode preference
        setupConnectionModePreference()
        
        // Setup home WiFi networks preference
        setupHomeWifiNetworksPreference()
        
        // Setup manual WiFi name override preference
        setupManualHomeNetworkPreference()
        
        // Disable autocorrect for all EditTextPreferences
        disableAutocorrectForAllInputs()
        
        // Update WiFi status
        updateWifiStatus()
    }
    
    /**
     * Sets up the connection mode preference
     */
    private fun setupConnectionModePreference() {
        val connModePref = findPreference<androidx.preference.ListPreference>("connection_mode")
        
        // Set summary to show current selection
        connModePref?.summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
        
        // Handle changes
        connModePref?.setOnPreferenceChangeListener { _, newValue ->
            val mode = newValue.toString()
            
            when (mode) {
                "auto" -> {
                    Toast.makeText(requireContext(), 
                        "Auto mode: URL depends on WiFi network", 
                        Toast.LENGTH_SHORT).show()
                    
                    // Make home networks button visible
                    findPreference<Preference>("manage_home_networks_button")?.isVisible = true
                }
                "internal" -> {
                    Toast.makeText(requireContext(), 
                        "Always using Internal URL", 
                        Toast.LENGTH_SHORT).show()
                    
                    // Hide home networks button
                    findPreference<Preference>("manage_home_networks_button")?.isVisible = false
                }
                "external" -> {
                    Toast.makeText(requireContext(), 
                        "Always using External URL", 
                        Toast.LENGTH_SHORT).show()
                    
                    // Hide home networks button
                    findPreference<Preference>("manage_home_networks_button")?.isVisible = false
                }
            }
            
            // Update the status display
            updateWifiStatus()
            
            true
        }
    }
    
    /**
     * Set up the manual home network preference
     */
    private fun setupManualHomeNetworkPreference() {
        val manualHomeNetworkPref = findPreference<androidx.preference.EditTextPreference>("manual_home_network")
        
        // Get current WiFi status using modern API
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val isConnectedToWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        
        // Show current manual override value as part of the summary
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val currentOverride = prefs.getString("manual_home_network", "") ?: ""
        
        if (isConnectedToWifi) {
            val summary = if (currentOverride.isNotEmpty()) {
                "ACTIVE OVERRIDE: '$currentOverride'\n\nTap to change or clear WiFi name override"
            } else {
                "⚠️ AUTO-DETECTION FAILED - Enter your WiFi name here ⚠️"
            }
            manualHomeNetworkPref?.summary = summary
        } else {
            manualHomeNetworkPref?.summary = "Not connected to WiFi - Enable WiFi to use this feature"
        }
        
        // Try different ways to detect SSID to help the user
        var suggestedSsid: String? = null
        
        // Method 1: Try via WifiManager directly
        try {
            val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            suggestedSsid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")
            android.util.Log.d("SettingsFragment", "Direct SSID from WifiManager: $suggestedSsid")
        } catch (e: Exception) {
            android.util.Log.e("SettingsFragment", "Error getting direct SSID: ${e.message}")
        }
        
        // Method 2: Try via active network capabilities
        if (suggestedSsid == null || suggestedSsid == "<unknown ssid>") {
            try {
                if (capabilities != null) {
                    val wifiInfo = capabilities.transportInfo as? WifiInfo
                    suggestedSsid = wifiInfo?.ssid?.removeSurrounding("\"")
                    android.util.Log.d("SettingsFragment", "SSID from capabilities: $suggestedSsid")
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsFragment", "Error getting SSID from capabilities: ${e.message}")
            }
        }
        
        // Method 3: Try via network utils
        if (suggestedSsid == null || suggestedSsid == "<unknown ssid>") {
            val utils = NetworkUtils(requireContext())
            suggestedSsid = utils.forceWifiConnection()
            android.util.Log.d("SettingsFragment", "SSID from NetworkUtils: $suggestedSsid")
        }
        
        // Set up dialog message based on what we found
        if (suggestedSsid != null && 
            !suggestedSsid.equals("<unknown ssid>", ignoreCase = true) && 
            !suggestedSsid.contains("failed", ignoreCase = true) && 
            !suggestedSsid.equals("Current WiFi", ignoreCase = true)
        ) {
            // We have a real SSID to suggest - show a pre-filled option
            manualHomeNetworkPref?.text = suggestedSsid
            manualHomeNetworkPref?.dialogMessage = "Detected WiFi name: $suggestedSsid\n\n" +
                "We've pre-filled your current WiFi name to make this easier. " +
                "Just tap OK to use this value.\n\n" +
                "This manual override will be used for URL switching."
        } else {
            // Couldn't detect - give clear instructions
            manualHomeNetworkPref?.dialogMessage = "Enter your WiFi network name (SSID)\n\n" +
                "You need to manually type your current WiFi network name.\n\n" +
                "Look in your device's WiFi settings to see the name of the " +
                "network you're currently connected to."
        }
        
        manualHomeNetworkPref?.setOnPreferenceChangeListener { _, newValue ->
            val ssid = newValue.toString().trim()
            if (ssid.isNotEmpty()) {
                // Add to home networks automatically
                addNetworkToHomeNetworks(ssid)
                
                // Show confirmation with instructions
                Toast.makeText(
                    requireContext(), 
                    "Success! '$ssid' set as your WiFi name and added to home networks",
                    Toast.LENGTH_LONG
                ).show()
                
                updateWifiStatus()
                true
            } else {
                // If empty string, clear the override
                Toast.makeText(
                    requireContext(),
                    "Manual override cleared. Auto-detection will be used.",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
        }
    }
    
    private fun setupHomeWifiNetworksPreference() {
        // Get preferences
        val manageNetworksPref = findPreference<Preference>("manage_home_networks_button")
        
        // Visibility depends on connection mode
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
        manageNetworksPref?.isVisible = connectionMode == "auto"
        
        // Set up "Manage Networks" button
        manageNetworksPref?.setOnPreferenceClickListener {
            showManageNetworksDialog()
            true
        }
    }
    
    /**
     * Updates the WiFi status in the preference summary with simple network information
     * Takes into account the connection mode setting
     */
    private fun updateWifiStatus() {
        val wifiStatusPref = findPreference<Preference>("current_wifi_status")
        if (wifiStatusPref == null) return
        
        // Get connection mode
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
        
        // If not in auto mode, show simple status
        if (connectionMode != "auto") {
            when (connectionMode) {
                "internal" -> {
                    wifiStatusPref.summary = "Always using Internal URL\n(Connection mode: Forced Internal)"
                }
                "external" -> {
                    wifiStatusPref.summary = "Always using External URL\n(Connection mode: Forced External)"
                }
            }
            return
        }
        
        // In auto mode, check current WiFi status
        try {
            // First check if WiFi is enabled at all
            val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            
            // Directly check if we're connected to a WiFi network
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            val isConnectedToWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            
            // Try to get current SSID
            var currentSsid = getActiveWifiSsid()
            
            // CRITICAL FIX: Never show "Current WiFi" as the network name
            if (currentSsid == "Current WiFi" || currentSsid == "<unknown ssid>") {
                // Try to use the manual override
                val manualOverride = prefs.getString("manual_home_network", "")
                if (!manualOverride.isNullOrEmpty()) {
                    currentSsid = manualOverride
                } else {
                    // Try to use first home network
                    val homeNetworks = getHomeNetworks()
                    if (homeNetworks.isNotEmpty()) {
                        currentSsid = homeNetworks.first()
                    }
                }
            }
            
            // Show appropriate status
            when {
                !wifiManager.isWifiEnabled -> {
                    wifiStatusPref.summary = "WiFi is disabled\nUsing: External URL"
                }
                !isConnectedToWifi -> {
                    wifiStatusPref.summary = "WiFi is enabled but not connected\nUsing: External URL"
                }
                currentSsid != null -> {
                    val isHome = isHomeNetwork(currentSsid)
                    val status = if (isHome) "HOME" else "EXTERNAL"
                    val urlUsed = if (isHome) "Internal URL" else "External URL"
                    
                    // Show the network name
                    wifiStatusPref.summary = "Connected to: $currentSsid\nNetwork type: $status\nUsing: $urlUsed"
                }
                else -> {
                    // Must NEVER show "Current WiFi" here - use a helpful default
                    wifiStatusPref.summary = "Connected to WiFi\nUsing: Internal URL (Default)\n\nTry setting Connection Mode to 'Internal' for best results"
                }
            }
        } catch (e: Exception) {
            wifiStatusPref.summary = "Error checking network status\nUsing: Internal URL\n\nTry setting Connection Mode to 'Internal'"
            android.util.Log.e("SettingsFragment", "Error updating WiFi status: ${e.message}")
        }
    }
    
    /**
     * Get WiFi SSID using the simplified NetworkUtils approach
     */
    private fun getActiveWifiSsid(): String? {
        try {
            // Use networkUtils' simplified detection for best results
            val ssid = networkUtils.getSsidWithBestMethod()
            android.util.Log.d("SettingsFragment", "NetworkUtils SSID: $ssid")
            
            if (ssid != null && ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                return ssid
            }
            
            // If that fails, check for manual override
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val manualOverride = prefs.getString("manual_home_network", "")
            if (!manualOverride.isNullOrEmpty()) {
                android.util.Log.d("SettingsFragment", "Using manual override: $manualOverride")
                return manualOverride
            }
            
            // Check if we're even connected to WiFi
            if (networkUtils.isWifiConnected()) {
                android.util.Log.d("SettingsFragment", "Connected to WiFi but couldn't get SSID")
                // When connected to WiFi but detection fails, return a placeholder
                // But not 'Current WiFi' as that causes issues with URL selection
                return "Unknown WiFi" 
            }
            
            return null
        } catch (e: Exception) {
            android.util.Log.e("SettingsFragment", "Error getting WiFi SSID: ${e.message}")
            return null
        }
    }
    
    /**
     * Simplified dialog for adding a network manually
     */
    private fun showAddNetworkDialog() {
        // Get current WiFi name for pre-filling
        val currentSsid = getActiveWifiSsid()
        
        // Create edit text field
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.hint = "Enter WiFi network name"
        
        // Pre-fill with current network if available
        if (currentSsid != null && currentSsid != "Current WiFi" && currentSsid != "<unknown ssid>") {
            input.setText(currentSsid)
            input.selectAll()
        }
        
        // Set padding
        val container = LinearLayout(requireContext())
        container.setPadding(30, 20, 30, 0)
        container.addView(input)
        
        // Create and show dialog
        AlertDialog.Builder(requireContext())
            .setTitle("Add Home WiFi Network")
            .setMessage("Enter the WiFi network name to add to your home networks list." +
                (if (currentSsid != null) "\n\nCurrent network: $currentSsid" else ""))
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val networkName = input.text.toString().trim()
                if (networkName.isNotEmpty()) {
                    addNetworkToHomeNetworks(networkName)
                    
                    // Return to manage networks dialog
                    showManageNetworksDialog()
                } else {
                    Toast.makeText(requireContext(), 
                        "Network name cannot be empty", 
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                // Return to manage networks dialog
                showManageNetworksDialog()
            }
            .setNeutralButton("WiFi Settings") { _, _ ->
                openWifiSettings()
                
                // Return to dialog after a delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    showAddNetworkDialog()
                }, 500)
            }
            .show()
    }
    
    /**
     * Shows a dialog to add a detected network
     */
    private fun showAddDetectedNetworkDialog(ssid: String) {
        if (isHomeNetwork(ssid)) {
            // Already in home networks
            Toast.makeText(
                requireContext(), 
                "'$ssid' is already in your home networks list", 
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle("Add to Home Networks")
            .setMessage("Do you want to add '$ssid' to your home networks list?")
            .setPositiveButton("Yes") { _, _ ->
                addNetworkToHomeNetworks(ssid)
                Toast.makeText(
                    requireContext(),
                    "Added '$ssid' to home networks",
                    Toast.LENGTH_SHORT
                ).show()
                updateWifiStatus()
            }
            .setNegativeButton("No", null)
            .show()
    }
    
    /**
     * Extremely simple manual SSID-only management dialog
     * No MAC addresses, just plain WiFi names
     */
    private fun showManageNetworksDialog() {
        // Get saved networks
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val savedNetworks = prefs.getStringSet("home_wifi_networks", emptySet()) ?: emptySet()
        
        // Clean network list - remove any MAC addresses and quotes
        val homeNetworks = mutableSetOf<String>()
        for (network in savedNetworks) {
            val cleaned = network.trim().replace("\"", "")
            if (cleaned.isNotEmpty() && !isMacAddress(cleaned)) {
                homeNetworks.add(cleaned)
            }
        }
        
        // Get current WiFi name
        var currentSsid = getActiveWifiSsid()
        
        // Don't show "Current WiFi" as the current network
        if (currentSsid == "Current WiFi" || currentSsid == "<unknown ssid>") {
            currentSsid = null
        }
        
        // Sort networks alphabetically
        val sortedNetworks = homeNetworks.toList().sorted()
        
        // Create dialog
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Home WiFi Networks")
            .create()
        
        // Create linear layout for full customization
        val layout = LinearLayout(requireContext())
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(30, 30, 30, 30)
        
        // Add debugging status
        val statusText = TextView(requireContext())
        val connMode = prefs.getString("connection_mode", "auto") ?: "auto"
        
        if (currentSsid != null) {
            val isHome = homeNetworks.any { it.equals(currentSsid, ignoreCase = true) }
            statusText.text = "Current WiFi: $currentSsid\n" +
                              "Mode: ${connMode.uppercase()}\n" + 
                              "URL Used: ${if (connMode == "auto") 
                                 if (isHome) "Internal" else "External" 
                              else 
                                 if (connMode == "internal") "Internal" else "External"}\n\n" +
                              "If URL selection is not working correctly, try setting Connection Mode to Internal or External in settings."
        } else {
            statusText.text = "Not connected to WiFi or detection failed\n" +
                              "Mode: ${connMode.uppercase()}\n" +
                              "URL Used: ${if (connMode == "internal") "Internal" else "External"}\n\n" +
                              "If URL selection is not working correctly, try setting Connection Mode to Internal or External in settings."
        }
        statusText.setPadding(0, 0, 0, 20)
        layout.addView(statusText)
        
        // Add instructions
        val instructions = TextView(requireContext())
        instructions.text = "Enter the exact names of your home WiFi networks."
        instructions.setPadding(0, 0, 0, 20)
        layout.addView(instructions)
        
        // Add input field
        val inputLayout = LinearLayout(requireContext())
        inputLayout.orientation = LinearLayout.HORIZONTAL
        
        val input = EditText(requireContext())
        input.hint = "Enter WiFi name (SSID)"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        
        // Pre-fill with current network if available
        if (currentSsid != null) {
            input.setText(currentSsid)
        }
        
        val params = LinearLayout.LayoutParams(
            0, 
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        )
        input.layoutParams = params
        
        // Add button
        val addButton = android.widget.Button(requireContext())
        addButton.text = "Add"
        addButton.setOnClickListener {
            val newNetwork = input.text.toString().trim()
            if (newNetwork.isNotEmpty()) {
                if (!homeNetworks.any { it.equals(newNetwork, ignoreCase = true) }) {
                    // Add network
                    homeNetworks.add(newNetwork)
                    saveHomeNetworks(homeNetworks)
                    
                    // Clear input
                    input.text.clear()
                    
                    // Show confirmation
                    Toast.makeText(requireContext(), 
                        "Added '$newNetwork' to home networks", 
                        Toast.LENGTH_SHORT).show()
                    
                    // Refresh dialog
                    dialog.dismiss()
                    showManageNetworksDialog()
                } else {
                    Toast.makeText(requireContext(), 
                        "This network is already in your list", 
                        Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), 
                    "Please enter a WiFi name", 
                    Toast.LENGTH_SHORT).show()
            }
        }
        
        inputLayout.addView(input)
        inputLayout.addView(addButton)
        layout.addView(inputLayout)
        
        // Add network list label
        val listLabel = TextView(requireContext())
        listLabel.text = if (sortedNetworks.isEmpty()) 
            "\nNo home networks configured yet" 
        else 
            "\nConfigured Home Networks:"
        listLabel.setPadding(0, 20, 0, 10)
        layout.addView(listLabel)
        
        // Add networks list with delete buttons
        val networksLayout = LinearLayout(requireContext())
        networksLayout.orientation = LinearLayout.VERTICAL
        
        for (network in sortedNetworks) {
            val networkLayout = LinearLayout(requireContext())
            networkLayout.orientation = LinearLayout.HORIZONTAL
            networkLayout.setPadding(0, 10, 0, 10)
            
            val networkText = TextView(requireContext())
            networkText.text = network
            
            // Highlight current network
            if (currentSsid != null && network.equals(currentSsid, ignoreCase = true)) {
                networkText.setTextColor(Color.GREEN)
                networkText.text = "★ $network (Current)"
            }
            
            networkText.layoutParams = LinearLayout.LayoutParams(
                0, 
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            )
            
            val deleteButton = android.widget.Button(requireContext())
            deleteButton.text = "Delete"
            deleteButton.setOnClickListener {
                // Remove network
                homeNetworks.remove(network)
                saveHomeNetworks(homeNetworks)
                
                // Show confirmation
                Toast.makeText(requireContext(), 
                    "Removed '$network' from home networks", 
                    Toast.LENGTH_SHORT).show()
                
                // Refresh dialog
                dialog.dismiss()
                showManageNetworksDialog()
            }
            
            networkLayout.addView(networkText)
            networkLayout.addView(deleteButton)
            networksLayout.addView(networkLayout)
            
            // Add divider
            val divider = View(requireContext())
            divider.setBackgroundColor(Color.GRAY)
            divider.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
            networksLayout.addView(divider)
        }
        
        // Add scrollable container
        val scrollView = android.widget.ScrollView(requireContext())
        scrollView.addView(networksLayout)
        layout.addView(scrollView)
        
        // Set dialog content
        dialog.setView(layout)
        
        // Add done button
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "Done") { _, _ ->
            dialog.dismiss()
            updateWifiStatus()
        }
        
        // Show dialog
        dialog.show()
    }
    
    /**
     * Helper method to check if a string is a MAC address
     * Used to filter out any accidentally added MAC addresses
     */
    private fun isMacAddress(text: String): Boolean {
        return text.matches(Regex("([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})"))
    }
    
    private fun confirmDeleteAll() {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete All Networks")
            .setMessage("Are you sure you want to delete all home networks?")
            .setPositiveButton("Yes") { _, _ ->
                saveHomeNetworks(emptySet())
                Toast.makeText(context, "All home networks removed", Toast.LENGTH_SHORT).show()
                updateWifiStatus()
            }
            .setNegativeButton("No", null)
            .show()
    }
    
    /**
     * Simplified version - adds a network to home networks list
     */
    private fun addNetworkToHomeNetworks(ssid: String) {
        // Clean up the SSID first
        val cleanSsid = ssid.trim().replace("\"", "")
        
        if (cleanSsid.isEmpty()) {
            Toast.makeText(requireContext(), "Cannot add empty network name", Toast.LENGTH_SHORT).show()
            return
        }
        
        android.util.Log.d("SettingsFragment", "Adding network to home networks: '$cleanSsid'")
        
        // Get existing networks
        val networks = getHomeNetworks().toMutableSet()
        
        // Check if already exists
        if (networks.any { it.equals(cleanSsid, ignoreCase = true) }) {
            Toast.makeText(requireContext(), 
                "'$cleanSsid' is already in your home networks", 
                Toast.LENGTH_SHORT).show()
            return
        }
        
        // Add just the clean version
        networks.add(cleanSsid)
        
        // Save networks
        saveHomeNetworks(networks)
        
        // Log for debugging
        android.util.Log.d("SettingsFragment", "Updated home networks: ${networks.joinToString()}")
        
        // Show success message
        Toast.makeText(
            requireContext(),
            "Added '$cleanSsid' to home networks",
            Toast.LENGTH_SHORT
        ).show()
        
        // Update status display
        updateWifiStatus()
    }
    
    private fun getHomeNetworks(): Set<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return prefs.getStringSet("home_wifi_networks", emptySet()) ?: emptySet()
    }
    
    private fun saveHomeNetworks(networks: Set<String>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.edit().putStringSet("home_wifi_networks", networks).apply()
    }
    
    /**
     * Checks if a network SSID is in the home networks list
     * Now uses the NetworkUtils for better reliability
     */
    private fun isHomeNetwork(ssid: String): Boolean {
        // First check connection mode
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
        
        if (connectionMode == "internal") {
            return true // Always home in internal mode
        } else if (connectionMode == "external") {
            return false // Always external in external mode
        }
        
        // In auto mode, check the home networks list
        val homeNetworks = getHomeNetworks()
        
        // Case-insensitive matching
        val directMatch = homeNetworks.any { 
            it.equals(ssid, ignoreCase = true) 
        }
        
        if (directMatch) {
            return true
        }
        
        // Special handling for detection failures
        if (ssid == "Unknown WiFi" || ssid == "Current WiFi") {
            // When on WiFi but detection fails, default to treating as home network
            // This improves user experience since they're likely on home WiFi
            val manualOverride = prefs.getString("manual_home_network", "")
            if (!manualOverride.isNullOrEmpty() && homeNetworks.any { it.equals(manualOverride, ignoreCase = true) }) {
                return true // Manual override matches a home network
            }
            
            // Connected to WiFi but can't identify - assume home network for better UX
            return true
        }
        
        return false
    }
    
    /**
     * Opens Android WiFi settings screen
     */
    private fun openWifiSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            startActivity(intent)
            
            // Show a helpful toast
            Toast.makeText(
                requireContext(),
                "Check your WiFi name and enter it manually in the app",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Could not open WiFi settings",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Disables autocorrect and autofill for all EditTextPreferences
     */
    private fun disableAutocorrectForAllInputs() {
        try {
            // Find all EditTextPreferences
            val internalUrl = findPreference<EditTextPreference>("internal_url")
            val externalUrl = findPreference<EditTextPreference>("external_url")
            val manualHomeNetwork = findPreference<EditTextPreference>("manual_home_network")
            
            // Set dialog EditText creation listener for each preference
            val editTextPrefs = listOfNotNull(internalUrl, externalUrl, manualHomeNetwork)
            
            for (pref in editTextPrefs) {
                pref.setOnBindEditTextListener { editText ->
                    // Disable autocorrect and suggestions
                    editText.inputType = editText.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    editText.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    
                    // Disable autofill
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        editText.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "Error disabling autocorrect: ${e.message}")
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Check if we have required permissions
        val hasRequired = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) == 
                PackageManager.PERMISSION_GRANTED
        } else {
            requireContext().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == 
                PackageManager.PERMISSION_GRANTED
        }
        
        if (!hasRequired) {
            // This is critical, so show a visible alert
            AlertDialog.Builder(requireContext())
                .setTitle("Permission Required")
                .setMessage("WiFi detection requires NEARBY_WIFI_DEVICES permission on Android 13+ " +
                         "or LOCATION permission on older Android versions.\n\n" +
                         "Please go to App Settings and grant the required permission, " +
                         "then restart the app.")
                .setPositiveButton("OK") { _, _ -> 
                    // Optionally, open system app settings here
                    // But since we request in MainActivity, it's not necessary
                }
                .show()
            
            // Also show a toast
            Toast.makeText(
                requireContext(), 
                "WiFi detection requires permission. Please restart app and grant when prompted.", 
                Toast.LENGTH_LONG
            ).show()
        }
        
        // Update WiFi status on resume
        updateWifiStatus()
    }
}