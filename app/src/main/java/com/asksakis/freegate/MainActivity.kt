package com.asksakis.freegate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.asksakis.freegate.databinding.ActivityMainBinding
// NetworkFixer has been consolidated into NetworkUtils
import com.asksakis.freegate.utils.NetworkUtils
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var networkUtils: NetworkUtils
    // NetworkFixer functionality has been consolidated into NetworkUtils
    private var networkIndicator: TextView? = null
    
    // Permission request launcher for nearby devices (Android 13+)
    private val requestNearbyDevicesPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, refresh network status
            networkUtils.checkNetworkStatus()
            
            // Refresh the current fragment if it's the home fragment
            if (::navController.isInitialized && 
                navController.currentDestination?.id == R.id.nav_home) {
                navController.navigate(R.id.nav_home)
            }
        } else {
            // Permission denied, show a more helpful message
            Snackbar.make(
                binding.root, 
                "Freegate needs permission to detect WiFi networks for automatic URL switching.",
                Snackbar.LENGTH_LONG
            ).setAction("Grant") {
                requestRequiredPermissions()  
            }.show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)
        
        // Check and apply "Keep screen on" setting
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        // Initialize network utilities
        networkUtils = NetworkUtils(this)
        
        // Find network indicator
        networkIndicator = findViewById(R.id.network_indicator)
        
        // Log current permission status
        logPermissionStatus()
        
        // Always force-check permissions on each startup
        // This is critical for WiFi detection to work
        requestRequiredPermissions()
        
        // For Android 11+, we need to add the device to the local network device filter
        // This is not actually needed for WiFi detection - we use NetworkUtils instead
        /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            registerLocalNetworkRequest()
        }*/
        
        // Show a message about why we need permissions only if we don't have them
        if (!hasRequiredPermissions()) {
            Snackbar.make(
                binding.root,
                "WiFi detection requires permissions for automatic URL switching.",
                Snackbar.LENGTH_LONG
            ).setAction("Grant") {
                requestRequiredPermissions()
            }.show()
        }

        // FAB refresh button has been removed per user request
        // Network status is checked automatically in other ways
        
        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        
        // With FragmentContainerView, we need to wait for the fragment to be attached
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? androidx.navigation.fragment.NavHostFragment
        if (navHostFragment != null) {
            navController = navHostFragment.navController
            
            // Passing each menu ID as a set of Ids because each
            // menu should be considered as top level destinations.
            appBarConfiguration = AppBarConfiguration(
                setOf(
                    R.id.nav_home, R.id.nav_settings
                ), drawerLayout
            )
            setupActionBarWithNavController(navController, appBarConfiguration)
            navView.setupWithNavController(navController)
            
            // Setup navigation listener to update the network indicator
            navController.addOnDestinationChangedListener { _, destination, _ ->
                updateNetworkIndicator(destination)
            }
            
            // Observe network state changes
            networkUtils.isHomeNetwork.observe(this) { _ ->
                updateNetworkIndicator(navController.currentDestination)
            }
            networkUtils.currentUrl.observe(this) { _ ->
                updateNetworkIndicator(navController.currentDestination)
            }
        } else {
            // Fragment is not ready yet, we'll set it up in onPostCreate
            Log.d("MainActivity", "NavHostFragment not ready yet")
        }
    }
    
    /**
     * Check required permissions and request them if needed
     */
    private fun checkRequiredPermissions() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        }
    }
    
    /**
     * Request the appropriate permissions based on Android version
     * Using the exact approach from the example code
     */
    /**
     * Request the appropriate permissions based on Android version
     * For Android 13+, we use NEARBY_WIFI_DEVICES with neverForLocation flag
     */
    private fun requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            android.util.Log.d("MainActivity", "Requesting NEARBY_WIFI_DEVICES permission for Android 13+/16+")
            
            // Request all needed permissions for Android 13+ and 16+
            val permissionsToRequest = arrayOf(
                // NEARBY_WIFI_DEVICES is the main permission for WiFi SSID on Android 13+ and 16+
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_NETWORK_STATE,
                // Also request location permissions as they might still be needed on some devices
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            
            // Check each permission individually
            permissionsToRequest.forEach { permission ->
                val hasPermission = ContextCompat.checkSelfPermission(this, permission) == 
                    PackageManager.PERMISSION_GRANTED
                android.util.Log.d("MainActivity", "Permission $permission granted: $hasPermission")
            }
            
            // Request all permissions directly
            ActivityCompat.requestPermissions(this, permissionsToRequest, 100)
            
            // Also use the activity result launcher for the most critical permission
            requestNearbyDevicesPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            
            // Removed the Snackbar message as requested
            
            // Removed the toast message as requested
        } else {
            // For older versions, we need location permission
            android.util.Log.d("MainActivity", "Requesting ACCESS_FINE_LOCATION permission for Android <13")
            
            // Request both location permissions
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.ACCESS_NETWORK_STATE
                ),
                101
            )
            
            // Show a toast explaining why we need location permission
            Toast.makeText(
                this,
                "Freegate needs location permission to detect WiFi networks.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    /**
     * Register a network request to get SSID of the connected Wi-Fi network for Android 11+
     * This helps resolve the <unknown ssid> issue on newer Android versions
     * NOTE: This is not actually needed - we use NetworkUtils for network detection
     */
    /*private fun registerLocalNetworkRequest() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            // Create a request for WiFi networks
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Local networks may not have internet
                .build()
                
            // Create a callback to handle network changes with location info flag on Android 12+
            val networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                object : ConnectivityManager.NetworkCallback(
                    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
                ) {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        android.util.Log.d("MainActivity", "Local network available with location info")
                        
                        // Bind to the network briefly to improve SSID access
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            connectivityManager.bindProcessToNetwork(network)
                            connectivityManager.bindProcessToNetwork(null) // Unbind after briefly binding
                        }
                    }
                    
                    override fun onLost(network: Network) {
                        super.onLost(network)
                        android.util.Log.d("MainActivity", "Local network lost")
                    }
                    
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        super.onCapabilitiesChanged(network, capabilities)
                        android.util.Log.d("MainActivity", "Network capabilities changed")  
                    }
                }
            } else {
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        android.util.Log.d("MainActivity", "Local network available")
                        
                        // Bind to the network briefly to improve SSID access
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            connectivityManager.bindProcessToNetwork(network)
                            connectivityManager.bindProcessToNetwork(null) // Unbind after briefly binding
                        }
                    }
                    
                    override fun onLost(network: Network) {
                        super.onLost(network)
                        android.util.Log.d("MainActivity", "Local network lost")
                    }
                }
            }
            
            // Register the request with the callback
            connectivityManager.requestNetwork(request, networkCallback)
            
            android.util.Log.d("MainActivity", "Registered local network request for WiFi SSID access")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error registering local network request: ${e.message}")
        }
    }*/
    
    /**
     * Check if we have the required permissions based on Android version
     */
    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Log the status of all permissions needed for WiFi detection
     * This helps with debugging permission issues
     */
    private fun logPermissionStatus() {
        android.util.Log.d("MainActivity", "===== PERMISSION STATUS =====")
        
        // Log basic info
        android.util.Log.d("MainActivity", "Android SDK Version: ${Build.VERSION.SDK_INT}")
        android.util.Log.d("MainActivity", "Android VERSION.RELEASE: ${Build.VERSION.RELEASE}")
        
        // Check all potentially relevant permissions
        val permissions = arrayOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        
        permissions.forEach { permission ->
            val hasPermission = ContextCompat.checkSelfPermission(this, permission) == 
                PackageManager.PERMISSION_GRANTED
            android.util.Log.d("MainActivity", "Permission $permission: ${if (hasPermission) "GRANTED" else "DENIED"}")
        }
        
        // Check and log WiFi status
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        android.util.Log.d("MainActivity", "WiFi Enabled: ${wifiManager.isWifiEnabled}")
        
        // Try to get the SSID in multiple ways for debugging
        try {
            @Suppress("DEPRECATION")
            val wifiInfoSsid = wifiManager.connectionInfo?.ssid
            android.util.Log.d("MainActivity", "Debug - WiFi SSID direct from WifiManager: $wifiInfoSsid")
            
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            android.util.Log.d("MainActivity", "Debug - Has WiFi transport: ${networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true}")
            
            val transportInfo = networkCapabilities?.transportInfo
            android.util.Log.d("MainActivity", "Debug - TransportInfo is not null: ${transportInfo != null}")
            
            if (transportInfo != null && transportInfo is android.net.wifi.WifiInfo) {
                @Suppress("DEPRECATION")
                val transportSsid = transportInfo.ssid
                android.util.Log.d("MainActivity", "Debug - WiFi SSID from TransportInfo: $transportSsid")
            }
            
            // Try with direct settings access
            val systemSsid = android.provider.Settings.System.getString(contentResolver, "wifi_ssid")
            android.util.Log.d("MainActivity", "Debug - SSID from System settings: $systemSsid")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error in SSID debug: ${e.message}")
        }
        
        // Check main permission requirement
        val mainPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        val hasMainPermission = ContextCompat.checkSelfPermission(this, mainPermission) == 
            PackageManager.PERMISSION_GRANTED
            
        android.util.Log.d("MainActivity", "Main required permission ($mainPermission): ${if (hasMainPermission) "GRANTED" else "DENIED"}")
        android.util.Log.d("MainActivity", "===== END PERMISSION STATUS =====")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Don't inflate the menu
        return false
    }
    
    /**
     * Updates the network indicator based on the current network type
     */
    private fun updateNetworkIndicator(destination: NavDestination?) {
        try {
            // Get the indicator
            val indicator = networkIndicator ?: return
            
            // Get settings to determine connection mode
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
            
            // Check if we're on a home network
            val isHomeNetwork = networkUtils.isHomeNetwork()
            
            // In manual mode, use the fixed setting; in auto mode, use actual connection
            val isInternal = when (connectionMode) {
                "internal" -> true
                "external" -> false
                else -> isHomeNetwork
            }
            
            // Update indicator text
            indicator.text = if (isInternal) "INT" else "EXT"
            
            // Update indicator color
            val backgroundColor = if (isInternal) "#4CAF50" else "#F44336" // Green for INT, Red for EXT
            val drawable = indicator.background.mutate()
            drawable.setTint(Color.parseColor(backgroundColor))
            indicator.background = drawable
            
            // Only show on HomeFragment or when connection mode is forced
            val isHome = destination?.id == R.id.nav_home
            indicator.visibility = if (isHome || connectionMode != "auto") {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error updating network indicator: ${e.message}")
        }
    }

    // This is replaced by the enhanced version below
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else if (::navController.isInitialized) {
            when (navController.currentDestination?.id) {
                R.id.nav_home -> {
                    // Let the HomeFragment handle the back press for WebView navigation
                    val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
                    val currentFragment = navHostFragment?.childFragmentManager?.fragments?.getOrNull(0)
                    if (currentFragment is com.asksakis.freegate.ui.home.HomeFragment) {
                        if (currentFragment.handleBackPress()) {
                            // Back press was handled by the fragment
                            return
                        }
                    }
                    // If WebView couldn't go back, exit the app
                    @Suppress("DEPRECATION")
                    super.onBackPressed()
                }
                R.id.nav_settings -> {
                    // From settings, always navigate back to home
                    android.util.Log.d("MainActivity", "Back pressed from settings, navigating to home")
                    navController.navigate(R.id.nav_home)
                }
                else -> {
                    // For any other fragments, go back to home
                    android.util.Log.d("MainActivity", "Back pressed from other fragment, navigating to home")
                    navController.navigate(R.id.nav_home)
                }
            }
        } else {
            // NavController not yet initialized, just use default behavior
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
    
    /**
     * Update indicator when activity resumes
     */
    override fun onResume() {
        super.onResume()
        
        // Check and apply "Keep screen on" setting
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        // Update the network indicator when activity resumes
        if (::navController.isInitialized) {
            updateNetworkIndicator(navController.currentDestination)
        }
    }
    
    // Override to improve navigation consistency
    override fun onSupportNavigateUp(): Boolean {
        if (!::navController.isInitialized) {
            return super.onSupportNavigateUp()
        }
        
        val currentDestId = navController.currentDestination?.id
        
        // If we're in settings, always navigate back to home on up button
        if (currentDestId == R.id.nav_settings) {
            navController.navigate(R.id.nav_home)
            return true
        }
        
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
    
    // Handle late initialization of NavController
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        
        // If NavController wasn't initialized in onCreate, try again
        if (!::navController.isInitialized) {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? androidx.navigation.fragment.NavHostFragment
            if (navHostFragment != null) {
                navController = navHostFragment.navController
                
                val drawerLayout: DrawerLayout = binding.drawerLayout
                val navView: NavigationView = binding.navView
                
                appBarConfiguration = AppBarConfiguration(
                    setOf(
                        R.id.nav_home, R.id.nav_settings
                    ), drawerLayout
                )
                setupActionBarWithNavController(navController, appBarConfiguration)
                navView.setupWithNavController(navController)
                
                // Setup navigation listener to update the network indicator
                navController.addOnDestinationChangedListener { _, destination, _ ->
                    updateNetworkIndicator(destination)
                }
                
                // Observe network state changes
                networkUtils.isHomeNetwork.observe(this) { _ ->
                    updateNetworkIndicator(navController.currentDestination)
                }
                networkUtils.currentUrl.observe(this) { _ ->
                    updateNetworkIndicator(navController.currentDestination)
                }
            }
        }
    }
}